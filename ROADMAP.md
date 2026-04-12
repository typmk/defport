# Defport Roadmap

**Version:** 0.7.0-SNAPSHOT → 1.0.0
**Status:** Phase 7 Complete | 304 tests, 1,856 assertions, 0 failures

---

## Completed Phases

### Phase 1: Core Infrastructure ✅

- Core protocols (Port, Transport, ProtocolAdapter, PortRegistry)
- Registry implementations (EDN, Function, Hybrid)
- Transport implementations (stdio, HTTP)
- Platform portability (`.cljc` for JVM + Node.js)

### Phase 2: MCP Implementation ✅

- MCP 2025-11-25 protocol adapter
- Progressive disclosure DSL (`deftool`, `defprompt`, `defresource`, `run!`)
- 20% fewer lines than Python's FastMCP
- Resource subscriptions and change notifications
- Server-initiated sampling, elicitation, roots

### Phase 3: Advanced MCP Features ✅

- Three schema definition styles (Malli, plain, fluent)
- Builder API
- Elicitation (form and URL modes, MCP 2025-11-25)
- Completions (argument autocomplete)
- Per-session log level control

### Phase 4: Performance ✅

- Concurrent batch processing (four strategies: sequential, pmap, futures, core-async)
- 5–10x speedup for I/O-bound operations
- 100% backward compatible (sequential by default)

### Phase 5: Integration & Documentation ✅

- `docs/INTEGRATION.md` — Component, Integrant, Ring, Pedestal patterns
- `docs/ARCHITECTURE.md` — design rationale
- `defport.inspect` — datafy/nav for REPL introspection
- tap> events throughout MCP adapter

### Phase 6: State Refactor ✅

**Eliminated 8 global `defonce` atoms in the MCP adapter.** Each
`McpAdapter` now owns its own state via a `:state*` field holding a
single atom of an immutable map, threaded into handler context.
Multi-server-per-process works correctly.

- `create-protocol-state` creates fresh state
- `active-operations` flattened from nested `(atom false)` cancellation
  flags to `:active-operations` / `:cancelled-operations` sets
- Client-side state (3 globals) consolidated similarly
- `core.cljc` `register-port!` shadowing bug fixed
  (`register-global-port!` is the new name for the global-registry
  convenience function)

### Phase 7: Cross-Platform Restructure ✅

**Scoped defport honestly to its server-adapter role.**

- **Reader conditionals 225 → 74 (−67%)**, whole-def structural
  conditionals 92 → 8 (−91%).
- **Removed ~1,600 lines of unused speculative code** — subprocess
  client modules (`defport.mcp-client`, `dap-client`, `lsp-client`),
  `McpClient` record + 13 `client-*` functions, `create-server` /
  `start!` / `stop!` / `create-client` stubs.
- **Concurrency model codified**: synchronous handler contract
  (Ring-style), users bring their own async via the `Unwrappable`
  protocol (feature-detects `IDeref`/manifold/etc., no hard deps).
- **Node stdio transport** verified cross-platform — core server
  path compiles clean on CLJS.
- **Platform abstraction layer (`defport.util.platform`)** grown with
  `error-message`, `error-type`, `try-any` macro, `unwrap`, `process-id`,
  `utf8-byte-length`.
- **Namespace drift fixed**: `defport.protocols.mcp` → `defport.mcp`
  propagated across 36 files that were broken since commit `2d7918e`.
- **Compliance validator** no longer recurses into user-defined JSON
  Schema (tool schemas can use snake_case without errors).
- **Metadata forwarding**: `handle-tools-call` now preserves
  `:metadata` from handler results.
- **`:listChanged true`** now advertised for tools/prompts/resources
  since the adapter supports notifications.

---

## Current Metrics

| Metric | Value |
|--------|-------|
| MCP Spec Compliance | 100% (2025-11-25) |
| Tests | 304 |
| Assertions | 1,856 |
| Pass Rate | 100% |
| Core library CLJS compile | Clean |
| Reader conditionals | 74 (−67% from pre-Phase-7 baseline) |
| Lines of code | ~9,500 (−1,600 from Phase 6 baseline) |
| DX vs FastMCP | 20% fewer lines |

---

## Phase 8: LSP/DAP Server Hardening 🔮

**Priority:** Medium. The MCP story is solid. LSP and DAP server adapters
exist but haven't been validated end-to-end against real clients.

### 8.1 Real LSP test coverage

- Integration tests against `clojure-lsp` as the client — verify
  defport's LSP adapter can serve hover, completion, definition,
  references against a real editor
