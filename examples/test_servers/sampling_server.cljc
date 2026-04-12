(ns test-servers.sampling-server
  "MCP test server focusing on sampling functionality.

  This server provides tools that demonstrate server-initiated LLM sampling:
  - Tools that request LLM completions from the client
  - Model preference handling
  - Timeout management
  - System and user messages
  - Temperature and max tokens configuration

  Note: Sampling requires bidirectional communication. For stdio transport,
  this means the client must be able to receive and respond to sampling requests.

  Usage:
    HTTP:  clojure -M -m test-servers.sampling-server --http 8080
    Stdio: clojure -M -m test-servers.sampling-server --stdio"
  (:require [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.registry :as registry]
            [defport.transports.http :as http]
            [defport.transports.stdio :as stdio]
            [defport.util.platform :as platform]
            [clojure.string :as str]))

;; ============================================================================
;; Sampling Tool Handlers
;; ============================================================================

(defn generate-code-handler
  "Generate code using LLM sampling."
  [context]
  (let [description (get-in context [:params :description])
        language (get-in context [:params :language] "python")
        style (get-in context [:params :style] "clean")]

    (if (str/blank? description)
      {:error {:code -32602
               :message "Missing required argument: description"}}

      ;; Request LLM sampling from client
      (let [sampling-request
            {:method "sampling/createMessage"
             :params
             {:messages
              [{:role "system"
                :content {:type "text"
                          :text (str "You are a code generation assistant. "
                                    "Generate clean, well-documented " language " code. "
                                    "Style: " style)}}
               {:role "user"
                :content {:type "text"
                          :text (str "Generate " language " code for: " description)}}]
              :modelPreferences
              {:hints [{:name "claude-3-5-sonnet-20241022"}]
               :costPriority 0.5
               :speedPriority 0.5
               :intelligencePriority 0.8}
              :systemPrompt (str "Generate " language " code only. No explanations.")
              :maxTokens 500
              :temperature 0.3}}]

        ;; In real implementation, this would send sampling request to client
        ;; For testing, we simulate the response
        {:content
         [{:type "text"
           :text (str "# Simulated LLM response for code generation\n"
                     "# Description: " description "\n"
                     "# Language: " language "\n"
                     "# Style: " style "\n\n"
                     "def example_function():\n"
                     "    \"\"\"Generated code would appear here.\"\"\"\n"
                     "    pass")}]
         :metadata {:sampling-request sampling-request
                   :note "In production, this would use actual LLM sampling"}}))))

(defn explain-error-handler
  "Explain an error message using LLM sampling."
  [context]
  (let [error-msg (get-in context [:params :error_message])
        code-context (get-in context [:params :code_context])
        detail-level (get-in context [:params :detail_level] "detailed")]

    (if (str/blank? error-msg)
      {:error {:code -32602
               :message "Missing required argument: error_message"}}

      (let [sampling-request
            {:method "sampling/createMessage"
             :params
             {:messages
              [{:role "system"
                :content {:type "text"
                          :text "You are a debugging assistant. Explain errors clearly and provide actionable solutions."}}
               {:role "user"
                :content {:type "text"
                          :text (str "Error: " error-msg "\n\n"
                                    (when code-context
                                      (str "Code context:\n" code-context "\n\n"))
                                    "Provide a " detail-level " explanation.")}}]
              :modelPreferences
              {:hints [{:name "claude-3-5-sonnet-20241022"}]
               :intelligencePriority 0.9}
              :maxTokens 300
              :temperature 0.2}}]

        {:content
         [{:type "text"
           :text (str "# Simulated error explanation\n"
                     "Error: " error-msg "\n\n"
                     "This error typically occurs when...\n"
                     "To fix: ...\n")}]
         :metadata {:sampling-request sampling-request}}))))

(defn suggest-improvements-handler
  "Suggest code improvements using LLM sampling."
  [context]
  (let [code (get-in context [:params :code])
        focus (get-in context [:params :focus] "general")]

    (if (str/blank? code)
      {:error {:code -32602
               :message "Missing required argument: code"}}

      (let [sampling-request
            {:method "sampling/createMessage"
             :params
             {:messages
              [{:role "user"
                :content {:type "text"
                          :text (str "Review this code and suggest improvements.\n"
                                    "Focus: " focus "\n\n"
                                    code)}}]
              :modelPreferences
              {:hints [{:name "claude-3-5-sonnet-20241022"}]
               :intelligencePriority 0.8
               :speedPriority 0.3}
              :maxTokens 400
              :temperature 0.4}}]

        {:content
         [{:type "text"
           :text (str "# Simulated improvement suggestions\n"
                     "Code analysis for: " focus "\n\n"
                     "1. Consider...\n"
                     "2. Improve...\n"
                     "3. Refactor...\n")}]
         :metadata {:sampling-request sampling-request}}))))

