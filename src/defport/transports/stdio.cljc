(ns defport.transports.stdio
  "Stdio transport for JSON-RPC protocols (MCP, LSP, DAP).

  Implements the LSP Base Protocol for message framing:
  - Content-Length header + CRLF + CRLF + JSON body
  - Thread-safe writes with locking
  - Dedicated I/O threads (not go-blocks) for blocking operations
  - core.async channels for decoupling I/O from processing

  Based on battle-tested patterns from clojure-lsp/jsonrpc4clj.

  Platform support:
  - JVM: Full support with proper message framing
  - Node.js: Basic support (line-based, simpler framing)"
  (:require [defport.core :as core]
            [defport.util.platform :as platform :include-macros true]
            [clojure.core.async :as async]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [cheshire.core :as json]))
  #?(:clj (:import (java.io
                     InputStream
                     OutputStream
                     EOFException
                     IOException))))

#?(:clj (set! *warn-on-reflection* true))

;; ============================================================================
;; JVM Implementation - Full LSP/MCP message framing
;; ============================================================================

#?(:clj
   (do
     ;; Null output stream for discarding stdout.
     ;; Public because it's referenced from macro expansions in consumer namespaces.
     (def null-output-stream-writer
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

     ;; Write lock for thread-safe output
     (def ^:private write-lock (Object.))

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
                 (do (.append sb (char b))  ; byte == char for US-ASCII headers
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

     (defn- input-stream->input-chan
       "Create a channel that yields parsed messages from the input stream.
        Runs in a dedicated thread (not go-block) for blocking I/O.
        Closes channel on EOF or error."
       [^InputStream input]
       (let [messages (async/chan 1)]
         (async/thread
           (try
             (loop [headers {}]
               (let [line (read-header-line input)]
                 (cond
                   ;; EOF - close channel
                   (= line ::eof)
                   (async/close! messages)

                   ;; Blank line = end of headers, read message body
                   (clojure.string/blank? line)
                   (let [msg (read-message input headers)]
                     (if (async/>!! messages msg)
                       (recur {})  ; wait for next message
                       nil))       ; channel closed

                   ;; Header line - accumulate
                   :else
                   (recur (parse-header line headers)))))
             (catch Exception _e
               (async/close! messages))))
         messages))

     (defn- output-stream->output-chan
       "Create a channel that writes messages to the output stream.
        Runs in a dedicated thread for blocking I/O.
        Closes output when channel closes."
       [^OutputStream output]
       (let [messages (async/chan 1)]
         (async/thread
           (try
             (loop []
               (when-let [msg (async/<!! messages)]
                 (write-message output msg)
                 (recur)))
             (catch Exception _e
               nil))
           ;; Ensure output is flushed on close
           (try (.flush output) (catch Exception _)))
         messages))

     (defrecord StdioTransport [running?*
                                input-ch
                                output-ch
                                input-stream
                                output-stream
                                stderr
                                process-thread]
       core/Transport
       (transport-id [_] :stdio)

       (transport-start [this handler]
         (reset! running?* true)
         (let [in-stream (or @input-stream System/in)
               out-stream (or @output-stream System/out)
               in-ch (input-stream->input-chan in-stream)
               out-ch (output-stream->output-chan out-stream)]
           (reset! input-ch in-ch)
           (reset! output-ch out-ch)
           ;; Process messages in a dedicated thread with discarding-stdout
           (let [thread-ch
                 (async/thread
                   (discarding-stdout
                     (loop []
                       (when @running?*
                         (when-let [msg (async/<!! in-ch)]
                           (when-not (:parse-error msg)
                             (try
                               (let [response (handler msg)]
                                 (when response
                                   (async/>!! out-ch response)))
                               (catch Exception e
                                 (binding [*out* @stderr]
                                   (println "Handler error:" (.getMessage e))))))
                           (recur))))))]
             (reset! process-thread thread-ch)
             ;; BLOCK until the processing thread completes (connection closes)
             ;; This is the expected behavior for stdio transports
             (async/<!! thread-ch))
           nil))

       (transport-stop [_]
         (reset! running?* false)
         (when-let [in-ch @input-ch]
           (async/close! in-ch))
         (when-let [out-ch @output-ch]
           (async/close! out-ch))
         nil)

       (transport-send [_ message]
         (when-let [out-ch @output-ch]
           (async/>!! out-ch message))))))

;; ============================================================================
;; ClojureScript/Node.js Implementation - Line-based (simpler)
;; ============================================================================

#?(:cljs
   (do
     (defn- send-response!
       "Write a JSON response to stdout as a newline-delimited line.
       This is the MCP stdio convention (one JSON message per line).
       LSP/DAP stdio servers that need Content-Length framing should
       use a protocol-specific transport."
       [response]
       (when (some? response)
         (.write js/process.stdout
                 (str (platform/json-encode response) "\n"))))

     (defn- write-error-response!
       "Write a JSON-RPC error response to stdout."
       [request-id err]
       (send-response!
         {:jsonrpc "2.0"
          :id request-id
          :error {:code -32603
                  :message (str "Internal error: "
                                (platform/error-message err))}}))

     (defrecord StdioTransport [running?* readline-interface]
       core/Transport
       (transport-id [_] :stdio)

       (transport-start [this handler]
         (reset! running?* true)
         (let [readline (js/require "readline")
               rl (.createInterface readline
                    #js {:input js/process.stdin
                         :output js/process.stdout
                         :terminal false})]
           (reset! readline-interface rl)
           (.on rl "line"
                (fn [line]
                  ;; Each line is one JSON-RPC message. The callback body is
                  ;; synchronous Node code: parse → dispatch → unwrap → write.
                  ;; If the handler returns a js/Promise, we chain .then rather
                  ;; than blocking the event loop.
                  (platform/try-any
                    (let [request (platform/json-decode line)
                          result (handler request)]
                      (cond
                        ;; Plain value → write synchronously
                        (not (instance? js/Promise result))
                        (send-response! result)

                        ;; Promise → chain .then, write when resolved
                        :else
                        (-> result
                            (.then send-response!)
                            (.catch (fn [err]
                                      (write-error-response!
                                        (:id request) err))))))
                    (catch-any e
                      (.error js/console "Error processing request:" line)
                      (.error js/console (platform/error-message e))))))
           (.on rl "close"
                (fn []
                  (reset! running?* false))))
         nil)

       (transport-stop [_]
         (when-let [rl @readline-interface]
           (.close rl))
         (reset! running?* false))

       (transport-send [_ message]
         ;; For async notifications (e.g., progress, log messages) coming
         ;; from outside the request loop. MCP uses newline-delimited JSON.
         (send-response! message)))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn create-stdio-transport
  "Create a stdio transport for JSON-RPC protocols (MCP, LSP, DAP).

  JVM implementation features:
  - LSP Base Protocol message framing (Content-Length headers)
  - Thread-safe writes with locking
  - Dedicated I/O threads (not go-blocks)
  - core.async channels for message buffering
  - discarding-stdout protection in handler execution

  Options map (JVM only):
    :input-stream  - InputStream to read from (default System/in)
    :output-stream - OutputStream to write to (default System/out)
    :stderr        - Writer for error output (default *err*)

  Platform support:
  - JVM: Full LSP/MCP message framing
  - Node.js: Line-based with Content-Length output

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
   #?(:clj
      (->StdioTransport
        (atom false)                              ; running?*
        (atom nil)                                ; input-ch
        (atom nil)                                ; output-ch
        (atom (:input-stream opts))               ; input-stream
        (atom (:output-stream opts))              ; output-stream
        (atom (or (:stderr opts) *err*))          ; stderr
        (atom nil))                               ; process-thread

      :cljs
      (if (exists? js/process)
        (->StdioTransport (atom false) (atom nil))
        (throw (ex-info "Stdio transport not available in browser environment" {}))))))

;; ============================================================================
;; Utility - discarding-stdout for external use
;; ============================================================================

#?(:clj
   (defmacro with-discarded-stdout
     "Execute body with stdout writes discarded.

      Use this to wrap any code that might accidentally write to stdout
      when running in stdio mode. Essential for MCP/LSP compliance.

      Example:
        (with-discarded-stdout
          (some-library-fn-that-prints))"
     [& body]
     `(discarding-stdout ~@body)))