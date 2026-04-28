# Defport Roadmap

**Version:** 0.3.0 (2026-04-15)
**Status:** Phase 8 + Campaign 6 complete | 379 kaocha tests, 2103 assertions, 0 failures
**Spec coverage:** MCP · LSP · DAP · BSP · CDP · rosbridge — all 100%, drift-checked in CI

---

## Completed phases

### Phase 1 — Core infrastructure ✅
Core protocols (Port, Transport, ProtocolAdapter, PortRegistry).
Registry implementations (EDN, Function, Hybrid). Transports
(stdio, HTTP). Platform portability (`.cljc` for JVM + Node).

### Phase 2 — MCP implementation ✅
MCP 2025-11-25 protocol adapter. Progressive-disclosure DSL
(`deftool`, `defprompt`, `defresource`). Resource subscriptions,
change notifications, server-initiated sampling/elicitation/roots.

### Phase 3 — Advanced MCP features ✅
Elicitation (form + URL modes). Completions (argument autocomplete).
Per-session log-level control. Schema builder API.

### Phase 4 — Performance ✅
Concurrent batch processing (sequential, pmap, futures, core-async
strategies). 100% backward compatible — sequential is the default.

### Phase 5 — Integration & documentation ✅
`docs/INTEGRATION.md`, `docs/ARCHITECTURE.md`. `defport.inspect`
(datafy/nav). `tap>` events throughout the MCP adapter.

### Phase 6 — State refactor ✅
Eliminated 8 global `defonce` atoms. Per-adapter instance state
via a single atom holding an immutable map.

### Phase 7 — Cross-platform restructure ✅
Reader conditionals 225 → 74 (−67%). 1,600 lines of speculative
client-mode code removed (later restored as pluggable reference
transports in Phase 8, with the substrate to back them). Synchronous
handler contract codified (Ring-style). Platform abstraction layer
(`defport.util.platform`) grown with helpers for error-message,
try-any, process-id, UTF-8 byte length.

### Phase 8 — Substrate campaign ✅ (2026-04-14 → 2026-04-15)
**The library became a substrate, not just a server adapter.**

- Plain-data spec registries for every protocol: `defport.{mcp,lsp,dap}.spec`
- Client cores with pluggable `ClientTransport`: `defport.{mcp,lsp,dap}.client`
- Reference subprocess transports (JVM + Node): every protocol
- `sugar/run!` now works: one line starts a protocol-correct
  server (JSON-lines for MCP per the 2025-11-25 spec; Content-
  Length for LSP + DAP), auto-registers lifecycle handlers,
  threads the registry through dispatch
- Auto-derived LSP capabilities from registered ports
- Unified client `connect!` opts across protocols
- Cross-platform `await` (JVM blocks, CLJS returns `js/Promise`)
- Spec drift check runs in kaocha against upstream schemas
- 11 real interop bugs caught by writing runnable examples and
  integration tests — zero caught by unit tests

Tagged `v0.2.0`.

### Campaign 6 — Protocol family expansion ✅ (2026-04-15)
**Two new first-class protocols, one new transport, one bridge
client, two industrial example files.**

- **BSP 2.2** — Build Server Protocol. 27 methods extracted from
  upstream Smithy. `defport.bsp.spec` + client + subprocess
  transport. Full parity with LSP/DAP.
- **WebSocket transport** — JVM `java.net.http.WebSocket` (JDK 11+,
  zero new deps) + Node native WebSocket. Shared between CDP and
  rosbridge. Generic `WebsocketClientTransport` protocol, thin
  per-client wrappers for type discrimination.
- **CDP 1.3** — Chrome DevTools Protocol. 664 commands + 237 events
  across 56 domains, data-driven at load time from upstream
  `browser_protocol.json` + `js_protocol.json` — zero drift risk.
  Client core + 20 typed helpers for the common commands. **Real
  headless Chromium integration test**: `Runtime.evaluate("1+2+3")`
  returns 6 over a real WebSocket against Chromium 142.
