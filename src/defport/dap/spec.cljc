(ns defport.dap.spec
  "Single source of truth for DAP command and event routing.

   DAP differs from LSP in shape:
   - DAP messages have a `type` discriminator (\"request\" | \"response\"
     | \"event\") instead of LSP's method/notification split.
   - Requests carry a `command` string (e.g. \"continue\", \"stackTrace\");
     events carry an `event` string (e.g. \"stopped\", \"output\").
   - DAP capabilities live inside `InitializeResponse.body` as a flat
     map of boolean flags (e.g. `supportsStepBack: true`), one per
     supported feature. The capability key is implied by the command;
     see `:capability` in each entry below.

   Same plain-data substrate as defport.lsp.spec — every command is
   one row, accessors are pure functions, no schema lib in defport.

   Surface from @vscode/debugprotocol 1.65:
   - 45 commands (43 client→server, 2 server→client)
   - 17 events (all server→client notifications)"
  (:refer-clojure :exclude [methods]))

;; ============================================================================
;; Sugar param-extraction shapes
;; ============================================================================
;;
;; DAP arguments are usually flat maps already (no LSP-style
;; nested textDocument/position structure), so the sugar surface is
;; smaller. Most commands use :raw passthrough; a handful pull a
;; specific field out for ergonomics.

(def sugar-extractors
  "Map of sugar-key → fn that takes raw DAP arguments and returns a
   flat map the defcommand macro destructures into the user's
   named parameters."
  {:raw       (fn [args] args)
   :thread    (fn [args] {:thread-id (:threadId args)})
   :frame     (fn [args] {:frame-id (:frameId args)})
   :var-ref   (fn [args] {:variables-reference (:variablesReference args)})
   :source    (fn [args] {:source (:source args)
                          :source-reference (:sourceReference args)})})

;; ============================================================================
;; The command registry
;; ============================================================================
;;
;; Direction: :client->server is the common case (client asks server
;; to do something). :server->client commands are reverse requests
;; the server initiates against the client (runInTerminal, startDebugging).
;; :both is reserved for future use; nothing in DAP 1.65 is bidirectional
;; in that sense.

(def methods
  "Single source of truth for every DAP command defport routes."
  {;; -------------------------------------------------------------------------
   ;; Lifecycle
   ;; -------------------------------------------------------------------------
   :initialize
   {:command    "initialize"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {})
    :doc        "Initial handshake. Client introduces itself, server replies with capabilities."}

   :launch
   {:command    "launch"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {})
    :doc        "Launch the debuggee in run mode."}

   :attach
   {:command    "attach"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {})
    :doc        "Attach to an already-running debuggee."}

   :restart
   {:command    "restart"
    :kind       :request
    :direction  :client->server
    :capability :supportsRestartRequest
    :sugar      :raw
    :default    (constantly {})
    :doc        "Restart the debug session."}

   :disconnect
   {:command    "disconnect"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {})
    :doc        "Disconnect from the debuggee."}

   :terminate
   {:command    "terminate"
    :kind       :request
    :direction  :client->server
    :capability :supportsTerminateRequest
    :sugar      :raw
    :default    (constantly {})
    :doc        "Gracefully terminate the debuggee."}

   :terminate-threads
   {:command    "terminateThreads"
    :kind       :request
    :direction  :client->server
    :capability :supportsTerminateThreadsRequest
    :sugar      :raw
    :default    (constantly {})
    :doc        "Terminate one or more threads."}

   :configuration-done
   {:command    "configurationDone"
    :kind       :request
    :direction  :client->server
    :capability :supportsConfigurationDoneRequest
    :sugar      :raw
    :default    (constantly {})
    :doc        "Client tells the adapter that initial configuration is finished."}

   :cancel
   {:command    "cancel"
    :kind       :request
    :direction  :client->server
    :capability :supportsCancelRequest
    :sugar      :raw
    :default    (constantly {})
    :doc        "Cancel an in-flight request or progress operation."}

   ;; -------------------------------------------------------------------------
   ;; Breakpoints
   ;; -------------------------------------------------------------------------
   :set-breakpoints
   {:command    "setBreakpoints"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {:breakpoints []})
    :doc        "Set source breakpoints. Replaces all breakpoints in the source."}

   :set-function-breakpoints
   {:command    "setFunctionBreakpoints"
    :kind       :request
    :direction  :client->server
    :capability :supportsFunctionBreakpoints
    :sugar      :raw
    :default    (constantly {:breakpoints []})
    :doc        "Set function breakpoints by symbol name."}

   :set-exception-breakpoints
   {:command    "setExceptionBreakpoints"
    :kind       :request
    :direction  :client->server
    :capability :exceptionBreakpointFilters
    :sugar      :raw
    :default    (constantly {})
    :doc        "Configure which exceptions break."}

   :set-data-breakpoints
   {:command    "setDataBreakpoints"
    :kind       :request
    :direction  :client->server
    :capability :supportsDataBreakpoints
    :sugar      :raw
    :default    (constantly {:breakpoints []})
    :doc        "Set data (watch) breakpoints."}

   :data-breakpoint-info
   {:command    "dataBreakpointInfo"
    :kind       :request
    :direction  :client->server
    :capability :supportsDataBreakpoints
    :sugar      :raw
    :default    (constantly {:dataId nil :description "Data breakpoints not supported"})
    :doc        "Resolve a variable to a dataId for use in setDataBreakpoints."}

   :set-instruction-breakpoints
   {:command    "setInstructionBreakpoints"
    :kind       :request
    :direction  :client->server
    :capability :supportsInstructionBreakpoints
    :sugar      :raw
    :default    (constantly {:breakpoints []})
    :doc        "Set breakpoints at specific machine-code addresses."}

   :breakpoint-locations
   {:command    "breakpointLocations"
    :kind       :request
    :direction  :client->server
    :capability :supportsBreakpointLocationsRequest
    :sugar      :raw
    :default    (constantly {:breakpoints []})
    :doc        "Compute valid breakpoint locations within a source range."}

   ;; -------------------------------------------------------------------------
   ;; Stepping
   ;; -------------------------------------------------------------------------
   :continue
   {:command    "continue"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :thread
    :default    (constantly {:allThreadsContinued true})
    :doc        "Resume execution after a stop."}

   :next
   {:command    "next"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :thread
    :default    (constantly {})
    :doc        "Step over the next line."}

   :step-in
   {:command    "stepIn"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :thread
    :default    (constantly {})
    :doc        "Step into a call."}

   :step-out
   {:command    "stepOut"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :thread
    :default    (constantly {})
    :doc        "Step out of the current function."}

   :step-back
   {:command    "stepBack"
    :kind       :request
    :direction  :client->server
    :capability :supportsStepBack
    :sugar      :thread
    :default    (constantly {})
    :doc        "Step backward to the previous statement."}

   :reverse-continue
   {:command    "reverseContinue"
    :kind       :request
    :direction  :client->server
    :capability :supportsStepBack
    :sugar      :thread
    :default    (constantly {})
    :doc        "Reverse-execute until the previous breakpoint."}

   :restart-frame
   {:command    "restartFrame"
    :kind       :request
    :direction  :client->server
    :capability :supportsRestartFrame
    :sugar      :frame
    :default    (constantly {})
    :doc        "Restart the given stack frame from its top."}

   :pause
   {:command    "pause"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :thread
    :default    (constantly {})
    :doc        "Pause execution of one or all threads."}

   :goto
   {:command    "goto"
    :kind       :request
    :direction  :client->server
    :capability :supportsGotoTargetsRequest
    :sugar      :raw
    :default    (constantly {})
    :doc        "Set the next instruction to execute to a specific target."}

   :goto-targets
   {:command    "gotoTargets"
    :kind       :request
    :direction  :client->server
    :capability :supportsGotoTargetsRequest
    :sugar      :raw
    :default    (constantly {:targets []})
    :doc        "Compute valid goto targets at a position."}

   :step-in-targets
   {:command    "stepInTargets"
    :kind       :request
    :direction  :client->server
    :capability :supportsStepInTargetsRequest
    :sugar      :frame
    :default    (constantly {:targets []})
    :doc        "List possible targets for a step-in at a frame."}

   ;; -------------------------------------------------------------------------
   ;; Stack / scope / variables
   ;; -------------------------------------------------------------------------
   :threads
   {:command    "threads"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {:threads []})
    :doc        "List all threads in the debuggee."}

   :stack-trace
   {:command    "stackTrace"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :thread
    :default    (constantly {:stackFrames [] :totalFrames 0})
    :doc        "Get the stack trace for a thread."}

   :scopes
   {:command    "scopes"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :frame
    :default    (constantly {:scopes []})
    :doc        "List variable scopes for a stack frame."}

   :variables
   {:command    "variables"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :var-ref
    :default    (constantly {:variables []})
    :doc        "List variables in a scope or container."}

   :set-variable
   {:command    "setVariable"
    :kind       :request
    :direction  :client->server
    :capability :supportsSetVariable
    :sugar      :raw
    :default    (constantly {})
    :doc        "Set a variable's value."}

   :set-expression
   {:command    "setExpression"
    :kind       :request
    :direction  :client->server
    :capability :supportsSetExpression
    :sugar      :raw
    :default    (constantly {})
    :doc        "Evaluate an l-value expression and assign to it."}

   :evaluate
   {:command    "evaluate"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :raw
    :default    (constantly {:result "" :variablesReference 0})
    :doc        "Evaluate an expression in a frame's context."}

   :completions
   {:command    "completions"
    :kind       :request
    :direction  :client->server
    :capability :supportsCompletionsRequest
    :sugar      :raw
    :default    (constantly {:targets []})
    :doc        "Provide completions for a partial expression in the debug console."}

   :exception-info
   {:command    "exceptionInfo"
    :kind       :request
    :direction  :client->server
    :capability :supportsExceptionInfoRequest
    :sugar      :thread
    :default    (constantly {:exceptionId "" :description "" :breakMode "always"})
    :doc        "Detailed information about the exception that just stopped a thread."}

   ;; -------------------------------------------------------------------------
   ;; Source / modules / memory
   ;; -------------------------------------------------------------------------
   :source
   {:command    "source"
    :kind       :request
    :direction  :client->server
    :capability nil
    :sugar      :source
    :default    (constantly {:content ""})
    :doc        "Load source for a sourceReference."}

   :loaded-sources
   {:command    "loadedSources"
    :kind       :request
    :direction  :client->server
    :capability :supportsLoadedSourcesRequest
    :sugar      :raw
    :default    (constantly {:sources []})
    :doc        "List all loaded source files."}

   :modules
   {:command    "modules"
    :kind       :request
    :direction  :client->server
    :capability :supportsModulesRequest
    :sugar      :raw
    :default    (constantly {:modules [] :totalModules 0})
    :doc        "List loaded modules (libraries, frameworks)."}

   :read-memory
   {:command    "readMemory"
    :kind       :request
    :direction  :client->server
    :capability :supportsReadMemoryRequest
    :sugar      :raw
    :default    (constantly {:address "0" :data ""})
    :doc        "Read raw bytes from memory."}

   :write-memory
   {:command    "writeMemory"
    :kind       :request
    :direction  :client->server
    :capability :supportsWriteMemoryRequest
    :sugar      :raw
    :default    (constantly {})
    :doc        "Write raw bytes to memory."}

   :disassemble
   {:command    "disassemble"
    :kind       :request
    :direction  :client->server
    :capability :supportsDisassembleRequest
    :sugar      :raw
    :default    (constantly {:instructions []})
    :doc        "Disassemble code at a memory address."}

   :locations
   {:command    "locations"
    :kind       :request
    :direction  :client->server
    :capability :supportsLocationsRequest
    :sugar      :raw
    :default    (constantly {})
    :doc        "Resolve a location reference to a source position."}

   ;; -------------------------------------------------------------------------
   ;; Server-initiated reverse requests (server → client)
   ;; -------------------------------------------------------------------------
   :run-in-terminal
   {:command    "runInTerminal"
    :kind       :request
    :direction  :server->client
    :capability nil  ;; Client-side capability: supportsRunInTerminalRequest
    :sugar      :raw
    :default    nil  ;; No server-side default — server initiates this
    :doc        "Adapter asks the client to spawn a process in a terminal."}

   :start-debugging
   {:command    "startDebugging"
    :kind       :request
    :direction  :server->client
    :capability nil  ;; Client-side: supportsStartDebuggingRequest
    :sugar      :raw
    :default    nil
    :doc        "Adapter asks the client to launch a child debug session."}})

