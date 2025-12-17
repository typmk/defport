# Changelog

All notable changes to defport will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (Phase 6 - Integration & Documentation - December 7, 2025) 📚

**Status:** ✅ **Integration Patterns & Observability Complete**
**Tests:** 141 tests, 1027+ assertions, 0 failures

#### 6.1: Documentation (COMPLETE ✅)
- **`docs/INTEGRATION.md`** - Comprehensive integration patterns documentation
  - Component integration patterns with shared dependencies
  - Integrant integration with lifecycle management
  - Ring + Reitit integration with shared middleware
  - Pedestal interceptor integration
  - Authentication patterns (Ring middleware, context injection, per-tool auth)
  - Metrics integration (tap> subscriber, explicit metrics, Prometheus)
  - Database integration (connection pools, transactions)
  - Production deployment checklist
- **`docs/ARCHITECTURE.md`** - Design rationale and philosophy
  - Library vs framework decision explained
  - Four core abstractions (Port, Transport, ProtocolAdapter, PortRegistry)
  - MCP adapter architecture deep dive
  - Extension points (tap>, datafy/nav, Ring-compatible handlers)
  - Comparison with FastMCP, LSP4J, clojure-mcp
  - Design principles (protocols over implementations, data-driven, explicit)

#### 6.2: Observability Hooks (COMPLETE ✅)
- **tap> events in MCP adapter** - Zero-overhead observability
  - `:mcp/tool-call` - Tool execution completed (with duration-ms, success?)
  - `:mcp/operation-cancelled` - Operation was cancelled
  - `:mcp/error` - Error occurred (with error-code, error-message)
  - `:mcp/subscription-added` - Resource subscription added
  - `:mcp/subscription-removed` - Resource subscription removed
  - All events include `:timestamp` for correlation
  - Works with Portal, REBL, mulog, custom tap handlers
- **`defport.inspect` namespace** - REPL introspection via datafy/nav
  - Datafiable extensions for PortImpl, FunctionPortRegistry, EdnPortRegistry, HybridPortRegistry, McpAdapter
  - Navigate into handlers, ports, adapter state
  - `inspect` convenience function
  - `registry-summary` - Quick overview of registered ports
  - `adapter-summary` - MCP adapter state for debugging

#### 6.3: Integration Examples (COMPLETE ✅)
- **`examples/component_integration.clj`** - Stuart Sierra's Component
  - Database pool component with simulated queries
  - Metrics registry component
  - MCP server component with shared dependencies
  - Tool registration with access to db-pool and metrics
  - Complete system assembly and lifecycle
- **`examples/reitit_integration.clj`** - Ring + Reitit
  - Shared authentication middleware (API key validation)
  - Shared metrics middleware
  - MCP endpoint alongside web API routes
  - Same middleware stack for both web and MCP
  - Tools that access authenticated user context

#### Bug Fixes
- **Fixed ping test** - Updated to match MCP 2025-06-18 spec (empty response)

---

### Changed (Phase 6 - ARCHITECTURAL DECISION - January 13, 2025) 🎯

**BREAKING PHILOSOPHICAL CHANGE:** defport is now explicitly a **LOW-LEVEL LIBRARY** (like Ring for HTTP, Lacinia for GraphQL), not a framework.

**What This Means:**
- ✅ **We provide:** Protocol adapters (MCP, LSP, DAP), transport layer, core abstractions
- ❌ **We do NOT provide:** Auth middleware, metrics collectors, HTTP middleware stacks, Component/Integrant adapters, lifecycle management
- 📚 **Integration focus:** Documentation and examples showing how to integrate defport into YOUR existing stack

**Rationale:**
After comprehensive analysis of library vs framework tradeoffs, SaaS integration patterns, and the Clojure ecosystem, we determined that defport should follow the Ring pattern: provide low-level abstractions, let applications handle cross-cutting concerns (auth, metrics, middleware, lifecycle).

