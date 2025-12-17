# Tools Server - Manual Test Scenarios

This document provides step-by-step test scenarios for manual testing of the tools_server with MCP Inspector, Claude Desktop, Cursor, or other MCP clients.

## Prerequisites

1. **Start the server:**
   ```bash
   cd c:\Users\Apollo\CascadeProjects\defport
   clj -M:examples -m tools-server --stdio
   ```

2. **Configure your MCP client** with the server

3. **Have the MCP Inspector open** (recommended for debugging):
   ```bash
   npx @modelcontextprotocol/inspector
   ```

## Test Scenario 1: Basic Connection & Initialization

**Objective:** Verify MCP handshake and server info

**Steps:**
1. Connect to the server
2. Send `initialize` request
3. Verify response contains:
   - `protocolVersion`: "2025-06-18"
   - `serverInfo` with name and version
   - `capabilities` with `tools` capability

**Expected Result:**
```json
{
  "protocolVersion": "2025-06-18",
  "capabilities": {
    "tools": {}
  },
  "serverInfo": {
    "name": "defport-mcp-server",
    "version": "0.1.0"
  }
}
```

**Pass Criteria:** ✅ Proper JSON-RPC 2.0 envelope, camelCase fields, all required fields present

---

## Test Scenario 2: List All Tools

**Objective:** Verify tools/list returns all 16 tools with pagination

**Steps:**
1. Send `tools/list` request with no cursor
2. Verify response contains 10 tools (first page)
3. Verify `nextCursor` is present
4. Send `tools/list` with cursor from step 3
5. Verify second page contains remaining 6 tools
6. Verify `nextCursor` is absent (end of list)

**Expected Result (First Page):**
```json
{
  "tools": [
    {"name": "echo", "description": "Echo back the input message", ...},
    {"name": "add", ...},
    ... (10 tools total)
  ],
  "nextCursor": "offset-10"
}
```

**Expected Result (Second Page):**
```json
{
  "tools": [
    {"name": "long-running", ...},
    {"name": "slow-operation", ...},
    ... (6 tools total)
  ]
}
```

**Pass Criteria:** ✅ All 16 tools returned, pagination works, no nextCursor on last page

---

## Test Scenario 3: Simple Tool Execution (echo)

**Objective:** Verify basic tool call and response format

**Steps:**
1. Send `tools/call` request:
   ```json
   {
     "name": "echo",
     "arguments": {"message": "Hello, MCP!"}
   }
   ```
2. Verify response contains `content` array
3. Verify content has TextContent with echoed message

**Expected Result:**
```json
{
  "content": [
    {
      "type": "text",
      "text": "Hello, MCP!"
    }
  ]
}
```

**Pass Criteria:** ✅ Correct content format, message echoed exactly

---

## Test Scenario 4: Numeric Operations (add, multiply)

**Objective:** Verify numeric input handling and structured data output

**Steps:**
1. Call `add` tool:
   ```json
   {"name": "add", "arguments": {"a": 15, "b": 27}}
   ```
2. Verify result is 42
3. Call `multiply` tool:
   ```json
   {"name": "multiply", "arguments": {"a": 6, "b": 7}}
   ```
4. Verify result is 42

**Expected Result (add):**
```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"result\":42,\"operation\":\"addition\",\"operands\":[15,27]}"
    }
  ]
}
```

**Pass Criteria:** ✅ Correct calculations, structured JSON in TextContent

---

## Test Scenario 5: Array Operations (calculate-stats)

**Objective:** Verify array input handling

**Steps:**
1. Call `calculate-stats`:
   ```json
   {
     "name": "calculate-stats",
     "arguments": {"numbers": [1, 2, 3, 4, 5, 10]}
   }
   ```
2. Verify all statistics are correct:
   - count: 6
   - sum: 25
   - mean: ~4.17
   - min: 1
   - max: 10

**Expected Result:**
```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"count\":6,\"sum\":25,\"mean\":4.166666666666667,\"min\":1,\"max\":10,...}"
    }
  ]
}
```

**Pass Criteria:** ✅ All statistics correct, proper array handling

---

## Test Scenario 6: Text Manipulation (batch-process)

**Objective:** Verify batch array processing

**Steps:**
1. Call `batch-process` with uppercase:
   ```json
   {
     "name": "batch-process",
     "arguments": {
       "items": ["hello", "world", "test"],
       "operation": "uppercase"
     }
   }
   ```
