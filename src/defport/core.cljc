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
    params - Method parameters (map)
    context - Execution context (port-registry, transport, metadata, system)

    Returns:
    - Success: {:result <data>}
    - Error: {:error {:code <int> :message <str> :data <any>}}"))

(defprotocol ProtocolClient
  "Client-side protocol operations for bidirectional communication.

  Enables building protocol clients that:
  - Send requests to servers (tools/call, resources/read, etc.)
  - Handle incoming requests from servers (sampling, elicitation, roots)
  - Manage session state (initialize handshake, capabilities)

  Example usage:
    (def client (create-mcp-client transport))
    (protocol-connect client {:name \"my-client\" :version \"1.0\"})
    (protocol-request client \"tools/call\" {:name \"search\" :arguments {:q \"foo\"}})"

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

(defn protocol-client?
  "Check if x implements the ProtocolClient protocol."
  [x]
  (satisfies? ProtocolClient x))

;;; Client API

(defn create-client
  "Create a protocol client with flexible configuration.

  Options map:

  **Protocol Configuration:**
  - :protocol - Protocol keyword (:mcp, :lsp, :dap) or adapter instance
  - :protocol-version - Protocol version to request (default: latest)

  **Transport Configuration:**
  - :transport - Transport instance or keyword (:stdio, :http)
  - :transport-config - Transport configuration map
    - For :http: {:url \"http://localhost:8080\"}
    - For :stdio: {:command \"server-cmd\" :args [...]}

  **Client Identity:**
  - :client-info - Client identification {:name \"...\" :version \"...\" :description \"...\"}

  **Client Capabilities:**
  - :capabilities - Client capabilities to declare
    - :sampling - Can handle sampling requests {:tools {}}
    - :roots - Can provide filesystem roots {:listChanged true}
    - :elicitation - Can handle elicitation {:form {} :url {}}

  **Request Handlers (for server-initiated requests):**
  - :handlers - Map of method -> handler function
    - \"sampling/createMessage\" - Handle LLM requests
    - \"elicitation/create\" - Handle user input requests
    - \"roots/list\" - Provide filesystem roots

  Returns a client instance that can be used with protocol-connect.

  Example:
    (def client (create-client
                  {:protocol :mcp
                   :transport :http
                   :transport-config {:url \"http://localhost:9876\"}
                   :client-info {:name \"my-app\" :version \"1.0.0\"}
                   :capabilities {:sampling {:tools {}}
                                  :roots {:listChanged true}}
                   :handlers {\"sampling/createMessage\" my-sampling-handler
                              \"roots/list\" (fn [_ _] {:roots @my-roots})}}))"
  [options]
  ;; Implementation in protocol-specific namespaces
  (throw (ex-info "Use protocol-specific create function (e.g., mcp/create-client)"
                  {:options options})))

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
