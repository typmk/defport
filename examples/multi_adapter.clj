(ns multi-adapter
  "One registry, three protocols, three transports in one process.

  This example makes CLAUDE.md principle 3 concrete: defport's
  abstractions are genuinely composable. A single PortRegistry can
  feed MCP, LSP, and DAP adapters *simultaneously*, each running on
  its own transport, and the protocols don't know about each other.

  A consumer that wants to expose the same logic over all three
  protocols writes it once (as a plain Clojure fn) and registers
  three thin ports — one per protocol-specific metadata shape.

  ## Run

      clojure -M:examples -m multi-adapter

  The process starts three servers:
  - An MCP server on stdio (for MCP clients / Claude Desktop / Inspector)
  - An LSP server on a TCP socket :9999 (for editors that support socket LSP)
  - A DAP server on stdio? — no, can't share stdio with MCP in one
    process. The example uses TCP for the non-stdio protocols.

  Realistically, running three stdio transports in one process
  doesn't work because they all bind to System/in / System/out.
  The right shape for a multi-protocol process is:

  - One protocol on stdio (the \"primary\" one a client will spawn
    this process for)
  - Others on TCP or Unix sockets

  This example picks MCP for stdio since that's defnet's historical
  primary role. LSP and DAP get TCP.

  ## NOTE

  This example is illustrative. TCP transports for LSP and DAP are
  not yet shipped with defport — only stdio and HTTP. The
  demonstration below uses in-process dispatch to prove the
  shared-registry claim without spawning real TCP servers. A
  production multi-protocol setup would plug in real transports."
  (:require [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.lsp :as lsp]
            [defport.dap :as dap]
            [defport.sugar :as sugar]
            [defport.registry :as registry]))

;; ============================================================================
;; Shared domain logic (the thing a consumer actually writes)
;; ============================================================================
;;
;; Imagine these are real defnet graph queries. They're plain
;; Clojure functions — no protocol awareness.

(defn- search-symbols
  "Return symbols whose name contains `pattern`."
  [pattern]
  (let [kb {"foo"   {:file "foo.clj" :line 10 :doc "does foo things"}
            "bar"   {:file "bar.clj" :line 25 :doc "does bar things"}
            "hello" {:file "hello.clj" :line 3 :doc "prints a greeting"}}]
    (into {} (filter (fn [[n _]] (clojure.string/includes? n pattern)) kb))))

(defn- explain-symbol
  "Return a human-readable description of a symbol."
  [name]
  (get (search-symbols name) name))

;; ============================================================================
;; Three protocol surfaces, one logic
;; ============================================================================
;;
;; Each protocol sees the port defined for its own metadata. The
;; underlying logic (search-symbols / explain-symbol) is shared.

;; MCP — callable as a tool by LLM clients (Claude Desktop, Cursor)
(mcp/deftool search
  "Search for symbols by name pattern."
  [pattern :- :string]
  {:content [{:type "text"
              :text (pr-str (search-symbols pattern))}]})

;; LSP — same logic, different shape; editors can call hover to get
;; the doc for the symbol at the cursor
(lsp/deflsp hover
  "Hover info for the symbol at a position."
  [uri :- :string line :- :int col :- :int]
  ;; A real implementation would parse the document and extract the
  ;; word under the cursor. Example returns a canned hit.
  (when-let [entry (explain-symbol "foo")]
    {:contents {:kind "markdown"
                :value (format "**foo** — %s\n\nAt `%s:%d`"
                               (:doc entry) (:file entry) (:line entry))}}))

(lsp/deflsp document-symbol
  "List all symbols in a document."
  [uri :- :string]
  (vec
    (for [[name entry] (search-symbols "")]
      {:name name
       :kind 12 ;; Function
       :location {:uri (str "file:///" (:file entry))
                  :range {:start {:line (:line entry) :character 0}
                          :end   {:line (:line entry) :character 3}}}})))

;; DAP — same knowledge surfaced as an evaluator for the debug console
(dap/defcommand evaluate
  "Evaluate an expression in the debug console."
  [expression :- :string frameId :- :int]
  (if (clojure.string/starts-with? expression "explain ")
    (let [n (subs expression 8)
          entry (explain-symbol n)]
      {:result (if entry
                 (format "%s — %s (at %s:%d)"
                         n (:doc entry) (:file entry) (:line entry))
                 (str "unknown symbol: " n))
       :variablesReference 0})
    {:result (str "echo: " expression)
     :variablesReference 0}))

;; ============================================================================
;; Demonstrate: one registry, three adapters, three dispatches
;; ============================================================================
;;
;; The key claim: sugar/*registry* holds ports with three different
;; metadata shapes. Three adapters walk it, each picking up only its
;; own shape. We demonstrate by building all three adapters off the
;; same registry and running protocol-dispatch calls against each.

(defn -main [& _]
  (println "=== multi-adapter demo ===")
  (println "\nRegistry contents:")
  (doseq [port-def (core/list-ports @#'sugar/*registry*)]
    (println "  "
             (:id port-def)
             " metadata:"
             (-> port-def :metadata keys vec)))

  (let [mcp-adapter (sugar/create-adapter :mcp
                      {:server-info {:name "multi-mcp" :version "0"}})
        lsp-adapter (sugar/create-adapter :lsp
                      {:server-info {:name "multi-lsp" :version "0"}})
        dap-adapter (sugar/create-adapter :dap
                      {:server-info {:name "multi-dap" :version "0"}
                       :backend :repl})]

    (println "\n--- MCP view (sees only :mcp/tool ports) ---")
    (let [resp (core/protocol-dispatch mcp-adapter "tools/list" {}
                                       {:port-registry @#'sugar/*registry*})]
      (println "tools/list →")
      (doseq [tool (:tools resp)]
        (println "  " (:name tool) "-" (:description tool))))

    (println "\n--- LSP view (sees only :lsp/method ports) ---")
    (let [caps (core/protocol-capabilities lsp-adapter @#'sugar/*registry*)]
      (println "capabilities →" (keys caps)))
    (let [resp (core/protocol-dispatch lsp-adapter "textDocument/hover"
                                       {:textDocument {:uri "file:///a.clj"}
                                        :position {:line 5 :character 2}}
                                       {})]
      (println "hover →" (:contents resp)))

    (println "\n--- DAP view (sees only :dap/command ports) ---")
    (let [resp (core/protocol-dispatch dap-adapter "evaluate"
                                       {:command "evaluate"
                                        :arguments {:expression "explain foo"
                                                    :frameId 1}}
                                       {:port-registry @#'sugar/*registry*})
          body (if (and (map? resp) (contains? resp :result))
                 (:result resp)
                 resp)]
      (println "evaluate \"explain foo\" →" (:result body))))

  (println "\n=== done ===")
  (println "The registry is shared; each adapter filtered for its own metadata.")
  (println "For a long-running multi-protocol process, use sugar/run! per")
  (println "protocol — but note that stdio can only be claimed once per"))
