(ns integrations
  "Framework integration patterns - Component, Reitit, Mount, Integrant."
  (:require [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]
            [defport.transports.http :as http]))

;; ============================================================================
;; COMPONENT INTEGRATION (JVM)
;; ============================================================================

(comment
  "Integrate defport with Stuart Sierra's Component library."

  (require '[com.stuartsierra.component :as component])

  ;; MCP Server as a Component
  (defrecord McpServer [config db-pool registry adapter transport]
    component/Lifecycle
    (start [this]
      (let [reg (registry/create-function-registry)
            _ (core/register-port! reg
                {:id :search :name "search" :description "Search using DB"
                 :handler (fn [ctx]
                            (let [conn @(:conn* db-pool)]
                              {:result (query-db conn (:query (:params ctx)))}))})
            adp (mcp/create-mcp-adapter {:server-info config})
            trn (http/create-http-transport {:port (:port config)})]
        (core/transport-start trn (make-handler reg adp trn))
        (assoc this :registry reg :adapter adp :transport trn)))

    (stop [this]
      (when transport (core/transport-stop transport))
      (assoc this :registry nil :adapter nil :transport nil)))

  ;; System assembly
  (defn new-system [config]
    (component/system-map
      :db-pool (new-db-pool (:db config))
      :mcp-server (component/using
                    (map->McpServer {:config (:mcp config)})
                    [:db-pool])))

  ;; Usage
  (def system (component/start (new-system config)))
  (component/stop system))

;; ============================================================================
;; REITIT INTEGRATION (JVM)
;; ============================================================================

(comment
  "Add MCP endpoint alongside existing Reitit routes."

  (require '[reitit.ring :as ring]
           '[ring.adapter.jetty :as jetty])

  ;; MCP handler
  (defn mcp-handler [registry adapter]
    (fn [req]
      (let [body (parse-json (:body req))
            result (core/protocol-dispatch adapter (:method body) (:params body)
                     {:port-registry registry :request body})]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:jsonrpc "2.0" :id (:id body) :result result})})))

  ;; Combined routes
  (def app
    (ring/ring-handler
      (ring/router
        [["/api" ["/users" {:get list-users}] ["/posts" {:get list-posts}]]
         ["/mcp" ["/rpc" {:post {:handler (mcp-handler registry adapter)}}]
                 ["/health" {:get (fn [_] {:status 200 :body "OK"})}]]])))

  (jetty/run-jetty app {:port 3000 :join? false}))

;; ============================================================================
;; MOUNT INTEGRATION (JVM)
;; ============================================================================

(comment
  "Integrate with Mount state management."

  (require '[mount.core :as mount :refer [defstate]])

  (defstate mcp-registry
    :start (doto (registry/create-function-registry)
             (core/register-port! {:id :search :handler search-handler}))
    :stop nil)

  (defstate mcp-server
    :start (let [adapter (mcp/create-mcp-adapter {:server-info {:name "app"}})
                 transport (http/create-http-transport {:port 8080})]
             (core/transport-start transport (make-handler mcp-registry adapter transport))
             {:adapter adapter :transport transport})
    :stop (core/transport-stop (:transport mcp-server)))

  (mount/start)
  (mount/stop))

;; ============================================================================
;; INTEGRANT INTEGRATION (JVM)
;; ============================================================================

(comment
  "Integrate with Integrant."

  (require '[integrant.core :as ig])

  (defmethod ig/init-key :mcp/registry [_ _]
    (registry/create-function-registry))

  (defmethod ig/init-key :mcp/server [_ {:keys [registry port]}]
    (let [adapter (mcp/create-mcp-adapter {})
          transport (http/create-http-transport {:port port})]
      (core/transport-start transport (make-handler registry adapter transport))
      {:adapter adapter :transport transport}))

  (defmethod ig/halt-key! :mcp/server [_ {:keys [transport]}]
    (core/transport-stop transport))

  (def config {:mcp/registry {} :mcp/server {:registry (ig/ref :mcp/registry) :port 8080}})

  (def system (ig/init config))
  (ig/halt! system))

;; ============================================================================
;; EXPRESS INTEGRATION (Node.js)
;; ============================================================================

#?(:cljs
   (comment
     "Integrate with Express.js (Node.js)."

     (def express (js/require "express"))

     (defn create-mcp-app [registry adapter]
       (let [app (express)]
         (.use app (.json express))
         (.post app "/mcp/rpc"
           (fn [req res]
             (let [body (js->clj (.-body req) :keywordize-keys true)
                   result (core/protocol-dispatch adapter (:method body) (:params body)
                            {:port-registry registry :request body})]
               (.json res (clj->js {:jsonrpc "2.0" :id (:id body) :result result})))))
         (.get app "/health" (fn [_ res] (.send res "OK")))
         app))

     (def app (create-mcp-app registry adapter))
     (.listen app 3000)))