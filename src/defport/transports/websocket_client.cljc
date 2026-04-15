(ns defport.transports.websocket-client
  "WebSocket ClientTransport for defport's client cores.

  Opens a WebSocket connection to a URL, parses inbound text
  messages as JSON, pushes them onto a queue, and sends outbound
  messages as JSON text frames. Used by defport.cdp.client and
  defport.ros2.client — any client core that expects JSON-over-
  WebSocket instead of Content-Length-framed stdio.

  JVM implementation uses java.net.http.WebSocket (JDK 11+,
  zero new deps). CLJS implementation uses the global
  WebSocket constructor (available in browsers and Node 22+
  without `ws`).

  The transport presents the same ClientTransport shape as the
  subprocess transports so defport.{cdp,ros2}.client code doesn't
  care which kind of transport it's sitting on top of. Per
  CLAUDE.md principle 5, this is one of the 'optional reference
  transports'; consumers with exotic needs can implement their
  own."
  (:require [defport.util.platform :as platform :include-macros true])
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient WebSocket WebSocket$Listener]
                   [java.util.concurrent CompletionStage LinkedBlockingQueue TimeUnit])))

;; Generic ClientTransport protocol that works for any defport client
;; that wants JSON-over-WebSocket. Each client namespace also defines
;; its own ClientTransport protocol for type-discrimination, and
;; extends it to a thin wrapper around this generic transport. See
;; defport.cdp.client.transports.websocket for an example.

(defprotocol WebsocketClientTransport
  "Raw JSON-over-WebSocket client transport."
  (ws-start! [this])
  (ws-send! [this clj-map])
  (ws-recv! [this])
  (ws-stop! [this])
  (ws-alive? [this]))

#?(:clj
   (do
     (defn- build-listener
       [recv-queue buffer-ref alive?*]
       ;; WebSocket$Listener default methods accept null as the
       ;; CompletionStage return to mean "normal completion". Keep
       ;; the implementations side-effecting and just return nil.
       (reify WebSocket$Listener
         (onOpen [_ ws]
           (.request ^WebSocket ws 1))

         (onText [_ ws data last?]
           (let [^StringBuilder sb @buffer-ref]
             (.append sb ^CharSequence data)
             (when last?
               (let [s (.toString sb)]
                 (.setLength sb 0)
                 (try
                   (let [parsed (platform/json-decode s)]
                     (.put ^LinkedBlockingQueue recv-queue parsed))
                   (catch Exception e
                     (tap> {:event :ws.client/parse-error
                            :error (platform/error-message e)
                            :raw s}))))))
           (.request ^WebSocket ws 1)
           nil)

         (onClose [_ _ws _status _reason]
           (reset! alive?* false)
           (.put ^LinkedBlockingQueue recv-queue ::eof)
           nil)

         (onError [_ _ws t]
           (tap> {:event :ws.client/error
                  :error (platform/error-message t)})
           (reset! alive?* false))))

     (defrecord JvmWebsocketTransport [url ws* alive?* recv-queue buffer]
       WebsocketClientTransport
       (ws-start! [this]
         (let [client (HttpClient/newHttpClient)
               listener (build-listener recv-queue buffer alive?*)
               builder  (.newWebSocketBuilder client)
               fut      (.buildAsync builder (URI/create url) listener)
               ws       (.get fut 30 TimeUnit/SECONDS)]
           (reset! ws* ws)
           (reset! alive?* true)
           this))

       (ws-send! [this clj-map]
         (when-let [^WebSocket ws @ws*]
           (let [json (platform/json-encode clj-map)]
             (.sendText ws json true)))
         this)

       (ws-recv! [_]
         (let [m (.poll ^LinkedBlockingQueue recv-queue
                        0 TimeUnit/MILLISECONDS)]
           (cond
             (nil? m)  ::no-message
             (= m ::eof) ::eof
             :else m)))

       (ws-stop! [_]
         (reset! alive?* false)
         (when-let [^WebSocket ws @ws*]
           (try (.sendClose ws WebSocket/NORMAL_CLOSURE "bye") (catch Exception _))
           (try (.abort ws) (catch Exception _))
           (reset! ws* nil))
         nil)

       (ws-alive? [_]
         (boolean (and @alive?* @ws*))))

     (defn jvm-websocket-transport
       "Construct a JVM WebSocket transport for a given URL. Transport
        is NOT started yet — consumer wrappers call ws-start!
        inside their ClientTransport/transport-start! impl.

        Example:
          (jvm-websocket-transport \"ws://localhost:9222/devtools/page/XXX\")
          (jvm-websocket-transport \"ws://localhost:9090\")  ;; rosbridge"
       [url]
       (->JvmWebsocketTransport
         url
         (atom nil)
         (atom false)
         (LinkedBlockingQueue.)
         (atom (StringBuilder.))))))

#?(:cljs
   (do
     (defrecord NodeWebsocketTransport [url ws* alive?* recv-queue buffer]
       WebsocketClientTransport
       (ws-start! [this]
         (let [;; Prefer the global WebSocket if the runtime exposes
               ;; one (modern Node + browsers); fall back to
               ;; require("ws") if the consumer has it installed.
               WS (or (and (exists? js/WebSocket) js/WebSocket)
                      (try (js/require "ws") (catch :default _ nil)))
               _  (when-not WS
                    (throw (ex-info "No WebSocket available — install the `ws` npm package or run on a JS runtime with a global WebSocket."
                                    {:url url})))
               ws (WS. url)]
           (reset! ws* ws)
           (reset! alive?* true)
           (set! (.-onmessage ws)
                 (fn [ev]
                   (let [data (.-data ev)]
                     (try
                       (.push recv-queue (platform/json-decode data))
                       (catch js/Error e
                         (tap> {:event :ws.client/parse-error
                                :error (.-message e)}))))))
           (set! (.-onclose ws)
                 (fn [_ev]
                   (reset! alive?* false)
                   (.push recv-queue ::eof)))
           (set! (.-onerror ws)
                 (fn [ev]
                   (tap> {:event :ws.client/error :error (str ev)})))
           this))

       (ws-send! [this clj-map]
         (when-let [ws @ws*]
           (.send ws (platform/json-encode clj-map)))
         this)

       (ws-recv! [_]
         (if (zero? (.-length recv-queue))
           ::no-message
           (let [m (.shift recv-queue)]
             (if (= m ::eof) ::eof m))))

       (ws-stop! [_]
         (reset! alive?* false)
         (when-let [ws @ws*]
           (try (.close ws) (catch :default _))
           (reset! ws* nil))
         nil)

       (ws-alive? [_]
         (boolean (and @alive?* @ws*))))

     (defn node-websocket-transport
       [url]
       (->NodeWebsocketTransport url (atom nil) (atom false) (array) (atom nil)))))
