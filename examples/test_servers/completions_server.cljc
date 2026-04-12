(ns completions-server
  "MCP Completions Server - Demonstrates completion/complete feature for context-aware suggestions.

  The completion/complete feature allows servers to provide context-aware completions
  for tool arguments as users type. This enhances the UX by suggesting valid values
  based on the current context.

  This server demonstrates:
  1. File path completions (directory and file suggestions)
  2. Git branch completions (branch name suggestions)
  3. Command completions (shell command suggestions)
  4. Enum value completions (predefined option lists)
  5. Dynamic completions (context-dependent suggestions)
  6. Partial matching and filtering

  Run with:
    clojure -M:examples -m completions-server --http 8080
    clojure -M:examples -m completions-server --stdio"
  (:require [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.registry :as registry]
            [defport.transports.http :as http]
            [defport.transports.stdio :as stdio]
            [defport.util.platform :as platform]
            [clojure.string :as str]))

;; ============================================================================
;; Mock Data for Completions
;; ============================================================================

(def file-tree
  "Mock file system for path completions"
  {"/home/user"
   {:type :directory
    :children {"projects" {:type :directory
                           :children {"defport" {:type :directory
                                                 :children {"src" {:type :directory
                                                                   :children {"core.clj" {:type :file}
                                                                              "utils.clj" {:type :file}}}
                                                            "test" {:type :directory
                                                                    :children {"core_test.clj" {:type :file}}}
                                                            "README.md" {:type :file}
                                                            "deps.edn" {:type :file}}}
                                      "webapp" {:type :directory
                                                :children {"package.json" {:type :file}
                                                           "index.js" {:type :file}}}}}
               "documents" {:type :directory
                            :children {"notes.txt" {:type :file}
                                       "report.pdf" {:type :file}}}
               ".bashrc" {:type :file}
               ".vimrc" {:type :file}}}})

(def git-branches
  "Mock git branches"
  ["main" "develop" "feature/user-auth" "feature/api-endpoints"
   "bugfix/login-error" "release/v1.0" "hotfix/critical-bug"])

(def shell-commands
  "Mock shell commands"
  ["ls" "cd" "pwd" "cat" "grep" "find" "awk" "sed" "chmod" "chown"
   "mkdir" "rm" "cp" "mv" "touch" "echo" "export" "source" "alias"])

(def log-levels
  "Mock log levels (enum)"
  [{:value "debug" :label "Debug" :description "Detailed debugging information"}
   {:value "info" :label "Info" :description "General informational messages"}
   {:value "warn" :label "Warning" :description "Warning messages"}
   {:value "error" :label "Error" :description "Error messages"}
   {:value "fatal" :label "Fatal" :description "Fatal error messages"}])

(def output-formats
  "Mock output formats (enum)"
  [{:value "json" :label "JSON" :description "JavaScript Object Notation"}
   {:value "yaml" :label "YAML" :description "YAML Ain't Markup Language"}
   {:value "xml" :label "XML" :description "Extensible Markup Language"}
   {:value "csv" :label "CSV" :description "Comma-Separated Values"}
   {:value "toml" :label "TOML" :description "Tom's Obvious Minimal Language"}])

;; ============================================================================
;; Completion Helpers
;; ============================================================================

