(ns defport.protocols.mcp-completions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]
            [clojure.string :as str]))

(use-fixtures :each
  (fn [f]
    (mcp/reset-protocol-state!)
    (f)))

(deftest test-completion-basic
  (testing "Returns completions from simple function"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)

          ;; Register tool with completion function
          _ (core/register-port! registry
                                 {:id :search
                                  :handler (fn [_] {:result []})
                                  :metadata {:completions
                                             {:type (fn [partial _context]
                                                      (filter #(str/starts-with? % partial)
                                                              ["file" "function" "class" "variable"]))}}})

          context {:port-registry registry}
          params {:ref {:type "prompt" :name "search"}
                  :argument {:name "type" :value "f"}}
          result (core/protocol-dispatch adapter "completion/complete" params context)]

      (is (contains? result :completion))
      (is (= ["file" "function"] (get-in result [:completion :values])))
      (is (= 2 (get-in result [:completion :total])))
      (is (false? (get-in result [:completion :hasMore]))))))

(deftest test-completion-context-aware
  (testing "Uses context from previously entered arguments"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)

          ;; Register tool with context-aware completion
          _ (core/register-port! registry
                                 {:id :team-greeting
                                  :handler (fn [_] {:result "ok"})
                                  :metadata {:completions
                                             {:department (fn [partial _]
                                                            (filter #(str/starts-with? % partial)
                                                                    ["engineering" "sales" "marketing"]))
                                              :name (fn [partial context]
                                                      (let [dept (:department context)]
                                                        (case dept
                                                          "engineering" ["Alice" "Bob" "Charlie"]
                                                          "sales" ["Dave" "Eve"]
                                                          [])))}}})

          context {:port-registry registry}

          ;; First, complete department
          result1 (core/protocol-dispatch adapter "completion/complete"
                                           {:ref {:type "tool" :name "team-greeting"}
                                            :argument {:name "department" :value "en"}}
                                           context)]
      (is (= ["engineering"] (get-in result1 [:completion :values])))

      ;; Then, complete name based on department context
      (let [result2 (core/protocol-dispatch adapter "completion/complete"
                                             {:ref {:type "tool" :name "team-greeting"}
                                              :argument {:name "name" :value ""}
                                              :context {:arguments {:department "engineering"}}}
                                             context)]
        (is (= ["Alice" "Bob" "Charlie"] (get-in result2 [:completion :values])))))))

(deftest test-completion-no-function
  (testing "Returns empty completions when no function defined"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)

          ;; Register tool WITHOUT completion function
          _ (core/register-port! registry
                                 {:id :simple-tool
                                  :handler (fn [_] {:result "ok"})})

          context {:port-registry registry}
          params {:ref {:type "tool" :name "simple-tool"}
                  :argument {:name "param" :value "test"}}
          result (core/protocol-dispatch adapter "completion/complete" params context)]

      (is (contains? result :completion))
      (is (= [] (get-in result [:completion :values])))
      (is (= 0 (get-in result [:completion :total]))))))

(deftest test-completion-error-handling
  (testing "Returns error when completion function throws"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)

          ;; Register tool with failing completion function
          _ (core/register-port! registry
                                 {:id :bad-tool
                                  :handler (fn [_] {:result "ok"})
                                  :metadata {:completions
                                             {:param (fn [_ _]
                                                       (throw (ex-info "Completion error" {})))}}})

          context {:port-registry registry}
          params {:ref {:type "tool" :name "bad-tool"}
                  :argument {:name "param" :value "test"}}
          result (core/protocol-dispatch adapter "completion/complete" params context)]

      (is (contains? result :error))
      (is (= -32603 (get-in result [:error :code])))
      (is (str/includes? (get-in result [:error :message]) "Completion error")))))