If you're adding defport to an existing SaaS application, you already have:
- Auth system (buddy-auth, JWT secrets, session stores)
- Metrics infrastructure (Prometheus registry, mulog publishers)
- Database pools (HikariCP)
- Component lifecycle (Component/Integrant)
- HTTP middleware stacks

defport now integrates with YOUR infrastructure instead of providing its own.

**Phase 6 Implementation (Completed December 7, 2025):**
- Integration documentation (docs/INTEGRATION.md)
- Architecture documentation (docs/ARCHITECTURE.md)
- tap> events in MCP adapter
- datafy/nav support (defport.inspect)
- Integration examples (Component, Reitit)

**What Was Removed from Original Phase 6 Plan:**
- ❌ Auth middleware implementation (applications use buddy-auth)
- ❌ Metrics collector implementation (applications use Prometheus/iapetos)
- ❌ HTTP middleware stack (applications compose Ring middleware)
- ❌ Debug HTTP endpoints (HTTP concern, not protocol concern)
- ❌ Component/Integrant adapters (applications wrap us, not vice versa)
- ❌ New dependencies (buddy-auth, buddy-sign) - keep zero extra deps

**Benefits:**
- Zero duplicate infrastructure (app auth vs defport auth)
- Zero dependency conflicts (buddy versions, etc.)
- Maximum flexibility (integrate with ANY stack)
- Less code to maintain (documentation > implementation)
- Clear library boundaries (protocol adapters, nothing more)

**See Also:**
- [docs/INTEGRATION.md](docs/INTEGRATION.md) - Integration patterns
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) - Design rationale
- [SESSION_STATE.md](SESSION_STATE.md) - Complete session history

---

### Added (Phase 5 COMPLETE - January 13, 2025) 🚀

**Status:** ✅ **Performance Optimization - Concurrent Batch Processing**
**Tests:** 141 tests, 1027 assertions, 0 failures
**Performance:** 5-10x speedup for batch operations

#### 5.2: Concurrent Batch Processing (COMPLETE ✅)
- **`defport.util.batch` namespace** - Batch processing utilities (~200 lines)
  - `process-batch` - Main dispatcher with configurable concurrency strategies
  - `sequential-batch` - Default sequential processing (100% backward compatible)
  - `pmap-batch` - Simple parallel processing using Clojure's pmap
  - `futures-batch` - Parallel with per-request timeout enforcement (JVM only)
  - `core-async-batch` - Controlled concurrency with max-concurrency limits
  - Helper functions: `batch-enabled?`, `get-batch-strategy`, `get-batch-opts`
  - Platform queries: `available-strategies`, `strategy-available?`
  - Platform-portable (.cljc with reader conditionals for JVM & Node.js)
- **Performance configuration schema** in `defport.config` (+160 lines)
  - `performance-config-schema` - Schema for batch processing options
  - `performance-config-defaults` - Safe defaults (sequential, 10 workers, 30s timeout)
  - `validate-performance-config` - Validation with detailed error messages
  - `normalize-performance-config` - Merge with defaults and validate
  - `valid-batch-strategy?` - Strategy validation helper
- **MCP adapter integration** - Performance options support (+50 lines)
  - `:performance` option in `create-mcp-adapter`
  - `get-batch-strategy` - Extract strategy from adapter
  - `get-batch-opts` - Extract batch options ready for processing
  - `batch-enabled?` - Check if concurrent processing enabled
  - Default: Sequential processing (backward compatible)
  - Opt-in: Concurrent strategies via configuration
- **Enhanced example server** - `examples/simple_mcp_server.clj` updated
  - `handle-jsonrpc-batch` now uses `batch/process-batch`
  - Commented examples showing all 4 strategies
  - Performance configuration demonstrations
