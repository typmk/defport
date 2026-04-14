(ns defport.lsp.client.transports.subprocess-test
  "Tests for the JVM subprocess LSP client transport.

  We don't have a real LSP server in the test classpath, so we
  spawn `cat` as the subprocess. The transport writes framed
  JSON-RPC into cat's stdin; cat echoes it byte-for-byte to stdout;
  the transport's reader thread parses it back out. This exercises
  the full encode → write → ProcessBuilder → read → decode loop
  end-to-end, with cat as a reliable stand-in for any framed-JSON
  server."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.lsp.client :as client]
            [defport.lsp.client.transports.subprocess :as sub]
            [defport.transports.framing :as framing]))

(defn- recv-with-timeout
  "Poll the transport until a non-::no-message arrives or the timeout
   elapses. Returns the message or ::timeout."
  [tx timeout-ms]
  (let [end (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [m (client/transport-recv! tx)]
        (cond
          (not= ::client/no-message m) m
          (> (System/currentTimeMillis) end) ::timeout
          :else (do (Thread/sleep 5) (recur)))))))

(deftest test-subprocess-echo-roundtrip
  (testing "writing a framed message to `cat` reads it back out"
    (let [tx (sub/transport ["cat"])]
      (try
        (client/transport-start! tx)
        (is (client/transport-alive? tx))
        (client/transport-send! tx {:jsonrpc "2.0"
                                    :id 1
                                    :method "echo-test"
                                    :params {:n 42}})
        (let [msg (recv-with-timeout tx 1000)]
          (is (not= ::timeout msg))
          (is (= "echo-test" (:method msg)))
          (is (= {:n 42} (:params msg))))
        (finally
          (client/transport-stop! tx))))))

(deftest test-subprocess-multiple-messages-roundtrip
  (testing "three messages in sequence each round-trip"
    (let [tx (sub/transport ["cat"])]
      (try
        (client/transport-start! tx)
        (client/transport-send! tx {:id 1 :method "a"})
        (client/transport-send! tx {:id 2 :method "b"})
        (client/transport-send! tx {:id 3 :method "c"})
        (let [m1 (recv-with-timeout tx 1000)
              m2 (recv-with-timeout tx 1000)
              m3 (recv-with-timeout tx 1000)]
          (is (= ["a" "b" "c"] (mapv :method [m1 m2 m3]))))
        (finally
          (client/transport-stop! tx))))))

(deftest test-transport-alive-flips-after-stop
  (let [tx (sub/transport ["cat"])]
    (client/transport-start! tx)
    (is (client/transport-alive? tx))
    (client/transport-stop! tx)
    (Thread/sleep 50)
    (is (not (client/transport-alive? tx)))))

(deftest test-recv-on-fresh-transport-returns-no-message
  (let [tx (sub/transport ["cat"])]
    (try
      (client/transport-start! tx)
      (is (= ::client/no-message (client/transport-recv! tx)))
      (finally
        (client/transport-stop! tx)))))
