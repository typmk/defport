(ns defport.testing.server
  "Test server helpers for integration testing.

  Provides utilities for starting/stopping test servers, managing lifecycles,
  and creating test registries with ports."
  (:require [defport.core :as core]
            [defport.registry :as registry]
            [defport.mcp :as mcp]
            [defport.util.platform :as platform :include-macros true]
            [defport.transports.http :as http-transport]
            [defport.transports.stdio :as stdio-transport]
            #?(:clj [clj-http.client :as http-client])))

;; ============================================================================
;; Port Management
;; ============================================================================

(def ^:private next-test-port* (atom 9000))

(defn random-port
  "Generate a random port number for testing.
  Returns a port in the range 9000-19999 to avoid common conflicts."
  []
  (swap! next-test-port* (fn [p]
                          (if (> p 19999)
                            9000
                            (inc p)))))

(defn reset-port-counter!
  "Reset the test port counter (for testing)."
  []
  (reset! next-test-port* 9000))

;; ============================================================================
;; Test Registry Creation
;; ============================================================================

(defn create-test-registry
  "Create a test registry with sample ports.

  Accepts a map of port definitions where keys are port IDs and values are maps with:
  - :description - Port description
  - :handler - Handler function
  - :input-schema - Optional input schema
  - :output-schema - Optional output schema
  - :metadata - Optional metadata

  Example:
    (create-test-registry
      {:echo {:description \"Echo tool\"
              :handler (fn [ctx] {:result (:params ctx)})
              :input-schema {:type \"object\"
                            :properties {:message {:type \"string\"}}}}})"
  ([]
   (create-test-registry {}))
  ([port-defs]
   (let [reg (registry/create-function-registry)]
     (doseq [[id port-def] port-defs]
       (core/register-port! reg
         (merge {:id id
                 :name (name id)}
                port-def)))
     reg)))

(defn create-test-registry-with-tools
  "Create a test registry with standard test tools.

  Creates the following tools:
  - :echo - Echo back input
  - :add - Add two numbers
  - :error-tool - Always throws an error
  - :slow-tool - Simulates slow operation (1s delay)

  Options:
  - :include-dangerous - Include dangerous tools (default false)
  - :custom-tools - Map of additional custom tools to include"
  ([]
   (create-test-registry-with-tools nil))
  ([opts]
   (let [tools {:echo
                {:description "Echo back the input"
                 :handler (fn [ctx] {:result (:params ctx)})
                 :input-schema {:type "object"
                               :properties {:message {:type "string"}}
                               :required ["message"]}}

                :add
                {:description "Add two numbers"
                 :handler (fn [ctx]
                           (let [a (get-in ctx [:params :a])
                                 b (get-in ctx [:params :b])]
                             {:result (+ a b)}))
                 :input-schema {:type "object"
                               :properties {:a {:type "number"}
                                          :b {:type "number"}}
                               :required ["a" "b"]}}

                :error-tool
                {:description "Always throws an error"
                 :handler (fn [_]
                           {:error {:code -32603
                                   :message "Intentional error for testing"}})}

                :slow-tool
                {:description "Simulates a slow operation"
                 :handler (fn [ctx]
                           #?(:clj (Thread/sleep 1000)
                              :cljs (js/setTimeout (fn []) 1000))
                           {:result "completed"})}}

         dangerous-tools (when (:include-dangerous opts)
                          {:dangerous-delete
                           {:description "Delete a file (dangerous)"
                            :handler (fn [ctx]
                                      {:result "file deleted"})
                            :metadata {:dangerous true}}})

         custom-tools (:custom-tools opts)

         all-tools (merge tools dangerous-tools custom-tools)]

     (create-test-registry all-tools))))

;; ============================================================================
;; Server Lifecycle
;; ============================================================================

(defrecord TestServer [registry adapter transport server-state*])

(defn start-test-server
  "Start a test server with the given registry and configuration.

  Args:
  - registry: PortRegistry instance
  - adapter: ProtocolAdapter instance (e.g., MCP adapter)
  - opts: Configuration map with:
    - :transport - :http or :stdio (default :http)
    - :port - Port number (for HTTP, default random)
    - :host - Host (for HTTP, default \"127.0.0.1\")
    - :adapter-opts - Options to pass to adapter

  Returns TestServer record with :registry, :adapter, :transport, and :server-state*

  Example:
    (def server (start-test-server registry adapter {:transport :http :port 9999}))
    ;; ... test ...
    (stop-test-server server)"
  [registry adapter opts]
  (let [transport-type (or (:transport opts) :http)
        transport (case transport-type
                   :http (http-transport/create-http-transport
                          {:port (or (:port opts) (random-port))
                           :host (or (:host opts) "127.0.0.1")})
                   :stdio (stdio-transport/create-stdio-transport)
                   (throw (ex-info "Unknown transport type"
                                  {:transport transport-type})))

        adapter-opts (or (:adapter-opts opts) {})
        handler (fn [request]
                 (platform/try-any
                   (let [method (:method request)
                         params (:params request {})
                         request-id (:id request)
                         context (merge {:port-registry registry
                                        :transport transport
                                        :protocol (core/protocol-id adapter)
                                        :request request}
                                       adapter-opts)
                         result (core/protocol-dispatch adapter method params context)]
                     (if (:error result)
                       {:jsonrpc "2.0"
                        :id request-id
                        :error (:error result)}
                       {:jsonrpc "2.0"
                        :id request-id
                        :result result}))
                   (catch-any e
                     {:jsonrpc "2.0"
                      :id (:id request)
                      :error {:code -32603
                             :message (str "Internal error: " (platform/error-message e))}})))

        server-state (core/transport-start transport handler)]

    (->TestServer registry adapter transport (atom server-state))))

(defn stop-test-server
  "Stop a test server and clean up resources.

  Example:
    (stop-test-server server)"
  [server]
  (core/transport-stop (:transport server)))

(defn get-server-port
  "Get the port number of an HTTP test server.

  Example:
    (get-server-port server) ;=> 9999"
  [server]
  (when (instance? defport.transports.http.HttpTransport (:transport server))
    (get-in server [:transport :port])))

(defn get-server-url
  "Get the base URL of an HTTP test server.

  Example:
    (get-server-url server) ;=> \"http://127.0.0.1:9999\""
  [server]
  (when-let [port (get-server-port server)]
    (let [host (get-in server [:transport :host] "127.0.0.1")]
      (str "http://" host ":" port))))

(defn wait-for-server-ready
  "Wait for a server to be ready to accept connections.

  Args:
  - server: TestServer instance
  - timeout-ms: Maximum time to wait in milliseconds (default 5000)

  Returns true if server is ready, false if timeout.

  Example:
    (wait-for-server-ready server 10000)"
  ([server]
   (wait-for-server-ready server 5000))
  ([server timeout-ms]
   #?(:clj
      (let [start-time (System/currentTimeMillis)
            end-time (+ start-time timeout-ms)]
        (loop []
          (if (> (System/currentTimeMillis) end-time)
            false  ; Timeout
            (if-let [url (get-server-url server)]
              (let [continue? (try
                               ;; Try to connect to health endpoint
                               (let [response #?(:clj (http-client/get (str url "/health")
                                                                               {:socket-timeout 100
                                                                                :conn-timeout 100
                                                                                :throw-exceptions false})
                                                 :cljs nil)]
                                 (if (= 200 (:status response))
                                   :ready  ; Server is ready
                                   :retry))
                               (catch Exception _
                                 :retry))]
                (case continue?
                  :ready true
                  :retry (do
                          (Thread/sleep 50)
                          (recur))))
              ;; Stdio server - assume ready immediately
              true))))

      :cljs
      ;; ClojureScript - not yet implemented
      (throw (ex-info "wait-for-server-ready not implemented for ClojureScript" {})))))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defmacro with-test-server
  "Test fixture for starting and stopping a test server.

  Args:
  - bindings: [server-binding registry adapter opts]
  - body: Test forms to execute

  Example:
    (with-test-server [server registry adapter {:transport :http :port 9999}]
      (let [url (get-server-url server)]
        ;; ... test with server ...
        ))"
  [[binding registry adapter opts] & body]
  `(let [~binding (start-test-server ~registry ~adapter ~opts)]
     (try
       (wait-for-server-ready ~binding)
       ~@body
       (finally
         (stop-test-server ~binding)))))

(defmacro with-mcp-test-server
  "Test fixture for starting an MCP test server with standard tools.

  Args:
  - bindings: [server-binding opts]
  - body: Test forms to execute

  Options:
  - :registry - Custom PortRegistry (optional, creates default if not provided)
  - :transport - :http or :stdio (default :http)
  - :port - Port number (for HTTP)
  - :include-dangerous - Include dangerous tools (default false)
  - :custom-tools - Map of custom tools to add
  - :adapter-opts - Options for MCP adapter

  Example:
    (with-mcp-test-server [server {:port 9999}]
      (let [url (get-server-url server)]
        ;; ... test with MCP server ...
        ))"
  [[binding opts] & body]
  `(let [registry# (or (:registry ~opts)
                       (create-test-registry-with-tools
                        {:include-dangerous (:include-dangerous ~opts)
                         :custom-tools (:custom-tools ~opts)}))
         adapter# (mcp/create-mcp-adapter)
         server-opts# (dissoc ~opts :include-dangerous :custom-tools :registry)
         ~binding (start-test-server registry# adapter# server-opts#)]
     (try
       (wait-for-server-ready ~binding)
       ~@body
       (finally
         (stop-test-server ~binding)
         (mcp/reset-protocol-state!)))))
