(ns defport.util.platform
  "Platform abstractions for cross-platform code.

  Provides unified APIs for common platform-specific operations:
  - JSON encoding/decoding
  - UUID generation
  - Timestamps
  - Delays/sleep
  - Number parsing
  - Datafy/nav introspection

  All functions work identically on JVM and ClojureScript (Node.js/Browser)."
  #?(:clj (:require [cheshire.core :as json]
                    [clojure.core.protocols :as protocols]
                    [clojure.datafy :as datafy])
     :cljs (:require [cljs.reader :as reader])))

;; ============================================================================
;; JSON
;; ============================================================================

(defn json-encode
  "Encode data structure to JSON string.

  data: Clojure data structure
  opts: optional map (JVM only) passed to cheshire

  Returns JSON string."
  ([data]
   (json-encode data nil))
  ([data opts]
   #?(:clj (json/generate-string data opts)
      :cljs (js/JSON.stringify (clj->js data)))))

(defn json-decode
  "Decode JSON string to data structure.

  s: JSON string
  opts: optional map
    :keywordize-keys - convert keys to keywords (default true)

  Returns Clojure data structure."
  ([s]
   (json-decode s {:keywordize-keys true}))
  ([s opts]
   #?(:clj (json/parse-string s (:keywordize-keys opts true))
      :cljs (js->clj (js/JSON.parse s) :keywordize-keys (:keywordize-keys opts true)))))

;; ============================================================================
;; UUID
;; ============================================================================

(defn uuid
  "Generate a random UUID.

  Returns UUID as string."
  []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))))

;; ============================================================================
;; Time
;; ============================================================================

(defn now-ms
  "Get current timestamp in milliseconds since epoch.

  Returns long/number."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn now-iso
  "Get current timestamp as ISO-8601 string.

  Returns string like '2025-01-15T10:30:00.000Z'."
  []
  #?(:clj (.format (java.time.format.DateTimeFormatter/ISO_INSTANT)
                   (java.time.Instant/now))
     :cljs (.toISOString (js/Date.))))

(defn now-str
  "Get current timestamp as human-readable string.

  Returns string."
  []
  #?(:clj (str (java.util.Date.))
     :cljs (.toString (js/Date.))))

;; ============================================================================
;; Delays / Sleep
;; ============================================================================

(defn sleep
  "Sleep/delay for specified milliseconds.

  JVM: Blocks current thread (synchronous).
  CLJS: Returns a Promise that resolves after delay (asynchronous).

  ms: milliseconds to sleep

  Returns nil (JVM) or Promise (CLJS)."
  [ms]
  #?(:clj (Thread/sleep ms)
     :cljs (js/Promise. (fn [resolve] (js/setTimeout resolve ms)))))

(defn sleep-sync
  "Synchronous sleep - blocks execution.

  JVM: Same as sleep.
  CLJS: Not truly synchronous - logs warning and returns immediately.
        Use sleep with async/await patterns in CLJS.

  ms: milliseconds to sleep"
  [ms]
  #?(:clj (Thread/sleep ms)
     :cljs (do
             (js/console.warn "sleep-sync called in CLJS - use async patterns instead")
             nil)))

;; ============================================================================
;; Number Parsing
;; ============================================================================

(defn parse-int
  "Parse string to integer.

  s: string to parse
  default: value to return on parse failure (default nil)

  Returns integer or default."
  ([s]
   (parse-int s nil))
  ([s default]
   (try
     #?(:clj (Integer/parseInt s)
        :cljs (let [n (js/parseInt s 10)]
                (if (js/isNaN n) default n)))
     (catch #?(:clj Exception :cljs js/Error) _
       default))))

(defn parse-float
  "Parse string to floating point number.

  s: string to parse
  default: value to return on parse failure (default nil)

  Returns float/number or default."
  ([s]
   (parse-float s nil))
  ([s default]
   (try
     #?(:clj (Double/parseDouble s)
        :cljs (let [n (js/parseFloat s)]
                (if (js/isNaN n) default n)))
     (catch #?(:clj Exception :cljs js/Error) _
       default))))

;; ============================================================================
;; Environment
;; ============================================================================

(defn get-env
  "Get environment variable.

  name: environment variable name
  default: value to return if not set (default nil)

  Returns string or default.

  Note: Browser environment returns default (no env vars)."
  ([name]
   (get-env name nil))
  ([name default]
   #?(:clj (or (System/getenv name) default)
      :cljs (if (exists? js/process)
              (or (aget js/process.env name) default)
              default))))

;; ============================================================================
;; Platform Detection
;; ============================================================================

(def platform
  "Current platform: :jvm, :node, or :browser"
  #?(:clj :jvm
     :cljs (if (exists? js/process)
             :node
             :browser)))

(defn jvm? [] (= platform :jvm))
(defn node? [] (= platform :node))
(defn browser? [] (= platform :browser))
(defn server? [] (or (jvm?) (node?)))

;; ============================================================================
;; Stderr Output (for servers)
;; ============================================================================

(defn eprintln
  "Print to stderr (for server logging in stdio mode).

  args: values to print

  JVM: Prints to System/err
  Node.js: Prints to process.stderr
  Browser: Uses console.error"
  [& args]
  #?(:clj (binding [*out* *err*]
            (apply println args))
     :cljs (if (exists? js/process)
             (.write js/process.stderr (str (apply str (interpose " " args)) "\n"))
             (apply js/console.error args))))

;; ============================================================================
;; Datafy / Nav (REPL Introspection)
;; ============================================================================

(def datafiable-protocol
  "Reference to platform's Datafiable protocol.

   JVM: clojure.core.protocols/Datafiable
   CLJS: cljs.core/IDatafiable

   Use this when you need to reference the protocol directly,
   e.g., for extend-type metadata."
  #?(:clj protocols/Datafiable
     :cljs cljs.core/IDatafiable))

(defn datafy-value
  "Convert object to navigable data representation.

   Calls the platform-appropriate datafy implementation.
   Objects can implement Datafiable/IDatafiable to customize
   their data representation.

   obj: object to convert to data

   Returns data representation of obj."
  [obj]
  #?(:clj (datafy/datafy obj)
     :cljs (if (satisfies? cljs.core/IDatafiable obj)
             (cljs.core/-datafy obj)
             obj)))

(defn nav-value
  "Navigate to a value within a datafied context.

   Used to lazily navigate from a datafied representation
   to related data. Implementations can return transformed
   or lazily-loaded values.

   coll: the datafied collection/context
   k: key/index being navigated to (or nil)
   v: the value at k

   Returns (possibly transformed) v in context of coll and k."
  [coll k v]
  #?(:clj (datafy/nav coll k v)
     :cljs (if (satisfies? cljs.core/INavigable coll)
             (cljs.core/-nav coll k v)
             v)))