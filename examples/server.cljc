(ns server
  "MCP server demonstrating ALL defport features.

  Run:
    clj -M:examples -m server --http 8080
    clj -M:examples -m server --stdio
    node target/server.js --http 8080"
  (:require [defport.core :as core]
            [defport.registry :as registry]
            [defport.mcp :as mcp]
            [defport.transports.stdio :as stdio]
            [defport.transports.http :as http]
            [defport.util.batch :as batch]
            [clojure.string :as str]
            #?(:clj [cheshire.core :as json]
               :cljs ["fs" :as fs])
            #?(:cljs ["path" :as path]))
  #?(:clj (:gen-class)))

;; ============================================================================
;; Platform Utilities
;; ============================================================================

(defn now []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn sleep [ms]
  #?(:clj  (Thread/sleep ms)
     :cljs nil))

(defn json-encode [obj]
  #?(:clj  (json/generate-string obj)
     :cljs (.stringify js/JSON (clj->js obj))))

(defn json-decode [s]
  #?(:clj  (json/parse-string s true)
     :cljs (js->clj (.parse js/JSON s) :keywordize-keys true)))

(defn log [& args]
  #?(:clj  (apply println args)
     :cljs (.log js/console (str/join " " args))))

(defn log-err [& args]
  #?(:clj  (binding [*out* *err*] (apply println args))
     :cljs (.error js/console (str/join " " args))))

;; ============================================================================
;; Tool Handlers
;; ============================================================================

(defn echo-handler
  "Echo back input - simplest connectivity test."
  [{:keys [params]}]
  {:result {:echoed    (:text params)
            :length    (count (:text params))
            :timestamp (now)}})

(defn add-numbers-handler
  "Add two numbers."
  [{:keys [params]}]
  {:result {:sum       (+ (:a params) (:b params))
            :operation "addition"}})

(defn search-code-handler
  "Search code with progress and cancellation."
  [{:keys [params metadata]}]
  (let [query              (:query params)
        progress-callback  (:progress-callback metadata)
        cancellation-check (:cancellation-check metadata)]

    (when progress-callback
      (progress-callback 0.1 "Starting search..."))

    (sleep 200)

    (when (and cancellation-check (cancellation-check))
      (throw (ex-info "Search cancelled" {:code -32800})))

    (when progress-callback
      (progress-callback 0.5 "Searching files..."))

    (sleep 200)

    (when progress-callback
      (progress-callback 0.9 "Formatting results..."))

    {:content [{:type "text"
                :text (json-encode
                        {:query   query
                         :results [{:file "src/server.cljc" :line 42 :match "(defn search"}
                                   {:file "src/defport/core.cljc" :line 15 :match "(defprotocol"}]
                         :total   2
                         :elapsed-ms 400})}]}))

(defn get-stats-handler
  "Get project statistics."
  [{:keys [params]}]
  {:result {:project  "defport"
            :platform #?(:clj "JVM" :cljs "Node.js")
            :files    25
            :lines    5000
            :functions 150
            :tests    100
            :coverage 0.85
            :details  (when (:include-details params)
                        {:by-namespace
                         {"defport.core"          {:lines 500  :functions 20}
                          "defport.mcp" {:lines 2000 :functions 50}}})}})

(defn list-files-handler
  "List files within roots."
  [{:keys [params]}]
  (let [pattern (:pattern params #?(:clj "*.clj" :cljs "*.cljs"))
        roots   (mcp/get-roots)]
    {:result {:pattern pattern
              :roots   (count roots)
              :files   (if (empty? roots)
                         ["No roots configured"]
                         ["src/defport/core.cljc"
                          "src/defport/protocols/mcp.cljc"])}}))

(defn analyze-with-llm-handler
  "Analyze code using LLM sampling."
  [{:keys [params transport]}]
  (let [sample-id (mcp/create-sampling-request
                    transport
                    [{:role    "user"
                      :content {:type "text"
                                :text (str "Analyze:\n" (:code params))}}]
                    {:maxTokens   500
                     :temperature 0.7})
        response  (mcp/wait-for-sampling-response sample-id 30000)]
    (if response
      {:result {:analysis (get-in response [:content 0 :text] "No analysis")
                :model    (:model response "unknown")
                :status   "completed"}}
      {:error {:code    -32000
               :message "LLM sampling timed out"}})))

(defn confirm-action-handler
  "Confirm action with user via elicitation."
  [{:keys [params transport]}]
  (let [elicit-id (mcp/create-elicitation
                    transport
                    (str "Confirm: " (:action params) "?")
                    {:type       "object"
                     :properties {:confirmed {:type "boolean"}}
                     :required   ["confirmed"]})
        response  (mcp/wait-for-elicitation elicit-id 60000)]
    (case (:action response)
      :accept {:result {:status (if (get-in response [:content :confirmed])
                                  "confirmed"
                                  "declined")
                        :action (:action params)}}
      :cancel {:result {:status "cancelled"}}
      {:error {:code -32000 :message "Timeout"}})))

#?(:cljs
   (defn node-info-handler
     "Get Node.js runtime info."
     [_]
     {:result {:platform     js/process.platform
               :arch         js/process.arch
               :node-version js/process.version
               :uptime       (.uptime js/process)}}))

