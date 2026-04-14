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
  (:require #?(:clj  [cheshire.core :as json])
            #?(:cljs [cljs.reader :as reader])
            [clojure.core.protocols :as protocols]
            [clojure.datafy :as datafy]))

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

(defn process-id
  "Current process ID, or nil if unavailable.

  JVM: java.lang.ProcessHandle/current
  Node: js/process.pid
  Browser: nil"
  []
  #?(:clj (.pid (java.lang.ProcessHandle/current))
     :cljs (when (exists? js/process) (.-pid js/process))))

(defn utf8-byte-length
  "Byte length of a string when encoded as UTF-8.

  Used for Content-Length framing in LSP/DAP protocols where the header
  must report exact bytes, not characters.

  JVM: (.getBytes s \"UTF-8\") byte count.
  Node: Buffer.byteLength(s, 'utf8').
  Browser fallback: TextEncoder."
  [^String s]
  #?(:clj  (count (.getBytes s "UTF-8"))
     :cljs (if (and (exists? js/Buffer) (.-byteLength js/Buffer))
             (.byteLength js/Buffer s "utf8")
             ;; Browser fallback: TextEncoder
             (.-length (.encode (js/TextEncoder.) s)))))

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
;; Unwrappable — user-supplied async, without hard dependencies
;; ============================================================================
;;
;; Port handlers are synchronous by contract: (fn [context] result). But
;; sometimes a handler wants to return an async type — a Clojure future, a
;; manifold deferred, a js/Promise, a core.async channel — and have defport
;; resolve it transparently before formatting the protocol response.
;;
;; `unwrap` is the extension point. It takes anything and returns a synchronous
;; value, blocking if necessary (JVM) or throwing on CLJS if the underlying
;; type requires an event-loop turn that can't happen synchronously.
;;
;; Feature detection: no hard dependency on manifold / promesa / core.async.
;; If the user has them on the classpath and returns their types, unwrap does
;; the right thing. Otherwise, plain values pass through unchanged.

#?(:clj
   (def ^:private manifold-deferred?
     "Resolved lazily so manifold stays an optional dep."
     (delay (try (requiring-resolve 'manifold.deferred/deferred?)
                 (catch Throwable _ nil)))))

#?(:clj
   (def ^:private manifold-deref
     (delay (try (requiring-resolve 'manifold.deferred/success-value)
                 (catch Throwable _ nil)))))

(defn unwrap
  "Synchronously resolve a value that may be an async/deferred type.

  Handles:
  - Plain values → returned as-is
  - JVM: clojure.lang.IDeref (promise, future, delay, atom, reify IDeref)
  - JVM: manifold.deferred/Deferred (via feature detection, no hard dep)
  - CLJS: js/Promise → throws with a clear error message. Use `then-unwrap`
          or wrap your handler to produce a value before returning.
  - Anything else → returned as-is

  On JVM, blocks indefinitely (or until the underlying primitive resolves).
  For bounded waits, unwrap upstream before passing to defport.

  The intent: user handlers may optionally return these types and defport's
  dispatch will transparently resolve them. Synchronous handlers are the
  common case and pass through unchanged."
  [value]
  (cond
    (nil? value)
    nil

    #?(:clj (instance? clojure.lang.IDeref value)
       :cljs false)
    #?(:clj (deref value) :cljs value)

    #?@(:clj [(when-let [pred @manifold-deferred?]
                (pred value))
              (deref value)])

    #?@(:cljs [(instance? js/Promise value)
               (throw (ex-info
                       "Cannot synchronously unwrap a js/Promise. Either await it
                        inside your handler before returning, or use an async
                        transport that supports handler Promise returns."
                       {:value value}))])

    :else
    value))

;; ============================================================================
;; Exception Handling
;; ============================================================================

(defn error-message
  "Get exception message cross-platform.

  JVM: calls .getMessage on a Throwable.
  CLJS: reads .-message on a js/Error.

  Returns string (may be nil).

  Named `error-message` rather than `ex-message` to avoid shadowing
  clojure.core/ex-message (which works on ex-info maps, not general errors)."
  [e]
  #?(:clj (.getMessage ^Throwable e)
     :cljs (.-message e)))

