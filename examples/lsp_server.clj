(ns lsp-server
  "Minimum-viable LSP server using defport.

  Exposes hover, definition, references, and documentSymbol against
  a tiny in-memory dictionary. ~35 lines of user code.

  Defport handles: initialize/shutdown, capability negotiation
  (auto-derived from registered ports), cancellation state,
  document sync (didOpen/didChange/didSave/didClose),
  Content-Length framing.

  ## Run

      clojure -M:examples -m lsp-server

  ## Validate with Neovim built-in LSP (no extension needed)

  In any buffer:

      :lua vim.lsp.start({ \\
        name = 'defport-example', \\
        cmd = {'clojure', '-M:examples', '-m', 'lsp-server'}, \\
        root_dir = vim.fn.getcwd(), \\
      })

  Then `:lua vim.lsp.buf.hover()` returns the server's canned
  hover card for whatever word your cursor is on."
  (:require [defport.lsp :as lsp]
            [defport.sugar :as sugar]))

;; ----- Fake \"knowledge base\" ------------------------------------------------

(def ^:private definitions
  {"foo"   {:doc "A function that does foo things."
            :uri "file:///src/foo.clj" :line 10}
   "bar"   {:doc "A function that does bar things."
            :uri "file:///src/bar.clj" :line 25}
   "hello" {:doc "Prints a greeting."
            :uri "file:///src/hello.clj" :line 3}})

(defn- word-at [_uri _line _col]
  ;; Real implementations would parse the document store. The
  ;; example returns a constant so it's self-contained.
  "foo")

;; ----- Port definitions -----------------------------------------------------

(lsp/deflsp hover
  "Show hover info at a position."
  [uri :- :string line :- :int col :- :int]
  (when-let [entry (get definitions (word-at uri line col))]
    {:contents {:kind "markdown" :value (:doc entry)}}))

(lsp/deflsp definition
  "Jump to the definition of the symbol at a position."
  [uri :- :string line :- :int col :- :int]
  (when-let [entry (get definitions (word-at uri line col))]
    {:uri (:uri entry)
     :range {:start {:line (:line entry) :character 0}
             :end   {:line (:line entry) :character 3}}}))

(lsp/deflsp references
  "Find references to the symbol at a position."
  [uri :- :string line :- :int col :- :int]
  (vec
    (for [[_name entry] definitions]
      {:uri (:uri entry)
       :range {:start {:line (:line entry) :character 0}
               :end   {:line (:line entry) :character 3}}})))

(lsp/deflsp document-symbol
  "List symbols in a document."
  [uri :- :string]
  (vec
    (for [[name entry] definitions]
      {:name name
       :kind 12     ;; LSP SymbolKind :Function
       :location {:uri (:uri entry)
                  :range {:start {:line (:line entry) :character 0}
                          :end   {:line (:line entry) :character 3}}}})))

(defn -main [& _]
  (sugar/run! {:protocol :lsp
               :server-info {:name "defport-example-lsp"
                             :version "0.1.0"}}))
