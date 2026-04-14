(ns dap-server
  "Minimum-viable DAP server using defport.

  Exposes a no-op debugger: initialize succeeds, launch is accepted,
  threads returns one thread, evaluate echoes the expression back.
  Just enough to handshake with a real DAP client and prove the wire
  format works.

  Defport supplies: every DAP command defport.dap.spec knows about
  gets a sensible default (empty body or error body via
  :error-default). This example overrides only the three commands
  with real logic.

  ## Run

      clojure -M:examples -m dap-server

  ## Validate with nvim-dap

      require('dap').adapters['defport-example'] = {
        type = 'executable',
        command = 'clojure',
        args = {'-M:examples', '-m', 'dap-server'},
      }"
  (:require [defport.dap :as dap]
            [defport.sugar :as sugar]))

(dap/defcommand threads
  "List threads in the debuggee."
  []
  {:threads [{:id 1 :name "main"}]})

(dap/defcommand evaluate
  "Evaluate an expression in the current frame's context."
  [expression :- :string frameId :- :int]
  {:result (str "echo: " expression) :variablesReference 0})

(dap/defcommand stack-trace
  "Return a trivial one-frame stack."
  [thread-id :- :int]
  {:stackFrames [{:id 1 :name "main" :line 1 :column 0}]
   :totalFrames 1})

(defn -main [& _]
  (sugar/run! {:protocol :dap
               :server-info {:name "defport-example-dap"
                             :version "0.1.0"}
               :backend :repl}))
