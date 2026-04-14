(ns defport.protocols.dap-substrate-test
  "DAP adapter tests for the spec-registry substrate.

  Tests defport.dap.spec as the source of truth, the spec-driven
  defcommand wire-name resolution, capability auto-derivation, and
  end-to-end port routing through protocol-dispatch.

  The legacy isolation tests in dap_test.cljc still cover handler
  semantics; this file specifically exercises the substrate path."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.dap :as dap]
            [defport.dap.spec :as spec]
            [defport.registry :as registry]
            [defport.sugar :as sugar]))

(defn- fresh-registry [] (registry/create-function-registry))

;; ============================================================================
;; Spec registry as source of truth
;; ============================================================================

(deftest test-spec-resolves-wire-commands
  (testing "kebab-case command-name keywords resolve to camelCase wire strings"
    (is (= "stepIn"          (spec/wire-command :step-in)))
    (is (= "stepOut"         (spec/wire-command :step-out)))
    (is (= "setBreakpoints"  (spec/wire-command :set-breakpoints)))
    (is (= "stackTrace"      (spec/wire-command :stack-trace)))
    (is (= "evaluate"        (spec/wire-command :evaluate)))
    (is (= "configurationDone" (spec/wire-command :configuration-done)))))

(deftest test-spec-roundtrip
  (testing "command-name-for inverts wire-command for every entry"
    (doseq [cmd-key (spec/all-command-names)]
      (let [wire (spec/wire-command cmd-key)]
        (is (= cmd-key (spec/command-name-for wire))
            (str "command " wire " did not roundtrip"))))))

(deftest test-spec-defaults
  (testing "defaults give sensible empty responses for unimplemented commands"
    (is (= [] (:threads ((spec/default-response :threads) {}))))
    (is (= [] (:stackFrames ((spec/default-response :stack-trace) {}))))
    (is (= 0  (:totalFrames ((spec/default-response :stack-trace) {}))))
    (is (= [] (:scopes ((spec/default-response :scopes) {}))))
    (is (= [] (:variables ((spec/default-response :variables) {}))))))

(deftest test-server-initiated-flag
  (testing "runInTerminal and startDebugging are server→client"
    (is (spec/server-initiated? :run-in-terminal))
    (is (spec/server-initiated? :start-debugging))
    (is (not (spec/server-initiated? :evaluate)))
    (is (not (spec/server-initiated? :stack-trace)))))

;; ============================================================================
;; defcommand uses spec for wire names
;; ============================================================================

(deftest test-defcommand-uses-spec-wire-name
  (testing "kebab-case (defcommand step-in ...) → :dap/command \"stepIn\""
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (dap/defcommand step-in
          [thread-id :- :int]
          {:allThreadsContinued false}))
      (let [port-def (first (core/list-ports reg))]
        (is (= "stepIn" (get-in port-def [:metadata :dap/command])))))))

(deftest test-defcommand-falls-back-for-custom-commands
  (testing "commands not in spec keep the literal kebab name"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (dap/defcommand my-custom-cmd
          [arg :- :string]
          {:ok true}))
      (let [port-def (first (core/list-ports reg))]
        (is (= "my-custom-cmd" (get-in port-def [:metadata :dap/command])))))))

;; ============================================================================
;; Auto-derived capabilities
;; ============================================================================

(deftest test-capabilities-derive-from-ports
  (testing "registered ports lift their capability flags via spec"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (dap/defcommand step-back     [thread-id :- :int] {})
        (dap/defcommand restart-frame [frame-id  :- :int] {})
        (dap/defcommand completions   [text :- :string] {:targets []}))
      (let [adapter (sugar/create-adapter :dap
                      {:server-info {:name "t" :version "0"}
                       :registry reg})
            caps (core/protocol-capabilities adapter reg)]
        (is (= true (:supportsStepBack caps)))
        (is (= true (:supportsRestartFrame caps)))
        (is (= true (:supportsCompletionsRequest caps)))))))

;; ============================================================================
;; End-to-end: port-routed dispatch
;; ============================================================================

(deftest test-port-routed-dispatch-overrides-default
  (testing "a registered port wins over the legacy handle-request fallback"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (dap/defcommand threads
          []
          {:threads [{:id 1 :name "user-thread"}
                     {:id 2 :name "gc"}]}))
      (let [adapter (sugar/create-adapter :dap
                      {:server-info {:name "t" :version "0"}
                       :registry reg})
            resp (core/protocol-dispatch adapter "threads" {:arguments {}}
                   {:port-registry reg})
            body (if (and (map? resp) (contains? resp :result)) (:result resp) resp)]
        (is (= 2 (count (:threads body))))))))

(deftest test-unimplemented-command-degrades-via-spec-default
  (testing "a command with no port and no legacy handler returns a spec default"
    (let [reg (fresh-registry)
          adapter (sugar/create-adapter :dap
                    {:server-info {:name "t" :version "0"}
                     :registry reg})
          resp (core/protocol-dispatch adapter "stepInTargets"
                 {:arguments {:frameId 1}}
                 {:port-registry reg})
          body (if (and (map? resp) (contains? resp :result)) (:result resp) resp)]
      ;; Spec default for :step-in-targets is {:targets []}
      (is (= [] (:targets body))))))

(deftest test-error-default-fires-for-stepping-without-port
  (testing "stepIn with no port returns the spec :error-default shape"
    (let [reg (fresh-registry)
          adapter (sugar/create-adapter :dap
                    {:server-info {:name "t" :version "0"}
                     :registry reg})
          resp (core/protocol-dispatch adapter "stepIn"
                 {:arguments {:threadId 1}}
                 {:port-registry reg})
          body (if (and (map? resp) (contains? resp :result)) (:result resp) resp)]
      (is (false? (:success body)))
      (is (re-find #"Stepping not supported" (:message body))))))

(deftest test-error-default-overridden-by-port
  (testing "registering a port for stepIn replaces the :error-default"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (dap/defcommand step-in
          [thread-id :- :int]
          {:allThreadsContinued false :stoppedAtNew true}))
      (let [adapter (sugar/create-adapter :dap
                      {:server-info {:name "t" :version "0"}
                       :registry reg})
            resp (core/protocol-dispatch adapter "stepIn"
                   {:arguments {:threadId 1}}
                   {:port-registry reg})
            body (if (and (map? resp) (contains? resp :result)) (:result resp) resp)]
        (is (true? (:stoppedAtNew body)))))))
