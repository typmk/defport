(ns defport.ros2.spec
  "rosbridge_suite protocol — the JSON-over-WebSocket bridge that
   lets any client talk to ROS 2 topics / services / actions
   without needing rclcpp, rclpy, or DDS.

   rosbridge uses a small `op` field instead of JSON-RPC's
   `method`/`id`, but the shape is otherwise familiar:

       outgoing: {\"op\": \"call_service\", \"service\": \"/foo\", \"args\": {...}, \"id\": \"abc\"}
       incoming: {\"op\": \"service_response\", \"service\": \"/foo\", \"values\": {...}, \"id\": \"abc\", \"result\": true}

   15 operations cover the full rosbridge v2.0 surface:
   lifecycle, topics (pub/sub), services, actions, and auth.

   Spec ref:
   https://github.com/biobotic/rosbridge_suite/blob/ros2/ROSBRIDGE_PROTOCOL.md"
  (:refer-clojure :exclude [methods]))

(def methods
  "Every rosbridge v2.0 op, keyed by a handler-name keyword."
  {;; ----- Lifecycle -------------------------------------------------------
   :auth
   {:op "auth" :kind :notification :direction :client->server
    :doc "Authenticate via a mac/client/dest/rand/t/level/end handshake."}
   :status
   {:op "status" :kind :notification :direction :server->client
    :doc "Server status/error notification (info, warning, error)."}
   :set-level
   {:op "set_level" :kind :notification :direction :client->server
    :doc "Set the minimum log level the server reports."}
   :set-compression
   {:op "set_compression" :kind :notification :direction :client->server
    :doc "Configure compression (none | png | cbor) for outgoing messages."}

   ;; ----- Topics (pub/sub) ------------------------------------------------
   :advertise
   {:op "advertise" :kind :notification :direction :client->server
    :doc "Advertise that the client will publish to a topic."}
   :unadvertise
   {:op "unadvertise" :kind :notification :direction :client->server
    :doc "Stop advertising a topic."}
   :publish
   {:op "publish" :kind :notification :direction :both
    :doc "Publish one message to a topic (or receive one from the server)."}
   :subscribe
   {:op "subscribe" :kind :notification :direction :client->server
    :doc "Subscribe to a topic. Server starts pushing publish ops back."}
   :unsubscribe
   {:op "unsubscribe" :kind :notification :direction :client->server
    :doc "Unsubscribe from a topic."}

   ;; ----- Services (request/response) -------------------------------------
   :advertise-service
   {:op "advertise_service" :kind :notification :direction :client->server
    :doc "Advertise that the client implements a service."}
   :unadvertise-service
   {:op "unadvertise_service" :kind :notification :direction :client->server
    :doc "Stop implementing a service."}
   :call-service
   {:op "call_service" :kind :request :direction :client->server
    :doc "Call a service and await a response."}
   :service-response
   {:op "service_response" :kind :notification :direction :both
    :doc "Service call result."}
   :service-request
   {:op "service_request" :kind :notification :direction :server->client
    :doc "Server-originated service request (to a client-advertised service)."}

   ;; ----- Actions (long-running) -----------------------------------------
   :advertise-action
   {:op "advertise_action" :kind :notification :direction :client->server
    :doc "Advertise an action server."}
   :unadvertise-action
   {:op "unadvertise_action" :kind :notification :direction :client->server
    :doc "Stop advertising an action server."}
   :send-action-goal
   {:op "send_action_goal" :kind :request :direction :client->server
    :doc "Send an action goal and track its lifecycle."}
   :cancel-action-goal
   {:op "cancel_action_goal" :kind :notification :direction :client->server
    :doc "Cancel an in-flight action goal."}
   :action-result
   {:op "action_result" :kind :notification :direction :server->client
    :doc "Action completed result."}
   :action-feedback
   {:op "action_feedback" :kind :notification :direction :server->client
    :doc "Action progress feedback."}})

;; ============================================================================
;; Lookups
;; ============================================================================

(defn method-for [handler-name] (get methods handler-name))
(defn wire-op [handler-name] (:op (method-for handler-name)))
(defn op-to-handler-name [op-string]
  (some (fn [[k v]] (when (= op-string (:op v)) k)) methods))
(defn all-handler-names [] (keys methods))
(defn notification? [h] (= :notification (:kind (method-for h))))
(defn request? [h] (= :request (:kind (method-for h))))
(defn server-initiated? [h] (= :server->client (:direction (method-for h))))
