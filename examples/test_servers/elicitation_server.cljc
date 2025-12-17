(ns elicitation-server
  "MCP test server demonstrating elicitation (user input requests).

  This server showcases the MCP elicitation feature where tools can request
  information from the user through interactive forms during execution.

  Features demonstrated:
  - Simple text input elicitation (API key configuration)
  - Boolean confirmation elicitation (dangerous action confirmation)
  - Multi-field forms (user profile setup)
  - Optional vs required fields
  - Elicitation errors (user declined, cancelled)
  - Concurrent elicitation requests

  Usage:
    clojure -M:examples -m elicitation-server --http 8080
    clojure -M:examples -m elicitation-server --stdio"
  (:require
   [defport.core :as core]
   [defport.protocols.mcp :as mcp]
   [defport.registry :as registry]
   [defport.transports.http :as http]
   [defport.transports.stdio :as stdio]
   [defport.util.platform :as platform]
   [clojure.string :as str]))

;; ============================================================================
;; Tool Handlers with Elicitation
;; ============================================================================

(defn configure-api-handler
  "Tool that requests API key from user via elicitation."
  [context]
  (try
    (let [service (get-in context [:params :service] "unknown-service")
          ;; Request API key from user
          elicitation-request {:type "elicitation"
                               :title (str "Configure " service " API")
                               :description (str "Please provide your API key for " service)
                               :fields [{:name "api_key"
                                        :type "string"
                                        :description "Your API key"
                                        :required true
                                        :secret true}
                                       {:name "endpoint"
                                        :type "string"
                                        :description "Custom API endpoint (optional)"
                                        :required false}]}]

      ;; In a real implementation, this would be sent to the client
      ;; and the response would be received asynchronously.
      ;; For testing, we return a structure showing what would be requested.
      {:content [{:type "text"
                  :text (platform/json-encode
                         {:status "elicitation-requested"
                          :message (str "Would request API key for " service " from user")
                          :elicitation elicitation-request
                          :mock-response {:api_key "sk-test-123456"
                                         :endpoint "https://api.example.com"}})}]})
    (catch #?(:clj Exception :cljs js/Error) e
      {:error {:code -32603
               :message (str "Configure API failed: " #?(:clj (.getMessage e) :cljs (.-message e)))}})))

(defn confirm-action-handler
  "Tool that requests yes/no confirmation from user."
  [context]
  (try
    (let [action (get-in context [:params :action] "unknown action")
          details (get-in context [:params :details] "")
          ;; Request confirmation from user
          elicitation-request {:type "elicitation"
                               :title (str "Confirm: " action)
                               :description (str "Are you sure you want to " action "?")
                               :additional-info details
                               :fields [{:name "confirmed"
                                        :type "boolean"
                                        :description "Confirm this action"
                                        :required true}]}]

      {:content [{:type "text"
                  :text (platform/json-encode
                         {:status "confirmation-requested"
                          :message (str "Would request confirmation for: " action)
                          :elicitation elicitation-request
                          :mock-response {:confirmed true}})}]})
    (catch #?(:clj Exception :cljs js/Error) e
      {:error {:code -32603
               :message (str "Confirm action failed: " #?(:clj (.getMessage e) :cljs (.-message e)))}})))

(defn setup-profile-handler
  "Tool that requests multiple profile fields from user."
  [context]
  (try
    (let [;; Request profile information from user
          elicitation-request {:type "elicitation"
                               :title "Complete Your Profile"
                               :description "Please provide your profile information"
                               :fields [{:name "name"
                                        :type "string"
                                        :description "Full name"
                                        :required true}
                                       {:name "email"
                                        :type "string"
                                        :description "Email address"
                                        :required true}
                                       {:name "organization"
                                        :type "string"
                                        :description "Organization name"
                                        :required false}
                                       {:name "role"
                                        :type "string"
                                        :description "Your role"
                                        :required false}
                                       {:name "notifications"
                                        :type "boolean"
                                        :description "Enable notifications"
                                        :required false}]}]

      {:content [{:type "text"
                  :text (platform/json-encode
                         {:status "profile-setup-requested"
                          :message "Would request profile information from user"
                          :elicitation elicitation-request
                          :mock-response {:name "John Doe"
                                         :email "john@example.com"
                                         :organization "Acme Corp"
                                         :role "Developer"
                                         :notifications true}})}]})
    (catch #?(:clj Exception :cljs js/Error) e
      {:error {:code -32603
               :message (str "Setup profile failed: " #?(:clj (.getMessage e) :cljs (.-message e)))}})))

(defn request-credentials-handler
  "Tool that requests username and password."
  [context]
  (try
    (let [system (get-in context [:params :system] "system")
          elicitation-request {:type "elicitation"
                               :title (str "Login to " system)
                               :description (str "Please provide your credentials for " system)
                               :fields [{:name "username"
                                        :type "string"
                                        :description "Username"
                                        :required true}
                                       {:name "password"
                                        :type "string"
                                        :description "Password"
                                        :required true
                                        :secret true}]}]

      {:content [{:type "text"
                  :text (platform/json-encode
                         {:status "credentials-requested"
                          :message (str "Would request credentials for " system)
                          :elicitation elicitation-request
                          :mock-response {:username "user123"
                                         :password "********"}})}]})
    (catch #?(:clj Exception :cljs js/Error) e
      {:error {:code -32603
               :message (str "Request credentials failed: " #?(:clj (.getMessage e) :cljs (.-message e)))}})))

(defn choose-option-handler
  "Tool that requests user to choose from options."
  [context]
  (try
    (let [question (get-in context [:params :question] "Choose an option")
          options (get-in context [:params :options] ["Option A" "Option B" "Option C"])
          elicitation-request {:type "elicitation"
                               :title "Make a Choice"
                               :description question
                               :fields [{:name "choice"
                                        :type "string"
                                        :description (str "Available options: " (str/join ", " options))
                                        :required true}]}]

      {:content [{:type "text"
                  :text (platform/json-encode
                         {:status "choice-requested"
                          :message (str "Would request user to choose from options")
                          :question question
                          :options options
                          :elicitation elicitation-request
                          :mock-response {:choice (first options)}})}]})
    (catch #?(:clj Exception :cljs js/Error) e
      {:error {:code -32603
               :message (str "Choose option failed: " #?(:clj (.getMessage e) :cljs (.-message e)))}})))

(defn test-declined-handler
  "Tool that simulates user declining elicitation."
  [_context]
  {:content [{:type "text"
              :text (platform/json-encode
                     {:status "elicitation-declined"
                      :message "Simulates user declining the elicitation request"
                      :error {:code -32001
                              :message "User declined to provide information"}})}]})

(defn test-cancelled-handler
  "Tool that simulates user cancelling elicitation."
  [_context]
  {:content [{:type "text"
              :text (platform/json-encode
                     {:status "elicitation-cancelled"
                      :message "Simulates user cancelling the elicitation request"
                      :error {:code -32800
                              :message "User cancelled the operation"}})}]})

(defn test-timeout-handler
  "Tool that simulates elicitation timeout."
  [_context]
  {:content [{:type "text"
              :text (platform/json-encode
                     {:status "elicitation-timeout"
                      :message "Simulates elicitation request timing out"
                      :error {:code -32000
                              :message "Elicitation request timed out after 30 seconds"}})}]})

;; ============================================================================
;; Port Definitions
;; ============================================================================

(def tools
  {:configure-api
   {:id :configure-api
    :name "configure-api"
    :description "Configure API key for a service (requires user input)"
    :input-schema {:type "object"
                   :properties {:service {:type "string"
                                         :description "Service name (e.g., 'OpenAI', 'Anthropic')"}}
                   :required ["service"]}
    :handler configure-api-handler}

   :confirm-action
   {:id :confirm-action
    :name "confirm-action"
    :description "Request user confirmation for an action"
    :input-schema {:type "object"
                   :properties {:action {:type "string"
                                        :description "Action requiring confirmation"}
                               :details {:type "string"
                                        :description "Additional details about the action"}}
                   :required ["action"]}
    :handler confirm-action-handler}

   :setup-profile
   {:id :setup-profile
    :name "setup-profile"
    :description "Setup user profile with multiple fields"
    :input-schema {:type "object"
                   :properties {}
                   :required []}
    :handler setup-profile-handler}

   :request-credentials
   {:id :request-credentials
    :name "request-credentials"
    :description "Request login credentials for a system"
    :input-schema {:type "object"
                   :properties {:system {:type "string"
                                        :description "System name"}}
                   :required ["system"]}
    :handler request-credentials-handler}

   :choose-option
   {:id :choose-option
    :name "choose-option"
    :description "Ask user to choose from multiple options"
    :input-schema {:type "object"
                   :properties {:question {:type "string"
                                          :description "The question to ask"}
                               :options {:type "array"
                                        :items {:type "string"}
                                        :description "Available options"}}
                   :required ["question"]}
    :handler choose-option-handler}

   :test-declined
   {:id :test-declined
    :name "test-declined"
    :description "Test scenario: User declines elicitation"
    :input-schema {:type "object"
                   :properties {}
                   :required []}
    :handler test-declined-handler}

   :test-cancelled
   {:id :test-cancelled
    :name "test-cancelled"
    :description "Test scenario: User cancels elicitation"
    :input-schema {:type "object"
                   :properties {}
                   :required []}
    :handler test-cancelled-handler}

   :test-timeout
   {:id :test-timeout
    :name "test-timeout"
    :description "Test scenario: Elicitation times out"
    :input-schema {:type "object"
                   :properties {}
                   :required []}
    :handler test-timeout-handler}})

;; ============================================================================
;; Server Setup
;; ============================================================================

(defn create-registry
  "Create a port registry with all elicitation tools."
  []
  (let [reg (registry/create-function-registry)]
    (doseq [[_id tool] tools]
      (core/register-port! reg tool))
    reg))

(defn start-server
  "Start the elicitation test server.

  Options:
    :transport - :http or :stdio (default :http)
    :port - Port number for HTTP transport (default 8080)"
  [& {:keys [transport port]
      :or {transport :http
           port 8080}}]
  (let [registry (create-registry)
        adapter (mcp/create-mcp-adapter)
        server-info {:name "elicitation-test-server"
                     :version "1.0.0"}

        handler (fn [request]
                  (mcp/handle-request adapter registry request {:server-info server-info}))

        transport-impl (case transport
                        :http (http/create-http-transport {:port port})
                        :stdio (stdio/create-stdio-transport)
                        (throw (ex-info "Unknown transport" {:transport transport})))]

    (println (str "Starting Elicitation Test Server on " (name transport)
                  (when (= transport :http) (str " port " port)) "..."))
    (println (str "Tools available: " (count tools)))
    (println "\nElicitation features demonstrated:")
    (println "  - Simple text input (API key)")
    (println "  - Boolean confirmation (yes/no)")
    (println "  - Multi-field forms (profile setup)")
    (println "  - Required vs optional fields")
    (println "  - Secret fields (passwords)")
    (println "  - Error scenarios (declined, cancelled, timeout)")

    (core/transport-start transport-impl handler)

    (when (= transport :http)
      (println (str "\nServer ready at http://localhost:" port)))
    (println "\nPress Ctrl+C to stop.")

    ;; Keep server running
    #?(:clj @(promise) :cljs nil)))

(defn -main
  "Main entry point for the elicitation test server."
  [& args]
  (let [transport (if (some #{"--stdio"} args) :stdio :http)
        port (if-let [port-arg (some #(when #?(:clj (.startsWith ^String % "--port=")
                                               :cljs (str/starts-with? % "--port="))
                                        (subs % 7)) args)]
               (platform/parse-int port-arg)
               8080)]
    (start-server :transport transport :port port)))

#?(:cljs (set! *main-cli-fn* -main))