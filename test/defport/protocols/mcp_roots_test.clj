(ns defport.protocols.mcp-roots-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]))

(use-fixtures :each
  (fn [f]
    (mcp/reset-protocol-state!)
    (f)))

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
          registry (registry/create-function-registry)
          roots [{:uri "file:///workspace" :name "Project Root"}
                 {:uri "file:///home/user" :name "Home"}]
          _ (mcp/update-client-roots! roots)
          params {}
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "roots/list" params context)]
      (is (map? result))
      (is (= 2 (count (:roots result))))
      (is (= roots (:roots result))))))

(deftest test-update-client-roots
  (testing "Updates client roots"
    (let [roots [{:uri "file:///workspace" :name "Workspace"}]]
      (is (empty? @mcp/client-roots*))
      (mcp/update-client-roots! roots)
      (is (= roots @mcp/client-roots*))))

  (testing "Replaces existing roots"
    (mcp/update-client-roots! [{:uri "file:///old" :name "Old"}])
    (is (= 1 (count @mcp/client-roots*)))
    (let [new-roots [{:uri "file:///new1" :name "New 1"}
                     {:uri "file:///new2" :name "New 2"}]]
      (mcp/update-client-roots! new-roots)
      (is (= 2 (count @mcp/client-roots*)))
      (is (= new-roots @mcp/client-roots*)))))

(deftest test-is-path-in-roots
  (testing "Returns false when no roots configured"
    (is (false? (mcp/is-path-in-roots? "/workspace/src/foo.clj"))))

  (testing "Returns true for path within root"
    (mcp/update-client-roots! [{:uri "file:///workspace" :name "Workspace"}])
    (is (true? (mcp/is-path-in-roots? "/workspace/src/foo.clj")))
    (is (true? (mcp/is-path-in-roots? "/workspace/test/bar.clj"))))

  (testing "Returns false for path outside root"
    (mcp/update-client-roots! [{:uri "file:///workspace" :name "Workspace"}])
    (is (false? (mcp/is-path-in-roots? "/etc/passwd")))
    (is (false? (mcp/is-path-in-roots? "/home/user/file.txt"))))

  (testing "Works with multiple roots"
    (mcp/update-client-roots! [{:uri "file:///workspace" :name "Workspace"}
                               {:uri "file:///home/user" :name "Home"}])
    (is (true? (mcp/is-path-in-roots? "/workspace/src/foo.clj")))
    (is (true? (mcp/is-path-in-roots? "/home/user/docs/bar.txt")))
    (is (false? (mcp/is-path-in-roots? "/tmp/temp.txt")))))

(deftest test-validate-file-access
  (testing "Throws when path is outside roots"
    (mcp/update-client-roots! [{:uri "file:///workspace" :name "Workspace"}])
    (is (thrown? Exception
          (mcp/validate-file-access "/etc/passwd"))))

  (testing "Does not throw when path is within roots"
    (mcp/update-client-roots! [{:uri "file:///workspace" :name "Workspace"}])
    (is (nil? (mcp/validate-file-access "/workspace/src/foo.clj"))))

  (testing "Throws with appropriate error info"
    (mcp/update-client-roots! [{:uri "file:///workspace" :name "Workspace"}])
    (try
      (mcp/validate-file-access "/etc/passwd")
      (is false "Expected exception")
      (catch Exception e
        (let [data (ex-data e)]
          (is (= -32603 (:code data)))
          (is (= "/etc/passwd" (:file-path data)))
          (is (some? (:roots data))))))))

(deftest test-roots-in-initialize-response
  (testing "Initialize response includes roots capability"
    (let [adapter (mcp/create-mcp-adapter)
          registry (registry/create-function-registry)
          params {}
          context {:port-registry registry}
          result (core/protocol-dispatch adapter "initialize" params context)]
      (is (map? result))
      (is (= "2025-06-18" (:protocolVersion result)))
      (is (some? (get-in result [:capabilities :roots])))
      (is (= {:listChanged false} (get-in result [:capabilities :roots]))))))

(deftest test-reset-protocol-state-clears-roots
  (testing "reset-protocol-state! clears client roots"
    (mcp/update-client-roots! [{:uri "file:///workspace" :name "Workspace"}])
    (is (seq @mcp/client-roots*))
    (mcp/reset-protocol-state!)
    (is (empty? @mcp/client-roots*))))
