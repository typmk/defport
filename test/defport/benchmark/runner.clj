(ns defport.benchmark.runner
  "Main benchmark runner that orchestrates all performance tests.

  Usage:
    clojure -M:benchmark -m defport.benchmark.runner [suite] [options]

  Suites:
    all          - Run all benchmarks (default)
    quick        - Quick benchmarks (for CI/rapid development)
    batch        - Batch processing benchmarks only
    latency      - Single request latency benchmarks only
    registry     - Registry operation benchmarks only
    regression   - Regression detection (compare against baseline)

  Options:
    --save-baseline  - Save current results as baseline
    --compare        - Compare against saved baseline
    --output FILE    - Save results to file (JSON format)
    --verbose        - Verbose output

  Examples:
    # Quick smoke test
    clojure -M:benchmark -m defport.benchmark.runner quick

    # Run all benchmarks and save as baseline
    clojure -M:benchmark -m defport.benchmark.runner all --save-baseline

    # Run and compare against baseline
    clojure -M:benchmark -m defport.benchmark.runner all --compare

    # Run specific suite with output
    clojure -M:benchmark -m defport.benchmark.runner batch --output results.json"
  (:require [defport.benchmark.batch-benchmark :as batch-bench]
            [defport.benchmark.latency-benchmark :as latency-bench]
            [defport.benchmark.registry-benchmark :as registry-bench]
            [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]))

;; =============================================================================
;; Configuration
;; =============================================================================

(def baseline-file "benchmark-baseline.json")

(def system-info
  {:os-name (System/getProperty "os.name")
   :os-version (System/getProperty "os.version")
   :os-arch (System/getProperty "os.arch")
   :java-version (System/getProperty "java.version")
   :java-vendor (System/getProperty "java.vendor")
   :java-vm-name (System/getProperty "java.vm.name")
   :clojure-version (clojure-version)
   :processors (.availableProcessors (Runtime/getRuntime))
   :max-memory (/ (.maxMemory (Runtime/getRuntime)) 1024 1024)})

;; =============================================================================
;; Result Collection
;; =============================================================================

(defonce results* (atom {}))

(defn record-result!
  "Record a benchmark result."
  [suite benchmark-name result]
  (swap! results* assoc-in [suite benchmark-name]
         (assoc result :timestamp (str (Instant/now)))))

(defn get-results
  "Get all recorded results."
  []
  @results*)

(defn clear-results!
  "Clear all recorded results."
  []
  (reset! results* {}))

;; =============================================================================
;; Baseline Management
;; =============================================================================

(defn save-baseline
  "Save current results as baseline."
  [results]
  (let [baseline {:timestamp (str (Instant/now))
                  :system system-info
                  :results results}]
    (spit baseline-file (json/generate-string baseline {:pretty true}))
    (println "\n✓ Baseline saved to" baseline-file)))

(defn load-baseline
  "Load baseline from file."
  []
  (when (.exists (io/file baseline-file))
    (json/parse-string (slurp baseline-file) true)))