- **rosbridge v2.0 client** — Clojure ↔ ROS 2 without needing
  rclcpp/rclpy/DDS. `defport.ros2.spec` (20 ops), client core,
  typed helpers (advertise/publish/subscribe/call-service/
  send-action-goal). Integration test against a Python fake
  rosbridge server asserts subscribe+echo, call-service correlation
  by id, send-action-goal with feedback + result.
- **`examples/industrial_mcp.clj`** — 6 deftools over a mock
  SCADA backend. The canonical pattern for wrapping OPC UA /
  Modbus / DNP3 / IEC 61850 backends as MCP tools for AI
  assistants. Real JVM library (Eclipse Milo, j2mod, OpenDNP3)
  plugs in behind the backend atom.
- **`examples/robotics_mcp.clj`** — MCP server bridging to a live
  ROS 2 robot via `defport.ros2.client`. Four tools (list-topics,
  call-service, publish-twist, send-nav-goal) demonstrate the
  AI-to-robot pattern end-to-end.

Tagged `v0.3.0`.

---

## Current metrics (2026-04-15)

| Metric | Value |
|---|---|
| Tests (kaocha) | **379** (was 304 at start of Phase 8) |
| Assertions | **2,103** |
| Pass rate | 100% |
| CLJS smoke (via defnet) | 194 tests / 584 assertions, 0 failures |
| Real-external integration tests | 8 (MCP × server-everything, LSP × rust-analyzer, DAP × debugpy, CDP × Chromium 142, ROS 2 × fake-rosbridge, MCP/LSP/DAP servers × Python stdlib clients) |
| Protocols at 100% spec coverage | MCP, LSP, DAP, BSP, CDP, rosbridge |
| Reader conditionals | 74 (post-Phase-7 baseline), 0 added in Phase 8+6 |
| Real interop bugs caught by validation work | 11 |

---

## Directions under consideration

### Direction A — Real-world validation beyond defnet

Everything in v0.3.0 has been validated against either a real
external counterpart or a different-language stdlib client. Defnet
remains the canonical consumer but the substrate is now
independently useful. Opportunities:

1. **Ignition gateway module** — Inductive Automation's Ignition
   runs on the JVM and uses Eclipse Milo (the canonical open-source
   Java OPC UA stack) internally. A Clojure-compiled JAR could drop
   into Ignition's module directory and expose plant-floor tags as
   MCP tools — turning Ignition into an AI-addressable endpoint
   with ~200 LOC of Clojure. Nobody has done this publicly. Scope:
   separate library (`defport-ignition`), not a defport feature.
2. **BSP against real servers** — integration tests currently pass
   against defport's own stubs. CI should install either Bloop or
   Mill and run the real client against a throwaway project.
3. **rosbridge against real ROS 2** — integration test uses a
   Python fake. CI could install `ros-humble-rosbridge-server` and
   exercise a real topic/service/action round-trip.

### Direction B — Fill the CDP ergonomic gap

CDP's spec covers all 664 commands and 237 events automatically
(data-driven from upstream JSON), but only ~20 have typed helpers.
The other 644 are reachable via `(cdp/request! client :Domain/command params)`
but that's less ergonomic than `(cdp/page-navigate client url)`.
Filling this out is mechanical — one helper per command, driven
by the spec at macroexpansion time. Possible work: a `defcdp`
macro that auto-generates helpers for all commands in a named
domain, so consumers write `(defcdp :Page)` and get
`(cdp.page/navigate ...)` / `(cdp.page/reload ...)` / etc. for free.

### Direction C — Industrial / robotics ecosystem work

The Clojure ecosystem is a virgin territory for industrial and
robotics protocols:

- **Three abandoned ROS 1 Clojure bindings** (asimov, clojure-ros,
  rosclj). Zero ROS 2 / DDS.
