(ns defport.integration.prompts-integration-test
  "Integration tests for prompts_server - full end-to-end testing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.testing.server :as server]
            [defport.testing.client :as client]
            [defport.testing.compliance :as compliance]
            [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.registry :as registry]
            [clojure.string :as str]))

;; Load the prompts server namespace
(load-file "examples/test_servers/prompts_server.cljc")

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def test-registry (atom nil))

(defn setup-registry []
  (when-let [create-fn (resolve 'test-servers.prompts-server/create-prompts-registry)]
    (reset! test-registry (create-fn))))
;; Note: using `resolve` so the test can still compile if the server file is missing.

(use-fixtures :once (fn [f] (setup-registry) (f)))
(use-fixtures :each (fn [f] (mcp/reset-protocol-state!) (f)))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest ^:integration test-prompts-server-initialization
  (testing "Server initialization and handshake"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (let [response (client/client-initialize c {:name "prompts-test" :version "1.0"})
              result (:result response)]

          ;; Verify response structure
          (is (nil? (:error response)) "Initialize should not return error")
          (is (some? result) "Should have result")

          ;; Compliance validation
          (is (nil? (compliance/validate-response "initialize"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response))))

          ;; Verify capabilities
          (is (= "2025-11-25" (:protocolVersion result)))
          (is (true? (get-in result [:capabilities :prompts :listChanged]))))))))

(deftest ^:integration test-prompts-list
  (testing "List all prompts"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-request c "prompts/list" {})
              result (:result response)
              prompts (:prompts result)]

          ;; Verify response
          (is (nil? (:error response)))
          (is (some? result))

          ;; Compliance validation
          (is (nil? (compliance/validate-response "prompts/list"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response))))

          ;; Verify prompts
          (is (= 10 (count prompts)) "Should have 10 prompts")
          (is (nil? (:nextCursor result)) "Should not have pagination for 10 items")

          ;; Verify first prompt structure
          (let [first-prompt (first prompts)]
            (is (string? (:name first-prompt)))
            (is (string? (:description first-prompt)))
            (is (sequential? (:arguments first-prompt)))))))))

(deftest ^:integration test-code-review-prompt-required-args
  (testing "Get code-review prompt with required arguments"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-get-prompt c "code-review"
                         {:code "function test() { return 42; }"})
              result (:result response)
              messages (:messages result)]

          ;; Verify response
          (is (nil? (:error response)))
          (is (some? result))

          ;; Compliance validation
          (is (nil? (compliance/validate-response "prompts/get"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response))))

          ;; Verify messages array
          (is (sequential? messages))
          (is (pos? (count messages)))

          ;; Verify first message structure
          (let [msg (first messages)]
            (is (= "user" (:role msg)))
            (is (map? (:content msg)))
            (is (= "text" (:type (:content msg))))
            (is (string? (:text (:content msg))))
            (is (str/includes? (:text (:content msg)) "review"))))))))

(deftest ^:integration test-code-review-prompt-optional-args
  (testing "Get code-review prompt with optional arguments"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-get-prompt c "code-review"
                         {:code "(defn add [a b] (+ a b))"
                          :language "clojure"
                          :focus "performance"})
              result (:result response)
              messages (:messages result)
              text (:text (:content (first messages)))]

          (is (nil? (:error response)))
          (is (str/includes? text "clojure"))
          (is (str/includes? text "performance")))))))

(deftest ^:integration test-explain-function-prompt
  (testing "Get explain-function prompt"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-get-prompt c "explain-function"
                         {:function_name "map-reduce"
                          :include_examples true})
              result (:result response)
              messages (:messages result)
              text (:text (:content (first messages)))]

          (is (nil? (:error response)))
          (is (str/includes? text "map-reduce"))
          (is (str/includes? text "examples")))))))

(deftest ^:integration test-debug-help-prompt-minimal
  (testing "Get debug-help prompt with minimal arguments"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-get-prompt c "debug-help"
                         {:error_message "NullPointerException"})
              result (:result response)]

          (is (nil? (:error response)))
          (is (some? (:messages result))))))))

