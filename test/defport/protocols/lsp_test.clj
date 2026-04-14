(ns defport.protocols.lsp-test
  "LSP adapter tests — end-to-end from deflsp/register-ports! through
  protocol-dispatch. No real editor, no transport; exercises the
  adapter in isolation with a fresh sugar registry."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.lsp :as lsp]
            [defport.registry :as registry]
            [defport.sugar :as sugar]))

;; ============================================================================
;; Helpers
;; ============================================================================

(defn- fresh-registry [] (registry/create-function-registry))

(defn- build-adapter [reg]
  (sugar/create-adapter :lsp
    {:server-info {:name "test-lsp" :version "0.0.0"}
     :registry reg}))

(defn- dispatch [adapter method params]
  (core/protocol-dispatch adapter method params {}))

;; ============================================================================
;; Sugar → adapter → protocol-dispatch end-to-end
;; ============================================================================

(deftest test-deflsp-dispatches-via-adapter
  (testing "deflsp hover routes through protocol-dispatch to the port handler"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp hover
          [uri :- :string line :- :int col :- :int]
          {:contents {:kind "markdown"
                      :value (str uri "@" line ":" col)}}))
      (let [adapter (build-adapter reg)
            result (dispatch adapter "textDocument/hover"
                     {:textDocument {:uri "file:///a.clj"}
                      :position {:line 12 :character 4}})]
        (is (= {:contents {:kind "markdown" :value "file:///a.clj@12:4"}}
               result))))))

(deftest test-deflsp-references-returns-location-vector
  (testing "references handler returns a plain data vector"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp references
          [uri :- :string line :- :int col :- :int]
          [{:uri uri :range {:start {:line line :character col}
                             :end   {:line line :character col}}}]))
      (let [adapter (build-adapter reg)
            result (dispatch adapter "textDocument/references"
                     {:textDocument {:uri "file:///b.clj"}
                      :position {:line 1 :character 2}})]
        (is (vector? result))
        (is (= "file:///b.clj" (:uri (first result))))))))

(deftest test-deflsp-document-symbol-dispatches
  (testing "document-symbol handler gets just the uri"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp document-symbol
          [uri :- :string]
          [{:name "root" :uri uri}]))
      (let [adapter (build-adapter reg)
            result (dispatch adapter "textDocument/documentSymbol"
                     {:textDocument {:uri "file:///c.clj"}})]
        (is (= "file:///c.clj" (:uri (first result))))))))

(deftest test-deflsp-workspace-symbol-dispatches
  (testing "workspace-symbol routes via workspace/symbol method"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp workspace-symbol
          [query :- :string]
          [{:name query}]))
      (let [adapter (build-adapter reg)
            result (dispatch adapter "workspace/symbol" {:query "foo"})]
        (is (= [{:name "foo"}] result))))))

(deftest test-deflsp-rename-dispatches
  (testing "rename handler receives uri/line/col/new-name"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp rename
          [uri :- :string line :- :int col :- :int new-name :- :string]
          {:changes {uri [{:range {:start {:line line :character col}
                                   :end {:line line :character (+ col 3)}}
                           :newText new-name}]}}))
      (let [adapter (build-adapter reg)
            result (dispatch adapter "textDocument/rename"
                     {:textDocument {:uri "file:///d.clj"}
                      :position {:line 5 :character 10}
                      :newName "bar"})]
        (is (= "bar"
               (get-in result [:changes "file:///d.clj" 0 :newText])))))))

;; ============================================================================
;; Method-not-found path
;; ============================================================================

(deftest test-unknown-method-returns-error
  (testing "dispatching a method nobody registered returns method-not-found"
    (let [reg (fresh-registry)
          adapter (build-adapter reg)
          result (dispatch adapter "textDocument/hover"
                   {:textDocument {:uri "file:///x.clj"}
                    :position {:line 0 :character 0}})]
      (is (= -32601 (:code result))))))

;; ============================================================================
;; Handler-raised exception propagates as internal-error
;; ============================================================================

(deftest test-handler-exception-becomes-internal-error
  (testing "an exception in the user's handler body is caught and returned"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp hover
          [uri :- :string line :- :int col :- :int]
          (throw (ex-info "boom" {:uri uri}))))
      (let [adapter (build-adapter reg)
            result (dispatch adapter "textDocument/hover"
                     {:textDocument {:uri "file:///e.clj"}
                      :position {:line 0 :character 0}})]
        (is (= -32603 (:code result)))))))

;; ============================================================================
;; Legacy :lsp {:method ...} port still registers alongside sugar ports
;; ============================================================================

(deftest test-legacy-lsp-metadata-still-routes
  (testing "a port with nested :lsp {:method ...} metadata routes through
            the legacy create-port-handler wrapper"
    (let [reg (fresh-registry)]
      (core/register-port! reg
        {:id :legacy-hover
         :name "legacy-hover"
         :description "legacy-style hover"
         :input-schema {:type "object"}
         :handler (fn [ctx]
                    {:result {:function (get-in ctx [:params :file])
                              :callers 3}})
         :metadata {:lsp {:method "textDocument/hover"
                          :transform :hover}}})
      (let [adapter (build-adapter reg)
            result (dispatch adapter "textDocument/hover"
                     {:textDocument {:uri "file:///legacy.clj"}
                      :position {:line 0 :character 0}})]
        (is (contains? result :contents))))))
