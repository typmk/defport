# Defport Project History

This document captures the evolution of defport from concept to production-ready library, including key design decisions, strategic pivots, and lessons learned.

> **Note (2026-04-15, v0.3.0):** This is a historical record. Two decisions below were reversed in later phases — see CHANGELOG [0.3.0] and ROADMAP decision log:
> - "CDP out of scope" → reversed; CDP shipped in Campaign 6 as `defport.cdp.*` with 901 spec entries auto-derived from upstream JSON.
> - Phase 7 subprocess-client deletion → reversed in Phase 8 as pluggable reference transports under `defport.*.client.transports.*`.
>
> Current status: Phase 8 + Campaign 6 complete, 379 kaocha tests, BSP/CDP/rosbridge shipped alongside MCP/LSP/DAP.

---

## Timeline Overview

| Date | Phase | Key Achievement |
|------|-------|-----------------|
| Q4 2024 | Phase 1 | Core infrastructure extracted from defnet |
| Jan 12, 2025 | Phase 2 | MCP implementation + Progressive Disclosure DSL |
| Jan 12, 2025 | Phase 3 | Advanced MCP features (Malli, Builder, Elicitation) |
| Jan 13, 2025 | Phase 4 | Optional MCP features (ImageContent, Roots, Sampling) |
| Jan 13, 2025 | Phase 5 | Performance optimization (concurrent batch processing) |
| Jan 13, 2025 | Strategic Research | LSP/DAP client approach decision |
| Dec 17, 2025 | Phase 6 | Integration patterns & documentation |

---

## Phase 1: Core Infrastructure (Q4 2024)

### Origin Story

Defport was extracted from **defnet**, a production MCP server for Clojure code intelligence. The extraction goal was to create reusable infrastructure for building protocol servers.

### Key Decisions

**1. Library vs Framework**
- **Decision:** Library (like Ring for HTTP)
- **Rationale:** Applications retain control over configuration, lifecycle, and cross-cutting concerns
- **Impact:** Clean separation - defport provides protocol adapters, apps handle everything else

**2. Platform Portability**
- **Decision:** .cljc files with reader conditionals
- **Rationale:** Enable JVM, Node.js, and Browser deployment from single codebase
- **Impact:** Same code runs everywhere, future-proof for GraalVM

**3. Data-Driven Configuration**
- **Decision:** EDN-based port definitions
- **Rationale:** Hot-reload without restart, user-customizable, Clojure-native
- **Impact:** Three registry types (EDN, Function, Hybrid) for different use cases

### Deliverables

- Core protocols: `Port`, `Transport`, `ProtocolAdapter`, `PortRegistry`
- Transport implementations: stdio, HTTP
- Registry implementations: EDN, Function, Hybrid
- Utility infrastructure: validation, pagination, progress

---

## Phase 2: MCP Implementation (January 12, 2025)

### Goals

1. Implement MCP 2025-06-18 specification
2. Achieve better DX than Python's FastMCP

### Key Decisions

**1. Progressive Disclosure DSL**
- **Decision:** Create `deftool`, `defprompt`, `defresource` macros
- **Rationale:** 8-line server should be possible, complexity should be opt-in
- **Impact:** 20% fewer lines than FastMCP, 68% boilerplate reduction

**2. Spec-Compliant Content Types**
- **Problem:** Early implementation used non-standard `{:type "object"}` content
- **Solution:** Changed to TextContent with JSON serialization per MCP spec
- **Impact:** 100% spec compliance for content types

**3. Hybrid Security Model**
- **Decision:** Library provides mechanism, application provides policy
- **Implementation:** `:dangerous` metadata + `DEFPORT_ENABLE_REFACTORING` env var + custom filters
- **Rationale:** Safe defaults but full control for applications

### Achievements

- 90% MCP 2025-06-18 compliance (core features)
- Better DX than FastMCP (quantified: 20% fewer lines)
- 17 tests, 92 assertions, 0 failures

---

## Phase 3: Advanced MCP Features (January 12, 2025)

### Features Implemented

**1. Malli Schema Integration**
- Three schema definition styles: type annotations, inline Malli, named schemas
- Malli→JSON Schema converter for wire format
- Schema registry for reusable definitions
- Runtime validation with detailed error messages

**2. Builder API**
- Fluent API for programmatic server construction
- Complements DSL macros for complex use cases
- Runtime modifications (add/remove tools at runtime)

