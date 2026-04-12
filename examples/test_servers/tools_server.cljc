(ns tools-server
  "Comprehensive MCP tools server demonstrating all tool-related features.

  This server includes 15+ tools that demonstrate:
  - Basic tools (echo, add, multiply)
  - Search and data retrieval tools
  - Long-running operations with progress notifications
  - Cancellable operations
  - Error handling
  - Content types (TextContent with JSON, raw text)
  - Pagination scenarios

  Usage:
    # JVM
    clj -M:examples -m tools-server --http 8080
    clj -M:examples -m tools-server --stdio

    # Node.js
    node out/tools_server.js --http 8080
    node out/tools_server.js --stdio"
  (:require [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.registry :as registry]
            [defport.transports.http :as http-transport]
            [defport.transports.stdio :as stdio-transport]
            [defport.util.platform :as platform]
            [clojure.string :as str]))

;; ============================================================================
;; Tool Implementations
;; ============================================================================

(defn echo-handler
  "Echo back the input message. Demonstrates basic tool execution."
  [context]
  (let [message (get-in context [:params :message] "")]
    {:content [{:type "text"
                :text message}]}))

(defn add-handler
  "Add two numbers. Demonstrates numeric inputs and TextContent with JSON."
  [context]
  (let [a (get-in context [:params :a] 0)
        b (get-in context [:params :b] 0)
        result (+ a b)]
    {:content [{:type "text"
                :text (platform/json-encode {:result result
                                             :operation "addition"
                                             :operands [a b]})}]}))

(defn multiply-handler
  "Multiply two numbers."
  [context]
  (let [a (get-in context [:params :a] 1)
        b (get-in context [:params :b] 1)
        result (* a b)]
    {:content [{:type "text"
                :text (platform/json-encode {:result result
                                             :operation "multiplication"
                                             :operands [a b]})}]}))

(defn search-code-handler
  "Search for code matching a query. Demonstrates structured data return."
  [context]
  (let [query (get-in context [:params :query] "")
        ;; Simulate search results
        results [{:file "src/defport/core.cljc"
                  :line 42
                  :snippet "(defprotocol Port ...)"}
                 {:file "src/defport/protocols/mcp.cljc"
                  :line 156
                  :snippet "(defn handle-tools-call ...)"}
                 {:file "test/defport/protocols/mcp_test.clj"
                  :line 89
                  :snippet "(deftest test-tools-call ...)"}]]
    {:content [{:type "text"
                :text (platform/json-encode {:query query
                                             :resultCount (count results)
                                             :results results})}]}))

(defn get-time-handler
  "Get current timestamp. Demonstrates simple data retrieval."
  [context]
  {:content [{:type "text"
              :text (platform/json-encode {:timestamp (platform/now-ms)
                                           :formatted (platform/now-str)})}]})

(defn generate-uuid-handler
  "Generate a random UUID."
  [context]
  {:content [{:type "text"
              :text (platform/uuid)}]})

(defn list-files-handler
  "List files in a directory. Demonstrates pagination-ready data."
  [context]
  (let [directory (get-in context [:params :directory] ".")
        ;; Simulate file listing
        files [{:name "README.md" :size 1024 :type "file"}
               {:name "src" :size 4096 :type "directory"}
               {:name "test" :size 4096 :type "directory"}
               {:name "deps.edn" :size 512 :type "file"}
               {:name "CHANGELOG.md" :size 2048 :type "file"}]]
    {:content [{:type "text"
                :text (platform/json-encode {:directory directory
                                             :fileCount (count files)
                                             :files files})}]}))

(defn calculate-stats-handler
  "Calculate statistics for a list of numbers."
  [context]
  (let [numbers (get-in context [:params :numbers] [])
        sum (reduce + 0 numbers)
        cnt (count numbers)
        mean (if (pos? cnt) (double (/ sum cnt)) 0)
        min-val (if (seq numbers) (apply min numbers) 0)
        max-val (if (seq numbers) (apply max numbers) 0)]
    {:content [{:type "text"
                :text (platform/json-encode {:count cnt
                                             :sum sum
                                             :mean mean
                                             :min min-val
                                             :max max-val
                                             :numbers numbers})}]}))

