# DAP Implementation Research for Defport

## Executive Summary

This document explores the spectrum of DAP (Debug Adapter Protocol) implementation options for defport, from minimal protocol infrastructure to full language-agnostic debugging. The goal is to design `dap.cljc` that fits defport's philosophy: a library, not a framework.

**Key Insight**: DAP is essentially a REPL protocol in disguise. The `evaluate` command maps naturally onto REPL functionality, and `variables` enables data structure inspection. This makes Clojure an excellent fit.

---

## 1. Protocol Comparison: MCP vs LSP vs DAP

| Aspect | MCP | LSP | DAP |
|--------|-----|-----|-----|
| **Purpose** | AI model <-> tools/data | Editor <-> language analysis | IDE <-> debugger |
| **Transport** | JSON-RPC over stdio/HTTP | JSON-RPC over stdio/socket | Custom protocol over stdio/socket |
| **Base Protocol** | JSON-RPC 2.0 | JSON-RPC 2.0 | Custom (seq/type/command) |
| **State Model** | Stateless (per-request) | Stateful (document sync) | Stateful (debug session) |
| **Lifecycle** | initialize -> tools/call | initialize -> textDocument/* | initialize -> launch/attach -> stepping |
| **Primary Consumer** | AI assistants (Claude) | IDEs (VSCode, Emacs) | IDE debugger UIs |

**Protocol Message Structure**:

```
MCP/LSP (JSON-RPC 2.0):           DAP (Custom):
{                                  {
  "jsonrpc": "2.0",                  "seq": 1,
  "id": 1,                           "type": "request",
  "method": "tools/call",            "command": "launch",
  "params": {...}                    "arguments": {...}
}                                  }
```

---

## 2. DAP Protocol Deep Dive

### 2.1 Message Types

DAP uses three message types:

```clojure
;; Request (client -> adapter)
{:seq 1
 :type "request"
 :command "setBreakpoints"
 :arguments {:source {:path "/foo/bar.clj"}
             :breakpoints [{:line 10}]}}

;; Response (adapter -> client)
{:seq 2
 :type "response"
 :request_seq 1
 :success true
 :command "setBreakpoints"
 :body {:breakpoints [{:verified true :line 10}]}}

;; Event (adapter -> client, unsolicited)
{:seq 3
 :type "event"
 :event "stopped"
 :body {:reason "breakpoint"
        :threadId 1
        :allThreadsStopped true}}
```

### 2.2 Lifecycle

```
Client                              Adapter
   |                                   |
   |-------- initialize ------------->|
   |<------- capabilities ------------|
   |                                   |
   |-------- launch/attach ---------->|  (start debugging)
   |<------- initialized event -------|  (ready for config)
   |                                   |
   |-------- setBreakpoints --------->|
   |-------- setExceptionBreakpoints->|
   |-------- configurationDone ------>|
   |                                   |
   |<------- stopped event -----------|  (hit breakpoint)
   |-------- stackTrace ------------->|
   |-------- scopes ----------------->|
   |-------- variables --------------->|
   |-------- continue ----------------->|
   |                                   |
   |<------- terminated event --------|
   |-------- disconnect ------------->|
```

### 2.3 Key Capabilities

**Adapter Capabilities** (reported in initialize response):
```clojure
{:supportsConfigurationDoneRequest true
 :supportsFunctionBreakpoints true
 :supportsConditionalBreakpoints true
 :supportsHitConditionalBreakpoints false
 :supportsEvaluateForHovers true
 :supportsStepBack true              ; Time-travel (FlowStorm!)
 :supportsSetVariable true
 :supportsRestartFrame true
 :supportsGotoTargetsRequest false
 :supportsStepInTargetsRequest false
 :supportsCompletionsRequest true    ; REPL completion
 :supportsModulesRequest false
 :supportsRestartRequest true
 :supportsExceptionOptions true
 :supportsValueFormattingOptions true
 :supportsExceptionInfoRequest true
 :supportTerminateDebuggee true
 :supportSuspendDebuggee true
 :supportsDelayedStackTraceLoading true
 :supportsLoadedSourcesRequest true
 :supportsLogPoints true             ; Log instead of break
 :supportsTerminateThreadsRequest false
 :supportsSetExpression true
 :supportsTerminateRequest true
 :supportsDataBreakpoints false      ; Memory watchpoints
 :supportsReadMemoryRequest false
 :supportsWriteMemoryRequest false
 :supportsDisassembleRequest false
 :supportsCancelRequest true
 :supportsBreakpointLocationsRequest true
 :supportsClipboardContext true
 :supportsSteppingGranularity false
 :supportsInstructionBreakpoints false
 :supportsExceptionFilterOptions true
 :supportsSingleThreadExecutionRequests false}
```

### 2.4 Variable References (Critical Concept)

Variables use numeric handles for hierarchical inspection:

```clojure
;; 1. Get scopes for a stack frame
;; Request: scopes for frameId 0
;; Response:
{:scopes [{:name "Locals"
           :variablesReference 1000  ; Handle to fetch children
           :expensive false}
          {:name "Globals"
           :variablesReference 1001
           :expensive true}]}

;; 2. Fetch variables for scope
;; Request: variables for variablesReference 1000
;; Response:
{:variables [{:name "my-map"
              :value "{:a 1, :b 2}"
              :type "PersistentArrayMap"
              :variablesReference 1002  ; Has children!
              :namedVariables 2}
             {:name "my-vec"
              :value "[1 2 3]"
              :variablesReference 1003
              :indexedVariables 3}]}

;; 3. Expand nested structure
;; Request: variables for variablesReference 1002
;; Response:
{:variables [{:name ":a" :value "1" :variablesReference 0}
             {:name ":b" :value "2" :variablesReference 0}]}
```

**Key Rule**: Variable references are only valid while execution is paused. They expire on `continue`.

---

## 3. Implementation Spectrum

### Level 0: Protocol Infrastructure Only

**Description**: DAP message parsing/formatting and transport handling. No actual debugging - just the protocol foundation.

```
┌─────────────┐     ┌─────────────┐
│   Client    │────>│   defport   │
│  (VSCode)   │<────│  (DAP msg)  │ ── No backend
└─────────────┘     └─────────────┘
```

**Implementation**:
- DAP message codec (encode/decode)
- Transport layer (stdio, socket)
- Protocol state machine
- Capability negotiation

**Pros**: Foundation for all other levels
**Cons**: Useless alone

**Code Volume**: ~400 lines

---

### Level 1: REPL-as-Debugger

**Description**: Use DAP as a REPL protocol. The `evaluate` command becomes the REPL, `variables` enables data inspection. No breakpoints or stepping.

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   VSCode    │────>│   defport   │────>│   nREPL     │
│  (DAP UI)   │<────│  (Bridge)   │<────│  (Runtime)  │
└─────────────┘     └─────────────┘     └─────────────┘
```

**Implemented Commands**:
- `initialize` / `disconnect` - Session lifecycle
- `evaluate` - REPL evaluation (core feature)
- `variables` - Data structure inspection
- `completions` - Code completion

**Stubbed Commands** (required but no-op):
- `launch` / `attach` - Return success immediately
- `setBreakpoints` - Accept but ignore
- `threads` - Return single dummy thread
- `stackTrace` - Return empty or single frame

**Why This Works**:

> "The Debug Adapter Protocol is a REPL protocol in disguise."
> - [zignar.net](https://zignar.net/2025/06/23/debug-adapter-protocol-is-a-repl-protocol/)

The DAP UI (variables pane, watch expressions, debug console) maps perfectly to REPL interaction.

**Pros**: Works with any Clojure runtime, simple implementation, useful TODAY
**Cons**: No breakpoints, no stepping, debugging UI may confuse users

**Use Case**: REPL integration for editors that don't have native Clojure support

**Code Volume**: ~600 lines

---

### Level 2: CIDER/nREPL Bridge

**Description**: Bridge DAP to CIDER's debugger middleware. Leverage existing breakpoint and stepping infrastructure.

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   VSCode    │────>│   defport   │────>│   nREPL +   │
│  (DAP UI)   │<────│  (Bridge)   │<────│   CIDER     │
└─────────────┘     └─────────────┘     └─────────────┘
```

**This is what [clojure-dap](https://github.com/Olical/clojure-dap) is building.**

**Implemented Commands**:
- All Level 1 commands
- `setBreakpoints` - Translate to CIDER `#dbg` or `#break`
- `continue` / `next` / `stepIn` / `stepOut` - CIDER debug commands
- `stackTrace` - CIDER debug state
- `scopes` / `variables` - CIDER locals inspection

**Architecture**:
```clojure
;; DAP setBreakpoints
(defn handle-set-breakpoints [params context]
  (let [{:keys [source breakpoints]} params
        file (:path source)]
    ;; Translate to CIDER instrumentation
    (nrepl-send context
      {:op "debug-instrument"
       :file file
       :lines (map :line breakpoints)})
    {:breakpoints (map #(assoc % :verified true) breakpoints)}))

;; DAP continue -> CIDER debug-continue
(defn handle-continue [params context]
  (nrepl-send context {:op "debug-input" :input ":continue"})
  {:allThreadsContinued true})
```

**Pros**: Real breakpoints, leverages mature CIDER debugger
**Cons**: Requires CIDER middleware, Clojure-only, source instrumentation model

**Code Volume**: ~1200 lines

---

### Level 3: FlowStorm Bridge

**Description**: Bridge DAP to FlowStorm's omniscient time-travel debugger. Use DAP stepping commands for time-travel navigation.

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   VSCode    │────>│   defport   │────>│  FlowStorm  │
│  (DAP UI)   │<────│  (Bridge)   │<────│  (WS 7722)  │
└─────────────┘     └─────────────┘     └─────────────┘
```

**Key Insight**: FlowStorm records EVERYTHING. We can map DAP commands to timeline navigation:

```clojure
;; DAP stepBack -> FlowStorm timeline navigation
(defn handle-step-back [params context]
  (flowstorm-send context {:op :step-back})
  (let [state (flowstorm-get-state context)]
    ;; Emit stopped event with new position
    (emit-stopped-event context state)))

;; DAP variables -> FlowStorm value inspection
(defn handle-variables [params context]
  (let [ref (:variablesReference params)
        value (flowstorm-get-value context ref)]
    {:variables (value->dap-variables value)}))
```

**Capability Highlight**:
```clojure
{:supportsStepBack true           ; TIME TRAVEL!
 :supportsRestartFrame true       ; Replay frame
 :supportsEvaluateForHovers true  ; Inspect at any point
 :supportsValueFormattingOptions true}
```

**Pros**: Time-travel debugging (unique!), full execution history, no source instrumentation
**Cons**: Requires FlowStorm running, different mental model, WebSocket dependency

**Use Case**: Advanced debugging, production issue analysis, learning tool

**Code Volume**: ~1500 lines

---

### Level 4: JDI Direct (JVM Debugging)

**Description**: Direct integration with Java Debug Interface (JDI). Real JVM debugging like scala-debug-adapter.

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   VSCode    │────>│   defport   │────>│    JVM      │
│  (DAP UI)   │<────│  (DAP+JDI)  │<────│  (JDWP)     │
└─────────────┘     └─────────────┘     └─────────────┘
```

**Key JDI Operations**:
```clojure
(import '[com.sun.jdi Bootstrap VirtualMachine])

;; Connect to JVM
(defn connect-vm [host port]
  (let [vm-manager (Bootstrap/virtualMachineManager)
        connector (.attachingConnectors vm-manager)
        socket-connector (first (filter #(.name %) connector))
        args (.defaultArguments socket-connector)]
    (.setValue (.get args "hostname") host)
    (.setValue (.get args "port") (str port))
    (.attach socket-connector args)))

;; Set breakpoint
(defn set-breakpoint [vm class-pattern line]
  (let [req-manager (.eventRequestManager vm)
        classes (.classesByName vm class-pattern)]
    (doseq [cls classes]
      (let [locations (.locationsOfLine cls line)]
        (doseq [loc locations]
          (let [bp-req (.createBreakpointRequest req-manager loc)]
            (.enable bp-req)))))))
```

**Challenges for Clojure**:
1. **Class names**: Clojure generates classes like `user$eval1234`
2. **Source mapping**: Need to map Clojure source to JVM bytecode locations
3. **AOT vs dynamic**: Different behavior for compiled vs eval'd code
4. **Expression evaluation**: Must compile expressions with correct context

**Pros**: Real JVM debugging, works with Java interop, standard debugging model
**Cons**: Complex implementation, Clojure-specific challenges, JVM-only

**Code Volume**: ~3000 lines

---

### Level 5: Language-Agnostic Proxy

**Description**: Proxy/aggregate layer that routes to underlying DAP servers or debugger backends.

```
                    ┌─────────────┐
  ┌───────────────>│    GDB      │ (Native: C, C++, Rust)
  │                └─────────────┘
  │
┌─────────────┐    ┌─────────────┐
│   defport   │───>│  FlowStorm  │ (Clojure)
│  (Proxy)    │    └─────────────┘
└─────────────┘
  │                ┌─────────────┐
  └───────────────>│  Chrome     │ (JavaScript, TypeScript)
                   │  DevTools   │
                   └─────────────┘
```

**Architecture**:
```clojure
(def debugger-backends
  {:clojure   {:type :flowstorm :port 7722}
   :python    {:type :dap-proxy :command ["debugpy" "--listen" "5678"]}
   :rust      {:type :dap-proxy :command ["codelldb"]}
   :cpp       {:type :gdb :command ["gdb" "--interpreter=dap"]}
   :javascript {:type :cdp :port 9222}})

(defn route-request [request context]
  (let [file (get-in request [:arguments :source :path])
        lang (detect-language file)
        backend (get debugger-backends lang)]
    (case (:type backend)
      :flowstorm (flowstorm-dispatch request backend)
      :dap-proxy (dap-proxy-dispatch request backend)
      :gdb       (gdb-dispatch request backend)
      :cdp       (cdp-to-dap-dispatch request backend))))
```

**Features**:
- Multi-language debugging in single session
- Unified variable inspection across languages
- Aggregate breakpoint management
- Language detection from file path

**Pros**: True language-agnostic debugging, leverage existing debuggers
**Cons**: Complex aggregation, latency, configuration overhead

**Use Case**: Polyglot projects, AI code assistants needing cross-language debugging

**Code Volume**: ~2500 lines (proxy layer) + backend implementations

---

## 4. Architectural Analysis

### 4.1 State Management

DAP requires significant state tracking:

```clojure
(defonce dap-state*
  (atom {:session-id nil
         :initialized? false
         :configured? false
         :debuggee nil

         ;; Thread state
         :threads {}
         :stopped-threads #{}
         :all-threads-stopped? false

         ;; Breakpoints
         :breakpoints {}        ; source-path -> [bp]
         :exception-breakpoints #{}
         :function-breakpoints []

         ;; Variable references (expire on continue!)
         :next-var-ref (atom 1000)
         :var-refs {}          ; ref-id -> value

         ;; Stack frames
         :next-frame-id (atom 0)
         :frames {}            ; frame-id -> frame-data

         ;; Scopes
         :next-scope-id (atom 0)
         :scopes {}}))         ; scope-id -> scope-data

(defn clear-transient-state!
  "Clear state that expires when execution resumes."
  []
  (swap! dap-state* assoc
    :var-refs {}
    :frames {}
    :scopes {}))
```

### 4.2 Message Sequencing

DAP uses sequence numbers for correlation:

```clojure
(defonce seq-counter* (atom 0))

(defn next-seq []
  (swap! seq-counter* inc))

(defn make-response [request body]
  {:seq (next-seq)
   :type "response"
   :request_seq (:seq request)
   :success true
   :command (:command request)
   :body body})

(defn make-event [event body]
  {:seq (next-seq)
   :type "event"
   :event event
   :body body})
```

### 4.3 Transport Layer

DAP messages use HTTP-style framing:

```
Content-Length: 119\r\n
\r\n
{"seq":1,"type":"request","command":"initialize","arguments":{"clientID":"vscode","adapterID":"clojure"}}
```

```clojure
(defn read-message [input]
  (let [headers (read-headers input)
        content-length (parse-int (get headers "Content-Length"))
        body (read-bytes input content-length)]
    (json/parse-string body true)))

(defn write-message [output message]
  (let [body (json/generate-string message)
        bytes (.getBytes body "UTF-8")]
    (.write output (str "Content-Length: " (count bytes) "\r\n\r\n"))
    (.write output body)
    (.flush output)))
```

### 4.4 Capability Mapping to Ports

Map DAP capabilities to defport ports:

```clojure
(defn compute-capabilities [port-registry]
  (let [ports (core/list-ports port-registry)
        port-ids (set (map :id ports))]
    {:supportsConfigurationDoneRequest true
     :supportsEvaluateForHovers (contains? port-ids :evaluate)
     :supportsCompletionsRequest (contains? port-ids :completions)
     :supportsSetVariable (contains? port-ids :set-variable)
     :supportsStepBack (contains? port-ids :step-back)
     :supportsFunctionBreakpoints (contains? port-ids :function-breakpoints)
     ;; ... map other capabilities
     }))
```

---

## 5. Proposed Architecture for dap.cljc

### 5.1 Module Structure

```
src/defport/protocols/
├── mcp.cljc           # Existing MCP adapter
├── lsp/               # Existing LSP adapter
│   └── core.cljc
└── dap/
    ├── core.cljc      # DapAdapter (ProtocolAdapter impl)
    ├── codec.cljc     # Message encoding/decoding
    ├── state.cljc     # Session state management
    ├── handlers.cljc  # Request handlers by category
    ├── variables.cljc # Variable reference management
    ├── backends/      # Backend integrations
    │   ├── repl.cljc       # Level 1: REPL-as-debugger
    │   ├── nrepl.cljc      # Level 2: nREPL/CIDER bridge
    │   ├── flowstorm.cljc  # Level 3: FlowStorm bridge
    │   └── jdi.clj         # Level 4: JDI (JVM only)
    └── proxy.cljc     # Level 5: Multi-backend proxy
```

### 5.2 Core Protocol Implementation

```clojure
(ns defport.protocols.dap.core
  (:require [defport.core :as core]
            [defport.protocols.dap.codec :as codec]
            [defport.protocols.dap.state :as state]
            [defport.protocols.dap.handlers :as handlers]))

(defrecord DapAdapter [server-info backend-type backend-opts adapter-state]
  core/ProtocolAdapter

  (protocol-id [_] :dap)

  (protocol-version [_] "1.51")  ; DAP spec version

  (protocol-capabilities [this port-registry]
    (handlers/compute-capabilities port-registry backend-type))

  (protocol-dispatch [this command args context]
    (let [handler (handlers/get-handler command)
          enriched-context (assoc context
                             :adapter-state adapter-state
                             :backend-type backend-type
                             :backend-opts backend-opts)]
      (if handler
        (handler args enriched-context)
        {:success false
         :message (str "Unknown command: " command)}))))

(defn create-dap-adapter
  "Create a DAP adapter.

  Options:
    :server-info - Map with :name and :version
    :backend - Backend type (:repl, :nrepl, :flowstorm, :jdi, :proxy)
    :backend-opts - Backend-specific options

  Backend options by type:
    :repl - {:eval-fn (fn [code] result)}
    :nrepl - {:host \"localhost\" :port 7888}
    :flowstorm - {:host \"localhost\" :port 7722}
    :jdi - {:host \"localhost\" :port 5005}
    :proxy - {:backends {...}}
  "
  ([] (create-dap-adapter nil))
  ([opts]
   (let [server-info (or (:server-info opts)
                        {:name "defport-dap-server" :version "0.1.0"})
         backend-type (or (:backend opts) :repl)
         backend-opts (:backend-opts opts {})
         adapter-state (state/create-state)]
     (->DapAdapter server-info backend-type backend-opts adapter-state))))
```

### 5.3 Handlers Implementation

```clojure
(ns defport.protocols.dap.handlers
  (:require [defport.protocols.dap.state :as state]
            [defport.protocols.dap.variables :as vars]))

;; Handler registry
(defmulti handle-command (fn [command args context] command))

;; Lifecycle
(defmethod handle-command "initialize" [_ args context]
  (let [{:keys [adapter-state backend-type port-registry]} context]
    (state/initialize! adapter-state)
    {:capabilities (compute-capabilities port-registry backend-type)}))

(defmethod handle-command "launch" [_ args context]
  (let [{:keys [adapter-state backend-type backend-opts]} context]
    (case backend-type
      :repl     (do (state/set-launched! adapter-state) {})
      :nrepl    (nrepl/connect! backend-opts adapter-state)
      :flowstorm (flowstorm/connect! backend-opts adapter-state)
      :jdi      (jdi/launch! args backend-opts adapter-state))))

(defmethod handle-command "attach" [_ args context]
  (let [{:keys [adapter-state backend-type backend-opts]} context]
    (case backend-type
      :jdi (jdi/attach! args backend-opts adapter-state)
      (do (state/set-attached! adapter-state) {}))))

(defmethod handle-command "configurationDone" [_ _ context]
  (let [{:keys [adapter-state transport]} context]
    (state/set-configured! adapter-state)
    ;; Emit initialized event
    (core/transport-send transport
      (codec/make-event "initialized" {}))
    {}))

(defmethod handle-command "disconnect" [_ args context]
  (let [{:keys [adapter-state]} context]
    (state/reset! adapter-state)
    {}))

;; Execution Control
(defmethod handle-command "continue" [_ args context]
  (let [{:keys [adapter-state backend-type]} context]
    (state/clear-transient-state! adapter-state)
    (case backend-type
      :repl     {:allThreadsContinued true}
      :nrepl    (nrepl/continue! adapter-state)
      :flowstorm (flowstorm/continue! adapter-state)
      :jdi      (jdi/continue! args adapter-state))))

(defmethod handle-command "next" [_ args context]
  (handle-step :next args context))

(defmethod handle-command "stepIn" [_ args context]
  (handle-step :in args context))

(defmethod handle-command "stepOut" [_ args context]
  (handle-step :out args context))

(defmethod handle-command "stepBack" [_ args context]
  ;; Only FlowStorm supports time-travel
  (let [{:keys [backend-type adapter-state]} context]
    (if (= backend-type :flowstorm)
      (flowstorm/step-back! adapter-state)
      {:success false :message "stepBack not supported"})))

;; Breakpoints
(defmethod handle-command "setBreakpoints" [_ args context]
  (let [{:keys [source breakpoints]} args
        {:keys [adapter-state backend-type]} context]
    (state/set-breakpoints! adapter-state (:path source) breakpoints)
    (case backend-type
      :repl     {:breakpoints (map #(assoc % :verified false) breakpoints)}
      :nrepl    (nrepl/set-breakpoints! adapter-state source breakpoints)
      :flowstorm {:breakpoints (map #(assoc % :verified true) breakpoints)}
      :jdi      (jdi/set-breakpoints! adapter-state source breakpoints))))

;; Stack & Variables
(defmethod handle-command "threads" [_ _ context]
  (let [{:keys [adapter-state backend-type]} context]
    (case backend-type
      :repl {:threads [{:id 1 :name "main"}]}
      :jdi  (jdi/get-threads adapter-state)
      {:threads [{:id 1 :name "main"}]})))

(defmethod handle-command "stackTrace" [_ args context]
  (let [{:keys [adapter-state backend-type]} context]
    (case backend-type
      :repl     {:stackFrames [] :totalFrames 0}
      :nrepl    (nrepl/get-stack-trace adapter-state args)
      :flowstorm (flowstorm/get-stack-trace adapter-state args)
      :jdi      (jdi/get-stack-trace adapter-state args))))

(defmethod handle-command "scopes" [_ args context]
  (let [{:keys [adapter-state backend-type]} context
        frame-id (:frameId args)]
    (case backend-type
      :repl {:scopes [{:name "Globals"
                       :variablesReference (vars/create-ref adapter-state :globals)
                       :expensive false}]}
      :nrepl (nrepl/get-scopes adapter-state frame-id)
      :flowstorm (flowstorm/get-scopes adapter-state frame-id)
      :jdi (jdi/get-scopes adapter-state frame-id))))

(defmethod handle-command "variables" [_ args context]
  (let [{:keys [adapter-state backend-type]} context
        var-ref (:variablesReference args)]
    (vars/get-variables adapter-state var-ref)))

;; Evaluation (The REPL!)
(defmethod handle-command "evaluate" [_ args context]
  (let [{:keys [adapter-state backend-type backend-opts port-registry]} context
        {:keys [expression context frameId]} args]
    (case backend-type
      :repl
      (let [eval-port (core/get-port port-registry :evaluate)]
        (if eval-port
          (let [result (core/port-execute eval-port {:params {:code expression}})]
            {:result (pr-str (:result result))
             :variablesReference (vars/create-ref-for-value adapter-state (:result result))})
          {:result "No evaluate port registered" :variablesReference 0}))

      :nrepl (nrepl/evaluate adapter-state expression {:frame-id frameId})
      :flowstorm (flowstorm/evaluate adapter-state expression {:frame-id frameId})
      :jdi (jdi/evaluate adapter-state expression {:frame-id frameId}))))

;; Completions (REPL autocomplete)
(defmethod handle-command "completions" [_ args context]
  (let [{:keys [adapter-state backend-type port-registry]} context
        {:keys [text column]} args]
    (let [completions-port (core/get-port port-registry :completions)]
      (if completions-port
        (let [result (core/port-execute completions-port
                       {:params {:prefix text :column column}})]
          {:targets (map (fn [c] {:label c :type "function"}) (:result result))})
        {:targets []}))))

;; Default handler for unknown commands
(defmethod handle-command :default [command _ _]
  {:success false :message (str "Unknown command: " command)})

(defn get-handler [command]
  (fn [args context]
    (handle-command command args context)))
```

### 5.4 Variable Reference Management

```clojure
(ns defport.protocols.dap.variables)

(defn create-ref
  "Create a variable reference for a scope or container."
  [adapter-state scope-type]
  (let [ref-id (swap! (:next-var-ref @adapter-state) inc)]
    (swap! (:var-refs @adapter-state) assoc ref-id {:type scope-type})
    ref-id))

(defn create-ref-for-value
  "Create a variable reference for an actual Clojure value."
  [adapter-state value]
  (if (or (map? value) (vector? value) (seq? value) (set? value))
    (let [ref-id (swap! (:next-var-ref @adapter-state) inc)]
      (swap! (:var-refs @adapter-state) assoc ref-id {:type :value :value value})
      ref-id)
    0))  ; 0 means no children

(defn get-variables
  "Get variables for a reference ID."
  [adapter-state var-ref]
  (if-let [ref-data (get @(:var-refs @adapter-state) var-ref)]
    (case (:type ref-data)
      :globals (get-global-variables adapter-state)
      :locals  (get-local-variables adapter-state (:frame-id ref-data))
      :value   (value->variables adapter-state (:value ref-data)))
    {:variables []}))

(defn value->variables
  "Convert a Clojure value to DAP variables."
  [adapter-state value]
  {:variables
   (cond
     (map? value)
     (map (fn [[k v]]
            {:name (pr-str k)
             :value (truncate-str (pr-str v) 100)
             :type (type-name v)
             :variablesReference (create-ref-for-value adapter-state v)})
          value)

     (or (vector? value) (seq? value))
     (map-indexed (fn [i v]
                    {:name (str "[" i "]")
                     :value (truncate-str (pr-str v) 100)
                     :type (type-name v)
                     :variablesReference (create-ref-for-value adapter-state v)})
                  value)

     (set? value)
     (map-indexed (fn [i v]
                    {:name (str "#{" i "}")
                     :value (truncate-str (pr-str v) 100)
                     :type (type-name v)
                     :variablesReference (create-ref-for-value adapter-state v)})
                  value)

     :else [])})
```

---

## 6. Implementation Roadmap

### Phase 1: Protocol Infrastructure (Level 0)

**Goal**: DAP message handling foundation

1. Implement DAP codec (encode/decode)
2. Add DAP transport (stdio with Content-Length framing)
3. Create DapAdapter skeleton
4. Implement initialize/disconnect lifecycle

**Deliverables**:
- `src/defport/protocols/dap/core.cljc`
- `src/defport/protocols/dap/codec.cljc`
- `src/defport/transports/dap_stdio.cljc`

**Tests**: Protocol message round-trip, transport framing

---

### Phase 2: REPL-as-Debugger (Level 1)

**Goal**: Functional DAP server with REPL evaluation

1. Implement evaluate handler
2. Add variable reference management
3. Implement completions handler
4. Stub breakpoint/stepping handlers

**Deliverables**:
- `src/defport/protocols/dap/handlers.cljc`
- `src/defport/protocols/dap/variables.cljc`
- `src/defport/protocols/dap/backends/repl.cljc`

**Tests**: VSCode integration, evaluate/variables round-trip

---

### Phase 3: nREPL/CIDER Bridge (Level 2)

**Goal**: Real breakpoint debugging via CIDER

1. Implement nREPL client
2. Add CIDER debug middleware integration
3. Implement breakpoint → instrumentation translation
4. Add stack trace / scopes support

**Deliverables**:
- `src/defport/protocols/dap/backends/nrepl.cljc`

**Tests**: Breakpoint hit, stepping, local variable inspection

---

### Phase 4: FlowStorm Bridge (Level 3)

**Goal**: Time-travel debugging

1. Implement FlowStorm WebSocket client
2. Add timeline navigation (stepBack!)
3. Implement value inspection from recordings
4. Support restartFrame

**Deliverables**:
- `src/defport/protocols/dap/backends/flowstorm.cljc`

**Tests**: Time-travel navigation, recorded value inspection

---

### Phase 5: JDI Integration (Level 4)

**Goal**: Real JVM debugging

1. JDI connection management
2. Breakpoint → JDI translation
3. Stack frame / variable inspection via JDI
4. Expression evaluation compilation

**Deliverables**:
- `src/defport/protocols/dap/backends/jdi.clj` (JVM-only)

**Tests**: JVM breakpoint hit, Java interop debugging

---

### Phase 6: Multi-Backend Proxy (Level 5)

**Goal**: Language-agnostic debugging

1. Backend routing by file type
2. DAP proxy to external debuggers
3. GDB integration (native code)
4. Chrome DevTools bridge (JavaScript)

**Deliverables**:
- `src/defport/protocols/dap/proxy.cljc`
- `src/defport/protocols/dap/backends/gdb.clj`
- `src/defport/protocols/dap/backends/cdp.cljc`

---

## 7. Example Usage

### 7.1 REPL-as-Debugger (Level 1)

```clojure
(ns my-dap-server
  (:require [defport.protocols.dap.core :as dap]
            [defport.registry :as registry]
            [defport.transports.dap-stdio :as transport]))

;; Register REPL ports
(def my-registry (registry/create-function-registry))

(registry/register-port! my-registry
  {:id :evaluate
   :handler (fn [{:keys [params]}]
              {:result (eval (read-string (:code params)))})})

;; Create DAP adapter
(def adapter (dap/create-dap-adapter
  {:server-info {:name "clj-repl" :version "1.0.0"}
   :backend :repl}))

;; Start server
(def server (transport/create-dap-stdio-transport))

(transport/start server
  (fn [request]
    (dap/protocol-dispatch adapter
      (:command request)
      (:arguments request)
      {:port-registry my-registry
       :transport server})))
```

### 7.2 FlowStorm Time-Travel (Level 3)

```clojure
(def adapter (dap/create-dap-adapter
  {:server-info {:name "flowstorm-dap" :version "1.0.0"}
   :backend :flowstorm
   :backend-opts {:host "localhost" :port 7722}}))

;; Now VSCode can use stepBack!
```

### 7.3 Multi-Language Proxy (Level 5)

```clojure
(def adapter (dap/create-dap-adapter
  {:server-info {:name "polyglot-debugger" :version "1.0.0"}
   :backend :proxy
   :backend-opts {:backends
                  {:clojure {:type :flowstorm :port 7722}
                   :python  {:type :dap-proxy :command ["debugpy"]}
                   :rust    {:type :dap-proxy :command ["codelldb"]}
                   :c       {:type :gdb :command ["gdb" "--interpreter=dap"]}}}}))
```

---

## 8. Comparison Table

| Level | Breakpoints | Stepping | Time-Travel | Eval | Languages | Complexity |
|-------|-------------|----------|-------------|------|-----------|------------|
| L0: Infrastructure | - | - | - | - | - | Low |
| L1: REPL | - | - | - | **Yes** | Clojure | Low |
| L2: CIDER | **Yes** | **Yes** | - | **Yes** | Clojure | Medium |
| L3: FlowStorm | - | **Yes** | **Yes** | **Yes** | Clojure | Medium |
| L4: JDI | **Yes** | **Yes** | - | **Yes** | JVM | High |
| L5: Proxy | **Yes** | **Yes** | Varies | **Yes** | **Any** | High |

---

## 9. Key Decisions

### 9.1 Start with Level 1 (REPL-as-Debugger)

**Rationale**:
- Provides immediate value
- Simple implementation
- Validates architecture
- Works TODAY with any Clojure runtime

### 9.2 FlowStorm as Primary Debug Backend

**Rationale**:
- Time-travel is unique and powerful
- No source instrumentation required
- Already has WebSocket protocol
- Aligns with Clojure's immutability philosophy

### 9.3 JDI as Optional Enhancement

**Rationale**:
- Only needed for Java interop debugging
- Significant implementation effort
- scala-debug-adapter can be referenced

### 9.4 Language-Agnostic via Proxy

**Rationale**:
- Don't reinvent debuggers
- GDB now supports DAP natively
- Chrome DevTools handles JavaScript
- Aggregation layer adds value

---

## 10. References

- [DAP Specification](https://microsoft.github.io/debug-adapter-protocol/specification)
- [Microsoft DAP Repository](https://github.com/microsoft/debug-adapter-protocol)
- [DAP is a REPL Protocol](https://zignar.net/2025/06/23/debug-adapter-protocol-is-a-repl-protocol/)
- [clojure-dap](https://github.com/Olical/clojure-dap) - WIP Clojure DAP server
- [FlowStorm](https://github.com/flow-storm/flow-storm-debugger) - Time-travel debugger
- [scala-debug-adapter](https://github.com/scalacenter/scala-debug-adapter) - JDI reference
- [GDB DAP](https://sourceware.org/gdb/current/onlinedocs/gdb.html/Debugger-Adapter-Protocol.html)
- [GraalVM DAP](https://www.graalvm.org/latest/tools/dap/)
- [CIDER Debugger](https://docs.cider.mx/cider/debugging/debugger.html)
- [SLIME/SWANK](https://github.com/slime/slime) - Original Lisp debugging inspiration
- [Defport Architecture](ARCHITECTURE.md)
- [LSP Research](LSP_RESEARCH.md) - Similar analysis for LSP