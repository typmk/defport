(ns defport.protocols.mcp-client-test
  "Tests for defport.mcp.client — the protocol-free MCP client core.

  Uses the same in-memory paired-transport pattern as the LSP/DAP
  client tests: pair a defport MCP server adapter to the client
  through two queues, pump messages manually. Exercises
  request/response correlation, typed helpers reading wire methods
  from spec, and end-to-end roundtrips against a real deftool port."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.mcp.client :as client]
            [defport.mcp.spec :as spec]
            [defport.registry :as registry]
            [defport.sugar :as sugar]))

;; ============================================================================
;; In-memory paired transport
;; ============================================================================

(defrecord FakeMcpTransport [to-server to-client started?* stopped?*]
  client/ClientTransport
  (transport-start! [this]
    (reset! started?* true)
    this)
  (transport-send! [_ msg]
    (swap! to-server conj msg)
    nil)
  (transport-recv! [_]
    (let [[old _] (swap-vals! to-client (fn [q] (if (seq q) (subvec q 1) q)))]
      (if (seq old) (first old) ::client/no-message)))
  (transport-stop! [_] (reset! stopped?* true) nil)
  (transport-alive? [_] (and @started?* (not @stopped?*))))

(defn- make-paired-transport []
  (->FakeMcpTransport (atom []) (atom []) (atom false) (atom false)))

(defn- pump-one! [transport server-adapter port-registry]
  (let [[old _] (swap-vals! (:to-server transport)
                            (fn [q] (if (seq q) (subvec q 1) q)))]
    (when (seq old)
      (let [msg    (first old)
            method (:method msg)
            params (:params msg)
            id     (:id msg)]
        (when method
          (let [result (core/protocol-dispatch server-adapter method params
                                               {:port-registry port-registry})]
            (when id
              (swap! (:to-client transport) conj
                     (merge {:jsonrpc "2.0" :id id}
                            (if (contains? result :error)
                              {:error (:error result)}
                              {:result result})))))))
      true)))

(defn- pump-all! [transport adapter port-registry]
  (loop [n 0]
    (if (and (pump-one! transport adapter port-registry) (< n 100))
      (recur (inc n))
      n)))

(defn- fresh-registry [] (registry/create-function-registry))

;; ============================================================================
;; Typed helpers use spec wire methods
;; ============================================================================

(deftest test-typed-helpers-use-spec-wire-methods
  (let [tx (make-paired-transport)
        c  (client/create-client tx)]
    (client/transport-start! tx)
    (client/list-tools c)
    (client/call-tool c "search" {:q "foo"})
    (client/list-prompts c)
    (client/read-resource c "defport://x")
    (client/ping c)
    (is (= ["tools/list" "tools/call" "prompts/list" "resources/read" "ping"]
           (mapv :method @(:to-server tx))))))

;; ============================================================================
;; End-to-end: list-tools through a deftool-defined port
;; ============================================================================

(deftest test-list-tools-through-deftool-port
  (let [reg (fresh-registry)]
    (binding [sugar/*registry* reg]
      (mcp/deftool adder
        "Add two numbers"
        [a :- :int b :- :int]
        (+ a b)))
    (let [adapter (mcp/create-mcp-adapter)
          tx (make-paired-transport)
          c  (client/create-client tx)
          done (promise)]
      (client/transport-start! tx)
      (#'client/start-driver! c)
      (client/then (client/list-tools c)
                   (fn [r _] (deliver done r)))
      (pump-all! tx adapter reg)
      (Thread/sleep 30)
      (let [result (deref done 1000 :timeout)]
        (is (not= :timeout result))
        (is (vector? (:tools result)))
        (is (some #(= "adder" (:name %)) (:tools result)))))))

(deftest test-call-tool-through-deftool-port
  (let [reg (fresh-registry)]
    (binding [sugar/*registry* reg]
      (mcp/deftool adder
        "Add two numbers"
        [a :- :int b :- :int]
        (+ a b)))
    (let [adapter (mcp/create-mcp-adapter)
          tx (make-paired-transport)
          c  (client/create-client tx)
          done (promise)]
      (client/transport-start! tx)
      (#'client/start-driver! c)
      (client/then (client/call-tool c "adder" {:a 2 :b 3})
                   (fn [r _] (deliver done r)))
      (pump-all! tx adapter reg)
      (Thread/sleep 30)
      (let [result (deref done 1000 :timeout)]
        (is (not= :timeout result))
        ;; MCP wraps tool results in {:content [...] :isError false}
        (is (vector? (:content result)))))))

;; ============================================================================
;; Initialize handshake
;; ============================================================================

(deftest test-connect-async-completes-initialize
  (let [adapter (mcp/create-mcp-adapter
                  {:server-info {:name "t" :version "0.1"}})
        tx (make-paired-transport)
        c  (client/create-client tx)
        done (promise)]
    (client/connect-async! c {:client-info {:name "test-c" :version "0"}}
                           (fn [client err] (deliver done [client err])))
    (pump-all! tx adapter (registry/create-function-registry))
    (Thread/sleep 30)
    (let [[cl err] (deref done 1000 [:timeout nil])]
      (is (not= :timeout cl))
      (is (nil? err))
      (is (true? (:initialized? @(:state* cl))))
      (is (= "t" (:name (:server-info @(:state* cl))))))))

;; ============================================================================
;; Pending: then/await mechanics
;; ============================================================================

(deftest test-pending-then-and-resolve
  (let [p (client/->Pending 1 (atom {:status :pending :callbacks []}))
        seen (atom nil)]
    (client/then p (fn [r e] (reset! seen [r e])))
    (#'client/resolve-pending! p {:ok 1} nil)
    (is (= [{:ok 1} nil] @seen))))

(deftest test-await-blocks-until-resolved
  (let [p (client/->Pending 2 (atom {:status :pending :callbacks []}))]
    (future
      (Thread/sleep 20)
      (#'client/resolve-pending! p :done nil))
    (let [[r _] (client/await p)]
      (is (= :done r)))))

;; ============================================================================
;; Notifications
;; ============================================================================

(deftest test-on-notification-fires
  (let [tx (make-paired-transport)
        c  (client/create-client tx)
        seen (atom nil)]
    (client/on-notification c "notifications/message"
                            (fn [params] (reset! seen params)))
    (client/transport-start! tx)
    (#'client/start-driver! c)
    (swap! (:to-client tx) conj
           {:jsonrpc "2.0"
            :method "notifications/message"
            :params {:level "info" :data "hello"}})
    (Thread/sleep 50)
    (is (= "info" (:level @seen)))))