(defn reverse-string-handler
  "Reverse a string. Demonstrates text manipulation."
  [context]
  (let [text (get-in context [:params :text] "")]
    {:content [{:type "text"
                :text (str/reverse text)}]}))

(defn to-uppercase-handler
  "Convert text to uppercase."
  [context]
  (let [text (get-in context [:params :text] "")]
    {:content [{:type "text"
                :text (str/upper-case text)}]}))

(defn to-lowercase-handler
  "Convert text to lowercase."
  [context]
  (let [text (get-in context [:params :text] "")]
    {:content [{:type "text"
                :text (str/lower-case text)}]}))

(defn long-running-handler
  "Simulate a long-running operation with progress notifications.
  Demonstrates progress reporting and cancellation checking.

  Note: On CLJS this returns a Promise chain. The server infrastructure
  should handle async tool results appropriately."
  [context]
  (let [duration-ms (get-in context [:params :durationMs] 10000)
        steps 10
        step-duration (/ duration-ms steps)
        progress-callback (get-in context [:metadata :progress-callback])
        check-cancelled (get-in context [:metadata :cancellation-check])]

    #?(:clj
       ;; JVM: synchronous with Thread/sleep
       (do
         (doseq [step (range 1 (inc steps))]
           ;; Check for cancellation
           (when (and check-cancelled (check-cancelled))
             (throw (ex-info "Operation cancelled by client"
                             {:code -32800
                              :message "Operation was cancelled"})))
           ;; Report progress
           (when progress-callback
             (progress-callback {:progress step :total steps}))
           ;; Simulate work
           (platform/sleep step-duration))
         {:content [{:type "text"
                     :text (platform/json-encode {:completed true
                                                  :duration duration-ms
                                                  :steps steps})}]})

       :cljs
       ;; CLJS: async with Promises
       (let [run-step (fn run-step [step]
                        (if (> step steps)
                          ;; Done - return result
                          (js/Promise.resolve
                           {:content [{:type "text"
                                       :text (platform/json-encode {:completed true
                                                                    :duration duration-ms
                                                                    :steps steps})}]})
                          ;; Check cancellation, report progress, sleep, recurse
                          (if (and check-cancelled (check-cancelled))
                            (js/Promise.reject
                             (ex-info "Operation cancelled by client"
                                      {:code -32800 :message "Operation was cancelled"}))
                            (do
                              (when progress-callback
                                (progress-callback {:progress step :total steps}))
                              (.then (platform/sleep step-duration)
                                     (fn [_] (run-step (inc step))))))))]
         (run-step 1)))))

(defn slow-operation-handler
  "A slow operation (5 seconds) useful for timeout testing."
  [context]
  #?(:clj
     (do
       (platform/sleep 5000)
       {:content [{:type "text"
                   :text (platform/json-encode {:completed true
                                                :duration 5000})}]})
     :cljs
     (.then (platform/sleep 5000)
            (fn [_]
              {:content [{:type "text"
                          :text (platform/json-encode {:completed true
                                                       :duration 5000})}]}))))

(defn error-handler
  "Always returns an error. Useful for error handling tests."
  [context]
  {:error {:code -32000
           :message "This tool always fails"
           :data {:reason "This is a test error tool"
                  :timestamp (platform/now-ms)}}})

(defn json-parser-handler
  "Parse JSON string and return structured data. Demonstrates data transformation."
  [context]
  (let [json-str (get-in context [:params :jsonString] "{}")]
    (try
      (let [parsed (platform/json-decode json-str)]
        {:content [{:type "text"
                    :text (platform/json-encode {:success true
                                                 :parsed parsed})}]})
      (catch #?(:clj Exception :cljs js/Error) e
        {:error {:code -32602
                 :message "Invalid JSON"
                 :data {:error #?(:clj (.getMessage e)
                                  :cljs (.-message e))}}}))))

