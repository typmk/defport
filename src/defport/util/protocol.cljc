(ns defport.util.protocol
  "Protocol-level utilities for JSON-RPC based protocols.

  Provides request ID validation and operation cancellation support.
  These are protocol-agnostic features used by MCP, LSP, DAP, and custom protocols.")

;;; ============================================================================
;;; Request ID Validation
;;; ============================================================================

(defonce seen-request-ids* (atom #{}))

(defn validate-request-id
  "Validate that a request ID is unique within the session.

  request-id: JSON-RPC request ID (can be nil for notifications)

  Returns true if valid (or nil, which is valid for notifications), false if duplicate.
  Automatically tracks IDs in session-scoped atom."
  [request-id]
  (if (nil? request-id)
    true  ; Notifications don't have IDs, always valid
    (if (contains? @seen-request-ids* request-id)
      false  ; Duplicate ID
      (do
        (swap! seen-request-ids* conj request-id)
        true))))  ; New ID, add to set

(defn reset-request-ids!
  "Clear all tracked request IDs. Useful for testing or session resets."
  []
  (reset! seen-request-ids* #{}))

;;; ============================================================================
;;; Cancellation Support
;;; ============================================================================

(defonce active-operations* (atom {}))

(defn generate-call-id
  "Generate a unique call ID for an operation.

  Returns string ID in format: 'call-TIMESTAMP-RANDOM'

  Platform-agnostic using reader conditionals for timestamp."
  []
  (let [timestamp #?(:clj (System/currentTimeMillis)
                     :cljs (.now js/Date))
        random (rand-int 100000)]
    (str "call-" timestamp "-" random)))

(defn register-operation
  "Register an operation with its cancellation flag.

  call-id: unique call ID string

  Returns call-id for chaining.
  Creates cancellation flag atom initialized to false."
  [call-id]
  (swap! active-operations* assoc call-id (atom false))
  call-id)

(defn cancel-operation
  "Mark an operation as cancelled.

  call-id: unique call ID string

  Sets the operation's cancellation flag to true."
  [call-id]
  (when-let [cancelled-flag (get @active-operations* call-id)]
    (reset! cancelled-flag true)))

(defn is-cancelled?
  "Check if an operation is cancelled.

  call-id: unique call ID string

  Returns boolean, or nil if operation not found."
  [call-id]
  (when-let [cancelled-flag (get @active-operations* call-id)]
    @cancelled-flag))

(defn unregister-operation
  "Unregister an operation when it completes.

  call-id: unique call ID string

  Removes operation from active operations map."
  [call-id]
  (swap! active-operations* dissoc call-id))

(defn get-cancellation-check
  "Get a cancellation check function for an operation.

  call-id: unique call ID string

  Returns a zero-arg function that returns true if operation is cancelled.
  This can be passed to long-running operations for periodic cancellation checks."
  [call-id]
  (fn [] (is-cancelled? call-id)))

(defn reset-operations!
  "Clear all active operations. Useful for testing or session resets."
  []
  (reset! active-operations* {}))
