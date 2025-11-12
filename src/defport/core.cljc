(ns defport.core
  "Core protocols for defport - a platform-agnostic protocol adapter framework.

  Defport enables building protocol servers (MCP, LSP, DAP, custom) using an
  EDN-driven Ports & Adapters architecture. The framework is protocol-agnostic
  and platform-agnostic (.cljc compatible).")

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

  Protocol adapters handle:
  - Protocol-specific message formats (JSON-RPC, custom)
  - Capability negotiation (what the server supports)
  - Method dispatch (routing protocol methods to ports)
  - Request/response translation (protocol format <-> port context)
  - Protocol-specific features (progress, cancellation, etc.)"

  (protocol-id [this]
    "Unique identifier for this protocol (keyword).")

  (protocol-version [this]
    "Protocol version string.")

  (protocol-capabilities [this port-registry]
    "Return protocol capabilities based on available ports.

    port-registry is a PortRegistry implementation.

    Returns a protocol-specific capabilities map.
    Example (MCP): {:tools {:listChanged false}
                    :resources {:subscribe false}
                    :prompts {}}")

  (protocol-dispatch [this method params context]
    "Dispatch a protocol method to the appropriate port.

    method - Protocol method name (string, e.g. 'tools/call', 'textDocument/completion')
    params - Method parameters (map)
    context - Execution context (port-registry, transport, metadata, system)

    Returns:
    - Success: {:result <data>}
    - Error: {:error {:code <int> :message <str> :data <any>}}"))

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

;;; Server API

(defn create-server
  "Create a protocol server with flexible configuration.

  Options map:

  **Protocol Configuration:**
  - :protocol - Protocol adapter or keyword (:mcp, :lsp, :dap, :custom)
  - :protocol-config - Path to protocol EDN file or config map
  - :protocols - Vector of multiple protocols (for multi-protocol servers)

  **Transport Configuration:**
  - :transport - Transport or keyword (:stdio, :http, :websocket)
  - :transport-config - Transport configuration map
  - :transports - Vector of multiple transports

  **Port/Tool Configuration:**
  - :port-registry - PortRegistry implementation or keyword (:edn, :function, :hybrid)
  - :ports - EDN file path or vector of port definitions
  - :port-config - Additional port configuration

  **Dispatch Configuration:**
  - :dispatch - Dispatch strategy (:pipeline, :function, :multimethod)
  - :dispatch-config - Dispatch configuration (pipeline stages, etc.)

  **Lifecycle Hooks:**
  - :on-initialize - Function called on first request: (fn [context] -> updated-context)
  - :on-shutdown - Function called on server shutdown: (fn [context] -> nil)

  **Feature Toggles:**
  - :enable-progress - Enable progress notifications (default true)
  - :enable-cancellation - Enable operation cancellation (default true)
  - :enable-pagination - Enable cursor pagination (default true)

  **System State:**
  - :system - System state map (database, config, etc.)

  Returns a server instance (map) that can be started with start!."
  [options]
  ;; Implementation in defport.server namespace
  (throw (ex-info "Not yet implemented - see defport.server namespace"
                  {:options options})))

(defn start!
  "Start a protocol server.

  server - Server instance returned by create-server

  Returns the started server (with updated state)."
  [server]
  (throw (ex-info "Not yet implemented - see defport.server namespace"
                  {:server server})))

(defn stop!
  "Stop a protocol server and release resources.

  server - Running server instance

  Returns nil."
  [server]
  (throw (ex-info "Not yet implemented - see defport.server namespace"
                  {:server server})))

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
