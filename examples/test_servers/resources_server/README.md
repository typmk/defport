# Resources Test Server

MCP test server focused on **resources functionality** testing.

## Overview

This server provides 10 resources (5 static, 5 dynamic) to test all aspects of the MCP resources specification:
- Static resources (read-only, no subscriptions)
- Dynamic resources (change over time, support subscriptions)
- Resource update notifications
- Multiple concurrent subscribers
- Various MIME types (JSON, EDN, Markdown, Plain Text)
- URI-based resource identification

## Resources

### Static Resources (Read-Only)

#### 1. defport://schema
**Type:** Static
**MIME Type:** `application/edn`
**Description:** Server schema definition

Returns the complete server schema including tools, prompts, and resources definitions.

#### 2. defport://version
**Type:** Static
**MIME Type:** `application/json`
**Description:** Server version information

Returns version, protocol, and platform details.

#### 3. defport://environment
**Type:** Static
**MIME Type:** `application/json`
**Description:** Environment information

Returns Java version, OS details, and working directory.

#### 4. defport://documentation
**Type:** Static
**MIME Type:** `text/markdown`
**Description:** API documentation

Returns Markdown-formatted documentation about available resources.

#### 5. defport://readme
**Type:** Static
**MIME Type:** `text/plain`
**Description:** README content

Returns plain text README about the server.

### Dynamic Resources (Subscribable)

These resources change over time and support subscriptions for real-time updates.

#### 6. defport://config
**Type:** Dynamic (Subscribable)
**MIME Type:** `application/json`
**Update Frequency:** On configuration changes
**Description:** Server configuration

Returns current configuration including debug mode, log level, max connections, and timeout settings.

#### 7. defport://stats
**Type:** Dynamic (Subscribable)
**MIME Type:** `application/json`
**Update Frequency:** Every 2 seconds
**Description:** Server statistics

Returns real-time stats: request count, uptime, active connections, and last update timestamp.

#### 8. defport://logs
**Type:** Dynamic (Subscribable)
**MIME Type:** `application/json`
**Update Frequency:** Every 5 seconds
**Description:** Application logs

Returns the last 100 log entries with timestamps, levels, and messages.

#### 9. defport://health
**Type:** Dynamic (Subscribable)
**MIME Type:** `application/json`
**Update Frequency:** On health changes
**Description:** Server health status

Returns current health status (starting/healthy), uptime, and timestamp.

#### 10. defport://metrics
**Type:** Dynamic (Subscribable)
**MIME Type:** `application/json`
**Update Frequency:** Every 3 seconds
**Description:** Performance metrics

Returns requests per second, active connections, memory usage, and timestamp.

## Background Tasks

The server runs background tasks that automatically update dynamic resources:

- **Stats Updater** (2s interval) - Increments request counter and uptime
- **Log Generator** (5s interval) - Adds new log entries
- **Metrics Updater** (3s interval) - Updates connection counts

When a resource changes, all subscribers receive a `notifications/resources/updated` notification.

## Running the Server

### HTTP Mode
```bash
cd c:\Users\Apollo\CascadeProjects\defport
clojure -M -m test-servers.resources-server --http 8080
```

### Stdio Mode
```bash
cd c:\Users\Apollo\CascadeProjects\defport
clojure -M -m test-servers.resources-server --stdio
```

## Testing Scenarios

### Manual Testing with HTTP

1. **Initialize:**
```bash
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
```

2. **List Resources:**
```bash
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"resources/list","params":{}}'
```

3. **Read Static Resource:**
```bash
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"defport://version"}}'
```

4. **Subscribe to Dynamic Resource:**
```bash
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"resources/subscribe","params":{"uri":"defport://stats"}}'
```

5. **Unsubscribe:**
```bash
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":5,"method":"resources/unsubscribe","params":{"uri":"defport://stats"}}'
```

### Integration Testing

Run the automated integration tests:
```bash
clojure -M:test -m kaocha.runner --focus defport.integration.resources-integration-test
```

## Validation Points

This server validates:
- ✅ Static resource reading
- ✅ Dynamic resource reading
- ✅ Resource subscriptions
- ✅ Resource unsubscriptions
- ✅ Update notifications to subscribers
- ✅ Multiple concurrent subscribers
- ✅ Different MIME types
- ✅ URI-based identification
- ✅ Resources/list pagination
- ✅ Metadata in resource listings

## MCP Specification Compliance

- **Method:** `resources/list` - Lists all available resources with pagination
- **Method:** `resources/read` - Reads resource content by URI
- **Method:** `resources/subscribe` - Subscribe to resource updates
- **Method:** `resources/unsubscribe` - Unsubscribe from updates
- **Notification:** `notifications/resources/updated` - Sent when subscribed resource changes
- **Response:** Returns `contents` array with URI, mimeType, and text
- **Content:** Uses TextContent for all resource data
- **Subscriptions:** Only dynamic resources support subscriptions

## Architecture Notes

- **State Management:** Uses atoms for thread-safe updates
- **Background Tasks:** Separate futures for each update task
- **Notifications:** Automatic via `mcp/notify-resource-update`
- **Cleanup:** Shutdown hook stops background tasks gracefully
- **Concurrency:** Safe for multiple simultaneous subscribers
