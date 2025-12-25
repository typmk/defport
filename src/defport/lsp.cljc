(ns defport.lsp
  "Language Server Protocol 3.17 implementation for defport.

   This namespace provides the LSP specification as composable building blocks:

   1. **Types** - LSP data structures (Position, Range, Location, TextEdit, etc.)
   2. **Capabilities** - ServerCapabilities, ClientCapabilities builders
   3. **Methods** - LSP method constants and registry
   4. **Messages** - JSON-RPC encoding with Content-Length headers
   5. **Adapters** - LspAdapter implementing ProtocolAdapter

   Applications compose these primitives to build:
   - LSP Servers (expose functionality to editors)
   - LSP Clients (connect to external language servers)
   - LSP Proxies (bridge/aggregate multiple servers)

   ## Quick Start

   ```clojure
   ;; Create LSP server adapter
   (def adapter
     (create-adapter
       {:server-info {:name \"my-server\" :version \"1.0.0\"}
        :capabilities {:hover true
                       :definition true}}))

   ;; Register method handlers
   (register-method! adapter \"textDocument/hover\"
     (fn [params context]
       {:contents {:kind \"markdown\" :value \"Hello!\"}}))

   ;; Use with defport transport
   (defport.transports.stdio/start
     (fn [msg] (protocol-dispatch adapter (:method msg) (:params msg) {})))
   ```

   ## Spec Reference
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/"
  (:require [defport.core :as core]
            [clojure.string :as str]
            #?(:clj [cheshire.core :as json])
            #?(:cljs [cljs.reader :as reader]))
  #?(:clj (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
                   [java.net URLDecoder URLEncoder]
                   [java.nio.charset StandardCharsets])))

;; =============================================================================
;; LSP Protocol Version
;; =============================================================================

(def protocol-version "3.17")

;; =============================================================================
;; Section 1: LSP Types
;; =============================================================================
;; All types from LSP 3.17 specification as plain Clojure data constructors.
;; No magic - just functions that return maps matching the spec.

;; -----------------------------------------------------------------------------
;; Basic Types
;; -----------------------------------------------------------------------------

(defn position
  "Create a Position (zero-indexed line and character).
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#position"
  [line character]
  {:line line :character character})

(defn range-
  "Create a Range (start and end positions).
   Note: Named range- to avoid conflict with clojure.core/range.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#range"
  ([start-pos end-pos]
   {:start start-pos :end end-pos})
  ([start-line start-char end-line end-char]
   {:start (position start-line start-char)
    :end (position end-line end-char)}))

(defn location
  "Create a Location (URI + Range).
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#location"
  ([uri range]
   {:uri uri :range range})
  ([uri start-line start-char end-line end-char]
   {:uri uri :range (range- start-line start-char end-line end-char)}))

(defn location-link
  "Create a LocationLink (for go-to-definition with origin selection).
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#locationLink"
  [{:keys [origin-selection-range target-uri target-range target-selection-range]}]
  (cond-> {:targetUri target-uri
           :targetRange target-range
           :targetSelectionRange (or target-selection-range target-range)}
    origin-selection-range (assoc :originSelectionRange origin-selection-range)))

;; -----------------------------------------------------------------------------
;; Text Document Types
;; -----------------------------------------------------------------------------

(defn text-document-identifier
  "Create a TextDocumentIdentifier.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocumentIdentifier"
  [uri]
  {:uri uri})

(defn versioned-text-document-identifier
  "Create a VersionedTextDocumentIdentifier.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#versionedTextDocumentIdentifier"
  [uri version]
  {:uri uri :version version})

(defn text-document-item
  "Create a TextDocumentItem (full document content).
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocumentItem"
  [uri language-id version text]
  {:uri uri
   :languageId language-id
   :version version
   :text text})

(defn text-document-position-params
  "Create TextDocumentPositionParams.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocumentPositionParams"
  ([uri line character]
   {:textDocument (text-document-identifier uri)
    :position (position line character)})
  ([uri position]
   {:textDocument (text-document-identifier uri)
    :position position}))

;; -----------------------------------------------------------------------------
;; Text Edit Types
;; -----------------------------------------------------------------------------

(defn text-edit
  "Create a TextEdit.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textEdit"
  [range new-text]
  {:range range :newText new-text})

(defn annotated-text-edit
  "Create an AnnotatedTextEdit (TextEdit with change annotation).
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#annotatedTextEdit"
  [range new-text annotation-id]
  {:range range :newText new-text :annotationId annotation-id})

(defn text-document-edit
  "Create a TextDocumentEdit.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocumentEdit"
  [text-document edits]
  {:textDocument text-document :edits edits})

;; -----------------------------------------------------------------------------
;; Workspace Edit Types
;; -----------------------------------------------------------------------------

(defn workspace-edit
  "Create a WorkspaceEdit.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workspaceEdit"
  [{:keys [changes document-changes change-annotations]}]
  (cond-> {}
    changes (assoc :changes changes)
    document-changes (assoc :documentChanges document-changes)
    change-annotations (assoc :changeAnnotations change-annotations)))

(defn create-file
  "Create a CreateFile operation.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#createFile"
  [uri & {:keys [overwrite ignore-if-exists annotation-id]}]
  (cond-> {:kind "create" :uri uri}
    (some? overwrite) (assoc-in [:options :overwrite] overwrite)
    (some? ignore-if-exists) (assoc-in [:options :ignoreIfExists] ignore-if-exists)
    annotation-id (assoc :annotationId annotation-id)))

(defn rename-file
  "Create a RenameFile operation.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#renameFile"
  [old-uri new-uri & {:keys [overwrite ignore-if-exists annotation-id]}]
  (cond-> {:kind "rename" :oldUri old-uri :newUri new-uri}
    (some? overwrite) (assoc-in [:options :overwrite] overwrite)
    (some? ignore-if-exists) (assoc-in [:options :ignoreIfExists] ignore-if-exists)
    annotation-id (assoc :annotationId annotation-id)))

(defn delete-file
  "Create a DeleteFile operation.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#deleteFile"
  [uri & {:keys [recursive ignore-if-not-exists annotation-id]}]
  (cond-> {:kind "delete" :uri uri}
    (some? recursive) (assoc-in [:options :recursive] recursive)
    (some? ignore-if-not-exists) (assoc-in [:options :ignoreIfNotExists] ignore-if-not-exists)
    annotation-id (assoc :annotationId annotation-id)))

;; -----------------------------------------------------------------------------
;; Diagnostic Types
;; -----------------------------------------------------------------------------

(def diagnostic-severity
  "DiagnosticSeverity enum values.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#diagnosticSeverity"
  {:error 1
   :warning 2
   :information 3
   :hint 4})

(def diagnostic-tag
  "DiagnosticTag enum values.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#diagnosticTag"
  {:unnecessary 1
   :deprecated 2})

