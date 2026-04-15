(ns defport.sugar
  "Shared DSL infrastructure for all defport protocol adapters.

   This namespace provides one underlying macro (`define-port`) plus the
   orchestration primitives (`*registry*`, `create-adapter` multimethod,
   `run!`, `stop!`). Each protocol adapter (defport.mcp, defport.lsp,
   defport.dap) builds its thin protocol-specific macros
   (deftool / deflsp / defcommand) on top of these.

   The split is:

     - defport.sugar      — ONE `define-port` macro, parses typed
                            params, builds a handler, registers a
                            port with metadata. Platform-agnostic.
     - defport.mcp        — `deftool`, `defprompt`, `defresource`,
                            `defserver`, `run!` — MCP-specific thin
                            wrappers + MCP adapter implementation.
     - defport.lsp        — `deflsp`, `defhandler`, `run!` — LSP wrappers.
     - defport.dap        — `defdap`, `defcommand`, `run!` — DAP wrappers.

   A port can carry metadata for multiple protocols, letting one
   definition surface on multiple adapters:

     (define-port find-definition
       \"Find the definition of a symbol.\"
       {:mcp/tool true
        :lsp/method \"textDocument/definition\"}
       [name :- :string]
       (graph/find-definition name))

   Adapters read their own metadata key at port-list time and expose
   only the ports that opt into that protocol."
  (:require [defport.core :as core]
            [defport.registry :as registry]
            [defport.transports.stdio :as stdio-transport]
            [defport.transports.http :as http-transport]
            [defport.util.platform :as platform]))

;; ============================================================================
;; State Factory
;; ============================================================================

(defn create-state
  "Create a state map for a protocol sugar module.

   Returns a map with atoms for:
   - :server - Current server instance
   - :transport - Active transport
   - :running? - Server running state
   - Plus any additional keys specified"
  [& additional-keys]
  (into {:server (atom nil)
         :transport (atom nil)
         :running? (atom false)}
        (map (fn [k] [k (atom (if (#{:handlers :capabilities} k) {} []))]))
        additional-keys))

;; ============================================================================
;; Context Protocol
;; ============================================================================

(defprotocol IContextBase
  "Base context interface for all protocol handlers."
  (ctx-log [ctx level message] "Log message")
  (ctx-get-state [ctx key] "Get request state")
  (ctx-set-state [ctx key value] "Set request state"))

(defn base-context-impl
  "Create base context implementation map for use in defrecord.

   Usage in defrecord:
     (defrecord MyContext [state*]
       IContextBase
       (ctx-log [_ level msg] (base-log level msg))
       ...)"
  []
  {:log (fn [level message]
          (platform/eprintln (str "[" (name level) "] " message)))
   :get-state (fn [state* key] (get @state* key))
   :set-state (fn [state* key value] (swap! state* assoc key value))})

;; ============================================================================
;; Parameter Parsing
;; ============================================================================

(defn parse-params
  "Parse parameter vector into structured form.

   Supports:
   - [arg :- :type] - typed parameter
   - [arg :- :type \"desc\"] - typed with description
   - [arg :- :context] - context injection (or :ctx)
   - [arg] - untyped (defaults to :any)

   Returns {:params [...] :context-name symbol-or-nil}"
  [params]
  (loop [remaining params, result [], ctx-name nil]
    (if (empty? remaining)
      {:params result :context-name ctx-name}
      (let [[x & xs] remaining]
        (cond
          (= :- x) (recur xs result ctx-name)
          (symbol? x)
          (let [[sep typ desc & rest] xs]
            (if (= :- sep)
              (let [t (or typ :any)]
                (if (#{:context :ctx} t)
                  (recur (if (string? desc) rest (cons desc rest)) result x)
                  (let [rest-args (if (string? desc) rest (cons desc rest))]
                    (recur rest-args
                           (conj result {:name x :type t :description (when (string? desc) desc)})
                           ctx-name))))
              (recur xs (conj result {:name x :type :any}) ctx-name)))
          :else (recur xs result ctx-name))))))

;; ============================================================================
;; Type/Schema Conversion
;; ============================================================================

(def type-map
  "Map of type keywords to JSON Schema types."
  {:int {:type "integer"} :integer {:type "integer"} :long {:type "integer"}
   :float {:type "number"} :double {:type "number"} :number {:type "number"}
   :string {:type "string"} :str {:type "string"}
   :boolean {:type "boolean"} :bool {:type "boolean"}
   :any {} :map {:type "object"} :object {:type "object"}
   :array {:type "array"} :vector {:type "array"}
   :context nil :ctx nil})

(defn type->schema
  "Convert type keyword to JSON Schema."
  [t]
  (cond
    (keyword? t) (get type-map t {:type "string"})
    (map? t) t
    (vector? t) (case (first t)
                  :enum {:type "string" :enum (vec (rest t))}
                  :array {:type "array" :items (type->schema (second t))}
                  :map {:type "object"}
                  {:type "string"})
    :else {:type "string"}))

(defn params->json-schema
  "Convert parsed params to JSON Schema."
  [parsed]
  (let [props (into {} (for [{:keys [name type description]} (:params parsed)
                             :let [s (type->schema type)]
                             :when s]
                         [(clojure.core/name name)
                          (if description
                            (assoc s :description description)
                            s)]))
        req (vec (keep #(when-let [s (type->schema (:type %))]
                          (clojure.core/name (:name %)))
                       (:params parsed)))]
    {:type "object" :properties props :required req}))

(defn malli->json-schema
  "Convert Malli schema to JSON Schema (basic support)."
  [schema]
  (cond
    (keyword? schema)
    (type->schema schema)

    (vector? schema)
    (let [[tag & args] schema]
      (case tag
        :map (let [props (into {}
                              (for [entry args
                                    :when (vector? entry)]
                                (let [[k opts-or-schema maybe-schema] entry
                                      has-opts? (map? opts-or-schema)
                                      prop-schema (if has-opts? maybe-schema opts-or-schema)]
                                  [(name k)
                                   (malli->json-schema prop-schema)])))
                   required (vec (keep (fn [entry]
                                         (when (vector? entry)
                                           (let [[k opts] entry]
                                             (when-not (and (map? opts) (:optional opts))
                                               (name k)))))
                                       args))]
               {:type "object" :properties props :required required})
        :vector {:type "array" :items (malli->json-schema (first args))}
        :string {:type "string"}
        :int {:type "integer"}
        :boolean {:type "boolean"}
        :enum {:type "string" :enum (vec args)}
        {:type "object"}))

    :else {:type "object"}))

;; ============================================================================
;; Transport Lifecycle
;; ============================================================================

;; Transport lifecycle helpers — cross-platform.
;; Previously JVM-only via `requiring-resolve`, but the indirection was
;; unnecessary: there's no circular dependency between sugar and the
;; transport namespaces. Using direct requires lets CLJS consumers use
;; these helpers too.

(defn start-transport!
  "Start a transport with the given handler.

  Options:
  - :type - :stdio or :http (default :stdio)
  - :port - HTTP port (default 8080)
  - :transport-atom - atom to store transport reference
  - :running-atom - atom to track running state"
  [handler {:keys [type port transport-atom running-atom]
            :or {type :stdio port 8080}}]
  (let [transport (case type
                    :stdio (stdio-transport/create-stdio-transport)
                    :http  (http-transport/create-http-transport {:port port})
                    (throw (ex-info (str "Unknown transport type: " type)
                                    {:type type})))]
    (when transport-atom
      (reset! transport-atom transport))
    (core/transport-start transport handler)
    (when running-atom
      (reset! running-atom true))
    transport))

(defn stop-transport!
  "Stop a transport.

  Options:
  - :transport-atom - atom containing transport reference
  - :running-atom - atom to track running state"
  [{:keys [transport-atom running-atom]}]
  (when-let [transport (and transport-atom @transport-atom)]
    (core/transport-stop transport))
  (when transport-atom
    (reset! transport-atom nil))
  (when running-atom
    (reset! running-atom false)))

(defn print-startup-banner
  "Print startup banner to stderr."
  [server-name version transport-type item-count item-label items]
  (platform/eprintln (str "Starting " server-name " v" version))
  (platform/eprintln (str "Transport: " (name transport-type)))
  (platform/eprintln (str item-label ": " item-count))
  (doseq [item items]
    (platform/eprintln (str "  - " (or (:name item) (name item))))))

;; ============================================================================
;; Introspection Helpers
;; ============================================================================

(defn make-server-info
  "Create server info map."
  [server-atom running-atom counts-fn]
  (when-let [srv @server-atom]
    (merge {:name (:name srv)
            :version (:version srv)
            :running? @running-atom}
           (counts-fn srv))))

(defn make-running?
  "Create running? check function."
  [running-atom]
  (fn [] @running-atom))

;; ============================================================================
;; Result Conversion
;; ============================================================================

(defn ->text-content
  "Convert result to text content format.

   Used by MCP for tool results."
  [result]
  (cond
    (and (map? result) (or (:content result) (:error result) (:result result))) result
    (string? result) {:content [{:type "text" :text result}]}
    (number? result) {:content [{:type "text" :text (str result)}]}
    (or (map? result) (vector? result))
    {:content [{:type "text" :text (platform/json-encode result)}]}
    (nil? result) {:content []}
    :else {:content [{:type "text" :text (pr-str result)}]}))

;; ============================================================================
;; Macro Helpers
;; ============================================================================

(defn extract-doc-and-body
  "Extract docstring and body from macro args."
  [args]
  (if (string? (first args))
    [(first args) (rest args)]
    [nil args]))

(defn make-param-bindings
  "Generate let bindings for extracting params from a map.

   Returns: [sym1 (get params-sym :sym1) sym2 (get params-sym :sym2) ...]"
  [parsed-params params-sym]
  (vec (mapcat (fn [p]
                 (let [n (if (map? p) (:name p) p)]
                   [n `(get ~params-sym ~(keyword n))]))
               parsed-params)))

;; ============================================================================
;; Re-exports
;; ============================================================================

(def json-encode platform/json-encode)
(def json-decode platform/json-decode)
(def uuid platform/uuid)
(def now-ms platform/now-ms)
(def eprintln platform/eprintln)

;; ============================================================================
;; Unified port definition — the heart of the DSL
;; ============================================================================
;;
;; One `define-port` macro covers every protocol. Protocol-specific
;; macros (deftool, deflsp, defcommand) are thin wrappers that call
;; `define-port` with the right metadata.

(def ^:dynamic *registry*
  "Default port registry used by define-port and protocol-specific
  macros. A fresh FunctionPortRegistry by default. Rebind with
  `binding` for test isolation or multi-server scenarios.

  (binding [*registry* (registry/create-function-registry)]
    (deftool my-tool [x :- :int] (+ x 1))
    (run! {:protocol :mcp :transport :stdio}))"
  (registry/create-function-registry))

(defn parse-define-port-args
  "Parse the flexible argument shape that `define-port` and its
  protocol-specific wrappers accept:

    (define-port name metadata params body...)
    (define-port name doc metadata params body...)
    (define-port name options-map metadata params body...)
    (define-port name doc options-map metadata params body...)

  Returns {:name :doc :metadata :params :body}."
  [name args]
  (let [[doc args]   (if (string? (first args)) [(first args) (rest args)] [nil args])
        [opts args]  (if (and (map? (first args))
                               ;; Distinguish options map (has no protocol ns keys)
                               ;; from metadata map (has at least one protocol ns key)
                               (not (some #(and (keyword? %) (namespace %)) (keys (first args)))))
                       [(first args) (rest args)]
                       [{} args])
        [metadata args] (if (map? (first args))
                          [(first args) (rest args)]
                          [{} args])
        [params body]   [(first args) (rest args)]]
    {:name name
     :doc doc
     :options opts
     :metadata metadata
     :params params
     :body body}))

(defn build-schema-form
  "Build the schema quotation for a params form. Handles two shapes:
   - Type-annotated vector: [x :- :int y :- :string]
   - Malli schema: [:map [:x :int] [:y :string]]
   - Nil/empty: empty object schema

   Returns either a literal schema map (computed at macroexpansion time)
   or a quoted form that will be evaluated at runtime in the caller's
   namespace. Any symbols in quoted forms are fully qualified to this
   namespace so callers don't need any specific require alias."
  [params]
  (cond
    (nil? params)    {:type "object" :properties {} :required []}

    (and (vector? params)
         (keyword? (first params))
         (#{:map :vector :string :int :boolean} (first params)))
    `(defport.sugar/malli->json-schema '~params)

    (vector? params)
    (params->json-schema (parse-params params))

    :else {:type "object"}))

(defn extract-param-names
  "Extract the parameter symbol names from a params form.
   Returns a vector of symbols, or [] if no destructuring is needed."
  [params]
  (cond
    (nil? params) []
    (keyword? params) []
    (and (vector? params)
         (keyword? (first params))
         (= :map (first params)))
    ;; Malli :map schema — extract keys
    (vec (keep #(when (vector? %) (first %)) (rest params)))

    (vector? params)
    (mapv :name (:params (parse-params params)))

    :else []))

(defn extract-context-sym
  "If the params form requests context injection via [name :- :context]
   or [name :- :ctx], return that symbol; else nil."
  [params]
  (when (and (vector? params)
             (or (not (keyword? (first params)))
                 (= :- (second params))))
    (:context-name (parse-params params))))

(defmacro define-port
  "Define a port — a protocol-agnostic capability definition.

  A port is a named handler with:
    - an input schema (generated from the params form)
    - a protocol-specific metadata map (stamps which protocols expose it)
    - a body that runs when the handler is invoked

  Usage:

    (define-port greet
      \"Greet a user by name.\"
      {:mcp/tool true}
      [name :- :string]
      {:greeting (str \"Hi, \" name)})

  Metadata keys are per-protocol. A port with {:mcp/tool true} is
  exposed as an MCP tool. A port with {:lsp/method \"textDocument/hover\"}
  is exposed as an LSP method. A port with both surfaces on both.

  The body receives local bindings for each named parameter. To also
  receive the full request context, add a `ctx :- :context` parameter:

    (define-port process
      {:mcp/tool true}
      [uri :- :string ctx :- :context]
      (some-fn-using-ctx ctx uri))

  The port is registered into *registry*. Rebind with `binding` for
  test isolation or to register into a specific registry."
  [port-name & args]
  (let [{:keys [doc options metadata params body]} (parse-define-port-args port-name args)
        pnames (extract-param-names params)
        ctx-name (extract-context-sym params)
        schema-form (build-schema-form params)
        params-sym (gensym "params__")
        ctx-sym (gensym "context__")]
    `(let [handler# (fn [~ctx-sym]
                      (let [~params-sym (:params ~ctx-sym)
                            ~@(when ctx-name [ctx-name ctx-sym])
                            ~@(mapcat (fn [n]
                                        [n `(get ~params-sym ~(keyword (clojure.core/name n)))])
                                      pnames)]
                        (do ~@body)))
           port# {:id ~(keyword (clojure.core/name port-name))
                  :name ~(clojure.core/name port-name)
                  :description ~(or doc "")
                  :input-schema ~schema-form
                  :handler handler#
                  :metadata (merge ~metadata ~options)}]
       (defport.core/register-port! defport.sugar/*registry* port#)
       port#)))

;; ============================================================================
;; Adapter + Transport orchestration (multimethod)
;; ============================================================================

(defmulti create-adapter
  "Create a protocol adapter. Each protocol (:mcp, :lsp, :dap) registers
   a method. `opts` typically includes :server-info and any
   protocol-specific options.

   Usage:
     (create-adapter :mcp {:server-info {:name \"my-server\"}})"
  (fn [protocol _opts] protocol))

(defmethod create-adapter :default [protocol _opts]
  (throw (ex-info (str "No adapter registered for protocol: " protocol
                       ". Require defport.mcp, defport.lsp, or defport.dap "
                       "to register the appropriate adapter.")
                  {:protocol protocol})))

;; ============================================================================
;; Protocol-aware handler wrapping for run!
;; ============================================================================
;;
;; Each protocol has its own on-the-wire message shape:
;;
;; - MCP / LSP use JSON-RPC 2.0:
;;       inbound  {:jsonrpc \"2.0\" :id N :method \"foo\" :params {...}}
;;       outbound {:jsonrpc \"2.0\" :id N :result {...}}
;;                or {:jsonrpc \"2.0\" :id N :error {...}}
;;
;; - DAP has its own envelope:
;;       inbound  {:seq N :type \"request\" :command \"foo\" :arguments {...}}
;;       outbound {:seq N :type \"response\" :request_seq N
;;                 :success true :command \"foo\" :body {...}}
;;
;; sugar/run! wraps the raw protocol-dispatch output in the right
;; envelope automatically. Handlers at the transport level never need
;; to know which protocol they're talking.

(defn- jsonrpc-handler
  "Build a stdio handler that speaks JSON-RPC 2.0. Used for MCP and
   LSP. Dispatches to the adapter using (:method msg)/(:params msg)
   and wraps the body in a JSON-RPC response envelope."
  [adapter registry]
  (fn [msg]
    (let [result (core/protocol-dispatch adapter
                                         (:method msg)
                                         (:params msg)
                                         {:port-registry registry
                                          :request msg})]
      (when (:id msg)                          ;; notifications get no response
        (merge {:jsonrpc "2.0" :id (:id msg)}
               (if (and (map? result) (contains? result :error))
                 {:error (:error result)}
                 {:result result}))))))

(defn- dap-handler
  "Build a stdio handler that speaks DAP. Dispatches on (:command msg)
   passing the whole msg so the adapter can pull :arguments; wraps
   the body in a DAP response envelope."
  [adapter registry]
  (fn [msg]
    (let [result (core/protocol-dispatch adapter
                                         (:command msg)
                                         msg
                                         {:port-registry registry
                                          :request msg})
          body   (if (and (map? result) (contains? result :result))
                   (:result result)
                   result)]
      (when (:seq msg)
        {:seq 0
         :type "response"
         :request_seq (:seq msg)
         :success true
         :command (:command msg)
         :body body}))))

(defn- build-handler [protocol adapter registry]
  (case protocol
    (:mcp :lsp) (jsonrpc-handler adapter registry)
    :dap        (dap-handler adapter registry)
    (throw (ex-info "Unknown protocol for run!" {:protocol protocol}))))

(defn run!
  "Start a server speaking one protocol on one transport.

  Opts:
    :protocol    - :mcp, :lsp, or :dap (required)
    :transport   - :stdio, :http, or a pre-built Transport (default :stdio)
    :port        - HTTP port (for :http transport, default 8080)
    :server-info - {:name ... :version ...} passed to the adapter
    :registry    - PortRegistry instance (default: *registry*)
    :transport-opts - map forwarded to the transport constructor

  Behavior:
    - Wraps the raw protocol-dispatch output in the correct JSON-RPC /
      DAP response envelope automatically. The transport-level handler
      never needs to know which protocol it speaks.
    - stdio transports are created with :drain-on-exit? true so a
      cold JVM subprocess doesn't lose its last response on exit.
    - LSP adapters have their lifecycle + document-sync defaults
      registered automatically (you don't need to call
      lsp/register-default-handlers! yourself).

  Returns a map {:adapter :transport :registry :protocol} so callers
  can stop! it later."
  [{:keys [protocol transport port server-info registry transport-opts]
    :or   {transport :stdio port 8080}
    :as   opts}]
  (when-not protocol
    (throw (ex-info "run! requires :protocol" {:opts opts})))
  (let [registry (or registry *registry*)
        adapter  (create-adapter protocol (assoc opts :server-info server-info))
        handler  (build-handler protocol adapter registry)
        ;; MCP 2025-11-25 stdio uses JSON-lines (newline-delimited JSON).
        ;; LSP and DAP use Content-Length framing. Pick the right framing
        ;; automatically so consumers never have to know.
        default-framing (if (= protocol :mcp) :jsonlines :content-length)
        stdio-opts (merge {:drain-on-exit? true :framing default-framing}
                          transport-opts)
        t        (cond
                   (satisfies? core/Transport transport) transport
                   (= transport :stdio) (stdio-transport/create-stdio-transport stdio-opts)
                   (= transport :http)  (http-transport/create-http-transport
                                          (merge {:port port} transport-opts))
                   :else (throw (ex-info "Unknown transport type" {:transport transport})))]
    (core/transport-start t handler)
    {:adapter adapter :transport t :registry registry :protocol protocol}))

(defn stop!
  "Stop a server started with run!.

  Takes the map returned by run!. Idempotent — safe to call multiple times."
  [{:keys [transport]}]
  (when transport
    (core/transport-stop transport))
  nil)
