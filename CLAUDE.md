# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**defport** is a **LOW-LEVEL LIBRARY** for building protocol servers (MCP, LSP, DAP). It provides protocol adapters, NOT application frameworks.

**Status:** Phase 6 Complete | 141 tests, 1,027 assertions, 0 failures | 100% MCP 2025-06-18 spec compliant

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

## Core Architecture

### Four Key Abstractions (defport.core)

1. **Port** - Protocol-agnostic capability (same port works via MCP, LSP, DAP)
2. **Transport** - Message delivery (stdio, HTTP, WebSocket)
3. **ProtocolAdapter** - Protocol translation (MCP, LSP, DAP)
4. **PortRegistry** - Port management (EDN, Function, Hybrid)

### Key Files

| File | Purpose |
|------|---------|
| `src/defport/core.cljc` | Core protocols |
| `src/defport/dsl.cljc` | Progressive disclosure DSL (deftool, defprompt, etc.) |
| `src/defport/protocols/mcp.cljc` | MCP 2025-06-18 adapter |
| `src/defport/registry/core.cljc` | Registry implementations |
| `src/defport/transports/*.cljc` | Transport implementations |
| `src/defport/inspect.clj` | REPL introspection (datafy/nav) |

## Common Commands

```bash
# Run all tests
clojure -M:kaocha

# Run specific test namespace
clojure -M:kaocha --focus defport.protocols.mcp-test

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
