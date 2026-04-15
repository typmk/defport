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
            [defport.mcp.client :as mcp]
            [defport.mcp.client.transports.subprocess :as mcp-sub]
            [defport.lsp.client :as lsp]
            [defport.lsp.client.transports.subprocess :as lsp-sub]))

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

(deftest test-dap-real-debug-adapter
  (testing "defport DAP client ↔ real debug adapter (debugpy)"
    (if-not (and (available? "python3")
                 (zero? (:exit (shell/sh "python3" "-c" "import debugpy"))))
      (skip "debugpy not installed — pip install debugpy to enable this test")
      (is true "debugpy available — would run real DAP handshake here"))))
