# defport

**Synchronous, cross-platform, protocol-adapter library for building MCP, LSP, and DAP servers in Clojure/ClojureScript.**

Defport is to MCP what Ring is to HTTP: a thin, opinion-free adapter layer between JSON-RPC-on-the-wire and your handler functions. Build MCP, LSP, and DAP servers on JVM or Node with the same code.

## Status

**Tests:** 304 tests, 1,856 assertions, 0 failures
**MCP spec:** 100% MCP 2025-11-25 compliant
**Cross-platform:** JVM + Node (CLJS). Core library compiles clean on both.

| Phase | Status | Highlights |
|-------|--------|------------|
| Phase 1: Core Infrastructure | ✅ Complete | Ports, Transports, Registries, four core protocols |
| Phase 2: MCP Protocol | ✅ Complete | 100% MCP 2025-11-25 spec compliance |
| Phase 3: Advanced Features | ✅ Complete | Elicitation, Completions, Roots, Sampling, Progress |
| Phase 4: Performance | ✅ Complete | Concurrent batch processing (4 strategies) |
| Phase 5: Integration & Docs | ✅ Complete | ARCHITECTURE.md, INTEGRATION.md, tap> observability, datafy/nav |
| Phase 6: State Refactor | ✅ Complete | Per-adapter instance state (no more global atoms) |
| Phase 7: Cross-Platform Restructure | ✅ Complete | Scoped to server-adapter role, reader conditionals −67% |
| Phase 8: LSP/DAP Server Hardening | 🔮 Future | Real end-to-end coverage for LSP/DAP adapters |

---

## Quick Start — JVM

```clojure
(ns my-server
  (:require [mcp :as m]))

(m/deftool greet
  "Greet a user"
  [name :- :string]
  {:greeting (str "Hello, " name "!")})

(m/run! {:name "my-server" :version "1.0.0"})
```

One require, define your tools, run. Works with Claude Desktop, Cursor, and any MCP client.

## Quick Start — Node (CLJS)

Defport's core ships as `.cljc` and compiles clean to Node via shadow-cljs or `cljs.build.api`. Low-level API:

```clojure
(ns my-server
  (:require [defport.core :as core]
            [defport.registry :as reg]
            [defport.mcp :as mcp]
            [defport.transports.stdio :as stdio]))

(def registry (reg/create-function-registry))

(core/register-port! registry
  {:id :greet
   :description "Greet a user"
   :input-schema {:type "object"
                  :properties {:name {:type "string"}}
                  :required ["name"]}
   :handler (fn [ctx] {:result {:greeting (str "Hello, " (:name (:params ctx)))}})})

(def adapter (mcp/create-mcp-adapter))
(def transport (stdio/create-stdio-transport))

(core/transport-start transport
  (fn [request]
    (core/protocol-dispatch adapter
                            (:method request)
                            (:params request)
                            {:port-registry registry :request request})))
```

Handlers are synchronous on both platforms. If a handler returns a Promise on Node, the stdio transport chains `.then` and writes when resolved — synchronous code is the common case, async is opt-in.

---

## Philosophy: Low-Level Library

**Defport is a library, not a framework.** Like Ring for HTTP or Lacinia for GraphQL.

| We Provide | We Do NOT Provide |
|------------|-------------------|
| MCP/LSP/DAP protocol adapters | Auth middleware |
| Transports (stdio, HTTP) | Metrics collectors |
| Port registry system | HTTP middleware stacks |
| `Unwrappable` extension point for user async | Component/Integrant adapters |
| tap> observability hooks | Subprocess clients / external server drivers |
| datafy/nav for REPL introspection | Framework lifecycle management |

**Why?** You already have auth, metrics, async libraries, and lifecycle management in your application. Defport integrates with YOUR infrastructure. Six design principles (in `CLAUDE.md`) keep it that way.

### The synchronous contract