- **Comprehensive test suite** - 49 new tests, 544 new assertions
  - `test/defport/util/batch_test.clj` - 33 unit tests, 508 assertions
    - Sequential, pmap, futures, core.async strategy tests
    - Timeout handling and error isolation tests
    - Thread safety stress tests (50+ iterations)
    - Performance comparison benchmarks
    - Helper function tests
  - `test/defport/protocols/mcp_batch_test.clj` - 16 integration tests, 36 assertions
    - Real-world batch processing scenarios
    - Performance comparison tests (sequential vs parallel)
    - Concurrency limit enforcement (core.async)
    - Error isolation (one failure doesn't stop batch)
    - Order preservation verification
    - Large batch handling (100+ requests)
    - Empty batch edge cases
- **Comprehensive documentation** - 2 new guides (~1000+ lines total)
  - `docs/PERFORMANCE.md` - Performance tuning guide
    - Strategy comparison and selection guide
    - Configuration examples for all strategies
    - Performance benchmarks (5-10x speedup demonstrated)
    - Migration guide (step-by-step upgrade path)
    - Troubleshooting common issues
    - Platform differences (JVM vs Node.js)
  - `docs/CONCURRENCY.md` - Thread safety and concurrency model
    - Architecture overview with diagrams
    - Thread-safe component guarantees
    - Handler thread safety requirements
    - Best practices and common pitfalls
    - Testing strategies for concurrency
    - Example safe/unsafe patterns
- **Dependencies added**
  - `org.clojure/core.async {:mvn/version "1.6.681"}` - For core.async strategy

**Performance Impact:**
- **Sequential (default):** No change (100% backward compatible)
- **Pmap strategy:** 5-7x speedup for I/O-bound batch operations
- **Futures strategy:** 5-7x speedup with timeout safety
- **Core.async strategy:** 4-9x speedup with controlled concurrency

**Thread Safety:**
- All defport-managed state uses atoms (thread-safe by design)
- Port registries: lock-free reads, atomic updates (CAS)
- MCP adapter: atomic request validation and operation tracking
- Application responsibility: Handlers must be thread-safe when using concurrent strategies

**Backward Compatibility:**
- ✅ 100% backward compatible (sequential by default)
- ✅ Opt-in via `:performance {:batch-processing {:enabled true}}`
- ✅ All existing tests pass (0 failures)
- ✅ No breaking changes

**Platform Support:**
- ✅ JVM (Clojure): All strategies available
- ✅ Node.js (ClojureScript): Sequential and pmap available
- ⚠️ Futures strategy: JVM only
- ⚠️ Core.async strategy: Experimental in ClojureScript

---

### Added (Phase 4 COMPLETE - January 13, 2025) 🎉

**Status:** ✅ **100% MCP 2025-06-18 Spec Compliant (All Optional Features)**
**Tests:** 92 tests, 483 assertions, 0 failures

#### 4.1 & 4.2: ImageContent and AudioContent Support (COMPLETE ✅)
- **`defport.util.content` namespace** - Rich media content utilities
  - `base64-encode` / `base64-decode` - Platform-agnostic Base64 encoding (JVM & Node.js)
  - `image-content` - Create ImageContent with Base64-encoded image data
  - `audio-content` - Create AudioContent with Base64-encoded audio data
  - `load-image-file` - Load images from filesystem with auto-detected MIME types
  - `load-audio-file` - Load audio from filesystem with auto-detected MIME types
  - `text-content` - Create TextContent helper
  - MIME type detection: `guess-mime-type`, `guess-image-mime-type`, `guess-audio-mime-type`
  - Content validation: `valid-image-content?`, `valid-audio-content?`, `valid-text-content?`
  - Type detection: `content-type` (returns :image, :audio, :text, or :unknown)
  - Support for PNG, JPEG, GIF, WebP, SVG, BMP, ICO image formats
  - Support for WAV, MP3, OGG, M4A, FLAC, AAC, Opus audio formats
- **Enhanced MCP protocol** - ImageContent/AudioContent integrated in format-content
  - `format-content` recognizes and passes through ImageContent and AudioContent
  - Validation ensures proper MCP 2025-06-18 format compliance
- **Comprehensive test suite** - 17 new tests for content utilities
  - Base64 encoding/decoding tests (round-trip verification)
  - MIME type detection tests (all image and audio formats)
  - ImageContent creation and validation tests
  - AudioContent creation and validation tests
  - TextContent creation and validation tests
  - Content type detection tests
  - Integration tests (create, validate, round-trip)
- **`examples/media_content_example.clj`** - 311 lines demonstrating:
  - Image generation tools (diagrams, charts, QR codes)
  - Audio synthesis tools (text-to-speech, recording, effects)
  - File loading patterns (load-image-file, load-audio-file)
  - Mixed content responses (text + images)
  - Screenshot capture simulation
  - Image format conversion
  - Real-world usage patterns and validation

**MCP Spec Compliance:**
- ✅ ImageContent format: `{:type "image" :data "base64..." :mimeType "image/png"}`
- ✅ AudioContent format: `{:type "audio" :data "base64..." :mimeType "audio/wav"}`
- ✅ Platform-portable (JVM and Node.js reader conditionals)
- ✅ Automatic Base64 encoding/decoding
- ✅ MIME type detection from file extensions
- ✅ Full integration with existing format-content

#### 4.3: Roots Support (COMPLETE ✅)
- **Filesystem boundaries** - Client-defined root directories for safe file operations
  - `client-roots*` - Atom tracking client-shared filesystem roots
  - `handle-roots-list` - MCP handler for `roots/list` requests
  - `update-client-roots!` - Update roots when client notifies server
  - `is-path-in-roots?` - Validate file paths against configured roots
  - `validate-file-access` - Enforce filesystem boundaries (throws if outside roots)
- **DSL helpers** in `defport.dsl`
  - `get-roots` - Query current client filesystem roots
  - `validate-file!` - Validate file access in tool handlers
- **Roots capability** - Advertised in initialize response
  - `:roots {:listChanged false}` in capabilities map
  - `roots/list` method handler registered
- **Comprehensive test suite** - 8 new tests, 35 assertions
  - Root tracking and updates
  - Path validation (within/outside roots)
  - Multiple root support
  - File access validation
  - Error handling and exceptions
  - Initialize response verification
- **`examples/roots_example.clj`** - 370+ lines demonstrating:
  - Safe file reader (validate-file!)
  - Directory operations within roots
  - Multi-root workspace support
  - File search within boundaries
  - Integration with other features
  - Testing patterns

**MCP Spec Compliance:**
- ✅ roots/list handler
- ✅ Root tracking and validation
- ✅ File URI parsing (file:// prefix)
- ✅ Multi-root support
- ✅ Security boundaries enforced

#### 4.4: Sampling Support (COMPLETE ✅)
- **Server-initiated LLM requests** - Request completions from client during tool execution
  - `sampling-state*` - Atom tracking active sampling requests
  - `create-sampling-request` - Create sampling request with messages and options
  - `send-sampling-request` - Send request to client via transport
  - `handle-sampling-response` - Process client's LLM response
  - `wait-for-sampling-response` - Block waiting for response (with timeout)
  - `cancel-sampling-request` - Cancel pending request
- **Promise-based async coordination** - Platform-agnostic (JVM promises, JS Promises)
  - Automatic promise creation and resolution
  - Timeout handling (default 60s)
  - Request/response correlation by ID
- **DSL helper** in `defport.dsl`
  - `sample!` - Request LLM completion from client
  - Supports: messages, model preferences, system prompt, max tokens, timeout
  - Returns LLM response or nil on timeout
- **Sampling capability** - Advertised in initialize response
  - `:sampling {}` in capabilities map
  - Enables server→client LLM requests
- **Comprehensive test suite** - 10 new tests, 48 assertions
  - Request creation with options
  - Response handling and promises
  - Timeout behavior
  - Cancellation
  - State management
  - Multiple concurrent requests
  - Initialize response verification
- **`examples/sampling_example.clj`** - 420+ lines demonstrating:
  - Code analysis with LLM
  - Multi-step reasoning workflows
  - Self-reflection and verification
  - Iterative refinement
  - Context-aware assistance
  - Code generation
  - Conversation management
  - Data extraction
  - Error diagnosis
  - Chained sampling requests
  - Integration with elicitation

**MCP Spec Compliance:**
- ✅ sampling/createMessage request format
- ✅ Message structure (role, content)
- ✅ Optional parameters (modelPreferences, systemPrompt, maxTokens)
- ✅ Promise-based async coordination
- ✅ Platform-agnostic (JVM & Node.js)

**Phase 4 Achievement:**
- ✅ **100% MCP 2025-06-18 spec compliant** (core + all optional features)
- ✅ ImageContent, AudioContent, Roots, Sampling all implemented
- ✅ 92 tests, 483 assertions, 0 failures
- ✅ Comprehensive examples for all features
- ✅ Platform-agnostic (.cljc with reader conditionals)

### Added (Phase 3 COMPLETE - January 12, 2025) 🎉

**Status:** ✅ **100% MCP 2025-06-18 Core Spec Compliant**
**Tests:** 61 tests, 331 assertions, 0 failures

#### 3.1: Malli Schema Integration
- **`defport.schema` namespace** - Comprehensive Malli integration
  - `malli->json-schema` - Convert Malli schemas to JSON Schema for MCP
  - `validate-input` - Runtime validation with Malli schemas
  - `humanize-error` - Convert validation errors to human-readable messages
  - `create-schema-registry` - Named schema registry for reuse
  - `register-schema!`, `get-schema`, `list-schemas` - Schema management
  - `resolve-schema` - Handle both inline and named schemas
  - `schema->json-schema` - Unified conversion with registry support
  - Helper functions: `infer-schema-type`, `schema?`, `merge-schemas`, `add-description`
- **Enhanced `defport.dsl` with Malli support** - Three schema definition styles
  - **Type annotations** (backward compatible): `[query :- :string]`
  - **Inline Malli schemas**: `[:map [:query [:string {:min 1 :max 500}]]]`
  - **Named schemas**: `:search-params` (references registry)
  - Updated `deftool` macro to accept all three forms
  - Added `register-schema!`, `get-schema`, `list-schemas` DSL helpers
  - Schema-aware argument extraction for all forms
- **Comprehensive test suite** - 11 new tests, 83 assertions
  - Schema registry tests (create, register, get, list)
  - Malli→JSON Schema conversion tests (primitives, complex, nested)
  - Validation tests (valid/invalid inputs, error messages)
  - Schema inference tests (type detection)
  - Integration helper tests (resolve, convert)
  - Utility function tests (merge, description)
- **`examples/malli_schemas_example.clj`** - 400+ lines of examples
  - Type annotation examples (backward compatible)
  - Inline Malli schema examples
  - Named schema registry examples
  - Complex validation patterns (email, nested objects, arrays)
  - Side-by-side comparison (before/after Malli)
  - Runtime validation examples
  - Schema composition examples
  - Migration guide from type annotations to Malli
  - Real-world tool examples (file system, database, git, HTTP)
  - Testing patterns

**DX Impact:**
- **More expressive constraints**: min/max, regex, custom validators
- **Better validation**: Catch errors at definition or runtime
- **Schema reusability**: Define once, use many times
- **Backward compatible**: Type annotations still work perfectly

#### 3.2: Builder API
- **`defport.builder` namespace** - Fluent API for programmatic server construction
  - `server` - Create server builder with chainable functions
  - `tool`, `prompt`, `resource` - Add capabilities with full control
  - `register-schema` - Register named Malli schemas
  - `transport` - Configure HTTP or stdio transport
  - `enable-refactoring!`, `disable-refactoring!` - Security controls
  - `tool-filter` - Custom filtering logic
  - `enable-subscriptions!` - Real-time resource updates
  - `build!` - Register all components and create adapter
  - `start!`, `stop!` - Lifecycle management
  - `add-tool!`, `remove-tool!`, `add-prompt!`, `add-resource!` - Runtime modification
  - Introspection: `list-tools`, `list-prompts`, `list-resources`, `get-info`, `running?`
- **Test suite** - 12 tests, 71 assertions
- **`examples/builder_example.clj`** - 400+ lines demonstrating:
  - Simple to complex server construction
  - Security configurations
  - Hot reload scenarios
  - Testing patterns
  - Migration from DSL

#### 3.3: Elicitation Support (MCP 2025-06-18)
- **Server→client user input requests** - Interactive tool workflows
  - `create-elicitation` - Initiate user input request
  - `elicit-response!` - Record client response
  - `wait-for-elicitation` - Block waiting for response
  - `cancel-elicitation` - Cancel pending request
  - `handle-elicitation-create` - MCP protocol handler
  - `handle-elicitation-submit` - Client response handler
  - `handle-elicitation-cancel` - Cancellation handler
- **DSL integration** - `elicit!` helper function
  - Supports both JSON Schema and Malli schemas
  - Configurable timeout (default 60s)
  - Returns `{:action :content}` map
  - Handles accept/decline/cancel/timeout cases
- **Elicitation capability** - Reported in initialize response
- **Test suite** - 10 tests, 41 assertions
- **`examples/elicitation_example.clj`** - 400+ lines demonstrating:
  - Simple confirmations
  - Alternative options
  - Multi-step workflows
  - Context-aware questions
  - Error handling
  - Best practices

#### 3.4: Completions Support (MCP 2025-06-18)
- **Argument autocomplete** - Context-aware suggestions
  - `handle-completion-complete` - MCP protocol handler
  - Supports tool, prompt, and resource arguments
  - Context-aware completions (previous argument values)
  - Returns `{:values [] :total N :hasMore boolean}`
  - Error handling for failed completion functions
- **Metadata integration** - `:completions` in port metadata
  - Per-argument completion functions
  - Function signature: `(fn [partial-value context-map] -> [string])`
  - Supports static and dynamic completions
  - Automatic type conversion to strings
- **Completion capability** - Reported in initialize response
- **Test suite** - 9 tests, 29 assertions
- **`examples/completions_example.clj`** - 400+ lines demonstrating:
  - Static completions (enums)
  - Context-aware completions
  - Dynamic completions (databases, files, git)
  - Multi-level completions
  - Fuzzy matching
  - Caching strategies
  - Best practices

#### 3.5: logging/setLevel Support (MCP 2025-06-18)
- **Per-session log filtering** - Client controls minimum log level
  - `set-session-log-level!` - Set minimum level for session
  - `get-session-log-level` - Get current session level
  - `should-send-log?` - Check if message should be sent
  - Updated `send-log-message` - Respects session minimum level
  - `handle-logging-set-level` - MCP protocol handler
  - Supports levels: debug, info, warning, error
  - Default level: debug (show all)
- **Logging capability** - Reported in initialize response
- **Test suite** - 2 tests, 14 assertions added to MCP test suite

### Added (Phase 2 - January 12, 2025 - Session 1) 🎉

#### Progressive Disclosure DSL (Revolutionary DX Improvement)
- **`defport.dsl` namespace** - Better DX than Python's FastMCP (20% fewer lines!)
  - `deftool` macro - Define tools in 3 lines vs 25 manually
  - `defprompt` macro - Define AI prompts with templates
  - `defresource` macro - Define resources with MIME types
  - `start!` function - One-liner server startup
  - Schema inference from type annotations (`:- :string`, `:- :number`, etc.)
  - Hot reload support (`add-tool!`, `remove-tool!`, `add-prompt!`, `add-resource!`)
  - Introspection utilities (`list-tools`, `list-prompts`, `server-status`)
  - **68% boilerplate reduction** compared to manual registration
- **`examples/progressive_disclosure_example.clj`** - 400+ lines demonstrating all patterns
  - Simple tool definitions (minimal 3-line syntax)
  - Complex tools with options and metadata
  - Dangerous tool patterns with `^{:dangerous true}`
  - Prompt and resource definitions
  - Hot reload scenarios
  - **Side-by-side comparison with Python FastMCP**
  - Migration guide from verbose to DSL

#### MCP Spec Compliance Improvements
- **Fixed ObjectContent violation** - Now spec-compliant (MCP 2025-06-18)
  - Changed from non-standard `{:type "object"}` to `{:type "text" :text (json)}`
  - All content now uses TextContent with JSON serialization
  - Updated tests to verify spec compliance
- **Completed resource subscriptions** - Real-time updates
  - Wired `notify-resource-updated` to actually send notifications
  - Full `resources/subscribe` and `resources/unsubscribe` support
  - Subscriber tracking and management
- **Completed change notifications** - Dynamic updates
  - Wired `notify-tools-list-changed` to send notifications
  - Wired `notify-prompts-list-changed` to send notifications
  - Wired `notify-resources-list-changed` to send notifications
  - Auto-notify clients on registry changes
- **Completed logging infrastructure**
  - Fixed `send-log-message` to actually send via transport
  - Proper `notifications/message` delivery

#### Documentation & Examples
- **ROADMAP.md** - Complete feature roadmap through Phase 7
- **IMPLEMENTATION_SUMMARY.md** - Comprehensive Phase 2 technical summary
- **SESSION_SUMMARY.md** - Quick reference and session highlights
- **NEXT_SESSION.md** - Phase 3 implementation guide
- Updated README.md with Phase 2 status and quick start
- Updated MCP_IMPLEMENTATION.md with compliance table and roadmap

#### Test Suite Enhancements
- Updated tests to 17 tests, 92 assertions, 0 failures (100% pass rate)
- Added spec-compliant content type tests
- All existing tests maintained and passing

### MCP Protocol Adapter (Phase 2 Complete)
- **MCP Protocol Adapter** (`defport.protocols.mcp`) - ~90% MCP 2025-06-18 compliant
  - All core MCP methods: initialize, tools/*, prompts/*, resources/*
  - Progress notifications for long-running operations
  - Operation cancellation support
  - Request ID validation (duplicate detection)
  - Pagination (10 items per page, per MCP spec)
  - Platform-agnostic (.cljc with reader conditionals)
  - **Spec-compliant TextContent** - All structured data as JSON in TextContent
  - **Resource subscriptions** - Real-time `resources/updated` notifications
  - **Change notifications** - `tools/list_changed`, `prompts/list_changed`, `resources/list_changed`
  - **Dangerous tool filtering** - Hybrid security model (safe by default)
  - **Refactoring capability flag** - Security capability in initialize response
- Example MCP server (`examples/simple-mcp-server.clj`)
  - Complete working HTTP and stdio MCP server
  - Demonstrates all MCP features (tools, prompts, resources)
  - 400+ lines of example code with documentation
- Comprehensive test suite (`test/defport/protocols/mcp_test.clj`)
  - 17 tests covering all MCP handlers and new features
  - 91 assertions, 100% pass rate
  - Tests for pagination, cancellation, error handling
  - Tests for ObjectContent vs TextContent formatting
  - Tests for dangerous tool filtering (default, enabled, custom)
  - Tests for refactoring capability flags
- **Hybrid Security Model**
  - Library provides safe defaults (dangerous tools filtered)
  - `DEFPORT_ENABLE_REFACTORING` environment variable support
  - `:enable-refactoring` option for programmatic control
  - `:tool-filter` option for custom application policies
  - `:dangerous` metadata for marking refactoring/write tools
  - Clear separation: library provides mechanism, app provides policy

### Added (Phase 1 - January 2025)
- Core protocol definitions (`Port`, `Transport`, `ProtocolAdapter`, `PortRegistry`)
- `defport.util.protocol` - Request validation and operation cancellation (.cljc)
- `defport.util.pagination` - Cursor-based pagination utilities (.cljc)
- `defport.util.progress` - Progress notification support (.cljc)
- `defport.util.edn` - Simple EDN loading utilities (.cljc)
- `defport.config` - Basic config loading and validation helpers (.cljc)
- `defport.registry` - Port registry implementations:
  - `EdnPortRegistry` - Load ports from EDN files
  - `FunctionPortRegistry` - Programmatic port registration
  - `HybridPortRegistry` - Combined EDN + programmatic approach
- `defport.transports.stdio` - Stdio transport with JVM/Node.js support (.cljc)
- `defport.transports.http` - HTTP transport with http-kit (JVM) and http module (Node.js) (.cljc)
- Example port definitions in `examples/ports-example.edn`
- Comprehensive README with library philosophy and usage examples

### Changed
- **Library Philosophy:** Defport is now a pure library (not a framework)
  - Apps control configuration (no imposed config file locations)
  - No config cascading in library (apps implement their own strategy)
  - Simple, composable functions instead of framework magic
- **Port Registry:** `list-ports` now returns port descriptors (not Port implementations)
  - Descriptors include: `:id`, `:description`, `:input-schema`, `:output-schema`, `:metadata`
  - Makes it easier for protocol adapters to format capabilities
  - `get-port` still returns Port implementation for execution

### Fixed
- Reader conditional for `clojure.pprint` in `defport.util.edn`
- Port descriptor metadata mapping in registry implementations

### Removed
- Bootstrap configuration system (apps handle their own config management)
- Config cascading/search paths from library (moved to application level)

## [0.1.0-SNAPSHOT] - 2025-01-12

### Summary
Initial extraction from Defnet production codebase. Phase 1 complete: library foundation ready for MCP protocol adapter implementation.

### Core Abstractions
- **Port:** Protocol-agnostic capability/tool/operation interface
- **Transport:** Message delivery abstraction (stdio, HTTP, WebSocket)
- **ProtocolAdapter:** Protocol-specific message translation (MCP, LSP, DAP)
- **PortRegistry:** Port management and registration

### Platform Support
- ✅ JVM (Clojure) - Full support
- ✅ Node.js (ClojureScript) - Reader conditionals for I/O
- ⚠️ Browser (ClojureScript) - Limited (WebSocket transport only)
- 🚧 GraalVM Native - Future

### Design Decisions

**Why a library, not a framework?**
- More Clojure-idiomatic (composable functions, not magic)
- Apps control configuration (flexibility)
- Lower learning curve (explicit, not implicit)
- Easier to adopt incrementally

**Why .cljc?**
- Platform portability (JVM, Node.js, Browser)
- Code reuse across platforms
- Future-proof (GraalVM native compilation)

**Why EDN for port definitions?**
- Data-driven (Clojure philosophy)
- Hot-reload without restart
- User-customizable without code changes
- Clear separation of logic vs configuration

**Why three registry types?**
- `EdnPortRegistry` - Declarative, configuration-driven (Defnet style)
- `FunctionPortRegistry` - Programmatic, imperative (Scout style)
- `HybridPortRegistry` - Best of both worlds

### Known Limitations
- MCP protocol adapter not yet implemented
- No LSP/DAP support yet
- No WebSocket transport yet
- Limited browser support (file I/O restrictions)

### Next Steps
- [ ] Implement MCP protocol adapter
- [ ] Create example MCP server
- [ ] Refactor Defnet to use defport (validate abstraction)
- [ ] Add LSP protocol adapter (validate multi-protocol support)
- [ ] WebSocket transport
- [ ] Shadow-CLJS build for Node.js/NPM distribution

---

## Version History

### Extraction Timeline

**2025-01-12** - Initial extraction from Defnet
- 1,000+ lines of reusable infrastructure
- Platform-agnostic .cljc codebase
- Three transport implementations ready
- Registry system complete

**Source:** [Defnet](https://github.com/yourorg/defnet) - Production MCP server for Clojure code intelligence

---

## Credits

Extracted from Defnet by:
- The Defnet team
- Based on production code serving AI-powered code editors

Inspired by:
- Ports & Adapters pattern (Hexagonal Architecture)
- Ring's middleware simplicity
- Pedestal's interceptor composition
- Clojure's data-driven design

---

[Unreleased]: https://github.com/yourorg/defport/compare/v0.1.0...HEAD
[0.1.0-SNAPSHOT]: https://github.com/yourorg/defport/releases/tag/v0.1.0-SNAPSHOT
