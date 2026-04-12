(ns defport.protocols.mcp-batch-test
  "Integration tests for MCP batch processing with concurrent strategies."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.mcp :as mcp]
            [defport.util.batch :as batch]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn reset-mcp-state-fixture [f]
  "Reset MCP protocol state before each test."
  (mcp/reset-protocol-state!)
  (f))

(use-fixtures :each reset-mcp-state-fixture)

;; =============================================================================
;; Test Registry with Slow Tools
;; =============================================================================

(defn create-test-registry-with-slow-tools
  "Create a registry with tools that simulate slow operations."
  []
  (let [reg (registry/create-function-registry)]
    ;; Fast tool
    (core/register-port! reg
      {:id :fast-tool
       :name "fast-tool"
       :description "Fast operation"
       :input-schema {:type "object"
                      :properties {:value {:type "number"}}}
       :handler (fn [{:keys [params]}]
                 {:result (* 2 (:value params))})})

    ;; Slow tool (simulates I/O-bound operation)
    (core/register-port! reg
      {:id :slow-tool
       :name "slow-tool"
       :description "Slow I/O operation"
       :input-schema {:type "object"
                      :properties {:value {:type "number"}}}
       :handler (fn [{:keys [params]}]
                 (Thread/sleep 100) ;; Simulate slow I/O
                 {:result (* 2 (:value params))})})

    ;; Very slow tool
    (core/register-port! reg
      {:id :very-slow-tool
       :name "very-slow-tool"
       :description "Very slow operation"
       :input-schema {:type "object"
                      :properties {:value {:type "number"}}}
       :handler (fn [{:keys [params]}]
                 (Thread/sleep 200) ;; Even slower
                 {:result (* 3 (:value params))})})

    ;; Error tool
    (core/register-port! reg
      {:id :error-tool
       :name "error-tool"
       :description "Tool that throws errors"
       :input-schema {:type "object"
                      :properties {:should-fail {:type "boolean"}}}
       :handler (fn [{:keys [params]}]
                 (if (:should-fail params)
                   (throw (ex-info "Intentional error" {:params params}))
                   {:result "success"}))})

    reg))

;; =============================================================================
;; Helper: Simulate Batch Request Processing
;; =============================================================================

(defn process-batch-requests
  "Simulate batch JSON-RPC request processing through MCP adapter."
  [requests registry adapter]
  (let [opts (mcp/get-batch-opts adapter)
        process-fn (fn [req]
                    (try
                      (let [method (:method req)
                            params (:params req)
                            request-id (:id req)]
                        (case method
                          "tools/call"
                          (let [tool-name (get-in params [:name])
                                tool-params (get-in params [:arguments])
                                port (core/get-port registry (keyword tool-name))
                                context {:params tool-params
                                        :port-registry registry
                                        :request req}
                                result (core/port-execute port context)]
                            {:jsonrpc "2.0"
                             :id request-id
                             :result result})

                          ;; Unknown method
                          {:jsonrpc "2.0"
                           :id request-id
                           :error {:code -32601
                                  :message (str "Method not found: " method)}}))
                      (catch Exception e
                        {:jsonrpc "2.0"
                         :id (:id req)
                         :error {:code -32603
                                :message (str "Internal error: " (.getMessage e))}})))]
    (batch/process-batch requests process-fn opts)))

;; =============================================================================
;; Performance Comparison Tests
;; =============================================================================