Port handlers are `(fn [context] result)`. Plain functions. Defport calls them synchronously and wraps the return value as a protocol response. Handlers may optionally return async types that defport unwraps via feature detection:

```clojure
;; Plain value (common case)
(m/deftool search [query :- :string]
  (db/query! "SELECT * FROM code WHERE text LIKE ?" [query]))

;; Clojure future / promise / delay — unwrapped automatically
(m/deftool slow-op [input :- :string]
  (future (expensive-computation input)))

;; Manifold deferred — detected via requiring-resolve, no hard dep
(m/deftool manifold-style [input :- :string]
  (d/chain (fetch input) process))

;; core.async channel — supported on JVM via <!!
(require '[clojure.core.async :as a])
(m/deftool channel-style [input :- :string]
  (a/<!! (a/go (a/<! (async-op input)))))

;; js/Promise — supported by the Node stdio transport via .then chaining
(m/deftool promise-style [url :- :string]
  (js/fetch url))
```

**Defport has zero hard dependencies on any async library.** You bring your own.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full concurrency model rationale.

---

## Core Concepts

### Port — a capability

Protocol-agnostic. The same port can be exposed via MCP tools, LSP commands, or DAP requests.

```clojure
{:id :search-code
 :description "Search for code"
 :input-schema {:type "object" :properties {:query {:type "string"}}}
 :handler (fn [ctx] {:result (search (:params ctx))})}
```

### Transport — message delivery

- `stdio` — one process per peer, inherently sequential, works on JVM and Node
- `http` — long-running daemon, concurrency managed by http-kit (JVM) or Node's event loop

### ProtocolAdapter — JSON-RPC translation

`McpAdapter` maps MCP methods (`tools/call`, `prompts/get`, etc.) to port invocations, handles cancellation, forwards `:metadata`, emits observability events.

### PortRegistry — port management

Three registry types (all implement the same protocol):
- `FunctionPortRegistry` — register at runtime
- `EdnPortRegistry` — load declarative port definitions from EDN
- `HybridPortRegistry` — both

---

## Installation

```clojure
;; deps.edn
{:deps {io.github.typmk/defport {:git/tag "v0.7.0" :git/sha "..."}}}
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design rationale, concurrency model, protocol intersection |
| [INTEGRATION.md](docs/INTEGRATION.md) | Component, Integrant, Ring, Pedestal, auth, metrics patterns |
| [PERFORMANCE.md](docs/PERFORMANCE.md) | Batch processing strategies |
| [CONCURRENCY.md](docs/CONCURRENCY.md) | Thread safety (JVM) and event loop (Node) |
| [ROADMAP.md](ROADMAP.md) | Feature roadmap and phase status |
| [CHANGELOG.md](CHANGELOG.md) | Version history |
| [CLAUDE.md](CLAUDE.md) | Design principles (non-negotiable rules for future work) |

---

## Examples

### Progressive disclosure DSL

```clojure
(ns my-server
  (:require [mcp :as m]))

;; Simple tool
(m/deftool search-code
  "Search for code matching a query"
  [query :- :string]
  [{:file "src/example.clj" :line 42 :code "(defn example [] ...)"}])

;; Prompt
(m/defprompt explain-function
  "Generate explanation prompt"
  [function-name :- :string]
  [{:role "user"
    :content {:type "text" :text (str "Explain: " function-name)}}])

;; Resource
(m/defresource schema
  "Database schema"
  {:mime-type "application/edn"}
  (get-current-schema))

;; Start server
(m/run! {:name "my-server" :version "1.0.0" :transport :stdio})
```

### Data-driven API

```clojure
(ns my-server
  (:require [defport.core :as core]
            [defport.registry :as registry]
            [defport.mcp :as mcp]
            [defport.transports.http :as http]))

(def my-registry (registry/create-function-registry))

(core/register-port! my-registry
  {:id :search-code
   :description "Search for code"
   :input-schema {:type "object" :properties {:query {:type "string"}}}
   :handler (fn [context]
              {:result (search (get-in context [:params :query]))})})

