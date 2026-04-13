# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**defport** is a **LOW-LEVEL LIBRARY** for building protocol servers (MCP, LSP, DAP). It provides protocol adapters, NOT application frameworks.

**Status:** 304 tests, 1856 assertions, 0 failures | 100% MCP 2025-11-25 spec compliant | Core library compiles clean on JVM and Node (CLJS)

## Critical: Library Philosophy

**defport is like Ring for HTTP - a library, not a framework.**

| We Provide | We Do NOT Provide |
|------------|-------------------|
| Protocol adapters (MCP, LSP*, DAP*) | Auth middleware |
| Transport layer (stdio, HTTP) | Metrics collectors |
| Port registry system | HTTP middleware stacks |
| Observability hooks (tap>, datafy/nav) | Component/Integrant adapters |

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

### 5. The server/client asymmetry

- **Server-side defport** is fully synchronous and fully cross-platform. Node's `process.stdin.on('data', ...)` callbacks run synchronously in their body; no async machinery is needed.
- **Client-side defport does not ship.** The `ProtocolClient` protocol exists in `defport.core` as a *contract*; implementations belong in consumer code. A consumer that genuinely needs client role — spawning an external MCP server, proxying LSP traffic to rust-analyzer, observing a live DAP debug session — writes the `ProtocolClient` implementation in its own codebase using `babashka.process` / `ProcessBuilder` / Node `child_process`. Defnet is the canonical consumer; its DAP proxy lives in defnet, not here.
- The one place a platform semantic gap is real — Node cannot block the event loop while waiting on a subprocess — is isolated to that consumer-side code, not defport's.

### 6. Reader conditionals are for structural platform gaps only

After the cross-platform cleanup, remaining `#?(:clj ... :cljs ...)` conditionals should only appear in:

- `defport.util.platform` (the deliberate abstraction layer)
- Whole-function `#?(:clj ...)` guards for JVM-only features (client mode, Java IO)
- `ns` form `:require` blocks (unavoidable)

**Do not reintroduce mechanical conditionals** like `#?(:clj (.getMessage e) :cljs (.-message e))`. Use `platform/error-message` and friends. Use `platform/try-any` instead of reader-conditional catch types.

## Core Architecture

### Four Key Abstractions (defport.core)

1. **Port** - Protocol-agnostic capability (same port works via MCP, LSP, DAP)
2. **Transport** - Message delivery (stdio, HTTP, WebSocket)
3. **ProtocolAdapter** - Protocol translation (MCP, LSP, DAP)
4. **PortRegistry** - Port management (EDN, Function, Hybrid)

### Key Files

| File | Purpose |
|------|---------|
| `src/defport/core.cljc` | Core protocols (Port, Transport, ProtocolAdapter, PortRegistry, ProtocolClient contract) |
| `src/defport/mcp.cljc` | MCP 2025-11-25 server adapter — pure cross-platform |
| `src/defport/dap.cljc` | DAP server adapter — pure cross-platform |
| `src/defport/lsp.cljc` | LSP server adapter — pure cross-platform |
| `src/defport/registry.cljc` | Registry implementations (EDN, Function, Hybrid) |
| `src/defport/sugar.cljc` | Shared sugar infrastructure (`deftool`, `defprompt`, lifecycle hooks) |
| `src/defport/transports/stdio.cljc` | Stdio transport (JVM http-kit + Node callbacks in one file) |
| `src/defport/transports/http.cljc` | HTTP transport (JVM + Node, same pattern) |
| `src/defport/util/platform.cljc` | **The cross-platform abstraction layer** — error handling, JSON, time, `try-any` macro, `unwrap` extension point |
| `src/defport/inspect.cljc` | REPL introspection (datafy/nav), fully cross-platform |
| `src/defport/testing/server.cljc` | Test server helpers (cross-platform) |
| `src/defport/testing/client.clj` | Test client harness (JVM-only, subprocess spawning) |
| `src/mcp.cljc`, `src/dap.cljc`, `src/lsp.cljc` | Top-level sugar facades (single-segment namespaces: `mcp`, `dap`, `lsp`) |

## Common Commands

```bash
# Run all tests (304 tests / 1856 assertions)
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
