(ns defport.lsp.client
  "Protocol-free LSP client core.

  This namespace handles everything that doesn't depend on a specific
  platform: request/response correlation by id, notification dispatch,
  the initialize/initialized handshake, typed convenience helpers
  (hover-at, definition-at, references-at, rename-at, ...) that read
  their wire method strings from defport.lsp.spec.

  The platform-specific 20% — spawning a subprocess, reading framed
  bytes off stdio, writing them back — lives behind the
  `ClientTransport` protocol below. Defport ships zero implementations
  in this namespace; reference subprocess transports (JVM
  ProcessBuilder, Node child_process) live in optional sibling
  namespaces that consumers require explicitly. See CLAUDE.md
  principle 5 (pluggable platform primitives) for the rationale.

  ## Synchronous vs asynchronous
  `request!` returns a `Pending` record carrying the request id and
  a callback table. Use `(await pending)` for a blocking deref (JVM
  only — throws on CLJS, where Node cannot block the event loop).
  Use `(then pending callback)` for callback-style on either platform.
  Defport itself stays neutral about which async lib you use — bring
  promesa, manifold, core.async, raw thread, whatever you like."
  (:require [defport.lsp.spec :as spec]
            [defport.util.platform :as platform :include-macros true]))

;; ============================================================================
;; Transport protocol
;; ============================================================================

