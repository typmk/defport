# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**defport** is a **LOW-LEVEL LIBRARY** for building protocol servers and clients across six protocols: MCP, LSP, DAP, BSP, CDP, and rosbridge. It provides protocol adapters + client cores + a pluggable transport layer, NOT application frameworks.

**Status (v0.3.0):** 379 kaocha tests / 2,103 assertions / 0 failures | 100% spec coverage for every protocol (MCP 2025-11-25, LSP 3.17, DAP 1.65, BSP 2.2, CDP 1.3, rosbridge v2.0) verified programmatically against upstream schemas on every test run | 8 real-external integration tests against real Chromium, rust-analyzer, debugpy, `@modelcontextprotocol/server-everything`, and Python stdlib clients | Core library compiles clean on JVM and Node (CLJS)

## Critical: Library Philosophy

**defport is like Ring for HTTP - a library, not a framework.**

| We Provide | We Do NOT Provide |
|------------|-------------------|
| Protocol adapters (MCP, LSP, DAP, BSP) | Auth middleware |
| Client cores (MCP, LSP, DAP, BSP, CDP, rosbridge) | Metrics collectors |
| Transport layer (stdio, HTTP, WebSocket, subprocess) | HTTP middleware stacks |
| Plain-data spec registries (drift-checked vs upstream) | Component/Integrant adapters |
| Observability hooks (tap>, datafy/nav) | Native SCADA / DDS / CAN bindings |
| `sugar/run!` one-line launcher | Industrial vertical integrations |

**When developing:**

- **Do NOT add auth middleware** - Applications use buddy-auth
- **Do NOT add metrics collectors** - Applications use Prometheus/iapetos
- **Do NOT add framework features** - defport integrates into YOUR stack
- **Focus on protocol adapters and documentation**

## Core Design Principles (non-negotiable)

These are the rules that keep defport thin, composable, and long-lived. Violating any of them is a regression.

### 1. Synchronous port handler contract

Port handlers are `(fn [context] result)`. Plain functions. Defport calls them synchronously and wraps the return value as a protocol response.

- **Never** add an async primitive to the handler contract.
- **Never** require handlers to return channels, promises, or deferreds.
- **Do** let handlers optionally return async types that defport unwraps via `Unwrappable` (feature detection, no hard deps).
- The rule: the **common case is a plain return value**, async is opt-in.

See `docs/ARCHITECTURE.md` → "Concurrency Model" for full reasoning.

### 2. Users bring their own async

Defport does not depend on core.async, promesa, manifold, or any async library. Users who want async in their handlers use whatever they like — Pedestal interceptors, Ring+manifold chains, go-blocks, `future`, raw `Thread`, Node Promises, callbacks.

Feature-detect known async types (`clojure.lang.IDeref`, `js/Promise`, `manifold.deferred/deferred?` via `requiring-resolve`) for transparent unwrapping. Never hard-depend.

### 3. Defport is the protocol intersection

Defport holds what MCP, LSP, and DAP have in common: JSON-RPC framing, dispatch, cancellation, progress, state, content formatting, error mapping. Protocol-specific concerns (MCP tools, LSP text operations, DAP breakpoints) live in their respective adapters as thin mappings over the shared core.

A single port definition should be exposable via all three protocols with zero protocol-specific knowledge in the port itself.

**Unified use is emergent, not a feature.** Defport does not ship a "cross-protocol router" or a "capability layer." If an application wants MCP + LSP + DAP running together, it instantiates three adapters against one `PortRegistry` in its own `main`. That's six lines of user code and requires no defport abstraction beyond what already exists. The bar: individual-protocol use must be simple enough that composing three adapters into one process is the trivial consequence.

The **capability layer** (functions over a domain model that are exposed through multiple protocols with shape translation) lives in the consumer, not in defport. Defnet is the canonical example: defnet owns the graph and the capabilities; defport provides the three adapters defnet plugs them into.

### 4. Transport, not dispatcher, manages concurrency

- **Stdio**: one process = one peer = sequential. No concurrency primitives needed anywhere.
- **HTTP**: concurrency lives in the transport (thread pool on JVM, event loop on Node). Defport's core still sees one request at a time.
- Defport dispatch is a synchronous function from one request to one response. Concurrency *around* it is somebody else's problem.

### 5. The server/client asymmetry — pluggable platform primitives

