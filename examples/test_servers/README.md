# defport MCP Test Servers

This directory contains complete MCP test servers for both JVM and Node.js platforms. Use these servers to test defport with:
- **MCP Inspector** - Visual debugging and testing tool
- **Claude Desktop** - AI assistant with MCP support
- **Cursor** - AI-powered code editor with MCP support

## Quick Start

### JVM Server (Clojure)

```bash
# Run with stdio (for Claude Desktop, Cursor)
clojure -M:examples -m mcp-test-server-jvm

# Run with HTTP (for MCP Inspector)
clojure -M:examples -m mcp-test-server-jvm --http --port 3000
```

### Node.js Server (ClojureScript)

```bash
# First-time setup
cd examples/test_servers
npm install
npm run build

# Run with stdio (for Claude Desktop, Cursor)
npm run server

# Run with HTTP (for MCP Inspector)
npm run server:http

# Custom port
npm run server:http:custom 3001
```

## Available Tools

Both servers expose the same set of tools:

### Basic Tools
- **echo** - Echo back input text (tests connectivity)
- **add-numbers** - Add two numbers (tests parameter handling)
- **get-system-info** - Get system information (tests data serialization)
- **list-files** - List files in a directory (tests resource operations)
- **long-running-task** - Simulate long-running task (tests progress notifications)

### Dangerous Tools (filtered by default)
- **write-file** - Write content to a file (requires `DEFPORT_ENABLE_REFACTORING=true`)

### Prompts
- **code-review** - Generate code review prompt for language and focus area
- **explain-concept** - Generate educational prompt for programming concepts

### Resources
- **server-config** - Get current server configuration (JSON)
- **system-status** - Get system status and metrics (JSON)

## Testing with MCP Inspector