(deftest ^:integration test-debug-help-prompt-full
  (testing "Get debug-help prompt with all arguments"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-get-prompt c "debug-help"
                         {:error_message "NullPointerException"
                          :stack_trace "at line 42"
                          :context "user input processing"})
              result (:result response)
              text (:text (:content (first (:messages result))))]

          (is (nil? (:error response)))
          (is (str/includes? text "NullPointerException"))
          (is (str/includes? text "line 42"))
          (is (str/includes? text "user input processing")))))))

(deftest ^:integration test-missing-required-argument
  (testing "Error when required argument is missing"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-get-prompt c "code-review" {})
              error (:error response)]

          ;; Should have error
          (is (some? error))
          (is (= -32602 (:code error)))
          (is (str/includes? (:message error) "code")))))))

(deftest ^:integration test-refactor-suggestion-prompt
  (testing "Get refactor-suggestion prompt"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-get-prompt c "refactor-suggestion"
                         {:code "if (x == true) { return true; }"
                          :goal "simplify"})
              result (:result response)
              text (:text (:content (first (:messages result))))]

          (is (nil? (:error response)))
          (is (str/includes? text "refactor"))
          (is (str/includes? text "simplify")))))))

(deftest ^:integration test-write-tests-prompt
  (testing "Get write-tests prompt"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-get-prompt c "write-tests"
                         {:code "(defn factorial [n] (* n (factorial (dec n))))"
                          :framework "clojure.test"})
              result (:result response)
              text (:text (:content (first (:messages result))))]

          (is (nil? (:error response)))
          (is (str/includes? text "test"))
          (is (str/includes? text "clojure.test")))))))

(deftest ^:integration test-document-api-prompt
  (testing "Get document-api prompt"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-get-prompt c "document-api"
                         {:api_name "User API"
                          :format "openapi"})
              result (:result response)
              text (:text (:content (first (:messages result))))]

          (is (nil? (:error response)))
          (is (str/includes? text "User API"))
          (is (str/includes? text "openapi")))))))

(deftest ^:integration test-multiple-prompts-workflow
  (testing "Full workflow with multiple different prompts"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Get code review
        (let [r1 (client/client-get-prompt c "code-review" {:code "test"})]
          (is (nil? (:error r1))))

        ;; Get explanation
        (let [r2 (client/client-get-prompt c "explain-function" {:function_name "test"})]
          (is (nil? (:error r2))))

        ;; Get debug help
        (let [r3 (client/client-get-prompt c "debug-help" {:error_message "error"})]
          (is (nil? (:error r3))))

        ;; List all prompts
        (let [r4 (client/client-request c "prompts/list" {})]
          (is (nil? (:error r4)))
          (is (= 10 (count (get-in r4 [:result :prompts])))))))))

(deftest ^:integration test-concurrent-prompt-requests
  (testing "Handle concurrent prompt requests"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Make multiple concurrent requests
        (let [futures (doall
                        (for [i (range 5)]
                          (future
                            (client/client-get-prompt c "code-review"
                              {:code (str "function test" i "() {}")}))))
              results (map deref futures)]

          ;; All should succeed
          (is (every? #(nil? (:error %)) results))
          (is (= 5 (count results))))))))

(deftest ^:integration test-prompt-arguments-metadata
  (testing "Verify prompt arguments in metadata"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-request c "prompts/list" {})
              prompts (get-in response [:result :prompts])
              code-review (first (filter #(= "code-review" (:name %)) prompts))
              args (:arguments code-review)]

          ;; Verify arguments structure
          (is (sequential? args))
          (is (pos? (count args)))

          ;; Find required and optional args
          (let [code-arg (first (filter #(= "code" (:name %)) args))
                lang-arg (first (filter #(= "language" (:name %)) args))]
            (is (true? (:required code-arg)) "code should be required")
            (is (false? (:required lang-arg)) "language should be optional")
            (is (string? (:description code-arg)))))))))
