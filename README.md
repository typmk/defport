# defport

**Platform-agnostic, protocol-agnostic library for building protocol servers.**

Build MCP, LSP, DAP, and custom protocol servers with a clean, data-driven approach.

## Status

**Version:** 0.5.0-SNAPSHOT | **Tests:** 141 tests, 1,027 assertions, 0 failures

| Phase | Status | Highlights |
|-------|--------|------------|
| Phase 1: Core Infrastructure | ✅ Complete | Ports, Transports, Registries |
| Phase 2: MCP Protocol | ✅ Complete | 100% MCP 2025-06-18 spec compliance |
| Phase 3: Advanced Features | ✅ Complete | Malli, Builder API, Elicitation, Completions |
| Phase 4: Optional Features | ✅ Complete | ImageContent, Roots, Sampling |
| Phase 5: Performance | ✅ Complete | 5-10x speedup with concurrent batch processing |
| Phase 6: Integration | ✅ Complete | Integration patterns, tap> observability, datafy/nav |
| Phase 7: Multi-Protocol | 🔮 Planned | LSP client/proxy, DAP client/proxy |

---

## Quick Start (8 lines)

```clojure
(ns my-server
  (:require [defport :as mcp]))

(mcp/deftool greet
  "Greet a user"
  [name :- :string]
  {:greeting (str "Hello, " name "!")})

(mcp/start! {:name "my-server" :version "1.0.0"})
```

**That's it.** One require, define your tools, run.

---

## Philosophy: Low-Level Library

**Defport is a library, not a framework.** Like Ring for HTTP or Lacinia for GraphQL.

| We Provide | We Do NOT Provide |
|------------|-------------------|
| Protocol adapters (MCP, LSP*, DAP*) | Auth middleware |
| Transport layer (stdio, HTTP) | Metrics collectors |
| Port registry system | HTTP middleware stacks |
| Observability hooks (tap>, datafy/nav) | Component/Integrant adapters |

**Why?** You already have auth, metrics, and lifecycle management. Defport integrates with YOUR infrastructure.

```clojure
;; defport provides the MCP handler
(def mcp-handler (mcp/create-mcp-handler registry adapter))

;; YOU wrap it with YOUR middleware
(def app
  (-> mcp-handler
      (wrap-authentication your-auth-backend)  ; YOUR auth
      (wrap-metrics your-prometheus-registry)  ; YOUR metrics
      wrap-json))
```

See [docs/INTEGRATION.md](docs/INTEGRATION.md) for Component, Integrant, Ring, and Pedestal patterns.

---

## Core Concepts

### 1. Port (Capability)

A **Port** represents something your server can do. Protocol-agnostic - same port works via MCP, LSP, or any protocol.

```clojure
{:id :search-code
 :description "Search for code"
 :input-schema {:type "object" :properties {:query {:type "string"}}}
 :handler (fn [{:keys [params]}] {:result (search (:query params))})}
```

### 2. Transport (Message Delivery)

**Transports** handle low-level communication: `stdio` for CLI tools, `http` for web services.

### 3. ProtocolAdapter (Protocol Translation)

**Adapters** translate protocol messages (MCP, LSP, DAP) to port executions.

### 4. PortRegistry (Port Management)

**Registries** manage your ports. Load from EDN files, register programmatically, or both.

---

## Installation

```clojure
;; deps.edn
{:deps {io.github.yourorg/defport {:git/tag "v0.5.0" :git/sha "..."}}}
```

---

## Documentation

| Document | Description |
|----------|-------------|
| [INTEGRATION.md](docs/INTEGRATION.md) | Component, Integrant, Ring, auth, metrics patterns |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Design rationale and philosophy |
| [PERFORMANCE.md](docs/PERFORMANCE.md) | Batch processing and optimization |
| [CONCURRENCY.md](docs/CONCURRENCY.md) | Thread safety model |
| [PROJECT_HISTORY.md](docs/PROJECT_HISTORY.md) | Evolution and design decisions |
| [ROADMAP.md](ROADMAP.md) | Feature roadmap |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

