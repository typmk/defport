(ns defport.protocols.dap-test
  "Tests for DAP protocol adapter."
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as str]
            [defport.dap :as dap]
            [defport.core :as core]
            [defport.registry :as registry]))

;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn create-test-context
  "Create a context map for testing."
  [adapter & [opts]]
  (merge {:adapter-state (:adapter-state adapter)
          :backend-type (:backend-type adapter)
          :backend-opts (:backend-opts adapter)
          :server-info (:server-info adapter)
          :port-registry nil
          :transport nil}
         opts))

(defn dispatch-command
  "Dispatch a DAP command and return result.

  protocol-dispatch wraps non-success responses in {:result ...}; this helper
  unwraps that wrapper so tests can access response fields directly."
  [adapter command args & [ctx-opts]]
  (let [resp (core/protocol-dispatch adapter command {:arguments args}
               (create-test-context adapter ctx-opts))]
    (if (and (map? resp) (contains? resp :result) (not (contains? resp :error)))
      (:result resp)
      resp)))

;; ============================================================================
;; Adapter Creation Tests
;; ============================================================================

(deftest create-adapter-test
  (testing "Creates adapter with defaults"
    (let [adapter (dap/create-dap-adapter)]
      (is (= :dap (core/protocol-id adapter)))
      (is (= dap/dap-version (core/protocol-version adapter)))
      (is (= :repl (:backend-type adapter)))
      (is (some? (:adapter-state adapter)))))

  (testing "Creates adapter with custom options"
    (let [adapter (dap/create-dap-adapter
                   {:server-info {:name "test-server" :version "1.0.0"}
                    :backend :flowstorm
                    :backend-opts {:host "localhost" :port 7722}})]
      (is (= :flowstorm (:backend-type adapter)))
      (is (= {:name "test-server" :version "1.0.0"} (:server-info adapter)))
      (is (= {:host "localhost" :port 7722} (:backend-opts adapter))))))

;; ============================================================================
;; Capability Tests
;; ============================================================================

(deftest capabilities-test
  (testing "REPL mode capabilities"
    (let [adapter (dap/create-dap-adapter {:backend :repl})
          caps (core/protocol-capabilities adapter nil)]
      (is (true? (:supportsConfigurationDoneRequest caps)))
      (is (true? (:supportsEvaluateForHovers caps)))
      (is (true? (:supportsCompletionsRequest caps)))
      (is (false? (:supportsStepBack caps)))
      (is (false? (:supportsFunctionBreakpoints caps)))))

  (testing "FlowStorm mode capabilities"
    (let [adapter (dap/create-dap-adapter {:backend :flowstorm})
          caps (core/protocol-capabilities adapter nil)]
      (is (true? (:supportsStepBack caps)) "FlowStorm supports time-travel")
      (is (true? (:supportsRestartFrame caps)))))

  (testing "JDI mode capabilities"
    (let [adapter (dap/create-dap-adapter {:backend :jdi})
          caps (core/protocol-capabilities adapter nil)]
      (is (true? (:supportsFunctionBreakpoints caps)))
      (is (true? (:supportsConditionalBreakpoints caps)))
      (is (true? (:supportsSetVariable caps))))))

;; ============================================================================
;; Lifecycle Tests
;; ============================================================================

(deftest lifecycle-test
  (testing "Initialize request"
    (let [adapter (dap/create-dap-adapter)
          result (dispatch-command adapter "initialize"
                   {:clientID "test-client"
                    :adapterID "clojure"})]
      (is (map? (:capabilities result)))
      (is (true? (:initialized? @(:adapter-state adapter))))))

  (testing "Launch request"
    (let [adapter (dap/create-dap-adapter)
          result (dispatch-command adapter "launch"
                   {:program "test.clj"})]
      (is (map? result))
      (is (true? (:launched? @(:adapter-state adapter))))))

  (testing "Attach request"
    (let [adapter (dap/create-dap-adapter)
          result (dispatch-command adapter "attach"
                   {:host "localhost" :port 5005})]
      (is (map? result))
      (is (true? (:attached? @(:adapter-state adapter))))))

  (testing "ConfigurationDone request"
    (let [adapter (dap/create-dap-adapter)]
      (dispatch-command adapter "initialize" {})
      (dispatch-command adapter "launch" {})
      (let [result (dispatch-command adapter "configurationDone" {})]
        (is (map? result))
        (is (true? (:configured? @(:adapter-state adapter)))))))

  (testing "Disconnect request"
    (let [adapter (dap/create-dap-adapter)]
      (dispatch-command adapter "initialize" {})
      (dispatch-command adapter "launch" {})
      (let [result (dispatch-command adapter "disconnect" {})]
        (is (map? result))
        (is (false? (:initialized? @(:adapter-state adapter))))))))

