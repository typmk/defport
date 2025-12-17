# Protocol Implementation Plan
## CDP, prepl, nREPL Adapters for defport

**Priority Order:** CDP → prepl → nREPL
**Created:** 2025-01-13

---

## Overview

This document outlines the implementation plan for three new protocol adapters for defport:

1. **Chrome DevTools Protocol (CDP)** - Browser automation and inspection
2. **prepl** - Programmable REPL with structured output
3. **nREPL** - Network REPL for Clojure development tooling

All implementations follow defport's protocol-agnostic architecture where ports (capabilities) can be exposed through multiple protocol adapters.

---

## Phase 1: CDP (Chrome DevTools Protocol) 🚀 PRIORITY

### Reference Implementation
Scout project already has a CDP client implementation at `C:\Users\Apollo\CascadeProjects\scout\src\scout\cdp.clj` that we can reference for patterns.

### Architecture

**CDP Protocol Characteristics:**
- **Transport:** HTTP discovery + WebSocket for commands
- **Message Format:** JSON with `{id, method, params}` structure (similar to JSON-RPC 2.0)
- **Communication Pattern:** Bidirectional (client sends commands, server sends events)
- **Connection:** Requires Chrome running with `--remote-debugging-port=9222`

### CDP Protocol Adapter Design

```clojure
(ns defport.protocols.cdp
  "Chrome DevTools Protocol adapter for defport."
  (:require [defport.core :as core]
            [cheshire.core :as json])
  (:import [java.net.http HttpClient WebSocket$Listener WebSocket]))

;; Protocol adapter record
(defrecord CDPAdapter [port-registry
                       config              ; {:port 9222 :domains [...]}
                       ws-conn             ; atom: WebSocket connection
                       responses           ; atom: {id -> response}
                       msg-id              ; atom: message counter
                       event-handlers      ; atom: {event-method -> handler-fn}
                       text-buffer])       ; atom: incoming message buffer

;; Implements ProtocolAdapter
(extend-type CDPAdapter
  core/ProtocolAdapter
  (protocol-id [_] :cdp)
  (protocol-version [_] "1.3")
  (protocol-capabilities [this port-registry]
    ;; Return available CDP domains based on registered ports
    {:domains (compute-enabled-domains port-registry)
     :version {:browser "..." :protocolVersion "1.3"}})

  (protocol-dispatch [this method params context]
    ;; Route CDP commands to appropriate ports
    ;; e.g., "Page.navigate" -> :cdp/page-navigate port
    (dispatch-cdp-command this method params context)))
```

### Port Mapping Strategy

CDP domains map to defport ports with hierarchical naming:

| CDP Method | Port ID | Description |
|------------|---------|-------------|
| `Page.navigate` | `:cdp/page-navigate` | Navigate to URL |
| `Page.enable` | `:cdp/page-enable` | Enable page domain |
| `Runtime.evaluate` | `:cdp/runtime-evaluate` | Execute JavaScript |
| `Network.enable` | `:cdp/network-enable` | Enable network tracking |
| `DOM.getDocument` | `:cdp/dom-get-document` | Get DOM tree |
| `Debugger.enable` | `:cdp/debugger-enable` | Enable debugging |

**Event Handling:**
- CDP sends events asynchronously (e.g., `Network.requestWillBeSent`)
- Protocol adapter registers event handlers
- Events can trigger port executions or be stored in state

### Implementation Structure

```
src/defport/protocols/
├── cdp.cljc                      # CDP protocol adapter
├── cdp/
│   ├── connection.cljc           # WebSocket connection management
│   ├── domains.cljc              # CDP domain definitions
│   └── events.cljc               # Event handler system

test/defport/protocols/
└── cdp_test.clj                  # CDP adapter tests

examples/
└── cdp_browser_automation.clj    # Example: browser automation via CDP
```

### Core Features to Implement

