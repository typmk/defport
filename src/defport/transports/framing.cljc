(ns defport.transports.framing
  "Wire framing for JSON-RPC over byte streams.

  Two codecs, one namespace:

  ## Content-Length (LSP + DAP)

      Content-Length: 64\\r\\n
      \\r\\n
      <64 bytes of JSON UTF-8>

  LSP and DAP both wrap their payloads this way. Entry points:
    - `encode-message` / `empty-state` / `feed`

  ## JSON-lines (MCP 2025-11-25)

      {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"foo\"}\\n
      {\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"bar\"}\\n

  The MCP 2025-11-25 stdio transport specifies newline-delimited
  JSON, not Content-Length framing. Messages must not contain
  embedded newlines. Entry points:
    - `encode-line` / `empty-state-lines` / `feed-lines`

  All functions are pure and platform-free. Used by the subprocess
  transports (defport.*.client.transports.*) and reusable by anything
  else that speaks framed JSON-RPC over a byte channel."
  (:require [defport.util.platform :as platform :include-macros true])
  #?(:clj (:import [java.nio.charset StandardCharsets])))

;; ============================================================================
;; Encode
;; ============================================================================

(defn encode-message
  "Encode a clj map as a framed JSON-RPC message. Returns a byte array
   on JVM and a Buffer on Node.

   The result is ready to write directly to a transport's output
   stream — header lines are US-ASCII, body is UTF-8."
  [message]
  (let [json (platform/json-encode message)
        json-bytes #?(:clj  (.getBytes ^String json StandardCharsets/UTF_8)
                      :cljs (.from js/Buffer json "utf8"))
        content-length #?(:clj  (alength json-bytes)
                          :cljs (.-length ^js json-bytes))
        header (str "Content-Length: " content-length "\r\n\r\n")
        header-bytes #?(:clj  (.getBytes ^String header StandardCharsets/US_ASCII)
                        :cljs (.from js/Buffer header "ascii"))]
    #?(:clj
       (let [out (byte-array (+ (alength header-bytes) content-length))]
         (System/arraycopy header-bytes 0 out 0 (alength header-bytes))
         (System/arraycopy json-bytes 0 out (alength header-bytes) content-length)
         out)
       :cljs
       (.concat js/Buffer #js [header-bytes json-bytes]))))

;; ============================================================================
;; Decode
;; ============================================================================
;;
;; The decoder is a pure state machine. Callers feed bytes in via
;; `feed`; it returns `[messages new-state]` where `messages` is a
;; vector of completed parsed maps and `new-state` carries any
;; partial frame to the next call.
;;
;; State shape:
;;   {:buffer       ^bytes accumulated unread bytes
;;    :phase        :headers | :body
;;    :headers      {"Content-Length" "64" ...}  parsed so far in headers phase
;;    :content-len  long      total body length when known
;;    :body-read    long      bytes of body already consumed (always 0 here
;;                            since we extract whole bodies; kept for future
;;                            partial-body tracking if streams send chunks)}

(defn empty-state
  "Initial decoder state. Pass to `feed` for the first chunk."
  []
  {:buffer  #?(:clj (byte-array 0)
               :cljs (.alloc js/Buffer 0))
   :phase   :headers
   :headers {}})

(defn- buffer-length [buffer]
  #?(:clj (alength ^bytes buffer)
     :cljs (.-length ^js buffer)))

(defn- buffer-concat [a b]
  #?(:clj
     (let [la (alength ^bytes a)
           lb (alength ^bytes b)
           out (byte-array (+ la lb))]
       (System/arraycopy a 0 out 0 la)
       (System/arraycopy b 0 out la lb)
       out)
     :cljs
     (.concat js/Buffer #js [a b])))

(defn- buffer-slice [buffer start end]
  #?(:clj
     (let [len (- end start)
           out (byte-array len)]
       (System/arraycopy buffer start out 0 len)
       out)
     :cljs
     (.slice ^js buffer start end)))

(defn- buffer-string
  "Decode a slice of the buffer as a String."
  [buffer start end charset]
  #?(:clj
     (String. ^bytes buffer (int start) (int (- end start))
              ^String charset)
     :cljs
     (.toString (.slice ^js buffer start end)
                (case charset
                  "UTF-8"   "utf8"
                  "US-ASCII" "ascii"
                  charset))))

(defn- find-crlf-crlf
  "Find the offset just past the next \\r\\n\\r\\n in buffer starting
   at offset 0. Returns -1 if not present."
  [buffer]
  #?(:clj
     (let [^bytes b buffer
           len (alength b)]
       (loop [i 0]
         (if (> (+ i 4) len)
           -1
           (if (and (= (aget b i)       (byte 13))
                    (= (aget b (+ i 1)) (byte 10))
                    (= (aget b (+ i 2)) (byte 13))
                    (= (aget b (+ i 3)) (byte 10)))
             (+ i 4)
             (recur (inc i))))))
     :cljs
     (loop [i 0]
       (let [len (.-length ^js buffer)]
         (if (> (+ i 4) len)
           -1
           (if (and (= (.readUInt8 ^js buffer i)       13)
                    (= (.readUInt8 ^js buffer (+ i 1)) 10)
                    (= (.readUInt8 ^js buffer (+ i 2)) 13)
                    (= (.readUInt8 ^js buffer (+ i 3)) 10))
             (+ i 4)
             (recur (inc i))))))))

