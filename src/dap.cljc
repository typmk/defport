(ns dap
  "Simple DAP (Debug Adapter Protocol) servers AND clients in Clojure.

  ## Server Mode - One require, done:

    (ns my-debugger
      (:require [dap :refer [defdap defcommand run!]]))

    (defdap my-debugger)

    (defcommand evaluate [expression :- :string, ctx :- :context]
      \"Evaluate code in debug context\"
      (eval (read-string expression)))

    (run!)

  ## Client Mode - Connect to external debug adapters:

    (require '[dap :refer [connect! client-init! client-launch!
                           client-set-breakpoints! client-evaluate
                           client-on-event! client-disconnect!]])

    (def client (connect! {:command [\"node\" \"debugger.js\"]}))

    ;; Set up event handlers
    (client-on-event! client :stopped
      (fn [msg] (println \"Stopped:\" (get-in msg [:body :reason]))))
    (client-on-event! client :output
      (fn [msg] (print (get-in msg [:body :output]))))

    ;; Initialize and launch
    (client-init! client)
    (client-launch! client {:program \"app.js\"})

    ;; Set breakpoints and control execution
    (client-set-breakpoints! client \"src/main.js\" [10 20 30])
    (client-continue! client 1)

    ;; Evaluate expressions
    (client-evaluate client \"x + y\")

    ;; Clean up
    (client-disconnect! client)

  ## Progressive Disclosure (Server Mode):

  Level 1 - Minimal (REPL-as-debugger):
    (defdap demo)
    (defcommand evaluate [expr] (eval (read-string expr)))
    (run!)

  Level 2 - With context:
    (defcommand evaluate [expression :- :string, ctx :- :context]
      \"Evaluate with context\"
      (let [frame (current-frame ctx)]
        (eval-in-frame frame expression)))

  Level 3 - Event handlers:
    (on-launch (fn [args ctx]
                 (start-debuggee! (:program args))))

    (on-stopped (fn [reason ctx]
                  (log ctx :info (str \"Stopped: \" reason))))

  Level 4 - Fluent/programmatic:
    (-> (server \"Demo\")
        (with-backend :flowstorm {:host \"localhost\" :port 7722})
        (add-command :evaluate eval-handler)
        (build!)
        (start!))

  Level 5 - Cross-protocol registry:
    ;; In your app namespace:
    (require '[defport.core :as defport])
    (defport/register-port! {:id :evaluate
                             :handler safe-eval
                             :schema [:map [:code :string]]
                             :description \"Safe evaluation\"})

    ;; Expose as DAP command:
    (require '[dap :refer [expose-port!]])
    (expose-port! :evaluate)"
  (:require [defport.core :as core]
            [defport.dap :as dap-impl]
            [defport.sugar :as sugar]
            [defport.util.platform :as platform :include-macros true]))

;; ============================================================================
;; Internal State
;; ============================================================================

(defonce ^:private *server (atom nil))
(defonce ^:private *commands (atom {}))
(defonce ^:private *event-handlers (atom {}))
(defonce ^:private *transport (atom nil))
(defonce ^:private *running? (atom false))

;; ============================================================================
;; Context Protocol (Level 2)
;; ============================================================================

(defprotocol IContext
  "Context for command handlers. Injected via :context parameter."
  (log [ctx level message] "Log message to debug console")
  (send-output [ctx category text] "Send output event")
  (current-frame [ctx] "Get current stack frame")
  (get-variables [ctx scope] "Get variables in scope")
  (get-state [ctx key] "Get session state")
  (set-state [ctx key value] "Set session state"))

(defrecord Context [adapter transport state*]
  IContext
  (log [_ level message]
    (platform/eprintln (str "[" (name level) "] " message)))
  (send-output [_ category text]
    (when transport
      (dap-impl/send-output-event adapter transport category text)))
  (current-frame [_]
    ;; Would be populated during stopped state
    nil)
  (get-variables [_ scope]
    ;; Would query adapter state
    [])
  (get-state [_ key] (get @state* key))
  (set-state [_ key value] (swap! state* assoc key value)))

;; ============================================================================
;; Re-exports (Type Constructors)
;; ============================================================================

;; Response constructors
(def make-response dap-impl/make-response)
(def make-event dap-impl/make-event)
(def make-error-response dap-impl/make-error-response)

;; Stop reasons
(def stop-reason-step dap-impl/stop-reason-step)
(def stop-reason-breakpoint dap-impl/stop-reason-breakpoint)
(def stop-reason-exception dap-impl/stop-reason-exception)
(def stop-reason-pause dap-impl/stop-reason-pause)
(def stop-reason-entry dap-impl/stop-reason-entry)

;; Event helpers
(def send-stopped-event dap-impl/send-stopped-event)
(def send-output-event dap-impl/send-output-event)
(def send-breakpoint-event dap-impl/send-breakpoint-event)

;; State helpers
(def get-adapter-state dap-impl/get-adapter-state)

;; ============================================================================
;; Server Record & Builder
;; ============================================================================

(defrecord Server [name version adapter options])

(defn server
  "Create a DAP server (fluent API entry point).

  Level 4 usage:
    (-> (server \"Demo\" \"1.0.0\")
        (with-backend :repl {:eval-fn my-eval})
        (add-command :evaluate eval-handler)
        (build!)
        (start!))"
  ([name] (server name "1.0.0"))
  ([name version]
   (server name version {}))
  ([name version opts]
   (let [backend (or (:backend opts) :repl)
         backend-opts (or (:backend-opts opts) {})
         adapter (dap-impl/create-dap-adapter
                   {:server-info {:name name :version version}
                    :backend backend
                    :backend-opts backend-opts})
         srv (->Server name version adapter (atom {:backend backend
                                                   :backend-opts backend-opts}))]
     (reset! *server srv)
     srv)))

;; ============================================================================
;; Macros (Level 1-3)
;; ============================================================================

(defmacro defdap
  "Define a DAP debug adapter server.

  (defdap my-debugger)
  (defdap my-debugger \"2.0.0\")
  (defdap my-debugger \"2.0.0\" {:backend :flowstorm})"
  ([name] `(defdap ~name "1.0.0"))
  ([name version] `(defdap ~name ~version {}))
  ([name version opts]
   `(def ~name (server ~(clojure.core/name name) ~version ~opts))))

(defmacro defcommand
  "Define a DAP command handler.

  Simple:
    (defcommand evaluate [expression :- :string]
      \"Evaluate code\"
      (eval (read-string expression)))

  With context:
    (defcommand evaluate [expression :- :string, ctx :- :context]
      \"Evaluate with context\"
      (log ctx :info (str \"Evaluating: \" expression))
      (eval (read-string expression)))

  Commands map to DAP requests. Common commands:
  - evaluate - Evaluate expression
  - completions - Get completions
  - variables - Get variables
  - set-variable - Set variable value
  - source - Get source code"
  [command-name params & body]
  (let [[doc body] (sugar/extract-doc-and-body body)
        parsed (sugar/parse-params params)
        pnames (mapv :name (:params parsed))
        ctx-name (:context-name parsed)]
    `(let [handler# (fn [args# context#]
                      (let [~@(when ctx-name [ctx-name 'context#])
                            ~@(mapcat (fn [p]
                                        [(:name p) `(get args# ~(keyword (:name p)))])
                                      (:params parsed))]
                        ~@body))]
       (swap! *commands assoc ~(keyword command-name)
              {:handler handler#
               :doc ~doc
               :params ~(mapv (fn [p] {:name (keyword (:name p))
                                        :type (:type p)})
                              (:params parsed))})
       ~(keyword command-name))))

;; ============================================================================
;; Event Handlers (Level 3)
;; ============================================================================

(defn on-launch
  "Register a launch event handler.

  (on-launch (fn [args ctx]
               (start-debuggee! (:program args))))"
  [handler]
  (swap! *event-handlers assoc :launch handler))

(defn on-attach
  "Register an attach event handler.

  (on-attach (fn [args ctx]
               (connect-to! (:host args) (:port args))))"
  [handler]
  (swap! *event-handlers assoc :attach handler))

(defn on-disconnect
  "Register a disconnect event handler.

  (on-disconnect (fn [args ctx]
                   (cleanup!)))"
  [handler]
  (swap! *event-handlers assoc :disconnect handler))

(defn on-stopped
  "Register a stopped event handler (breakpoint hit, step complete, etc.).

  (on-stopped (fn [reason ctx]
                (log ctx :info (str \"Stopped: \" reason))))"
  [handler]
  (swap! *event-handlers assoc :stopped handler))

(defn on-continued
  "Register a continued event handler.

  (on-continued (fn [ctx]
                  (clear-ui-state!)))"
  [handler]
  (swap! *event-handlers assoc :continued handler))

;; ============================================================================
;; Fluent API (Level 4)
;; ============================================================================

(defn with-backend
  "Configure debug backend (fluent API).

  Backends:
  - :repl - REPL-as-debugger (default)
  - :nrepl - nREPL/CIDER bridge
  - :flowstorm - FlowStorm time-travel debugging
  - :jdi - Java Debug Interface

  (-> (server \"Demo\")
      (with-backend :flowstorm {:host \"localhost\" :port 7722}))"
  [server backend-type opts]
  (swap! (:options server) assoc
         :backend backend-type
         :backend-opts opts)
  ;; Recreate adapter with new backend
  (let [new-adapter (dap-impl/create-dap-adapter
                      {:server-info {:name (:name server) :version (:version server)}
                       :backend backend-type
                       :backend-opts opts})]
    (->Server (:name server) (:version server) new-adapter (:options server))))

(defn add-command
  "Add a command handler (fluent API).

  (-> (server \"Demo\")
      (add-command :evaluate eval-fn {:doc \"Evaluate expression\"}))"
  [server command-id handler opts]
  (swap! *commands assoc command-id
         {:handler handler
          :doc (:doc opts)
          :params (:params opts [])})
  server)

(defn with-options
  "Set server options (fluent API).

  (-> (server \"Demo\")
      (with-options {:strict-mode true}))"
  [server opts]
  (swap! (:options server) merge opts)
  server)

;; ============================================================================
;; Request Handler
;; ============================================================================

(defn- create-handler
  "Create DAP request handler that integrates custom commands."
  [server-info adapter commands event-handlers]
  (fn [request]
    (let [;; DAP uses 'command' for request method
          command (or (:command request)
                      (get request "command")
                      (:method request))
          args (or (:arguments request)
                   (get request "arguments")
                   (:params request)
                   {})
          ctx (->Context adapter @*transport (atom {}))]

      ;; Check for custom command first
      (if-let [cmd-def (get commands (keyword command))]
        (platform/try-any
          (let [result ((:handler cmd-def) args ctx)]
            {:result (cond
                       (map? result) result
                       (nil? result) {}
                       :else {:result (pr-str result)
                              :variablesReference 0})})
          (catch-any e
            {:error {:code -32603
                     :message (platform/error-message e)}}))

        ;; Fall through to adapter's built-in handlers
        (let [result (core/protocol-dispatch adapter command args {:context ctx})]
          ;; Trigger event handlers if applicable
          (when-let [handler (get event-handlers (keyword command))]
            (platform/try-any
              (handler args ctx)
              (catch-any _
                nil)))
          result)))))

;; ============================================================================
;; Transport & Running
;; ============================================================================

(defn build!
  "Build the server (fluent API). Prepares handler but doesn't start transport.

  (-> (server \"Demo\")
      (add-command ...)
      (build!))"
  [server]
  (let [handler (create-handler
                  {:name (:name server) :version (:version server)}
                  (:adapter server)
                  @*commands
                  @*event-handlers)]
    (swap! (:options server) assoc :handler handler)
    server))

(defn start!
  "Start the server (fluent API).

  (-> (server \"Demo\")
      (add-command ...)
      (build!)
      (start!))"
  [server]
  (let [handler (get-in @(:options server) [:handler])]

    (when-not handler
      (throw (ex-info "Server not built. Call build! first." {})))

    (sugar/print-startup-banner
     (:name server) (:version server)
     :stdio (count @*commands) "Commands"
     (map (fn [[k v]] {:name (name k)}) @*commands))

    (sugar/start-transport! handler
                            {:type :stdio
                             :transport-atom *transport
                             :running-atom *running?})

    (platform/eprintln "DAP server ready.")
    server))

(defn run!
  "Run the DAP server (simple API).

  (run!)                              ; stdio (standard for DAP)
  (run! {:backend :flowstorm})        ; with FlowStorm backend"
  ([] (run! {}))
  ([opts]
   (let [backend (or (:backend opts) :repl)
         backend-opts (or (:backend-opts opts) {})
         srv (or @*server (server "defport-dap" "1.0.0"
                                  {:backend backend
                                   :backend-opts backend-opts}))
         _ (build! srv)
         handler (get-in @(:options srv) [:handler])]

     (sugar/print-startup-banner
      (:name srv) (:version srv)
      :stdio (count @*commands) "Commands"
      (map (fn [[k v]] {:name (name k)}) @*commands))

     (sugar/start-transport! handler
                             {:type :stdio
                              :transport-atom *transport
                              :running-atom *running?})

     (platform/eprintln "DAP server ready.")
     #?(:clj @(promise) :cljs nil))))

(defn stop!
  "Stop the server."
  []
  (sugar/stop-transport! {:transport-atom *transport
                          :running-atom *running?})
  (reset! *commands {})
  (reset! *event-handlers {})
  (reset! *server nil))

;; ============================================================================
;; Cross-Protocol Registry Integration
;; ============================================================================

(defn expose-port!
  "Expose a registered port as a DAP command.

   Takes a port ID from the global registry and exposes it as a DAP command.
   Allows sharing business logic across protocols.

   Options:
   - :as - Expose with a different name (keyword)

   Example:
     ;; First register in your app:
     (defport/register-port! {:id :safe-eval
                              :handler safe-eval
                              :schema [:map [:code :string]]
                              :description \"Safe evaluation\"})

     ;; Then expose as DAP command:
     (expose-port! :safe-eval)

     ;; Or with a different name:
     (expose-port! :safe-eval :as :evaluate)

   Returns the command keyword or throws if port not found."
  [port-id & {:keys [as]}]
  (if-let [port (core/get-registered-port port-id)]
    (let [command-name (or as port-id)
          ;; Wrap handler to adapt from DAP args format
          wrapped-handler (fn [args context]
                            (let [result ((:handler port) {:params args
                                                           :context context
                                                           :protocol :dap})]
                              ;; Convert to DAP response format
                              (cond
                                (map? result) result
                                (nil? result) {}
                                :else {:result (dap-impl/truncate-str (pr-str result) 10000)
                                       :type (dap-impl/type-name result)
                                       :variablesReference 0})))]
      (swap! *commands assoc command-name
             {:handler wrapped-handler
              :doc (:description port)
              :params []
              :_port-id port-id})
      command-name)
    (throw (ex-info (str "Port not found in registry: " port-id)
                    {:port-id port-id
                     :available (core/list-registered-ports)}))))

(defn expose-all-ports!
  "Expose all registered ports as DAP commands.

   Optionally filter by predicate.

   Example:
     ;; Expose all ports
     (expose-all-ports!)

     ;; Expose only ports with :dap in their protocols metadata
     (expose-all-ports! #(contains? (get-in % [:metadata :protocols]) :dap))"
  ([]
   (expose-all-ports! (constantly true)))
  ([pred]
   (doseq [port (core/list-registered-port-defs)
           :when (pred port)]
     (expose-port! (:id port)))))

;; ============================================================================
;; Introspection
;; ============================================================================

(defn list-commands
  "List all registered commands."
  []
  (mapv (fn [[k v]]
          {:id k
           :name (name k)
           :description (:doc v)})
        @*commands))

(defn server-info
  "Get server info."
  []
  (sugar/make-server-info *server *running?
                          (fn [srv]
                            {:commands (count @*commands)
                             :backend (get-in @(:options srv) [:backend] :repl)})))

(defn running?
  "Check if server is running."
  []
  @*running?)

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn capabilities
  "Get current server capabilities based on backend."
  []
  (when-let [srv @*server]
    (dap-impl/compute-capabilities nil (get-in @(:options srv) [:backend] :repl))))

(defn session-state
  "Get current debug session state."
  []
  (when-let [srv @*server]
    (dap-impl/get-adapter-state (:adapter srv))))

;; ============================================================================
;; Client Mode (Level 4) — not included
;; ============================================================================
;;
;; Defport does not ship a subprocess DAP client. Spawning external
;; debug adapters and wiring them to stdio is application concern, not
;; library concern.
;;
;; If your application needs to drive an external DAP adapter, spawn
;; the subprocess yourself (ProcessBuilder / babashka.process / Node's
;; child_process) and use the cross-platform defport.dap protocol
;; helpers (make-event, make-response, etc.) to frame messages.