**3. Elicitation Support**
- Server→client user input requests (interactive tool workflows)
- `elicit!` DSL helper with promise-based coordination
- Timeout and cancellation support

**4. Completions Support**
- Argument autocomplete with context awareness
- Per-argument completion functions
- Dynamic completions based on previous inputs

**5. Logging Level Control**
- Per-session log filtering
- Standard MCP `logging/setLevel` handler

### Achievements

- 100% MCP 2025-06-18 core spec compliant
- 61 tests, 331 assertions, 0 failures

---

## Phase 4: Optional MCP Features (January 13, 2025)

### Features Implemented

**1. ImageContent & AudioContent**
- Platform-agnostic Base64 encoding/decoding (JVM + Node.js)
- MIME type detection for images and audio
- File loading helpers

**2. Roots Support**
- Filesystem boundaries for safe file operations
- Path validation against configured roots
- Multi-root support

**3. Sampling Support**
- Server-initiated LLM requests
- Promise-based async coordination
- Timeout handling

### Achievement

**100% MCP 2025-06-18 Spec Compliant** - All core AND optional features implemented

- 92 tests, 483 assertions, 0 failures

---

## Phase 5: Performance Optimization (January 13, 2025)

### Goals

- Optimize batch request processing
- Maintain 100% backward compatibility
- Document thread safety model

### Features Implemented

**Concurrent Batch Processing** (4 strategies):
1. **Sequential** - Default, 100% backward compatible
2. **Pmap** - Simple parallel processing
3. **Futures** - Parallel with timeout enforcement (JVM only)
4. **Core.async** - Controlled concurrency with limits

### Key Decisions

**1. Opt-In Parallelism**
- **Decision:** Sequential by default, parallel opt-in
- **Rationale:** Safety first, performance when explicitly requested
- **Impact:** Zero risk to existing users

**2. Platform-Specific Strategies**
- **JVM:** All 4 strategies available
- **Node.js:** Sequential and pmap only
- **Rationale:** Futures require JVM threading model

### Achievements

- 5-10x speedup for I/O-bound batch operations
- 141 tests, 1,027 assertions, 0 failures
- Comprehensive documentation (PERFORMANCE.md, CONCURRENCY.md)

---

## Strategic Research Session (January 13, 2025)

### The Breakthrough

This session fundamentally transformed defport's strategic direction through comprehensive research on protocol integration options.

### Key Findings

**1. LSP Client/Proxy Integration**
- **Discovery:** LSP is language-agnostic; one client can connect to 100+ servers
- **Proof of Concept:** mcp-language-server (Go project) already proves feasibility
- **Impact:** 10-100x value multiplier vs Clojure-only approach

**2. DAP Client/Proxy Integration**
- **Discovery:** Same pattern as LSP - connect to existing debuggers
- **Impact:** Universal debugging across all languages

**3. CDP Out of Scope**
- **Decision:** Do NOT implement Chrome DevTools Protocol
- **Rationale:** CDP is client automation (Playwright, Puppeteer), not server capabilities
- **Impact:** Avoided scope creep, maintained library focus

**4. Linter Integration is Free**
- **Discovery:** LSP diagnostics include linting automatically
- **Impact:** $6K saved, universal linter support

### Market Positioning Transformation

**Before:**
> "defport: Build MCP servers in Clojure"

**After:**
> "defport: Universal Protocol Abstraction for Code Intelligence"
>
> Connect ANY language server (LSP) or debugger (DAP) to ANY protocol (MCP, GraphQL, gRPC).

### Cost-Benefit Analysis

| Approach | Coverage | Cost | Value |
|----------|----------|------|-------|
| Original Plan (Clojure-only) | 1 language | $48K | Moderate |
| LSP/DAP Client Approach | 100+ languages | $17-22K | Extreme |

**Savings:** $26-31K with 10-100x more value

### What Was Removed from Plans

- CDP Protocol Adapter (out of scope)
- Building LSP servers from scratch (integrate instead)
- Building debuggers from scratch (integrate instead)
- Separate linter integration (included in LSP)
- CLI framework (document patterns only)

---

## Phase 6: Integration & Documentation (December 2025)

### Architectural Decision: Low-Level Library

**Critical Decision:** defport is explicitly a **LOW-LEVEL LIBRARY** (like Ring, Lacinia), not a framework.

**What We Provide:**
- Protocol adapters (MCP, future LSP/DAP)
- Transport layer (stdio, HTTP)
- Extension hooks (tap>, datafy/nav)
- Integration documentation

