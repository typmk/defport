(ns defport.integration.completions-integration-test
  "Integration tests for completions_server - MCP completion/complete feature"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.testing.server :as server]
            [defport.testing.client :as client]
            [defport.testing.compliance :as compliance]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ============================================================================
;; Mock Completion Data (simplified from main server)
;; ============================================================================

(def test-paths
  [{:value "/home/user/projects" :label "projects" :type "directory"}
   {:value "/home/user/documents" :label "documents" :type "directory"}
   {:value "/home/user/.bashrc" :label ".bashrc" :type "file"}])

(def test-branches
  ["main" "develop" "feature/auth" "feature/api" "bugfix/login"])

(def test-commands
  ["ls" "cd" "cat" "grep" "chmod"])

(def test-log-levels
  [{:value "debug" :label "Debug"}
   {:value "info" :label "Info"}
   {:value "warn" :label "Warning"}
   {:value "error" :label "Error"}])

;; ============================================================================
;; Completion Handlers (simplified)
;; ============================================================================

(defn complete-path [partial]
  (let [prefix (or partial "")]
    (->> test-paths
         (filter #(str/starts-with? (:label %) prefix))
         vec)))

(defn complete-branch [partial]
  (let [prefix (or partial "")]
    (->> test-branches
         (filter #(str/starts-with? % prefix))
         (map (fn [branch]
                {:value branch :label branch :type "branch"}))
         vec)))

(defn complete-command [partial]
  (let [prefix (or partial "")]
    (->> test-commands
         (filter #(str/starts-with? % prefix))
         (map (fn [cmd]
                {:value cmd :label cmd :type "command"}))
         vec)))

(defn complete-enum [values partial]
  (let [prefix (str/lower-case (or partial ""))]
    (->> values
         (filter #(str/starts-with? (str/lower-case (:value %)) prefix))
         vec)))

;; ============================================================================
;; Tool Handlers
;; ============================================================================

(defn read-file-handler [context]
  {:content [{:type "text"
              :text (json/generate-string
                     {:action "read-file"
                      :path (get-in context [:params :path])})}]})

(defn git-checkout-handler [context]
  {:content [{:type "text"
              :text (json/generate-string
                     {:action "git-checkout"
                      :branch (get-in context [:params :branch])})}]})

(defn execute-command-handler [context]
  {:content [{:type "text"
              :text (json/generate-string
                     {:action "execute-command"
                      :command (get-in context [:params :command])})}]})

(defn set-log-level-handler [context]
  {:content [{:type "text"
              :text (json/generate-string
                     {:action "set-log-level"
                      :level (get-in context [:params :level])})}]})

;; ============================================================================
;; Test Registry Factory
;; ============================================================================

(defn create-completions-test-registry []
  (let [reg (registry/create-function-registry)]
    (core/register-port! reg
      {:id :read-file
       :name "read-file"
       :description "Read a file (supports path completion)"
       :input-schema {:type "object"
                      :properties {:path {:type "string"}}
                      :required ["path"]}
       :handler read-file-handler})

    (core/register-port! reg
      {:id :git-checkout
       :name "git-checkout"
       :description "Checkout a git branch"
       :input-schema {:type "object"
                      :properties {:branch {:type "string"}}
                      :required ["branch"]}
       :handler git-checkout-handler})

    (core/register-port! reg
      {:id :execute-command
       :name "execute-command"
       :description "Execute a shell command"
       :input-schema {:type "object"
                      :properties {:command {:type "string"}}
                      :required ["command"]}
       :handler execute-command-handler})

    (core/register-port! reg
      {:id :set-log-level
       :name "set-log-level"
       :description "Set logging level"
       :input-schema {:type "object"
                      :properties {:level {:type "string"
                                           :enum ["debug" "info" "warn" "error"]}}
                      :required ["level"]}
       :handler set-log-level-handler})

    reg))

;; ============================================================================
;; Custom Adapter with Completion Support
;; ============================================================================

(defn handle-completion [ref argument-value]
  (let [{:keys [name argument]} ref]
    (cond
      (and (= name "read-file") (= argument "path"))
      (complete-path argument-value)

      (and (= name "git-checkout") (= argument "branch"))
      (complete-branch argument-value)

      (and (= name "execute-command") (= argument "command"))
      (complete-command argument-value)

      (and (= name "set-log-level") (= argument "level"))
      (complete-enum test-log-levels argument-value)

      :else
      [])))

(defn create-completions-adapter []
  (let [base-adapter (mcp/create-mcp-adapter {})]
    (reify core/ProtocolAdapter
      (protocol-id [_] :mcp)
      (protocol-version [_] "2025-06-18")
      (protocol-capabilities [_ port-registry]
        (merge (core/protocol-capabilities base-adapter port-registry)
               {:completion {}}))
      (protocol-dispatch [_ method params context]
        (if (= method "completion/complete")
          {:completion {:values (handle-completion
                                 (:ref params)
                                 (get-in params [:argument :value]))
                        :total 0
                        :hasMore false}}
          (core/protocol-dispatch base-adapter method params context))))))

;; ============================================================================
;; Helper Macro
;; ============================================================================

(use-fixtures :each (fn [f] (mcp/reset-protocol-state!) (f)))

(defmacro with-completions-test-server [[srv-binding opts] & body]
  `(let [reg# (create-completions-test-registry)
         adapter# (create-completions-adapter)
         opts# (merge {:transport :http :registry reg#} ~opts)
         ~srv-binding (server/start-test-server reg# adapter# opts#)]
     (try
       (server/wait-for-server-ready ~srv-binding)
       ~@body
       (finally
         (server/stop-test-server ~srv-binding)))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest ^:integration test-completions-server-initialization
  (testing "Completions server initializes and reports completion capability"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (let [response (client/client-initialize c {:name "test-client" :version "1.0"})]
          (is (nil? (:error response)) "Initialization should succeed")
          (is (= "2025-06-18" (get-in response [:result :protocolVersion])))
          (let [capabilities (get-in response [:result :capabilities])]
            (is (contains? capabilities :completion) "Should have completion capability")))))))

(deftest ^:integration test-path-completion
  (testing "Path completion suggestions"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Empty path - should return all top-level suggestions
        (let [response (client/client-request c "completion/complete"
                                              {:ref {:type "ref/prompt"
                                                     :name "read-file"
                                                     :argument "path"}
                                               :argument {:name "path" :value ""}})]
          (is (nil? (:error response)) "Should succeed")
          (let [values (get-in response [:result :completion :values])]
            (is (sequential? values) "Should return values array")
            (is (pos? (count values)) "Should have suggestions")))

        ;; Partial path
        (let [response (client/client-request c "completion/complete"
                                              {:ref {:type "ref/prompt"
                                                     :name "read-file"
                                                     :argument "path"}
                                               :argument {:name "path" :value "proj"}})]
          (is (nil? (:error response)))
          (let [values (get-in response [:result :completion :values])]
            (is (every? #(str/starts-with? (:label %) "proj") values)
                "All suggestions should match prefix")))))))

(deftest ^:integration test-branch-completion
  (testing "Git branch completion suggestions"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; All branches
        (let [response (client/client-request c "completion/complete"
                                              {:ref {:type "ref/prompt"
                                                     :name "git-checkout"
                                                     :argument "branch"}
                                               :argument {:name "branch" :value ""}})]
          (is (nil? (:error response)))
          (let [values (get-in response [:result :completion :values])]
            (is (>= (count values) 3) "Should have multiple branches")))

        ;; Feature branches only
        (let [response (client/client-request c "completion/complete"
                                              {:ref {:type "ref/prompt"
                                                     :name "git-checkout"
                                                     :argument "branch"}
                                               :argument {:name "branch" :value "feature/"}})]
          (is (nil? (:error response)))
          (let [values (get-in response [:result :completion :values])]
            (is (every? #(str/starts-with? (:value %) "feature/") values)
                "All suggestions should be feature branches")))))))

(deftest ^:integration test-command-completion
  (testing "Shell command completion suggestions"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        (let [response (client/client-request c "completion/complete"
                                              {:ref {:type "ref/prompt"
                                                     :name "execute-command"
                                                     :argument "command"}
                                               :argument {:name "command" :value "c"}})]
          (is (nil? (:error response)))
          (let [values (get-in response [:result :completion :values])]
            (is (pos? (count values)) "Should have command suggestions")
            (is (every? #(str/starts-with? (:value %) "c") values)
                "All commands should start with 'c'")))))))

(deftest ^:integration test-enum-completion
  (testing "Enum value completion suggestions"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; All enum values
        (let [response (client/client-request c "completion/complete"
                                              {:ref {:type "ref/prompt"
                                                     :name "set-log-level"
                                                     :argument "level"}
                                               :argument {:name "level" :value ""}})]
          (is (nil? (:error response)))
          (let [values (get-in response [:result :completion :values])]
            (is (= 4 (count values)) "Should have 4 log levels")))

        ;; Filtered enum values
        (let [response (client/client-request c "completion/complete"
                                              {:ref {:type "ref/prompt"
                                                     :name "set-log-level"
                                                     :argument "level"}
                                               :argument {:name "level" :value "e"}})]
          (is (nil? (:error response)))
          (let [values (get-in response [:result :completion :values])]
            (is (= 1 (count values)) "Should have 1 match (error)")
            (is (= "error" (:value (first values))))))))))

(deftest ^:integration test-completion-structure
  (testing "Completion response structure is valid"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        (let [response (client/client-request c "completion/complete"
                                              {:ref {:type "ref/prompt"
                                                     :name "read-file"
                                                     :argument "path"}
                                               :argument {:name "path" :value ""}})]
          (is (nil? (:error response)))

          (let [completion (get-in response [:result :completion])]
            (is (contains? completion :values) "Should have values")
            (is (contains? completion :total) "Should have total")
            (is (contains? completion :hasMore) "Should have hasMore")

            ;; Check value structure
            (when-let [first-value (first (:values completion))]
              (is (contains? first-value :value) "Value should have :value")
              (is (contains? first-value :label) "Value should have :label")
              (is (contains? first-value :type) "Value should have :type"))))))))

(deftest ^:integration test-tool-execution-with-completed-value
  (testing "Execute tool with a completed value"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Get completion
        (let [comp-response (client/client-request c "completion/complete"
                                                   {:ref {:type "ref/prompt"
                                                          :name "git-checkout"
                                                          :argument "branch"}
                                                    :argument {:name "branch" :value "main"}})]
          (is (nil? (:error comp-response)))

          ;; Use completed value in tool call
          (let [values (get-in comp-response [:result :completion :values])
                branch-value (:value (first values))
                tool-response (client/client-call-tool c "git-checkout" {:branch branch-value})]
            (is (nil? (:error tool-response)) "Tool call should succeed")
            (let [content (get-in tool-response [:result :content])
                  data (json/parse-string (get-in content [0 :text]) true)]
              (is (= branch-value (:branch data)) "Should use completed branch value"))))))))

(deftest ^:integration test-no-completions-for-invalid-argument
  (testing "No completions for invalid tool/argument combination"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Request completion for non-existent tool
        (let [response (client/client-request c "completion/complete"
                                              {:ref {:type "ref/prompt"
                                                     :name "nonexistent-tool"
                                                     :argument "arg"}
                                               :argument {:name "arg" :value ""}})]
          ;; Should return empty completions, not error
          (is (nil? (:error response)))
          (let [values (get-in response [:result :completion :values])]
            (is (empty? values) "Should return empty completions")))))))

(deftest ^:integration test-concurrent-completion-requests
  (testing "Handle concurrent completion requests"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Send multiple completion requests concurrently
        (let [requests [{:ref {:type "ref/prompt" :name "read-file" :argument "path"}
                         :argument {:name "path" :value ""}}
                        {:ref {:type "ref/prompt" :name "git-checkout" :argument "branch"}
                         :argument {:name "branch" :value "feature/"}}
                        {:ref {:type "ref/prompt" :name "execute-command" :argument "command"}
                         :argument {:name "command" :value "c"}}]
              futures (mapv (fn [req]
                              (future
                                (client/client-request c "completion/complete" req)))
                            requests)
              responses (mapv deref futures)]

          ;; All requests should complete
          (is (= 3 (count responses)))

          ;; All should succeed
          (doseq [response responses]
            (is (nil? (:error response)) "Concurrent requests should succeed")))))))

(deftest ^:integration test-compliance-validation
  (testing "All responses comply with MCP 2025-06-18 spec"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        ;; Initialize
        (let [init-response (client/client-initialize c {:name "test" :version "1.0"})]
          (is (nil? (compliance/validate-response "initialize"
                                                  (dissoc init-response :_request-id)
                                                  (:_request-id init-response)))))

        ;; Tools list
        (let [list-response (client/client-request c "tools/list" {})]
          (is (nil? (compliance/validate-response "tools/list"
                                                  (dissoc list-response :_request-id)
                                                  (:_request-id list-response)))))

        ;; Tool call
        (let [call-response (client/client-call-tool c "read-file" {:path "/test"})]
          (is (nil? (compliance/validate-response "tools/call"
                                                  (dissoc call-response :_request-id)
                                                  (:_request-id call-response)))))))))

(deftest ^:integration test-full-workflow
  (testing "Complete workflow: list tools, get completions, execute tool"
    (with-completions-test-server [srv {}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        ;; 1. Initialize
        (let [init (client/client-initialize c {:name "test" :version "1.0"})]
          (is (nil? (:error init))))

        ;; 2. List tools
        (let [tools-resp (client/client-request c "tools/list" {})]
          (is (nil? (:error tools-resp)))
          (let [tools (get-in tools-resp [:result :tools])]
            (is (some #(= "git-checkout" (:name %)) tools))))

        ;; 3. Get branch completions
        (let [comp-resp (client/client-request c "completion/complete"
                                               {:ref {:type "ref/prompt"
                                                      :name "git-checkout"
                                                      :argument "branch"}
                                                :argument {:name "branch" :value "m"}})]
          (is (nil? (:error comp-resp)))
          (let [values (get-in comp-resp [:result :completion :values])]
            (is (some #(= "main" (:value %)) values) "Should suggest 'main' branch")))

        ;; 4. Execute tool with completed value
        (let [exec-resp (client/client-call-tool c "git-checkout" {:branch "main"})]
          (is (nil? (:error exec-resp)))
          (let [content (get-in exec-resp [:result :content])
                data (json/parse-string (get-in content [0 :text]) true)]
            (is (= "git-checkout" (:action data)))
            (is (= "main" (:branch data)))))))))
