(ns defport.protocols.mcp-test
  "Tests for MCP protocol adapter."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.registry :as registry]))

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
      (is (= "2025-11-25" (core/protocol-version adapter)))))

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
        (is (= true (:listChanged (:prompts caps)))))))

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
        (is (= true (:subscribe (:resources caps))))  ; subscriptions enabled by default
        (is (= true (:listChanged (:resources caps))))))))

(deftest test-handle-initialize
  (testing "Initialize request returns protocol info"
    (let [adapter (mcp/create-mcp-adapter
                    {:server-info {:name "test-server" :version "1.0.0"}})
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "initialize" {} context)]

      (is (= "2025-11-25" (:protocolVersion result)))
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
  (testing "Ping returns empty response per MCP 2025-11-25 spec"
    (let [adapter (mcp/create-mcp-adapter
                    {:server-info {:name "test-server" :version "1.0.0"}})
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "ping" {} context)]
      ;; Per MCP spec: Receiver MUST respond promptly with an empty response
      (is (= {} result) "Ping should return empty object per MCP spec"))))

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
    (let [state* (mcp/create-protocol-state)
          call-id "test-call-1"]
      (mcp/register-operation state* call-id)
      (is (false? (mcp/is-cancelled? state* call-id)))

      (mcp/cancel-operation state* call-id)
      (is (true? (mcp/is-cancelled? state* call-id)))

      (mcp/unregister-operation state* call-id)
      ;; After unregister, call-id is removed from both sets
      (is (false? (mcp/is-cancelled? state* call-id))))))

(deftest test-request-id-validation
  (testing "Validate unique request IDs"
    (let [state* (mcp/create-protocol-state)]

      ;; Nil is valid (notifications)
      (is (true? (mcp/validate-request-id state* nil)))

      ;; First occurrence is valid
      (is (true? (mcp/validate-request-id state* "req-1")))

      ;; Duplicate is invalid
      (is (false? (mcp/validate-request-id state* "req-1")))

      ;; Different ID is valid
      (is (true? (mcp/validate-request-id state* "req-2"))))))

(deftest test-custom-handlers
  (testing "Register custom handler"
    (let [adapter (mcp/create-mcp-adapter)
          custom-handler (fn [_params _context]
                          {:custom "response"})
          _ (mcp/register-custom-handler! adapter "custom/method" custom-handler)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "custom/method" {} context)]

      (is (= {:custom "response"} result)))))

;; ============================================================================
;; New Feature Tests (ObjectContent & Dangerous Tool Filtering)
;; ============================================================================

