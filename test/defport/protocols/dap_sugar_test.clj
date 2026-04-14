(ns defport.protocols.dap-sugar-test
  "Tests for the DAP sugar DSL (defcommand).

  Scoped to port registration + direct handler invocation. Full
  command-dispatch wiring is Step 4 (DAP honest-done pass) work."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.dap :as dap]
            [defport.registry :as registry]
            [defport.sugar :as sugar]))

(defn- fresh-registry [] (registry/create-function-registry))

(deftest test-defcommand-registers-with-dap-metadata
  (testing "defcommand stamps :dap/command and registers into *registry*"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (dap/defcommand evaluate
          "Evaluate an expression."
          [expression :- :string frameId :- :int]
          {:result (str "=> " expression) :variablesReference 0}))
      (let [port-def (first (core/list-ports reg))]
        (is (= :evaluate (:id port-def)))
        (is (= "Evaluate an expression." (:description port-def)))
        (is (= "evaluate" (get-in port-def [:metadata :dap/command])))))))

(deftest test-defcommand-handler-executes
  (testing "defcommand handler binds params from arguments"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (dap/defcommand evaluate
          [expression :- :string frameId :- :int]
          {:result (str expression "@" frameId)}))
      (let [port (core/get-port reg :evaluate)
            result (core/port-execute port
                     {:params {:expression "(+ 1 2)" :frameId 7}})]
        (is (= {:result "(+ 1 2)@7"} result))))))

(deftest test-defcommand-options-merge
  (testing "options map merges into metadata"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (dap/defcommand set-breakpoints
          {:dangerous true}
          [source :- :map breakpoints :- :vector]
          {:breakpoints breakpoints}))
      (let [port-def (first (core/list-ports reg))]
        (is (= "set-breakpoints" (get-in port-def [:metadata :dap/command])))
        (is (= true (get-in port-def [:metadata :dangerous])))))))

(deftest test-create-adapter-dap-dispatches
  (testing "sugar/create-adapter :dap returns a DapAdapter"
    (let [adapter (sugar/create-adapter :dap
                    {:server-info {:name "t" :version "0"} :backend :repl})]
      (is (some? adapter))
      (is (= :dap (core/protocol-id adapter))))))
