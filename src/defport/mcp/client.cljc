(ns defport.mcp.client
  "Protocol-free MCP client core.

  Parallels defport.lsp.client. Speaks JSON-RPC 2.0 over a pluggable
  ClientTransport. Reads every wire method name from defport.mcp.spec.
  Ships zero transport implementations here — reference subprocess
  transports live in defport.mcp.client.transports.subprocess and
  consumers who want their own plug in a ClientTransport.

  ## Why an MCP client?
  The same consumer code that writes `(deftool search [...])` and
  exposes a server can now ALSO consume an external MCP server:
  spawn `server-everything` or `server-filesystem`, call
  `(tools/list client)`, surface its tools to the user. Defnet's
  long-term vision includes acting as a router / bridge in front of
  other MCP servers — this client core is the substrate that enables
  it.

  ## Usage
  ```clojure
  (require '[defport.mcp.client :as mcp]
           '[defport.mcp.client.transports.subprocess :as sub])

  (def client
    (-> (sub/transport [\"mcp-server-filesystem\" \"/path/to/root\"])
        (mcp/create-client)
        (mcp/connect! {})))

  @(mcp/list-tools client)
  @(mcp/call-tool client \"read_file\" {:path \"README.md\"})
  ```"
  (:require [defport.mcp.spec :as spec]
            [defport.util.platform :as platform :include-macros true]))

;; ============================================================================
;; Transport protocol
;; ============================================================================

(defprotocol ClientTransport
  "Bidirectional framed JSON-RPC channel for an MCP client. Same
   contract as defport.lsp.client/ClientTransport — separated into
   its own protocol so MCP transports can have independent reference
   implementations and so a single concrete transport doesn't have
   to claim it speaks every defport protocol."
  (transport-start! [this])
  (transport-send! [this message])
  (transport-recv! [this])
  (transport-stop! [this])
  (transport-alive? [this]))

;; ============================================================================
;; Pending requests
;; ============================================================================

(defrecord Pending [id state*])

