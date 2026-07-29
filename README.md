# defport

**Build protocol servers and clients in Clojure and ClojureScript — MCP, LSP, DAP, BSP, CDP, rosbridge.**

defport is to protocol work what Ring is to HTTP: a thin adapter between
JSON-RPC on the wire and your handler functions. Write a capability once,
expose it through any of the six protocols, on JVM or Node.

It is a library, not a framework. You bring auth, metrics, async and
lifecycle; defport handles framing, dispatch, capability negotiation and
spec conformance.

```clojure
;; deps.edn
{:deps {io.github.typmk/defport
        {:git/tag "v0.3.0" :git/sha "6a7d697"}}}
```

## A server

```clojure
(ns my-server
  (:require [defport.mcp :as mcp]
            [defport.sugar :as sugar]))

(mcp/deftool search
  "Search for code by pattern."
  [pattern :- :string]
  {:content [{:type "text" :text (my/search pattern)}]})

(defn -main [& _]
  (sugar/run! {:protocol :mcp
               :server-info {:name "my-server" :version "1.0.0"}}))
```

That is the whole server. `tools/list`, `tools/call`, capability
negotiation, JSON-lines framing, cancellation and lifecycle come from the
library. Point Claude Desktop, Cursor or MCP Inspector at it over stdio.

LSP and DAP have the same shape — `lsp/deflsp` and `dap/defcommand`.
Capabilities derive from the ports you register, so `hoverProvider` appears
in the `initialize` response without you writing a capability map, and
`:set-breakpoints` resolves to the wire name `"setBreakpoints"` from the DAP
spec registry.

## A client

Every protocol ships a client as well as a server adapter.

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

Same shape for `defport.lsp.client` against rust-analyzer or clojure-lsp,
`defport.dap.client` against debugpy, `defport.bsp.client` against Bloop or
sbt, `defport.cdp.client` against headless Chromium over WebSocket, and
`defport.ros2.client` against a ROS 2 robot via rosbridge — no
rclcpp/rclpy/DDS needed. See [examples/](examples/).

## One capability, several protocols

```clojure
(mcp/deftool find-callers [symbol :- :string]
  {:content [{:type "text" :text (pr-str (graph/callers symbol))}]})

(lsp/deflsp references [uri :- :string line :- :int col :- :int]
  (graph/references-at uri line col))

(dap/defcommand evaluate [expression :- :string frameId :- :int]
  {:result (graph/eval expression frameId)})
```

Three adapters over one registry. Each filters `defport.sugar/*registry*`
for its own metadata, so the protocols never see each other's ports while
sharing the logic underneath.

## Handlers are plain functions

A handler is `(fn [context] result)`. defport calls it synchronously and
wraps the return value. If you return something async it is unwrapped by
feature detection — Clojure `future`/`promise`/`delay`, Manifold deferreds
(resolved via `requiring-resolve`, no hard dependency), core.async channels
on the JVM, and `js/Promise` on Node.

**defport has no hard dependency on any async library.**

## Spec coverage

Verified programmatically on every test run against
`vscode-languageserver-protocol`, `@vscode/debugprotocol`, the upstream MCP
`schema.json`, the BSP Smithy spec, and Chromium's `browser_protocol.json`
and `js_protocol.json`:

| Protocol               | Official | defport | Coverage |
|------------------------|---------:|--------:|---------:|
| LSP 3.17 methods       |       78 |      80 |     100% |
| DAP 1.65 commands      |       45 |      45 |     100% |
| DAP 1.65 events        |       17 |      17 |     100% |
| MCP 2025-11-25 methods |       31 |      31 |     100% |
| BSP 2.2 methods        |       27 |      27 |     100% |
| CDP 1.3 commands       |      664 |     664 |     100% |
| CDP 1.3 events         |      237 |     237 |     100% |
| rosbridge v2.0 ops     |       20 |      20 |     100% |

385 tests, 2,106 assertions, 0 failures. Eight of them run against the real
thing rather than a mock: headless Chromium 142, rust-analyzer, debugpy,
`@modelcontextprotocol/server-everything`, and a Python stdlib client
driving defport's own MCP/LSP/DAP server roles.

## Platforms

| Platform      | Support                                                     |
|---------------|-------------------------------------------------------------|
| JVM           | Everything                                                   |
| Node (CLJS)   | Core server path — `defport.core`, `.mcp`, `.registry`, stdio. HTTP transport experimental |
| Browser (CLJS)| WebSocket only — no stdio, no subprocess                     |

Every `.cljc` namespace compiles clean on both JVM and Node, so a
Node project can build an MCP server with no JVM-only code on the graph.

## Observability

defport emits `tap>` events — `:mcp/tool-call`, `:mcp/error`,
`:mcp/operation-cancelled`, `:mcp/subscription-added`,
`:mcp/subscription-removed`, each with a `:timestamp`.

```clojure
(add-tap println)
```

Adapters and registries also support `datafy`/`nav` for REPL introspection.

## Documentation

| Document | What's in it |
|----------|--------------|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design rationale, concurrency model, protocol intersection |
| [INTEGRATION.md](docs/INTEGRATION.md) | Component, Integrant, Ring, Pedestal, auth and metrics patterns |
| [CONCURRENCY.md](docs/CONCURRENCY.md) | Thread safety on the JVM, event loop on Node |
| [PERFORMANCE.md](docs/PERFORMANCE.md) | Concurrent batch processing and tuning |
| [BENCHMARKING.md](docs/BENCHMARKING.md) | Running the benchmarks, reading results, regression detection |
| [CHANGELOG.md](CHANGELOG.md) | Version history |
| [ROADMAP.md](ROADMAP.md) | What's planned |

## Contributing

Issues and pull requests welcome. [CLAUDE.md](CLAUDE.md) records the design
principles the library is held to — worth reading before a change that adds
a dependency or moves work into the library that belongs in the caller.

## License

[Eclipse Public License 1.0](LICENSE).
