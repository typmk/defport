(ns defport.inspect
  "REPL introspection support via datafy/nav.

  Extends Datafiable protocol for defport types to enable rich REPL
  exploration with Portal, REBL, Gadget Inspector, and other datafy-aware tools.

  Usage:
    (require '[defport.inspect]) ; Load extensions
    (require '[defport.util.platform :as p])

    ;; Inspect MCP adapter
    (p/datafy-value mcp-adapter)
    ;; => {:type :mcp-adapter
    ;;     :protocol-id :mcp
    ;;     :protocol-version \"2025-06-18\"
    ;;     :server-info {:name \"my-server\" :version \"1.0.0\"}
    ;;     :capabilities {...}
    ;;     :method-handlers [...]}

    ;; Navigate into method handlers
    (p/nav-value (p/datafy-value adapter) :method-handlers nil)
    ;; => [\"initialize\" \"tools/list\" \"tools/call\" ...]

  Cross-platform: Works on both JVM (Clojure) and ClojureScript."
  (:require [defport.core :as core]
            [defport.registry :as registry]
            [defport.protocols.mcp :as mcp]
            [defport.util.platform :as platform]
            #?(:clj [clojure.core.protocols :as p])))

;; ============================================================================
;; Port Types
;; ============================================================================

#?(:clj
   (extend-type defport.registry.PortImpl
     p/Datafiable
     (datafy [port]
       (with-meta
         {:type :port
          :id (:id port)
          :description (:description port)
          :input-schema (get-in port [:schema-map :input-schema])
          :output-schema (get-in port [:schema-map :output-schema])
          :metadata (:metadata-map port)
          :has-handler? (some? (:handler-fn port))}
         {`p/nav (fn [_data k _v]
                   (case k
                     :handler-fn (:handler-fn port)
                     :full-schema (:schema-map port)
                     nil))})))

   :cljs
   (extend-type defport.registry.PortImpl
     IDatafiable
     (-datafy [port]
       (with-meta
         {:type :port
          :id (:id port)
          :description (:description port)
          :input-schema (get-in port [:schema-map :input-schema])
          :output-schema (get-in port [:schema-map :output-schema])
          :metadata (:metadata-map port)
          :has-handler? (some? (:handler-fn port))}
         {'cljs.core/-nav (fn [_data k _v]
                            (case k
                              :handler-fn (:handler-fn port)
                              :full-schema (:schema-map port)
                              nil))}))))

;; ============================================================================
;; Registry Types
;; ============================================================================

#?(:clj
   (extend-type defport.registry.FunctionPortRegistry
     p/Datafiable
     (datafy [registry]
       (let [ports @(:ports* registry)]
         (with-meta
           {:type :function-registry
            :port-count (count ports)
            :port-ids (vec (keys ports))}
           {`p/nav (fn [_data k _v]
                     (case k
                       :ports (vec (vals ports))
                       :port-map ports
                       nil))}))))

   :cljs
   (extend-type defport.registry.FunctionPortRegistry
     IDatafiable
     (-datafy [registry]
       (let [ports @(:ports* registry)]
         (with-meta
           {:type :function-registry
            :port-count (count ports)
            :port-ids (vec (keys ports))}
           {'cljs.core/-nav (fn [_data k _v]
                              (case k
                                :ports (vec (vals ports))
                                :port-map ports
                                nil))})))))

#?(:clj
   (extend-type defport.registry.EdnPortRegistry
     p/Datafiable
     (datafy [registry]
       (let [ports @(:ports* registry)]
         (with-meta
           {:type :edn-registry
            :edn-source (:edn-source registry)
            :port-count (count ports)
            :port-ids (vec (keys ports))}
           {`p/nav (fn [_data k _v]
                     (case k
                       :ports (vec (vals ports))
                       :port-map ports
                       nil))}))))

   :cljs
   (extend-type defport.registry.EdnPortRegistry
     IDatafiable
     (-datafy [registry]
       (let [ports @(:ports* registry)]
         (with-meta
           {:type :edn-registry
            :edn-source (:edn-source registry)
            :port-count (count ports)
            :port-ids (vec (keys ports))}
           {'cljs.core/-nav (fn [_data k _v]
                              (case k
                                :ports (vec (vals ports))
                                :port-map ports
                                nil))})))))

#?(:clj
   (extend-type defport.registry.HybridPortRegistry
     p/Datafiable
     (datafy [registry]
       (let [ports @(:ports* registry)]
         (with-meta
           {:type :hybrid-registry
            :edn-sources (:edn-sources registry)
            :port-count (count ports)
            :port-ids (vec (keys ports))}
           {`p/nav (fn [_data k _v]
                     (case k
                       :ports (vec (vals ports))
                       :port-map ports
                       nil))}))))

   :cljs
   (extend-type defport.registry.HybridPortRegistry
     IDatafiable
     (-datafy [registry]
       (let [ports @(:ports* registry)]
         (with-meta
           {:type :hybrid-registry
            :edn-sources (:edn-sources registry)
            :port-count (count ports)
            :port-ids (vec (keys ports))}
           {'cljs.core/-nav (fn [_data k _v]
                              (case k
                                :ports (vec (vals ports))
                                :port-map ports
                                nil))})))))

;; ============================================================================
;; MCP Adapter
;; ============================================================================

#?(:clj
   (extend-type defport.protocols.mcp.McpAdapter
     p/Datafiable
     (datafy [adapter]
       (let [handlers @(:method-handlers* adapter)
             opts (:adapter-opts adapter)]
         (with-meta
           {:type :mcp-adapter
            :protocol-id :mcp
            :protocol-version "2025-06-18"
            :server-info (:server-info adapter)
            :refactoring-enabled? (:refactoring-enabled? opts)
            :subscriptions-enabled? (:enable-subscriptions? opts)
            :uri-scheme (:uri-scheme opts)
            :method-count (count handlers)
            :methods (vec (sort (keys handlers)))
            ;; Protocol state snapshot
            :active-operations (count @mcp/active-operations*)
            :resource-subscriptions (count @mcp/resource-subscriptions*)
            :elicitations (count @mcp/elicitation-state*)
            :sampling-requests (count @mcp/sampling-state*)}
           {`p/nav (fn [_data k _v]
                     (case k
                       :method-handlers handlers
                       :adapter-opts opts
                       :performance (:performance opts)
                       :active-operations-map @mcp/active-operations*
                       :subscriptions-map @mcp/resource-subscriptions*
                       nil))}))))

   :cljs
   (extend-type defport.protocols.mcp.McpAdapter
     IDatafiable
     (-datafy [adapter]
       (let [handlers @(:method-handlers* adapter)
             opts (:adapter-opts adapter)]
         (with-meta
           {:type :mcp-adapter
            :protocol-id :mcp
            :protocol-version "2025-06-18"
            :server-info (:server-info adapter)
            :refactoring-enabled? (:refactoring-enabled? opts)
            :subscriptions-enabled? (:enable-subscriptions? opts)
            :uri-scheme (:uri-scheme opts)
            :method-count (count handlers)
            :methods (vec (sort (keys handlers)))
            ;; Protocol state snapshot
            :active-operations (count @mcp/active-operations*)
            :resource-subscriptions (count @mcp/resource-subscriptions*)
            :elicitations (count @mcp/elicitation-state*)
            :sampling-requests (count @mcp/sampling-state*)}
           {'cljs.core/-nav (fn [_data k _v]
                              (case k
                                :method-handlers handlers
                                :adapter-opts opts
                                :performance (:performance opts)
                                :active-operations-map @mcp/active-operations*
                                :subscriptions-map @mcp/resource-subscriptions*
                                nil))})))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn inspect
  "Convenience function to datafy a defport object.

  Example:
    (inspect my-adapter)
    ;; => {:type :mcp-adapter ...}"
  [obj]
  (platform/datafy-value obj))

(defn registry-summary
  "Get a summary of a port registry.

  Returns map with port names, descriptions, and metadata."
  [registry]
  (let [ports (core/list-ports registry)]
    {:port-count (count ports)
     :ports (mapv (fn [p]
                    {:id (:id p)
                     :description (:description p)
                     :dangerous? (get-in p [:metadata :dangerous] false)
                     :prompt? (get-in p [:metadata :prompt] false)
                     :resource? (get-in p [:metadata :resource] false)})
                  ports)}))

(defn adapter-summary
  "Get a summary of an MCP adapter's current state.

  Useful for debugging connection issues."
  [adapter]
  {:server-info (:server-info adapter)
   :methods (vec (sort (keys @(:method-handlers* adapter))))
   :state {:active-operations (count @mcp/active-operations*)
           :subscriptions (count @mcp/resource-subscriptions*)
           :pending-elicitations (count @mcp/elicitation-state*)
           :pending-sampling (count @mcp/sampling-state*)
           :seen-request-ids (count @mcp/seen-request-ids*)}})
