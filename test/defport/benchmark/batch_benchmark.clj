(ns defport.benchmark.batch-benchmark
  "Performance benchmarks for batch processing strategies using Criterium.

  Run with: clojure -M:benchmark -m defport.benchmark.batch-benchmark"
  (:require [criterium.core :as crit]
            [defport.util.batch :as batch]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]))

;; =============================================================================
;; Benchmark Configuration
;; =============================================================================

(def ^:dynamic *warmup-iterations* 10)
(def ^:dynamic *measurement-iterations* 100)
(def ^:dynamic *verbose* true)

(defn log [& args]
  (when *verbose*
    (apply println args)))

;; =============================================================================
;; Test Data Generation
;; =============================================================================

(defn generate-requests
  "Generate N test requests with varying complexity."
  [n & {:keys [method params-fn]
        :or {method "tools/call"
             params-fn (fn [i] {:value i})}}]
  (vec (for [i (range n)]
         {:jsonrpc "2.0"
          :id i
          :method method
          :params (params-fn i)})))

(defn fast-handler
  "Handler that completes immediately (CPU-bound simulation)."
  [{:keys [params]}]
  {:result (* 2 (:value params))})

(defn io-bound-handler
  "Handler simulating I/O operations."
  [{:keys [params]}]
  (Thread/sleep 10) ;; Simulate 10ms I/O
  {:result (:value params)})

(defn cpu-bound-handler
  "Handler simulating CPU-intensive work."
  [{:keys [params]}]
  (let [n (:value params)]
    ;; Compute something CPU-intensive
    (reduce + (range (* n 1000))))
  {:result "computed"})

;; =============================================================================
;; Low-Level Batch Processing Benchmarks
;; =============================================================================

(defn benchmark-sequential-batch
  "Benchmark sequential batch processing."
  [batch-size handler-fn]
  (log "\n=== Sequential Batch Processing ===" )
  (log "Batch size:" batch-size)
  (let [items (vec (range batch-size))]
    (crit/with-progress-reporting
      (crit/bench
        (batch/sequential-batch items handler-fn)
        :verbose))))

(defn benchmark-pmap-batch
  "Benchmark parallel map batch processing."
  [batch-size handler-fn]
  (log "\n=== Parallel Map (pmap) Batch Processing ===" )
  (log "Batch size:" batch-size)
  (let [items (vec (range batch-size))]
    (crit/with-progress-reporting
      (crit/bench
        (batch/pmap-batch items handler-fn)
        :verbose))))

(defn benchmark-futures-batch
  "Benchmark futures-based batch processing."
  [batch-size handler-fn]
  (log "\n=== Futures Batch Processing ===" )
  (log "Batch size:" batch-size)
  (let [items (vec (range batch-size))
        opts {:timeout-ms 30000}]
    (crit/with-progress-reporting
      (crit/bench
        (batch/futures-batch items handler-fn opts)
        :verbose))))

(defn benchmark-core-async-batch
  "Benchmark core.async batch processing."
  [batch-size handler-fn concurrency]
  (log "\n=== Core.async Batch Processing ===" )
  (log "Batch size:" batch-size "| Concurrency:" concurrency)
  (let [items (vec (range batch-size))
        opts {:max-concurrency concurrency :timeout-ms 60000}]
    (crit/with-progress-reporting
      (crit/bench
        (batch/core-async-batch items handler-fn opts)
        :verbose))))

;; =============================================================================
;; Strategy Comparison
;; =============================================================================

(defn compare-strategies
  "Compare all batch processing strategies."
  [batch-size handler-fn handler-name]
  (log "\n" (str (apply str (repeat 80 "="))))
  (log "COMPARING STRATEGIES:" handler-name)
  (log "Batch size:" batch-size)
  (log (str (apply str (repeat 80 "="))))

  ;; Sequential (baseline)
  (log "\n[1/4] Sequential (baseline)")
  (benchmark-sequential-batch batch-size handler-fn)

  ;; Pmap
  (log "\n[2/4] Parallel Map")
  (benchmark-pmap-batch batch-size handler-fn)

  ;; Futures
  (log "\n[3/4] Futures")
  (benchmark-futures-batch batch-size handler-fn)

  ;; Core.async
  (log "\n[4/4] Core.async")
  (benchmark-core-async-batch batch-size handler-fn 10))

;; =============================================================================
;; MCP Integration Benchmarks
;; =============================================================================

(defn create-benchmark-registry
  "Create a registry with benchmark tools."
  []
  (let [reg (registry/create-function-registry)]
    (core/register-port! reg
      {:id :fast-tool
       :name "fast-tool"
       :description "Fast computation"
       :input-schema {:type "object"
                      :properties {:value {:type "number"}}}
       :handler fast-handler})

    (core/register-port! reg
      {:id :io-tool
       :name "io-tool"
       :description "I/O bound operation"
       :input-schema {:type "object"
                      :properties {:value {:type "number"}}}
       :handler io-bound-handler})

    (core/register-port! reg
      {:id :cpu-tool
       :name "cpu-tool"
       :description "CPU intensive operation"
       :input-schema {:type "object"
                      :properties {:value {:type "number"}}}
       :handler cpu-bound-handler})
    reg))

