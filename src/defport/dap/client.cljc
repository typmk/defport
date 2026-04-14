(ns defport.dap.client
  "Protocol-free DAP client core.

  Symmetric to defport.lsp.client but speaks the DAP wire shape:

  - Messages have a `type` discriminator: \"request\" | \"response\" | \"event\"
  - Requests carry `seq` (allocated monotonically), `command`, `arguments`
  - Responses carry `request_seq`, `command`, `success`, `body` (or
    `message` on failure)
  - Events carry `event` and `body`

  Same async story as the LSP client: `request!` returns a `Pending`
  the caller can `then` (callback) or `await` (JVM block). Same
  pluggable transport story: `ClientTransport` is a small protocol;
  reference subprocess transports live in optional sibling
  namespaces. Same spec-driven helpers: every `continue!`,
  `step-in!`, `evaluate!` reads its wire command name from
  defport.dap.spec.

  See defport.lsp.client for the design rationale; this namespace
  is the DAP equivalent."
  (:require [defport.dap.spec :as spec]
            [defport.util.platform :as platform :include-macros true]))

;; ============================================================================
;; Transport protocol
;; ============================================================================

(defprotocol ClientTransport
  "Bidirectional framed JSON-RPC channel for a DAP client.

   Same contract as defport.lsp.client/ClientTransport — separated
   into its own protocol so DAP and LSP transports can have
   independent reference implementations and so a single concrete
   transport doesn't have to claim it speaks both wire formats."
  (transport-start! [this]
    "Start the transport. Returns this. Throws on failure.")
  (transport-send! [this message]
    "Send a clj map as a framed DAP message. Returns this.")
  (transport-recv! [this]
    "Return the next inbound clj map, ::no-message if none ready, or ::eof.")
  (transport-stop! [this]
    "Stop the transport.")
  (transport-alive? [this]
    "True iff the channel is open."))

;; ============================================================================
;; Pending requests
;; ============================================================================

(defrecord Pending [seq state*])

(defn then
  "Register a callback to fire when the pending resolves.
   `(then p (fn [body error] ...))`. Returns the pending."
  [^Pending pending callback]
  (let [now (swap! (:state* pending)
                   (fn [s]
                     (if (= :pending (:status s))
                       (update s :callbacks conj callback)
                       s)))]
    (when (not= :pending (:status now))
      (callback (:body now) (:error now))))
  pending)

(defn await
  "Block until the pending resolves and return [body error]. JVM-only."
  [^Pending pending]
  #?(:clj
     (let [done (promise)]
       (then pending (fn [b e] (deliver done [b e])))
       @done)
     :cljs
     (throw (ex-info
              "dap.client/await is JVM-only — Node cannot block. Use `then` with a callback."
              {:platform :cljs}))))

(defn- resolve-pending! [^Pending pending body error]
  (let [old (swap-vals! (:state* pending)
                        (fn [s]
                          (if (= :pending (:status s))
                            (assoc s
                                   :status (if error :error :done)
                                   :body body
                                   :error error)
                            s)))
        prev (first old)]
    (when (= :pending (:status prev))
      (doseq [cb (:callbacks prev)]
        (cb body error))))
  pending)

;; ============================================================================
;; Client value
;; ============================================================================

(defrecord Client [transport state*]
  ;; state* holds:
  ;;   {:next-seq           int — DAP seq allocator
  ;;    :pending            {seq Pending}
  ;;    :event-handlers     {event-name (fn [body])}
  ;;    :initialized?       bool
  ;;    :adapter-capabilities {} — what the adapter reported
  ;;    :launched?          bool
  ;;   }
  )

(defn create-client
  "Create a Client wrapping a transport. Transport is NOT started yet —
   call `connect!` (JVM) or `connect-async!` (any platform)."
  [transport]
  (->Client transport
            (atom {:next-seq 1
                   :pending {}
                   :event-handlers {}
                   :initialized? false
                   :adapter-capabilities nil
                   :launched? false})))

(defn- next-seq! [^Client client]
  (let [[old _] (swap-vals! (:state* client) update :next-seq inc)]
    (:next-seq old)))

(defn- register-pending! [^Client client seq]
  (let [p (->Pending seq (atom {:status :pending :callbacks []}))]
    (swap! (:state* client) assoc-in [:pending seq] p)
    p))

(defn- forget-pending! [^Client client seq]
  (let [s (swap-vals! (:state* client) update :pending dissoc seq)]
    (get-in (first s) [:pending seq])))

;; ============================================================================
;; Event handlers
;; ============================================================================

