# Roots Server - MCP Roots List Feature

A comprehensive test server demonstrating MCP's `roots/list` feature for path validation and security boundaries.

## Overview

The `roots/list` feature in MCP allows servers to declare a list of root directories that define the scope of file operations. This server demonstrates:

1. **Static roots declaration** - Pre-configured root directories
2. **Dynamic roots management** - Add/remove roots at runtime
3. **Path validation** - Validate file paths against declared roots
4. **Security enforcement** - Block operations outside roots
5. **Change notifications** - Notify clients when roots change (capability declared)

## Quick Start

### HTTP Transport
```bash
# Start server on default port 8080
clojure -M:examples -m roots-server --http 8080

# Test with curl
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"roots/list","params":{}}'
```

### Stdio Transport
```bash
# Start server in stdio mode
clojure -M:examples -m roots-server --stdio

# Send JSON-RPC requests via stdin
{"jsonrpc":"2.0","id":1,"method":"roots/list","params":{}}
```

## Default Roots

The server starts with three pre-configured roots:

1. **Projects Directory**: `file:///home/user/projects`
2. **Documents**: `file:///home/user/documents`
3. **Temp Directory**: `file:///tmp`

## Available Tools

### File Operations (Root-Validated)

#### 1. `list-files`
List files in a directory (validates against roots).

**Parameters:**
- `path` (string, required): Directory path to list

**Example:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "list-files",
    "arguments": {
      "path": "/home/user/projects"
    }
  }
}
```

**Success Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"path\":\"/home/user/projects\",\"files\":[...],\"count\":10}"
    }]
  }
}
```

**Error Response (path outside roots):**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "error": {
    "code": -32602,
    "message": "Invalid path: outside declared roots",
    "data": {
      "valid": false,
      "error": "Path is outside declared roots",
      "path": "/etc",
      "roots": ["file:///home/user/projects", "file:///home/user/documents", "file:///tmp"]
    }
  }
}
```

#### 2. `read-file`
Read a file (validates against roots).

**Parameters:**
- `path` (string, required): File path to read

**Example:**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "read-file",
    "arguments": {
      "path": "/home/user/projects/README.md"
    }
  }
}
```

### Path Validation Tools

#### 3. `validate-path`
Explicitly validate a path against declared roots.

**Parameters:**
- `path` (string, required): Path to validate

**Example:**
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "validate-path",
    "arguments": {
      "path": "/home/user/projects/src/main.clj"
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"valid\":true}"
    }]
  }
}
```

#### 4. `check-access`
Check if multiple paths are accessible.

**Parameters:**
- `paths` (array of strings, required): Paths to check

**Example:**
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "check-access",
    "arguments": {
      "paths": [
        "/home/user/projects/test.txt",
        "/etc/passwd",
        "/tmp/data.json"
      ]
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "content": [{
      "type": "text",
      "text": "{\"paths\":[...],\"results\":[...],\"accessible\":2,\"blocked\":1}"
    }]
  }
}
```

### Root Management Tools (Dangerous)

These tools modify the server's root configuration and require `DEFPORT_ENABLE_REFACTORING=true` or the `:enable-refactoring` option.

#### 5. `add-root`
Add a new root directory dynamically.

**Parameters:**
- `uri` (string, required): Root URI (e.g., `file:///path/to/dir`)
- `name` (string, required): Human-readable name

**Example:**
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "add-root",
    "arguments": {
      "uri": "file:///opt/data",
      "name": "Data Directory"
    }
  }
}
```

#### 6. `remove-root`
Remove a root directory dynamically.

**Parameters:**
- `uri` (string, required): Root URI to remove

**Example:**
```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "method": "tools/call",
  "params": {
    "name": "remove-root",
    "arguments": {
      "uri": "file:///tmp"
    }
  }
}
```

### Utility Tools

#### 7. `get-roots`
Get current roots list (alternative to `roots/list` method).

**Example:**
```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "method": "tools/call",
  "params": {
    "name": "get-roots",
    "arguments": {}
  }
}
```

#### 8. `test-security`
Test security boundary enforcement with various paths.

**Parameters:**
- `safe-path` (string, optional): Path within roots
- `unsafe-path` (string, optional): Path outside roots
- `relative-path` (string, optional): Relative path test

**Example:**
```json
{
  "jsonrpc": "2.0",
  "id": 9,
  "method": "tools/call",
  "params": {
    "name": "test-security",
    "arguments": {
      "safe-path": "/home/user/projects/test.txt",
      "unsafe-path": "/etc/passwd",
      "relative-path": "../../../etc/passwd"
    }
  }
}
```

## MCP Protocol Methods

### `roots/list`
Get the list of declared roots.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "roots/list",
  "params": {}
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "roots": [
      {
        "uri": "file:///home/user/projects",
        "name": "Projects Directory"
      },
      {
        "uri": "file:///home/user/documents",
        "name": "Documents"
      },
      {
        "uri": "file:///tmp",
        "name": "Temp Directory"
      }
    ]
  }
}
```

