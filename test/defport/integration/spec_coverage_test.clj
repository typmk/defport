(ns defport.integration.spec-coverage-test
  "Assert defport's spec registries cover 100% of the authoritative
  external specs. Fails if any method is missing — drift breaks CI.

  The extraction functions here mirror scripts/spec_coverage.clj, so
  the test and the standalone CLI tool share the same definition of
  'official' and produce matching output."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [defport.mcp.spec :as mcp-spec]
            [defport.lsp.spec :as lsp-spec]
            [defport.dap.spec :as dap-spec]))

(def ^:private defnet-root
  "/home/hbtweb/GitHub/defnet")

(defn- lsp-official-methods []
  (let [dir (io/file defnet-root "node_modules"
                     "vscode-languageserver-protocol" "lib" "common")]
    (when (.exists dir)
      (let [text (apply str (mapv slurp
                                  (filter #(and (.isFile %)
                                                (.endsWith (.getName %) ".d.ts"))
                                          (file-seq dir))))
            matches (re-seq #"['\"]([a-zA-Z$][a-zA-Z$/]+)['\"]" text)
            candidates (->> matches (map second) set)
            method-re #"^(textDocument|workspace|window|notebookDocument|client|\$|callHierarchy|typeHierarchy)/|^(initialize|initialized|shutdown|exit)$"]
        (into #{} (filter #(re-find method-re %)) candidates)))))

(defn- dap-official-commands []
  (let [f (io/file defnet-root "node_modules" "@vscode" "debugprotocol"
                   "lib" "debugProtocol.d.ts")]
    (when (.exists f)
      (into #{} (map second) (re-seq #"command: '([a-zA-Z]+)'" (slurp f))))))

(defn- dap-official-events []
  (let [f (io/file defnet-root "node_modules" "@vscode" "debugprotocol"
                   "lib" "debugProtocol.d.ts")]
    (when (.exists f)
      (into #{} (map second) (re-seq #"event: '([a-zA-Z]+)'" (slurp f))))))

(def ^:private mcp-2025-11-25-methods
  #{"initialize" "ping"
    "tools/list" "tools/call"
    "prompts/list" "prompts/get"
    "resources/list" "resources/read" "resources/subscribe"
    "resources/unsubscribe" "resources/templates/list"
    "roots/list"
    "elicitation/create"
    "sampling/createMessage"
    "completion/complete"
    "logging/setLevel"
    "notifications/initialized" "notifications/cancelled"
    "notifications/progress" "notifications/message"
    "notifications/tools/list_changed"
    "notifications/prompts/list_changed"
    "notifications/resources/list_changed"
    "notifications/resources/updated"
    "notifications/roots/list_changed"})

(defn- assert-covers [label defport-set official-set]
  (let [missing (set/difference official-set defport-set)]
    (is (empty? missing)
        (str label " missing from defport registry: " missing))))

;; ============================================================================
;; Tests
;; ============================================================================

(deftest test-lsp-covers-official
  (testing "every LSP 3.17 method in vscode-languageserver-protocol is in defport.lsp.spec"
    (if-let [official (lsp-official-methods)]
      (let [defport-methods (into #{} (map lsp-spec/wire-method)
                                  (lsp-spec/all-handler-names))]
        (assert-covers "LSP" defport-methods official))
      (is true "vscode-languageserver-protocol not in node_modules — skipping"))))

(deftest test-dap-commands-cover-official
  (testing "every DAP command in @vscode/debugprotocol is in defport.dap.spec"
    (if-let [official (dap-official-commands)]
      (let [defport-cmds (into #{} (map dap-spec/wire-command)
                               (dap-spec/all-command-names))]
        (assert-covers "DAP command" defport-cmds official))
      (is true "@vscode/debugprotocol not in node_modules — skipping"))))

(deftest test-dap-events-cover-official
  (testing "every DAP event in @vscode/debugprotocol is in defport.dap.spec"
    (if-let [official (dap-official-events)]
      (let [defport-evts (into #{} (map dap-spec/wire-event)
                               (dap-spec/all-event-names))]
        (assert-covers "DAP event" defport-evts official))
      (is true "@vscode/debugprotocol not in node_modules — skipping"))))

(deftest test-mcp-covers-official
  (testing "every MCP 2025-11-25 method is in defport.mcp.spec"
    (let [defport-methods (into #{} (map mcp-spec/wire-method)
                                (mcp-spec/all-method-names))]
      (assert-covers "MCP" defport-methods mcp-2025-11-25-methods))))
