(ns test-servers.prompts-server
  "MCP test server focusing on prompts functionality.

  This server provides a comprehensive set of prompts to test:
  - Required and optional arguments
  - Message arrays with roles
  - Template rendering
  - Multiple prompts listing
  - Argument validation

  Usage:
    HTTP:  clojure -M -m test-servers.prompts-server --http 8080
    Stdio: clojure -M -m test-servers.prompts-server --stdio"
  (:require [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.registry :as registry]
            [defport.transports.http :as http]
            [defport.transports.stdio :as stdio]
            [defport.util.platform :as platform]
            [clojure.string :as str]))

;; ============================================================================
;; Prompt Handlers
;; ============================================================================

(defn code-review-handler
  "Generate a code review prompt for the given code snippet."
  [context]
  (let [code (get-in context [:params :code])
        language (get-in context [:params :language] "unknown")
        focus (get-in context [:params :focus] "general")]
    (if (str/blank? code)
      {:error {:code -32602
               :message "Missing required argument: code"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Please review the following " language " code.\n"
                              "Focus area: " focus "\n\n"
                              "```" language "\n"
                              code "\n"
                              "```\n\n"
                              "Provide feedback on:\n"
                              "1. Code quality and style\n"
                              "2. Potential bugs or issues\n"
                              "3. Performance considerations\n"
                              "4. Best practices and improvements")}}]})))

(defn explain-function-handler
  "Generate a prompt to explain a specific function."
  [context]
  (let [function-name (get-in context [:params :function_name])
        include-examples (get-in context [:params :include_examples] true)]
    (if (str/blank? function-name)
      {:error {:code -32602
               :message "Missing required argument: function_name"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Please explain the function: " function-name "\n\n"
                              "Include:\n"
                              "1. Purpose and functionality\n"
                              "2. Parameters and return value\n"
                              "3. Time and space complexity\n"
                              (when include-examples
                                "4. Usage examples\n"))}}]})))

(defn debug-help-handler
  "Generate a debugging assistance prompt."
  [context]
  (let [error-msg (get-in context [:params :error_message])
        stack-trace (get-in context [:params :stack_trace])
        context-info (get-in context [:params :context])]
    (if (str/blank? error-msg)
      {:error {:code -32602
               :message "Missing required argument: error_message"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "I'm encountering an error and need debugging help.\n\n"
                              "Error: " error-msg "\n\n"
                              (when stack-trace
                                (str "Stack trace:\n" stack-trace "\n\n"))
                              (when context-info
                                (str "Context:\n" context-info "\n\n"))
                              "Please help me:\n"
                              "1. Understand what's causing this error\n"
                              "2. Identify the root cause\n"
                              "3. Suggest potential solutions")}}]})))

(defn refactor-suggestion-handler
  "Generate refactoring suggestions for code."
  [context]
  (let [code (get-in context [:params :code])
        goal (get-in context [:params :goal] "improve readability")]
    (if (str/blank? code)
      {:error {:code -32602
               :message "Missing required argument: code"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Please suggest refactoring improvements for this code.\n"
                              "Goal: " goal "\n\n"
                              "Code:\n"
                              code "\n\n"
                              "Provide:\n"
                              "1. Specific refactoring suggestions\n"
                              "2. Improved code examples\n"
                              "3. Explanation of benefits")}}]})))

(defn write-tests-handler
  "Generate a prompt to write tests for code."
  [context]
  (let [code (get-in context [:params :code])
        framework (get-in context [:params :framework] "clojure.test")
        coverage (get-in context [:params :coverage] "comprehensive")]
    (if (str/blank? code)
      {:error {:code -32602
               :message "Missing required argument: code"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Please write tests for the following code.\n"
                              "Framework: " framework "\n"
                              "Coverage level: " coverage "\n\n"
                              "Code:\n"
                              code "\n\n"
                              "Include:\n"
                              "1. Unit tests for all functions\n"
                              "2. Edge cases and error conditions\n"
                              "3. Integration tests if applicable")}}]})))

(defn document-api-handler
  "Generate API documentation prompt."
  [context]
  (let [api-name (get-in context [:params :api_name])
        endpoints (get-in context [:params :endpoints])
        format (get-in context [:params :format] "markdown")]
    (if (str/blank? api-name)
      {:error {:code -32602
               :message "Missing required argument: api_name"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Please create API documentation for: " api-name "\n"
                              (when endpoints
                                (str "Endpoints: " endpoints "\n"))
                              "Format: " format "\n\n"
                              "Include:\n"
                              "1. Overview and purpose\n"
                              "2. Authentication details\n"
                              "3. Endpoint specifications\n"
                              "4. Request/response examples\n"
                              "5. Error codes and handling")}}]})))