;; ============================================================================
;; Events (always server → client notifications)
;; ============================================================================

(def events
  "Single source of truth for every DAP event defport emits.
   Events are always server-to-client notifications. Each entry
   declares the event name and the body shape the emit-* helper
   builds.

   Surface: 17 events from DAP 1.65."
  {:initialized
   {:event "initialized"
    :doc   "Adapter is ready to accept configuration requests."}
   :stopped
   {:event "stopped"
    :doc   "Execution stopped (breakpoint, step, exception, etc.)."}
   :continued
   {:event "continued"
    :doc   "Execution resumed."}
   :exited
   {:event "exited"
    :doc   "Debuggee process exited."}
   :terminated
   {:event "terminated"
    :doc   "Debug session is about to end."}
   :thread
   {:event "thread"
    :doc   "A thread started or exited."}
   :output
   {:event "output"
    :doc   "Output to one of the debug console categories."}
   :breakpoint
   {:event "breakpoint"
    :doc   "Breakpoint state changed (verified, removed, etc.)."}
   :module
   {:event "module"
    :doc   "A module was loaded, unloaded, or changed."}
   :loaded-source
   {:event "loadedSource"
    :doc   "A source was added, removed, or changed."}
   :process
   {:event "process"
    :doc   "Adapter started/attached to a debuggee process."}
   :capabilities
   {:event "capabilities"
    :doc   "Adapter wants to update its capabilities mid-session."}
   :progress-start
   {:event "progressStart"
    :doc   "A long-running operation started."}
   :progress-update
   {:event "progressUpdate"
    :doc   "A long-running operation reported progress."}
   :progress-end
   {:event "progressEnd"
    :doc   "A long-running operation ended."}
   :invalidated
   {:event "invalidated"
    :doc   "Cached state for one or more areas should be re-fetched."}
   :memory
   {:event "memory"
    :doc   "Memory at a region was written by the adapter."}})