(defn write-documentation-handler
  "Generate documentation using LLM sampling."
  [context]
  (let [code (get-in context [:params :code])
        format (get-in context [:params :format] "markdown")]

    (if (str/blank? code)
      {:error {:code -32602
               :message "Missing required argument: code"}}

      (let [sampling-request
            {:method "sampling/createMessage"
             :params
             {:messages
              [{:role "system"
                :content {:type "text"
                          :text (str "Generate clear, comprehensive documentation in " format " format.")}}
               {:role "user"
                :content {:type "text"
                          :text (str "Document this code:\n\n" code)}}]
              :modelPreferences
              {:hints [{:name "claude-3-5-sonnet-20241022"}]
               :costPriority 0.4}
              :maxTokens 600
              :temperature 0.3}}]

        {:content
         [{:type "text"
           :text (str "# Simulated documentation\n"
                     "## Overview\n"
                     "This code provides...\n\n"
                     "## Usage\n"
                     "Example usage...\n")}]
         :metadata {:sampling-request sampling-request}}))))

(defn translate-code-handler
  "Translate code between languages using LLM sampling."
  [context]
  (let [code (get-in context [:params :code])
        from-lang (get-in context [:params :from_language])
        to-lang (get-in context [:params :to_language])]

    (if (or (str/blank? code) (str/blank? from-lang) (str/blank? to-lang))
      {:error {:code -32602
               :message "Missing required arguments: code, from_language, to_language"}}

      (let [sampling-request
            {:method "sampling/createMessage"
             :params
             {:messages
              [{:role "system"
                :content {:type "text"
                          :text (str "Translate code from " from-lang " to " to-lang ". "
                                    "Preserve functionality and add appropriate comments.")}}
               {:role "user"
                :content {:type "text"
                          :text (str "Translate this " from-lang " code to " to-lang ":\n\n" code)}}]
              :modelPreferences
              {:hints [{:name "claude-3-5-sonnet-20241022"}]
               :intelligencePriority 0.9}
              :maxTokens 800
              :temperature 0.2}}]

        {:content
         [{:type "text"
           :text (str "# Simulated translation from " from-lang " to " to-lang "\n"
                     "# Original: " from-lang "\n"
                     "# Target: " to-lang "\n\n"
                     "// Translated code would appear here")}]
         :metadata {:sampling-request sampling-request}}))))

(defn generate-tests-handler
  "Generate unit tests using LLM sampling."
  [context]
  (let [code (get-in context [:params :code])
        framework (get-in context [:params :framework] "pytest")]

    (if (str/blank? code)
      {:error {:code -32602
               :message "Missing required argument: code"}}

      (let [sampling-request
            {:method "sampling/createMessage"
             :params
             {:messages
              [{:role "system"
                :content {:type "text"
                          :text (str "Generate comprehensive unit tests using " framework ".")}}
               {:role "user"
                :content {:type "text"
                          :text (str "Write tests for:\n\n" code)}}]
              :modelPreferences
              {:hints [{:name "claude-3-5-sonnet-20241022"}]
               :intelligencePriority 0.8}
              :maxTokens 700
              :temperature 0.3}}]

        {:content
         [{:type "text"
           :text (str "# Simulated test generation\n"
                     "# Framework: " framework "\n\n"
                     "def test_example():\n"
                     "    assert True  # Tests would be here")}]
         :metadata {:sampling-request sampling-request}}))))

(defn answer-question-handler
  "Answer a programming question using LLM sampling."
  [context]
  (let [question (get-in context [:params :question])
        context-info (get-in context [:params :context])]

    (if (str/blank? question)
      {:error {:code -32602
               :message "Missing required argument: question"}}

      (let [sampling-request
            {:method "sampling/createMessage"
             :params
             {:messages
              [{:role "user"
                :content {:type "text"
                          :text (str question
                                    (when context-info
                                      (str "\n\nContext: " context-info)))}}]
              :modelPreferences
              {:hints [{:name "claude-3-5-sonnet-20241022"}]
               :intelligencePriority 0.9}
              :maxTokens 500
              :temperature 0.5}}]

        {:content
         [{:type "text"
           :text (str "# Simulated answer\n"
                     "Question: " question "\n\n"
                     "The answer would be provided here with detailed explanation...")}]
         :metadata {:sampling-request sampling-request}}))))

(defn optimize-performance-handler
  "Suggest performance optimizations using LLM sampling."
  [context]
  (let [code (get-in context [:params :code])
        constraints (get-in context [:params :constraints])]

    (if (str/blank? code)
      {:error {:code -32602
               :message "Missing required argument: code"}}

      (let [sampling-request
            {:method "sampling/createMessage"
             :params
             {:messages
              [{:role "system"
                :content {:type "text"
                          :text "You are a performance optimization expert. Provide specific, actionable improvements."}}
               {:role "user"
                :content {:type "text"
                          :text (str "Optimize this code for performance:\n\n"
                                    code
                                    (when constraints
                                      (str "\n\nConstraints: " constraints)))}}]
              :modelPreferences
              {:hints [{:name "claude-3-5-sonnet-20241022"}]
               :intelligencePriority 0.85}
              :maxTokens 600
              :temperature 0.4}}]

        {:content
         [{:type "text"
           :text (str "# Simulated performance optimization\n"
                     "Current bottlenecks:\n"
                     "1. ...\n\n"
                     "Suggested optimizations:\n"
                     "1. ...\n")}]
         :metadata {:sampling-request sampling-request}}))))