2. Verify all items are uppercased
3. Repeat with "lowercase" and "reverse" operations

**Expected Result (uppercase):**
```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"operation\":\"uppercase\",\"results\":[\"HELLO\",\"WORLD\",\"TEST\"],...}"
    }
  ]
}
```

**Pass Criteria:** ✅ All operations work correctly, array items processed

---

## Test Scenario 7: Error Handling (error-tool)

**Objective:** Verify proper error response format

**Steps:**
1. Call `error-tool`:
   ```json
   {"name": "error-tool", "arguments": {}}
   ```
2. Verify response contains `error` field (not `result`)
3. Verify error has proper structure:
   - `code`: -32000 (server error)
   - `message`: string
   - `data`: optional additional info

**Expected Result:**
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

**Pass Criteria:** ✅ Error format correct, proper error code, no result field

---

## Test Scenario 8: Invalid JSON Handling (json-parser)

**Objective:** Verify error handling for invalid input

**Steps:**
1. Call `json-parser` with valid JSON:
   ```json
   {
     "name": "json-parser",
     "arguments": {"jsonString": "{\"name\":\"Alice\",\"age\":30}"}
   }
   ```
2. Verify success response
3. Call `json-parser` with invalid JSON:
   ```json
   {
     "name": "json-parser",
     "arguments": {"jsonString": "invalid json {"}
   }
   ```
4. Verify error response with code -32602 (Invalid params)

**Expected Result (valid):**
```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"success\":true,\"parsed\":{\"name\":\"Alice\",\"age\":30}}"
    }
  ]
}
```

**Expected Result (invalid):**
```json
{
  "error": {
    "code": -32602,
    "message": "Invalid JSON",
    "data": {"error": "..."}
  }
}
```

**Pass Criteria:** ✅ Valid JSON parsed correctly, invalid JSON returns proper error

---

## Test Scenario 9: Progress Notifications (long-running)

**Objective:** Verify progress notifications during long operations

**Steps:**
1. Call `long-running` tool with progress token:
   ```json
   {
     "name": "long-running",
     "arguments": {"durationMs": 10000},
     "_meta": {"progressToken": "progress-123"}
   }
   ```
2. Observe progress notifications sent during execution
3. Verify final result after ~10 seconds

**Expected Progress Notifications:**
```json
{
  "method": "notifications/progress",
  "params": {
    "progressToken": "progress-123",
    "progress": 1,
    "total": 10
  }
}
```
(Repeated for steps 2-10)

**Expected Final Result:**
```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"completed\":true,\"duration\":10000,\"steps\":10}"
    }
  ]
}
```

**Pass Criteria:** ✅ Progress notifications sent at each step, final result correct

---

## Test Scenario 10: Cancellation (long-running)

**Objective:** Verify tools/call/cancel functionality

**Steps:**
1. Start `long-running` operation:
   ```json
   {
     "method": "tools/call",
     "params": {
       "name": "long-running",
       "arguments": {"durationMs": 30000}
     },
     "id": 100
   }
   ```
2. After ~3 seconds, send cancel request:
   ```json
   {
     "method": "tools/call/cancel",
     "params": {"requestId": 100},
     "id": 101
   }
   ```
3. Verify original request returns error with code -32800

**Expected Cancellation Response:**
```json
{
  "error": {
    "code": -32800,
    "message": "Operation cancelled by client"
  }
}
```

**Pass Criteria:** ✅ Operation cancelled mid-execution, proper error code

---

## Test Scenario 11: Search Functionality (search-code)

**Objective:** Verify structured data return for search results

**Steps:**
1. Call `search-code`:
   ```json
   {
     "name": "search-code",
     "arguments": {"query": "defprotocol"}
   }
   ```
2. Verify results contain:
   - Query string
   - Result count
   - Array of results with file, line, snippet

**Expected Result:**
```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"query\":\"defprotocol\",\"resultCount\":3,\"results\":[...]}"
    }
  ]
}
```

**Pass Criteria:** ✅ Search results properly structured, all fields present

---

## Test Scenario 12: Concurrent Operations

**Objective:** Verify server handles multiple simultaneous requests

**Steps:**
1. Send 3 requests simultaneously:
   - Request 1: `slow-operation` (5 seconds)
   - Request 2: `add` with a=10, b=20
   - Request 3: `echo` with message="concurrent test"
2. Verify all 3 responses are correct
3. Verify fast operations (add, echo) complete before slow-operation

