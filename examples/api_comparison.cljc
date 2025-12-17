(ns api-comparison
  "Defport offers two styles: Macro-based (concise) vs Fluent (explicit).

  Both styles are in the unified defport namespace - one require, all features.

  Choose Macros for: Quick prototypes, simple servers, minimal boilerplate
  Choose Fluent for:  Complex config, dynamic tools, testing, explicit control"
  (:require [defport :refer [;; Macros (Level 1-3)
                             defserver deftool defprompt defresource run! stop!
                             ;; Fluent API (Level 4)
                             server add-tool add-resource add-prompt
                             with-transport with-options build! start!
                             ;; Introspection
                             list-tools list-prompts list-resources running?
                             ;; Runtime modification
                             add-tool! add-prompt! add-resource!
                             ;; Advanced (Level 5)
                             elicit! sample! get-roots validate-file!
                             ;; Schema
                             register-schema!]]))

;; =============================================================================
;; MACRO API - Concise, declarative (Level 1-3)
;; =============================================================================

(comment
  ;; Level 1: Minimal
  (defserver my-server "1.0.0")

  (deftool search-code [query :- :string]
    "Search codebase."
    {:matches [] :total 0})

  (run!)

  ;; Level 2: With context
  (deftool process [uri :- :string, ctx :- :context]
    "Process with progress reporting."
    (report-progress ctx 50 100 "Processing...")
    {:result "done"})

  ;; Level 3: With options
  (deftool rename-function
    {:dangerous true :tags #{:refactoring}}
    [old-name :- :string, new-name :- :string]
    "Rename function (modifies files)."
    {:files-changed 3})

  ;; Prompts
  (defprompt explain [code :- :string]
    "Explain code."
    [{:role    "user"
      :content {:type "text" :text (str "Explain: " code)}}])

  ;; Resources
  (defresource "schema://db"
    "Database schema."
    {:tables [:users :posts]})

  ;; Run with options
  (run! {:transport :http :port 8080}))

;; =============================================================================
;; FLUENT API - Explicit, functional (Level 4)
;; =============================================================================

(comment
  ;; Builder pattern
  (def my-server
    (-> (server "my-server" "1.0.0")

        ;; Add tools
        (add-tool :search-code
          (fn [{:keys [params]}]
            {:result {:matches [] :total 0}})
          {:description "Search codebase"
           :schema [:map [:query :string]]})

        ;; Dangerous tools
        (add-tool :rename-function
          (fn [{:keys [params]}]
            {:result {:files-changed 3}})
          {:description "Rename function"
           :dangerous true})

        ;; Add prompts
        (add-prompt :explain
          (fn [{:keys [params]}]
            {:messages [{:role "user"
                         :content {:type "text" :text "Explain..."}}]})
          {:description "Explain code"})

        ;; Add resources
        (add-resource "schema://db"
          (fn [_] {:tables [:users :posts]})
          {:description "DB schema"})

        ;; Configure transport
        (with-transport :http {:port 8080})
        (build!)))

  ;; Lifecycle
  (start! my-server)
  (stop! my-server)

  ;; Introspection
  (list-tools)
  (list-prompts)
  (running?)

  ;; Hot reload
  (add-tool! {:id :new-tool
              :name "new-tool"
              :description "Added at runtime"
              :handler (fn [_] {:result "ok"})}))

;; =============================================================================
;; ADVANCED FEATURES (Level 5)
;; =============================================================================

(comment
  ;; Elicitation - ask user for input during tool execution
  (deftool delete-file [path :- :string]
    "Delete with confirmation."
    (let [response (elicit! {:message (str "Delete " path "?")
                             :schema [:map [:confirmed :boolean]]})]
      (when (and (= :accept (:action response))
                 (get-in response [:content :confirmed]))
        {:deleted path})))

  ;; Sampling - request LLM completion from client
  (deftool analyze [code :- :string]
    "Analyze code using LLM."
    (let [response (sample! [{:role "user"
                              :content {:type "text"
                                        :text (str "Analyze:\n" code)}}])]
      {:analysis (:content response)}))

  ;; Malli schemas for complex validation
  (register-schema! :search-params
    [:map
     [:query [:string {:min 1 :max 500}]]
     [:limit {:optional true} [:int {:min 1 :max 100}]]])

  (deftool search :search-params
    "Search with validated params."
    {:results []}))

;; =============================================================================
;; Comparison Summary
;; =============================================================================

(comment
  "
  | Feature          | Macros               | Fluent               |
  |------------------|----------------------|----------------------|
  | Lines of code    | ~5 per tool          | ~12 per tool         |
  | Schema syntax    | [arg :- :type]       | Malli or JSON Schema |
  | Global state     | Yes (implicit)       | No (explicit)        |
  | Hot reload       | add-tool!            | add-tool! (on server)|
  | Introspection    | list-tools           | list-tools           |
  | Testing          | Reset state manually | Isolated instances   |
  | Dynamic tools    | Possible             | Natural              |

  Both use the same unified defport namespace.
  Mix styles freely in the same project.
  ")
