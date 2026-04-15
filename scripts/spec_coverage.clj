(ns scripts.spec-coverage
  "Programmatically compare defport's spec registries against the
  authoritative external sources shipped with the reference
  implementations:

  - LSP:  node_modules/vscode-languageserver-protocol/lib/common/*.d.ts
  - DAP:  node_modules/@vscode/debugprotocol/lib/debugProtocol.d.ts
  - MCP:  no .d.ts reference available; compared against the
          2025-11-25 spec's published method list (hardcoded).

  Run with:
      clojure -M:test -i scripts/spec_coverage.clj

  Exits 0 if coverage is 100% for every protocol, non-zero if any
  method is missing from a defport registry. Extras in defport (not
  in the official source) are reported but don't fail — defport may
  legitimately carry methods the extraction script misses (e.g.
  $/cancelRequest which the LSP reference .d.ts handles as a
  generic notification pattern)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [defport.mcp.spec :as mcp-spec]
            [defport.lsp.spec :as lsp-spec]
            [defport.dap.spec :as dap-spec]))

;; ============================================================================
;; Extractors — pull method strings from reference .d.ts files
;; ============================================================================

(def ^:private defnet-root
  "/home/hbtweb/GitHub/defnet")

(defn- extract-lsp-methods
  "Pull LSP method strings from vscode-languageserver-protocol .d.ts files."
  []
  (let [files (->> (file-seq (io/file defnet-root
                                      "node_modules"
                                      "vscode-languageserver-protocol"
                                      "lib"
                                      "common"))
                   (filter #(and (.isFile %) (.endsWith (.getName %) ".d.ts"))))
        text  (apply str (mapv slurp files))
        ;; LSP method strings live inside single/double quoted string
        ;; literals. Pull anything that looks like a namespaced method
        ;; ("foo/bar" or "foo/bar/baz") or a bare lifecycle name.
        matches (re-seq #"['\"]([a-zA-Z$][a-zA-Z$/]+)['\"]" text)
        candidates (->> matches (map second) set)
        method-re #"^(textDocument|workspace|window|notebookDocument|client|\$|callHierarchy|typeHierarchy)/|^(initialize|initialized|shutdown|exit)$"]
    (->> candidates
         (filter #(re-find method-re %))
         sort
         vec)))

(defn- extract-dap-commands
  "Pull DAP command strings from @vscode/debugprotocol/debugProtocol.d.ts."
  []
  (let [f (io/file defnet-root "node_modules" "@vscode" "debugprotocol"
                   "lib" "debugProtocol.d.ts")
        text (slurp f)
        commands (->> (re-seq #"command: '([a-zA-Z]+)'" text) (map second) set sort vec)]
    commands))

(defn- extract-dap-events
  "Pull DAP event strings from @vscode/debugprotocol/debugProtocol.d.ts."
  []
  (let [f (io/file defnet-root "node_modules" "@vscode" "debugprotocol"
                   "lib" "debugProtocol.d.ts")
        text (slurp f)
        events (->> (re-seq #"event: '([a-zA-Z]+)'" text) (map second) set sort vec)]
    events))

(defn- mcp-official-methods
  "Read the MCP 2025-11-25 schema from resources/ and extract every
   method const string. The schema ships in the repo at
   resources/mcp-schema-2025-11-25.json — fetched from
   https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/schema/2025-11-25/schema.json
   and committed so CI doesn't hit GitHub on every run."
  []
  (let [r (io/resource "mcp-schema-2025-11-25.json")
        text (slurp r)
        method-re #"\"const\"\s*:\s*\"([a-zA-Z]+(?:/[a-zA-Z/_]+)?)\""
        candidates (->> (re-seq method-re text) (map second) set)]
    (into #{}
          (filter #(or (= % "initialize")
                       (= % "ping")
                       (and (.contains ^String % "/")
                            (not (.startsWith ^String % "ref/")))))
          candidates)))

;; ============================================================================
;; Report
;; ============================================================================

(defn- diff-sets [defport-methods official-methods]
  (let [d (set defport-methods)
        o (set official-methods)]
    {:defport-count (count d)
     :official-count (count o)
     :in-both (count (set/intersection d o))
     :missing-from-defport (vec (sort (set/difference o d)))
     :extras-in-defport (vec (sort (set/difference d o)))
     :coverage-pct (when (pos? (count o))
                     (format "%.1f%%"
                             (double (* 100 (/ (count (set/intersection d o))
                                               (count o))))))}))

(defn- report-protocol [label diff]
  (println "\n===" label "===")
  (println "  official methods:" (:official-count diff))
  (println "  defport methods: " (:defport-count diff))
  (println "  coverage:        " (:coverage-pct diff))
  (when (seq (:missing-from-defport diff))
    (println "  MISSING from defport (" (count (:missing-from-defport diff)) "):")
    (doseq [m (:missing-from-defport diff)] (println "    -" m)))
  (when (seq (:extras-in-defport diff))
    (println "  extras in defport (" (count (:extras-in-defport diff)) "):")
    (doseq [m (:extras-in-defport diff)] (println "    +" m))))

(defn- main []
  (println "\nSpec coverage report\n" (java.util.Date.))

  (let [lsp-official  (extract-lsp-methods)
        lsp-defport   (map lsp-spec/wire-method (lsp-spec/all-handler-names))
        lsp-diff      (diff-sets lsp-defport lsp-official)

        dap-official-cmds   (extract-dap-commands)
        dap-defport-cmds    (map dap-spec/wire-command (dap-spec/all-command-names))
        dap-cmd-diff        (diff-sets dap-defport-cmds dap-official-cmds)

        dap-official-evts   (extract-dap-events)
        dap-defport-evts    (map dap-spec/wire-event (dap-spec/all-event-names))
        dap-evt-diff        (diff-sets dap-defport-evts dap-official-evts)

        mcp-defport   (map mcp-spec/wire-method (mcp-spec/all-method-names))
        mcp-diff      (diff-sets mcp-defport (mcp-official-methods))]

    (report-protocol "LSP 3.17 methods" lsp-diff)
    (report-protocol "DAP commands" dap-cmd-diff)
    (report-protocol "DAP events" dap-evt-diff)
    (report-protocol "MCP 2025-11-25 methods" mcp-diff)

    (let [total-missing (+ (count (:missing-from-defport lsp-diff))
                           (count (:missing-from-defport dap-cmd-diff))
                           (count (:missing-from-defport dap-evt-diff))
                           (count (:missing-from-defport mcp-diff)))]
      (println "\n=== summary ===")
      (println "  total missing across all specs:" total-missing)
      (if (zero? total-missing)
        (do (println "  PASS — defport covers 100% of every official spec it tracks")
            (System/exit 0))
        (do (println "  FAIL —" total-missing "methods missing")
            (System/exit 1))))))

(main)
