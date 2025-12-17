(ns defport.integration.resources-integration-test
  "Integration tests for resources_server - full end-to-end testing."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [defport.testing.server :as server]
            [defport.testing.client :as client]
            [defport.testing.compliance :as compliance]
            [defport.core :as core]
            [defport.protocols.mcp :as mcp]
            [defport.registry :as registry]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; Load the resources server namespace
(load-file "examples/test_servers/resources_server/jvm/resources_server.clj")

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(def test-registry (atom nil))

(defn setup-registry []
  (reset! test-registry (test-servers.resources-server/create-resources-registry)))

(use-fixtures :once (fn [f] (setup-registry) (f)))
(use-fixtures :each (fn [f] (mcp/reset-protocol-state!) (f)))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest ^:integration test-resources-server-initialization
  (testing "Server initialization and handshake"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (let [response (client/client-initialize c {:name "resources-test" :version "1.0"})
              result (:result response)]

          ;; Verify response structure
          (is (nil? (:error response)) "Initialize should not return error")
          (is (some? result) "Should have result")

          ;; Compliance validation
          (is (nil? (compliance/validate-response "initialize"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response))))

          ;; Verify capabilities
          (is (= "2025-06-18" (:protocolVersion result)))
          (is (true? (get-in result [:capabilities :resources :subscribe])))
          (is (true? (get-in result [:capabilities :resources :listChanged]))))))))

(deftest ^:integration test-resources-list
  (testing "List all resources"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-request c "resources/list" {})
              result (:result response)
              resources (:resources result)]

          ;; Verify response
          (is (nil? (:error response)))
          (is (some? result))

          ;; Compliance validation
          (is (nil? (compliance/validate-response "resources/list"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response))))

          ;; Verify resources
          (is (= 10 (count resources)) "Should have 10 resources")
          (is (nil? (:nextCursor result)) "Should not have pagination for 10 items")

          ;; Verify first resource structure
          (let [first-resource (first resources)]
            (is (string? (:uri first-resource)))
            (is (string? (:name first-resource)))
            (is (string? (:description first-resource)))
            (is (string? (:mimeType first-resource)))))))))

(deftest ^:integration test-read-static-resource
  (testing "Read static resource (version)"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-read-resource c "defport://version")
              result (:result response)
              contents (:contents result)]

          ;; Verify response
          (is (nil? (:error response)))
          (is (some? result))

          ;; Compliance validation
          (is (nil? (compliance/validate-response "resources/read"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response))))

          ;; Verify contents
          (is (sequential? contents))
          (is (= 1 (count contents)))

          ;; Verify first content structure
          (let [content (first contents)]
            (is (= "defport://version" (:uri content)))
            (is (= "application/json" (:mimeType content)))
            (is (string? (:text content)))

            ;; Parse and verify JSON
            (let [data (json/parse-string (:text content) true)]
              (is (string? (:name data)))
              (is (string? (:version data)))
              (is (string? (:protocol data))))))))))

(deftest ^:integration test-read-edn-resource
  (testing "Read EDN resource (schema)"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-read-resource c "defport://schema")
              contents (get-in response [:result :contents])
              content (first contents)]

          (is (nil? (:error response)))
          (is (= "application/edn" (:mimeType content)))
          (is (string? (:text content)))

          ;; Verify it's valid EDN
          (let [data (clojure.edn/read-string (:text content))]
            (is (map? data))
            (is (contains? data :tools))))))))

(deftest ^:integration test-read-markdown-resource
  (testing "Read Markdown resource (documentation)"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-read-resource c "defport://documentation")
              contents (get-in response [:result :contents])
              content (first contents)]

          (is (nil? (:error response)))
          (is (= "text/markdown" (:mimeType content)))
          (is (str/includes? (:text content) "#"))
          (is (str/includes? (:text content) "Resources")))))))

(deftest ^:integration test-read-plain-text-resource
  (testing "Read plain text resource (readme)"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-read-resource c "defport://readme")
              contents (get-in response [:result :contents])
              content (first contents)]

          (is (nil? (:error response)))
          (is (= "text/plain" (:mimeType content)))
          (is (str/includes? (:text content) "Welcome")))))))

(deftest ^:integration test-read-dynamic-resource
  (testing "Read dynamic resource (stats)"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-read-resource c "defport://stats")
              contents (get-in response [:result :contents])
              content (first contents)]

          (is (nil? (:error response)))
          (is (= "application/json" (:mimeType content)))

          ;; Parse and verify stats structure
          (let [stats (json/parse-string (:text content) true)]
            (is (number? (:requests stats)))
            (is (number? (:uptime-seconds stats)))
            (is (number? (:active-connections stats)))
            (is (number? (:last-updated stats)))))))))

(deftest ^:integration test-subscribe-to-resource
  (testing "Subscribe to dynamic resource"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-request c "resources/subscribe"
                         {:uri "defport://stats"})]

          ;; Verify successful subscription
          (is (nil? (:error response)))
          (is (some? (:result response)))

          ;; Compliance validation
          (is (nil? (compliance/validate-response "resources/subscribe"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response)))))))))

