# Architecture & Design Rationale

This document explains defport's architectural decisions and design philosophy.

---

## Core Philosophy: Low-Level Library

**defport is a low-level library, not a framework.**

Like Ring for HTTP or Lacinia for GraphQL, defport provides protocol abstractions and lets applications handle everything else.

### What This Means

| We Provide | We Do NOT Provide |
|------------|-------------------|
| Protocol adapters (MCP, LSP, DAP, BSP) + clients (MCP, LSP, DAP, BSP, CDP, rosbridge) | Auth middleware |
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

## Protocol Intersection

Defport is the **intersection of MCP, LSP, and DAP** — the shared machinery that all three JSON-RPC-over-transport protocols need, exposed as reusable primitives.

### What the three protocols share

| Concern | MCP | LSP | DAP |
|---------|-----|-----|-----|
| Wire format | JSON-RPC 2.0 | JSON-RPC 2.0 | JSON-RPC 2.0 (DAP flavor) |
| Transports | stdio, HTTP/SSE | stdio, TCP, IPC | stdio, TCP |
| Request/response shape | `{id, method, params}` → `{id, result/error}` | same | same |
| Capability negotiation | `initialize` handshake | `initialize` handshake | `initialize` request |
| Cancellation | `tools/call/cancel` | `$/cancelRequest` | `cancel` request |
| Progress notifications | `notifications/progress` | `$/progress` | `progress` event |
| Error codes | JSON-RPC -32xxx | JSON-RPC -32xxx | JSON-RPC -32xxx |
| Structured input schemas | JSON Schema | JSON Schema | JSON Schema |

**Defport lives at this intersection.** It provides the JSON-RPC framing, the dispatch layer, the cancellation tracking, the progress hooks, the error mapping, and the state management — once — and each protocol adapter maps its specific methods (`tools/call`, `textDocument/hover`, `setBreakpoints`) to the same underlying port invocation.

### What each protocol specializes

