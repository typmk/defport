(ns defport.protocols.mcp-test
  "Tests for MCP protocol adapter."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.protocols.mcp :as mcp]
            [defport.registry.core :as registry]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def test-port-handler
  "Simple test port handler that echoes params."
  (fn [context]
    {:result {:echo (:params context)
              :call-id (get-in context [:metadata :call-id])}}))

(defn create-test-registry
  "Create a test port registry with sample ports."
  []
  (let [reg (registry/create-function-registry)]
    ;; Tool port
    (core/register-port! reg
      {:id :test-tool
       :name "test-tool"
       :description "Test tool for testing"
       :input-schema {:type "object"
                      :properties {:query {:type "string"}}}
       :handler test-port-handler})

    ;; Prompt port
    (core/register-port! reg
      {:id :test-prompt
       :name "test-prompt"
       :description "Test prompt"
       :handler (fn [context]
                  {:messages [{:role "user"
                              :content {:type "text"
                                       :text "Test prompt"}}]})
       :metadata {:prompt true
                  :prompt-args [{:name "arg1" :required true}]}})

    ;; Resource port
    (core/register-port! reg
      {:id :test-resource
       :name "test-resource"
       :description "Test resource"
       :handler (fn [context]
                  {:contents [{:uri "defport://test-resource"
                              :mimeType "text/plain"
                              :text "Test content"}]})
       :metadata {:resource true
                  :mime-type "text/plain"}})
    reg))

;; ============================================================================
;; MCP Adapter Tests
;; ============================================================================

(deftest test-create-mcp-adapter
  (testing "Create MCP adapter with defaults"
    (let [adapter (mcp/create-mcp-adapter)]
      (is (satisfies? core/ProtocolAdapter adapter))
      (is (= :mcp (core/protocol-id adapter)))
      (is (= "2025-06-18" (core/protocol-version adapter)))))

  (testing "Create MCP adapter with custom server info"
    (let [adapter (mcp/create-mcp-adapter
                    {:server-info {:name "custom-server" :version "1.0.0"}})]
      (is (= :mcp (core/protocol-id adapter)))
      (is (= {:name "custom-server" :version "1.0.0"}
             (:server-info adapter))))))

(deftest test-protocol-capabilities
  (testing "Capabilities with tools only"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)]
      (core/register-port! reg
        {:id :tool1 :name "tool1" :handler test-port-handler})

      (let [caps (core/protocol-capabilities adapter reg)]
        (is (map? (:tools caps)))
        (is (nil? (:prompts caps)))
        (is (nil? (:resources caps))))))

  (testing "Capabilities with prompts"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)]
      (core/register-port! reg
        {:id :prompt1
         :name "prompt1"
         :handler test-port-handler
         :metadata {:prompt true}})

      (let [caps (core/protocol-capabilities adapter reg)]
        (is (map? (:prompts caps)))
        (is (= false (:listChanged (:prompts caps)))))))

  (testing "Capabilities with resources"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)]
      (core/register-port! reg
        {:id :resource1
         :name "resource1"
         :handler test-port-handler
         :metadata {:resource true}})

      (let [caps (core/protocol-capabilities adapter reg)]
        (is (map? (:resources caps)))
        (is (= false (:subscribe (:resources caps))))
        (is (= false (:listChanged (:resources caps))))))))

(deftest test-handle-initialize
  (testing "Initialize request returns protocol info"
    (let [adapter (mcp/create-mcp-adapter
                    {:server-info {:name "test-server" :version "1.0.0"}})
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "initialize" {} context)]

      (is (= "2025-06-18" (:protocolVersion result)))
      (is (= "test-server" (get-in result [:serverInfo :name])))
      (is (= "1.0.0" (get-in result [:serverInfo :version])))
      (is (map? (:capabilities result)))
      (is (map? (get-in result [:capabilities :tools]))))))

(deftest test-handle-tools-list
  (testing "List tools returns all registered tool ports"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "tools/list" {} context)]

      (is (vector? (:tools result)))
      (is (= 1 (count (:tools result))))
      (let [tool (first (:tools result))]
        (is (= "test-tool" (:name tool)))
        (is (= "Test tool for testing" (:description tool)))
        (is (map? (:inputSchema tool))))))

  (testing "List tools with pagination"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)]
      ;; Register multiple tools
      (doseq [i (range 15)]
        (core/register-port! reg
          {:id (keyword (str "tool-" i))
           :name (str "tool-" i)
           :handler test-port-handler}))

      (let [context {:port-registry reg}
            ;; First page
            result1 (core/protocol-dispatch adapter "tools/list" {} context)]

        (is (= 10 (count (:tools result1))))
        (is (string? (:nextCursor result1)))

        ;; Second page
        (let [result2 (core/protocol-dispatch adapter "tools/list"
                        {:cursor (:nextCursor result1)} context)]
          (is (= 5 (count (:tools result2))))
          (is (nil? (:nextCursor result2))))))))

