(ns lsp
  "Simple LSP servers in Clojure.

  One require, done:

    (ns my-lsp
      (:require [lsp :refer [deflsp defhandler run!]]))

    (deflsp my-server \"1.0.0\")

    (defhandler hover [uri :- :string, line :- :int, col :- :int]
      \"Get hover info\"
      (markdown \"Hello from hover!\"))

    (defhandler definition [uri :- :string, line :- :int, col :- :int]
      \"Go to definition\"
      (location uri 0 0 0 10))

    (run!)

  Progressive disclosure:

  Level 1 - Minimal:
    (deflsp demo)
    (defhandler hover [uri line col] (markdown \"Hi\"))
    (run!)

  Level 2 - Full params:
    (defhandler completion
      [uri :- :string, line :- :int, col :- :int, ctx :- :context]
      \"Get completions\"
      (completion-list [(completion-item {:label \"foo\" :kind :function})]))

  Level 3 - Raw method:
    (defmethod! \"textDocument/semanticTokens/full\"
      (fn [params ctx] ...))

  Level 4 - Client mode:
    (def client (connect! {:command [\"clojure-lsp\"]}))
    (hover-at client \"file:///foo.clj\" 10 5)
    (definition-at client \"file:///foo.clj\" 10 5)

  Level 5 - Cross-protocol registry:
    ;; In your app namespace:
    (require '[defport.core :as defport])
    (defport/register-port! {:id :analyze
                             :handler analyze-code
                             :schema [:map [:code :string]]
                             :description \"Analyze code\"})

    ;; Expose as LSP hover:
    (require '[lsp :refer [expose-port!]])
    (expose-port! :analyze :as :hover)"
  (:require [defport.core :as core]
            [defport.lsp :as lsp]
            [defport.sugar :as sugar]
            [defport.util.platform :as platform]))

;; ============================================================================
;; Internal State
;; ============================================================================

(defonce ^:private *server (atom nil))
(defonce ^:private *handlers (atom {}))
(defonce ^:private *capabilities (atom {}))
(defonce ^:private *transport (atom nil))
(defonce ^:private *running? (atom false))
(defonce ^:private *documents (atom {}))

;; ============================================================================
;; Re-exports (Type Constructors)
;; ============================================================================

;; Positions & Ranges
(def position lsp/position)
(def range- lsp/range-)
(def location lsp/location)
(def location-link lsp/location-link)

;; Content
(def markdown lsp/markdown)
(def plaintext lsp/plaintext)
(def hover lsp/hover)

;; Completions
(def completion-item lsp/completion-item)
(def completion-list lsp/completion-list)

;; Symbols
(def document-symbol lsp/document-symbol)
(def symbol-information lsp/symbol-information)
(def workspace-symbol lsp/workspace-symbol)

;; Diagnostics
(def diagnostic lsp/diagnostic)

;; Code Actions
(def code-action lsp/code-action)
(def command lsp/command)
(def text-edit lsp/text-edit)
(def workspace-edit lsp/workspace-edit)

;; Signatures
(def signature-help lsp/signature-help)
(def signature-information lsp/signature-information)
(def parameter-information lsp/parameter-information)

;; Call Hierarchy
(def call-hierarchy-item lsp/call-hierarchy-item)
(def call-hierarchy-incoming-call lsp/call-hierarchy-incoming-call)
(def call-hierarchy-outgoing-call lsp/call-hierarchy-outgoing-call)

;; URIs
(def file->uri lsp/file->uri)
(def uri->file lsp/uri->file)

;; Errors
(def error lsp/error-response)

;; ============================================================================
;; Method Constants (for reference)
;; ============================================================================

(def methods lsp/methods)

;; ============================================================================
;; Context Protocol
;; ============================================================================

(defprotocol IContext
  "Context available in handlers."
  (get-document [ctx uri] "Get open document content")
  (get-documents [ctx] "List open document URIs")
  (log [ctx level message] "Log to client")
  (notify [ctx method params] "Send notification to client"))

(defrecord Context [adapter documents* transport]
  IContext
  (get-document [_ uri]
    (when-let [doc (lsp/doc-get (:document-store adapter) uri)]
      (:content doc)))
  (get-documents [_]
    (lsp/doc-list (:document-store adapter)))
  (log [_ level message]
    (platform/eprintln (str "[" (name level) "] " message)))
  (notify [_ method params]
    ;; TODO: Send via transport
    nil))

;; ============================================================================
;; Handler Registry
;; ============================================================================

(def ^:private handler-methods
  "Map of handler keywords to LSP method strings."
  {:hover "textDocument/hover"
   :definition "textDocument/definition"
   :declaration "textDocument/declaration"
   :type-definition "textDocument/typeDefinition"
   :implementation "textDocument/implementation"
   :references "textDocument/references"
   :completion "textDocument/completion"
   :signature-help "textDocument/signatureHelp"
   :document-symbol "textDocument/documentSymbol"
   :workspace-symbol "workspace/symbol"
   :code-action "textDocument/codeAction"
   :code-lens "textDocument/codeLens"
   :formatting "textDocument/formatting"
   :range-formatting "textDocument/rangeFormatting"
   :rename "textDocument/rename"
   :prepare-rename "textDocument/prepareRename"
   :folding-range "textDocument/foldingRange"
   :selection-range "textDocument/selectionRange"
   :document-highlight "textDocument/documentHighlight"
   :document-link "textDocument/documentLink"
   :semantic-tokens "textDocument/semanticTokens/full"
   :inlay-hint "textDocument/inlayHint"
   :call-hierarchy-prepare "textDocument/prepareCallHierarchy"
   :call-hierarchy-incoming "callHierarchy/incomingCalls"
   :call-hierarchy-outgoing "callHierarchy/outgoingCalls"})

(def ^:private method->capability
  "Map of handler keyword to capability key."
  {:hover :hover
   :definition :definition
   :declaration :declaration
   :type-definition :type-definition
   :implementation :implementation
   :references :references
   :completion :completion
   :signature-help :signature-help
   :document-symbol :document-symbol
   :workspace-symbol :workspace-symbol
   :code-action :code-action
   :code-lens :code-lens
   :formatting :document-formatting
   :range-formatting :document-range-formatting
   :rename :rename
   :folding-range :folding-range
   :selection-range :selection-range
   :document-highlight :document-highlight
   :document-link :document-link
   :semantic-tokens :semantic-tokens
   :inlay-hint :inlay-hint})

;; ============================================================================
;; Param Extraction
;; ============================================================================

(defn- extract-position-params
  "Extract uri, line, col from textDocument/position params."
  [params]
  (let [uri (get-in params [:textDocument :uri])
        line (get-in params [:position :line])
        col (get-in params [:position :character])]
    {:uri uri :line line :col col}))

(defn- extract-range-params
  "Extract uri and range from params."
  [params]
  (let [uri (get-in params [:textDocument :uri])
        range (:range params)]
    {:uri uri :range range}))

(defn- wrap-handler
  "Wrap user handler to extract common params."
  [handler-key user-handler]
  (fn [params context]
    (let [base-params (case handler-key
                        (:hover :definition :declaration :type-definition
                         :implementation :references :completion
                         :signature-help :document-highlight :inlay-hint
                         :call-hierarchy-prepare)
                        (extract-position-params params)

                        (:code-action :range-formatting :selection-range)
                        (extract-range-params params)

                        (:document-symbol :formatting :code-lens
                         :folding-range :document-link :semantic-tokens)
                        {:uri (get-in params [:textDocument :uri])}

                        :workspace-symbol
                        {:query (:query params)}

                        :rename
                        (merge (extract-position-params params)
                               {:new-name (:newName params)})

                        :prepare-rename
                        (extract-position-params params)

                        (:call-hierarchy-incoming :call-hierarchy-outgoing)
                        {:item (:item params)}

                        ;; Default: pass raw params
                        params)]
      (user-handler (merge base-params {:raw params}) context))))

;; ============================================================================
;; Server Definition
;; ============================================================================

(defrecord Server [name version adapter options])

(defn server
  "Create an LSP server."
  ([name] (server name "1.0.0"))
  ([name version]
   (let [adapter (lsp/create-adapter
                  {:server-info {:name name :version version}
                   :capabilities {}})
         srv (->Server name version adapter (atom {}))]
     ;; Register default lifecycle + doc sync handlers
     (lsp/register-default-handlers! adapter)
     (reset! *server srv)
     srv)))

(defmacro deflsp
  "Define an LSP server.

  (deflsp my-server)
  (deflsp my-server \"2.0.0\")"
  ([name] `(deflsp ~name "1.0.0"))
  ([name version]
   `(def ~name (server ~(clojure.core/name name) ~version))))

;; ============================================================================
;; Handler Definition
;; ============================================================================

(defmacro defhandler
  "Define an LSP handler.

  Simple:
    (defhandler hover [uri line col]
      \"Get hover info\"
      (markdown \"Hello!\"))

  With types:
    (defhandler hover [uri :- :string, line :- :int, col :- :int]
      \"Hover handler\"
      (hover (markdown (str \"File: \" uri \" Line: \" line))))

  With context:
    (defhandler completion [uri :- :string, line :- :int, col :- :int, ctx :- :context]
      \"Completions\"
      (let [doc (get-document ctx uri)]
        (completion-list [...])))"
  [handler-name params & body]
  (let [[doc body] (sugar/extract-doc-and-body body)
        parsed (sugar/parse-params params)
        pnames (mapv :name (:params parsed))
        ctx-name (:context-name parsed)
        method-key (keyword handler-name)
        method-str (get handler-methods method-key)]
    `(let [user-fn# (fn [extracted-params# context#]
                      (let [~@(when ctx-name [ctx-name 'context#])
                            ~@(mapcat (fn [p]
                                        [(:name p) `(get extracted-params# ~(keyword (:name p)))])
                                      (:params parsed))]
                        ~@body))
           wrapped# (wrap-handler ~method-key user-fn#)]
       ;; Register in global handlers
       (swap! *handlers assoc ~method-key {:handler wrapped# :doc ~doc})
       ;; Enable capability
       (swap! *capabilities assoc ~(get method->capability method-key method-key) true)
       ;; Register on server if exists
       (when-let [srv# @*server]
         (lsp/register-method! (:adapter srv#) ~method-str wrapped#))
       ~method-key)))

(defmacro defmethod!
  "Register a raw LSP method handler.

  (defmethod! \"textDocument/semanticTokens/full\"
    (fn [params ctx]
      {:data [...]}))"
  [method-string handler]
  `(do
     (swap! *handlers assoc ~method-string {:handler ~handler :raw true})
     (when-let [srv# @*server]
       (lsp/register-method! (:adapter srv#) ~method-string ~handler))
     ~method-string))

;; ============================================================================
;; Running
;; ============================================================================

(defn build!
  "Build the server with registered handlers."
  ([] (build! @*server))
  ([srv]
   (let [adapter (:adapter srv)
         caps @*capabilities]
     ;; Update capabilities based on registered handlers
     (let [new-caps (lsp/server-capabilities
                     :text-document-sync (lsp/text-document-sync-options
                                          :open-close true
                                          :change :incremental)
                     :hover (:hover caps)
                     :definition (:definition caps)
                     :declaration (:declaration caps)
                     :type-definition (:type-definition caps)
                     :implementation (:implementation caps)
                     :references (:references caps)
                     :completion (when (:completion caps)
                                   (lsp/completion-options :trigger-characters ["." "/"]))
                     :signature-help (when (:signature-help caps)
                                       (lsp/signature-help-options :trigger-characters ["(" ","]))
                     :document-symbol (:document-symbol caps)
                     :workspace-symbol (:workspace-symbol caps)
                     :code-action (when (:code-action caps)
                                    (lsp/code-action-options))
                     :document-formatting (:document-formatting caps)
                     :document-range-formatting (:document-range-formatting caps)
                     :rename (when (:rename caps)
                               (lsp/rename-options :prepare-provider (:prepare-rename caps)))
                     :folding-range (:folding-range caps)
                     :selection-range (:selection-range caps)
                     :document-highlight (:document-highlight caps)
                     :call-hierarchy (:call-hierarchy-prepare caps)
                     :inlay-hint (:inlay-hint caps))]
       ;; Update adapter capabilities
       ;; Note: In a real implementation we'd need to mutate the adapter's capabilities
       ;; For now, store in options
       (swap! (:options srv) assoc :capabilities new-caps))
     srv)))

#?(:clj
   (defn run!
     "Run the LSP server.

     (run!)                              ; stdio
     (run! {:transport :http :port 8080})"
     ([] (run! {}))
     ([opts]
      (let [srv (or @*server (server "defport-lsp"))
            _ (build! srv)
            adapter (:adapter srv)
            transport-type (or (:transport opts) :stdio)]

        (sugar/print-startup-banner
         (:name srv) (:version srv)
         transport-type (count @*handlers) "Handlers"
         (map (fn [[k _]] {:name (name k)}) @*handlers))

        (sugar/start-transport!
         (fn [request]
           (let [method (:method request)
                 params (:params request)
                 ctx (->Context adapter (atom {}) @*transport)]
             (core/protocol-dispatch adapter method params {:context ctx})))
         {:type transport-type
          :port (or (:port opts) 8080)
          :transport-atom *transport
          :running-atom *running?})

        (platform/eprintln "LSP server ready.")
        @(promise)))))

(defn stop!
  "Stop the server."
  []
  #?(:clj (sugar/stop-transport! {:transport-atom *transport
                                  :running-atom *running?}))
  (reset! *handlers {})
  (reset! *capabilities {})
  (reset! *server nil)
  (reset! *transport nil)
  (reset! *running? false))

;; ============================================================================
;; Client Mode
;; ============================================================================

#?(:clj
   (defn connect!
     "Connect to an external LSP server.

     (def client (connect! {:command [\"clojure-lsp\"]}))
     (hover-at client \"file:///foo.clj\" 10 5)"
     [{:keys [command env dir root-uri]}]
     (let [client (lsp/create-client {:command command :env env :dir dir})]
       (lsp/initialize client (or root-uri (str "file:///" (System/getProperty "user.dir"))))
       client)))

#?(:clj
   (defn disconnect!
     "Disconnect from LSP server."
     [client]
     (lsp/client-stop client)))

;; Client convenience functions
#?(:clj
   (defn hover-at
     "Get hover at position."
     [client uri line col]
     (lsp/hover-at client uri line col)))

#?(:clj
   (defn definition-at
     "Get definition at position."
     [client uri line col]
     (lsp/definition-at client uri line col)))

#?(:clj
   (defn references-at
     "Get references at position."
     [client uri line col]
     (lsp/references-at client uri line col)))

#?(:clj
   (defn complete-at
     "Get completions at position."
     [client uri line col]
     (lsp/complete-at client uri line col)))

#?(:clj
   (defn symbols-in
     "Get symbols in document."
     [client uri]
     (lsp/symbols-in-document client uri)))

#?(:clj
   (defn search-symbols
     "Search workspace symbols."
     [client query]
     (lsp/symbols-in-workspace client query)))

;; ============================================================================
;; Cross-Protocol Registry Integration
;; ============================================================================

(defn expose-port!
  "Expose a registered port as an LSP handler.

   Takes a port ID from the global registry and exposes it as an LSP handler.
   The :as option specifies which LSP method to map to.

   Options:
   - :as - LSP handler keyword (:hover, :definition, :completion, etc.) - REQUIRED

   Supported :as values:
   - :hover - textDocument/hover
   - :definition - textDocument/definition
   - :declaration - textDocument/declaration
   - :type-definition - textDocument/typeDefinition
   - :implementation - textDocument/implementation
   - :references - textDocument/references
   - :completion - textDocument/completion
   - :signature-help - textDocument/signatureHelp
   - :document-symbol - textDocument/documentSymbol
   - :workspace-symbol - workspace/symbol
   - :code-action - textDocument/codeAction
   - :code-lens - textDocument/codeLens
   - :formatting - textDocument/formatting
   - :rename - textDocument/rename

   Example:
     ;; First register in your app:
     (defport/register-port! {:id :analyze
                              :handler analyze-code
                              :schema [:map [:code :string]]
                              :description \"Analyze code\"})

     ;; Then expose as LSP hover:
     (expose-port! :analyze :as :hover)

   The handler receives a context map with:
   - :uri, :line, :col (for position-based handlers)
   - :raw (original LSP params)
   - :context (LSP context with documents, logging, etc.)

   Returns the handler keyword or throws if port not found."
  [port-id & {:keys [as]}]
  (when-not as
    (throw (ex-info "expose-port! requires :as option for LSP (e.g., :hover, :definition)"
                    {:port-id port-id})))
  (if-let [port (core/get-registered-port port-id)]
    (let [method-key as
          method-str (get handler-methods method-key)]
      (when-not method-str
        (throw (ex-info (str "Unknown LSP handler type: " as)
                        {:as as
                         :available (keys handler-methods)})))

      (let [;; Wrap to adapt from LSP params to port context
            wrapped (wrap-handler
                     method-key
                     (fn [extracted-params context]
                       (let [result ((:handler port) {:params extracted-params
                                                      :context context
                                                      :protocol :lsp})]
                         ;; Convert result to LSP format based on handler type
                         (cond
                           ;; Hover returns markdown/plaintext
                           (= method-key :hover)
                           (if (string? result)
                             (hover (markdown result))
                             result)

                           ;; Definition returns location(s)
                           (#{:definition :declaration :type-definition :implementation} method-key)
                           result

                           ;; References returns array of locations
                           (= method-key :references)
                           result

                           ;; Completion returns completion list
                           (= method-key :completion)
                           (if (vector? result)
                             (completion-list result)
                             result)

                           ;; Document symbols
                           (= method-key :document-symbol)
                           result

                           ;; Workspace symbols
                           (= method-key :workspace-symbol)
                           result

                           ;; Default: pass through
                           :else result))))]

        ;; Register handler
        (swap! *handlers assoc method-key {:handler wrapped :doc (:description port)})
        (swap! *capabilities assoc (get method->capability method-key method-key) true)

        ;; Register on server if exists
        (when-let [srv @*server]
          (lsp/register-method! (:adapter srv) method-str wrapped))

        method-key))
    (throw (ex-info (str "Port not found in registry: " port-id)
                    {:port-id port-id
                     :available (core/list-registered-ports)}))))

;; ============================================================================
;; Introspection
;; ============================================================================

(defn list-handlers
  "List registered handlers."
  []
  (keys @*handlers))

(defn server-info
  "Get server info."
  []
  (sugar/make-server-info *server *running?
                          (fn [_]
                            {:handlers (count @*handlers)
                             :capabilities (keys @*capabilities)})))

(defn running?
  "Check if server is running."
  []
  @*running?)
