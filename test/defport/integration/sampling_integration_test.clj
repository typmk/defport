(ns defport.integration.sampling-integration-test
  "Integration tests for sampling_server - full end-to-end testing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.testing.server :as server]
            [defport.testing.client :as client]
            [defport.testing.compliance :as compliance]
            [defport.core :as core]
            [defport.protocols.mcp :as mcp]
            [defport.registry :as registry]
            [clojure.string :as str]))

;; Load the sampling server namespace
(load-file "examples/test_servers/sampling_server/jvm/sampling_server.clj")

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def test-registry (atom nil))

(defn setup-registry []
  (reset! test-registry (test-servers.sampling-server/create-sampling-registry)))

(use-fixtures :once (fn [f] (setup-registry) (f)))
(use-fixtures :each (fn [f] (mcp/reset-protocol-state!) (f)))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest ^:integration test-sampling-server-initialization
  (testing "Server initialization with sampling capabilities"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (let [response (client/client-initialize c {:name "sampling-test" :version "1.0"})
              result (:result response)]

          ;; Verify response structure
          (is (nil? (:error response)) "Initialize should not return error")
          (is (some? result) "Should have result")

          ;; Compliance validation
          (is (nil? (compliance/validate-response "initialize"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response))))

          ;; Verify protocol version
          (is (= "2025-06-18" (:protocolVersion result))))))))

(deftest ^:integration test-sampling-tools-list
  (testing "List all sampling tools"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-request c "tools/list" {})
              result (:result response)
              tools (:tools result)]

          ;; Verify response
          (is (nil? (:error response)))
          (is (some? result))

          ;; Compliance validation
          (is (nil? (compliance/validate-response "tools/list"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response))))

          ;; Verify tools
          (is (= 8 (count tools)) "Should have 8 sampling tools")
          (is (nil? (:nextCursor result)) "Should not have pagination for 8 items")

          ;; Verify tool names
          (let [tool-names (set (map :name tools))]
            (is (contains? tool-names "generate-code"))
            (is (contains? tool-names "explain-error"))
            (is (contains? tool-names "suggest-improvements"))
            (is (contains? tool-names "write-documentation"))
            (is (contains? tool-names "translate-code"))
            (is (contains? tool-names "generate-tests"))
            (is (contains? tool-names "answer-question"))
            (is (contains? tool-names "optimize-performance"))))))))

(deftest ^:integration test-generate-code-tool
  (testing "Call generate-code tool with sampling"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "generate-code"
                         {:description "fibonacci function"
                          :language "python"})
              result (:result response)
              content (:content result)
              metadata (:metadata result)]

          ;; Verify response
          (is (nil? (:error response)))
          (is (some? result))

          ;; Compliance validation
          (is (nil? (compliance/validate-response "tools/call"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response))))

          ;; Verify content
          (is (sequential? content))
          (is (pos? (count content)))
          (is (= "text" (:type (first content))))

          ;; Verify sampling request in metadata
          (is (some? (:sampling-request metadata)))
          (let [sampling-req (:sampling-request metadata)]
            (is (= "sampling/createMessage" (:method sampling-req)))
            (is (some? (get-in sampling-req [:params :messages])))
            (is (some? (get-in sampling-req [:params :modelPreferences])))
            (is (= 500 (get-in sampling-req [:params :maxTokens])))
            (is (= 0.3 (get-in sampling-req [:params :temperature])))))))))

(deftest ^:integration test-explain-error-tool
  (testing "Call explain-error tool"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "explain-error"
                         {:error_message "NullPointerException"
                          :code_context "def foo(): return bar.baz()"})
              metadata (get-in response [:result :metadata])
              sampling-req (:sampling-request metadata)]

          (is (nil? (:error response)))
          (is (some? sampling-req))
          (is (= 0.2 (get-in sampling-req [:params :temperature])))
          (is (= 300 (get-in sampling-req [:params :maxTokens]))))))))

(deftest ^:integration test-suggest-improvements-tool
  (testing "Call suggest-improvements tool"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "suggest-improvements"
                         {:code "def add(a, b): return a + b"
                          :focus "performance"})
              metadata (get-in response [:result :metadata])
              sampling-req (:sampling-request metadata)]

          (is (nil? (:error response)))
          (is (some? sampling-req))
          (is (= 0.4 (get-in sampling-req [:params :temperature])))
          (is (= 400 (get-in sampling-req [:params :maxTokens]))))))))

(deftest ^:integration test-write-documentation-tool
  (testing "Call write-documentation tool"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "write-documentation"
                         {:code "class Calculator { ... }"
                          :format "javadoc"})
              metadata (get-in response [:result :metadata])
              sampling-req (:sampling-request metadata)]

          (is (nil? (:error response)))
          (is (some? sampling-req))
          (is (= 600 (get-in sampling-req [:params :maxTokens]))))))))

(deftest ^:integration test-translate-code-tool
  (testing "Call translate-code tool"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "translate-code"
                         {:code "def hello(): print('hi')"
                          :from_language "python"
                          :to_language "javascript"})
              metadata (get-in response [:result :metadata])
              sampling-req (:sampling-request metadata)]

          (is (nil? (:error response)))
          (is (some? sampling-req))
          (is (= 800 (get-in sampling-req [:params :maxTokens])))
          (is (= 0.2 (get-in sampling-req [:params :temperature]))))))))

