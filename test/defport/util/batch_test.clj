(ns defport.util.batch-test
  "Tests for batch processing utilities with different concurrency strategies."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.util.batch :as batch]))

;; =============================================================================
;; Strategy 1: Sequential Batch Processing
;; =============================================================================

(deftest test-sequential-batch-maintains-order
  (testing "Sequential processing maintains order"
    (let [items (range 10)
          results (batch/sequential-batch items inc)]
      (is (= (range 1 11) results)))))

(deftest test-sequential-batch-applies-function
  (testing "Sequential processing applies function correctly"
    (let [items [1 2 3 4 5]
          results (batch/sequential-batch items #(* % 2))]
      (is (= [2 4 6 8 10] results)))))

(deftest test-sequential-batch-empty-input
  (testing "Sequential processing handles empty input"
    (let [results (batch/sequential-batch [] inc)]
      (is (= [] results)))))

;; =============================================================================
;; Strategy 2: Parallel Map (pmap)
;; =============================================================================

(deftest test-pmap-batch-maintains-order
  (testing "Parallel processing maintains order"
    (let [items (range 100)
          results (batch/pmap-batch items inc)]
      (is (= (range 1 101) results)))))

(deftest test-pmap-batch-applies-function
  (testing "Parallel processing applies function correctly"
    (let [items [1 2 3 4 5]
          results (batch/pmap-batch items #(* % 2))]
      (is (= [2 4 6 8 10] results)))))

(deftest test-pmap-batch-empty-input
  (testing "Parallel processing handles empty input"
    (let [results (batch/pmap-batch [] inc)]
      (is (= [] results)))))

;; =============================================================================
;; Strategy 3: Futures (JVM only)
;; =============================================================================

(deftest test-futures-batch-maintains-order
  (testing "Futures batch processing maintains order"
    (let [items (range 10)
          results (batch/futures-batch items inc {:timeout-ms 5000})]
      (is (= (range 1 11) results)))))

(deftest test-futures-batch-timeout
  (testing "Futures batch respects timeout"
    (let [items [1 2 3]
          slow-fn (fn [x] (Thread/sleep 200) x)
          results (batch/futures-batch items slow-fn {:timeout-ms 50})]
      ;; All should timeout
      (is (= 3 (count results)))
      (is (every? #(contains? % :error) results))
      (is (every? #(= -32000 (get-in % [:error :code])) results)))))

(deftest test-futures-batch-mixed-timeout
  (testing "Futures batch handles mixed timeout/success"
    (let [items [1 2 3]
          variable-fn (fn [x]
                       (when (= x 2)
                         (Thread/sleep 100))
                       x)
          results (batch/futures-batch items variable-fn {:timeout-ms 50})]
      ;; First and third should succeed, second should timeout
      (is (= 3 (count results)))
      (is (= 1 (first results)))
      (is (contains? (second results) :error))
      (is (= 3 (nth results 2))))))

(deftest test-futures-batch-default-timeout
  (testing "Futures batch uses default timeout"
    (let [items [1 2 3]
          results (batch/futures-batch items inc {})]
      (is (= [2 3 4] results)))))

;; =============================================================================
;; Strategy 4: Core.async (JVM only)
;; =============================================================================

(deftest test-core-async-batch-maintains-order
  (testing "Core.async batch processing maintains order"
    (let [items (range 20)
          results (batch/core-async-batch items inc {:max-concurrency 5
                                                      :timeout-ms 10000})]
      (is (= (range 1 21) results)))))

(deftest test-core-async-batch-respects-concurrency-limit
  (testing "Core.async respects max-concurrency"
    (let [counter (atom 0)
          max-concurrent (atom 0)
          items (range 20)
          slow-fn (fn [x]
                   (let [current (swap! counter inc)]
                     (swap! max-concurrent #(max % current))
                     (Thread/sleep 50)
                     (swap! counter dec)
                     x))
          results (batch/core-async-batch items slow-fn {:max-concurrency 5
                                                          :timeout-ms 10000})]
      (is (= (range 20) results))
      (is (<= @max-concurrent 5)
          (str "Max concurrent operations should be <= 5, was " @max-concurrent)))))

(deftest test-core-async-batch-timeout
  (testing "Core.async batch respects overall timeout"
    (let [items (range 10)
          slow-fn (fn [x] (Thread/sleep 200) x)
          results (batch/core-async-batch items slow-fn {:max-concurrency 2
                                                          :timeout-ms 500})]
      ;; Should timeout before completing all items
      (is (every? #(contains? % :error) results)))))

(deftest test-core-async-batch-empty-input
  (testing "Core.async handles empty input"
    (let [results (batch/core-async-batch [] inc {:max-concurrency 5})]
      (is (= [] results)))))

;; =============================================================================
;; Strategy Dispatcher
;; =============================================================================

(deftest test-process-batch-sequential-strategy
  (testing "process-batch dispatches to sequential"
    (let [items [1 2 3 4 5]
          results (batch/process-batch items inc {:strategy :sequential})]
      (is (= [2 3 4 5 6] results)))))

(deftest test-process-batch-pmap-strategy
  (testing "process-batch dispatches to pmap"
    (let [items [1 2 3 4 5]
          results (batch/process-batch items inc {:strategy :pmap})]
      (is (= [2 3 4 5 6] results)))))

(deftest test-process-batch-futures-strategy
  (testing "process-batch dispatches to futures"
    (let [items [1 2 3]
          results (batch/process-batch items inc {:strategy :futures
                                                  :timeout-ms 5000})]
      (is (= [2 3 4] results)))))

(deftest test-process-batch-core-async-strategy
  (testing "process-batch dispatches to core-async"
    (let [items [1 2 3]
          results (batch/process-batch items inc {:strategy :core-async
                                                  :max-concurrency 5
                                                  :timeout-ms 5000})]
      (is (= [2 3 4] results)))))

(deftest test-process-batch-default-strategy
  (testing "process-batch uses sequential by default"
    (let [items [1 2 3]
          results (batch/process-batch items inc {})]
      (is (= [2 3 4] results)))))

(deftest test-process-batch-unknown-strategy
  (testing "process-batch throws on unknown strategy"
    (is (thrown-with-msg? Exception #"Unknown batch strategy"
          (batch/process-batch [1 2 3] inc {:strategy :unknown})))))

;; =============================================================================
;; Thread Safety Tests
;; =============================================================================

(deftest test-concurrent-thread-safety
  (testing "No race conditions in concurrent execution"
    ;; Run 50 iterations to expose race conditions
    (dotimes [_ 50]
      (let [items (range 50)
            counter (atom 0)
            results (batch/pmap-batch items
                                      (fn [x]
                                        (swap! counter inc)
                                        x))]
        (is (= 50 (count results)))
        (is (= 50 @counter))
        (is (= (range 50) results))))))

(deftest test-futures-thread-safety
  (testing "Futures processing is thread-safe"
    (dotimes [_ 50]
      (let [items (range 30)
            counter (atom 0)
            results (batch/futures-batch items
                                         (fn [x]
                                           (swap! counter inc)
                                           x)
                                         {:timeout-ms 5000})]
        (is (= 30 (count results)))
        (is (= 30 @counter))
        (is (= (range 30) results))))))

(deftest test-core-async-thread-safety
  (testing "Core.async processing is thread-safe"
    (dotimes [_ 50]
      (let [items (range 30)
            counter (atom 0)
            results (batch/core-async-batch items
                                            (fn [x]
                                              (swap! counter inc)
                                              x)
                                            {:max-concurrency 5
                                             :timeout-ms 10000})]
        (is (= 30 (count results)))
        (is (= 30 @counter))
        (is (= (range 30) results))))))

;; =============================================================================
;; Helper Functions Tests
;; =============================================================================

(deftest test-batch-enabled
  (testing "batch-enabled? detects enabled flag"
    (is (false? (batch/batch-enabled? {})))
    (is (false? (batch/batch-enabled? {:batch-processing {:enabled false}})))
    (is (true? (batch/batch-enabled? {:batch-processing {:enabled true}})))))

(deftest test-get-batch-strategy
  (testing "get-batch-strategy extracts strategy"
    (is (= :sequential (batch/get-batch-strategy {})))
    (is (= :pmap (batch/get-batch-strategy {:batch-processing {:strategy :pmap}})))
    (is (= :core-async (batch/get-batch-strategy {:batch-processing {:strategy :core-async}})))))

(deftest test-get-batch-opts
  (testing "get-batch-opts extracts options"
    (let [opts (batch/get-batch-opts {:batch-processing {:enabled true
                                                          :strategy :pmap
                                                          :max-concurrency 20
                                                          :timeout-ms 60000}})]
      (is (= :pmap (:strategy opts)))
      (is (= 20 (:max-concurrency opts)))
      (is (= 60000 (:timeout-ms opts))))))

(deftest test-valid-strategy
  (testing "valid-strategy? validates strategy keywords"
    (is (true? (batch/valid-strategy? :sequential)))
    (is (true? (batch/valid-strategy? :pmap)))
    (is (true? (batch/valid-strategy? :futures)))
    (is (true? (batch/valid-strategy? :core-async)))
    (is (false? (batch/valid-strategy? :invalid)))
    (is (false? (batch/valid-strategy? :unknown)))))

(deftest test-available-strategies
  (testing "available-strategies returns all JVM strategies"
    (let [strategies (batch/available-strategies)]
      (is (some #{:sequential} strategies))
      (is (some #{:pmap} strategies))
      (is (some #{:futures} strategies))
      (is (some #{:core-async} strategies)))))

(deftest test-strategy-available
  (testing "strategy-available? checks platform support"
    (is (true? (batch/strategy-available? :sequential)))
    (is (true? (batch/strategy-available? :pmap)))
    (is (true? (batch/strategy-available? :futures)))
    (is (true? (batch/strategy-available? :core-async)))
    (is (false? (batch/strategy-available? :invalid)))))

;; =============================================================================
;; Performance Comparison Tests
;; =============================================================================

(deftest test-parallel-faster-than-sequential
  (testing "Parallel processing should be faster than sequential for I/O-bound operations"
    (let [items (vec (repeat 10 nil))
          slow-fn (fn [_] (Thread/sleep 50) :done)

          ;; Sequential timing
          start-seq (System/nanoTime)
          _ (batch/sequential-batch items slow-fn)
          duration-seq (/ (- (System/nanoTime) start-seq) 1000000.0)

          ;; Parallel timing
          start-par (System/nanoTime)
          _ (batch/pmap-batch items slow-fn)
          duration-par (/ (- (System/nanoTime) start-par) 1000000.0)]

      ;; Parallel should be at least 2x faster (conservative check)
      (is (< duration-par (* duration-seq 0.6))
          (str "Parallel (" duration-par "ms) should be faster than sequential ("
               duration-seq "ms)")))))

;; =============================================================================
;; Error Handling Tests
;; =============================================================================

(deftest test-sequential-batch-with-errors
  (testing "Sequential batch handles errors in process-fn"
    (let [items [1 2 3]
          error-fn (fn [x]
                    (if (= x 2)
                      (throw (ex-info "Test error" {:x x}))
                      x))]
      (is (thrown? Exception
            (batch/sequential-batch items error-fn))))))

(deftest test-pmap-batch-with-errors
  (testing "Pmap batch handles errors in process-fn"
    (let [items [1 2 3]
          error-fn (fn [x]
                    (if (= x 2)
                      (throw (ex-info "Test error" {:x x}))
                      x))]
      (is (thrown? Exception
            (batch/pmap-batch items error-fn))))))

(deftest test-futures-batch-with-errors
  (testing "Futures batch handles errors in process-fn"
    (let [items [1 2 3]
          error-fn (fn [x]
                    (if (= x 2)
                      (throw (ex-info "Test error" {:x x}))
                      x))
          results (batch/futures-batch items error-fn {:timeout-ms 5000})]
      ;; Should return results even if one fails
      (is (= 3 (count results)))
      ;; First and third should succeed, second should return error
      (is (= 1 (first results)))
      (is (contains? (second results) :error))
      (is (= -32603 (get-in (second results) [:error :code])))
      (is (= 3 (nth results 2))))))