(deftest test-batch-sequential-vs-parallel-performance
  (testing "Parallel batch processing is faster than sequential"
    (let [registry (create-test-registry-with-slow-tools)
          requests (vec (repeatedly 10
                         #(hash-map :jsonrpc "2.0"
                                   :id (rand-int 100000)
                                   :method "tools/call"
                                   :params {:name "slow-tool"
                                           :arguments {:value 5}})))

          ;; Sequential adapter
          adapter-seq (mcp/create-mcp-adapter
                       {:performance {:batch-processing {:strategy :sequential}}})

          ;; Parallel adapter
          adapter-par (mcp/create-mcp-adapter
                       {:performance {:batch-processing {:strategy :pmap}}})

          ;; Measure sequential timing
          start-seq (System/nanoTime)
          results-seq (process-batch-requests requests registry adapter-seq)
          duration-seq (/ (- (System/nanoTime) start-seq) 1000000.0)

          ;; Measure parallel timing
          start-par (System/nanoTime)
          results-par (process-batch-requests requests registry adapter-par)
          duration-par (/ (- (System/nanoTime) start-par) 1000000.0)]

      ;; Both should succeed
      (is (= 10 (count results-seq)))
      (is (= 10 (count results-par)))

      ;; All results should be successful
      (is (every? #(contains? % :result) results-seq))
      (is (every? #(contains? % :result) results-par))

      ;; Parallel should be significantly faster (at least 2x)
      (println "Sequential:" (format "%.2f" duration-seq) "ms, Parallel:" (format "%.2f" duration-par) "ms")
      (is (< duration-par (* duration-seq 0.6))
          (str "Parallel (" duration-par "ms) should be faster than sequential (" duration-seq "ms)")))))

(deftest test-batch-core-async-concurrency-limit
  (testing "Core.async respects max-concurrency limit"
    (let [registry (create-test-registry-with-slow-tools)
          concurrent-counter (atom 0)
          max-observed (atom 0)

          ;; Override slow-tool to track concurrency
          _ (core/register-port! registry
              {:id :slow-tool
               :name "slow-tool"
               :description "Slow operation with concurrency tracking"
               :input-schema {:type "object"}
               :handler (fn [{:keys [params]}]
                         (let [current (swap! concurrent-counter inc)]
                           (swap! max-observed #(max % current))
                           (Thread/sleep 100)
                           (swap! concurrent-counter dec)
                           {:result (* 2 (:value params))}))})

          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing
                                 {:strategy :core-async
                                  :max-concurrency 3}}})

          requests (vec (repeatedly 15
                         #(hash-map :jsonrpc "2.0"
                                   :id (rand-int 100000)
                                   :method "tools/call"
                                   :params {:name "slow-tool"
                                           :arguments {:value 5}})))

          results (process-batch-requests requests registry adapter)]

      ;; All should succeed
      (is (= 15 (count results)))
      (is (every? #(contains? % :result) results))

      ;; Max concurrent operations should not exceed limit
      (is (<= @max-observed 3)
          (str "Max concurrent operations should be <= 3, was " @max-observed)))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-batch-error-isolation
  (testing "One failed request doesn't stop batch processing"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing {:strategy :pmap}}})

          requests [{:jsonrpc "2.0"
                    :id 1
                    :method "tools/call"
                    :params {:name "fast-tool"
                            :arguments {:value 5}}}
                   {:jsonrpc "2.0"
                    :id 2
                    :method "tools/call"
                    :params {:name "error-tool"
                            :arguments {:should-fail true}}}
                   {:jsonrpc "2.0"
                    :id 3
                    :method "tools/call"
                    :params {:name "fast-tool"
                            :arguments {:value 10}}}]

          results (process-batch-requests requests registry adapter)]

      ;; All requests processed
      (is (= 3 (count results)))

      ;; First and third succeed
      (is (contains? (first results) :result))
      (is (contains? (nth results 2) :result))

      ;; Second fails
      (is (contains? (second results) :error)))))

(deftest test-batch-mixed-methods
  (testing "Batch with mixed request types"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing {:strategy :pmap}}})

          requests [{:jsonrpc "2.0"
                    :id 1
                    :method "tools/call"
                    :params {:name "fast-tool"
                            :arguments {:value 5}}}
                   {:jsonrpc "2.0"
                    :id 2
                    :method "unknown/method"
                    :params {}}
                   {:jsonrpc "2.0"
                    :id 3
                    :method "tools/call"
                    :params {:name "slow-tool"
                            :arguments {:value 10}}}]

          results (process-batch-requests requests registry adapter)]

      ;; All requests processed
      (is (= 3 (count results)))

      ;; First and third succeed
      (is (contains? (first results) :result))
      (is (contains? (nth results 2) :result))

      ;; Second returns method not found
      (is (= -32601 (get-in (second results) [:error :code]))))))

;; =============================================================================
;; Configuration Tests
;; =============================================================================

(deftest test-adapter-batch-opts-extraction
  (testing "Adapter correctly extracts batch options"
    (let [adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing
                                 {:enabled true
                                  :strategy :pmap
                                  :max-concurrency 20
                                  :timeout-ms 60000}}})
          opts (mcp/get-batch-opts adapter)]

      (is (= :pmap (:strategy opts)))
      (is (= 20 (:max-concurrency opts)))
      (is (= 60000 (:timeout-ms opts))))))

(deftest test-adapter-batch-strategy-accessor
  (testing "Adapter provides batch strategy accessor"
    (let [adapter-seq (mcp/create-mcp-adapter
                       {:performance {:batch-processing {:strategy :sequential}}})
          adapter-par (mcp/create-mcp-adapter
                       {:performance {:batch-processing {:strategy :pmap}}})
          adapter-async (mcp/create-mcp-adapter
                         {:performance {:batch-processing {:strategy :core-async}}})]

      (is (= :sequential (mcp/get-batch-strategy adapter-seq)))
      (is (= :pmap (mcp/get-batch-strategy adapter-par)))
      (is (= :core-async (mcp/get-batch-strategy adapter-async))))))

