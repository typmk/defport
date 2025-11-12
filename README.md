# defport

**Platform-agnostic, protocol-agnostic library for building protocol servers.**

Defport is a pure Clojure/ClojureScript library that provides the building blocks for protocol servers (MCP, LSP, DAP, GraphQL, gRPC, custom). You provide the business logic and configuration, defport provides the infrastructure.

## Status

🚧 **In Active Development** - Extracted from production [Defnet](https://github.com/yourorg/defnet) codebase

**Phase 1 Complete:**
- ✅ Core protocols defined (Port, Transport, ProtocolAdapter, PortRegistry)
- ✅ Utility infrastructure (protocol validation, pagination, progress)
- ✅ Registry system (EDN, Function, Hybrid)
- ✅ Transport implementations (stdio, HTTP)
- ✅ Simple EDN loading utilities
- 🚧 MCP protocol adapter (next)

## Philosophy: Library, Not Framework

**Defport is a library, not a framework.** This means:

- **You control configuration** - Defport doesn't impose config file locations or search paths
- **You control application structure** - Defport provides tools, you build the app
- **No magic** - Everything is explicit, debuggable functions
- **Composable** - Use only what you need

**Defport provides:**
- Protocols (abstract interfaces)
- Implementations (transports, registries)
- Utilities (validation, pagination, progress)

**Your app provides:**
- EDN configuration files (wherever you want them)
- Business logic (port handlers)
- Configuration management (if needed)

## Quick Start

### 1. Add Dependency

```clojure
;; deps.edn
{:deps {defport/defport {:mvn/version "0.1.0-SNAPSHOT"}}}
```

### 2. Define Your Ports (Tools/Capabilities)

```edn
;; resources/my-app/tools.edn
{:ports
 {:search-code
  {:id :search-code
   :description "Search for code"
   :input-schema {:type "object"
                  :properties {:query {:type "string"}}}
   :output-schema {:type "array"}
   :handler my.app/search-handler
   :metadata {:token-budget 1000}}}}
```

### 3. Implement Handlers

```clojure
(ns my.app
  (:require [defport.registry.core :as registry]
            [defport.transports.http :as http]
            [defport.core :as defport]))

(defn search-handler [context]
  (let [query (get-in context [:params :query])]
    {:result (do-search query)}))
```

### 4. Create and Start Server

```clojure
(ns my.server
  (:require [defport.registry.core :as registry]
            [defport.transports.http :as http]))

;; Create port registry from your EDN
(def ports (registry/create-edn-registry "resources/my-app/tools.edn"))

;; Create HTTP transport
(def transport (http/create-http-transport {:port 9876}))

;; Start server (simplified - full implementation in examples/)
(defport/transport-start transport
  (fn [request]
    ;; Your request handling logic here
    ))
```

## Core Concepts

### 1. Port (Capability/Tool/Operation)

A **Port** represents something your server can do. It's protocol-agnostic - the same port can be exposed via MCP, LSP, or any other protocol.

```clojure
(defprotocol Port
  (port-id [this])           ; :search-code
  (port-schema [this])       ; {:input-schema ... :output-schema ...}
  (port-execute [this ctx])) ; Execute the operation
```

### 2. Transport (Message Delivery)

A **Transport** handles low-level message delivery (stdio, HTTP, WebSocket).

```clojure
(defprotocol Transport
  (transport-start [this handler])  ; Start listening
  (transport-stop [this])           ; Stop
  (transport-send [this message]))  ; Send message
```

**Implementations:**
- `defport.transports.stdio` - stdin/stdout (MCP, LSP, DAP)
- `defport.transports.http` - HTTP server (MCP HTTP mode)

### 3. PortRegistry (Port Management)

A **PortRegistry** manages your ports.

```clojure
(defprotocol PortRegistry
  (list-ports [this])              ; Get all ports
  (get-port [this port-id])        ; Get specific port
  (register-port! [this port-def])) ; Add/update port
```

**Implementations:**
- `EdnPortRegistry` - Load from EDN files
- `FunctionPortRegistry` - Register programmatically
- `HybridPortRegistry` - Both approaches

### 4. ProtocolAdapter (Protocol Translation)

A **ProtocolAdapter** translates protocol-specific messages to/from ports.

```clojure
(defprotocol ProtocolAdapter
  (protocol-capabilities [this registry])      ; What can this server do?
  (protocol-dispatch [this method params ctx])) ; Route method to port
```

**Implementations:**
- ✅ **MCP** (Model Context Protocol) - `defport.protocols.mcp`
- 🚧 **LSP** (Language Server Protocol) - Planned
- 🚧 **DAP** (Debug Adapter Protocol) - Planned

## Example: MCP Server

### Quick Start with MCP

```clojure
(ns my-mcp-server
  (:require [defport.core :as core]
            [defport.registry.core :as registry]
            [defport.protocols.mcp :as mcp]
            [defport.transports.http :as http]))

;; 1. Create port registry and register tools
(def registry (registry/create-function-registry))

(core/register-port! registry
  {:id :search-code
   :description "Search for code"
   :input-schema {:type "object"
                  :properties {:query {:type "string"}}}
   :handler (fn [{:keys [params]}]
              {:result {:matches [...]}})})

;; 2. Create MCP protocol adapter
(def mcp-adapter (mcp/create-mcp-adapter
                   {:server-info {:name "my-server" :version "1.0.0"}}))

;; 3. Create HTTP transport
(def transport (http/create-http-transport {:port 8080}))

;; 4. Start server
(core/transport-start transport
  (fn [request]
    (let [context {:port-registry registry}]
      (core/protocol-dispatch mcp-adapter
                              (:method request)
                              (:params request)
                              context))))
```

See [examples/simple-mcp-server.clj](examples/simple-mcp-server.clj) for a complete working example.

### Full MCP Server Example

```clojure
(ns my-mcp-server
  (:require [defport.registry.core :as registry]
            [defport.transports.stdio :as stdio]))

;; Your app's configuration (wherever you want it)
(def config
  {:ports-file "resources/my-app/mcp-tools.edn"})

;; Create components
(def port-registry
  (registry/create-edn-registry (:ports-file config)))

(def transport
  (stdio/create-stdio-transport))

;; Start server
(defn -main [& args]
  (defport.core/transport-start transport
    (fn [request]
      ;; MCP protocol handling here
      ;; (Will be provided by defport.protocols.mcp namespace)
      )))
```

## Platform Support

| Platform | Status | Notes |
|----------|--------|-------|
| **JVM (Clojure)** | ✅ Primary | Full feature support |
| **Node.js (CLJS)** | ✅ Supported | Reader conditionals for I/O |
| **Browser (CLJS)** | ⚠️ Limited | WebSocket transport only |
| **GraalVM Native** | 🚧 Future | Fast startup, small binaries |

All core code is `.cljc` with reader conditionals for platform-specific operations.

## Configuration Management

**Defport is deliberately simple** - it doesn't impose configuration strategies.

### Simple Approach (Just Load EDN)

```clojure
(def config (defport.util.edn/load-edn "config/server.edn"))
```

### Advanced Approach (Cascading Configs)

If you need cascading configs with search paths, env var overrides, etc., implement it in your app. See [Defnet's config system](https://github.com/yourorg/defnet/blob/main/src/defnet/services/config.clj) for an example.

Defport provides `defport.config/merge-configs` and `defport.config/validate-config` as helpers.

## Project Structure

```
defport/                           # The library
├── src/defport/
│   ├── core.cljc                 # Core protocols
│   ├── config.cljc               # Config utilities
│   ├── registry/
│   │   └── core.cljc            # Port registry implementations
│   ├── transports/
│   │   ├── stdio.cljc           # Stdio transport
│   │   └── http.cljc            # HTTP transport
│   ├── protocols/               # Protocol adapters (future)
│   │   ├── mcp.cljc            # MCP adapter
│   │   ├── lsp.cljc            # LSP adapter
│   │   └── dap.cljc            # DAP adapter
│   └── util/
│       ├── edn.cljc            # EDN loading
│       ├── protocol.cljc       # Request validation, cancellation
│       ├── pagination.cljc     # Cursor pagination
│       └── progress.cljc       # Progress notifications
└── examples/
    ├── ports-example.edn        # Example port definitions
    └── simple-server/           # Example server (coming soon)

your-app/                         # Your application
├── resources/your-app/
│   ├── tools.edn                # Your port definitions
│   └── config.edn               # Your configuration
└── src/your_app/
    ├── handlers.clj             # Your business logic
    └── server.clj               # Uses defport library
```

## Comparison

| Aspect | Defport | Custom Implementation | Framework (LSP4J, etc.) |
|--------|---------|----------------------|-------------------------|
| **Protocols** | Multiple (MCP, LSP, DAP, custom) | One at a time | One specific |
| **Platforms** | JVM, Node, Browser | JVM only | JVM only |
| **Configuration** | Your choice | Your code | Framework's way |
| **Learning Curve** | Low (simple library) | High (build everything) | Medium (learn framework) |
| **Flexibility** | High (composable) | Highest (full control) | Low (framework constraints) |
| **Reuse** | High (ports work across protocols) | Low | Medium |

## Examples

### EDN Port Registry

```clojure
(require '[defport.registry.core :as registry])

(def ports (registry/create-edn-registry "resources/tools.edn"))

(registry/list-ports ports)
;=> [{:id :search-code ...} {:id :find-callers ...}]
```

### Function Port Registry

```clojure
(require '[defport.registry.core :as registry])

(def ports (registry/create-function-registry))

(registry/register-port! ports
  {:id :my-tool
   :input-schema {...}
   :handler (fn [ctx] {:result "done"})})
```

### Hybrid Registry

```clojure
(def ports (registry/create-hybrid-registry
             ["resources/base-tools.edn"]))  ; Load EDN first

(registry/register-port! ports              ; Then add programmatic
  {:id :dynamic-tool :handler ...})
```

### HTTP Transport

```clojure
(require '[defport.transports.http :as http])

(def transport (http/create-http-transport
                 {:port 8080
                  :cors {:allow-origin "*"}}))

(http/transport-start transport my-handler)
;=> ✓ HTTP transport started on http://127.0.0.1:8080

;; Endpoints:
;; POST /rpc - JSON-RPC 2.0
;; GET /health - Health check
;; GET /info - Server info
```

### Stdio Transport

```clojure
(require '[defport.transports.stdio :as stdio])

(def transport (stdio/create-stdio-transport))

(stdio/transport-start transport my-handler)
;; Reads JSON-RPC from stdin, writes to stdout
```

## Roadmap

### ✅ Phase 1: Library Foundation (COMPLETE)
- [x] Core protocols
- [x] Utility infrastructure
- [x] Registry system
- [x] Transport implementations (stdio, HTTP)
- [x] MCP protocol adapter
- [x] Example MCP server
- [x] Comprehensive test suite (14 tests, 68 assertions, 100% pass)

### Phase 2: Integration & Production
- [ ] Refactor Defnet to use defport
- [ ] Production testing with real MCP clients
- [ ] Performance benchmarking

### Phase 3: LSP Support
- [ ] LSP protocol adapter
- [ ] Example LSP server
- [ ] Validate abstraction with second protocol

### Phase 4: Platform Expansion
- [ ] Node.js build pipeline (Shadow-CLJS)
- [ ] NPM package
- [ ] Browser WebSocket transport

### Phase 5: Production Ready
- [ ] Comprehensive documentation
- [ ] API stability guarantees
- [ ] Performance benchmarking
- [ ] Release v1.0.0

## Related Projects

- **[Defnet](https://github.com/yourorg/defnet)** - Clojure code intelligence MCP server (uses defport)
- **[Scout](https://github.com/yourorg/scout)** - CDP-based web scraping MCP server (will use defport)

## Contributing

Defport is in active development. Contributions welcome:

- Protocol adapters (LSP, DAP, custom)
- Transport implementations (WebSocket, gRPC)
- Documentation improvements
- Bug reports and feature requests

## License

EPL 1.0 (Eclipse Public License) - same as Clojure

## Credits

Extracted from [Defnet](https://github.com/yourorg/defnet) by the Defnet team.

Inspired by:
- Ports & Adapters pattern (Hexagonal Architecture)
- Ring's simplicity (middleware, handlers)
- Pedestal's interceptors
- Clojure's data-driven philosophy

---

**Questions? Issues?**

Open an issue on GitHub or join #defport on Clojurians Slack.
