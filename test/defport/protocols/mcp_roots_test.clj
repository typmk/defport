(ns defport.protocols.mcp-roots-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.mcp :as mcp]))

(deftest test-handle-roots-list
  (testing "Returns empty roots by default"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          params {}
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "roots/list" params context)]
      (is (map? result))
      (is (vector? (:roots result)))
      (is (empty? (:roots result)))))

  (testing "Returns client roots after update"
    (let [adapter (mcp/create-mcp-adapter)
          state* (mcp/adapter-state adapter)
          registry (registry/create-function-registry)
          roots [{:uri "file:///workspace" :name "Project Root"}
                 {:uri "file:///home/user" :name "Home"}]
          _ (mcp/update-client-roots! state* roots)
          params {}
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "roots/list" params context)]
      (is (map? result))
      (is (= 2 (count (:roots result))))
      (is (= roots (:roots result))))))

(deftest test-update-client-roots
  (testing "Updates client roots"
    (let [state* (mcp/create-protocol-state)
          roots [{:uri "file:///workspace" :name "Workspace"}]]
      (is (empty? (:client-roots @state*)))
      (mcp/update-client-roots! state* roots)
      (is (= roots (:client-roots @state*)))))

  (testing "Replaces existing roots"
    (let [state* (mcp/create-protocol-state)]
      (mcp/update-client-roots! state* [{:uri "file:///old" :name "Old"}])
      (is (= 1 (count (:client-roots @state*))))
      (let [new-roots [{:uri "file:///new1" :name "New 1"}
                       {:uri "file:///new2" :name "New 2"}]]
        (mcp/update-client-roots! state* new-roots)
        (is (= 2 (count (:client-roots @state*))))
        (is (= new-roots (:client-roots @state*)))))))

(deftest test-is-path-in-roots
  (testing "Returns false when no roots configured"
    (let [state* (mcp/create-protocol-state)]
      (is (false? (mcp/is-path-in-roots? state* "/workspace/src/foo.clj")))))

  (testing "Returns true for path within root"
    (let [state* (mcp/create-protocol-state)]
      (mcp/update-client-roots! state* [{:uri "file:///workspace" :name "Workspace"}])
      (is (true? (mcp/is-path-in-roots? state* "/workspace/src/foo.clj")))
      (is (true? (mcp/is-path-in-roots? state* "/workspace/test/bar.clj")))))

  (testing "Returns false for path outside root"
    (let [state* (mcp/create-protocol-state)]
      (mcp/update-client-roots! state* [{:uri "file:///workspace" :name "Workspace"}])
      (is (false? (mcp/is-path-in-roots? state* "/etc/passwd")))
      (is (false? (mcp/is-path-in-roots? state* "/home/user/file.txt")))))

  (testing "Works with multiple roots"
    (let [state* (mcp/create-protocol-state)]
      (mcp/update-client-roots! state* [{:uri "file:///workspace" :name "Workspace"}
                                         {:uri "file:///home/user" :name "Home"}])
      (is (true? (mcp/is-path-in-roots? state* "/workspace/src/foo.clj")))
      (is (true? (mcp/is-path-in-roots? state* "/home/user/docs/bar.txt")))
      (is (false? (mcp/is-path-in-roots? state* "/tmp/temp.txt"))))))

(deftest test-validate-file-access
  (testing "Throws when path is outside roots"
    (let [state* (mcp/create-protocol-state)]
      (mcp/update-client-roots! state* [{:uri "file:///workspace" :name "Workspace"}])
      (is (thrown? Exception
            (mcp/validate-file-access state* "/etc/passwd")))))

  (testing "Does not throw when path is within roots"
    (let [state* (mcp/create-protocol-state)]
      (mcp/update-client-roots! state* [{:uri "file:///workspace" :name "Workspace"}])
      (is (nil? (mcp/validate-file-access state* "/workspace/src/foo.clj")))))

  (testing "Throws with appropriate error info"
    (let [state* (mcp/create-protocol-state)]
      (mcp/update-client-roots! state* [{:uri "file:///workspace" :name "Workspace"}])
      (try
        (mcp/validate-file-access state* "/etc/passwd")
        (is false "Expected exception")
        (catch Exception e
          (let [data (ex-data e)]
            (is (= -32603 (:code data)))
            (is (= "/etc/passwd" (:file-path data)))
            (is (some? (:roots data)))))))))

(deftest test-roots-in-initialize-response
  (testing "Initialize response includes roots capability"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          params {}
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "initialize" params context)]
      (is (map? result))
      (is (some? (get-in result [:capabilities :roots])))
      (is (= {:listChanged false} (get-in result [:capabilities :roots]))))))

(deftest test-reset-protocol-state-clears-roots
  (testing "reset-protocol-state! clears client roots"
    (let [state* (mcp/create-protocol-state)]
      (mcp/update-client-roots! state* [{:uri "file:///workspace" :name "Workspace"}])
      (is (seq (:client-roots @state*)))
      (mcp/reset-protocol-state! state*)
      (is (empty? (:client-roots @state*))))))
