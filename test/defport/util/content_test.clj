(ns defport.util.content-test
  "Tests for defport.util.content - Content type utilities for MCP."
  (:require [clojure.test :refer [deftest testing is]]
            [defport.util.content :as content]))

;; =============================================================================
;; Base64 Encoding/Decoding Tests
;; =============================================================================

(deftest base64-encode-test
  (testing "encodes byte arrays to Base64 strings"
    (let [data (.getBytes "hello world")
          encoded (content/base64-encode data)]
      (is (string? encoded))
      (is (= "aGVsbG8gd29ybGQ=" encoded))))

  (testing "encodes empty data"
    (let [data (.getBytes "")
          encoded (content/base64-encode data)]
      (is (string? encoded))
      (is (= "" encoded))))

  (testing "encodes binary data"
    (let [data (byte-array [0 1 2 3 255])
          encoded (content/base64-encode data)]
      (is (string? encoded))
      (is (not (empty? encoded))))))

(deftest base64-decode-test
  (testing "decodes Base64 strings to byte arrays"
    (let [decoded (content/base64-decode "aGVsbG8gd29ybGQ=")]
      (is (bytes? decoded))
      (is (= "hello world" (String. decoded)))))

  (testing "decodes empty string"
    (let [decoded (content/base64-decode "")]
      (is (bytes? decoded))
      (is (zero? (count decoded)))))

  (testing "round-trip encoding and decoding"
    (let [original "test data 123"
          data (.getBytes original)
          encoded (content/base64-encode data)
          decoded (content/base64-decode encoded)
          result (String. decoded)]
      (is (= original result)))))

;; =============================================================================
;; MIME Type Detection Tests
;; =============================================================================

(deftest guess-image-mime-type-test
  (testing "detects PNG"
    (is (= "image/png" (content/guess-image-mime-type "test.png")))
    (is (= "image/png" (content/guess-image-mime-type "TEST.PNG")))
    (is (= "image/png" (content/guess-image-mime-type "/path/to/image.png"))))

  (testing "detects JPEG"
    (is (= "image/jpeg" (content/guess-image-mime-type "test.jpg")))
    (is (= "image/jpeg" (content/guess-image-mime-type "test.jpeg"))))

  (testing "detects other image formats"
    (is (= "image/gif" (content/guess-image-mime-type "test.gif")))
    (is (= "image/webp" (content/guess-image-mime-type "test.webp")))
    (is (= "image/svg+xml" (content/guess-image-mime-type "test.svg")))
    (is (= "image/bmp" (content/guess-image-mime-type "test.bmp"))))

  (testing "returns default for unknown extensions"
    (is (= "application/octet-stream" (content/guess-image-mime-type "test.xyz")))))

(deftest guess-audio-mime-type-test
  (testing "detects WAV"
    (is (= "audio/wav" (content/guess-audio-mime-type "test.wav")))
    (is (= "audio/wav" (content/guess-audio-mime-type "TEST.WAV"))))

  (testing "detects MP3"
    (is (= "audio/mpeg" (content/guess-audio-mime-type "test.mp3"))))

  (testing "detects other audio formats"
    (is (= "audio/ogg" (content/guess-audio-mime-type "test.ogg")))
    (is (= "audio/mp4" (content/guess-audio-mime-type "test.m4a")))
    (is (= "audio/flac" (content/guess-audio-mime-type "test.flac"))))

  (testing "returns default for unknown extensions"
    (is (= "application/octet-stream" (content/guess-audio-mime-type "test.xyz")))))

(deftest guess-mime-type-test
  (testing "detects image types"
    (is (= "image/png" (content/guess-mime-type "test.png")))
    (is (= "image/jpeg" (content/guess-mime-type "test.jpg"))))

  (testing "detects audio types"
    (is (= "audio/wav" (content/guess-mime-type "test.wav")))
    (is (= "audio/mpeg" (content/guess-mime-type "test.mp3")))))