(defn process-mcp-batch
  "Process MCP batch requests through adapter."
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

(defn benchmark-mcp-adapter
  "Benchmark MCP adapter with different strategies."
  [strategy batch-size tool-name]
  (log "\n=== MCP Adapter Benchmark ===" )
  (log "Strategy:" strategy "| Batch size:" batch-size "| Tool:" tool-name)

  (let [registry (create-benchmark-registry)
        adapter (mcp/create-mcp-adapter
                  {:performance {:batch-processing {:strategy strategy}}})
        requests (generate-requests batch-size
                   :params-fn (fn [i] {:name tool-name
                                       :arguments {:value i}}))]
    (crit/with-progress-reporting
      (crit/bench
        (process-mcp-batch requests registry adapter)
        :verbose))))

;; =============================================================================
;; Scalability Tests
;; =============================================================================

(defn benchmark-scalability
  "Test how performance scales with batch size."
  [strategy sizes handler-fn]
  (log "\n" (str (apply str (repeat 80 "="))))
  (log "SCALABILITY TEST:" strategy)
  (log (str (apply str (repeat 80 "="))))

  (doseq [size sizes]
    (log "\nBatch size:" size)
    (let [items (vec (range size))
          opts {:strategy strategy
                :max-concurrency 10
                :timeout-ms 60000}]
      (crit/with-progress-reporting
        (crit/quick-bench
          (batch/process-batch items handler-fn opts))))))

;; =============================================================================
;; Concurrency Tuning Benchmarks
;; =============================================================================

(defn benchmark-concurrency-levels
  "Test different concurrency levels for core.async."
  [batch-size handler-fn concurrency-levels]
  (log "\n" (str (apply str (repeat 80 "="))))
  (log "CONCURRENCY TUNING (core.async)")
  (log "Batch size:" batch-size)
  (log (str (apply str (repeat 80 "="))))

  (doseq [concurrency concurrency-levels]
    (log "\nConcurrency level:" concurrency)
    (let [items (vec (range batch-size))
          opts {:max-concurrency concurrency :timeout-ms 60000}]
      (crit/with-progress-reporting
        (crit/quick-bench
          (batch/core-async-batch items handler-fn opts))))))

;; =============================================================================
;; Main Benchmark Suites
;; =============================================================================

(defn run-quick-benchmarks
  "Run quick benchmarks (for CI/rapid development)."
  []
  (log "\n" (str (apply str (repeat 80 "="))))
  (log "QUICK BENCHMARK SUITE")
  (log (str (apply str (repeat 80 "="))))

  (binding [crit/*final-gc-problem-threshold* 0.01]
    ;; Small batch comparison
    (compare-strategies 10 inc "Simple increment (fast)")

    ;; Medium batch with I/O
    (log "\n\nMCP Adapter - Fast tool")
    (benchmark-mcp-adapter :sequential 10 "fast-tool")
    (benchmark-mcp-adapter :pmap 10 "fast-tool")))

(defn run-comprehensive-benchmarks
  "Run comprehensive benchmarks (for releases/major changes)."
  []
  (log "\n" (str (apply str (repeat 80 "="))))
  (log "COMPREHENSIVE BENCHMARK SUITE")
  (log (str (apply str (repeat 80 "="))))

  (binding [crit/*final-gc-problem-threshold* 0.01]
    ;; 1. Strategy comparison with different workloads
    (compare-strategies 10 inc "Fast (increment)")
    (compare-strategies 50 inc "Medium batch (increment)")
    (compare-strategies 10 io-bound-handler "I/O bound")

    ;; 2. Scalability tests
    (benchmark-scalability :pmap [10 50 100] inc)
    (benchmark-scalability :core-async [10 50 100] inc)

    ;; 3. Concurrency tuning
    (benchmark-concurrency-levels 50 io-bound-handler [2 5 10 20])

    ;; 4. MCP integration
    (doseq [strategy [:sequential :pmap :core-async]]
      (benchmark-mcp-adapter strategy 20 "fast-tool"))))

(defn run-stress-benchmarks
  "Run stress benchmarks (large batches, resource limits)."
  []
  (log "\n" (str (apply str (repeat 80 "="))))
  (log "STRESS BENCHMARK SUITE")
  (log (str (apply str (repeat 80 "="))))

  (binding [crit/*final-gc-problem-threshold* 0.01]
    ;; Large batch scalability
    (benchmark-scalability :pmap [100 500 1000] inc)
    (benchmark-scalability :core-async [100 500 1000] inc)

    ;; High concurrency
    (benchmark-concurrency-levels 100 io-bound-handler [10 50 100])))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main entry point for benchmarks."
  [& args]
  (let [mode (or (first args) "quick")]
    (println "\n╔═══════════════════════════════════════════════════════════════╗")
    (println "║          DEFPORT PERFORMANCE BENCHMARKS (Criterium)          ║")
    (println "╚═══════════════════════════════════════════════════════════════╝")
    (println "\nMode:" mode)
    (println "Java version:" (System/getProperty "java.version"))
    (println "JVM:" (System/getProperty "java.vm.name"))
    (println "Clojure version:" (clojure-version))
    (println "Available processors:" (.availableProcessors (Runtime/getRuntime)))
    (println)

    (case mode
      "quick" (run-quick-benchmarks)
      "comprehensive" (run-comprehensive-benchmarks)
      "stress" (run-stress-benchmarks)
      (do
        (println "Unknown mode:" mode)
        (println "Available modes: quick, comprehensive, stress")
        (System/exit 1)))

    (println "\n✓ Benchmarks complete!")
    (shutdown-agents)))

(comment
  ;; Run individual benchmarks in REPL
  (benchmark-sequential-batch 10 inc)
  (benchmark-pmap-batch 10 inc)
  (compare-strategies 10 inc "Test")
  (run-quick-benchmarks)
  )
