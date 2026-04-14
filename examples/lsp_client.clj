(ns lsp-client
  "Minimum-viable LSP client using defport.

  Spawns an external LSP server as a subprocess, runs the
  initialize handshake, and asks for hover info at a position.
  By default spawns defport's own example LSP server so the
  example is self-contained.

  ## Run

      clojure -M:examples -m lsp-client

  ## Validate against a real LSP

  Swap `command` for one of:

      [\"clojure-lsp\"]
      [\"rust-analyzer\"]
      [\"typescript-language-server\" \"--stdio\"]
      [\"gopls\"]

  Then point :root-uri at a project for that language. The
  initialize response should contain the server's real capabilities."
  (:require [defport.lsp.client :as lsp]
            [defport.lsp.client.transports.subprocess :as sub]))

(defn -main [& _]
  (let [command ["clojure" "-M:examples" "-m" "lsp-server"]
        client (-> (sub/transport command)
                   (lsp/create-client)
                   (lsp/connect! {:client-info {:name "defport-example-client"
                                                :version "0.1.0"}
                                  :root-uri "file:///tmp"
                                  :capabilities {}}))]
    (try
      (println "Connected to:"
               (get-in @(:state* client) [:server-info :name]))

      (println "\nServer capabilities:")
      (doseq [[k _] (:server-capabilities @(:state* client))]
        (println "  " k))

      (let [[result error] (lsp/await
                             (lsp/hover-at client "file:///example.clj" 0 0))]
        (if error
          (println "\nhover-at failed:" error)
          (println "\nHover:" result)))

      (let [[result error] (lsp/await
                             (lsp/document-symbols client "file:///example.clj"))]
        (if error
          (println "document-symbols failed:" error)
          (do
            (println "\nDocument symbols:")
            (doseq [sym result]
              (println "  " (:name sym))))))

      (finally
        (lsp/disconnect! client)))))
