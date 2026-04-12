# Session State: Phase 7 COMPLETE — Cross-Platform Restructure

**Phase:** 7 — Cross-Platform Restructure
**Status:** ✅ **COMPLETE** (2026-04-12)
**Tests:** 304 tests, 1,856 assertions, 0 failures
**Goal:** Scope defport honestly to server-adapter role; concentrate
platform-specific code; ship a truly cross-platform core.

---

## Phase 7 Completion Summary

### The big changes

This phase combined three interlocking improvements:

1. **State refactor** — eliminated 8 global `defonce` atoms in the MCP
   adapter, replaced with a single atom-of-map owned by each adapter
   instance. Multi-server-per-process now works.

2. **Scope correction** — removed subprocess client features that
   don't fit defport's role. `defport.mcp-client`, `dap-client`,
   `lsp-client` modules deleted. `McpClient` record + 13 `client-*`
   functions deleted. Four vestigial "not yet implemented" stubs
   deleted. **~1,600 lines of unused speculative code gone.**

3. **Reader conditional concentration** — 225 → 74 (−67%) total,
   whole-def structural conditionals 92 → 8 (−91%). Platform-specific
   code now lives in `defport.util.platform` where it belongs.
   Core adapter files (`defport/mcp.cljc`, `defport/dap.cljc`,
   `defport/lsp.cljc`) are essentially cross-platform.

### Completed items ✅

1. **Fixed namespace drift** — `defport.protocols.mcp` → `defport.mcp`
   propagated across 36 files. Test suite became runnable for the
   first time since commit 2d7918e.

2. **Fixed `register-port!` shadowing bug** — the `defn register-port!`
   in `defport.core` was clobbering the `PortRegistry` protocol method
   with the same name. Renamed the global convenience to
   `register-global-port!`. Protocol dispatch now works as documented.

3. **State refactor** — 8 `defonce` atoms → single `create-protocol-state`
   atom per adapter. `active-operations` flattened from nested
   `(atom false)` flags to `:active-operations` / `:cancelled-operations`
   sets. Client-side state (3 more globals) also consolidated —
   then deleted entirely when client mode was removed.

4. **`Unwrappable` extension point** — `platform/unwrap` handles
   user-supplied async return types (future, promise, delay, manifold
   deferred via `requiring-resolve`). Zero hard deps on async libraries.
   Threaded through three dispatch sites: `tools/call`, `prompts/get`,
   `resources/read`. Five tests verify the contract end-to-end.

5. **`platform/try-any` macro** — catches `Throwable` on JVM,
   `:default` on CLJS via `(:ns &env)` detection. Eliminates
   `catch` reader conditionals at call sites. Supports optional
   `finally` clauses.

6. **Node (CLJS) stdio transport** — synchronous `readline` callbacks,
   supports handler Promise returns via `.then` chaining. Core
   server path compiles clean on Node.

7. **Compliance validator fixed** — `validate-field-naming` no longer
   recurses into user-defined JSON Schema. Unblocks tools with
   Python-style `user_id`/`error_message` field names.

8. **Metadata forwarding** — `handle-tools-call` now preserves
   `:metadata` from handler results so tools can return sampling
   request info, progress hints, etc.

9. **Server capabilities corrected** — `:listChanged true` now
   advertised for tools/prompts/resources (was `false` despite the
   adapter supporting notifications).

10. **Subprocess client features removed** (the scope correction).
    Zero tests, zero callers, wrong scope. Users needing client-role
    features implement `ProtocolClient` in their own code.

11. **Documentation** — `docs/ARCHITECTURE.md` gained major sections
    on Concurrency Model and Protocol Intersection. Six design
    principles codified in `CLAUDE.md`.

### Files created

```
New JVM-only file (extracted test harness):
  src/defport/testing/client.clj  (renamed from .cljc, removed all
                                   #?(:clj ...) wrappers since it was
                                   always JVM-only in spirit)
```

### Files deleted

```
Subprocess client modules (scope correction):
  src/defport/mcp_client.clj    (~350 lines)
  src/defport/dap_client.clj    (~430 lines)
  src/defport/lsp_client.clj    (~250 lines)
```

### Files modified

```
Source:
  src/defport/core.cljc         (−132: removed create-server, start!,
                                 stop!, create-client stubs; updated
                                 ProtocolClient docstring)
  src/defport/mcp.cljc          (−481: McpClient record + 13 client-*
                                 functions removed; state refactor;
                                 unwrap plumbed through)
  src/defport/dap.cljc          (JVM-only code extracted then removed)
  src/defport/lsp.cljc          (JVM-only code extracted then removed;
                                 platform/utf8-byte-length, process-id
                                 used)
  src/defport/sugar.cljc        (start-transport! / stop-transport!
                                 made cross-platform)
  src/defport/inspect.cljc      (datafy extend-type unified across
                                 platforms)
  src/defport/util/platform.cljc (error-message, error-type, try-any,
                                  unwrap, process-id, utf8-byte-length
                                  added)
  src/defport/util/batch.cljc   (pmap-batch CLJS branch fixed)
  src/defport/util/edn.cljc     (clojure.reader/read-string typo fixed)
  src/defport/util/progress.cljc (uses platform/json-encode)
  src/defport/transports/stdio.cljc (Node CLJS impl with Promise chaining)
  src/defport/transports/http.cljc  (uses platform/json-encode)
  src/defport/testing/server.cljc   (uses platform/try-any)
  src/mcp.cljc                  (client mode section removed)
  src/dap.cljc                  (client mode section removed)
  src/lsp.cljc                  (client mode section removed; run!
                                 block-forever made cross-platform)
  test/defport/protocols/mcp_test.clj (5 new unwrap tests)

Docs:
  docs/ARCHITECTURE.md  (+Concurrency Model, +Protocol Intersection)
  CLAUDE.md             (+Core Design Principles section)
  README.md             (status, quick-start, cross-platform section)
  ROADMAP.md            (Phase 7 complete, Phase 8+ planned)
  CHANGELOG.md          (Phase 7 entry added)
  SESSION_STATE.md      (this file)
```

