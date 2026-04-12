(ns defport.benchmark.latency-benchmark
  "Single operation latency benchmarks.

  Measures latency of core operations: port execution, registry lookups, protocol operations.

  Run with: clojure -M:benchmark -m defport.benchmark.latency-benchmark"
  (:require [criterium.core :as crit]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.mcp :as mcp]
            [cheshire.core :as json]))

;; =============================================================================
;; Test Registry Setup
;; =============================================================================

(defn create-test-registry
  "Create a registry with various port types for benchmarking."
  []
  (let [reg (registry/create-function-registry)]
    ;; Fast tool - minimal computation
    (core/register-port! reg
      {:id :echo
       :name "echo"
       :description "Echo input"
       :input-schema {:type "object"
                      :properties {:message {:type "string"}}}
       :handler (fn [{:keys [params]}]
                  {:result (:message params)})})

    ;; Medium complexity - data transformation
    (core/register-port! reg
      {:id :transform
       :name "transform"
       :description "Transform data"
       :input-schema {:type "object"
                      :properties {:data {:type "array"}}}
       :handler (fn [{:keys [params]}]
                  {:result (vec (map #(* % 2) (:data params)))})})

    ;; Complex - nested operations
    (core/register-port! reg
      {:id :complex
       :name "complex"
       :description "Complex operation"
       :input-schema {:type "object"
                      :properties {:input {:type "object"}}}
       :handler (fn [{:keys [params]}]
                  (let [input (:input params)
                        processed (reduce-kv
                                    (fn [m k v]
                                      (assoc m k (str v "-processed")))
                                    {}
                                    input)]
                    {:result processed}))})

    ;;  Prompt
    (core/register-port! reg
      {:id :code-review
       :name "code-review"
       :description "Review code"
       :metadata {:prompt true
                  :prompt-args [{:name "code" :required true}]}
       :handler (fn [{:keys [params]}]
                  {:messages [{:role "user"
                               :content {:type "text"
                                        :text (str "Review: " (:code params))}}]})})

    ;; Resource
    (core/register-port! reg
      {:id :schema
       :name "schema://config"
       :description "Configuration schema"
       :metadata {:resource true
                  :mime-type "application/json"}
       :handler (fn [_]
                  {:contents [{:uri "schema://config"
                               :mimeType "application/json"
                               :text "{\"type\": \"object\"}"}]})})
    reg))

;; =============================================================================
;; Port Execution Benchmarks
;; =============================================================================

(defn benchmark-port-execution-fast
  "Benchmark fast port execution (echo)."
  []
  (println "\n=== Fast Port Execution (echo) ===" )
  (let [registry (create-test-registry)
        port (core/get-port registry :echo)
        context {:params {:message "Hello, World!"}
                 :port-registry registry}]
    (crit/with-progress-reporting
      (crit/bench
        (core/port-execute port context)
        :verbose))))

(defn benchmark-port-execution-medium
  "Benchmark medium complexity port execution (transform)."
  []
  (println "\n=== Medium Port Execution (transform) ===" )
  (let [registry (create-test-registry)
        port (core/get-port registry :transform)
        context {:params {:data (vec (range 100))}
                 :port-registry registry}]
    (crit/with-progress-reporting
      (crit/bench
        (core/port-execute port context)
        :verbose))))

(defn benchmark-port-execution-complex
  "Benchmark complex port execution."
  []
  (println "\n=== Complex Port Execution ===" )
  (let [registry (create-test-registry)
        port (core/get-port registry :complex)
        context {:params {:input {:key1 "value1"
                                  :key2 "value2"
                                  :key3 "value3"
                                  :key4 "value4"
                                  :key5 "value5"}}
                 :port-registry registry}]
    (crit/with-progress-reporting
      (crit/bench
        (core/port-execute port context)
        :verbose))))

;; =============================================================================
;; Registry Operation Benchmarks
;; =============================================================================

(defn benchmark-port-lookup
  "Benchmark single port lookup."
  []
  (println "\n=== Port Lookup ===" )
  (let [registry (create-test-registry)]
    (crit/with-progress-reporting
      (crit/bench
        (core/get-port registry :echo)
        :verbose))))

(defn benchmark-list-ports
  "Benchmark listing all ports."
  []
  (println "\n=== List Ports ===" )
  (let [registry (create-test-registry)]
    (crit/with-progress-reporting
      (crit/bench
        (core/list-ports registry)
        :verbose))))

;; =============================================================================
;; Protocol Capability Benchmarks
;; =============================================================================

(defn benchmark-protocol-capabilities
  "Benchmark protocol capabilities generation."
  []
  (println "\n=== Protocol Capabilities ===" )
  (let [adapter (mcp/create-mcp-adapter)
        registry (create-test-registry)]
    (crit/with-progress-reporting
      (crit/bench
        (core/protocol-capabilities adapter registry)
        :verbose))))

;; =============================================================================
;; JSON Serialization Benchmarks
;; =============================================================================

(defn benchmark-json-serialization-small
  "Benchmark JSON serialization of small response."
  []
  (println "\n=== JSON Serialization (Small) ===" )
  (let [response {:jsonrpc "2.0"
                  :id 1
                  :result {:content [{:type "text"
                                     :text "Sample response"}]}}]
    (crit/with-progress-reporting
      (crit/bench
        (json/generate-string response)
        :verbose))))

(defn benchmark-json-serialization-medium
  "Benchmark JSON serialization of medium response."
  []
  (println "\n=== JSON Serialization (Medium) ===" )
  (let [response {:jsonrpc "2.0"
                  :id 1
                  :result {:content (vec (repeatedly 10
                                          #(hash-map :type "text"
                                                    :text "Sample text for benchmarking purposes")))}}]
    (crit/with-progress-reporting
      (crit/bench
        (json/generate-string response)
        :verbose))))

(defn benchmark-json-deserialization
  "Benchmark JSON deserialization of requests."
  []
  (println "\n=== JSON Deserialization ===" )
  (let [json-str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"]
    (crit/with-progress-reporting
      (crit/bench
        (json/parse-string json-str true)
        :verbose))))

;; =============================================================================
;; Main Benchmark Suites
;; =============================================================================

(defn run-all-latency-benchmarks
  "Run all latency benchmarks."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "LATENCY BENCHMARKS")
  (println (str (apply str (repeat 80 "="))))

  (binding [crit/*final-gc-problem-threshold* 0.01]
    ;; Port execution
    (benchmark-port-execution-fast)
    (benchmark-port-execution-medium)
    (benchmark-port-execution-complex)

    ;; Registry operations
    (benchmark-port-lookup)
    (benchmark-list-ports)

    ;; Protocol operations
    (benchmark-protocol-capabilities)

    ;; JSON overhead
    (benchmark-json-serialization-small)
    (benchmark-json-serialization-medium)
    (benchmark-json-deserialization)))

(defn run-quick-latency-benchmarks
  "Run quick subset of latency benchmarks."
  []
  (println "\n" (str (apply str (repeat 80 "="))))
  (println "QUICK LATENCY BENCHMARKS")
  (println (str (apply str (repeat 80 "="))))

  (binding [crit/*final-gc-problem-threshold* 0.01]
    (benchmark-port-execution-fast)
    (benchmark-port-lookup)
    (benchmark-list-ports)
    (benchmark-json-serialization-small)))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn -main
  "Main entry point for latency benchmarks."
  [& args]
  (let [mode (or (first args) "quick")]
    (println "\n╔═══════════════════════════════════════════════════════════════╗")
    (println "║          DEFPORT LATENCY BENCHMARKS (Criterium)              ║")
    (println "╚═══════════════════════════════════════════════════════════════╝")
    (println "\nMode:" mode)
    (println "Java version:" (System/getProperty "java.version"))
    (println "Clojure version:" (clojure-version))
    (println)

    (case mode
      "quick" (run-quick-latency-benchmarks)
      "all" (run-all-latency-benchmarks)
      (do
        (println "Unknown mode:" mode)
        (println "Available modes: quick, all")
        (System/exit 1)))

    (println "\n✓ Latency benchmarks complete!")
    (shutdown-agents)))

(comment
  ;; Run individual benchmarks in REPL
  (benchmark-port-execution-fast)
  (benchmark-port-lookup)
  (run-quick-latency-benchmarks)
  )