(defn on-event
  "Register a handler `(fn [body])` for an inbound DAP event by event
   name string (e.g. \"stopped\", \"output\"). Returns the client."
  [^Client client event-name handler-fn]
  (swap! (:state* client) assoc-in [:event-handlers event-name] handler-fn)
  client)

(defn off-event
  [^Client client event-name]
  (swap! (:state* client) update :event-handlers dissoc event-name)
  client)

;; ============================================================================
;; Send / receive
;; ============================================================================

(defn request!
  "Send a DAP request. Returns a Pending."
  [^Client client command arguments]
  (let [seq (next-seq! client)
        p   (register-pending! client seq)]
    (transport-send! (:transport client)
                     {:seq seq
                      :type "request"
                      :command command
                      :arguments (or arguments {})})
    p))

(defn dispatch-incoming!
  "Route a single inbound DAP message:
   - type=response → resolve the matching pending by request_seq
   - type=event    → fire the registered event handler by event name
   - type=request  → server-initiated request (runInTerminal,
                     startDebugging); not yet implemented, tap and ignore."
  [^Client client message]
  (let [{:keys [type request_seq success body event command]} message]
    (case type
      "response"
      (when-let [p (forget-pending! client request_seq)]
        (if success
          (resolve-pending! p body nil)
          (resolve-pending! p nil {:command command :message (:message message)})))

      "event"
      (when-let [handler (get-in @(:state* client) [:event-handlers event])]
        (handler body))

      "request"
      (do
        (tap> {:event :dap.client/server-initiated-request-not-implemented
               :command command :seq (:seq message)})
        nil)

      (tap> {:event :dap.client/unrecognized-message :message message}))))

;; ============================================================================
;; Driver loop (platform-specific glue)
;; ============================================================================

#?(:clj
   (defn- start-jvm-reader-thread! [^Client client]
     (let [t (Thread.
               ^Runnable
               (fn []
                 (loop []
                   (when (transport-alive? (:transport client))
                     (let [msg (transport-recv! (:transport client))]
                       (cond
                         (= ::eof msg)        nil
                         (= ::no-message msg) (do (Thread/sleep 5) (recur))
                         :else                (do (dispatch-incoming! client msg)
                                                  (recur))))))))]
       (.setDaemon t true)
       (.setName t "defport.dap.client/reader")
       (.start t)
       t)))

#?(:cljs
   (defn- start-cljs-poll-loop! [^Client client]
     (let [tick (fn tick []
                  (when (transport-alive? (:transport client))
                    (let [msg (transport-recv! (:transport client))]
                      (cond
                        (= ::eof msg)        nil
                        (= ::no-message msg) (js/setImmediate tick)
                        :else                (do (dispatch-incoming! client msg)
                                                 (js/setImmediate tick))))))]
       (js/setImmediate tick)
       nil)))

(defn- start-driver! [client]
  #?(:clj  (start-jvm-reader-thread! client)
     :cljs (start-cljs-poll-loop! client)))

;; ============================================================================
;; Connect / launch / disconnect
;; ============================================================================
;;
;; DAP's startup is a multi-step dance. The client must:
;;   1. Send `initialize` and wait for the response (capabilities)
;;   2. Send either `launch` or `attach` (the adapter starts the
;;      debuggee or connects to it)
;;   3. Wait for the `initialized` EVENT from the adapter
;;   4. Send `setBreakpoints`, `setFunctionBreakpoints`, etc. as
;;      configured
;;   5. Send `configurationDone`
;;
;; defport.dap.client ships connect-and-launch! / connect-and-attach!
;; that drive steps 1-5 sequentially. Consumers who need finer control
;; can call request! directly with the right command names.

(defn connect-async!
  "Cross-platform: start the transport, drive `initialize`, callback
   with [client capabilities] or [nil error]. Does NOT launch or
   attach — caller does that with launch! or attach!."
  [^Client client opts callback]
  (transport-start! (:transport client))
  (start-driver! client)
  (let [args (merge {:clientID "defport-dap-client"
                     :clientName "defport"
                     :adapterID (:adapter-id opts "defport")
                     :pathFormat "path"
                     :linesStartAt1 true
                     :columnsStartAt1 true
                     :supportsRunInTerminalRequest false
                     :supportsStartDebuggingRequest false
                     :supportsVariableType true
                     :supportsVariablePaging false
                     :supportsMemoryReferences false
                     :supportsProgressReporting false
                     :supportsInvalidatedEvent false}
                    (:initialize opts))]
    (then (request! client (spec/wire-command :initialize) args)
          (fn [body error]
            (if error
              (callback nil error)
              (do
                (swap! (:state* client) assoc
                       :initialized? true
                       :adapter-capabilities body)
                (callback client nil)))))
    nil))

(defn connect!
  "JVM-only: start, initialize, return the client. Throws on failure."
  [^Client client opts]
  #?(:clj
     (let [done (promise)]
       (connect-async! client opts (fn [c err] (deliver done [c err])))
       (let [[c err] (deref done 5000 [::timeout nil])]
         (cond
           (= ::timeout c) (throw (ex-info "DAP initialize timed out" {}))
           err             (throw (ex-info "DAP initialize failed" {:error err}))
           :else           c)))
     :cljs
     (throw (ex-info "dap.client/connect! is JVM-only — use connect-async!"
                     {:platform :cljs}))))