;; ============================================================================
;; Dangerous Tool Handlers
;; ============================================================================

(defn rename-function-handler
  "Rename function across codebase."
  [{:keys [params]}]
  {:result {:operation      "rename-function"
            :old-name       (:old-name params)
            :new-name       (:new-name params)
            :files-modified 3
            :status         "success"}})

(defn delete-file-handler
  "Delete file within roots."
  [{:keys [params]}]
  (try
    (mcp/validate-file-access (:path params))
    {:result {:status    "deleted"
              :path      (:path params)
              :timestamp (now)}}
    (catch #?(:clj Exception :cljs js/Error) e
      {:error {:code    -32602
               :message #?(:clj (.getMessage e) :cljs (.-message e))}})))

;; ============================================================================
;; Prompt Handlers
;; ============================================================================

(defn explain-function-prompt
  "Generate prompt to explain a function."
  [{:keys [params]}]
  {:messages [{:role    "user"
               :content {:type "text"
                         :text (str "Explain `" (:function-name params) "`"
                                    (when (:include-examples params)
                                      " with examples."))}}]})

(defn review-code-prompt
  "Generate prompt to review code."
  [{:keys [params]}]
  {:messages [{:role    "system"
               :content {:type "text"
                         :text "You are an expert code reviewer."}}
              {:role    "user"
               :content {:type "text"
                         :text (str "Review (focus: " (:focus params "general") "):\n```\n"
                                    (:code params) "\n```")}}]})

;; ============================================================================
;; Resource Handlers
;; ============================================================================

(defn schema-resource-handler
  "Return database schema."
  [_]
  {:contents [{:uri      "defport://schema"
               :mimeType "application/edn"
               :text     (pr-str {:entities {:function {:id   :keyword
                                                        :name :string}
                                             :file     {:path  :string
                                                        :lines :integer}}})}]})

(defn logs-resource-handler
  "Return recent server logs."
  [{:keys [params]}]
  {:contents [{:uri      "defport://logs"
               :mimeType "text/plain"
               :text     (str/join "\n"
                           ["[INFO] Server started"
                            "[DEBUG] Processing"
                            (str "[INFO] Last " (:lines params 100) " lines")])}]})

(defn config-resource-handler
  "Return server configuration."
  [_]
  {:contents [{:uri      "defport://config"
               :mimeType "application/json"
               :text     (json-encode
                           {:server   {:name     "defport-server"
                                       :version  "1.0.0"
                                       :platform #?(:clj "JVM" :cljs "Node.js")}
                            :features ["tools" "prompts" "resources" "sampling"]})}]})

#?(:cljs
   (defn package-json-resource-handler
     "Return package.json."
     [_]
     (let [pkg-path (.join path (.cwd js/process) "package.json")]
       (if (.existsSync fs pkg-path)
         {:contents [{:uri      "defport://package.json"
                      :mimeType "application/json"
                      :text     (.readFileSync fs pkg-path "utf8")}]}
         {:error {:code    -32002
                  :message "package.json not found"}}))))

;; ============================================================================
;; Port Registry Setup
;; ============================================================================

(defn file-path-completer [partial _]
  (filter #(str/includes? % partial)
          ["src/defport/core.cljc"
           "src/defport/protocols/mcp.cljc"]))

(defn create-registry
  "Create registry with all tools, prompts, and resources."
  []
  (let [reg (registry/create-function-registry)]

    ;; === Safe Tools ===

    (core/register-port! reg
      {:id          :echo
       :name        "echo"
       :description "Echo back input text"
       :input-schema {:type       "object"
                      :properties {:text {:type "string"}}
                      :required   ["text"]}
       :handler     echo-handler})

    (core/register-port! reg
      {:id          :add-numbers
       :name        "add-numbers"
       :description "Add two numbers"
       :input-schema {:type       "object"
                      :properties {:a {:type "number"}
                                   :b {:type "number"}}
                      :required   ["a" "b"]}
       :handler     add-numbers-handler})

    (core/register-port! reg
      {:id          :search-code
       :name        "search-code"
       :description "Search code with progress"
       :input-schema {:type       "object"
                      :properties {:query {:type "string"}}
                      :required   ["query"]}
       :handler     search-code-handler})

    (core/register-port! reg
      {:id          :get-stats
       :name        "get-stats"
       :description "Get project statistics"
       :input-schema {:type       "object"
                      :properties {:include-details {:type "boolean"}}}
       :handler     get-stats-handler})

    (core/register-port! reg
      {:id          :list-files
       :name        "list-files"
       :description "List files within roots"
       :input-schema {:type       "object"
                      :properties {:pattern {:type "string"}}}
       :handler     list-files-handler})

    (core/register-port! reg
      {:id          :analyze-with-llm
       :name        "analyze-with-llm"
       :description "Analyze code using LLM"
       :input-schema {:type       "object"
                      :properties {:code {:type "string"}}
                      :required   ["code"]}
       :handler     analyze-with-llm-handler})

    (core/register-port! reg
      {:id          :confirm-action
       :name        "confirm-action"
       :description "Confirm action with user"
       :input-schema {:type       "object"
                      :properties {:action {:type "string"}}
                      :required   ["action"]}
       :handler     confirm-action-handler})

    #?(:cljs
       (core/register-port! reg
         {:id          :node-info
          :name        "node-info"
          :description "Get Node.js runtime info"
          :handler     node-info-handler}))

    ;; === Dangerous Tools ===

    (core/register-port! reg
      {:id          :rename-function
       :name        "rename-function"
       :description "Rename function across codebase (DANGEROUS)"
       :input-schema {:type       "object"
                      :properties {:old-name {:type "string"}
                                   :new-name {:type "string"}}
                      :required   ["old-name" "new-name"]}
       :handler     rename-function-handler
       :metadata    {:dangerous true}})

    (core/register-port! reg
      {:id          :delete-file
       :name        "delete-file"
       :description "Delete file within roots (DANGEROUS)"
       :input-schema {:type       "object"
                      :properties {:path {:type "string"}}
                      :required   ["path"]}
       :handler     delete-file-handler
       :metadata    {:dangerous    true
                     :completions {:path file-path-completer}}})

    ;; === Prompts ===

    (core/register-port! reg
      {:id          :explain-function
       :name        "explain-function"
       :description "Generate prompt to explain a function"
       :input-schema {:type       "object"
                      :properties {:function-name    {:type "string"}
                                   :include-examples {:type "boolean"}}
                      :required   ["function-name"]}
       :handler     explain-function-prompt
       :metadata    {:prompt true}})

    (core/register-port! reg
      {:id          :review-code
       :name        "review-code"
       :description "Generate prompt to review code"
       :input-schema {:type       "object"
                      :properties {:code  {:type "string"}
                                   :focus {:type "string"}}
                      :required   ["code"]}
       :handler     review-code-prompt
       :metadata    {:prompt true}})

    ;; === Resources ===

    (core/register-port! reg
      {:id          :schema
       :name        "schema"
       :description "Database schema"
       :handler     schema-resource-handler
       :metadata    {:resource  true
                     :mime-type "application/edn"}})

    (core/register-port! reg
      {:id          :logs
       :name        "logs"
       :description "Server logs"
       :input-schema {:type       "object"
                      :properties {:lines {:type "integer"}}}
       :handler     logs-resource-handler
       :metadata    {:resource     true
                     :mime-type    "text/plain"
                     :subscribable true}})

    (core/register-port! reg
      {:id          :config
       :name        "config"
       :description "Server configuration"
       :handler     config-resource-handler
       :metadata    {:resource  true
                     :mime-type "application/json"}})

    #?(:cljs
       (core/register-port! reg
         {:id          :package-json
          :name        "package.json"
          :description "Node.js package.json"
          :handler     package-json-resource-handler
          :metadata    {:resource  true
                        :mime-type "application/json"}}))

    reg))

;; ============================================================================
;; JSON-RPC Request Handling
;; ============================================================================

(defn handle-request
  "Handle a single JSON-RPC request."
  [request registry adapter transport]
  (try
    (let [result (core/protocol-dispatch
                   adapter
                   (:method request)
                   (:params request {})
                   {:port-registry registry
                    :transport     transport
                    :protocol      :mcp
                    :request       request})]
      (when (contains? request :id)
        {:jsonrpc "2.0"
         :id      (:id request)
         (if (:error result) :error :result)
         (or (:error result) result)}))
    (catch #?(:clj Exception :cljs js/Error) e
      (log-err "ERROR:" #?(:clj (.getMessage e) :cljs (.-message e)))
      {:jsonrpc "2.0"
       :id      (:id request)
       :error   {:code    -32603
                 :message (str "Internal error: "
                               #?(:clj (.getMessage e) :cljs (.-message e)))}})))

(defn handle-batch
  "Handle batch JSON-RPC requests."
  [requests registry adapter transport]
  (batch/process-batch
    requests
    #(handle-request % registry adapter transport)
    (mcp/get-batch-opts adapter)))

;; ============================================================================
;; Server Startup
;; ============================================================================

(defn start-http
  "Start HTTP MCP server."
  [port]
  (let [registry  (create-registry)
        adapter   (mcp/create-mcp-adapter
                    {:server-info {:name    "defport-server"
                                   :version "1.0.0"}})
        transport (http/create-http-transport {:port port})
        handler   (fn [req]
                    (let [parsed (if (string? req)
                                   (json-decode req)
                                   (json-decode (:body req)))]
                      (if (#?(:clj vector? :cljs array?) parsed)
                        (handle-batch parsed registry adapter transport)
                        (handle-request parsed registry adapter transport))))]
    (core/transport-start transport handler)
    (log "\n✓ MCP Server started on http://127.0.0.1:" port)
    (log "  Platform:" #?(:clj "JVM" :cljs "Node.js"))
    #?(:clj @(promise))))

(defn start-stdio
  "Start stdio MCP server."
  []
  (let [registry  (create-registry)
        adapter   (mcp/create-mcp-adapter
                    {:server-info {:name    "defport-server"
                                   :version "1.0.0"}})
        transport (stdio/create-stdio-transport)
        handler   (fn [req]
                    (if (#?(:clj vector? :cljs array?) req)
                      (handle-batch req registry adapter transport)
                      (handle-request req registry adapter transport)))]
    (log-err "✓ MCP Server starting in stdio mode")
    (log-err "  Platform:" #?(:clj "JVM" :cljs "Node.js"))
    (core/transport-start transport handler)))

;; ============================================================================
;; Main Entry Points
;; ============================================================================

#?(:clj
   (defn -main [& args]
     (cond
       (some #{"--http"} args)
       (let [idx  (.indexOf (vec args) "--http")
             port (if (< (inc idx) (count args))
                    (Integer/parseInt (nth args (inc idx)))
                    8080)]
         (start-http port))

       (some #{"--stdio"} args)
       (start-stdio)

       :else
       (do
         (log "Usage: clj -M:examples -m server --http [port]")
         (log "       clj -M:examples -m server --stdio")
         (System/exit 1)))))

#?(:cljs
   (defn main []
     (let [args (js->clj (.-argv js/process))]
       (cond
         (some #{"--http"} args)
         (let [idx  (.indexOf args "--http")
               port (if (< (inc idx) (count args))
                      (js/parseInt (nth args (inc idx)))
                      8080)]
           (start-http port))

         (some #{"--stdio"} args)
         (start-stdio)

         :else
         (do
           (log "Usage: node target/server.js --http [port]")
           (log "       node target/server.js --stdio")
           (.exit js/process 1))))))

#?(:cljs (set! (.-exports js/module) #js {:main main}))
