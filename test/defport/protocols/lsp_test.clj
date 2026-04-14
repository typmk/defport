(ns defport.protocols.lsp-test
  "LSP adapter tests — end-to-end from deflsp/register-ports! through
  protocol-dispatch. No real editor, no transport; exercises the
  adapter in isolation with a fresh sugar registry."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.core :as core]
            [defport.lsp :as lsp]
            [defport.lsp.spec :as spec]
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

;; ============================================================================
;; State machinery: cancellation, progress, initialize
;; ============================================================================

(deftest test-cancel-request-flips-cancelled-flag
  (testing "$/cancelRequest marks the targeted request-id as cancelled"
    (let [reg (fresh-registry)
          adapter (build-adapter reg)
          state* (lsp/adapter-state adapter)]
      (lsp/register-operation state* "req-7")
      (is (not (lsp/cancelled? state* "req-7")))
      (core/protocol-dispatch adapter "$/cancelRequest" {:id "req-7"} {})
      (is (lsp/cancelled? state* "req-7"))
      (is (nil? (core/protocol-dispatch adapter "$/cancelRequest"
                                        {:id "req-7"} {}))
          "$/cancelRequest is a notification, dispatch returns nil"))))

(deftest test-progress-notification-is-passthrough
  (testing "$/progress dispatches without error and returns nil"
    (let [reg (fresh-registry)
          adapter (build-adapter reg)
          result (core/protocol-dispatch adapter "$/progress"
                   {:token "t1" :value {:kind "begin"}} {})]
      (is (nil? result)))))

(deftest test-handler-context-carries-state-and-request-id
  (testing "handler context includes :state* and :request-id"
    (let [reg (fresh-registry)
          captured (atom nil)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp hover
          [uri :- :string line :- :int col :- :int ctx :- :context]
          (reset! captured ctx)
          {:ok true}))
      (let [adapter (build-adapter reg)]
        (core/protocol-dispatch adapter "textDocument/hover"
          {:textDocument {:uri "file:///s.clj"} :position {:line 0 :character 0}}
          {:id "req-42"})
        (is (some? (:state* @captured)))
        (is (= "req-42" (:request-id @captured)))))))

(deftest test-operation-unregistered-after-handler-returns
  (testing "active-operations set is clean after dispatch returns"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp hover
          [uri :- :string line :- :int col :- :int]
          {:contents {:kind "plaintext" :value "x"}}))
      (let [adapter (build-adapter reg)
            state* (lsp/adapter-state adapter)]
        (core/protocol-dispatch adapter "textDocument/hover"
          {:textDocument {:uri "file:///s.clj"} :position {:line 0 :character 0}}
          {:id "req-99"})
        (is (empty? (:active-operations @state*)))))))

(deftest test-initialize-stores-client-capabilities
  (testing "default-initialize-handler stores root + capabilities in state"
    (let [reg (fresh-registry)
          adapter (build-adapter reg)
          state* (lsp/adapter-state adapter)]
      (lsp/register-lifecycle-handlers! adapter)
      (core/protocol-dispatch adapter "initialize"
        {:rootUri "file:///proj" :capabilities {:textDocument {:hover true}}}
        {})
      (is (true? (:initialized @state*)))
      (is (= "file:///proj" (:root-uri @state*)))
      (is (= {:textDocument {:hover true}} (:client-capabilities @state*))))))

;; ============================================================================
;; Auto-derived capabilities
;; ============================================================================

;; ============================================================================
;; didSave handling
;; ============================================================================

(deftest test-did-save-notification-without-text
  (testing "didSave is dispatched as a notification, no error"
    (let [reg (fresh-registry)
          adapter (build-adapter reg)]
      (lsp/register-document-sync-handlers! adapter)
      (let [result (core/protocol-dispatch adapter "textDocument/didSave"
                     {:textDocument {:uri "file:///a.clj"}} {})]
        (is (nil? result))))))

(deftest test-did-save-with-include-text-syncs-document
  (testing "when the client sends :text, the document store is updated"
    (let [reg (fresh-registry)
          adapter (build-adapter reg)
          doc-store (:document-store adapter)]
      (lsp/register-document-sync-handlers! adapter)
      ;; Open first so the store has a doc
      (core/protocol-dispatch adapter "textDocument/didOpen"
        {:textDocument {:uri "file:///b.clj"
                        :languageId "clojure"
                        :version 1
                        :text "(ns old)"}} {})
      (core/protocol-dispatch adapter "textDocument/didSave"
        {:textDocument {:uri "file:///b.clj" :version 2}
         :text "(ns new)"} {})
      (let [doc (lsp/doc-get doc-store "file:///b.clj")]
        (is (= "(ns new)" (:content doc)))))))

(deftest test-capabilities-auto-derive-from-registry
  (testing "deflsp hover + references implies hoverProvider + referencesProvider"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp hover [uri :- :string line :- :int col :- :int]
          {:contents {:kind "plaintext" :value "x"}})
        (lsp/deflsp references [uri :- :string line :- :int col :- :int]
          []))
      (let [caps (lsp/capabilities-from-registry reg)]
        (is (contains? caps :hoverProvider))
        (is (contains? caps :referencesProvider))
        (is (not (contains? caps :renameProvider)))))))