**Phase 1.1: Connection & Basic Commands**
- [ ] HTTP discovery (`/json` endpoint)
- [ ] WebSocket connection with listener
- [ ] Basic command/response mechanism
- [ ] Message ID tracking
- [ ] Text buffer for fragmented messages

**Phase 1.2: Core Domains**
- [ ] `Page` domain (navigate, enable, lifecycle events)
- [ ] `Runtime` domain (evaluate, call function)
- [ ] `Network` domain (enable, request tracking, cookies)
- [ ] `DOM` domain (get document, query selector)

**Phase 1.3: Advanced Features**
- [ ] Event subscription system
- [ ] Multi-target support (tabs/workers)
- [ ] Screenshot capture
- [ ] Network interception
- [ ] Console message capture

**Phase 1.4: Port Registry Integration**
- [ ] Port discovery from CDP capabilities
- [ ] Dynamic port registration per domain
- [ ] Port metadata (required domains, permissions)

### Example Usage

```clojure
(require '[defport.core :as defport]
         '[defport.protocols.cdp :as cdp]
         '[defport.transports.websocket :as ws])

;; Start Chrome: chrome --remote-debugging-port=9222

;; Create CDP adapter
(def adapter (cdp/create-adapter
              {:port 9222
               :domains [:Page :Runtime :Network :DOM]}))

;; Register CDP ports
(def registry (defport/create-registry))
(cdp/register-default-ports! registry adapter)

;; Execute CDP commands via ports
(defport/execute-port registry :cdp/page-navigate
  {:params {:url "https://example.com"}
   :adapter adapter})

(defport/execute-port registry :cdp/runtime-evaluate
  {:params {:expression "document.title"}
   :adapter adapter})
;; => {:result {:type "string" :value "Example Domain"}}
```

### Integration with Scout

defport's CDP adapter can be used as a **drop-in replacement** for Scout's CDP client:

```clojure
;; Scout currently uses scout.cdp directly
(require '[scout.cdp :as cdp])
(def client (cdp/create {:port 9222}))

;; With defport integration:
(require '[defport.protocols.cdp :as defport-cdp])
(def adapter (defport-cdp/create-adapter {:port 9222}))
;; Scout can expose its scraping operations as defport ports
;; which can then be accessed via MCP, LSP, or custom protocols
```

---

## Phase 2: prepl (Programmable REPL)

### Protocol Characteristics

**prepl** (introduced in Clojure 1.10):
- **Transport:** Socket-based (typically stdio or TCP)
- **Message Format:** EDN input, EDN output with structured tags
- **Communication Pattern:** Send code → receive structured results
- **Output Tags:**
  - `:tag :ret` - evaluation result
  - `:tag :out` - stdout during evaluation
  - `:tag :err` - stderr during evaluation
  - `:tag :tap` - tap> values

### prepl Protocol Adapter Design

```clojure
(ns defport.protocols.prepl
  "Programmable REPL (prepl) adapter for defport."
  (:require [defport.core :as core]
            [clojure.edn :as edn]))

(defrecord PreplAdapter [port-registry
                         config              ; {:host "localhost" :port 5555}
                         socket-conn         ; atom: socket connection
                         output-handler])    ; fn: handle prepl output

(extend-type PreplAdapter
  core/ProtocolAdapter
  (protocol-id [_] :prepl)
  (protocol-version [_] "1.10")
  (protocol-capabilities [this port-registry]
    {:eval true
     :tap true
     :namespace-management true})

  (protocol-dispatch [this method params context]
    ;; prepl has simpler dispatch: eval, tap, set-namespace
    (case method
      "eval" (handle-eval this params context)
      "tap" (handle-tap this params context)
      "set-ns" (handle-set-ns this params context))))
```

### Port Mapping

