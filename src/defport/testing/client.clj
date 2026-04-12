(ns defport.testing.client
  "Test client for MCP servers.

  Provides a minimal MCP client for testing servers in integration tests.
  Supports both HTTP and stdio transports.

  JVM-only by design: uses clj-http for HTTP and java.io.Process for stdio.
  A Node-based test client would live in a separate namespace."
  (:require [defport.util.platform :as platform]
            [clj-http.client :as http-client]
            [clojure.java.io :as io]))

;; ============================================================================
;; Request ID Management
;; ============================================================================

(def ^:private next-request-id* (atom 0))

(defn- generate-request-id
  "Generate a unique request ID for testing."
  []
  (swap! next-request-id* inc))

(defn reset-request-ids!
  "Reset request ID counter (for testing)."
  []
  (reset! next-request-id* 0))

;; ============================================================================
;; HTTP Client
;; ============================================================================

(defrecord HttpTestClient [base-url timeout-ms]
  java.io.Closeable
  (close [_]
    ;; HTTP clients are stateless, nothing to close
    nil))

(defn- http-request
  "Send JSON-RPC request via HTTP."
  [client method params]
  (let [request-id (generate-request-id)
        request-body {:jsonrpc "2.0"
                      :id request-id
                      :method method
                      :params (or params {})}
        response (http-client/post
                  (str (:base-url client) "/mcp")
                  {:body (platform/json-encode request-body)
                   :content-type :json
                   :socket-timeout (:timeout-ms client)
                   :conn-timeout (:timeout-ms client)
                   :throw-exceptions false})
        body (platform/json-decode (:body response))]
    (assoc body :_request-id request-id)))

;; ============================================================================
;; Stdio Client
;; ============================================================================

(defrecord StdioTestClient [process* in* out* err* running?*]
  java.io.Closeable
  (close [_]
    (when @running?*
      (reset! running?* false)
      (when-let [proc @process*]
        (.destroy proc))
      (when-let [in @in*]
        (.close in))
      (when-let [out @out*]
        (.close out)))))

(defn- start-stdio-process
  "Start a server process for stdio communication."
  [command args]
  (let [pb (ProcessBuilder. (into [command] args))
        _ (.redirectErrorStream pb false)
        proc (.start pb)
        in (io/reader (.getInputStream proc))
        out (io/writer (.getOutputStream proc))
        err (io/reader (.getErrorStream proc))]
    {:process proc
     :in in
     :out out
     :err err}))