### Metrics

| Metric | Before Phase 7 | After |
|--------|---------------:|------:|
| Tests | Unrunnable | 304 |
| Assertions | — | 1,856 |
| Failures | Couldn't load | 0 |
| Reader conditionals (total) | 225 | 74 |
| Whole-def conditionals | 92 | 8 |
| Global `defonce` atoms in McpAdapter | 8 | 0 |
| Client-side global atoms | 3 | 0 |
| Lines of source code | ~11,100 | ~9,500 |
| Core library CLJS compile | Broken (namespace drift) | Clean |

---

## Next Phase: Phase 8 — LSP/DAP Server Hardening

**Priority:** Medium
**Prerequisites:** None (all phases 1-7 complete)

### 8.1 Real LSP test coverage

Defport's LSP server adapter exists but has no end-to-end tests against
a real editor. Building real test coverage would:

- Validate the adapter against actual LSP client expectations
- Surface bugs in method routing, capability negotiation, document sync
- Enable production use of defport as an LSP server backend

**Approach:** Either spawn a real LSP client in tests (headless editor
or scripted protocol test harness) or use `clojure-lsp` as a reference
implementation to test against.

### 8.2 Real DAP test coverage

Only REPL-mode breakpoint/stepping stubs are tested. Full DAP needs
validation against an actual debug UI.

### 8.3 Cross-protocol port routing

The port abstraction was designed so one definition could be exposed
via all three protocols. Today there's no example showing a single
port serving MCP + LSP + DAP simultaneously. Build one, document it,
or simplify the design if it's not actually useful.

### 8.4 CLJS/Node end-to-end server

The core compiles clean on Node but hasn't been validated with Claude
Desktop or another real MCP client. Build `examples/cljs-node/` with
shadow-cljs, compile, run, verify.

---

## Critical architectural decisions (non-negotiable)

These are the rules that keep defport thin, composable, and long-lived.
See `CLAUDE.md` for the full text and rationale.

1. **Synchronous port handler contract.** Port handlers are
   `(fn [context] result)`. Never add async primitives to the contract.
2. **Users bring their own async.** Feature-detect async types, no
   hard deps on core.async/promesa/manifold.
3. **Protocol intersection, not union.** MCP/LSP/DAP share JSON-RPC
   dispatch, cancellation, state, content formatting. Protocol adapters
   are thin mappings over the shared core.
4. **Transport manages concurrency.** Stdio is 1-process-1-peer. HTTP
   concurrency lives in the transport. Dispatch core stays synchronous.
5. **Server/client asymmetry.** Server is universal and fully
   cross-platform. Client features (spawning external servers) don't
   ship — they belong in user code.
6. **No mechanical reader conditionals.** Use `platform/*` helpers.

## Library philosophy

**defport is a LOW-LEVEL LIBRARY like Ring or Lacinia.**

Defport provides:
- MCP/LSP/DAP protocol adapters
- Transport implementations
- `ProtocolClient` protocol contract (but no implementation)
- tap> events and datafy/nav for observability
- `Unwrappable` extension point for user async

Defport does NOT provide:
- Auth middleware
- Metrics collectors
- HTTP middleware stacks
- Lifecycle management (Component/Integrant/Mount)
- Subprocess clients (users implement `ProtocolClient` themselves)
- A specific concurrency model

**Rationale:** Applications already have auth, metrics, database pools,
async libraries, and lifecycle management. Defport integrates with YOUR
infrastructure, not the other way around. It's Ring for protocols.

---

## Test status

```
clojure -M:kaocha
304 tests, 1856 assertions, 0 failures.
```

Key test files:

```
test/defport/protocols/
  mcp_test.clj             — core MCP adapter tests + 5 unwrap tests
  mcp_batch_test.clj       — concurrent batch processing
  mcp_completions_test.clj — argument completion
  mcp_elicitation_test.clj — form + URL elicitation modes
  mcp_roots_test.clj       — client filesystem roots
  mcp_sampling_test.clj    — server-initiated LLM requests
  dap_test.cljc            — DAP protocol adapter (partial)

test/defport/integration/
  elicitation_integration_test.clj
  completions_integration_test.clj
  prompts_integration_test.clj
  resources_integration_test.clj
  roots_integration_test.clj
  sampling_integration_test.clj
  tools_integration_test.clj

test/defport/testing/
  client_test.clj    — HTTP test client integration
  server_test.clj    — test server harness
  compliance_test.clj — MCP spec compliance validation
```

---

## How to onboard a new contributor

1. Read `CLAUDE.md` — six non-negotiable design principles
2. Read `docs/ARCHITECTURE.md` — concurrency model, protocol intersection,
   design rationale
3. Run `clojure -M:kaocha` and see 304 tests pass
4. Pick a Phase 8 item from `ROADMAP.md` or find a TODO in the source
5. Before adding code: make sure your change doesn't violate any of
   the six design principles. When in doubt, ask.

---

*Last Updated: 2026-04-12 (Phase 7 complete)*
