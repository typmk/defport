(ns demo-server
  "FastMCP-style demo showing progressive disclosure.

  Run with:
    clj -M:examples -m demo-server
    clj -M:examples -m demo-server --http 8080"
  (:require [defport :refer [defserver deftool defresource defprompt
                              run! log report-progress]]))

;; ============================================================================
;; Level 1: Minimal - Just Works
;; ============================================================================

(defserver demo)

(deftool add [a :- :int, b :- :int]
  "Add two numbers"
  (+ a b))

(deftool multiply [a :- :int, b :- :int]
  "Multiply two numbers"
  (* a b))

(deftool greet [name :- :string]
  "Generate a greeting"
  (str "Hello, " name "!"))

;; ============================================================================
;; Level 2: With Context - Opt-in Power
;; ============================================================================

(deftool process-data [uri :- :string, ctx :- :context]
  "Process data with progress reporting"
  (log ctx :info (str "Processing " uri "..."))
  (report-progress ctx 25 100 "Loading")
  ;; Simulate work
  (report-progress ctx 50 100 "Analyzing")
  (report-progress ctx 75 100 "Finalizing")
  (report-progress ctx 100 100 "Complete")
  {:status "success"
   :uri uri
   :message "Data processed successfully"})

(deftool search-with-logging [query :- :string, ctx :- :context]
  "Search with detailed logging"
  (log ctx :info (str "Searching for: " query))
  ;; Simulate search
  (let [results [{:title "Result 1" :score 0.95}
                 {:title "Result 2" :score 0.87}
                 {:title "Result 3" :score 0.76}]]
    (log ctx :info (str "Found " (count results) " results"))
    results))

;; ============================================================================
;; Level 3: With Options - Full Control
;; ============================================================================

(deftool calculate
  {:tags #{:math :advanced}
   :annotations {:read-only-hint true}}
  [operation :- :string, a :- :number, b :- :number]
  "Perform a calculation with full options"
  (case operation
    "add" (+ a b)
    "subtract" (- a b)
    "multiply" (* a b)
    "divide" (if (zero? b)
               {:error {:code -32602 :message "Division by zero"}}
               (/ a b))
    {:error {:code -32602 :message (str "Unknown operation: " operation)}}))

;; ============================================================================
;; Resources
;; ============================================================================

(defresource "config://version"
  "Get server version"
  "1.0.0")

(defresource "config://settings"
  "Get server settings"
  {:debug false
   :max-results 100
   :timeout-ms 30000})

;; ============================================================================
;; Prompts
;; ============================================================================

(defprompt summarize [text :- :string]
  "Generate a summarization prompt"
  (str "Please summarize the following text concisely:\n\n" text))

(defprompt explain [topic :- :string, level :- :string]
  "Generate an explanation prompt"
  (str "Please explain " topic " at a " level " level. "
       "Use clear examples and avoid jargon."))

;; ============================================================================
;; Entry Point
;; ============================================================================

(defn -main [& args]
  (let [transport (if (some #{"--http"} args) :http :stdio)
        port (or (some->> args
                          (drop-while #(not= "--http" %))
                          second
                          #?(:clj Integer/parseInt
                             :cljs js/parseInt))
                 8080)]
    (run! {:transport transport :port port})))

#?(:cljs (set! *main-cli-fn* -main))