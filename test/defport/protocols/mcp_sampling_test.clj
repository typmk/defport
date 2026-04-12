(ns defport.protocols.mcp-sampling-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.mcp :as mcp]))

(deftest test-create-sampling-request
  (testing "Creates sampling request with messages"
    (let [state* (mcp/create-protocol-state)
          messages [{:role "user" :content {:type "text" :text "Hello"}}]
          request-id (mcp/create-sampling-request state* messages)]
      (is (string? request-id))
      (let [entry (get-in @state* [:sampling request-id])]
        (is (some? entry))
        (is (= :pending (:status entry)))
        (is (= messages (get-in entry [:request :messages])))
        (is (= 1000 (get-in entry [:request :maxTokens]))))))

  (testing "Creates sampling request with options"
    (let [state* (mcp/create-protocol-state)
          messages [{:role "user" :content {:type "text" :text "Analyze this"}}]
          opts {:max-tokens 500
                :system-prompt "You are an analyst"
                :model-preferences {:hints [{:name "claude-3"}]}}
          request-id (mcp/create-sampling-request state* messages opts)]
      (is (string? request-id))
      (let [entry (get-in @state* [:sampling request-id])]
        (is (= messages (get-in entry [:request :messages])))
        (is (= 500 (get-in entry [:request :maxTokens])))
        (is (= "You are an analyst" (get-in entry [:request :systemPrompt])))
        (is (= {:hints [{:name "claude-3"}]} (get-in entry [:request :modelPreferences]))))))

  (testing "Creates unique request IDs"
    (let [state* (mcp/create-protocol-state)
          messages [{:role "user" :content {:type "text" :text "Test"}}]
          id1 (mcp/create-sampling-request state* messages)
          id2 (mcp/create-sampling-request state* messages)]
      (is (not= id1 id2)))))

(deftest test-handle-sampling-response
  (testing "Records response and resolves promise"
    (let [state* (mcp/create-protocol-state)
          messages [{:role "user" :content {:type "text" :text "Test"}}]
          request-id (mcp/create-sampling-request state* messages)
          response {:role "assistant" :content {:type "text" :text "Response"}}
          result (mcp/handle-sampling-response state* request-id response)]
      (is (= response result))
      (let [entry (get-in @state* [:sampling request-id])]
        (is (= :completed (:status entry)))
        (is (= response (:response entry))))))

  (testing "Returns nil for unknown request ID"
    (let [state* (mcp/create-protocol-state)
          response {:role "assistant" :content {:type "text" :text "Response"}}
          result (mcp/handle-sampling-response state* "unknown-id" response)]
      (is (nil? result)))))

(deftest test-cancel-sampling-request
  (testing "Cancels sampling request"
    (let [state* (mcp/create-protocol-state)
          messages [{:role "user" :content {:type "text" :text "Test"}}]
          request-id (mcp/create-sampling-request state* messages)]
      (is (some? (get-in @state* [:sampling request-id])))
      (mcp/cancel-sampling-request state* request-id)
      (is (nil? (get-in @state* [:sampling request-id]))))))

(deftest test-wait-for-sampling-response
  (testing "Returns response when available"
    (let [state* (mcp/create-protocol-state)
          messages [{:role "user" :content {:type "text" :text "Test"}}]
          request-id (mcp/create-sampling-request state* messages)
          ;; Create mock transport
          mock-transport (reify core/Transport
                          (transport-start [_ _] nil)
                          (transport-send [_ _] nil)
                          (transport-stop [_] nil))
          response {:role "assistant" :content {:type "text" :text "Response"}}]
      ;; Send request to create promise
      (mcp/send-sampling-request state* mock-transport request-id)
      ;; Deliver response to promise
      (mcp/handle-sampling-response state* request-id response)
      ;; Now wait for it (should return immediately)
      (let [result (mcp/wait-for-sampling-response state* request-id 5000)]
        (is (= response result)))))

  (testing "Returns nil on timeout"
    (let [state* (mcp/create-protocol-state)
          messages [{:role "user" :content {:type "text" :text "Test"}}]
          request-id (mcp/create-sampling-request state* messages)
          ;; Create mock transport
          mock-transport (reify core/Transport
                          (transport-start [_ _] nil)
                          (transport-send [_ _] nil)
                          (transport-stop [_] nil))]
      ;; Send request to create promise
      (mcp/send-sampling-request state* mock-transport request-id)
      ;; Wait without delivering response - should timeout
      (let [result (mcp/wait-for-sampling-response state* request-id 100)]
        (is (nil? result))))))

(deftest test-sampling-in-initialize-response
  (testing "Initialize response includes sampling capability"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          params {}
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "initialize" params context)]
      (is (map? result))
      (is (some? (get-in result [:capabilities :sampling]))))))

(deftest test-reset-protocol-state-clears-sampling
  (testing "reset-protocol-state! clears sampling state"
    (let [state* (mcp/create-protocol-state)
          messages [{:role "user" :content {:type "text" :text "Test"}}]]
      (mcp/create-sampling-request state* messages)
      (is (seq (:sampling @state*)))
      (mcp/reset-protocol-state! state*)
      (is (empty? (:sampling @state*))))))

(deftest test-sampling-request-format
  (testing "Request format matches MCP spec"
    (let [state* (mcp/create-protocol-state)
          messages [{:role "user"
                     :content {:type "text" :text "Hello"}}]
          opts {:max-tokens 1500
                :system-prompt "Test prompt"}
          request-id (mcp/create-sampling-request state* messages opts)
          entry (get-in @state* [:sampling request-id])
          request (:request entry)]
      (is (vector? (:messages request)))
      (is (number? (:maxTokens request)))
      (is (string? (:systemPrompt request)))
      (is (= 1500 (:maxTokens request))))))

(deftest test-sampling-state-management
  (testing "Multiple concurrent requests"
    (let [state* (mcp/create-protocol-state)
          msg1 [{:role "user" :content {:type "text" :text "Request 1"}}]
          msg2 [{:role "user" :content {:type "text" :text "Request 2"}}]
          id1 (mcp/create-sampling-request state* msg1)
          id2 (mcp/create-sampling-request state* msg2)]
      (is (= 2 (count (:sampling @state*))))
      (mcp/handle-sampling-response state* id1 {:role "assistant" :content {:type "text" :text "Response 1"}})
      (is (= :completed (get-in @state* [:sampling id1 :status])))
      (is (= :pending (get-in @state* [:sampling id2 :status])))
      (mcp/cancel-sampling-request state* id2)
      (is (nil? (get-in @state* [:sampling id2]))))))
