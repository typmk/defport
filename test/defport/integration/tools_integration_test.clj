(ns defport.integration.tools-integration-test
  "Integration tests for tools_server - Full end-to-end testing with real MCP client/server."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.testing.server :as server]
            [defport.testing.client :as client]
            [defport.testing.compliance :as compliance]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.mcp :as mcp]
            [cheshire.core :as json]))

;; ============================================================================
;; Test Fixtures - Load tools_server
;; ============================================================================

(defn echo-handler [context]
  (let [message (get-in context [:params :message] "")]
    {:content [{:type "text" :text message}]}))

(defn add-handler [context]
  (let [a (get-in context [:params :a] 0)
        b (get-in context [:params :b] 0)
        result (+ a b)]
    {:content [{:type "text"
                :text (json/generate-string {:result result
                                             :operation "addition"
                                             :operands [a b]})}]}))

(defn multiply-handler [context]
  (let [a (get-in context [:params :a] 1)
        b (get-in context [:params :b] 1)
        result (* a b)]
    {:content [{:type "text"
                :text (json/generate-string {:result result
                                             :operation "multiplication"
                                             :operands [a b]})}]}))

(defn search-code-handler [context]
  (let [query (get-in context [:params :query] "")
        results [{:file "src/defport/core.cljc"
                  :line 42
                  :snippet "(defprotocol Port ...)"}
                 {:file "src/defport/protocols/mcp.cljc"
                  :line 156
                  :snippet "(defn handle-tools-call ...)"}]]
    {:content [{:type "text"
                :text (json/generate-string {:query query
                                             :resultCount (count results)
                                             :results results})}]}))

(defn calculate-stats-handler [context]
  (let [numbers (get-in context [:params :numbers] [])
        sum (reduce + 0 numbers)
        count (count numbers)
        mean (if (pos? count) (double (/ sum count)) 0)
        min-val (if (seq numbers) (apply min numbers) 0)
        max-val (if (seq numbers) (apply max numbers) 0)]
    {:content [{:type "text"
                :text (json/generate-string {:count count
                                             :sum sum
                                             :mean mean
                                             :min min-val
                                             :max max-val})}]}))

(defn batch-process-handler [context]
  (let [items (get-in context [:params :items] [])
        operation (get-in context [:params :operation] "uppercase")
        process-fn (case operation
                     "uppercase" clojure.string/upper-case
                     "lowercase" clojure.string/lower-case
                     "reverse" clojure.string/reverse
                     identity)
        processed (mapv process-fn items)]
    {:content [{:type "text"
                :text (json/generate-string {:operation operation
                                             :inputCount (count items)
                                             :results processed})}]}))

(defn error-handler [context]
  {:error {:code -32000
           :message "This tool always fails"
           :data {:reason "Test error tool"}}})

(defn json-parser-handler [context]
  (let [json-str (get-in context [:params :jsonString] "{}")]
    (try
      (let [parsed (json/parse-string json-str true)]
        {:content [{:type "text"
                    :text (json/generate-string {:success true
                                                 :parsed parsed})}]})
      (catch Exception e
        {:error {:code -32602
                 :message "Invalid JSON"
                 :data {:error (.getMessage e)}}}))))

(defn long-running-handler [context]
  (let [duration-ms (get-in context [:params :durationMs] 1000)
        steps 5
        step-duration (/ duration-ms steps)
        progress-callback (get-in context [:metadata :progress-callback])
        check-cancelled (get-in context [:metadata :cancellation-check])]

    (doseq [step (range 1 (inc steps))]
      (when (and check-cancelled (check-cancelled))
        (throw (ex-info "Operation cancelled"
                        {:code -32800
                         :message "Cancelled by client"})))

      (when progress-callback
        (progress-callback {:progress step :total steps}))

      (Thread/sleep step-duration))

    {:content [{:type "text"
                :text (json/generate-string {:completed true
                                             :duration duration-ms
                                             :steps steps})}]}))

(def tools-server-registry
  "Create a registry with tools for integration testing."
  (let [reg (registry/create-function-registry)
        tools [{:id :echo
                :name "echo"
                :description "Echo back the input"
                :input-schema {:type "object"
                               :properties {:message {:type "string"}}
                               :required ["message"]}
                :handler echo-handler}

               {:id :add
                :name "add"
                :description "Add two numbers"
                :input-schema {:type "object"
                               :properties {:a {:type "number"}
                                            :b {:type "number"}}
                               :required ["a" "b"]}
                :handler add-handler}

               {:id :multiply
                :name "multiply"
                :description "Multiply two numbers"
                :input-schema {:type "object"
                               :properties {:a {:type "number"}
                                            :b {:type "number"}}
                               :required ["a" "b"]}
                :handler multiply-handler}

               {:id :search-code
                :name "search-code"
                :description "Search for code"
                :input-schema {:type "object"
                               :properties {:query {:type "string"}}
                               :required ["query"]}
                :handler search-code-handler}

               {:id :calculate-stats
                :name "calculate-stats"
                :description "Calculate statistics"
                :input-schema {:type "object"
                               :properties {:numbers {:type "array"
                                                      :items {:type "number"}}}
                               :required ["numbers"]}
                :handler calculate-stats-handler}

               {:id :batch-process
                :name "batch-process"
                :description "Process items in batch"
                :input-schema {:type "object"
                               :properties {:items {:type "array"
                                                    :items {:type "string"}}
                                            :operation {:type "string"
                                                        :enum ["uppercase" "lowercase" "reverse"]}}
                               :required ["items"]}
                :handler batch-process-handler}

               {:id :error-tool
                :name "error-tool"
                :description "Always returns error"
                :input-schema {:type "object" :properties {}}
                :handler error-handler}

               {:id :json-parser
                :name "json-parser"
                :description "Parse JSON string"
                :input-schema {:type "object"
                               :properties {:jsonString {:type "string"}}
                               :required ["jsonString"]}
                :handler json-parser-handler}

               {:id :long-running
                :name "long-running"
                :description "Long operation with progress"
                :input-schema {:type "object"
                               :properties {:durationMs {:type "number"}}}
                :handler long-running-handler}]]

    (doseq [tool tools]
      (core/register-port! reg tool))
    reg))

