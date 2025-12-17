(ns defport.sugar
  "Shared infrastructure for protocol-specific sugar APIs (MCP, LSP, etc.).

   Provides:
   - State management atoms
   - Context protocol base
   - Parameter parsing
   - Transport lifecycle
   - Introspection helpers
   - Type/schema conversion"
  (:require [defport.util.platform :as platform]))

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

#?(:clj
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
                       :stdio
                       (let [create-fn (requiring-resolve 'defport.transports.stdio/create-stdio-transport)]
                         (create-fn))

                       :http
                       (let [create-fn (requiring-resolve 'defport.transports.http/create-http-transport)]
                         (create-fn {:port port})))]

       (when transport-atom
         (reset! transport-atom transport))

       (let [start-fn (requiring-resolve 'defport.core/transport-start)]
         (start-fn transport handler))

       (when running-atom
         (reset! running-atom true))

       transport)))

#?(:clj
   (defn stop-transport!
     "Stop a transport.

     Options:
     - :transport-atom - atom containing transport reference
     - :running-atom - atom to track running state"
     [{:keys [transport-atom running-atom]}]
     (when-let [transport (and transport-atom @transport-atom)]
       ((requiring-resolve 'defport.core/transport-stop) transport))
     (when transport-atom
       (reset! transport-atom nil))
     (when running-atom
       (reset! running-atom false))))

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
