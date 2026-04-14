(ns mcp-client
  "Minimum-viable MCP client using defport.

  Spawns an external MCP server as a subprocess and enumerates
  its tools. By default it spawns defport's own example MCP server
  (so the example is self-contained); swap the `command` for any
  real MCP server to validate against the real world.

  ~20 lines of user code. Defport handles: Content-Length framing,
  request/response correlation by id, initialize handshake,
  subprocess lifecycle, reader thread / poll loop, typed helpers.

  ## Run

      clojure -M:examples -m mcp-client

  ## Validate against a real MCP server

  Swap `command` for one of:

      [\"npx\" \"-y\" \"@modelcontextprotocol/server-filesystem\" \"/tmp\"]
      [\"npx\" \"-y\" \"@modelcontextprotocol/server-github\"]
      [\"npx\" \"-y\" \"@modelcontextprotocol/server-everything\"]

  Any of these will connect, the server will respond to initialize,
  and `list-tools` will return its real tool set."
  (:require [defport.mcp.client :as mcp]
            [defport.mcp.client.transports.subprocess :as sub]))

(defn -main [& _]
  (let [;; Default: spawn defport's own example server so the
        ;; example is self-contained. Replace this vector with the
        ;; command for any real MCP server to validate externally.
        command ["clojure" "-M:examples" "-m" "mcp-server"]

        ;; Build the transport, wrap it in a client, connect.
        ;; connect! blocks until the initialize handshake completes.
        client (-> (sub/transport command)
                   (mcp/create-client)
                   (mcp/connect! {:client-info {:name "defport-example-client"
                                                :version "0.1.0"}}))]

    (try
      (println "Connected to:"
               (get-in @(:state* client) [:server-info :name]))

      ;; list-tools returns a Pending — await blocks on JVM.
      (let [[result error] (mcp/await (mcp/list-tools client))]
        (if error
          (println "list-tools failed:" error)
          (do
            (println "\nTools:")
            (doseq [tool (:tools result)]
              (println "  " (:name tool) "-" (:description tool))))))

      ;; Invoke a tool by name.
      (let [[result error] (mcp/await (mcp/call-tool client "add" {:a 3 :b 4}))]
        (if error
          (println "\ncall-tool failed:" error)
          (println "\nadd(3, 4) =>" result)))

      (finally
        (mcp/disconnect! client)))))