(defn get-path-entries
  "Get directory entries for a given path"
  [path]
  (let [parts (filter seq (str/split path #"/"))
        navigate (fn [tree parts]
                   (if (empty? parts)
                     tree
                     (when-let [node (get tree (first parts))]
                       (recur (:children node) (rest parts)))))]
    (when-let [node (navigate file-tree parts)]
      (map (fn [[name entry]]
             {:name name
              :type (:type entry)
              :path (str path "/" name)})
           (:children node)))))

(defn complete-path
  "Complete a file path based on partial input"
  [partial]
  (if (str/blank? partial)
    ;; No input - suggest top-level entries
    [{:value "/home/user" :label "/home/user" :type "directory"}]
    (let [dir-path (if (str/ends-with? partial "/")
                     partial
                     (let [idx (str/last-index-of partial "/")]
                       (if idx
                         (subs partial 0 (inc idx))
                         "/")))
          prefix (if (str/ends-with? partial "/")
                   ""
                   (let [idx (str/last-index-of partial "/")]
                     (if idx
                       (subs partial (inc idx))
                       partial)))
          entries (get-path-entries (str/replace dir-path #"/$" ""))]
      (->> entries
           (filter #(str/starts-with? (:name %) prefix))
           (map (fn [entry]
                  {:value (:path entry)
                   :label (:name entry)
                   :type (name (:type entry))
                   :description (str (name (:type entry)) ": " (:path entry))}))))))

(defn complete-git-branch
  "Complete a git branch name based on partial input"
  [partial]
  (let [prefix (or partial "")]
    (->> git-branches
         (filter #(str/starts-with? % prefix))
         (map (fn [branch]
                {:value branch
                 :label branch
                 :type "branch"
                 :description (str "Git branch: " branch)})))))

(defn complete-shell-command
  "Complete a shell command based on partial input"
  [partial]
  (let [prefix (or partial "")]
    (->> shell-commands
         (filter #(str/starts-with? % prefix))
         (map (fn [cmd]
                {:value cmd
                 :label cmd
                 :type "command"
                 :description (str "Shell command: " cmd)})))))

(defn complete-enum
  "Complete from an enum list"
  [enum-values partial]
  (let [prefix (str/lower-case (or partial ""))]
    (->> enum-values
         (filter #(str/starts-with? (str/lower-case (:value %)) prefix))
         (map (fn [item]
                {:value (:value item)
                 :label (:label item)
                 :type "enum"
                 :description (:description item)})))))

;; ============================================================================
;; Tool Handlers
;; ============================================================================

(defn read-file-handler
  "Read a file (with path completion)"
  [context]
  (let [path (get-in context [:params :path])]
    {:content [{:type "text"
                :text (platform/json-encode
                       {:action "read-file"
                        :path path
                        :content (str "Contents of " path)})}]}))

(defn git-checkout-handler
  "Checkout a git branch (with branch completion)"
  [context]
  (let [branch (get-in context [:params :branch])]
    {:content [{:type "text"
                :text (platform/json-encode
                       {:action "git-checkout"
                        :branch branch
                        :result (str "Checked out branch: " branch)})}]}))

(defn execute-command-handler
  "Execute a shell command (with command completion)"
  [context]
  (let [command (get-in context [:params :command])
        args (get-in context [:params :args] "")]
    {:content [{:type "text"
                :text (platform/json-encode
                       {:action "execute-command"
                        :command command
                        :args args
                        :result (str "Executed: " command " " args)})}]}))

(defn set-log-level-handler
  "Set logging level (with enum completion)"
  [context]
  (let [level (get-in context [:params :level])]
    {:content [{:type "text"
                :text (platform/json-encode
                       {:action "set-log-level"
                        :level level
                        :result (str "Log level set to: " level)})}]}))

(defn convert-format-handler
  "Convert data format (with format completion)"
  [context]
  (let [data (get-in context [:params :data])
        from-format (get-in context [:params :from])
        to-format (get-in context [:params :to])]
    {:content [{:type "text"
                :text (platform/json-encode
                       {:action "convert-format"
                        :from from-format
                        :to to-format
                        :result (str "Converted from " from-format " to " to-format)})}]}))

(defn search-files-handler
  "Search files with multiple path completions"
  [context]
  (let [paths (get-in context [:params :paths])
        pattern (get-in context [:params :pattern])]
    {:content [{:type "text"
                :text (platform/json-encode
                       {:action "search-files"
                        :paths paths
                        :pattern pattern
                        :matches (count paths)})}]}))

(defn test-completions-handler
  "Test all completion types"
  [context]
  (let [file-path (get-in context [:params :file-path] "")
        branch (get-in context [:params :branch] "")
        command (get-in context [:params :command] "")
        log-level (get-in context [:params :log-level] "")
        format (get-in context [:params :format] "")]
    {:content [{:type "text"
                :text (platform/json-encode
                       {:action "test-completions"
                        :inputs {:file-path file-path
                                 :branch branch
                                 :command command
                                 :log-level log-level
                                 :format format}
                        :completions {:path-suggestions (count (complete-path file-path))
                                      :branch-suggestions (count (complete-git-branch branch))
                                      :command-suggestions (count (complete-shell-command command))
                                      :level-suggestions (count (complete-enum log-levels log-level))
                                      :format-suggestions (count (complete-enum output-formats format))}})}]}))

;; ============================================================================
;; Registry Setup
;; ============================================================================

(defn create-registry []
  "Create and populate the tool registry"
  (let [reg (registry/create-function-registry)]

    ;; File operations with path completion
    (core/register-port! reg
      {:id :read-file
       :name "read-file"
       :description "Read a file (supports path completion)"
       :input-schema {:type "object"
                      :properties {:path {:type "string"
                                          :description "File path to read"}}
                      :required ["path"]}
       :handler read-file-handler})

    ;; Git operations with branch completion
    (core/register-port! reg
      {:id :git-checkout
       :name "git-checkout"
       :description "Checkout a git branch (supports branch completion)"
       :input-schema {:type "object"
                      :properties {:branch {:type "string"
                                            :description "Branch name to checkout"}}
                      :required ["branch"]}
       :handler git-checkout-handler})

    ;; Shell command execution with command completion
    (core/register-port! reg
      {:id :execute-command
       :name "execute-command"
       :description "Execute a shell command (supports command completion)"
       :input-schema {:type "object"
                      :properties {:command {:type "string"
                                             :description "Command to execute"}
                                   :args {:type "string"
                                          :description "Command arguments"}}
                      :required ["command"]}
       :handler execute-command-handler})

    ;; Configuration with enum completion
    (core/register-port! reg
      {:id :set-log-level
       :name "set-log-level"
       :description "Set logging level (enum completion)"
       :input-schema {:type "object"
                      :properties {:level {:type "string"
                                           :description "Log level (debug/info/warn/error/fatal)"
                                           :enum ["debug" "info" "warn" "error" "fatal"]}}
                      :required ["level"]}
       :handler set-log-level-handler})

    ;; Format conversion with enum completion
    (core/register-port! reg
      {:id :convert-format
       :name "convert-format"
       :description "Convert data between formats (enum completion)"
       :input-schema {:type "object"
                      :properties {:data {:type "string"
                                          :description "Data to convert"}
                                   :from {:type "string"
                                          :description "Source format"
                                          :enum ["json" "yaml" "xml" "csv" "toml"]}
                                   :to {:type "string"
                                        :description "Target format"
                                        :enum ["json" "yaml" "xml" "csv" "toml"]}}
                      :required ["data" "from" "to"]}
       :handler convert-format-handler})

    ;; Multi-path search with multiple completions
    (core/register-port! reg
      {:id :search-files
       :name "search-files"
       :description "Search files in multiple paths (array completion)"
       :input-schema {:type "object"
                      :properties {:paths {:type "array"
                                           :items {:type "string"}
                                           :description "Paths to search"}
                                   :pattern {:type "string"
                                            :description "Search pattern"}}
                      :required ["paths" "pattern"]}
       :handler search-files-handler})

    ;; Testing tool for all completion types
    (core/register-port! reg
      {:id :test-completions
       :name "test-completions"
       :description "Test all completion types"
       :input-schema {:type "object"
                      :properties {:file-path {:type "string"
                                               :description "File path (test path completion)"}
                                   :branch {:type "string"
                                           :description "Branch name (test branch completion)"}
                                   :command {:type "string"
                                            :description "Shell command (test command completion)"}
                                   :log-level {:type "string"
                                              :description "Log level (test enum completion)"
                                              :enum ["debug" "info" "warn" "error" "fatal"]}
                                   :format {:type "string"
                                           :description "Output format (test enum completion)"
                                           :enum ["json" "yaml" "xml" "csv" "toml"]}}}
       :handler test-completions-handler})

    reg))

;; ============================================================================
;; MCP Adapter with Completions Support
;; ============================================================================

(defn handle-completion-request
  "Handle completion/complete requests"
  [request]
  (let [ref (get-in request [:params :ref])
        {:keys [name argument]} ref
        argument-value (get-in request [:params :argument :value] "")]

    ;; Generate completions based on tool and argument
    (let [completions (cond
                        ;; File path completions
                        (and (= name "read-file") (= argument "path"))
                        (complete-path argument-value)

                        (and (= name "search-files") (= argument "paths"))
                        (complete-path argument-value)

                        ;; Git branch completions
                        (and (= name "git-checkout") (= argument "branch"))
                        (complete-git-branch argument-value)

                        ;; Shell command completions
                        (and (= name "execute-command") (= argument "command"))
                        (complete-shell-command argument-value)

                        ;; Enum completions
                        (and (= name "set-log-level") (= argument "level"))
                        (complete-enum log-levels argument-value)

                        (or (and (= name "convert-format") (= argument "from"))
                            (and (= name "convert-format") (= argument "to")))
                        (complete-enum output-formats argument-value)

                        ;; Test completions tool
                        (and (= name "test-completions") (= argument "file-path"))
                        (complete-path argument-value)

                        (and (= name "test-completions") (= argument "branch"))
                        (complete-git-branch argument-value)

                        (and (= name "test-completions") (= argument "command"))
                        (complete-shell-command argument-value)

                        (and (= name "test-completions") (= argument "log-level"))
                        (complete-enum log-levels argument-value)

                        (and (= name "test-completions") (= argument "format"))
                        (complete-enum output-formats argument-value)

                        ;; No completions available
                        :else
                        [])]

      {:jsonrpc "2.0"
       :id (:id request)
       :result {:completion {:values completions
                             :total (count completions)
                             :hasMore false}}})))

(defn create-completions-adapter
  "Create MCP adapter with completion/complete support"
  [opts]
  (let [base-adapter (mcp/create-mcp-adapter opts)]
    (reify core/ProtocolAdapter
      (protocol-id [_]
        (core/protocol-id base-adapter))

      (protocol-version [_]
        (core/protocol-version base-adapter))

      (protocol-capabilities [_ port-registry]
        (merge (core/protocol-capabilities base-adapter port-registry)
               {:completion {}}))

      (protocol-dispatch [_ method params context]
        (if (= method "completion/complete")
          ;; Handle completion request directly
          (let [request {:method method :params params :id (platform/uuid)}]
            (:result (handle-completion-request request)))
          ;; Delegate to base adapter
          (core/protocol-dispatch base-adapter method params context))))))

;; ============================================================================
;; Server Startup
;; ============================================================================

(defn start-server
  "Start the completions server with specified transport"
  [transport-type port]
  (let [registry (create-registry)
        adapter (create-completions-adapter {})

        ;; Create request handler
        handler (fn [request]
                  (if (= (:method request) "completion/complete")
                    (handle-completion-request request)
                    (let [context {:port-registry registry}
                          result (core/protocol-dispatch adapter (:method request) (:params request) context)]
                      (if (:error result)
                        {:jsonrpc "2.0"
                         :id (:id request)
                         :error (:error result)}
                        {:jsonrpc "2.0"
                         :id (:id request)
                         :result result}))))

        ;; Start transport
        transport (case transport-type
                    :http (let [t (http/create-http-transport {:port port})]
                            (core/transport-start t handler)
                            t)
                    :stdio (let [t (stdio/create-stdio-transport)]
                             (core/transport-start t handler)
                             t))]

    (println (str "Completions server started on "
                  (case transport-type
                    :http (str "http://localhost:" port)
                    :stdio "stdio")))
    (println "\nAvailable tools:")
    (println "  - read-file: Read file (path completion)")
    (println "  - git-checkout: Checkout branch (branch completion)")
    (println "  - execute-command: Execute command (command completion)")
    (println "  - set-log-level: Set log level (enum completion)")
    (println "  - convert-format: Convert format (enum completion)")
    (println "  - search-files: Search files (array path completion)")
    (println "  - test-completions: Test all completion types")
    (println "\nCompletion support:")
    (println "  - Use completion/complete method to get suggestions")
    (println "  - Supports: paths, branches, commands, enums")

    ;; Keep running for stdio, return transport for HTTP
    (when (= transport-type :stdio)
      #?(:clj @(promise) :cljs nil)) ;; Block forever
    transport))

(defn -main [& args]
  (let [transport (if (some #{"--stdio"} args) :stdio :http)
        port (or (some->> args
                          (drop-while #(not= "--http" %))
                          second
                          platform/parse-int)
                 8080)]
    (start-server transport port)))

#?(:cljs (set! *main-cli-fn* -main))