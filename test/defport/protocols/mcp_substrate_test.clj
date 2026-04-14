(ns defport.protocols.mcp-substrate-test
  "Spec-driven substrate tests for MCP.

  Mirrors defport.protocols.lsp-substrate-test and
  defport.protocols.dap-substrate-test. Tests that (a) every
  default method in the adapter is covered by the spec registry,
  (b) roundtrip wire-method ↔ method-name-for, (c) default handler
  symbols resolve to real vars in defport.mcp."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.mcp :as mcp]
            [defport.mcp.spec :as spec]))

(deftest test-spec-covers-adapter-default-methods
  (testing "every wire method the spec names is registered when the adapter boots"
    (let [adapter  (mcp/create-mcp-adapter)
          handlers @(:method-handlers* adapter)
          default-wire-methods (set (keys handlers))]
      (doseq [method-name (spec/all-method-names)
              :let [entry (spec/method-for method-name)
                    wire  (:method entry)
                    handler-sym (:handler-sym entry)]
              :when handler-sym]
        (is (contains? default-wire-methods wire)
            (str "spec method " wire " has handler-sym " handler-sym
                 " but isn't in the adapter's default handlers"))))))

(deftest test-spec-roundtrip
  (testing "method-name-for inverts wire-method for every entry"
    (doseq [mk (spec/all-method-names)]
      (let [wire (spec/wire-method mk)]
        (is (= mk (spec/method-name-for wire))
            (str "method " wire " did not roundtrip"))))))

(deftest test-handler-syms-resolve
  (testing "every :handler-sym in the spec resolves to a real var"
    (doseq [mk (spec/all-method-names)
            :let [entry (spec/method-for mk)
                  sym   (:handler-sym entry)]
            :when sym]
      (is (some? (resolve sym))
          (str "handler-sym " sym " does not resolve to a var")))))

(deftest test-server-initiated-flag
  (testing "roots, sampling, elicitation-create, list_changed notifications are server→client"
    (is (spec/server-initiated? :roots/list))
    (is (spec/server-initiated? :sampling/createMessage))
    (is (spec/server-initiated? :elicitation/create))
    (is (spec/server-initiated? :notifications/tools-list-changed))
    (is (spec/server-initiated? :notifications/prompts-list-changed))
    (is (spec/server-initiated? :notifications/resources-list-changed))
    (is (spec/server-initiated? :notifications/resources-updated))
    (is (spec/server-initiated? :notifications/message))
    (is (not (spec/server-initiated? :tools/list)))
    (is (not (spec/server-initiated? :ping)))))

(deftest test-capabilities-for-collection
  (testing "capabilities-for derives the right cap set from method names"
    (is (= #{:tools :prompts :resources}
           (spec/capabilities-for [:tools/list :prompts/list :resources/list])))
    (is (= #{:tools}
           (spec/capabilities-for [:tools/list :tools/call])))))

(deftest test-wire-method-lookups
  (is (= "initialize"        (spec/wire-method :initialize)))
  (is (= "tools/list"        (spec/wire-method :tools/list)))
  (is (= "tools/call"        (spec/wire-method :tools/call)))
  (is (= "prompts/get"       (spec/wire-method :prompts/get)))
  (is (= "resources/read"    (spec/wire-method :resources/read)))
  (is (= "completion/complete" (spec/wire-method :completion/complete)))
  (is (= "logging/setLevel"  (spec/wire-method :logging/setLevel))))
