(ns defport.config
  "Simple configuration utilities for defport.

  This namespace provides basic config handling. Applications using defport
  are responsible for their own configuration management strategies
  (cascading configs, search paths, env var overrides, etc.).

  For a full-featured config system, see defnet's config.clj as an example."
  (:require [defport.util.edn :as edn]))

(defn load-config
  "Load configuration from EDN source.

  source can be:
  - String file path: 'config/server.edn'
  - String with 'classpath:' prefix: 'classpath:app/config.edn'
  - Map: {:port 9876} (returned as-is)

  defaults: optional map of default values (merged with loaded config)

  Returns config map (loaded config merged with defaults).

  Example:
    (load-config 'resources/server.edn' {:port 9876 :host 'localhost'})"
  ([source]
   (load-config source nil))
  ([source defaults]
   (let [loaded (edn/load-edn source)]
     (if defaults
       (merge defaults loaded)
       loaded))))

(defn get-in-config
  "Get nested value from config with optional default.

  config: config map
  path: vector of keys (e.g. [:server :port])
  default: value to return if path not found

  Returns value at path or default."
  ([config path]
   (get-in-config config path nil))
  ([config path default]
   (get-in config path default)))

(defn validate-config
  "Validate config against a schema.

  config: config map
  schema: validation schema (map with :required and :optional keys)

  schema format:
    {:required [:key1 :key2]          ; Keys that must exist
     :optional [:key3 :key4]          ; Keys that may exist
     :validate {:key1 pred-fn         ; Validation functions
                :key2 pred-fn}}

  Returns {:valid? true} or {:valid? false :errors [...]}

  Example:
    (validate-config config
      {:required [:port :host]
       :validate {:port #(and (number? %) (pos? %))}})"
  [config schema]
  (let [required (:required schema)
        validate-fns (:validate schema)
        errors (atom [])]

    ;; Check required keys
    (doseq [k required]
      (when-not (contains? config k)
        (swap! errors conj {:type :missing-required
                           :key k
                           :message (str "Required key " k " not found")})))

    ;; Validate values
    (doseq [[k pred-fn] validate-fns]
      (when (contains? config k)
        (when-not (pred-fn (get config k))
          (swap! errors conj {:type :validation-failed
                             :key k
                             :value (get config k)
                             :message (str "Validation failed for " k)}))))

    (if (empty? @errors)
      {:valid? true}
      {:valid? false :errors @errors})))

(defn merge-configs
  "Deep merge multiple config maps.

  Right-most maps take precedence.

  Example:
    (merge-configs defaults user-config env-overrides)"
  [& configs]
  (apply merge-with
         (fn [a b]
           (if (and (map? a) (map? b))
             (merge-configs a b)
             b))
         configs))

;; =============================================================================
;; Performance Configuration Schema
;; =============================================================================

(def performance-config-schema
  "Schema for performance-related configuration options.

  Includes batch processing concurrency strategies and limits."
  {:batch-processing
   {:enabled {:type :boolean
              :default false
              :description "Enable concurrent batch processing"}
    :strategy {:type :keyword
               :enum #{:sequential :pmap :futures :core-async}
               :default :sequential
               :description "Batch processing strategy"}
    :max-concurrency {:type :integer
                      :default 10
                      :min 1
                      :max 100
                      :description "Max concurrent operations (for :core-async)"}
    :timeout-ms {:type :integer
                 :default 30000
                 :min 1000
                 :max 300000
                 :description "Timeout per item or overall (milliseconds)"}}})

(def performance-config-defaults
  "Default values for performance configuration."
  {:batch-processing
   {:enabled false
    :strategy :sequential
    :max-concurrency 10
    :timeout-ms 30000}})

(defn valid-batch-strategy?
  "Check if batch strategy is valid.

  Args:
    strategy - Keyword to check

  Returns:
    Boolean - true if strategy is valid"
  [strategy]
  (contains? #{:sequential :pmap :futures :core-async} strategy))

(defn validate-performance-config
  "Validate performance configuration.

  Args:
    config - Performance config map (may be nested under :performance key)

  Returns:
    {:valid? true} or {:valid? false :errors [...]}

  Example:
    (validate-performance-config
      {:batch-processing
       {:enabled true
        :strategy :pmap
        :max-concurrency 10
        :timeout-ms 30000}})"
  [config]
  (let [batch-config (get config :batch-processing {})
        errors (atom [])]

    ;; Validate enabled (must be boolean)
    (when (contains? batch-config :enabled)
      (when-not (boolean? (:enabled batch-config))
        (swap! errors conj {:type :validation-failed
                           :key :enabled
                           :value (:enabled batch-config)
                           :message "enabled must be boolean"})))

    ;; Validate strategy (must be valid keyword)
    (when (contains? batch-config :strategy)
      (let [strategy (:strategy batch-config)]
        (when-not (and (keyword? strategy)
                       (valid-batch-strategy? strategy))
          (swap! errors conj {:type :validation-failed
                             :key :strategy
                             :value strategy
                             :message "strategy must be one of: :sequential :pmap :futures :core-async"}))))

    ;; Validate max-concurrency (1-100)
    (when (contains? batch-config :max-concurrency)
      (let [mc (:max-concurrency batch-config)]
        (when-not (and (integer? mc) (>= mc 1) (<= mc 100))
          (swap! errors conj {:type :validation-failed
                             :key :max-concurrency
                             :value mc
                             :message "max-concurrency must be integer between 1 and 100"}))))

    ;; Validate timeout-ms (1000-300000)
    (when (contains? batch-config :timeout-ms)
      (let [timeout (:timeout-ms batch-config)]
        (when-not (and (integer? timeout) (>= timeout 1000) (<= timeout 300000))
          (swap! errors conj {:type :validation-failed
                             :key :timeout-ms
                             :value timeout
                             :message "timeout-ms must be integer between 1000 and 300000"}))))

    (if (empty? @errors)
      {:valid? true}
      {:valid? false :errors @errors})))

(defn normalize-performance-config
  "Normalize performance configuration with defaults.

  Merges user config with defaults and validates.

  Args:
    config - User performance config (may be partial)

  Returns:
    Normalized config map with all defaults filled in

  Example:
    (normalize-performance-config
      {:batch-processing {:enabled true :strategy :pmap}})
    ;; => {:batch-processing {:enabled true
    ;;                        :strategy :pmap
    ;;                        :max-concurrency 10
    ;;                        :timeout-ms 30000}}"
  [config]
  (let [normalized (merge-configs performance-config-defaults config)
        validation (validate-performance-config normalized)]
    (if (:valid? validation)
      normalized
      (throw (ex-info "Invalid performance configuration"
                      {:validation validation
                       :config config})))))

(comment
  ;; Usage examples

  ;; Enable concurrent batch processing
  (normalize-performance-config
   {:batch-processing
    {:enabled true
     :strategy :pmap}})

  ;; With timeout
  (normalize-performance-config
   {:batch-processing
    {:enabled true
     :strategy :futures
     :timeout-ms 30000}})

  ;; With concurrency limit
  (normalize-performance-config
   {:batch-processing
    {:enabled true
     :strategy :core-async
     :max-concurrency 10}})

  ;; Validation
  (validate-performance-config
   {:batch-processing
    {:enabled true
     :strategy :invalid}})
  ;; => {:valid? false :errors [...]}
  )