;; ============================================================================
;; Lookups
;; ============================================================================

(defn method-for
  "Look up the spec entry for a command-name keyword. nil for unknown."
  [command-name]
  (get methods command-name))

(defn command-name-for
  "Inverse: given a DAP command string, return the command-name keyword."
  [command-string]
  (some (fn [[k v]] (when (= command-string (:command v)) k))
        methods))

(defn wire-command
  "Convenience: command-name → wire command string."
  [command-name]
  (:command (method-for command-name)))

(defn capability-key
  "The InitializeResponse.body capability key implied by a command,
   or nil for commands with no capability flag (always supported)."
  [command-name]
  (:capability (method-for command-name)))

(defn sugar-extractor
  "Resolve a command-name to its sugar param-extraction fn."
  [command-name]
  (let [k (or (:sugar (method-for command-name)) :raw)]
    (get sugar-extractors k)))

(defn default-response
  "Default-response value or fn for a command-name."
  [command-name]
  (:default (method-for command-name)))

(defn server-initiated?
  "True for commands the adapter initiates against the client
   (runInTerminal, startDebugging). The defport adapter ignores
   these in protocol-dispatch — they're sent, not received."
  [command-name]
  (= :server->client (:direction (method-for command-name))))

(defn all-command-names
  "Every command-name keyword in the registry. Order not guaranteed."
  []
  (keys methods))

(defn capability-keys-from-command-names
  "Given a collection of command-name keywords (e.g. the commands a
   consumer has actually registered), return the set of capability
   flags that should be set in InitializeResponse.body."
  [command-names]
  (into #{}
        (keep capability-key)
        command-names))

(defn validate-inbound
  "Run a command's :validate-in predicate against arguments if set."
  [command-name args]
  (when-let [validator (:validate-in (method-for command-name))]
    (let [result (validator args)]
      (when (and (some? result) (not (true? result)))
        (if (map? result) result {:error result})))))

(defn validate-outbound
  "Run a command's :validate-out predicate against a response if set."
  [command-name response]
  (when-let [validator (:validate-out (method-for command-name))]
    (let [result (validator response)]
      (when (and (some? result) (not (true? result)))
        (if (map? result) result {:error result})))))

;; ============================================================================
;; Event lookups
;; ============================================================================

(defn event-for
  "Look up the event spec for an event-name keyword."
  [event-name]
  (get events event-name))

(defn wire-event
  "Convenience: event-name → wire event string."
  [event-name]
  (:event (event-for event-name)))

(defn all-event-names
  "Every event-name keyword in the registry."
  []
  (keys events))
