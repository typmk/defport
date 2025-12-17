(ns defport.benchmark.registry-benchmark
  "Performance benchmarks for port registry operations.

  Tests lookup, registration, and listing performance with varying registry sizes.

  Run with: clojure -M:benchmark -m defport.benchmark.registry-benchmark"
  (:require [criterium.core :as crit]
            [defport.core :as core]
            [defport.registry :as registry]))

;; =============================================================================
;; Test Data Generation
;; =============================================================================

(defn generate-port
  "Generate a test port with given ID."
  [id]
  {:id (keyword (str "port-" id))
   :name (str "port-" id)
   :description (str "Test port " id)
   :input-schema {:type "object"
                  :properties {:value {:type "number"}}}
   :handler (fn [{:keys [params]}]
              {:result (* 2 (:value params))})})

(defn populate-registry
  "Populate registry with N ports."
  [registry n]
  (doseq [i (range n)]
    (core/register-port! registry (generate-port i)))
  registry)

(defn create-populated-registry
  "Create a new registry with N ports."
  [n]
  (let [reg (registry/create-function-registry)]
    (populate-registry reg n)))

;; =============================================================================
;; Lookup Benchmarks
;; =============================================================================

(defn benchmark-port-lookup-small
  "Benchmark port lookup in small registry (10 ports)."
  []
  (println "\n=== Port Lookup (Small Registry: 10 ports) ===" )
  (let [reg (create-populated-registry 10)
        target-id :port-5]
    (crit/with-progress-reporting
      (crit/bench
        (core/get-port reg target-id)
        :verbose))))

(defn benchmark-port-lookup-medium
  "Benchmark port lookup in medium registry (100 ports)."
  []
  (println "\n=== Port Lookup (Medium Registry: 100 ports) ===" )
  (let [reg (create-populated-registry 100)
        target-id :port-50]
    (crit/with-progress-reporting
      (crit/bench
        (core/get-port reg target-id)
        :verbose))))

(defn benchmark-port-lookup-large
  "Benchmark port lookup in large registry (1000 ports)."
  []
  (println "\n=== Port Lookup (Large Registry: 1000 ports) ===" )
  (let [reg (create-populated-registry 1000)
        target-id :port-500]
    (crit/with-progress-reporting
      (crit/bench
        (core/get-port reg target-id)
        :verbose))))

(defn benchmark-port-lookup-not-found
  "Benchmark port lookup when port doesn't exist."
  []
  (println "\n=== Port Lookup (Not Found) ===" )
  (let [reg (create-populated-registry 100)
        target-id :non-existent-port]
    (crit/with-progress-reporting
      (crit/bench
        (core/get-port reg target-id)
        :verbose))))

;; =============================================================================
;; Registration Benchmarks
;; =============================================================================

(defn benchmark-port-registration
  "Benchmark port registration."
  []
  (println "\n=== Port Registration ===" )
  (let [port (generate-port 9999)]
    (crit/with-progress-reporting
      (crit/bench
        (let [reg (registry/create-function-registry)]
          (core/register-port! reg port))
        :verbose))))

(defn benchmark-bulk-registration-10
  "Benchmark bulk registration of 10 ports."
  []
  (println "\n=== Bulk Registration (10 ports) ===" )
  (let [ports (mapv generate-port (range 10))]
    (crit/with-progress-reporting
      (crit/bench
        (let [reg (registry/create-function-registry)]
          (doseq [port ports]
            (core/register-port! reg port)))
        :verbose))))

(defn benchmark-bulk-registration-100
  "Benchmark bulk registration of 100 ports."
  []
  (println "\n=== Bulk Registration (100 ports) ===" )
  (let [ports (mapv generate-port (range 100))]
    (crit/with-progress-reporting
      (crit/quick-bench
        (let [reg (registry/create-function-registry)]
          (doseq [port ports]
            (core/register-port! reg port)))))))

