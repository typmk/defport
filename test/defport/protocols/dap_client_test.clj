(ns defport.protocols.dap-client-test
  "Tests for defport.dap.client.

  Uses an in-memory FakeTransport that pairs a defport DAP server
  adapter to a defport DAP client through two queues — no
  subprocess, no real wire framing. Exercises seq-based request
  correlation, event dispatch, the typed convenience helpers
  reading wire commands from spec, and end-to-end round-trips
  through defcommand-defined ports."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.dap :as dap]
            [defport.dap.client :as client]
            [defport.dap.spec :as spec]
            [defport.registry :as registry]
            [defport.sugar :as sugar]))

;; ============================================================================
;; In-memory paired transport
;; ============================================================================

(defrecord FakeDapTransport [to-server to-client started?* stopped?*]
  client/ClientTransport
  (transport-start! [this]
    (reset! started?* true)
    this)
  (transport-send! [_ msg]
    (swap! to-server conj msg)
    nil)
  (transport-recv! [_]
    (let [[old _] (swap-vals! to-client (fn [q] (if (seq q) (subvec q 1) q)))]
      (if (seq old)
        (first old)
        ::client/no-message)))
  (transport-stop! [_]
    (reset! stopped?* true)
    nil)
  (transport-alive? [_]
    (and @started?* (not @stopped?*))))

(defn- make-paired-transport []
  (->FakeDapTransport (atom []) (atom []) (atom false) (atom false)))

(defn- pump-one!
  "Drain one message off the client→server queue, dispatch it through
   the DAP adapter, and (for requests) push a response back."
  [transport server-adapter port-registry]
  (let [[old _] (swap-vals! (:to-server transport)
                            (fn [q] (if (seq q) (subvec q 1) q)))]
    (when (seq old)
      (let [msg (first old)
            command (:command msg)
            args    (:arguments msg)
            seq-num (:seq msg)]
        (when command
          (let [resp (core/protocol-dispatch server-adapter command
                                             {:command command :arguments args}
                                             {:port-registry port-registry})
                ;; protocol-dispatch wraps non-success in {:result ...};
                ;; unwrap once for the wire body.
                body (if (and (map? resp) (contains? resp :result)
                              (not (contains? resp :error)))
                       (:result resp)
                       resp)]
            (swap! (:to-client transport) conj
                   {:seq 0
                    :type "response"
                    :request_seq seq-num
                    :success true
                    :command command
                    :body body}))))
      true)))

(defn- pump-all! [transport adapter port-registry]
  (loop [n 0]
    (if (and (pump-one! transport adapter port-registry) (< n 100))
      (recur (inc n))
      n)))

(defn- fresh-registry [] (registry/create-function-registry))

;; ============================================================================
;; Smoke: spec-driven wire commands
;; ============================================================================

(deftest test-typed-helpers-use-spec-wire-names
  (testing "every typed helper's request lands the camelCase wire command"
    (let [tx (make-paired-transport)
          c  (client/create-client tx)]
      (client/transport-start! tx)
      (client/step-in! c 1)
      (client/set-breakpoints! c {:path "x.clj"} [])
      (client/stack-trace c 1)
      (client/configuration-done! c)
      (let [sent (mapv :command @(:to-server tx))]
        (is (= ["stepIn" "setBreakpoints" "stackTrace" "configurationDone"]
               sent))))))

;; ============================================================================
;; End-to-end: defcommand → port → client/threads
;; ============================================================================

(deftest test-threads-roundtrips-through-defcommand
  (testing "client/threads receives the body returned by a defcommand port"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (dap/defcommand threads
          []
          {:threads [{:id 1 :name "user-thread"}
                     {:id 2 :name "gc"}]}))
      (let [adapter (sugar/create-adapter :dap
                      {:server-info {:name "t" :version "0"}
                       :registry reg})
            tx      (make-paired-transport)
            c       (client/create-client tx)]
        (client/transport-start! tx)
        (#'client/start-driver! c)
        (let [pending (client/threads c)
              done    (promise)]
          (client/then pending (fn [body err] (deliver done [body err])))
          (pump-all! tx adapter reg)
          (Thread/sleep 30)
          (let [[body err] (deref done 1000 [::timeout nil])]
            (is (not= ::timeout body))
            (is (nil? err))
            (is (= 2 (count (:threads body))))))))))

