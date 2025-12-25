(ns defport.transports.stdio-jsonlines
  "JSON Lines (newline-delimited JSON) stdio transport for MCP.

  Some MCP clients (like Claude Code) send messages as newline-delimited JSON
  instead of using LSP-style Content-Length framing.

  This transport reads JSON objects separated by newlines and writes responses
  the same way."
  (:require [defport.core :as core]
            [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import (java.io
             BufferedReader
             InputStream
             OutputStream
             PrintWriter
             InputStreamReader
             OutputStreamWriter)))

(set! *warn-on-reflection* true)

;; ============================================================================
;; Message Reading (JSON Lines)
;; ============================================================================

(defn- read-json-line
  "Read a single JSON line from input. Blocks waiting for input.
   Returns parsed JSON map, :eof on end of stream, or :error on parse failure."
  [^BufferedReader reader]
  (try
    (when-let [line (.readLine reader)]
      (if (empty? line)
        :empty  ; blank line, skip
        (try
          (json/parse-string line true)
          (catch Exception e
            (binding [*out* *err*]
              (println "JSON parse error:" (.getMessage e))
              (println "Line:" line))
            :error))))
    (catch Exception e
      :eof)))

;; ============================================================================
;; Message Writing (JSON Lines)
;; ============================================================================

(def ^:private write-lock (Object.))

(defn- write-json-line
  "Write a JSON message as a single line.
   Thread-safe via write-lock."
  [^PrintWriter writer msg]
  (locking write-lock
    (.println writer (json/generate-string msg))
    (.flush writer)))

;; ============================================================================
;; Transport Implementation
;; ============================================================================

(defrecord JsonLinesStdioTransport [running?*
                                     reader*
                                     writer*
                                     stderr*]
  core/Transport
  (transport-id [_] :stdio-jsonlines)

  (transport-start [_ handler]
    (reset! running?* true)
    (let [reader @reader*
          writer @writer*]
      ;; Simple blocking loop - read JSON line, handle, write response
      (loop []
        (when @running?*
          (let [msg (read-json-line reader)]
            (cond
              ;; EOF - connection closed
              (= msg :eof)
              (reset! running?* false)

              ;; Parse error or empty line - skip
              (or (= msg :error) (= msg :empty))
              (recur)

              ;; Valid message - handle and respond
              :else
              (do
                (try
                  (when-let [response (handler msg)]
                    (write-json-line writer response))
                  (catch Exception e
                    (binding [*out* @stderr*]
                      (println "Handler error:" (.getMessage e)))))
                (recur))))))
      nil))

  (transport-stop [_]
    (reset! running?* false)
    nil)

  (transport-send [_ message]
    (when-let [writer @writer*]
      (write-json-line writer message))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn create-jsonlines-transport
  "Create a JSON Lines (newline-delimited) stdio transport for MCP.

  This transport reads and writes JSON messages separated by newlines,
  which is used by some MCP clients like Claude Code.

  Options map:
    :input-stream  - InputStream to read from (default System/in)
    :output-stream - OutputStream to write to (default System/out)
    :stderr        - Writer for error output (default *err*)

  Example:
    (def transport (create-jsonlines-transport))

    ;; Custom streams (for testing)
    (def transport (create-jsonlines-transport
                     {:input-stream test-input
                      :output-stream test-output}))"
  ([]
   (create-jsonlines-transport nil))
  ([opts]
   (let [input (or (:input-stream opts) System/in)
         output (or (:output-stream opts) System/out)
         reader (BufferedReader. (InputStreamReader. ^InputStream input "UTF-8"))
         writer (PrintWriter. (OutputStreamWriter. ^OutputStream output "UTF-8") true)]
     (->JsonLinesStdioTransport
       (atom false)                           ; running?*
       (atom reader)                          ; reader*
       (atom writer)                          ; writer*
       (atom (or (:stderr opts) *err*))))))   ; stderr*
