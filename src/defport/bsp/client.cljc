(ns defport.bsp.client
  "Protocol-free BSP client core.

  Parallels defport.lsp.client. BSP speaks JSON-RPC 2.0 over stdio
  just like LSP — same request/response shape, same Content-Length
  framing, same dispatch pattern. Most of this file is structurally
  identical to defport.lsp.client; the only thing that changes is
  the spec it reads from and the typed helper surface.

  Ships no transport implementations. Consumers plug in a
  ClientTransport (the reference subprocess transport at
  defport.bsp.client.transports.subprocess works against any
  stdio-based BSP server: sbt, Mill, Bloop, Bazel's BSP server,
  etc.)."
  (:require [defport.bsp.spec :as spec]
            [defport.util.platform :as platform :include-macros true]))

;; ============================================================================
;; Transport protocol
;; ============================================================================

(defprotocol ClientTransport
  "Bidirectional framed JSON-RPC channel for a BSP client.
   Identical contract to the LSP/DAP/MCP equivalents; we define a
   fresh protocol per client namespace so a concrete transport
   record can choose which clients it serves."
  (transport-start! [this])
  (transport-send! [this message])
  (transport-recv! [this])
  (transport-stop! [this])
  (transport-alive? [this]))

;; ============================================================================
;; Pending / Client records (copied from defport.lsp.client)
;; ============================================================================

(defrecord Pending [id state*])

(defn then
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

(defrecord Client [transport state*])

(defn create-client
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
;; Notifications + send/receive + dispatch (same shape as LSP client)
;; ============================================================================

(defn on-notification
  [^Client client method handler-fn]
  (swap! (:state* client) assoc-in [:notification-handlers method] handler-fn)
  client)

(defn off-notification
  [^Client client method]
  (swap! (:state* client) update :notification-handlers dissoc method)
  client)

(defn notify!
  [^Client client method params]
  (transport-send! (:transport client)
                   {:jsonrpc "2.0" :method method :params params})
  client)

(defn request!
  [^Client client method params]
  (let [id (next-id! client)
        p  (register-pending! client id)]
    (transport-send! (:transport client)
                     {:jsonrpc "2.0" :id id :method method :params params})
    p))

(defn dispatch-incoming!
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
        (tap> {:event :bsp.client/server-initiated-request-not-implemented
               :method method :id id})
        nil)

      method
      (when-let [handler (get-in @(:state* client) [:notification-handlers method])]
        (handler params))

      :else
      (tap> {:event :bsp.client/unrecognized-message :message message}))))

;; ============================================================================
;; Driver loop
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
       (.setName t "defport.bsp.client/reader")
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
  "Start the transport, drive build/initialize, callback with
   [client error]. Cross-platform.

   Opts:
     :display-name      — client display name (default \"defport-bsp-client\")
     :version           — client version (default \"0.1.0\")
     :bsp-version       — BSP protocol version (default \"2.2.0\")
     :root-uri          — workspace root URI (required by most servers)
     :capabilities      — language capabilities map (e.g. {:languageIds [\"clojure\"]})"
  [^Client client opts callback]
  (transport-start! (:transport client))
  (start-driver! client)
  (let [params {:displayName  (or (:display-name opts) "defport-bsp-client")
                :version      (or (:version opts) "0.1.0")
                :bspVersion   (or (:bsp-version opts) "2.2.0")
                :rootUri      (or (:root-uri opts) "file:///")
                :capabilities (or (:capabilities opts)
                                  {:languageIds []})}]
    (then (request! client (spec/wire-method :initialize) params)
          (fn [result error]
            (if error
              (callback nil error)
              (do
                (swap! (:state* client) assoc
                       :initialized? true
                       :server-capabilities (:capabilities result)
                       :server-info {:displayName (:displayName result)
                                     :version     (:version result)})
                (notify! client (spec/wire-method :initialized) {})
                (callback client nil)))))
    nil))

(defn connect!
  "JVM-only blocking connect. Returns the client or throws."
  [^Client client opts]
  #?(:clj
     (let [done (promise)
           timeout-ms (or (:connect-timeout-ms opts) 30000)]
       (connect-async! client opts (fn [c err] (deliver done [c err])))
       (let [[c err] (deref done timeout-ms [::timeout nil])]
         (cond
           (= ::timeout c) (throw (ex-info "BSP initialize timed out"
                                           {:timeout-ms timeout-ms}))
           err             (throw (ex-info "BSP initialize failed" {:error err}))
           :else           c)))
     :cljs
     (throw (ex-info "bsp.client/connect! is JVM-only — use connect-async!"
                     {:platform :cljs}))))

(defn disconnect!
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

(defn workspace-build-targets
  "List every build target the server exposes."
  [^Client client]
  (request! client (spec/wire-method :workspace-build-targets) {}))

(defn workspace-reload
  "Ask the server to reload its build configuration."
  [^Client client]
  (request! client (spec/wire-method :workspace-reload) {}))

(defn build-target-sources
  "Get source roots for given target ids."
  [^Client client target-ids]
  (request! client (spec/wire-method :build-target-sources) {:targets target-ids}))

(defn build-target-inverse-sources
  "Find the build target that owns a given source file URI."
  [^Client client uri]
  (request! client (spec/wire-method :build-target-inverse-sources)
            {:textDocument {:uri uri}}))

(defn build-target-dependency-sources
  [^Client client target-ids]
  (request! client (spec/wire-method :build-target-dependency-sources)
            {:targets target-ids}))

(defn build-target-dependency-modules
  [^Client client target-ids]
  (request! client (spec/wire-method :build-target-dependency-modules)
            {:targets target-ids}))

(defn build-target-resources
  [^Client client target-ids]
  (request! client (spec/wire-method :build-target-resources) {:targets target-ids}))

(defn build-target-output-paths
  [^Client client target-ids]
  (request! client (spec/wire-method :build-target-output-paths) {:targets target-ids}))

(defn build-target-compile
  "Compile the given targets. Returns Pending with {:statusCode n :dataKind ... :data ...}."
  ([client target-ids] (build-target-compile client target-ids nil))
  ([^Client client target-ids origin-id]
   (request! client (spec/wire-method :build-target-compile)
             (cond-> {:targets target-ids}
               origin-id (assoc :originId origin-id)))))

(defn build-target-test
  [^Client client target-ids]
  (request! client (spec/wire-method :build-target-test) {:targets target-ids}))

(defn build-target-run
  [^Client client target-id]
  (request! client (spec/wire-method :build-target-run) {:target target-id}))

(defn build-target-clean-cache
  [^Client client target-ids]
  (request! client (spec/wire-method :build-target-clean-cache) {:targets target-ids}))

(defn debug-session-start
  [^Client client target-ids data-kind data]
  (request! client (spec/wire-method :debug-session-start)
            {:targets target-ids :dataKind data-kind :data data}))
