(ns defport.transports.stdio-test
  "Tests for stdio transport with LSP/MCP message framing."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.transports.stdio :as stdio]
            [defport.core :as core]
            [clojure.core.async :as async]
            [cheshire.core :as json])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)))

(defn- create-framed-message
  "Create a properly framed JSON-RPC message with Content-Length header."
  [msg]
  (let [content (json/generate-string msg)
        content-bytes (.getBytes content "utf-8")]
    (str "Content-Length: " (count content-bytes) "\r\n"
         "\r\n"
         content)))

(defn- create-input-stream
  "Create an input stream from framed messages."
  [& messages]
  (let [framed (apply str (map create-framed-message messages))]
    (ByteArrayInputStream. (.getBytes framed "utf-8"))))

(defn- parse-output-messages
  "Parse framed messages from output stream bytes."
  [^bytes output-bytes]
  (let [content (String. output-bytes "utf-8")
        ;; Split on Content-Length headers
        parts (clojure.string/split content #"Content-Length: \d+\r\n\r\n")]
    (->> parts
         (remove clojure.string/blank?)
         (mapv #(json/parse-string % true)))))

(deftest test-message-framing
  (testing "Transport correctly frames output messages"
    (let [out-stream (ByteArrayOutputStream.)
          transport (stdio/create-stdio-transport
                      {:input-stream (create-input-stream)
                       :output-stream out-stream})
          ;; Start with no-op handler
          _ (core/transport-start transport (constantly nil))]

      ;; Send a message
      (core/transport-send transport {:jsonrpc "2.0" :id 1 :result {:ok true}})

      ;; Give it time to process
      (Thread/sleep 100)

      ;; Check output has Content-Length header
      (let [output (String. (.toByteArray out-stream) "utf-8")]
        (is (clojure.string/includes? output "Content-Length:"))
        (is (clojure.string/includes? output "\r\n\r\n"))
        (is (clojure.string/includes? output "jsonrpc")))

      (core/transport-stop transport))))

(deftest test-request-response-roundtrip
  (testing "Transport handles request-response roundtrip"
    (let [request {:jsonrpc "2.0" :id 1 :method "test" :params {:foo "bar"}}
          out-stream (ByteArrayOutputStream.)
          received (atom nil)
          transport (stdio/create-stdio-transport
                      {:input-stream (create-input-stream request)
                       :output-stream out-stream})]

      ;; Handler that echoes back
      (core/transport-start transport
                            (fn [msg]
                              (reset! received msg)
                              {:jsonrpc "2.0" :id (:id msg) :result {:echo (:params msg)}}))

      ;; Give it time to process
      (Thread/sleep 200)

      ;; Verify request was received
      (is (= "test" (:method @received)))
      (is (= {:foo "bar"} (:params @received)))

      ;; Verify response was sent
      (let [output (String. (.toByteArray out-stream) "utf-8")]
        (is (clojure.string/includes? output "echo"))
        (is (clojure.string/includes? output "bar")))

      (core/transport-stop transport))))

(deftest test-multiple-messages
  (testing "Transport handles multiple messages in sequence"
    (let [msg1 {:jsonrpc "2.0" :id 1 :method "first" :params {}}
          msg2 {:jsonrpc "2.0" :id 2 :method "second" :params {}}
          msg3 {:jsonrpc "2.0" :id 3 :method "third" :params {}}
          out-stream (ByteArrayOutputStream.)
          received (atom [])
          transport (stdio/create-stdio-transport
                      {:input-stream (create-input-stream msg1 msg2 msg3)
                       :output-stream out-stream})]

      (core/transport-start transport
                            (fn [msg]
                              (swap! received conj (:method msg))
                              {:jsonrpc "2.0" :id (:id msg) :result {:ok true}}))

      ;; Give it time to process all messages
      (Thread/sleep 300)

      ;; Verify all messages were received
      (is (= ["first" "second" "third"] @received))

      (core/transport-stop transport))))

(deftest test-discarding-stdout
  (testing "Handler code cannot pollute stdout"
    (let [request {:jsonrpc "2.0" :id 1 :method "test" :params {}}
          out-stream (ByteArrayOutputStream.)
          transport (stdio/create-stdio-transport
                      {:input-stream (create-input-stream request)
                       :output-stream out-stream})]

      ;; Handler that tries to print (should be discarded)
      (core/transport-start transport
                            (fn [_msg]
                              (println "THIS SHOULD NOT APPEAR IN OUTPUT")
                              (print "NEITHER SHOULD THIS")
                              {:jsonrpc "2.0" :id 1 :result {:ok true}}))

      ;; Give it time to process
      (Thread/sleep 200)

      ;; Verify output only contains valid JSON-RPC
      (let [output (String. (.toByteArray out-stream) "utf-8")]
        (is (not (clojure.string/includes? output "THIS SHOULD NOT APPEAR")))
        (is (not (clojure.string/includes? output "NEITHER SHOULD THIS")))
        ;; But the response should be there
        (is (clojure.string/includes? output "result")))

      (core/transport-stop transport))))

(deftest test-with-discarded-stdout-macro
  (testing "with-discarded-stdout macro works"
    (let [captured (atom nil)]
      ;; Capture what would go to stdout
      (let [sw (java.io.StringWriter.)]
        (binding [*out* sw]
          (stdio/with-discarded-stdout
            (println "This should be discarded"))
          (reset! captured (str sw))))

      (is (= "" @captured)))))

(deftest test-write-lock-thread-safety
  (testing "Concurrent writes don't interleave"
    (let [out-stream (ByteArrayOutputStream.)
          transport (stdio/create-stdio-transport
                      {:input-stream (create-input-stream)
                       :output-stream out-stream})]

      (core/transport-start transport (constantly nil))

      ;; Send many messages concurrently
      (let [futures (doall
                      (for [i (range 10)]
                        (future
                          (core/transport-send transport
                                               {:jsonrpc "2.0" :id i :result {:n i}}))))]
        ;; Wait for all to complete
        (doseq [f futures] @f))

      ;; Give output thread time to flush
      (Thread/sleep 200)

      ;; Verify output is valid (no interleaved Content-Length headers)
      (let [output (String. (.toByteArray out-stream) "utf-8")
            ;; Count complete messages by counting Content-Length headers
            header-count (count (re-seq #"Content-Length:" output))]
        ;; Should have exactly 10 headers
        (is (= 10 header-count)))

      (core/transport-stop transport))))