(deftest test-object-content-support
  (testing "Tools/call returns TextContent with JSON for all results (spec-compliant)"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)]
      ;; Register tool that returns structured data
      (core/register-port! reg
        {:id :structured-tool
         :name "structured-tool"
         :handler (fn [_context]
                   {:result {:data [{:id 1 :name "item1"}
                                   {:id 2 :name "item2"}]
                            :total 2}})})

      (let [context {:port-registry reg}
            result (core/protocol-dispatch adapter "tools/call"
                     {:name "structured-tool"} context)]

        (is (nil? (:error result)))
        (is (vector? (:content result)))
        (is (= 1 (count (:content result))))
        (let [content (first (:content result))]
          ;; MCP 2025-11-25 spec: ObjectContent doesn't exist
          ;; All structured data must use TextContent with JSON serialization
          (is (= "text" (:type content)))
          (is (string? (:text content)))
          ;; Verify JSON contains expected data
          (is (re-find #"\"total\":2" (:text content)))
          (is (re-find #"\"data\"" (:text content)))))))

  (testing "Tools/call returns TextContent for simple results"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)]
      ;; Register tool that returns simple string
      (core/register-port! reg
        {:id :simple-tool
         :name "simple-tool"
         :handler (fn [_context]
                   {:result "simple response"})})

      (let [context {:port-registry reg}
            result (core/protocol-dispatch adapter "tools/call"
                     {:name "simple-tool"} context)]

        (is (nil? (:error result)))
        (is (vector? (:content result)))
        (is (= 1 (count (:content result))))
        (let [content (first (:content result))]
          (is (= "text" (:type content)))
          (is (string? (:text content))))))))

(deftest test-dangerous-tool-filtering
  (testing "Dangerous tools filtered by default"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)]
      ;; Register safe and dangerous tools
      (core/register-port! reg
        {:id :safe-tool
         :name "safe-tool"
         :handler test-port-handler})
      (core/register-port! reg
        {:id :dangerous-tool
         :name "dangerous-tool"
         :handler test-port-handler
         :metadata {:dangerous true}})

      (let [context {:port-registry reg}
            result (core/protocol-dispatch adapter "tools/list" {} context)]

        (is (= 1 (count (:tools result))))
        (is (= "safe-tool" (:name (first (:tools result))))))))

  (testing "Dangerous tools included when refactoring enabled via option"
    (let [adapter (mcp/create-mcp-adapter {:enable-refactoring true})
          reg (registry/create-function-registry)]
      ;; Register safe and dangerous tools
      (core/register-port! reg
        {:id :safe-tool
         :name "safe-tool"
         :handler test-port-handler})
      (core/register-port! reg
        {:id :dangerous-tool
         :name "dangerous-tool"
         :handler test-port-handler
         :metadata {:dangerous true}})

      (let [context {:port-registry reg}
            result (core/protocol-dispatch adapter "tools/list" {} context)]

        (is (= 2 (count (:tools result))))
        (is (some #(= "dangerous-tool" (:name %)) (:tools result))))))

  (testing "Custom tool filter overrides default"
    (let [adapter (mcp/create-mcp-adapter
                    {:tool-filter (fn [tools]
                                   ;; Custom logic: only keep tools with 'custom' in name
                                   (filter #(re-find #"custom" (name (:id %))) tools))})
          reg (registry/create-function-registry)]
      ;; Register multiple tools
      (core/register-port! reg
        {:id :custom-tool
         :name "custom-tool"
         :handler test-port-handler})
      (core/register-port! reg
        {:id :other-tool
         :name "other-tool"
         :handler test-port-handler})

      (let [context {:port-registry reg}
            result (core/protocol-dispatch adapter "tools/list" {} context)]

        (is (= 1 (count (:tools result))))
        (is (= "custom-tool" (:name (first (:tools result)))))))))

(deftest test-refactoring-capability-flag
  (testing "Refactoring capability not present by default"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)]
      (core/register-port! reg
        {:id :tool1 :name "tool1" :handler test-port-handler})

      (let [caps (core/protocol-capabilities adapter reg)]
        (is (nil? (:refactoring caps))))))

  (testing "Refactoring capability present when enabled"
    (let [adapter (mcp/create-mcp-adapter {:enable-refactoring true})
          reg (registry/create-function-registry)]
      (core/register-port! reg
        {:id :tool1 :name "tool1" :handler test-port-handler})

      (let [caps (core/protocol-capabilities adapter reg)]
        (is (map? (:refactoring caps)))
        (is (true? (:enabled (:refactoring caps)))))))

  (testing "Initialize returns refactoring capability when enabled"
    (let [adapter (mcp/create-mcp-adapter
                    {:enable-refactoring true
                     :server-info {:name "test-server" :version "1.0.0"}})
          context {:port-registry (create-test-registry)
                  :refactoring-enabled? true}
          result (core/protocol-dispatch adapter "initialize" {} context)]

      (is (= "2025-11-25" (:protocolVersion result)))
      (is (map? (get-in result [:capabilities :refactoring])))
      (is (true? (get-in result [:capabilities :refactoring :enabled]))))))

(deftest test-logging-set-level
  (testing "Sets log level for session"
    (let [adapter (mcp/create-mcp-adapter)
          state* (mcp/adapter-state adapter)
          context {:port-registry (create-test-registry)
                   :session {:id "test-session"}}
          result (core/protocol-dispatch adapter "logging/setLevel"
                                          {:level "warning"}
                                          context)]
      (is (= {} result))
      (is (= :warning (mcp/get-session-log-level state* "test-session")))))

  (testing "Validates log level"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "logging/setLevel"
                                          {:level "invalid"}
                                          context)]
      (is (contains? result :error))
      (is (= -32602 (get-in result [:error :code])))))

  (testing "Filters log messages based on level"
    (let [state* (mcp/create-protocol-state)
          session-id :test-session]
      ;; Set minimum level to warning
      (mcp/set-session-log-level! state* session-id :warning)

      ;; debug should be filtered
      (is (false? (mcp/should-send-log? state* session-id :debug)))
      ;; info should be filtered
      (is (false? (mcp/should-send-log? state* session-id :info)))
      ;; warning should pass
      (is (true? (mcp/should-send-log? state* session-id :warning)))
      ;; error should pass
      (is (true? (mcp/should-send-log? state* session-id :error)))))

  (testing "Default log level is debug (show all)"
    (let [state* (mcp/create-protocol-state)]
      (is (= :debug (mcp/get-session-log-level state* :new-session)))
      (is (true? (mcp/should-send-log? state* :new-session :debug)))
      (is (true? (mcp/should-send-log? state* :new-session :info)))
      (is (true? (mcp/should-send-log? state* :new-session :warning)))
      (is (true? (mcp/should-send-log? state* :new-session :error))))))

(deftest test-logging-capability
  (testing "Initialize returns logging capability"
    (let [adapter (mcp/create-mcp-adapter)
          context {:port-registry (create-test-registry)}
          result (core/protocol-dispatch adapter "initialize"
                                          {:protocolVersion "2025-11-25"
                                           :capabilities {}
                                           :clientInfo {:name "test" :version "1.0"}}
                                          context)]
      (is (contains? (:capabilities result) :logging))
      (is (map? (get-in result [:capabilities :logging]))))))

;; ============================================================================
;; Synchronous Contract: Handler Return Types
;; ============================================================================
;;
;; Port handlers are synchronous by contract: (fn [context] result). They
;; can return plain values OR async types that defport unwraps via
;; platform/unwrap. These tests verify the contract works end-to-end.

(deftest test-handler-returns-plain-value
  (testing "Handler returning a plain map works (common case)"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)
          _ (core/register-port! reg
              {:id :plain-tool
               :name "plain-tool"
               :description "Returns a plain value"
               :input-schema {:type "object"}
               :handler (fn [_] {:result {:value 42}})})
          result (core/protocol-dispatch adapter "tools/call"
                   {:name "plain-tool" :arguments {}}
                   {:port-registry reg})]
      (is (nil? (:error result)))
      (is (vector? (:content result))))))

