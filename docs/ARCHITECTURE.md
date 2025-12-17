# Architecture & Design Rationale

This document explains defport's architectural decisions and design philosophy.

---

## Core Philosophy: Low-Level Library

**defport is a low-level library, not a framework.**

Like Ring for HTTP or Lacinia for GraphQL, defport provides protocol abstractions and lets applications handle everything else.

### What This Means

| We Provide | We Do NOT Provide |
|------------|-------------------|
| Protocol adapters (MCP, LSP*, DAP*) | Auth middleware |
| Transport layer (stdio, HTTP) | Metrics collectors |
| Port registry system | HTTP middleware stacks |
| Progress & cancellation | Component/Integrant adapters |
| Content formatting | Lifecycle management |
| Batch processing | Configuration frameworks |

### Why Library Over Framework?

**1. Applications Already Have Infrastructure**

When adding MCP to an existing SaaS app, you already have:
- Authentication (buddy-auth, JWT, sessions)
- Metrics (Prometheus, Datadog, New Relic)
- Database pools (HikariCP, c3p0)
- Lifecycle management (Component, Integrant, Mount)
- Logging (mulog, timbre, logback)

Building these into defport would mean:
- Duplicate systems (defport auth vs app auth)
- Dependency conflicts (buddy versions, Prometheus versions)
- Configuration confusion (which auth config is used?)
- Wasted code (you'd disable defport's version anyway)

**2. Maximum Flexibility**

As a library, defport works with ANY stack:
```clojure
;; Works with Component
(component/using (mcp/new-server config) [:db-pool :auth])

;; Works with Integrant
(defmethod ig/init-key :app/mcp [_ opts] ...)

;; Works with plain functions
(def handler (mcp/create-handler registry adapter))

;; Works with Ring
["/mcp" {:post {:handler handler}}]

;; Works with Pedestal
["/mcp" :post [mcp-interceptor]]
```

**3. Clojure Ecosystem Precedent**

This follows established Clojure patterns:

| Library | What It Provides | What Apps Add |
|---------|------------------|---------------|
| **Ring** | HTTP abstractions | Auth, sessions, routing |
| **Lacinia** | GraphQL execution | Auth, caching, subscriptions |
| **next.jdbc** | JDBC operations | Connection pools, migrations |
| **defport** | Protocol adapters | Auth, metrics, lifecycle |

**4. Simpler Maintenance**

Less code means:
- Fewer bugs
- Smaller attack surface
- Faster updates
- Clearer responsibilities

---

## Four Core Abstractions

defport is built on four protocols that enable protocol-agnostic, platform-portable operation.

### 1. Port

A Port represents a capability that can be exposed via any protocol.

```clojure
(defprotocol Port
  (port-id [this])
  (port-schema [this])
  (port-execute [this context]))
```

**Design Rationale:**
- **Protocol-agnostic:** Same port works via MCP tools, LSP commands, DAP requests
- **Schema-first:** Input/output schemas enable validation and documentation
- **Context-rich:** Execute receives full context (params, metadata, registry)

**Example:**
```clojure
{:id :search-code
 :description "Search for code"
 :input-schema {:type "object"
                :properties {:query {:type "string"}}}
 :output-schema {:type "object"
                 :properties {:results {:type "array"}}}
 :handler (fn [context]
            {:result (search (:params context))})}
```

### 2. Transport

A Transport handles message delivery for a specific medium.

```clojure
(defprotocol Transport
  (transport-start [this handler])
  (transport-send [this message])
  (transport-stop [this]))
```

**Design Rationale:**
- **Platform-specific:** stdio works differently on JVM vs Node.js
- **Bidirectional:** Both request/response and notifications
- **Lifecycle-aware:** Clean start/stop semantics

**Implementations:**
- `StdioTransport` - For CLI tools and IDE integration
- `HttpTransport` - For HTTP-based MCP, REST APIs
- `WebSocketTransport` - For real-time connections (future)

### 3. ProtocolAdapter

A ProtocolAdapter translates between protocol messages and ports.

```clojure
(defprotocol ProtocolAdapter
  (protocol-id [this])
  (protocol-version [this])
  (protocol-capabilities [this port-registry])
  (protocol-dispatch [this method params context]))
```

**Design Rationale:**
- **Protocol-specific:** Each protocol (MCP, LSP, DAP) has different message formats
- **Capability negotiation:** Reports what the server supports
- **Method routing:** Maps protocol methods to port executions

**Current Implementation:**
- `McpAdapter` - Full MCP 2025-06-18 support

**Future Implementations:**
- `LspAdapter` - LSP client/proxy for any language server
- `DapAdapter` - DAP client/proxy for any debugger

### 4. PortRegistry

A PortRegistry manages port registration and lookup.

```clojure
(defprotocol PortRegistry
  (register-port! [this port])
  (unregister-port! [this port-id])
  (get-port [this port-id])
  (list-ports [this]))
```