- **Server-side defport** is fully synchronous and fully cross-platform. Node's `process.stdin.on('data', ...)` callbacks run synchronously in their body; no async machinery is needed.
- **Client-side defport ships the protocol-free 80%.** Request/response correlation by id, notification dispatch, initialize/shutdown handshakes, typed convenience helpers (`hover-at`, `definition-at`, `next`, `step-in`, etc.), error mapping. All of this is platform-free Clojure and lives in `defport.lsp.client` / `defport.dap.client` / `defport.mcp.client`.
- **The 20% that's genuinely platform-specific is a small protocol consumers plug in.** A `ClientTransport` (or per-protocol equivalent) describes `start!` / `send!` / `recv!` / `stop!`. Defport ships *reference transports* in optional namespaces — `defport.lsp.client.transports.subprocess-jvm` (uses `ProcessBuilder`) and `defport.lsp.client.transports.subprocess-node` (uses `child_process.spawn`) — both behind one constructor function. Consumers who want raw sockets, in-process pipes, WebSockets, or proxied transports write their own.
- This honors the original load-bearing constraint (Node cannot block the event loop while waiting on a subprocess) by keeping the platform glue at the edge in optional namespaces and making the core client logic platform-free.
- **Defnet is still the canonical consumer**, but its job shrinks to "construct a transport (or use the bundled one) and plug in domain logic." Its DAP backend integration (nREPL/FlowStorm/JDI) still lives in defnet — defport ships the protocol routing, defnet ships what `next` and `step-in` actually mean.

### 6. Reader conditionals are for structural platform gaps only

After the cross-platform cleanup, remaining `#?(:clj ... :cljs ...)` conditionals should only appear in:

- `defport.util.platform` (the deliberate abstraction layer)
- Whole-function `#?(:clj ...)` guards for JVM-only features (client mode, Java IO)
- `ns` form `:require` blocks (unavoidable)

**Do not reintroduce mechanical conditionals** like `#?(:clj (.getMessage e) :cljs (.-message e))`. Use `platform/error-message` and friends. Use `platform/try-any` instead of reader-conditional catch types.

## Core Architecture

### Four Key Abstractions (defport.core)

1. **Port** - Protocol-agnostic capability (same port works via MCP, LSP, DAP, BSP)
2. **Transport** - Message delivery (stdio, HTTP, WebSocket, subprocess)
3. **ProtocolAdapter** - Protocol translation for server role (MCP, LSP, DAP, BSP)
4. **ClientTransport** - Pluggable client-side transport (one protocol per namespace)
5. **PortRegistry** - Port management (EDN, Function, Hybrid)
6. **Spec registry** - Plain-data wire method catalog per protocol, drift-checked on every test run

### Key files (by protocol)

| File | Purpose |
|------|---------|
| `src/defport/core.cljc` | Core protocols (Port, Transport, ProtocolAdapter, PortRegistry, ProtocolClient) |
| `src/defport/sugar.cljc` | Unified DSL + `sugar/run!` one-line launcher + `create-adapter` multimethod |
| `src/defport/util/platform.cljc` | Cross-platform abstraction layer (error, JSON, time, `try-any`, `unwrap`) |
| `src/defport/registry.cljc` | Registry implementations (EDN, Function, Hybrid) |
| `src/defport/transports/stdio.cljc` | Stdio transport — Content-Length and JSON-lines framing, both platforms |
| `src/defport/transports/framing.cljc` | Shared Content-Length + JSON-lines codecs |
| `src/defport/transports/http.cljc` | HTTP transport — Ring-compatible |
| `src/defport/transports/websocket_client.cljc` | WebSocket ClientTransport — JVM + Node, shared between CDP and rosbridge |
| **MCP** — `src/defport/mcp.cljc` | Server adapter (1,650 LOC) |
| `src/defport/mcp/spec.cljc` | 31 methods, drift-checked vs upstream `schema.json` |
| `src/defport/mcp/client.cljc` | Client core (11 typed helpers) |
| `src/defport/mcp/client/transports/subprocess.cljc` | Reference subprocess transport (JSON-lines) |
| **LSP** — `src/defport/lsp.cljc` | Server adapter (~1,900 LOC) |
| `src/defport/lsp/spec.cljc` | 80 methods covering LSP 3.17 |
| `src/defport/lsp/client.cljc` | Client core (18 typed helpers) |
| `src/defport/lsp/client/transports/subprocess.cljc` | Reference subprocess transport (Content-Length) |
| **DAP** — `src/defport/dap.cljc` | Server adapter (~1,100 LOC) |
| `src/defport/dap/spec.cljc` | 45 commands + 17 events |
| `src/defport/dap/client.cljc` | Client core (30 typed helpers) |
| `src/defport/dap/client/transports/subprocess.cljc` | Reference subprocess transport |
| **BSP** — `src/defport/bsp/spec.cljc` | 27 methods from upstream Smithy |
| `src/defport/bsp/client.cljc` | Client core (13 typed helpers) |
| `src/defport/bsp/client/transports/subprocess.cljc` | Reference subprocess transport |
| **CDP** — `src/defport/cdp/spec.cljc` | 664 commands + 237 events auto-derived from upstream JSON at load time |
| `src/defport/cdp/client.cljc` | Client core (20 typed helpers + generic `request!`) |
| `src/defport/cdp/client/transports/websocket.cljc` | WebSocket transport wrapper |
| **rosbridge v2.0** — `src/defport/ros2/spec.cljc` | 20 ops |
| `src/defport/ros2/client.cljc` | Client core (12 typed helpers) |
| `src/defport/ros2/client/transports/websocket.cljc` | WebSocket transport wrapper |
| `src/defport/inspect.cljc` | REPL introspection (datafy/nav) |

