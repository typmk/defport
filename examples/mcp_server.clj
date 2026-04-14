(ns mcp-server
  "Minimum-viable MCP server using defport.

  13 lines of user code. Everything protocol-related — tools/list,
  tools/call, capability negotiation, JSON-RPC framing, stdio
  transport, lifecycle — comes from defport.

  ## Run

      clojure -M:examples -m mcp-server

  ## Validate with MCP Inspector

      npx @modelcontextprotocol/inspector \\
        clojure -M:examples -m mcp-server

  A browser tab opens. The `add` tool should appear; invoking it
  with `{\"a\": 3, \"b\": 4}` should return 7.

  ## Validate with Claude Desktop

  Add to `~/Library/Application Support/Claude/claude_desktop_config.json`:

      {\"mcpServers\": {
         \"defport-example\": {
           \"command\": \"clojure\",
           \"args\": [\"-M:examples\", \"-m\", \"mcp-server\"],
           \"cwd\": \"/absolute/path/to/defport\"}}}"
  (:require [defport.mcp :as mcp]
            [defport.sugar :as sugar]))

(mcp/deftool add
  "Add two numbers."
  [a :- :int b :- :int]
  {:content [{:type "text"
              :text (str "The sum of " a " and " b " is " (+ a b))}]})

(defn -main [& _]
  (sugar/run! {:protocol :mcp
               :server-info {:name "defport-example-mcp"
                             :version "0.1.0"}}))
