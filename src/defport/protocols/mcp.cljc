(ns defport.protocols.mcp
  "Model Context Protocol (MCP) adapter for defport.

  Implements the MCP protocol (2025-06-18 spec) as a ProtocolAdapter.
  Handles JSON-RPC 2.0 message routing, tool calls, resource access, and prompts.

  Platform-agnostic using reader conditionals for JVM/Node.js compatibility."
  (:require [defport.core :as core]
            [defport.util.protocol :as proto-util]
            [defport.util.pagination :as pagination]
            [defport.util.progress :as progress]
            [cheshire.core :as json]))

;; ============================================================================
;; Protocol State Management
;; ============================================================================

(defonce seen-request-ids* (atom #{}))
(defonce active-operations* (atom {}))

(defn reset-protocol-state!
  "Reset protocol state (for testing or reconnection).
  Clears all tracked request IDs and active operations."
  []
  (reset! seen-request-ids* #{})
  (reset! active-operations* {}))

(defn validate-request-id
  "Validate that a request ID is unique within the session.
  Returns true if valid (or nil for notifications), false if duplicate."
  [request-id]
  (if (nil? request-id)
    true  ; Notifications don't have IDs
    (if (contains? @seen-request-ids* request-id)
      false  ; Duplicate
      (do
        (swap! seen-request-ids* conj request-id)
        true))))

;; ============================================================================
;; Operation Cancellation Support
;; ============================================================================

(defn register-operation
  "Register an operation for cancellation tracking.
  Returns the call-id for chaining."
  [call-id]
  (swap! active-operations* assoc call-id (atom false))
  call-id)

(defn cancel-operation
  "Mark an operation as cancelled."
  [call-id]
  (when-let [cancelled-flag (get @active-operations* call-id)]
    (reset! cancelled-flag true)))

(defn is-cancelled?
  "Check if an operation is cancelled."
  [call-id]
  (when-let [cancelled-flag (get @active-operations* call-id)]
    @cancelled-flag))

(defn unregister-operation
  "Unregister an operation when complete."
  [call-id]
  (swap! active-operations* dissoc call-id))

;; ============================================================================
;; MCP Method Handlers
;; ============================================================================

(defn handle-initialize
  "Handle MCP initialize request.
  Returns protocol version and server capabilities."
  [_params _context server-info]
  {:protocolVersion "2025-06-18"
   :serverInfo (or server-info {:name "defport-mcp-server" :version "0.1.0"})
   :capabilities {:tools {}
                  :prompts {:listChanged false}
                  :resources {:subscribe false :listChanged false}}})

(defn handle-tools-list
  "Handle tools/list request with pagination."
  [params context]
  (let [cursor (:cursor params)
        registry (:port-registry context)
        all-ports (core/list-ports registry)
        ;; Filter for tool ports (exclude prompts and resources)
        tool-ports (filter (fn [port-def]
                            (not (or (get-in port-def [:metadata :prompt])
                                    (get-in port-def [:metadata :resource]))))
                          all-ports)
        ;; Convert ports to MCP tool format
        all-tools (mapv (fn [port-def]
                          (cond-> {:name (name (:id port-def))
                                   :description (:description port-def "")
                                   :inputSchema (:input-schema port-def {})}
                            (:annotations (:metadata port-def))
                            (assoc :annotations (:annotations (:metadata port-def)))))
                        tool-ports)
        ;; MCP spec uses page size of 10
        paginated (pagination/paginate-items all-tools cursor {:page-size 10})]
    (cond-> {:tools (:items paginated)}
      (:nextCursor paginated) (assoc :nextCursor (:nextCursor paginated)))))

(defn handle-tools-call
  "Handle tools/call request with progress and cancellation support."
  [params context]
  (let [tool-name (:name params)
        tool-params (:arguments params {})
        registry (:port-registry context)
        call-id (proto-util/generate-call-id)
        progress-token (get-in params [:_meta :progressToken])]

    (if (nil? tool-name)
      {:error {:code -32602 :message "Invalid params: missing tool name"}}

      (let [port (core/get-port registry (keyword tool-name))]
        (if (nil? port)
          {:error {:code -32602 :message (str "Unknown tool: " tool-name)}}

          (try
            (register-operation call-id)

            ;; Check if cancelled before starting
            (if (is-cancelled? call-id)
              {:error {:code -32800 :message "Operation was cancelled"}}

              (let [;; Create progress callback if token provided
                    progress-callback (when progress-token
                                       (progress/create-progress-callback
                                         progress-token
                                         (:transport context)))

                    ;; Create cancellation check
                    cancellation-check (fn [] (is-cancelled? call-id))

                    ;; Build execution context
                    exec-context (assoc context
                                   :params tool-params
                                   :metadata {:call-id call-id
                                             :progress-token progress-token
                                             :progress-callback progress-callback
                                             :cancellation-check cancellation-check})

                    ;; Execute the port
                    result (core/port-execute port exec-context)]

                ;; Check if cancelled during execution
                (if (is-cancelled? call-id)
                  {:error {:code -32800 :message "Operation was cancelled"}}

                  ;; Return result
                  (if-let [err (:error result)]
                    {:error err}
                    ;; MCP expects content array
                    {:content (or (:content result)
                                  [{:type "text"
                                    :text (json/generate-string (:result result))}])}))))

            (catch #?(:clj Exception :cljs js/Error) e
              {:error {:code -32603
                       :message (str "Internal error: "
                                     #?(:clj (.getMessage e)
                                        :cljs (.-message e)))}})

            (finally
              (unregister-operation call-id))))))))

(defn handle-tools-call-cancel
  "Handle tools/call/cancel request."
  [params _context]
  (let [call-id (:callId params)]
    (if (nil? call-id)
      {:error {:code -32602 :message "Invalid params: missing callId"}}
      (if (contains? @active-operations* call-id)
        (do
          (cancel-operation call-id)
          {})  ; Success
        {:error {:code -32602 :message (str "Operation not found: " call-id)}}))))

(defn handle-prompts-list
  "Handle prompts/list request with pagination.
  Prompts are ports with :prompt metadata."
  [params context]
  (let [cursor (:cursor params)
        registry (:port-registry context)
        all-ports (core/list-ports registry)
        ;; Filter ports with :prompt metadata
        prompt-ports (filter #(get-in % [:metadata :prompt]) all-ports)
        ;; Convert to MCP prompt format
        all-prompts (mapv (fn [port-def]
                            {:name (name (:id port-def))
                             :description (:description port-def "")
                             :arguments (:prompt-args (:metadata port-def) [])})
                          prompt-ports)
        ;; MCP spec uses page size of 10
        paginated (pagination/paginate-items all-prompts cursor {:page-size 10})]
    (cond-> {:prompts (:items paginated)}
      (:nextCursor paginated) (assoc :nextCursor (:nextCursor paginated)))))

(defn handle-prompts-get
  "Handle prompts/get request."
  [params context]
  (let [prompt-name (:name params)
        prompt-args (:arguments params {})]
    (if (nil? prompt-name)
      {:error {:code -32602 :message "Invalid params: missing prompt name"}}

      (let [registry (:port-registry context)
            port (core/get-port registry (keyword prompt-name))]
        (if (nil? port)
          {:error {:code -32602 :message (str "Unknown prompt: " prompt-name)}}

          (if-not (get-in (core/port-schema port) [:metadata :prompt])
            {:error {:code -32602 :message (str "Not a prompt: " prompt-name)}}

            (try
              (let [exec-context (assoc context :params prompt-args)
                    result (core/port-execute port exec-context)]
                (if-let [err (:error result)]
                  {:error err}
                  ;; Prompts return messages array
                  {:messages (or (:messages result)
                                 [{:role "user"
                                   :content {:type "text"
                                            :text (json/generate-string (:result result))}}])}))

              (catch #?(:clj Exception :cljs js/Error) e
                {:error {:code -32603
                         :message (str "Internal error: "
                                       #?(:clj (.getMessage e)
                                          :cljs (.-message e)))}}))))))))

(defn handle-resources-list
  "Handle resources/list request with pagination.
  Resources are ports with :resource metadata."
  [params context]
  (let [cursor (:cursor params)
        registry (:port-registry context)
        all-ports (core/list-ports registry)
        ;; Filter ports with :resource metadata
        resource-ports (filter #(get-in % [:metadata :resource]) all-ports)
        ;; Convert to MCP resource format
        all-resources (mapv (fn [port-def]
                              {:uri (str "defport://" (name (:id port-def)))
                               :name (name (:id port-def))
                               :description (:description port-def "")
                               :mimeType (get-in port-def [:metadata :mime-type] "application/json")})
                            resource-ports)
        ;; MCP spec uses page size of 10
        paginated (pagination/paginate-items all-resources cursor {:page-size 10})]
    (cond-> {:resources (:items paginated)}
      (:nextCursor paginated) (assoc :nextCursor (:nextCursor paginated)))))

(defn handle-resources-read
  "Handle resources/read request."
  [params context]
  (let [uri (:uri params)]
    (if (nil? uri)
      {:error {:code -32602 :message "Invalid params: missing resource URI"}}

      ;; Parse URI (defport://port-id format)
      (if-let [port-id (when (.startsWith uri "defport://")
                        (keyword (subs uri 10)))]
        (let [registry (:port-registry context)
              port (core/get-port registry port-id)]
          (if (nil? port)
            {:error {:code -32602 :message (str "Unknown resource: " uri)}}

            (if-not (get-in (core/port-schema port) [:metadata :resource])
              {:error {:code -32602 :message (str "Not a resource: " uri)}}

              (try
                (let [exec-context (assoc context :params {:uri uri})
                      result (core/port-execute port exec-context)]
                  (if-let [err (:error result)]
                    {:error err}
                    ;; Resources return contents array
                    {:contents (or (:contents result)
                                   [{:uri uri
                                     :mimeType "application/json"
                                     :text (json/generate-string (:result result))}])}))

                (catch #?(:clj Exception :cljs js/Error) e
                  {:error {:code -32603
                           :message (str "Internal error: "
                                         #?(:clj (.getMessage e)
                                            :cljs (.-message e)))}})))))

        {:error {:code -32602 :message (str "Invalid resource URI: " uri)}}))))

(defn handle-ping
  "Handle ping request (convenience, not in MCP spec)."
  [_params _context server-info]
  {:pong true
   :server (:name server-info "defport-mcp-server")
   :version (:version server-info "0.1.0")})

;; ============================================================================
;; MCP Protocol Adapter Implementation
;; ============================================================================

(defrecord McpAdapter [server-info method-handlers*]
  core/ProtocolAdapter

  (protocol-id [_]
    :mcp)

  (protocol-version [_]
    "2025-06-18")

  (protocol-capabilities [_ port-registry]
    (let [ports (core/list-ports port-registry)
          has-prompts? (some #(get-in % [:metadata :prompt]) ports)
          has-resources? (some #(get-in % [:metadata :resource]) ports)]
      {:tools {}
       :prompts (when has-prompts? {:listChanged false})
       :resources (when has-resources? {:subscribe false :listChanged false})}))

  (protocol-dispatch [this method params context]
    (let [handlers @method-handlers*
          handler (get handlers method)]
      (if handler
        (try
          ;; Validate request ID if present
          (when-let [request-id (:id (:request context))]
            (when-not (validate-request-id request-id)
              (throw (ex-info "Duplicate request ID"
                              {:code -32600
                               :message "Invalid Request: duplicate request ID"}))))

          ;; Call handler
          (handler params context)

          (catch #?(:clj Exception :cljs js/Error) e
            {:error {:code -32603
                     :message (str "Internal error: "
                                   #?(:clj (.getMessage e)
                                      :cljs (.-message e)))}}))

        ;; Unknown method
        {:error {:code -32601 :message (str "Method not found: " method)}}))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn create-mcp-adapter
  "Create an MCP protocol adapter.

  Options:
  - :server-info - Map with :name and :version (default: defport-mcp-server v0.1.0)
  - :custom-handlers - Map of method name -> handler fn (overrides defaults)

  Returns McpAdapter instance implementing ProtocolAdapter protocol.

  Example:
    (def adapter (create-mcp-adapter {:server-info {:name \"my-server\" :version \"1.0.0\"}}))
    (core/protocol-dispatch adapter \"tools/list\" {} context)"
  ([]
   (create-mcp-adapter nil))

  ([opts]
   (let [server-info (or (:server-info opts)
                        {:name "defport-mcp-server" :version "0.1.0"})
         custom-handlers (:custom-handlers opts {})

         ;; Default method handlers
         default-handlers {"initialize" #(handle-initialize %1 %2 server-info)
                          "tools/list" handle-tools-list
                          "tools/call" handle-tools-call
                          "tools/call/cancel" handle-tools-call-cancel
                          "prompts/list" handle-prompts-list
                          "prompts/get" handle-prompts-get
                          "resources/list" handle-resources-list
                          "resources/read" handle-resources-read
                          "ping" #(handle-ping %1 %2 server-info)}

         ;; Merge custom handlers
         method-handlers (merge default-handlers custom-handlers)]

     (->McpAdapter server-info (atom method-handlers)))))

(defn register-custom-handler!
  "Register or override a custom method handler.

  adapter - McpAdapter instance
  method - Method name string (e.g., \"custom/analyze\")
  handler - Handler fn with signature: (fn [params context] -> result)

  Example:
    (register-custom-handler! adapter \"custom/analyze\"
      (fn [params context]
        {:result \"analyzed\"}))"
  [adapter method handler]
  (swap! (:method-handlers* adapter) assoc method handler))
