(ns defport.lsp.client.transports.subprocess
  "Reference subprocess transport for the LSP client.

   Spawns an external LSP server (rust-analyzer, clojure-lsp,
   typescript-language-server, ...) via java.lang.ProcessBuilder on
   the JVM and reads/writes Content-Length framed JSON-RPC over its
   stdio.

   This namespace is *optional* — defport.lsp.client itself ships
   no implementations, only the ClientTransport protocol. Consumers
   who want their own transport (raw socket, in-process pipe, etc.)
   skip requiring this namespace and write their own. Per CLAUDE.md
   principle 5 / 6, the platform-specific glue lives at the edge in
   this file behind whole-function reader conditionals.

   ## Usage
   ```clojure
   (require '[defport.lsp.client :as lsp]
            '[defport.lsp.client.transports.subprocess :as sub])

   (def client
     (-> (sub/transport [\"clojure-lsp\"])
         (lsp/create-client)
         (lsp/connect! {:root-uri \"file:///path/to/project\"})))
   ```

   The CLJS Node version (`child_process.spawn`) is not yet shipped
   — it would live in this same file behind a `:cljs` reader
   conditional once needed."
  (:require [defport.lsp.client :as client]
            [defport.transports.framing :as framing]
            [defport.util.platform :as platform :include-macros true])
  #?(:clj (:import [java.io InputStream OutputStream]
                   [java.lang ProcessBuilder Process]
                   [java.util.concurrent LinkedBlockingQueue TimeUnit])))