(defprotocol ClientTransport
  "Bidirectional framed JSON-RPC channel for an LSP client.

   Implementations bridge defport's protocol-free client logic to a
   platform-specific channel: subprocess stdio, TCP socket,
   WebSocket, in-process pipe. Reference implementations live in
   defport.lsp.client.transports.* — consumers may write their own.

   The contract is synchronous from defport's perspective:

   - transport-start! runs the platform side (spawn / connect / fork)
     and returns when the channel is ready to send/recv.
   - transport-send! is fire-and-forget. Implementations are
     responsible for thread safety on the underlying channel.
   - transport-recv! blocks (JVM) or polls (CLJS) for the next
     incoming message. Returns a clj map or ::eof. CLJS implementations
     must avoid blocking the event loop — they typically schedule a
     callback that pushes into a buffer and return ::no-message when
     the buffer is empty. The driver loop checks ::no-message and
     reschedules itself."
  (transport-start! [this]
    "Start the transport. Spawn subprocess, open socket, etc.
     Returns this for chaining. Throws on failure.")
  (transport-send! [this message]
    "Send a clj map as a framed JSON-RPC message. Encoding (Content-Length
     headers + JSON body) is the transport's job. Returns this.")
  (transport-recv! [this]
    "Return the next incoming clj map, ::no-message if none ready
     (CLJS), or ::eof when the channel closes.")
  (transport-stop! [this]
    "Stop the transport, releasing all resources. Idempotent.")
  (transport-alive? [this]
    "True iff the channel is open and usable."))

;; ============================================================================
;; Pending requests
;; ============================================================================

(defrecord Pending [id state*]
  ;; state* holds {:status :pending|:done|:error :result ... :error ... :callbacks []}
  )

(defn- pending? [x] (instance? Pending x))

(defn then
  "Register a callback to fire when the pending request resolves.
   `(then p (fn [result error] ...))`. Returns the pending so calls
   can chain. Cross-platform — works on JVM and CLJS."
  [^Pending pending callback]
  (let [now (swap! (:state* pending)
                   (fn [s]
                     (if (= :pending (:status s))
                       (update s :callbacks conj callback)
                       s)))]
    (when (not= :pending (:status now))
      (callback (:result now) (:error now))))
  pending)

(defn await
  "Block until the pending request resolves and return [result error].
   JVM-only — on CLJS this throws because Node cannot block.

   For the common case use the typed convenience helpers
   (hover-at, definition-at, ...) which return [result error]
   directly on JVM and a Pending on CLJS so consumers pick their own
   async strategy."
  [^Pending pending]
  #?(:clj
     (let [done   (promise)]
       (then pending (fn [r e] (deliver done [r e])))
       @done)
     :cljs
     (throw (ex-info
              "lsp.client/await is JVM-only — Node cannot block the event loop. Use `then` with a callback, or wrap the Pending in your own async lib."
              {:platform :cljs}))))

(defn- resolve-pending!
  "Internal: mark a pending as done with a result or error and fire
   all registered callbacks."
  [^Pending pending result error]
  (let [old (swap-vals! (:state* pending)
                        (fn [s]
                          (if (= :pending (:status s))
                            (assoc s
                                   :status (if error :error :done)
                                   :result result
                                   :error error)
                            s)))
        prev (first old)]
    (when (= :pending (:status prev))
      (doseq [cb (:callbacks prev)]
        (cb result error))))
  pending)

;; ============================================================================
;; Client value
;; ============================================================================

(defrecord Client [transport state*]
  ;; state* holds:
  ;;   {:next-id           int — monotonic request id allocator
  ;;    :pending           {id Pending}     — in-flight requests
  ;;    :notification-handlers {method (fn [params])}
  ;;    :initialized?      bool
  ;;    :server-capabilities ...
  ;;    :server-info       ...}
  )

(defn create-client
  "Create a Client value wrapping a transport. The transport is NOT
   started yet — call `connect!` to start it and run the initialize
   handshake."
  [transport]
  (->Client transport
            (atom {:next-id 1
                   :pending {}
                   :notification-handlers {}
                   :initialized? false
                   :server-capabilities nil
                   :server-info nil})))

(defn- next-id! [^Client client]
  (let [[old _] (swap-vals! (:state* client) update :next-id inc)]
    (:next-id old)))

(defn- register-pending! [^Client client id]
  (let [p (->Pending id (atom {:status :pending :callbacks []}))]
    (swap! (:state* client) assoc-in [:pending id] p)
    p))

(defn- forget-pending! [^Client client id]
  (let [s (swap-vals! (:state* client) update :pending dissoc id)]
    (get-in (first s) [:pending id])))

;; ============================================================================
;; Notifications
;; ============================================================================

(defn on-notification
  "Register a handler `(fn [params])` for an inbound LSP notification
   method (e.g. \"textDocument/publishDiagnostics\", \"$/progress\").
   Returns the client for chaining."
  [^Client client method handler-fn]
  (swap! (:state* client) assoc-in [:notification-handlers method] handler-fn)
  client)

(defn off-notification
  "Remove a previously registered notification handler."
  [^Client client method]
  (swap! (:state* client) update :notification-handlers dissoc method)
  client)

;; ============================================================================
;; Send / receive
;; ============================================================================

(defn notify!
  "Send a notification. Fire and forget. Returns the client."
  [^Client client method params]
  (transport-send! (:transport client)
                   {:jsonrpc "2.0"
                    :method method
                    :params params})
  client)

(defn request!
  "Send a request. Returns a Pending you can `then` (callback) or
   `await` (JVM block)."
  [^Client client method params]
  (let [id (next-id! client)
        p  (register-pending! client id)]
    (transport-send! (:transport client)
                     {:jsonrpc "2.0"
                      :id id
                      :method method
                      :params params})
    p))

(defn dispatch-incoming!
  "Route a single inbound message to the right place:
   - Has :id and :result / :error → resolve the matching pending
   - Has :method and no :id → fire the notification handler
   - Has :method and :id → server-initiated request (rare); not yet
     implemented, logged via tap and ignored.

   Driver loops call this for every message they read off the
   transport. Pure dispatch — no I/O."
  [^Client client message]
  (let [{:keys [id method result error params]} message]
    (cond
      ;; Response to one of our requests
      (and id (or result error) (not method))
      (when-let [p (forget-pending! client id)]
        (resolve-pending! p result error))

      ;; Server-initiated request (workspace/applyEdit, window/showMessageRequest)
      (and method id)
      (do
        (tap> {:event :lsp.client/server-initiated-request-not-implemented
               :method method :id id})
        nil)

      ;; Notification
      method
      (when-let [handler (get-in @(:state* client) [:notification-handlers method])]
        (handler params))

      :else
      (tap> {:event :lsp.client/unrecognized-message :message message}))))

;; ============================================================================
;; Driver loop (platform-specific glue)
;; ============================================================================
;;
;; The driver pulls messages off the transport and dispatches them.
;; JVM uses a dedicated reader thread; CLJS schedules itself via the
;; event loop so it doesn't block. Both paths call dispatch-incoming!
;; — that's the cross-platform part.

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
       (.setName t "defport.lsp.client/reader")
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
;; Connect / disconnect
;; ============================================================================

(defn connect!
  "Start the transport, drive the initialize/initialized handshake,
   and return the connected client.

   On JVM the initialize response is awaited inline (the call
   blocks). On CLJS use connect-async! instead.

   Opts:
     :client-info         {:name ... :version ...}  (default sensible)
     :root-uri            \"file:///...\"            (optional)
     :workspace-folders   [...]
     :capabilities        client capabilities map (default sensible)
     :initialization-opts arbitrary backend-specific options"
  [^Client client opts]
  (transport-start! (:transport client))
  (start-driver! client)
  #?(:clj
     (let [params {:processId nil
                   :clientInfo (or (:client-info opts)
                                   {:name "defport-lsp-client" :version "0.1.0"})
                   :rootUri (:root-uri opts)
                   :workspaceFolders (:workspace-folders opts)
                   :capabilities (or (:capabilities opts) {})
                   :initializationOptions (:initialization-opts opts)}
           [result error] (await (request! client (spec/wire-method :initialize) params))]
       (if error
         (throw (ex-info "LSP initialize failed" {:error error}))
         (do
           (swap! (:state* client) assoc
                  :initialized? true
                  :server-capabilities (:capabilities result)
                  :server-info (:serverInfo result))
           (notify! client (spec/wire-method :initialized) {})
           client)))
     :cljs
     (throw (ex-info
              "lsp.client/connect! is JVM-only — use connect-async! on CLJS"
              {:platform :cljs}))))

(defn connect-async!
  "Cross-platform connect. Starts the transport, kicks off initialize,
   and invokes (callback client error) once the handshake completes.

   On CLJS this is the only way to connect. On JVM you can also use
   the simpler `connect!`."
  [^Client client opts callback]
  (transport-start! (:transport client))
  (start-driver! client)
  (let [params {:processId nil
                :clientInfo (or (:client-info opts)
                                {:name "defport-lsp-client" :version "0.1.0"})
                :rootUri (:root-uri opts)
                :workspaceFolders (:workspace-folders opts)
                :capabilities (or (:capabilities opts) {})
                :initializationOptions (:initialization-opts opts)}]
    (then (request! client (spec/wire-method :initialize) params)
          (fn [result error]
            (if error
              (callback nil error)
              (do
                (swap! (:state* client) assoc
                       :initialized? true
                       :server-capabilities (:capabilities result)
                       :server-info (:serverInfo result))
                (notify! client (spec/wire-method :initialized) {})
                (callback client nil)))))
    nil))

(defn disconnect!
  "Send shutdown + exit and stop the transport. Returns nil."
  [^Client client]
  (try
    #?(:clj (await (request! client (spec/wire-method :shutdown) nil))
       :cljs (request! client (spec/wire-method :shutdown) nil))
    (notify! client (spec/wire-method :exit) {})
    (catch #?(:clj Exception :cljs js/Error) _e nil))
  (transport-stop! (:transport client))
  nil)

;; ============================================================================
;; Typed convenience helpers
;; ============================================================================
;;
;; All read their wire method strings from defport.lsp.spec — single
;; source of truth. Each helper returns a Pending; callers `then` /
;; `await` per their async preference.

(defn hover-at
  "Request hover info at a position. Returns Pending."
  [^Client client uri line character]
  (request! client (spec/wire-method :hover)
            {:textDocument {:uri uri}
             :position {:line line :character character}}))

(defn definition-at
  "Request the definition location of the symbol at a position."
  [^Client client uri line character]
  (request! client (spec/wire-method :definition)
            {:textDocument {:uri uri}
             :position {:line line :character character}}))

(defn declaration-at
  [^Client client uri line character]
  (request! client (spec/wire-method :declaration)
            {:textDocument {:uri uri}
             :position {:line line :character character}}))

(defn type-definition-at
  [^Client client uri line character]
  (request! client (spec/wire-method :type-definition)
            {:textDocument {:uri uri}
             :position {:line line :character character}}))

(defn implementation-at
  [^Client client uri line character]
  (request! client (spec/wire-method :implementation)
            {:textDocument {:uri uri}
             :position {:line line :character character}}))

(defn references-at
  "Find references to the symbol at a position.
   include-declaration? defaults to true."
  ([client uri line character]
   (references-at client uri line character true))
  ([^Client client uri line character include-declaration?]
   (request! client (spec/wire-method :references)
             {:textDocument {:uri uri}
              :position {:line line :character character}
              :context {:includeDeclaration include-declaration?}})))

(defn document-symbols
  "List symbols in a document."
  [^Client client uri]
  (request! client (spec/wire-method :document-symbol)
            {:textDocument {:uri uri}}))

(defn workspace-symbols
  "Search workspace-wide for symbols matching a query."
  [^Client client query]
  (request! client (spec/wire-method :workspace-symbol)
            {:query query}))

(defn rename-at
  "Compute a workspace edit to rename the symbol at a position."
  [^Client client uri line character new-name]
  (request! client (spec/wire-method :rename)
            {:textDocument {:uri uri}
             :position {:line line :character character}
             :newName new-name}))

(defn completion-at
  [^Client client uri line character]
  (request! client (spec/wire-method :completion)
            {:textDocument {:uri uri}
             :position {:line line :character character}}))

(defn signature-help-at
  [^Client client uri line character]
  (request! client (spec/wire-method :signature-help)
            {:textDocument {:uri uri}
             :position {:line line :character character}}))

(defn code-action-at
  "Compute code actions for a range."
  [^Client client uri start-line start-char end-line end-char]
  (request! client (spec/wire-method :code-action)
            {:textDocument {:uri uri}
             :range {:start {:line start-line :character start-char}
                     :end {:line end-line :character end-char}}
             :context {:diagnostics []}}))

(defn format-document
  [^Client client uri]
  (request! client (spec/wire-method :formatting)
            {:textDocument {:uri uri}
             :options {:tabSize 2 :insertSpaces true}}))

;; ============================================================================
;; Document sync notifications (for clients that observe a workspace)
;; ============================================================================

(defn did-open!
  "Notify the server that a document was opened."
  [^Client client uri language-id version text]
  (notify! client (spec/wire-method :did-open)
           {:textDocument {:uri uri
                           :languageId language-id
                           :version version
                           :text text}}))

(defn did-change!
  "Notify the server that a document changed. content-changes is a
   vector of LSP TextDocumentContentChangeEvent maps."
  [^Client client uri version content-changes]
  (notify! client (spec/wire-method :did-change)
           {:textDocument {:uri uri :version version}
            :contentChanges content-changes}))

(defn did-save!
  "Notify the server that a document was saved. text is optional and
   only included if the server's textDocumentSync.save.includeText is
   true."
  ([client uri] (did-save! client uri nil))
  ([^Client client uri text]
   (notify! client (spec/wire-method :did-save)
            (cond-> {:textDocument {:uri uri}}
              text (assoc :text text)))))

(defn did-close!
  "Notify the server that a document was closed."
  [^Client client uri]
  (notify! client (spec/wire-method :did-close)
           {:textDocument {:uri uri}}))

(defn cancel-request!
  "Notify the server to cancel an in-flight request by id."
  [^Client client request-id]
  (notify! client (spec/wire-method :cancel-request)
           {:id request-id}))