(deftest test-create-adapter-reports-derived-capabilities
  (testing "sugar/create-adapter :lsp merges derived caps with user caps"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp document-symbol [uri :- :string] []))
      (let [adapter (build-adapter reg)
            caps (core/protocol-capabilities adapter reg)]
        (is (contains? caps :documentSymbolProvider))))))

(deftest test-initialize-returns-derived-capabilities
  (testing "initialize response reflects derived providers"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp rename
          [uri :- :string line :- :int col :- :int new-name :- :string]
          {:changes {}}))
      (let [adapter (build-adapter reg)
            _ (lsp/register-lifecycle-handlers! adapter)
            result (core/protocol-dispatch adapter "initialize"
                     {:rootUri "file:///proj" :capabilities {}}
                     {:port-registry reg})]
        (is (contains? (:capabilities result) :renameProvider))))))

;; ============================================================================
;; Spec-registry substrate
;; ============================================================================

(deftest test-spec-registry-is-source-of-truth
  (testing "every method we route through register-ports! resolves through spec"
    (is (= "textDocument/hover" (spec/wire-method :hover)))
    (is (= "textDocument/references" (spec/wire-method :references)))
    (is (= "workspace/symbol" (spec/wire-method :workspace-symbol)))
    (is (= :hoverProvider (spec/capability-key :hover)))
    (is (= :referencesProvider (spec/capability-key :references)))
    (is (= :workspaceSymbolProvider (spec/capability-key :workspace-symbol)))
    (is (spec/request? :hover))
    (is (spec/notification? :did-open))
    (is (spec/notification? :cancel-request))))

(deftest test-spec-defaults-derive-empty-server
  (testing "spec defaults give sensible empty responses with no ports registered"
    (is (nil? ((spec/default-response :hover) {})))
    (is (= [] ((spec/default-response :references) {})))
    (is (= [] ((spec/default-response :document-symbol) {})))
    (is (nil? ((spec/default-response :rename) {})))))

(deftest test-handler-name-roundtrip-via-spec
  (testing "spec/handler-name-for inverts spec/wire-method"
    (doseq [hk (spec/all-handler-names)]
      (let [wire (spec/wire-method hk)]
        (is (= hk (spec/handler-name-for wire))
            (str "method " wire " did not roundtrip through handler-name-for"))))))

(deftest test-deflsp-uses-spec-sugar-shape
  (testing "deflsp respects sugar shape declared in spec — code-action uses :range"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp code-action
          [uri :- :string range :- :map]
          {:got-uri uri :got-range range}))
      (let [adapter (build-adapter reg)
            rng     {:start {:line 1 :character 0} :end {:line 4 :character 10}}
            result  (dispatch adapter "textDocument/codeAction"
                              {:textDocument {:uri "file:///c.clj"}
                               :range rng})]
        (is (= {:got-uri "file:///c.clj" :got-range rng} result))))))

(deftest test-substrate-stress-semantic-tokens
  (testing "semantic-tokens (document shape, unusual response) routes via spec"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp semantic-tokens
          [uri :- :string]
          {:data [0 0 5 1 0   ;; (deltaLine deltaStart length type modifiers) × N
                  1 2 3 2 0]}))
      (let [adapter (build-adapter reg)
            result  (dispatch adapter "textDocument/semanticTokens/full"
                              {:textDocument {:uri "file:///s.clj"}})]
        (is (= 10 (count (:data result))))
        (let [caps (lsp/capabilities-from-registry reg)]
          (is (contains? caps :semanticTokensProvider)))))))

(deftest test-substrate-stress-call-hierarchy
  (testing "call-hierarchy methods (raw shape, multi-step) route via spec"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp call-hierarchy-prepare
          [uri :- :string line :- :int col :- :int]
          [{:name "foo" :kind 12 :uri uri
            :range {:start {:line line :character col}
                    :end {:line line :character (+ col 3)}}}])
        (lsp/deflsp call-hierarchy-incoming
          [item :- :map]
          [{:from item :fromRanges []}]))
      (let [adapter (build-adapter reg)
            prep    (dispatch adapter "textDocument/prepareCallHierarchy"
                              {:textDocument {:uri "file:///c.clj"}
                               :position {:line 5 :character 2}})
            item    (first prep)]
        (is (= "foo" (:name item)))
        (let [incoming (dispatch adapter "callHierarchy/incomingCalls"
                                 {:item item})]
          (is (= "foo" (get-in (first incoming) [:from :name]))))
        (let [caps (lsp/capabilities-from-registry reg)]
          (is (contains? caps :callHierarchyProvider)))))))

(deftest test-capabilities-derive-via-spec-only
  (testing "capabilities-from-registry resolves through spec, not a private table"
    (let [reg (fresh-registry)]
      (binding [sugar/*registry* reg]
        (lsp/deflsp folding-range [uri :- :string] [])
        (lsp/deflsp signature-help [uri :- :string line :- :int col :- :int] nil))
      (let [caps (lsp/capabilities-from-registry reg)]
        (is (contains? caps :foldingRangeProvider))
        (is (contains? caps :signatureHelpProvider))
        (is (not (contains? caps :hoverProvider)))))))

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
