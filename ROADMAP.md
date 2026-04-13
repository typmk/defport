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
exist but were audited 2026-04-12 and found at substantially different
readiness levels. See "Audit findings" below for coverage tables.

### Audit findings (2026-04-12)

**LSP adapter (`defport/lsp.cljc`, 1499 LOC):** ~15% implemented, ~37%
stubbed (declared, no server handler), ~48% missing. Zero LSP tests exist.

- Works: `initialize`, `initialized`, `shutdown`, `exit`,
  `didOpen/didChange/didClose`, `ProtocolAdapter` contract, port metadata
  routing via `:metadata {:lsp {:method ...}}`
- Stubbed (declared, no server handler): `hover`, `definition`,
  `references`, `documentSymbol`, `completion`, `codeAction`, `rename`,
  `formatting`, `workspace/symbol`, `$/cancelRequest`, `$/progress`,
  `didSave`. Client-side helpers exist but only format outgoing params;
  server never responds.
- Missing entirely: `typeDefinition`, `implementation`, `signatureHelp`,
  `prepareRename`, pull `diagnostic`, `semanticTokens`, `foldingRange`,
  `workspace/applyEdit`, `executeCommand`, `window/*`
- **Load-bearing gap**: LSP's state atom is `{:initialized false}` only.
  MCP's has 140+ lines of cancellation/progress/subscription machinery.
  `$/cancelRequest` and `$/progress` cannot work until that infrastructure
  is ported across.

**DAP adapter (`defport/dap.cljc`, 943 LOC):** 13 implemented, 6 partial,
28 stubbed, 5 missing events. 442 LOC of isolation tests exist.

- Works: full lifecycle (`initialize`/`launch`/`attach`/
  `configurationDone`/`disconnect`/`terminate`), `setBreakpoints` with
  state storage and verified status, `continue`, `scopes`, `variables`,
  `evaluate` with port-registry integration, `completions`, message
  codecs, emitted events (`stopped`, `continued`, `terminated`, `output`,
  `breakpoint`)
- Hardcoded stubs: `threads` returns one-frame placeholder, `stackTrace`
  returns empty, `source`/`loadedSources`/`modules` return empty
- Unsupported: `next`, `stepIn`, `stepOut`, `stepBack`, `reverseContinue`,
  `pause`, `goto`, `setVariable`, `setExpression`
- Missing events: `exited`, `thread`, `module`, `process`
- Backend flags (`:nrepl`, `:flowstorm`, `:jdi`) affect capability
  reporting only; no backend integration code exists
- **Scope clarification**: the stepping/stackTrace/threads stubs only
  become real when a debug backend drives them, and **backends live in
  consumer code, not defport**. The defport-side DAP adapter is ~4 days
  from honest done (stackTrace plumbing, missing events, polish).

### 8.1 LSP core-features pass (~1 week)

- Port cancellation/progress state machinery from MCP (prerequisite for
  `$/cancelRequest` and `$/progress`)
- Implement default server handlers for `hover`, `definition`,
  `references`, `documentSymbol`, `rename` that route through port
  metadata
- Add `didSave` handling
- First real LSP test file — adapter in isolation, no real editor
  required. Integration tests against a real editor can come later in
  user code.

### 8.2 DAP adapter honest-done pass (~4 days)

- Port cancellation/progress state pattern from MCP
- `stackTrace` plumbing — proper frame structure, source/line/column,
  so consumer-side backends can fill it in
- Emit missing events: `exited`, `thread`, `module`, `process`
- Additional tests for the new plumbing

**Not in scope for defport**: nREPL/FlowStorm/JDI backend integration.
That's 2+ weeks of consumer-side work and belongs wherever the consumer
(defnet) spawns or attaches to debuggers.

### 8.3 Multi-adapter composition example

The Port/Transport/ProtocolAdapter/PortRegistry abstractions already
compose: a consumer instantiates multiple adapters against a single
registry and runs them together. **This is a pattern, not a feature** —
defport does not ship a "cross-protocol router" or a capability layer.
The work here is to *document and demonstrate* the composition, not
to build new infrastructure.

