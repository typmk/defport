(ns defport.testing.server-test
  "Tests for the MCP test server helper library."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [defport.testing.server :as server]
            [defport.testing.client :as client]
            [defport.protocols.mcp :as mcp]
            [defport.core :as core]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(use-fixtures :each
  (fn [f]
    ;; Reset state before each test
    (server/reset-port-counter!)
    (client/reset-request-ids!)
    (mcp/reset-protocol-state!)
    (f)))

;; ============================================================================
;; Port Management Tests
;; ============================================================================

(deftest test-random-port-generation
  (testing "Random port generation produces unique ports"
    (server/reset-port-counter!)
    (let [port1 (server/random-port)
          port2 (server/random-port)
          port3 (server/random-port)]
      (is (number? port1))
      (is (number? port2))
      (is (number? port3))
      (is (not= port1 port2))
      (is (not= port2 port3))
      (is (>= port1 9000))
      (is (<= port1 19999)))))

(deftest test-port-counter-reset
  (testing "Port counter can be reset"
    (server/reset-port-counter!)
    (server/random-port)
    (server/random-port)
    (server/reset-port-counter!)
    (let [port (server/random-port)]
      (is (= 9001 port)))))

;; Removed test-port-counter-wraparound as it tries to access private implementation details

;; ============================================================================
;; Test Registry Creation Tests
;; ============================================================================

(deftest test-create-empty-test-registry
  (testing "Can create empty test registry"
    (let [reg (server/create-test-registry)]
      (is (satisfies? core/PortRegistry reg))
      (is (empty? (core/list-ports reg))))))

(deftest test-create-test-registry-with-ports
  (testing "Can create test registry with custom ports"
    (let [reg (server/create-test-registry
               {:my-tool
                {:description "My test tool"
                 :handler (fn [ctx] {:result "ok"})
                 :input-schema {:type "object"
                               :properties {:input {:type "string"}}}}})]
      (is (satisfies? core/PortRegistry reg))
      (let [ports (core/list-ports reg)]
        (is (= 1 (count ports)))
        (is (= :my-tool (:id (first ports))))
        (is (= "My test tool" (:description (first ports))))))))

(deftest test-create-test-registry-with-tools
  (testing "Can create test registry with standard tools"
    (let [reg (server/create-test-registry-with-tools)]
      (let [ports (core/list-ports reg)
            port-ids (set (map :id ports))]
        ;; Should have standard test tools
        (is (contains? port-ids :echo))
        (is (contains? port-ids :add))
        (is (contains? port-ids :error-tool))
        (is (contains? port-ids :slow-tool))

        ;; Should NOT have dangerous tools by default
        (is (not (contains? port-ids :dangerous-delete)))))))

(deftest test-create-test-registry-with-dangerous-tools
  (testing "Can create test registry with dangerous tools included"
    (let [reg (server/create-test-registry-with-tools {:include-dangerous true})]
      (let [ports (core/list-ports reg)
            port-ids (set (map :id ports))]
        ;; Should have dangerous tools when requested
        (is (contains? port-ids :dangerous-delete))))))

(deftest test-create-test-registry-with-custom-tools
  (testing "Can create test registry with custom tools"
    (let [reg (server/create-test-registry-with-tools
               {:custom-tools
                {:my-custom-tool
                 {:description "Custom tool"
                  :handler (fn [ctx] {:result "custom"})}}})]
      (let [ports (core/list-ports reg)
            port-ids (set (map :id ports))]
        ;; Should have standard tools
        (is (contains? port-ids :echo))

        ;; Should have custom tool
        (is (contains? port-ids :my-custom-tool))))))

;; ============================================================================
;; Standard Tool Handler Tests
;; ============================================================================

(deftest test-echo-tool
  (testing "Echo tool returns input"
    (let [reg (server/create-test-registry-with-tools)
          port (core/get-port reg :echo)
          result (core/port-execute port {:params {:message "hello"}})]
      (is (= {:result {:message "hello"}} result)))))

(deftest test-add-tool
  (testing "Add tool adds two numbers"
    (let [reg (server/create-test-registry-with-tools)
          port (core/get-port reg :add)
          result (core/port-execute port {:params {:a 5 :b 3}})]
      (is (= {:result 8} result)))))

(deftest test-error-tool
  (testing "Error tool returns error"
    (let [reg (server/create-test-registry-with-tools)
          port (core/get-port reg :error-tool)
          result (core/port-execute port {})]
      (is (map? (:error result)))
      (is (= -32603 (get-in result [:error :code]))))))
;; ============================================================================
;; Server Lifecycle Tests
;; ============================================================================

(deftest test-start-and-stop-http-server
  (testing "Can start and stop HTTP test server"
    (let [reg (server/create-test-registry-with-tools)
          adapter (mcp/create-mcp-adapter)
          srv (server/start-test-server reg adapter {:transport :http})]
      (try
        ;; Server should be running
        (is (some? srv))
        (is (some? (:registry srv)))
        (is (some? (:adapter srv)))
        (is (some? (:transport srv)))

        ;; Server should have a port
        (is (number? (server/get-server-port srv)))

        ;; Server should have a URL
        (is (string? (server/get-server-url srv)))
        (is (.startsWith (server/get-server-url srv) "http://"))

        (finally
          (server/stop-test-server srv))))))

(deftest test-server-with-specific-port
  (testing "Can start server on specific port"
    (let [reg (server/create-test-registry-with-tools)
          adapter (mcp/create-mcp-adapter)
          port 9876
          srv (server/start-test-server reg adapter {:transport :http :port port})]
      (try
        (is (= port (server/get-server-port srv)))
        (is (= (str "http://127.0.0.1:" port) (server/get-server-url srv)))
        (finally
          (server/stop-test-server srv))))))

(deftest test-wait-for-server-ready
  (testing "Wait for server ready works"
    (let [reg (server/create-test-registry-with-tools)
          adapter (mcp/create-mcp-adapter)
          srv (server/start-test-server reg adapter {:transport :http})]
      (try
        ;; Server should become ready
        (is (server/wait-for-server-ready srv 5000))
        (finally
          (server/stop-test-server srv))))))

;; ============================================================================
;; Test Fixture Macro Tests
;; ============================================================================

(deftest test-with-test-server-macro
  (testing "with-test-server macro works correctly"
    (let [reg (server/create-test-registry-with-tools)
          adapter (mcp/create-mcp-adapter)
          server-was-running (atom false)]
      (server/with-test-server [srv reg adapter {:transport :http}]
        ;; Inside the block, server should be running
        (is (some? srv))
        (is (some? (server/get-server-url srv)))
        (reset! server-was-running true))

      ;; After the block, we should have run the test
      (is @server-was-running))))

(deftest test-with-mcp-test-server-macro
  (testing "with-mcp-test-server macro works correctly"
    (let [server-was-running (atom false)
          port (atom nil)]
      (server/with-mcp-test-server [srv {:transport :http}]
        ;; Inside the block, server should be running
        (is (some? srv))
        (is (some? (server/get-server-url srv)))
        (reset! port (server/get-server-port srv))
        (reset! server-was-running true))

      ;; After the block, we should have run the test
      (is @server-was-running)
      (is (number? @port)))))

;; ============================================================================
;; Integration Tests with Client
;; ============================================================================

(deftest test-server-and-client-integration
  (testing "Server and client work together"
    (server/with-mcp-test-server [srv {:transport :http}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize
          (let [response (client/client-initialize c {:name "test" :version "1.0"})]
            (is (nil? (:error response)))
            (is (= "2025-06-18" (get-in response [:result :protocolVersion]))))

          ;; List tools
          (let [response (client/client-list-tools c)]
            (is (nil? (:error response)))
            (let [tools (get-in response [:result :tools])
                  tool-names (set (map :name tools))]
              (is (contains? tool-names "echo"))
              (is (contains? tool-names "add"))))

          ;; Call echo tool
          (let [response (client/client-call-tool c "echo" {:message "hello"})]
            (is (nil? (:error response)))
            (is (sequential? (get-in response [:result :content]))))

          ;; Call add tool
          (let [response (client/client-call-tool c "add" {:a 10 :b 20})]
            (is (nil? (:error response)))
            (is (sequential? (get-in response [:result :content])))))))))

