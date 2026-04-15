(ns defport.cdp.client
  "Chrome DevTools Protocol client core.

  CDP uses JSON-RPC over WebSocket. Same request/response shape as
  LSP/DAP/MCP — `{id, method, params}` out, `{id, result|error}` back.
  Events have no id: `{method, params}`.

  Unlike LSP/DAP/MCP which defport can speak over stdio subprocess
  transports, CDP requires a WebSocket. The JVM transport uses
  java.net.http.WebSocket (JDK 11+, zero new deps) via
  defport.transports.websocket-client.

  Typical flow: run Chromium with
    chromium --headless --disable-gpu --remote-debugging-port=9222
  fetch the target list from http://localhost:9222/json, pick the
  page you want, and connect to its `webSocketDebuggerUrl`.

  This namespace ships helpers for the common commands (Page,
  Runtime, Network, DOM, Browser, Target, Input). The full 664-
  command surface is available via generic `request!` + spec lookup."
  (:require [defport.cdp.spec :as spec]
            [defport.transports.websocket-client :as ws]
            [defport.util.platform :as platform :include-macros true]))

;; ============================================================================
;; Pending / Client — same shape as the other clients
;; ============================================================================

(defprotocol ClientTransport
  "CDP client transport — thin wrapper around a JSON-over-WebSocket
   channel."
  (transport-start! [this])
  (transport-send! [this message])
  (transport-recv! [this])
  (transport-stop! [this])
  (transport-alive? [this]))

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
                 (if e (reject (clj->js e))
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
  "Wrap a CDP ClientTransport in a Client value. Transport is NOT
   started yet — call `connect!` (JVM) or `connect-async!` to
   kick the driver loop."
  [transport]
  (->Client transport
            (atom {:next-id 1
                   :pending {}
                   :event-handlers {}
                   :started? false})))

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
;; Event handlers (CDP events are just server-initiated notifications)
;; ============================================================================

(defn on-event
  "Register a handler `(fn [params])` for a CDP event by its wire
   method string, e.g. \"Page.loadEventFired\"."
  [^Client client event-name handler-fn]
  (swap! (:state* client) assoc-in [:event-handlers event-name] handler-fn)
  client)

(defn off-event
  [^Client client event-name]
  (swap! (:state* client) update :event-handlers dissoc event-name)
  client)

;; ============================================================================
;; Dispatch
;; ============================================================================

(defn dispatch-incoming!
  "Route one inbound message to the right place."
  [^Client client message]
  (let [{:keys [id method params]} message
        has-result? (contains? message :result)
        has-error?  (contains? message :error)]
    (cond
      (and id (or has-result? has-error?) (not method))
      (when-let [p (forget-pending! client id)]
        (resolve-pending! p (:result message) (:error message)))

      method
      (when-let [handler (get-in @(:state* client) [:event-handlers method])]
        (handler params))

      :else
      (tap> {:event :cdp.client/unrecognized-message :message message}))))

;; ============================================================================
;; Driver loop (JVM reader thread / CLJS setImmediate)
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
       (.setName t "defport.cdp.client/reader")
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
;; Send + request!
;; ============================================================================

(defn request!
  "Send a CDP command. `method` is the wire string
   (\"Domain.command\") or a spec keyword like :Page/navigate.
   Returns a Pending."
  [^Client client method params]
  (let [wire (if (keyword? method) (spec/wire-method method) method)
        id (next-id! client)
        p  (register-pending! client id)]
    (when-not wire
      (throw (ex-info (str "Unknown CDP method: " method) {:method method})))
    (transport-send! (:transport client)
                     {:id id :method wire :params (or params {})})
    p))

;; ============================================================================
;; Connect / disconnect
;; ============================================================================

(defn connect-async!
  "Start the transport and kick the driver loop. CDP has no
   explicit handshake — once the WebSocket is open, commands may
   flow. Calls (callback client nil) as soon as the transport
   reports alive."
  [^Client client _opts callback]
  (transport-start! (:transport client))
  (start-driver! client)
  (swap! (:state* client) assoc :started? true)
  (callback client nil)
  nil)

(defn connect!
  "JVM-only blocking connect. Returns the client."
  [^Client client opts]
  #?(:clj
     (let [done (promise)]
       (connect-async! client opts (fn [c err] (deliver done [c err])))
       (let [[c err] @done]
         (if err (throw (ex-info "CDP connect failed" {:error err})) c)))
     :cljs
     (throw (ex-info "cdp.client/connect! is JVM-only — use connect-async!"
                     {:platform :cljs}))))

