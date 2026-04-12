(ns defport.util.edn
  "Simple EDN loading utilities for defport library.

  Applications using defport are responsible for their own config management.
  This namespace provides basic EDN loading helpers that apps can use."
  (:require [clojure.edn :as edn]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.pprint :as pprint])))

#?(:clj
   (defn load-edn-file
     "Load and parse EDN file from filesystem (JVM).

     path: absolute or relative file path

     Returns parsed EDN data structure.
     Throws exception if file not found or invalid EDN."
     [path]
     (with-open [r (io/reader path)]
       (edn/read (java.io.PushbackReader. r))))

   :cljs
   (defn load-edn-file
     "Load and parse EDN file from filesystem (Node.js only).

     path: absolute or relative file path

     Returns parsed EDN data structure.
     Throws exception if file not found or invalid EDN.
     Not available in browser environment."
     [path]
     (if (exists? js/require)
       (let [fs (js/require "fs")
             content (.readFileSync fs path "utf8")]
         (edn/read-string content))
       (throw (ex-info "File I/O not available in browser environment"
                      {:path path})))))

#?(:clj
   (defn load-edn-resource
     "Load EDN from classpath resource (JVM).

     resource-path: path relative to classpath root (e.g. 'defnet/tools.edn')

     Returns parsed EDN data structure or nil if resource not found."
     [resource-path]
     (when-let [resource (io/resource resource-path)]
       (load-edn-file (.getPath resource))))

   :cljs
   (defn load-edn-resource
     "Load EDN from compiled resources (CLJS).

     Note: In ClojureScript, resources must be explicitly required at compile time.
     This is a placeholder - real implementation requires build-time integration.

     Returns nil - apps should use load-edn-file with explicit paths."
     [resource-path]
     nil))

(defn load-edn
  "Load EDN from file path, resource, or return as-is if already a map.

  source can be:
  - String file path: 'resources/my-config.edn' (loaded from filesystem)
  - String with 'classpath:' prefix: 'classpath:defnet/tools.edn' (loaded from resources)
  - Map: {:foo :bar} (returned as-is)

  Returns EDN data structure.

  Examples:
    (load-edn 'config/server.edn')
    (load-edn 'classpath:defnet/protocols.edn')
    (load-edn {:port 9876})"
  [source]
  (cond
    (map? source)
    source

    (and (string? source) (.startsWith source "classpath:"))
    (load-edn-resource (subs source 10))

    (string? source)
    (load-edn-file source)

    :else
    (throw (ex-info "Invalid EDN source - must be file path, classpath:path, or map"
                   {:source source :type (type source)}))))

(defn parse-edn-string
  "Parse EDN from string.

  s: EDN string

  Returns parsed EDN data structure."
  [s]
  (edn/read-string s))

(defn edn->string
  "Convert EDN data structure to string.

  data: EDN data structure
  opts: optional map with:
    - :pretty? - pretty-print with indentation (default false)

  Returns EDN string."
  ([data]
   (edn->string data nil))
  ([data opts]
   (if (:pretty? opts)
     #?(:clj (with-out-str (pprint/pprint data))
        :cljs (pr-str data))  ; ClojureScript doesn't have pprint in core
     (pr-str data))))