**Design Rationale:**
- **Multiple implementations:** EDN-based, programmatic, or hybrid
- **Mutable but thread-safe:** Uses atoms internally
- **Descriptor vs implementation:** `list-ports` returns data, `get-port` returns protocol

**Implementations:**
- `EdnPortRegistry` - Load from EDN files (declarative)
- `FunctionPortRegistry` - Register at runtime (imperative)
- `HybridPortRegistry` - Both approaches combined

---

## MCP Adapter Architecture

The MCP adapter is the most complete protocol implementation, serving as the reference for future adapters.

### Message Flow

```
Client Request
     │
     ▼
┌─────────────┐
│  Transport  │  (HTTP or stdio)
└─────────────┘
     │
     ▼
┌─────────────┐
│ McpAdapter  │  (protocol-dispatch)
└─────────────┘
     │
     ├─── validate-request-id (duplicate detection)
     │
     ├─── route to method handler
     │         │
     │         ├─── handle-tools-call
     │         │         │
     │         │         ├─── filter dangerous tools
     │         │         ├─── lookup port
     │         │         ├─── register operation (cancellation)
     │         │         ├─── execute port
     │         │         └─── format-content
     │         │
     │         ├─── handle-prompts-get
     │         ├─── handle-resources-read
     │         └─── etc.
     │
     ▼
Response (JSON-RPC 2.0)
```

### State Management

The MCP adapter uses atoms for thread-safe state:

```clojure
;; Request ID validation (prevent replays)
(defonce seen-request-ids* (atom #{}))

;; Active operations (for cancellation)
(defonce active-operations* (atom {}))

;; Resource subscriptions
(defonce resource-subscriptions* (atom {}))

;; Session log levels
(defonce session-log-levels* (atom {}))

;; Client roots (filesystem boundaries)
(defonce client-roots* (atom []))

;; Sampling state (LLM coordination)
(defonce sampling-state* (atom {}))
```

**Design Rationale:**
- **Atoms:** Lock-free reads, atomic updates (compare-and-swap)
- **Global state:** Necessary for protocol features (subscriptions, cancellation)
- **Reset function:** `reset-protocol-state!` for testing

### Content Formatting

Per MCP 2025-06-18, all structured data uses TextContent with JSON:

```clojure
(defn format-content [result]
  (cond
    ;; Image/Audio pass through
    (content/valid-image-content? result) [result]
    (content/valid-audio-content? result) [result]
    (content/valid-text-content? result) [result]

    ;; Everything else → JSON in TextContent
    :else [{:type "text"
            :text (json/generate-string result)}]))
```

**Why not ObjectContent?**
ObjectContent doesn't exist in the MCP spec. Early implementations used it incorrectly. We follow the spec exactly.

### Hybrid Security Model

Dangerous tool filtering uses a hybrid approach:

1. **Library provides mechanism:** `:dangerous` metadata, filtering function
2. **App provides policy:** Enable via option, env var, or custom filter

```clojure
;; Library defaults (safe)
(def adapter (mcp/create-mcp-adapter))
;; → Dangerous tools filtered from tools/list

;; App enables refactoring
(def adapter (mcp/create-mcp-adapter {:enable-refactoring true}))
;; → All tools visible

;; App provides custom policy
(def adapter (mcp/create-mcp-adapter
               {:tool-filter (fn [tools]
                              (if (admin? current-user)
                                tools
                                (remove :dangerous tools)))}))
```

---

## Extension Points

defport provides hooks for observability without imposing implementations.

### tap> Events

When implemented, defport emits tap> events at key points:

```clojure
(tap> {:event :mcp/tool-call
       :tool-id :search-code
       :duration-ms 42
       :success? true
       :timestamp 1704067200000})

(tap> {:event :mcp/error
       :method "tools/call"
       :error-code -32602
       :error-message "Unknown tool: foo"})
```

**Why tap>?**
- Zero overhead when no taps registered
- Works with Portal, REBL, mulog, custom loggers
- Clojure core feature (no dependencies)
- Non-blocking

### datafy/nav

Core types implement Datafiable for REPL introspection:

```clojure
(require '[clojure.datafy :refer [datafy nav]])

(datafy mcp-adapter)
;; => {:type :mcp-adapter
;;     :protocol-version "2025-06-18"
;;     :server-info {:name "my-server" :version "1.0.0"}
;;     :capabilities {...}
;;     :active-operations 3
;;     :subscriptions 2}

(nav (datafy registry) :ports nil)
;; => [{:id :search-code ...} {:id :analyze ...}]
```

**Why datafy/nav?**
- Standard Clojure protocol
- Works with Portal, REBL
- Enables navigation without exposing internals
- Future-proof

### Ring-Compatible Handlers

MCP handlers have Ring-compatible signatures:

```clojure
(fn [request] -> response)
```

This enables:
- Ring middleware composition
- Pedestal interceptor wrapping
- Standard HTTP patterns

---

## Platform Portability

