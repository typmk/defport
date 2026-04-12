(ns mcp
  "Simple MCP servers AND clients in Clojure.

  NOTE: This namespace intentionally shadows clojure.core/run! with its own
  run! function for starting MCP servers. Use clojure.core/run! if needed.

  ## Server Mode - One require, done:

    (ns my-server
      (:require [mcp :refer [defserver deftool run!]]))

    (defserver demo)

    (deftool add [a :- :int, b :- :int]
      \"Add two numbers\"
      (+ a b))

    (run!)

  ## Client Mode - Connect to external MCP servers:

    (require '[mcp :refer [connect! client-list-tools
                           client-call-tool client-disconnect!]])

    (def client (connect! {:command [\"npx\" \"-y\" \"@anthropic/mcp-server-filesystem\"]
                           :client-info {:name \"my-app\" :version \"1.0\"}}))

    ;; Explore available tools
    (client-list-tools client)

    ;; Call a tool
    (client-call-tool client \"read_file\" {:path \"/tmp/test.txt\"})

    ;; Clean up
    (client-disconnect! client)

  ## Progressive Disclosure (Server Mode):

  Level 1 - Minimal:
    (defserver demo)
    (deftool add [a :- :int, b :- :int] (+ a b))
    (run!)

  Level 2 - With context:
    (deftool process [uri :- :string, ctx :- :context]
      (log ctx :info \"Processing...\")
      (report-progress ctx 50 100)
      ...)

  Level 3 - With options:
    (deftool add
      {:tags #{:math} :annotations {:read-only-hint true}}
      [a :- :int, b :- :int]
      (+ a b))

  Level 4 - Fluent/programmatic:
    (-> (server \"Demo\")
        (add-tool :add add-handler {:description \"Add\" :schema {...}})
        (with-transport :http {:port 8080})
        (build!)
        (start!))

  Level 5 - Advanced MCP features:
    (deftool book-restaurant [restaurant :- :string]
      (let [response (elicit! {:message \"Confirm?\" :schema [:map [:ok :boolean]]})]
        (when (= :accept (:action response))
          (book! restaurant))))

  Level 6 - Cross-protocol registry:
    ;; In your app namespace:
    (require '[defport.core :as defport])
    (defport/register-port! {:id :analyze
                             :handler analyze-code
                             :schema [:map [:code :string]]
                             :description \"Analyze code\"})

    ;; Expose as MCP tool:
    (require '[mcp :refer [expose-port!]])
    (expose-port! :analyze)
    ;; Or with a different name:
    (expose-port! :analyze :as :code-analysis)"
  (:refer-clojure :exclude [run!])
  (:require [defport.core :as core]
            [defport.mcp :as mcp-impl]
            [defport.sugar :as sugar]
            ;; :include-macros true tells CLJS to also load macros from
            ;; this .cljc file under the same alias, so (platform/try-any ...)
            ;; call sites resolve the macro correctly on both platforms.
            ;; Clojure ignores this key.
            [defport.util.platform :as platform :include-macros true]))

;; ============================================================================
;; Internal State
;; ============================================================================

(defonce *server (atom nil))
(defonce *tools (atom []))
(defonce *resources (atom []))
(defonce *prompts (atom []))
(defonce ^:private *schemas (atom {}))
(defonce ^:private *transport (atom nil))
(defonce ^:private *running? (atom false))
(defonce ^:private *lifespan-context (atom nil))
(defonce ^:private *lifecycle-hooks (atom {}))

;; ============================================================================
;; Context Protocol (Level 2)
;; ============================================================================

(defprotocol IContext
  "Context for tool handlers. Injected via :context parameter."
  (log [ctx level message] "Log message to client")
  (report-progress [ctx current total] [ctx current total message] "Report progress")
  (read-resource [ctx uri] "Read a resource")
  (get-state [ctx key] "Get request state")
  (set-state [ctx key value] "Set request state")
  (get-lifespan [ctx] [ctx key] "Get lifespan context or specific key"))

(defrecord Context [server request state* lifespan-ctx*]
  IContext
  (log [_ level message]
    (platform/eprintln (str "[" (name level) "] " message)))
  (report-progress [_ current total]
    (platform/eprintln (str "Progress: " current "/" total)))
  (report-progress [_ current total message]
    (platform/eprintln (str "Progress: " current "/" total " - " message)))
  (read-resource [_ uri] nil)
  (get-state [_ key] (get @state* key))
  (set-state [_ key value] (swap! state* assoc key value))
  (get-lifespan [this] (get-lifespan this nil))
  (get-lifespan [_ key]
    (let [ctx (if (fn? lifespan-ctx*) (lifespan-ctx*) lifespan-ctx*)]
      (if key (get ctx key) ctx))))

;; ============================================================================
;; Schema Registry
;; ============================================================================

(defn register-schema!
  "Register a named schema for reuse in tool definitions.

  Example:
    (register-schema! :search-params
      [:map [:query :string] [:limit {:optional true} :int]])

    (deftool search :search-params
      (search query limit))"
  [name schema]
  (swap! *schemas assoc name schema))

(defn get-schema
  "Get a registered schema by name."
  [name]
  (get @*schemas name))

(defn list-schemas
  "List all registered schema names."
  []
  (keys @*schemas))

;; ============================================================================
;; Server Record & Builder
;; ============================================================================

(defrecord Server [name version tools resources prompts options transport-config
                   ;; Lifecycle fields
                   lifespan-config     ; {:setup fn :cleanup fn} or nil
                   lifecycle-hooks     ; {:on-start fn :on-stop fn :on-initialize fn ...}
                   lifespan-context*]) ; atom holding current lifespan context

(defn server
  "Create a server (fluent API entry point).

  Level 4 usage:
    (-> (server \"Demo\" \"1.0.0\")
        (add-tool :add handler {:description \"Add\"})
        (with-transport :http {:port 8080})
        (build!)
        (start!))

  With lifecycle:
    (-> (server \"Demo\")
        (with-lifespan {:setup (fn [] {:db (open-conn)})
                        :cleanup (fn [{:keys [db]}] (close-conn db))})
        (with-hooks {:on-start (fn [srv] (log \"Starting\"))
                     :on-stop (fn [srv] (log \"Stopping\"))})
        (add-tool :search search-fn)
        (build!)
        (start!))"
  ([name] (server name "1.0.0"))
  ([name version]
   (let [s (->Server name version
                     (atom []) (atom []) (atom []) (atom {}) (atom nil)
                     nil nil (atom nil))]
     (reset! *server s)
     s)))

;; ============================================================================
;; Macros (Level 1-3)
;; ============================================================================

(defmacro defserver
  "Define the MCP server.

  (defserver my-server)
  (defserver my-server \"2.0.0\")"
  ([name] `(defserver ~name "1.0.0"))
  ([name version]
   `(def ~name (server ~(clojure.core/name name) ~version))))

(defmacro deftool
  "Define an MCP tool.

  Simple:
    (deftool add [a :- :int, b :- :int]
      \"Add two numbers\"
      (+ a b))

  With context:
    (deftool process [uri :- :string, ctx :- :context]
      \"Process URI\"
      (log ctx :info \"Working...\")
      ...)

  With options:
    (deftool add
      {:tags #{:math} :dangerous true}
      [a :- :int, b :- :int]
      \"Add numbers\"
      (+ a b))

  With Malli schema:
    (deftool search
      [:map [:query :string] [:limit {:optional true} :int]]
      \"Search code\"
      (do-search query limit))

  With named schema:
    (deftool search :search-params
      \"Search code\"
      (do-search query limit))"
  [tool-name & args]
  (let [;; Parse: opts? params doc? body
        [opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [params args] [(first args) (rest args)]
        [doc body] (sugar/extract-doc-and-body args)

        ;; Handle different param forms
        schema-form (cond
                      ;; Named schema reference
                      (keyword? params)
                      `(or (sugar/malli->json-schema (get-schema ~params))
                           {:type "object"})

                      ;; Malli schema (starts with :map, :vector, etc.)
                      (and (vector? params)
                           (keyword? (first params))
                           (#{:map :vector :string :int :boolean} (first params)))
                      `(sugar/malli->json-schema '~params)

                      ;; Type annotations
                      (vector? params)
                      (sugar/params->json-schema (sugar/parse-params params))

                      :else {:type "object"})

        ;; Extract param names for binding
        parsed (when (and (vector? params)
                          (or (not (keyword? (first params)))
                              (= :- (second params))))
                 (sugar/parse-params params))

        ;; For Malli :map schema, extract keys
        malli-keys (when (and (vector? params)
                              (= :map (first params)))
                     (vec (keep #(when (vector? %) (first %)) (rest params))))

        ctx-name (or (:context-name parsed)
                     (some #(when (= :context (second %)) (first %))
                           (partition 3 params)))

        pnames (or (mapv :name (:params parsed)) malli-keys [])
        params-sym (gensym "params")
        ;; Create explicit gensym for context to use in both fn param and let bindings
        ctx-sym (gensym "context")]

    `(let [handler# (fn [~ctx-sym]
                      (let [~params-sym (:params ~ctx-sym)
                            ~@(when ctx-name [ctx-name ctx-sym])
                            ~@(mapcat (fn [p]
                                        (let [n (if (map? p) (:name p) p)]
                                          [n `(get ~params-sym ~(clojure.core/name n))]))
                                      pnames)]
                        (sugar/->text-content (do ~@body))))
           schema# ~schema-form
           tool# {:id ~(keyword tool-name)
                  :name ~(clojure.core/name tool-name)
                  :description ~doc
                  :input-schema schema#
                  :handler handler#
                  :options ~opts}]
       (swap! *tools conj tool#)
       (when-let [s# @*server]
         (swap! (:tools s#) conj tool#))
       tool#)))

(defmacro defresource
  "Define an MCP resource.

  Static:
    (defresource \"config://version\" \"Get version\" \"1.0.0\")

  Dynamic:
    (defresource \"users://{id}/profile\" [id :- :string]
      \"Get user profile\"
      {:name id :status \"active\"})"
  [uri & args]
  (let [[params args] (if (vector? (first args))
                        [(first args) (rest args)]
                        [[] args])
        [doc body] (sugar/extract-doc-and-body args)
        parsed (sugar/parse-params params)
        params-sym (gensym "params")]
    `(let [handler# (fn [~params-sym]
                      (let [~@(mapcat (fn [p]
                                        [(:name p) `(get ~params-sym ~(keyword (:name p)))])
                                      (:params parsed))]
                        ~@body))
           res# {:uri ~uri
                 :description ~doc
                 :handler handler#
                 :template? ~(boolean (re-find #"\{" uri))}]
       (swap! *resources conj res#)
       (when-let [s# @*server]
         (swap! (:resources s#) conj res#))
       res#)))

(defmacro defprompt
  "Define an MCP prompt.

  (defprompt summarize [text :- :string]
    \"Summarization prompt\"
    [{:role \"user\"
      :content {:type \"text\" :text (str \"Summarize: \" text)}}])"
  [prompt-name & args]
  (let [[params args] (if (vector? (first args))
                        [(first args) (rest args)]
                        [[] args])
        [doc body] (sugar/extract-doc-and-body args)
        parsed (sugar/parse-params params)
        prompt-args (mapv (fn [{:keys [name]}]
                            {:name (clojure.core/name name) :required true})
                          (:params parsed))
        params-sym (gensym "params")]
    `(let [handler# (fn [~params-sym]
                      (let [~@(mapcat (fn [p]
                                        [(:name p) `(get ~params-sym ~(keyword (:name p)))])
                                      (:params parsed))]
                        {:messages (do ~@body)}))
           p# {:name ~(clojure.core/name prompt-name)
               :description ~doc
               :arguments ~prompt-args
               :handler handler#}]
       (swap! *prompts conj p#)
       (when-let [s# @*server]
         (swap! (:prompts s#) conj p#))
       p#)))

;; ============================================================================
;; Fluent API (Level 4)
;; ============================================================================

(defn add-tool
  "Add a tool to server (fluent API).

  (-> (server \"Demo\")
      (add-tool :add add-fn {:description \"Add\" :schema {...}}))"
  [server id handler opts]
  (let [schema (cond
                 (:schema opts) (if (vector? (:schema opts))
                                  (sugar/malli->json-schema (:schema opts))
                                  (:schema opts))
                 :else {:type "object"})
        tool {:id id
              :name (name id)
              :description (:description opts)
              :input-schema schema
              :handler handler
              :options (dissoc opts :description :schema)}]
    (swap! (:tools server) conj tool)
    server))

(defn add-resource
  "Add a resource to server (fluent API)."
  [server uri handler opts]
  (let [res {:uri uri
             :description (:description opts)
             :handler handler
             :mime-type (:mime-type opts "application/json")
             :template? (boolean (re-find #"\{" uri))}]
    (swap! (:resources server) conj res)
    server))

(defn add-prompt
  "Add a prompt to server (fluent API)."
  [server name handler opts]
  (let [p {:name (clojure.core/name name)
           :description (:description opts)
           :arguments (:arguments opts [])
           :handler handler}]
    (swap! (:prompts server) conj p)
    server))

(defn with-transport
  "Configure transport (fluent API).

  (-> (server \"Demo\")
      (with-transport :http {:port 8080}))"
  [server type opts]
  (reset! (:transport-config server) {:type type :opts opts})
  server)

(defn with-options
  "Set server options (fluent API).

  (-> (server \"Demo\")
      (with-options {:enable-refactoring true}))"
  [server opts]
  (swap! (:options server) merge opts)
  server)

(defn with-lifespan
  "Configure lifespan resource management (fluent API).

  Lifespan provides setup/cleanup lifecycle for resources that should
  live for the duration of the server. Resources are available to all
  handlers via (get-lifespan ctx :key).

  Options:
    :setup   - (fn [] context-map) called on start!, returns resources
    :cleanup - (fn [ctx] ...) called on stop!, receives setup result

  Example:
    (-> (server \"Demo\")
        (with-lifespan {:setup (fn [] {:db (open-conn) :cache (atom {})})
                        :cleanup (fn [{:keys [db]}] (close-conn db))})
        (add-tool :search search-fn)
        (build!)
        (start!))

  In handlers:
    (deftool search [query :- :string, ctx :- :context]
      (let [db (get-lifespan ctx :db)]
        (query-db db query)))

  For lazy initialization (defnet pattern):
    (with-lifespan {:setup (fn [] {:get-conn #(ensure-conn!)})})
    ;; Then in handler: ((get-lifespan ctx :get-conn))"
  [server {:keys [setup cleanup] :as config}]
  (assoc server :lifespan-config config))

(defn with-hooks
  "Configure lifecycle hooks (fluent API).

  Hooks are called at various lifecycle points. All hooks receive
  the server as first argument. Hooks are optional and independent.

  Supported hooks:
    :on-start      - (fn [server] ...) called when server starts
    :on-stop       - (fn [server] ...) called when server stops
    :on-initialize - (fn [server session] ...) called per MCP client connection
    :on-shutdown   - (fn [server session] ...) called per MCP client disconnect

  Example:
    (-> (server \"Demo\")
        (with-hooks {:on-start (fn [srv] (log/info \"Starting\" (:name srv)))
                     :on-stop (fn [srv] (log/info \"Stopping\"))
                     :on-initialize (fn [srv session]
                                      (ensure-system-initialized!))})
        (build!)
        (start!))

  Composing hooks:
    (-> (server \"Demo\")
        (with-hooks {:on-start log-start})
        (with-hooks {:on-start register-instance})  ; Merges, both called
        ...)"
  [server hooks]
  (update server :lifecycle-hooks merge hooks))

;; ============================================================================
;; Request Handler
;; ============================================================================

(defn- create-handler
  "Create JSON-RPC request handler.

  Args:
    server-info     - {:name \"...\" :version \"...\"}
    tools           - vector of tool definitions
    resources       - vector of resource definitions
    prompts         - vector of prompt definitions
    lifespan-ctx-fn - (fn [] context-map) or context-map, lifespan resources
    hooks           - {:on-initialize fn :on-shutdown fn ...}"
  [server-info tools resources prompts lifespan-ctx-fn hooks]
  (fn [request]
    (let [method (:method request)
          params (:params request)
          ;; Resolve lifespan context (can be fn for lazy or map for eager)
          get-lifespan-ctx (if (fn? lifespan-ctx-fn)
                             lifespan-ctx-fn
                             (constantly lifespan-ctx-fn))]
      (case method
        "initialize"
        (do
          ;; Call on-initialize hook if provided
          (when-let [on-init (:on-initialize hooks)]
            (platform/try-any
              (on-init {:client-info (:clientInfo params)
                        :protocol-version (:protocolVersion params)})
              (catch-any e
                (platform/eprintln (str "on-initialize hook error: "
                                        (platform/error-message e))))))
          {:result {:protocolVersion "2025-06-18"
                    :capabilities {:tools {}
                                   :resources (when (seq resources) {})
                                   :prompts (when (seq prompts) {})}
                    :serverInfo server-info}})

        "tools/list"
        {:result {:tools (mapv #(select-keys % [:name :description :input-schema :inputSchema])
                               tools)}}

        "tools/call"
        (let [tool-name (:name params)
              tool (some #(when (= (:name %) tool-name) %) tools)]
          (if tool
            (platform/try-any
              (let [ctx (->Context nil request (atom {}) get-lifespan-ctx)
                    result ((:handler tool) (assoc ctx :params (:arguments params)))]
                {:result (if (:content result) result {:content [{:type "text" :text (str result)}]})})
              (catch-any e
                {:error {:code -32603 :message (platform/error-message e)}}))
            {:error {:code -32601 :message (str "Tool not found: " tool-name)}}))

        "resources/list"
        {:result {:resources (mapv #(select-keys % [:uri :name :description :mimeType])
                                   (remove :template? resources))}}

        "resources/templates/list"
        {:result {:resourceTemplates (mapv #(select-keys % [:uriTemplate :name :description])
                                           (filter :template? resources))}}

        "resources/read"
        (let [uri (:uri params)
              res (some #(when (= (:uri %) uri) %) resources)]
          (if res
            (platform/try-any
              {:result {:contents [{:uri uri
                                    :mimeType (:mime-type res "text/plain")
                                    :text (str ((:handler res) params))}]}}
              (catch-any e
                {:error {:code -32603 :message (platform/error-message e)}}))
            {:error {:code -32002 :message (str "Resource not found: " uri)}}))

        "prompts/list"
        {:result {:prompts (mapv #(select-keys % [:name :description :arguments])
                                 prompts)}}

        "prompts/get"
        (let [prompt-name (:name params)
              prompt (some #(when (= (:name %) prompt-name) %) prompts)]
          (if prompt
            (platform/try-any
              {:result ((:handler prompt) (:arguments params {}))}
              (catch-any e
                {:error {:code -32603 :message (platform/error-message e)}}))
            {:error {:code -32601 :message (str "Prompt not found: " prompt-name)}}))

        "ping"
        {:result {}}

        ;; Default
        {:error {:code -32601 :message (str "Method not found: " method)}}))))

;; ============================================================================
;; Transport & Running
;; ============================================================================

(defn build!
  "Build the server (fluent API). Prepares handler but doesn't start transport.

  (-> (server \"Demo\")
      (add-tool ...)
      (build!))"
  [server]
  (let [all-tools @(:tools server)
        all-resources @(:resources server)
        all-prompts @(:prompts server)
        server-info {:name (:name server) :version (:version server)}
        ;; Get lifespan context accessor (fn or nil)
        lifespan-ctx-fn (when (:lifespan-config server)
                          (fn []
                            (or @(:lifespan-context* server)
                                ;; Return empty map if not yet initialized
                                {})))
        hooks (:lifecycle-hooks server)
        handler (create-handler server-info all-tools all-resources all-prompts
                                lifespan-ctx-fn hooks)]
    (swap! (:options server) assoc :handler handler)
    server))

(defn start!
  "Start the server (fluent API).

  Lifecycle sequence:
  1. Run lifespan :setup function (if configured)
  2. Call :on-start hook
  3. Start transport
  4. Block until transport stops
  5. Call :on-stop hook
  6. Run lifespan :cleanup function

  (-> (server \"Demo\")
      (add-tool ...)
      (with-transport :http {:port 8080})
      (with-lifespan {:setup (fn [] {:db conn})
                      :cleanup (fn [{:keys [db]}] (close db))})
      (build!)
      (start!))"
  [server]
  (let [transport-config @(:transport-config server)
        transport-type (or (:type transport-config) :stdio)
        transport-opts (or (:opts transport-config) {})
        handler (get-in @(:options server) [:handler])
        lifespan-config (:lifespan-config server)
        hooks (:lifecycle-hooks server)]

    (when-not handler
      (throw (ex-info "Server not built. Call build! first." {})))

    ;; Run lifespan setup
    (when-let [setup-fn (:setup lifespan-config)]
      (let [ctx (setup-fn)]
        (reset! (:lifespan-context* server) ctx)
        (reset! *lifespan-context ctx)))

    ;; Call on-start hook
    (when-let [on-start (:on-start hooks)]
      (platform/try-any
        (on-start server)
        (catch-any e
          (platform/eprintln (str "on-start hook error: "
                                  (platform/error-message e))))))

    (sugar/print-startup-banner
     (:name server) (:version server)
     transport-type (count @(:tools server)) "Tools"
     @(:tools server))

    (try
      (sugar/start-transport! handler
                              {:type transport-type
                               :port (:port transport-opts 8080)
                               :transport-atom *transport
                               :running-atom *running?})

      (platform/eprintln "Server ready.")
      server

      (finally
        ;; Call on-stop hook
        (when-let [on-stop (:on-stop hooks)]
          (platform/try-any
            (on-stop server)
            (catch-any e
              (platform/eprintln (str "on-stop hook error: "
                                      (platform/error-message e))))))

        ;; Run lifespan cleanup
        (when-let [cleanup-fn (:cleanup lifespan-config)]
          (when-let [ctx @(:lifespan-context* server)]
            (platform/try-any
              (cleanup-fn ctx)
              (catch-any e
                (platform/eprintln (str "lifespan cleanup error: "
                                        (platform/error-message e))))))
          (reset! (:lifespan-context* server) nil)
          (reset! *lifespan-context nil))))))

(defn run!
  "Run the MCP server (simple API).

  Options:
    :transport - :stdio (default) or :http
    :port      - HTTP port (default 8080)
    :lifespan  - {:setup fn :cleanup fn} for resource lifecycle
    :on-start  - (fn [server] ...) called when server starts
    :on-stop   - (fn [server] ...) called when server stops
    :on-initialize - (fn [session] ...) called per MCP client connection

  Examples:
    (run!)                              ; stdio, no lifespan

    (run! {:transport :http :port 8080}) ; HTTP

    ;; With lifespan (defnet pattern)
    (run! {:lifespan {:setup (fn [] {:conn (db/open-conn)})
                      :cleanup (fn [{:keys [conn]}] (db/close conn))}
           :on-start (fn [srv] (log/info \"Starting\"))
           :on-initialize (fn [session] (ensure-initialized!))})"
  ([] (run! {}))
  ([opts]
   (let [srv (or @*server (server "defport"))
         all-tools (vec (concat @*tools @(:tools srv)))
         all-resources (vec (concat @*resources @(:resources srv)))
         all-prompts (vec (concat @*prompts @(:prompts srv)))
         server-info {:name (:name srv) :version (:version srv)}
         ;; Merge lifespan from opts with server config
         lifespan-config (or (:lifespan opts) (:lifespan-config srv))
         ;; Merge hooks from opts with server config
         hooks (merge (:lifecycle-hooks srv)
                      (select-keys opts [:on-start :on-stop :on-initialize :on-shutdown]))
         transport-type (or (:transport opts) :stdio)]

     ;; Run lifespan setup
     (when-let [setup-fn (:setup lifespan-config)]
       (let [ctx (setup-fn)]
         (when (:lifespan-context* srv)
           (reset! (:lifespan-context* srv) ctx))
         (reset! *lifespan-context ctx)))

     ;; Call on-start hook
     (when-let [on-start (:on-start hooks)]
       (platform/try-any
         (on-start srv)
         (catch-any e
           (platform/eprintln (str "on-start hook error: "
                                   (platform/error-message e))))))

     ;; Create lifespan context accessor
     (let [lifespan-ctx-fn (when lifespan-config
                             (fn [] (or @*lifespan-context {})))
           handler (create-handler server-info all-tools all-resources all-prompts
                                   lifespan-ctx-fn hooks)]

       (sugar/print-startup-banner
        (:name srv) (:version srv)
        transport-type (count all-tools) "Tools"
        all-tools)

       (try
         (sugar/start-transport! handler
                                 {:type transport-type
                                  :port (or (:port opts) 8080)
                                  :transport-atom *transport
                                  :running-atom *running?})

         (platform/eprintln "Server ready.")
         #?(:clj @(promise) :cljs nil)

         (finally
           ;; Call on-stop hook
           (when-let [on-stop (:on-stop hooks)]
             (platform/try-any
               (on-stop srv)
               (catch-any e
                 (platform/eprintln (str "on-stop hook error: "
                                         (platform/error-message e))))))

           ;; Run lifespan cleanup
           (when-let [cleanup-fn (:cleanup lifespan-config)]
             (when-let [ctx @*lifespan-context]
               (platform/try-any
                 (cleanup-fn ctx)
                 (catch-any e
                   (platform/eprintln (str "lifespan cleanup error: "
                                           (platform/error-message e))))))
             (reset! *lifespan-context nil))))))))

(defn stop!
  "Stop the server and run cleanup.

  If lifespan was configured, runs cleanup function.
  Calls :on-stop hook if configured.
  Resets all global state."
  []
  ;; Call on-stop hook if registered
  (when-let [hooks @*lifecycle-hooks]
    (when-let [on-stop (:on-stop hooks)]
      (platform/try-any
        (on-stop @*server)
        (catch-any e
          (platform/eprintln (str "on-stop hook error: "
                                  (platform/error-message e)))))))

  ;; Run lifespan cleanup
  (when-let [srv @*server]
    (when-let [lifespan-config (:lifespan-config srv)]
      (when-let [cleanup-fn (:cleanup lifespan-config)]
        (when-let [ctx @*lifespan-context]
          (platform/try-any
            (cleanup-fn ctx)
            (catch-any e
              (platform/eprintln (str "lifespan cleanup error: "
                                      (platform/error-message e)))))))))

  ;; Stop transport
  (sugar/stop-transport! {:transport-atom *transport
                          :running-atom *running?})

  ;; Reset state
  (reset! *lifespan-context nil)
  (reset! *lifecycle-hooks {})
  (reset! *tools [])
  (reset! *resources [])
  (reset! *prompts [])
  (reset! *server nil))

;; ============================================================================
;; Cross-Protocol Registry Integration
;; ============================================================================

(defn expose-port!
  "Expose a registered port as an MCP tool.

   Takes a port ID from the global registry and exposes it as an MCP tool.
   Allows sharing business logic across protocols.

   Options:
   - :as - Expose with a different name (keyword)

   Example:
     ;; First register in your app:
     (defport/register-port! {:id :analyze
                              :handler analyze-code
                              :schema [:map [:code :string]]
                              :description \"Analyze code\"})

     ;; Then expose as MCP tool:
     (expose-port! :analyze)

     ;; Or with a different name:
     (expose-port! :analyze :as :code-analysis)

   Returns the tool definition or throws if port not found."
  [port-id & {:keys [as]}]
  (if-let [port (core/get-registered-port port-id)]
    (let [tool-name (or as port-id)
          schema (if-let [s (:schema port)]
                   (if (vector? s)
                     (sugar/malli->json-schema s)
                     s)
                   {:type "object"})
          ;; Wrap handler to convert result to text content
          wrapped-handler (fn [context]
                            (sugar/->text-content ((:handler port) context)))
          tool {:id tool-name
                :name (name tool-name)
                :description (:description port)
                :input-schema schema
                :handler wrapped-handler
                :options (or (:metadata port) {})
                :_port-id port-id}]
      (swap! *tools conj tool)
      (when-let [srv @*server]
        (swap! (:tools srv) conj tool))
      tool)
    (throw (ex-info (str "Port not found in registry: " port-id)
                    {:port-id port-id
                     :available (core/list-registered-ports)}))))

(defn expose-all-ports!
  "Expose all registered ports as MCP tools.

   Optionally filter by predicate.

   Example:
     ;; Expose all ports
     (expose-all-ports!)

     ;; Expose only ports with :mcp in their protocols metadata
     (expose-all-ports! #(contains? (get-in % [:metadata :protocols]) :mcp))"
  ([]
   (expose-all-ports! (constantly true)))
  ([pred]
   (doseq [port (core/list-registered-port-defs)
           :when (pred port)]
     (expose-port! (:id port)))))

;; ============================================================================
;; Runtime Modification (Hot Reload)
;; ============================================================================

(defn add-tool!
  "Add a tool at runtime and notify clients."
  [tool-def]
  (swap! *tools conj tool-def)
  (when-let [srv @*server]
    (swap! (:tools srv) conj tool-def))
  ;; TODO: notify clients via transport
  tool-def)

(defn add-resource!
  "Add a resource at runtime and notify clients."
  [resource-def]
  (swap! *resources conj resource-def)
  (when-let [srv @*server]
    (swap! (:resources srv) conj resource-def))
  resource-def)

(defn add-prompt!
  "Add a prompt at runtime and notify clients."
  [prompt-def]
  (swap! *prompts conj prompt-def)
  (when-let [srv @*server]
    (swap! (:prompts srv) conj prompt-def))
  prompt-def)

;; ============================================================================
;; Introspection
;; ============================================================================

(defn list-tools
  "List all registered tools."
  []
  (let [srv @*server
        tools (if srv @(:tools srv) @*tools)]
    (mapv #(select-keys % [:id :name :description]) tools)))

(defn list-resources
  "List all registered resources."
  []
  (let [srv @*server
        resources (if srv @(:resources srv) @*resources)]
    (mapv #(select-keys % [:uri :description]) resources)))

(defn list-prompts
  "List all registered prompts."
  []
  (let [srv @*server
        prompts (if srv @(:prompts srv) @*prompts)]
    (mapv #(select-keys % [:name :description]) prompts)))

(defn server-info
  "Get server info."
  []
  (sugar/make-server-info *server *running?
                          (fn [srv]
                            {:tools (count (list-tools))
                             :resources (count (list-resources))
                             :prompts (count (list-prompts))})))

(defn running?
  "Check if server is running."
  []
  @*running?)

;; ============================================================================
;; Advanced MCP Features (Level 5)
;; ============================================================================

(defn elicit!
  "Request user input during tool execution (MCP 2025-06-18 elicitation).

  Example:
    (deftool book-restaurant [restaurant :- :string]
      (let [response (elicit! {:message \"Confirm booking?\"
                               :schema [:map [:confirm :boolean]]})]
        (when (= :accept (:action response))
          (book! restaurant))))"
  [{:keys [message schema timeout-ms] :or {timeout-ms 60000}}]
  (when (or (nil? message) (nil? schema))
    (throw (ex-info "elicit! requires :message and :schema" {})))

  (let [json-schema (if (vector? schema)
                      (sugar/malli->json-schema schema)
                      schema)]
    ;; TODO: Implement actual elicitation via transport
    ;; For now, return a mock response
    {:action :accept :content {}}))

(defn sample!
  "Request LLM completion from client during tool execution.

  Example:
    (deftool analyze [code :- :string]
      (let [analysis (sample! [{:role \"user\"
                                :content {:type \"text\"
                                         :text (str \"Analyze: \" code)}}]
                              {:max-tokens 500})]
        {:analysis (:content analysis)}))"
  [messages & [opts]]
  ;; TODO: Implement actual sampling via transport
  ;; For now, return a mock response
  {:role "assistant"
   :content {:type "text" :text "Mock LLM response"}})

(defn get-roots
  "Get current client filesystem roots."
  []
  ;; TODO: Implement actual roots tracking
  [])

(defn validate-file!
  "Validate file is within allowed roots. Throws if outside."
  [file-path]
  ;; TODO: Implement actual validation
  true)

;; ============================================================================
;; Utilities (re-exported for convenience)
;; ============================================================================

(def json-encode sugar/json-encode)
(def json-decode sugar/json-decode)
(def uuid sugar/uuid)
(def now-ms sugar/now-ms)
(def eprintln sugar/eprintln)

;; ============================================================================
;; Client Mode — not included
;; ============================================================================
;;
;; Defport does not ship a subprocess MCP client. Spawning external MCP
;; servers and wiring them to stdio is application concern, not
;; library concern — like HTTP client libraries vs Ring.
;;
;; If your application needs to act as an MCP client:
;;
;;   1. Spawn the subprocess yourself (ProcessBuilder, babashka.process,
;;      or Node's child_process for CLJS consumers).
;;   2. Use (mcp-impl/create-mcp-client opts) to get a ProtocolClient
;;      that speaks JSON-RPC over the transport you provide.
;;   3. Wire your process's stdin/stdout to a Transport of your choice
;;      and call (core/protocol-connect client transport client-info).
;;
;; This lets you bring your own subprocess library, your own error
;; handling, and your own concurrency model.
