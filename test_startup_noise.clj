;; Test what Clojure outputs on startup before our code runs
;; This simulates what an MCP client would see

(ns test-startup-noise
  (:require [defport.transports.stdio :as stdio]
            [defport.core :as core]
            [cheshire.core :as json])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)))

;; The issue: By the time this code runs, Clojure may have already
;; printed to stdout (REPL banner, warnings, etc.)

;; We can't test the ACTUAL startup noise from within Clojure itself
;; because we're already past that point. We need to test from outside.

(println "If you see this message, stdout is being used incorrectly!")
(println "MCP/LSP clients expect ONLY Content-Length framed JSON on stdout")

;; What we CAN test: that once the transport starts, nothing else leaks
(let [out-stream (ByteArrayOutputStream.)
      in-stream (ByteArrayInputStream. (.getBytes "" "utf-8"))
      transport (stdio/create-stdio-transport {:input-stream in-stream
                                               :output-stream out-stream})]
  (core/transport-start transport (constantly nil))

  ;; Try various things that might print
  (binding [*out* *out*]  ; This should NOT affect the transport's output
    (println "This should go to real stdout, not transport"))

  ;; The transport's output should still be clean
  (core/transport-send transport {:jsonrpc "2.0" :id 1 :result {}})
  (Thread/sleep 100)

  (let [output (String. (.toByteArray out-stream) "utf-8")]
    (println "\n--- Transport output ---")
    (println output)
    (println "--- End output ---")
    (if (and (.startsWith output "Content-Length:")
             (not (.contains output "should")))
      (println "\nPASS: Transport output is clean")
      (println "\nFAIL: Transport output is polluted")))

  (core/transport-stop transport))

(System/exit 0)