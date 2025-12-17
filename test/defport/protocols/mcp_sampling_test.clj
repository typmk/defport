(ns defport.protocols.mcp-sampling-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]))

(use-fixtures :each
  (fn [f]
    (mcp/reset-protocol-state!)
    (f)))

(deftest test-create-sampling-request
  (testing "Creates sampling request with messages"
    (let [messages [{:role "user" :content {:type "text" :text "Hello"}}]
          request-id (mcp/create-sampling-request messages)]
      (is (string? request-id))
      (let [state (get @mcp/sampling-state* request-id)]
        (is (some? state))
        (is (= :pending (:status state)))
        (is (= messages (get-in state [:request :messages])))
        (is (= 1000 (get-in state [:request :maxTokens]))))))

  (testing "Creates sampling request with options"
    (let [messages [{:role "user" :content {:type "text" :text "Analyze this"}}]
          opts {:max-tokens 500
                :system-prompt "You are an analyst"
                :model-preferences {:hints [{:name "claude-3"}]}}
          request-id (mcp/create-sampling-request messages opts)]
      (is (string? request-id))
      (let [state (get @mcp/sampling-state* request-id)]
        (is (= messages (get-in state [:request :messages])))
        (is (= 500 (get-in state [:request :maxTokens])))
        (is (= "You are an analyst" (get-in state [:request :systemPrompt])))
        (is (= {:hints [{:name "claude-3"}]} (get-in state [:request :modelPreferences]))))))

  (testing "Creates unique request IDs"
    (let [messages [{:role "user" :content {:type "text" :text "Test"}}]
          id1 (mcp/create-sampling-request messages)
          id2 (mcp/create-sampling-request messages)]
      (is (not= id1 id2)))))

(deftest test-handle-sampling-response
  (testing "Records response and resolves promise"
    (let [messages [{:role "user" :content {:type "text" :text "Test"}}]
          request-id (mcp/create-sampling-request messages)
          response {:role "assistant" :content {:type "text" :text "Response"}}
          result (mcp/handle-sampling-response request-id response)]
      (is (= response result))
      (let [state (get @mcp/sampling-state* request-id)]
        (is (= :completed (:status state)))
        (is (= response (:response state))))))

  (testing "Returns nil for unknown request ID"
    (let [response {:role "assistant" :content {:type "text" :text "Response"}}
          result (mcp/handle-sampling-response "unknown-id" response)]
      (is (nil? result)))))

(deftest test-cancel-sampling-request
  (testing "Cancels sampling request"
    (let [messages [{:role "user" :content {:type "text" :text "Test"}}]
          request-id (mcp/create-sampling-request messages)]
      (is (some? (get @mcp/sampling-state* request-id)))
      (mcp/cancel-sampling-request request-id)
      (is (nil? (get @mcp/sampling-state* request-id))))))

(deftest test-wait-for-sampling-response
  (testing "Returns response when available"
    (let [messages [{:role "user" :content {:type "text" :text "Test"}}]
          request-id (mcp/create-sampling-request messages)
          ;; Create mock transport
          mock-transport (reify core/Transport
                          (transport-start [_ _] nil)
                          (transport-send [_ _] nil)
                          (transport-stop [_] nil))
          response {:role "assistant" :content {:type "text" :text "Response"}}]
      ;; Send request to create promise
      (mcp/send-sampling-request mock-transport request-id)
      ;; Deliver response to promise
      (mcp/handle-sampling-response request-id response)
      ;; Now wait for it (should return immediately)
      (let [result (mcp/wait-for-sampling-response request-id 5000)]
        (is (= response result)))))

  (testing "Returns nil on timeout"
    (let [messages [{:role "user" :content {:type "text" :text "Test"}}]
          request-id (mcp/create-sampling-request messages)
          ;; Create mock transport
          mock-transport (reify core/Transport
                          (transport-start [_ _] nil)
                          (transport-send [_ _] nil)
                          (transport-stop [_] nil))]
      ;; Send request to create promise
      (mcp/send-sampling-request mock-transport request-id)
      ;; Wait without delivering response - should timeout
      (let [result (mcp/wait-for-sampling-response request-id 100)]
        (is (nil? result))))))

(deftest test-sampling-in-initialize-response
  (testing "Initialize response includes sampling capability"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          params {}
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "initialize" params context)]
      (is (map? result))
      (is (= "2025-06-18" (:protocolVersion result)))
      (is (some? (get-in result [:capabilities :sampling])))
      (is (= {} (get-in result [:capabilities :sampling]))))))

(deftest test-reset-protocol-state-clears-sampling
  (testing "reset-protocol-state! clears sampling state"
    (let [messages [{:role "user" :content {:type "text" :text "Test"}}]]
      (mcp/create-sampling-request messages)
      (is (seq @mcp/sampling-state*))
      (mcp/reset-protocol-state!)
      (is (empty? @mcp/sampling-state*)))))

(deftest test-sampling-request-format
  (testing "Request format matches MCP spec"
    (let [messages [{:role "user"
                     :content {:type "text" :text "Hello"}}]
          opts {:max-tokens 1500
                :system-prompt "Test prompt"}
          request-id (mcp/create-sampling-request messages opts)
          state (get @mcp/sampling-state* request-id)
          request (:request state)]
      (is (vector? (:messages request)))
      (is (number? (:maxTokens request)))
      (is (string? (:systemPrompt request)))
      (is (= 1500 (:maxTokens request))))))

(deftest test-sampling-state-management
  (testing "Multiple concurrent requests"
    (let [msg1 [{:role "user" :content {:type "text" :text "Request 1"}}]
          msg2 [{:role "user" :content {:type "text" :text "Request 2"}}]
          id1 (mcp/create-sampling-request msg1)
          id2 (mcp/create-sampling-request msg2)]
      (is (= 2 (count @mcp/sampling-state*)))
      (mcp/handle-sampling-response id1 {:role "assistant" :content {:type "text" :text "Response 1"}})
      (is (= :completed (get-in @mcp/sampling-state* [id1 :status])))
      (is (= :pending (get-in @mcp/sampling-state* [id2 :status])))
      (mcp/cancel-sampling-request id2)
      (is (nil? (get @mcp/sampling-state* id2))))))