(defn optimize-query-handler
  "Generate database query optimization prompt."
  [context]
  (let [query (get-in context [:params :query])
        database (get-in context [:params :database] "PostgreSQL")]
    (if (str/blank? query)
      {:error {:code -32602
               :message "Missing required argument: query"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Please optimize this " database " query:\n\n"
                              query "\n\n"
                              "Provide:\n"
                              "1. Optimized query version\n"
                              "2. Explanation of improvements\n"
                              "3. Index recommendations\n"
                              "4. Performance impact analysis")}}]})))

(defn architecture-review-handler
  "Generate architecture review prompt."
  [context]
  (let [description (get-in context [:params :description])
        scale (get-in context [:params :scale] "medium")
        constraints (get-in context [:params :constraints])]
    (if (str/blank? description)
      {:error {:code -32602
               :message "Missing required argument: description"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Please review this system architecture:\n\n"
                              description "\n\n"
                              "Scale: " scale "\n"
                              (when constraints
                                (str "Constraints: " constraints "\n\n"))
                              "Evaluate:\n"
                              "1. Scalability and performance\n"
                              "2. Reliability and fault tolerance\n"
                              "3. Security considerations\n"
                              "4. Cost optimization\n"
                              "5. Improvement recommendations")}}]})))

(defn security-audit-handler
  "Generate security audit prompt."
  [context]
  (let [code (get-in context [:params :code])
        scope (get-in context [:params :scope] "full")]
    (if (str/blank? code)
      {:error {:code -32602
               :message "Missing required argument: code"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Please perform a security audit on this code.\n"
                              "Scope: " scope "\n\n"
                              "Code:\n"
                              code "\n\n"
                              "Check for:\n"
                              "1. SQL injection vulnerabilities\n"
                              "2. XSS vulnerabilities\n"
                              "3. Authentication/authorization issues\n"
                              "4. Data validation problems\n"
                              "5. Cryptographic weaknesses\n"
                              "6. OWASP Top 10 vulnerabilities")}}]})))

(defn onboarding-guide-handler
  "Generate new developer onboarding guide prompt."
  [context]
  (let [project-name (get-in context [:params :project_name])
        tech-stack (get-in context [:params :tech_stack])
        team-size (get-in context [:params :team_size] "small")]
    (if (str/blank? project-name)
      {:error {:code -32602
               :message "Missing required argument: project_name"}}
      {:messages
       [{:role "user"
         :content {:type "text"
                   :text (str "Create an onboarding guide for: " project-name "\n"
                              (when tech-stack
                                (str "Tech stack: " tech-stack "\n"))
                              "Team size: " team-size "\n\n"
                              "Include:\n"
                              "1. Project overview and goals\n"
                              "2. Development environment setup\n"
                              "3. Codebase structure\n"
                              "4. Development workflow\n"
                              "5. Key concepts and patterns\n"
                              "6. Resources and documentation")}}]})))

;; ============================================================================
;; Registry Setup
;; ============================================================================

