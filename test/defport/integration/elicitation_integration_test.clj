(ns defport.integration.elicitation-integration-test
  "Integration tests for the elicitation test server.

  Tests the complete request/response cycle for all elicitation tools,
  verifying MCP 2025-11-25 compliance and elicitation functionality."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [defport.core :as core]
   [defport.mcp :as mcp]
   [defport.registry :as registry]
   [defport.testing.client :as client]
   [defport.testing.server :as server]
   [defport.testing.compliance :as compliance]
   [cheshire.core :as json]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn reset-state-fixture [f]
  (mcp/reset-protocol-state!)
  (f)
  (mcp/reset-protocol-state!))

(use-fixtures :each reset-state-fixture)

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn create-elicitation-registry
  "Create a registry with elicitation tools."
  []
  (let [reg (registry/create-function-registry)]
    ;; Configure API tool
    (core/register-port! reg
      {:id :configure-api
       :name "configure-api"
       :description "Configure API key for a service"
       :input-schema {:type "object"
                      :properties {:service {:type "string"}}
                      :required ["service"]}
       :handler (fn [context]
                  (let [service (get-in context [:params :service] "unknown-service")
                        elicitation {:type "elicitation"
                                    :title (str "Configure " service " API")
                                    :description (str "Please provide your API key for " service)
                                    :fields [{:name "api_key"
                                             :type "string"
                                             :description "Your API key"
                                             :required true
                                             :secret true}
                                            {:name "endpoint"
                                             :type "string"
                                             :description "Custom API endpoint"
                                             :required false}]}]
                    {:content [{:type "text"
                               :text (json/generate-string
                                      {:status "elicitation-requested"
                                       :message (str "Would request API key for " service)
                                       :elicitation elicitation
                                       :mock-response {:api_key "sk-test-123"
                                                      :endpoint "https://api.example.com"}})}]}))})

    ;; Confirm action tool
    (core/register-port! reg
      {:id :confirm-action
       :name "confirm-action"
       :description "Request user confirmation for an action"
       :input-schema {:type "object"
                      :properties {:action {:type "string"}
                                  :details {:type "string"}}
                      :required ["action"]}
       :handler (fn [context]
                  (let [action (get-in context [:params :action])
                        details (get-in context [:params :details] "")
                        elicitation {:type "elicitation"
                                    :title (str "Confirm: " action)
                                    :description (str "Are you sure you want to " action "?")
                                    :additional-info details
                                    :fields [{:name "confirmed"
                                             :type "boolean"
                                             :description "Confirm this action"
                                             :required true}]}]
                    {:content [{:type "text"
                               :text (json/generate-string
                                      {:status "confirmation-requested"
                                       :message (str "Would request confirmation for: " action)
                                       :elicitation elicitation
                                       :mock-response {:confirmed true}})}]}))})

    ;; Setup profile tool
    (core/register-port! reg
      {:id :setup-profile
       :name "setup-profile"
       :description "Setup user profile with multiple fields"
       :input-schema {:type "object"
                      :properties {}
                      :required []}
       :handler (fn [_context]
                  (let [elicitation {:type "elicitation"
                                    :title "Complete Your Profile"
                                    :description "Please provide your profile information"
                                    :fields [{:name "name" :type "string" :required true}
                                            {:name "email" :type "string" :required true}
                                            {:name "organization" :type "string" :required false}
                                            {:name "role" :type "string" :required false}
                                            {:name "notifications" :type "boolean" :required false}]}]
                    {:content [{:type "text"
                               :text (json/generate-string
                                      {:status "profile-setup-requested"
                                       :message "Would request profile information"
                                       :elicitation elicitation
                                       :mock-response {:name "John Doe"
                                                      :email "john@example.com"
                                                      :organization "Acme Corp"
                                                      :notifications true}})}]}))})

    ;; Request credentials tool
    (core/register-port! reg
      {:id :request-credentials
       :name "request-credentials"
       :description "Request login credentials"
       :input-schema {:type "object"
                      :properties {:system {:type "string"}}
                      :required ["system"]}
       :handler (fn [context]
                  (let [system (get-in context [:params :system])
                        elicitation {:type "elicitation"
                                    :title (str "Login to " system)
                                    :description (str "Provide credentials for " system)
                                    :fields [{:name "username" :type "string" :required true}
                                            {:name "password" :type "string" :required true :secret true}]}]
                    {:content [{:type "text"
                               :text (json/generate-string
                                      {:status "credentials-requested"
                                       :message (str "Would request credentials for " system)
                                       :elicitation elicitation
                                       :mock-response {:username "user123"
                                                      :password "********"}})}]}))})

    ;; Choose option tool
    (core/register-port! reg
      {:id :choose-option
       :name "choose-option"
       :description "Ask user to choose from options"
       :input-schema {:type "object"
                      :properties {:question {:type "string"}
                                  :options {:type "array" :items {:type "string"}}}
                      :required ["question"]}
       :handler (fn [context]
                  (let [question (get-in context [:params :question])
                        options (get-in context [:params :options] ["Option A" "Option B"])
                        elicitation {:type "elicitation"
                                    :title "Make a Choice"
                                    :description question
                                    :fields [{:name "choice" :type "string" :required true}]}]
                    {:content [{:type "text"
                               :text (json/generate-string
                                      {:status "choice-requested"
                                       :message "Would request user choice"
                                       :question question
                                       :options options
                                       :elicitation elicitation
                                       :mock-response {:choice (first options)}})}]}))})

    ;; Error scenario tools
    (core/register-port! reg
      {:id :test-declined
       :name "test-declined"
       :description "Test user declining elicitation"
       :input-schema {:type "object" :properties {} :required []}
       :handler (fn [_]
                  {:content [{:type "text"
                             :text (json/generate-string
                                    {:status "elicitation-declined"
                                     :error {:code -32001
                                            :message "User declined to provide information"}})}]})})

    (core/register-port! reg
      {:id :test-cancelled
       :name "test-cancelled"
       :description "Test user cancelling elicitation"
       :input-schema {:type "object" :properties {} :required []}
       :handler (fn [_]
                  {:content [{:type "text"
                             :text (json/generate-string
                                    {:status "elicitation-cancelled"
                                     :error {:code -32800
                                            :message "User cancelled the operation"}})}]})})

    (core/register-port! reg
      {:id :test-timeout
       :name "test-timeout"
       :description "Test elicitation timeout"
       :input-schema {:type "object" :properties {} :required []}
       :handler (fn [_]
                  {:content [{:type "text"
                             :text (json/generate-string
                                    {:status "elicitation-timeout"
                                     :error {:code -32000
                                            :message "Elicitation request timed out"}})}]})})

    reg))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest ^:integration test-elicitation-server-initialization
  (testing "Elicitation server initializes correctly"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (let [response (client/client-initialize c {:name "elicitation-test"
                                                     :version "1.0"})
              clean-response (dissoc response :_request-id)]
          ;; Verify compliance
          (is (nil? (compliance/validate-response "initialize"
                                                  clean-response
                                                  (:_request-id response))))

          ;; Verify protocol version
          (is (= "2025-11-25" (get-in response [:result :protocolVersion])))

          ;; Verify server info
          (is (= "defport-mcp-server" (get-in response [:result :serverInfo :name]))))))))

