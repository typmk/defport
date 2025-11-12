(ns simple-mcp-server
  "Example MCP server using defport library.

  This demonstrates how to build a complete MCP server with:
  - EDN-based port definitions
  - HTTP and stdio transports
  - Custom tool handlers
  - Progress and cancellation support

  Run with:
    clj -M -m simple-mcp-server --http 8080
    clj -M -m simple-mcp-server --stdio"
  (:require [defport.core :as core]
            [defport.registry.core :as registry]
            [defport.protocols.mcp :as mcp]
            [defport.transports.stdio :as stdio]
            [defport.transports.http :as http]
            [cheshire.core :as json])
  (:gen-class))

;; ============================================================================
;; Example Port Handlers
;; ============================================================================

(defn search-code-handler
  "Example search handler - searches for code matching query."
  [{:keys [params metadata] :as context}]
  (let [query (:query params)
        progress-callback (:progress-callback metadata)
        cancellation-check (:cancellation-check metadata)]

    ;; Simulate progress updates
    (when progress-callback
      (progress-callback 0.2 "Searching files..."))

    ;; Check cancellation
    (when (and cancellation-check (cancellation-check))
      (throw (ex-info "Operation cancelled" {:code -32800})))

    ;; Simulate search
    (Thread/sleep 100)

    (when progress-callback
      (progress-callback 0.8 "Found results"))

    ;; Return results in MCP content format
    {:content [{:type "text"
                :text (json/generate-string
                        {:query query
                         :results [{:file "src/example.clj"
                                   :line 42
                                   :text "(defn example [] ...)"}
                                  {:file "src/other.clj"
                                   :line 10
                                   :text "(defn other [] ...)"}]})}]}))

(defn get-stats-handler
  "Example stats handler - returns project statistics."
  [{:keys [params] :as context}]
  {:result {:project-name "example-project"
            :total-files 150
            :total-functions 450
            :total-lines 12500
            :languages ["Clojure" "ClojureScript"]
            :query-params params}})

(defn generate-prompt-handler
  "Example prompt handler - generates AI prompt from template."
  [{:keys [params] :as context}]
  (let [function-name (:function-name params)]
    {:messages [{:role "user"
                 :content {:type "text"
                          :text (str "Explain the function: " function-name)}}]}))

(defn get-schema-handler
  "Example resource handler - returns database schema."
  [{:keys [params] :as context}]
  {:contents [{:uri "defport://schema"
               :mimeType "application/edn"
               :text (pr-str {:entities {:function {:id :keyword
                                                   :name :string
                                                   :namespace :string}
                                        :file {:path :string
                                              :lines :integer}}})}]})

;; ============================================================================
;; Port Registry Setup
;; ============================================================================

(defn create-port-registry
  "Create and populate port registry with example tools."
  []
  (let [registry (registry/create-function-registry)]

    ;; Register tool: search-code
    (registry/register-port! registry
      {:id :search-code
       :name "search-code"
       :description "Search for code matching a query"
       :input-schema {:type "object"
                      :properties {:query {:type "string"
                                          :description "Search query"}}
                      :required ["query"]}
       :handler search-code-handler
       :metadata {:token-budget 1000
                  :dangerous? false}})

    ;; Register tool: get-stats
    (registry/register-port! registry
      {:id :get-stats
       :name "get-stats"
       :description "Get project statistics"
       :input-schema {:type "object"
                      :properties {:include-details {:type "boolean"
                                                    :description "Include detailed stats"}}}
       :handler get-stats-handler
       :metadata {:token-budget 500}})

    ;; Register prompt: explain-function
    (registry/register-port! registry
      {:id :explain-function
       :name "explain-function"
       :description "Generate AI prompt to explain a function"
       :input-schema {:type "object"
                      :properties {:function-name {:type "string"
                                                  :description "Function to explain"}}
                      :required ["function-name"]}
       :handler generate-prompt-handler
       :metadata {:prompt true
                  :prompt-args [{:name "function-name"
                                :description "Function to explain"
                                :required true}]}})

    ;; Register resource: schema
    (registry/register-port! registry
      {:id :schema
       :name "schema"
       :description "Database schema definition"
       :handler get-schema-handler
       :metadata {:resource true
                  :mime-type "application/edn"}})

    registry))

