# Tools Server - MCP Test Server

A comprehensive MCP test server demonstrating all tool-related features of the Model Context Protocol (MCP) 2025-06-18 specification.

## Overview

This server provides 16 tools that cover various MCP features:

- **Basic operations**: echo, add, multiply
- **Text manipulation**: reverse-string, to-uppercase, to-lowercase
- **Data operations**: calculate-stats, json-parser, batch-process
- **System tools**: get-time, generate-uuid, list-files
- **Search**: search-code
- **Long-running operations**: long-running (with progress), slow-operation
- **Error handling**: error-tool

## Features Demonstrated

### ✅ Basic Tool Execution
- Simple input/output tools (echo, add, multiply)
- Type validation (strings, numbers, arrays, objects)
- Content formatting with TextContent + JSON

### ✅ Progress Notifications
- `long-running` tool reports progress during execution
- Progress tokens and progress callbacks
- Step-by-step progress updates

### ✅ Cancellation Support
- `long-running` tool checks for cancellation
- Proper error code (-32800) on cancellation
- Clean operation cleanup

### ✅ Error Handling
- `error-tool` always returns error for testing
- `json-parser` handles invalid JSON gracefully
- Proper JSON-RPC error codes

### ✅ Structured Data
- All tools return TextContent with JSON serialization
- Complex nested objects and arrays
- Proper content type handling

### ✅ Array/Batch Operations
- `batch-process` handles array inputs
- `calculate-stats` processes number arrays
- Demonstrates batch data handling

## Installation

No installation needed! This server is part of the defport repository.

## Usage

### HTTP Transport

Start the server on a specific port:

```bash
cd c:\Users\Apollo\CascadeProjects\defport
clj -M:examples -m tools-server --http 8080
```

Test with curl:

```bash
# Initialize
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-06-18",
      "capabilities": {},
      "clientInfo": {"name": "test", "version": "1.0"}
    }
  }'

# List tools
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {}
  }'

# Call echo tool
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "echo",
      "arguments": {"message": "Hello, MCP!"}
    }
  }'
```

### Stdio Transport

Start the server on stdio:

```bash
cd c:\Users\Apollo\CascadeProjects\defport
clj -M:examples -m tools-server --stdio
```

Then send JSON-RPC messages via stdin. Each message should be on a single line.

### MCP Inspector

To use with MCP Inspector, add this to your `mcp_servers.json`:

```json
{
  "mcpServers": {
    "defport-tools-jvm": {
      "command": "clj",
      "args": ["-M:examples", "-m", "tools-server", "--stdio"],
      "cwd": "c:\\Users\\Apollo\\CascadeProjects\\defport"
    }
  }
}
```

Then start MCP Inspector:

```bash
npx @modelcontextprotocol/inspector
```

## Available Tools

### 1. echo
Echo back the input message.

**Input:**
```json
{"message": "Hello, world!"}
```

**Output:**
```json
Hello, world!
```

### 2. add
Add two numbers together.

**Input:**
```json
{"a": 5, "b": 3}
```

**Output:**
```json
{"result": 8, "operation": "addition", "operands": [5, 3]}
```

### 3. multiply
Multiply two numbers.

**Input:**
```json
{"a": 4, "b": 7}
```

**Output:**
```json
{"result": 28, "operation": "multiplication", "operands": [4, 7]}
```

### 4. search-code
Search for code matching a query (simulated results).

**Input:**
```json
{"query": "defprotocol"}
```

**Output:**
```json
{
  "query": "defprotocol",
  "resultCount": 3,
  "results": [
    {"file": "src/defport/core.cljc", "line": 42, "snippet": "(defprotocol Port ...)"},
    ...
  ]
}
```

### 5. get-time
Get current timestamp.

**Input:**
```json
{}
```

**Output:**
```json
{
  "timestamp": 1705161234567,
  "formatted": "Sat Jan 13 12:34:56 PST 2025"
}
```

### 6. generate-uuid
Generate a random UUID.

**Input:**
```json
{}
```

**Output:**
```json
"550e8400-e29b-41d4-a716-446655440000"
```

### 7. list-files
List files in a directory (simulated).

**Input:**
```json
{"directory": "/home/user/project"}
```

**Output:**
```json
{
  "directory": "/home/user/project",
  "fileCount": 5,
  "files": [
    {"name": "README.md", "size": 1024, "type": "file"},
    ...
  ]
}
```

