(ns defport.integration.real-external-test
  "Integration tests against real external MCP/LSP/DAP servers.

  These tests prove defport's client cores speak the real wire
  format that real independent implementations expect. Running
  these exercises:

    - Content-Length framing (encode + decode against a real peer)
    - JSON serialization (Clojure keywordized ↔ JS string keys)
    - Request/response correlation by id / seq
    - Real initialize handshake semantics (protocol version, caps)
    - Real typed helpers returning real data

  None of the unit tests in the rest of the test suite can catch a
  wire-format bug — every one uses an in-memory paired transport or
  `cat` echo. These tests catch the class of bug that only shows up
  when a second, independent implementation of the same spec is on
  the other end of the pipe.

  ## Skip semantics

  Each test checks whether its external peer is available. If not,
  the test logs a skip message via `is true` so the suite stays
  green on developer machines without the tool. CI installs the
  tools in setup and runs the whole suite unconditionally."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [cheshire.core :as json]
            [defport.mcp.client :as mcp]
            [defport.mcp.client.transports.subprocess :as mcp-sub]
            [defport.lsp.client :as lsp]
            [defport.lsp.client.transports.subprocess :as lsp-sub]
            [defport.dap.client :as dap]
            [defport.dap.client.transports.subprocess :as dap-sub]
            [defport.bsp.client :as bsp]
            [defport.bsp.client.transports.subprocess :as bsp-sub]
            [defport.cdp.client :as cdp]
            [defport.cdp.client.transports.websocket :as cdp-ws]
            [defport.ros2.client :as ros2]
            [defport.ros2.client.transports.websocket :as ros2-ws]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- which
  "Return absolute path to a binary on PATH, or nil."
  [cmd]
  (let [result (shell/sh "which" cmd)]
    (when (zero? (:exit result))
      (clojure.string/trim (:out result)))))

(defn- runnable?
  "Probe a command by running it with `--version` (or a fallback
   arg) and checking whether it exits successfully. Catches broken
   rustup-style proxies whose binary path exists but which error on
   invocation."
  [cmd & {:keys [probe-args] :or {probe-args ["--version"]}}]
  (when (which cmd)
    (try
      (let [r (apply shell/sh cmd probe-args)]
        (zero? (:exit r)))
      (catch Exception _ false))))

(defn- available? [cmd]
  (runnable? cmd))

(defn- skip
  "Mark a test as passing with a skip message. Keeps the suite
   green on dev machines without the external tool."
  [reason]
  (is true (str "SKIPPED: " reason)))

;; ============================================================================
;; MCP: defport client → @modelcontextprotocol/server-everything
;; ============================================================================
;;
;; server-everything is Microsoft's reference MCP server — it exposes
;; a broad sample of tools, prompts, resources, and exercises the
;; full 2025-11-25 protocol surface. If defport's client can handshake
;; with it and list its tools, the wire format is right.

(deftest test-mcp-real-server-everything
  (testing "defport MCP client can connect to @modelcontextprotocol/server-everything"
    (if-not (available? "npx")
      (skip "npx not installed")
      (let [tx (mcp-sub/transport ["npx" "-y" "@modelcontextprotocol/server-everything"])
            client (mcp/create-client tx)]
        (try
          ;; First-time npx can take 30+ seconds to download the package.
          (mcp/connect! client {:client-info {:name "defport-integration-test"
                                              :version "0.1.0"}
                                :connect-timeout-ms 120000})

          (is (true? (:initialized? @(:state* client)))
              "client should be initialized after connect!")
          (is (some? (:server-info @(:state* client)))
              "server info should be captured during handshake")

          ;; Enumerate tools the reference server exposes.
          (let [[body err] (mcp/await (mcp/list-tools client))]
            (is (nil? err) (str "list-tools error: " err))
            (is (vector? (:tools body)) "tools/list should return a vector")
            (is (pos? (count (:tools body)))
                "server-everything exposes multiple sample tools")
            (when (pos? (count (:tools body)))
              ;; Every tool should have at least :name and :inputSchema
              (let [t (first (:tools body))]
                (is (string? (:name t)))
                (is (map? (:inputSchema t))
                    "every MCP tool must declare an inputSchema"))))

          ;; List prompts and resources too — server-everything has them.
          (let [[body _] (mcp/await (mcp/list-prompts client))]
            (is (sequential? (:prompts body))
                "prompts/list should return a sequential coll"))

          (let [[body _] (mcp/await (mcp/list-resources client))]
            (is (sequential? (:resources body))
                "resources/list should return a sequential coll"))

          (finally
            (mcp/disconnect! client)))))))

