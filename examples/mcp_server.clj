(ns mcp-server
  "Minimum-viable MCP server using defport.

  Exposes one tool (`add`) that adds two numbers. The whole thing is
  ~15 lines of user code — everything else (tools/list, tools/call,
  capability negotiation, request/response correlation, lifecycle
  handlers, stdio framing) comes from defport.

  ## Run

      clojure -M:examples -m mcp-server

  ## Validate with MCP Inspector

      npx @modelcontextprotocol/inspector \\
        clojure -M:examples -m mcp-server

  A browser tab opens. You should see the `add` tool listed.
  Clicking it, entering two numbers, and invoking should return
  their sum.

  ## Validate with Claude Desktop

  Add to `~/Library/Application Support/Claude/claude_desktop_config.json`
  (or your platform's equivalent):

      {\"mcpServers\": {
         \"defport-example\": {
           \"command\": \"clojure\",
           \"args\": [\"-M:examples\", \"-m\", \"mcp-server\"],
           \"cwd\": \"/absolute/path/to/defport\"}}}

  Restart Claude Desktop; the `add` tool should appear."
  (:require [defport.core :as core]
            [defport.mcp :as mcp]
            [defport.sugar :as sugar]
            [defport.transports.stdio :as stdio]))

;; ----- Tool definition ------------------------------------------------------
;;
;; `deftool` registers a port into defport.sugar/*registry* with
;; {:mcp/tool true} metadata. `create-adapter :mcp` picks these up
;; automatically at dispatch time.

(mcp/deftool add
  "Add two numbers."
  [a :- :int b :- :int]
  {:content [{:type "text"
              :text (str "The sum of " a " and " b " is " (+ a b))}]})

;; ----- Main -----------------------------------------------------------------

(defn -main [& _]
  (let [adapter   (sugar/create-adapter :mcp
                    {:server-info {:name "defport-example-mcp"
                                   :version "0.1.0"}})
        transport (stdio/create-stdio-transport {:drain-on-exit? true})
        handler   (fn [msg]
                    ;; protocol-dispatch returns a body; wrap it in
                    ;; JSON-RPC envelope for the transport to send.
                    (let [result (core/protocol-dispatch adapter
                                                         (:method msg)
                                                         (:params msg)
                                                         {})]
                      (when (:id msg)      ;; only respond to requests
                        (merge {:jsonrpc "2.0" :id (:id msg)}
                               (if (contains? result :error)
                                 {:error (:error result)}
                                 {:result result})))))]
    ;; Blocks until the client disconnects.
    (core/transport-start transport handler)))