(deftest ^:integration test-unsubscribe-from-resource
  (testing "Unsubscribe from dynamic resource"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; First subscribe
        (client/client-request c "resources/subscribe" {:uri "defport://logs"})

        ;; Then unsubscribe
        (let [response (client/client-request c "resources/unsubscribe"
                         {:uri "defport://logs"})]

          (is (nil? (:error response)))
          (is (some? (:result response)))

          ;; Compliance validation
          (is (nil? (compliance/validate-response "resources/unsubscribe"
                                                   (dissoc response :_request-id)
                                                   (:_request-id response)))))))))

(deftest ^:integration test-multiple-resource-reads
  (testing "Read multiple different resources"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Read several resources
        (let [r1 (client/client-read-resource c "defport://version")
              r2 (client/client-read-resource c "defport://schema")
              r3 (client/client-read-resource c "defport://stats")
              r4 (client/client-read-resource c "defport://config")]

          ;; All should succeed
          (is (nil? (:error r1)))
          (is (nil? (:error r2)))
          (is (nil? (:error r3)))
          (is (nil? (:error r4)))

          ;; Verify different MIME types
          (is (= "application/json" (get-in r1 [:result :contents 0 :mimeType])))
          (is (= "application/edn" (get-in r2 [:result :contents 0 :mimeType])))
          (is (= "application/json" (get-in r3 [:result :contents 0 :mimeType]))))))))

(deftest ^:integration test-concurrent-resource-reads
  (testing "Handle concurrent resource read requests"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Make multiple concurrent requests
        (let [uris ["defport://version" "defport://stats" "defport://config"
                    "defport://health" "defport://metrics"]
              futures (doall
                        (for [uri uris]
                          (future (client/client-read-resource c uri))))
              results (map deref futures)]

          ;; All should succeed
          (is (every? #(nil? (:error %)) results))
          (is (= 5 (count results))))))))

(deftest ^:integration test-invalid-resource-uri
  (testing "Error when requesting non-existent resource"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-read-resource c "defport://nonexistent")
              error (:error response)]

          ;; Should have error
          (is (some? error))
          (is (number? (:code error))))))))

(deftest ^:integration test-resource-metadata
  (testing "Verify resource metadata in listings"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        (let [response (client/client-request c "resources/list" {})
              resources (get-in response [:result :resources])
              stats-resource (first (filter #(= "defport://stats" (:uri %)) resources))]

          ;; Verify metadata
          (is (some? stats-resource))
          (is (= "defport://stats" (:uri stats-resource)))
          (is (string? (:name stats-resource)))
          (is (string? (:description stats-resource)))
          (is (= "application/json" (:mimeType stats-resource))))))))

(deftest ^:integration test-full-resources-workflow
  (testing "Complete workflow: list, read, subscribe, unsubscribe"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        ;; Initialize
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; List resources
        (let [list-resp (client/client-request c "resources/list" {})]
          (is (nil? (:error list-resp)))
          (is (= 10 (count (get-in list-resp [:result :resources])))))

        ;; Read static resource
        (let [read-resp (client/client-read-resource c "defport://version")]
          (is (nil? (:error read-resp))))

        ;; Subscribe to dynamic resource
        (let [sub-resp (client/client-request c "resources/subscribe"
                         {:uri "defport://stats"})]
          (is (nil? (:error sub-resp))))

        ;; Read the subscribed resource
        (let [read-resp (client/client-read-resource c "defport://stats")]
          (is (nil? (:error read-resp))))

        ;; Unsubscribe
        (let [unsub-resp (client/client-request c "resources/unsubscribe"
                           {:uri "defport://stats"})]
          (is (nil? (:error unsub-resp))))))))

(deftest ^:integration test-all-mime-types
  (testing "Verify all different MIME types work correctly"
    (server/with-mcp-test-server [srv {:registry @test-registry :transport :http}]
      (client/with-test-client [c :http {:url (server/get-server-url srv)}]
        (client/client-initialize c {:name "test" :version "1.0"})

        ;; Test each MIME type
        (let [json-resp (client/client-read-resource c "defport://version")
              edn-resp (client/client-read-resource c "defport://schema")
              markdown-resp (client/client-read-resource c "defport://documentation")
              plain-resp (client/client-read-resource c "defport://readme")]

          ;; All should succeed
          (is (every? nil? (map :error [json-resp edn-resp markdown-resp plain-resp])))

          ;; Verify MIME types
          (is (= "application/json"
                (get-in json-resp [:result :contents 0 :mimeType])))
          (is (= "application/edn"
                (get-in edn-resp [:result :contents 0 :mimeType])))
          (is (= "text/markdown"
                (get-in markdown-resp [:result :contents 0 :mimeType])))
          (is (= "text/plain"
                (get-in plain-resp [:result :contents 0 :mimeType]))))))))