## Common Commands

```bash
# Run all tests (379 tests / 2,103 assertions at v0.3.0)
clojure -M:kaocha

# Run a specific test namespace
clojure -M:kaocha --focus defport.protocols.mcp-test

# Run a specific deftest
clojure -M:kaocha --focus defport.protocols.mcp-test/test-handler-returns-future

# REPL with test paths
clojure -M:test:examples

# Run examples
clojure -M:examples -m simple-mcp-server
```

## Development Patterns

### Creating a Port

**DSL (Recommended):**
```clojure
(mcp/deftool search-code
  "Search for code"
  [query :- :string]
  (do-search query))
```

**Programmatic:**
```clojure
(core/register-port! registry
  {:id :search-code
   :description "Search for code"
   :input-schema {:type "object" :properties {:query {:type "string"}}}
   :handler (fn [context]
              {:result (do-search (get-in context [:params :query]))})})
```

### Port Handler Context

```clojure
{:params {...}              ; Input parameters
 :port-registry ...         ; Registry instance
 :transport ...             ; Transport instance
 :metadata {:call-id ...    ; Operation ID
            :progress-callback ... ; Progress reporting
            :cancellation-check ...}} ; Check if cancelled
```

### Port Handler Return Values

```clojure
;; Success
{:result data}              ; For tools
{:messages [...]}           ; For prompts
{:contents [...]}           ; For resources

;; Error
{:error {:code -32602 :message "Invalid params"}}
```

### Dangerous Tools

```clojure
{:id :refactor-code
 :handler ...
 :metadata {:dangerous true}}  ; Filtered by default
```

Enable via `:enable-refactoring true` option or `DEFPORT_ENABLE_REFACTORING=true` env var.

## MCP Implementation Notes

### Content Types

All structured data uses TextContent with JSON (per MCP 2025-06-18 spec):

```clojure
;; Correct
{:type "text" :text (json/generate-string data)}

;; Wrong (ObjectContent doesn't exist)
{:type "object" :object data}  ; ❌
```

### tap> Observability

MCP adapter emits tap> events:

- `:mcp/tool-call` - Tool execution (with duration-ms, success?)
- `:mcp/error` - Errors (with error-code, error-message)
- `:mcp/operation-cancelled` - Cancellations
- `:mcp/subscription-added/removed` - Resource subscriptions

```clojure
;; Development
(add-tap println)

;; Production
(add-tap (fn [e] (when (:event e) (record-metric! e))))
```

### State Management

```clojure
;; Reset between tests
(mcp/reset-protocol-state!)
```

## Testing Notes

- Test files: `test/defport/protocols/*.clj`
- Use `(registry/create-function-registry)` for clean state
- Reset MCP state: `(mcp/reset-protocol-state!)`
- All core code is `.cljc` (JVM + Node.js)

## Platform Considerations

| Platform | Status |
|----------|--------|
| JVM (Clojure) | ✅ Full support |
| Node.js (CLJS) | ✅ Full support |
| Browser (CLJS) | ⚠️ WebSocket only |

Use reader conditionals: `#?(:clj ... :cljs ...)`

## Documentation

| Document | Purpose |
|----------|---------|
| [docs/INTEGRATION.md](docs/INTEGRATION.md) | Integration patterns (Component, Ring, auth, metrics) |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design rationale |
| [docs/PROJECT_HISTORY.md](docs/PROJECT_HISTORY.md) | Evolution and decisions |
| [ROADMAP.md](ROADMAP.md) | Feature roadmap |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

## Common Gotchas

1. **Port descriptors vs implementations:** `list-ports` returns data, `get-port` returns protocol
2. **Content types:** Always use TextContent with JSON for MCP structured data
3. **Dangerous tools:** Mark write operations with `{:metadata {:dangerous true}}`
4. **DSL global state:** Uses global atom - create custom registries for isolation
5. **Platform conditionals:** Check platform before I/O operations