(defn- stdio-request
  "Send JSON-RPC request via stdio."
  [client method params]
  (let [request-id (generate-request-id)
        request-body {:jsonrpc "2.0"
                      :id request-id
                      :method method
                      :params (or params {})}
        out @(:out* client)
        in @(:in* client)]
    ;; Send request
    (.write out (platform/json-encode request-body))
    (.write out "\n")
    (.flush out)

    ;; Read response
    (let [response-line (.readLine in)
          response (platform/json-decode response-line)]
      (assoc response :_request-id request-id))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn create-client
  "Create a test client for MCP servers.

  Transport options:
  - :http {:url \"http://localhost:8080\" :timeout-ms 5000}
  - :stdio {:command \"clojure\" :args [\"-M\" \"-m\" \"my-server\"]}

  Returns a client that can be used with client-request functions.

  Example:
    (def client (create-client :http {:url \"http://localhost:8080\"}))
    (def client (create-client :stdio {:command \"clojure\"
                                        :args [\"-M\" \"-m\" \"simple-mcp-server\" \"--stdio\"]}))

    ;; Use client
    (client-request client \"initialize\" {...})

    ;; Cleanup
    (.close client)"
  [transport-type opts]
  (case transport-type
    :http
    (let [url (or (:url opts) "http://localhost:8080")
          timeout-ms (or (:timeout-ms opts) 5000)]
      (->HttpTestClient url timeout-ms))

    :stdio
    (let [{:keys [command args]} opts
          _ (when-not command
              (throw (ex-info "Stdio client requires :command" {:opts opts})))
          {:keys [process in out _err]} (start-stdio-process command args)
          client (->StdioTestClient (atom process)
                                    (atom in)
                                    (atom out)
                                    (atom nil)
                                    (atom true))]
      ;; Wait a bit for server to start
      (Thread/sleep 100)
      client)

    (throw (ex-info "Unknown transport type" {:transport transport-type}))))

(defn disconnect-client
  "Disconnect and cleanup a test client."
  [client]
  (.close client))

(defn client-request
  "Send a JSON-RPC request to the server and return the response.

  Returns response map with:
  - :jsonrpc - Protocol version
  - :id - Request ID
  - :result - Result (if success)
  - :error - Error (if failure)
  - :_request-id - Original request ID (for validation)

  Example:
    (client-request client \"initialize\"
                   {:protocolVersion \"2025-11-25\"
                    :capabilities {}
                    :clientInfo {:name \"test-client\" :version \"1.0.0\"}})"
  [client method params]
  (cond
    (instance? HttpTestClient client)
    (http-request client method params)

    (instance? StdioTestClient client)
    (stdio-request client method params)

    :else
    (throw (ex-info "Unknown client type" {:client client}))))

;; ============================================================================
;; Convenience Methods
;; ============================================================================

(defn client-initialize
  "Send initialize request.

  Example:
    (client-initialize client {:name \"test-client\" :version \"1.0.0\"})"
  [client client-info]
  (client-request client "initialize"
                 {:protocolVersion "2025-11-25"
                  :capabilities {}
                  :clientInfo client-info}))

(defn client-list-tools
  "Send tools/list request.

  Example:
    (client-list-tools client)
    (client-list-tools client {:cursor \"offset-10\"})"
  ([client]
   (client-list-tools client nil))
  ([client params]
   (client-request client "tools/list" params)))

(defn client-call-tool
  "Send tools/call request.

  Example:
    (client-call-tool client \"search-code\" {:query \"defn\"})"
  [client tool-name arguments]
  (client-request client "tools/call"
                 {:name tool-name
                  :arguments arguments}))

(defn client-list-prompts
  "Send prompts/list request.

  Example:
    (client-list-prompts client)
    (client-list-prompts client {:cursor \"offset-10\"})"
  ([client]
   (client-list-prompts client nil))
  ([client params]
   (client-request client "prompts/list" params)))

(defn client-get-prompt
  "Send prompts/get request.

  Example:
    (client-get-prompt client \"explain-function\" {:function \"foo\"})"
  [client prompt-name arguments]
  (client-request client "prompts/get"
                 {:name prompt-name
                  :arguments arguments}))

(defn client-list-resources
  "Send resources/list request.

  Example:
    (client-list-resources client)
    (client-list-resources client {:cursor \"offset-10\"})"
  ([client]
   (client-list-resources client nil))
  ([client params]
   (client-request client "resources/list" params)))

(defn client-read-resource
  "Send resources/read request.

  Example:
    (client-read-resource client \"defport://schema\")"
  [client resource-uri]
  (client-request client "resources/read"
                 {:uri resource-uri}))

(defn client-subscribe-resource
  "Send resources/subscribe request.

  Example:
    (client-subscribe-resource client \"defport://logs\")"
  [client resource-uri]
  (client-request client "resources/subscribe"
                 {:uri resource-uri}))

(defn client-unsubscribe-resource
  "Send resources/unsubscribe request.

  Example:
    (client-unsubscribe-resource client \"defport://logs\")"
  [client resource-uri]
  (client-request client "resources/unsubscribe"
                 {:uri resource-uri}))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defmacro with-test-client
  "Test fixture for creating and cleaning up test client.

  Example:
    (with-test-client [client :http {:url \"http://localhost:9999\"}]
      (let [response (client-initialize client {:name \"test\" :version \"1.0\"})]
        (is (= \"2025-11-25\" (get-in response [:result :protocolVersion])))))"
  [[binding transport-type opts] & body]
  `(let [~binding (create-client ~transport-type ~opts)]
     (try
       ~@body
       (finally
         (disconnect-client ~binding)))))