[MCP Inspector](https://github.com/modelcontextprotocol/inspector) is a visual tool for testing MCP servers.

### Installation

```bash
npm install -g @modelcontextprotocol/inspector
```

### Configuration

The `mcp-inspector-config.json` file contains pre-configured servers:

```bash
# Use the config file
mcp-inspector --config examples/test_servers/mcp-inspector-config.json

# Or run directly
mcp-inspector http://localhost:3000
```

### Available Configurations

- **defport-jvm-http** - JVM server on port 3000 (HTTP)
- **defport-jvm-stdio** - JVM server (stdio)
- **defport-node-http** - Node.js server on port 3001 (HTTP)
- **defport-node-stdio** - Node.js server (stdio)

### Testing Workflow

1. Start your chosen server (JVM or Node.js)
2. Launch MCP Inspector with the config
3. Select a server from the dropdown
4. Test tools, prompts, and resources
5. Verify responses and error handling

## Testing with Claude Desktop

[Claude Desktop](https://claude.ai/download) supports MCP servers via stdio transport.

### Configuration

Add to your Claude Desktop config file:

**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
**Linux**: `~/.config/Claude/claude_desktop_config.json`

#### JVM Server

```json
{
  "mcpServers": {
    "defport-jvm": {
      "command": "clojure",
      "args": ["-M:examples", "-m", "mcp-test-server-jvm"],
      "cwd": "C:\\Users\\Apollo\\CascadeProjects\\defport"
    }
  }
}
```

#### Node.js Server

```json
{
  "mcpServers": {
    "defport-node": {
      "command": "node",
      "args": ["examples/test_servers/target/mcp_test_server_node.js"],
      "cwd": "C:\\Users\\Apollo\\CascadeProjects\\defport"
    }
  }
}
```

**Note**: Replace `C:\\Users\\Apollo\\CascadeProjects\\defport` with your actual project path.

### Testing Workflow

1. Add server configuration to Claude Desktop config
2. Restart Claude Desktop
3. Open a new conversation
4. Ask Claude to use the tools (e.g., "Use the echo tool to say hello")
5. Verify tool responses appear in the conversation

### Enabling Dangerous Tools

To enable the `write-file` tool:

```json
{
  "mcpServers": {
    "defport-jvm": {
      "command": "clojure",
      "args": ["-M:examples", "-m", "mcp-test-server-jvm"],
      "cwd": "C:\\Users\\Apollo\\CascadeProjects\\defport",
      "env": {
        "DEFPORT_ENABLE_REFACTORING": "true"
      }
    }
  }
}
```

## Testing with Cursor

[Cursor](https://cursor.sh/) supports MCP servers via stdio transport.

### Configuration

Add to your Cursor settings (`settings.json`):

#### JVM Server

```json
{
  "mcp.servers": {
    "defport-jvm": {
      "command": "clojure",
      "args": ["-M:examples", "-m", "mcp-test-server-jvm"],
      "cwd": "C:\\Users\\Apollo\\CascadeProjects\\defport"
    }
  }
}
```

#### Node.js Server

```json
{
  "mcp.servers": {
    "defport-node": {
      "command": "node",
      "args": ["examples/test_servers/target/mcp_test_server_node.js"],
      "cwd": "C:\\Users\\Apollo\\CascadeProjects\\defport"
    }
  }
}
```

**Note**: Replace `C:\\Users\\Apollo\\CascadeProjects\\defport` with your actual project path.

### Testing Workflow

1. Add server configuration to Cursor settings
2. Restart Cursor
3. Open the MCP panel (View → MCP or Cmd/Ctrl+Shift+M)
4. Verify server connection and available tools
5. Use tools through the Cursor AI interface

## Troubleshooting

### Server Won't Start

**JVM Server:**
```bash
# Check if Clojure is installed
clojure --version

# Verify deps.edn has :examples alias
clojure -M:examples -e '(println "OK")'

# Check for port conflicts (HTTP mode)
netstat -an | grep 3000
```

**Node.js Server:**
```bash
# Check if Node.js is installed
node --version

# Rebuild if needed
cd examples/test_servers
npm run clean
npm install
npm run build

# Check build output
ls -la target/mcp_test_server_node.js
```

### Connection Issues

**Stdio Transport:**
- Ensure server outputs to stderr, not stdout (defport handles this)
- Check for JSON parsing errors in client logs
- Verify no stray print statements in server code

**HTTP Transport:**
- Verify port is not in use
- Check firewall settings
- Use `curl` to test endpoint:
  ```bash
  curl -X POST http://localhost:3000/rpc \
    -H "Content-Type: application/json" \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
  ```

### Tool Not Found

- Verify tool is registered: Check server startup output
- Check for typos in tool name
- Ensure server has fully initialized before client connects

### Dangerous Tools Not Available

Dangerous tools (like `write-file`) are filtered by default. Enable via:

**Environment variable:**
```bash
DEFPORT_ENABLE_REFACTORING=true clojure -M:examples -m mcp-test-server-jvm
```

**Or in client config:**
```json
{
  "env": {
    "DEFPORT_ENABLE_REFACTORING": "true"
  }
}
```

## Development

### Adding New Tools

Edit `mcp_test_server_jvm.clj` or `mcp_test_server_node.cljs`:

```clojure
(dsl/deftool my-new-tool
  "Description of what the tool does"
  [param1 :- :string
   param2 :- :number]
  {:result "tool output"})
```

### Rebuilding Node.js Server

```bash
cd examples/test_servers
npm run build        # Development build
npm run build:release  # Optimized build
npm run watch        # Auto-rebuild on changes
```

### Testing Changes

```bash
# Terminal 1: Start server
clojure -M:examples -m mcp-test-server-jvm --http

# Terminal 2: Test with curl
curl -X POST http://localhost:3000/rpc \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Examples

### Test Tool Call (MCP Inspector)

```json
{
  "method": "tools/call",
  "params": {
    "name": "echo",
    "arguments": {
      "text": "Hello from MCP Inspector!"
    }
  }
}
```

### Test Prompt (Claude Desktop)

"Use the code-review prompt to review JavaScript code with a focus on security"

### Test Resource (curl)

```bash
curl -X POST http://localhost:3000/rpc \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "resources/read",
    "params": {
      "uri": "defport://server-config"
    }
  }'
```

## Platform Differences

### JVM Server
- Uses Java system properties for system info
- File operations use `clojure.java.io`
- Better for development (faster REPL workflow)

### Node.js Server
- Uses Node.js `os` and `fs` modules
- Better for deployment (smaller footprint)
- Requires build step for changes

Both servers implement the **exact same MCP interface** - choose based on your deployment needs.

## Further Reading

- [MCP Specification](https://spec.modelcontextprotocol.io/)
- [MCP Inspector Documentation](https://github.com/modelcontextprotocol/inspector)
- [Claude Desktop MCP Guide](https://claude.ai/docs/mcp)
- [defport Documentation](../../docs/)

## License

EPL-2.0 (same as defport)