(defn batch-process-handler
  "Process multiple items in batch. Demonstrates handling array inputs."
  [context]
  (let [items (get-in context [:params :items] [])
        operation (get-in context [:params :operation] "uppercase")
        process-fn (case operation
                     "uppercase" str/upper-case
                     "lowercase" str/lower-case
                     "reverse" str/reverse
                     identity)
        processed (mapv process-fn items)]
    {:content [{:type "text"
                :text (platform/json-encode {:operation operation
                                             :inputCount (count items)
                                             :outputCount (count processed)
                                             :results processed})}]}))

;; ============================================================================
;; Tool Definitions
;; ============================================================================

(def tools
  [{:id :echo
    :name "echo"
    :description "Echo back the input message"
    :input-schema {:type "object"
                   :properties {:message {:type "string"
                                          :description "Message to echo back"}}
                   :required ["message"]}
    :handler echo-handler}

   {:id :add
    :name "add"
    :description "Add two numbers together"
    :input-schema {:type "object"
                   :properties {:a {:type "number"
                                    :description "First number"}
                                :b {:type "number"
                                    :description "Second number"}}
                   :required ["a" "b"]}
    :handler add-handler}

   {:id :multiply
    :name "multiply"
    :description "Multiply two numbers"
    :input-schema {:type "object"
                   :properties {:a {:type "number"
                                    :description "First number"}
                                :b {:type "number"
                                    :description "Second number"}}
                   :required ["a" "b"]}
    :handler multiply-handler}

   {:id :search-code
    :name "search-code"
    :description "Search for code matching a query"
    :input-schema {:type "object"
                   :properties {:query {:type "string"
                                        :description "Search query"}}
                   :required ["query"]}
    :handler search-code-handler}

   {:id :get-time
    :name "get-time"
    :description "Get current timestamp"
    :input-schema {:type "object"
                   :properties {}}
    :handler get-time-handler}

   {:id :generate-uuid
    :name "generate-uuid"
    :description "Generate a random UUID"
    :input-schema {:type "object"
                   :properties {}}
    :handler generate-uuid-handler}

   {:id :list-files
    :name "list-files"
    :description "List files in a directory"
    :input-schema {:type "object"
                   :properties {:directory {:type "string"
                                            :description "Directory path"}}
                   :required ["directory"]}
    :handler list-files-handler}

   {:id :calculate-stats
    :name "calculate-stats"
    :description "Calculate statistics for a list of numbers"
    :input-schema {:type "object"
                   :properties {:numbers {:type "array"
                                          :items {:type "number"}
                                          :description "List of numbers"}}
                   :required ["numbers"]}
    :handler calculate-stats-handler}

   {:id :reverse-string
    :name "reverse-string"
    :description "Reverse a string"
    :input-schema {:type "object"
                   :properties {:text {:type "string"
                                       :description "Text to reverse"}}
                   :required ["text"]}
    :handler reverse-string-handler}

   {:id :to-uppercase
    :name "to-uppercase"
    :description "Convert text to uppercase"
    :input-schema {:type "object"
                   :properties {:text {:type "string"
                                       :description "Text to convert"}}
                   :required ["text"]}
    :handler to-uppercase-handler}

   {:id :to-lowercase
    :name "to-lowercase"
    :description "Convert text to lowercase"
    :input-schema {:type "object"
                   :properties {:text {:type "string"
                                       :description "Text to convert"}}
                   :required ["text"]}
    :handler to-lowercase-handler}

   {:id :long-running
    :name "long-running"
    :description "Simulate a long-running operation with progress notifications (10+ seconds)"
    :input-schema {:type "object"
                   :properties {:durationMs {:type "number"
                                             :description "Duration in milliseconds"
                                             :default 10000}}
                   :required []}
    :handler long-running-handler}

   {:id :slow-operation
    :name "slow-operation"
    :description "A slow operation (5 seconds) useful for timeout testing"
    :input-schema {:type "object"
                   :properties {}}
    :handler slow-operation-handler}

   {:id :error-tool
    :name "error-tool"
    :description "Always returns an error (for error handling tests)"
    :input-schema {:type "object"
                   :properties {}}
    :handler error-handler}

   {:id :json-parser
    :name "json-parser"
    :description "Parse a JSON string and return structured data"
    :input-schema {:type "object"
                   :properties {:jsonString {:type "string"
                                             :description "JSON string to parse"}}
                   :required ["jsonString"]}
    :handler json-parser-handler}

   {:id :batch-process
    :name "batch-process"
    :description "Process multiple items in batch"
    :input-schema {:type "object"
                   :properties {:items {:type "array"
                                        :items {:type "string"}
                                        :description "Items to process"}
                                :operation {:type "string"
                                            :enum ["uppercase" "lowercase" "reverse"]
                                            :description "Operation to perform"
                                            :default "uppercase"}}
                   :required ["items"]}
    :handler batch-process-handler}])