(defn launch!
  "Send `launch` with the supplied launch arguments. Returns Pending."
  [^Client client launch-args]
  (request! client (spec/wire-command :launch) launch-args))

(defn attach!
  "Send `attach` with the supplied attach arguments. Returns Pending."
  [^Client client attach-args]
  (request! client (spec/wire-command :attach) attach-args))

(defn configuration-done!
  "Send `configurationDone`. Returns Pending."
  [^Client client]
  (request! client (spec/wire-command :configuration-done) {}))

(defn disconnect!
  "Send `disconnect` and stop the transport. Returns nil."
  [^Client client]
  (try
    #?(:clj (await (request! client (spec/wire-command :disconnect) {}))
       :cljs (request! client (spec/wire-command :disconnect) {}))
    (catch #?(:clj Exception :cljs js/Error) _e nil))
  (transport-stop! (:transport client))
  nil)

;; ============================================================================
;; Typed convenience helpers — all read wire commands from spec
;; ============================================================================

;; Stepping
(defn continue! [^Client client thread-id]
  (request! client (spec/wire-command :continue) {:threadId thread-id}))
(defn next! [^Client client thread-id]
  (request! client (spec/wire-command :next) {:threadId thread-id}))
(defn step-in! [^Client client thread-id]
  (request! client (spec/wire-command :step-in) {:threadId thread-id}))
(defn step-out! [^Client client thread-id]
  (request! client (spec/wire-command :step-out) {:threadId thread-id}))
(defn step-back! [^Client client thread-id]
  (request! client (spec/wire-command :step-back) {:threadId thread-id}))
(defn reverse-continue! [^Client client thread-id]
  (request! client (spec/wire-command :reverse-continue) {:threadId thread-id}))
(defn pause! [^Client client thread-id]
  (request! client (spec/wire-command :pause) {:threadId thread-id}))
(defn restart-frame! [^Client client frame-id]
  (request! client (spec/wire-command :restart-frame) {:frameId frame-id}))

;; Stack / scope / variables
(defn threads [^Client client]
  (request! client (spec/wire-command :threads) {}))
(defn stack-trace
  ([client thread-id] (stack-trace client thread-id 0 20))
  ([^Client client thread-id start levels]
   (request! client (spec/wire-command :stack-trace)
             {:threadId thread-id :startFrame start :levels levels})))
(defn scopes [^Client client frame-id]
  (request! client (spec/wire-command :scopes) {:frameId frame-id}))
(defn variables [^Client client variables-reference]
  (request! client (spec/wire-command :variables)
            {:variablesReference variables-reference}))
(defn set-variable! [^Client client variables-reference name value]
  (request! client (spec/wire-command :set-variable)
            {:variablesReference variables-reference :name name :value value}))
(defn evaluate
  ([client expression] (evaluate client expression nil "watch"))
  ([^Client client expression frame-id context]
   (request! client (spec/wire-command :evaluate)
             (cond-> {:expression expression :context context}
               frame-id (assoc :frameId frame-id)))))
(defn completions [^Client client text column & {:keys [frame-id line]}]
  (request! client (spec/wire-command :completions)
            (cond-> {:text text :column column}
              frame-id (assoc :frameId frame-id)
              line     (assoc :line line))))

;; Breakpoints
(defn set-breakpoints! [^Client client source breakpoints]
  (request! client (spec/wire-command :set-breakpoints)
            {:source source :breakpoints breakpoints}))
(defn set-function-breakpoints! [^Client client breakpoints]
  (request! client (spec/wire-command :set-function-breakpoints)
            {:breakpoints breakpoints}))
(defn set-exception-breakpoints! [^Client client filters]
  (request! client (spec/wire-command :set-exception-breakpoints)
            {:filters filters}))
(defn set-data-breakpoints! [^Client client breakpoints]
  (request! client (spec/wire-command :set-data-breakpoints)
            {:breakpoints breakpoints}))

;; Source / modules / memory
(defn source [^Client client source-reference]
  (request! client (spec/wire-command :source)
            {:sourceReference source-reference}))
(defn loaded-sources [^Client client]
  (request! client (spec/wire-command :loaded-sources) {}))
(defn modules
  ([client] (modules client 0 100))
  ([^Client client start-module module-count]
   (request! client (spec/wire-command :modules)
             {:startModule start-module :moduleCount module-count})))
(defn read-memory [^Client client memory-reference offset count]
  (request! client (spec/wire-command :read-memory)
            {:memoryReference memory-reference :offset offset :count count}))
(defn disassemble [^Client client memory-reference instruction-count]
  (request! client (spec/wire-command :disassemble)
            {:memoryReference memory-reference
             :instructionCount instruction-count}))
