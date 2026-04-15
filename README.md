# defport

**A Clojure/ClojureScript library for building MCP, LSP, and DAP servers and clients.**

Defport is to protocol work what Ring is to HTTP: a thin, opinion-free
adapter layer between JSON-RPC-on-the-wire and your handler functions.
Define each capability once, surface it through three protocols, on
JVM or Node.

## Status

**Tests:** 374 kaocha / 2,086 assertions / 0 failures. 194 CLJS /
584 assertions / 0 failures. 3 real-external integration tests
(MCP passes against
[`@modelcontextprotocol/server-everything`](https://www.npmjs.com/package/@modelcontextprotocol/server-everything);
LSP + DAP skip cleanly when their external peer isn't installed).

**Spec coverage** — verified programmatically on every test run
against `vscode-languageserver-protocol`, `@vscode/debugprotocol`,
and the upstream MCP `schema.json`:

| Protocol                | Official | defport | Coverage |
|-------------------------|---------:|--------:|---------:|
| LSP 3.17 methods        |    78    |   80    |  100.0%  |
| DAP 1.65 commands       |    45    |   45    |  100.0%  |
| DAP 1.65 events         |    17    |   17    |  100.0%  |
| MCP 2025-11-25 methods  |    31    |   31    |  100.0%  |

**Cross-platform:** JVM + Node (CLJS). Every `.cljc` namespace
compiles clean on both.

---

## Quick start — MCP server

```clojure
(ns my-mcp-server
  (:require [defport.mcp :as mcp]
            [defport.sugar :as sugar]))

(mcp/deftool search
  "Search for code by pattern."
  [pattern :- :string]
  {:content [{:type "text" :text (my/search pattern)}]})

(defn -main [& _]
  (sugar/run! {:protocol :mcp
               :server-info {:name "my-mcp-server" :version "1.0.0"}}))
```

Defport supplies everything else: `tools/list`, `tools/call`,
capability negotiation, JSON-lines framing (per MCP 2025-11-25),
cancellation state, lifecycle handlers. For
`examples/mcp_server.clj` that's **13 lines of user code**. Point
Claude Desktop, Cursor, or MCP Inspector at it via stdio.

## Quick start — LSP server

```clojure
(ns my-lsp-server
  (:require [defport.lsp :as lsp]
            [defport.sugar :as sugar]))

(lsp/deflsp hover
  "Return hover info at a position."
  [uri :- :string line :- :int col :- :int]
  {:contents {:kind "markdown" :value (my/explain-at uri line col)}})

(lsp/deflsp references
  "Find references to the symbol at a position."
  [uri :- :string line :- :int col :- :int]
  (my/references-at uri line col))

(defn -main [& _]
  (sugar/run! {:protocol :lsp
               :server-info {:name "my-lsp-server" :version "1.0.0"}}))
```

`deflsp` reads sugar shapes from `defport.lsp.spec` at macroexpansion
time — position, range, document, rename shapes are pre-extracted
from raw LSP params. Capabilities derive from registered ports, so
`hoverProvider` and `referencesProvider` show up in the `initialize`
response without you writing a capability map.

## Quick start — DAP server

```clojure
(ns my-dap-server
  (:require [defport.dap :as dap]
            [defport.sugar :as sugar]))

(dap/defcommand evaluate
  "Evaluate an expression in the top stack frame."
  [expression :- :string frameId :- :int]
  {:result (my/eval-in-frame expression frameId)
   :variablesReference 0})

(defn -main [& _]
  (sugar/run! {:protocol :dap
               :server-info {:name "my-dap-server" :version "1.0.0"}
               :backend :repl}))
```

`defcommand` resolves `:step-in` → `"stepIn"`,
`:set-breakpoints` → `"setBreakpoints"` automatically via the DAP
spec registry — correct camelCase wire names without touching them.

## Quick start — talking to external servers

MCP client spawning `@modelcontextprotocol/server-filesystem`:

```clojure
(require '[defport.mcp.client :as mcp]
         '[defport.mcp.client.transports.subprocess :as sub])

(def fs
  (-> (sub/transport ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/tmp"])
      (mcp/create-client)
      (mcp/connect! {:client-info {:name "my-client" :version "0.1.0"}})))

(mcp/await (mcp/list-tools fs))
(mcp/await (mcp/call-tool fs "read_file" {:path "/tmp/foo.txt"}))
```

Same shape for `defport.lsp.client` against rust-analyzer or
clojure-lsp, and `defport.dap.client` against debugpy. Each ships
18–30 typed convenience helpers reading wire names from its spec
registry.

## Multi-protocol in one process

```clojure
(mcp/deftool find-callers [symbol :- :string]
  {:content [{:type "text" :text (pr-str (graph/callers symbol))}]})

(lsp/deflsp references [uri :- :string line :- :int col :- :int]
  (graph/references-at uri line col))

(dap/defcommand evaluate [expression :- :string frameId :- :int]
  {:result (graph/eval expression frameId)})
```

Three adapters, three protocols, one shared registry. Each adapter
filters `defport.sugar/*registry*` for its own metadata
(`:mcp/tool`, `:lsp/method`, `:dap/command`). The protocols never
see each other's ports but share the same underlying logic. See
`examples/multi_adapter.clj`.

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
