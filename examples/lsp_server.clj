(ns lsp-server
  "Minimum-viable LSP server using defport.

  Exposes hover, references, and documentSymbol over a trivial
  in-memory dictionary. ~30 lines of user code.

  Defport handles: initialize/shutdown, capability negotiation
  (auto-derived from registered ports), cancellation state,
  document sync, Content-Length framing.

  ## Run

      clojure -M:examples -m lsp-server

  ## Validate with Neovim (no extension needed)

  In any file, run:

      :lua vim.lsp.start({ \\
        name = 'defport-example', \\
        cmd = {'clojure', '-M:examples', '-m', 'lsp-server'}, \\
        root_dir = vim.fn.getcwd(), \\
      })

  Then `:lua vim.lsp.buf.hover()` should return the dictionary
  entry for whatever word your cursor is on.

  ## Validate with VS Code

  Requires a wrapper extension — use `vscode-languageclient` inside
  any existing VS Code extension shell pointing at this subprocess.
  See https://code.visualstudio.com/api/language-extensions/language-server-extension-guide"
  (:require [defport.core :as core]
            [defport.lsp :as lsp]
            [defport.sugar :as sugar]
            [defport.transports.stdio :as stdio]))

;; ----- Fake \"knowledge base\" ------------------------------------------------

(def ^:private definitions
  "A tiny stand-in for whatever your real backend would look up."
  {"foo"    {:doc "A function that does foo things."
             :uri "file:///src/foo.clj"
             :line 10}
   "bar"    {:doc "A function that does bar things."
             :uri "file:///src/bar.clj"
             :line 25}
   "hello"  {:doc "Prints a greeting."
             :uri "file:///src/hello.clj"
             :line 3}})

(defn- word-at [uri line col]
  ;; Real implementations would parse the document store. We fake it
  ;; by always returning \"foo\" so the example is self-contained.
  "foo")

;; ----- Port definitions -----------------------------------------------------

(lsp/deflsp hover
  [uri :- :string line :- :int col :- :int]
  "Show hover info at a position."
  (when-let [entry (get definitions (word-at uri line col))]
    {:contents {:kind "markdown"
                :value (:doc entry)}}))

(lsp/deflsp definition
  [uri :- :string line :- :int col :- :int]
  "Jump to the definition of the symbol at a position."
  (when-let [entry (get definitions (word-at uri line col))]
    {:uri (:uri entry)
     :range {:start {:line (:line entry) :character 0}
             :end   {:line (:line entry) :character 3}}}))

(lsp/deflsp references
  [uri :- :string line :- :int col :- :int]
  "Find references to the symbol at a position."
  (vec
    (for [[_name entry] definitions]
      {:uri (:uri entry)
       :range {:start {:line (:line entry) :character 0}
               :end   {:line (:line entry) :character 3}}})))

(lsp/deflsp document-symbol
  [uri :- :string]
  "List symbols in a document."
  (vec
    (for [[name entry] definitions]
      {:name name
       :kind 12     ;; LSP SymbolKind :Function
       :location {:uri (:uri entry)
                  :range {:start {:line (:line entry) :character 0}
                          :end   {:line (:line entry) :character 3}}}})))

;; ----- Main -----------------------------------------------------------------

(defn -main [& _]
  (let [adapter   (sugar/create-adapter :lsp
                    {:server-info {:name "defport-example-lsp"
                                   :version "0.1.0"}})
        _         (lsp/register-default-handlers! adapter)
        transport (stdio/create-stdio-transport {:drain-on-exit? true})
        handler   (fn [msg]
                    (let [result (core/protocol-dispatch adapter
                                                         (:method msg)
                                                         (:params msg)
                                                         {})]
                      (when (:id msg)
                        (merge {:jsonrpc "2.0" :id (:id msg)}
                               (if (and (map? result) (contains? result :error))
                                 {:error (:error result)}
                                 {:result result})))))]
    (core/transport-start transport handler)))