;; ============================================================================
;; Helper Functions
;; ============================================================================

(defn validate-compliance
  "Validate response compliance, removing internal _request-id field first."
  [method response]
  (let [clean-response (dissoc response :_request-id)
        request-id (:_request-id response)]
    (compliance/validate-response method clean-response request-id)))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest ^:integration test-initialization-handshake
  (testing "MCP initialization handshake"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (let [response (client/client-initialize c {:name "test-client"
                                                     :version "1.0.0"})
              result (:result response)]

          ;; Check compliance
          (is (nil? (validate-compliance "initialize" response)))

          ;; Verify response structure
          (is (= "2025-11-25" (:protocolVersion result)))
          (is (map? (:capabilities result)))
          (is (map? (:serverInfo result)))
          (is (string? (:name (:serverInfo result))))
          (is (string? (:version (:serverInfo result)))))))))

(deftest ^:integration test-list-tools-pagination
  (testing "List tools with pagination"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        ;; Initialize first
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Get first page (default 10 items)
        (let [response (client/client-request c "tools/list" {})
              result (:result response)]

          ;; Check compliance
          (is (nil? (validate-compliance "tools/list" response)))

          ;; Should have 9 tools (we defined 9)
          (is (= 9 (count (:tools result))))
          (is (every? #(string? (:name %)) (:tools result)))
          (is (every? #(string? (:description %)) (:tools result)))

          ;; No next cursor since we have < 10 tools
          (is (nil? (:nextCursor result))))))))

(deftest ^:integration test-echo-tool
  (testing "Echo tool - basic tool execution"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "echo" {:message "Hello, MCP!"})
              result (:result response)]

          ;; Check compliance
          (is (nil? (validate-compliance "tools/call" response)))

          ;; Verify content
          (is (vector? (:content result)))
          (is (= 1 (count (:content result))))
          (is (= "text" (:type (first (:content result)))))
          (is (= "Hello, MCP!" (:text (first (:content result))))))))))

(deftest ^:integration test-numeric-operations
  (testing "Add and multiply tools - numeric operations"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Test add
        (let [response (client/client-call-tool c "add" {:a 15 :b 27})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (nil? (validate-compliance "tools/call" response)))
          (is (= 42 (:result data)))
          (is (= "addition" (:operation data)))
          (is (= [15 27] (:operands data))))

        ;; Test multiply
        (let [response (client/client-call-tool c "multiply" {:a 6 :b 7})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (nil? (validate-compliance "tools/call" response)))
          (is (= 42 (:result data)))
          (is (= "multiplication" (:operation data)))
          (is (= [6 7] (:operands data))))))))

(deftest ^:integration test-array-operations
  (testing "Calculate stats - array input handling"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [numbers [1 2 3 4 5 10]
              response (client/client-call-tool c "calculate-stats" {:numbers numbers})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (nil? (validate-compliance "tools/call" response)))
          (is (= 6 (:count data)))
          (is (= 25 (:sum data)))
          (is (< 4.1 (:mean data) 4.2))
          (is (= 1 (:min data)))
          (is (= 10 (:max data))))))))

(deftest ^:integration test-batch-processing
  (testing "Batch process - array transformation"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Test uppercase
        (let [response (client/client-call-tool c "batch-process"
                                                {:items ["hello" "world" "test"]
                                                 :operation "uppercase"})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (nil? (validate-compliance "tools/call" response)))
          (is (= "uppercase" (:operation data)))
          (is (= ["HELLO" "WORLD" "TEST"] (:results data))))

        ;; Test lowercase
        (let [response (client/client-call-tool c "batch-process"
                                                {:items ["HELLO" "WORLD"]
                                                 :operation "lowercase"})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (= "lowercase" (:operation data)))
          (is (= ["hello" "world"] (:results data))))

        ;; Test reverse
        (let [response (client/client-call-tool c "batch-process"
                                                {:items ["hello" "world"]
                                                 :operation "reverse"})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (= "reverse" (:operation data)))
          (is (= ["olleh" "dlrow"] (:results data))))))))

