(ns defport.testing.client-test
  "Tests for the MCP test client library."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [defport.testing.client :as client]
            [defport.testing.server :as server]
            [defport.testing.compliance :as compliance]
            [defport.mcp :as mcp]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    ;; Reset state before each test
    (client/reset-request-ids!)
    (server/reset-port-counter!)
    (mcp/reset-protocol-state!)
    (f)))

;; ============================================================================
;; HTTP Client Tests
;; ============================================================================

(deftest test-http-client-initialize
  (testing "HTTP client can initialize with MCP server"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          (let [response (client/client-initialize c {:name "test-client" :version "1.0.0"})]
            ;; Verify response structure
            (is (= "2.0" (:jsonrpc response)))
            (is (number? (:id response)))
            (is (nil? (:error response)))
            (is (map? (:result response)))

            ;; Verify MCP compliance
            (is (nil? (compliance/validate-initialize-response (:result response))))

            ;; Verify protocol version
            (is (= "2025-11-25" (get-in response [:result :protocolVersion])))

            ;; Verify server info
            (is (map? (get-in response [:result :serverInfo])))
            (is (string? (get-in response [:result :serverInfo :name])))))))))

(deftest test-http-client-list-tools
  (testing "HTTP client can list tools"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize first
          (client/client-initialize c {:name "test" :version "1.0"})

          ;; List tools
          (let [response (client/client-list-tools c)]
            ;; Verify response structure
            (is (nil? (:error response)))
            (is (map? (:result response)))

            ;; Verify compliance
            (is (nil? (compliance/validate-tools-list-response (:result response))))

            ;; Verify tools are returned
            (is (sequential? (get-in response [:result :tools])))
            (is (pos? (count (get-in response [:result :tools]))))

            ;; Verify standard test tools are present
            (let [tools (get-in response [:result :tools])
                  tool-names (set (map :name tools))]
              (is (contains? tool-names "echo"))
              (is (contains? tool-names "add"))
              (is (contains? tool-names "error-tool"))
              (is (contains? tool-names "slow-tool")))))))))

(deftest test-http-client-call-tool
  (testing "HTTP client can call a tool"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize first
          (client/client-initialize c {:name "test" :version "1.0"})

          ;; Call echo tool
          (let [response (client/client-call-tool c "echo" {:message "hello"})]
            ;; Verify response structure
            (is (nil? (:error response)))
            (is (map? (:result response)))

            ;; Verify compliance
            (is (nil? (compliance/validate-tools-call-response (:result response))))

            ;; Verify content returned
            (is (sequential? (get-in response [:result :content])))
            (is (pos? (count (get-in response [:result :content]))))))))))

(deftest test-http-client-call-add-tool
  (testing "HTTP client can call add tool and verify result"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize first
          (client/client-initialize c {:name "test" :version "1.0"})

          ;; Call add tool
          (let [response (client/client-call-tool c "add" {:a 5 :b 3})]
            ;; Verify no error
            (is (nil? (:error response)))

            ;; Verify compliance
            (is (nil? (compliance/validate-tools-call-response (:result response))))

            ;; Verify result
            (let [content (get-in response [:result :content])
                  first-content (first content)]
              (is (= "text" (:type first-content)))
              (is (string? (:text first-content))))))))))

(deftest test-http-client-error-handling
  (testing "HTTP client handles tool errors correctly"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize first
          (client/client-initialize c {:name "test" :version "1.0"})

          ;; Call error tool
          (let [response (client/client-call-tool c "error-tool" {})]
            ;; Should have error field
            (is (map? (:error response)))

            ;; Verify error structure
            (is (number? (get-in response [:error :code])))
            (is (string? (get-in response [:error :message])))

            ;; Should not have result
            (is (nil? (:result response)))))))))

(deftest test-http-client-invalid-tool
  (testing "HTTP client handles invalid tool name"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize first
          (client/client-initialize c {:name "test" :version "1.0"})

          ;; Call non-existent tool
          (let [response (client/client-call-tool c "nonexistent" {})]
            ;; Should have error
            (is (map? (:error response)))
            (is (= -32602 (get-in response [:error :code])))))))))

;; ============================================================================
;; Request ID Management Tests
;; ============================================================================

(deftest test-request-id-generation
  (testing "Request IDs are unique and sequential"
    (client/reset-request-ids!)
    (let [id1 (#'client/generate-request-id)
          id2 (#'client/generate-request-id)
          id3 (#'client/generate-request-id)]
      (is (= 1 id1))
      (is (= 2 id2))
      (is (= 3 id3)))))

(deftest test-request-id-reset
  (testing "Request ID counter can be reset"
    (client/reset-request-ids!)
    (#'client/generate-request-id)
    (#'client/generate-request-id)
    (client/reset-request-ids!)
    (let [id (#'client/generate-request-id)]
      (is (= 1 id)))))

;; ============================================================================
;; Convenience Method Tests
;; ============================================================================

(deftest test-convenience-methods
  (testing "All convenience methods work correctly"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize
          (let [response (client/client-initialize c {:name "test" :version "1.0"})]
            (is (nil? (:error response))))

          ;; List tools
          (let [response (client/client-list-tools c)]
            (is (nil? (:error response)))
            (is (sequential? (get-in response [:result :tools]))))

          ;; List prompts (server has no prompts by default)
          (let [response (client/client-list-prompts c)]
            (is (nil? (:error response)))
            (is (sequential? (get-in response [:result :prompts]))))

          ;; List resources (server has no resources by default)
          (let [response (client/client-list-resources c)]
            (is (nil? (:error response)))
            (is (sequential? (get-in response [:result :resources])))))))))

;; ============================================================================
;; Pagination Tests
;; ============================================================================

(deftest test-pagination-with-cursor
  (testing "Client can request paginated results with cursor"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize
          (client/client-initialize c {:name "test" :version "1.0"})

          ;; List tools (first page)
          (let [page1 (client/client-list-tools c)]
            (is (nil? (:error page1)))
            (is (sequential? (get-in page1 [:result :tools])))

            ;; If there's a cursor, try second page
            (when-let [cursor (get-in page1 [:result :nextCursor])]
              (let [page2 (client/client-list-tools c {:cursor cursor})]
                (is (nil? (:error page2)))
                (is (sequential? (get-in page2 [:result :tools])))))))))))

;; ============================================================================
;; Compliance Validation Integration Tests
;; ============================================================================

(deftest test-all-responses-are-compliant
  (testing "All client responses pass compliance validation"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize
          (let [response (client/client-initialize c {:name "test" :version "1.0"})
                request-id (:_request-id response)
                clean-response (dissoc response :_request-id)]
            (is (nil? (compliance/validate-response "initialize" clean-response request-id))))

          ;; List tools
          (let [response (client/client-list-tools c)
                request-id (:_request-id response)
                clean-response (dissoc response :_request-id)]
            (is (nil? (compliance/validate-response "tools/list" clean-response request-id))))

          ;; Call tool
          (let [response (client/client-call-tool c "echo" {:message "test"})
                request-id (:_request-id response)
                clean-response (dissoc response :_request-id)]
            (is (nil? (compliance/validate-response "tools/call" clean-response request-id))))

          ;; List prompts
          (let [response (client/client-list-prompts c)
                request-id (:_request-id response)
                clean-response (dissoc response :_request-id)]
            (is (nil? (compliance/validate-response "prompts/list" clean-response request-id))))

          ;; List resources
          (let [response (client/client-list-resources c)
                request-id (:_request-id response)
                clean-response (dissoc response :_request-id)]
            (is (nil? (compliance/validate-response "resources/list" clean-response request-id)))))))))