;; ============================================================================
;; Registry Setup
;; ============================================================================

(defn create-sampling-registry
  "Create a registry with all sampling tools."
  []
  (let [reg (registry/create-function-registry)]

    (core/register-port! reg
      {:id :generate-code
       :description "Generate code using LLM sampling"
       :input-schema {:type "object"
                      :properties {:description {:type "string"}
                                   :language {:type "string"}
                                   :style {:type "string"}}
                      :required ["description"]}
       :handler generate-code-handler})

    (core/register-port! reg
      {:id :explain-error
       :description "Explain an error message using LLM sampling"
       :input-schema {:type "object"
                      :properties {:error_message {:type "string"}
                                   :code_context {:type "string"}
                                   :detail_level {:type "string"}}
                      :required ["error_message"]}
       :handler explain-error-handler})

    (core/register-port! reg
      {:id :suggest-improvements
       :description "Suggest code improvements using LLM sampling"
       :input-schema {:type "object"
                      :properties {:code {:type "string"}
                                   :focus {:type "string"}}
                      :required ["code"]}
       :handler suggest-improvements-handler})

    (core/register-port! reg
      {:id :write-documentation
       :description "Generate documentation using LLM sampling"
       :input-schema {:type "object"
                      :properties {:code {:type "string"}
                                   :format {:type "string"}}
                      :required ["code"]}
       :handler write-documentation-handler})

    (core/register-port! reg
      {:id :translate-code
       :description "Translate code between languages using LLM sampling"
       :input-schema {:type "object"
                      :properties {:code {:type "string"}
                                   :from_language {:type "string"}
                                   :to_language {:type "string"}}
                      :required ["code" "from_language" "to_language"]}
       :handler translate-code-handler})

    (core/register-port! reg
      {:id :generate-tests
       :description "Generate unit tests using LLM sampling"
       :input-schema {:type "object"
                      :properties {:code {:type "string"}
                                   :framework {:type "string"}}
                      :required ["code"]}
       :handler generate-tests-handler})

    (core/register-port! reg
      {:id :answer-question
       :description "Answer a programming question using LLM sampling"
       :input-schema {:type "object"
                      :properties {:question {:type "string"}
                                   :context {:type "string"}}
                      :required ["question"]}
       :handler answer-question-handler})

    (core/register-port! reg
      {:id :optimize-performance
       :description "Suggest performance optimizations using LLM sampling"
       :input-schema {:type "object"
                      :properties {:code {:type "string"}
                                   :constraints {:type "string"}}
                      :required ["code"]}
       :handler optimize-performance-handler})

    reg))

;; ============================================================================
;; Server Startup
;; ============================================================================

(defn create-handler
  "Create a JSON-RPC request handler."
  [registry adapter transport]
  (fn [request]
    (try
      (let [method (:method request)
            params (:params request {})
            request-id (:id request)
            context {:port-registry registry
                     :transport transport
                     :protocol :mcp
                     :request request}
            result (core/protocol-dispatch adapter method params context)]
        (if (:error result)
          {:jsonrpc "2.0"
           :id request-id
           :error (:error result)}
          {:jsonrpc "2.0"
           :id request-id
           :result result}))
      (catch #?(:clj Exception :cljs js/Error) e
        {:jsonrpc "2.0"
         :id (:id request)
         :error {:code -32603
                 :message (str "Internal error: " #?(:clj (.getMessage e) :cljs (.-message e)))}}))))

(defn -main
  "Start the sampling test server.
  Usage:
    --http PORT  Start HTTP server on PORT
    --stdio      Start stdio server"
  [& args]
  (let [registry (create-sampling-registry)
        adapter (mcp/create-mcp-adapter)
        mode (first args)
        port (when (= mode "--http") (platform/parse-int (second args)))]

    (println "Starting MCP Sampling Test Server...")
    (println "Registered sampling tools:" (count (core/list-ports registry)))
    (println "\nNote: This server demonstrates sampling request structure.")
    (println "Actual LLM sampling requires bidirectional client communication.")

    (case mode
      "--http"
      (let [transport (http/create-http-transport {:port port})
            handler (create-handler registry adapter transport)]
        (println (str "\nHTTP server listening on port " port))
        (core/transport-start transport handler)
        #?(:clj @(promise) :cljs nil))

      "--stdio"
      (let [transport (stdio/create-stdio-transport)
            handler (create-handler registry adapter transport)]
        (println "\nStdio server ready")
        (core/transport-start transport handler))

      (println "Usage: --http PORT or --stdio"))))

#?(:cljs (set! *main-cli-fn* -main))