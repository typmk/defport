(ns defport.bsp.client.transports.subprocess
  "Reference subprocess transport for the BSP client — spawns a
   build server (sbt, Mill, Bloop, Bazel's BSP server, etc.) and
   speaks Content-Length-framed JSON-RPC over its stdio. Mirrors
   defport.lsp.client.transports.subprocess."
  (:require [defport.bsp.client :as client]
            [defport.transports.framing :as framing])
  #?(:clj (:import [java.io InputStream OutputStream]
                   [java.lang ProcessBuilder Process]
                   [java.util.concurrent LinkedBlockingQueue TimeUnit])))

#?(:clj
   (do
     (defn- start-reader-thread!
       [^Process process queue alive?*]
       (let [in  ^InputStream (.getInputStream process)
             buf (byte-array 8192)]
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
           (.setName "defport.bsp.client.transports.subprocess/reader")
           (.start))))

     (defn- start-stderr-thread!
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
                           (tap> {:event :bsp.client.subprocess/stderr
                                  :text (String. ^bytes buf 0 (int n))}))
                         (when (>= n 0) (recur)))))))
           (.setDaemon true)
           (.setName "defport.bsp.client.transports.subprocess/stderr")
           (.start))))

     (defrecord SubprocessBspTransport
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
       "Construct a SubprocessBspTransport that runs `command`.

        Example:
          (transport [\"bloop\" \"bsp\"])
          (transport [\"sbt\" \"bspRun\"])
          (transport [\"mill\" \"mill.bsp.BSP/start\"])"
       [command]
       (->SubprocessBspTransport (vec command)
                                 (atom nil)
                                 (atom false)
                                 (LinkedBlockingQueue.)
                                 (Object.)))))

#?(:cljs
   (defn transport [_]
     (throw (ex-info
              "defport.bsp.client.transports.subprocess — Node variant not yet shipped."
              {:platform :cljs}))))
