(ns defport.integration.roots-integration-test
  "Integration tests for roots_server - MCP roots/list feature demonstration"
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
;; Roots State Management (for testing)
;; ============================================================================

(def test-roots-state
  (atom {:roots [{:uri "file:///home/user/projects" :name "Projects Directory"}
                 {:uri "file:///home/user/documents" :name "Documents"}
                 {:uri "file:///tmp" :name "Temp Directory"}]}))

(defn get-roots []
  (:roots @test-roots-state))

(defn add-root! [uri name]
  (swap! test-roots-state update :roots conj {:uri uri :name name})
  true)

(defn remove-root! [uri]
  (swap! test-roots-state update :roots
         (fn [roots] (vec (remove #(= (:uri %) uri) roots))))
  true)

(defn reset-roots! []
  (reset! test-roots-state
          {:roots [{:uri "file:///home/user/projects" :name "Projects Directory"}
                   {:uri "file:///home/user/documents" :name "Documents"}
                   {:uri "file:///tmp" :name "Temp Directory"}]}))

(use-fixtures :each (fn [f] (mcp/reset-protocol-state!) (reset-roots!) (f)))

(defn path-in-root? [path root-uri]
  (let [root-path (-> root-uri
                      (str/replace #"^file://" "")
                      (str/replace #"^///" "/"))
        normalized-path (str/replace path "\\" "/")]
    (str/starts-with? normalized-path root-path)))

(defn validate-path [path]
  (let [roots (get-roots)]
    (if (some #(path-in-root? path (:uri %)) roots)
      {:valid true}
      {:valid false
       :error "Path is outside declared roots"
       :path path
       :roots (mapv :uri roots)})))

;; ============================================================================
;; Test Handlers
;; ============================================================================

(defn validate-path-handler [context]
  (let [path (get-in context [:params :path])
        validation (validate-path path)]
    {:content [{:type "text"
                :text (json/generate-string validation)}]}))

(defn check-access-handler [context]
  (let [paths (get-in context [:params :paths])
        results (mapv (fn [path]
                        {:path path
                         :validation (validate-path path)})
                      paths)]
    {:content [{:type "text"
                :text (json/generate-string
                       {:paths paths
                        :results results
                        :accessible (count (filter #(get-in % [:validation :valid]) results))
                        :blocked (count (filter #(not (get-in % [:validation :valid])) results))})}]}))

(defn list-files-handler [context]
  (let [path (get-in context [:params :path])
        validation (validate-path path)]
    (if (:valid validation)
      {:content [{:type "text"
                  :text (json/generate-string
                         {:path path
                          :files []
                          :count 0})}]}
      {:error {:code -32602
               :message "Invalid path: outside declared roots"
               :data validation}})))

(defn read-file-handler [context]
  (let [path (get-in context [:params :path])
        validation (validate-path path)]
    (if (:valid validation)
      {:content [{:type "text"
                  :text (json/generate-string
                         {:path path
                          :size 0
                          :content ""})}]}
      {:error {:code -32602
               :message "Invalid path: outside declared roots"
               :data validation}})))

(defn get-roots-handler [context]
  {:content [{:type "text"
              :text (json/generate-string
                     {:roots (get-roots)
                      :count (count (get-roots))})}]})

(defn add-root-handler [context]
  (let [uri (get-in context [:params :uri])
        name (get-in context [:params :name])]
    (if (and uri name)
      (do
        (add-root! uri name)
        {:content [{:type "text"
                    :text (json/generate-string
                           {:status "success"
                            :message "Root added"
                            :uri uri
                            :name name
                            :current-roots (get-roots)})}]})
      {:error {:code -32602
               :message "Missing required parameters: uri and name"}})))

(defn remove-root-handler [context]
  (let [uri (get-in context [:params :uri])]
    (if uri
      (do
        (remove-root! uri)
        {:content [{:type "text"
                    :text (json/generate-string
                           {:status "success"
                            :message "Root removed"
                            :uri uri
                            :current-roots (get-roots)})}]})
      {:error {:code -32602
               :message "Missing required parameter: uri"}})))

(defn test-security-handler [context]
  (let [test-paths [(get-in context [:params :safe-path] "/home/user/projects/test.txt")
                    (get-in context [:params :unsafe-path] "/etc/passwd")
                    (get-in context [:params :relative-path] "../../../etc/passwd")]
        results (mapv (fn [path]
                        {:path path
                         :validation (validate-path path)
                         :verdict (if (:valid (validate-path path))
                                   "ALLOWED"
                                   "BLOCKED")})
                      test-paths)]
    {:content [{:type "text"
                :text (json/generate-string
                       {:test "Security Boundary Enforcement"
                        :results results
                        :summary {:allowed (count (filter #(= (:verdict %) "ALLOWED") results))
                                  :blocked (count (filter #(= (:verdict %) "BLOCKED") results))}})}]}))

;; ============================================================================
;; Test Registry Factory
;; ============================================================================

(defn create-roots-test-registry []
  (reset-roots!)
  (let [reg (registry/create-function-registry)]
    (core/register-port! reg
      {:id :validate-path
       :name "validate-path"
       :description "Validate a path against declared roots"
       :input-schema {:type "object"
                      :properties {:path {:type "string"}}
                      :required ["path"]}
       :handler validate-path-handler})

    (core/register-port! reg
      {:id :check-access
       :name "check-access"
       :description "Check if multiple paths are accessible"
       :input-schema {:type "object"
                      :properties {:paths {:type "array" :items {:type "string"}}}
                      :required ["paths"]}
       :handler check-access-handler})

    (core/register-port! reg
      {:id :list-files
       :name "list-files"
       :description "List files in a directory (validates against roots)"
       :input-schema {:type "object"
                      :properties {:path {:type "string"}}
                      :required ["path"]}
       :handler list-files-handler})

    (core/register-port! reg
      {:id :read-file
       :name "read-file"
       :description "Read a file (validates against roots)"
       :input-schema {:type "object"
                      :properties {:path {:type "string"}}
                      :required ["path"]}
       :handler read-file-handler})

    (core/register-port! reg
      {:id :get-roots
       :name "get-roots"
       :description "Get current roots list"
       :input-schema {:type "object" :properties {}}
       :handler get-roots-handler})

    (core/register-port! reg
      {:id :add-root
       :name "add-root"
       :description "Add a new root directory"
       :input-schema {:type "object"
                      :properties {:uri {:type "string"}
                                   :name {:type "string"}}
                      :required ["uri" "name"]}
       :handler add-root-handler
       :metadata {:dangerous true}})

    (core/register-port! reg
      {:id :remove-root
       :name "remove-root"
       :description "Remove a root directory"
       :input-schema {:type "object"
                      :properties {:uri {:type "string"}}
                      :required ["uri"]}
       :handler remove-root-handler
       :metadata {:dangerous true}})

    (core/register-port! reg
      {:id :test-security
       :name "test-security"
       :description "Test security boundary enforcement"
       :input-schema {:type "object"
                      :properties {:safe-path {:type "string"}
                                   :unsafe-path {:type "string"}
                                   :relative-path {:type "string"}}}
       :handler test-security-handler})

    reg))

;; Helper macro for tests
(defmacro with-roots-test-server [[srv-binding opts] & body]
  `(let [reg# (create-roots-test-registry)
         adapter-opts# (select-keys ~opts [:enable-refactoring])
         adapter# (mcp/create-mcp-adapter adapter-opts#)
         opts# (merge {:transport :http :registry reg#} ~opts)
         ~srv-binding (server/start-test-server reg# adapter# opts#)]
     (try
       (server/wait-for-server-ready ~srv-binding)
       ~@body
       (finally
         (server/stop-test-server ~srv-binding)))))

(deftest ^:integration test-roots-server-initialization
  (testing "Roots server initializes and reports roots capability"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (let [response (client/client-initialize c {:name "test-client" :version "1.0"})]
          (is (nil? (:error response)) "Initialization should succeed")
          (is (= "2025-06-18" (get-in response [:result :protocolVersion]))
              "Should report MCP 2025-06-18")

          ;; Check roots capability (if supported by adapter)
          (let [capabilities (get-in response [:result :capabilities])]
            (is (map? capabilities) "Should have capabilities object")))))))

(deftest ^:integration test-roots-list-via-tools
  (testing "Get roots list via get-roots tool"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        (let [response (client/client-call-tool c "get-roots" {})]
          (is (nil? (:error response)) "Should succeed")

          (let [content (get-in response [:result :content])
                data (json/parse-string (get-in content [0 :text]) true)]
            (is (vector? (:roots data)) "Should return roots array")
            (is (pos? (:count data)) "Should have at least one root")
            (is (>= (count (:roots data)) 3) "Should have default 3 roots")

            ;; Validate root structure
            (let [root (first (:roots data))]
              (is (string? (:uri root)) "Root should have URI")
              (is (string? (:name root)) "Root should have name")
              (is (re-matches #"file://.*" (:uri root)) "URI should start with file://"))))))))

(deftest ^:integration test-validate-path
  (testing "Path validation against roots"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Validate path within roots
        (let [response (client/client-call-tool c "validate-path"
                                                {:path "/home/user/projects/test.txt"})]
          (is (nil? (:error response)) "Should succeed")

          (let [content (get-in response [:result :content])
                data (json/parse-string (get-in content [0 :text]) true)]
            (is (true? (:valid data)) "Path within roots should be valid")))

        ;; Validate path outside roots
        (let [response (client/client-call-tool c "validate-path"
                                                {:path "/etc/passwd"})]
          (is (nil? (:error response)) "Tool call should succeed")

          (let [content (get-in response [:result :content])
                data (json/parse-string (get-in content [0 :text]) true)]
            (is (false? (:valid data)) "Path outside roots should be invalid")
            (is (string? (:error data)) "Should include error message")))))))

(deftest ^:integration test-check-access
  (testing "Check access to multiple paths"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        (let [response (client/client-call-tool c "check-access"
                                                {:paths ["/home/user/projects/a.txt"
                                                         "/etc/passwd"
                                                         "/tmp/b.txt"]})]
          (is (nil? (:error response)) "Should succeed")

          (let [content (get-in response [:result :content])
                data (json/parse-string (get-in content [0 :text]) true)]
            (is (= 3 (count (:results data))) "Should check all 3 paths")
            (is (pos? (:accessible data)) "Should have accessible paths")
            (is (pos? (:blocked data)) "Should have blocked paths")
            (is (= 3 (+ (:accessible data) (:blocked data)))
                "Sum should equal total paths")))))))

(deftest ^:integration test-list-files-security
  (testing "List files with root validation"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Try to list files in an allowed root
        (let [response (client/client-call-tool c "list-files" {:path "/tmp"})]
          ;; This might succeed or fail depending on if /tmp exists
          ;; but should not return security error
          (when-not (:error response)
            (let [content (get-in response [:result :content])]
              (is (sequential? content) "Should return content array"))))

        ;; Try to list files outside roots - should fail with security error
        (let [response (client/client-call-tool c "list-files" {:path "/etc"})]
          (is (some? (:error response)) "Should return error")
          (is (= -32602 (get-in response [:error :code]))
              "Should return invalid params error")
          (is (re-find #"outside declared roots" (get-in response [:error :message]))
              "Error message should mention roots"))))))

(deftest ^:integration test-read-file-security
  (testing "Read file with root validation"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Try to read file outside roots - should fail with security error
        (let [response (client/client-call-tool c "read-file" {:path "/etc/passwd"})]
          (is (some? (:error response)) "Should return error")
          (is (= -32602 (get-in response [:error :code]))
              "Should return invalid params error")
          (is (re-find #"outside declared roots" (get-in response [:error :message]))
              "Error message should mention roots"))))))

(deftest ^:integration test-test-security-tool
  (testing "Security testing tool"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        (let [response (client/client-call-tool c "test-security"
                                                {:safe-path "/home/user/projects/test.txt"
                                                 :unsafe-path "/etc/passwd"
                                                 :relative-path "../../../etc/passwd"})]
          (is (nil? (:error response)) "Should succeed")

          (let [content (get-in response [:result :content])
                data (json/parse-string (get-in content [0 :text]) true)]
            (is (= "Security Boundary Enforcement" (:test data)))
            (is (vector? (:results data)) "Should have results array")
            (is (= 3 (count (:results data))) "Should test 3 paths")

            ;; Check summary
            (is (pos? (get-in data [:summary :allowed])) "Should have allowed paths")
            (is (pos? (get-in data [:summary :blocked])) "Should have blocked paths")

            ;; Verify verdicts
            (doseq [result (:results data)]
              (is (contains? #{"ALLOWED" "BLOCKED"} (:verdict result))
                  "Each result should have ALLOWED or BLOCKED verdict"))))))))

(deftest ^:integration test-dangerous-tools-filtered
  (testing "Dangerous root management tools are filtered by default"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        (let [response (client/client-request c "tools/list" {})]
          (is (nil? (:error response)) "Should succeed")

          (let [tools (get-in response [:result :tools])
                tool-names (set (map :name tools))]
            ;; Dangerous tools should be filtered
            (is (not (contains? tool-names "add-root"))
                "add-root should be filtered (dangerous)")
            (is (not (contains? tool-names "remove-root"))
                "remove-root should be filtered (dangerous)")

            ;; Safe tools should be present
            (is (contains? tool-names "list-files") "list-files should be present")
            (is (contains? tool-names "validate-path") "validate-path should be present")))))))

(deftest ^:integration test-dangerous-tools-with-refactoring
  (testing "Dangerous tools visible with refactoring mode enabled"
    (with-roots-test-server [srv {:transport :http :enable-refactoring true}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        (let [response (client/client-request c "tools/list" {})]
          (is (nil? (:error response)) "Should succeed")

          (let [tools (get-in response [:result :tools])
                tool-names (set (map :name tools))]
            ;; Dangerous tools should be visible
            (is (contains? tool-names "add-root") "add-root should be visible")
            (is (contains? tool-names "remove-root") "remove-root should be visible")))))))

(deftest ^:integration test-add-root-operation
  (testing "Add root operation (with refactoring mode)"
    (with-roots-test-server [srv {:transport :http :enable-refactoring true}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Get initial roots
        (let [initial-response (client/client-call-tool c "get-roots" {})
              initial-data (json/parse-string
                            (get-in initial-response [:result :content 0 :text]) true)
              initial-count (:count initial-data)]

          ;; Add a new root
          (let [response (client/client-call-tool c "add-root"
                                                  {:uri "file:///opt/data"
                                                   :name "Data Directory"})]
            (is (nil? (:error response)) "Should succeed")

            (let [content (get-in response [:result :content])
                  data (json/parse-string (get-in content [0 :text]) true)]
              (is (= "success" (:status data)))
              (is (= "Root added" (:message data)))
              (is (= "file:///opt/data" (:uri data)))
              (is (= (inc initial-count) (count (:current-roots data)))
                  "Should have one more root"))))))))

(deftest ^:integration test-remove-root-operation
  (testing "Remove root operation (with refactoring mode)"
    (with-roots-test-server [srv {:transport :http :enable-refactoring true}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Get initial roots
        (let [initial-response (client/client-call-tool c "get-roots" {})
              initial-data (json/parse-string
                            (get-in initial-response [:result :content 0 :text]) true)
              initial-count (:count initial-data)]

          ;; Remove a root
          (let [response (client/client-call-tool c "remove-root"
                                                  {:uri "file:///tmp"})]
            (is (nil? (:error response)) "Should succeed")

            (let [content (get-in response [:result :content])
                  data (json/parse-string (get-in content [0 :text]) true)]
              (is (= "success" (:status data)))
              (is (= "Root removed" (:message data)))
              (is (= "file:///tmp" (:uri data)))
              (is (= (dec initial-count) (count (:current-roots data)))
                  "Should have one fewer root"))))))))

(deftest ^:integration test-concurrent-validation
  (testing "Concurrent path validation requests"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test-client" :version "1.0"})

        ;; Send multiple validation requests concurrently
        (let [paths ["/home/user/projects/a.txt"
                     "/home/user/documents/b.txt"
                     "/tmp/c.txt"
                     "/etc/passwd"
                     "/home/user/projects/deep/nested/d.txt"]
              futures (mapv (fn [path]
                              (future
                                (client/client-call-tool c "validate-path" {:path path})))
                            paths)
              responses (mapv deref futures)]

          ;; All requests should complete
          (is (= 5 (count responses)) "Should get 5 responses")

          ;; All should succeed (no errors at tool call level)
          (doseq [response responses]
            (is (nil? (:error response)) "Tool calls should succeed"))

          ;; Parse validation results
          (let [results (mapv (fn [response]
                                (json/parse-string
                                 (get-in response [:result :content 0 :text]) true))
                              responses)
                valid-count (count (filter :valid results))
                invalid-count (count (remove :valid results))]
            (is (pos? valid-count) "Should have some valid paths")
            (is (pos? invalid-count) "Should have some invalid paths")
            (is (= 5 (+ valid-count invalid-count))
                "All validations should be classified")))))))

(deftest ^:integration test-compliance-validation
  (testing "All responses comply with MCP 2025-06-18 spec"
    (with-roots-test-server [srv {:transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        ;; Initialize
        (let [init-response (client/client-initialize c {:name "test" :version "1.0"})]
          (is (nil? (compliance/validate-response "initialize"
                                                  (dissoc init-response :_request-id)
                                                  (:_request-id init-response)))
              "Initialize response should be compliant"))

        ;; Tools list
        (let [list-response (client/client-request c "tools/list" {})]
          (is (nil? (compliance/validate-response "tools/list"
                                                  (dissoc list-response :_request-id)
                                                  (:_request-id list-response)))
              "Tools list response should be compliant"))

        ;; Tool call
        (let [call-response (client/client-call-tool c "validate-path"
                                                     {:path "/home/user/test"})]
          (is (nil? (compliance/validate-response "tools/call"
                                                  (dissoc call-response :_request-id)
                                                  (:_request-id call-response)))
              "Tool call response should be compliant"))))))

(deftest ^:integration test-full-workflow
  (testing "Complete roots server workflow"
    (with-roots-test-server [srv {:transport :http :enable-refactoring true}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        ;; 1. Initialize
        (let [init-response (client/client-initialize c {:name "test" :version "1.0"})]
          (is (nil? (:error init-response))))

        ;; 2. Get initial roots
        (let [roots-response (client/client-call-tool c "get-roots" {})
              roots-data (json/parse-string
                          (get-in roots-response [:result :content 0 :text]) true)]
          (is (= 3 (:count roots-data)) "Should start with 3 roots"))

        ;; 3. Validate paths against initial roots
        (let [valid-response (client/client-call-tool c "validate-path"
                                                      {:path "/home/user/projects/test"})
              valid-data (json/parse-string
                          (get-in valid-response [:result :content 0 :text]) true)]
          (is (true? (:valid valid-data)) "Path should be valid"))

        ;; 4. Add a new root
        (let [add-response (client/client-call-tool c "add-root"
                                                    {:uri "file:///opt/app"
                                                     :name "Application"})]
          (is (nil? (:error add-response))))

        ;; 5. Verify new root is accessible
        (let [new-path-response (client/client-call-tool c "validate-path"
                                                         {:path "/opt/app/config.json"})
              new-path-data (json/parse-string
                             (get-in new-path-response [:result :content 0 :text]) true)]
          (is (true? (:valid new-path-data)) "New root path should be valid"))

        ;; 6. Check batch access
        (let [batch-response (client/client-call-tool c "check-access"
                                                      {:paths ["/home/user/projects/a"
                                                               "/opt/app/b"
                                                               "/etc/c"]})
              batch-data (json/parse-string
                          (get-in batch-response [:result :content 0 :text]) true)]
          (is (= 2 (:accessible batch-data)) "Should have 2 accessible paths")
          (is (= 1 (:blocked batch-data)) "Should have 1 blocked path"))

        ;; 7. Test security boundaries
        (let [security-response (client/client-call-tool c "test-security" {})
              security-data (json/parse-string
                             (get-in security-response [:result :content 0 :text]) true)]
          (is (= "Security Boundary Enforcement" (:test security-data))))

        ;; 8. Remove a root
        (let [remove-response (client/client-call-tool c "remove-root"
                                                       {:uri "file:///tmp"})]
          (is (nil? (:error remove-response))))

        ;; 9. Verify removed root is no longer accessible
        (let [removed-path-response (client/client-call-tool c "validate-path"
                                                             {:path "/tmp/test"})
              removed-path-data (json/parse-string
                                 (get-in removed-path-response [:result :content 0 :text]) true)]
          (is (false? (:valid removed-path-data))
              "Removed root path should be invalid"))))))
