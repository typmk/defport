(ns defport.transports.stdio
  "Stdio transport for JSON-RPC protocols.

  Reads JSON-RPC messages from stdin, writes responses to stdout.
  Platform-agnostic using reader conditionals for JVM vs Node.js."
  (:require [defport.core :as core]
            [cheshire.core :as json]
            #?(:clj [clojure.java.io :as io])))

#?(:clj
   (defrecord StdioTransport [running?* stdout stderr]
     core/Transport
     (transport-id [_] :stdio)

     (transport-start [this handler]
       (reset! running?* true)
       (let [in (io/reader *in*)]
         (try
           (loop []
             (when @running?*
               (when-let [line (.readLine in)]
                 (try
                   (let [request (json/parse-string line true)
                         response (handler request)]
                     (when response
                       (core/transport-send this response)))
                   (catch Exception e
                     (binding [*out* stderr]
                       (println "Error processing request:" (.getMessage e))
                       (.printStackTrace e)))))
               (recur)))
           (catch Exception e
             (binding [*out* stderr]
               (println "Stdio transport error:" (.getMessage e))
               (.printStackTrace e)))))
       nil)

     (transport-stop [_]
       (reset! running?* false))

     (transport-send [_ message]
       (binding [*out* stdout]
         (println (json/generate-string message))
         (.flush *out*))))

   :cljs
   (defrecord StdioTransport [running?* readline-interface]
     core/Transport
     (transport-id [_] :stdio)

     (transport-start [this handler]
       (reset! running?* true)
       (let [readline (js/require "readline")
             rl (readline.createInterface
                  #js {:input js/process.stdin
                       :output js/process.stdout
                       :terminal false})]
         (reset! readline-interface rl)
         (.on rl "line"
              (fn [line]
                (try
                  (let [request (js->clj (js/JSON.parse line) :keywordize-keys true)
                        response (handler request)]
                    (when response
                      (core/transport-send this response)))
                  (catch js/Error e
                    (.error js/console.error "Error processing request:" (.-message e))))))
         (.on rl "close"
              (fn []
                (reset! running?* false))))
       nil)

     (transport-stop [_]
       (when-let [rl @readline-interface]
         (.close rl))
       (reset! running?* false))

     (transport-send [_ message]
       (.log js/console (js/JSON.stringify (clj->js message))))))

(defn create-stdio-transport
  "Create a stdio transport for JSON-RPC protocols.

  opts: optional map with:
    - :stdout - output stream (default *out*)
    - :stderr - error stream (default *err*)

  Returns StdioTransport instance.

  Platform support:
  - JVM: Full support
  - Node.js: Full support
  - Browser: Not supported (no stdin/stdout)

  Example:
    (def transport (create-stdio-transport))
    (transport-start transport my-handler)"
  ([]
   (create-stdio-transport nil))
  ([opts]
   #?(:clj
      (let [stdout (or (:stdout opts) *out*)
            stderr (or (:stderr opts) *err*)]
        (->StdioTransport (atom false) stdout stderr))

      :cljs
      (if (exists? js/process)
        (->StdioTransport (atom false) (atom nil))
        (throw (ex-info "Stdio transport not available in browser environment" {}))))))
