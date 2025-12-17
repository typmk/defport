# Defport Roadmap

**Version:** 0.5.0-SNAPSHOT → 1.0.0
**Status:** Phase 6 Complete | 141 tests, 1,027 assertions, 0 failures

---

## Completed Phases

### Phase 1: Core Infrastructure ✅

- Core protocols (Port, Transport, ProtocolAdapter, PortRegistry)
- Registry implementations (EDN, Function, Hybrid)
- Transport implementations (stdio, HTTP)
- Platform portability (.cljc for JVM + Node.js)

### Phase 2: MCP Implementation ✅

- MCP 2025-06-18 protocol adapter
- Progressive disclosure DSL (`deftool`, `defprompt`, `defresource`, `start!`)
- Better DX than Python's FastMCP (20% fewer lines)
- Resource subscriptions and change notifications

### Phase 3: Advanced MCP Features ✅

- Malli schema integration (three definition styles)
- Builder API (fluent programmatic construction)
- Elicitation support (server→client user input)
- Completions support (argument autocomplete)
- Logging level control (per-session filtering)

### Phase 4: Optional MCP Features ✅

- ImageContent & AudioContent (Base64, MIME types)
- Roots support (filesystem boundaries)
- Sampling support (server-initiated LLM requests)
- **Achievement:** 100% MCP 2025-06-18 spec compliant

### Phase 5: Performance Optimization ✅

- Concurrent batch processing (4 strategies)
- 5-10x speedup for I/O-bound operations
- 100% backward compatible (sequential by default)
- Comprehensive documentation (PERFORMANCE.md, CONCURRENCY.md)

### Phase 6: Integration & Documentation ✅

- `docs/INTEGRATION.md` - Component, Integrant, Ring, auth, metrics patterns
- `docs/ARCHITECTURE.md` - Design rationale and philosophy
- `defport.inspect` namespace - datafy/nav for REPL introspection
- tap> events throughout MCP adapter
- Integration examples (Component, Reitit)

---

## Current Metrics

| Metric | Value |
|--------|-------|
| MCP Spec Compliance | 100% |
| Tests | 141 |
| Assertions | 1,027 |
| Pass Rate | 100% |
| DX vs FastMCP | 20% fewer lines |

---

## Phase 7: Multi-Protocol Support 🔮

**Target:** Q2 2025
**Priority:** HIGH - Strategic transformation

### Strategic Vision

Transform defport from "MCP library for Clojure" to "Universal Protocol Abstraction for Code Intelligence."

```
Claude Desktop (MCP)
    ↓
defport
    ├── LSP Clients → Python, Rust, Go, TypeScript LSP servers
    ├── DAP Clients → Python, Node, Java debuggers
    └── Custom Ports → Your domain logic
```

### 7.1 LSP Client/Proxy Integration ⭐

**Goal:** Connect to ANY language server (100+ languages)

- Build LSP client infrastructure
- Route requests by file type automatically
- Expose LSP features via MCP tools
- Diagnostics/linting included automatically

**Value:** 10-100x more valuable than Clojure-only approach

### 7.2 DAP Client/Proxy Integration

**Goal:** Universal debugging integration

- Connect to ANY debug adapter
- AI-assisted debugging via MCP
- Multi-language breakpoint management

### 7.3-7.4 Optional Protocol Adapters

- GraphQL adapter (web integration)
- gRPC adapter (microservices)

---

## Phase 8: Platform Expansion 🔮

**Target:** Q3 2025

- ClojureScript build pipeline (Shadow-CLJS)
- NPM package for Node.js
- GraalVM native-image support

---

## Phase 9: Release & Distribution 🔮

**Target:** Q3 2025

- Publish to Clojars
- API reference (Codox)
- Tutorial series
- Release v1.0.0

---

## Success Metrics (1.0.0)

| Metric | Current | Target |
|--------|---------|--------|
| GitHub Stars | 0 | 100+ |
| Production Users | 1 (defnet) | 10+ |
| Protocols | MCP | MCP + LSP + DAP |
| Platforms | JVM, Node.js | + Browser, GraalVM |

---

## Out of Scope

These features are explicitly NOT planned:

- **CDP (Chrome DevTools Protocol)** - Different abstraction level (client automation)
- **Auth middleware** - Applications use buddy-auth
- **Metrics collectors** - Applications use Prometheus/iapetos
- **CLI framework** - Document patterns only
- **Building LSP servers** - Connect to existing servers instead
- **Building debuggers** - Connect to existing adapters instead

---

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| Q4 2024 | Library vs Framework | Applications retain control |
| Jan 2025 | LSP client approach | 10-100x value vs building servers |
| Jan 2025 | CDP out of scope | Mature ecosystem exists |
| Jan 2025 | No auth/metrics | Applications have these already |

See [docs/PROJECT_HISTORY.md](docs/PROJECT_HISTORY.md) for complete evolution.

---

*Last Updated: December 2025*