(defn create-prompts-registry
  "Create a registry with all test prompts."
  []
  (let [reg (registry/create-function-registry)]
    ;; Register prompts
    (core/register-port! reg
      {:id :code-review
       :description "Generate a code review prompt for the given code snippet"
       :input-schema {:type "object"
                      :properties {:code {:type "string" :description "Code to review"}
                                   :language {:type "string" :description "Programming language"}
                                   :focus {:type "string" :description "Review focus area"}}
                      :required ["code"]}
       :handler code-review-handler
       :metadata {:prompt true
                  :prompt-args [{:name "code" :description "Code to review" :required true}
                                {:name "language" :description "Programming language" :required false}
                                {:name "focus" :description "Review focus area" :required false}]}})

    (core/register-port! reg
      {:id :explain-function
       :description "Generate a prompt to explain a specific function"
       :input-schema {:type "object"
                      :properties {:function_name {:type "string" :description "Name of function"}
                                   :include_examples {:type "boolean" :description "Include usage examples"}}
                      :required ["function_name"]}
       :handler explain-function-handler
       :metadata {:prompt true
                  :prompt-args [{:name "function_name" :description "Name of function to explain" :required true}
                                {:name "include_examples" :description "Include usage examples" :required false}]}})

    (core/register-port! reg
      {:id :debug-help
       :description "Generate a debugging assistance prompt"
       :input-schema {:type "object"
                      :properties {:error_message {:type "string" :description "Error message"}
                                   :stack_trace {:type "string" :description "Stack trace"}
                                   :context {:type "string" :description "Additional context"}}
                      :required ["error_message"]}
       :handler debug-help-handler
       :metadata {:prompt true
                  :prompt-args [{:name "error_message" :description "Error message" :required true}
                                {:name "stack_trace" :description "Stack trace" :required false}
                                {:name "context" :description "Additional context" :required false}]}})

    (core/register-port! reg
      {:id :refactor-suggestion
       :description "Generate refactoring suggestions for code"
       :input-schema {:type "object"
                      :properties {:code {:type "string" :description "Code to refactor"}
                                   :goal {:type "string" :description "Refactoring goal"}}
                      :required ["code"]}
       :handler refactor-suggestion-handler
       :metadata {:prompt true
                  :prompt-args [{:name "code" :description "Code to refactor" :required true}
                                {:name "goal" :description "Refactoring goal" :required false}]}})

    (core/register-port! reg
      {:id :write-tests
       :description "Generate a prompt to write tests for code"
       :input-schema {:type "object"
                      :properties {:code {:type "string" :description "Code to test"}
                                   :framework {:type "string" :description "Testing framework"}
                                   :coverage {:type "string" :description "Coverage level"}}
                      :required ["code"]}
       :handler write-tests-handler
       :metadata {:prompt true
                  :prompt-args [{:name "code" :description "Code to test" :required true}
                                {:name "framework" :description "Testing framework" :required false}
                                {:name "coverage" :description "Coverage level" :required false}]}})

    (core/register-port! reg
      {:id :document-api
       :description "Generate API documentation prompt"
       :input-schema {:type "object"
                      :properties {:api_name {:type "string" :description "API name"}
                                   :endpoints {:type "string" :description "API endpoints"}
                                   :format {:type "string" :description "Documentation format"}}
                      :required ["api_name"]}
       :handler document-api-handler
       :metadata {:prompt true
                  :prompt-args [{:name "api_name" :description "Name of the API" :required true}
                                {:name "endpoints" :description "API endpoints list" :required false}
                                {:name "format" :description "Documentation format" :required false}]}})

    (core/register-port! reg
      {:id :optimize-query
       :description "Generate database query optimization prompt"
       :input-schema {:type "object"
                      :properties {:query {:type "string" :description "Database query"}
                                   :database {:type "string" :description "Database type"}}
                      :required ["query"]}
       :handler optimize-query-handler
       :metadata {:prompt true
                  :prompt-args [{:name "query" :description "Database query to optimize" :required true}
                                {:name "database" :description "Database type" :required false}]}})

    (core/register-port! reg
      {:id :architecture-review
       :description "Generate architecture review prompt"
       :input-schema {:type "object"
                      :properties {:description {:type "string" :description "Architecture description"}
                                   :scale {:type "string" :description "System scale"}
                                   :constraints {:type "string" :description "Constraints"}}
                      :required ["description"]}
       :handler architecture-review-handler
       :metadata {:prompt true
                  :prompt-args [{:name "description" :description "System architecture description" :required true}
                                {:name "scale" :description "Expected scale" :required false}
                                {:name "constraints" :description "Design constraints" :required false}]}})

    (core/register-port! reg
      {:id :security-audit
       :description "Generate security audit prompt"
       :input-schema {:type "object"
                      :properties {:code {:type "string" :description "Code to audit"}
                                   :scope {:type "string" :description "Audit scope"}}
                      :required ["code"]}
       :handler security-audit-handler
       :metadata {:prompt true
                  :prompt-args [{:name "code" :description "Code to audit" :required true}
                                {:name "scope" :description "Audit scope" :required false}]}})

    (core/register-port! reg
      {:id :onboarding-guide
       :description "Generate new developer onboarding guide prompt"
       :input-schema {:type "object"
                      :properties {:project_name {:type "string" :description "Project name"}
                                   :tech_stack {:type "string" :description "Technology stack"}
                                   :team_size {:type "string" :description "Team size"}}
                      :required ["project_name"]}
       :handler onboarding-guide-handler
       :metadata {:prompt true
                  :prompt-args [{:name "project_name" :description "Project name" :required true}
                                {:name "tech_stack" :description "Technology stack used" :required false}
                                {:name "team_size" :description "Team size" :required false}]}})

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
  "Start the prompts test server.
  Usage:
    --http PORT  Start HTTP server on PORT
    --stdio      Start stdio server"
  [& args]
  (let [registry (create-prompts-registry)
        adapter (mcp/create-mcp-adapter)
        mode (first args)
        port (when (= mode "--http") (platform/parse-int (second args)))]

    (println "Starting MCP Prompts Test Server...")
    (println "Registered prompts:" (count (core/list-ports registry)))

    (case mode
      "--http"
      (let [transport (http/create-http-transport {:port port})
            handler (create-handler registry adapter transport)]
        (println (str "HTTP server listening on port " port))
        (core/transport-start transport handler)
        #?(:clj @(promise) :cljs nil))

      "--stdio"
      (let [transport (stdio/create-stdio-transport)
            handler (create-handler registry adapter transport)]
        (println "Stdio server ready")
        (core/transport-start transport handler))

      (println "Usage: --http PORT or --stdio"))))

#?(:cljs (set! *main-cli-fn* -main))