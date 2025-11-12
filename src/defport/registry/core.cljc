(ns defport.registry.core
  "Port registry implementations for defport.

  Provides three registry types:
  - EdnPortRegistry: Load ports from EDN files
  - FunctionPortRegistry: Register ports programmatically
  - HybridPortRegistry: Support both approaches"
  (:require [defport.core :as core]
            [defport.util.edn :as edn]))

;;; Port record implementation

(defrecord PortImpl [id description schema-map handler-fn metadata-map]
  core/Port
  (port-id [_] id)
  (port-schema [_] (assoc schema-map :metadata metadata-map))
  (port-execute [_ context]
    (handler-fn context)))

(defn create-port
  "Create a Port implementation from a map.

  port-def: map with:
    - :id - port identifier (keyword)
    - :name - optional port name (defaults to str of :id)
    - :description - optional human-readable description
    - :input-schema - input JSON Schema or Malli schema
    - :output-schema - output schema
    - :handler - handler function (fn [context] -> {:result data} or {:error ...})
    - :metadata - optional metadata map
    - :annotations - optional MCP annotations map

  Returns Port implementation."
  [port-def]
  (map->PortImpl
    {:id (:id port-def)
     :description (:description port-def "")
     :schema-map {:input-schema (:input-schema port-def {})
                  :output-schema (:output-schema port-def {})}
     :handler-fn (:handler port-def)
     :metadata-map (merge
                     (or (:metadata port-def) {})
                     (when (:annotations port-def)
                       {:annotations (:annotations port-def)}))}))

(defn port-descriptor
  "Get a descriptor map for a port (for listing).

  port: Port implementation

  Returns map with:
    {:id :port-id
     :description \"...\"
     :input-schema {...}
     :output-schema {...}
     :metadata {...}}"
  [port]
  (let [schema (core/port-schema port)]
    {:id (core/port-id port)
     :description (:description port "")
     :input-schema (:input-schema schema)
     :output-schema (:output-schema schema)
     :metadata (-> port :metadata-map)}))

;;; EDN Port Registry

(defrecord EdnPortRegistry [ports* edn-source]
  core/PortRegistry
  (list-ports [_]
    ;; Return port descriptors for protocol adapters
    (mapv port-descriptor (vals @ports*)))

  (get-port [_ port-id]
    (get @ports* port-id))

  (register-port! [this port-def]
    (let [port (if (core/port? port-def)
                 port-def
                 (create-port port-def))
          port-id (core/port-id port)]
      (swap! ports* assoc port-id port)
      port)))

(defn load-ports-from-edn
  "Load port definitions from EDN file or map.

  EDN format:
    {:ports
     {:port-id-1
      {:id :port-id-1
       :description \"...\"
       :input-schema {...}
       :output-schema {...}
       :handler my.ns/handler-fn
       :metadata {...}}
      :port-id-2 {...}}}

  Returns map of port-id -> Port."
  [edn-source]
  (let [edn-data (edn/load-edn edn-source)
        ports-map (:ports edn-data)]
    (reduce-kv
      (fn [m port-id port-def]
        (assoc m port-id (create-port (assoc port-def :id port-id))))
      {}
      ports-map)))

(defn create-edn-registry
  "Create a port registry from EDN file or map.

  edn-source: EDN file path, classpath:path, or map

  Returns EdnPortRegistry instance.

  Example:
    (create-edn-registry 'resources/defnet/protocols.edn')
    (create-edn-registry 'classpath:scout/tools.edn')
    (create-edn-registry {:ports {...}})"
  [edn-source]
  (let [ports (load-ports-from-edn edn-source)]
    (->EdnPortRegistry (atom ports) edn-source)))

;;; Function Port Registry

(defrecord FunctionPortRegistry [ports*]
  core/PortRegistry
  (list-ports [_]
    ;; Return port descriptors for protocol adapters
    (mapv port-descriptor (vals @ports*)))

  (get-port [_ port-id]
    (get @ports* port-id))

  (register-port! [this port-def]
    (let [port (if (core/port? port-def)
                 port-def
                 (create-port port-def))
          port-id (core/port-id port)]
      (swap! ports* assoc port-id port)
      port)))

(defn create-function-registry
  "Create an empty port registry for programmatic registration.

  Returns FunctionPortRegistry instance.

  Example:
    (def registry (create-function-registry))
    (register-port! registry
      {:id :my-port
       :input-schema {...}
       :handler my-handler-fn})"
  []
  (->FunctionPortRegistry (atom {})))

;;; Hybrid Port Registry

(defrecord HybridPortRegistry [ports* edn-sources]
  core/PortRegistry
  (list-ports [_]
    ;; Return port descriptors for protocol adapters
    (mapv port-descriptor (vals @ports*)))

  (get-port [_ port-id]
    (get @ports* port-id))

  (register-port! [this port-def]
    (let [port (if (core/port? port-def)
                 port-def
                 (create-port port-def))
          port-id (core/port-id port)]
      (swap! ports* assoc port-id port)
      port)))

(defn create-hybrid-registry
  "Create a hybrid port registry supporting both EDN and programmatic registration.

  edn-sources: optional vector of EDN file paths or maps to load initially

  Returns HybridPortRegistry instance.

  Example:
    (def registry (create-hybrid-registry ['resources/base-tools.edn']))
    (register-port! registry {:id :custom-tool :handler ...})"
  ([]
   (create-hybrid-registry nil))
  ([edn-sources]
   (let [initial-ports (if edn-sources
                        (reduce
                          (fn [acc source]
                            (merge acc (load-ports-from-edn source)))
                          {}
                          edn-sources)
                        {})]
     (->HybridPortRegistry (atom initial-ports) (or edn-sources [])))))

;;; Helper functions

(defn register-ports-from-edn!
  "Register multiple ports from EDN source into an existing registry.

  registry: any PortRegistry implementation
  edn-source: EDN file path, classpath:path, or map

  Returns registry."
  [registry edn-source]
  (let [ports (load-ports-from-edn edn-source)]
    (doseq [[_ port] ports]
      (core/register-port! registry port))
    registry))

(defn list-port-descriptors
  "Get descriptors for all ports in a registry.

  registry: PortRegistry implementation

  Returns vector of descriptor maps."
  [registry]
  (mapv port-descriptor (core/list-ports registry)))