;; =============================================================================
;; ImageContent Tests
;; =============================================================================

(deftest image-content-test
  (testing "creates ImageContent from binary data"
    (let [data (.getBytes "fake-png-data")
          ic (content/image-content data "image/png")]
      (is (map? ic))
      (is (= "image" (:type ic)))
      (is (= "image/png" (:mimeType ic)))
      (is (string? (:data ic)))
      (is (= "ZmFrZS1wbmctZGF0YQ==" (:data ic)))))

  (testing "creates ImageContent with different MIME types"
    (let [data (.getBytes "test")
          ic-png (content/image-content data "image/png")
          ic-jpg (content/image-content data "image/jpeg")]
      (is (= "image/png" (:mimeType ic-png)))
      (is (= "image/jpeg" (:mimeType ic-jpg)))))

  (testing "Base64 encodes data"
    (let [data (.getBytes "hello")
          ic (content/image-content data "image/png")]
      (is (not (empty? (:data ic))))
      (is (string? (:data ic))))))

(deftest load-image-file-test
  (testing "throws on non-existent file"
    (is (thrown? Exception
          (content/load-image-file "/nonexistent/file.png"))))

  ;; Note: Testing actual file loading requires creating temp files
  ;; which we skip for unit tests. Integration tests should cover this.
  )

;; =============================================================================
;; AudioContent Tests
;; =============================================================================

(deftest audio-content-test
  (testing "creates AudioContent from binary data"
    (let [data (.getBytes "fake-wav-data")
          ac (content/audio-content data "audio/wav")]
      (is (map? ac))
      (is (= "audio" (:type ac)))
      (is (= "audio/wav" (:mimeType ac)))
      (is (string? (:data ac)))
      (is (= "ZmFrZS13YXYtZGF0YQ==" (:data ac)))))

  (testing "creates AudioContent with different MIME types"
    (let [data (.getBytes "test")
          ac-wav (content/audio-content data "audio/wav")
          ac-mp3 (content/audio-content data "audio/mpeg")]
      (is (= "audio/wav" (:mimeType ac-wav)))
      (is (= "audio/mpeg" (:mimeType ac-mp3)))))

  (testing "Base64 encodes data"
    (let [data (.getBytes "hello")
          ac (content/audio-content data "audio/wav")]
      (is (not (empty? (:data ac))))
      (is (string? (:data ac))))))

(deftest load-audio-file-test
  (testing "throws on non-existent file"
    (is (thrown? Exception
          (content/load-audio-file "/nonexistent/file.wav")))))

;; =============================================================================
;; TextContent Tests
;; =============================================================================

(deftest text-content-test
  (testing "creates TextContent from string"
    (let [tc (content/text-content "hello world")]
      (is (map? tc))
      (is (= "text" (:type tc)))
      (is (= "hello world" (:text tc)))))

  (testing "converts non-string to string"
    (let [tc (content/text-content 123)]
      (is (= "123" (:text tc)))))

  (testing "handles empty string"
    (let [tc (content/text-content "")]
      (is (= "" (:text tc))))))

;; =============================================================================
;; Content Type Detection Tests
;; =============================================================================

(deftest content-type-test
  (testing "detects ImageContent"
    (is (= :image (content/content-type {:type "image" :data "..." :mimeType "image/png"}))))

  (testing "detects AudioContent"
    (is (= :audio (content/content-type {:type "audio" :data "..." :mimeType "audio/wav"}))))

  (testing "detects TextContent"
    (is (= :text (content/content-type {:type "text" :text "hello"}))))

  (testing "detects plain string as text"
    (is (= :text (content/content-type "hello world"))))

  (testing "returns unknown for unrecognized content"
    (is (= :unknown (content/content-type {:foo "bar"})))))

;; =============================================================================
;; Content Validation Tests
;; =============================================================================