- **MCP**: tools, prompts, resources, sampling, elicitation
- **LSP**: textDocument/* and workspace/* operations, diagnostics, completions at cursor positions
- **DAP**: launch/attach sessions, breakpoints, stepping, stack frame inspection

These are adapter-level concerns — they map protocol-specific messages to port calls and format protocol-specific responses. The *plumbing* (dispatch, cancellation, state, content formatting) is shared in the core.

### The payoff

A single port definition can be exposed via all three protocols:

```clojure
(core/register-port! registry
  {:id :find-definition
   :handler find-definition-handler
   :input-schema {...}})

;; Exposed via MCP as a tool
(mcp/expose-port! adapter :find-definition)

;; Exposed via LSP as textDocument/definition
(lsp/expose-port! adapter :find-definition :as "textDocument/definition")

;; Exposed via DAP as a custom request
(dap/expose-port! adapter :find-definition :as "customFindDefinition")
```

One capability, three protocols, one implementation. **This is why defport exists as a library distinct from any single protocol implementation.** If you only needed MCP, you'd use an MCP-specific library. Defport's value is the shared substrate.

---

## Concurrency Model

Defport's concurrency philosophy is captured in one rule:

> **Port handlers are synchronous. Users bring their own async.**

This follows Ring exactly, and for the same reasons: a protocol adapter should not impose a concurrency model on its consumers. The handler contract is `(fn [context] result)` — a plain function from context to value. Whatever the handler does internally — blocking I/O, async I/O, spawning threads, parking go-blocks, awaiting promises — is invisible to defport.

### Why synchronous-by-default is the right choice

**The alternative would be picking an async primitive.** If defport chose core.async, every user would need core.async as a dependency and every handler would live inside a go-block. If defport chose promesa, same story. If defport chose manifold, it would be JVM-only. Each choice forces contagion onto users who didn't ask for it.

Ring solved this in 2009 by refusing to pick. Ring handlers are `(fn [request] response)`. Users who want async handling return a manifold deferred; manifold-aware Ring adapters unwrap it. Users who want blocking handlers write blocking code. Users who want core.async call `<!!` inside their handler. Ring doesn't know or care. **That decision is why Ring is still the base of the Clojure web ecosystem 15 years later** — it stayed neutral while async fashions came and went.

Defport applies the same principle to protocol adapters.

### Deployment models, and what each implies

#### Stdio: 1 process = 1 peer

When an MCP/LSP/DAP client spawns a stdio server, the server process has exactly one peer connected via exactly one pair of stdin/stdout pipes. If ten clients want the same server, ten separate processes are spawned — each with its own private stdio, its own state, its own lifetime.

```
Client 1 → spawns → [server process #1]  (stdio to client 1)
Client 2 → spawns → [server process #2]  (stdio to client 2)
Client N → spawns → [server process #N]  (stdio to client N)
```

Under this model:
- **No concurrency needed** inside the process — linear request/response loop
- **No shared state** across clients — each has its own process
- **No authentication** — the stdio pipe is authenticated by "you spawned me"
- **No session management** — one process = one implicit session
- **Crash isolation is free** — one client's process crashing doesn't touch others

The whole stdio deployment model is inherently sequential. A synchronous request-loop is the correct implementation on any platform:

```clojure
;; Pseudocode — both JVM and Node versions look like this
(loop []
  (let [request  (read-stdin-line)
        response (dispatch request)]
    (write-stdout-line response)
    (recur)))
```

No async primitives. No concurrency primitives. Straight-line code. **On Node this works because `process.stdin.on('data', ...)` callbacks run synchronously in their bodies — the event loop drives the loop, but the work inside the callback is plain function calls.**

#### HTTP: 1 process = N peers

When a server runs over HTTP (SSE or streamable HTTP), it's a long-running daemon that many clients connect to simultaneously. This is where *real* concurrency appears:

```
[server process]  ← HTTP:9876
    ↑   ↑   ↑   ↑
    └───┴───┴───┴── many concurrent clients
```

Now you care about:
- Concurrent handler execution (slow tool calls shouldn't block fast ones)
- Per-session state keyed by client ID / auth token
- Authentication
- Backpressure and rate limiting
- Graceful shutdown across connections

**But notice: none of these concerns belong to defport.** Defport's job is still "given one request, produce one response." The concurrency happens at the transport layer — http-kit manages a thread pool on JVM; Node's `http.createServer` drives an event loop on Node. Each request, once routed to defport, is a synchronous dispatch that returns a value. Whether ten of them are running on ten threads, or interleaved on an event loop, is invisible to defport's core.

This is the Ring insight again: Ring handlers are synchronous; Jetty/http-kit/aleph manage concurrency around them. Users who want async can wrap their handler in `manifold.deferred/chain`. Defport works the same way.

### Users bring their own async

Defport provides one small extension point — the `Unwrappable` protocol (or equivalent) — to let handlers optionally return async types that get transparently unwrapped:

```clojure
;; A synchronous handler — 90% of the time
(mcp/deftool search [query :- :string]
  (db/query! "SELECT ..." [query]))

;; A handler that returns a Clojure promise/future
(mcp/deftool slow-op [input :- :string]
  (future (expensive-computation input)))
;; Defport derefs the future before wrapping the response.

;; A handler that returns a manifold deferred (JVM)
(mcp/deftool manifold-style [input :- :string]
  (d/chain (fetch input) process))
;; Defport unwraps via manifold if it's on the classpath.

;; A handler that returns a js/Promise (Node/CLJS)
(mcp/deftool promise-style [url :- :string]
  (js/fetch url))
;; Node transport chains .then before writing the response.

;; A handler that returns a core.async channel
(require '[clojure.core.async :as a])
(mcp/deftool channel-style [input :- :string]
  (let [ch (a/chan)]
    (a/go (a/>! ch (compute input)))
    ch))
;; Defport takes first value from channel.
```

**Defport does not depend on manifold, promesa, or core.async.** It uses feature detection (`requiring-resolve`, `instance?`) to unwrap user-provided types if they're present. Users who don't use async libraries don't pay for them. Users who do, get transparent support without defport dictating which one.

### Why no async primitive in defport's core

There are three concrete reasons:

**1. Async contagion.** If defport's handler contract required returning a channel or promise, every consumer would have to use that primitive. Handlers inside a Pedestal service using interceptors would need to convert. Handlers inside a Ring+manifold app would need to convert. Users doing ordinary blocking work would need to wrap everything in channel machinery. The tax is uniform and unavoidable.

**2. Platform split on blocking semantics.** Every async primitive that supports both JVM and CLJS has an asymmetric blocking story. `core.async`'s `<!!` is JVM-only. `promesa`'s `await` is JVM-only. `manifold` is JVM-only entirely. There is no cross-platform primitive where "block until this resolves" works identically on both runtimes. Picking any of them would create a new cross-platform inconsistency to paper over.

**3. It's not defport's problem.** Concurrency across requests belongs to the transport (which already uses threads on JVM and the event loop on Node). Concurrency within a request belongs to the user's handler (which can use whatever async library it likes). Defport is the thin layer between those two, and it needs no concurrency model of its own.

### The server/client asymmetry

One subtlety worth calling out: **server-side defport is fully synchronous and fully cross-platform. Client-side defport (spawning external MCP/LSP/DAP servers) inherently needs async on Node.**

When defport is a *server*, it receives requests via a transport callback and produces responses synchronously. On both JVM (blocking I/O) and Node (event-callback-driven I/O), this works with straight-line code.

When defport is a *client* — using `connect!` to spawn an external protocol server subprocess and wait for responses — it has to wait for I/O to arrive. On JVM this is `BufferedReader.readLine()`, which blocks a thread. On Node, there's no way to block-wait — you must use callbacks or promises. **This is the one place where the platform semantic gap is real**, and it's why the current `connect!` implementation is JVM-only.

The client-side story is a separate, optional feature surface. When it's implemented for Node, it will necessarily use Node-native async primitives (callbacks or promises), but that async concern lives **entirely inside the client module**, not in the core port abstraction.

### Summary

| Concern | Who handles it? |
|---------|-----------------|
| Concurrency across requests | Transport (thread pool on JVM, event loop on Node) |
| Concurrency within a request | User's handler (their choice of primitive) |
| Async unwrapping (returning promise/channel/deferred) | Defport's `Unwrappable` extension point |
| Authentication, session management, rate limiting | User's middleware/framework |
| Blocking on client-side external server responses | Client module (JVM-only for now) |

Defport's core is a **pure synchronous protocol adapter**. That's the feature, not the limitation.

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
| **Multi-protocol** | ✅ MCP, LSP, DAP, BSP, CDP, rosbridge | ❌ LSP only |
| **Hot reload** | ✅ Yes | ❌ No |
| **Data-driven** | ✅ EDN, Malli | ❌ Annotations |

### defport vs bhauman/clojure-mcp

| Aspect | defport | clojure-mcp |
|--------|---------|-------------|
| **Focus** | Multi-protocol substrate | MCP REPL integration |
| **Use case** | Build MCP/LSP/DAP/BSP/CDP/rosbridge servers and clients | Clojure REPL via MCP |
| **Multi-protocol** | ✅ Six protocols shipped | ❌ MCP only |
| **Server building** | ✅ Primary use | ❌ Not designed for |
| **Client building** | ✅ Pluggable ClientTransport | ❌ Not designed for |

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

### 6. Synchronous by Default

```clojure
;; Good: Plain function, user decides concurrency
(mcp/deftool search [query :- :string]
  (db/query! "SELECT ..." [query]))

;; Bad: Forcing users into a specific async primitive
(mcp/deftool search [query :- :string]
  (go (<! (async-query query))))  ; defport shouldn't require core.async
```

Port handlers are `(fn [context] result)`. Defport calls them synchronously and wraps the return value as a protocol response. If a handler wants to do async work, it unwraps internally and returns a value — or returns an async type and defport unwraps via the `Unwrappable` extension point. **Never** require users to adopt a specific concurrency model.

### 7. Users Bring Their Own Async

```clojure
;; Defport's dispatch layer is agnostic to async primitives.
;; Any of these handler shapes work:

;; Plain value
(fn [ctx] {:results [...]})

;; Clojure promise / future / delay
(fn [ctx] (future (compute ctx)))

;; Manifold deferred (JVM, if on classpath)
(fn [ctx] (d/chain (fetch ctx) process))

;; core.async channel (on either platform)
(fn [ctx] (go (<! (async-op ctx))))

;; js/Promise (CLJS/Node)
(fn [ctx] (js/fetch (:url ctx)))
```

Defport's dispatch layer detects and unwraps these via feature detection — no hard dependency on any async library. Users on Pedestal use interceptors; users on Ring use middleware; users on manifold use chains; users who like blocking use blocking. Defport composes with all of them because it refuses to pick one.

### 8. Protocol Intersection, Not Union

A port is defined once and exposed through any or all supported protocols. Defport holds the intersection of MCP, LSP, and DAP — the JSON-RPC framing, the dispatch, the cancellation, the state, the content formatting — as shared primitives. Protocol-specific concerns (MCP tools vs LSP document operations vs DAP breakpoints) live in their respective adapters as thin mappings over the shared core. The library's value is this shared substrate; single-protocol libraries miss the payoff.

---

## Multi-Protocol Composition

The LSP and DAP server adapters exist today alongside MCP. All three satisfy the same `ProtocolAdapter` protocol and consume the same `PortRegistry`. This is the composition model:

```
         ┌─────────────────────┐
         │  Consumer (defnet)  │
         │  capability layer   │
         └──────────┬──────────┘
                    │ registers ports once
                    ▼
            ┌───────────────┐
            │  PortRegistry │
            └───────┬───────┘
          ┌─────────┼─────────┐
          ▼         ▼         ▼
      ┌───────┐ ┌───────┐ ┌───────┐
      │  MCP  │ │  LSP  │ │  DAP  │     ← defport ships these
      └───┬───┘ └───┬───┘ └───┬───┘
          ▼         ▼         ▼
       Claude    editors   debug UIs
```

**Unified use is emergent.** Defport does not provide a "cross-protocol router" or any abstraction above `ProtocolAdapter`. Running MCP + LSP + DAP together is six lines in the consumer's `main`:

```clojure
(def registry (reg/create-function-registry))
(my-app/register-ports! registry)    ;; consumer's capabilities

(def mcp (mcp/create-mcp-adapter {:server-info {...}}))
(def lsp (lsp/create-lsp-adapter {:server-info {...}}))
(def dap (dap/create-dap-adapter {:server-info {...}}))

;; each runs on its own transport, all share one registry and one graph
```

Each adapter is independently runnable, independently testable, independently consumable. A user who only wants MCP pays nothing for LSP or DAP — they just don't instantiate them. A user who wants all three gets it for free because the adapters don't know about each other; they only know the shared `PortRegistry` contract.

### Where the capability layer lives

A "capability" is a function over a domain model — `find-references(symbol, scope)`, `rename-symbol(old, new)`, `explain-function(id)` — that can be invoked from multiple protocols with per-protocol shape translation. **Capabilities live in the consumer, not in defport.**

Defnet is the canonical consumer. Its graph (static structure + runtime events + context events) is the domain model. Its capabilities operate on that graph. The per-protocol exposure is metadata on the port:

```clojure
;; In defnet, not defport
(reg/register-port! registry
  {:id :find-references
   :handler (fn [ctx] (graph/find-references ...))
   :metadata {:protocols #{:mcp :lsp}
              :mcp {:description "Find callers of a function"}
              :lsp {:method "textDocument/references"}}})
```

The MCP adapter reads `:mcp` metadata when listing tools; the LSP adapter reads `:lsp` metadata when registering methods. The capability function itself is written once and knows nothing about either protocol. Defport stays thin; defnet does the semantic work.

### DAP as the runtime membrane

Where MCP and LSP are static-graph frontends, DAP is the runtime feed. The consumer (defnet) can observe a live debug session — by acting as a DAP client attached to a real debug adapter, or by sitting as a proxy between an IDE and the real adapter — and record stack frames, scopes, variable snapshots, and step events into its event log. Those events attribute to graph nodes via source mapping, grounding trust scoring, hotspot analysis, and call-path data in measured runtime behavior instead of static inference.

This is where "the semantic meets the metal": the static graph names concepts, the binary descent (DWARF, disasm, compiler IR) grounds them in machine code, and DAP provides the time dimension — which representations actually executed, when, with what values. All three streams land in the same event-sourced store.

**None of this lives in defport.** Defport provides the `ProtocolClient` contract (in `defport.core`) and the DAP protocol mechanics (JSON-RPC framing, method dispatch, state, cancellation). The client implementation, the proxy transport, the source-mapping, the graph attribution — all consumer code.

---

## Summary

defport's architecture follows Clojure principles:
- **Simple:** Four core protocols
- **Composable:** Library, not framework
- **Data-driven:** EDN configuration, Malli schemas
- **Platform-portable:** .cljc everywhere
- **Explicit:** No magic, clear boundaries

This enables defport to integrate with ANY existing stack while providing robust protocol support.