(def adapter (mcp/create-mcp-adapter
               {:server-info {:name "my-server" :version "1.0.0"}}))

(def transport (http/create-http-transport {:port 8080}))

(core/transport-start transport
  (fn [request]
    (core/protocol-dispatch adapter (:method request) (:params request)
                            {:port-registry my-registry :transport transport})))
```

### REPL introspection

```clojure
(require '[defport.inspect :as inspect])
(require '[clojure.datafy :refer [datafy]])

(datafy adapter)
;; => {:type :mcp-adapter
;;     :protocol-version "2025-11-25"
;;     :methods [...]
;;     :active-operations 0
;;     :resource-subscriptions 0}

(inspect/registry-summary my-registry)
;; => {:port-count 5 :ports [{:id :search-code ...}]}
```

---

## Observability

Defport emits tap> events for zero-cost observability:

```clojure
;; Development
(add-tap println)

;; Production
(add-tap (fn [e]
           (when (and (map? e) (:event e))
             (case (:event e)
               :mcp/tool-call         (prometheus/inc! :tool-calls {:tool (:tool e)})
               :mcp/error             (prometheus/inc! :errors)
               :mcp/operation-cancelled (prometheus/inc! :cancellations)
               nil))))
```

Events emitted: `:mcp/tool-call`, `:mcp/error`, `:mcp/operation-cancelled`, `:mcp/subscription-added`, `:mcp/subscription-removed`. All include `:timestamp`.

---

## Client mode (not included)

**Defport does not ship a subprocess client** for "my program wants to spawn an MCP server and drive it." That use case — spawning an external process and sending it JSON-RPC requests — belongs in your application, not in a protocol adapter library (the same way `clj-http` is separate from Ring).

If your application needs client-role capability, implement `defport.core/ProtocolClient` using whichever subprocess library fits your stack:

```clojure
(require '[babashka.process :as p]
         '[defport.core :as core])

(defrecord MyMcpClient [process in out state]
  core/ProtocolClient
  (protocol-connect [this transport client-info] ...)
  (protocol-request [this method params] ...)
  (protocol-notify  [this method params] ...)
  (protocol-disconnect [this] ...)
  (register-request-handler! [this method handler] ...))
```

The `ProtocolClient` protocol is defined and documented; implementations are your concern.

---

## Platform support

| Platform | Status | Notes |
|----------|--------|-------|
| JVM (Clojure) | ✅ Full | All server features, all transports, all tests |
| Node (CLJS) | ✅ Core library | `defport.mcp`, `defport.core`, `defport.registry`, `defport.transports.stdio` compile clean. HTTP transport on Node is experimental. |
| Browser (CLJS) | ⚠️ Limited | WebSocket only (no stdio, no subprocess spawning) |

**The core server path is truly cross-platform.** A CLJS/Node project can require `defport.mcp` and build an MCP server without any JVM-only code on the required graph.

---

## Why Defport?

| Aspect | Defport Approach |
|--------|------------------|
| Philosophy | Library, not framework |
| Protocols | MCP (production), LSP + DAP (exploratory) |
| Concurrency | Synchronous handlers, users bring their own async |
| Platform | `.cljc` throughout, honestly cross-platform |
| Auth/Metrics | You provide |
| Subprocess clients | You provide |

**20% fewer lines than FastMCP for equivalent server functionality**, with no Python dependency and genuine cross-platform support.

---

## Contributing

Contributions welcome:

- LSP/DAP adapter hardening (real end-to-end tests)
- CLJS HTTP transport validation
- Transport implementations (WebSocket, gRPC)
- Documentation improvements
- Bug reports

Before contributing, read [CLAUDE.md](CLAUDE.md) — the six design principles there are non-negotiable and apply to all new code.

## License

EPL 1.0 (Eclipse Public License)
