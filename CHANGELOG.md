# Changelog

All notable changes to defport will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added (Phase 2 - January 2025)
- **MCP Protocol Adapter** (`defport.protocols.mcp`) - Full MCP 2025-06-18 support
  - All core MCP methods: initialize, tools/*, prompts/*, resources/*
  - Progress notifications for long-running operations
  - Operation cancellation support
  - Request ID validation (duplicate detection)
  - Pagination (10 items per page, per MCP spec)
  - Platform-agnostic (.cljc with reader conditionals)
- Example MCP server (`examples/simple-mcp-server.clj`)
  - Complete working HTTP and stdio MCP server
  - Demonstrates all MCP features (tools, prompts, resources)
  - 400+ lines of example code with documentation
- Comprehensive test suite (`test/defport/protocols/mcp_test.clj`)
  - 14 tests covering all MCP handlers
  - 68 assertions, 100% pass rate
  - Tests for pagination, cancellation, error handling

### Added (Phase 1 - January 2025)
- Core protocol definitions (`Port`, `Transport`, `ProtocolAdapter`, `PortRegistry`)
- `defport.util.protocol` - Request validation and operation cancellation (.cljc)
- `defport.util.pagination` - Cursor-based pagination utilities (.cljc)
- `defport.util.progress` - Progress notification support (.cljc)
- `defport.util.edn` - Simple EDN loading utilities (.cljc)
- `defport.config` - Basic config loading and validation helpers (.cljc)
- `defport.registry.core` - Port registry implementations:
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