- Document test suite: spawn an editor or simulated LSP client, run
  workflows
- Fix bugs surfaced by real usage

### 8.2 Real DAP test coverage

- Currently only REPL-mode breakpoint/stepping stubs are tested.
  Real DAP needs to work against an actual debug UI (VS Code, or
  a scripted test harness that speaks DAP)
- Validate session lifecycle, breakpoint verification, stack frame
  inspection

### 8.3 Cross-protocol port routing

The four Port/Transport/ProtocolAdapter/PortRegistry abstractions
were designed so one port definition could be exposed via all three
protocols. Today this is aspirational — there's no example showing
a single port definition serving MCP tools AND LSP commands AND DAP
requests. Build it, document it, or remove it from the design claims.

---

## Phase 9: CLJS/Node Story Hardening 🔮

**Priority:** Medium. The core MCP server path compiles clean on Node,
but hasn't been exercised end-to-end with a real client.

### 9.1 End-to-end CLJS MCP server

- `examples/cljs-node/` — a minimal MCP server built with shadow-cljs,
  compiled to Node, connected to Claude Desktop
- Documented build process and deployment
- Validates the synchronous Node stdio transport in production

### 9.2 Node HTTP transport

- The HTTP transport has a `:cljs` branch that's untested
- Verify it works for multi-client scenarios
- Document session management patterns (apps need to key state
  per-client when running as HTTP daemon)

### 9.3 Shadow-cljs build docs

- How to consume defport from a CLJS project
- How to deploy to AWS Lambda, Cloud Run, or similar serverless
- Publishing to npm (if we decide this is a goal)

---

## Phase 10: Release & Distribution 🔮

**Target:** Q3 2026
**Prerequisites:** Phases 8–9 complete, or explicit decision to ship
without them

### 10.1 Publish to Clojars

- Versioning strategy (semantic, follows MCP spec revisions)
- `pom.xml` / `deps.edn` cleanup
- Artifact naming

### 10.2 API reference

- Codox for docstring extraction
- Hosted on a project site

### 10.3 Tutorial series

- "Build an MCP server in 10 lines"
- "Integrate defport into an existing Ring app"
- "CLJS + Node MCP servers for serverless deployment"

### 10.4 Release v1.0.0

---

## Success Metrics (1.0.0)

| Metric | Current | Target |
|--------|---------|--------|
| GitHub Stars | 0 | 100+ |
| Production Users | 1 (defnet) | 10+ |
| Protocols | MCP (production) | MCP + LSP + DAP production |
| Platforms | JVM, Node (core) | JVM, Node full |
| Tests | 304 | Maintain |

---

## Out of Scope (explicitly not planned)

These features are **not planned** and contributions adding them will
be declined unless the design rationale changes:

- **Subprocess client modes** (spawning external MCP/LSP/DAP servers
  from defport). This is application concern, not library concern.
  The `ProtocolClient` protocol exists as a contract; implementations
  live in user code. (See Phase 7 for context on why.)
- **Auth middleware** — applications use buddy-auth or their own
- **Metrics collectors** — applications use Prometheus/iapetos via tap>
- **CLI framework** — document patterns only
- **Lifecycle management** — applications use Component/Integrant/Mount
- **CDP (Chrome DevTools Protocol)** — different abstraction level,
  mature ecosystem already exists
- **Core async as a required dependency** — users bring their own
  async primitive; defport stays synchronous
- **Session/tenancy management in the HTTP transport** — applications
  layer this via middleware

---

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| Q4 2024 | Library vs Framework | Applications retain control |
| Jan 2025 | LSP client approach | 10-100x value vs building servers |
| Jan 2025 | CDP out of scope | Mature ecosystem exists |
| Jan 2025 | No auth/metrics | Applications have these already |
| 2026-04-12 | State refactor | 8 globals → per-adapter instance state |
| 2026-04-12 | Subprocess clients removed | Not defport's concern; belongs in user code. Zero tests, zero callers. ~1,600 lines deleted. |
| 2026-04-12 | Synchronous handler contract codified | Ring-style. Never add async primitives to the contract. Users bring their own async via `Unwrappable`. |
| 2026-04-12 | Reader conditional concentration | Platform-specific code lives in `defport.util.platform`. Mechanical conditionals eliminated via helpers (`error-message`, `try-any`, `now-ms`, etc.). |

See [docs/PROJECT_HISTORY.md](docs/PROJECT_HISTORY.md) for complete evolution.

See [CLAUDE.md](CLAUDE.md) for the six non-negotiable design principles.

---

*Last Updated: 2026-04-12*
