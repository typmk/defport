(ns dap-server
  "Minimum-viable DAP server using defport.

  Exposes a no-op debugger: initialize succeeds, launch is accepted,
  threads returns one thread, evaluate echoes the expression back.
  Just enough to handshake with a real DAP client (VS Code, nvim-dap)
  and prove the wire format is correct.

  Defport supplies: every command defport.dap.spec knows about
  gets a sensible default (empty body or error body via
  :error-default). This example only overrides the three
  commands that need real logic.

  ## Run

      clojure -M:examples -m dap-server

  ## Validate with VS Code launch.json

      {
        \"version\": \"0.2.0\",
        \"configurations\": [{
          \"type\": \"defport-example\",
          \"request\": \"launch\",
          \"name\": \"Defport example adapter\",
          \"program\": \"${file}\",
          \"debugAdapter\": {
            \"command\": \"clojure\",
            \"args\": [\"-M:examples\", \"-m\", \"dap-server\"]}}]
      }

  The `type` needs to be registered via a VS Code extension. For
  pure validation without an extension, use nvim-dap instead."
  (:require [defport.core :as core]
            [defport.dap :as dap]
            [defport.sugar :as sugar]
            [defport.transports.stdio :as stdio]))

;; ----- Command definitions --------------------------------------------------
;;
;; `defcommand` reads the sugar shape from defport.dap.spec at
;; macroexpansion time. :thread-shape commands re-key :threadId → :thread-id
;; automatically before binding the user's named parameters.

(dap/defcommand threads
  "List threads in the debuggee."
  []
  {:threads [{:id 1 :name "main"}]})

(dap/defcommand evaluate
  "Evaluate an expression in the current frame's context."
  [expression :- :string frameId :- :int]
  {:result (str "echo: " expression)
   :variablesReference 0})

(dap/defcommand stack-trace
  "Return a trivial one-frame stack."
  [thread-id :- :int]
  {:stackFrames [{:id 1 :name "main" :line 1 :column 0}]
   :totalFrames 1})

;; ----- Main -----------------------------------------------------------------

(defn -main [& _]
  (let [adapter   (sugar/create-adapter :dap
                    {:server-info {:name "defport-example-dap"
                                   :version "0.1.0"}
                     :backend :repl})
        transport (stdio/create-stdio-transport {:drain-on-exit? true})
        handler   (fn [msg]
                    ;; DAP messages have :command / :arguments / :seq /
                    ;; :type instead of JSON-RPC's :method / :params /
                    ;; :id. protocol-dispatch consumes the raw msg and
                    ;; returns a body; we wrap it as a response with
                    ;; matching request_seq.
                    (let [result (core/protocol-dispatch adapter
                                                         (:command msg)
                                                         msg
                                                         {})]
                      (when (:seq msg)
                        {:seq 0
                         :type "response"
                         :request_seq (:seq msg)
                         :success true
                         :command (:command msg)
                         :body (if (and (map? result) (contains? result :result))
                                 (:result result)
                                 result)})))]
    (core/transport-start transport handler)))
