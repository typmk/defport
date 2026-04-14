# defport examples

Runnable reference files — one per protocol per role. Each is
self-contained, uses defport's public API, and shows the minimum
surface you need to write to get a working server or client.

| File | Role | Lines of user code |
|---|---|---|
| [`mcp_server.clj`](mcp_server.clj) | MCP server | ~15 |
| [`mcp_client.clj`](mcp_client.clj) | MCP client | ~20 |
| [`lsp_server.clj`](lsp_server.clj) | LSP server | ~30 |
| [`lsp_client.clj`](lsp_client.clj) | LSP client | ~20 |
| [`dap_server.clj`](dap_server.clj) | DAP server | ~20 |
| [`dap_client.clj`](dap_client.clj) | DAP client | ~25 |

## Running

Every example is a plain `-main` function; run via the
`:examples` alias:

```bash
clojure -M:examples -m mcp-server     # server (stays running)
clojure -M:examples -m mcp-client     # client (exits after one pass)
clojure -M:examples -m lsp-server
clojure -M:examples -m lsp-client
clojure -M:examples -m dap-server
clojure -M:examples -m dap-client
```

Each client spawns the matching server by default so the whole
example is self-contained. Swap the `command` vector at the top
of any client file to point at a real server.

## Validation paths (no extension required)

### MCP server → MCP Inspector

```bash
npx @modelcontextprotocol/inspector clojure -M:examples -m mcp-server
```

A browser tab opens showing your tools. Any wire-format bug
appears as a decode error in the inspector UI. This is the
single cheapest validation for an MCP server.

### MCP server → Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json`
(macOS) or equivalent:

```json
{
  "mcpServers": {
    "defport-example": {
      "command": "clojure",
      "args": ["-M:examples", "-m", "mcp-server"],
      "cwd": "/absolute/path/to/defport"
    }
  }
}
```

### LSP server → Neovim built-in LSP

In any Clojure buffer:

```lua
:lua vim.lsp.start({
  name = 'defport-example',
  cmd = {'clojure', '-M:examples', '-m', 'lsp-server'},
  root_dir = vim.fn.getcwd(),
})
```

Then `:lua vim.lsp.buf.hover()` — defport's example server
returns a canned hover card. Requires Neovim 0.8+.

### DAP server → nvim-dap

```lua
require('dap').adapters['defport-example'] = {
  type = 'executable',
  command = 'clojure',
  args = {'-M:examples', '-m', 'dap-server'},
}

require('dap').configurations.python = {{
  type = 'defport-example',
  request = 'launch',
  name = 'Defport example',
  program = '${file}',
}}
```

Then `:lua require('dap').continue()` runs the launch flow.

## What defport provides

For ~15 lines of `deftool`/`deflsp`/`defcommand`, defport supplies:

- **Wire framing**: Content-Length header + JSON body, both encode
  and streaming decode.
- **Request/response correlation**: by id for LSP/MCP, by seq for DAP.
- **Subprocess management**: ProcessBuilder on JVM, child_process on
  Node, both behind one constructor.
- **Reader thread / poll loop**: JVM blocks on the stream, Node
  schedules via the event loop — neither blocks the dispatcher.
- **Spec-driven routing**: 25 LSP methods, 45 DAP commands + 17
  events, 29 MCP methods. Each ships with its wire name, sugar
  shape, default body, and (where applicable) error-default.
- **Capability derivation**: `capabilities-from-registry` walks
  registered ports and builds the `initialize` response
  automatically. You don't hand-write a capability map.
- **Lifecycle handlers**: initialize / shutdown / exit / didOpen /
  didChange / didSave / didClose on LSP, initialize / launch /
  configurationDone / disconnect on DAP, initialize on MCP.

None of this code appears in the examples above.

## Design principles (applied here)

The examples deliberately use the public API as a consumer would:

- `sugar/create-adapter :mcp|:lsp|:dap` is the one-call adapter
  constructor. It reads the sugar registry, auto-computes
  capabilities, and returns a running adapter.
- `defport.core/protocol-dispatch` is the single dispatch entry
  point. Transports call it once per inbound message.
- `defport.transports.stdio/start` is the stock stdio transport.
  Replace with an HTTP/WebSocket transport when you need one;
  the adapter doesn't care.

Read defport's `CLAUDE.md` principle 3 (protocol intersection) and
principle 5 (pluggable platform primitives) for the architectural
rationale behind what these 100 lines of example code are doing.