(deftest test-adapter-batch-enabled-flag
  (testing "Adapter correctly reports batch enabled status"
    (let [adapter-disabled (mcp/create-mcp-adapter
                            {:performance {:batch-processing {:enabled false}}})
          adapter-enabled (mcp/create-mcp-adapter
                           {:performance {:batch-processing {:enabled true}}})]

      (is (false? (mcp/batch-enabled? adapter-disabled)))
      (is (true? (mcp/batch-enabled? adapter-enabled))))))

(deftest test-default-batch-configuration
  (testing "Default adapter uses sequential strategy"
    (let [adapter (mcp/create-mcp-adapter)
          opts (mcp/get-batch-opts adapter)]

      (is (= :sequential (:strategy opts)))
      (is (false? (mcp/batch-enabled? adapter))))))

;; =============================================================================
;; Order Preservation Tests
;; =============================================================================

(deftest test-batch-order-preservation-sequential
  (testing "Sequential batch preserves request order"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing {:strategy :sequential}}})

          requests (vec (for [i (range 20)]
                         {:jsonrpc "2.0"
                          :id i
                          :method "tools/call"
                          :params {:name "fast-tool"
                                  :arguments {:value i}}}))

          results (process-batch-requests requests registry adapter)
          result-ids (mapv :id results)]

      (is (= (range 20) result-ids)))))

(deftest test-batch-order-preservation-parallel
  (testing "Parallel batch preserves request order"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing {:strategy :pmap}}})

          requests (vec (for [i (range 50)]
                         {:jsonrpc "2.0"
                          :id i
                          :method "tools/call"
                          :params {:name "slow-tool"
                                  :arguments {:value i}}}))

          results (process-batch-requests requests registry adapter)
          result-ids (mapv :id results)]

      (is (= (range 50) result-ids)))))

(deftest test-batch-order-preservation-core-async
  (testing "Core.async batch preserves request order"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing
                                 {:strategy :core-async
                                  :max-concurrency 5}}})

          requests (vec (for [i (range 30)]
                         {:jsonrpc "2.0"
                          :id i
                          :method "tools/call"
                          :params {:name "slow-tool"
                                  :arguments {:value i}}}))

          results (process-batch-requests requests registry adapter)
          result-ids (mapv :id results)]

      (is (= (range 30) result-ids)))))

;; =============================================================================
;; Large Batch Tests
;; =============================================================================

(deftest test-large-batch-sequential
  (testing "Sequential processing handles large batches"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing {:strategy :sequential}}})

          requests (vec (for [i (range 100)]
                         {:jsonrpc "2.0"
                          :id i
                          :method "tools/call"
                          :params {:name "fast-tool"
                                  :arguments {:value i}}}))

          results (process-batch-requests requests registry adapter)]

      (is (= 100 (count results)))
      (is (every? #(contains? % :result) results)))))

(deftest test-large-batch-parallel
  (testing "Parallel processing handles large batches efficiently"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing {:strategy :pmap}}})

          requests (vec (for [i (range 100)]
                         {:jsonrpc "2.0"
                          :id i
                          :method "tools/call"
                          :params {:name "fast-tool"
                                  :arguments {:value i}}}))

          start (System/nanoTime)
          results (process-batch-requests requests registry adapter)
          duration (/ (- (System/nanoTime) start) 1000000.0)]

      (is (= 100 (count results)))
      (is (every? #(contains? % :result) results))
      (println "Large batch (100 items) parallel processing:" (format "%.2f" duration) "ms"))))

;; =============================================================================
;; Empty Batch Tests
;; =============================================================================

(deftest test-empty-batch-sequential
  (testing "Sequential strategy handles empty batch"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing {:strategy :sequential}}})
          results (process-batch-requests [] registry adapter)]

      (is (= [] results)))))

(deftest test-empty-batch-parallel
  (testing "Parallel strategy handles empty batch"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing {:strategy :pmap}}})
          results (process-batch-requests [] registry adapter)]

      (is (= [] results)))))

(deftest test-empty-batch-core-async
  (testing "Core.async strategy handles empty batch"
    (let [registry (create-test-registry-with-slow-tools)
          adapter (mcp/create-mcp-adapter
                   {:performance {:batch-processing {:strategy :core-async}}})
          results (process-batch-requests [] registry adapter)]

      (is (= [] results)))))
