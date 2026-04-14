(ns dap-client
  "Minimum-viable DAP client using defport.

  Spawns an external debug adapter, runs initialize + launch, and
  issues a threads request. By default spawns defport's own example
  DAP server.

  ## Run

      clojure -M:examples -m dap-client

  ## Validate against a real debug adapter

  Swap `command` for one of:

      [\"python\" \"-m\" \"debugpy.adapter\"]
      [\"node\" \"/path/to/vscode-js-debug/dist/debugServer.js\"]
      [\"dlv\" \"dap\"]                     ; Go

  Then adapt `launch-args` to the target language/program."
  (:require [defport.dap.client :as dap]
            [defport.dap.client.transports.subprocess :as sub]))

(defn -main [& _]
  (let [command ["clojure" "-M:examples" "-m" "dap-server"]

        client (-> (sub/transport command)
                   (dap/create-client)
                   (dap/connect! {:adapter-id "defport-example"}))]
    (try
      (println "Initialized. Adapter capabilities:")
      (doseq [[k _] (:adapter-capabilities @(:state* client))]
        (println "  " k))

      ;; Launch a notional debuggee. For a real adapter you'd
      ;; pass program/cwd/args/env here.
      (let [[_ error] (dap/await (dap/launch! client {:program "noop"}))]
        (if error
          (println "\nlaunch failed:" error)
          (println "\nLaunched.")))

      (let [[body error] (dap/await (dap/threads client))]
        (if error
          (println "threads failed:" error)
          (do
            (println "\nThreads:")
            (doseq [t (:threads body)]
              (println "  " (:id t) (:name t))))))

      ;; Evaluate an expression in the top frame (frame-id 1 is our
      ;; example server's fake frame).
      (let [[body error] (dap/await (dap/evaluate client "(+ 1 2)" 1 "watch"))]
        (if error
          (println "evaluate failed:" error)
          (println "\nevaluate (+ 1 2) =>" (:result body))))

      (finally
        (dap/disconnect! client)))))