;; =============================================================================
;; Listing Benchmarks
;; =============================================================================

(defn benchmark-list-ports-small
  "Benchmark listing ports in small registry (10 ports)."
  []
  (println "\n=== List Ports (Small Registry: 10 ports) ===" )
  (let [reg (create-populated-registry 10)]
    (crit/with-progress-reporting
      (crit/bench
        (core/list-ports reg)
        :verbose))))

(defn benchmark-list-ports-medium
  "Benchmark listing ports in medium registry (100 ports)."
  []
  (println "\n=== List Ports (Medium Registry: 100 ports) ===" )
  (let [reg (create-populated-registry 100)]
    (crit/with-progress-reporting
      (crit/bench
        (core/list-ports reg)
        :verbose))))

(defn benchmark-list-ports-large
  "Benchmark listing ports in large registry (1000 ports)."
  []
  (println "\n=== List Ports (Large Registry: 1000 ports) ===" )
  (let [reg (create-populated-registry 1000)]
    (crit/with-progress-reporting
      (crit/quick-bench
        (core/list-ports reg)))))

;; =============================================================================
;; Filtering Benchmarks
;; =============================================================================

(defn create-mixed-registry
  "Create registry with tools, prompts, and resources."
  [n]
  (let [reg (registry/create-function-registry)]
    (doseq [i (range n)]
      (let [type (rand-nth [:tool :prompt :resource])
            port (merge (generate-port i)
                        (case type
                          :prompt {:metadata {:prompt true
                                              :prompt-args [{:name "arg" :required true}]}}
                          :resource {:metadata {:resource true
                                                :mime-type "text/plain"}}
                          :tool {}))]
        (core/register-port! reg port)))
    reg))

