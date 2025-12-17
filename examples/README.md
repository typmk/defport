# Defport Examples

## Quick Start

```bash
# JVM
clj -M:examples -m server --http 8080
clj -M:examples -m server --stdio

# Node.js
npx shadow-cljs compile server
node target/server.js --http 8080
```

## Files

| File | Purpose |
|------|---------|
| `server.cljc` | Full MCP server |
| `quick_start.cljc` | Minimal hello world |
| `api_comparison.cljc` | DSL vs Builder APIs |
| `features.cljc` | All MCP features |
| `integrations.cljc` | Framework patterns |
| `ports-example.edn` | Data-driven ports |

## MCP Inspector

```bash
npx @modelcontextprotocol/inspector clj -M:examples -m server --stdio
```