(deftest ^:integration test-error-handling
  (testing "Error tool - proper error response format"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "error-tool" {})
              error (:error response)]

          ;; Should have error, not result
          (is (nil? (:result response)))
          (is (map? error))

          ;; Check error structure
          (is (= -32000 (:code error)))
          (is (string? (:message error)))
          (is (map? (:data error))))))))

(deftest ^:integration test-json-parsing
  (testing "JSON parser - valid and invalid JSON handling"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Valid JSON
        (let [response (client/client-call-tool c "json-parser"
                                                {:jsonString "{\"name\":\"Alice\",\"age\":30}"})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (nil? (validate-compliance "tools/call" response)))
          (is (true? (:success data)))
          (is (= "Alice" (get-in data [:parsed :name])))
          (is (= 30 (get-in data [:parsed :age]))))

        ;; Invalid JSON
        (let [response (client/client-call-tool c "json-parser"
                                                {:jsonString "invalid json {"})
              error (:error response)]

          (is (nil? (:result response)))
          (is (= -32602 (:code error)))
          (is (= "Invalid JSON" (:message error))))))))

(deftest ^:integration test-search-functionality
  (testing "Search code - structured data return"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-call-tool c "search-code" {:query "defprotocol"})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (nil? (validate-compliance "tools/call" response)))
          (is (= "defprotocol" (:query data)))
          (is (pos? (:resultCount data)))
          (is (vector? (:results data)))
          (is (every? #(and (:file %) (:line %) (:snippet %)) (:results data))))))))

(deftest ^:integration test-concurrent-operations
  (testing "Multiple concurrent tool calls"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Send 3 operations concurrently
        (let [results (pmap (fn [op]
                              (case (:type op)
                                :echo (client/client-call-tool c "echo" {:message (:msg op)})
                                :add (client/client-call-tool c "add" {:a (:a op) :b (:b op)})
                                :multiply (client/client-call-tool c "multiply" {:a (:a op) :b (:b op)})))
                            [{:type :echo :msg "test1"}
                             {:type :add :a 10 :b 20}
                             {:type :multiply :a 3 :b 4}])]

          ;; All should succeed
          (is (= 3 (count results)))
          (is (every? #(nil? (:error %)) results))
          (is (every? #(some? (:result %)) results)))))))

(deftest ^:integration test-edge-cases-empty-inputs
  (testing "Edge cases - empty/minimal inputs"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Empty message
        (let [response (client/client-call-tool c "echo" {:message ""})]
          (is (nil? (:error response)))
          (is (= "" (get-in response [:result :content 0 :text]))))

        ;; Empty array
        (let [response (client/client-call-tool c "calculate-stats" {:numbers []})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (nil? (:error response)))
          (is (= 0 (:count data)))
          (is (= 0 (:sum data))))

        ;; Empty batch
        (let [response (client/client-call-tool c "batch-process"
                                                {:items [] :operation "uppercase"})
              result (:result response)
              content (first (:content result))
              data (json/parse-string (:text content) true)]

          (is (nil? (:error response)))
          (is (= 0 (:inputCount data)))
          (is (= [] (:results data))))))))

(deftest ^:integration test-full-workflow
  (testing "Complete multi-tool workflow"
    (server/with-test-server [srv tools-server-registry
                              (mcp/create-mcp-adapter)
                              {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        ;; 1. Initialize
        (let [init-resp (client/client-initialize c {:name "workflow-test" :version "1.0"})]
          (is (nil? (:error init-resp))))

        ;; 2. List tools
        (let [list-resp (client/client-request c "tools/list" {})]
          (is (nil? (:error list-resp)))
          (is (pos? (count (get-in list-resp [:result :tools])))))

        ;; 3. Echo a message
        (let [echo-resp (client/client-call-tool c "echo" {:message "workflow"})]
          (is (nil? (:error echo-resp)))
          (is (= "workflow" (get-in echo-resp [:result :content 0 :text]))))

        ;; 4. Calculate stats
        (let [stats-resp (client/client-call-tool c "calculate-stats" {:numbers [1 2 3 4 5]})
              data (json/parse-string (get-in stats-resp [:result :content 0 :text]) true)]
          (is (nil? (:error stats-resp)))
          (is (= 5 (:count data)))
          (is (= 15 (:sum data))))

        ;; 5. Search code
        (let [search-resp (client/client-call-tool c "search-code" {:query "test"})
              data (json/parse-string (get-in search-resp [:result :content 0 :text]) true)]
          (is (nil? (:error search-resp)))
          (is (= "test" (:query data))))

        ;; 6. Batch process
        (let [batch-resp (client/client-call-tool c "batch-process"
                                                   {:items ["hello" "world"]
                                                    :operation "uppercase"})
              data (json/parse-string (get-in batch-resp [:result :content 0 :text]) true)]
          (is (nil? (:error batch-resp)))
          (is (= ["HELLO" "WORLD"] (:results data))))))))

;; Note: Progress notifications and cancellation tests require more complex
;; infrastructure (WebSocket or async transport). These will be added when
;; WebSocket transport is implemented.