| prepl Operation | Port ID | Description |
|-----------------|---------|-------------|
| Eval expression | `:prepl/eval` | Evaluate Clojure code |
| Tap values | `:prepl/tap` | Access tap> values |
| Set namespace | `:prepl/set-ns` | Change current namespace |
| Get namespace | `:prepl/get-ns` | Get current namespace |

### Implementation Structure

```
src/defport/protocols/
├── prepl.cljc                    # prepl protocol adapter
└── prepl/
    ├── socket.cljc               # Socket connection management
    └── output.cljc               # Output parsing and handling

test/defport/protocols/
└── prepl_test.clj                # prepl adapter tests

examples/
└── prepl_remote_eval.clj         # Example: remote code evaluation
```

### Example Usage

```clojure
(require '[defport.protocols.prepl :as prepl])

;; Start prepl server (built into Clojure 1.10+)
;; clojure -J-Dclojure.server.repl="{:port 5555 :accept clojure.core.server/io-prepl}"

;; Create prepl adapter
(def adapter (prepl/create-adapter
              {:host "localhost"
               :port 5555}))

;; Execute code via port
(defport/execute-port registry :prepl/eval
  {:params {:code "(+ 1 2 3)"}
   :adapter adapter})
;; => {:result {:tag :ret :val "6" :ns "user" :ms 0 :form "(+ 1 2 3)"}}
```

---

## Phase 3: nREPL (Network REPL)

### Protocol Characteristics

**nREPL:**
- **Transport:** TCP socket with BEncode binary protocol
- **Message Format:** BEncoded dictionaries `{"op" "eval" "code" "(+ 1 2)"}`
- **Communication Pattern:** Bidirectional with sessions
- **Middleware System:** Extensible via middleware chain
- **Session Management:** Multi-session support with IDs

### nREPL Protocol Adapter Design

```clojure
(ns defport.protocols.nrepl
  "nREPL (Network REPL) adapter for defport."
  (:require [defport.core :as core]
            [nrepl.bencode :as bencode]))

(defrecord NREPLAdapter [port-registry
                         config              ; {:host "localhost" :port 7888}
                         socket-conn         ; atom: socket connection
                         sessions            ; atom: {session-id -> state}
                         msg-id              ; atom: message counter
                         middleware])        ; vector: middleware chain

(extend-type NREPLAdapter
  core/ProtocolAdapter
  (protocol-id [_] :nrepl)
  (protocol-version [_] "1.1.0")
  (protocol-capabilities [this port-registry]
    {:ops (compute-available-ops port-registry)
     :versions {:nrepl "1.1.0" :clojure "*clojure-version*"}
     :session true
     :interrupt true})

  (protocol-dispatch [this method params context]
    ;; nREPL uses "op" field instead of "method"
    (dispatch-nrepl-op this (:op params) params context)))
```

### Port Mapping

| nREPL Op | Port ID | Description |
|----------|---------|-------------|
| `eval` | `:nrepl/eval` | Evaluate code in session |
| `load-file` | `:nrepl/load-file` | Load and eval file |
| `interrupt` | `:nrepl/interrupt` | Interrupt evaluation |
| `complete` | `:nrepl/complete` | Code completion |
| `info` | `:nrepl/info` | Symbol info lookup |
| `clone` | `:nrepl/clone-session` | Clone session |
| `close` | `:nrepl/close-session` | Close session |
| `describe` | `:nrepl/describe` | Server capabilities |

### Implementation Structure

```
src/defport/protocols/
├── nrepl.cljc                    # nREPL protocol adapter
└── nrepl/
    ├── bencode.cljc              # BEncode encoding/decoding
    ├── session.cljc              # Session management
    ├── middleware.cljc           # Middleware chain
    └── ops.cljc                  # nREPL operation handlers

test/defport/protocols/
└── nrepl_test.clj                # nREPL adapter tests

examples/
└── nrepl_ide_integration.clj     # Example: IDE connection via nREPL
```

### Example Usage