## Test Scenarios

### Scenario 1: Path Validation

**Objective:** Verify that paths are correctly validated against roots.

1. **Start the server**
   ```bash
   clojure -M:examples -m roots-server --http 8080
   ```

2. **Get roots list**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"roots/list","params":{}}'
   ```

3. **Validate a path within roots**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"validate-path","arguments":{"path":"/home/user/projects/test.txt"}}}'
   ```

   **Expected:** `{"valid":true}`

4. **Validate a path outside roots**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"validate-path","arguments":{"path":"/etc/passwd"}}}'
   ```

   **Expected:** `{"valid":false,"error":"Path is outside declared roots",...}`

### Scenario 2: Security Enforcement

**Objective:** Verify that file operations are blocked outside roots.

1. **Try to list files within roots**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list-files","arguments":{"path":"/tmp"}}}'
   ```

   **Expected:** Success with file list

2. **Try to list files outside roots**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"list-files","arguments":{"path":"/etc"}}}'
   ```

   **Expected:** Error with code -32602

### Scenario 3: Dynamic Root Management

**Objective:** Add and remove roots at runtime.

1. **Enable refactoring mode**
   ```bash
   DEFPORT_ENABLE_REFACTORING=true clojure -M:examples -m roots-server --http 8080
   ```

2. **Add a new root**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"add-root","arguments":{"uri":"file:///opt/data","name":"Data Directory"}}}'
   ```

3. **Verify new root appears in roots/list**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":7,"method":"roots/list","params":{}}'
   ```

4. **Remove a root**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"remove-root","arguments":{"uri":"file:///tmp"}}}'
   ```

### Scenario 4: Batch Path Checking

**Objective:** Check multiple paths for accessibility.

1. **Check access to multiple paths**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"check-access","arguments":{"paths":["/home/user/projects/a.txt","/etc/passwd","/tmp/b.txt"]}}}'
   ```

   **Expected:** Response showing 2 accessible, 1 blocked

### Scenario 5: Security Testing

**Objective:** Test various path traversal attempts.

1. **Run security test**
   ```bash
   curl -X POST http://localhost:8080 \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"test-security","arguments":{"safe-path":"/home/user/projects/test.txt","unsafe-path":"/etc/passwd","relative-path":"../../../etc/passwd"}}}'
   ```

   **Expected:** Shows which paths are allowed/blocked

## Architecture Notes

### Path Validation Logic

The server validates paths by:
1. Normalizing the root URI (removing `file://` prefix)
2. Normalizing the target path (converting backslashes to forward slashes)
3. Checking if the path starts with any root path

### Security Boundaries

- All file operations (`list-files`, `read-file`) validate paths before execution
- Operations outside declared roots return error code `-32602` (Invalid params)
- Relative path traversal attempts (e.g., `../../`) are caught by validation
- Root management operations are marked as dangerous and require opt-in

### Change Notifications

The server declares `roots: { listChanged: true }` capability during initialization. When roots are added or removed:
1. The internal state is updated
2. A `roots/list_changed` notification should be sent to all clients (TODO: requires notification infrastructure)

## Integration with MCP Clients

### MCP Inspector

```json
{
  "mcpServers": {
    "defport-roots-jvm": {
      "command": "clj",
      "args": ["-M:examples", "-m", "roots-server", "--stdio"],
      "cwd": "c:\\Users\\Apollo\\CascadeProjects\\defport"
    }
  }
}
```

### Claude Desktop

```json
{
  "mcpServers": {
    "defport-roots": {
      "command": "clj",
      "args": ["-M:examples", "-m", "roots-server", "--stdio"]
    }
  }
}
```

## Compliance Notes

### MCP 2025-06-18 Spec

- ✅ **roots/list**: Returns array of `{uri, name}` objects
- ✅ **Capability Declaration**: `roots: { listChanged: true }`
- ✅ **Content Format**: All tool results use TextContent with JSON
- ✅ **Error Codes**: Standard JSON-RPC codes for validation errors
- ✅ **Field Naming**: camelCase throughout (uri, name, not uri_path, display_name)

### Security Model

- 🔒 **Safe by default**: File operations validate against roots
- 🔓 **Dangerous operations**: Root management requires opt-in
- ✅ **Metadata marking**: `add-root` and `remove-root` marked with `{:dangerous true}`
- ✅ **Error messages**: Clear indication when paths are outside roots

## Testing

Run integration tests:
```bash
clojure -M:test -m kaocha.runner --focus defport.integration.roots-integration-test
```

## Future Enhancements

1. **Root change notifications**: Send `notifications/roots/list_changed` when roots change
2. **Symbolic link handling**: Resolve symlinks before validation
3. **Glob pattern support**: Allow roots to specify patterns like `file:///home/user/projects/*`
4. **Permission levels**: Different roots with different access levels (read-only, read-write)
5. **Platform-specific paths**: Better handling of Windows paths (C:\, UNC paths)