(defn diagnostic
  "Create a Diagnostic.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#diagnostic"
  [{:keys [range message severity code code-description source tags
           related-information data]}]
  (cond-> {:range range :message message}
    severity (assoc :severity (if (keyword? severity)
                                (get diagnostic-severity severity)
                                severity))
    code (assoc :code code)
    code-description (assoc :codeDescription code-description)
    source (assoc :source source)
    tags (assoc :tags (mapv #(if (keyword? %) (get diagnostic-tag %) %) tags))
    related-information (assoc :relatedInformation related-information)
    data (assoc :data data)))

(defn diagnostic-related-information
  "Create DiagnosticRelatedInformation.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#diagnosticRelatedInformation"
  [location message]
  {:location location :message message})

;; -----------------------------------------------------------------------------
;; Completion Types
;; -----------------------------------------------------------------------------

(def completion-item-kind
  "CompletionItemKind enum values.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#completionItemKind"
  {:text 1
   :method 2
   :function 3
   :constructor 4
   :field 5
   :variable 6
   :class 7
   :interface 8
   :module 9
   :property 10
   :unit 11
   :value 12
   :enum 13
   :keyword 14
   :snippet 15
   :color 16
   :file 17
   :reference 18
   :folder 19
   :enum-member 20
   :constant 21
   :struct 22
   :event 23
   :operator 24
   :type-parameter 25})

(def insert-text-format
  "InsertTextFormat enum values."
  {:plain-text 1
   :snippet 2})

(defn completion-item
  "Create a CompletionItem.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#completionItem"
  [{:keys [label label-details kind tags detail documentation deprecated
           preselect sort-text filter-text insert-text insert-text-format
           insert-text-mode text-edit text-edit-text additional-text-edits
           commit-characters command data]}]
  (cond-> {:label label}
    label-details (assoc :labelDetails label-details)
    kind (assoc :kind (if (keyword? kind) (get completion-item-kind kind) kind))
    tags (assoc :tags tags)
    detail (assoc :detail detail)
    documentation (assoc :documentation documentation)
    deprecated (assoc :deprecated deprecated)
    preselect (assoc :preselect preselect)
    sort-text (assoc :sortText sort-text)
    filter-text (assoc :filterText filter-text)
    insert-text (assoc :insertText insert-text)
    insert-text-format (assoc :insertTextFormat
                              (if (keyword? insert-text-format)
                                (get insert-text-format insert-text-format)
                                insert-text-format))
    insert-text-mode (assoc :insertTextMode insert-text-mode)
    text-edit (assoc :textEdit text-edit)
    text-edit-text (assoc :textEditText text-edit-text)
    additional-text-edits (assoc :additionalTextEdits additional-text-edits)
    commit-characters (assoc :commitCharacters commit-characters)
    command (assoc :command command)
    data (assoc :data data)))

(defn completion-list
  "Create a CompletionList.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#completionList"
  [items & {:keys [is-incomplete item-defaults]}]
  (cond-> {:isIncomplete (boolean is-incomplete) :items items}
    item-defaults (assoc :itemDefaults item-defaults)))

;; -----------------------------------------------------------------------------
;; Symbol Types
;; -----------------------------------------------------------------------------

(def symbol-kind
  "SymbolKind enum values.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#symbolKind"
  {:file 1
   :module 2
   :namespace 3
   :package 4
   :class 5
   :method 6
   :property 7
   :field 8
   :constructor 9
   :enum 10
   :interface 11
   :function 12
   :variable 13
   :constant 14
   :string 15
   :number 16
   :boolean 17
   :array 18
   :object 19
   :key 20
   :null 21
   :enum-member 22
   :struct 23
   :event 24
   :operator 25
   :type-parameter 26})

(def symbol-tag
  "SymbolTag enum values."
  {:deprecated 1})

(defn document-symbol
  "Create a DocumentSymbol (hierarchical).
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#documentSymbol"
  [{:keys [name detail kind tags deprecated range selection-range children]}]
  (cond-> {:name name
           :kind (if (keyword? kind) (get symbol-kind kind) kind)
           :range range
           :selectionRange selection-range}
    detail (assoc :detail detail)
    tags (assoc :tags (mapv #(if (keyword? %) (get symbol-tag %) %) tags))
    deprecated (assoc :deprecated deprecated)
    children (assoc :children children)))

(defn symbol-information
  "Create a SymbolInformation (flat).
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#symbolInformation"
  [{:keys [name kind tags deprecated location container-name]}]
  (cond-> {:name name
           :kind (if (keyword? kind) (get symbol-kind kind) kind)
           :location location}
    tags (assoc :tags (mapv #(if (keyword? %) (get symbol-tag %) %) tags))
    deprecated (assoc :deprecated deprecated)
    container-name (assoc :containerName container-name)))

(defn workspace-symbol
  "Create a WorkspaceSymbol.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workspaceSymbol"
  [{:keys [name kind tags container-name location data]}]
  (cond-> {:name name
           :kind (if (keyword? kind) (get symbol-kind kind) kind)}
    tags (assoc :tags tags)
    container-name (assoc :containerName container-name)
    location (assoc :location location)
    data (assoc :data data)))

;; -----------------------------------------------------------------------------
;; Code Action Types
;; -----------------------------------------------------------------------------

(def code-action-kind
  "CodeActionKind constants.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#codeActionKind"
  {:empty ""
   :quick-fix "quickfix"
   :refactor "refactor"
   :refactor-extract "refactor.extract"
   :refactor-inline "refactor.inline"
   :refactor-rewrite "refactor.rewrite"
   :source "source"
   :source-organize-imports "source.organizeImports"
   :source-fix-all "source.fixAll"})

(defn code-action
  "Create a CodeAction.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#codeAction"
  [{:keys [title kind diagnostics is-preferred disabled edit command data]}]
  (cond-> {:title title}
    kind (assoc :kind (if (keyword? kind) (get code-action-kind kind kind) kind))
    diagnostics (assoc :diagnostics diagnostics)
    is-preferred (assoc :isPreferred is-preferred)
    disabled (assoc :disabled disabled)
    edit (assoc :edit edit)
    command (assoc :command command)
    data (assoc :data data)))

(defn command
  "Create a Command.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#command"
  [title command-id & args]
  (cond-> {:title title :command command-id}
    (seq args) (assoc :arguments (vec args))))

;; -----------------------------------------------------------------------------
;; Hover Types
;; -----------------------------------------------------------------------------

(defn markup-content
  "Create MarkupContent.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#markupContent"
  [kind value]
  {:kind kind :value value})

(defn markdown
  "Create markdown MarkupContent."
  [value]
  (markup-content "markdown" value))

(defn plaintext
  "Create plaintext MarkupContent."
  [value]
  (markup-content "plaintext" value))

(defn hover
  "Create a Hover response.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#hover"
  ([contents]
   {:contents contents})
  ([contents range]
   {:contents contents :range range}))

;; -----------------------------------------------------------------------------
;; Signature Help Types
;; -----------------------------------------------------------------------------

(defn parameter-information
  "Create ParameterInformation.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#parameterInformation"
  [label & {:keys [documentation]}]
  (cond-> {:label label}
    documentation (assoc :documentation documentation)))

(defn signature-information
  "Create SignatureInformation.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#signatureInformation"
  [label & {:keys [documentation parameters active-parameter]}]
  (cond-> {:label label}
    documentation (assoc :documentation documentation)
    parameters (assoc :parameters parameters)
    active-parameter (assoc :activeParameter active-parameter)))

(defn signature-help
  "Create SignatureHelp.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#signatureHelp"
  [signatures & {:keys [active-signature active-parameter]}]
  (cond-> {:signatures signatures}
    active-signature (assoc :activeSignature active-signature)
    active-parameter (assoc :activeParameter active-parameter)))

;; -----------------------------------------------------------------------------
;; Call Hierarchy Types
;; -----------------------------------------------------------------------------

(defn call-hierarchy-item
  "Create a CallHierarchyItem.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#callHierarchyItem"
  [{:keys [name kind tags detail uri range selection-range data]}]
  (cond-> {:name name
           :kind (if (keyword? kind) (get symbol-kind kind) kind)
           :uri uri
           :range range
           :selectionRange selection-range}
    tags (assoc :tags tags)
    detail (assoc :detail detail)
    data (assoc :data data)))

(defn call-hierarchy-incoming-call
  "Create a CallHierarchyIncomingCall.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#callHierarchyIncomingCall"
  [from from-ranges]
  {:from from :fromRanges from-ranges})

(defn call-hierarchy-outgoing-call
  "Create a CallHierarchyOutgoingCall.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#callHierarchyOutgoingCall"
  [to from-ranges]
  {:to to :fromRanges from-ranges})

;; -----------------------------------------------------------------------------
;; Semantic Tokens Types
;; -----------------------------------------------------------------------------

(def semantic-token-types
  "Standard semantic token types.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#semanticTokenTypes"
  ["namespace" "type" "class" "enum" "interface" "struct" "typeParameter"
   "parameter" "variable" "property" "enumMember" "event" "function"
   "method" "macro" "keyword" "modifier" "comment" "string" "number"
   "regexp" "operator" "decorator"])

(def semantic-token-modifiers
  "Standard semantic token modifiers.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#semanticTokenModifiers"
  ["declaration" "definition" "readonly" "static" "deprecated" "abstract"
   "async" "modification" "documentation" "defaultLibrary"])

(defn semantic-tokens-legend
  "Create a SemanticTokensLegend."
  [& {:keys [token-types token-modifiers]}]
  {:tokenTypes (or token-types semantic-token-types)
   :tokenModifiers (or token-modifiers semantic-token-modifiers)})

;; =============================================================================
;; Section 2: LSP Error Codes
;; =============================================================================

(def error-codes
  "LSP and JSON-RPC error codes.
   https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#errorCodes"
  {;; JSON-RPC standard errors
   :parse-error -32700
   :invalid-request -32600
   :method-not-found -32601
   :invalid-params -32602
   :internal-error -32603

   ;; LSP reserved errors
   :server-not-initialized -32002
   :unknown-error-code -32001

   ;; LSP request errors
   :request-failed -32803
   :server-cancelled -32802
   :content-modified -32801
   :request-cancelled -32800})

(defn error-response
  "Create a JSON-RPC error response."
  [code message & {:keys [data]}]
  (cond-> {:code (if (keyword? code) (get error-codes code code) code)
           :message message}
    data (assoc :data data)))

;; =============================================================================
;; Section 3: LSP Methods
;; =============================================================================
;; All LSP 3.17 method names as constants, organized by category.

(def methods
  "All LSP 3.17 method names organized by category."
  {;; Lifecycle
   :lifecycle
   {:initialize "initialize"
    :initialized "initialized"
    :shutdown "shutdown"
    :exit "exit"
    :set-trace "$/setTrace"
    :log-trace "$/logTrace"
    :cancel-request "$/cancelRequest"
    :progress "$/progress"}

   ;; Window
   :window
   {:show-message "window/showMessage"
    :show-message-request "window/showMessageRequest"
    :show-document "window/showDocument"
    :log-message "window/logMessage"
    :work-done-progress-create "window/workDoneProgress/create"
    :work-done-progress-cancel "window/workDoneProgress/cancel"}

   ;; Telemetry
   :telemetry
   {:event "telemetry/event"}

   ;; Client
   :client
   {:register-capability "client/registerCapability"
    :unregister-capability "client/unregisterCapability"}

   ;; Workspace
   :workspace
   {:workspace-folders "workspace/workspaceFolders"
    :did-change-workspace-folders "workspace/didChangeWorkspaceFolders"
    :configuration "workspace/configuration"
    :did-change-configuration "workspace/didChangeConfiguration"
    :did-change-watched-files "workspace/didChangeWatchedFiles"
    :symbol "workspace/symbol"
    :symbol-resolve "workspaceSymbol/resolve"
    :execute-command "workspace/executeCommand"
    :apply-edit "workspace/applyEdit"
    :will-create-files "workspace/willCreateFiles"
    :did-create-files "workspace/didCreateFiles"
    :will-rename-files "workspace/willRenameFiles"
    :did-rename-files "workspace/didRenameFiles"
    :will-delete-files "workspace/willDeleteFiles"
    :did-delete-files "workspace/didDeleteFiles"}

   ;; Text Document Synchronization
   :text-sync
   {:did-open "textDocument/didOpen"
    :did-change "textDocument/didChange"
    :will-save "textDocument/willSave"
    :will-save-wait-until "textDocument/willSaveWaitUntil"
    :did-save "textDocument/didSave"
    :did-close "textDocument/didClose"}

   ;; Language Features
   :language
   {:completion "textDocument/completion"
    :completion-resolve "completionItem/resolve"
    :hover "textDocument/hover"
    :signature-help "textDocument/signatureHelp"
    :declaration "textDocument/declaration"
    :definition "textDocument/definition"
    :type-definition "textDocument/typeDefinition"
    :implementation "textDocument/implementation"
    :references "textDocument/references"
    :document-highlight "textDocument/documentHighlight"
    :document-symbol "textDocument/documentSymbol"
    :code-action "textDocument/codeAction"
    :code-action-resolve "codeAction/resolve"
    :code-lens "textDocument/codeLens"
    :code-lens-resolve "codeLens/resolve"
    :code-lens-refresh "workspace/codeLens/refresh"
    :document-link "textDocument/documentLink"
    :document-link-resolve "documentLink/resolve"
    :document-color "textDocument/documentColor"
    :color-presentation "textDocument/colorPresentation"
    :formatting "textDocument/formatting"
    :range-formatting "textDocument/rangeFormatting"
    :on-type-formatting "textDocument/onTypeFormatting"
    :rename "textDocument/rename"
    :prepare-rename "textDocument/prepareRename"
    :folding-range "textDocument/foldingRange"
    :selection-range "textDocument/selectionRange"
    :prepare-call-hierarchy "textDocument/prepareCallHierarchy"
    :call-hierarchy-incoming "callHierarchy/incomingCalls"
    :call-hierarchy-outgoing "callHierarchy/outgoingCalls"
    :prepare-type-hierarchy "textDocument/prepareTypeHierarchy"
    :type-hierarchy-supertypes "typeHierarchy/supertypes"
    :type-hierarchy-subtypes "typeHierarchy/subtypes"
    :document-semantic-tokens-full "textDocument/semanticTokens/full"
    :document-semantic-tokens-full-delta "textDocument/semanticTokens/full/delta"
    :document-semantic-tokens-range "textDocument/semanticTokens/range"
    :semantic-tokens-refresh "workspace/semanticTokens/refresh"
    :linked-editing-range "textDocument/linkedEditingRange"
    :moniker "textDocument/moniker"
    :inlay-hint "textDocument/inlayHint"
    :inlay-hint-resolve "inlayHint/resolve"
    :inlay-hint-refresh "workspace/inlayHint/refresh"
    :inline-value "textDocument/inlineValue"
    :inline-value-refresh "workspace/inlineValue/refresh"
    :diagnostic "textDocument/diagnostic"
    :workspace-diagnostic "workspace/diagnostic"
    :diagnostic-refresh "workspace/diagnostic/refresh"}

   ;; Notebook Document (LSP 3.17)
   :notebook
   {:did-open "notebookDocument/didOpen"
    :did-change "notebookDocument/didChange"
    :did-save "notebookDocument/didSave"
    :did-close "notebookDocument/didClose"}})

(defn method-name
  "Get the LSP method string for a category and method keyword.
   Example: (method-name :language :definition) => \"textDocument/definition\""
  [category method-key]
  (get-in methods [category method-key]))

;; Convenience aliases for common methods
(def m:initialize (method-name :lifecycle :initialize))
(def m:initialized (method-name :lifecycle :initialized))
(def m:shutdown (method-name :lifecycle :shutdown))
(def m:exit (method-name :lifecycle :exit))
(def m:did-open (method-name :text-sync :did-open))
(def m:did-change (method-name :text-sync :did-change))
(def m:did-close (method-name :text-sync :did-close))
(def m:completion (method-name :language :completion))
(def m:hover (method-name :language :hover))
(def m:definition (method-name :language :definition))
(def m:references (method-name :language :references))
(def m:document-symbol (method-name :language :document-symbol))
(def m:workspace-symbol (method-name :workspace :symbol))
(def m:code-action (method-name :language :code-action))
(def m:formatting (method-name :language :formatting))
(def m:rename (method-name :language :rename))

;; =============================================================================
;; Section 4: Capabilities
;; =============================================================================
;; Builders for ServerCapabilities and ClientCapabilities

(def text-document-sync-kind
  "TextDocumentSyncKind enum values."
  {:none 0
   :full 1
   :incremental 2})

(defn text-document-sync-options
  "Create TextDocumentSyncOptions."
  [& {:keys [open-close change will-save will-save-wait-until save]}]
  (cond-> {}
    (some? open-close) (assoc :openClose open-close)
    change (assoc :change (if (keyword? change)
                            (get text-document-sync-kind change)
                            change))
    (some? will-save) (assoc :willSave will-save)
    (some? will-save-wait-until) (assoc :willSaveWaitUntil will-save-wait-until)
    save (assoc :save (if (boolean? save)
                        save
                        {:includeText (:include-text save false)}))))

(defn completion-options
  "Create CompletionOptions."
  [& {:keys [trigger-characters all-commit-characters resolve-provider
             work-done-progress]}]
  (cond-> {}
    trigger-characters (assoc :triggerCharacters trigger-characters)
    all-commit-characters (assoc :allCommitCharacters all-commit-characters)
    (some? resolve-provider) (assoc :resolveProvider resolve-provider)
    (some? work-done-progress) (assoc :workDoneProgress work-done-progress)))

(defn signature-help-options
  "Create SignatureHelpOptions."
  [& {:keys [trigger-characters retrigger-characters work-done-progress]}]
  (cond-> {}
    trigger-characters (assoc :triggerCharacters trigger-characters)
    retrigger-characters (assoc :retriggerCharacters retrigger-characters)
    (some? work-done-progress) (assoc :workDoneProgress work-done-progress)))

(defn code-action-options
  "Create CodeActionOptions."
  [& {:keys [code-action-kinds resolve-provider work-done-progress]}]
  (cond-> {}
    code-action-kinds (assoc :codeActionKinds
                             (mapv #(if (keyword? %)
                                      (get code-action-kind % %)
                                      %)
                                   code-action-kinds))
    (some? resolve-provider) (assoc :resolveProvider resolve-provider)
    (some? work-done-progress) (assoc :workDoneProgress work-done-progress)))

(defn rename-options
  "Create RenameOptions."
  [& {:keys [prepare-provider work-done-progress]}]
  (cond-> {}
    (some? prepare-provider) (assoc :prepareProvider prepare-provider)
    (some? work-done-progress) (assoc :workDoneProgress work-done-progress)))

(defn semantic-tokens-options
  "Create SemanticTokensOptions."
  [legend & {:keys [range full work-done-progress]}]
  (cond-> {:legend legend}
    (some? range) (assoc :range range)
    full (assoc :full full)
    (some? work-done-progress) (assoc :workDoneProgress work-done-progress)))

(defn server-capabilities
  "Build ServerCapabilities.
   Pass capability options or true/false for simple capabilities.

   Example:
   (server-capabilities
     :text-document-sync (text-document-sync-options :open-close true :change :incremental)
     :hover true
     :completion (completion-options :trigger-characters [\".\"])
     :definition true
     :references true)"
  [& {:keys [text-document-sync completion hover signature-help declaration
             definition type-definition implementation references
             document-highlight document-symbol code-action code-lens
             document-link color-provider document-formatting
             document-range-formatting document-on-type-formatting
             rename folding-range selection-range execute-command
             workspace-symbol workspace call-hierarchy semantic-tokens
             moniker linked-editing-range type-hierarchy inline-value
             inlay-hint diagnostic position-encoding general]
      :as opts}]
  (let [->opt (fn [v]
                (cond
                  (nil? v) nil
                  (false? v) nil
                  (true? v) {}
                  :else v))]
    (cond-> {}
      text-document-sync (assoc :textDocumentSync text-document-sync)
      (->opt completion) (assoc :completionProvider (->opt completion))
      (->opt hover) (assoc :hoverProvider (->opt hover))
      (->opt signature-help) (assoc :signatureHelpProvider (->opt signature-help))
      (->opt declaration) (assoc :declarationProvider (->opt declaration))
      (->opt definition) (assoc :definitionProvider (->opt definition))
      (->opt type-definition) (assoc :typeDefinitionProvider (->opt type-definition))
      (->opt implementation) (assoc :implementationProvider (->opt implementation))
      (->opt references) (assoc :referencesProvider (->opt references))
      (->opt document-highlight) (assoc :documentHighlightProvider (->opt document-highlight))
      (->opt document-symbol) (assoc :documentSymbolProvider (->opt document-symbol))
      (->opt code-action) (assoc :codeActionProvider (->opt code-action))
      (->opt code-lens) (assoc :codeLensProvider (->opt code-lens))
      (->opt document-link) (assoc :documentLinkProvider (->opt document-link))
      (->opt color-provider) (assoc :colorProvider (->opt color-provider))
      (->opt document-formatting) (assoc :documentFormattingProvider (->opt document-formatting))
      (->opt document-range-formatting) (assoc :documentRangeFormattingProvider (->opt document-range-formatting))
      (->opt document-on-type-formatting) (assoc :documentOnTypeFormattingProvider (->opt document-on-type-formatting))
      (->opt rename) (assoc :renameProvider (->opt rename))
      (->opt folding-range) (assoc :foldingRangeProvider (->opt folding-range))
      (->opt selection-range) (assoc :selectionRangeProvider (->opt selection-range))
      (->opt execute-command) (assoc :executeCommandProvider (->opt execute-command))
      (->opt workspace-symbol) (assoc :workspaceSymbolProvider (->opt workspace-symbol))
      workspace (assoc :workspace workspace)
      (->opt call-hierarchy) (assoc :callHierarchyProvider (->opt call-hierarchy))
      (->opt semantic-tokens) (assoc :semanticTokensProvider (->opt semantic-tokens))
      (->opt moniker) (assoc :monikerProvider (->opt moniker))
      (->opt linked-editing-range) (assoc :linkedEditingRangeProvider (->opt linked-editing-range))
      (->opt type-hierarchy) (assoc :typeHierarchyProvider (->opt type-hierarchy))
      (->opt inline-value) (assoc :inlineValueProvider (->opt inline-value))
      (->opt inlay-hint) (assoc :inlayHintProvider (->opt inlay-hint))
      (->opt diagnostic) (assoc :diagnosticProvider (->opt diagnostic))
      position-encoding (assoc :positionEncoding position-encoding)
      general (assoc :general general))))

(defn client-capabilities
  "Build ClientCapabilities for connecting to LSP servers.
   Returns a minimal but functional set of client capabilities."
  [& {:keys [workspace text-document window general experimental]}]
  (cond-> {}
    workspace (assoc :workspace workspace)
    text-document (assoc :textDocument text-document)
    window (assoc :window window)
    general (assoc :general general)
    experimental (assoc :experimental experimental)))

(def default-client-capabilities
  "Minimal client capabilities for basic LSP client functionality."
  (client-capabilities
   :text-document
   {:synchronization {:dynamicRegistration false
                      :willSave false
                      :willSaveWaitUntil false
                      :didSave true}
    :completion {:dynamicRegistration false
                 :completionItem {:snippetSupport false
                                  :deprecatedSupport true
                                  :preselectSupport true}}
    :hover {:dynamicRegistration false
            :contentFormat ["markdown" "plaintext"]}
    :signatureHelp {:dynamicRegistration false}
    :definition {:dynamicRegistration false}
    :references {:dynamicRegistration false}
    :documentSymbol {:dynamicRegistration false}
    :codeAction {:dynamicRegistration false}
    :rename {:dynamicRegistration false
             :prepareSupport true}}
   :workspace
   {:workspaceFolders false
    :symbol {:dynamicRegistration false}}))

;; =============================================================================
;; Section 5: JSON-RPC Messages
;; =============================================================================

(defn request-message
  "Create a JSON-RPC request message."
  [id method params]
  (cond-> {:jsonrpc "2.0" :id id :method method}
    params (assoc :params params)))

(defn response-message
  "Create a JSON-RPC response message."
  ([id result]
   {:jsonrpc "2.0" :id id :result result})
  ([id result error]
   (if error
     {:jsonrpc "2.0" :id id :error error}
     {:jsonrpc "2.0" :id id :result result})))

(defn notification-message
  "Create a JSON-RPC notification message (no id, no response expected)."
  [method params]
  (cond-> {:jsonrpc "2.0" :method method}
    params (assoc :params params)))

(defn encode-lsp-message
  "Encode message with LSP Content-Length header."
  [msg]
  (let [json-str #?(:clj (json/generate-string msg)
                    :cljs (js/JSON.stringify (clj->js msg)))
        byte-length #?(:clj (count (.getBytes ^String json-str "UTF-8"))
                       :cljs (.-length json-str))]
    (str "Content-Length: " byte-length "\r\n\r\n" json-str)))

(defn parse-lsp-headers
  "Parse LSP message headers into a map."
  [header-str]
  (into {}
        (for [line (clojure.string/split-lines header-str)
              :let [[_ k v] (re-matches #"([^:]+):\s*(.+)" line)]
              :when k]
          [(clojure.string/lower-case k) (clojure.string/trim v)])))

#?(:clj
   (defn read-lsp-message
     "Read a single LSP message from BufferedReader.
      Returns parsed JSON or nil on EOF/error."
     [^BufferedReader reader]
     (try
       (loop [headers []]
         (when-let [line (.readLine reader)]
           (if (= line "")
             ;; Headers complete
             (let [header-map (parse-lsp-headers (clojure.string/join "\n" headers))
                   content-length (some-> (get header-map "content-length")
                                          Long/parseLong)]
               (when content-length
                 (let [buffer (char-array content-length)
                       chars-read (.read reader buffer 0 content-length)]
                   (when (= chars-read content-length)
                     (json/parse-string (String. buffer) true)))))
             ;; Continue reading headers
             (recur (conj headers line)))))
       (catch Exception e
         (tap> {:event :lsp/read-error :error (.getMessage e)})
         nil))))

#?(:clj
   (defn write-lsp-message
     "Write an LSP message to BufferedWriter."
     [^BufferedWriter writer msg]
     (let [encoded (encode-lsp-message msg)]
       (locking writer
         (.write writer encoded)
         (.flush writer)))))

;; =============================================================================
;; Section 6: URI Utilities
;; =============================================================================

(defn file->uri
  "Convert file path to file:// URI."
  [path]
  (when path
    #?(:clj
       (let [normalized (-> path
                            (.replace "\\" "/")
                            (.replaceFirst "^/" ""))]
         (str "file:///" (URLEncoder/encode normalized StandardCharsets/UTF_8)
              ;; URLEncoder encodes / as %2F, we need to restore them
              (.replace "%2F" "/")))
       :cljs
       (str "file:///" path))))

(defn uri->file
  "Convert file:// URI to file path."
  [uri]
  (when uri
    (-> uri
        (clojure.string/replace #"^file:///" "")
        (clojure.string/replace #"^file://" "")
        #?(:clj (URLDecoder/decode StandardCharsets/UTF_8)
           :cljs identity))))

;; =============================================================================
;; Section 7: Method Registry
;; =============================================================================
;; Allows applications to register handlers for LSP methods.

(defprotocol MethodRegistry
  "Registry for LSP method handlers."
  (register-method [this method handler]
    "Register a handler function for an LSP method.
     Handler signature: (fn [params context] result)")
  (unregister-method [this method]
    "Remove handler for an LSP method.")
  (get-handler [this method]
    "Get registered handler for a method.")
  (list-methods [this]
    "List all registered methods."))

(defrecord AtomMethodRegistry [handlers*]
  MethodRegistry
  (register-method [_ method handler]
    (swap! handlers* assoc method handler))
  (unregister-method [_ method]
    (swap! handlers* dissoc method))
  (get-handler [_ method]
    (get @handlers* method))
  (list-methods [_]
    (keys @handlers*)))

(defn create-method-registry
  "Create a new method registry."
  []
  (->AtomMethodRegistry (atom {})))

;; =============================================================================
;; Section 8: Document Store
;; =============================================================================
;; Stateful document synchronization for LSP server mode.

(defprotocol DocumentStore
  "Manages synchronized document state."
  (doc-open [this uri content version language-id]
    "Track a newly opened document.")
  (doc-change [this uri changes version]
    "Apply changes to document.")
  (doc-close [this uri]
    "Stop tracking document.")
  (doc-get [this uri]
    "Get document by URI.")
  (doc-list [this]
    "List all tracked document URIs."))

(defn apply-text-edit
  "Apply a single TextEdit to content string."
  [content {:keys [range text newText]}]
  (let [new-text (or newText text "")
        {:keys [start end]} range]
    (if (and start end)
      (let [lines (vec (clojure.string/split content #"\n" -1))
            start-line (:line start)
            start-char (:character start)
            end-line (:line end)
            end-char (:character end)]
        (if (and (< start-line (count lines))
                 (<= end-line (count lines)))
          (let [before-text (subs (get lines start-line "")
                                  0 (min start-char (count (get lines start-line ""))))
                after-text (subs (get lines end-line "")
                                 (min end-char (count (get lines end-line ""))))
                new-content-lines (clojure.string/split new-text #"\n" -1)
                result-lines (vec (concat
                                   (take start-line lines)
                                   [(str before-text (first new-content-lines))]
                                   (rest (butlast new-content-lines))
                                   (when (> (count new-content-lines) 1)
                                     [(str (last new-content-lines) after-text)])
                                   (when (<= (count new-content-lines) 1)
                                     [(str (first new-content-lines) after-text)])
                                   (drop (inc end-line) lines)))]
            (clojure.string/join "\n" (take (+ (count lines)
                                               (- (count new-content-lines) 1)
                                               (- start-line end-line))
                                            result-lines)))
          content))
      ;; Full document replacement
      new-text)))

(defn apply-content-changes
  "Apply a sequence of content changes to document."
  [content changes]
  (reduce
   (fn [c change]
     (if (:range change)
       (apply-text-edit c change)
       ;; Full sync
       (or (:text change) c)))
   content
   changes))

(defrecord InMemoryDocumentStore [documents*]
  DocumentStore
  (doc-open [_ uri content version language-id]
    (swap! documents* assoc uri
           {:uri uri
            :content content
            :version version
            :languageId language-id
            :openedAt #?(:clj (System/currentTimeMillis)
                         :cljs (.now js/Date))}))

  (doc-change [_ uri changes version]
    (swap! documents* update uri
           (fn [doc]
             (when doc
               (-> doc
                   (assoc :version version)
                   (update :content apply-content-changes changes))))))

  (doc-close [_ uri]
    (swap! documents* dissoc uri))

  (doc-get [_ uri]
    (get @documents* uri))

  (doc-list [_]
    (keys @documents*)))

(defn create-document-store
  "Create an in-memory document store."
  []
  (->InMemoryDocumentStore (atom {})))

;; =============================================================================
;; Section 9: LSP Adapter (ProtocolAdapter implementation)
;; =============================================================================

(defrecord LspAdapter [server-info
                       capabilities
                       method-registry
                       document-store
                       state*]
  core/ProtocolAdapter
  (protocol-id [_] :lsp)

  (protocol-version [_] protocol-version)

  (protocol-capabilities [_ _port-registry]
    capabilities)

  (protocol-dispatch [this method params context]
    (let [handler (get-handler method-registry method)
          ctx (assoc context
                     :adapter this
                     :document-store document-store)]
      (if handler
        (try
          (let [result (handler params ctx)]
            (tap> {:event :lsp/method-handled :method method})
            result)
          (catch #?(:clj Exception :cljs :default) e
            (tap> {:event :lsp/method-error :method method
                   :error #?(:clj (.getMessage e) :cljs (.-message e))})
            (error-response :internal-error
                            #?(:clj (.getMessage e) :cljs (.-message e)))))
        (do
          (tap> {:event :lsp/method-not-found :method method})
          (error-response :method-not-found
                          (str "Method not implemented: " method)))))))

(defn create-adapter
  "Create an LSP adapter.

   Options:
     :server-info  - {:name \"my-server\" :version \"1.0.0\"}
     :capabilities - ServerCapabilities map (use server-capabilities fn)
     :methods      - Map of method -> handler fn to pre-register

   Example:
   (create-adapter
     {:server-info {:name \"my-lsp\" :version \"0.1.0\"}
      :capabilities (server-capabilities
                      :text-document-sync (text-document-sync-options
                                            :open-close true
                                            :change :incremental)
                      :hover true
                      :definition true)
      :methods {\"textDocument/hover\" my-hover-handler}})"
  [{:keys [server-info capabilities methods]}]
  (let [registry (create-method-registry)
        doc-store (create-document-store)
        state* (atom {:initialized false})]

    ;; Register provided methods
    (doseq [[method handler] methods]
      (register-method registry method handler))

    (->LspAdapter
     (or server-info {:name "defport-lsp" :version "0.1.0"})
     (or capabilities (server-capabilities))
     registry
     doc-store
     state*)))

(defn register-method!
  "Register a method handler on an adapter.

   Handler signature: (fn [params context] result)

   Context contains:
     :adapter        - The LspAdapter
     :document-store - DocumentStore for accessing open documents
     :port-registry  - Port registry (if provided at dispatch time)

   Example:
   (register-method! adapter \"textDocument/hover\"
     (fn [{:keys [textDocument position]} ctx]
       (let [doc (doc-get (:document-store ctx) (:uri textDocument))]
         (hover (markdown \"Hello!\")))))"
  [^LspAdapter adapter method handler]
  (register-method (:method-registry adapter) method handler))

;; =============================================================================
;; Section 10: Default Handlers
;; =============================================================================
;; Optional default implementations for common LSP methods.
;; Applications can use these or provide their own.

(defn default-initialize-handler
  "Default initialize handler. Returns server capabilities."
  [adapter]
  (fn [params context]
    (let [{:keys [rootUri capabilities]} params]
      (tap> {:event :lsp/initialize :root-uri rootUri
             :client-capabilities capabilities})
      (swap! (:state* adapter) assoc :initialized true :root-uri rootUri)
      {:capabilities (:capabilities adapter)
       :serverInfo (:server-info adapter)})))

(defn default-initialized-handler
  "Default initialized notification handler."
  [_adapter]
  (fn [_params _context]
    (tap> {:event :lsp/initialized})
    nil))

(defn default-shutdown-handler
  "Default shutdown handler."
  [adapter]
  (fn [_params _context]
    (tap> {:event :lsp/shutdown})
    (swap! (:state* adapter) assoc :shutting-down true)
    nil))

(defn default-exit-handler
  "Default exit notification handler."
  [_adapter]
  (fn [_params _context]
    (tap> {:event :lsp/exit})
    nil))

(defn default-did-open-handler
  "Default textDocument/didOpen handler."
  [_adapter]
  (fn [params context]
    (let [{:keys [textDocument]} params
          {:keys [uri languageId version text]} textDocument
          doc-store (:document-store context)]
      (doc-open doc-store uri text version languageId)
      (tap> {:event :lsp/did-open :uri uri})
      nil)))

(defn default-did-change-handler
  "Default textDocument/didChange handler."
  [_adapter]
  (fn [params context]
    (let [{:keys [textDocument contentChanges]} params
          {:keys [uri version]} textDocument
          doc-store (:document-store context)]
      (doc-change doc-store uri contentChanges version)
      nil)))

(defn default-did-close-handler
  "Default textDocument/didClose handler."
  [_adapter]
  (fn [params context]
    (let [uri (get-in params [:textDocument :uri])
          doc-store (:document-store context)]
      (doc-close doc-store uri)
      (tap> {:event :lsp/did-close :uri uri})
      nil)))

(defn register-lifecycle-handlers!
  "Register default lifecycle handlers on adapter."
  [adapter]
  (doto adapter
    (register-method! m:initialize (default-initialize-handler adapter))
    (register-method! m:initialized (default-initialized-handler adapter))
    (register-method! m:shutdown (default-shutdown-handler adapter))
    (register-method! m:exit (default-exit-handler adapter))))

(defn register-document-sync-handlers!
  "Register default document sync handlers on adapter."
  [adapter]
  (doto adapter
    (register-method! m:did-open (default-did-open-handler adapter))
    (register-method! m:did-change (default-did-change-handler adapter))
    (register-method! m:did-close (default-did-close-handler adapter))))

(defn register-default-handlers!
  "Register all default handlers (lifecycle + document sync)."
  [adapter]
  (doto adapter
    (register-lifecycle-handlers!)
    (register-document-sync-handlers!)))

;; =============================================================================
;; Section 11: LSP Client
;; =============================================================================
;; For connecting to external LSP servers.

(defprotocol LspClient
  "Client for communicating with external LSP servers."
  (client-start [this]
    "Start the client connection.")
  (client-request [this method params]
    "Send request, block for response.")
  (client-request-async [this method params callback]
    "Send request, invoke callback with response.")
  (client-notify [this method params]
    "Send notification (no response).")
  (client-stop [this]
    "Stop the client connection.")
  (client-alive? [this]
    "Check if client is connected."))

#?(:clj
   (defrecord StdioLspClient [process
                              ^BufferedReader reader
                              ^BufferedWriter writer
                              request-id*
                              pending*
                              alive?*
                              reader-thread]
     LspClient
     (client-start [this]
       this) ; Already started in constructor

     (client-request [this method params]
       (when @alive?*
         (let [id (swap! request-id* inc)
               msg (request-message id method params)
               response-promise (promise)]
           (swap! pending* assoc id response-promise)
           (tap> {:event :lsp/client-request :id id :method method})
           (write-lsp-message writer msg)
           (let [result (deref response-promise 30000 ::timeout)]
             (swap! pending* dissoc id)
             (if (= result ::timeout)
               {:error (error-response :request-failed "Request timed out")}
               result)))))

     (client-request-async [this method params callback]
       (when @alive?*
         (let [id (swap! request-id* inc)
               msg (request-message id method params)]
           (swap! pending* assoc id callback)
           (tap> {:event :lsp/client-request-async :id id :method method})
           (write-lsp-message writer msg))))

     (client-notify [this method params]
       (when @alive?*
         (let [msg (notification-message method params)]
           (tap> {:event :lsp/client-notify :method method})
           (write-lsp-message writer msg))))

     (client-stop [this]
       (when (compare-and-set! alive?* true false)
         (try
           (client-request this m:shutdown nil)
           (client-notify this m:exit nil)
           (catch Exception _))
         (.destroy ^Process process)
         ;; Complete pending with errors
         (doseq [[id p] @pending*]
           (when (instance? clojure.lang.IPending p)
             (deliver p {:error (error-response :server-cancelled "Client stopped")})))))

     (client-alive? [_]
       @alive?*)))

#?(:clj
   (defn- start-client-reader-thread
     "Start background thread to read responses from LSP server."
     [^BufferedReader reader pending* alive?*]
     (doto (Thread.
            (fn []
              (try
                (while @alive?*
                  (when-let [msg (read-lsp-message reader)]
                    (tap> {:event :lsp/client-received :message msg})
                    (if-let [id (:id msg)]
                      ;; Response
                      (when-let [handler (get @pending* id)]
                        (if (fn? handler)
                          (handler msg)
                          (deliver handler msg)))
                      ;; Server notification
                      (tap> {:event :lsp/server-notification
                             :method (:method msg)
                             :params (:params msg)}))))
                (catch Exception e
                  (when @alive?*
                    (tap> {:event :lsp/client-reader-error
                           :error (.getMessage e)}))))))
       (.setDaemon true)
       (.setName "defport-lsp-client-reader")
       (.start))))

#?(:clj
   (defn create-client
     "Create an LSP client that connects to an external server via stdio.

      Options:
        :command - Command vector [\"pyright-langserver\" \"--stdio\"]
        :env     - Environment variables map (optional)
        :dir     - Working directory (optional)

      Example:
      (def client (create-client {:command [\"clojure-lsp\"]}))
      (client-request client \"initialize\" {...})
      (client-notify client \"initialized\" {})
      (client-request client \"textDocument/hover\" {...})"
     [{:keys [command env dir]}]
     (let [pb (ProcessBuilder. ^java.util.List (vec command))
           _ (when dir (.directory pb (java.io.File. ^String dir)))
           _ (when env (.putAll (.environment pb) ^java.util.Map env))
           process (.start pb)
           reader (BufferedReader.
                   (InputStreamReader. (.getInputStream process) "UTF-8"))
           writer (BufferedWriter.
                   (OutputStreamWriter. (.getOutputStream process) "UTF-8"))
           request-id* (atom 0)
           pending* (atom {})
           alive?* (atom true)
           reader-thread (start-client-reader-thread reader pending* alive?*)]
       (->StdioLspClient process reader writer request-id* pending*
                         alive?* reader-thread))))

;; =============================================================================
;; Section 12: Convenience Client API
;; =============================================================================
;; High-level functions for common LSP operations.

(defn initialize
  "Initialize LSP connection.
   Returns InitializeResult with server capabilities."
  [client root-uri & {:keys [capabilities]}]
  (let [response (client-request client m:initialize
                  {:processId #?(:clj (.pid (java.lang.ProcessHandle/current))
                                 :cljs nil)
                   :rootUri root-uri
                   :capabilities (or capabilities default-client-capabilities)})]
    (when-not (:error response)
      (client-notify client m:initialized {})
      (:result response))))

(defn shutdown
  "Shutdown LSP connection gracefully."
  [client]
  (client-request client m:shutdown nil)
  (client-notify client m:exit nil))

(defn hover-at
  "Get hover information at position."
  [client uri line character]
  (let [response (client-request client m:hover
                  (text-document-position-params uri line character))]
    (:result response)))

(defn definition-at
  "Get definition location(s) for symbol at position."
  [client uri line character]
  (let [response (client-request client m:definition
                  (text-document-position-params uri line character))]
    (:result response)))

(defn references-at
  "Get all references to symbol at position."
  [client uri line character & {:keys [include-declaration]
                                :or {include-declaration true}}]
  (let [response (client-request client m:references
                  (assoc (text-document-position-params uri line character)
                         :context {:includeDeclaration include-declaration}))]
    (:result response)))

(defn complete-at
  "Get completions at position."
  [client uri line character]
  (let [response (client-request client m:completion
                  (text-document-position-params uri line character))]
    (:result response)))

(defn symbols-in-document
  "Get all symbols in document."
  [client uri]
  (let [response (client-request client (method-name :language :document-symbol)
                  {:textDocument (text-document-identifier uri)})]
    (:result response)))

(defn symbols-in-workspace
  "Search for symbols in workspace."
  [client query]
  (let [response (client-request client m:workspace-symbol
                  {:query query})]
    (:result response)))

(defn format-document
  "Format entire document."
  [client uri & {:keys [tab-size insert-spaces]
                 :or {tab-size 2 insert-spaces true}}]
  (let [response (client-request client m:formatting
                  {:textDocument (text-document-identifier uri)
                   :options {:tabSize tab-size
                             :insertSpaces insert-spaces}})]
    (:result response)))

(defn rename-at
  "Rename symbol at position."
  [client uri line character new-name]
  (let [response (client-request client m:rename
                  (assoc (text-document-position-params uri line character)
                         :newName new-name))]
    (:result response)))

(defn code-actions-at
  "Get code actions for range."
  [client uri start-line start-char end-line end-char & {:keys [diagnostics only]}]
  (let [response (client-request client m:code-action
                  {:textDocument (text-document-identifier uri)
                   :range (range- start-line start-char end-line end-char)
                   :context (cond-> {}
                              diagnostics (assoc :diagnostics diagnostics)
                              only (assoc :only only))})]
    (:result response)))

;; =============================================================================
;; Section 13: Port-Based Routing (Cross-Protocol)
;; =============================================================================
;; Enable exposing defport.core ports as LSP methods via metadata.
;;
;; Example port with LSP metadata:
;;   (defport.core/register-port!
;;     {:id :find-callers
;;      :handler find-callers-handler
;;      :metadata {:lsp {:method "textDocument/references"
;;                       :transform :locations}}})
;;
;; Usage:
;;   (def adapter (create-adapter {...}))
;;   (register-ports! adapter)  ; Auto-registers ports with :lsp metadata

(defn- transform-result
  "Transform port result to LSP format based on :transform metadata.

   Transforms:
   - :locations - Vector of locations [{:file :line}] -> Location[]
   - :location  - Single {:file :line} -> Location
   - :hover     - {:callers :callees :name} -> Hover
   - :symbols   - Vector of {:qn :file :line} -> SymbolInformation[]
   - nil        - Return result as-is"
  [transform result]
  (case transform
    :locations
    (mapv (fn [item]
            (location (str "file://" (or (:file item) ""))
                      (or (:line item) 0) 0
                      (or (:line item) 0) 100))
          (or (:callers result) (:locations result) result))

    :location
    (when-let [loc (first (or (:locations result) [result]))]
      (location (str "file://" (or (:file loc) ""))
                (or (:line loc) 0) 0
                (or (:line loc) 0) 100))

    :hover
    {:contents (markdown
                (str "## " (or (:function result) (:name result) "Unknown") "\n\n"
                     (when-let [c (:callers result)]
                       (str "**Callers:** " (if (number? c) c (count c)) "\n"))
                     (when-let [c (:callees result)]
                       (str "**Callees:** " (if (number? c) c (count c)) "\n"))
                     (when-let [t (:tests result)]
                       (str "**Tests:** " (count t)))))}

    :symbols
    (mapv (fn [item]
            (symbol-information
              {:name (or (:qn item) (:name item))
               :kind :function
               :location (location (str "file://" (or (:file item) ""))
                                   (or (:line item) 0) 0
                                   (or (:line item) 0) 100)}))
          (or (:results result) result))

    ;; Default: return as-is
    result))

(defn- create-port-handler
  "Create LSP handler from port definition."
  [port-def]
  (let [handler (:handler port-def)
        transform (get-in port-def [:metadata :lsp :transform])]
    (fn [params context]
      (try
        (let [;; Convert LSP params to port params
              port-params (cond-> {}
                            (:textDocument params)
                            (assoc :file (some-> (:textDocument params)
                                                 :uri
                                                 (str/replace #"^file://" "")))
                            (:position params)
                            (-> (assoc :line (:line (:position params)))
                                (assoc :column (:character (:position params))))
                            ;; Pass through function-name if provided
                            (:function-name params)
                            (assoc :function-name (:function-name params))
                            ;; Pass through query if provided
                            (:query params)
                            (assoc :query (:query params)))
              ;; Execute port handler
              result (handler (assoc context :params port-params))
              ;; Extract result data
              data (or (:result result) result)]
          (transform-result transform data))
        (catch #?(:clj Exception :cljs :default) e
          (error-response :internal-error
                          #?(:clj (.getMessage e)
                             :cljs (.-message e))))))))

(defn find-ports-for-lsp
  "Find all registered ports with :lsp metadata.

   Returns map of {lsp-method port-def}"
  []
  (->> (core/list-registered-port-defs)
       (filter #(get-in % [:metadata :lsp :method]))
       (map (fn [p] [(get-in p [:metadata :lsp :method]) p]))
       (into {})))

(defn register-ports!
  "Register all ports with :lsp metadata as LSP method handlers.

   Automatically finds ports in defport.core registry that have
   :metadata {:lsp {:method \"textDocument/...\"}} and registers
   them as handlers on the adapter.

   Example:
     (def adapter (create-adapter {...}))
     (register-ports! adapter)

   After this, LSP requests like textDocument/references will
   be routed to the matching port handler."
  [adapter]
  (doseq [[method port-def] (find-ports-for-lsp)]
    (tap> {:event :lsp/registering-port
           :method method
           :port-id (:id port-def)})
    (register-method! adapter method (create-port-handler port-def)))
  adapter)

;; Note: expose-port! is provided by the lsp convenience namespace (src/lsp.cljc)
;; which offers a user-friendlier API with :as keyword mapping.