(defn disconnect!
  [^Client client]
  (transport-stop! (:transport client))
  nil)

;; ============================================================================
;; Typed convenience helpers for the common commands
;; ============================================================================
;;
;; Defport covers 664 CDP commands via `request!` + spec keywords.
;; These wrappers cover the ones 90% of consumers actually use.

;; --- Browser / Target -------------------------------------------------------

(defn browser-get-version [^Client client]
  (request! client :Browser/getVersion {}))

(defn target-get-targets [^Client client]
  (request! client :Target/getTargets {}))

(defn target-create-target [^Client client url]
  (request! client :Target/createTarget {:url url}))

(defn target-close-target [^Client client target-id]
  (request! client :Target/closeTarget {:targetId target-id}))

;; --- Page -------------------------------------------------------------------

(defn page-enable [^Client client]
  (request! client :Page/enable {}))

(defn page-navigate
  "Navigate the page to a URL."
  [^Client client url]
  (request! client :Page/navigate {:url url}))

(defn page-reload
  ([client] (page-reload client false))
  ([^Client client ignore-cache?]
   (request! client :Page/reload {:ignoreCache ignore-cache?})))

(defn page-capture-screenshot
  "Returns a Pending with {:data <base64>}."
  ([client] (page-capture-screenshot client nil))
  ([^Client client opts]
   (request! client :Page/captureScreenshot (or opts {}))))

(defn page-print-to-pdf
  [^Client client opts]
  (request! client :Page/printToPDF (or opts {})))

;; --- Runtime ----------------------------------------------------------------

(defn runtime-enable [^Client client]
  (request! client :Runtime/enable {}))

(defn runtime-evaluate
  "Evaluate a JS expression in the page's global context. Returns
   Pending with {:result {:type :value :...} :exceptionDetails ...}."
  ([client expression] (runtime-evaluate client expression {}))
  ([^Client client expression opts]
   (request! client :Runtime/evaluate
             (merge {:expression expression
                     :returnByValue true
                     :awaitPromise true}
                    opts))))

;; --- DOM --------------------------------------------------------------------

(defn dom-enable [^Client client]
  (request! client :DOM/enable {}))

(defn dom-get-document
  ([client] (dom-get-document client -1))
  ([^Client client depth]
   (request! client :DOM/getDocument {:depth depth :pierce false})))

(defn dom-query-selector
  [^Client client node-id selector]
  (request! client :DOM/querySelector {:nodeId node-id :selector selector}))

(defn dom-get-outer-html
  [^Client client node-id]
  (request! client :DOM/getOuterHTML {:nodeId node-id}))

;; --- Network ----------------------------------------------------------------

(defn network-enable
  ([client] (network-enable client {}))
  ([^Client client opts]
   (request! client :Network/enable opts)))

(defn network-set-user-agent-override
  [^Client client ua]
  (request! client :Network/setUserAgentOverride {:userAgent ua}))

(defn network-set-extra-http-headers
  [^Client client headers]
  (request! client :Network/setExtraHTTPHeaders {:headers headers}))

;; --- Input ------------------------------------------------------------------

(defn input-dispatch-mouse-event
  [^Client client {:keys [type x y button click-count]
                   :or {button "left" click-count 1}}]
  (request! client :Input/dispatchMouseEvent
            {:type type :x x :y y :button button :clickCount click-count}))

(defn input-dispatch-key-event
  [^Client client {:keys [type text key code]}]
  (request! client :Input/dispatchKeyEvent
            (cond-> {:type type}
              text (assoc :text text)
              key  (assoc :key key)
              code (assoc :code code))))
