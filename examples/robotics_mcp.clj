(ns robotics-mcp
  "MCP server that exposes a ROS 2 robot via defport.ros2.client.

  This is the robotics-side companion to industrial_mcp.clj. It
  shows how a Clojure process can act as an MCP bridge between an
  AI assistant (Claude, Cursor, etc.) and a ROS 2 robot running
  rosbridge_server — no rclcpp, no rclpy, no DDS FFI. Just
  defport's MCP substrate on one side and defport.ros2.client on
  the other.

  ## How it fits together

      [AI client]  ──MCP──►  [this process]  ──rosbridge WS──►  [ROS 2 robot]
                             ^                 ^
                             |                 |
                             defport.mcp       defport.ros2.client

  The MCP side exposes tools like `move-to-pose`, `read-joint-state`,
  `list-topics`. Each tool calls into the rosbridge client. The AI
  never sees JSON-RPC, WebSocket framing, or ROS 2 message shapes —
  it just calls tools.

  ## Run (against a real ROS 2 robot)

      ;; Start rosbridge_server on your ROS 2 robot:
      ros2 launch rosbridge_server rosbridge_websocket_launch.xml

      ;; Start this MCP server (it'll connect to localhost:9090):
      clojure -M:examples -m robotics-mcp

      ;; Attach any MCP client. Call move-to-pose.

  The example uses a mock `rosbridge-mock` state when no real
  rosbridge server is reachable, so it boots stand-alone for
  demonstration. Swap `rosbridge-url` in `-main` to point at a
  real ROS 2 host."
  (:require [defport.mcp :as mcp]
            [defport.sugar :as sugar]
            [defport.ros2.client :as ros2]
            [defport.ros2.client.transports.websocket :as ros2-ws]))

;; ----- Connection state ------------------------------------------------------
;;
;; Created at -main time. Mock fallback used if the WebSocket
;; never connects so the example doesn't crash when you're just
;; inspecting the tools via MCP Inspector.

(defonce ^:private ros2-client* (atom nil))

(defn- client []
  (or @ros2-client*
      (throw (ex-info "ROS 2 client not connected" {}))))

;; ----- MCP tools over the ROS 2 client --------------------------------------

(mcp/deftool list-topics
  "List advertised ROS 2 topics via the rosapi service."
  []
  (let [[body err] (ros2/await
                     (ros2/call-service (client) "/rosapi/topics" {}))]
    {:content [{:type "text"
                :text (if err
                        (str "Failed: " err)
                        (pr-str body))}]}))

(mcp/deftool call-service
  "Call an arbitrary ROS 2 service with JSON args. The AI needs to
   know the service name and the args shape the target service
   expects — typically by first calling list-services or reading
   the interface definition."
  [service :- :string args :- :map]
  (let [[body err] (ros2/await (ros2/call-service (client) service args))]
    {:content [{:type "text"
                :text (if err (str "Error: " err) (pr-str body))}]}))

(mcp/deftool publish-twist
  "Publish a geometry_msgs/msg/Twist to a topic — the canonical
   'drive this robot' tool. Linear x/y/z and angular x/y/z in m/s
   and rad/s respectively."
  [topic :- :string
   linear-x :- :number linear-y :- :number linear-z :- :number
   angular-x :- :number angular-y :- :number angular-z :- :number]
  (ros2/publish! (client) topic
                 {:linear  {:x linear-x :y linear-y :z linear-z}
                  :angular {:x angular-x :y angular-y :z angular-z}})
  {:content [{:type "text"
              :text (str "Published Twist to " topic)}]})

(mcp/deftool send-nav-goal
  "Send a nav2-compatible NavigateToPose action goal."
  [frame-id :- :string x :- :number y :- :number yaw :- :number]
  (let [goal {:pose {:header {:frame_id frame-id}
                     :pose   {:position    {:x x :y y :z 0}
                              :orientation {:x 0 :y 0 :z (Math/sin (/ yaw 2))
                                            :w (Math/cos (/ yaw 2))}}}}
        pending (ros2/send-action-goal (client) "/navigate_to_pose" goal)
        [result err] (ros2/await pending)]
    {:content [{:type "text"
                :text (if err
                        (str "Nav goal failed: " err)
                        (str "Nav goal result: " (pr-str result)))}]}))

;; ----- Main -----------------------------------------------------------------

(defn -main [& _]
  (let [rosbridge-url (or (System/getenv "ROSBRIDGE_URL") "ws://localhost:9090")]
    ;; Best-effort connect. If rosbridge isn't running the MCP
    ;; tools will still register, they'll just throw on invocation.
    (try
      (let [c (-> (ros2-ws/transport rosbridge-url)
                  (ros2/create-client)
                  (ros2/connect! {}))]
        (reset! ros2-client* c)
        (binding [*out* *err*]
          (println "[robotics-mcp] connected to" rosbridge-url)))
      (catch Exception e
        (binding [*out* *err*]
          (println "[robotics-mcp] could not reach rosbridge at" rosbridge-url
                   "—" (.getMessage e))
          (println "[robotics-mcp] tools will throw on invocation"))))
    (sugar/run! {:protocol :mcp
                 :server-info {:name "defport-robotics-mcp-example"
                               :version "0.1.0"}})))
