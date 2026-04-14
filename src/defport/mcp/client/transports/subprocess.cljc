(ns defport.mcp.client.transports.subprocess
  "Reference subprocess transport for the MCP client.

   Same shape as the LSP and DAP equivalents — spawn the MCP server
   as a child process and speak framed JSON-RPC over its stdio.
   Optional namespace per CLAUDE.md principle 5. Consumers who want
   a socket/WebSocket/in-process transport skip this file and plug
   in their own ClientTransport.

   ## Usage
   ```clojure
   (require '[defport.mcp.client :as mcp]
            '[defport.mcp.client.transports.subprocess :as sub])

   (def client
     (-> (sub/transport [\"mcp-server-filesystem\" \"/path/to/root\"])
         (mcp/create-client)
         (mcp/connect! {})))
   ```"
  (:require [defport.mcp.client :as client]
            [defport.transports.framing :as framing]
            [defport.util.platform :as platform :include-macros true])
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
           (.setName "defport.mcp.client.transports.subprocess/reader")
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
                           (tap> {:event :mcp.client.subprocess/stderr
                                  :text (String. ^bytes buf 0 (int n))}))
                         (when (>= n 0) (recur)))))))
           (.setDaemon true)
           (.setName "defport.mcp.client.transports.subprocess/stderr")
           (.start))))

     (defrecord SubprocessMcpTransport
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
       "Construct a SubprocessMcpTransport that runs `command`.

        Example:
          (transport [\"mcp-server-filesystem\" \"/path\"])
          (transport [\"npx\" \"-y\" \"@modelcontextprotocol/server-github\"])"
       [command]
       (->SubprocessMcpTransport (vec command)
                                 (atom nil)
                                 (atom false)
                                 (LinkedBlockingQueue.)
                                 (Object.)))))

#?(:cljs
   (do
     (defrecord SubprocessMcpTransportNode
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
           (.on (.-stdout proc) "data"
                (fn [chunk]
                  (let [[msgs new-decoder] (framing/feed @decoder* chunk)]
                    (reset! decoder* new-decoder)
                    (doseq [m msgs] (.push recv-queue m)))))
           (.on (.-stderr proc) "data"
                (fn [chunk]
                  (tap> {:event :mcp.client.subprocess/stderr
                         :text (.toString chunk "utf8")})))
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
       "Construct a SubprocessMcpTransportNode that runs `command`."
       [command]
       (->SubprocessMcpTransportNode (vec command)
                                     (atom nil)
                                     (atom false)
                                     (array)
                                     (atom nil)))))