;; ============================================================================
;; Step helpers route to the matching defcommand port
;; ============================================================================

(deftest test-step-in-roundtrips-through-defcommand
  (let [reg (fresh-registry)
        seen (atom nil)]
    (binding [sugar/*registry* reg]
      (dap/defcommand step-in
        [thread-id :- :int]
        (reset! seen thread-id)
        {}))
    (let [adapter (sugar/create-adapter :dap
                    {:server-info {:name "t" :version "0"}
                     :registry reg})
          tx      (make-paired-transport)
          c       (client/create-client tx)]
      (client/transport-start! tx)
      (#'client/start-driver! c)
      (let [done (promise)]
        (client/then (client/step-in! c 7)
                     (fn [_ _] (deliver done :done)))
        (pump-all! tx adapter reg)
        (Thread/sleep 30)
        (deref done 1000 nil))
      (is (= 7 @seen)))))

;; ============================================================================
;; Events: server pushes, client handler fires
;; ============================================================================

(deftest test-on-event-fires-for-server-events
  (let [tx (make-paired-transport)
        c  (client/create-client tx)
        seen (atom nil)]
    (client/on-event c "stopped"
                     (fn [body] (reset! seen body)))
    (client/transport-start! tx)
    (#'client/start-driver! c)
    (swap! (:to-client tx) conj
           {:seq 1
            :type "event"
            :event "stopped"
            :body {:reason "breakpoint" :threadId 1}})
    (Thread/sleep 50)
    (is (= "breakpoint" (:reason @seen)))))

;; ============================================================================
;; Pending: response routing by request_seq
;; ============================================================================

(deftest test-response-routes-by-request-seq
  (let [tx (make-paired-transport)
        c  (client/create-client tx)
        results (atom [])]
    (client/transport-start! tx)
    (#'client/start-driver! c)
    ;; Issue two requests; reply out of order
    (let [p1 (client/threads c)
          p2 (client/scopes c 5)]
      (client/then p1 (fn [b _] (swap! results conj [:p1 b])))
      (client/then p2 (fn [b _] (swap! results conj [:p2 b])))
      ;; Reply to p2 first
      (swap! (:to-client tx) conj
             {:seq 1 :type "response" :request_seq (:seq p2)
              :success true :command "scopes"
              :body {:scopes [{:name "Locals"}]}})
      (Thread/sleep 30)
      (swap! (:to-client tx) conj
             {:seq 2 :type "response" :request_seq (:seq p1)
              :success true :command "threads"
              :body {:threads [{:id 1 :name "main"}]}})
      (Thread/sleep 30)
      (is (= #{:p1 :p2} (set (map first @results))))
      (is (= "Locals" (-> (some #(when (= :p2 (first %)) %) @results)
                          second :scopes first :name)))
      (is (= "main"   (-> (some #(when (= :p1 (first %)) %) @results)
                          second :threads first :name))))))

;; ============================================================================
;; Failed responses become errors on the Pending
;; ============================================================================

(deftest test-failed-response-fires-callback-with-error
  (let [tx (make-paired-transport)
        c  (client/create-client tx)
        result (atom nil)]
    (client/transport-start! tx)
    (#'client/start-driver! c)
    (let [p (client/evaluate c "(/ 1 0)")]
      (client/then p (fn [b e] (reset! result [b e])))
      (swap! (:to-client tx) conj
             {:seq 1 :type "response" :request_seq (:seq p)
              :success false :command "evaluate"
              :message "Division by zero"})
      (Thread/sleep 30)
      (let [[body err] @result]
        (is (nil? body))
        (is (= "Division by zero" (:message err)))))))