### 8. calculate-stats
Calculate statistics for a list of numbers.

**Input:**
```json
{"numbers": [1, 2, 3, 4, 5, 10]}
```

**Output:**
```json
{
  "count": 6,
  "sum": 25,
  "mean": 4.166666666666667,
  "min": 1,
  "max": 10,
  "numbers": [1, 2, 3, 4, 5, 10]
}
```

### 9. reverse-string
Reverse a string.

**Input:**
```json
{"text": "Hello"}
```

**Output:**
```json
"olleH"
```

### 10. to-uppercase
Convert text to uppercase.

**Input:**
```json
{"text": "hello world"}
```

**Output:**
```json
"HELLO WORLD"
```

### 11. to-lowercase
Convert text to lowercase.

**Input:**
```json
{"text": "HELLO WORLD"}
```

**Output:**
```json
"hello world"
```

### 12. long-running
Simulate a long-running operation with progress notifications.

**Input:**
```json
{"durationMs": 10000}
```

**Output (after 10 seconds):**
```json
{"completed": true, "duration": 10000, "steps": 10}
```

**Progress notifications sent during execution:**
```json
{"progress": 1, "total": 10}
{"progress": 2, "total": 10}
...
{"progress": 10, "total": 10}
```

### 13. slow-operation
A slow operation (5 seconds) useful for timeout testing.

**Input:**
```json
{}
```

**Output (after 5 seconds):**
```json
{"completed": true, "duration": 5000}
```

### 14. error-tool
Always returns an error (for error handling tests).

**Input:**
```json
{}
```

**Output (error):**
```json
{
  "error": {
    "code": -32000,
    "message": "This tool always fails",
    "data": {
      "reason": "This is a test error tool",
      "timestamp": 1705161234567
    }
  }
}
```

### 15. json-parser
Parse a JSON string and return structured data.

**Input:**
```json
{"jsonString": "{\"name\": \"Alice\", \"age\": 30}"}
```

**Output:**
```json
{
  "success": true,
  "parsed": {"name": "Alice", "age": 30}
}
```

**Error case:**
```json
{"jsonString": "invalid json"}
```

**Output (error):**
```json
{
  "error": {
    "code": -32602,
    "message": "Invalid JSON",
    "data": {"error": "..."}
  }
}
```

### 16. batch-process
Process multiple items in batch.

**Input:**
```json
{
  "items": ["hello", "world", "test"],
  "operation": "uppercase"
}
```

**Output:**
```json
{
  "operation": "uppercase",
  "inputCount": 3,
  "outputCount": 3,
  "results": ["HELLO", "WORLD", "TEST"]
}
```

**Operations:** "uppercase", "lowercase", "reverse"

## Testing Scenarios

See [test_scenarios.md](test_scenarios.md) for comprehensive manual testing scenarios.

## Integration Testing

This server is designed to be used with defport's integration test suite:

```clojure
(require '[defport.testing.server :as server]
         '[defport.testing.client :as client])

(server/with-mcp-test-server [srv {:transport :http}]
  (client/with-test-client [c :http {:url (server/get-server-url srv)}]
    ;; Run tests...
    ))
```

## Platform Support

- ✅ **JVM (Clojure)**: Full support
- ⏳ **Node.js (ClojureScript)**: Coming soon

## MCP Spec Compliance

This server is fully compliant with MCP 2025-06-18 specification:

- ✅ JSON-RPC 2.0 envelope
- ✅ camelCase field naming
- ✅ TextContent with JSON serialization (no ObjectContent)
- ✅ Progress notifications
- ✅ Cancellation support
- ✅ Proper error codes
- ✅ Request ID validation

## Troubleshooting

### Server won't start

**Issue:** Port already in use

**Solution:** Use a different port:
```bash
clj -M:examples -m tools-server --http 9999
```

### Connection refused

**Issue:** Firewall blocking connections

**Solution:** Allow Java/Clojure through firewall or use stdio transport

### Progress notifications not working

**Issue:** Client doesn't support progress

**Solution:** Check that client sends `progressToken` in `tools/call` request

### Long-running operation hangs

**Issue:** Client timeout is too short

**Solution:** Increase client timeout or use `tools/call/cancel` to cancel the operation

## See Also

- [MCP Specification](https://spec.modelcontextprotocol.io/)
- [defport Documentation](../../../docs/)
- [Integration Testing Guide](../../../docs/testing/INTEGRATION_TESTING.md)
