(ns defport.mcp
  "Model Context Protocol (MCP) server adapter for defport.

  Implements the server role of MCP 2025-11-25 spec: receives JSON-RPC
  requests, dispatches to user-provided port handlers, formats responses.
  Platform-agnostic (.cljc — works on JVM and Node/CLJS).

  ## Building Servers (ProtocolAdapter)

  Use `create-mcp-adapter` to build MCP servers that expose tools, resources, and prompts:

    (def adapter (create-mcp-adapter {:server-info {:name \"my-server\" :version \"1.0\"}}))
    (protocol-dispatch adapter \"tools/call\" {:name \"search\" :arguments {...}} context)

  The adapter supports server-initiated requests (sampling, elicitation,
  progress, log messages) via the `send-*-request` / `notify-*` helpers.
  These send JSON-RPC requests to the connected client over the existing
  transport — no separate client object is needed.

  ## Client Role (not shipped)

  Defport does NOT ship an MCP client for the case where your program
  wants to spawn and drive an external MCP server. That use case is
  application concern (like clj-http vs Ring). See the end of this file
  and `defport.core/ProtocolClient` for the contract you can implement
  in your own code.

  ## Observability via tap>

  Both adapters emit tap> events for observability:

    {:event :mcp/<event-type>
     :timestamp <epoch-ms>
     ...event-specific-data}

  Event types:
  - :mcp/tool-call - Tool execution completed
  - :mcp/prompt-get - Prompt execution completed
  - :mcp/resource-read - Resource read completed
  - :mcp/error - Error occurred
  - :mcp/operation-cancelled - Operation was cancelled
  - :mcp/subscription-added - Resource subscription added
  - :mcp/subscription-removed - Resource subscription removed

  Usage:
    ;; Development - print all events
    (add-tap println)

    ;; Production - route to metrics
    (add-tap (fn [e]
               (when (and (map? e) (:event e))
                 (record-metric! e))))"
  (:require [defport.core :as core]
            [defport.mcp.spec :as spec]
            [defport.sugar :as sugar :include-macros true]
            [defport.util.platform :as platform :include-macros true]
            [defport.util.protocol :as proto-util]
            [defport.util.pagination :as pagination]
            [defport.util.progress :as progress]
            [defport.util.content :as content]
            [defport.util.batch :as batch]))

;; ============================================================================
;; Observability Helpers
;; ============================================================================

(defn- emit-event!
  "Emit a tap> event for observability.
  Events are maps with :event key identifying the event type.
  Zero overhead when no taps registered."
  [event-type data]
  (tap> (assoc data
          :event event-type
          :timestamp (platform/now-ms))))

;; ============================================================================
;; Configuration
;; ============================================================================

(def ^:private refactoring-enabled?
  "Check if dangerous refactoring tools are enabled via environment variable.
  Set DEFPORT_ENABLE_REFACTORING=true to enable."
  (= "true" (platform/get-env "DEFPORT_ENABLE_REFACTORING" "false")))

;; ============================================================================
;; Protocol State — single atom, immutable map
;; ============================================================================

(def ^:private empty-state
  "The shape of a fresh protocol state."
  {:seen-request-ids    #{}
   :active-operations   #{}
   :cancelled-operations #{}
   :resource-subscriptions {}
   :change-notifications {:tools false :prompts false :resources false}
   :elicitation         {}
   :session-log-levels  {}
   :client-roots        []
   :sampling            {}})

(defn create-protocol-state
  "Create a fresh protocol state atom.

  Each McpAdapter owns one. Returns a single atom holding an immutable map.
  All mutations go through swap! on this one atom — no nested atoms, consistent
  snapshots, trivial to inspect or reset."
  []
  (atom empty-state))

;; Default state for backward compatibility with code that calls
;; reset-protocol-state! without an adapter reference.
(defonce default-state* (create-protocol-state))

(defn reset-protocol-state!
  "Reset protocol state. Accepts an optional state atom (defaults to the
  global default-state* for backward compatibility)."
  ([]
   (reset-protocol-state! default-state*))
  ([state*]
   (reset! state* empty-state)))

(defn adapter-state
  "Get the protocol state atom from an adapter. Useful for tests and introspection."
  [adapter]
  (:state* adapter))

;; ============================================================================
;; Request ID Validation
;; ============================================================================

(defn validate-request-id
  "Validate that a request ID is unique within the session.
  Returns true if valid (or nil for notifications), false if duplicate."
  [state* request-id]
  (if (nil? request-id)
    true
    (let [[old _] (swap-vals! state* update :seen-request-ids conj request-id)]
      (not (contains? (:seen-request-ids old) request-id)))))

;; ============================================================================
;; Operation Cancellation Support
;; ============================================================================

(defn register-operation
  "Register an operation for cancellation tracking.
  Returns the call-id for chaining."
  [state* call-id]
  (swap! state* update :active-operations conj call-id)
  call-id)

(defn cancel-operation
  "Mark an operation as cancelled."
  [state* call-id]
  (swap! state* update :cancelled-operations conj call-id))

(defn is-cancelled?
  "Check if an operation is cancelled."
  [state* call-id]
  (contains? (:cancelled-operations @state*) call-id))

(defn unregister-operation
  "Unregister an operation when complete."
  [state* call-id]
  (swap! state* (fn [s]
                  (-> s
                      (update :active-operations disj call-id)
                      (update :cancelled-operations disj call-id)))))

;; ============================================================================
;; Resource Subscription Support
;; ============================================================================

(defn subscribe-to-resource
  "Subscribe to resource updates.
  Returns subscription ID for tracking."
  [state* uri]
  (let [sub-id (proto-util/generate-call-id)]
    (swap! state* update-in [:resource-subscriptions uri] (fnil conj #{}) sub-id)
    sub-id))

(defn unsubscribe-from-resource
  "Unsubscribe from resource updates."
  [state* uri sub-id]
  (swap! state* (fn [s]
                  (let [subs (disj (get-in s [:resource-subscriptions uri] #{}) sub-id)]
                    (if (empty? subs)
                      (update s :resource-subscriptions dissoc uri)
                      (assoc-in s [:resource-subscriptions uri] subs))))))

(defn get-resource-subscribers
  "Get all subscriber IDs for a resource URI."
  [state* uri]
  (get-in @state* [:resource-subscriptions uri] #{}))

(defn notify-resource-updated
  "Send resource updated notification to all subscribers.

  state* - Protocol state atom
  transport - Transport instance for sending notifications
  uri - Resource URI that was updated

  Sends notifications/resources/updated to all subscribers via transport."
  [state* transport uri]
  (when transport
    (let [subscribers (get-resource-subscribers state* uri)]
      (when (seq subscribers)
        (core/transport-send transport
          {:jsonrpc "2.0"
           :method "notifications/resources/updated"
           :params {:uri uri}})))))

;; ============================================================================
;; Elicitation Support (MCP 2025-11-25)
;; ============================================================================

(defn create-elicitation
  "Create a new elicitation request (server→client user input).

  MCP 2025-11-25 supports two modes:
  - Form mode: Structured data collection with JSON schema validation
  - URL mode: Out-of-band interactions via external URLs (OAuth, credentials)

  Args:
    state* - Protocol state atom
    message - Message to present to user
    opts - Options map (optional for backward compat with form mode):
      :mode - :form (default) or :url
      :schema - JSON Schema for form mode (primitives only per MCP spec)
      :url - URL for URL mode (required for URL mode)
      :elicitation-id - Optional custom ID (auto-generated if not provided)

  Returns elicitation ID for tracking.

  Examples:
    ;; Form mode (backward compatible)
    (create-elicitation state* \"Enter your name\" {:schema {:type \"object\" :properties {:name {:type \"string\"}}}})

    ;; URL mode (new in 2025-11-25)
    (create-elicitation state* \"Please authorize\" {:mode :url :url \"https://example.com/oauth\"})"
  ([state* message]
   (create-elicitation state* message nil))
  ([state* message opts]
   (let [;; Handle backward compat: if opts is a map with :type, it's a schema
         opts (if (and (map? opts) (:type opts))
                {:mode :form :schema opts}
                opts)
         elicit-id (or (:elicitation-id opts) (proto-util/generate-call-id))
         mode (or (:mode opts) :form)
         ;; Promise atom for async delivery — stored as a value inside the
         ;; immutable map. This is the one place a nested ref is warranted:
         ;; it's a one-shot delivery mechanism, not shared mutable state.
         promise-atom (atom nil)]
     (swap! state* assoc-in [:elicitation elicit-id]
            (cond-> {:message message
                     :mode mode
                     :timestamp (platform/now-ms)
                     :promise promise-atom}
              (= mode :form) (assoc :schema (:schema opts))
              (= mode :url) (assoc :url (:url opts))))
     elicit-id)))

(defn get-elicitation
  "Get elicitation state by ID."
  [state* elicit-id]
  (get-in @state* [:elicitation elicit-id]))

(defn elicit-response!
  "Record the client's response to an elicitation request.

  Args:
    state* - Protocol state atom
    elicit-id - Elicitation ID
    action - :accept, :decline, or :cancel
    content - Form data if accepted

  This should be called by the handler that receives the client's response."
  [state* elicit-id action content]
  (when-let [elicitation (get-in @state* [:elicitation elicit-id])]
    (let [response {:action action :content content}]
      (swap! state* update-in [:elicitation elicit-id] assoc
             :action action
             :content content
             :completed true)
      ;; Deliver to promise if it exists
      (when-let [promise-atom (:promise elicitation)]
        (reset! promise-atom response))
      response)))

(defn wait-for-elicitation
  "Block waiting for elicitation response (for use in tool handlers).

  Args:
    state* - Protocol state atom
    elicit-id - Elicitation ID
    timeout-ms - Maximum time to wait (default 60000ms = 1 minute)

  Returns response map with :action and :content, or nil if timeout."
  [state* elicit-id & [timeout-ms]]
  (let [timeout (or timeout-ms 60000)
        start-time (platform/now-ms)
        elicitation (get-in @state* [:elicitation elicit-id])
        promise-atom (:promise elicitation)]
    (loop []
      (if-let [response @promise-atom]
        response
        (let [elapsed (- (platform/now-ms) start-time)]
          (if (> elapsed timeout)
            nil  ; Timeout
            (do
              #?(:clj (Thread/sleep 100)
                 :cljs (js/setTimeout #() 100))
              (recur))))))))

(defn cancel-elicitation
  "Cancel an elicitation request."
  [state* elicit-id]
  (swap! state* update :elicitation dissoc elicit-id))

(defn notify-elicitation-complete
  "Send elicitation completion notification (for URL mode).

  Per MCP 2025-11-25: Servers MAY send this notification when URL mode
  elicitation completes, to inform the client that the user has finished
  the out-of-band interaction.

  Args:
    transport - Transport instance for sending notifications
    elicit-id - Elicitation ID that completed

  Sends notifications/elicitation/complete to client via transport."
  [transport elicit-id]
  (when transport
    (core/transport-send transport
      {:jsonrpc "2.0"
       :method "notifications/elicitation/complete"
       :params {:elicitationId elicit-id}})))

;; ============================================================================
;; Logging Support (MCP 2025-11-25)
;; ============================================================================

(def ^:private log-level-order
  "Log level ordering for filtering."
  {:debug 0
   :info 1
   :warning 2
   :error 3})

(defn set-session-log-level!
  "Set minimum log level for a session.

  Args:
    state* - Protocol state atom
    session-id - Session identifier (string or keyword)
    level - Minimum log level (:debug, :info, :warning, :error)

  Messages below this level will not be sent to the client."
  [state* session-id level]
  (swap! state* assoc-in [:session-log-levels session-id] level))

(defn get-session-log-level
  "Get minimum log level for a session.

  Returns the configured level or :debug (show all) if not set."
  [state* session-id]
  (get-in @state* [:session-log-levels session-id] :debug))

(defn should-send-log?
  "Check if a log message should be sent based on session's minimum level.

  Args:
    state* - Protocol state atom
    session-id - Session identifier
    level - Log level of the message

  Returns true if message level >= session minimum level."
  [state* session-id level]
  (let [min-level (get-session-log-level state* session-id)
        level-value (get log-level-order level 0)
        min-level-value (get log-level-order min-level 0)]
    (>= level-value min-level-value)))

(defn send-log-message
  "Send a log message notification to the client (with level filtering).

  state* - Protocol state atom
  transport - Transport instance for sending notifications
  level - Log level (:debug, :info, :warning, :error)
  message - Log message string
  data - Optional additional data map
  session-id - Optional session ID for filtering (defaults to :default)

  Sends notifications/message to client via transport if level >= session minimum."
  [state* transport level message & {:keys [data session-id]
                                      :or {session-id :default}}]
  (when (and transport (should-send-log? state* session-id level))
    (core/transport-send transport
      {:jsonrpc "2.0"
       :method "notifications/message"
       :params (cond-> {:level (name level)
                        :logger "defport"
                        :data message}
                 data (assoc :data data))})))

;; ============================================================================
;; Roots Support (MCP 2025-06-18)
;; ============================================================================

(defn handle-roots-list
  "Handle roots/list request - returns client filesystem roots.

  This is typically called BY the server TO the client,
  but we need to track roots the client has shared with us.

  Returns:
    {:roots [{:uri \"file:///workspace\" :name \"Project Root\"}]}"
  [state* _params _context]
  {:roots (:client-roots @state*)})

(defn update-client-roots!
  "Update the list of client roots (called when client notifies us).

  Called when client sends notifications/roots/list_changed."
  [state* new-roots]
  (swap! state* assoc :client-roots new-roots))

(defn get-roots
  "Get the current list of client roots.

  Returns vector of root maps with :uri and :name keys.

  Example:
    (get-roots state*)
    ;; => [{:uri \"file:///workspace\" :name \"Project\"}]"
  [state*]
  (:client-roots @state*))

(defn is-path-in-roots?
  "Check if a file path is within any client root.

  Example:
    (is-path-in-roots? state* \"/workspace/src/foo.clj\")
    ;; => true if /workspace is a root"
  [state* file-path]
  (let [roots (:client-roots @state*)]
    (boolean
      (some (fn [root]
              (let [root-uri (:uri root)
                    ;; Extract path from file:// URI
                    root-path (if (.startsWith root-uri "file://")
                                (subs root-uri 7)
                                root-uri)]
                (.startsWith file-path root-path)))
            roots))))

(defn validate-file-access
  "Validate that file access is within allowed roots.

  Throws exception if file is outside roots."
  [state* file-path]
  (when-not (is-path-in-roots? state* file-path)
    (throw (ex-info "File access denied: outside allowed roots"
                    {:code -32603
                     :file-path file-path
                     :roots (:client-roots @state*)}))))

;; ============================================================================
;; Sampling Support (MCP 2025-11-25)
;; ============================================================================

(defn create-sampling-request
  "Create a sampling request to send to client.

  Args:
    state* - Protocol state atom
    messages - Conversation messages (vector of maps with :role and :content)
    opts - Options map with:
      :model-preferences - Optional model hints
      :system-prompt - Optional system prompt
      :max-tokens - Token limit (default 1000)
      :tools - Optional vector of tool definitions for LLM to use (new in 2025-11-25)
      :tool-choice - Optional tool choice mode {:mode \"auto\"|\"required\"|\"none\"} (new in 2025-11-25)

  Returns:
    Sampling request ID (for tracking response)

  Example with tools (2025-11-25):
    (create-sampling-request state*
      [{:role \"user\" :content {:type \"text\" :text \"What's the weather?\"}}]
      {:tools [{:name \"get_weather\"
                :description \"Get current weather\"
                :inputSchema {:type \"object\"
                              :properties {:city {:type \"string\"}}
                              :required [\"city\"]}}]
       :tool-choice {:mode \"auto\"}})"
  [state* messages & [opts]]
  (let [request-id (proto-util/generate-call-id)
        request (cond-> {:messages messages
                         :maxTokens (or (:max-tokens opts) 1000)}
                  (:model-preferences opts)
                  (assoc :modelPreferences (:model-preferences opts))

                  (:system-prompt opts)
                  (assoc :systemPrompt (:system-prompt opts))

                  ;; MCP 2025-11-25: Tool calling support
                  (:tools opts)
                  (assoc :tools (:tools opts))

                  (:tool-choice opts)
                  (assoc :toolChoice (:tool-choice opts)))]

    (swap! state* assoc-in [:sampling request-id]
           {:request request
            :status :pending
            :timestamp (platform/now-ms)})
    request-id))

(defn send-sampling-request
  "Send sampling request to client via transport.

  Returns promise that resolves when client responds."
  [state* transport request-id]
  (let [request (get-in @state* [:sampling request-id :request])]
    ;; Send to client
    (core/transport-send transport
      {:jsonrpc "2.0"
       :id request-id
       :method "sampling/createMessage"
       :params request})

    ;; Create and store promise
    #?(:clj (let [p (promise)]
              (swap! state* assoc-in [:sampling request-id :promise] p)
              p)
       :cljs (let [resolve-fn (atom nil)
                   p (js/Promise.
                       (fn [resolve _reject]
                         (reset! resolve-fn resolve)))]
               (swap! state* assoc-in [:sampling request-id :promise] @resolve-fn)
               p))))

(defn handle-sampling-response
  "Handle client's response to sampling request.

  Called when client returns LLM completion."
  [state* request-id response]
  (when-let [entry (get-in @state* [:sampling request-id])]
    ;; Update state
    (swap! state* update-in [:sampling request-id] assoc
      :status :completed
      :response response)

    ;; Resolve promise
    #?(:clj (when-let [p (:promise entry)]
              (deliver p response))
       :cljs (when-let [resolve (:promise entry)]
                (resolve response)))

    response))

(defn wait-for-sampling-response
  "Block waiting for sampling response (for use in tool handlers).

  Args:
    state* - Protocol state atom
    request-id - Sampling request ID
    timeout-ms - Maximum time to wait (default 60000ms = 1 minute)

  Returns response map, or nil if timeout."
  [state* request-id & [timeout-ms]]
  (let [timeout (or timeout-ms 60000)
        start-time (platform/now-ms)]
    #?(:clj
       (let [p (get-in @state* [:sampling request-id :promise])]
         (if p
           (deref p timeout nil)
           nil))
       :cljs
       (loop []
         (let [entry (get-in @state* [:sampling request-id])
               elapsed (- (platform/now-ms) start-time)]
           (cond
             (= :completed (:status entry))
             (:response entry)

             (> elapsed timeout)
             nil

             :else
             (do
               (js/setTimeout #() 100)
               (recur))))))))

(defn cancel-sampling-request
  "Cancel a sampling request."
  [state* request-id]
  (swap! state* update :sampling dissoc request-id))

;; ============================================================================
;; Change Notification Support
;; ============================================================================

(defn enable-change-notifications!
  "Enable change notifications for a capability type.

  type - :tools, :prompts, or :resources"
  [state* type]
  (swap! state* assoc-in [:change-notifications type] true))

(defn change-notifications-enabled?
  "Check if change notifications are enabled for a type."
  [state* type]
  (get-in @state* [:change-notifications type] false))

(defn notify-tools-list-changed
  "Send tools/list_changed notification to client.

  state* - Protocol state atom
  transport - Transport instance for sending notifications

  Applications should call this when tools are added/removed/updated."
  [state* transport]
  (when (and transport (change-notifications-enabled? state* :tools))
    (core/transport-send transport
      {:jsonrpc "2.0"
       :method "notifications/tools/list_changed"})))

(defn notify-prompts-list-changed
  "Send prompts/list_changed notification to client.

  state* - Protocol state atom
  transport - Transport instance for sending notifications

  Applications should call this when prompts are added/removed/updated."
  [state* transport]
  (when (and transport (change-notifications-enabled? state* :prompts))
    (core/transport-send transport
      {:jsonrpc "2.0"
       :method "notifications/prompts/list_changed"})))

(defn notify-resources-list-changed
  "Send resources/list_changed notification to client.

  state* - Protocol state atom
  transport - Transport instance for sending notifications

  Applications should call this when resources are added/removed/updated."
  [state* transport]
  (when (and transport (change-notifications-enabled? state* :resources))
    (core/transport-send transport
      {:jsonrpc "2.0"
       :method "notifications/resources/list_changed"})))

;; ============================================================================
;; Content Formatting
;; ============================================================================

(defn- format-content
  "Format result as MCP content array.

  Handles multiple content types per MCP 2025-06-18 spec:
  - TextContent: Structured data (default) or plain text
  - ImageContent: Base64-encoded images
  - AudioContent: Base64-encoded audio
  - ResourceLink, EmbeddedResource: Resource references

  ObjectContent does NOT exist in the spec - all structured data must use TextContent.

  Args:
    result - Result from tool execution

  Returns:
    Vector of content objects

  Examples:
    ;; Structured data -> TextContent with JSON
    (format-content {:status \"ok\" :data [1 2 3]})
    ;; => [{:type \"text\" :text \"{\\\"status\\\":\\\"ok\\\",...}\"}]

    ;; Image content -> ImageContent (pass through)
    (format-content {:type \"image\" :data \"base64...\" :mimeType \"image/png\"})
    ;; => [{:type \"image\" :data \"base64...\" :mimeType \"image/png\"}]"
  [result]
  (cond
    ;; Image content (has :type "image")
    (content/valid-image-content? result)
    [result]

    ;; Audio content (has :type "audio")
    (content/valid-audio-content? result)
    [result]

    ;; Text content (has :type "text")
    (content/valid-text-content? result)
    [result]

    ;; Structured data -> TextContent with JSON
    :else
    [{:type "text"
      :text (platform/json-encode result)}]))

;; ============================================================================
;; MCP Method Handlers
;; ============================================================================

(defn handle-initialize
  "Handle MCP initialize request.
  Returns protocol version and server capabilities."
  [_params context server-info]
  (let [subscriptions-enabled? (get context :enable-subscriptions? true)]
    {:protocolVersion "2025-11-25"
     :serverInfo (or server-info {:name "defport-mcp-server" :version "0.1.0"})
     :capabilities (cond-> {:tools {:listChanged true}
                            :prompts {:listChanged true}
                            :resources {:subscribe subscriptions-enabled?
                                       :listChanged true}
                            :roots {:listChanged false}
                            ;; MCP 2025-11-25: sampling with tools support
                            :sampling {:tools {}}
                            ;; MCP 2025-11-25: elicitation with form and url modes
                            :elicitation {:form {} :url {}}
                            :completion {}
                            :logging {}}
                     ;; Add refactoring capability if enabled
                     (get context :refactoring-enabled?)
                     (assoc :refactoring {:enabled true}))}))

(defn handle-tools-list
  "Handle tools/list request with pagination.
  Filters dangerous tools unless refactoring is enabled or custom filter provided."
  [params context]
  (let [cursor (:cursor params)
        registry (:port-registry context)
        all-ports (core/list-ports registry)
        refactoring-enabled? (get context :refactoring-enabled? false)
        tool-filter (or (:tool-filter context) identity)

        ;; Filter for tool ports (exclude prompts and resources)
        tool-ports (filter (fn [port-def]
                            (not (or (get-in port-def [:metadata :prompt])
                                    (get-in port-def [:metadata :resource]))))
                          all-ports)

        ;; Apply dangerous tool filtering (hybrid approach)
        filtered-ports (if refactoring-enabled?
                        tool-ports
                        (remove #(get-in % [:metadata :dangerous]) tool-ports))

        ;; Apply custom tool filter if provided
        final-ports (tool-filter filtered-ports)

        ;; Convert ports to MCP tool format
        all-tools (mapv (fn [port-def]
                          (cond-> {:name (name (:id port-def))
                                   :description (:description port-def "")
                                   :inputSchema (:input-schema port-def {})}
                            (:annotations (:metadata port-def))
                            (assoc :annotations (:annotations (:metadata port-def)))
                            ;; MCP 2025-11-25: icons support
                            (:icons (:metadata port-def))
                            (assoc :icons (:icons (:metadata port-def)))))
                        final-ports)
        ;; MCP spec recommends page size of 10, but allow override via context
        page-size (or (:page-size context) 10)
        paginated (pagination/paginate-items all-tools cursor {:page-size page-size})]
    (cond-> {:tools (:items paginated)}
      (:nextCursor paginated) (assoc :nextCursor (:nextCursor paginated)))))

(defn handle-tools-call
  "Handle tools/call request with progress and cancellation support.
  Emits tap> events: :mcp/tool-call, :mcp/operation-cancelled, :mcp/error"
  [params context]
  (let [state* (:state* context)
        tool-name (:name params)
        tool-params (:arguments params {})
        registry (:port-registry context)
        call-id (proto-util/generate-call-id)
        progress-token (get-in params [:_meta :progressToken])
        start-time (platform/now-ms)]

    (if (nil? tool-name)
      (do
        (emit-event! :mcp/error {:method "tools/call"
                                 :error-code -32602
                                 :error-message "Invalid params: missing tool name"})
        {:error {:code -32602 :message "Invalid params: missing tool name"}})

      (let [port (core/get-port registry (keyword tool-name))]
        (if (nil? port)
          (do
            (emit-event! :mcp/error {:method "tools/call"
                                     :tool tool-name
                                     :error-code -32602
                                     :error-message (str "Unknown tool: " tool-name)})
            {:error {:code -32602 :message (str "Unknown tool: " tool-name)}})

          (platform/try-any
            (register-operation state* call-id)

            ;; Check if cancelled before starting
            (if (is-cancelled? state* call-id)
              (do
                (emit-event! :mcp/operation-cancelled {:tool tool-name :call-id call-id})
                {:error {:code -32800 :message "Operation was cancelled"}})

              (let [;; Create progress callback if token provided
                    progress-callback (when progress-token
                                       (progress/create-progress-callback
                                         progress-token
                                         (:transport context)))

                    ;; Create cancellation check
                    cancellation-check (fn [] (is-cancelled? state* call-id))

                    ;; Build execution context
                    exec-context (assoc context
                                   :params tool-params
                                   :metadata {:call-id call-id
                                             :progress-token progress-token
                                             :progress-callback progress-callback
                                             :cancellation-check cancellation-check})

                    ;; Execute the port. Unwrap any user-supplied async type
                    ;; (promise/future/delay/manifold-deferred) back to a plain
                    ;; value before formatting the response.
                    result (platform/unwrap (core/port-execute port exec-context))
                    duration-ms (- (platform/now-ms) start-time)]

                ;; Check if cancelled during execution
                (if (is-cancelled? state* call-id)
                  (do
                    (emit-event! :mcp/operation-cancelled {:tool tool-name
                                                          :call-id call-id
                                                          :duration-ms duration-ms})
                    {:error {:code -32800 :message "Operation was cancelled"}})

                  ;; Return result
                  (if-let [err (:error result)]
                    (do
                      (emit-event! :mcp/tool-call {:tool tool-name
                                                   :call-id call-id
                                                   :success? false
                                                   :error-code (:code err)
                                                   :error-message (:message err)
                                                   :duration-ms duration-ms})
                      {:error err})
                    ;; Success
                    (do
                      (emit-event! :mcp/tool-call {:tool tool-name
                                                   :call-id call-id
                                                   :success? true
                                                   :duration-ms duration-ms})
                      ;; MCP expects content array (TextContent, ImageContent, or AudioContent)
                      ;; Use format-content to serialize structured data as TextContent with JSON.
                      ;; Forward :metadata if the handler provided it (e.g., sampling requests).
                      (cond-> {:content (or (:content result)
                                            (format-content (:result result)))}
                        (:metadata result) (assoc :metadata (:metadata result))))))))

            (catch-any e
              (let [duration-ms (- (platform/now-ms) start-time)
                    error-msg (platform/error-message e)]
                (emit-event! :mcp/tool-call {:tool tool-name
                                             :call-id call-id
                                             :success? false
                                             :error-code -32603
                                             :error-message error-msg
                                             :duration-ms duration-ms})
                {:error {:code -32603
                         :message (str "Internal error: " error-msg)}}))

            (finally
              (unregister-operation state* call-id))))))))

(defn handle-tools-call-cancel
  "Handle tools/call/cancel request."
  [params context]
  (let [state* (:state* context)
        call-id (:callId params)]
    (if (nil? call-id)
      {:error {:code -32602 :message "Invalid params: missing callId"}}
      (if (contains? (:active-operations @state*) call-id)
        (do
          (cancel-operation state* call-id)
          {})  ; Success
        {:error {:code -32602 :message (str "Operation not found: " call-id)}}))))

(defn handle-prompts-list
  "Handle prompts/list request with pagination.
  Prompts are ports with :prompt metadata."
  [params context]
  (let [cursor (:cursor params)
        registry (:port-registry context)
        all-ports (core/list-ports registry)
        ;; Filter ports with :prompt metadata
        prompt-ports (filter #(get-in % [:metadata :prompt]) all-ports)
        ;; Convert to MCP prompt format
        all-prompts (mapv (fn [port-def]
                            (cond-> {:name (name (:id port-def))
                                     :description (:description port-def "")
                                     :arguments (:prompt-args (:metadata port-def) [])}
                              ;; MCP 2025-11-25: icons support
                              (:icons (:metadata port-def))
                              (assoc :icons (:icons (:metadata port-def)))))
                          prompt-ports)
        ;; MCP spec recommends page size of 10, but allow override via context
        page-size (or (:page-size context) 10)
        paginated (pagination/paginate-items all-prompts cursor {:page-size page-size})]
    (cond-> {:prompts (:items paginated)}
      (:nextCursor paginated) (assoc :nextCursor (:nextCursor paginated)))))

(defn handle-prompts-get
  "Handle prompts/get request."
  [params context]
  (let [prompt-name (:name params)
        prompt-args (:arguments params {})]
    (if (nil? prompt-name)
      {:error {:code -32602 :message "Invalid params: missing prompt name"}}

      (let [registry (:port-registry context)
            port (core/get-port registry (keyword prompt-name))]
        (if (nil? port)
          {:error {:code -32602 :message (str "Unknown prompt: " prompt-name)}}

          (if-not (get-in (core/port-schema port) [:metadata :prompt])
            {:error {:code -32602 :message (str "Not a prompt: " prompt-name)}}

            (platform/try-any
              (let [exec-context (assoc context :params prompt-args)
                    ;; Unwrap any user-supplied async type before formatting.
                    result (platform/unwrap (core/port-execute port exec-context))]
                (if-let [err (:error result)]
                  {:error err}
                  ;; Prompts return messages array
                  {:messages (or (:messages result)
                                 [{:role "user"
                                   :content {:type "text"
                                            :text (platform/json-encode (:result result))}}])}))

              (catch-any e
                {:error {:code -32603
                         :message (str "Internal error: "
                                       (platform/error-message e))}}))))))))

(defn handle-resources-list
  "Handle resources/list request with pagination.
  Resources are ports with :resource metadata.

  Context options:
    :uri-scheme - Custom URI scheme (default: \"defport\")
                  Example: \"defnet\" produces URIs like \"defnet://function-id\""
  [params context]
  (let [cursor (:cursor params)
        registry (:port-registry context)
        uri-scheme (or (:uri-scheme context) "defport")
        all-ports (core/list-ports registry)
        ;; Filter ports with :resource metadata
        resource-ports (filter #(get-in % [:metadata :resource]) all-ports)
        ;; Convert to MCP resource format
        all-resources (mapv (fn [port-def]
                              (cond-> {:uri (str uri-scheme "://" (name (:id port-def)))
                                       :name (name (:id port-def))
                                       :description (:description port-def "")
                                       :mimeType (get-in port-def [:metadata :mime-type] "application/json")}
                                ;; MCP 2025-11-25: icons support
                                (:icons (:metadata port-def))
                                (assoc :icons (:icons (:metadata port-def)))))
                            resource-ports)
        ;; MCP spec recommends page size of 10, but allow override via context
        page-size (or (:page-size context) 10)
        paginated (pagination/paginate-items all-resources cursor {:page-size page-size})]
    (cond-> {:resources (:items paginated)}
      (:nextCursor paginated) (assoc :nextCursor (:nextCursor paginated)))))

(defn handle-resources-read
  "Handle resources/read request.

  Context options:
    :uri-scheme - Custom URI scheme (default: \"defport\")
                  Used to parse incoming URIs"
  [params context]
  (let [uri (:uri params)
        uri-scheme (or (:uri-scheme context) "defport")
        uri-prefix (str uri-scheme "://")]
    (if (nil? uri)
      {:error {:code -32602 :message "Invalid params: missing resource URI"}}

      ;; Parse URI (scheme://port-id format)
      (if-let [port-id (when (.startsWith uri uri-prefix)
                        (keyword (subs uri (count uri-prefix))))]
        (let [registry (:port-registry context)
              port (core/get-port registry port-id)]
          (if (nil? port)
            {:error {:code -32602 :message (str "Unknown resource: " uri)}}

            (if-not (get-in (core/port-schema port) [:metadata :resource])
              {:error {:code -32602 :message (str "Not a resource: " uri)}}

              (platform/try-any
                (let [exec-context (assoc context :params {:uri uri})
                      ;; Unwrap any user-supplied async type before formatting.
                      result (platform/unwrap (core/port-execute port exec-context))]
                  (if-let [err (:error result)]
                    {:error err}
                    ;; Resources return contents array
                    {:contents (or (:contents result)
                                   [{:uri uri
                                     :mimeType "application/json"
                                     :text (platform/json-encode (:result result))}])}))

                (catch-any e
                  {:error {:code -32603
                           :message (str "Internal error: "
                                         (platform/error-message e))}})))))

        {:error {:code -32602 :message (str "Invalid resource URI: " uri)}}))))

(defn handle-resources-subscribe
  "Handle resources/subscribe request.
  Emits tap> event: :mcp/subscription-added"
  [params context]
  (let [state* (:state* context)
        uri (:uri params)]
    (if (nil? uri)
      {:error {:code -32602 :message "Invalid params: missing resource URI"}}
      (let [sub-id (subscribe-to-resource state* uri)]
        (emit-event! :mcp/subscription-added {:uri uri :subscription-id sub-id})
        {}))))  ; Success - empty result

(defn handle-resources-unsubscribe
  "Handle resources/unsubscribe request.
  Emits tap> event: :mcp/subscription-removed"
  [params context]
  (let [state* (:state* context)
        uri (:uri params)
        sub-id (get-in context [:metadata :subscription-id])]  ; Apps track this
    (if (nil? uri)
      {:error {:code -32602 :message "Invalid params: missing resource URI"}}
      (do
        (emit-event! :mcp/subscription-removed {:uri uri :subscription-id sub-id})
        (unsubscribe-from-resource state* uri sub-id)
        {}))))  ; Success - empty result

(defn handle-elicitation-create
  "Handle elicitation/create request (server→client user input).

  Per MCP 2025-11-25 spec, supports two modes:

  Form mode (default):
  - Server requests structured user input via JSON Schema
  - Client presents form/UI to user
  - Client responds with accept/decline/cancel + form data

  URL mode (new in 2025-11-25):
  - Server provides URL for out-of-band interaction
  - Used for OAuth flows, credential entry, etc.
  - Data does NOT pass through MCP client (security)

  This is typically called FROM a tool handler via the elicit! DSL helper.

  Params:
    :mode - \"form\" (default) or \"url\"
    :message - Message to present to user (required)
    :requestedSchema - JSON Schema for form mode
    :url - URL for URL mode
    :elicitationId - Custom ID for URL mode (auto-generated if not provided)

  Returns:
    {:elicitationId <id>} - ID for tracking the elicitation

  Note: The actual response comes later via client's separate call."
  [params context]
  (let [state* (:state* context)
        mode (keyword (or (:mode params) "form"))
        message (:message params)
        schema (:requestedSchema params)
        url (:url params)
        custom-id (:elicitationId params)]
    (cond
      ;; Message is always required
      (nil? message)
      {:error {:code -32602 :message "Invalid params: message required"}}

      ;; Form mode requires schema
      (and (= mode :form) (nil? schema))
      {:error {:code -32602 :message "Invalid params: requestedSchema required for form mode"}}

      ;; URL mode requires url
      (and (= mode :url) (nil? url))
      {:error {:code -32602 :message "Invalid params: url required for URL mode"}}

      :else
      (let [elicit-id (create-elicitation state* message
                        (cond-> {:mode mode}
                          (= mode :form) (assoc :schema schema)
                          (= mode :url) (assoc :url url)
                          custom-id (assoc :elicitation-id custom-id)))]
        {:elicitationId elicit-id}))))

(defn handle-elicitation-submit
  "Handle client's response to elicitation (client→server).

  Params:
    :elicitationId - ID of the elicitation
    :action - \"accept\", \"decline\", or \"cancel\"
    :content - Form data (if accepted)

  Returns empty result on success."
  [params context]
  (let [state* (:state* context)
        elicit-id (:elicitationId params)
        action (keyword (:action params))
        content (:content params)]
    (if (nil? elicit-id)
      {:error {:code -32602 :message "Invalid params: elicitationId required"}}
      (do
        (elicit-response! state* elicit-id action content)
        {}))))

(defn handle-elicitation-cancel
  "Handle elicitation cancellation (client→server).

  Params:
    :elicitationId - ID of the elicitation to cancel

  Returns empty result on success."
  [params context]
  (let [state* (:state* context)
        elicit-id (:elicitationId params)]
    (if (nil? elicit-id)
      {:error {:code -32602 :message "Invalid params: elicitationId required"}}
      (do
        (cancel-elicitation state* elicit-id)
        {}))))

(defn handle-completion-complete
  "Handle completion/complete request for argument autocomplete.

  Per MCP 2025-06-18 spec:
  - Client requests completions for a partial argument value
  - Server returns suggested completions based on context

  Params:
    :ref - Reference map with :type and :name (tool/prompt/resource)
    :argument - Map with :name and :value (partial input)
    :context - Map with :arguments (previously entered values)

  Returns:
    {:completion {:values [...] :total N :hasMore boolean}}"
  [params context]
  (let [ref (:ref params)
        arg-name (get-in params [:argument :name])
        arg-value (get-in params [:argument :value] "")
        prev-args (get-in params [:context :arguments] {})

        ;; Get the referenced port
        port-type (:type ref)
        port-name (:name ref)
        port-id (keyword port-name)]

    (if (or (nil? ref) (nil? arg-name))
      {:error {:code -32602 :message "Invalid params: ref and argument.name required"}}

      (let [registry (:port-registry context)
            port (core/get-port registry port-id)]
        (if (nil? port)
          {:error {:code -32602 :message (str "Unknown port: " port-name)}}

          ;; Get completion function from port metadata
          (let [port-schema (core/port-schema port)
                completion-fn (get-in port-schema [:metadata :completions (keyword arg-name)])]
            (if completion-fn
              (platform/try-any
                ;; Call completion function with partial value and context
                (let [values (completion-fn arg-value prev-args)
                      ;; Ensure values is a vector of strings
                      value-strs (mapv str values)]
                  {:completion {:values value-strs
                                :total (count value-strs)
                                :hasMore false}})
                (catch-any e
                  {:error {:code -32603
                           :message (str "Completion error: "
                                         (platform/error-message e))}}))
              ;; No completion function defined
              {:completion {:values []
                            :total 0
                            :hasMore false}})))))))

(defn handle-logging-set-level
  "Handle logging/setLevel request (MCP 2025-06-18).

  Allows client to set minimum log level for the session.
  Only messages at or above this level will be sent.

  Params:
    :level - Log level string (\"debug\", \"info\", \"warning\", \"error\")

  Returns empty result on success."
  [params context]
  (let [state* (:state* context)
        level-str (:level params)
        level (when level-str (keyword level-str))
        session-id (or (get-in context [:session :id]) :default)]
    (if (and level (contains? log-level-order level))
      (do
        (set-session-log-level! state* session-id level)
        {})
      {:error {:code -32602
               :message (str "Invalid params: level must be one of debug, info, warning, error")}})))

(defn handle-ping
  "Handle ping request per MCP 2025-06-18 spec.

  Per spec: Receiver MUST respond promptly with an empty response.
  This allows either party to confirm the connection remains active."
  [_params _context _server-info]
  {})

;; ============================================================================
;; MCP Protocol Adapter Implementation
;; ============================================================================

(defrecord McpAdapter [server-info method-handlers* adapter-opts state*]
  core/ProtocolAdapter

  (protocol-id [_]
    :mcp)

  (protocol-version [_]
    "2025-11-25")

  (protocol-capabilities [_ port-registry]
    (let [ports (core/list-ports port-registry)
          has-prompts? (some #(get-in % [:metadata :prompt]) ports)
          has-resources? (some #(get-in % [:metadata :resource]) ports)
          refactoring-enabled? (:refactoring-enabled? adapter-opts)
          subscriptions-enabled? (get adapter-opts :enable-subscriptions? true)]
      (cond-> {:tools {:listChanged true}
               :prompts (when has-prompts? {:listChanged true})
               :resources (when has-resources?
                           {:subscribe subscriptions-enabled?
                            :listChanged true})
               :roots {:listChanged false}
               ;; MCP 2025-11-25: sampling with tools support
               :sampling {:tools {}}
               ;; MCP 2025-11-25: elicitation with form and url modes
               :elicitation {:form {} :url {}}
               :completion {}
               :logging {}}
        refactoring-enabled?
        (assoc :refactoring {:enabled true}))))

  (protocol-dispatch [this method params context]
    (let [handlers @method-handlers*
          handler (get handlers method)
          ;; Enrich context with adapter options AND instance state
          enriched-context (assoc (merge context adapter-opts)
                                 :state* state*)]
      (if handler
        (platform/try-any
          ;; Validate request ID if present
          (when-let [request-id (:id (:request enriched-context))]
            (when-not (validate-request-id state* request-id)
              (throw (ex-info "Duplicate request ID"
                              {:code -32600
                               :message "Invalid Request: duplicate request ID"}))))

          ;; Call handler with enriched context
          (handler params enriched-context)

          (catch-any e
            {:error {:code -32603
                     :message (str "Internal error: "
                                   (platform/error-message e))}}))

        ;; Unknown method
        {:error {:code -32601 :message (str "Method not found: " method)}}))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn create-mcp-adapter
  "Create an MCP protocol adapter with hybrid security model.

  Options:
  - :server-info - Map with :name, :version, and optional :description (MCP 2025-11-25)
  - :custom-handlers - Map of method name -> handler fn (overrides defaults)
  - :enable-refactoring - Boolean to enable dangerous tools (default: check DEFPORT_ENABLE_REFACTORING env var)
  - :tool-filter - Custom filter fn (fn [ports] -> filtered-ports) to override default filtering
  - :uri-scheme - Custom URI scheme for resources (default: \"defport\")
                  Example: \"defnet\" produces URIs like \"defnet://resource-id\"
  - :state* - Optional externally-provided protocol state atom (for shared state scenarios).
              If omitted, a fresh state is created per adapter (recommended).
  - :performance - Performance configuration map with:
    - :batch-processing - Batch processing options:
      - :enabled - Enable concurrent batch processing (default: false)
      - :strategy - :sequential | :pmap | :futures | :core-async (default: :sequential)
      - :max-concurrency - Max parallel operations (default: 10, for :core-async)
      - :timeout-ms - Timeout per item or overall (default: 30000)

  Hybrid Security Model:
  - By default, tools with :dangerous metadata are filtered from tools/list
  - Set :enable-refactoring true or DEFPORT_ENABLE_REFACTORING=true to include dangerous tools
  - Provide custom :tool-filter for application-specific filtering logic
  - Applications mark dangerous tools via {:metadata {:dangerous true}}

  Performance Configuration:
  - Default: Sequential batch processing (backward compatible)
  - Opt-in: Enable concurrent processing for 5-10x speedup on batch operations
  - See defport.util.batch for strategy details

  Returns McpAdapter instance implementing ProtocolAdapter protocol.

  Examples:
    ;; Default (safe mode - dangerous tools filtered, own state)
    (def adapter (create-mcp-adapter))

    ;; Enable refactoring via options
    (def adapter (create-mcp-adapter {:enable-refactoring true}))

    ;; Custom URI scheme for resources
    (def adapter (create-mcp-adapter {:uri-scheme \"myapp\"}))
    ;; => Resources will have URIs like \"myapp://resource-id\"

    ;; Custom tool filter
    (def adapter (create-mcp-adapter
                   {:tool-filter (fn [tools]
                                   (if (user-has-permission? :refactor)
                                     tools
                                     (remove dangerous? tools)))}))

    ;; Enable concurrent batch processing
    (def adapter (create-mcp-adapter
                   {:performance {:batch-processing {:enabled true
                                                     :strategy :pmap}}}))

    ;; With controlled concurrency
    (def adapter (create-mcp-adapter
                   {:performance {:batch-processing {:enabled true
                                                     :strategy :core-async
                                                     :max-concurrency 10}}}))

    ;; Environment variable control (set DEFPORT_ENABLE_REFACTORING=true)
    (def adapter (create-mcp-adapter))"
  ([]
   (create-mcp-adapter nil))

  ([opts]
   (let [server-info (or (:server-info opts)
                        {:name "defport-mcp-server"
                         :version "0.1.0"
                         :description "MCP server built with defport"})
         custom-handlers (:custom-handlers opts {})

         ;; Hybrid approach: check option first, then env var, default false
         refactoring-enabled? (if (contains? opts :enable-refactoring)
                               (:enable-refactoring opts)
                               refactoring-enabled?)

         ;; Resource subscriptions enabled by default
         subscriptions-enabled? (get opts :enable-subscriptions? true)

         ;; Performance options (default to sequential)
         performance (merge {:batch-processing {:enabled false
                                                :strategy :sequential
                                                :max-concurrency 10
                                                :timeout-ms 30000}}
                           (:performance opts))

         ;; URI scheme for resource URIs (default: "defport")
         ;; Allows apps to use custom schemes like "defnet://"
         uri-scheme (or (:uri-scheme opts) "defport")

         ;; Build adapter options for context enrichment
         adapter-opts {:refactoring-enabled? refactoring-enabled?
                      :tool-filter (:tool-filter opts)
                      :enable-subscriptions? subscriptions-enabled?
                      :performance performance
                      :uri-scheme uri-scheme}

         ;; Per-adapter protocol state — no global sharing
         state* (or (:state* opts) (create-protocol-state))

         ;; Default method handlers derived from defport.mcp.spec. For
         ;; each spec entry with a :handler-sym, resolve the symbol to
         ;; a var and install it under the wire method string. Three
         ;; methods need closure wrapping because their handlers take
         ;; extra implicit arguments (server-info / state*) that the
         ;; dispatcher doesn't know about; those overrides take
         ;; precedence over the spec-derived entries.
         spec-derived-handlers
         (reduce (fn [acc [wire-method handler-sym]]
                   (if-let [v (resolve handler-sym)]
                     (assoc acc wire-method @v)
                     acc))
                 {}
                 (spec/default-handler-syms))

         ;; Overrides for handlers that need closure over server-info
         ;; or state* — these three can't be flat (2-arity params+ctx)
         ;; fns without an API change to their handler fn.
         closure-wrapped-handlers
         {"initialize" (fn [p ctx] (handle-initialize p ctx server-info))
          "ping"       (fn [p ctx] (handle-ping p ctx server-info))
          "roots/list" (fn [p ctx] (handle-roots-list state* p ctx))
          ;; Inline MCP Inspector extension: no spec entry carries the
          ;; lambda, so supply it here.
          "resources/templates/list" (fn [_ _] {:resourceTemplates []})}

         default-handlers (merge spec-derived-handlers closure-wrapped-handlers)

         ;; Merge custom handlers last so they win
         method-handlers (merge default-handlers custom-handlers)]

     (->McpAdapter server-info (atom method-handlers) adapter-opts state*))))

(defn register-custom-handler!
  "Register or override a custom method handler.

  adapter - McpAdapter instance
  method - Method name string (e.g., \"custom/analyze\")
  handler - Handler fn with signature: (fn [params context] -> result)

  Example:
    (register-custom-handler! adapter \"custom/analyze\"
      (fn [params context]
        {:result \"analyzed\"}))"
  [adapter method handler]
  (swap! (:method-handlers* adapter) assoc method handler))

;; ============================================================================
;; Performance Configuration Accessors
;; ============================================================================

(defn get-batch-strategy
  "Get the batch processing strategy from adapter.

  Args:
    adapter - McpAdapter instance

  Returns:
    Keyword - :sequential | :pmap | :futures | :core-async"
  [adapter]
  (get-in (:adapter-opts adapter) [:performance :batch-processing :strategy] :sequential))

(defn get-batch-opts
  "Get batch processing options from adapter.

  Args:
    adapter - McpAdapter instance

  Returns:
    Map with batch options ready to pass to batch/process-batch"
  [adapter]
  (batch/get-batch-opts (:performance (:adapter-opts adapter))))

(defn batch-enabled?
  "Check if batch processing is enabled in adapter.

  Args:
    adapter - McpAdapter instance

  Returns:
    Boolean - true if batch processing is explicitly enabled"
  [adapter]
  (get-in (:adapter-opts adapter) [:performance :batch-processing :enabled] false))

;; ============================================================================
;; MCP Client Implementation (ProtocolClient)
;; ============================================================================


;; ============================================================================
;; Client mode — not included
;; ============================================================================
;;
;; Defport does not ship an MCP client for "my program wants to talk to
;; an external MCP server." That use case — spawning a subprocess MCP
;; server and driving it from your Clojure code — is application concern,
;; not library concern. Bring your own subprocess primitive (ProcessBuilder,
;; babashka.process, Node child_process), your own transport, and your
;; own concurrency model.
;;
;; What defport DOES provide for client-role needs:
;;
;;   - The defport.core/ProtocolClient protocol — the contract a client
;;     must satisfy. Implement it for your own needs.
;;
;;   - Server-initiated request support inside defport.mcp/McpAdapter:
;;     when running as a server, the adapter can send sampling/elicitation
;;     requests TO its connected client using the existing transport.
;;     See `send-sampling-request`, `create-elicitation`, etc. above.
;;
;; Everything else — McpClient record, create-mcp-client, and the
;; client-list-tools / client-call-tool / etc. convenience wrappers —
;; was removed. It's ~350 lines of untested speculative code that
;; belongs in user applications if they need it.

;; ============================================================================
;; Progressive-disclosure DSL — deftool / defprompt / defresource
;; ============================================================================
;;
;; Thin wrappers around `defport.sugar/define-port` that stamp the
;; right MCP metadata. The underlying machinery (param parsing, schema
;; generation, handler wrapping, registry registration) lives in
;; defport.sugar.
;;
;; Usage:
;;   (require '[defport.mcp :refer [deftool defprompt defresource run!]])
;;
;;   (deftool greet
;;     "Greet a user by name."
;;     [name :- :string]
;;     {:greeting (str "Hi, " name)})
;;
;;   (run! {:server-info {:name "my-server" :version "1.0"}
;;          :transport :stdio})

(defn- parse-mcp-macro-args
  "Parse arguments for deftool / defprompt / defresource.

  Accepts two orders, both common in existing defport code:

    Clojure-style (doc first):
      (deftool name doc? options? params body...)

    Classic MCP-style (params first):
      (deftool name options? params doc? body...)

  Returns [doc options params body] for emitting a define-port call."
  [args]
  ;; First, pull off an optional leading docstring.
  (let [[doc args] (if (string? (first args)) [(first args) (rest args)] [nil args])
        ;; Optional options map.
        [opts args] (if (and (map? (first args))
                              ;; Don't confuse metadata (namespaced keys) with options.
                              (not (some #(and (keyword? %) (namespace %))
                                         (keys (first args)))))
                      [(first args) (rest args)]
                      [nil args])
        ;; Params vector (required).
        [params args] [(first args) (rest args)]
        ;; If doc wasn't at the front, it may be between params and body
        ;; (the classic MCP convention).
        [doc body] (if (and (nil? doc) (string? (first args)))
                     [(first args) (rest args)]
                     [doc args])]
    [doc opts params body]))

(defmacro deftool
  "Define an MCP tool.

  Usage:
    (deftool add [a :- :int b :- :int]
      \"Add two numbers\"
      (+ a b))

  With context injection:
    (deftool process [uri :- :string ctx :- :context]
      \"Process a URI\"
      (log ctx :info \"Working...\")
      ...)

  With options:
    (deftool add {:tags #{:math}}
      [a :- :int b :- :int]
      \"Add numbers\"
      (+ a b))

  With Malli schema:
    (deftool search [:map [:query :string] [:limit {:optional true} :int]]
      \"Search code\"
      (do-search query limit))"
  [tool-name & args]
  (let [[doc opts params body] (parse-mcp-macro-args args)]
    `(sugar/define-port ~tool-name
       ~@(when doc [doc])
       ~@(when opts [opts])
       {:mcp/tool true}
       ~params
       ~@body)))

(defmacro defprompt
  "Define an MCP prompt.

  Usage:
    (defprompt summarize [text :- :string]
      \"Summarization prompt\"
      [{:role \"user\"
        :content {:type \"text\" :text (str \"Summarize: \" text)}}])"
  [prompt-name & args]
  (let [[doc opts params body] (parse-mcp-macro-args args)]
    `(sugar/define-port ~prompt-name
       ~@(when doc [doc])
       ~@(when opts [opts])
       {:mcp/prompt true}
       ~params
       ~@body)))

(defmacro defresource
  "Define an MCP resource.

  Usage:
    (defresource schema
      \"Current database schema\"
      {:mime-type \"application/edn\"}
      []
      (get-current-schema))"
  [resource-name & args]
  (let [[doc opts params body] (parse-mcp-macro-args args)]
    `(sugar/define-port ~resource-name
       ~@(when doc [doc])
       ~@(when opts [opts])
       {:mcp/resource true}
       ~(or params [])
       ~@body)))

;; ============================================================================
;; Adapter multimethod registration
;; ============================================================================

(defmethod sugar/create-adapter :mcp
  [_protocol opts]
  (create-mcp-adapter opts))

;; ============================================================================
;; Top-level run! convenience
;; ============================================================================

(defn run!
  "Start an MCP server on the given transport.

  Opts:
    :server-info - {:name ... :version ...} (default: derived from main ns)
    :transport   - :stdio (default), :http, or a pre-built Transport
    :port        - HTTP port (for :http transport, default 8080)
    :registry    - PortRegistry instance (default: defport.sugar/*registry*)

  Ports registered via deftool / defprompt / defresource (or via
  sugar/define-port with {:mcp/*} metadata) are exposed via this server.

  For multi-adapter scenarios (MCP + LSP + DAP in one process), skip
  this and instantiate adapters/transports directly against a shared
  registry.

  Returns a map {:adapter ... :transport ... :registry ...} that can
  be passed to stop! to tear down."
  [opts]
  (sugar/run! (assoc opts :protocol :mcp)))

(defn stop!
  "Stop an MCP server started with run!.

  Pass the map returned by run!. Idempotent."
  [server]
  (sugar/stop! server))
