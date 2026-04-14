(ns defport.protocols.lsp-sugar-test
  "Tests for the LSP sugar DSL (deflsp / defhandler).

  Scoped to port registration + direct handler invocation with a
  simulated LSP context. Full adapter-dispatch wiring (register-ports!
  end-to-end via protocol-dispatch) is covered by the LSP core-features
  pass."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.lsp :as lsp]
            [defport.registry :as registry]
            [defport.sugar :as sugar]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- fresh-registry [] (registry/create-function-registry))

(defn- invoke [port raw-params]
  (core/port-execute port {:params raw-params}))

(defn- invoke-with-ctx [port raw-params ctx]
  (core/port-execute port (assoc ctx :params raw-params)))

;; ============================================================================
;; Port registration
;; ============================================================================

(deftest test-deflsp-position-method-registers
  (testing "deflsp hover stamps :lsp/method and registers into *registry*"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp hover
          [uri :- :string line :- :int col :- :int]
          "Return hover info at position."
          {:contents {:kind "markdown" :value (str uri ":" line ":" col)}}))
      (let [port (core/get-port reg :hover)
            port-def (first (core/list-ports reg))]
        (is (some? port))
        (is (= :hover (core/port-id port)))
        (is (= "Return hover info at position." (:description port-def)))
        (is (= "textDocument/hover"
               (get-in port-def [:metadata :lsp/method])))))))

(deftest test-deflsp-unknown-method-throws
  (testing "deflsp with an unknown method keyword fails at macroexpansion"
    (is (thrown? Exception
          (eval '(defport.lsp/deflsp not-a-real-method
                   [uri :- :string]
                   uri))))))

;; ============================================================================
;; Position-method extraction (hover / definition / references)
;; ============================================================================

(deftest test-deflsp-hover-extracts-position
  (testing "hover handler pulls uri/line/col out of raw LSP params"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp hover
          [uri :- :string line :- :int col :- :int]
          {:uri uri :line line :col col}))
      (let [port (core/get-port reg :hover)
            result (invoke port {:textDocument {:uri "file:///a.clj"}
                                 :position {:line 10 :character 3}})]
        (is (= {:uri "file:///a.clj" :line 10 :col 3} result))))))

(deftest test-deflsp-references-extracts-position
  (testing "references handler gets the same position shape as hover"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp references
          [uri :- :string line :- :int col :- :int]
          [{:uri uri :range {:start {:line line :character col}
                             :end   {:line line :character col}}}]))
      (let [port (core/get-port reg :references)
            result (invoke port {:textDocument {:uri "file:///b.clj"}
                                 :position {:line 5 :character 12}})]
        (is (= "textDocument/references"
               (get-in (first (core/list-ports reg)) [:metadata :lsp/method])))
        (is (= 1 (count result)))
        (is (= "file:///b.clj" (:uri (first result))))))))

;; ============================================================================
;; Range / document / workspace-symbol / rename shapes
;; ============================================================================

(deftest test-deflsp-range-method-extracts-range
  (testing "code-action handler receives uri + range"
    (let [reg (fresh-registry)
          rng {:start {:line 1 :character 0} :end {:line 4 :character 10}}]
      (binding [sugar/*registry* reg]
        (lsp/deflsp code-action
          [uri :- :string range :- :map]
          {:uri uri :range range}))
      (let [port (core/get-port reg :code-action)
            result (invoke port {:textDocument {:uri "file:///c.clj"}
                                 :range rng})]
        (is (= "textDocument/codeAction"
               (get-in (first (core/list-ports reg)) [:metadata :lsp/method])))
        (is (= {:uri "file:///c.clj" :range rng} result))))))

(deftest test-deflsp-document-method-extracts-uri
  (testing "document-symbol handler receives just the uri"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp document-symbol
          [uri :- :string]
          [{:name "f" :uri uri}]))
      (let [port (core/get-port reg :document-symbol)
            result (invoke port {:textDocument {:uri "file:///d.clj"}})]
        (is (= "textDocument/documentSymbol"
               (get-in (first (core/list-ports reg)) [:metadata :lsp/method])))
        (is (= "file:///d.clj" (:uri (first result))))))))

(deftest test-deflsp-workspace-symbol-extracts-query
  (testing "workspace-symbol handler receives query"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp workspace-symbol
          [query :- :string]
          [{:name query}]))
      (let [port (core/get-port reg :workspace-symbol)
            result (invoke port {:query "foo"})]
        (is (= "workspace/symbol"
               (get-in (first (core/list-ports reg)) [:metadata :lsp/method])))
        (is (= [{:name "foo"}] result))))))

(deftest test-deflsp-rename-extracts-position-and-new-name
  (testing "rename handler receives uri/line/col/new-name"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp rename
          [uri :- :string line :- :int col :- :int new-name :- :string]
          {:uri uri :line line :col col :new-name new-name}))
      (let [port (core/get-port reg :rename)
            result (invoke port {:textDocument {:uri "file:///e.clj"}
                                 :position {:line 2 :character 7}
                                 :newName "bar"})]
        (is (= {:uri "file:///e.clj" :line 2 :col 7 :new-name "bar"}
               result))))))

;; ============================================================================
;; Context injection
;; ============================================================================

(deftest test-deflsp-context-injection
  (testing "ctx :- :context binds the full handler context"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp hover
          [uri :- :string line :- :int col :- :int ctx :- :context]
          {:uri uri :request-id (:request-id ctx)}))
      (let [port (core/get-port reg :hover)
            result (invoke-with-ctx port
                     {:textDocument {:uri "file:///f.clj"}
                      :position {:line 0 :character 0}}
                     {:request-id "req-42"})]
        (is (= "file:///f.clj" (:uri result)))
        (is (= "req-42" (:request-id result)))))))

;; ============================================================================
;; defhandler (arbitrary method string)
;; ============================================================================

(deftest test-defhandler-registers-with-custom-method
  (testing "defhandler stamps an arbitrary :lsp/method and passes raw params"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/defhandler "textDocument/semanticTokens/full"
          [params :- :map]
          {:data [1 2 3]}))
      (let [port-def (first (core/list-ports reg))]
        (is (= "textDocument/semanticTokens/full"
               (get-in port-def [:metadata :lsp/method])))))))

;; ============================================================================
;; Adapter multimethod
;; ============================================================================

(deftest test-create-adapter-lsp-dispatches
  (testing "sugar/create-adapter :lsp returns an LspAdapter"
    (let [adapter (sugar/create-adapter :lsp {:server-info {:name "t" :version "0"}})]
      (is (some? adapter))
      (is (= "t" (get-in adapter [:server-info :name]))))))