(deftest valid-image-content-test
  (testing "validates correct ImageContent"
    (is (true? (content/valid-image-content?
                 {:type "image"
                  :data "base64data"
                  :mimeType "image/png"}))))

  (testing "rejects missing type"
    (is (false? (content/valid-image-content?
                  {:data "base64data"
                   :mimeType "image/png"}))))

  (testing "rejects wrong type"
    (is (false? (content/valid-image-content?
                  {:type "audio"
                   :data "base64data"
                   :mimeType "image/png"}))))

  (testing "rejects missing data"
    (is (false? (content/valid-image-content?
                  {:type "image"
                   :mimeType "image/png"}))))

  (testing "rejects missing mimeType"
    (is (false? (content/valid-image-content?
                  {:type "image"
                   :data "base64data"}))))

  (testing "rejects wrong mimeType prefix"
    (is (false? (content/valid-image-content?
                  {:type "image"
                   :data "base64data"
                   :mimeType "audio/wav"}))))

  (testing "rejects non-map"
    (is (false? (content/valid-image-content? "not a map")))))

(deftest valid-audio-content-test
  (testing "validates correct AudioContent"
    (is (true? (content/valid-audio-content?
                 {:type "audio"
                  :data "base64data"
                  :mimeType "audio/wav"}))))

  (testing "rejects missing type"
    (is (false? (content/valid-audio-content?
                  {:data "base64data"
                   :mimeType "audio/wav"}))))

  (testing "rejects wrong type"
    (is (false? (content/valid-audio-content?
                  {:type "image"
                   :data "base64data"
                   :mimeType "audio/wav"}))))

  (testing "rejects missing data"
    (is (false? (content/valid-audio-content?
                  {:type "audio"
                   :mimeType "audio/wav"}))))

  (testing "rejects missing mimeType"
    (is (false? (content/valid-audio-content?
                  {:type "audio"
                   :data "base64data"}))))

  (testing "rejects wrong mimeType prefix"
    (is (false? (content/valid-audio-content?
                  {:type "audio"
                   :data "base64data"
                   :mimeType "image/png"}))))

  (testing "rejects non-map"
    (is (false? (content/valid-audio-content? "not a map")))))

(deftest valid-text-content-test
  (testing "validates correct TextContent"
    (is (true? (content/valid-text-content?
                 {:type "text"
                  :text "hello"}))))

  (testing "rejects missing type"
    (is (false? (content/valid-text-content?
                  {:text "hello"}))))

  (testing "rejects wrong type"
    (is (false? (content/valid-text-content?
                  {:type "image"
                   :text "hello"}))))

  (testing "rejects missing text"
    (is (false? (content/valid-text-content?
                  {:type "text"}))))

  (testing "rejects non-map"
    (is (false? (content/valid-text-content? "not a map")))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest image-content-integration-test
  (testing "create and validate ImageContent"
    (let [data (.getBytes "test-image-data")
          ic (content/image-content data "image/png")]
      (is (content/valid-image-content? ic))
      (is (= :image (content/content-type ic)))))

  (testing "round-trip image data"
    (let [original-data (.getBytes "original-image-bytes")
          ic (content/image-content original-data "image/jpeg")
          decoded-data (content/base64-decode (:data ic))
          result-data (String. decoded-data)]
      (is (= "original-image-bytes" result-data)))))

(deftest audio-content-integration-test
  (testing "create and validate AudioContent"
    (let [data (.getBytes "test-audio-data")
          ac (content/audio-content data "audio/wav")]
      (is (content/valid-audio-content? ac))
      (is (= :audio (content/content-type ac)))))

  (testing "round-trip audio data"
    (let [original-data (.getBytes "original-audio-bytes")
          ac (content/audio-content original-data "audio/mp3")
          decoded-data (content/base64-decode (:data ac))
          result-data (String. decoded-data)]
      (is (= "original-audio-bytes" result-data)))))

(deftest text-content-integration-test
  (testing "create and validate TextContent"
    (let [tc (content/text-content "hello world")]
      (is (content/valid-text-content? tc))
      (is (= :text (content/content-type tc))))))