- **Zero native Clojure Modbus / OPC UA / DNP3 / IEC 61850 libraries.**
- **JVM interop is the pragmatic path**: Eclipse Milo (OPC UA),
  j2mod (Modbus TCP/RTU), OpenDNP3 (via JNI), Eclipse Cyclone DDS
  Java (ROS 2). Each of these is tractable as a thin Clojure
  wrapper.

Defport's contribution to this space is not protocol adapters —
the wire formats are binary, often real-time, often hardware-routed,
and don't fit defport's text-based JSON-RPC substrate. Defport's
contribution is **the `deftool`-over-JVM-interop pattern**: wrap
any of these Java libraries in a plain Clojure function, expose
as an MCP tool, let an AI assistant query the plant floor. The
`industrial_mcp.clj` and `robotics_mcp.clj` examples demonstrate
the pattern; production work would build:

1. **`defport-opcua`** (separate lib) — thin Clojure wrapper around
   Eclipse Milo client, 50–100 LOC. Usable standalone or as an
   MCP tool backend.
2. **`defport-modbus`** — thin wrapper around j2mod, similar shape.
3. **`defport-ros2`** (evolution of `defport.ros2`) — eventually
   add direct DDS support via Cyclone DDS Java (~weeks of work,
   probably not worth it while rosbridge is in scope).

These are separate libraries, not defport phases — defport stays
the protocol substrate, these become the industrial-integration
layer that sits on top of it.

### Direction D — Node story deepening

The core MCP/LSP/DAP/BSP/CDP/rosbridge paths all compile clean on
Node and have smoke tests via `defnet/defport_smoke_test.cljs`, but
only the MCP/LSP/DAP round-trips have been exercised end-to-end on
Node. CDP and rosbridge on Node are structurally the same but
haven't been validated. Follow-up:

- CDP client vs real headless Chromium from a Node process
- rosbridge client vs the same fake server from Node
- defnet eventually adopts defport's sugar paths as its primary MCP
  surface (currently defnet uses the upstream `@modelcontextprotocol/sdk`
  directly — understandable historical reason, no longer necessary)

### Direction E — Release & distribution

**Prerequisites:** all of v0.3.0 is ready to publish except the
artifact plumbing.

1. **Publish to Clojars** as `typmk/defport {:mvn/version "0.3.0"}`
2. **Codox API reference** site from the (extensive) docstrings
3. **Tutorial series**: one short doc per protocol, each showing
   the 15-line server + client pattern

---

## Success metrics (updated for 0.3.0 → 1.0.0)

| Metric | 0.3.0 | Target 1.0.0 |
|---|---:|---:|
| Protocols with 100% spec coverage | 6 (MCP, LSP, DAP, BSP, CDP, rosbridge) | 6+ (maintain) |
| Real-external integration tests | 8 | 10+ |
| Production users | 1 (defnet) | 3+ |
| GitHub stars | 0 | 50+ |
| Published to Clojars | no | yes |
| CDP typed helpers | ~20 of 664 | domain-complete for Page, Runtime, DOM, Network, Target |

---

## Out of scope (explicitly not planned)

**Updated 2026-04-15.** A few items that used to be out-of-scope
have been moved into the substrate (subprocess client modes, CDP)
because the validation-driven work showed they belong here. Items
still out-of-scope:

- **Auth middleware** — applications use buddy-auth or their own
- **Metrics collectors** — applications use Prometheus/iapetos via `tap>`
- **CLI framework** — document patterns, don't ship one
- **Lifecycle management** — applications use Component/Integrant/Mount
- **core.async as a required dependency** — users bring their own
  async primitive; defport stays synchronous
- **Session/tenancy management in the HTTP transport** — applications
  layer this via middleware
- **Direct DDS / Cyclone DDS Java / rclcpp FFI** — wrong scope. ROS 2
  integration goes through rosbridge (already shipped) for the
  common case; a hypothetical native DDS binding belongs in a
  separate library (`defport-ros2-native` or similar).
