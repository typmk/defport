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