(deftest test-completion-validation
  (testing "Validates required params - ref"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "completion/complete"
                                          {:argument {:name "test"}}
                                          context)]
      (is (contains? result :error))
      (is (= -32602 (get-in result [:error :code])))))

  (testing "Validates required params - argument.name"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "completion/complete"
                                          {:ref {:type "tool" :name "test"}}
                                          context)]
      (is (contains? result :error))
      (is (= -32602 (get-in result [:error :code])))))

  (testing "Returns error for unknown port"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "completion/complete"
                                          {:ref {:type "tool" :name "nonexistent"}
                                           :argument {:name "param" :value "test"}}
                                          context)]
      (is (contains? result :error))
      (is (= -32602 (get-in result [:error :code])))
      (is (str/includes? (get-in result [:error :message]) "Unknown port")))))

(deftest test-completion-capability
  (testing "Reports completion capability in initialize"
    (let [adapter (mcp/create-mcp-adapter {:server-info {:name "test" :version "1.0"}})
          registry (registry/create-function-registry)
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "initialize"
                                          {:protocolVersion "2025-06-18"
                                           :capabilities {}
                                           :clientInfo {:name "test-client" :version "1.0"}}
                                          context)]
      (is (contains? result :capabilities))
      (is (contains? (:capabilities result) :completion))
      (is (map? (get-in result [:capabilities :completion]))))))

(deftest test-completion-with-empty-partial
  (testing "Returns all completions when partial value is empty"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)

          _ (core/register-port! registry
                                 {:id :test-tool
                                  :handler (fn [_] {:result "ok"})
                                  :metadata {:completions
                                             {:option (fn [partial _]
                                                        (if (empty? partial)
                                                          ["option1" "option2" "option3"]
                                                          (filter #(str/starts-with? % partial)
                                                                  ["option1" "option2" "option3"])))}}})

          context {:port-registry registry}
          params {:ref {:type "tool" :name "test-tool"}
                  :argument {:name "option" :value ""}}
          result (core/protocol-dispatch adapter "completion/complete" params context)]

      (is (= ["option1" "option2" "option3"] (get-in result [:completion :values])))
      (is (= 3 (get-in result [:completion :total]))))))

(deftest test-completion-type-conversion
  (testing "Converts non-string values to strings"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)

          _ (core/register-port! registry
                                 {:id :numeric-tool
                                  :handler (fn [_] {:result "ok"})
                                  :metadata {:completions
                                             {:port (fn [_ _]
                                                      [8080 8081 8082])}}})  ; Return numbers

          context {:port-registry registry}
          params {:ref {:type "tool" :name "numeric-tool"}
                  :argument {:name "port" :value "808"}}
          result (core/protocol-dispatch adapter "completion/complete" params context)]

      (is (every? string? (get-in result [:completion :values])))
      (is (= ["8080" "8081" "8082"] (get-in result [:completion :values]))))))

(deftest test-multiple-arguments
  (testing "Handles multiple arguments with different completion functions"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)

          _ (core/register-port! registry
                                 {:id :multi-arg-tool
                                  :handler (fn [_] {:result "ok"})
                                  :metadata {:completions
                                             {:arg1 (fn [_ _] ["a1" "a2"])
                                              :arg2 (fn [_ _] ["b1" "b2"])
                                              :arg3 (fn [_ _] ["c1" "c2"])}}})

          context {:port-registry registry}]

      ;; Complete arg1
      (let [result1 (core/protocol-dispatch adapter "completion/complete"
                                             {:ref {:type "tool" :name "multi-arg-tool"}
                                              :argument {:name "arg1" :value ""}}
                                             context)]
        (is (= ["a1" "a2"] (get-in result1 [:completion :values]))))

      ;; Complete arg2
      (let [result2 (core/protocol-dispatch adapter "completion/complete"
                                             {:ref {:type "tool" :name "multi-arg-tool"}
                                              :argument {:name "arg2" :value ""}}
                                             context)]
        (is (= ["b1" "b2"] (get-in result2 [:completion :values]))))

      ;; Complete arg3
      (let [result3 (core/protocol-dispatch adapter "completion/complete"
                                             {:ref {:type "tool" :name "multi-arg-tool"}
                                              :argument {:name "arg3" :value ""}}
                                             context)]
        (is (= ["c1" "c2"] (get-in result3 [:completion :values])))))))