;; ============================================================================
;; JSON-RPC Message Handling
;; ============================================================================

(defn handle-jsonrpc-request
  "Handle a JSON-RPC 2.0 request and return response.

  Integrates:
  - MCP protocol adapter (routes method to port)
  - Port registry (looks up port handlers)
  - Transport (for progress notifications)"
  [request port-registry mcp-adapter transport]
  (try
    (let [method (:method request)
          params (:params request {})
          request-id (:id request)

          ;; Build context for protocol adapter
          context {:port-registry port-registry
                   :transport transport
                   :protocol :mcp
                   :request request}

          ;; Dispatch through MCP adapter
          result (core/protocol-dispatch mcp-adapter method params context)]

      ;; Format JSON-RPC response
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
               :message (str "Internal error: " (.getMessage e))}})))

(defn handle-jsonrpc-batch
  "Handle batch JSON-RPC requests."
  [requests port-registry mcp-adapter transport]
  (mapv #(handle-jsonrpc-request % port-registry mcp-adapter transport) requests))

;; ============================================================================
;; Server Startup
;; ============================================================================

(defn start-http-server
  "Start HTTP MCP server on specified port."
  [port]
  (let [registry (create-port-registry)
        mcp-adapter (mcp/create-mcp-adapter {:server-info {:name "simple-mcp-server"
                                                           :version "0.1.0"}})
        transport (http/create-http-transport {:port port})

        ;; Handler function for HTTP transport
        handler (fn [request]
                  (let [body (if (string? request)
                              request
                              (:body request))
                        parsed (if (string? body)
                                (json/parse-string body true)
                                body)]
                    (if (vector? parsed)
                      ;; Batch request
                      (handle-jsonrpc-batch parsed registry mcp-adapter transport)
                      ;; Single request
                      (handle-jsonrpc-request parsed registry mcp-adapter transport))))]

    (core/transport-start transport handler)

    (println "\n✓ Simple MCP server started")
    (println (str "  HTTP endpoint: http://127.0.0.1:" port "/rpc"))
    (println (str "  Health check:  http://127.0.0.1:" port "/health"))
    (println (str "  Server info:   http://127.0.0.1:" port "/info"))
    (println "\nAvailable tools:")
    (println "  - search-code: Search for code matching a query")
    (println "  - get-stats: Get project statistics")
    (println "\nAvailable prompts:")
    (println "  - explain-function: Generate AI prompt to explain a function")
    (println "\nAvailable resources:")
    (println "  - defport://schema: Database schema definition")
    (println "\nPress Ctrl+C to stop server")

    ;; Keep server running
    @(promise)))

(defn start-stdio-server
  "Start stdio MCP server."
  []
  (let [registry (create-port-registry)
        mcp-adapter (mcp/create-mcp-adapter {:server-info {:name "simple-mcp-server"
                                                           :version "0.1.0"}})
        transport (stdio/create-stdio-transport)

        ;; Handler function for stdio transport
        handler (fn [request]
                  (if (vector? request)
                    ;; Batch request
                    (handle-jsonrpc-batch request registry mcp-adapter transport)
                    ;; Single request
                    (handle-jsonrpc-request request registry mcp-adapter transport)))]

    (binding [*out* *err*]
      (println "✓ Simple MCP server starting in stdio mode")
      (println "  Reading JSON-RPC from stdin, writing to stdout"))

    (core/transport-start transport handler)))

(defn -main
  "Entry point for simple MCP server.

  Usage:
    clj -M -m simple-mcp-server --http 8080
    clj -M -m simple-mcp-server --stdio"
  [& args]
  (cond
    (some #{"--http"} args)
    (let [port-idx (.indexOf (vec args) "--http")
          port (if (< (inc port-idx) (count args))
                 (Integer/parseInt (nth args (inc port-idx)))
                 8080)]
      (start-http-server port))

    (some #{"--stdio"} args)
    (start-stdio-server)

    :else
    (do
      (println "Simple MCP Server - Example defport application")
      (println "")
      (println "Usage:")
      (println "  clj -M -m simple-mcp-server --http [port]  Start HTTP server (default port: 8080)")
      (println "  clj -M -m simple-mcp-server --stdio        Start stdio server")
      (System/exit 1))))
