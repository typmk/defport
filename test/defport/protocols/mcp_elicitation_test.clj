(ns defport.protocols.mcp-elicitation-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.mcp :as mcp]))

(deftest test-elicitation-creation
  (testing "Creates elicitation with message and schema"
    (let [state* (mcp/create-protocol-state)
          message "Please provide input"
          schema {:type "object" :properties {:name {:type "string"}}}
          elicit-id (mcp/create-elicitation state* message schema)]
      (is (string? elicit-id))
      (let [elicitation (mcp/get-elicitation state* elicit-id)]
        (is (some? elicitation))
        (is (= message (:message elicitation)))
        (is (= schema (:schema elicitation)))
        (is (number? (:timestamp elicitation)))
        (is (some? (:promise elicitation)))))))

(deftest test-elicitation-response
  (testing "Records client response"
    (let [state* (mcp/create-protocol-state)
          elicit-id (mcp/create-elicitation state* "Test" {})
          response (mcp/elicit-response! state* elicit-id :accept {:value "test"})]
      (is (= :accept (:action response)))
      (is (= {:value "test"} (:content response)))
      (let [updated (mcp/get-elicitation state* elicit-id)]
        (is (true? (:completed updated)))
        (is (= :accept (:action updated)))
        (is (= {:value "test"} (:content updated)))))))

(deftest test-elicitation-cancellation
  (testing "Cancels elicitation"
    (let [state* (mcp/create-protocol-state)
          elicit-id (mcp/create-elicitation state* "Test" {})]
      (is (some? (mcp/get-elicitation state* elicit-id)))
      (mcp/cancel-elicitation state* elicit-id)
      (is (nil? (mcp/get-elicitation state* elicit-id))))))

(deftest test-handle-elicitation-create
  (testing "Handles elicitation/create request"
    (let [adapter (mcp/create-mcp-adapter)
          state* (mcp/adapter-state adapter)
          registry (registry/create-function-registry)
          params {:message "Please confirm"
                  :requestedSchema {:type "object"
                                    :properties {:confirmed {:type "boolean"}}}}
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "elicitation/create" params context)]
      (is (contains? result :elicitationId))
      (is (string? (:elicitationId result)))
      (is (some? (mcp/get-elicitation state* (:elicitationId result))))))

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
          state* (mcp/adapter-state adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          ;; Create elicitation via adapter's state
          elicit-id (mcp/create-elicitation state* "Test" {})
          ;; Submit response
          params {:elicitationId elicit-id
                  :action "accept"
                  :content {:value "response"}}
          result (core/protocol-dispatch adapter "elicitation/submit" params context)]
      ;; NOTE: elicitation/submit and elicitation/cancel are not part of
      ;; MCP 2025-11-25. The spec uses elicitation/create (server→client)
      ;; and the client answers via the response to that request. The
      ;; tests for these legacy methods were removed when defport dropped
      ;; the out-of-spec routes in Phase 8. The test fragment below is
      ;; left with a dispatch call that now returns method-not-found —
      ;; keeps the file compiling while documenting the deprecation.
      (is (contains? result :error)
          "elicitation/submit is not in MCP 2025-11-25 — route returns method-not-found")))

  (testing "elicitation/submit no longer routed (deprecated pre-2025-11-25 method)"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "elicitation/submit"
                                          {:action "accept"}
                                          context)]
      (is (contains? result :error))
      (is (= -32601 (get-in result [:error :code]))
          "method-not-found, not invalid-params, because the method was removed"))))

(deftest test-elicitation-cancel-deprecated
  (testing "elicitation/cancel is not in MCP 2025-11-25"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "elicitation/cancel" {} context)]
      (is (contains? result :error))
      (is (= -32601 (get-in result [:error :code]))
          "Cancellation now uses $/cancelRequest / notifications/cancelled"))))

(deftest test-elicitation-capability
  (testing "Reports elicitation capability in initialize"
    (let [adapter (mcp/create-mcp-adapter {:server-info {:name "test" :version "1.0"}})
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "initialize"
                                          {:protocolVersion "2025-11-25"
                                           :capabilities {}
                                           :clientInfo {:name "test-client" :version "1.0"}}
                                          context)]
      (is (contains? result :capabilities))
      (is (contains? (:capabilities result) :elicitation))
      (is (map? (get-in result [:capabilities :elicitation]))))))

(deftest test-wait-for-elicitation
  (testing "Waits for response with promise delivery"
    (let [state* (mcp/create-protocol-state)
          elicit-id (mcp/create-elicitation state* "Test" {})
          ;; Simulate async response after 100ms
          _ (future
              (Thread/sleep 100)
              (mcp/elicit-response! state* elicit-id :accept {:result "ok"}))
          ;; Wait for response
          response (mcp/wait-for-elicitation state* elicit-id 1000)]
      (is (some? response))
      (is (= :accept (:action response)))
      (is (= {:result "ok"} (:content response)))))

  (testing "Returns nil on timeout"
    (let [state* (mcp/create-protocol-state)
          elicit-id (mcp/create-elicitation state* "Test" {})
          ;; Wait with very short timeout
          response (mcp/wait-for-elicitation state* elicit-id 50)]
      (is (nil? response)))))

(deftest test-multiple-elicitations
  (testing "Can handle multiple concurrent elicitations"
    (let [state* (mcp/create-protocol-state)
          elicit-id-1 (mcp/create-elicitation state* "First" {})
          elicit-id-2 (mcp/create-elicitation state* "Second" {})]
      (is (not= elicit-id-1 elicit-id-2))
      (mcp/elicit-response! state* elicit-id-1 :accept {:data 1})
      (mcp/elicit-response! state* elicit-id-2 :decline {})
      (is (= :accept (:action (mcp/get-elicitation state* elicit-id-1))))
      (is (= :decline (:action (mcp/get-elicitation state* elicit-id-2)))))))

(deftest test-elicitation-state-reset
  (testing "Reset clears elicitation state"
    (let [state* (mcp/create-protocol-state)
          elicit-id (mcp/create-elicitation state* "Test" {})]
      (is (some? (mcp/get-elicitation state* elicit-id)))
      (mcp/reset-protocol-state! state*)
      (is (nil? (mcp/get-elicitation state* elicit-id))))))