(defn benchmark-filter-by-metadata
  "Benchmark filtering ports by metadata."
  []
  (println "\n=== Filter Ports by Metadata (100 mixed ports) ===" )
  (let [reg (create-mixed-registry 100)]
    (crit/with-progress-reporting
      (crit/bench
        (filter #(get-in % [:metadata :prompt]) (core/list-ports reg))
        :verbose))))

;; =============================================================================
;; Concurrent Access Benchmarks
;; =============================================================================

(defn benchmark-concurrent-reads
  "Benchmark concurrent read access to registry."
  []
  (println "\n=== Concurrent Registry Reads (10 threads) ===" )
  (let [reg (create-populated-registry 100)
        target-ids (repeatedly 10 #(keyword (str "port-" (rand-int 100))))]
    (crit/with-progress-reporting
      (crit/bench
        (doall (pmap #(core/get-port reg %) target-ids))
        :verbose))))

(defn benchmark-concurrent-mixed-ops
  "Benchmark concurrent mixed operations (reads + writes)."
  []
  (println "\n=== Concurrent Mixed Operations ===" )
  (let [base-reg (create-populated-registry 50)]
    (crit/with-progress-reporting
      (crit/quick-bench
        (let [reg (registry/create-function-registry)]
          ;; Copy base ports
          (doseq [port-desc (core/list-ports base-reg)]
            (core/register-port! reg (core/get-port base-reg (:id port-desc))))
          ;; Concurrent reads and writes
          (doall
            (pmap
              (fn [i]
                (if (even? i)
                  (core/get-port reg (keyword (str "port-" (mod i 50))))
                  (core/register-port! reg (generate-port (+ 1000 i)))))
              (range 20))))))))

;; =============================================================================
;; Memory and Allocation Benchmarks
;; =============================================================================

(defn benchmark-registry-memory
  "Benchmark memory usage of registry with varying sizes."
  []
  (println "\n=== Registry Memory Footprint ===" )
  (println "\nCreating registries of varying sizes...")

  (let [sizes [10 100 1000]]
    (doseq [size sizes]
      (println (str "\nRegistry size: " size " ports"))
      (let [_ (System/gc)
            before (.totalMemory (Runtime/getRuntime))
            reg (create-populated-registry size)
            _ (System/gc)
            after (.totalMemory (Runtime/getRuntime))
            used (- after before)]
        (println (str "  Approximate memory: " (/ used 1024) " KB"))
        (println (str "  Per port: " (/ used size) " bytes"))))))

;; =============================================================================
;; Lookup Strategy Comparison
;; =============================================================================

(defn benchmark-lookup-strategies
  "Compare different lookup strategies."
  [registry-size]
  (println "\n=== Lookup Strategy Comparison ===" )
  (println "Registry size:" registry-size)

  (let [reg (create-populated-registry registry-size)]
    ;; First element
    (println "\nLookup FIRST element:")
    (crit/with-progress-reporting
      (crit/quick-bench
        (core/get-port reg :port-0)))

    ;; Middle element
    (println "\nLookup MIDDLE element:")
    (crit/with-progress-reporting
      (crit/quick-bench
        (core/get-port reg (keyword (str "port-" (quot registry-size 2))))))

    ;; Last element
    (println "\nLookup LAST element:")
    (crit/with-progress-reporting
      (crit/quick-bench
        (core/get-port reg (keyword (str "port-" (dec registry-size))))))))

;; =============================================================================
;; Main Benchmark Suites
;; =============================================================================

(defn run-quick-benchmarks
  "Run quick registry benchmarks."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "QUICK REGISTRY BENCHMARKS")
  (println (str (apply str (repeat 80 "="))))

  (binding [crit/*final-gc-problem-threshold* 0.01]
    (benchmark-port-lookup-small)
    (benchmark-port-registration)
    (benchmark-list-ports-small)))

(defn run-comprehensive-benchmarks
  "Run comprehensive registry benchmarks."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "COMPREHENSIVE REGISTRY BENCHMARKS")
  (println (str (apply str (repeat 80 "="))))

  (binding [crit/*final-gc-problem-threshold* 0.01]
    ;; Lookup benchmarks
    (benchmark-port-lookup-small)
    (benchmark-port-lookup-medium)
    (benchmark-port-lookup-large)
    (benchmark-port-lookup-not-found)

    ;; Registration benchmarks
    (benchmark-port-registration)
    (benchmark-bulk-registration-10)
    (benchmark-bulk-registration-100)

    ;; Listing benchmarks
    (benchmark-list-ports-small)
    (benchmark-list-ports-medium)
    (benchmark-list-ports-large)

    ;; Filtering
    (benchmark-filter-by-metadata)

    ;; Concurrent access
    (benchmark-concurrent-reads)
    (benchmark-concurrent-mixed-ops)

    ;; Lookup strategies
    (benchmark-lookup-strategies 100)))

(defn run-memory-benchmarks
  "Run memory-focused benchmarks."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "REGISTRY MEMORY BENCHMARKS")
  (println (str (apply str (repeat 80 "="))))

  (benchmark-registry-memory))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main entry point for registry benchmarks."
  [& args]
  (let [mode (or (first args) "quick")]
    (println "\n╔═══════════════════════════════════════════════════════════════╗")
    (println "║         DEFPORT REGISTRY BENCHMARKS (Criterium)              ║")
    (println "╚═══════════════════════════════════════════════════════════════╝")
    (println "\nMode:" mode)
    (println "Java version:" (System/getProperty "java.version"))
    (println "Clojure version:" (clojure-version))
    (println)

    (case mode
      "quick" (run-quick-benchmarks)
      "comprehensive" (run-comprehensive-benchmarks)
      "memory" (run-memory-benchmarks)
      (do
        (println "Unknown mode:" mode)
        (println "Available modes: quick, comprehensive, memory")
        (System/exit 1)))

    (println "\n✓ Registry benchmarks complete!")
    (shutdown-agents)))

(comment
  ;; Run individual benchmarks in REPL
  (benchmark-port-lookup-small)
  (benchmark-list-ports-medium)
  (run-quick-benchmarks)
  )
