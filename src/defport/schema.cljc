(ns defport.schema
  "Malli schema integration for defport.

  Provides utilities for converting Malli schemas to JSON Schema
  and integrating with the progressive disclosure DSL.

  Example usage:
    (require '[defport.schema :as schema])

    ;; Convert Malli to JSON Schema
    (schema/malli->json-schema [:string {:min 1 :max 500}])
    ;; => {:type \"string\" :minLength 1 :maxLength 500}

    ;; Define reusable schemas
    (def registry (schema/create-schema-registry))
    (schema/register-schema! registry :search-params
      [:map
       [:query [:string {:min 1}]]
       [:limit {:optional true} [:int {:min 1 :max 100}]]])

    ;; Validate input
    (schema/validate-input [:string {:min 1}] \"hello\")
    ;; => nil (valid)
    (schema/validate-input [:string {:min 1}] \"\")
    ;; => explanation map (invalid)"
  (:require [malli.core :as m]
            [malli.json-schema :as mjs]
            [malli.error :as me]))

;; ============================================================================
;; Schema Registry
;; ============================================================================

(defn create-schema-registry
  "Create a registry of named schemas for reuse.

  Returns an atom containing a map of schema-name -> schema.

  Example:
    (def registry (create-schema-registry))
    (register-schema! registry :user-id [:int {:min 1}])
    (get-schema registry :user-id)
    ;; => [:int {:min 1}]"
  []
  (atom {}))

(defn register-schema!
  "Register a named schema in the registry.

  Args:
    registry - Schema registry (atom)
    name - Keyword name for the schema
    schema - Malli schema

  Returns the registry.

  Example:
    (register-schema! registry :search-params
      [:map
       [:query [:string {:min 1}]]
       [:limit {:optional true} [:int {:min 1 :max 100}]]])"
  [registry name schema]
  (swap! registry assoc name schema)
  registry)

(defn get-schema
  "Get a schema from the registry by name.

  Args:
    registry - Schema registry (atom)
    name - Keyword name of the schema

  Returns the Malli schema or nil if not found.

  Example:
    (get-schema registry :search-params)
    ;; => [:map [:query [:string {:min 1}]] ...]"
  [registry name]
  (get @registry name))

(defn list-schemas
  "List all schema names in the registry.

  Args:
    registry - Schema registry (atom)

  Returns a sequence of keyword names.

  Example:
    (list-schemas registry)
    ;; => (:search-params :user-id :file-path)"
  [registry]
  (keys @registry))

;; ============================================================================
;; Malli -> JSON Schema Conversion
;; ============================================================================

(defn malli->json-schema
  "Convert Malli schema to JSON Schema format for MCP protocol.

  Uses Malli's built-in JSON Schema transformer with MCP-specific adjustments.

  Args:
    malli-schema - Malli schema (vector or schema object)

  Returns a JSON Schema map (Clojure map, not JSON string).

  Examples:
    ;; Simple types
    (malli->json-schema :string)
    ;; => {:type \"string\"}

    (malli->json-schema [:string {:min 1 :max 500}])
    ;; => {:type \"string\" :minLength 1 :maxLength 500}

    (malli->json-schema [:int {:min 1 :max 100}])
    ;; => {:type \"integer\" :minimum 1 :maximum 100}

    ;; Objects (maps)
    (malli->json-schema
      [:map
       [:query [:string {:min 1}]]
       [:limit {:optional true} [:int {:min 1 :max 100}]]])
    ;; => {:type \"object\"
    ;;     :properties {:query {:type \"string\" :minLength 1}
    ;;                  :limit {:type \"integer\" :minimum 1 :maximum 100}}
    ;;     :required [\"query\"]}

    ;; Arrays
    (malli->json-schema [:vector :string])
    ;; => {:type \"array\" :items {:type \"string\"}}

    ;; Enums
    (malli->json-schema [:enum \"draft\" \"published\" \"archived\"])
    ;; => {:enum [\"draft\" \"published\" \"archived\"]}

    ;; Complex nested schemas
    (malli->json-schema
      [:map
       [:name :string]
       [:age [:int {:min 0}]]
       [:tags {:optional true} [:vector :string]]
       [:metadata {:optional true} [:map [:key :string] [:value :any]]]])
    ;; => Complex JSON Schema with nested objects"
  [malli-schema]
  (try
    (mjs/transform malli-schema)
    (catch #?(:clj Exception :cljs js/Error) e
      ;; If transformation fails, return a generic object schema
      ;; This handles edge cases gracefully
      {:type "object"
       :description (str "Schema conversion failed: " (ex-message e))})))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-input
  "Validate input against Malli schema.

  Args:
    schema - Malli schema
    value - Value to validate

  Returns nil if valid, or an explanation map if invalid.

  The explanation map contains:
    :schema - The schema that failed
    :value - The value that was validated
    :errors - Sequence of error maps with :path, :in, :message, etc.

  Example:
    (validate-input [:string {:min 1}] \"hello\")
    ;; => nil (valid)

    (validate-input [:string {:min 1}] \"\")
    ;; => {:schema [:string {:min 1}]
    ;;     :value \"\"
    ;;     :errors [{:path [] :in [] :message \"should have at least 1 characters\"}]}

    (validate-input [:map [:query :string]] {:query \"test\"})
    ;; => nil (valid)

    (validate-input [:map [:query :string]] {:limit 10})
    ;; => {:schema ... :value ... :errors [{:path [:query] :message \"missing required key\"}]}"
  [schema value]
  (when-not (m/validate schema value)
    (m/explain schema value)))

(defn humanize-error
  "Convert Malli explanation to human-readable error messages.

  Args:
    explanation - Result from validate-input (when invalid)

  Returns a map of field paths to error messages.

  Example:
    (def result (validate-input [:string {:min 1}] \"\"))
    (humanize-error result)
    ;; => {[] [\"should have at least 1 characters\"]}

    (def result (validate-input
                  [:map [:query :string] [:limit [:int {:min 1}]]]
                  {:query \"\" :limit 0}))
    (humanize-error result)
    ;; => {[:query] [\"should have at least 1 characters\"]
    ;;     [:limit] [\"should be at least 1\"]}"
  [explanation]
  (when explanation
    (me/humanize explanation)))

;; ============================================================================
;; Schema Inference Helpers
;; ============================================================================

(defn infer-schema-type
  "Infer the basic Malli type from a value.

  This is useful for runtime schema generation or validation.

  Args:
    value - Any Clojure value

  Returns a basic Malli schema keyword.

  Example:
    (infer-schema-type \"hello\") ;; => :string
    (infer-schema-type 42) ;; => :int
    (infer-schema-type 3.14) ;; => :double
    (infer-schema-type true) ;; => :boolean
    (infer-schema-type [1 2 3]) ;; => :vector
    (infer-schema-type {:a 1}) ;; => :map"
  [value]
  (cond
    (string? value) :string
    (integer? value) :int
    (float? value) :double
    (boolean? value) :boolean
    (map? value) :map
    (vector? value) :vector
    (sequential? value) :sequential
    (keyword? value) :keyword
    (symbol? value) :symbol
    (nil? value) :nil
    :else :any))

(defn schema?
  "Check if a value looks like a Malli schema.

  Args:
    x - Value to check

  Returns true if x appears to be a Malli schema (vector starting with keyword or registry ref).

  Example:
    (schema? [:string {:min 1}]) ;; => true
    (schema? :string) ;; => true (simple type)
    (schema? [:map [:name :string]]) ;; => true
    (schema? \"not a schema\") ;; => false
    (schema? {:type \"object\"}) ;; => false (this is JSON Schema)"
  [x]
  (or (keyword? x)
      (and (vector? x)
           (keyword? (first x)))))

;; ============================================================================
;; Integration Helpers
;; ============================================================================

(defn resolve-schema
  "Resolve a schema reference to an actual Malli schema.

  If the input is already a Malli schema, return it as-is.
  If the input is a keyword, look it up in the registry.

  Args:
    schema-or-ref - Either a Malli schema or a keyword name
    registry - Optional schema registry (atom)

  Returns the resolved Malli schema or nil if not found.

  Example:
    (resolve-schema [:string {:min 1}] nil)
    ;; => [:string {:min 1}] (already a schema)

    (register-schema! registry :user-id [:int {:min 1}])
    (resolve-schema :user-id registry)
    ;; => [:int {:min 1}] (looked up in registry)

    (resolve-schema :unknown registry)
    ;; => nil (not found)"
  [schema-or-ref registry]
  (cond
    ;; If it's a vector or non-keyword, treat as schema
    (and (not (keyword? schema-or-ref))
         (schema? schema-or-ref))
    schema-or-ref

    ;; If keyword and registry provided, look up
    (and (keyword? schema-or-ref) registry)
    (get-schema registry schema-or-ref)

    ;; Keyword without registry or not in registry
    :else nil))

(defn schema->json-schema
  "Convert a schema (Malli or keyword reference) to JSON Schema.

  This is the main entry point for schema conversion with registry support.

  Args:
    schema-or-ref - Malli schema or keyword name
    registry - Optional schema registry (atom)

  Returns JSON Schema map or nil if schema cannot be resolved.

  Example:
    (schema->json-schema [:string {:min 1}] nil)
    ;; => {:type \"string\" :minLength 1}

    (register-schema! registry :search-params [:map [:query :string]])
    (schema->json-schema :search-params registry)
    ;; => {:type \"object\" :properties {:query {:type \"string\"}} :required [\"query\"]}"
  [schema-or-ref registry]
  (when-let [schema (resolve-schema schema-or-ref registry)]
    (malli->json-schema schema)))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn merge-schemas
  "Merge multiple Malli map schemas into one.

  Useful for composing schemas from multiple sources.

  Args:
    schemas - Sequence of Malli :map schemas

  Returns a merged :map schema.

  Example:
    (merge-schemas
      [:map [:name :string]]
      [:map [:age :int]]
      [:map [:email {:optional true} :string]])
    ;; => [:map [:name :string] [:age :int] [:email {:optional true} :string]]"
  [& schemas]
  (let [entries (mapcat rest schemas)]
    (into [:map] entries)))

(defn add-description
  "Add a description to a Malli schema.

  Args:
    schema - Malli schema
    description - String description

  Returns schema with description metadata.

  Example:
    (add-description [:string {:min 1}] \"User's name\")
    ;; => [:string {:min 1 :description \"User's name\"}]"
  [schema description]
  (if (vector? schema)
    (let [[type opts & rest] schema
          opts (if (map? opts) opts {})]
      (into [type (assoc opts :description description)] rest))
    schema))
