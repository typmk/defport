(ns defport.bsp.spec
  "Build Server Protocol 2.2 — single source of truth for method
   routing.

   BSP sits between build tools (sbt, Mill, Bazel, Gradle, Bloop)
   and clients (IDEs, language servers that need build info). It
   speaks JSON-RPC 2.0 over stdio, exactly like LSP and DAP —
   which means every piece of defport's substrate drops in
   unchanged: spec registry, sugar macros, subprocess transports,
   sugar/run!.

   The authoritative spec lives at
   https://github.com/build-server-protocol/build-server-protocol/blob/master/spec/src/main/resources/META-INF/smithy/bsp/bsp.smithy
   and is committed into resources/bsp.smithy.

   Surface: 27 methods (15 requests + 12 notifications) covering
   lifecycle, workspace discovery, build target operations, and
   debug sessions. Plain Clojure data — no schema lib."
  (:refer-clojure :exclude [methods]))

(def sugar-extractors
  "BSP params are mostly `{:targets [BuildTargetIdentifier...]}` or
   similarly uniform shapes; defcommand/defbsp benefit from a
   handful of simple extractors."
  {:raw     (fn [params] params)
   :targets (fn [params] {:targets (:targets params)})
   :target  (fn [params] {:target (:target params)})
   :origin  (fn [params] {:origin-id (:originId params)})})

