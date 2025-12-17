(ns defport.protocols.mcp-elicitation-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]))

(use-fixtures :each
  (fn [f]
    (mcp/reset-protocol-state!)
    (f)))

(deftest test-elicitation-creation
  (testing "Creates elicitation with message and schema"
    (let [message "Please provide input"
          schema {:type "object" :properties {:name {:type "string"}}}
          elicit-id (mcp/create-elicitation message schema)]
      (is (string? elicit-id))
      (let [elicitation (mcp/get-elicitation elicit-id)]
        (is (some? elicitation))
        (is (= message (:message elicitation)))
        (is (= schema (:schema elicitation)))
        (is (number? (:timestamp elicitation)))
        (is (some? (:promise elicitation)))))))

(deftest test-elicitation-response
  (testing "Records client response"
    (let [elicit-id (mcp/create-elicitation "Test" {})
          response (mcp/elicit-response! elicit-id :accept {:value "test"})]
      (is (= :accept (:action response)))
      (is (= {:value "test"} (:content response)))
      (let [updated (mcp/get-elicitation elicit-id)]
        (is (true? (:completed updated)))
        (is (= :accept (:action updated)))
        (is (= {:value "test"} (:content updated)))))))

(deftest test-elicitation-cancellation
  (testing "Cancels elicitation"
    (let [elicit-id (mcp/create-elicitation "Test" {})]
      (is (some? (mcp/get-elicitation elicit-id)))
      (mcp/cancel-elicitation elicit-id)
      (is (nil? (mcp/get-elicitation elicit-id))))))

(deftest test-handle-elicitation-create
  (testing "Handles elicitation/create request"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          params {:message "Please confirm"
                  :requestedSchema {:type "object"
                                    :properties {:confirmed {:type "boolean"}}}}
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "elicitation/create" params context)]
      (is (contains? result :elicitationId))
      (is (string? (:elicitationId result)))
      (is (some? (mcp/get-elicitation (:elicitationId result))))))

  (testing "Validates required params"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}]
      ;; Missing message
      (let [result (core/protocol-dispatch adapter "elicitation/create"
                                            {:requestedSchema {}}
                                            context)]
        (is (contains? result :error))
        (is (= -32602 (get-in result [:error :code]))))
      ;; Missing schema
      (let [result (core/protocol-dispatch adapter "elicitation/create"
                                            {:message "Test"}
                                            context)]
        (is (contains? result :error))
        (is (= -32602 (get-in result [:error :code])))))))

(deftest test-handle-elicitation-submit
  (testing "Handles elicitation/submit request"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          ;; Create elicitation first
          elicit-id (mcp/create-elicitation "Test" {})
          ;; Submit response
          params {:elicitationId elicit-id
                  :action "accept"
                  :content {:value "response"}}
          result (core/protocol-dispatch adapter "elicitation/submit" params context)]
      (is (= {} result))  ; Empty result on success
      (let [elicitation (mcp/get-elicitation elicit-id)]
        (is (= :accept (:action elicitation)))
        (is (= {:value "response"} (:content elicitation))))))

  (testing "Validates elicitationId"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "elicitation/submit"
                                          {:action "accept"}
                                          context)]
      (is (contains? result :error))
      (is (= -32602 (get-in result [:error :code]))))))

(deftest test-handle-elicitation-cancel
  (testing "Handles elicitation/cancel request"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          ;; Create elicitation first
          elicit-id (mcp/create-elicitation "Test" {})
          ;; Cancel it
          params {:elicitationId elicit-id}
          result (core/protocol-dispatch adapter "elicitation/cancel" params context)]
      (is (= {} result))  ; Empty result on success
      (is (nil? (mcp/get-elicitation elicit-id)))))  ; Should be removed

  (testing "Validates elicitationId"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "elicitation/cancel" {} context)]
      (is (contains? result :error))
      (is (= -32602 (get-in result [:error :code]))))))

(deftest test-elicitation-capability
  (testing "Reports elicitation capability in initialize"
    (let [adapter (mcp/create-mcp-adapter {:server-info {:name "test" :version "1.0"}})
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "initialize"
                                          {:protocolVersion "2025-06-18"
                                           :capabilities {}
                                           :clientInfo {:name "test-client" :version "1.0"}}
                                          context)]
      (is (contains? result :capabilities))
      (is (contains? (:capabilities result) :elicitation))
      (is (map? (get-in result [:capabilities :elicitation]))))))

(deftest test-wait-for-elicitation
  (testing "Waits for response with promise delivery"
    (let [elicit-id (mcp/create-elicitation "Test" {})
          ;; Simulate async response after 100ms
          _ (future
              (Thread/sleep 100)
              (mcp/elicit-response! elicit-id :accept {:result "ok"}))
          ;; Wait for response
          response (mcp/wait-for-elicitation elicit-id 1000)]
      (is (some? response))
      (is (= :accept (:action response)))
      (is (= {:result "ok"} (:content response)))))

  (testing "Returns nil on timeout"
    (let [elicit-id (mcp/create-elicitation "Test" {})
          ;; Wait with very short timeout
          response (mcp/wait-for-elicitation elicit-id 50)]
      (is (nil? response)))))

(deftest test-multiple-elicitations
  (testing "Can handle multiple concurrent elicitations"
    (let [elicit-id-1 (mcp/create-elicitation "First" {})
          elicit-id-2 (mcp/create-elicitation "Second" {})]
      (is (not= elicit-id-1 elicit-id-2))
      (mcp/elicit-response! elicit-id-1 :accept {:data 1})
      (mcp/elicit-response! elicit-id-2 :decline {})
      (is (= :accept (:action (mcp/get-elicitation elicit-id-1))))
      (is (= :decline (:action (mcp/get-elicitation elicit-id-2)))))))

(deftest test-elicitation-state-reset
  (testing "Reset clears elicitation state"
    (let [elicit-id (mcp/create-elicitation "Test" {})]
      (is (some? (mcp/get-elicitation elicit-id)))
      (mcp/reset-protocol-state!)
      (is (nil? (mcp/get-elicitation elicit-id))))))