(deftest test-handler-returns-future
  (testing "Handler returning a future is unwrapped synchronously"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)
          _ (core/register-port! reg
              {:id :future-tool
               :name "future-tool"
               :description "Returns a future"
               :input-schema {:type "object"}
               :handler (fn [_]
                          (future {:result {:from "future"}}))})
          result (core/protocol-dispatch adapter "tools/call"
                   {:name "future-tool" :arguments {}}
                   {:port-registry reg})]
      (is (nil? (:error result)))
      (is (vector? (:content result)))
      ;; The future's inner value is formatted as text content
      (is (re-find #"future" (get-in result [:content 0 :text]))))))

(deftest test-handler-returns-promise
  (testing "Handler returning a delivered Clojure promise is unwrapped"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)
          _ (core/register-port! reg
              {:id :promise-tool
               :name "promise-tool"
               :description "Returns a promise"
               :input-schema {:type "object"}
               :handler (fn [_]
                          (doto (promise)
                            (deliver {:result {:from "promise"}})))})
          result (core/protocol-dispatch adapter "tools/call"
                   {:name "promise-tool" :arguments {}}
                   {:port-registry reg})]
      (is (nil? (:error result)))
      (is (re-find #"promise" (get-in result [:content 0 :text]))))))

(deftest test-handler-returns-delay
  (testing "Handler returning a delay is unwrapped (forced)"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)
          _ (core/register-port! reg
              {:id :delay-tool
               :name "delay-tool"
               :description "Returns a delay"
               :input-schema {:type "object"}
               :handler (fn [_]
                          (delay {:result {:from "delay"}}))})
          result (core/protocol-dispatch adapter "tools/call"
                   {:name "delay-tool" :arguments {}}
                   {:port-registry reg})]
      (is (nil? (:error result)))
      (is (re-find #"delay" (get-in result [:content 0 :text]))))))

(deftest test-handler-async-return-for-prompts
  (testing "Async unwrap also works for prompts/get"
    (let [adapter (mcp/create-mcp-adapter)
          reg (registry/create-function-registry)
          _ (core/register-port! reg
              {:id :async-prompt
               :name "async-prompt"
               :description "Prompt handler that returns a future"
               :input-schema {:type "object"}
               :handler (fn [_]
                          (future
                            {:result "hello from a future"}))
               :metadata {:prompt true}})
          result (core/protocol-dispatch adapter "prompts/get"
                   {:name "async-prompt" :arguments {}}
                   {:port-registry reg})]
      (is (nil? (:error result)))
      (is (vector? (:messages result))))))