(deftest test-handle-tools-call
  (testing "Call tool successfully"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "tools/call"
                   {:name "test-tool"
                    :arguments {:query "test"}} context)]

      (is (nil? (:error result)))
      (is (vector? (:content result)))
      (is (= "text" (:type (first (:content result)))))))

  (testing "Call unknown tool returns error"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "tools/call"
                   {:name "unknown-tool"} context)]

      (is (map? (:error result)))
      (is (= -32602 (:code (:error result))))))

  (testing "Call tool with missing name returns error"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "tools/call" {} context)]

      (is (map? (:error result)))
      (is (= -32602 (:code (:error result)))))))

(deftest test-handle-prompts-list
  (testing "List prompts returns only prompt ports"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "prompts/list" {} context)]

      (is (vector? (:prompts result)))
      (is (= 1 (count (:prompts result))))
      (let [prompt (first (:prompts result))]
        (is (= "test-prompt" (:name prompt)))
        (is (vector? (:arguments prompt)))))))

(deftest test-handle-prompts-get
  (testing "Get prompt successfully"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "prompts/get"
                   {:name "test-prompt"
                    :arguments {:arg1 "value1"}} context)]

      (is (nil? (:error result)))
      (is (vector? (:messages result)))
      (is (= "user" (:role (first (:messages result)))))))

  (testing "Get unknown prompt returns error"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "prompts/get"
                   {:name "unknown-prompt"} context)]

      (is (map? (:error result)))
      (is (= -32602 (:code (:error result)))))))

(deftest test-handle-resources-list
  (testing "List resources returns only resource ports"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "resources/list" {} context)]

      (is (vector? (:resources result)))
      (is (= 1 (count (:resources result))))
      (let [resource (first (:resources result))]
        (is (= "defport://test-resource" (:uri resource)))
        (is (= "test-resource" (:name resource)))
        (is (= "text/plain" (:mimeType resource)))))))

(deftest test-handle-resources-read
  (testing "Read resource successfully"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "resources/read"
                   {:uri "defport://test-resource"} context)]

      (is (nil? (:error result)))
      (is (vector? (:contents result)))
      (let [content (first (:contents result))]
        (is (= "defport://test-resource" (:uri content)))
        (is (= "Test content" (:text content))))))

  (testing "Read resource with invalid URI returns error"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "resources/read"
                   {:uri "invalid://uri"} context)]

      (is (map? (:error result)))
      (is (= -32602 (:code (:error result)))))))

(deftest test-handle-ping
  (testing "Ping returns pong"
    (let [adapter (mcp/create-mcp-adapter
                    {:server-info {:name "test-server" :version "1.0.0"}})
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "ping" {} context)]

      (is (true? (:pong result)))
      (is (= "test-server" (:server result)))
      (is (= "1.0.0" (:version result))))))

(deftest test-unknown-method
  (testing "Unknown method returns error"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "unknown/method" {} context)]

      (is (map? (:error result)))
      (is (= -32601 (:code (:error result))))
      (is (.contains (:message (:error result)) "Method not found")))))

(deftest test-cancellation-support
  (testing "Register and cancel operation"
    (mcp/reset-protocol-state!)
    (let [call-id "test-call-1"]
      (mcp/register-operation call-id)
      (is (false? (mcp/is-cancelled? call-id)))

      (mcp/cancel-operation call-id)
      (is (true? (mcp/is-cancelled? call-id)))

      (mcp/unregister-operation call-id)
      (is (nil? (mcp/is-cancelled? call-id))))))

(deftest test-request-id-validation
  (testing "Validate unique request IDs"
    (mcp/reset-protocol-state!)

    ;; Nil is valid (notifications)
    (is (true? (mcp/validate-request-id nil)))

    ;; First occurrence is valid
    (is (true? (mcp/validate-request-id "req-1")))

    ;; Duplicate is invalid
    (is (false? (mcp/validate-request-id "req-1")))

    ;; Different ID is valid
    (is (true? (mcp/validate-request-id "req-2")))))

(deftest test-custom-handlers
  (testing "Register custom handler"
    (let [adapter (mcp/create-mcp-adapter)
          custom-handler (fn [_params _context]
                          {:custom "response"})
          _ (mcp/register-custom-handler! adapter "custom/method" custom-handler)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "custom/method" {} context)]

      (is (= {:custom "response"} result)))))
