(ns roots-server
  "MCP Roots Server - Demonstrates roots/list feature for path validation.

  The roots/list feature allows servers to declare a list of root directories
  that define the scope of file operations. Clients can use this to:
  - Validate file paths before operations
  - Show directory trees in UI
  - Enforce security boundaries

  This server demonstrates:
  1. Static roots declaration
  2. Dynamic roots (add/remove at runtime)
  3. Path validation against roots
  4. Roots change notifications
  5. Security enforcement

  Run with:
    clojure -M:examples -m roots-server --http 8080
    clojure -M:examples -m roots-server --stdio"
  (:require [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.registry :as registry]
            [defport.transports.http :as http]
            [defport.transports.stdio :as stdio]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.io File]))

;; ============================================================================
;; Roots Management State
;; ============================================================================

(defonce roots-state
  "Server state for managing roots list"
  (atom {:roots [{:uri "file:///home/user/projects"
                  :name "Projects Directory"}
                 {:uri "file:///home/user/documents"
                  :name "Documents"}
                 {:uri "file:///tmp"
                  :name "Temp Directory"}]
         :subscribers #{}}))

(defn get-roots []
  "Get current roots list"
  (:roots @roots-state))

(defn add-root! [uri name]
  "Add a new root dynamically"
  (swap! roots-state update :roots conj {:uri uri :name name})
  ;; TODO: Send roots/list_changed notification to subscribers
  true)

(defn remove-root! [uri]
  "Remove a root by URI"
  (swap! roots-state update :roots
         (fn [roots] (vec (remove #(= (:uri %) uri) roots))))
  ;; TODO: Send roots/list_changed notification to subscribers
  true)

(defn path-in-root? [path root-uri]
  "Check if a path is within a root directory"
  (let [root-path (-> root-uri
                      (str/replace #"^file://" "")
                      (str/replace #"^///" "/")) ;; Handle Windows paths
        normalized-path (str/replace path "\\" "/")]
    (str/starts-with? normalized-path root-path)))

(defn validate-path [path]
  "Validate that a path is within one of the declared roots"
  (let [roots (get-roots)]
    (if (some #(path-in-root? path (:uri %)) roots)
      {:valid true}
      {:valid false
       :error "Path is outside declared roots"
       :path path
       :roots (mapv :uri roots)})))

;; ============================================================================
;; Tool Handlers
;; ============================================================================

(defn list-files-handler
  "List files in a directory (validates against roots)"
  [context]
  (try
    (let [path (get-in context [:params :path])
          validation (validate-path path)]
      (if (:valid validation)
        (let [dir (io/file path)
              files (when (.exists dir)
                      (->> (.listFiles dir)
                           (map (fn [^File f]
                                  {:name (.getName f)
                                   :path (.getPath f)
                                   :type (if (.isDirectory f) "directory" "file")
                                   :size (.length f)}))
                           vec))]
          {:content [{:type "text"
                      :text (json/generate-string
                             {:path path
                              :files (or files [])
                              :count (count files)})}]})
        ;; Path validation failed
        {:error {:code -32602
                 :message "Invalid path: outside declared roots"
                 :data validation}}))
    (catch Exception e
      {:error {:code -32603
               :message (str "Failed to list files: " (.getMessage e))}})))

(defn read-file-handler
  "Read a file (validates against roots)"
  [context]
  (try
    (let [path (get-in context [:params :path])
          validation (validate-path path)]
      (if (:valid validation)
        (let [file (io/file path)]
          (if (.exists file)
            (if (.isFile file)
              (let [content (slurp file)
                    size (.length file)]
                {:content [{:type "text"
                            :text (json/generate-string
                                   {:path path
                                    :size size
                                    :content content})}]})
              {:error {:code -32602
                       :message "Path is not a file"
                       :data {:path path}}})
            {:error {:code -32602
                     :message "File not found"
                     :data {:path path}}}))
        ;; Path validation failed
        {:error {:code -32602
                 :message "Invalid path: outside declared roots"
                 :data validation}}))
    (catch Exception e
      {:error {:code -32603
               :message (str "Failed to read file: " (.getMessage e))}})))

(defn validate-path-handler
  "Explicitly validate a path against roots"
  [context]
  (let [path (get-in context [:params :path])
        validation (validate-path path)]
    {:content [{:type "text"
                :text (json/generate-string validation)}]}))

(defn check-access-handler
  "Check if multiple paths are accessible"
  [context]
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

(defn add-root-handler
  "Add a new root dynamically (dangerous operation)"
  [context]
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

(defn remove-root-handler
  "Remove a root dynamically (dangerous operation)"
  [context]
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

(defn get-roots-handler
  "Get current roots list (alternative to roots/list method)"
  [context]
  {:content [{:type "text"
              :text (json/generate-string
                     {:roots (get-roots)
                      :count (count (get-roots))})}]})

(defn test-security-handler
  "Test security boundary enforcement with various paths"
  [context]
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
;; Registry Setup
;; ============================================================================

(defn create-registry []
  "Create and populate the tool registry"
  (let [reg (registry/create-function-registry)]

    ;; File operations (with root validation)
    (registry/register-port! reg
      {:id :list-files
       :name "list-files"
       :description "List files in a directory (validates against roots)"
       :input-schema {:type "object"
                      :properties {:path {:type "string"
                                          :description "Directory path to list"}}
                      :required ["path"]}
       :handler list-files-handler})

    (registry/register-port! reg
      {:id :read-file
       :name "read-file"
       :description "Read a file (validates against roots)"
       :input-schema {:type "object"
                      :properties {:path {:type "string"
                                          :description "File path to read"}}
                      :required ["path"]}
       :handler read-file-handler})

    ;; Path validation tools
    (registry/register-port! reg
      {:id :validate-path
       :name "validate-path"
       :description "Validate a path against declared roots"
       :input-schema {:type "object"
                      :properties {:path {:type "string"
                                          :description "Path to validate"}}
                      :required ["path"]}
       :handler validate-path-handler})

    (registry/register-port! reg
      {:id :check-access
       :name "check-access"
       :description "Check if multiple paths are accessible"
       :input-schema {:type "object"
                      :properties {:paths {:type "array"
                                           :items {:type "string"}
                                           :description "Paths to check"}}
                      :required ["paths"]}
       :handler check-access-handler})

    ;; Root management (dangerous operations)
    (registry/register-port! reg
      {:id :add-root
       :name "add-root"
       :description "Add a new root directory (requires refactoring mode)"
       :input-schema {:type "object"
                      :properties {:uri {:type "string"
                                         :description "Root URI (e.g., file:///path/to/dir)"}
                                   :name {:type "string"
                                          :description "Human-readable name"}}
                      :required ["uri" "name"]}
       :handler add-root-handler
       :metadata {:dangerous true}})

    (registry/register-port! reg
      {:id :remove-root
       :name "remove-root"
       :description "Remove a root directory (requires refactoring mode)"
       :input-schema {:type "object"
                      :properties {:uri {:type "string"
                                         :description "Root URI to remove"}}
                      :required ["uri"]}
       :handler remove-root-handler
       :metadata {:dangerous true}})

    ;; Utility tools
    (registry/register-port! reg
      {:id :get-roots
       :name "get-roots"
       :description "Get current roots list"
       :input-schema {:type "object"
                      :properties {}}
       :handler get-roots-handler})

    (registry/register-port! reg
      {:id :test-security
       :name "test-security"
       :description "Test security boundary enforcement with various paths"
       :input-schema {:type "object"
                      :properties {:safe-path {:type "string"
                                               :description "Path within roots (optional)"}
                                   :unsafe-path {:type "string"
                                                 :description "Path outside roots (optional)"}
                                   :relative-path {:type "string"
                                                   :description "Relative path test (optional)"}}}
       :handler test-security-handler})

    reg))

;; ============================================================================
;; MCP Adapter with Roots Support
;; ============================================================================

(defn create-roots-adapter
  "Create MCP adapter with roots/list support"
  []
  (let [adapter (mcp/create-mcp-adapter)]
    ;; Override handle-request to support roots/list
    (reify core/ProtocolAdapter
      (adapter-initialize [_ registry options]
        (core/adapter-initialize adapter registry options))

      (handle-request [_ registry transport request]
        (if (= (:method request) "roots/list")
          ;; Handle roots/list request
          {:jsonrpc "2.0"
           :id (:id request)
           :result {:roots (get-roots)}}
          ;; Delegate to standard MCP adapter
          (core/handle-request adapter registry transport request))))))

;; ============================================================================
;; Server Startup
;; ============================================================================

(defn start-server
  "Start the roots server with specified transport"
  [transport-type port]
  (let [registry (create-registry)
        adapter (create-roots-adapter)

        ;; Initialize the adapter
        init-opts {:server-info {:name "defport-roots-server"
                                 :version "1.0.0"}
                   :capabilities {:roots {:listChanged true}
                                  :tools {}}}
        _ (core/adapter-initialize adapter registry init-opts)

        ;; Create request handler
        handler (fn [request]
                  (core/handle-request adapter registry nil request))

        ;; Start transport
        transport (case transport-type
                    :http (let [t (http/create-http-transport {:port port})]
                            (core/transport-start t handler)
                            t)
                    :stdio (let [t (stdio/create-stdio-transport)]
                             (core/transport-start t handler)
                             t))]

    (println (str "Roots server started on "
                  (case transport-type
                    :http (str "http://localhost:" port)
                    :stdio "stdio")))
    (println "Declared roots:")
    (doseq [root (get-roots)]
      (println (str "  - " (:name root) ": " (:uri root))))
    (println "\nAvailable tools:")
    (println "  - list-files: List files in directory (validates against roots)")
    (println "  - read-file: Read file (validates against roots)")
    (println "  - validate-path: Validate path against roots")
    (println "  - check-access: Check multiple paths")
    (println "  - get-roots: Get current roots list")
    (println "  - test-security: Test security boundary enforcement")
    (println "  - add-root: Add new root (dangerous)")
    (println "  - remove-root: Remove root (dangerous)")

    ;; Keep running for stdio, return transport for HTTP
    (when (= transport-type :stdio)
      @(promise)) ;; Block forever
    transport))

(defn -main [& args]
  (let [transport (if (some #{"--stdio"} args) :stdio :http)
        port (or (some->> args
                          (drop-while #(not= "--http" %))
                          second
                          Integer/parseInt)
                 8080)]
    (start-server transport port)))

(comment
  ;; Start HTTP server
  (def server (start-server :http 8080))

  ;; Test path validation
  (validate-path "/home/user/projects/test.txt")
  (validate-path "/etc/passwd")

  ;; Add a root
  (add-root! "file:///opt/data" "Data Directory")

  ;; Remove a root
  (remove-root! "file:///tmp")

  ;; Get roots
  (get-roots)

  ;; Stop server
  (core/transport-stop server)
  )