(deftest test-server-with-custom-tools-integration
  (testing "Server with custom tools works with client"
    (server/with-mcp-test-server [srv {:transport :http
                                       :custom-tools
                                       {:greet
                                        {:description "Greet a person"
                                         :handler (fn [ctx]
                                                   {:result (str "Hello, " (get-in ctx [:params :name]) "!")})}}}]
      (let [url (server/get-server-url srv)]
        (client/with-test-client [c :http {:url url}]
          ;; Initialize
          (client/client-initialize c {:name "test" :version "1.0"})

          ;; List tools - should include custom tool
          (let [response (client/client-list-tools c)
                tools (get-in response [:result :tools])
                tool-names (set (map :name tools))]
            (is (contains? tool-names "greet")))

          ;; Call custom tool
          (let [response (client/client-call-tool c "greet" {:name "World"})]
            (is (nil? (:error response)))
            (is (sequential? (get-in response [:result :content])))))))))

(deftest test-multiple-concurrent-servers
  (testing "Can run multiple test servers concurrently"
    (server/with-mcp-test-server [srv1 {:transport :http}]
      (server/with-mcp-test-server [srv2 {:transport :http}]
        (let [port1 (server/get-server-port srv1)
              port2 (server/get-server-port srv2)]
          ;; Ports should be different
          (is (not= port1 port2))

          ;; Both servers should be functional
          (client/with-test-client [c1 :http {:url (server/get-server-url srv1)}]
            (client/with-test-client [c2 :http {:url (server/get-server-url srv2)}]
              (let [r1 (client/client-initialize c1 {:name "client1" :version "1.0"})
                    r2 (client/client-initialize c2 {:name "client2" :version "1.0"})]
                (is (nil? (:error r1)))
                (is (nil? (:error r2)))))))))))
