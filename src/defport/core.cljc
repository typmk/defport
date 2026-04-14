(ns defport.core
  "Core protocols for defport - a platform-agnostic protocol adapter framework.

  Defport enables building protocol clients AND servers (MCP, LSP, DAP, custom)
  using an EDN-driven Ports & Adapters architecture. The framework is:
  - Protocol-agnostic (MCP, LSP, DAP, custom)
  - Role-agnostic (client or server)
  - Platform-agnostic (.cljc compatible)

  ## Bidirectional Architecture

  The same adapter can operate in both roles:
  - **Server role**: Receives requests via `protocol-dispatch`, sends responses
  - **Client role**: Sends requests via `protocol-request`, receives responses

  Both roles can handle incoming requests from the other side:
  - Server can send sampling/elicitation requests TO client
  - Client handles these via registered handlers")

;;; Core Abstractions

(defprotocol Port
  "A port represents a capability, tool, or operation that can be exposed via protocols.

  Ports are protocol-agnostic - the same port can be exposed via MCP, LSP, DAP,
  or custom protocols. The protocol adapter handles translation between protocol-specific
  message formats and the universal port interface.

  Example:
    A 'find-callers' port can be exposed as:
    - MCP tools/call method
    - LSP workspace/symbol method
    - Custom protocol 'code/findCallers' method"

  (port-id [this]
    "Unique identifier for this port (keyword).")

  (port-schema [this]
    "Input/output schema for this port. Returns a map with:
     {:input-schema {...}  ; JSON Schema or Malli schema
      :output-schema {...}}")

  (port-execute [this context]
    "Execute the port operation.

    Context map contains:
    - :params - Input parameters (validated against input-schema)
    - :protocol - Protocol identifier (:mcp, :lsp, :dap, :custom)
    - :transport - Transport identifier (:stdio, :http, :websocket)
    - :metadata - Protocol-specific metadata (progress-token, request-id, etc.)
    - :system - System state (database connection, config, etc.)

    Returns:
    - Success: {:result <data> :metadata {...}}
    - Error: {:error {:code <int> :message <str> :data <any>}}"))

(defprotocol Transport
  "Transport layer abstraction for protocol communication.

  Transports handle the low-level message delivery (stdio, HTTP, WebSocket, etc.)
  and are platform-specific (JVM vs Node.js). Use reader conditionals to provide
  platform-specific implementations.

  Transports are bidirectional - they can send and receive messages."

  (transport-id [this]
    "Unique identifier for this transport (keyword).")

  (transport-start [this handler]
    "Start the transport and begin processing messages.

    handler is a function: (fn [message] -> response-or-nil)

    For request/response transports (HTTP), handler returns a response.
    For streaming transports (stdio, WebSocket), handler processes messages
    and may call transport-send to send responses asynchronously.

    Returns a closeable handle or nil.")

  (transport-stop [this]
    "Stop the transport and release resources.")

  (transport-send [this message]
    "Send a message through the transport.

    message is a map with protocol-specific structure (typically JSON-RPC).

    For request/response transports, this is called by the handler.
    For streaming transports, this is called by the protocol adapter to send
    notifications or responses."))

(defprotocol ProtocolAdapter
  "Adapts a specific protocol (MCP, LSP, DAP, custom) to the port system.

  Protocol adapters are **bidirectional** - they can operate as:
  - **Server**: Receives requests via `protocol-dispatch`, sends responses
  - **Client**: Sends requests via `protocol-request`, receives responses

  Protocol adapters handle:
  - Protocol-specific message formats (JSON-RPC, custom)
  - Capability negotiation (both client and server capabilities)
  - Method dispatch (routing protocol methods to ports/handlers)
  - Request/response translation (protocol format <-> port context)
  - Protocol-specific features (progress, cancellation, etc.)"

  (protocol-id [this]
    "Unique identifier for this protocol (keyword).")

  (protocol-version [this]
    "Protocol version string.")

  (protocol-capabilities [this port-registry]
    "Return protocol capabilities based on available ports (server role).

    port-registry is a PortRegistry implementation.

    Returns a protocol-specific capabilities map.
    Example (MCP): {:tools {:listChanged false}
                    :resources {:subscribe false}
                    :prompts {}}")

  (protocol-dispatch [this method params context]
    "Dispatch an incoming protocol method (server role).

    method - Protocol method name (string, e.g. 'tools/call', 'textDocument/completion')
    params - Method parameters (map) — for LSP/MCP this is the :params
             of the JSON-RPC message; for DAP it's the entire inbound
             message (which carries :arguments)
    context - Execution context map. Keys vary by protocol but the
              following are common and stable:

                :port-registry  — the PortRegistry instance the adapter
                                  should walk to find tools/commands/
                                  handlers. Injected automatically by
                                  `sugar/create-adapter` from the sugar
                                  registry.
                :request        — the full inbound message (for
                                  transports that want to inspect
                                  envelope fields like :id or :seq).
                :id             — the JSON-RPC request id (LSP/MCP).
                :state*         — the adapter's state atom (MCP, LSP).
                :transport      — the Transport instance, if the caller
                                  wants to push notifications out.

              Protocol-specific additions:

                MCP: :refactoring-enabled? :tool-filter
                     :enable-subscriptions? :performance :uri-scheme
                LSP: :document-store :adapter :request-id :progress-token
                DAP: :adapter-state :backend-type :backend-opts :server-info

    Returns:
    - Success: {:result <data>} or the raw body depending on protocol
    - Error: {:error {:code <int> :message <str> :data <any>}}"))

(defprotocol ProtocolClient
  "Client-side protocol operations for bidirectional communication.

  Defines the contract for a protocol client — the other side of a
  ProtocolAdapter. A ProtocolClient can:
  - Send requests to servers (tools/call, resources/read, etc.)
  - Handle incoming requests from servers (sampling, elicitation, roots)
  - Manage session state (initialize handshake, capabilities)

  NOTE: Defport does NOT ship a ProtocolClient implementation. Spawning
  and driving an external protocol server is application concern, not
  library concern (the same way clj-http is separate from Ring). If your
  application needs client-role capability:

    1. Spawn the subprocess yourself (ProcessBuilder, babashka.process,
       child_process in CLJS, whatever fits your stack).
    2. Implement this protocol for your client record.
    3. Use your own transport and concurrency model.

  This protocol exists as a contract that external implementations can
  satisfy, not as something defport provides."

  (protocol-connect [this transport client-info]
    "Connect to a server and perform initialization handshake.

    transport - Transport instance for communication
    client-info - Client identification {:name \"...\" :version \"...\"}

    Returns:
    - Success: {:result {:protocolVersion \"...\" :capabilities {...} :serverInfo {...}}}
    - Error: {:error {:code <int> :message <str>}}")

  (protocol-request [this method params]
    "Send a request to the server and wait for response.

    method - Protocol method name (string)
    params - Method parameters (map)

    Returns:
    - Success: {:result <data>}
    - Error: {:error {:code <int> :message <str>}}")

  (protocol-notify [this method params]
    "Send a notification to the server (no response expected).

    method - Protocol method name (string)
    params - Method parameters (map)

    Returns nil.")

  (protocol-disconnect [this]
    "Disconnect from the server and cleanup resources.

    Returns nil.")

  (register-request-handler! [this method handler]
    "Register a handler for incoming requests FROM the server.

    In MCP, servers can send requests to clients for:
    - sampling/createMessage - LLM completion requests
    - elicitation/create - User input requests
    - roots/list - Filesystem root queries

    method - Method name to handle (string)
    handler - Function (fn [params context] -> {:result ...} or {:error ...})

    Returns nil."))

(defprotocol PortRegistry
  "Registry for managing ports (capabilities, tools, operations).

  Port registries can be:
  - EDN-based: Load port definitions from .edn files
  - Function-based: Register ports programmatically
  - Hybrid: Support both approaches

  Registries are mutable (support registration at runtime) but provide
  immutable views via list-ports and get-port."

  (list-ports [this]
    "Return a vector of all registered port descriptors.

    Each descriptor is a map:
    {:id :find-callers
     :name \"Find Callers\"
     :description \"Find all functions that call the target function\"
     :input-schema {...}
     :output-schema {...}
     :metadata {:protocols #{:mcp :lsp}
                :token-budget 1000
                :dangerous? false}}")

  (get-port [this port-id]
    "Get a port by ID. Returns a Port implementation or nil.")

  (register-port! [this port-def]
    "Register a new port or update an existing one.

    port-def can be:
    - A Port implementation
    - A map (will be converted to a Port implementation)
    - An EDN file path (will be loaded and converted)

    Returns the registered Port."))

;;; Utility Functions

(defn port?
  "Check if x implements the Port protocol."
  [x]
  (satisfies? Port x))

(defn transport?
  "Check if x implements the Transport protocol."
  [x]
  (satisfies? Transport x))

(defn protocol-adapter?
  "Check if x implements the ProtocolAdapter protocol."
  [x]
  (satisfies? ProtocolAdapter x))

(defn port-registry?
  "Check if x implements the PortRegistry protocol."
  [x]
  (satisfies? PortRegistry x))

(defn protocol-client?
  "Check if x implements the ProtocolClient protocol."
  [x]
  (satisfies? ProtocolClient x))

;;; ============================================================================
;;; Cross-Protocol Registry
;;; ============================================================================

;; Global registry for cross-protocol capabilities.
;;
;; Structure:
;; {:analyze {:id :analyze
;;            :handler (fn [context] ...)
;;            :schema [:map [:code :string]]
;;            :description "Analyze code"
;;            :metadata {...}}}
(defonce ^:private *registry (atom {}))

(defn register-global-port!
  "Register a capability to the global cross-protocol registry.

   This is the core of cross-protocol sharing. Register once, expose everywhere.
   For registering ports to a specific PortRegistry, use the protocol method:
     (register-port! registry port-def)

   port-def is a map with:
   - :id - Keyword identifier (required)
   - :handler - Function (fn [context] -> result) (required)
   - :schema - Input schema (Malli or JSON Schema)
   - :description - Human-readable description
   - :metadata - Additional metadata (tags, annotations, etc.)

   Example:
     (register-global-port! {:id :analyze
                             :handler analyze-code
                             :schema [:map [:code :string]]
                             :description \"Analyze code structure\"})

     ;; Then expose in protocols:
     (mcp/expose-port! :analyze)                    ; as MCP tool
     (lsp/expose-port! :analyze :as :hover)         ; as LSP hover

   Returns the registered port definition."
  [port-def]
  (let [id (or (:id port-def)
               (throw (ex-info "Port definition requires :id" {:port-def port-def})))
        handler (or (:handler port-def)
                    (throw (ex-info "Port definition requires :handler" {:port-def port-def})))
        port (merge {:id id :handler handler} port-def)]
    (swap! *registry assoc id port)
    port))

(defn get-registered-port
  "Get a registered port by ID. Returns nil if not found."
  [port-id]
  (get @*registry port-id))

(defn list-registered-ports
  "List all registered port IDs."
  []
  (keys @*registry))

(defn list-registered-port-defs
  "List all registered port definitions."
  []
  (vals @*registry))

(defn unregister-port!
  "Remove a port from the registry."
  [port-id]
  (swap! *registry dissoc port-id)
  nil)

(defn clear-registry!
  "Clear all registered ports. Useful for testing."
  []
  (reset! *registry {})
  nil)