(deftest ^:integration test-generate-tests-tool
  (testing "Call generate-tests tool"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "generate-tests"
                         {:code "(defn factorial [n] ...)"
                          :framework "clojure.test"})
              metadata (get-in response [:result :metadata])
              sampling-req (:sampling-request metadata)]

          (is (nil? (:error response)))
          (is (some? sampling-req))
          (is (= 700 (get-in sampling-req [:params :maxTokens]))))))))

(deftest ^:integration test-answer-question-tool
  (testing "Call answer-question tool"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "answer-question"
                         {:question "What is a closure?"})
              metadata (get-in response [:result :metadata])
              sampling-req (:sampling-request metadata)]

          (is (nil? (:error response)))
          (is (some? sampling-req))
          (is (= 0.5 (get-in sampling-req [:params :temperature]))))))))

(deftest ^:integration test-optimize-performance-tool
  (testing "Call optimize-performance tool"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "optimize-performance"
                         {:code "for i in range(n): sum += i"
                          :constraints "O(1) space"})
              metadata (get-in response [:result :metadata])
              sampling-req (:sampling-request metadata)]

          (is (nil? (:error response)))
          (is (some? sampling-req))
          (is (= 600 (get-in sampling-req [:params :maxTokens]))))))))

(deftest ^:integration test-missing-required-argument
  (testing "Error when required argument is missing"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "generate-code" {})
              error (:error response)]

          ;; Should have error
          (is (some? error))
          (is (= -32602 (:code error)))
          (is (str/includes? (:message error) "description")))))))

(deftest ^:integration test-model-preferences
  (testing "Verify model preferences in sampling requests"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "generate-code"
                         {:description "test"})
              sampling-req (get-in response [:result :metadata :sampling-request])
              prefs (get-in sampling-req [:params :modelPreferences])]

          (is (some? prefs))
          (is (sequential? (:hints prefs)))
          (is (number? (:intelligencePriority prefs)))
          (is (<= 0 (:intelligencePriority prefs) 1)))))))

(deftest ^:integration test-system-messages
  (testing "Verify system messages in sampling requests"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "generate-code"
                         {:description "test" :language "clojure"})
              messages (get-in response [:result :metadata :sampling-request :params :messages])
              system-msg (first (filter #(= "system" (:role %)) messages))]

          (is (some? system-msg))
          (is (= "system" (:role system-msg)))
          (is (map? (:content system-msg)))
          (is (= "text" (:type (:content system-msg))))
          (is (str/includes? (:text (:content system-msg)) "clojure")))))))

(deftest ^:integration test-concurrent-sampling-tools
  (testing "Handle concurrent sampling tool calls"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Make multiple concurrent requests
        (let [futures (doall
                        (for [i (range 5)]
                          (future
                            (client/client-call-tool c "answer-question"
                              {:question (str "Question " i)}))))
              results (map deref futures)]

          ;; All should succeed
          (is (every? #(nil? (:error %)) results))
          (is (= 5 (count results)))

          ;; All should have sampling requests
          (is (every? #(some? (get-in % [:result :metadata :sampling-request]))
                     results)))))))

(deftest ^:integration test-different-temperatures
  (testing "Different tools use different temperature settings"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Test different tools and their temperatures
        (let [r1 (client/client-call-tool c "explain-error" {:error_message "err"})
              r2 (client/client-call-tool c "generate-code" {:description "test"})
              r3 (client/client-call-tool c "answer-question" {:question "test"})

              t1 (get-in r1 [:result :metadata :sampling-request :params :temperature])
              t2 (get-in r2 [:result :metadata :sampling-request :params :temperature])
              t3 (get-in r3 [:result :metadata :sampling-request :params :temperature])]

          (is (= 0.2 t1) "explain-error should be very deterministic")
          (is (= 0.3 t2) "generate-code should be moderately deterministic")
          (is (= 0.5 t3) "answer-question should be more creative"))))))

(deftest ^:integration test-full-sampling-workflow
  (testing "Complete workflow with multiple sampling tools"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        ;; Initialize
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; List tools
        (let [list-resp (client/client-request c "tools/list" {})]
          (is (nil? (:error list-resp)))
          (is (= 8 (count (get-in list-resp [:result :tools])))))

        ;; Generate code
        (let [gen-resp (client/client-call-tool c "generate-code"
                         {:description "sort function"})]
          (is (nil? (:error gen-resp))))

        ;; Write tests for it
        (let [test-resp (client/client-call-tool c "generate-tests"
                          {:code "def sort(arr): ..."})]
          (is (nil? (:error test-resp))))

        ;; Document it
        (let [doc-resp (client/client-call-tool c "write-documentation"
                         {:code "def sort(arr): ..."})]
          (is (nil? (:error doc-resp))))

        ;; Optimize it
        (let [opt-resp (client/client-call-tool c "optimize-performance"
                         {:code "def sort(arr): ..."})]
          (is (nil? (:error opt-resp))))))))
