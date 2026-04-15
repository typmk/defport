(ns defport.cdp.client.transports.websocket
  "Reference WebSocket transport for the CDP client. Thin wrapper
   that bridges defport.transports.websocket-client (generic JSON-
   over-WebSocket) to defport.cdp.client/ClientTransport.

   ## Usage

       (require '[defport.cdp.client :as cdp]
                '[defport.cdp.client.transports.websocket :as ws])

       ;; Chromium running with --remote-debugging-port=9222 exposes
       ;; its target list at http://localhost:9222/json — fetch one
       ;; target's webSocketDebuggerUrl and connect to it.
       (def client
         (-> (ws/transport \"ws://localhost:9222/devtools/page/ABCDEF\")
             (cdp/create-client)
             (cdp/connect! {})))"
  (:require [defport.cdp.client :as client]
            [defport.transports.websocket-client :as raw]))

(defrecord CdpWebsocketTransport [inner]
  client/ClientTransport
  (transport-start! [this] (raw/ws-start! inner) this)
  (transport-send!  [this msg] (raw/ws-send! inner msg) this)
  (transport-recv!  [_]
    (let [m (raw/ws-recv! inner)]
      (cond
        (= m :defport.transports.websocket-client/no-message) ::client/no-message
        (= m :defport.transports.websocket-client/eof)        ::client/eof
        :else m)))
  (transport-stop!  [_] (raw/ws-stop! inner))
  (transport-alive? [_] (raw/ws-alive? inner)))

(defn transport
  "Construct a CDP WebSocket client transport for a given URL.

   Example:
     (transport \"ws://localhost:9222/devtools/page/ABCDEF\")"
  [url]
  (->CdpWebsocketTransport
    #?(:clj  (raw/jvm-websocket-transport url)
       :cljs (raw/node-websocket-transport url))))