defport uses .cljc files with reader conditionals for cross-platform support.

### Reader Conditional Pattern

```clojure
(defn current-time-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn env-var [name]
  #?(:clj (System/getenv name)
     :cljs (when (exists? js/process)
             (aget (.-env js/process) name))))
```

### Platform Support Matrix

| Platform | Status | Transports | Notes |
|----------|--------|------------|-------|
| JVM | ✅ Full | stdio, HTTP | Primary platform |
| Node.js | ✅ Full | stdio, HTTP | Via ClojureScript |
| Browser | ⚠️ Limited | WebSocket only | No stdio/file I/O |
| GraalVM | 🚧 Planned | TBD | Fast startup goal |

---

## Comparison with Other Approaches

### defport vs FastMCP (Python)

| Aspect | defport | FastMCP |
|--------|---------|---------|
| **Philosophy** | Library | Framework |
| **Auth** | You provide | Built-in |
| **Metrics** | You provide | Built-in |
| **Lines of code** | 8 for hello world | 10 for hello world |
| **Multi-protocol** | ✅ Planned (LSP, DAP) | ❌ MCP only |
| **Platform** | JVM, Node.js | Python only |

defport is 20% fewer lines for equivalent functionality, with more flexibility.

### defport vs LSP4J (Java)

| Aspect | defport | LSP4J |
|--------|---------|-------|
| **Philosophy** | Library | Framework |
| **Language** | Clojure (.cljc) | Java |
| **Multi-protocol** | ✅ MCP, LSP*, DAP* | ❌ LSP only |
| **Hot reload** | ✅ Yes | ❌ No |
| **Data-driven** | ✅ EDN, Malli | ❌ Annotations |

### defport vs bhauman/clojure-mcp

| Aspect | defport | clojure-mcp |
|--------|---------|-------------|
| **Focus** | Multi-protocol library | MCP REPL integration |
| **Use case** | Build MCP servers | Clojure REPL via MCP |
| **Multi-protocol** | ✅ Planned | ❌ MCP only |
| **Server building** | ✅ Primary use | ❌ Not designed for |

---

## Design Principles

### 1. Protocols Over Implementations

```clojure
;; Good: Protocol-based abstraction
(defprotocol Port
  (port-execute [this context]))

;; Bad: Concrete implementation
(defn execute-tool [tool-map context]
  ...)
```

### 2. Data-Driven Configuration

```clojure
;; Good: Data (can be loaded from EDN)
{:id :search-code
 :input-schema {:type "object" ...}
 :handler search-handler}

;; Bad: Code-driven
(deftool search-code
  {:type "object" ...}
  (fn [params] ...))  ; Can't serialize
```

### 3. Context Over Globals

```clojure
;; Good: Context injection
(defn handler [context]
  (let [db (:db-pool context)
        user (:user context)]
    ...))

;; Bad: Global state
(defn handler [params]
  (let [db @global-db-pool
        user @current-user]
    ...))
```

### 4. Composition Over Configuration

```clojure
;; Good: Compose middleware
(def handler
  (-> mcp-handler
      wrap-auth
      wrap-metrics
      wrap-logging))

;; Bad: Configuration flags
(def handler
  (create-handler {:auth true
                   :metrics true
                   :logging true}))
```

### 5. Explicit Over Magic

```clojure
;; Good: Explicit registration
(core/register-port! registry port-def)

;; Bad: Classpath scanning
(auto-discover-ports! "my.app.tools")
```

---

## Future Architecture

### Multi-Protocol Vision

Phase 7 will add LSP and DAP adapters:

```
         ┌─────────────┐
         │  Your App   │
         └─────────────┘
                │
                ▼
         ┌─────────────┐
         │ Port Registry│
         └─────────────┘
                │
    ┌───────────┼───────────┐
    ▼           ▼           ▼
┌───────┐  ┌───────┐  ┌───────┐
│  MCP  │  │  LSP  │  │  DAP  │
└───────┘  └───────┘  └───────┘
    │           │           │
    ▼           ▼           ▼
 Claude      pyright     debugpy
 Cursor    rust-analyzer   delve
```

Same ports, exposed via multiple protocols.

### Universal Code Intelligence

The end goal:
```clojure
;; Define once
(core/register-port! registry
  {:id :goto-definition
   :handler goto-definition-handler})

;; Expose via MCP (Claude, Cursor)
(def mcp-server (mcp/create-adapter ...))

;; Expose via LSP (VS Code, Emacs)
(def lsp-server (lsp/create-client ...))

;; Expose via GraphQL (Web IDE)
(def graphql-server (graphql/create-adapter ...))
```

---

## Summary

defport's architecture follows Clojure principles:
- **Simple:** Four core protocols
- **Composable:** Library, not framework
- **Data-driven:** EDN configuration, Malli schemas
- **Platform-portable:** .cljc everywhere
- **Explicit:** No magic, clear boundaries

This enables defport to integrate with ANY existing stack while providing robust protocol support.