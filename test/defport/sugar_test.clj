(ns defport.sugar-test
  "Tests for the unified DSL machinery in defport.sugar.

  Protocol-specific sugar macros (deftool, deflsp, defcommand) are
  thin wrappers around `define-port` and are tested in their
  respective protocol test namespaces. This file covers the
  cross-cutting behavior: argument parsing, schema generation,
  handler binding, and port registration."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.sugar :as sugar :refer [define-port]]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn with-fresh-registry
  "Run body with *registry* bound to a new empty registry.
   Returns [result registry] so tests can inspect both."
  [f]
  (let [r (registry/create-function-registry)]
    (binding [sugar/*registry* r]
      [(f) r])))

;; ============================================================================
;; Argument parsing
;; ============================================================================

(deftest test-parse-define-port-args
  (testing "name + metadata + params + body"
    (let [parsed (sugar/parse-define-port-args 'my-port
                   '({:mcp/tool true} [x :- :int] (+ x 1)))]
      (is (= 'my-port (:name parsed)))
      (is (nil? (:doc parsed)))
      (is (= {} (:options parsed)))
      (is (= {:mcp/tool true} (:metadata parsed)))
      (is (= '[x :- :int] (:params parsed)))
      (is (= '((+ x 1)) (:body parsed)))))

  (testing "name + doc + metadata + params + body"
    (let [parsed (sugar/parse-define-port-args 'my-port
                   '("A port that adds one" {:mcp/tool true} [x :- :int] (+ x 1)))]
      (is (= "A port that adds one" (:doc parsed)))
      (is (= {:mcp/tool true} (:metadata parsed)))))

  (testing "name + options + metadata + params + body"
    (let [parsed (sugar/parse-define-port-args 'my-port
                   '({:tags #{:math}} {:mcp/tool true} [x :- :int] (+ x 1)))]
      (is (= {:tags #{:math}} (:options parsed)))
      (is (= {:mcp/tool true} (:metadata parsed)))))

  (testing "name + doc + options + metadata + params + body (full form)"
    (let [parsed (sugar/parse-define-port-args 'my-port
                   '("doc" {:tags #{:math}} {:mcp/tool true} [x :- :int] (+ x 1)))]
      (is (= "doc" (:doc parsed)))
      (is (= {:tags #{:math}} (:options parsed)))
      (is (= {:mcp/tool true} (:metadata parsed))))))

;; ============================================================================
;; Schema generation
;; ============================================================================

(deftest test-build-schema-form
  (testing "nil params → empty object schema"
    (is (= {:type "object" :properties {} :required []}
           (sugar/build-schema-form nil))))

  (testing "type-annotated params → computed at macroexpansion time"
    (let [schema (sugar/build-schema-form '[x :- :int y :- :string])]
      (is (= "object" (:type schema)))
      (is (= {:type "integer"} (get-in schema [:properties "x"])))
      (is (= {:type "string"} (get-in schema [:properties "y"])))
      (is (= #{"x" "y"} (set (:required schema))))))

  (testing "Malli schema → runtime call"
    (let [form (sugar/build-schema-form '[:map [:x :int] [:y :string]])]
      (is (seq? form))
      (is (= 'defport.sugar/malli->json-schema (first form))))))

;; ============================================================================
;; define-port end-to-end
;; ============================================================================

(deftest test-define-port-simple
  (testing "registers a port with the right shape"
    (let [[_ reg] (with-fresh-registry
                    (fn []
                      (define-port my-tool
                        {:mcp/tool true}
                        [x :- :int]
                        (* x 2))))
          port (core/get-port reg :my-tool)]
      (is (some? port))
      (is (= :my-tool (core/port-id port)))
      (is (= {:type "object"
              :properties {"x" {:type "integer"}}
              :required ["x"]}
             (:input-schema (core/port-schema port)))))))

(deftest test-define-port-with-docstring
  (testing "docstring propagates to port description"
    (let [[_ reg] (with-fresh-registry
                    (fn []
                      (define-port my-tool
                        "Multiplies by two."
                        {:mcp/tool true}
                        [x :- :int]
                        (* x 2))))
          port (core/get-port reg :my-tool)
          port-list (core/list-ports reg)]
      (is (= 1 (count port-list)))
      (is (= "Multiplies by two." (:description (first port-list)))))))

(deftest test-define-port-handler-runs
  (testing "port handler executes with params bound as locals"
    (let [[_ reg] (with-fresh-registry
                    (fn []
                      (define-port adder
                        {:mcp/tool true}
                        [a :- :int b :- :int]
                        (+ a b))))
          port (core/get-port reg :adder)
          result (core/port-execute port {:params {:a 2 :b 3}})]
      (is (= 5 result)))))

(deftest test-define-port-no-params
  (testing "port with no params works"
    (let [[_ reg] (with-fresh-registry
                    (fn []
                      (define-port ping
                        {:mcp/tool true}
                        []
                        :pong)))
          port (core/get-port reg :ping)]
      (is (= :pong (core/port-execute port {:params {}}))))))

(deftest test-define-port-metadata-carries-through
  (testing "metadata from define-port ends up in the port descriptor"
    (let [[_ reg] (with-fresh-registry
                    (fn []
                      (define-port multi-protocol
                        {:mcp/tool true
                         :lsp/method "textDocument/custom"
                         :dap/command "customCommand"}
                        [x :- :int]
                        x)))
          port-def (first (core/list-ports reg))]
      (is (= {:mcp/tool true
              :lsp/method "textDocument/custom"
              :dap/command "customCommand"}
             (:metadata port-def))))))

(deftest test-define-port-context-injection
  (testing "ctx :- :context binds the full context"
    (let [[_ reg] (with-fresh-registry
                    (fn []
                      (define-port with-ctx
                        {:mcp/tool true}
                        [name :- :string ctx :- :context]
                        {:name name :request-id (:request-id ctx)})))
          port (core/get-port reg :with-ctx)
          result (core/port-execute port {:params {:name "alice"}
                                          :request-id "req-123"})]
      (is (= "alice" (:name result)))
      (is (= "req-123" (:request-id result))))))

(deftest test-define-port-options-merged-into-metadata
  (testing "options map merges into metadata"
    (let [[_ reg] (with-fresh-registry
                    (fn []
                      (define-port my-tool
                        {:tags #{:math} :dangerous true}
                        {:mcp/tool true}
                        [x :- :int]
                        x)))
          port-def (first (core/list-ports reg))]
      (is (= {:mcp/tool true :tags #{:math} :dangerous true}
             (:metadata port-def))))))

(deftest test-define-port-multi-protocol-single-definition
  (testing "one port can be defined for multiple protocols simultaneously"
    (let [[_ reg] (with-fresh-registry
                    (fn []
                      (define-port find-references
                        "Find all references to a symbol."
                        {:mcp/tool true
                         :lsp/method "textDocument/references"}
                        [symbol :- :string]
                        [{:file "example.clj" :line 42 :column 10 :symbol symbol}])))
          port-def (first (core/list-ports reg))]
      ;; Same port surfaces in both protocols via metadata
      (is (= true (get-in port-def [:metadata :mcp/tool])))
      (is (= "textDocument/references" (get-in port-def [:metadata :lsp/method]))))))

;; ============================================================================
;; Adapter multimethod
;; ============================================================================

(deftest test-create-adapter-default-throws
  (testing "create-adapter for unknown protocol raises a clear error"
    (is (thrown-with-msg? Exception #"No adapter registered"
          (sugar/create-adapter :unknown {})))))