**What We Do NOT Provide:**
- Auth middleware (use buddy-auth)
- Metrics collectors (use Prometheus/iapetos)
- HTTP middleware stacks (use Ring middleware)
- Component/Integrant adapters (apps wrap us)

### Deliverables

**Documentation:**
- `docs/INTEGRATION.md` - Comprehensive integration patterns
- `docs/ARCHITECTURE.md` - Design rationale and philosophy

**Observability:**
- tap> events throughout MCP adapter
- datafy/nav support for core types via `defport.inspect`

**Examples:**
- Component integration example
- Ring + Reitit integration example

---

## Key Design Principles

### 1. Integration Over Implementation

**Lesson:** Leverage existing mature implementations instead of rebuilding

**Examples:**
- Don't build LSP servers → Connect to 100+ existing servers
- Don't build debuggers → Connect to existing debug adapters
- Don't build linters → Use diagnostics from LSP servers
- Don't build auth → Use existing buddy-auth

### 2. Protocol Abstraction is the Killer Feature

**Insight:** Same port works via ANY protocol

```clojure
;; Define once
(deftool goto-definition ...)

;; Works via MCP (AI tools), LSP (IDEs), GraphQL (web), gRPC (microservices)
```

### 3. Library Philosophy Prevents Scope Creep

**Principle:** defport is a library, not a framework

**Decisions Made:**
- Don't build CLI framework → Document patterns
- Don't build CDP client → Reference existing tools
- Don't build auth middleware → Integrate with buddy-auth

**Benefit:** Clear boundaries, low maintenance, developer autonomy

### 4. Progressive Disclosure

**Principle:** Simple things should be simple, complex things should be possible

```clojure
;; Simple (8 lines)
(deftool greet [name :- :string]
  {:greeting (str "Hello, " name)})
(start!)

;; Complex (when needed)
(-> (server "my-server" "1.0.0")
    (tool :greet handler {:schema [...] :dangerous true})
    (enable-refactoring!)
    (build!))
```

---

## Metrics Evolution

| Phase | Tests | Assertions | Pass Rate |
|-------|-------|------------|-----------|
| Phase 2 | 17 | 92 | 100% |
| Phase 3 | 61 | 331 | 100% |
| Phase 4 | 92 | 483 | 100% |
| Phase 5 | 141 | 1,027 | 100% |
| Phase 6 | 141 | 1,027+ | 100% |

---

## Lessons Learned

### 1. Spec Compliance Matters

Early ObjectContent implementation was non-standard. Fixing it early prevented technical debt.

### 2. DX Can Be Quantified

"Better than FastMCP" became measurable: 20% fewer lines for equivalent functionality.

### 3. Research Before Building

The strategic research session saved $26-31K by identifying that LSP client approach is dramatically more valuable than building custom language servers.

### 4. Library > Framework for Infrastructure

Applications already have auth, metrics, middleware. Don't duplicate - integrate.

### 5. Backward Compatibility is Non-Negotiable

Every phase maintained 100% backward compatibility. Sequential-by-default batch processing exemplifies this.

---

## Future Direction (Phase 7+)

### Phase 7: Multi-Protocol Support

**7.1 LSP Client/Proxy Integration**
- Connect to ANY language server
- Route requests by file type
- Universal code intelligence

**7.2 DAP Client/Proxy Integration**
- Connect to ANY debug adapter
- AI-assisted debugging
- Universal breakpoint management

**7.3-7.4 Optional Protocol Adapters**
- GraphQL
- gRPC

### Vision

defport becomes the **universal bridge** between AI agents (via MCP) and language tooling (via LSP/DAP).

---

## File Archaeology

These files document defport's evolution and are preserved here:

- `IMPLEMENTATION_SUMMARY.md` - Phase 2 detailed implementation
- `SESSION_SUMMARY.md` - Phase 2 session summary
- `MCP_IMPLEMENTATION.md` - MCP adapter documentation
- `PHASE_4_PLAN.md` - Phase 4 implementation plan
- `SESSION_STATE_TEST.md` - Testing infrastructure development
- `SESSION_INSIGHTS_2025-01-13.md` - Strategic research findings
- `PROTOCOL_IMPLEMENTATION_PLAN.md` - CDP/prepl/nREPL analysis (decided out of scope)
- `RESEARCH_*.md` - Various research documents

---

*This history compiled December 2025 from session documents and CHANGELOG entries.*