(defn compare-with-baseline
  "Compare current results with baseline."
  [current-results]
  (if-let [baseline (load-baseline)]
    (do
      (println "\n" (str (apply str (repeat 80 "="))))
      (println "REGRESSION ANALYSIS")
      (println (str (apply str (repeat 80 "="))))
      (println "\nBaseline timestamp:" (:timestamp baseline))
      (println "Current timestamp:" (str (Instant/now)))
      (println)

      (let [baseline-results (:results baseline)
            regressions (atom [])
            improvements (atom [])]
        (doseq [[suite benchmarks] current-results
                [bench-name current] benchmarks]
          (when-let [baseline-val (get-in baseline-results [suite bench-name :mean])]
            (let [current-val (:mean current)
                  change-pct (* 100 (/ (- current-val baseline-val) baseline-val))]
              (cond
                (> change-pct 10)
                (swap! regressions conj {:suite suite
                                         :benchmark bench-name
                                         :baseline baseline-val
                                         :current current-val
                                         :change-pct change-pct})

                (< change-pct -10)
                (swap! improvements conj {:suite suite
                                          :benchmark bench-name
                                          :baseline baseline-val
                                          :current current-val
                                          :change-pct change-pct})))))

        ;; Report regressions
        (if (seq @regressions)
          (do
            (println "⚠️  REGRESSIONS DETECTED:")
            (doseq [reg @regressions]
              (println (format "  %s/%s: %.2f ms → %.2f ms (%.1f%% SLOWER)"
                               (:suite reg)
                               (:benchmark reg)
                               (:baseline reg)
                               (:current reg)
                               (:change-pct reg)))))
          (println "✓ No significant regressions"))

        ;; Report improvements
        (when (seq @improvements)
          (println "\n✓ IMPROVEMENTS DETECTED:")
          (doseq [imp @improvements]
            (println (format "  %s/%s: %.2f ms → %.2f ms (%.1f%% FASTER)"
                             (:suite imp)
                             (:benchmark imp)
                             (:baseline imp)
                             (:current imp)
                             (Math/abs (:change-pct imp))))))

        ;; Exit code based on regressions
        (if (seq @regressions)
          (do
            (println "\n❌ Benchmark regressions detected!")
            1)
          (do
            (println "\n✓ All benchmarks within acceptable range")
            0))))
    (do
      (println "⚠️  No baseline found. Run with --save-baseline to create one.")
      0)))

;; =============================================================================
;; Output Formatting
;; =============================================================================

(defn save-results-to-file
  "Save results to JSON file."
  [results output-file]
  (let [output {:timestamp (str (Instant/now))
                :system system-info
                :results results}]
    (spit output-file (json/generate-string output {:pretty true}))
    (println "\n✓ Results saved to" output-file)))

(defn print-summary
  "Print benchmark summary."
  [results]
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "BENCHMARK SUMMARY")
  (println (str (apply str (repeat 80 "="))))

  (doseq [[suite benchmarks] results]
    (println "\n" suite ":")
    (doseq [[bench-name result] benchmarks]
      (println (format "  %-40s  Mean: %.3f ms"
                       bench-name
                       (or (:mean result) 0.0))))))

;; =============================================================================
;; Benchmark Suite Execution
;; =============================================================================

(defn run-quick-suite
  "Run quick benchmark suite (for CI)."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "QUICK BENCHMARK SUITE")
  (println (str (apply str (repeat 80 "="))))

  (println "\n[1/3] Batch processing benchmarks...")
  (batch-bench/run-quick-benchmarks)

  (println "\n[2/3] Latency benchmarks...")
  (latency-bench/run-quick-latency-benchmarks)

  (println "\n[3/3] Registry benchmarks...")
  (registry-bench/run-quick-benchmarks))

(defn run-batch-suite
  "Run batch processing benchmarks only."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "BATCH PROCESSING BENCHMARKS")
  (println (str (apply str (repeat 80 "="))))

  (batch-bench/run-comprehensive-benchmarks))

(defn run-latency-suite
  "Run latency benchmarks only."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "LATENCY BENCHMARKS")
  (println (str (apply str (repeat 80 "="))))

  (latency-bench/run-all-latency-benchmarks))

(defn run-registry-suite
  "Run registry benchmarks only."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "REGISTRY BENCHMARKS")
  (println (str (apply str (repeat 80 "="))))

  (registry-bench/run-comprehensive-benchmarks))

(defn run-all-suites
  "Run all benchmark suites."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "COMPREHENSIVE BENCHMARK SUITE")
  (println (str (apply str (repeat 80 "="))))

  (println "\n[1/4] Batch processing benchmarks...")
  (batch-bench/run-comprehensive-benchmarks)

  (println "\n[2/4] Latency benchmarks...")
  (latency-bench/run-all-latency-benchmarks)

  (println "\n[3/4] Registry benchmarks...")
  (registry-bench/run-comprehensive-benchmarks)

  (println "\n[4/4] Memory benchmarks...")
  (registry-bench/run-memory-benchmarks))

