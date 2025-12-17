# Integration Patterns

This guide shows how to integrate defport into existing Clojure applications and stacks.

**Philosophy:** defport is a low-level library (like Ring for HTTP, Lacinia for GraphQL). It provides protocol adapters; you provide the application infrastructure (auth, metrics, database, lifecycle).

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Component Integration](#component-integration)
3. [Integrant Integration](#integrant-integration)
4. [Ring + Reitit Integration](#ring--reitit-integration)
5. [Pedestal Integration](#pedestal-integration)
6. [Authentication Patterns](#authentication-patterns)
7. [Metrics Integration](#metrics-integration)
8. [Database Integration](#database-integration)
9. [Production Deployment](#production-deployment)

---

## Quick Start

The simplest MCP server in 8 lines:

```clojure
(ns my-app.mcp
  (:require [defport.dsl :as mcp]))

(mcp/deftool greet
  "Greet a user"
  [name :- :string]
  {:greeting (str "Hello, " name "!")})

(mcp/start! {:name "my-server" :version "1.0.0"})
```

For production applications, you'll want to integrate defport with your existing infrastructure.

---

## Component Integration

Use Stuart Sierra's Component library to manage defport lifecycle alongside your other components.

### deps.edn

```clojure
{:deps {com.stuartsierra/component {:mvn/version "1.1.0"}
        io.github.yourorg/defport {:mvn/version "0.5.0"}}}
```

### MCP Component

```clojure
(ns my-app.components.mcp
  (:require [com.stuartsierra.component :as component]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]
            [defport.transports.http :as http]))

(defrecord McpServer [config db-pool auth-backend metrics-registry
                      ;; Runtime state
                      port-registry mcp-adapter transport]
  component/Lifecycle

  (start [this]
    (println "Starting MCP server on port" (:port config))

    ;; Create port registry
    (let [registry (registry/create-function-registry)

          ;; Create adapter with shared infrastructure
          adapter (mcp/create-mcp-adapter
                    {:server-info {:name (:name config)
                                   :version (:version config)}
                     :enable-refactoring (:enable-refactoring config false)})

          ;; Create HTTP transport
          transport (http/create-http-transport {:port (:port config)})]

      ;; Register tools that use shared components
      (register-tools! registry {:db-pool db-pool
                                  :auth-backend auth-backend
                                  :metrics-registry metrics-registry})

      ;; Start transport with MCP handler
      (core/transport-start transport
        (fn [request]
          (core/protocol-dispatch adapter
                                   (:method request)
                                   (:params request)
                                   {:port-registry registry
                                    :transport transport
                                    :request request})))

      (assoc this
        :port-registry registry
        :mcp-adapter adapter
        :transport transport)))

  (stop [this]
    (println "Stopping MCP server")
    (when transport
      (core/transport-stop transport))
    (assoc this
      :port-registry nil
      :mcp-adapter nil
      :transport nil)))

(defn new-mcp-server [config]
  (map->McpServer {:config config}))
```

### Registering Tools with Shared Dependencies

```clojure
(ns my-app.components.mcp
  (:require [defport.core :as core]))

(defn register-tools!
  "Register MCP tools with access to shared components."
  [registry {:keys [db-pool auth-backend metrics-registry]}]

  ;; Tool that uses database
  (core/register-port! registry
    {:id :search-users
     :description "Search users in database"
     :input-schema {:type "object"
                    :properties {:query {:type "string"}}}
     :handler (fn [context]
                ;; Use shared db-pool
                (let [query (get-in context [:params :query])
                      results (jdbc/query db-pool
                                ["SELECT * FROM users WHERE name LIKE ?"
                                 (str "%" query "%")])]
                  {:result results}))})

  ;; Tool with metrics
  (core/register-port! registry
    {:id :analyze-code
     :description "Analyze code with metrics tracking"
     :input-schema {:type "object"
                    :properties {:code {:type "string"}}}
     :handler (fn [context]
                (let [start (System/currentTimeMillis)]
                  (try
                    (let [result (analyze (:code (:params context)))]
                      ;; Record success metric
                      (metrics/inc! metrics-registry :mcp-tool-calls
                                   {:tool "analyze-code" :status "success"})
                      {:result result})
                    (finally
                      ;; Record duration
                      (metrics/observe! metrics-registry :mcp-tool-duration
                                       (- (System/currentTimeMillis) start)
                                       {:tool "analyze-code"})))))}))
```

### Complete System Assembly

```clojure
(ns my-app.system
  (:require [com.stuartsierra.component :as component]
            [my-app.components.db :as db]
            [my-app.components.auth :as auth]
            [my-app.components.metrics :as metrics]
            [my-app.components.mcp :as mcp]
            [my-app.components.web :as web]))

(defn create-system [config]
  (component/system-map
    ;; Shared infrastructure
    :db-pool (db/new-db-pool (:database config))
    :auth-backend (auth/new-auth-backend (:auth config))
    :metrics-registry (metrics/new-metrics-registry)

    ;; MCP server (depends on shared infrastructure)
    :mcp-server (component/using
                  (mcp/new-mcp-server (:mcp config))
                  [:db-pool :auth-backend :metrics-registry])

    ;; Web server (same dependencies)
    :web-server (component/using
                  (web/new-web-server (:web config))
                  [:db-pool :auth-backend :metrics-registry])))

;; Usage
(def system (atom nil))

(defn start! []
  (reset! system (component/start (create-system (load-config)))))

(defn stop! []
  (when @system
    (component/stop @system)
    (reset! system nil)))
```

---

## Integrant Integration

For Integrant users, wrap defport as Integrant keys.

### Config

```clojure
;; resources/config.edn
{:my-app/db-pool {:jdbc-url "jdbc:postgresql://localhost/mydb"}

 :my-app/mcp-server {:port 8080
                     :name "my-server"
                     :version "1.0.0"
                     :db-pool #ig/ref :my-app/db-pool}}
```

### Implementation

```clojure
(ns my-app.system
  (:require [integrant.core :as ig]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]
            [defport.transports.http :as http]))

(defmethod ig/init-key :my-app/mcp-server
  [_ {:keys [port name version db-pool]}]
  (let [registry (registry/create-function-registry)
        adapter (mcp/create-mcp-adapter
                  {:server-info {:name name :version version}})
        transport (http/create-http-transport {:port port})]

    ;; Register tools with db-pool
    (core/register-port! registry
      {:id :query-db
       :description "Query database"
       :input-schema {:type "object"
                      :properties {:sql {:type "string"}}}
       :handler (fn [context]
                  {:result (jdbc/query db-pool
                             [(get-in context [:params :sql])])})})

    ;; Start transport
    (core/transport-start transport
      (fn [request]
        (core/protocol-dispatch adapter
                                 (:method request)
                                 (:params request)
                                 {:port-registry registry
                                  :transport transport
                                  :request request})))

    {:registry registry
     :adapter adapter
     :transport transport}))

(defmethod ig/halt-key! :my-app/mcp-server
  [_ {:keys [transport]}]
  (when transport
    (core/transport-stop transport)))
```

---

## Ring + Reitit Integration

Add MCP as another route in your Ring application.

### Single HTTP Server, Multiple Protocols

```clojure
(ns my-app.routes
  (:require [reitit.ring :as ring]
            [ring.middleware.json :as json]
            [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]))

;; Create defport components (do this once at startup)
(def mcp-registry (registry/create-function-registry))
(def mcp-adapter (mcp/create-mcp-adapter
                   {:server-info {:name "my-app" :version "1.0.0"}}))

;; MCP handler for Ring
(defn mcp-handler [request]
  (let [body (:body-params request)
        response (core/protocol-dispatch mcp-adapter
                                          (:method body)
                                          (:params body)
                                          {:port-registry mcp-registry
                                           :request body})]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body {:jsonrpc "2.0"
            :id (:id body)
            :result response}}))

;; Combined routes
(def app
  (ring/ring-handler
    (ring/router
      [;; Your web routes
       ["/api"
        ["/users" {:get list-users-handler
                   :post create-user-handler}]
        ["/health" {:get health-handler}]]

       ;; MCP endpoint (same middleware stack!)
       ["/mcp" {:post {:handler mcp-handler
                       :middleware [json/wrap-json-body
                                    json/wrap-json-response
                                    ;; YOUR existing auth middleware
                                    wrap-authentication
                                    wrap-authorization]}}]])))
```

### Sharing Middleware

The key insight: MCP requests are just HTTP POST requests. Your existing Ring middleware works:

```clojure
(defn wrap-mcp-auth
  "Reuse your existing auth middleware for MCP."
  [handler]
  (fn [request]
    ;; Your existing auth logic
    (if-let [user (authenticate-request request)]
      (handler (assoc request :user user))
      {:status 401
       :body {:error "Unauthorized"}})))

;; MCP tools can access authenticated user
(core/register-port! mcp-registry
  {:id :get-my-data
   :description "Get current user's data"
   :handler (fn [context]
              ;; Access user from Ring request context
              (let [user (get-in context [:ring-request :user])]
                {:result (fetch-user-data (:id user))}))})
```

---

## Pedestal Integration

Add MCP as a Pedestal interceptor.

### MCP Interceptor

```clojure
(ns my-app.interceptors.mcp
  (:require [defport.core :as core]
            [defport.protocols.mcp :as mcp]
            [defport.registry :as registry]))

(defn mcp-interceptor
  "Create Pedestal interceptor for MCP protocol handling."
  [{:keys [adapter registry]}]
  {:name ::mcp-handler
   :enter (fn [context]
            (let [request (get-in context [:request :json-params])
                  response (core/protocol-dispatch adapter
                                                    (:method request)
                                                    (:params request)
                                                    {:port-registry registry
                                                     :request request
                                                     ;; Pass Pedestal context for shared state
                                                     :pedestal-context context})]
              (assoc context :response
                {:status 200
                 :headers {"Content-Type" "application/json"}
                 :body {:jsonrpc "2.0"
                        :id (:id request)
                        :result response}})))})
```

### Service Map with Shared Interceptors

```clojure
(ns my-app.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [my-app.interceptors.auth :as auth]
            [my-app.interceptors.metrics :as metrics]
            [my-app.interceptors.mcp :as mcp-int]
            [defport.protocols.mcp :as mcp]
            [defport.registry :as registry]))

;; Shared MCP state
(def mcp-registry (registry/create-function-registry))
(def mcp-adapter (mcp/create-mcp-adapter {}))

;; Shared interceptors (work for both web and MCP)
(def common-interceptors
  [http/json-body
   auth/authentication-interceptor
   auth/authorization-interceptor
   metrics/request-metrics-interceptor])

(def routes
  (route/expand-routes
    #{;; Web routes
      ["/api/users" :get (conj common-interceptors `list-users)]
      ["/api/users" :post (conj common-interceptors `create-user)]

      ;; MCP endpoint with SAME interceptors
      ["/mcp" :post (conj common-interceptors
                          (mcp-int/mcp-interceptor
                            {:adapter mcp-adapter
                             :registry mcp-registry}))]}))

(def service-map
  {::http/routes routes
   ::http/type :jetty
   ::http/port 8080})
```

---

## Authentication Patterns

defport doesn't include auth because you already have it. Here's how to integrate.

### Pattern 1: Ring Middleware Wrapping

```clojure
(ns my-app.mcp.auth
  (:require [buddy.auth :as auth]
            [buddy.auth.backends :as backends]))

;; Your existing JWT backend
(def jwt-backend
  (backends/jws {:secret (System/getenv "JWT_SECRET")
                 :token-name "Bearer"}))

(defn wrap-mcp-auth
  "Wrap MCP handler with buddy-auth."
  [mcp-handler]
  (fn [request]
    (let [request (auth/authenticate request jwt-backend)]
      (if (auth/authenticated? request)
        (mcp-handler request)
        {:status 401
         :body {:jsonrpc "2.0"
                :error {:code -32001
                        :message "Authentication required"}}}))))

;; Usage
(def mcp-routes
  [["/mcp" {:post {:handler (wrap-mcp-auth mcp-handler)}}]])
```

### Pattern 2: Context Injection

```clojure
(defn create-mcp-handler
  "Create MCP handler with auth context."
  [adapter registry]
  (fn [ring-request]
    (let [user (:identity ring-request)  ; From buddy-auth
          body (:body-params ring-request)

          ;; Pass user to MCP context
          response (core/protocol-dispatch adapter
                                            (:method body)
                                            (:params body)
                                            {:port-registry registry
                                             :request body
                                             :user user  ; <-- Available in tool handlers
                                             :permissions (get-user-permissions user)})]
      {:status 200
       :body {:jsonrpc "2.0"
              :id (:id body)
              :result response}})))

;; Tool can check permissions
(core/register-port! registry
  {:id :admin-action
   :description "Admin-only action"
   :metadata {:dangerous true}
   :handler (fn [context]
              (if (contains? (:permissions context) :admin)
                {:result (do-admin-action)}
                {:error {:code -32003 :message "Permission denied"}}))})
```

### Pattern 3: Per-Tool Authorization

```clojure
(defn with-permission
  "Wrap handler with permission check."
  [required-permission handler-fn]
  (fn [context]
    (let [user-perms (get context :permissions #{})]
      (if (contains? user-perms required-permission)
        (handler-fn context)
        {:error {:code -32003
                 :message (str "Permission required: " required-permission)}}))))

;; Register tools with permissions
(core/register-port! registry
  {:id :read-data
   :description "Read data (requires :read permission)"
   :handler (with-permission :read
              (fn [context]
                {:result (read-data (:params context))}))})

(core/register-port! registry
  {:id :write-data
   :description "Write data (requires :write permission)"
   :metadata {:dangerous true}
   :handler (with-permission :write
              (fn [context]
                {:result (write-data (:params context))}))})
```

---

## Metrics Integration

### Pattern 1: tap> Subscriber

defport emits tap> events (when enabled). Subscribe and route to your metrics system:

```clojure
(ns my-app.observability
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.fn :as fn-collector]))

(def registry
  (-> (prometheus/collector-registry)
      (prometheus/register
        (prometheus/counter :mcp/tool-calls {:labels [:tool :status]})
        (prometheus/histogram :mcp/tool-duration {:labels [:tool]}))))

(defn tap-subscriber
  "Route defport tap> events to Prometheus."
  [event]
  (when (and (map? event) (:event event))
    (case (:event event)
      :mcp/tool-call
      (do
        (prometheus/inc registry :mcp/tool-calls
                        {:tool (name (:tool-id event))
                         :status (if (:success? event) "success" "error")})
        (prometheus/observe registry :mcp/tool-duration
                           (:duration-ms event)
                           {:tool (name (:tool-id event))}))

      :mcp/error
      (prometheus/inc registry :mcp/tool-calls
                      {:tool (:method event) :status "error"})

      nil)))

;; Register subscriber at startup
(add-tap tap-subscriber)
```

### Pattern 2: Explicit Metrics in Handlers

```clojure
(defn metered-handler
  "Wrap handler with explicit metrics."
  [metrics-registry tool-name handler-fn]
  (fn [context]
    (let [start (System/currentTimeMillis)]
      (try
        (let [result (handler-fn context)]
          (prometheus/inc metrics-registry :mcp/tool-calls
                         {:tool tool-name :status "success"})
          result)
        (catch Exception e
          (prometheus/inc metrics-registry :mcp/tool-calls
                         {:tool tool-name :status "error"})
          (throw e))
        (finally
          (prometheus/observe metrics-registry :mcp/tool-duration
                             (- (System/currentTimeMillis) start)
                             {:tool tool-name}))))))
```

### Pattern 3: Unified /metrics Endpoint

```clojure
;; Both web and MCP metrics on same endpoint
(def routes
  [["/metrics" {:get {:handler (fn [_]
                                  {:status 200
                                   :headers {"Content-Type" "text/plain"}
                                   :body (prometheus/write-text-format registry)})}}]
   ["/api" web-routes]
   ["/mcp" mcp-routes]])
```

---

## Database Integration

### Pattern 1: Connection Pool in Context

```clojure
(defn create-system [{:keys [db-config mcp-config]}]
  (let [;; Create shared pool once
        db-pool (hikari/make-datasource db-config)

        ;; Create MCP components
        registry (registry/create-function-registry)
        adapter (mcp/create-mcp-adapter mcp-config)]

    ;; Register tools that use db-pool
    (core/register-port! registry
      {:id :search
       :handler (fn [context]
                  (let [pool (:db-pool context)  ; <-- From context
                        query (get-in context [:params :query])]
                    {:result (jdbc/query pool ["SELECT * FROM items WHERE name LIKE ?"
                                               (str "%" query "%")])}))})

    ;; Return handler that injects db-pool
    {:handler (fn [request]
                (core/protocol-dispatch adapter
                                         (:method request)
                                         (:params request)
                                         {:port-registry registry
                                          :db-pool db-pool  ; <-- Inject here
                                          :request request}))
     :close-fn #(.close db-pool)}))
```

### Pattern 2: Transaction Management

```clojure
(defn transactional-handler
  "Wrap handler in database transaction."
  [db-pool handler-fn]
  (fn [context]
    (jdbc/with-transaction [tx db-pool]
      (handler-fn (assoc context :tx tx)))))

;; Tool uses transaction
(core/register-port! registry
  {:id :transfer-funds
   :metadata {:dangerous true}
   :handler (transactional-handler db-pool
              (fn [context]
                (let [tx (:tx context)
                      {:keys [from to amount]} (:params context)]
                  (jdbc/execute! tx ["UPDATE accounts SET balance = balance - ? WHERE id = ?" amount from])
                  (jdbc/execute! tx ["UPDATE accounts SET balance = balance + ? WHERE id = ?" amount to])
                  {:result {:status "transferred"}})))})
```

---

## Production Deployment

### Single Process Architecture

The simplest approach: run MCP alongside your web server in the same process.

```clojure
(ns my-app.main
  (:require [my-app.system :as system]))

(defn -main [& args]
  (let [config (load-config)
        sys (system/create-system config)]

    ;; Start everything
    (component/start sys)

    ;; Register shutdown hook
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. #(component/stop sys)))

    (println "Server started")
    (println "  Web: http://localhost:" (get-in config [:web :port]))
    (println "  MCP: http://localhost:" (get-in config [:mcp :port]))

    ;; Block forever
    @(promise)))
```

### Security Checklist

Before deploying to production:

- [ ] **Authentication enabled** - MCP endpoint requires valid credentials
- [ ] **Dangerous tools filtered** - Set `DEFPORT_ENABLE_REFACTORING=false` in production
- [ ] **Rate limiting** - Apply same limits as your web API
- [ ] **Request size limits** - Prevent oversized JSON payloads
- [ ] **TLS enabled** - MCP traffic should be encrypted
- [ ] **Logging configured** - All MCP requests logged for audit
- [ ] **Metrics enabled** - Monitor tool execution times and error rates
- [ ] **Health checks** - Include MCP in your health monitoring

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: my-app
          image: my-app:latest
          ports:
            - containerPort: 8080  # Web
            - containerPort: 8081  # MCP
          env:
            - name: DEFPORT_ENABLE_REFACTORING
              value: "false"
            - name: JWT_SECRET
              valueFrom:
                secretKeyRef:
                  name: my-app-secrets
                  key: jwt-secret
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
          readinessProbe:
            httpGet:
              path: /health
              port: 8080
```

### Monitoring Recommendations

1. **Grafana Dashboard** - Track MCP tool calls, latency, errors alongside web metrics
2. **Alerting** - Alert on high error rates, slow tool execution
3. **Tracing** - Include MCP requests in distributed tracing (Jaeger, Zipkin)
4. **Audit Logging** - Log all tool calls with user identity

---

## Summary

defport integrates with your existing infrastructure:

| Concern | You Provide | defport Provides |
|---------|-------------|------------------|
| **Auth** | buddy-auth, JWT secrets | Context injection |
| **Metrics** | Prometheus, iapetos | tap> events |
| **Database** | HikariCP, connection pools | Context injection |
| **Lifecycle** | Component, Integrant | Stateless adapters |
| **HTTP** | Ring, Pedestal, Reitit | Protocol handlers |
| **Logging** | mulog, timbre | Log message notifications |

**Key principle:** defport provides the protocol layer. You control everything else.

---

## Next Steps

- [Architecture Documentation](ARCHITECTURE.md) - Design rationale
- [Performance Guide](PERFORMANCE.md) - Batch processing optimization
- [Concurrency Guide](CONCURRENCY.md) - Thread safety model