```clojure
(require '[defport.protocols.nrepl :as nrepl])

;; Start nREPL server
;; clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.1.0"}}}' -m nrepl.cmdline

;; Create nREPL adapter
(def adapter (nrepl/create-adapter
              {:host "localhost"
               :port 7888
               :middleware [:session :eval :interrupt :complete :info]}))

;; Execute operations via ports
(defport/execute-port registry :nrepl/eval
  {:params {:op "eval" :code "(+ 1 2 3)" :session "session-123"}
   :adapter adapter})
;; => {:result {:value "6" :ns "user" :session "session-123" :status ["done"]}}
```

---

## Cross-Protocol Features

### Universal Ports That Work Across Protocols

Some ports can be exposed via multiple protocols:

| Port ID | MCP | CDP | prepl | nREPL | LSP |
|---------|-----|-----|-------|-------|-----|
| `:code/eval` | ✅ | ✅ | ✅ | ✅ | ❌ |
| `:code/navigate` | ✅ | ✅ | ❌ | ❌ | ✅ |
| `:code/complete` | ✅ | ❌ | ❌ | ✅ | ✅ |
| `:browser/screenshot` | ✅ | ✅ | ❌ | ❌ | ❌ |

**Example:** A single `:code/eval` port that works via MCP tools, CDP Runtime.evaluate, prepl eval, and nREPL eval op:

```clojure
(defport/register-port! registry
  {:id :code/eval
   :description "Evaluate code expression"
   :input-schema {:type "object"
                  :properties {:code {:type "string"}}
                  :required [:code]}
   :handler (fn [context]
              (let [code (get-in context [:params :code])
                    protocol (get context :protocol)]
                (case protocol
                  :mcp (eval-via-mcp code context)
                  :cdp (eval-via-cdp code context)
                  :prepl (eval-via-prepl code context)
                  :nrepl (eval-via-nrepl code context))))
   :metadata {:protocols #{:mcp :cdp :prepl :nrepl}}})
```

---

## Testing Strategy

### Per-Protocol Tests

Each protocol adapter needs:

1. **Connection Tests**
   - Establish connection
   - Handle connection failures
   - Reconnection logic

2. **Message Format Tests**
   - Encode/decode messages correctly
   - Handle malformed messages
   - Buffer management (fragmented messages)

3. **Command/Operation Tests**
   - Execute basic commands
   - Handle responses
   - Error handling

4. **Port Integration Tests**
   - Port registration
   - Port discovery from protocol capabilities
   - Port execution via protocol dispatch

5. **State Management Tests**
   - Session management (nREPL)
   - Event subscriptions (CDP)
   - Output buffering (prepl)

### Cross-Protocol Integration Tests

Test that ports work correctly across multiple protocols:

```clojure
(deftest cross-protocol-eval-test
  (testing "Universal eval port works via CDP, prepl, and nREPL"
    (let [registry (create-test-registry)
          cdp-adapter (cdp/create-adapter {...})
          prepl-adapter (prepl/create-adapter {...})
          nrepl-adapter (nrepl/create-adapter {...})]

      ;; Register universal eval port
      (register-universal-eval-port! registry)

      ;; Test via CDP
      (is (= "6" (eval-via-protocol registry cdp-adapter "(+ 1 2 3)")))

      ;; Test via prepl
      (is (= "6" (eval-via-protocol registry prepl-adapter "(+ 1 2 3)")))

      ;; Test via nREPL
      (is (= "6" (eval-via-protocol registry nrepl-adapter "(+ 1 2 3)"))))))
```

---

## Implementation Timeline

### Week 1-2: CDP Foundation
- [ ] CDP connection management (HTTP discovery + WebSocket)
- [ ] Basic command/response mechanism
- [ ] Page and Runtime domains
- [ ] Initial port registry integration
- [ ] Basic tests

### Week 3: CDP Advanced Features
- [ ] Network and DOM domains
- [ ] Event handling system
- [ ] Multi-target support
- [ ] Screenshot and console capture
- [ ] Comprehensive tests

