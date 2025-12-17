(ns features
  "MCP advanced features reference.

  Sections:
    1. Sampling    - LLM requests during tool execution
    2. Elicitation - Interactive user input
    3. Completions - Argument autocomplete
    4. Roots       - Filesystem boundaries
    5. Media       - Image/audio content
    6. Progress    - Real-time updates
    7. Malli       - Schema validation"
  (:require [defport :as mcp]
            [defport.util.content :as content]
            [clojure.string :as str]))

;; =============================================================================
;; 1. SAMPLING - Request LLM completions during tool execution
;; =============================================================================

(comment
  "Sampling lets tools call the LLM for reasoning steps."

  (mcp/deftool analyze-code
    "Analyze code using LLM."
    [code :- :string]
    (let [response (mcp/sample!
                     [{:role    "user"
                       :content {:type "text"
                                 :text (str "Analyze this code:\n\n" code)}}]
                     {:max-tokens 500})]
      {:analysis (get-in response [:content :text])
       :status   "completed"}))

  ;; Multi-step reasoning
  (mcp/deftool solve-problem
    "Break down and solve a problem."
    [problem :- :string]
    (let [plan     (mcp/sample!
                     [{:role    "user"
                       :content {:type "text"
                                 :text (str "Plan steps for: " problem)}}])
          solution (mcp/sample!
                     [{:role    "user"
                       :content {:type "text"
                                 :text (str "Execute: " (:text (:content plan)))}}])]
      {:plan     plan
       :solution solution})))

;; =============================================================================
;; 2. ELICITATION - Request user input during tool execution
;; =============================================================================

(comment
  "Elicitation lets tools ask users for confirmation or input."

  (mcp/deftool delete-file
    "Delete file with user confirmation."
    [path :- :string]
    (let [response (mcp/elicit!
                     {:message (str "Delete " path "?")
                      :schema  {:type       "object"
                                :properties {:confirmed {:type "boolean"}}
                                :required   ["confirmed"]}})]
      (case (:action response)
        :accept  (if (get-in response [:content :confirmed])
                   {:result "Deleted" :path path}
                   {:result "Cancelled"})
        :decline {:result "Declined"}
        :cancel  {:result "Cancelled"})))

  ;; Multiple choice elicitation
  (mcp/deftool choose-strategy
    "Let user choose optimization strategy."
    [target :- :string]
    (let [response (mcp/elicit!
                     {:message "Choose strategy:"
                      :schema  {:type       "object"
                                :properties {:strategy {:type "string"
                                                        :enum ["speed" "memory" "balanced"]}}}})]
      {:strategy (get-in response [:content :strategy])
       :target   target})))

;; =============================================================================
;; 3. COMPLETIONS - Argument autocomplete
;; =============================================================================

(comment
  "Completions provide suggestions as users type."

  ;; Static completions
  (mcp/deftool search-code
    "Search with type filter."
    [query :- :string
     type  :- :string]
    {:metadata {:completions
                {:type (fn [partial _]
                         (filter #(str/starts-with? % partial)
                                 ["file" "function" "class" "variable"]))}}}
    {:results []})

  ;; Context-aware completions (second arg depends on first)
  (mcp/deftool team-greeting
    "Greet team member."
    [department :- :string
     name       :- :string]
    {:metadata {:completions
                {:department (fn [partial _]
                               (filter #(str/starts-with? % partial)
                                       ["engineering" "design" "marketing"]))
                 :name       (fn [partial {:keys [department]}]
                               (case department
                                 "engineering" ["alice" "bob"]
                                 "design"      ["carol" "dave"]
                                 []))}}}
    {:greeting (str "Hello, " name " from " department)}))

;; =============================================================================
;; 4. ROOTS - Filesystem boundaries
;; =============================================================================

(comment
  "Roots restrict file access to allowed directories."

  (mcp/deftool read-file-safe
    "Read file (validates within roots)."
    [path :- :string]
    (mcp/validate-file! path)  ; Throws if outside roots
    {:content (slurp path)})

  (mcp/deftool list-workspace
    "List files in workspace roots."
    []
    (let [roots (mcp/get-roots)]
      {:roots (map :uri roots)}))

  ;; Client sends roots via notification:
  ;; {"method": "notifications/roots/list_changed",
  ;;  "params": {"roots": [{"uri": "file:///workspace", "name": "Project"}]}}
  )

;; =============================================================================
;; 5. MEDIA CONTENT - Images and audio
;; =============================================================================

(comment
  "Return rich media content from tools."

  (mcp/deftool generate-diagram
    "Generate architecture diagram."
    [component :- :string]
    (let [png-bytes (generate-png-somehow component)]
      (content/image-content png-bytes "image/png")))

  (mcp/deftool text-to-speech
    "Convert text to audio."
    [text :- :string]
    (let [wav-bytes (synthesize-audio text)]
      (content/audio-content wav-bytes "audio/wav")))

  ;; Mixed content response
  (mcp/deftool analyze-image
    "Analyze image and return annotated version."
    [path :- :string]
    {:content [{:type "text"
                :text "Found 3 objects:"}
               (content/image-content (annotate-image path) "image/png")]}))

;; =============================================================================
;; 6. PROGRESS - Real-time updates
;; =============================================================================

(comment
  "Send progress updates for long-running operations."

  (mcp/deftool index-project
    "Index all files with progress."
    [path :- :string]
    (let [files (find-all-files path)
          total (count files)]
      (doseq [[i file] (map-indexed vector files)]
        (mcp/progress! (/ i total) (str "Indexing: " file))
        (index-file! file))
      {:indexed total}))

  ;; Check for cancellation
  (mcp/deftool long-search
    "Long-running search with cancellation support."
    [query :- :string]
    (loop [results []
           page    0]
      (when (mcp/cancelled?)
        (throw (ex-info "Cancelled" {:code -32800})))
      (mcp/progress! (/ page 10) (str "Page " page))
      (if (< page 10)
        (recur (into results (search-page query page))
               (inc page))
        results))))

;; =============================================================================
;; 7. MALLI SCHEMAS - Advanced validation
;; =============================================================================

(comment
  "Use Malli for expressive schema constraints."

  ;; Inline Malli schema
  (mcp/deftool advanced-search
    "Search with Malli validation."
    [:map
     [:query   [:string {:min 1 :max 500}]]
     [:limit   {:optional true} [:int {:min 1 :max 100}]]
     [:filters {:optional true}
      [:map
       [:type [:enum "file" "function" "class"]]]]]
    {:results []})

  ;; Named schemas (reusable)
  (mcp/register-schema! :file-path
    [:string {:min 1 :pattern #"^[^<>:\"|?*]+$"}])

  (mcp/deftool read-safe
    "Read with validated path."
    [:map [:path :file-path]]
    {:content (slurp (:path params))}))