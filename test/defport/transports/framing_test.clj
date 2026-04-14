(ns defport.transports.framing-test
  "Pure-function tests for Content-Length framed JSON-RPC encoding +
  the streaming decoder. The decoder is a state machine — these
  tests verify it handles whole frames, split frames (header arrives
  before body), multiple frames in one chunk, and parse errors."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.transports.framing :as f])
  (:import [java.nio.charset StandardCharsets]))

(defn- bytes->str [b] (String. ^bytes b StandardCharsets/UTF_8))
(defn- ->bytes [s] (.getBytes ^String s StandardCharsets/UTF_8))

;; ============================================================================
;; Encode
;; ============================================================================

(deftest test-encode-emits-content-length-header
  (let [out (bytes->str (f/encode-message {:hello "world"}))]
    (is (re-find #"^Content-Length: \d+\r\n\r\n" out))
    (is (.contains out "{\"hello\":\"world\"}"))))

(deftest test-encode-content-length-matches-utf8-byte-length
  (let [;; Multi-byte UTF-8 chars to confirm we count bytes not chars
        msg {:text "héllo世界"}
        out (bytes->str (f/encode-message msg))
        [hdr body] (clojure.string/split out #"\r\n\r\n" 2)
        declared-len (Long/parseLong (second (re-find #"Content-Length: (\d+)" hdr)))]
    (is (= declared-len (count (.getBytes ^String body StandardCharsets/UTF_8))))))

;; ============================================================================
;; Decode — whole frames
;; ============================================================================

(deftest test-decode-single-whole-frame
  (let [encoded (f/encode-message {:jsonrpc "2.0" :id 1 :method "ping"})
        [msgs state] (f/feed (f/empty-state) encoded)]
    (is (= 1 (count msgs)))
    (is (= "ping" (:method (first msgs))))
    (is (zero? (alength ^bytes (:buffer state))))))

(deftest test-decode-two-frames-in-one-chunk
  (let [m1 (f/encode-message {:id 1 :method "foo"})
        m2 (f/encode-message {:id 2 :method "bar"})
        chunk (let [out (byte-array (+ (alength ^bytes m1) (alength ^bytes m2)))]
                (System/arraycopy m1 0 out 0 (alength ^bytes m1))
                (System/arraycopy m2 0 out (alength ^bytes m1) (alength ^bytes m2))
                out)
        [msgs _] (f/feed (f/empty-state) chunk)]
    (is (= 2 (count msgs)))
    (is (= ["foo" "bar"] (mapv :method msgs)))))

;; ============================================================================
;; Decode — split frames
;; ============================================================================

(deftest test-decode-frame-split-mid-header
  (let [encoded (f/encode-message {:method "hello"})
        ;; Send the first 5 bytes, then the rest
        len     (alength ^bytes encoded)
        c1      (let [a (byte-array 5)]
                  (System/arraycopy encoded 0 a 0 5) a)
        c2      (let [a (byte-array (- len 5))]
                  (System/arraycopy encoded 5 a 0 (- len 5)) a)
        [m1 s1] (f/feed (f/empty-state) c1)
        [m2 _]  (f/feed s1 c2)]
    (is (empty? m1) "Headers incomplete after first chunk → no messages")
    (is (= 1 (count m2)))
    (is (= "hello" (:method (first m2))))))

(deftest test-decode-frame-split-mid-body
  (let [encoded (f/encode-message {:method "halfsies" :args [1 2 3 4 5]})
        ;; Find header end so we can split right after it.
        s (String. ^bytes encoded StandardCharsets/ISO_8859_1)
        header-end (+ 4 (.indexOf s "\r\n\r\n"))
        c1 (let [a (byte-array (+ header-end 3))]
             (System/arraycopy encoded 0 a 0 (+ header-end 3)) a)
        c2 (let [rest-len (- (alength ^bytes encoded) (+ header-end 3))
                 a (byte-array rest-len)]
             (System/arraycopy encoded (+ header-end 3) a 0 rest-len) a)
        [m1 s1] (f/feed (f/empty-state) c1)
        [m2 _]  (f/feed s1 c2)]
    (is (empty? m1) "Body incomplete after first chunk → no messages yet")
    (is (= 1 (count m2)))
    (is (= "halfsies" (:method (first m2))))))

(deftest test-decode-three-frames-spread-across-many-chunks
  (let [m1 (f/encode-message {:id 1 :method "a"})
        m2 (f/encode-message {:id 2 :method "b"})
        m3 (f/encode-message {:id 3 :method "c"})
        all-bytes (let [out (byte-array (+ (alength ^bytes m1)
                                           (alength ^bytes m2)
                                           (alength ^bytes m3)))
                        _ (System/arraycopy m1 0 out 0 (alength ^bytes m1))
                        _ (System/arraycopy m2 0 out (alength ^bytes m1) (alength ^bytes m2))
                        _ (System/arraycopy m3 0 out (+ (alength ^bytes m1) (alength ^bytes m2))
                                            (alength ^bytes m3))]
                    out)
        ;; Feed one byte at a time
        [final-msgs _]
        (reduce
          (fn [[msgs state] i]
            (let [chunk (byte-array 1)]
              (aset-byte chunk 0 (aget all-bytes i))
              (let [[new-msgs new-state] (f/feed state chunk)]
                [(into msgs new-msgs) new-state])))
          [[] (f/empty-state)]
          (range (alength all-bytes)))]
    (is (= 3 (count final-msgs)))
    (is (= ["a" "b" "c"] (mapv :method final-msgs)))))

;; ============================================================================
;; Decode — parse errors
;; ============================================================================

(deftest test-decode-invalid-json-yields-parse-error
  (let [malformed (let [body (.getBytes "{not-valid-json" StandardCharsets/UTF_8)
                        hdr  (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n")
                                        StandardCharsets/US_ASCII)
                        out  (byte-array (+ (alength hdr) (alength body)))]
                    (System/arraycopy hdr 0 out 0 (alength hdr))
                    (System/arraycopy body 0 out (alength hdr) (alength body))
                    out)
        [msgs _] (f/feed (f/empty-state) malformed)]
    (is (= 1 (count msgs)))
    (is (contains? (first msgs) :defport.transports.framing/parse-error))))