;; ============================================================================
;; Server Setup
;; ============================================================================

(defn create-registry
  "Create a registry with all tools."
  []
  (let [reg (registry/create-function-registry)]
    (doseq [tool tools]
      (core/register-port! reg tool))
    reg))

(defn start-http-server
  "Start HTTP server on specified port."
  [port]
  (let [reg (create-registry)
        adapter (mcp/create-mcp-adapter)
        transport (http-transport/create-http-transport {:port port})]
    (println (str "Starting tools_server on HTTP port " port "..."))
    (println (str "Platform: " (name platform/platform)))
    (println (str "Tools available: " (count tools)))
    (println "Tools:")
    (doseq [tool tools]
      (println (str "  - " (:name tool) ": " (:description tool))))
    (println)
    (println "Test with:")
    (println (str "  curl -X POST http://localhost:" port
                  " -H 'Content-Type: application/json'"
                  " -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                  "\"params\":{\"protocolVersion\":\"2025-06-18\","
                  "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1.0\"}}}'"))
    (println)
    (core/start-server reg adapter transport)
    (println "Server started. Press Ctrl+C to stop.")
    ;; Keep the server running
    #?(:clj @(promise)
       :cljs nil)))  ; Node.js event loop keeps it alive

(defn start-stdio-server
  "Start stdio server."
  []
  (let [reg (create-registry)
        adapter (mcp/create-mcp-adapter)
        transport (stdio-transport/create-stdio-transport)]
    (platform/eprintln "Starting tools_server on stdio...")
    (platform/eprintln (str "Platform: " (name platform/platform)))
    (platform/eprintln (str "Tools available: " (count tools)))
    (core/start-server reg adapter transport)
    (platform/eprintln "Server started. Listening on stdin/stdout.")
    ;; Keep the server running
    #?(:clj @(promise)
       :cljs nil)))

(defn -main
  "Main entry point. Parse command line args and start server."
  [& args]
  (let [args-vec (vec args)]
    (cond
      ;; HTTP transport
      (and (>= (count args-vec) 2)
           (= "--http" (first args-vec)))
      (let [port (platform/parse-int (second args-vec) 8080)]
        (start-http-server port))

      ;; stdio transport
      (and (>= (count args-vec) 1)
           (= "--stdio" (first args-vec)))
      (start-stdio-server)

      ;; Default: HTTP on port 8080
      :else
      (do
        (println "Usage:")
        (println "  clj -M:examples -m tools-server --http <port>")
        (println "  clj -M:examples -m tools-server --stdio")
        (println)
        (println "Starting default HTTP server on port 8080...")
        (start-http-server 8080)))))

;; For ClojureScript, export main for Node.js
#?(:cljs
   (set! *main-cli-fn* -main))