(def methods
  "Every BSP 2.2 method. Keyed by handler-name keyword."
  {;; -------------------------------------------------------------------------
   ;; Lifecycle
   ;; -------------------------------------------------------------------------
   :initialize
   {:method     "build/initialize"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {:capabilities {}})
    :doc        "Client introduces itself and negotiates build capabilities."}

   :initialized
   {:method     "build/initialized"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Client confirms the initialize handshake is complete."}

   :shutdown
   {:method     "build/shutdown"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly nil)
    :doc        "Ask the build server to shut down."}

   :exit
   {:method     "build/exit"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Tell the build server to exit its process."}

   ;; -------------------------------------------------------------------------
   ;; Workspace discovery
   ;; -------------------------------------------------------------------------
   :workspace-build-targets
   {:method     "workspace/buildTargets"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {:targets []})
    :doc        "List every build target in the workspace."}

   :workspace-reload
   {:method     "workspace/reload"
    :kind       :request
    :direction  :client->server
    :capability :canReload
    :sugar      :raw
    :default    (constantly nil)
    :doc        "Reload the workspace build configuration."}

   ;; -------------------------------------------------------------------------
   ;; Build target operations (the main surface a consumer cares about)
   ;; -------------------------------------------------------------------------
   :build-target-sources
   {:method     "buildTarget/sources"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :targets
    :default    (constantly {:items []})
    :doc        "List source roots for given build targets."}

   :build-target-inverse-sources
   {:method     "buildTarget/inverseSources"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {:targets []})
    :doc        "Find build targets containing a given source file."}

   :build-target-dependency-sources
   {:method     "buildTarget/dependencySources"
    :kind       :request
    :direction  :client->server
    :capability :dependencySourcesProvider
    :sugar      :targets
    :default    (constantly {:items []})
    :doc        "List source jars for target dependencies."}

   :build-target-dependency-modules
   {:method     "buildTarget/dependencyModules"
    :kind       :request
    :direction  :client->server
    :capability :dependencyModulesProvider
    :sugar      :targets
    :default    (constantly {:items []})
    :doc        "List Maven/Ivy modules for target dependencies."}

   :build-target-resources
   {:method     "buildTarget/resources"
    :kind       :request
    :direction  :client->server
    :capability :resourcesProvider
    :sugar      :targets
    :default    (constantly {:items []})
    :doc        "List resource roots for given build targets."}

   :build-target-output-paths
   {:method     "buildTarget/outputPaths"
    :kind       :request
    :direction  :client->server
    :capability :outputPathsProvider
    :sugar      :targets
    :default    (constantly {:items []})
    :doc        "List output paths (class files, jars) for targets."}

   :build-target-compile
   {:method     "buildTarget/compile"
    :kind       :request
    :direction  :client->server
    :capability :compileProvider
    :sugar      :targets
    :default    (constantly {:statusCode 2})   ;; 2 = ERROR: no handler
    :doc        "Compile the given build targets."}

   :build-target-test
   {:method     "buildTarget/test"
    :kind       :request
    :direction  :client->server
    :capability :testProvider
    :sugar      :targets
    :default    (constantly {:statusCode 2})
    :doc        "Run tests in the given build targets."}

   :build-target-run
   {:method     "buildTarget/run"
    :kind       :request
    :direction  :client->server
    :capability :runProvider
    :sugar      :target
    :default    (constantly {:statusCode 2})
    :doc        "Run a build target's main entry point."}

   :build-target-clean-cache
   {:method     "buildTarget/cleanCache"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :targets
    :default    (constantly {:cleaned true})
    :doc        "Clean cached build artifacts for the given targets."}

   :buildTarget-didChange
   {:method     "buildTarget/didChange"
    :kind       :notification
    :direction  :server->client
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Server tells client the build target set has changed."}

   ;; -------------------------------------------------------------------------
   ;; Debug sessions
   ;; -------------------------------------------------------------------------
   :debug-session-start
   {:method     "debugSession/start"
    :kind       :request
    :direction  :client->server
    :capability :debugProvider
    :sugar      :raw
    :default    (constantly {:uri ""})
    :doc        "Start a debug session for the given targets."}

   ;; -------------------------------------------------------------------------
   ;; Task / diagnostics / output notifications (server → client)
   ;; -------------------------------------------------------------------------
   :task-start
   {:method     "build/taskStart"
    :kind       :notification
    :direction  :server->client
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Server signals a long-running build task has started."}

   :task-progress
   {:method     "build/taskProgress"
    :kind       :notification
    :direction  :server->client
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Server reports progress on an in-flight build task."}

   :task-finish
   {:method     "build/taskFinish"
    :kind       :notification
    :direction  :server->client
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Server signals a build task has completed."}

   :publish-diagnostics
   {:method     "build/publishDiagnostics"
    :kind       :notification
    :direction  :server->client
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Server pushes diagnostics for a source file."}

   :show-message
   {:method     "build/showMessage"
    :kind       :notification
    :direction  :server->client
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Server asks client to display a message to the user."}

   :log-message
   {:method     "build/logMessage"
    :kind       :notification
    :direction  :server->client
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Server emits a log message to the client."}

   :run-print-stdout
   {:method     "run/printStdout"
    :kind       :notification
    :direction  :server->client
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Stream stdout from a running build target."}

   :run-print-stderr
   {:method     "run/printStderr"
    :kind       :notification
    :direction  :server->client
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Stream stderr from a running build target."}

   :run-read-stdin
   {:method     "run/readStdin"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Client sends stdin to a running build target."}})

;; ============================================================================
;; Lookups (parallel to defport.lsp.spec)
;; ============================================================================

(defn method-for [handler-name] (get methods handler-name))

(defn method-name-for [method-string]
  (some (fn [[k v]] (when (= method-string (:method v)) k)) methods))

(defn wire-method [handler-name] (:method (method-for handler-name)))

(defn capability-key [handler-name] (:capability (method-for handler-name)))

(defn sugar-extractor [handler-name]
  (let [k (or (:sugar (method-for handler-name)) :raw)]
    (get sugar-extractors k)))

(defn default-response [handler-name] (:default (method-for handler-name)))

(defn notification? [handler-name]
  (= :notification (:kind (method-for handler-name))))

(defn request? [handler-name]
  (= :request (:kind (method-for handler-name))))

(defn server-initiated? [handler-name]
  (= :server->client (:direction (method-for handler-name))))

(defn all-handler-names [] (keys methods))