(deftest ^:integration test-list-elicitation-tools
  (testing "Lists all elicitation tools"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-request c "tools/list" {})
              tools (get-in response [:result :tools])]

          ;; Verify 8 tools available
          (is (= 8 (count tools)))

          ;; Verify tool names
          (let [tool-names (set (map :name tools))]
            (is (contains? tool-names "configure-api"))
            (is (contains? tool-names "confirm-action"))
            (is (contains? tool-names "setup-profile"))
            (is (contains? tool-names "request-credentials"))
            (is (contains? tool-names "choose-option"))
            (is (contains? tool-names "test-declined"))
            (is (contains? tool-names "test-cancelled"))
            (is (contains? tool-names "test-timeout"))))))))

(deftest ^:integration test-configure-api-elicitation
  (testing "Configure API tool requests API key"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "configure-api" {:service "OpenAI"})
              content (get-in response [:result :content])
              data (json/parse-string (get-in content [0 :text]) true)]

          ;; Verify status
          (is (= "elicitation-requested" (:status data)))

          ;; Verify elicitation structure
          (let [elicitation (:elicitation data)]
            (is (= "elicitation" (:type elicitation)))
            (is (= "Configure OpenAI API" (:title elicitation)))
            (is (string? (:description elicitation)))

            ;; Verify fields
            (let [fields (:fields elicitation)]
              (is (= 2 (count fields)))

              ;; Check api_key field
              (let [api-key-field (first (filter #(= "api_key" (:name %)) fields))]
                (is (some? api-key-field))
                (is (= "string" (:type api-key-field)))
                (is (true? (:required api-key-field)))
                (is (true? (:secret api-key-field))))

              ;; Check endpoint field
              (let [endpoint-field (first (filter #(= "endpoint" (:name %)) fields))]
                (is (some? endpoint-field))
                (is (= "string" (:type endpoint-field)))
                (is (false? (:required endpoint-field)))))))))))

(deftest ^:integration test-confirm-action-elicitation
  (testing "Confirm action tool requests boolean confirmation"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "confirm-action"
                                                {:action "delete files"
                                                 :details "This will delete 100 files"})
              content (get-in response [:result :content])
              data (json/parse-string (get-in content [0 :text]) true)]

          ;; Verify elicitation
          (let [elicitation (:elicitation data)
                fields (:fields elicitation)
                confirmed-field (first fields)]

            (is (= "elicitation" (:type elicitation)))
            (is (= "boolean" (:type confirmed-field)))
            (is (= "confirmed" (:name confirmed-field)))
            (is (true? (:required confirmed-field)))))))))

