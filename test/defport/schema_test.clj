(ns defport.schema-test
  "Tests for Malli schema integration."
  (:require [clojure.test :refer [deftest is testing]]
            [defport.schema :as schema]))

;; ============================================================================
;; Schema Registry Tests
;; ============================================================================

(deftest test-schema-registry
  (testing "Create schema registry"
    (let [registry (schema/create-schema-registry)]
      (is (some? registry))
      (is (= {} @registry))))

  (testing "Register and get schema"
    (let [registry (schema/create-schema-registry)
          test-schema [:string {:min 1}]]
      (schema/register-schema! registry :user-name test-schema)
      (is (= test-schema (schema/get-schema registry :user-name)))))

  (testing "Register multiple schemas"
    (let [registry (schema/create-schema-registry)]
      (schema/register-schema! registry :name [:string {:min 1}])
      (schema/register-schema! registry :age [:int {:min 0}])
      (schema/register-schema! registry :email [:re #".+@.+"])
      (is (= 3 (count (schema/list-schemas registry))))
      (is (contains? (set (schema/list-schemas registry)) :name))
      (is (contains? (set (schema/list-schemas registry)) :age))
      (is (contains? (set (schema/list-schemas registry)) :email))))

  (testing "Get non-existent schema"
    (let [registry (schema/create-schema-registry)]
      (is (nil? (schema/get-schema registry :not-found))))))

;; ============================================================================
;; Malli -> JSON Schema Conversion Tests
;; ============================================================================

(deftest test-malli-to-json-schema-primitives
  (testing "String type"
    (is (= {:type "string"}
           (schema/malli->json-schema :string))))

  (testing "String with min/max"
    (let [result (schema/malli->json-schema [:string {:min 1 :max 500}])]
      (is (= "string" (:type result)))
      (is (= 1 (:minLength result)))
      (is (= 500 (:maxLength result)))))

  (testing "Integer type"
    (is (= {:type "integer"}
           (schema/malli->json-schema :int))))

  (testing "Integer with constraints"
    (let [result (schema/malli->json-schema [:int {:min 1 :max 100}])]
      (is (= "integer" (:type result)))
      (is (= 1 (:minimum result)))
      (is (= 100 (:maximum result)))))

  (testing "Double type"
    (is (= {:type "number"}
           (schema/malli->json-schema :double))))

  (testing "Boolean type"
    (is (= {:type "boolean"}
           (schema/malli->json-schema :boolean)))))

(deftest test-malli-to-json-schema-complex
  (testing "Map schema with required fields"
    (let [result (schema/malli->json-schema
                   [:map
                    [:query :string]
                    [:limit :int]])]
      (is (= "object" (:type result)))
      (is (contains? (:properties result) :query))
      (is (contains? (:properties result) :limit))
      (is (= #{:query :limit} (set (:required result))))))

  (testing "Map schema with optional fields"
    (let [result (schema/malli->json-schema
                   [:map
                    [:query [:string {:min 1}]]
                    [:limit {:optional true} [:int {:min 1 :max 100}]]])]
      (is (= "object" (:type result)))
      (is (contains? (:properties result) :query))
      (is (contains? (:properties result) :limit))
      (is (= #{:query} (set (:required result))))
      (is (= 1 (get-in result [:properties :limit :minimum])))
      (is (= 100 (get-in result [:properties :limit :maximum])))))

  (testing "Vector schema"
    (let [result (schema/malli->json-schema [:vector :string])]
      (is (= "array" (:type result)))
      (is (= "string" (get-in result [:items :type])))))

  (testing "Enum schema"
    (let [result (schema/malli->json-schema [:enum "draft" "published" "archived"])]
      (is (= #{"draft" "published" "archived"} (set (:enum result))))))

  (testing "Nested map schema"
    (let [result (schema/malli->json-schema
                   [:map
                    [:name :string]
                    [:metadata [:map
                                [:key :string]
                                [:value :any]]]])]
      (is (= "object" (:type result)))
      (is (= "object" (get-in result [:properties :metadata :type]))))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-validate-input
  (testing "Valid string"
    (is (nil? (schema/validate-input [:string {:min 1}] "hello"))))

  (testing "Invalid string (too short)"
    (let [result (schema/validate-input [:string {:min 1}] "")]
      (is (some? result))
      (is (contains? result :errors))))

  (testing "Valid integer"
    (is (nil? (schema/validate-input [:int {:min 0 :max 100}] 42))))

  (testing "Invalid integer (too low)"
    (let [result (schema/validate-input [:int {:min 1}] 0)]
      (is (some? result))
      (is (contains? result :errors))))

  (testing "Valid map"
    (is (nil? (schema/validate-input
                [:map [:query :string] [:limit :int]]
                {:query "test" :limit 10}))))

  (testing "Invalid map (missing required field)"
    (let [result (schema/validate-input
                   [:map [:query :string] [:limit :int]]
                   {:limit 10})]
      (is (some? result))
      (is (contains? result :errors))))

  (testing "Valid map with optional field"
    (is (nil? (schema/validate-input
                [:map [:query :string] [:limit {:optional true} :int]]
                {:query "test"}))))

  (testing "Invalid map (wrong type)"
    (let [result (schema/validate-input
                   [:map [:query :string]]
                   {:query 123})]
      (is (some? result))
      (is (contains? result :errors)))))

(deftest test-humanize-error
  (testing "Humanize string error"
    (let [explanation (schema/validate-input [:string {:min 1}] "")
          humanized (schema/humanize-error explanation)]
      (is (some? humanized))
      ;; Can be map or vector depending on error structure
      (is (or (map? humanized) (vector? humanized)))))

  (testing "Humanize map error"
    (let [explanation (schema/validate-input
                        [:map [:query [:string {:min 1}]]]
                        {:query ""})
          humanized (schema/humanize-error explanation)]
      (is (some? humanized))
      ;; Can be map or vector depending on error structure
      (is (or (map? humanized) (vector? humanized)))))

  (testing "Humanize nil (valid input)"
    (is (nil? (schema/humanize-error nil)))))

;; ============================================================================
;; Schema Inference Tests
;; ============================================================================

(deftest test-infer-schema-type
  (testing "Infer string"
    (is (= :string (schema/infer-schema-type "hello"))))

  (testing "Infer integer"
    (is (= :int (schema/infer-schema-type 42))))

  (testing "Infer double"
    (is (= :double (schema/infer-schema-type 3.14))))

  (testing "Infer boolean"
    (is (= :boolean (schema/infer-schema-type true))))

  (testing "Infer vector"
    (is (= :vector (schema/infer-schema-type [1 2 3]))))

  (testing "Infer map"
    (is (= :map (schema/infer-schema-type {:a 1}))))

  (testing "Infer keyword"
    (is (= :keyword (schema/infer-schema-type :foo))))

  (testing "Infer nil"
    (is (= :nil (schema/infer-schema-type nil)))))

(deftest test-schema-predicate
  (testing "Identify Malli schema"
    (is (true? (schema/schema? [:string {:min 1}])))
    (is (true? (schema/schema? :string)))
    (is (true? (schema/schema? [:map [:name :string]])))
    (is (false? (schema/schema? "not a schema")))
    (is (false? (schema/schema? {:type "object"})))))

;; ============================================================================
;; Integration Helpers Tests
;; ============================================================================

(deftest test-resolve-schema
  (testing "Resolve Malli schema (pass-through)"
    (let [schema [:string {:min 1}]]
      (is (= schema (schema/resolve-schema schema nil)))))

  (testing "Resolve keyword reference"
    (let [registry (schema/create-schema-registry)
          test-schema [:int {:min 1}]]
      (schema/register-schema! registry :user-id test-schema)
      (is (= test-schema (schema/resolve-schema :user-id registry)))))

  (testing "Resolve unknown keyword"
    (let [registry (schema/create-schema-registry)]
      (is (nil? (schema/resolve-schema :unknown registry)))))

  (testing "Resolve without registry"
    (is (nil? (schema/resolve-schema :unknown nil)))))

(deftest test-schema-to-json-schema
  (testing "Convert Malli schema"
    (let [result (schema/schema->json-schema [:string {:min 1}] nil)]
      (is (= "string" (:type result)))
      (is (= 1 (:minLength result)))))

  (testing "Convert named schema"
    (let [registry (schema/create-schema-registry)]
      (schema/register-schema! registry :search-params
        [:map
         [:query [:string {:min 1}]]
         [:limit {:optional true} :int]])
      (let [result (schema/schema->json-schema :search-params registry)]
        (is (some? result))
        (is (= "object" (:type result)))
        (is (contains? (:properties result) :query))
        (is (contains? (:properties result) :limit)))))

  (testing "Convert unknown schema (returns nil)"
    (let [registry (schema/create-schema-registry)]
      (is (nil? (schema/schema->json-schema :unknown registry))))))

;; ============================================================================
;; Utility Functions Tests
;; ============================================================================

(deftest test-merge-schemas
  (testing "Merge two map schemas"
    (let [result (schema/merge-schemas
                   [:map [:name :string]]
                   [:map [:age :int]])]
      (is (= :map (first result)))
      (is (= 2 (count (rest result))))))

  (testing "Merge three map schemas"
    (let [result (schema/merge-schemas
                   [:map [:name :string]]
                   [:map [:age :int]]
                   [:map [:email {:optional true} :string]])]
      (is (= :map (first result)))
      (is (= 3 (count (rest result)))))))

(deftest test-add-description
  (testing "Add description to simple schema"
    (let [result (schema/add-description :string "User's name")]
      (is (= :string result))))

  (testing "Add description to schema with options"
    (let [result (schema/add-description [:string {:min 1}] "User's name")]
      (is (= :string (first result)))
      (is (= "User's name" (get (second result) :description)))))

  (testing "Add description to schema without options"
    (let [result (schema/add-description [:string] "User's name")]
      (is (= :string (first result)))
      (is (= "User's name" (get (second result) :description))))))
