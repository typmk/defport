(ns defport.mcp.spec
  "Single source of truth for MCP method routing.

  Parallels defport.lsp.spec and defport.dap.spec. Unlike those two,
  MCP's handlers do substantive work — they aren't stubs that degrade
  to empty bodies. So spec entries hold metadata *about* each method
  (wire string, kind, direction, client vs server origin, docs) plus
  a symbol name pointing at the concrete handler in defport.mcp. The
  adapter resolves symbols to vars when it wires up its dispatch
  table, keeping the spec file independent of the handler
  implementations (no circular require).

  Surface: MCP 2025-11-25 spec. 17 core methods covering
  initialize / tools / prompts / resources / roots / elicitation /
  completion / logging / ping, plus the server-initiated
  sampling/createMessage and a handful of notifications.

  Plain Clojure data — no schema lib. Validation slots (:validate-in,
  :validate-out) accept any callable so consumers can plug in spec,
  malli, or hand-rolled predicates if they want."
  (:refer-clojure :exclude [methods]))

;; ============================================================================
;; The method registry
;; ============================================================================
;;
;; :handler-sym points at the fully-qualified symbol defport.mcp
;; exports for this method. The adapter calls `resolve` at
;; wire-up time to get the actual var. nil for methods with no
;; shipped default handler (notifications the adapter accepts but
;; doesn't itself process).

(def methods
  "Every MCP method defport 's server routes, keyed by a method-name
   keyword. Each row is plain data — add a row to add a method."
  {;; -------------------------------------------------------------------------
   ;; Lifecycle
   ;; -------------------------------------------------------------------------
   :initialize
   {:method      "initialize"
    :kind        :request
    :direction   :client->server
    :handler-sym 'defport.mcp/handle-initialize
    :doc         "Client introduces itself; server replies with capabilities."}

   :ping
   {:method      "ping"
    :kind        :request
    :direction   :both
    :handler-sym 'defport.mcp/handle-ping
    :doc         "Liveness probe. Either side may initiate."}

   :notifications/initialized
   {:method      "notifications/initialized"
    :kind        :notification
    :direction   :client->server
    :handler-sym nil
    :doc         "Client tells the server initialization is complete."}

   :notifications/cancelled
   {:method      "notifications/cancelled"
    :kind        :notification
    :direction   :both
    :handler-sym nil
    :doc         "Either side cancels a previously-sent request."}

   :notifications/progress
   {:method      "notifications/progress"
    :kind        :notification
    :direction   :both
    :handler-sym nil
    :doc         "Progress update for a long-running operation."}

   ;; -------------------------------------------------------------------------
   ;; Tools
   ;; -------------------------------------------------------------------------
   :tools/list
   {:method      "tools/list"
    :kind        :request
    :direction   :client->server
    :capability  :tools
    :handler-sym 'defport.mcp/handle-tools-list
    :doc         "List tools the server exposes."}

   :tools/call
   {:method      "tools/call"
    :kind        :request
    :direction   :client->server
    :capability  :tools
    :handler-sym 'defport.mcp/handle-tools-call
    :doc         "Invoke a tool with arguments."}

   :notifications/tools-list-changed
   {:method      "notifications/tools/list_changed"
    :kind        :notification
    :direction   :server->client
    :capability  :tools
    :handler-sym nil
    :doc         "Server tells client the tool list has changed."}

   ;; -------------------------------------------------------------------------
   ;; Prompts
   ;; -------------------------------------------------------------------------
   :prompts/list
   {:method      "prompts/list"
    :kind        :request
    :direction   :client->server
    :capability  :prompts
    :handler-sym 'defport.mcp/handle-prompts-list
    :doc         "List prompts the server exposes."}

   :prompts/get
   {:method      "prompts/get"
    :kind        :request
    :direction   :client->server
    :capability  :prompts
    :handler-sym 'defport.mcp/handle-prompts-get
    :doc         "Fetch a named prompt with optional arguments."}

   :notifications/prompts-list-changed
   {:method      "notifications/prompts/list_changed"
    :kind        :notification
    :direction   :server->client
    :capability  :prompts
    :handler-sym nil
    :doc         "Server tells client the prompt list has changed."}

   ;; -------------------------------------------------------------------------
   ;; Resources
   ;; -------------------------------------------------------------------------
   :resources/list
   {:method      "resources/list"
    :kind        :request
    :direction   :client->server
    :capability  :resources
    :handler-sym 'defport.mcp/handle-resources-list
    :doc         "List resources the server exposes."}

   :resources/read
   {:method      "resources/read"
    :kind        :request
    :direction   :client->server
    :capability  :resources
    :handler-sym 'defport.mcp/handle-resources-read
    :doc         "Fetch the content of a resource by URI."}

   :resources/subscribe
   {:method      "resources/subscribe"
    :kind        :request
    :direction   :client->server
    :capability  :resources
    :handler-sym 'defport.mcp/handle-resources-subscribe
    :doc         "Subscribe to update notifications for a resource URI."}

   :resources/unsubscribe
   {:method      "resources/unsubscribe"
    :kind        :request
    :direction   :client->server
    :capability  :resources
    :handler-sym 'defport.mcp/handle-resources-unsubscribe
    :doc         "Unsubscribe from a resource URI."}

   :resources/templates-list
   {:method      "resources/templates/list"
    :kind        :request
    :direction   :client->server
    :capability  :resources
    :handler-sym nil   ;; MCP Inspector extension — adapter ships an inline no-op
    :doc         "List resource templates. MCP Inspector extension."}

   :notifications/resources-list-changed
   {:method      "notifications/resources/list_changed"
    :kind        :notification
    :direction   :server->client
    :capability  :resources
    :handler-sym nil
    :doc         "Server tells client the resource list has changed."}

   :notifications/resources-updated
   {:method      "notifications/resources/updated"
    :kind        :notification
    :direction   :server->client
    :capability  :resources
    :handler-sym nil
    :doc         "Server tells client a subscribed resource's content changed."}

   ;; -------------------------------------------------------------------------
   ;; Roots
   ;; -------------------------------------------------------------------------
   :roots/list
   {:method      "roots/list"
    :kind        :request
    :direction   :server->client  ;; server ASKS the client for roots
    :capability  :roots
    :handler-sym 'defport.mcp/handle-roots-list
    :doc         "Server asks client to list its filesystem roots."}

   :notifications/roots-list-changed
   {:method      "notifications/roots/list_changed"
    :kind        :notification
    :direction   :client->server
    :capability  :roots
    :handler-sym nil
    :doc         "Client tells server its root list has changed."}

   ;; -------------------------------------------------------------------------
   ;; Elicitation (client↔server form-style interaction)
   ;; -------------------------------------------------------------------------
   :elicitation/create
   {:method      "elicitation/create"
    :kind        :request
    :direction   :server->client
    :capability  :elicitation
    :handler-sym 'defport.mcp/handle-elicitation-create
    :doc         "Server asks client to present a form and return the user's response."}

   :notifications/elicitation-complete
   {:method      "notifications/elicitation/complete"
    :kind        :notification
    :direction   :client->server
    :capability  :elicitation
    :handler-sym nil
    :doc         "Client reports an elicitation has completed."}

   ;; -------------------------------------------------------------------------
   ;; Sampling (server asks client to run an LLM call)
   ;; -------------------------------------------------------------------------
   :sampling/createMessage
   {:method      "sampling/createMessage"
    :kind        :request
    :direction   :server->client
    :capability  :sampling
    :handler-sym nil  ;; server-initiated — no server-side handler
    :doc         "Server asks client to run a sampling/LLM request and return the result."}

   ;; -------------------------------------------------------------------------
   ;; Completion / logging
   ;; -------------------------------------------------------------------------
   :completion/complete
   {:method      "completion/complete"
    :kind        :request
    :direction   :client->server
    :capability  :completions
    :handler-sym 'defport.mcp/handle-completion-complete
    :doc         "Client asks server for completions on an in-progress prompt / tool argument."}

   :logging/setLevel
   {:method      "logging/setLevel"
    :kind        :request
    :direction   :client->server
    :capability  :logging
    :handler-sym 'defport.mcp/handle-logging-set-level
    :doc         "Client sets the server's log level for this session."}

   :notifications/message
   {:method      "notifications/message"
    :kind        :notification
    :direction   :server->client
    :capability  :logging
    :handler-sym nil
    :doc         "Server emits a log message to the client."}

   ;; -------------------------------------------------------------------------
   ;; Tasks API (MCP 2025-11-25)
   ;; -------------------------------------------------------------------------
   ;; Long-running operations that outlive a single request/response.
   ;; Defport ships no default handlers — consumers register tool-style
   ;; ports that drive the task lifecycle.
   :tasks/list
   {:method      "tasks/list"
    :kind        :request
    :direction   :client->server
    :capability  :tasks
    :handler-sym nil
    :doc         "List in-flight long-running tasks."}

   :tasks/get
   {:method      "tasks/get"
    :kind        :request
    :direction   :client->server
    :capability  :tasks
    :handler-sym nil
    :doc         "Get the current status of a task."}

   :tasks/cancel
   {:method      "tasks/cancel"
    :kind        :request
    :direction   :client->server
    :capability  :tasks
    :handler-sym nil
    :doc         "Request cancellation of an in-flight task."}

   :tasks/result
   {:method      "tasks/result"
    :kind        :request
    :direction   :client->server
    :capability  :tasks
    :handler-sym nil
    :doc         "Fetch the completed result of a task."}

   :notifications/tasks-status
   {:method      "notifications/tasks/status"
    :kind        :notification
    :direction   :server->client
    :capability  :tasks
    :handler-sym nil
    :doc         "Server pushes status updates for an in-flight task."}})

;; ============================================================================
;; Lookups
;; ============================================================================

(defn method-for
  "Look up a method-name keyword in the registry."
  [method-name]
  (get methods method-name))

(defn method-name-for
  "Inverse: wire method string → method-name keyword, or nil."
  [method-string]
  (some (fn [[k v]] (when (= method-string (:method v)) k))
        methods))

(defn wire-method
  "Convenience: method-name keyword → wire method string."
  [method-name]
  (:method (method-for method-name)))

(defn handler-sym
  "Symbol pointing at the defport.mcp handler fn for this method, or
   nil. The adapter resolves this to a var at wire-up time."
  [method-name]
  (:handler-sym (method-for method-name)))

(defn notification?
  [method-name]
  (= :notification (:kind (method-for method-name))))

(defn request?
  [method-name]
  (= :request (:kind (method-for method-name))))

(defn server-initiated?
  "True for requests/notifications the server originates against the
   client — roots/list, elicitation/create, sampling/createMessage,
   notifications/{tools,prompts,resources}/list_changed, etc.

   Direction :both is NOT server-initiated — it means either side
   can originate (e.g. ping, notifications/cancelled)."
  [method-name]
  (= :server->client (:direction (method-for method-name))))

(defn all-method-names
  []
  (keys methods))

(defn capabilities-for
  "Set of capability keywords reachable from a collection of method
   names. Useful when building the initialize response from the
   set of methods that actually have ports registered."
  [method-names]
  (into #{} (keep #(:capability (method-for %))) method-names))

(defn default-handler-syms
  "Sequence of [wire-method-string handler-symbol] for every method
   where the spec names a handler. The MCP adapter walks this at
   wire-up to build its dispatch table — a single source of truth
   replacing the old hand-maintained default-handlers map."
  []
  (for [[_ entry] methods
        :when (:handler-sym entry)]
    [(:method entry) (:handler-sym entry)]))