;; =============================================================================
;; Command Line Interface
;; =============================================================================

(defn parse-args
  "Parse command line arguments."
  [args]
  (let [suite (or (first args) "all")
        flags (set (rest args))]
    {:suite suite
     :save-baseline (contains? flags "--save-baseline")
     :compare (contains? flags "--compare")
     :verbose (contains? flags "--verbose")
     :output (when-let [idx (.indexOf (vec args) "--output")]
               (get args (inc idx)))}))

(defn print-usage
  "Print usage information."
  []
  (println "
Defport Performance Benchmark Suite

Usage:
  clojure -M:benchmark -m defport.benchmark.runner [suite] [options]

Suites:
  all          Run all benchmarks (default)
  quick        Quick benchmarks for CI/rapid development
  batch        Batch processing benchmarks only
  latency      Single request latency benchmarks only
  registry     Registry operation benchmarks only

Options:
  --save-baseline   Save current results as baseline
  --compare         Compare against saved baseline
  --output FILE     Save results to JSON file
  --verbose         Verbose output
  --help            Show this help

Examples:
  # Quick smoke test
  clojure -M:benchmark -m defport.benchmark.runner quick

  # Run all benchmarks and save as baseline
  clojure -M:benchmark -m defport.benchmark.runner all --save-baseline

  # Run and compare against baseline (for CI)
  clojure -M:benchmark -m defport.benchmark.runner all --compare

  # Run specific suite with output file
  clojure -M:benchmark -m defport.benchmark.runner batch --output results.json
"))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main entry point for benchmark runner."
  [& args]
  (let [opts (parse-args args)]
    (when (or (empty? args) (contains? (set args) "--help"))
      (print-usage)
      (System/exit 0))

    (println "\n╔═══════════════════════════════════════════════════════════════╗")
    (println "║            DEFPORT PERFORMANCE BENCHMARK SUITE                ║")
    (println "╚═══════════════════════════════════════════════════════════════╝")
    (println "\nSuite:" (:suite opts))
    (println "Date:" (str (Instant/now)))
    (println "\nSystem Information:")
    (doseq [[k v] system-info]
      (println (format "  %-20s %s" (name k) v)))
    (println)

    ;; Run benchmarks
    (let [start-time (System/nanoTime)]
      (case (:suite opts)
        "quick" (run-quick-suite)
        "batch" (run-batch-suite)
        "latency" (run-latency-suite)
        "registry" (run-registry-suite)
        "all" (run-all-suites)
        (do
          (println "❌ Unknown suite:" (:suite opts))
          (print-usage)
          (System/exit 1)))

      (let [duration (/ (- (System/nanoTime) start-time) 1000000000.0)]
        (println "\n" (str (apply str (repeat 80 "="))))
        (println (format "✓ Benchmarks complete! Total time: %.2f seconds" duration))
        (println (str (apply str (repeat 80 "="))))))

    ;; Post-processing
    (let [results (get-results)]
      ;; Save baseline if requested
      (when (:save-baseline opts)
        (save-baseline results))

      ;; Compare with baseline if requested
      (let [exit-code
            (if (:compare opts)
              (compare-with-baseline results)
              0)]

        ;; Save to output file if requested
        (when-let [output-file (:output opts)]
          (save-results-to-file results output-file))

        ;; Exit with appropriate code
        (shutdown-agents)
        (System/exit exit-code)))))

(comment
  ;; Test CLI parsing
  (parse-args ["quick" "--verbose"])
  (parse-args ["all" "--save-baseline" "--output" "results.json"])

  ;; Run from REPL
  (run-quick-suite)
  )
