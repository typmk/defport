(ns defport.util.content
  "Content type utilities for MCP responses.

  Provides helpers for creating rich content types:
  - ImageContent: Base64-encoded images
  - AudioContent: Base64-encoded audio
  - TextContent: Plain text
  - Helper functions for encoding/decoding

  Platform-agnostic with reader conditionals for JVM and Node.js."
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io]))
  #?(:cljs (:require-macros [defport.util.content :refer [base64-encode base64-decode]])))

;; =============================================================================
;; Base64 Encoding/Decoding
;; =============================================================================

(defn base64-encode
  "Encode binary data to Base64 string.

  Platform-specific implementation:
  - JVM: Uses java.util.Base64
  - Node.js: Uses Buffer.toString('base64')
  - Browser: Uses btoa (future support)

  Args:
    data - Binary data (byte array on JVM, Buffer on Node.js)

  Returns:
    Base64-encoded string

  Example:
    (base64-encode (.getBytes \"hello\"))
    ;; => \"aGVsbG8=\""
  [data]
  #?(:clj (-> (java.util.Base64/getEncoder)
              (.encodeToString data))
     :cljs (if (exists? js/Buffer)
             ;; Node.js environment
             (.toString (.from js/Buffer data) "base64")
             ;; Browser environment (future)
             (js/btoa (apply str (map char data))))))

