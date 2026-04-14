(ns defport.lsp.spec
  "The single source of truth for LSP method routing.

   Every method we know about is one row in `methods`. Each row carries
   everything defport needs to handle that method without per-method
   handler code:

     :method        — wire string (e.g. \"textDocument/hover\")
     :kind          — :request | :notification
     :direction     — :client->server | :server->client | :both
     :capability    — ServerCapabilities key the method implies, or nil
     :sugar         — keyword into `sugar-extractors` describing how
                      `deflsp` should pull flat params from the raw LSP
                      params shape, or nil for raw passthrough
     :default       — value or fn returning the response when no port
                      is registered. nil for notifications.
     :validate-in   — optional (fn [params] → truthy or nil for ok,
                      anything else for an error map). Catches
                      malformed inbound messages early.
     :validate-out  — optional (fn [response] → same shape). Catches
                      bad user-handler responses before they hit the
                      wire.
     :doc           — one-line human description.

   Plain Clojure data. No schema lib — defport stays neutral about
   which validation library a consumer prefers. Predicate slots
   accept any callable. Consumers wrap spec, malli, plumatic, or
   hand-rolled checks as plain functions and slot them in.

   This file is the keystone of the spec-registry substrate: every
   macro, default handler, capability reporter, and route in
   `defport.lsp` reads from this map. Adding an LSP method means
   adding a row here, not writing a new function."
  (:refer-clojure :exclude [methods]))

;; ============================================================================
;; Sugar param-extraction shapes
;; ============================================================================
;;
;; LSP methods come in a small number of param shapes. The `deflsp`
;; macro looks up the method's :sugar key here to know how to pull
;; flat parameters out of the raw LSP params map. Adding a new shape
;; means one entry here + the methods that use it.

(def sugar-extractors
  "Map of sugar-key → fn that takes raw LSP params and returns a
   flat map the deflsp macro destructures into the user's named
   parameters."
  {:position
   (fn [params]
     {:uri  (get-in params [:textDocument :uri])
      :line (get-in params [:position :line])
      :col  (get-in params [:position :character])})

   :range
   (fn [params]
     {:uri   (get-in params [:textDocument :uri])
      :range (:range params)})

   :document
   (fn [params]
     {:uri (get-in params [:textDocument :uri])})

   :workspace-symbol
   (fn [params]
     {:query (:query params)})

   :rename
   (fn [params]
     {:uri      (get-in params [:textDocument :uri])
      :line     (get-in params [:position :line])
      :col      (get-in params [:position :character])
      :new-name (:newName params)})

   :raw
   (fn [params] params)})

;; ============================================================================
;; The method registry
;; ============================================================================

(def methods
  "Single source of truth for every LSP method defport knows about.
   Keyed by handler-name keyword (the same keyword `deflsp` uses).
   Add a row to add a method."
  {;; -------------------------------------------------------------------------
   ;; Lifecycle
   ;; -------------------------------------------------------------------------
   :initialize
   {:method     "initialize"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil   ;; lifecycle default ships as a fn from spec.cljc
    :doc        "Initial handshake. Client introduces itself, server replies with capabilities."}

   :initialized
   {:method     "initialized"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Client tells the server it has finished receiving the initialize response."}

   :shutdown
   {:method     "shutdown"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Ask the server to shut down. Must be followed by exit."}

   :exit
   {:method     "exit"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Tell the server to exit its process."}

   ;; -------------------------------------------------------------------------
   ;; Special notifications
   ;; -------------------------------------------------------------------------
   :cancel-request
   {:method     "$/cancelRequest"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil   ;; defport handles cancellation in protocol-dispatch
    :doc        "Notification to cancel an in-flight request."}

   :progress
   {:method     "$/progress"
    :kind       :notification
    :direction  :both
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Bidirectional progress notification."}

   ;; -------------------------------------------------------------------------
   ;; Document sync
   ;; -------------------------------------------------------------------------
   :did-open
   {:method     "textDocument/didOpen"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Client opened a document."}

   :did-change
   {:method     "textDocument/didChange"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Client changed a document."}

   :did-save
   {:method     "textDocument/didSave"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Client saved a document. May include the new text if save.includeText is set."}

   :did-close
   {:method     "textDocument/didClose"
    :kind       :notification
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    nil
    :doc        "Client closed a document."}

   ;; -------------------------------------------------------------------------
   ;; Language features (the ones with end-to-end tests today)
   ;; -------------------------------------------------------------------------
   :hover
   {:method     "textDocument/hover"
    :kind       :request
    :direction  :client->server
    :capability :hoverProvider
    :sugar      :position
    :default    (constantly nil)   ;; nil = no hover available, per LSP spec
    :doc        "Show hover info at a position."}

   :definition
   {:method     "textDocument/definition"
    :kind       :request
    :direction  :client->server
    :capability :definitionProvider
    :sugar      :position
    :default    (constantly nil)
    :doc        "Resolve definition location for the symbol at a position."}

   :references
   {:method     "textDocument/references"
    :kind       :request
    :direction  :client->server
    :capability :referencesProvider
    :sugar      :position
    :default    (constantly [])
    :doc        "Find all references to the symbol at a position."}

   :document-symbol
   {:method     "textDocument/documentSymbol"
    :kind       :request
    :direction  :client->server
    :capability :documentSymbolProvider
    :sugar      :document
    :default    (constantly [])
    :doc        "List symbols defined in a document."}

   :rename
   {:method     "textDocument/rename"
    :kind       :request
    :direction  :client->server
    :capability :renameProvider
    :sugar      :rename
    :default    (constantly nil)
    :doc        "Compute a workspace edit to rename the symbol at a position."}

   :workspace-symbol
   {:method     "workspace/symbol"
    :kind       :request
    :direction  :client->server
    :capability :workspaceSymbolProvider
    :sugar      :workspace-symbol
    :default    (constantly [])
    :doc        "Search workspace-wide for symbols matching a query."}

   ;; -------------------------------------------------------------------------
   ;; Language features (routed but no end-to-end test coverage yet — entries
   ;; carry capability + sugar so deflsp + capabilities-from-registry work)
   ;; -------------------------------------------------------------------------
   :declaration
   {:method     "textDocument/declaration"
    :kind       :request
    :direction  :client->server
    :capability :declarationProvider
    :sugar      :position
    :default    (constantly nil)
    :doc        "Resolve declaration location for the symbol at a position."}

   :type-definition
   {:method     "textDocument/typeDefinition"
    :kind       :request
    :direction  :client->server
    :capability :typeDefinitionProvider
    :sugar      :position
    :default    (constantly nil)
    :doc        "Resolve type definition location."}

   :implementation
   {:method     "textDocument/implementation"
    :kind       :request
    :direction  :client->server
    :capability :implementationProvider
    :sugar      :position
    :default    (constantly nil)
    :doc        "Resolve implementation location(s)."}

   :document-highlight
   {:method     "textDocument/documentHighlight"
    :kind       :request
    :direction  :client->server
    :capability :documentHighlightProvider
    :sugar      :position
    :default    (constantly [])
    :doc        "Highlight occurrences of the symbol at a position in the document."}

   :prepare-rename
   {:method     "textDocument/prepareRename"
    :kind       :request
    :direction  :client->server
    :capability :renameProvider
    :sugar      :position
    :default    (constantly nil)
    :doc        "Validate whether a rename is possible at a position."}

   :code-action
   {:method     "textDocument/codeAction"
    :kind       :request
    :direction  :client->server
    :capability :codeActionProvider
    :sugar      :range
    :default    (constantly [])
    :doc        "Compute available code actions for a range."}

   :code-lens
   {:method     "textDocument/codeLens"
    :kind       :request
    :direction  :client->server
    :capability :codeLensProvider
    :sugar      :document
    :default    (constantly [])
    :doc        "List code lenses (inline actions) in a document."}

   :formatting
   {:method     "textDocument/formatting"
    :kind       :request
    :direction  :client->server
    :capability :documentFormattingProvider
    :sugar      :document
    :default    (constantly [])
    :doc        "Format a whole document."}

   :range-formatting
   {:method     "textDocument/rangeFormatting"
    :kind       :request
    :direction  :client->server
    :capability :documentRangeFormattingProvider
    :sugar      :range
    :default    (constantly [])
    :doc        "Format a range within a document."}

   :folding-range
   {:method     "textDocument/foldingRange"
    :kind       :request
    :direction  :client->server
    :capability :foldingRangeProvider
    :sugar      :document
    :default    (constantly [])
    :doc        "Compute foldable regions in a document."}

   :selection-range
   {:method     "textDocument/selectionRange"
    :kind       :request
    :direction  :client->server
    :capability :selectionRangeProvider
    :sugar      :range
    :default    (constantly [])
    :doc        "Compute selection ranges (smart-select-up tree)."}

   :signature-help
   {:method     "textDocument/signatureHelp"
    :kind       :request
    :direction  :client->server
    :capability :signatureHelpProvider
    :sugar      :position
    :default    (constantly nil)
    :doc        "Show parameter hints for the call expression at a position."}

   :completion
   {:method     "textDocument/completion"
    :kind       :request
    :direction  :client->server
    :capability :completionProvider
    :sugar      :position
    :default    (constantly [])
    :doc        "Compute completion items at a position."}

   :inlay-hint
   {:method     "textDocument/inlayHint"
    :kind       :request
    :direction  :client->server
    :capability :inlayHintProvider
    :sugar      :range
    :default    (constantly [])
    :doc        "Compute inlay hints for a range."}

   :semantic-tokens
   {:method     "textDocument/semanticTokens/full"
    :kind       :request
    :direction  :client->server
    :capability :semanticTokensProvider
    :sugar      :document
    :default    (constantly nil)
    :doc        "Compute semantic tokens for a whole document."}

   :document-link
   {:method     "textDocument/documentLink"
    :kind       :request
    :direction  :client->server
    :capability :documentLinkProvider
    :sugar      :document
    :default    (constantly [])
    :doc        "List clickable links in a document."}

   :call-hierarchy-prepare
   {:method     "textDocument/prepareCallHierarchy"
    :kind       :request
    :direction  :client->server
    :capability :callHierarchyProvider
    :sugar      :position
    :default    (constantly nil)
    :doc        "Prepare a call-hierarchy item at a position."}

   :call-hierarchy-incoming
   {:method     "callHierarchy/incomingCalls"
    :kind       :request
    :direction  :client->server
    :capability :callHierarchyProvider
    :sugar      :raw
    :default    (constantly [])
    :doc        "Resolve incoming calls for a call-hierarchy item."}

   :call-hierarchy-outgoing
   {:method     "callHierarchy/outgoingCalls"
    :kind       :request
    :direction  :client->server
    :capability :callHierarchyProvider
    :sugar      :raw
    :default    (constantly [])
    :doc        "Resolve outgoing calls for a call-hierarchy item."}})

;; ============================================================================
;; Lookups
;; ============================================================================
;;
;; All accessors over the registry. Defport's adapter/macro code goes
;; through these instead of touching the map directly, so the registry
;; can grow shape (e.g. add :since-version) without rippling.

(defn method-for
  "Look up the spec entry for a handler-name keyword (e.g. :hover).
   Returns nil for unknown handler names."
  [handler-name]
  (get methods handler-name))

(defn handler-name-for
  "Inverse: given an LSP method string, return the handler-name keyword.
   Returns nil if the method string isn't in the registry."
  [method-string]
  (some (fn [[k v]] (when (= method-string (:method v)) k))
        methods))

(defn wire-method
  "Convenience: handler-name → wire method string."
  [handler-name]
  (:method (method-for handler-name)))

(defn capability-key
  "The ServerCapabilities key implied by a handler-name, or nil."
  [handler-name]
  (:capability (method-for handler-name)))

(defn sugar-extractor
  "Resolve a handler-name to its sugar param-extraction fn.
   Falls back to :raw passthrough if the entry has no :sugar set."
  [handler-name]
  (let [k (or (:sugar (method-for handler-name)) :raw)]
    (get sugar-extractors k)))

(defn default-response
  "Get the default-response value or fn for a handler-name.
   Notifications return nil; requests return whatever :default was."
  [handler-name]
  (:default (method-for handler-name)))

(defn notification?
  "Is this method-name a notification (no response) rather than a request?"
  [handler-name]
  (= :notification (:kind (method-for handler-name))))

(defn request?
  "Is this method-name a request (expects a response)?"
  [handler-name]
  (= :request (:kind (method-for handler-name))))

(defn all-handler-names
  "List every handler-name keyword in the registry. Stable order is
   not guaranteed — callers that need ordering should sort."
  []
  (keys methods))

(defn capability-keys-from-handler-names
  "Given a collection of handler-name keywords (e.g. the ports a
   consumer has actually registered), return the set of capability
   keys that should be enabled in ServerCapabilities."
  [handler-names]
  (into #{}
        (keep capability-key)
        handler-names))

(defn validate-inbound
  "Run a method's :validate-in predicate against raw params if one is
   set. Returns nil on success, an error map on failure. Methods with
   no :validate-in always pass."
  [handler-name params]
  (when-let [validator (:validate-in (method-for handler-name))]
    (let [result (validator params)]
      (when (and (some? result) (not (true? result)))
        (if (map? result) result {:error result})))))

(defn validate-outbound
  "Run a method's :validate-out predicate against a response if one is
   set. Returns nil on success, an error map on failure."
  [handler-name response]
  (when-let [validator (:validate-out (method-for handler-name))]
    (let [result (validator response)]
      (when (and (some? result) (not (true? result)))
        (if (map? result) result {:error result})))))