;; ============================================================================
;; Breakpoint Tests
;; ============================================================================

(deftest breakpoints-test
  (testing "setBreakpoints in REPL mode (unverified)"
    (let [adapter (dap/create-dap-adapter {:backend :repl})
          result (dispatch-command adapter "setBreakpoints"
                   {:source {:path "/test/file.clj"}
                    :breakpoints [{:line 10}
                                  {:line 20 :condition "true"}]})]
      (is (= 2 (count (:breakpoints result))))
      (is (every? false? (map :verified (:breakpoints result))))))

  (testing "setBreakpoints in nREPL mode (verified)"
    (let [adapter (dap/create-dap-adapter {:backend :nrepl})
          result (dispatch-command adapter "setBreakpoints"
                   {:source {:path "/test/file.clj"}
                    :breakpoints [{:line 10}]})]
      (is (= 1 (count (:breakpoints result))))
      (is (true? (first (map :verified (:breakpoints result)))))))

  (testing "setFunctionBreakpoints"
    (let [adapter (dap/create-dap-adapter)
          result (dispatch-command adapter "setFunctionBreakpoints"
                   {:breakpoints [{:name "my.ns/my-fn"}]})]
      (is (= 1 (count (:breakpoints result))))
      (is (false? (:verified (first (:breakpoints result)))))))

  (testing "setExceptionBreakpoints"
    (let [adapter (dap/create-dap-adapter)
          result (dispatch-command adapter "setExceptionBreakpoints"
                   {:filters ["uncaught"]})]
      (is (vector? (:breakpoints result)))
      (is (= #{"uncaught"} (:exception-breakpoints @(:adapter-state adapter)))))))

;; ============================================================================
;; Execution Control Tests
;; ============================================================================

(deftest execution-control-test
  (testing "continue clears transient state"
    (let [adapter (dap/create-dap-adapter)
          state (:adapter-state adapter)]
      ;; Add some transient state
      (swap! state assoc :stopped-threads #{1 2}
                         :var-refs {1000 {:type :value}})
      (dispatch-command adapter "continue" {:threadId 1})
      (is (empty? (:stopped-threads @state)))
      (is (empty? (:var-refs @state)))))

  (testing "stepping not supported in REPL mode"
    (let [adapter (dap/create-dap-adapter {:backend :repl})]
      (are [cmd] (contains? (dispatch-command adapter cmd {}) :message)
        "next"
        "stepIn"
        "stepOut")))

  (testing "stepBack only for FlowStorm"
    (let [repl-adapter (dap/create-dap-adapter {:backend :repl})
          fs-adapter (dap/create-dap-adapter {:backend :flowstorm})]
      (is (contains? (dispatch-command repl-adapter "stepBack" {}) :message)
          "REPL mode doesn't support stepBack")
      ;; FlowStorm would need actual connection, so it still fails but differently
      (is (some? (dispatch-command fs-adapter "stepBack" {}))))))

;; ============================================================================
;; Thread & Stack Tests
;; ============================================================================

(deftest threads-stack-test
  (testing "threads returns main thread"
    (let [adapter (dap/create-dap-adapter)
          result (dispatch-command adapter "threads" {})]
      (is (= 1 (count (:threads result))))
      (is (= 1 (:id (first (:threads result)))))
      (is (= "main" (:name (first (:threads result)))))))

  (testing "stackTrace returns empty in REPL mode"
    (let [adapter (dap/create-dap-adapter)
          result (dispatch-command adapter "stackTrace" {:threadId 1})]
      (is (= 0 (:totalFrames result)))
      (is (empty? (:stackFrames result)))))

  (testing "scopes returns REPL scope"
    (let [adapter (dap/create-dap-adapter)
          result (dispatch-command adapter "scopes" {:frameId 0})]
      (is (= 1 (count (:scopes result))))
      (is (= "REPL" (:name (first (:scopes result))))))))

;; ============================================================================
;; Variable Reference Tests
;; ============================================================================

(deftest variable-ref-test
  (testing "create-var-ref returns incrementing IDs"
    (let [state (dap/create-state)
          ref1 (dap/create-var-ref state :value {:a 1})
          ref2 (dap/create-var-ref state :value {:b 2})]
      (is (> ref2 ref1))
      (is (= {:type :value :data {:a 1}} (dap/get-var-ref state ref1)))
      (is (= {:type :value :data {:b 2}} (dap/get-var-ref state ref2)))))

  (testing "value-has-children? detects expandable values"
    (is (true? (dap/value-has-children? {:a 1})))
    (is (true? (dap/value-has-children? [1 2 3])))
    (is (true? (dap/value-has-children? #{1 2})))
    (is (true? (dap/value-has-children? '(1 2 3))))
    (is (false? (dap/value-has-children? 42)))
    (is (false? (dap/value-has-children? "string")))
    (is (false? (dap/value-has-children? :keyword))))

  (testing "value->variables converts maps"
    (let [state (dap/create-state)
          vars (dap/value->variables state {:a 1 :b "hello"})]
      (is (= 2 (count vars)))
      (is (every? #(contains? % :name) vars))
      (is (every? #(contains? % :value) vars))
      (is (every? #(contains? % :type) vars))))

  (testing "value->variables converts vectors"
    (let [state (dap/create-state)
          vars (dap/value->variables state [1 2 3])]
      (is (= 3 (count vars)))
      (is (= "[0]" (:name (first vars))))))

  (testing "nested values get variable references"
    (let [state (dap/create-state)
          vars (dap/value->variables state {:nested {:a 1}})]
      (is (> (:variablesReference (first vars)) 0)
          "Nested map should have variable reference"))))

;; ============================================================================
;; Evaluation Tests
;; ============================================================================

(deftest evaluate-test
  ;; A default eval is a JVM affordance, not a cross-platform one: Node has no
  ;; runtime eval of Clojure forms without self-hosted ClojureScript. So the
  ;; two platforms are asserted separately rather than the CLJS case being
  ;; left to fail.
  #?(:clj
     (testing "evaluate with the JVM default eval"
       (let [adapter (dap/create-dap-adapter)
             result (dispatch-command adapter "evaluate"
                      {:expression "(+ 1 2)"
                       :context "repl"})]
         (is (= "3" (:result result)))
         (is (= 0 (:variablesReference result)))
         (is (= "Integer" (:type result)))))

     :cljs
     (testing "with no eval-fn, CLJS reports a DAP error rather than a result"
       (let [adapter (dap/create-dap-adapter)
             result (dispatch-command adapter "evaluate"
                      {:expression "(+ 1 2)"
                       :context "repl"})]
         (is (nil? dap/default-eval-fn) "no default eval exists under CLJS")
         (is (str/includes? (:result result) "Error"))
         (is (str/includes? (:result result) "eval function"))
         ;; The regression this guards: the old :cljs branch returned an error
         ;; MAP as a value, which was then typed and given a var-ref, so a
         ;; client saw a successful evaluation of Map[1].
         (is (= 0 (:variablesReference result))
             "a failed evaluation must not be handed a variables reference")
         (is (not= "Map[1]" (:type result))))))

  (testing "evaluate returns complex structures"
    ;; Supplies its own eval-fn. Without one this asserted only that CLJS
    ;; returned SOME string with SOME var-ref, which the old error map
    ;; satisfied — it passed on Node while evaluating nothing at all.
    (let [adapter (dap/create-dap-adapter
                   {:backend :repl
                    :backend-opts {:eval-fn (fn [_] {:a 1 :b 2})}})
          result (dispatch-command adapter "evaluate"
                   {:expression "{:a 1 :b 2}"
                    :context "repl"})]
      (is (string? (:result result)))
      (is (str/includes? (:result result) ":a"))
      (is (> (:variablesReference result) 0)
          "Complex result should have variable reference")))

  (testing "evaluate with custom eval-fn"
    (let [adapter (dap/create-dap-adapter
                   {:backend :repl
                    :backend-opts {:eval-fn (fn [_] "custom result")}})
          result (dispatch-command adapter "evaluate"
                   {:expression "anything"
                    :context "repl"})]
      (is (= "\"custom result\"" (:result result)))))

  (testing "evaluate with registered port"
    (let [my-registry (registry/create-function-registry)]
      (core/register-port! my-registry
        {:id :evaluate
         :handler (fn [{:keys [params]}]
                    {:result (str "Evaluated: " (:code params))})})
      (let [adapter (dap/create-dap-adapter)
            result (dispatch-command adapter "evaluate"
                     {:expression "test-code"}
                     {:port-registry my-registry})]
        (is (= "\"Evaluated: test-code\"" (:result result))))))

  (testing "evaluate handles a throwing eval-fn gracefully"
    ;; Was (throw (Exception. "test error")) evaluated by the default eval and
    ;; checked with .contains — two JVM-only constructs in a .cljc test:
    ;; there is no Exception in CLJS, and .contains is not a JS String method.
    ;; A supplied eval-fn that throws exercises the same catch-any path on both.
    (let [adapter (dap/create-dap-adapter
                   {:backend :repl
                    :backend-opts {:eval-fn (fn [_]
                                              (throw (ex-info "test error" {})))}})
          result (dispatch-command adapter "evaluate"
                   {:expression "anything"
                    :context "repl"})]
      (is (string? (:result result)))
      (is (str/includes? (:result result) "Error"))
      (is (= 0 (:variablesReference result))))))

;; ============================================================================
;; Completions Tests
;; ============================================================================

(deftest completions-test
  (testing "completions without port returns empty"
    (let [adapter (dap/create-dap-adapter)
          result (dispatch-command adapter "completions"
                   {:text "map" :column 3})]
      (is (empty? (:targets result)))))

  (testing "completions with registered port"
    (let [my-registry (registry/create-function-registry)]
      (core/register-port! my-registry
        {:id :completions
         :handler (fn [{:keys [params]}]
                    {:result ["map" "mapv" "mapcat"]})})
      (let [adapter (dap/create-dap-adapter)
            result (dispatch-command adapter "completions"
                     {:text "map" :column 3}
                     {:port-registry my-registry})]
        (is (= 3 (count (:targets result))))
        (is (every? #(contains? % :label) (:targets result)))))))

;; ============================================================================
;; State Management Tests
;; ============================================================================

(deftest state-management-test
  (testing "clear-transient-state! clears appropriate fields"
    (let [state (dap/create-state)]
      (swap! state assoc
        :var-refs {1000 {:type :value}}
        :frames {0 {:name "test"}}
        :scopes {0 {:name "locals"}}
        :stopped-threads #{1})
      (dap/clear-transient-state! state)
      (is (empty? (:var-refs @state)))
      (is (empty? (:frames @state)))
      (is (empty? (:scopes @state)))
      (is (empty? (:stopped-threads @state)))))

  (testing "reset-state! preserves session-id"
    (let [state (dap/create-state)
          original-id (:session-id @state)]
      (dap/set-initialized! state)
      (dap/set-launched! state)
      (dap/reset-state! state)
      (is (= original-id (:session-id @state)))
      (is (false? (:initialized? @state)))
      (is (false? (:launched? @state))))))

;; ============================================================================
;; Type Name Tests
;; ============================================================================

(deftest type-name-test
  (testing "type-name returns readable names"
    (is (= "nil" (dap/type-name nil)))
    (is (= "String" (dap/type-name "hello")))
    (is (= "Integer" (dap/type-name 42)))
    (is (= "Number" (dap/type-name 3.14)))
    (is (= "Boolean" (dap/type-name true)))
    (is (= "Keyword" (dap/type-name :test)))
    (is (= "Symbol" (dap/type-name 'test)))
    (is (= "Map[2]" (dap/type-name {:a 1 :b 2})))
    (is (= "Vector[3]" (dap/type-name [1 2 3])))
    (is (= "Set[2]" (dap/type-name #{1 2})))
    (is (= "Function" (dap/type-name (fn [] nil))))))

;; ============================================================================
;; Message Codec Tests
;; ============================================================================

(deftest message-codec-test
  (testing "make-response creates valid DAP response"
    (let [request {:seq 1 :type "request" :command "test"}
          response (dap/make-response 2 request {:data "result"})]
      (is (= 2 (:seq response)))
      (is (= "response" (:type response)))
      (is (= 1 (:request_seq response)))
      (is (true? (:success response)))
      (is (= "test" (:command response)))
      (is (= {:data "result"} (:body response)))))

  (testing "make-event creates valid DAP event"
    (let [event (dap/make-event 3 "stopped" {:reason "breakpoint"})]
      (is (= 3 (:seq event)))
      (is (= "event" (:type event)))
      (is (= "stopped" (:event event)))
      (is (= {:reason "breakpoint"} (:body event)))))

  (testing "make-error-response creates error"
    (let [request {:seq 1 :type "request" :command "test"}
          response (dap/make-error-response 2 request "Something went wrong")]
      (is (false? (:success response)))
      (is (= "Something went wrong" (:message response))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest integration-test
  (testing "Full lifecycle with evaluation"
    (let [adapter (dap/create-dap-adapter)
          ctx {:adapter-state (:adapter-state adapter)
               :backend-type :repl
               ;; An explicit eval-fn, so the lifecycle is what this test
               ;; measures rather than which platform happens to ship an eval.
               :backend-opts {:eval-fn (fn [_] 42)}
               :transport nil}]
      ;; Initialize
      (let [result (dap/handle-request "initialize"
                     {:clientID "test"} ctx)]
        (is (map? (:capabilities result))))

      ;; Launch
      (dap/handle-request "launch" {:program "test.clj"} ctx)
      (is (true? (:launched? @(:adapter-state adapter))))

      ;; Configuration done
      (dap/handle-request "configurationDone" {} ctx)
      (is (true? (:configured? @(:adapter-state adapter))))

      ;; Evaluate
      (let [result (dap/handle-request "evaluate"
                     {:expression "(* 6 7)" :context "repl"} ctx)]
        (is (= "42" (:result result))))

      ;; Get threads
      (let [result (dap/handle-request "threads" {} ctx)]
        (is (= 1 (count (:threads result)))))

      ;; Disconnect
      (dap/handle-request "disconnect" {} ctx)
      (is (false? (:launched? @(:adapter-state adapter)))))))