- `examples/multi-adapter/` — minimal example showing one registry
  feeding MCP + LSP + DAP adapters in one process, each on its own
  transport, each independently runnable
- Document the per-protocol metadata pattern
  (`:metadata {:protocols #{:mcp :lsp} :mcp {...} :lsp {...}}`) as
  the contract consumers use when a single port should surface
  through multiple protocols with shape translation
- Verify via defnet (the canonical consumer) that MCP + LSP composition
  works end-to-end against the same underlying graph. The capability
  layer, per-protocol exposure metadata, and DAP client/proxy all live
  in defnet — not in defport.

### 8.4 Sugar facade consolidation

Audit 2026-04-12 confirmed `src/mcp.cljc` (1041 LOC, ns `mcp`),
`src/lsp.cljc` (614 LOC, ns `lsp`), and `src/dap.cljc` (571 LOC, ns `dap`)
are pure DSL wrappers over `defport.{mcp,lsp,dap}`, not duplicated
implementation. Dependency direction is unidirectional
(sugar → adapter). **Zero test files and zero internal code requires
the single-segment namespaces** — they exist only for tutorial/README
ergonomics.

Single-segment namespaces cause CLJS warnings and collide with user code
(`(require '[mcp])`). Consolidation plan:

- Rename `src/mcp.cljc` → `src/defport/mcp_sugar.cljc` (ns
  `defport.mcp-sugar`), same for LSP and DAP
- Create `src/defport.cljc` as a root re-export for the README pattern
  `(:require [defport :as mcp] :refer [deftool])` — this matches how
  the examples already import things
- Verify the current state of examples/ and the classpath-resolution of
  bare `[defport ...]` requires before starting (some examples may be
  broken today)
- Run 304 tests green, update CHANGELOG

Estimated cost: ~2 hours. Low risk because the tests don't touch the
single-segment namespaces.

---

## Revised ordering (post-audit)

The audits re-ordered the critical path. Current sequence:

1. **Verify defport root namespace state** (30 min) — does
   `src/defport.cljc` exist, what do `(:require [defport ...])`
   statements in examples/ actually resolve to, what's broken
2. **Sugar consolidation** (8.4, ~2 h) — rename sugar files, add root
   re-export, update examples, 304 tests green
3. **Defport cleanup** (~3 h) — 3 stray catch-type conditionals, stdio
   transport consolidation/documentation, `examples/multi-adapter/`
4. **DAP honest-done pass** (8.2, ~4 d) — cancellation/progress state,
   stackTrace plumbing, missing events
5. **LSP core-features pass** (8.1, ~1 w) — state port from MCP, default
   handlers for hover/definition/references/documentSymbol/rename,
   first real test file

At this point defport ships three composable adapters at honest parity.
The follow-on work (defnet capability layer, defnet LSP facade, defnet
DAP client/proxy/recorder) is consumer-side and tracked in defnet's
roadmap, not here.

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
| 2026-04-12 | Unified use is emergent, not a feature | Defport ships three independent adapters that all consume `PortRegistry`. Composing them is a user-code concern. No cross-protocol router, no capability layer in defport. The bar: individual use simple enough that multi-adapter composition is a trivial consequence. |
| 2026-04-12 | Capability layer lives in consumer | A capability is a function over a domain model exposed through multiple protocols with shape translation. This belongs in the consumer (defnet), not defport. Defport provides `ProtocolAdapter` + `PortRegistry`; the consumer provides the capabilities and the per-protocol metadata that controls exposure. |
| 2026-04-12 | DAP client + proxy belongs in defnet | Defnet's use of DAP (observing live debug sessions, projecting runtime events onto the static graph) is the "semantic meets metal" bridge. That implementation lives in defnet, not defport. Defport provides the `ProtocolClient` contract and the DAP protocol mechanics; defnet provides the subprocess spawning, the proxy transport, and the source-to-graph attribution. |

See [docs/PROJECT_HISTORY.md](docs/PROJECT_HISTORY.md) for complete evolution.

See [CLAUDE.md](CLAUDE.md) for the six non-negotiable design principles.

---

*Last Updated: 2026-04-12*