;; ============================================================================
;; LSP: defport client → rust-analyzer
;; ============================================================================
;;
;; rust-analyzer is a real, widely-used, spec-conformant LSP 3.17
;; implementation. If defport's client can handshake with it and
;; query a trivial workspace, the wire format is right.

(defn- write-minimal-rust-project!
  "Create a tiny Rust project in a tmp directory so rust-analyzer has
   something to parse. Returns the absolute path."
  []
  (let [dir (java.io.File/createTempFile "defport-lsp-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (spit (io/file dir "Cargo.toml")
          "[package]\nname = \"defport-lsp-test\"\nversion = \"0.1.0\"\nedition = \"2021\"\n")
    (let [src (io/file dir "src")]
      (.mkdirs src)
      (spit (io/file src "lib.rs")
            "pub fn hello() -> &'static str { \"hello world\" }\n"))
    (.getAbsolutePath dir)))

(deftest test-lsp-real-rust-analyzer
  (testing "defport LSP client can connect to rust-analyzer"
    (if-not (available? "rust-analyzer")
      (skip "rust-analyzer not installed")
      (let [project-dir (write-minimal-rust-project!)
            tx (lsp-sub/transport ["rust-analyzer"])
            client (lsp/create-client tx)
            done (promise)]
        (try
          (lsp/connect-async!
            client
            {:client-info {:name "defport-integration-test" :version "0.1.0"}
             :root-uri (str "file://" project-dir)
             ;; rust-analyzer wants a minimal capabilities structure;
             ;; empty map makes it reject the initialize in some
             ;; versions. Supply the lowest-viable shape.
             :capabilities {:textDocument {:hover {:contentFormat ["markdown" "plaintext"]}
                                           :definition {:linkSupport false}}
                            :workspace {:applyEdit false}}}
            (fn [c err] (deliver done [c err])))

          ;; rust-analyzer is slow on first run — it parses the whole
          ;; project and builds an internal DB. 120s is generous.
          (let [[c err] (deref done 120000 [::timeout nil])]
            (is (not= ::timeout c)
                "rust-analyzer initialize handshake did not complete in 120s")
            (is (nil? err) (str "initialize error: " err))
            (when c
              (is (true? (:initialized? @(:state* c))))
              (let [caps (:server-capabilities @(:state* c))]
                (is (map? caps) "rust-analyzer should return a capability map")
                ;; rust-analyzer supports at minimum hover, definition, references
                (is (or (:hoverProvider caps)
                        (contains? caps :hoverProvider))
                    "rust-analyzer should declare hoverProvider"))))

          (finally
            (lsp/disconnect! client)
            ;; Clean up the tmp project
            (letfn [(rm-rf [^java.io.File f]
                      (when (.isDirectory f)
                        (doseq [c (.listFiles f)] (rm-rf c)))
                      (.delete f))]
              (rm-rf (io/file project-dir)))))))))

;; ============================================================================
;; DAP: we don't have debugpy pre-installed, so defer real-client validation
;; ============================================================================
;;
;; See the dap-server example for a manual validation path via nvim-dap.
;; A full in-CI DAP integration test would `pip install debugpy` in the
;; setup phase and spawn it here. Leaving a placeholder test that
;; deliberately skips so the suite documents the gap rather than hiding it.

(defn- debugpy-available? []
  (and (runnable? "python3")
       (zero? (:exit (shell/sh "python3" "-c" "import debugpy")))))

;; ============================================================================
;; Server-role validation: external Python clients spawn defport servers
;; ============================================================================
;;
;; Complements the client-role tests above. Each of these spawns a
;; Python script (stdlib only, no deps) that acts as a real external
;; client and talks to defport's example server subprocess. If the
;; Python client asserts a clean round-trip, defport's server is
;; proven to speak the right wire to a different-language peer.

(defn- run-python-client
  "Spawn the Python helper script with `server-cmd` as the server
   it should start. Returns [exit-code stdout stderr]."
  [script & server-cmd]
  (apply shell/sh "python3" script server-cmd))

(defn- script-path [name]
  (str "test/defport/integration/resources/" name))

;; ============================================================================
;; CDP: defport client vs real Chromium
;; ============================================================================

(def ^:private chromium-candidates
  ;; Distributions and CI images disagree on the name, so this test hardcoded
  ;; `chromium` and passed only where that name happened to exist.
  ;;
  ;; Resolving the name is NOT enough. GitHub's ubuntu runners DO put a
  ;; `chromium` on PATH, and it does not start — `which` succeeds and the
  ;; DevTools port never opens. So each candidate is launched and checked,
  ;; and the first that actually answers wins.
  ["google-chrome" "google-chrome-stable" "chromium" "chromium-browser"
   ;; Chromium forks speak CDP too, and on a machine that has one of these and
   ;; no plain `chromium` the test would otherwise skip forever.
   "helium" "brave-browser" "vivaldi" "thorium-browser"])

(defn- devtools-up?
  [port]
  (try (slurp (str "http://localhost:" port "/json/version")) true
       (catch Exception _ false)))

(defn- try-launch!
  "Launch BIN headless and wait for the DevTools port. Returns the Process on
   success, nil if it never came up (destroying whatever it started)."
  [bin port]
  (let [proc (try (.start (ProcessBuilder. [bin
                                            "--headless=new"
                                            "--disable-gpu"
                                            "--no-sandbox"
                                            (str "--remote-debugging-port=" port)
                                            "about:blank"]))
                  (catch Exception _ nil))]
    (when proc
      (loop [tries 25]
        (cond
          (devtools-up? port) proc
          (zero? tries)       (do (.destroyForcibly proc) nil)
          :else               (do (Thread/sleep 200) (recur (dec tries))))))))

(defn- start-headless-chromium!
  "Spawn a Chromium-family browser with a remote debugging port. Returns the
   Process handle, or nil if no candidate could be started."
  [port]
  (some #(try-launch! % port) chromium-candidates))

(deftest test-cdp-real-chromium
  (testing "defport CDP client can drive real Chromium"
    ;; Guarded on a browser that actually STARTS, not on the name `chromium`
    ;; being resolvable. The old guard was wrong in both directions: it skipped
    ;; on any machine whose browser is called something else (so this never ran
    ;; locally), and it passed on CI runners that carry a `chromium` which does
    ;; not launch (so this errored there).
    (if-let [proc (start-headless-chromium! 9222)]
      (let [port 9222]
        (try
          (let [targets (json/parse-string
                          (slurp (str "http://localhost:" port "/json")) true)
                page    (first (filter #(= "page" (:type %)) targets))
                ws-url  (:webSocketDebuggerUrl page)
                client  (-> (cdp-ws/transport ws-url)
                            (cdp/create-client)
                            (cdp/connect! {}))]
            (try
              ;; Browser.getVersion — proves the WS transport is wired
              (let [[body err] (cdp/await (cdp/browser-get-version client))]
                (is (nil? err) (str "Browser.getVersion error: " err))
                (is (string? (:product body)))
                (is (re-find #"Chrome/" (:product body)))
                (is (string? (:protocolVersion body))))

              ;; Runtime.evaluate — proves JSON-RPC envelope is correct
              (let [[body err] (cdp/await (cdp/runtime-evaluate client "1 + 2 + 3"))]
                (is (nil? err))
                (is (= 6 (get-in body [:result :value]))))

              ;; Page.navigate + a deferred DOM read (via a direct
              ;; JS expression rather than waiting for loadEventFired,
              ;; which would need an event handler)
              (let [[_ err] (cdp/await
                              (cdp/page-navigate client
                                "data:text/html,<h1 id='x'>hello defport</h1>"))]
                (is (nil? err)))

              (finally
                (cdp/disconnect! client))))
          (finally
            (.destroy proc)
            (try (.waitFor proc) (catch Exception _)))))
      (skip (str "no Chromium-family browser could be started; tried "
                 (clojure.string/join ", " chromium-candidates))))))

;; ============================================================================
;; rosbridge: defport.ros2.client vs a minimal fake rosbridge_server
;; ============================================================================
;;
;; No ROS 2 installation on this dev box. Instead we spawn a
;; Python WebSocket server that implements just enough of the
;; rosbridge v2.0 protocol to exercise defport's client round-trip:
;; subscribe, call_service, send_action_goal. The Python server
;; uses the `websockets` package — ubiquitous on modern Debian/
;; Ubuntu/Kali.

(defn- python-websockets-available? []
  (and (runnable? "python3")
       (zero? (:exit (shell/sh "python3" "-c" "import websockets")))))

(defn- start-fake-rosbridge!
  "Start the Python fake rosbridge server in a subprocess. Returns
   the Process handle."
  [port]
  (let [pb (ProcessBuilder.
             ["python3"
              "test/defport/integration/resources/fake_rosbridge_server.py"
              (str port)])
        _ (.redirectErrorStream pb false)
        proc (.start pb)]
    ;; Wait until it's listening — the server writes a startup line
    ;; to stderr.
    (Thread/sleep 600)
    proc))

(deftest test-ros2-fake-rosbridge-round-trip
  (testing "defport.ros2.client round-trips against a fake rosbridge_server"
    (if-not (python-websockets-available?)
      (skip "python3 `websockets` package not installed")
      (let [port 9099
            proc (start-fake-rosbridge! port)]
        (try
          (let [client (-> (ros2-ws/transport (str "ws://127.0.0.1:" port))
                           (ros2/create-client)
                           (ros2/connect! {}))
                topic-hit (promise)]
            (try
              ;; subscribe + on-topic should fire when the fake
              ;; server echoes a publish back
              (ros2/on-topic client "/scan"
                             (fn [msg] (deliver topic-hit msg)))
              (ros2/subscribe! client "/scan" "sensor_msgs/msg/LaserScan")
              (let [msg (deref topic-hit 2000 ::timeout)]
                (is (not= ::timeout msg))
                (is (= "hello from fake rosbridge" (:data msg))))

              ;; call_service — correlation by id
              (let [[body err] (ros2/await
                                 (ros2/call-service client
                                                    "/add_two_ints"
                                                    {:a 1 :b 2}))]
                (is (nil? err))
                (is (= 42 (:sum body))))

              ;; send_action_goal — correlation by id,
              ;; intermediate feedback taps through
              (let [[body err] (ros2/await
                                 (ros2/send-action-goal client
                                                        "/fibonacci"
                                                        {:order 5}))]
                (is (nil? err))
                (is (vector? (:sequence body)))
                (is (= [0 1 1 2 3] (:sequence body))))

              (finally
                (ros2/disconnect! client))))
          (finally
            (.destroy proc)
            (try (.waitFor proc) (catch Exception _))))))))

(deftest test-python-mcp-client-vs-defport-server
  (testing "external Python MCP client spawns defport's MCP server and round-trips"
    (if-not (available? "python3")
      (skip "python3 not installed")
      (let [r (run-python-client (script-path "external_mcp_client.py")
                                 "clojure" "-M:examples" "-m" "mcp-server")]
        (is (zero? (:exit r))
            (str "external Python MCP client failed\n"
                 "stderr:\n" (:err r)
                 "\nstdout:\n" (:out r)))))))

(deftest test-python-lsp-client-vs-defport-server
  (testing "external Python LSP client spawns defport's LSP server and round-trips"
    (if-not (available? "python3")
      (skip "python3 not installed")
      (let [r (run-python-client (script-path "external_lsp_client.py")
                                 "clojure" "-M:examples" "-m" "lsp-server")]
        (is (zero? (:exit r))
            (str "external Python LSP client failed\n"
                 "stderr:\n" (:err r)
                 "\nstdout:\n" (:out r)))))))

(deftest test-python-dap-client-vs-defport-server
  (testing "external Python DAP client spawns defport's DAP server and round-trips"
    (if-not (available? "python3")
      (skip "python3 not installed")
      (let [r (run-python-client (script-path "external_dap_client.py")
                                 "clojure" "-M:examples" "-m" "dap-server")]
        (is (zero? (:exit r))
            (str "external Python DAP client failed\n"
                 "stderr:\n" (:err r)
                 "\nstdout:\n" (:out r)))))))

;; ============================================================================
;; BSP: defport client → scala-cli bsp
;; ============================================================================
;;
;; scala-cli is the cleanest single-binary BSP server available on a
;; dev machine — one install, no workspace pre-configuration. `scala-cli
;; bsp` spawns a BSP 2.2 server on stdio. If defport's BSP client can
;; handshake with it and list buildTargets, the wire format is right.

(defn- write-minimal-scala-project!
  "Create a tiny scala-cli project (single .scala file with using
   directives). Returns the absolute path."
  []
  (let [dir (java.io.File/createTempFile "defport-bsp-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (spit (io/file dir "hello.scala")
          "//> using scala 3.3.1\n@main def hello(): Unit = println(\"hello\")\n")
    (.getAbsolutePath dir)))

(deftest test-bsp-real-scala-cli
  (testing "defport BSP client can connect to scala-cli bsp"
    (if-not (available? "scala-cli")
      (skip "scala-cli not installed")
      (let [project-dir (write-minimal-scala-project!)
            tx     (bsp-sub/transport ["scala-cli" "bsp" "--workspace" project-dir "."])
            client (bsp/create-client tx)
            done   (promise)]
        (try
          (bsp/connect-async!
            client
            {:display-name "defport-integration-test"
             :version      "0.1.0"
             :root-uri     (str "file://" project-dir)
             :capabilities {:languageIds ["scala"]}}
            (fn [c err] (deliver done [c err])))

          ;; scala-cli cold start can pull the Scala toolchain. 180s is generous.
          (let [[c err] (deref done 180000 [::timeout nil])]
            (is (not= ::timeout c)
                "BSP initialize handshake did not complete in 180s")
            (is (nil? err) (str "initialize error: " err))
            (when c
              (is (true? (:initialized? @(:state* c))))
              (let [caps (:server-capabilities @(:state* c))]
                (is (map? caps) "scala-cli should return a capability map"))

              ;; workspace/buildTargets is the canonical BSP read.
              (let [[body err2] (bsp/await (bsp/workspace-build-targets c))]
                (is (nil? err2) (str "workspace/buildTargets error: " err2))
                (is (sequential? (:targets body))
                    "workspace/buildTargets should return a :targets seq"))))

          (finally
            (bsp/disconnect! client)
            (letfn [(rm-rf [^java.io.File f]
                      (when (.isDirectory f)
                        (doseq [c (.listFiles f)] (rm-rf c)))
                      (.delete f))]
              (rm-rf (io/file project-dir)))))))))

(deftest test-dap-real-debugpy
  (testing "defport DAP client can connect to debugpy.adapter"
    (if-not (debugpy-available?)
      (skip "debugpy not installed — python3 -m pip install --user debugpy")
      (let [tx (dap-sub/transport ["python3" "-m" "debugpy.adapter"])
            client (dap/create-client tx)
            done (promise)]
        (try
          (dap/connect-async!
            client
            {:client-info {:name "defport-integration-test" :version "0.1.0"}
             :adapter-id "python"}
            (fn [c err] (deliver done [c err])))

          ;; Cold Python subprocess can take a few seconds. 30s is generous.
          (let [[c err] (deref done 30000 [::timeout nil])]
            (is (not= ::timeout c)
                "debugpy initialize handshake did not complete in 30s")
            (is (nil? err) (str "initialize error: " err))
            (when c
              (is (true? (:initialized? @(:state* c))))
              (let [caps (:adapter-capabilities @(:state* c))]
                (is (map? caps) "debugpy should return a capability map")
                ;; debugpy supports the standard DAP surface — some of
                ;; these flags should be present.
                (is (true? (:supportsConfigurationDoneRequest caps))
                    "debugpy should support configurationDone"))))

          (finally
            (dap/disconnect! client)))))))