(defn error-type
  "Get exception type name cross-platform.

  JVM: class name of the Throwable.
  CLJS: constructor name of the error.

  Returns string."
  [e]
  #?(:clj (.getName (class e))
     :cljs (.-name (type e))))

(defmacro try-any
  "Cross-platform try with a catch-any handler.

  Catches any error: Throwable on JVM, :default on ClojureScript.
  Supports an optional trailing finally clause.

  Usage:
    (try-any
      (risky-operation)
      (catch-any e
        (handle-error e))
      (finally
        (cleanup)))

  The catch-any form must appear; finally is optional.
  Detects CLJS compilation via (:ns &env) — no reader conditional
  is needed at call sites."
  {:style/indent 0}
  [& forms]
  (let [catch-form (first (filter #(and (seq? %) (= 'catch-any (first %))) forms))
        finally-form (first (filter #(and (seq? %) (= 'finally (first %))) forms))
        try-body (remove #(or (identical? catch-form %)
                              (identical? finally-form %))
                         forms)
        catch-type (if (:ns &env) :default 'Throwable)]
    (when-not catch-form
      (throw (ex-info "try-any requires a (catch-any binding body...) form"
                      {:forms forms})))
    (let [[_ binding & catch-body] catch-form]
      `(try
         ~@try-body
         (catch ~catch-type ~binding
           ~@catch-body)
         ~@(when finally-form [finally-form])))))

;; ============================================================================
;; Datafy / Nav (REPL Introspection)
;; ============================================================================

;; CLJS and Clojure both use clojure.core.protocols/Datafiable and
;; clojure.datafy/datafy. CLJS ships this in its core since 1.10.
;; No reader conditionals needed.

(def datafiable-protocol
  "Reference to the Datafiable protocol — same on JVM and CLJS.

   Use this in extend-type / extend-protocol when you need a reference
   to the protocol itself (rather than its methods)."
  protocols/Datafiable)

(defn datafy-value
  "Convert object to navigable data representation.

   Calls clojure.datafy/datafy on both JVM and CLJS.
   Objects can implement clojure.core.protocols/Datafiable to customize
   their data representation."
  [obj]
  (datafy/datafy obj))

(defn nav-value
  "Navigate to a value within a datafied context.

   Calls clojure.datafy/nav on both JVM and CLJS."
  [coll k v]
  (datafy/nav coll k v))

;; ============================================================================
;; URL encoding / decoding
;; ============================================================================

(defn url-encode
  "Percent-encode a string for use in a URI."
  [s]
  #?(:clj  (java.net.URLEncoder/encode ^String s "UTF-8")
     :cljs (js/encodeURIComponent s)))

(defn url-decode
  "Percent-decode a URI-encoded string."
  [s]
  #?(:clj  (java.net.URLDecoder/decode ^String s "UTF-8")
     :cljs (js/decodeURIComponent s)))

;; ============================================================================
;; Base64 encoding / decoding
;; ============================================================================

(defn base64-encode
  "Encode binary data (bytes on JVM, Uint8Array/Buffer on Node) to Base64.

   Accepts a byte array on the JVM or anything js/Buffer.from can consume
   on Node."
  [data]
  #?(:clj  (.encodeToString (java.util.Base64/getEncoder) ^bytes data)
     :cljs (if (exists? js/Buffer)
             (.toString (.from js/Buffer data) "base64")
             (js/btoa (apply str (map char data))))))

(defn base64-decode
  "Decode a Base64 string to binary data (byte array on JVM, Buffer on Node)."
  [s]
  #?(:clj  (.decode (java.util.Base64/getDecoder) ^String s)
     :cljs (if (exists? js/Buffer)
             (.from js/Buffer s "base64")
             (let [binary (js/atob s)]
               (js/Uint8Array. (map #(.charCodeAt % 0) binary))))))