**Expected Behavior:**
- Requests 2 and 3 complete in <1 second
- Request 1 completes in ~5 seconds
- All responses have correct request IDs
- No interference between operations

**Pass Criteria:** ✅ All operations complete correctly, proper concurrency handling

---

## Test Scenario 13: Time-based Tools (get-time, generate-uuid)

**Objective:** Verify non-deterministic tools return valid data

**Steps:**
1. Call `get-time`:
   ```json
   {"name": "get-time", "arguments": {}}
   ```
2. Verify timestamp is reasonable (close to current time)
3. Verify formatted date string is present
4. Call `generate-uuid`:
   ```json
   {"name": "generate-uuid", "arguments": {}}
   ```
5. Verify UUID format (8-4-4-4-12 hex digits)

**Expected Result (get-time):**
```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"timestamp\":1705161234567,\"formatted\":\"Sat Jan 13 ...\"}"
    }
  ]
}
```

**Expected Result (generate-uuid):**
```json
{
  "content": [
    {
      "type": "text",
      "text": "550e8400-e29b-41d4-a716-446655440000"
    }
  ]
}
```

**Pass Criteria:** ✅ Timestamp within reasonable range, UUID format valid

---

## Test Scenario 14: Edge Cases - Empty Inputs

**Objective:** Verify handling of empty/minimal inputs

**Steps:**
1. Call `echo` with empty message:
   ```json
   {"name": "echo", "arguments": {"message": ""}}
   ```
2. Call `calculate-stats` with empty array:
   ```json
   {"name": "calculate-stats", "arguments": {"numbers": []}}
   ```
3. Call `batch-process` with empty items:
   ```json
   {"name": "batch-process", "arguments": {"items": []}}
   ```
4. Verify all handle empty inputs gracefully

**Pass Criteria:** ✅ No crashes, sensible default values (e.g., count=0, results=[])

---

## Test Scenario 15: Timeout Testing (slow-operation)

**Objective:** Verify client timeout handling

**Steps:**
1. Configure client with 3-second timeout
2. Call `slow-operation` (takes 5 seconds):
   ```json
   {"name": "slow-operation", "arguments": {}}
   ```
3. Verify client times out after 3 seconds
4. Configure client with 10-second timeout
5. Repeat call
6. Verify operation completes successfully

**Pass Criteria:** ✅ Client timeout works correctly, operation completes with sufficient timeout

---

## Test Scenario 16: Full Workflow - Multi-Tool Usage

**Objective:** Verify server handles complex multi-tool workflow

**Steps:**
1. Initialize connection
2. List all tools
3. Generate UUID
4. Echo the UUID
5. Get current time
6. Calculate stats for [1, 2, 3, 4, 5]
7. Search for code
8. Convert search query to uppercase
9. Verify all operations work correctly in sequence

**Pass Criteria:** ✅ All operations complete successfully, no state leakage between calls

---

## Compliance Checklist

After completing all scenarios, verify:

- ✅ All responses use JSON-RPC 2.0 envelope
- ✅ All field names are camelCase (no snake_case)
- ✅ All structured data uses TextContent with JSON string
- ✅ No ObjectContent in any response
- ✅ Error responses have proper structure and codes
- ✅ Progress notifications have correct format
- ✅ Cancellation returns code -32800
- ✅ Pagination works correctly (10 items per page)
- ✅ Request IDs are properly echoed in responses

---

## Troubleshooting Tips

### No progress notifications received
- Check client supports progress tokens
- Verify `_meta.progressToken` is sent in request
- Check server logs for progress callback invocations

### Cancellation doesn't work
- Verify `tools/call/cancel` uses correct request ID
- Check operation is still running when cancel is sent
- Ensure operation checks cancellation periodically

### Empty responses
- Check server logs for errors
- Verify tool handler returns proper content structure
- Ensure TextContent format is correct

### Timeout errors
- Increase client timeout for long-running operations
- Consider using cancellation instead of timeout
- Check network latency isn't causing delays

---

## Automated Testing

For automated testing of these scenarios, see:
- `test/defport/integration/tools_integration_test.clj`
- Run with: `clj -M:kaocha --focus :integration`

---

## Next Steps

After completing manual testing:
1. Run automated integration tests
2. Test with other MCP clients (Claude Desktop, Cursor)
3. Profile performance under load
4. Test with Node.js implementation (when available)