(deftest ^:integration test-setup-profile-multi-field
  (testing "Setup profile tool requests multiple fields"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "setup-profile" {})
              content (get-in response [:result :content])
              data (json/parse-string (get-in content [0 :text]) true)
              fields (get-in data [:elicitation :fields])]

          ;; Verify 5 fields
          (is (= 5 (count fields)))

          ;; Verify required fields
          (let [required-fields (filter :required fields)
                optional-fields (remove :required fields)]
            (is (= 2 (count required-fields)))
            (is (= 3 (count optional-fields)))

            ;; Check field names
            (let [field-names (set (map :name fields))]
              (is (contains? field-names "name"))
              (is (contains? field-names "email"))
              (is (contains? field-names "organization"))
              (is (contains? field-names "role"))
              (is (contains? field-names "notifications")))))))))

(deftest ^:integration test-request-credentials-secret-fields
  (testing "Request credentials tool marks password as secret"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "request-credentials" {:system "AWS"})
              content (get-in response [:result :content])
              data (json/parse-string (get-in content [0 :text]) true)
              fields (get-in data [:elicitation :fields])]

          ;; Verify username field (not secret)
          (let [username-field (first (filter #(= "username" (:name %)) fields))]
            (is (some? username-field))
            (is (true? (:required username-field)))
            (is (not (:secret username-field))))

          ;; Verify password field (secret)
          (let [password-field (first (filter #(= "password" (:name %)) fields))]
            (is (some? password-field))
            (is (true? (:required password-field)))
            (is (true? (:secret password-field)))))))))

(deftest ^:integration test-choose-option-with-options
  (testing "Choose option tool includes options in request"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "choose-option"
                                                {:question "Deploy to which environment?"
                                                 :options ["dev" "staging" "prod"]})
              content (get-in response [:result :content])
              data (json/parse-string (get-in content [0 :text]) true)]

          ;; Verify question and options preserved
          (is (= "Deploy to which environment?" (:question data)))
          (is (= ["dev" "staging" "prod"] (:options data)))

          ;; Verify elicitation structure
          (is (= "elicitation" (get-in data [:elicitation :type])))
          (is (= "Make a Choice" (get-in data [:elicitation :title]))))))))

