(ns defport.dap
  "Debug Adapter Protocol (DAP) adapter for defport.

  Implements the DAP protocol (v1.51) as a ProtocolAdapter.
  Handles debug session lifecycle, breakpoints, stepping, and evaluation.

  Platform-agnostic using reader conditionals for JVM/Node.js compatibility.

  ## Implementation Levels

  This adapter supports multiple backend modes:

  - :repl     - REPL-as-debugger (Level 1) - evaluate/variables only
  - :nrepl    - nREPL/CIDER bridge (Level 2) - breakpoints via CIDER
  - :flowstorm - FlowStorm bridge (Level 3) - time-travel debugging
  - :jdi      - Java Debug Interface (Level 4) - JVM debugging
  - :proxy    - Multi-backend proxy (Level 5) - language-agnostic

  ## Observability via tap>

  This adapter emits tap> events at key points for observability.
  Events have the shape:

    {:event :dap/<event-type>
     :timestamp <epoch-ms>
     ...event-specific-data}

  Event types:
  - :dap/initialized - Debug session initialized
  - :dap/launched - Debuggee launched
  - :dap/attached - Attached to debuggee
  - :dap/breakpoint-set - Breakpoint configured
  - :dap/stopped - Execution stopped (breakpoint, step, etc.)
  - :dap/continued - Execution continued
  - :dap/evaluate - Expression evaluated
  - :dap/error - Error occurred

  Usage:
    ;; Development - print all events
    (add-tap println)

    ;; Production - route to metrics
    (add-tap (fn [e]
               (when (and (map? e) (= (namespace (:event e)) \"dap\"))
                 (record-metric! e))))"
  (:require [defport.core :as core]
            [cheshire.core :as json]))

;; ============================================================================
;; Observability Helpers
;; ============================================================================

(defn- current-timestamp
  "Get current timestamp in milliseconds."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

(defn- emit-event!
  "Emit a tap> event for observability.
  Events are maps with :event key identifying the event type.
  Zero overhead when no taps registered."
  [event-type data]
  (tap> (assoc data
          :event event-type
          :timestamp (current-timestamp))))

;; ============================================================================
;; DAP Protocol Constants
;; ============================================================================

(def ^:const dap-version "1.51")

;; DAP message types
(def ^:const msg-type-request "request")
(def ^:const msg-type-response "response")
(def ^:const msg-type-event "event")

;; DAP stop reasons
(def ^:const stop-reason-step "step")
(def ^:const stop-reason-breakpoint "breakpoint")
(def ^:const stop-reason-exception "exception")
(def ^:const stop-reason-pause "pause")
(def ^:const stop-reason-entry "entry")
(def ^:const stop-reason-goto "goto")
(def ^:const stop-reason-function-breakpoint "function breakpoint")
(def ^:const stop-reason-data-breakpoint "data breakpoint")

;; ============================================================================
;; DAP Message Codec
;; ============================================================================

(defn make-response
  "Create a DAP response message."
  [seq-num request body & {:keys [success message] :or {success true}}]
  (cond-> {:seq seq-num
           :type msg-type-response
           :request_seq (:seq request)
           :success success
           :command (:command request)}
    body (assoc :body body)
    message (assoc :message message)))

(defn make-event
  "Create a DAP event message."
  [seq-num event-name body]
  {:seq seq-num
   :type msg-type-event
   :event event-name
   :body body})

(defn make-error-response
  "Create a DAP error response."
  [seq-num request message & [error-details]]
  (cond-> {:seq seq-num
           :type msg-type-response
           :request_seq (:seq request)
           :success false
           :command (:command request)
           :message message}
    error-details (assoc :body {:error error-details})))

;; ============================================================================
;; DAP Session State Management
;; ============================================================================

(defn create-state
  "Create a new DAP session state."
  []
  (atom {:session-id (str (random-uuid))
         :initialized? false
         :configured? false
         :launched? false
         :attached? false

         ;; Sequence counter for outgoing messages
         :seq-counter (atom 0)

         ;; Thread tracking
         :threads {}
         :stopped-threads #{}
         :all-threads-stopped? false

         ;; Breakpoints
         :breakpoints {}          ; path -> [{:line :condition :hitCondition}]
         :exception-breakpoints #{} ; exception filter ids
         :function-breakpoints []

         ;; Variable references (expire on continue!)
         :next-var-ref (atom 1000)
         :var-refs {}             ; ref-id -> {:type :value/scope/frame :data ...}

         ;; Stack frames
         :next-frame-id (atom 0)
         :frames {}               ; frame-id -> frame-data

         ;; Scopes
         :next-scope-id (atom 0)
         :scopes {}               ; scope-id -> scope-data

         ;; Backend-specific state
         :backend-state {}}))

(defn next-seq!
  "Get next sequence number for outgoing messages."
  [state]
  (swap! (:seq-counter @state) inc))

(defn clear-transient-state!
  "Clear state that expires when execution resumes."
  [state]
  (swap! state assoc
    :var-refs {}
    :frames {}
    :scopes {}
    :stopped-threads #{}
    :all-threads-stopped? false))

(defn set-initialized!
  "Mark session as initialized."
  [state]
  (swap! state assoc :initialized? true))

(defn set-configured!
  "Mark session as configuration done."
  [state]
  (swap! state assoc :configured? true))

(defn set-launched!
  "Mark session as launched."
  [state]
  (swap! state assoc :launched? true))

(defn set-attached!
  "Mark session as attached."
  [state]
  (swap! state assoc :attached? true))

(defn reset-state!
  "Reset session state."
  [state]
  (reset! state (-> @(create-state)
                    (assoc :session-id (:session-id @state)))))

;; ============================================================================
;; Variable Reference Management
;; ============================================================================

(defn create-var-ref
  "Create a variable reference and store mapping."
  [state var-type data]
  (let [ref-id (swap! (:next-var-ref @state) inc)]
    (swap! state update :var-refs assoc ref-id {:type var-type :data data})
    ref-id))

(defn get-var-ref
  "Get data for a variable reference."
  [state ref-id]
  (get-in @state [:var-refs ref-id]))

(defn value-has-children?
  "Check if a Clojure value has children to expand."
  [value]
  (or (map? value)
      (vector? value)
      (seq? value)
      (set? value)
      (instance? #?(:clj clojure.lang.IRecord :cljs cljs.core/IRecord) value)))

(defn create-var-ref-for-value
  "Create a variable reference for a Clojure value if it has children."
  [state value]
  (if (value-has-children? value)
    (create-var-ref state :value value)
    0))

(defn truncate-str
  "Truncate a string to max length with ellipsis."
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (- max-len 3)) "...")
    s))

(defn type-name
  "Get a readable type name for a value."
  [value]
  (cond
    (nil? value) "nil"
    (map? value) (str "Map[" (count value) "]")
    (vector? value) (str "Vector[" (count value) "]")
    (list? value) (str "List[" (count value) "]")
    (seq? value) "LazySeq"
    (set? value) (str "Set[" (count value) "]")
    (string? value) "String"
    (number? value) (if (integer? value) "Integer" "Number")
    (boolean? value) "Boolean"
    (keyword? value) "Keyword"
    (symbol? value) "Symbol"
    (fn? value) "Function"
    :else (str (type value))))

(defn value->variables
  "Convert a Clojure value to DAP variables."
  [state value]
  (cond
    (map? value)
    (mapv (fn [[k v]]
            {:name (truncate-str (pr-str k) 50)
             :value (truncate-str (pr-str v) 100)
             :type (type-name v)
             :variablesReference (create-var-ref-for-value state v)})
          value)

    (or (vector? value) (list? value) (seq? value))
    (map-indexed (fn [i v]
                   {:name (str "[" i "]")
                    :value (truncate-str (pr-str v) 100)
                    :type (type-name v)
                    :variablesReference (create-var-ref-for-value state v)})
                 (take 1000 value))  ; Limit for large collections

    (set? value)
    (map-indexed (fn [i v]
                   {:name (str "#{" i "}")
                    :value (truncate-str (pr-str v) 100)
                    :type (type-name v)
                    :variablesReference (create-var-ref-for-value state v)})
                 (take 1000 value))

    :else []))

;; ============================================================================
;; Default Capabilities (REPL-as-debugger mode)
;; ============================================================================

(def default-capabilities
  "Default DAP capabilities for REPL-as-debugger mode."
  {:supportsConfigurationDoneRequest true
   :supportsFunctionBreakpoints false
   :supportsConditionalBreakpoints false
   :supportsHitConditionalBreakpoints false
   :supportsEvaluateForHovers true
   :supportsStepBack false
   :supportsSetVariable false
   :supportsRestartFrame false
   :supportsGotoTargetsRequest false
   :supportsStepInTargetsRequest false
   :supportsCompletionsRequest true
   :completionTriggerCharacters ["." "/" ":" "-"]
   :supportsModulesRequest false
   :supportsRestartRequest false
   :supportsExceptionOptions false
   :supportsValueFormattingOptions true
   :supportsExceptionInfoRequest false
   :supportTerminateDebuggee false
   :supportSuspendDebuggee false
   :supportsDelayedStackTraceLoading false
   :supportsLoadedSourcesRequest false
   :supportsLogPoints false
   :supportsTerminateThreadsRequest false
   :supportsSetExpression false
   :supportsTerminateRequest true
   :supportsDataBreakpoints false
   :supportsReadMemoryRequest false
   :supportsWriteMemoryRequest false
   :supportsDisassembleRequest false
   :supportsCancelRequest false
   :supportsBreakpointLocationsRequest false
   :supportsClipboardContext false
   :supportsSteppingGranularity false
   :supportsInstructionBreakpoints false
   :supportsExceptionFilterOptions false
   :supportsSingleThreadExecutionRequests false})

(defn compute-capabilities
  "Compute DAP capabilities based on backend and registered ports."
  [port-registry backend-type]
  (let [ports (when port-registry (core/list-ports port-registry))
        port-ids (set (map :id ports))]
    (cond-> default-capabilities
      ;; FlowStorm supports time-travel
      (= backend-type :flowstorm)
      (assoc :supportsStepBack true
             :supportsRestartFrame true)

      ;; CIDER/nREPL supports breakpoints
      (#{:nrepl :jdi} backend-type)
      (assoc :supportsFunctionBreakpoints true
             :supportsConditionalBreakpoints true)

      ;; JDI supports more features
      (= backend-type :jdi)
      (assoc :supportsSetVariable true
             :supportsExceptionOptions true
             :supportsExceptionInfoRequest true
             :supportsLoadedSourcesRequest true)

      ;; Enable completions if port exists
      (contains? port-ids :completions)
      (assoc :supportsCompletionsRequest true))))

;; ============================================================================
;; DAP Request Handlers
;; ============================================================================

(defmulti handle-request
  "Handle a DAP request. Dispatch on command name."
  (fn [command _args _context] command))

;; --- Lifecycle ---

(defmethod handle-request "initialize"
  [_ args context]
  (let [{:keys [adapter-state backend-type port-registry server-info]} context]
    (set-initialized! adapter-state)
    (emit-event! :dap/initialized {:client-id (:clientID args)
                                   :adapter-id (:adapterID args)})
    {:capabilities (compute-capabilities port-registry backend-type)
     :supportsRunInTerminalRequest false}))

(defmethod handle-request "launch"
  [_ args context]
  (let [{:keys [adapter-state transport backend-type]} context]
    (set-launched! adapter-state)
    (emit-event! :dap/launched {:program (:program args)
                                :args (:args args)})
    ;; Send initialized event after launch
    (when transport
      (core/transport-send transport
        (make-event (next-seq! adapter-state) "initialized" {})))
    {}))

(defmethod handle-request "attach"
  [_ args context]
  (let [{:keys [adapter-state transport]} context]
    (set-attached! adapter-state)
    (emit-event! :dap/attached {:host (:host args)
                                :port (:port args)})
    ;; Send initialized event after attach
    (when transport
      (core/transport-send transport
        (make-event (next-seq! adapter-state) "initialized" {})))
    {}))

(defmethod handle-request "configurationDone"
  [_ _args context]
  (let [{:keys [adapter-state]} context]
    (set-configured! adapter-state)
    {}))

(defmethod handle-request "disconnect"
  [_ args context]
  (let [{:keys [adapter-state transport]} context
        terminate? (:terminateDebuggee args false)]
    ;; Send terminated event
    (when transport
      (core/transport-send transport
        (make-event (next-seq! adapter-state) "terminated" {})))
    (reset-state! adapter-state)
    {}))

(defmethod handle-request "terminate"
  [_ _args context]
  (let [{:keys [adapter-state transport]} context]
    (when transport
      (core/transport-send transport
        (make-event (next-seq! adapter-state) "terminated" {})))
    {}))

(defmethod handle-request "restart"
  [_ args context]
  (let [{:keys [adapter-state transport]} context]
    ;; Reset state and re-initialize
    (reset-state! adapter-state)
    {:success false :message "Restart not supported in this mode"}))

(defmethod handle-request "cancel"
  [_ args context]
  (let [{:keys [requestId progressId]} args]
    ;; Cancel is a no-op in basic implementation
    {}))

;; --- Breakpoints (stub for REPL mode) ---

(defmethod handle-request "setBreakpoints"
  [_ args context]
  (let [{:keys [adapter-state backend-type]} context
        {:keys [source breakpoints]} args
        path (or (:path source) (:name source))]
    ;; Store breakpoints in state
    (swap! adapter-state assoc-in [:breakpoints path] breakpoints)
    (emit-event! :dap/breakpoint-set {:path path
                                      :count (count breakpoints)})
    ;; In REPL mode, breakpoints are not verified
    {:breakpoints (mapv (fn [bp]
                          (cond-> {:id (hash [path (:line bp)])
                                   :verified (boolean (#{:nrepl :jdi :flowstorm} backend-type))
                                   :line (:line bp)}
                            (:column bp) (assoc :column (:column bp))))
                        breakpoints)}))

(defmethod handle-request "setFunctionBreakpoints"
  [_ args context]
  (let [{:keys [adapter-state]} context
        {:keys [breakpoints]} args]
    (swap! adapter-state assoc :function-breakpoints breakpoints)
    {:breakpoints (mapv (fn [bp]
                          {:id (hash (:name bp))
                           :verified false
                           :message "Function breakpoints not supported in REPL mode"})
                        breakpoints)}))

(defmethod handle-request "setExceptionBreakpoints"
  [_ args context]
  (let [{:keys [adapter-state]} context
        {:keys [filters]} args]
    (swap! adapter-state assoc :exception-breakpoints (set filters))
    {:breakpoints []}))

(defmethod handle-request "setDataBreakpoints"
  [_ args context]
  (let [{:keys [adapter-state backend-type]} context
        {:keys [breakpoints]} args]
    (swap! adapter-state assoc :data-breakpoints breakpoints)
    {:breakpoints (mapv (fn [bp]
                          {:id (hash (:dataId bp))
                           :verified (= backend-type :jdi)
                           :message (when-not (= backend-type :jdi)
                                      "Data breakpoints not supported")})
                        breakpoints)}))

(defmethod handle-request "setInstructionBreakpoints"
  [_ args context]
  (let [{:keys [adapter-state backend-type]} context
        {:keys [breakpoints]} args]
    (swap! adapter-state assoc :instruction-breakpoints breakpoints)
    {:breakpoints (mapv (fn [bp]
                          {:id (hash (:instructionReference bp))
                           :verified (= backend-type :jdi)
                           :message (when-not (= backend-type :jdi)
                                      "Instruction breakpoints not supported")})
                        breakpoints)}))

(defmethod handle-request "breakpointLocations"
  [_ args context]
  (let [{:keys [source line column endLine endColumn]} args]
    ;; Return the line itself as the only valid location in REPL mode
    {:breakpoints [{:line line}]}))

(defmethod handle-request "dataBreakpointInfo"
  [_ args context]
  (let [{:keys [variablesReference name frameId]} args
        {:keys [backend-type]} context]
    (if (= backend-type :jdi)
      {:dataId (str variablesReference ":" name)
       :description (str "Watch: " name)
       :accessTypes ["read" "write" "readWrite"]
       :canPersist false}
      {:dataId nil
       :description "Data breakpoints not supported in this mode"})))

;; --- Execution Control (minimal for REPL mode) ---

(defmethod handle-request "continue"
  [_ _args context]
  (let [{:keys [adapter-state]} context]
    (clear-transient-state! adapter-state)
    (emit-event! :dap/continued {})
    {:allThreadsContinued true}))

(defmethod handle-request "next"
  [_ _args _context]
  ;; Step over - not supported in REPL mode
  {:success false :message "Stepping not supported in REPL mode"})

(defmethod handle-request "stepIn"
  [_ _args _context]
  {:success false :message "Stepping not supported in REPL mode"})

(defmethod handle-request "stepOut"
  [_ _args _context]
  {:success false :message "Stepping not supported in REPL mode"})

(defmethod handle-request "stepBack"
  [_ _args context]
  (let [{:keys [backend-type]} context]
    (if (= backend-type :flowstorm)
      ;; FlowStorm backend would handle this
      {:success false :message "FlowStorm backend not connected"}
      {:success false :message "Step back not supported"})))

(defmethod handle-request "pause"
  [_ _args _context]
  {:success false :message "Pause not supported in REPL mode"})

(defmethod handle-request "reverseContinue"
  [_ _args context]
  (let [{:keys [backend-type]} context]
    (if (= backend-type :flowstorm)
      {:success false :message "FlowStorm backend not connected"}
      {:success false :message "Reverse continue not supported"})))

(defmethod handle-request "restartFrame"
  [_ args context]
  (let [{:keys [backend-type]} context
        {:keys [frameId]} args]
    (if (= backend-type :flowstorm)
      {:success false :message "FlowStorm backend not connected"}
      {:success false :message "Restart frame not supported in this mode"})))

(defmethod handle-request "goto"
  [_ args context]
  (let [{:keys [threadId targetId]} args]
    {:success false :message "Goto not supported in REPL mode"}))

(defmethod handle-request "gotoTargets"
  [_ args context]
  (let [{:keys [source line column]} args]
    {:targets []}))

(defmethod handle-request "stepInTargets"
  [_ args context]
  (let [{:keys [frameId]} args]
    {:targets []}))

;; --- Threads & Stack ---

(defmethod handle-request "threads"
  [_ _args _context]
  ;; Return single main thread for REPL mode
  {:threads [{:id 1 :name "main"}]})

(defmethod handle-request "stackTrace"
  [_ args context]
  (let [{:keys [adapter-state backend-type]} context]
    (case backend-type
      :repl
      ;; No stack trace in REPL mode
      {:stackFrames []
       :totalFrames 0}

      ;; Other backends would provide real stack traces
      {:stackFrames []
       :totalFrames 0})))

(defmethod handle-request "scopes"
  [_ args context]
  (let [{:keys [adapter-state]} context
        frame-id (:frameId args)]
    ;; For REPL mode, return a globals scope for variable inspection
    {:scopes [{:name "REPL"
               :presentationHint "globals"
               :variablesReference (create-var-ref adapter-state :repl-scope nil)
               :expensive false}]}))

(defmethod handle-request "variables"
  [_ args context]
  (let [{:keys [adapter-state port-registry]} context
        var-ref (:variablesReference args)]
    (if-let [ref-data (get-var-ref adapter-state var-ref)]
      (case (:type ref-data)
        :value
        {:variables (vec (value->variables adapter-state (:data ref-data)))}

        :repl-scope
        ;; For REPL scope, we could list ns publics
        {:variables []}

        :locals
        {:variables []}

        {:variables []})
      {:variables []})))

;; --- Evaluation (The core REPL functionality!) ---

(defmethod handle-request "evaluate"
  [_ args context]
  (let [{:keys [adapter-state port-registry backend-opts]} context
        {:keys [expression context frameId]} args
        eval-context (keyword (or context "repl"))]
    (emit-event! :dap/evaluate {:expression expression
                                :context eval-context})
    (try
      ;; Try to use evaluate port if registered
      (if-let [eval-port (and port-registry (core/get-port port-registry :evaluate))]
        (let [result (core/port-execute eval-port
                       {:params {:code expression
                                 :context eval-context
                                 :frame-id frameId}})]
          (if-let [error (:error result)]
            {:result (str "Error: " (:message error))
             :variablesReference 0}
            {:result (truncate-str (pr-str (:result result)) 10000)
             :type (type-name (:result result))
             :variablesReference (create-var-ref-for-value adapter-state (:result result))}))

        ;; Fallback to direct eval if no port (dangerous in production!)
        (let [eval-fn (or (:eval-fn backend-opts)
                          #?(:clj (fn [code] (eval (read-string code)))
                             :cljs (fn [code] {:error "No eval function provided"})))
              result (eval-fn expression)]
          {:result (truncate-str (pr-str result) 10000)
           :type (type-name result)
           :variablesReference (create-var-ref-for-value adapter-state result)}))

      (catch #?(:clj Exception :cljs js/Error) e
        {:result (str "Error: " #?(:clj (.getMessage e) :cljs (.-message e)))
         :variablesReference 0}))))

;; --- Completions ---

(defmethod handle-request "completions"
  [_ args context]
  (let [{:keys [port-registry]} context
        {:keys [text column line]} args]
    (if-let [completions-port (and port-registry (core/get-port port-registry :completions))]
      (let [result (core/port-execute completions-port
                     {:params {:prefix text
                               :column column
                               :line line}})]
        {:targets (mapv (fn [c]
                          (if (string? c)
                            {:label c :type "function"}
                            c))
                        (or (:result result) []))})
      {:targets []})))

;; --- Source ---

(defmethod handle-request "source"
  [_ args context]
  ;; Return empty for now
  {:content ""})

(defmethod handle-request "loadedSources"
  [_ _args _context]
  {:sources []})

;; --- Modules ---

(defmethod handle-request "modules"
  [_ _args _context]
  {:modules []
   :totalModules 0})

;; --- Variable Modification ---

(defmethod handle-request "setVariable"
  [_ args context]
  (let [{:keys [adapter-state backend-type port-registry]} context
        {:keys [variablesReference name value format]} args]
    (if (= backend-type :jdi)
      ;; JDI backend would handle this
      {:success false :message "JDI backend not connected"}
      {:success false :message "Set variable not supported in this mode"})))

(defmethod handle-request "setExpression"
  [_ args context]
  (let [{:keys [expression value frameId format]} args
        {:keys [backend-type]} context]
    {:success false :message "Set expression not supported in this mode"}))

;; --- Exception Info ---

(defmethod handle-request "exceptionInfo"
  [_ args context]
  (let [{:keys [threadId]} args
        {:keys [backend-type]} context]
    (if (= backend-type :jdi)
      {:exceptionId "unknown"
       :description "Exception info requires JDI backend"
       :breakMode "unhandled"}
      {:exceptionId "unknown"
       :description "Exception info not available in REPL mode"
       :breakMode "never"})))

;; --- Thread Management ---

(defmethod handle-request "terminateThreads"
  [_ args context]
  (let [{:keys [threadIds]} args]
    {:success false :message "Thread termination not supported"}))

;; --- Memory Operations ---

(defmethod handle-request "readMemory"
  [_ args context]
  (let [{:keys [memoryReference offset count]} args
        {:keys [backend-type]} context]
    (if (= backend-type :jdi)
      {:address memoryReference
       :data nil
       :unreadableBytes count}
      {:success false :message "Memory read not supported in this mode"})))

(defmethod handle-request "writeMemory"
  [_ args context]
  (let [{:keys [memoryReference offset allowPartial data]} args]
    {:success false :message "Memory write not supported in this mode"}))

;; --- Disassembly ---

(defmethod handle-request "disassemble"
  [_ args context]
  (let [{:keys [memoryReference offset instructionOffset instructionCount resolveSymbols]} args
        {:keys [backend-type]} context]
    (if (= backend-type :jdi)
      {:instructions []}
      {:success false :message "Disassembly not supported in this mode"})))

;; --- Default handler ---

(defmethod handle-request :default
  [command _args _context]
  {:success false
   :message (str "Unknown command: " command)})

;; ============================================================================
;; DAP Protocol Adapter Implementation
;; ============================================================================

(defrecord DapAdapter [server-info backend-type backend-opts adapter-state]
  core/ProtocolAdapter

  (protocol-id [_]
    :dap)

  (protocol-version [_]
    dap-version)

  (protocol-capabilities [_ port-registry]
    (compute-capabilities port-registry backend-type))

  (protocol-dispatch [this method params context]
    (let [;; DAP uses 'command' not 'method', and 'arguments' not 'params'
          command (or method (:command params) (get params "command"))
          args (or (:arguments params) (get params "arguments") params)
          ;; Build enriched context
          enriched-context (assoc context
                             :adapter-state adapter-state
                             :backend-type backend-type
                             :backend-opts backend-opts
                             :server-info server-info)]
      (try
        (let [result (handle-request command args enriched-context)]
          (if (:success result)
            result
            {:result result}))
        (catch #?(:clj Exception :cljs js/Error) e
          (emit-event! :dap/error {:command command
                                   :error #?(:clj (.getMessage e)
                                            :cljs (.-message e))})
          {:error {:code -32603
                   :message (str "Internal error: "
                                 #?(:clj (.getMessage e)
                                    :cljs (.-message e)))}})))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn create-dap-adapter
  "Create a DAP protocol adapter.

  Options:
    :server-info - Map with :name and :version (required for clients)
    :backend - Backend type keyword:
      - :repl (default) - REPL-as-debugger, evaluate/variables only
      - :nrepl - nREPL/CIDER bridge (requires cider-nrepl)
      - :flowstorm - FlowStorm time-travel debugging
      - :jdi - Java Debug Interface (JVM only)
      - :proxy - Multi-backend language proxy
    :backend-opts - Backend-specific options:
      - :repl - {:eval-fn (fn [code] result)}
      - :nrepl - {:host \"localhost\" :port 7888}
      - :flowstorm - {:host \"localhost\" :port 7722}
      - :jdi - {:host \"localhost\" :port 5005}
      - :proxy - {:backends {...}}

  Examples:
    ;; Simple REPL mode (default)
    (def adapter (create-dap-adapter))

    ;; With custom evaluator
    (def adapter (create-dap-adapter
                   {:backend :repl
                    :backend-opts {:eval-fn my-safe-eval}}))

    ;; FlowStorm time-travel
    (def adapter (create-dap-adapter
                   {:backend :flowstorm
                    :backend-opts {:host \"localhost\" :port 7722}}))

  Returns DapAdapter instance implementing ProtocolAdapter protocol."
  ([] (create-dap-adapter nil))
  ([opts]
   (let [server-info (or (:server-info opts)
                        {:name "defport-dap-server" :version "0.1.0"})
         backend-type (or (:backend opts) :repl)
         backend-opts (or (:backend-opts opts) {})
         adapter-state (create-state)]
     (->DapAdapter server-info backend-type backend-opts adapter-state))))

(defn get-adapter-state
  "Get the current adapter state (for debugging/testing)."
  [adapter]
  @(:adapter-state adapter))

(defn send-stopped-event
  "Send a stopped event to the client.

  Useful when implementing backends that detect breakpoint hits.

  Args:
    adapter - DapAdapter instance
    transport - Transport to send on
    reason - Stop reason (\"breakpoint\", \"step\", \"exception\", etc.)
    opts - Optional map with:
      - :thread-id - Thread that stopped (default 1)
      - :all-threads-stopped - Whether all threads stopped
      - :description - Human-readable description
      - :text - Additional text

  Example:
    (send-stopped-event adapter transport \"breakpoint\"
      {:thread-id 1 :description \"Hit breakpoint at line 42\"})"
  [adapter transport reason & [opts]]
  (let [state (:adapter-state adapter)
        thread-id (or (:thread-id opts) 1)
        event-body (cond-> {:reason reason
                            :threadId thread-id
                            :allThreadsStopped (or (:all-threads-stopped opts) true)}
                     (:description opts) (assoc :description (:description opts))
                     (:text opts) (assoc :text (:text opts)))]
    (swap! state update :stopped-threads conj thread-id)
    (emit-event! :dap/stopped {:reason reason :thread-id thread-id})
    (core/transport-send transport
      (make-event (next-seq! state) "stopped" event-body))))

(defn send-output-event
  "Send an output event to the client (debug console).

  Args:
    adapter - DapAdapter instance
    transport - Transport to send on
    category - Output category (\"console\", \"stdout\", \"stderr\", \"telemetry\")
    output - Text to output

  Example:
    (send-output-event adapter transport \"console\" \"Hello from debugger!\")"
  [adapter transport category output]
  (let [state (:adapter-state adapter)]
    (core/transport-send transport
      (make-event (next-seq! state) "output"
        {:category category
         :output output}))))

(defn send-breakpoint-event
  "Send a breakpoint event to notify client of breakpoint status change.

  Args:
    adapter - DapAdapter instance
    transport - Transport to send on
    reason - Reason (\"changed\", \"new\", \"removed\")
    breakpoint - Breakpoint map with :id, :verified, :line, etc."
  [adapter transport reason breakpoint]
  (let [state (:adapter-state adapter)]
    (core/transport-send transport
      (make-event (next-seq! state) "breakpoint"
        {:reason reason
         :breakpoint breakpoint}))))

;; ============================================================================
;; DAP Client Implementation
;; ============================================================================
;; For connecting to external DAP debug adapters.

(defprotocol DapClient
  "Client for communicating with external DAP debug adapters."
  (client-start [this]
    "Start the client connection.")
  (client-request [this command arguments]
    "Send request, block for response.")
  (client-request-async [this command arguments callback]
    "Send request, invoke callback with response.")
  (client-stop [this]
    "Stop the client connection.")
  (client-alive? [this]
    "Check if client is connected."))

#?(:clj
   (defn- write-dap-message
     "Write a DAP message with Content-Length header."
     [^java.io.BufferedWriter writer message]
     (let [json-str (json/generate-string message)
           bytes (.getBytes json-str "UTF-8")
           header (str "Content-Length: " (count bytes) "\r\n\r\n")]
       (.write writer header)
       (.write writer json-str)
       (.flush writer))))

#?(:clj
   (defn- read-dap-message
     "Read a DAP message with Content-Length header."
     [^java.io.BufferedReader reader]
     (try
       (loop [headers {}]
         (let [line (.readLine reader)]
           (cond
             (nil? line) nil
             (= line "") ; End of headers
             (when-let [content-length (get headers "Content-Length")]
               (let [len (Integer/parseInt content-length)
                     buffer (char-array len)]
                 (.read reader buffer 0 len)
                 (json/parse-string (String. buffer) true)))
             :else
             (let [[k v] (clojure.string/split line #": " 2)]
               (recur (assoc headers k v))))))
       (catch Exception e
         nil))))

#?(:clj
   (defrecord StdioDapClient [process
                              ^java.io.BufferedReader reader
                              ^java.io.BufferedWriter writer
                              seq-counter*
                              pending*
                              alive?*
                              reader-thread
                              event-handlers*]
     DapClient
     (client-start [this]
       this) ; Already started in constructor

     (client-request [this command arguments]
       (when @alive?*
         (let [seq-num (swap! seq-counter* inc)
               msg {:seq seq-num
                    :type "request"
                    :command command
                    :arguments arguments}
               response-promise (promise)]
           (swap! pending* assoc seq-num response-promise)
           (tap> {:event :dap/client-request :seq seq-num :command command})
           (write-dap-message writer msg)
           (let [result (deref response-promise 30000 ::timeout)]
             (swap! pending* dissoc seq-num)
             (if (= result ::timeout)
               {:success false :message "Request timed out"}
               result)))))

     (client-request-async [this command arguments callback]
       (when @alive?*
         (let [seq-num (swap! seq-counter* inc)
               msg {:seq seq-num
                    :type "request"
                    :command command
                    :arguments arguments}]
           (swap! pending* assoc seq-num callback)
           (tap> {:event :dap/client-request-async :seq seq-num :command command})
           (write-dap-message writer msg))))

     (client-stop [this]
       (when (compare-and-set! alive?* true false)
         (try
           (client-request this "disconnect" {:terminateDebuggee true})
           (catch Exception _))
         (.destroy ^Process process)
         ;; Complete pending with errors
         (doseq [[seq-num p] @pending*]
           (when (instance? clojure.lang.IPending p)
             (deliver p {:success false :message "Client stopped"})))))

     (client-alive? [_]
       @alive?*)))

#?(:clj
   (defn- start-dap-client-reader-thread
     "Start background thread to read responses from DAP adapter."
     [^java.io.BufferedReader reader pending* event-handlers* alive?*]
     (doto (Thread.
            (fn []
              (try
                (while @alive?*
                  (when-let [msg (read-dap-message reader)]
                    (tap> {:event :dap/client-received :message msg})
                    (case (:type msg)
                      "response"
                      (when-let [handler (get @pending* (:request_seq msg))]
                        (if (fn? handler)
                          (handler msg)
                          (deliver handler msg)))

                      "event"
                      (let [event-name (keyword (:event msg))]
                        (tap> {:event :dap/adapter-event
                               :dap-event event-name
                               :body (:body msg)})
                        (when-let [handler (get @event-handlers* event-name)]
                          (try (handler msg)
                               (catch Exception e
                                 (tap> {:event :dap/event-handler-error
                                        :error (.getMessage e)})))))

                      ;; Ignore other message types
                      nil)))
                (catch Exception e
                  (when @alive?*
                    (tap> {:event :dap/client-reader-error
                           :error (.getMessage e)}))))))
       (.setDaemon true)
       (.setName "defport-dap-client-reader")
       (.start))))

#?(:clj
   (defn create-client
     "Create a DAP client that connects to an external debug adapter via stdio.

      Options:
        :command - Command vector [\"node\" \"debug-adapter.js\"]
        :env     - Environment variables map (optional)
        :dir     - Working directory (optional)

      Example:
        (def client (create-client {:command [\"node\" \"./debugger.js\"]}))
        (initialize! client)
        (launch! client {:program \"app.js\"})
        (set-breakpoints! client \"src/main.js\" [10 20 30])
        (continue! client 1)
        (disconnect! client)"
     [{:keys [command env dir]}]
     (let [pb (ProcessBuilder. ^java.util.List (vec command))
           _ (when dir (.directory pb (java.io.File. ^String dir)))
           _ (when env (.putAll (.environment pb) ^java.util.Map env))
           process (.start pb)
           reader (java.io.BufferedReader.
                   (java.io.InputStreamReader. (.getInputStream process) "UTF-8"))
           writer (java.io.BufferedWriter.
                   (java.io.OutputStreamWriter. (.getOutputStream process) "UTF-8"))
           seq-counter* (atom 0)
           pending* (atom {})
           event-handlers* (atom {})
           alive?* (atom true)
           reader-thread (start-dap-client-reader-thread reader pending* event-handlers* alive?*)]
       (->StdioDapClient process reader writer seq-counter* pending*
                         alive?* reader-thread event-handlers*))))

#?(:clj
   (defn on-event!
     "Register an event handler for DAP events.

      Events: :initialized, :stopped, :continued, :exited, :terminated,
              :thread, :output, :breakpoint, :module, :loadedSource, :process

      Example:
        (on-event! client :stopped
          (fn [msg]
            (println \"Stopped:\" (get-in msg [:body :reason]))))
        (on-event! client :output
          (fn [msg]
            (println (get-in msg [:body :output]))))"
     [client event-key handler]
     (swap! (:event-handlers* client) assoc event-key handler)))

;; ============================================================================
;; Convenience Client API
;; ============================================================================
;; High-level functions for common DAP operations.

#?(:clj
   (defn initialize!
     "Initialize DAP connection.
      Returns capabilities from the adapter."
     [client & {:keys [client-id client-name adapter-id lines-start-at-1 columns-start-at-1]
                :or {client-id "defport"
                     client-name "Defport DAP Client"
                     adapter-id "unknown"
                     lines-start-at-1 true
                     columns-start-at-1 true}}]
     (let [response (client-request client "initialize"
                      {:clientID client-id
                       :clientName client-name
                       :adapterID adapter-id
                       :linesStartAt1 lines-start-at-1
                       :columnsStartAt1 columns-start-at-1
                       :pathFormat "path"
                       :supportsVariableType true
                       :supportsVariablePaging false
                       :supportsRunInTerminalRequest false
                       :supportsMemoryReferences false
                       :supportsProgressReporting false})]
       (when (:success response)
         (:body response)))))

#?(:clj
   (defn launch!
     "Launch a debug target.
      Args is adapter-specific (program, args, cwd, env, etc.)."
     [client args]
     (let [response (client-request client "launch" args)]
       (:success response))))

#?(:clj
   (defn attach!
     "Attach to a running debug target.
      Args is adapter-specific (host, port, processId, etc.)."
     [client args]
     (let [response (client-request client "attach" args)]
       (:success response))))

#?(:clj
   (defn configuration-done!
     "Signal that configuration is complete."
     [client]
     (let [response (client-request client "configurationDone" {})]
       (:success response))))

#?(:clj
   (defn disconnect!
     "Disconnect from the debug adapter."
     [client & {:keys [terminate-debuggee restart]
                :or {terminate-debuggee false restart false}}]
     (let [response (client-request client "disconnect"
                      {:terminateDebuggee terminate-debuggee
                       :restart restart})]
       (:success response))))

#?(:clj
   (defn set-breakpoints!
     "Set breakpoints in a source file.
      Returns vector of Breakpoint objects."
     [client source-path lines]
     (let [response (client-request client "setBreakpoints"
                      {:source {:path source-path}
                       :breakpoints (mapv (fn [line]
                                            (if (map? line) line {:line line}))
                                          lines)})]
       (get-in response [:body :breakpoints]))))

#?(:clj
   (defn set-function-breakpoints!
     "Set function breakpoints.
      Names is a vector of function names or maps with :name, :condition, :hitCondition."
     [client names]
     (let [response (client-request client "setFunctionBreakpoints"
                      {:breakpoints (mapv (fn [n]
                                            (if (map? n) n {:name n}))
                                          names)})]
       (get-in response [:body :breakpoints]))))

#?(:clj
   (defn set-exception-breakpoints!
     "Set exception breakpoints.
      Filters is a vector of exception filter IDs (adapter-specific)."
     [client filters]
     (let [response (client-request client "setExceptionBreakpoints"
                      {:filters filters})]
       (:success response))))

#?(:clj
   (defn continue!
     "Continue execution.
      Returns true if all threads continued."
     [client thread-id]
     (let [response (client-request client "continue"
                      {:threadId thread-id})]
       (get-in response [:body :allThreadsContinued]))))

#?(:clj
   (defn step-over!
     "Step over to next statement."
     [client thread-id]
     (let [response (client-request client "next"
                      {:threadId thread-id})]
       (:success response))))

#?(:clj
   (defn step-in!
     "Step into function."
     [client thread-id]
     (let [response (client-request client "stepIn"
                      {:threadId thread-id})]
       (:success response))))

#?(:clj
   (defn step-out!
     "Step out of current function."
     [client thread-id]
     (let [response (client-request client "stepOut"
                      {:threadId thread-id})]
       (:success response))))

#?(:clj
   (defn pause!
     "Pause execution."
     [client thread-id]
     (let [response (client-request client "pause"
                      {:threadId thread-id})]
       (:success response))))

#?(:clj
   (defn threads
     "Get all threads."
     [client]
     (let [response (client-request client "threads" {})]
       (get-in response [:body :threads]))))

#?(:clj
   (defn stack-trace
     "Get stack trace for a thread."
     [client thread-id & {:keys [start-frame levels]
                          :or {start-frame 0 levels 20}}]
     (let [response (client-request client "stackTrace"
                      {:threadId thread-id
                       :startFrame start-frame
                       :levels levels})]
       (get-in response [:body :stackFrames]))))

#?(:clj
   (defn scopes
     "Get scopes for a stack frame."
     [client frame-id]
     (let [response (client-request client "scopes"
                      {:frameId frame-id})]
       (get-in response [:body :scopes]))))

#?(:clj
   (defn variables
     "Get variables for a scope/variable reference."
     [client variables-reference & {:keys [filter start count]}]
     (let [response (client-request client "variables"
                      (cond-> {:variablesReference variables-reference}
                        filter (assoc :filter filter)
                        start (assoc :start start)
                        count (assoc :count count)))]
       (get-in response [:body :variables]))))

#?(:clj
   (defn evaluate
     "Evaluate expression.
      Context: :watch, :repl, :hover, :clipboard, :variables"
     [client expression & {:keys [frame-id context]
                           :or {context "repl"}}]
     (let [response (client-request client "evaluate"
                      (cond-> {:expression expression
                               :context context}
                        frame-id (assoc :frameId frame-id)))]
       (when (:success response)
         (:body response)))))

#?(:clj
   (defn completions
     "Get completions for text."
     [client text column & {:keys [frame-id line]}]
     (let [response (client-request client "completions"
                      (cond-> {:text text :column column}
                        frame-id (assoc :frameId frame-id)
                        line (assoc :line line)))]
       (get-in response [:body :targets]))))