(defn base64-decode
  "Decode Base64 string to binary data.

  Args:
    base64-str - Base64-encoded string

  Returns:
    Binary data (byte array on JVM, Buffer on Node.js)

  Example:
    (base64-decode \"aGVsbG8=\")
    ;; => byte array representing \"hello\""
  [base64-str]
  #?(:clj (-> (java.util.Base64/getDecoder)
              (.decode base64-str))
     :cljs (if (exists? js/Buffer)
             ;; Node.js environment
             (.from js/Buffer base64-str "base64")
             ;; Browser environment (future)
             (let [binary (js/atob base64-str)]
               (js/Uint8Array. (map #(.charCodeAt % 0) binary))))))

;; =============================================================================
;; MIME Type Detection
;; =============================================================================

(defn guess-image-mime-type
  "Guess image MIME type from file extension.

  Args:
    file-path - Path to image file

  Returns:
    MIME type string (e.g., \"image/png\")

  Example:
    (guess-image-mime-type \"diagram.png\")
    ;; => \"image/png\""
  [file-path]
  (let [ext (-> file-path
                str
                (str/split #"\.")
                last
                str/lower-case)]
    (case ext
      "png" "image/png"
      "jpg" "image/jpeg"
      "jpeg" "image/jpeg"
      "gif" "image/gif"
      "bmp" "image/bmp"
      "webp" "image/webp"
      "svg" "image/svg+xml"
      "ico" "image/x-icon"
      "application/octet-stream")))

(defn guess-audio-mime-type
  "Guess audio MIME type from file extension.

  Args:
    file-path - Path to audio file

  Returns:
    MIME type string (e.g., \"audio/wav\")

  Example:
    (guess-audio-mime-type \"speech.wav\")
    ;; => \"audio/wav\""
  [file-path]
  (let [ext (-> file-path
                str
                (str/split #"\.")
                last
                str/lower-case)]
    (case ext
      "wav" "audio/wav"
      "mp3" "audio/mpeg"
      "ogg" "audio/ogg"
      "m4a" "audio/mp4"
      "aac" "audio/aac"
      "flac" "audio/flac"
      "opus" "audio/opus"
      "weba" "audio/webm"
      "application/octet-stream")))

(defn guess-mime-type
  "Guess MIME type from file extension (images and audio).

  Args:
    file-path - Path to file

  Returns:
    MIME type string

  Example:
    (guess-mime-type \"test.png\")
    ;; => \"image/png\""
  [file-path]
  (let [ext (-> file-path
                str
                (str/split #"\.")
                last
                str/lower-case)]
    (if (contains? #{"png" "jpg" "jpeg" "gif" "bmp" "webp" "svg" "ico"} ext)
      (guess-image-mime-type file-path)
      (guess-audio-mime-type file-path))))

;; =============================================================================
;; ImageContent
;; =============================================================================

(defn image-content
  "Create ImageContent for MCP response.

  MCP ImageContent format:
  {\"type\": \"image\",
   \"data\": \"base64-encoded-data\",
   \"mimeType\": \"image/png\"}

  Args:
    data - Binary image data (byte array on JVM, Buffer on Node.js)
    mime-type - MIME type string (e.g., \"image/png\", \"image/jpeg\")

  Returns:
    ImageContent map conforming to MCP spec

  Example:
    (image-content png-bytes \"image/png\")
    ;; => {:type \"image\" :data \"iVBORw0...\" :mimeType \"image/png\"}"
  [data mime-type]
  {:type "image"
   :data (base64-encode data)
   :mimeType mime-type})

(defn load-image-file
  "Load image from file and create ImageContent.

  Reads binary file, detects MIME type, and encodes to Base64.

  Args:
    file-path - Path to image file (string)

  Returns:
    ImageContent map

  Example:
    (load-image-file \"diagram.png\")
    ;; => {:type \"image\" :data \"...\" :mimeType \"image/png\"}

  Platform Support:
    - JVM: Full support
    - Node.js: Full support
    - Browser: Limited (no file system access)"
  [file-path]
  #?(:clj (let [file (io/file file-path)
                bytes (with-open [in (io/input-stream file)]
                        (let [length (.length file)
                              buffer (byte-array length)]
                          (.read in buffer)
                          buffer))
                mime-type (guess-image-mime-type file-path)]
            (image-content bytes mime-type))
     :cljs (if (exists? js/require)
             ;; Node.js environment
             (let [fs (js/require "fs")
                   buffer (.readFileSync fs file-path)
                   mime-type (guess-image-mime-type file-path)]
               (image-content buffer mime-type))
             ;; Browser environment
             (throw (ex-info "File system access not available in browser"
                            {:file-path file-path
                             :hint "Use fetch() or FileReader API instead"})))))

;; =============================================================================
;; AudioContent
;; =============================================================================

(defn audio-content
  "Create AudioContent for MCP response.

  MCP AudioContent format:
  {\"type\": \"audio\",
   \"data\": \"base64-encoded-data\",
   \"mimeType\": \"audio/wav\"}

  Args:
    data - Binary audio data (byte array on JVM, Buffer on Node.js)
    mime-type - MIME type string (e.g., \"audio/wav\", \"audio/mp3\")

  Returns:
    AudioContent map conforming to MCP spec

  Example:
    (audio-content wav-bytes \"audio/wav\")
    ;; => {:type \"audio\" :data \"UklGRi...\" :mimeType \"audio/wav\"}"
  [data mime-type]
  {:type "audio"
   :data (base64-encode data)
   :mimeType mime-type})

(defn load-audio-file
  "Load audio from file and create AudioContent.

  Reads binary file, detects MIME type, and encodes to Base64.

  Args:
    file-path - Path to audio file (string)

  Returns:
    AudioContent map

  Example:
    (load-audio-file \"speech.wav\")
    ;; => {:type \"audio\" :data \"...\" :mimeType \"audio/wav\"}

  Platform Support:
    - JVM: Full support
    - Node.js: Full support
    - Browser: Limited (no file system access)"
  [file-path]
  #?(:clj (let [file (io/file file-path)
                bytes (with-open [in (io/input-stream file)]
                        (let [length (.length file)
                              buffer (byte-array length)]
                          (.read in buffer)
                          buffer))
                mime-type (guess-audio-mime-type file-path)]
            (audio-content bytes mime-type))
     :cljs (if (exists? js/require)
             ;; Node.js environment
             (let [fs (js/require "fs")
                   buffer (.readFileSync fs file-path)
                   mime-type (guess-audio-mime-type file-path)]
               (audio-content buffer mime-type))
             ;; Browser environment
             (throw (ex-info "File system access not available in browser"
                            {:file-path file-path
                             :hint "Use fetch() or FileReader API instead"})))))

;; =============================================================================
;; TextContent Helpers
;; =============================================================================

(defn text-content
  "Create TextContent for MCP response.

  MCP TextContent format:
  {\"type\": \"text\",
   \"text\": \"content string\"}

  Args:
    text - Text content (string)

  Returns:
    TextContent map conforming to MCP spec

  Example:
    (text-content \"Hello, world!\")
    ;; => {:type \"text\" :text \"Hello, world!\"}"
  [text]
  {:type "text"
   :text (str text)})

;; =============================================================================
;; Content Type Detection
;; =============================================================================

(defn content-type
  "Detect content type from value.

  Args:
    value - Content value (map with :type, or string)

  Returns:
    Keyword indicating type (:image, :audio, :text, :unknown)

  Example:
    (content-type {:type \"image\" :data \"...\"})
    ;; => :image"
  [value]
  (cond
    (and (map? value) (= "image" (:type value))) :image
    (and (map? value) (= "audio" (:type value))) :audio
    (and (map? value) (= "text" (:type value))) :text
    (string? value) :text
    :else :unknown))

(defn valid-image-content?
  "Check if value is valid ImageContent.

  Valid if:
  - Has :type \"image\"
  - Has :data (Base64 string)
  - Has :mimeType starting with \"image/\"

  Args:
    value - Value to check

  Returns:
    Boolean

  Example:
    (valid-image-content? {:type \"image\" :data \"abc\" :mimeType \"image/png\"})
    ;; => true"
  [value]
  (and (map? value)
       (= "image" (:type value))
       (string? (:data value))
       (string? (:mimeType value))
       (str/starts-with? (:mimeType value) "image/")))

(defn valid-audio-content?
  "Check if value is valid AudioContent.

  Valid if:
  - Has :type \"audio\"
  - Has :data (Base64 string)
  - Has :mimeType starting with \"audio/\"

  Args:
    value - Value to check

  Returns:
    Boolean

  Example:
    (valid-audio-content? {:type \"audio\" :data \"abc\" :mimeType \"audio/wav\"})
    ;; => true"
  [value]
  (and (map? value)
       (= "audio" (:type value))
       (string? (:data value))
       (string? (:mimeType value))
       (str/starts-with? (:mimeType value) "audio/")))

(defn valid-text-content?
  "Check if value is valid TextContent.

  Valid if:
  - Has :type \"text\"
  - Has :text (string)

  Args:
    value - Value to check

  Returns:
    Boolean

  Example:
    (valid-text-content? {:type \"text\" :text \"hello\"})
    ;; => true"
  [value]
  (and (map? value)
       (= "text" (:type value))
       (string? (:text value))))