(deftest ^:integration test-elicitation-declined-error
  (testing "Declined elicitation returns error code"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "test-declined" {})
              content (get-in response [:result :content])
              data (json/parse-string (get-in content [0 :text]) true)
              error (:error data)]

          (is (= "elicitation-declined" (:status data)))
          (is (= -32001 (:code error)))
          (is (string? (:message error))))))))

(deftest ^:integration test-elicitation-cancelled-error
  (testing "Cancelled elicitation returns cancellation error code"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "test-cancelled" {})
              content (get-in response [:result :content])
              data (json/parse-string (get-in content [0 :text]) true)
              error (:error data)]

          (is (= "elicitation-cancelled" (:status data)))
          (is (= -32800 (:code error))))))))

(deftest ^:integration test-elicitation-timeout-error
  (testing "Timeout elicitation returns timeout error"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "test-timeout" {})
              content (get-in response [:result :content])
              data (json/parse-string (get-in content [0 :text]) true)
              error (:error data)]

          (is (= "elicitation-timeout" (:status data)))
          (is (= -32000 (:code error)))
          (is (re-find #"(?i)timed out" (:message error))))))))

(deftest ^:integration test-concurrent-elicitations
  (testing "Multiple elicitation requests can be handled concurrently"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Make multiple concurrent requests
        (let [futures [(future (client/client-call-tool c "configure-api" {:service "OpenAI"}))
                      (future (client/client-call-tool c "confirm-action" {:action "deploy"}))
                      (future (client/client-call-tool c "setup-profile" {}))]
              results (mapv deref futures)]

          ;; Verify all succeeded
          (is (= 3 (count results)))
          (doseq [result results]
            (is (nil? (:error result)))
            (is (sequential? (get-in result [:result :content])))))))))

(deftest ^:integration test-elicitation-compliance
  (testing "All elicitation responses are MCP compliant"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Test each tool for compliance
        (let [test-calls [["configure-api" {:service "Test"}]
                         ["confirm-action" {:action "test"}]
                         ["setup-profile" {}]
                         ["request-credentials" {:system "Test"}]
                         ["choose-option" {:question "Test?"}]]]

          (doseq [[tool-name args] test-calls]
            (let [response (client/client-call-tool c tool-name args)
                  clean-response (dissoc response :_request-id)]

              ;; Verify MCP compliance
              (is (nil? (compliance/validate-response "tools/call"
                                                      clean-response
                                                      (:_request-id response)))
                  (str "Tool " tool-name " should be MCP compliant")))))))))

(deftest ^:integration test-elicitation-full-workflow
  (testing "Complete elicitation workflow"
    (server/with-mcp-test-server [srv {:registry (create-elicitation-registry)
                                        :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        ;; 1. Initialize
        (let [init-response (client/client-initialize c {:name "test" :version "1.0"})]
          (is (nil? (:error init-response))))

        ;; 2. List tools
        (let [list-response (client/client-request c "tools/list" {})]
          (is (= 8 (count (get-in list-response [:result :tools])))))

        ;; 3. Call configure-api (simple elicitation)
        (let [api-response (client/client-call-tool c "configure-api" {:service "OpenAI"})
              data (json/parse-string (get-in api-response [:result :content 0 :text]) true)]
          (is (= "elicitation-requested" (:status data))))

        ;; 4. Call setup-profile (multi-field elicitation)
        (let [profile-response (client/client-call-tool c "setup-profile" {})
              data (json/parse-string (get-in profile-response [:result :content 0 :text]) true)]
          (is (= 5 (count (get-in data [:elicitation :fields])))))

        ;; 5. Test error scenario
        (let [declined-response (client/client-call-tool c "test-declined" {})
              data (json/parse-string (get-in declined-response [:result :content 0 :text]) true)]
          (is (= "elicitation-declined" (:status data))))))))
