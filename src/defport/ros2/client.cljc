(ns defport.ros2.client
  "rosbridge v2.0 client core.

  Speaks JSON over WebSocket against a `rosbridge_server` instance
  running on a ROS 2 host. Lets a Clojure / ClojureScript process
  publish to topics, subscribe to topics, call services, and send
  action goals — without needing rclcpp, rclpy, DDS, or the ROS
  toolchain.

  The rosbridge wire is `op`-based rather than JSON-RPC's
  `method`/`id`. Requests (call_service, send_action_goal) supply
  an `id` string and expect a matching `service_response` /
  `action_result` with the same id back. Notifications (publish,
  subscribe, advertise, status) don't carry ids.

  Defport's substrate pattern still applies — this namespace
  correlates responses by id, dispatches incoming notifications to
  handlers, and threads a WebSocket ClientTransport underneath."
  (:require [defport.ros2.spec :as spec]
            [defport.transports.websocket-client :as ws]
            [defport.util.platform :as platform :include-macros true]))

(defprotocol ClientTransport
  "rosbridge WebSocket transport. Wraps the generic
   defport.transports.websocket-client."
  (transport-start! [this])
  (transport-send!  [this msg])
  (transport-recv!  [this])
  (transport-stop!  [this])
  (transport-alive? [this]))

(defrecord Pending [id state*])

(defn then [^Pending pending callback]
  (let [now (swap! (:state* pending)
                   (fn [s]
                     (if (= :pending (:status s))
                       (update s :callbacks conj callback)
                       s)))]
    (when (not= :pending (:status now))
      (callback (:result now) (:error now))))
  pending)

(defn await [^Pending pending]
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
                            (assoc s :status (if error :error :done)
                                     :result result :error error)
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
            (atom {:next-id 0
                   :pending {}               ;; id -> Pending
                   :topic-handlers {}        ;; topic -> handler
                   :status-handler nil})))

(defn- next-id! [^Client client]
  (let [[old _] (swap-vals! (:state* client) update :next-id inc)]
    (str "req-" (:next-id old))))

(defn- register-pending! [^Client client id]
  (let [p (->Pending id (atom {:status :pending :callbacks []}))]
    (swap! (:state* client) assoc-in [:pending id] p)
    p))

(defn- forget-pending! [^Client client id]
  (let [s (swap-vals! (:state* client) update :pending dissoc id)]
    (get-in (first s) [:pending id])))

;; ============================================================================
;; Notification handlers
;; ============================================================================

(defn on-topic
  "Register a callback for messages published on `topic`. The
   callback receives the inbound message's :msg field."
  [^Client client topic handler-fn]
  (swap! (:state* client) assoc-in [:topic-handlers topic] handler-fn)
  client)

(defn off-topic
  [^Client client topic]
  (swap! (:state* client) update :topic-handlers dissoc topic)
  client)

(defn on-status
  "Register a handler for rosbridge status/error notifications.
   The handler receives `{:level :msg :id}`."
  [^Client client handler-fn]
  (swap! (:state* client) assoc :status-handler handler-fn)
  client)

;; ============================================================================
;; Send / receive
;; ============================================================================

(defn send-op!
  "Low-level: send a rosbridge op. Prefer the typed helpers below."
  [^Client client op-map]
  (transport-send! (:transport client) op-map)
  client)

(defn- send-request!
  "Send an op that expects a response (call_service, send_action_goal).
   Allocates an id, registers a Pending, returns it."
  [^Client client op-map]
  (let [id (next-id! client)
        p  (register-pending! client id)]
    (transport-send! (:transport client) (assoc op-map :id id))
    p))

;; ============================================================================
;; Dispatch
;; ============================================================================

(defn dispatch-incoming!
  [^Client client message]
  (let [op (:op message)]
    (case op
      "publish"
      (when-let [handler (get-in @(:state* client)
                                  [:topic-handlers (:topic message)])]
        (handler (:msg message)))

      ("service_response" "action_result")
      (when-let [p (forget-pending! client (:id message))]
        (if (false? (:result message))
          (resolve-pending! p nil
                            {:service (:service message)
                             :action  (:action message)
                             :values  (:values message)})
          (resolve-pending! p
                            (or (:values message) (:result message))
                            nil)))

      "action_feedback"
      ;; Action feedback is intermediate — consumers wire it through
      ;; on-topic-style handlers indexed by action name. Not resolved.
      (tap> {:event :ros2.client/action-feedback
             :action (:action message)
             :values (:values message)})

      "status"
      (when-let [h (:status-handler @(:state* client))]
        (h {:level (:level message)
            :msg   (:msg message)
            :id    (:id message)}))

      (tap> {:event :ros2.client/unrecognized-op :message message}))))

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
       (.setName t "defport.ros2.client/reader")
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
  "Start the transport and kick the driver loop. rosbridge has no
   handshake — once the WebSocket is open, ops flow."
  [^Client client _opts callback]
  (transport-start! (:transport client))
  (start-driver! client)
  (callback client nil)
  nil)

(defn connect!
  [^Client client opts]
  #?(:clj
     (let [done (promise)]
       (connect-async! client opts (fn [c err] (deliver done [c err])))
       (let [[c err] @done]
         (if err (throw (ex-info "rosbridge connect failed" {:error err})) c)))
     :cljs
     (throw (ex-info "ros2.client/connect! is JVM-only — use connect-async!"
                     {:platform :cljs}))))

(defn disconnect!
  [^Client client]
  (transport-stop! (:transport client))
  nil)

;; ============================================================================
;; Typed convenience helpers
;; ============================================================================

;; --- Topics -----------------------------------------------------------------

(defn advertise!
  "Advertise that the client will publish to `topic` with the given
   ROS 2 message type, e.g. \"std_msgs/msg/String\"."
  [^Client client topic type]
  (send-op! client {:op "advertise" :topic topic :type type}))

(defn unadvertise!
  [^Client client topic]
  (send-op! client {:op "unadvertise" :topic topic}))

(defn publish!
  "Publish `msg` to `topic`. `msg` is a clj map matching the
   message type's field layout."
  [^Client client topic msg]
  (send-op! client {:op "publish" :topic topic :msg msg}))

(defn subscribe!
  "Subscribe to `topic`. Messages arrive via the handler registered
   with `on-topic`. `type` is optional but recommended."
  ([client topic] (subscribe! client topic nil))
  ([^Client client topic type]
   (send-op! client
             (cond-> {:op "subscribe" :topic topic}
               type (assoc :type type)))))

(defn unsubscribe!
  [^Client client topic]
  (send-op! client {:op "unsubscribe" :topic topic}))

;; --- Services ---------------------------------------------------------------

(defn call-service
  "Call a ROS 2 service and return a Pending resolving to the
   response values."
  [^Client client service args]
  (send-request! client {:op "call_service" :service service :args args}))

;; --- Actions ----------------------------------------------------------------

(defn send-action-goal
  "Send an action goal and return a Pending that resolves to the
   result. Intermediate feedback is delivered via tap> as
   `:ros2.client/action-feedback` events; consumers that want
   structured feedback should wire their own dispatch above this
   layer."
  [^Client client action goal]
  (send-request! client {:op "send_action_goal" :action action :goal goal}))

(defn cancel-action-goal
  [^Client client action id]
  (send-op! client {:op "cancel_action_goal" :action action :id id}))