(defn- parse-headers
  "Parse a header block string into a {header-name string} map.
   Header names are case-insensitive in HTTP-style; we preserve the
   first occurrence's casing."
  [^String header-block]
  (->> (clojure.string/split header-block #"\r\n")
       (remove clojure.string/blank?)
       (map #(let [[k v] (clojure.string/split % #":\s*" 2)] [k v]))
       (into {})))

(defn feed
  "Feed the next chunk of bytes (or Buffer on Node) into the decoder.
   Returns `[messages new-state]`. Idempotent in the sense that if
   no bytes are passed and state has nothing to drain, you get back
   the same state with an empty message vector."
  [state chunk]
  (let [combined (if (and chunk (pos? (buffer-length chunk)))
                   (buffer-concat (:buffer state) chunk)
                   (:buffer state))]
    (loop [s    (assoc state :buffer combined)
           msgs []]
      (case (:phase s)
        :headers
        (let [end (find-crlf-crlf (:buffer s))]
          (if (neg? end)
            ;; Need more bytes for headers
            [msgs s]
            (let [header-str   (buffer-string (:buffer s) 0 (- end 4) "US-ASCII")
                  headers      (parse-headers header-str)
                  cl-str       (or (get headers "Content-Length") "0")
                  content-len  (try
                                 #?(:clj  (Long/parseLong cl-str)
                                    :cljs (js/parseInt cl-str 10))
                                 (catch #?(:clj Exception :cljs js/Error) _ 0))
                  rest-buffer  (buffer-slice (:buffer s) end (buffer-length (:buffer s)))]
              (recur (assoc s
                            :phase :body
                            :headers headers
                            :content-len content-len
                            :buffer rest-buffer)
                     msgs))))

        :body
        (let [content-len (:content-len s)
              avail       (buffer-length (:buffer s))]
          (if (< avail content-len)
            ;; Need more bytes for body
            [msgs s]
            (let [body-str   (buffer-string (:buffer s) 0 content-len "UTF-8")
                  rest-buf   (buffer-slice (:buffer s) content-len avail)
                  parsed     (try
                               (platform/json-decode body-str)
                               (catch #?(:clj Exception :cljs js/Error) e
                                 {::parse-error (platform/error-message e)
                                  ::raw body-str}))]
              (recur {:buffer rest-buf
                      :phase :headers
                      :headers {}}
                     (conj msgs parsed)))))))))

;; ============================================================================
;; JSON-lines codec (MCP 2025-11-25 stdio transport)
;; ============================================================================

(defn encode-line
  "Encode a clj map as a single JSON-lines message — serialize to
   JSON, append a newline. Returns a byte array on JVM, a Buffer on
   Node. No Content-Length header.

   Use this for MCP stdio; LSP and DAP stdio still use
   `encode-message` (Content-Length framing)."
  [message]
  (let [json (platform/json-encode message)
        line (str json "\n")]
    #?(:clj  (.getBytes ^String line StandardCharsets/UTF_8)
       :cljs (.from js/Buffer line "utf8"))))

(defn empty-state-lines
  "Initial decoder state for the JSON-lines codec. Pass to `feed-lines`
   for the first chunk."
  []
  {:buffer #?(:clj (byte-array 0) :cljs (.alloc js/Buffer 0))})

(defn- find-newline
  "Offset just past the next \\n in buffer, or -1 if not present."
  [buffer]
  #?(:clj
     (let [^bytes b buffer
           len (alength b)]
       (loop [i 0]
         (cond
           (>= i len) -1
           (= (aget b i) (byte 10)) (inc i)
           :else (recur (inc i)))))
     :cljs
     (let [len (.-length ^js buffer)]
       (loop [i 0]
         (cond
           (>= i len) -1
           (= (.readUInt8 ^js buffer i) 10) (inc i)
           :else (recur (inc i)))))))

(defn feed-lines
  "Feed a chunk of bytes (or Buffer on Node) into the JSON-lines
   decoder. Returns `[messages new-state]`. Lines that fail JSON
   parse become `{::parse-error ... ::raw ...}` maps so the stream
   stays aligned.

   Empty lines are skipped silently — real-world MCP implementations
   sometimes emit extra whitespace around messages."
  [state chunk]
  (let [combined (if (and chunk (pos? (buffer-length chunk)))
                   (buffer-concat (:buffer state) chunk)
                   (:buffer state))]
    (loop [buf  combined
           msgs []]
      (let [end (find-newline buf)]
        (if (neg? end)
          [msgs {:buffer buf}]
          (let [line-str (buffer-string buf 0 (dec end) "UTF-8")
                rest-buf (buffer-slice buf end (buffer-length buf))]
            (if (clojure.string/blank? line-str)
              (recur rest-buf msgs)
              (let [parsed (try
                             (platform/json-decode line-str)
                             (catch #?(:clj Exception :cljs js/Error) e
                               {::parse-error (platform/error-message e)
                                ::raw line-str}))]
                (recur rest-buf (conj msgs parsed))))))))))