#?(:clj
   (do
     ;; ----------------------------------------------------------------------
     ;; Reader thread
     ;; ----------------------------------------------------------------------
     ;; The reader thread blocks on the subprocess's stdout, feeds bytes
     ;; into the framing decoder, and pushes parsed messages onto a
     ;; LinkedBlockingQueue. transport-recv! polls the queue with no
     ;; wait, returning ::client/no-message if empty. This way the LSP
     ;; client's driver loop never blocks the dispatcher thread waiting
     ;; for a frame.

     (defn- start-reader-thread!
       [^Process process queue alive?*]
       (let [in    ^InputStream (.getInputStream process)
             buf   (byte-array 8192)]
         (doto (Thread.
                 ^Runnable
                 (fn []
                   (loop [decoder (framing/empty-state)]
                     (when @alive?*
                       (let [n (try (.read in buf 0 (alength buf))
                                    (catch Exception _ -1))]
                         (cond
                           (neg? n)
                           (do (reset! alive?* false)
                               (.put ^LinkedBlockingQueue queue ::client/eof))

                           (zero? n)
                           (do (Thread/sleep 1) (recur decoder))

                           :else
                           (let [chunk (byte-array n)]
                             (System/arraycopy buf 0 chunk 0 n)
                             (let [[msgs new-decoder] (framing/feed decoder chunk)]
                               (doseq [m msgs]
                                 (.put ^LinkedBlockingQueue queue m))
                               (recur new-decoder)))))))))
           (.setDaemon true)
           (.setName "defport.lsp.client.transports.subprocess/reader")
           (.start))))

     (defn- start-stderr-thread!
       "Drain the subprocess's stderr to tap> so it doesn't backpressure."
       [^Process process alive?*]
       (let [err ^InputStream (.getErrorStream process)
             buf (byte-array 4096)]
         (doto (Thread.
                 ^Runnable
                 (fn []
                   (loop []
                     (when @alive?*
                       (let [n (try (.read err buf 0 (alength buf))
                                    (catch Exception _ -1))]
                         (when (pos? n)
                           (tap> {:event :lsp.client.subprocess/stderr
                                  :text (String. ^bytes buf 0 (int n))}))
                         (when (>= n 0) (recur)))))))
           (.setDaemon true)
           (.setName "defport.lsp.client.transports.subprocess/stderr")
           (.start))))

     ;; ----------------------------------------------------------------------
     ;; Transport record
     ;; ----------------------------------------------------------------------

     (defrecord SubprocessLspTransport
                [command process* alive?* recv-queue write-lock]
       client/ClientTransport
       (transport-start! [this]
         (let [pb (ProcessBuilder. ^java.util.List command)
               _ (.redirectErrorStream pb false)
               proc (.start pb)]
           (reset! process* proc)
           (reset! alive?* true)
           (start-reader-thread! proc recv-queue alive?*)
           (start-stderr-thread! proc alive?*)
           this))

       (transport-send! [this msg]
         (when-let [^Process proc @process*]
           (let [^OutputStream out (.getOutputStream proc)
                 ^bytes encoded (framing/encode-message msg)]
             (locking write-lock
               (.write out encoded)
               (.flush out))))
         this)

       (transport-recv! [_]
         (let [m (.poll ^LinkedBlockingQueue recv-queue
                        0 TimeUnit/MILLISECONDS)]
           (cond
             (nil? m)             ::client/no-message
             (= m ::client/eof)   ::client/eof
             :else                m)))

       (transport-stop! [_]
         (reset! alive?* false)
         (when-let [^Process proc @process*]
           (try (.close (.getOutputStream proc)) (catch Exception _))
           (try (.destroy proc) (catch Exception _))
           (reset! process* nil))
         nil)

       (transport-alive? [_]
         (boolean (and @alive?* @process* (.isAlive ^Process @process*)))))

     (defn transport
       "Construct a SubprocessLspTransport that runs `command` (a
        sequential of strings: program + arguments). The process is
        NOT started yet — defport.lsp.client/connect! starts it.

        Example:
          (transport [\"clojure-lsp\"])
          (transport [\"rust-analyzer\" \"--log-file\" \"ra.log\"])"
       [command]
       (->SubprocessLspTransport (vec command)
                                 (atom nil)
                                 (atom false)
                                 (LinkedBlockingQueue.)
                                 (Object.)))))

#?(:cljs
   (do
     ;; ----------------------------------------------------------------------
     ;; Node child_process implementation
     ;; ----------------------------------------------------------------------
     ;; Node is event-driven — there's no reader thread. proc.stdout
     ;; emits 'data' events that we feed into the framing decoder; parsed
     ;; messages are pushed into a JS array used as a queue. The LSP
     ;; client's CLJS driver loop polls the queue via transport-recv!
     ;; and reschedules itself with js/setImmediate.

     (defrecord SubprocessLspTransportNode
                [command process* alive?* recv-queue decoder*]
       client/ClientTransport
       (transport-start! [this]
         (let [child-process (js/require "child_process")
               proc (.spawn child-process
                            (first command)
                            (clj->js (vec (rest command)))
                            #js {:stdio "pipe"})]
           (reset! process* proc)
           (reset! alive?* true)
           (reset! decoder* (framing/empty-state))
           ;; Wire up incoming data → framing → queue
           (.on (.-stdout proc) "data"
                (fn [chunk]
                  (let [[msgs new-decoder] (framing/feed @decoder* chunk)]
                    (reset! decoder* new-decoder)
                    (doseq [m msgs] (.push recv-queue m)))))
           ;; Drain stderr to tap>
           (.on (.-stderr proc) "data"
                (fn [chunk]
                  (tap> {:event :lsp.client.subprocess/stderr
                         :text (.toString chunk "utf8")})))
           ;; Mark dead on exit
           (.on proc "exit"
                (fn [_code _signal]
                  (reset! alive?* false)
                  (.push recv-queue ::client/eof)))
           this))

       (transport-send! [this msg]
         (when-let [proc @process*]
           (let [encoded (framing/encode-message msg)]
             (.write (.-stdin proc) encoded)))
         this)

       (transport-recv! [_]
         (if (zero? (.-length recv-queue))
           ::client/no-message
           (let [m (.shift recv-queue)]
             (if (= m ::client/eof) ::client/eof m))))

       (transport-stop! [_]
         (reset! alive?* false)
         (when-let [proc @process*]
           (try (.end (.-stdin proc)) (catch :default _))
           (try (.kill proc) (catch :default _))
           (reset! process* nil))
         nil)

       (transport-alive? [_]
         (boolean (and @alive?* @process*))))

     (defn transport
       "Construct a SubprocessLspTransportNode that runs `command` (a
        sequential of strings: program + arguments).

        Example:
          (transport [\"clojure-lsp\"])
          (transport [\"rust-analyzer\" \"--log-file\" \"ra.log\"])"
       [command]
       (->SubprocessLspTransportNode (vec command)
                                     (atom nil)
                                     (atom false)
                                     (array)
                                     (atom nil)))))