---

## Examples

### Progressive Disclosure DSL (Recommended)

```clojure
(ns my-server
  (:require [defport :as mcp]))

;; Simple tool
(mcp/deftool search-code
  "Search for code matching a query"
  [query :- :string]
  [{:file "src/example.clj" :line 42 :code "(defn example [] ...)"}])

;; Tool with Malli schema
(mcp/deftool create-user
  "Create a new user"
  [:map
   [:name [:string {:min 1 :max 100}]]
   [:email [:re #".+@.+\..+"]]
   [:age {:optional true} [:int {:min 0 :max 150}]]]
  (create-user-in-db params))

;; Prompt
(mcp/defprompt explain-function
  "Generate explanation prompt"
  [function-name :- :string]
  [{:role "user" :content {:type "text" :text (str "Explain: " function-name)}}])

;; Resource
(mcp/defresource schema
  "Database schema"
  {:mime-type "application/edn"}
  (get-current-schema))

;; Start server
(mcp/start! {:name "my-server" :version "1.0.0" :transport :stdio})
```

### Data-Driven API (Full Control)

```clojure
(ns my-server
  (:require [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]
            [defport.transports.http :as http]))

;; Create registry
(def my-registry (registry/create-function-registry))

;; Register port
(core/register-port! my-registry
  {:id :search-code
   :description "Search for code"
   :input-schema {:type "object" :properties {:query {:type "string"}}}
   :handler (fn [context]
              {:result (search (get-in context [:params :query]))})})

;; Create adapter
(def adapter (mcp/create-mcp-adapter
               {:server-info {:name "my-server" :version "1.0.0"}}))

;; Create transport and start
(def transport (http/create-http-transport {:port 8080}))

(core/transport-start transport
  (fn [request]
    (core/protocol-dispatch adapter (:method request) (:params request)
      {:port-registry my-registry :transport transport})))
```

### REPL Introspection

```clojure
(require '[defport.inspect :as inspect])
(require '[clojure.datafy :refer [datafy]])

;; Inspect adapter state
(datafy adapter)
;; => {:type :mcp-adapter :protocol-version "2025-06-18" :methods [...]}

;; Quick registry summary
(inspect/registry-summary my-registry)
;; => {:port-count 5 :ports [{:id :search-code ...}]}
```

---

## Observability

Defport emits tap> events for zero-cost observability:

```clojure
;; Development - print all events
(add-tap println)

;; Production - route to metrics
(add-tap (fn [e]
           (when (and (map? e) (:event e))
             (case (:event e)
               :mcp/tool-call (prometheus/inc! :tool-calls {:tool (:tool e)})
               :mcp/error (prometheus/inc! :errors)
               nil))))
```

Events: `:mcp/tool-call`, `:mcp/error`, `:mcp/operation-cancelled`, `:mcp/subscription-added`, `:mcp/subscription-removed`

---

## Platform Support

| Platform | Status | Notes |
|----------|--------|-------|
| JVM (Clojure) | ✅ Full | All features |
| Node.js (CLJS) | ✅ Full | Via ClojureScript |
| Browser (CLJS) | ⚠️ Limited | WebSocket only |

---

## Why Defport?

| Aspect | Defport Approach |
|--------|------------------|
| Philosophy | Library, not framework - you stay in control |
| Protocols | MCP today, LSP and DAP planned |
| Auth/Metrics | You provide - integrates with your stack |
| Ergonomics | Convenience macros without magic |

---

## Contributing

Contributions welcome:

- Protocol adapters (LSP, DAP)
- Transport implementations (WebSocket, gRPC)
- Documentation improvements
- Bug reports

## License

EPL 1.0 (Eclipse Public License)

---

**Questions?** Open an issue or join #defport on Clojurians Slack.