(defn then
  "Register a callback `(fn [result error])` to fire when the pending
   resolves. Returns the pending."
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
  "Resolve a Pending.

   On JVM: blocks until resolved, returns [result error].
   On CLJS: returns a js/Promise that resolves to [result error]
   or rejects with the error map. Node cannot block the event
   loop — consumers use `.then` / `async/await` at the call site."
  [^Pending pending]
  #?(:clj
     (let [done (promise)]
       (then pending (fn [r e] (deliver done [r e])))
       @done)
     :cljs
     (js/Promise.
       (fn [resolve reject]
         (then pending
               (fn [r e]
                 (if e
                   (reject (clj->js e))
                   (resolve #js [(clj->js r) nil]))))))))

(defn- resolve-pending! [^Pending pending result error]
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
  ;;   {:next-id             int
  ;;    :pending             {id Pending}
  ;;    :notification-handlers {method (fn [params])}
  ;;    :initialized?        bool
  ;;    :server-capabilities ... (what the server reported in initialize response)
  ;;    :server-info         ...
  ;;    :protocol-version    ... (MCP protocol version agreed on)
  ;;   }
  )

(defn create-client
  "Wrap a transport in a Client. Transport is NOT started yet — call
   `connect!` (JVM) or `connect-async!` (any platform)."
  [transport]
  (->Client transport
            (atom {:next-id 1
                   :pending {}
                   :notification-handlers {}
                   :initialized? false
                   :server-capabilities nil
                   :server-info nil
                   :protocol-version nil})))

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
  "Register a handler for an inbound notification method. Returns the client."
  [^Client client method handler-fn]
  (swap! (:state* client) assoc-in [:notification-handlers method] handler-fn)
  client)

(defn off-notification
  [^Client client method]
  (swap! (:state* client) update :notification-handlers dissoc method)
  client)

;; ============================================================================
;; Send / receive
;; ============================================================================

(defn notify!
  "Send a notification. Returns the client."
  [^Client client method params]
  (transport-send! (:transport client)
                   {:jsonrpc "2.0"
                    :method method
                    :params params})
  client)

(defn request!
  "Send a JSON-RPC request. Returns a Pending."
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
  "Route one inbound message to the right place: response → pending,
   notification → handler, server-initiated request → tap log.

   Checks for :result / :error key presence (not value truthiness)
   so responses carrying a nil body still resolve their pending."
  [^Client client message]
  (let [{:keys [id method params]} message
        has-result? (contains? message :result)
        has-error?  (contains? message :error)]
    (cond
      (and id (or has-result? has-error?) (not method))
      (when-let [p (forget-pending! client id)]
        (resolve-pending! p (:result message) (:error message)))

      (and method id)
      (do
        (tap> {:event :mcp.client/server-initiated-request-not-implemented
               :method method :id id})
        nil)

      method
      (when-let [handler (get-in @(:state* client) [:notification-handlers method])]
        (handler params))

      :else
      (tap> {:event :mcp.client/unrecognized-message :message message}))))

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
       (.setName t "defport.mcp.client/reader")
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

(defn connect-async!
  "Start the transport, drive MCP initialize, invoke (callback client
   error). Cross-platform.

   Opts:
     :client-info         {:name ... :version ...}
     :protocol-version    default \"2025-11-25\"
     :capabilities        client capabilities map"
  [^Client client opts callback]
  (transport-start! (:transport client))
  (start-driver! client)
  (let [params {:protocolVersion (or (:protocol-version opts) "2025-11-25")
                :clientInfo (or (:client-info opts)
                                {:name "defport-mcp-client" :version "0.1.0"})
                :capabilities (or (:capabilities opts) {})}]
    (then (request! client (spec/wire-method :initialize) params)
          (fn [result error]
            (if error
              (callback nil error)
              (do
                (swap! (:state* client) assoc
                       :initialized? true
                       :server-capabilities (:capabilities result)
                       :server-info (:serverInfo result)
                       :protocol-version (:protocolVersion result))
                (notify! client (spec/wire-method :notifications/initialized) {})
                (callback client nil)))))
    nil))

(defn connect!
  "JVM-only blocking connect. Returns the client or throws.
   Timeout defaults to 30s — enough for a cold JVM subprocess to
   boot. Override with `:connect-timeout-ms` in opts if you need
   a tighter bound."
  [^Client client opts]
  #?(:clj
     (let [done (promise)
           timeout-ms (or (:connect-timeout-ms opts) 30000)]
       (connect-async! client opts (fn [c err] (deliver done [c err])))
       (let [[c err] (deref done timeout-ms [::timeout nil])]
         (cond
           (= ::timeout c) (throw (ex-info "MCP initialize timed out"
                                           {:timeout-ms timeout-ms}))
           err             (throw (ex-info "MCP initialize failed" {:error err}))
           :else           c)))
     :cljs
     (throw (ex-info "mcp.client/connect! is JVM-only — use connect-async!"
                     {:platform :cljs}))))

(defn disconnect!
  "Stop the transport. MCP has no shutdown request — the wire closes."
  [^Client client]
  (transport-stop! (:transport client))
  nil)

;; ============================================================================
;; Typed convenience helpers — all read wire methods from spec
;; ============================================================================

(defn list-tools
  "Request the server's tool list. Returns Pending with the
   `{:tools [...]}` body on resolve."
  [^Client client]
  (request! client (spec/wire-method :tools/list) {}))

(defn call-tool
  "Invoke a tool by name with arguments. Returns Pending with the
   `{:content [...] :isError false}` body on resolve."
  [^Client client tool-name arguments]
  (request! client (spec/wire-method :tools/call)
            {:name tool-name :arguments (or arguments {})}))

(defn list-prompts
  [^Client client]
  (request! client (spec/wire-method :prompts/list) {}))

(defn get-prompt
  [^Client client prompt-name arguments]
  (request! client (spec/wire-method :prompts/get)
            {:name prompt-name :arguments (or arguments {})}))

(defn list-resources
  [^Client client]
  (request! client (spec/wire-method :resources/list) {}))

(defn read-resource
  [^Client client uri]
  (request! client (spec/wire-method :resources/read) {:uri uri}))

(defn subscribe-resource!
  [^Client client uri]
  (request! client (spec/wire-method :resources/subscribe) {:uri uri}))

(defn unsubscribe-resource!
  [^Client client uri]
  (request! client (spec/wire-method :resources/unsubscribe) {:uri uri}))

(defn complete
  "Ask the server for completions on an in-progress prompt/tool argument."
  [^Client client ref argument-name argument-value]
  (request! client (spec/wire-method :completion/complete)
            {:ref ref :argument {:name argument-name :value argument-value}}))

(defn set-log-level!
  [^Client client level]
  (request! client (spec/wire-method :logging/setLevel) {:level level}))

(defn ping
  [^Client client]
  (request! client (spec/wire-method :ping) {}))