### Week 4: prepl Implementation
- [ ] Socket connection management
- [ ] EDN input/output handling
- [ ] Tag-based output routing
- [ ] Port integration
- [ ] Tests and examples

### Week 5-6: nREPL Implementation
- [ ] BEncode implementation or dependency
- [ ] Socket connection with BEncode transport
- [ ] Session management
- [ ] Core ops (eval, load-file, interrupt)
- [ ] Middleware system
- [ ] CIDER integration ops (complete, info, etc.)
- [ ] Comprehensive tests

### Week 7: Cross-Protocol Integration
- [ ] Universal port patterns
- [ ] Cross-protocol tests
- [ ] Documentation
- [ ] Examples for each protocol
- [ ] Performance optimization

---

## Dependencies

### CDP
- `cheshire` - JSON encoding/decoding (already in deps.edn)
- Java 11+ `java.net.http` - WebSocket client (built-in)

### prepl
- `clojure.edn` - EDN parsing (built-in)
- Socket I/O (built-in)

### nREPL
- `nrepl/bencode` - BEncode implementation
- OR implement bencode in defport (lightweight)
- Socket I/O (built-in)

### Transport Layer
- May need WebSocket transport abstraction for CDP
- TCP socket transport for prepl/nREPL

---

## Open Questions

1. **CDP Multi-Client Support:** Should defport CDP adapter support multiple concurrent browser connections?

2. **prepl vs Socket REPL:** Should we also support plain socket REPL (simpler, no tags)?

3. **nREPL Middleware:** Should defport implement nREPL middleware from scratch or wrap existing nREPL server?

4. **LSP Integration:** You mentioned clojure-lsp. Should we implement full LSP protocol or just integrate with existing clojure-lsp as a client?

5. **Protocol Priority:** CDP → prepl → nREPL is current order. Does this match your priorities?

---

## Success Criteria

Each protocol adapter is considered complete when:

1. ✅ Implements `ProtocolAdapter` protocol
2. ✅ Establishes connection and handles lifecycle
3. ✅ Encodes/decodes protocol messages correctly
4. ✅ Routes protocol methods to ports
5. ✅ Handles errors and edge cases
6. ✅ Has comprehensive tests (>80% coverage)
7. ✅ Has working examples
8. ✅ Is documented in CLAUDE.md and README.md
9. ✅ Integrates with existing defport infrastructure (registry, DSL, etc.)

---

## Notes from Scout CDP Implementation

**Key Patterns to Reuse:**
1. **WebSocket Listener Pattern** - Scout's `create-listener` with `onText`, `onError`, `onClose`
2. **Text Buffer for Fragmented Messages** - CDP can send multi-part text messages
3. **Response Tracking by ID** - Map of request ID → response
4. **Inflight Request Tracking** - For network idle detection
5. **Lifecycle Event Tracking** - Page lifecycle (DOMContentLoaded, load, etc.)

**Improvements for defport:**
1. **Port-Based Architecture** - Scout directly implements CDP commands; defport exposes them as ports
2. **Protocol Agnostic** - Same ports can work via MCP or CDP
3. **Better Error Handling** - Use defport's structured error format
4. **Event System** - More sophisticated event subscription/handler system
5. **Multi-Target Support** - Scout connects to one page; defport could manage multiple targets

---

## References

- **CDP Spec:** https://chromedevtools.github.io/devtools-protocol/
- **prepl Docs:** https://clojure.org/reference/repl_and_main#_launching_a_socket_server
- **nREPL Spec:** https://nrepl.org/nrepl/index.html
- **Scout CDP Implementation:** `C:\Users\Apollo\CascadeProjects\scout\src\scout\cdp.clj`
- **defport MCP Implementation:** [src/defport/protocols/mcp.cljc](src/defport/protocols/mcp.cljc)
