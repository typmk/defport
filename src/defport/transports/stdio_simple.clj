(ns defport.transports.stdio-simple
  "Simple blocking stdio transport for JSON-RPC protocols (MCP, LSP, DAP).

  This is a lightweight alternative to stdio.cljc that does NOT use core.async.
  Benefits:
  - ~3 seconds faster startup (no core.async dependency)
  - Simpler implementation (single-threaded blocking I/O)
  - Suitable for request-response protocols like MCP

  Trade-offs:
  - No channel buffering between I/O and processing
  - Single-threaded (one request at a time)
  - JVM only (no ClojureScript support)

  For high-throughput or streaming protocols, use stdio.cljc instead."
  (:require [defport.core :as core]
            [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import (java.io
             InputStream
             OutputStream
             EOFException
             IOException)))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Null output stream for discarding stdout
;; ============================================================================

(def ^:private null-output-stream-writer
  (java.io.OutputStreamWriter.
    (proxy [java.io.OutputStream] []
      (write
        ([^bytes _b])
        ([^bytes _b _off _len])))))

(defmacro discarding-stdout
  "Evaluates body in a context where writes to *out* are discarded.
   Critical for stdio transports where stdout must remain pure JSON-RPC."
  [& body]
  `(binding [*out* null-output-stream-writer]
     ~@body))

;; ============================================================================
;; Message Reading (LSP Base Protocol)
;; ============================================================================

(defn- read-header-line
  "Read a single header line from input. Blocks waiting for input.
   Returns ::eof on end of stream, string otherwise."
  [^InputStream input]
  (try
    (let [sb (StringBuilder.)]
      (loop []
        (let [b (.read input)]
          (case b
            -1 ::eof                    ; end of stream
            10 (str sb)                 ; LF = end of line
            13 (recur)                  ; ignore CR
            (do (.append sb (char b))   ; byte == char for US-ASCII headers
                (recur))))))
    (catch IOException _e
      ::eof)))

(defn- parse-header
  "Parse a header line into the headers map."
  [line headers]
  (let [[h v] (clojure.string/split line #":\s*" 2)]
    (assoc headers h v)))

(defn- parse-charset
  "Extract charset from Content-Type header, default to utf-8."
  [content-type]
  (or (when content-type
        (when-let [[_ charset] (re-find #"(?i)charset=(.*)$" content-type)]
          (when (not= "utf8" charset)
            charset)))
      "utf-8"))

(defn- read-n-bytes
  "Read exactly n bytes from input stream. Blocks until all bytes read."
  [^InputStream input content-length charset-s]
  (let [buffer (byte-array content-length)]
    (loop [total-read 0]
      (when (< total-read content-length)
        (let [new-read (.read input buffer total-read (- content-length total-read))]
          (when (< new-read 0)
            (throw (EOFException. "Unexpected end of input")))
          (recur (+ total-read new-read)))))
    (String. ^bytes buffer ^String charset-s)))

(defn- read-message
  "Read a complete JSON-RPC message after headers have been parsed."
  [^InputStream input headers]
  (try
    (let [content-length (Long/valueOf ^String (get headers "Content-Length"))
          charset-s (parse-charset (get headers "Content-Type"))
          content (read-n-bytes input content-length charset-s)]
      (json/parse-string content true))
    (catch Exception e
      {:parse-error true :exception e})))

(defn- read-next-message
  "Read the next complete message from input stream.
   Returns message map, :eof, or :error."
  [^InputStream input]
  (loop [headers {}]
    (let [line (read-header-line input)]
      (cond
        ;; EOF - end of stream
        (= line ::eof)
        :eof

        ;; Blank line = end of headers, read message body
        (clojure.string/blank? line)
        (let [msg (read-message input headers)]
          (if (:parse-error msg)
            :error
            msg))

        ;; Header line - accumulate
        :else
        (recur (parse-header line headers))))))

;; ============================================================================
;; Message Writing (LSP Base Protocol)
;; ============================================================================

(def ^:private write-lock (Object.))

(defn- write-message
  "Write a JSON-RPC message with Content-Length framing.
   Thread-safe via write-lock."
  [^OutputStream output msg]
  (let [content (json/generate-string msg)
        content-bytes (.getBytes content "utf-8")]
    (locking write-lock
      (doto output
        ;; Headers are US-ASCII per LSP spec
        (.write (.getBytes (str "Content-Length: " (count content-bytes) "\r\n"
                                "\r\n")
                           "US-ASCII"))
        ;; Body is UTF-8
        (.write content-bytes)
        (.flush)))))

;; ============================================================================
;; Transport Implementation
;; ============================================================================

(defrecord SimpleStdioTransport [running?*
                                  input-stream*
                                  output-stream*
                                  stderr*]
  core/Transport
  (transport-id [_] :stdio-simple)

  (transport-start [_ handler]
    (reset! running?* true)
    (let [in-stream (or @input-stream* System/in)
          out-stream (or @output-stream* System/out)]
      ;; Simple blocking loop - read message, handle, write response
      (discarding-stdout
        (loop []
          (when @running?*
            (let [msg (read-next-message in-stream)]
              (cond
                ;; EOF - connection closed
                (= msg :eof)
                (reset! running?* false)

                ;; Parse error - skip
                (= msg :error)
                (recur)

                ;; Valid message - handle and respond
                :else
                (do
                  (try
                    (when-let [response (handler msg)]
                      (write-message out-stream response))
                    (catch Exception e
                      (binding [*out* @stderr*]
                        (println "Handler error:" (.getMessage e)))))
                  (recur)))))))
      nil))

  (transport-stop [_]
    (reset! running?* false)
    nil)

  (transport-send [_ message]
    (when-let [out-stream @output-stream*]
      (write-message out-stream message))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn create-stdio-transport
  "Create a simple blocking stdio transport for JSON-RPC protocols.

  This transport does NOT use core.async, resulting in ~3s faster startup.
  Suitable for request-response protocols like MCP.

  Options map:
    :input-stream  - InputStream to read from (default System/in)
    :output-stream - OutputStream to write to (default System/out)
    :stderr        - Writer for error output (default *err*)

  Example:
    ;; Default (System/in, System/out)
    (def transport (create-stdio-transport))

    ;; Custom streams (for testing)
    (def transport (create-stdio-transport
                     {:input-stream (io/input-stream \"test.jsonl\")
                      :output-stream my-output-stream}))"
  ([]
   (create-stdio-transport nil))
  ([opts]
   (->SimpleStdioTransport
     (atom false)                              ; running?*
     (atom (:input-stream opts))               ; input-stream*
     (atom (:output-stream opts))              ; output-stream*
     (atom (or (:stderr opts) *err*)))))       ; stderr*

;; ============================================================================
;; Utility - discarding-stdout for external use
;; ============================================================================

(defmacro with-discarded-stdout
  "Execute body with stdout writes discarded.

   Use this to wrap any code that might accidentally write to stdout
   when running in stdio mode. Essential for MCP/LSP compliance.

   Example:
     (with-discarded-stdout
       (some-library-fn-that-prints))"
  [& body]
  `(discarding-stdout ~@body))
