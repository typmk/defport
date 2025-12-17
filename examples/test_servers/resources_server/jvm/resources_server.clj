(ns test-servers.resources-server
  "MCP test server focusing on resources functionality.

  This server provides comprehensive resource testing including:
  - Static and dynamic resources
  - Resource subscriptions
  - Update notifications
  - Multiple subscribers
  - MIME type handling
  - URI-based resource identification

  Usage:
    HTTP:  clojure -M -m test-servers.resources-server --http 8080
    Stdio: clojure -M -m test-servers.resources-server --stdio"
  (:require [defport.core :as core]
            [defport.protocols.mcp :as mcp]
            [defport.registry :as registry]
            [defport.transports.http :as http]
            [defport.transports.stdio :as stdio]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ============================================================================
;; State Management for Dynamic Resources
;; ============================================================================

(def server-stats
  "Server statistics that update over time."
  (atom {:requests 0
         :uptime-seconds 0
         :active-connections 0
         :last-updated (System/currentTimeMillis)}))

(def application-logs
  "Application logs that accumulate over time."
  (atom []))

(def configuration
  "Application configuration that can be updated."
  (atom {:debug-mode false
         :log-level "info"
         :max-connections 100
         :timeout-ms 5000}))

(defn update-stats!
  "Simulate stats update."
  []
  (swap! server-stats
    (fn [stats]
      (-> stats
          (update :requests inc)
          (update :uptime-seconds inc)
          (assoc :last-updated (System/currentTimeMillis))))))

(defn add-log!
  "Add a log entry."
  [level message]
  (swap! application-logs conj
    {:timestamp (System/currentTimeMillis)
     :level level
     :message message})
  ;; Keep only last 100 logs
  (when (> (count @application-logs) 100)
    (swap! application-logs #(vec (take-last 100 %)))))

;; ============================================================================
;; Resource Handlers
;; ============================================================================

(defn schema-handler
  "Return the server's schema definition."
  [context]
  {:contents
   [{:uri "defport://schema"
     :mimeType "application/edn"
     :text (pr-str
             {:tools {:search-code {:input {:type "object"
                                            :properties {:query {:type "string"}}}}}
              :prompts {:explain-function {:arguments [{:name "function_name" :required true}]}}
              :resources {:schema {:uri "defport://schema"}
                         :config {:uri "defport://config"}}})}]})

(defn config-handler
  "Return current configuration."
  [context]
  {:contents
   [{:uri "defport://config"
     :mimeType "application/json"
     :text (json/generate-string @configuration)}]})

(defn stats-handler
  "Return current server statistics (subscribable)."
  [context]
  {:contents
   [{:uri "defport://stats"
     :mimeType "application/json"
     :text (json/generate-string @server-stats)}]})

(defn logs-handler
  "Return application logs (subscribable)."
  [context]
  {:contents
   [{:uri "defport://logs"
     :mimeType "application/json"
     :text (json/generate-string @application-logs)}]})

(defn health-handler
  "Return server health status."
  [context]
  (let [uptime (:uptime-seconds @server-stats)
        status (if (> uptime 10) "healthy" "starting")]
    {:contents
     [{:uri "defport://health"
       :mimeType "application/json"
       :text (json/generate-string
               {:status status
                :uptime-seconds uptime
                :timestamp (System/currentTimeMillis)})}]}))

(defn version-handler
  "Return server version information."
  [context]
  {:contents
   [{:uri "defport://version"
     :mimeType "application/json"
     :text (json/generate-string
             {:name "defport-resources-test-server"
              :version "1.0.0"
              :protocol "MCP/2025-06-18"
              :platform "JVM"})}]})

(defn metrics-handler
  "Return detailed performance metrics."
  [context]
  {:contents
   [{:uri "defport://metrics"
     :mimeType "application/json"
     :text (json/generate-string
             {:requests-per-second (/ (:requests @server-stats)
                                     (max 1 (:uptime-seconds @server-stats)))
              :active-connections (:active-connections @server-stats)
              :memory-usage-mb (/ (.totalMemory (Runtime/getRuntime))
                                 (* 1024 1024))
              :timestamp (System/currentTimeMillis)})}]})

(defn environment-handler
  "Return environment information."
  [context]
  {:contents
   [{:uri "defport://environment"
     :mimeType "application/json"
     :text (json/generate-string
             {:java-version (System/getProperty "java.version")
              :os-name (System/getProperty "os.name")
              :os-arch (System/getProperty "os.arch")
              :user-dir (System/getProperty "user.dir")})}]})

(defn documentation-handler
  "Return API documentation."
  [context]
  {:contents
   [{:uri "defport://documentation"
     :mimeType "text/markdown"
     :text "# Resources Test Server\n\n## Available Resources\n\n### Static Resources\n- `defport://schema` - Server schema (EDN)\n- `defport://version` - Version info (JSON)\n- `defport://environment` - Environment info (JSON)\n- `defport://documentation` - This documentation (Markdown)\n\n### Dynamic Resources (Subscribable)\n- `defport://config` - Server configuration (JSON)\n- `defport://stats` - Server statistics (JSON)\n- `defport://logs` - Application logs (JSON)\n- `defport://health` - Health status (JSON)\n- `defport://metrics` - Performance metrics (JSON)\n\n## Subscriptions\n\nSubscribe to dynamic resources to receive updates when they change.\n"}]})

(defn readme-handler
  "Return README content."
  [context]
  {:contents
   [{:uri "defport://readme"
     :mimeType "text/plain"
     :text "Welcome to the defport Resources Test Server!\n\nThis server demonstrates MCP resources functionality including:\n- Static resources (schema, version, docs)\n- Dynamic resources (stats, logs, config)\n- Subscriptions and notifications\n- Multiple MIME types (JSON, EDN, Markdown, Plain Text)\n\nUse resources/list to see all available resources.\nUse resources/subscribe to get updates for dynamic resources.\n"}]})

;; ============================================================================
;; Background Tasks (for generating updates)
;; ============================================================================

(def background-tasks-running (atom false))

(defn start-background-tasks!
  "Start background tasks that update resources."
  [registry adapter]
  (when (compare-and-set! background-tasks-running false true)
    ;; Stats updater
    (future
      (while @background-tasks-running
        (Thread/sleep 2000)
        (update-stats!)
        ;; Notify subscribers
        (mcp/notify-resource-updated registry adapter "defport://stats")))

    ;; Log generator
    (future
      (while @background-tasks-running
        (Thread/sleep 5000)
        (let [level (rand-nth ["info" "warn" "debug"])
              message (rand-nth ["Request processed" "Cache updated" "Background job completed"])]
          (add-log! level message))
        ;; Notify subscribers
        (mcp/notify-resource-updated registry adapter "defport://logs")))

    ;; Metrics updater
    (future
      (while @background-tasks-running
        (Thread/sleep 3000)
        (swap! server-stats update :active-connections
          #(max 0 (+ % (- (rand-int 3) 1))))
        ;; Notify subscribers
        (mcp/notify-resource-updated registry adapter "defport://metrics")))))

(defn stop-background-tasks!
  "Stop background tasks."
  []
  (reset! background-tasks-running false))

;; ============================================================================
;; Registry Setup
;; ============================================================================

(defn create-resources-registry
  "Create a registry with all test resources."
  []
  (let [reg (registry/create-function-registry)]
    ;; Static resources
    (core/register-port! reg
      {:id :schema
       :description "Server schema definition"
       :handler schema-handler
       :metadata {:resource true
                  :uri "defport://schema"
                  :mime-type "application/edn"}})

    (core/register-port! reg
      {:id :version
       :description "Server version information"
       :handler version-handler
       :metadata {:resource true
                  :uri "defport://version"
                  :mime-type "application/json"}})

    (core/register-port! reg
      {:id :environment
       :description "Environment information"
       :handler environment-handler
       :metadata {:resource true
                  :uri "defport://environment"
                  :mime-type "application/json"}})

    (core/register-port! reg
      {:id :documentation
       :description "API documentation"
       :handler documentation-handler
       :metadata {:resource true
                  :uri "defport://documentation"
                  :mime-type "text/markdown"}})

    (core/register-port! reg
      {:id :readme
       :description "README content"
       :handler readme-handler
       :metadata {:resource true
                  :uri "defport://readme"
                  :mime-type "text/plain"}})

    ;; Dynamic resources (subscribable)
    (core/register-port! reg
      {:id :config
       :description "Server configuration (subscribable)"
       :handler config-handler
       :metadata {:resource true
                  :uri "defport://config"
                  :mime-type "application/json"
                  :subscribable true}})

    (core/register-port! reg
      {:id :stats
       :description "Server statistics (subscribable)"
       :handler stats-handler
       :metadata {:resource true
                  :uri "defport://stats"
                  :mime-type "application/json"
                  :subscribable true}})

    (core/register-port! reg
      {:id :logs
       :description "Application logs (subscribable)"
       :handler logs-handler
       :metadata {:resource true
                  :uri "defport://logs"
                  :mime-type "application/json"
                  :subscribable true}})

    (core/register-port! reg
      {:id :health
       :description "Server health status (subscribable)"
       :handler health-handler
       :metadata {:resource true
                  :uri "defport://health"
                  :mime-type "application/json"
                  :subscribable true}})

    (core/register-port! reg
      {:id :metrics
       :description "Performance metrics (subscribable)"
       :handler metrics-handler
       :metadata {:resource true
                  :uri "defport://metrics"
                  :mime-type "application/json"
                  :subscribable true}})

    reg))

;; ============================================================================
;; Server Startup
;; ============================================================================

(defn create-handler
  "Create a JSON-RPC request handler."
  [registry adapter transport]
  (fn [request]
    (try
      (let [method (:method request)
            params (:params request {})
            request-id (:id request)
            context {:port-registry registry
                     :transport transport
                     :protocol :mcp
                     :request request}
            result (core/protocol-dispatch adapter method params context)]
        (if (:error result)
          {:jsonrpc "2.0"
           :id request-id
           :error (:error result)}
          {:jsonrpc "2.0"
           :id request-id
           :result result}))
      (catch Exception e
        {:jsonrpc "2.0"
         :id (:id request)
         :error {:code -32603
                 :message (str "Internal error: " (.getMessage e))}}))))

(defn -main
  "Start the resources test server.
  Usage:
    --http PORT  Start HTTP server on PORT
    --stdio      Start stdio server"
  [& args]
  (let [registry (create-resources-registry)
        adapter (mcp/create-mcp-adapter)
        mode (first args)
        port (when (= mode "--http") (Integer/parseInt (second args)))]

    (println "Starting MCP Resources Test Server...")
    (println "Registered resources:" (count (core/list-ports registry)))

    ;; Start background tasks
    (start-background-tasks! registry adapter)
    (println "Background update tasks started")

    ;; Add shutdown hook
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. (fn []
                 (println "\nShutting down...")
                 (stop-background-tasks!)
                 (println "Background tasks stopped"))))

    (case mode
      "--http"
      (let [transport (http/create-http-transport {:port port})
            handler (create-handler registry adapter transport)]
        (println (str "HTTP server listening on port " port))
        (core/transport-start transport handler)
        @(promise))

      "--stdio"
      (let [transport (stdio/create-stdio-transport)
            handler (create-handler registry adapter transport)]
        (println "Stdio server ready")
        (core/transport-start transport handler))

      (println "Usage: --http PORT or --stdio"))))