- **Native SCADA protocols (Modbus, OPC UA, DNP3, IEC 61850)** —
  wrong substrate shape. These are binary, often real-time, and
  live behind JVM-interop libraries (Eclipse Milo, j2mod, OpenDNP3).
  Defport's contribution is the MCP-wraps-backend pattern, not a
  native protocol implementation. See `examples/industrial_mcp.clj`
  and `project_ignition_mcp_opportunity` memory note.
- **Ignition module packaging** — interesting but belongs in its own
  repo (`defport-ignition`), not defport itself.

---

## Decision log

| Date | Decision | Rationale |
|---|---|---|
| Q4 2024 | Library vs framework | Applications retain control |
| Jan 2025 | LSP client approach | 10–100x value vs building servers |
| ~~Jan 2025~~ | ~~CDP out of scope~~ | **Reversed 2026-04-15**: CDP is the closest substrate fit in the non-LSP family. Shipped as `defport.cdp` in Campaign 6 with real Chromium integration. |
| Jan 2025 | No auth/metrics | Applications have these already |
| 2026-04-12 | State refactor | 8 globals → per-adapter instance state |
| 2026-04-12 | ~~Subprocess clients removed~~ | **Revisited**: Phase 8 restored them as *pluggable reference transports* behind `ClientTransport`. The original removal was right for that scope; bringing them back with the substrate pattern honors CLAUDE.md principle 5's pluggable-primitives form. |
| 2026-04-12 | Synchronous handler contract | Ring-style. Never add async primitives to the contract. |
| 2026-04-12 | Reader conditional concentration | Platform-specific code in `defport.util.platform`. |
| 2026-04-12 | Unified use is emergent | Three independent adapters, one registry, composition in consumer code. |
| 2026-04-12 | Capability layer in consumer | Domain-specific shape translation lives in defnet, not defport. |
| 2026-04-12 | DAP client + proxy in defnet | Consumer-side spawning and source-to-graph attribution lives in defnet. |
| 2026-04-15 | MCP stdio = JSON-lines | Campaign 5o caught defport using Content-Length for MCP stdio by spawning `@modelcontextprotocol/server-everything`. MCP 2025-11-25 specifies newline-delimited JSON; LSP/DAP still use Content-Length. `sugar/run!` auto-selects per protocol. |
| 2026-04-15 | Spec registries are plain data | No schema lib (malli, spec). Predicate fns in validate slots for consumers to plug their own. Keeps defport neutral about which schema library a consumer prefers. |
| 2026-04-15 | BSP adopted | Direct substrate fit (JSON-RPC over stdio, same as LSP/DAP), small surface, upstream Smithy spec is the source of truth. |
| 2026-04-15 | CDP adopted | WebSocket + JSON-RPC + domain routing = defport's shape. Spec registry generated at load time from upstream JSON — zero drift, zero maintenance. |
| 2026-04-15 | WebSocket transport shipped | `java.net.http.WebSocket` on JVM (JDK 11+, zero new deps) + native `WebSocket` on Node. Shared between CDP and rosbridge. |
| 2026-04-15 | rosbridge client shipped as ROS 2 bridge | `defport.ros2.client` speaks rosbridge v2.0, which is defport-shaped (JSON-over-WebSocket with op routing). No DDS, no FFI, no rclcpp. Closes the Clojure ↔ ROS 2 gap for the 80% case. |
| 2026-04-15 | Industrial / SCADA protocols out of scope as native adapters | Wrong substrate shape (binary, real-time, hardware-routed). The contribution to that space is the MCP-wraps-backend pattern documented in `examples/industrial_mcp.clj`. |

See [`docs/PROJECT_HISTORY.md`](docs/PROJECT_HISTORY.md) for the
complete evolution, and [`CLAUDE.md`](CLAUDE.md) for the six
non-negotiable design principles.
