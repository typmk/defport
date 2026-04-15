(ns defport.ros2.client.transports.websocket
  "Reference WebSocket transport for the rosbridge client.

   ## Usage

       (require '[defport.ros2.client :as ros2]
                '[defport.ros2.client.transports.websocket :as ws])

       ;; Assuming rosbridge_server is running on port 9090:
       (def robot
         (-> (ws/transport \"ws://localhost:9090\")
             (ros2/create-client)
             (ros2/connect! {})))

       (ros2/subscribe! robot \"/scan\" \"sensor_msgs/msg/LaserScan\")
       (ros2/on-topic robot \"/scan\" (fn [msg] (println :got msg)))"
  (:require [defport.ros2.client :as client]
            [defport.transports.websocket-client :as raw]))

(defrecord Ros2WebsocketTransport [inner]
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
  "Construct a rosbridge WebSocket client transport.

   Example:
     (transport \"ws://localhost:9090\")"
  [url]
  (->Ros2WebsocketTransport
    #?(:clj  (raw/jvm-websocket-transport url)
       :cljs (raw/node-websocket-transport url))))
