# defport

**A Clojure/ClojureScript library for building MCP, LSP, and DAP servers and clients.**

Defport is to protocol work what Ring is to HTTP: a thin, opinion-free
adapter layer between JSON-RPC-on-the-wire and your handler functions.
Define each capability once, surface it through three protocols, on
JVM or Node.

## Status

**Tests:** 379 kaocha / 2,103 assertions / 0 failures. 194 CLJS
smoke / 584 assertions / 0 failures. 8 real-external integration
tests — MCP ↔ `@modelcontextprotocol/server-everything`, LSP ↔
rust-analyzer 1.94.1, DAP ↔ debugpy 1.8.20, **CDP ↔ real headless
Chromium 142**, ROS 2 ↔ fake rosbridge; MCP/LSP/DAP server roles
validated by a Python stdlib external client.

**Spec coverage** — verified programmatically on every test run
against `vscode-languageserver-protocol`, `@vscode/debugprotocol`,
the upstream MCP `schema.json`, the BSP Smithy spec, and
Chromium's `browser_protocol.json` + `js_protocol.json`:

| Protocol                | Official | defport | Coverage |
|-------------------------|---------:|--------:|---------:|
| LSP 3.17 methods        |    78    |   80    |  100.0%  |
| DAP 1.65 commands       |    45    |   45    |  100.0%  |
| DAP 1.65 events         |    17    |   17    |  100.0%  |
| MCP 2025-11-25 methods  |    31    |   31    |  100.0%  |
| BSP 2.2 methods         |    27    |   27    |  100.0%  |
| CDP 1.3 commands        |   664    |  664    |  100.0%  (data-driven from upstream JSON) |
| CDP 1.3 events          |   237    |  237    |  100.0%  |
| rosbridge v2.0 ops      |    20    |   20    |  100.0%  |

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

## Quick start — BSP (Build Server Protocol)

Same shape as LSP/DAP. Spawn a BSP server (sbt, Mill, Bloop,
Bazel) and query its build graph:

```clojure
(require '[defport.bsp.client :as bsp]
         '[defport.bsp.client.transports.subprocess :as sub])

(def build
  (-> (sub/transport ["bloop" "bsp"])
      (bsp/create-client)
      (bsp/connect! {:root-uri "file:///path/to/project"
                     :capabilities {:languageIds ["clojure"]}})))

(bsp/await (bsp/workspace-build-targets build))
(bsp/await (bsp/build-target-compile build [{:uri "bloop://my-target"}]))
```

Full surface: 27 methods covering lifecycle, workspace discovery,
build target operations (sources/dependencies/compile/test/run),
debug sessions, and task/diagnostic notifications.

## Quick start — CDP (Chrome DevTools Protocol)

Drive a real Chromium browser over WebSocket:

```clojure
(require '[defport.cdp.client :as cdp]
         '[defport.cdp.client.transports.websocket :as ws]
         '[cheshire.core :as json])

;; Start chromium with: chromium --headless=new --remote-debugging-port=9222
(def page (first (filter #(= "page" (:type %))
                         (json/parse-string
                           (slurp "http://localhost:9222/json") true))))

(def browser
  (-> (ws/transport (:webSocketDebuggerUrl page))
      (cdp/create-client)
      (cdp/connect! {})))

(cdp/await (cdp/browser-get-version browser))
(cdp/await (cdp/page-navigate browser "https://example.com"))
(cdp/await (cdp/runtime-evaluate browser "document.title"))
(cdp/await (cdp/page-capture-screenshot browser))
```

The full CDP surface (664 commands + 237 events across 56 domains)
is reachable via `(cdp/request! client :Domain/command params)`.
20 common commands have typed helpers.

## Quick start — ROS 2 via rosbridge

Talk to a ROS 2 robot from Clojure without rclcpp/rclpy/DDS:

```clojure
(require '[defport.ros2.client :as ros2]
         '[defport.ros2.client.transports.websocket :as ws])

;; Robot runs: ros2 launch rosbridge_server rosbridge_websocket_launch.xml
(def robot
  (-> (ws/transport "ws://robot.local:9090")
      (ros2/create-client)
      (ros2/connect! {})))

;; Subscribe to a laser scan topic
(ros2/on-topic robot "/scan" (fn [msg] (println :ranges (:ranges msg))))
(ros2/subscribe! robot "/scan" "sensor_msgs/msg/LaserScan")

;; Call a service
@(ros2/await (ros2/call-service robot "/add_two_ints" {:a 1 :b 2}))

;; Send a nav goal
(ros2/send-action-goal robot "/navigate_to_pose"
  {:pose {:header {:frame_id "map"}
          :pose {:position {:x 5 :y 3 :z 0}
                 :orientation {:x 0 :y 0 :z 0 :w 1}}}})
```

20 ops covering lifecycle, topics, services, and actions. Uses
the same JSON-over-WebSocket transport as CDP.

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
