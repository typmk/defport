# Session state — v0.3.0 tagged (2026-04-15)

**Tag:** `v0.3.0`
**Tests:** 379 kaocha / 2,103 assertions / 0 failures
**CLJS smoke:** 194 tests / 584 assertions / 0 failures (via defnet)
**Spec coverage:** 100% across MCP / LSP / DAP / BSP / CDP / rosbridge
**Real external integration tests:** 8 passing, zero skipped on CI-installable tools

---

## What shipped in 0.3.0

### New protocols (first-class substrate entries)

- **BSP 2.2** — `defport.bsp.{spec,client}` + subprocess transport. 27
  methods from the upstream Smithy spec. Full parity with LSP/DAP.
- **CDP 1.3** — `defport.cdp.{spec,client}` + WebSocket transport.
  901 entries (664 commands + 237 events × 56 domains) auto-derived
  from upstream `browser_protocol.json` + `js_protocol.json` at load
  time — zero drift risk. Validated against real Chromium 142 via
  `java.net.http.WebSocket`.
- **rosbridge v2.0** — `defport.ros2.{spec,client}` + WebSocket
  transport. 20 ops. Clojure ↔ ROS 2 without DDS or FFI.

### New transport

- **`defport.transports.websocket-client`** — generic JSON-over-WebSocket
  `ClientTransport` shared between CDP and rosbridge. JVM uses
  `java.net.http.WebSocket` (JDK 11+, zero new deps); Node uses the
  global `WebSocket` or the `ws` npm package.

### New examples

- **`examples/industrial_mcp.clj`** — 6 deftools over a mock SCADA
  backend. Canonical pattern for wrapping OPC UA / Modbus / DNP3 /
  IEC 61850 behind MCP for AI assistants.
- **`examples/robotics_mcp.clj`** — MCP server bridging to a live ROS 2
  robot via `defport.ros2.client`.

### Real external integration tests

| Test | Peer | Direction |
|---|---|---|
| `test-mcp-real-server-everything` | `@modelcontextprotocol/server-everything` | defport client → vendor server |
| `test-lsp-real-rust-analyzer` | rust-analyzer 1.94.1 | defport client → vendor server |
| `test-dap-real-debugpy` | debugpy 1.8.20 | defport client → vendor server |
| `test-cdp-real-chromium` | Chromium 142 headless | defport client → vendor server |
| `test-ros2-fake-rosbridge-round-trip` | Python `websockets` fake | defport client → stdlib peer |
| `test-python-mcp-client-vs-defport-server` | Python stdlib | external client → defport server |
| `test-python-lsp-client-vs-defport-server` | Python stdlib | external client → defport server |
| `test-python-dap-client-vs-defport-server` | Python stdlib | external client → defport server |

---

## What changed from 0.2.0

- Tests 374 → 379 (+5)
- Assertions 2,086 → 2,103 (+17)
- New namespaces: `defport.bsp.*`, `defport.cdp.*`, `defport.ros2.*`, `defport.transports.websocket-client`
- New resource files: `resources/bsp.smithy`, `resources/cdp-browser-protocol.json`, `resources/cdp-js-protocol.json`
- New example files: `examples/industrial_mcp.clj`, `examples/robotics_mcp.clj`
- ROADMAP rewritten with Campaign 6 and new direction entries
- README rewritten with BSP / CDP / rosbridge quick-starts
- CHANGELOG got a [0.3.0] entry
- Two decisions reversed from earlier phases:
  1. **CDP** — was "out of scope" in the 0.1 roadmap, now shipped
     because it's the closest substrate fit after LSP/DAP/MCP.
  2. **Subprocess client modes** — deleted in Phase 7, restored in
     Phase 8 as pluggable reference transports. The original
     deletion was right for that scope; the rebuild honors CLAUDE.md
     principle 5's pluggable-primitives form.

---

## Open directions (see ROADMAP.md for detail)

1. **Ignition gateway module** — Inductive Automation's Ignition runs
   on the JVM and uses Eclipse Milo internally. A Clojure-compiled
   JAR dropped into Ignition's module directory could expose plant-
   floor tags as MCP tools. Not a defport feature — belongs in its
   own repo (`defport-ignition`). Flagged in memory as a
   commercially interesting lead.
2. **BSP real-server integration test** — CI would install Bloop or
   Mill and exercise the client against a throwaway project.
3. **rosbridge real-server integration test** — CI would install
   `ros-humble-rosbridge-server`.
4. **CDP ergonomic gap** — only ~20 of 664 commands have typed
   helpers. Consider a `defcdp` macro that auto-generates helpers
   per domain from the spec.
5. **Publish to Clojars** as `typmk/defport {:mvn/version "0.3.0"}`.
6. **Native DDS / rclcpp / OPC UA / Modbus clients** — still out of
   scope as defport features. The MCP-wraps-JVM-library pattern is
   the contribution to that space.

---

## Phase 7 history (preserved for context)

Phase 7 shipped 2026-04-12 at 304 tests / 1,856 assertions. It
scoped defport honestly to server-adapter role, cut ~1,600 lines
of speculative client-mode code, concentrated reader conditionals
in `defport.util.platform` (225 → 74, −67%), and fixed a
long-standing `register-port!` shadowing bug. Phase 8 and Campaign
6 built on top of that cleaned-up substrate to add the client-role
story back as a pluggable reference transport, plus BSP/CDP/
rosbridge/industrial examples. See the ROADMAP decision log for
the full arc.
