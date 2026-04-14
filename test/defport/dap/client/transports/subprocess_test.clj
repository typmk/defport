(ns defport.dap.client.transports.subprocess-test
  "Tests for the JVM subprocess DAP client transport. Same shape as
  the LSP equivalent — spawn `cat`, write framed JSON, read it back."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.dap.client :as client]
            [defport.dap.client.transports.subprocess :as sub]))

(defn- recv-with-timeout [tx timeout-ms]
  (let [end (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [m (client/transport-recv! tx)]
        (cond
          (not= ::client/no-message m) m
          (> (System/currentTimeMillis) end) ::timeout
          :else (do (Thread/sleep 5) (recur)))))))

(deftest test-subprocess-echo-roundtrip
  (testing "DAP-shaped framed message round-trips through `cat`"
    (let [tx (sub/transport ["cat"])]
      (try
        (client/transport-start! tx)
        (is (client/transport-alive? tx))
        (client/transport-send! tx {:seq 1
                                    :type "request"
                                    :command "evaluate"
                                    :arguments {:expression "(+ 1 2)"}})
        (let [msg (recv-with-timeout tx 1000)]
          (is (not= ::timeout msg))
          (is (= "evaluate" (:command msg)))
          (is (= "(+ 1 2)" (get-in msg [:arguments :expression]))))
        (finally
          (client/transport-stop! tx))))))

(deftest test-subprocess-multiple-messages-roundtrip
  (testing "three commands round-trip in order"
    (let [tx (sub/transport ["cat"])]
      (try
        (client/transport-start! tx)
        (client/transport-send! tx {:seq 1 :type "request" :command "threads"})
        (client/transport-send! tx {:seq 2 :type "request" :command "scopes"})
        (client/transport-send! tx {:seq 3 :type "request" :command "stackTrace"})
        (let [m1 (recv-with-timeout tx 1000)
              m2 (recv-with-timeout tx 1000)
              m3 (recv-with-timeout tx 1000)]
          (is (= ["threads" "scopes" "stackTrace"]
                 (mapv :command [m1 m2 m3]))))
        (finally
          (client/transport-stop! tx))))))

(deftest test-transport-alive-flips-after-stop
  (let [tx (sub/transport ["cat"])]
    (client/transport-start! tx)
    (is (client/transport-alive? tx))
    (client/transport-stop! tx)
    (Thread/sleep 50)
    (is (not (client/transport-alive? tx)))))
