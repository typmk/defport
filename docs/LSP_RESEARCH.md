# LSP Implementation Research for Defport

## Executive Summary

This document explores the spectrum of LSP implementation options for defport, from minimal linter extensions to full language server implementations. The goal is to design `lsp.cljc` that fits defport's philosophy: a library, not a framework.

---

## 1. Protocol Comparison: MCP vs LSP

| Aspect | MCP (Model Context Protocol) | LSP (Language Server Protocol) |
|--------|------------------------------|--------------------------------|
| **Purpose** | AI model ↔ tools/data | Editor ↔ language analysis |
| **Transport** | JSON-RPC over stdio/HTTP | JSON-RPC over stdio/socket |
| **Lifecycle** | initialize → tools/call | initialize → textDocument/* |
| **State Model** | Stateless (per-request) | Stateful (document sync) |
| **Primary Consumer** | AI assistants (Claude, Cursor) | IDEs (VSCode, Emacs, Vim) |
| **Capability Focus** | Tools, Resources, Prompts | Completion, Diagnostics, Navigation |

**Key Insight**: Both protocols use JSON-RPC 2.0 and capability negotiation. Defport's `ProtocolAdapter` abstraction can support both.

---

## 2. Implementation Spectrum

### Level 0: Linter Extension (Minimal)
**Description**: Extend existing LSP servers (clojure-lsp, pyright) with custom diagnostics.

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│   Editor    │────▶│ clojure-lsp  │────▶│   defport   │
│  (Client)   │◀────│  (Server)    │◀────│  (Linter)   │
└─────────────┘     └──────────────┘     └─────────────┘
```

**Implementation**:
- Defport provides custom linter rules via clojure-lsp's extension mechanism
- Rules shipped as `.clj` files in `clojure-lsp.exports/`
- No direct LSP protocol handling

**Pros**: Zero protocol code, leverage existing infrastructure
**Cons**: Limited to supported LSP servers, no control over protocol

**Use Case**: Add defnet-specific diagnostics to clojure-lsp

---

### Level 1: LSP Client (Proxy/Bridge)
**Description**: Defport acts as LSP client, connecting to external language servers.

```
┌─────────────┐     ┌─────────────┐     ┌──────────────┐
│  MCP Client │────▶│   defport   │────▶│  pyright     │
│  (Claude)   │◀────│  (Bridge)   │◀────│  (LSP)       │
└─────────────┘     │             │     └──────────────┘
                    │             │     ┌──────────────┐
                    │             │────▶│  rust-analyzer│
                    │             │◀────│  (LSP)       │
                    └─────────────┘     └──────────────┘
```

**Implementation**:
- `LspClient` protocol for connecting to external LSP servers
- Translate LSP responses to MCP tool responses
- Aggregate results from multiple language servers

**Pros**: Language-agnostic, leverage mature LSP implementations
**Cons**: Latency (extra hop), dependency on external servers

**Use Case**: AI assistant accesses Python analysis via pyright

---

### Level 2: LSP Server (Subset)
**Description**: Defport implements select LSP methods, exposing ports as LSP features.

```
┌─────────────┐     ┌─────────────┐
│   Editor    │────▶│   defport   │
│  (VSCode)   │◀────│  (LSP)      │
└─────────────┘     └─────────────┘
```

**Implemented Methods**:
- `textDocument/definition` → find-definition port
- `textDocument/references` → find-usages port
- `workspace/symbol` → search-code port
- `textDocument/publishDiagnostics` → linter port

**Pros**: Direct editor integration, full control
**Cons**: Limited features, must implement document sync

**Use Case**: Expose defnet analysis tools directly to editors

---

### Level 3: LSP Server (Full)
**Description**: Complete LSP implementation with all standard capabilities.

**Implemented Capabilities**:
- Document synchronization (full & incremental)
- Completion with resolve
- Hover documentation
- Go to definition/implementation/type definition
- Find references
- Workspace symbols
- Code actions and quick fixes
- Formatting (document, range, on-type)
- Rename with preview
- Call hierarchy
- Semantic tokens
- Inlay hints
- Code lens

**Pros**: Full editor feature parity
**Cons**: Significant implementation effort, language-specific logic

**Use Case**: Build new language server (e.g., for EDN, Datalog)

---

### Level 4: Multi-Protocol Hub
**Description**: Unified hub exposing same ports via MCP, LSP, DAP simultaneously.

```
┌─────────────┐
│   Claude    │──MCP──┐
└─────────────┘       │     ┌─────────────┐
                      │────▶│   defport   │
┌─────────────┐       │     │    Hub      │
│   VSCode    │──LSP──┤     └─────────────┘
└─────────────┘       │           │
                      │     ┌─────────────┐
┌─────────────┐       │     │    Ports    │
│  Debugger   │──DAP──┘     │  Registry   │
└─────────────┘             └─────────────┘
```

**Pros**: Single codebase, consistent behavior across protocols
**Cons**: Complex state management, protocol impedance mismatch

**Use Case**: Defnet exposes tools to both AI and editors

---

## 3. Architectural Analysis

### 3.1 State Management Challenge

MCP is largely **stateless** (each tool call is independent).
LSP is **stateful** (tracks open documents, workspace state).

```clojure
;; MCP: Stateless
(defn handle-tools-call [params]
  (let [result (execute-tool params)]
    {:content [{:type "text" :text (json/encode result)}]}))

;; LSP: Stateful - must track documents
(defn handle-did-open [params]
  (swap! documents assoc uri content)
  nil)  ; notification, no response

(defn handle-completion [params]
  (let [doc (get @documents uri)
        items (compute-completions doc position)]
    {:items items}))
```

**Solution**: LSP adapter maintains document state; ports receive document content in context.

### 3.2 Capability Mapping

| MCP Concept | LSP Equivalent |
|-------------|----------------|
| Tool | Code Action, Command |
| Resource | Custom request, Extension |
| Prompt | Snippet, Code Action template |
| Progress | Work Done Progress |
| Logging | window/logMessage |

### 3.3 Method Routing

LSP has ~50 standard methods vs MCP's ~15. Group by concern:

```clojure
(def lsp-method-groups
  {:lifecycle     ["initialize" "initialized" "shutdown" "exit"]
   :document-sync ["textDocument/didOpen" "textDocument/didChange"
                   "textDocument/didClose" "textDocument/didSave"]
   :language      ["textDocument/completion" "textDocument/hover"
                   "textDocument/definition" "textDocument/references"
                   "textDocument/rename" "textDocument/codeAction"]
   :workspace     ["workspace/symbol" "workspace/executeCommand"
                   "workspace/applyEdit" "workspace/configuration"]
   :window        ["window/showMessage" "window/logMessage"
                   "window/showDocument" "window/workDoneProgress/create"]})
```

---

## 4. Clojure-LSP Deep Dive

### 4.1 Architecture Patterns

**Static Analysis Approach**:
- Analyzes code without execution (safe for refactoring)
- Uses clj-kondo for parsing and linting
- Maintains in-memory database of project structure

**Key Components**:
```
clojure-lsp/
├── lib/                    # Core library
│   └── src/clojure_lsp/
│       ├── handlers.clj    # LSP method dispatch
│       ├── feature/        # Language features
│       ├── refactor/       # Code transformations
│       ├── db.clj          # Project knowledge base
│       └── kondo.clj       # clj-kondo integration
├── cli/                    # Standalone executable
└── server/                 # LSP server wrapper
```

### 4.2 Extension Mechanisms

**Custom Linters** (Level 0 integration point):
```clojure
;; .lsp/config.edn
{:linters
 {:custom
  {:my-org.my-linter/check
   {:level :warning}}}}

;; clojure-lsp.exports/linters/my_org/my_linter.clj
(ns my-org.my-linter
  (:require [clojure-lsp.linter-api :as api]))

(defn lint [analysis]
  (for [var (:var-definitions analysis)
        :when (suspicious? var)]
    {:message "Suspicious pattern"
     :range (:range var)
     :severity :warning}))
```

**Classpath Config Export**:
Libraries can ship configuration in JAR:
```
resources/clojure-lsp.exports/<group>/<artifact>/config.edn
```

### 4.3 Distribution Model

Clojure-lsp demonstrates a multi-interface distribution pattern:

| Interface | Use Case | Integration Point |
|-----------|----------|-------------------|
| **JVM API** | REPL, libraries | `clojure-lsp.api` namespace |
| **CLI** | CI/CD, scripts | Standalone executable |
| **Lein Plugin** | Leiningen projects | `lein-clojure-lsp` |
| **Babashka Pod** | bb scripts | Pod protocol |
| **LSP Server** | Editors | stdio/socket |

**Key Insight**: Same core functionality exposed via 5 different interfaces. Defport should follow this pattern:

```clojure
;; Core API (library)
(require '[defport.protocols.lsp.api :as lsp-api])
(lsp-api/find-definition {:file "src/foo.clj" :line 10 :column 5})

;; CLI
;; $ defport lsp definition src/foo.clj:10:5

;; MCP Tool (for AI)
;; tools/call: lsp/definition {file: "src/foo.clj", line: 10, column: 5}

;; LSP Server (for editors)
;; textDocument/definition {uri: "file:///src/foo.clj", position: {line: 10, character: 5}}
```

### 4.4 Reusable Patterns

1. **Separation of concerns**: handlers → features → analysis
2. **Database-driven**: All queries against in-memory DB
3. **Incremental updates**: Document changes update DB incrementally
4. **GraalVM-ready**: Native compilation for fast startup
5. **Multi-interface**: Same core via API, CLI, LSP, MCP

---

## 5. Language-Agnostic Design

### 5.1 Multi-Language Server Client

Defport can connect to ANY LSP server as a client:

```clojure
(def language-servers
  {:python    {:command ["pyright-langserver" "--stdio"]}
   :rust      {:command ["rust-analyzer"]}
   :go        {:command ["gopls"]}
   :java      {:command ["jdtls"]}
   :clojure   {:command ["clojure-lsp"]}
   :typescript {:command ["typescript-language-server" "--stdio"]}})

(defn create-lsp-client [language]
  (let [config (get language-servers language)]
    (->LspClient config)))
```

### 5.2 Unified Query Interface

```clojure
;; Same port works for any language
(defn find-definition [{:keys [file line column language]}]
  (let [client (get-or-create-client language)]
    (lsp-request client "textDocument/definition"
      {:textDocument {:uri (file->uri file)}
       :position {:line line :character column}})))

;; Exposed as MCP tool
{:id :find-definition
 :description "Find definition across any supported language"
 :input-schema {:type "object"
                :properties {:file {:type "string"}
                             :line {:type "integer"}
                             :column {:type "integer"}
                             :language {:type "string"
                                       :enum ["python" "rust" "go" "clojure"]}}}
 :handler find-definition}
```

### 5.3 Result Normalization

Different LSP servers return slightly different structures. Normalize:

```clojure
(defn normalize-location [lsp-location]
  {:file (uri->file (:uri lsp-location))
   :line (get-in lsp-location [:range :start :line])
   :column (get-in lsp-location [:range :start :character])})

(defn normalize-diagnostic [lsp-diagnostic]
  {:message (:message lsp-diagnostic)
   :severity (case (:severity lsp-diagnostic)
               1 :error
               2 :warning
               3 :info
               4 :hint)
   :range (normalize-range (:range lsp-diagnostic))})
```

---

## 6. Proposed Architecture for lsp.cljc

### 6.1 Module Structure

```
src/defport/protocols/
├── mcp.cljc           # Existing MCP adapter
├── lsp/
│   ├── core.cljc      # LSP adapter (ProtocolAdapter impl)
│   ├── client.cljc    # LSP client for connecting to servers
│   ├── server.cljc    # LSP server capabilities
│   ├── document.cljc  # Document synchronization state
│   ├── methods.cljc   # Method handlers by category
│   ├── capabilities.cljc  # Capability negotiation
│   └── types.cljc     # LSP type definitions
└── dap.cljc           # Future: Debug Adapter Protocol
```

### 6.2 Core Protocols

```clojure
(ns defport.protocols.lsp.core
  (:require [defport.core :as core]))

;; LSP-specific state management
(defprotocol DocumentStore
  (open-document [this uri content version])
  (update-document [this uri changes version])
  (close-document [this uri])
  (get-document [this uri]))

;; LSP client for connecting to external servers
(defprotocol LspClient
  (client-initialize [this root-uri capabilities])
  (client-request [this method params])
  (client-notify [this method params])
  (client-shutdown [this]))

;; LSP server adapter (implements ProtocolAdapter)
(defrecord LspAdapter [server-info capabilities document-store]
  core/ProtocolAdapter
  (protocol-id [_] :lsp)
  (protocol-version [_] "3.17")
  (protocol-capabilities [_ registry]
    (compute-server-capabilities registry capabilities))
  (protocol-dispatch [this method params context]
    (dispatch-lsp-method this method params context)))
```

### 6.3 Document State Management

```clojure
(ns defport.protocols.lsp.document)

(defrecord InMemoryDocumentStore [documents*]
  DocumentStore
  (open-document [_ uri content version]
    (swap! documents* assoc uri {:content content
                                  :version version
                                  :uri uri}))
  (update-document [_ uri changes version]
    (swap! documents* update uri
           (fn [doc]
             (-> doc
                 (assoc :version version)
                 (update :content apply-changes changes)))))
  (close-document [_ uri]
    (swap! documents* dissoc uri))
  (get-document [_ uri]
    (get @documents* uri)))

(defn create-document-store []
  (->InMemoryDocumentStore (atom {})))
```

### 6.4 Method Dispatch

```clojure
(ns defport.protocols.lsp.methods
  (:require [defport.protocols.lsp.core :as lsp]
            [defport.protocols.lsp.document :as doc]))

(defmulti handle-method (fn [adapter method params context] method))

;; Lifecycle
(defmethod handle-method "initialize" [adapter _ params context]
  (let [{:keys [capabilities rootUri]} params
        server-caps (lsp/compute-capabilities adapter context)]
    {:capabilities server-caps
     :serverInfo (:server-info adapter)}))

(defmethod handle-method "initialized" [_ _ _ _]
  nil) ; notification

(defmethod handle-method "shutdown" [adapter _ _ _]
  (lsp/shutdown adapter)
  nil)

;; Document sync
(defmethod handle-method "textDocument/didOpen" [adapter _ params _]
  (let [{:keys [textDocument]} params
        {:keys [uri languageId version text]} textDocument]
    (doc/open-document (:document-store adapter) uri text version))
  nil)

(defmethod handle-method "textDocument/didChange" [adapter _ params _]
  (let [{:keys [textDocument contentChanges]} params]
    (doc/update-document (:document-store adapter)
                         (:uri textDocument)
                         contentChanges
                         (:version textDocument)))
  nil)

;; Language features - delegate to ports
(defmethod handle-method "textDocument/definition" [adapter _ params context]
  (let [{:keys [textDocument position]} params
        doc (doc/get-document (:document-store adapter) (:uri textDocument))
        port (core/get-port (:port-registry context) :find-definition)]
    (when port
      (let [result (core/port-execute port
                     {:params {:file (uri->file (:uri textDocument))
                              :line (:line position)
                              :column (:character position)
                              :content (:content doc)}
                      :context context})]
        (result->lsp-location result)))))

;; Workspace
(defmethod handle-method "workspace/symbol" [adapter _ params context]
  (let [{:keys [query]} params
        port (core/get-port (:port-registry context) :search-code)]
    (when port
      (let [results (core/port-execute port
                      {:params {:query query}
                       :context context})]
        (results->workspace-symbols results)))))
```

### 6.5 LSP Client Implementation

```clojure
(ns defport.protocols.lsp.client
  (:require [defport.transports.stdio :as stdio]
            [cheshire.core :as json]))

(defrecord StdioLspClient [process in out request-id* pending*]
  LspClient
  (client-initialize [this root-uri capabilities]
    (client-request this "initialize"
      {:rootUri root-uri
       :capabilities capabilities}))

  (client-request [this method params]
    (let [id (swap! request-id* inc)
          request {:jsonrpc "2.0"
                   :id id
                   :method method
                   :params params}
          response-promise (promise)]
      (swap! pending* assoc id response-promise)
      (send-message out request)
      (deref response-promise 30000 {:error "timeout"})))

  (client-notify [this method params]
    (send-message out {:jsonrpc "2.0"
                       :method method
                       :params params}))

  (client-shutdown [this]
    (client-request this "shutdown" nil)
    (client-notify this "exit" nil)
    (.destroy process)))

(defn create-lsp-client [{:keys [command]}]
  (let [process (start-process command)
        in (io/reader (.getInputStream process))
        out (io/writer (.getOutputStream process))]
    (->StdioLspClient process in out (atom 0) (atom {}))))
```

### 6.6 Capability Configuration

```clojure
(ns defport.protocols.lsp.capabilities)

(def default-server-capabilities
  {:textDocumentSync {:openClose true
                      :change 2  ; incremental
                      :save {:includeText false}}
   :completionProvider {:triggerCharacters ["." "/" ":"]}
   :hoverProvider true
   :definitionProvider true
   :referencesProvider true
   :documentSymbolProvider true
   :workspaceSymbolProvider true
   :codeActionProvider true
   :renameProvider {:prepareProvider true}})

(defn compute-server-capabilities [registry user-capabilities]
  (let [ports (core/list-ports registry)
        port-ids (set (map :id ports))]
    (cond-> default-server-capabilities
      (not (port-ids :completion))
      (dissoc :completionProvider)

      (not (port-ids :hover))
      (dissoc :hoverProvider)

      (not (port-ids :find-definition))
      (dissoc :definitionProvider)

      ;; Add user overrides
      true
      (merge user-capabilities))))
```

---

## 7. Implementation Roadmap

### Phase 1: LSP Client (Level 1)
**Goal**: Connect to external LSP servers, expose via MCP

1. Implement `LspClient` protocol
2. Add stdio transport for LSP client
3. Create `lsp-bridge` MCP tool
4. Test with pyright, rust-analyzer

**Deliverables**:
- `src/defport/protocols/lsp/client.cljc`
- MCP tools: `lsp/definition`, `lsp/references`, `lsp/hover`

### Phase 2: LSP Server Subset (Level 2)
**Goal**: Expose defport ports as LSP methods

1. Implement `LspAdapter` (ProtocolAdapter)
2. Add document state management
3. Map ports to LSP methods
4. Implement initialize/shutdown lifecycle

**Deliverables**:
- `src/defport/protocols/lsp/core.cljc`
- `src/defport/protocols/lsp/document.cljc`
- `src/defport/protocols/lsp/methods.cljc`

### Phase 3: Full LSP Server (Level 3)
**Goal**: Complete LSP implementation

1. Incremental document sync
2. Completion with resolve
3. Code actions and quick fixes
4. Semantic tokens
5. Progress reporting

**Deliverables**:
- Full LSP 3.17 compliance
- Test suite against VSCode, Neovim

### Phase 4: Multi-Protocol Hub (Level 4)
**Goal**: Unified port exposure

1. Shared port registry
2. Protocol-specific formatters
3. State synchronization
4. Unified configuration

---

## 8. Example Usage

### 8.1 LSP Client (Bridge to External Servers)

```clojure
(ns my-app
  (:require [defport :as mcp]
            [defport.protocols.lsp.client :as lsp-client]))

;; Create LSP clients for multiple languages
(def clients
  {:python (lsp-client/create {:command ["pyright-langserver" "--stdio"]})
   :rust (lsp-client/create {:command ["rust-analyzer"]})})

;; MCP tool that uses LSP client
(mcp/deftool find-definition
  "Find definition in any supported language"
  [file :- :string
   line :- :int
   column :- :int]
  (let [lang (detect-language file)
        client (get clients lang)]
    (lsp-client/request client "textDocument/definition"
      {:textDocument {:uri (str "file://" file)}
       :position {:line line :character column}})))

(mcp/start! {:name "multi-lang-server" :version "1.0.0"})
```

### 8.2 LSP Server (Expose Ports to Editors)

```clojure
(ns my-lsp-server
  (:require [defport.protocols.lsp.core :as lsp]
            [defport.registry :as registry]
            [defport.transports.stdio :as stdio]))

;; Create registry with ports
(def my-registry (registry/create-function-registry))

(registry/register-port! my-registry
  {:id :find-definition
   :handler (fn [{:keys [params]}]
              (find-def (:file params) (:line params)))})

;; Create LSP adapter
(def adapter (lsp/create-lsp-adapter
  {:server-info {:name "my-server" :version "1.0.0"}}))

;; Start LSP server
(def transport (stdio/create-stdio-transport))

(stdio/start transport
  (fn [request]
    (lsp/protocol-dispatch adapter (:method request) (:params request)
      {:port-registry my-registry})))
```

### 8.3 Dual Protocol (MCP + LSP)

```clojure
(ns dual-protocol-server
  (:require [defport :as mcp]
            [defport.protocols.lsp.core :as lsp]
            [defport.transports.stdio :as stdio]
            [defport.transports.http :as http]))

;; Shared port registry
(def registry (mcp/create-registry))

;; Register ports once
(mcp/deftool search-code
  "Search for code patterns"
  [query :- :string]
  (search-codebase query))

;; Create adapters
(def mcp-adapter (mcp/create-adapter {:name "dual-server"}))
(def lsp-adapter (lsp/create-adapter {:name "dual-server"}))

;; Start both protocols
;; MCP over HTTP (port 8080)
(http/start {:port 8080}
  (fn [req] (mcp/dispatch mcp-adapter req {:registry registry})))

;; LSP over stdio
(stdio/start
  (fn [req] (lsp/dispatch lsp-adapter req {:registry registry})))
```

---

## 9. Comparison with Alternatives

| Approach | Effort | Flexibility | Use Case |
|----------|--------|-------------|----------|
| Linter Extension (L0) | Low | Limited | Add rules to existing LSP |
| LSP Client (L1) | Medium | High | Language-agnostic AI bridge |
| LSP Server Subset (L2) | Medium | Medium | Expose specific features |
| Full LSP Server (L3) | High | Full | New language support |
| Multi-Protocol Hub (L4) | High | Maximum | Unified tooling platform |

---

## 10. Recommendations

### For Defport Core
**Implement Level 1 (LSP Client) first**:
- Provides immediate value (AI can access any language)
- Validates architecture before bigger investment
- Reuses existing, mature LSP servers

### For Defnet Integration
**Start with Level 0 (Linter Extension)**:
- Ship defnet-specific diagnostics via clojure-lsp
- Minimal code, maximum compatibility

**Then add Level 2 (LSP Server Subset)**:
- Expose defnet tools directly to editors
- `find-callers`, `search-code` as LSP methods

### For New Languages
**Consider Level 3 (Full LSP Server)** if:
- No good LSP server exists
- Need custom analysis (EDN, Datalog)
- Want tight integration with defport features

---

## 11. Open Questions

1. **State Synchronization**: How to keep document state consistent between MCP and LSP when both protocols are active?

2. **Error Mapping**: How to map defport errors to LSP error codes consistently?

3. **Progress Unification**: Can MCP and LSP progress notifications share implementation?

4. **Testing Strategy**: How to test LSP compliance? Use existing test suites?

5. **Platform Parity**: Which features should be .cljc (JVM + CLJS) vs .clj only?

---

## References

- [LSP Specification 3.17](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/)
- [Microsoft LSP Repository](https://github.com/microsoft/language-server-protocol)
- [Clojure-LSP](https://github.com/clojure-lsp/clojure-lsp)
- [Clojure-LSP Features](https://clojure-lsp.io/features/)
- [LSP4J](https://github.com/eclipse-lsp4j/lsp4j)
- [Langserver.org](https://langserver.org/)
- [Defport Architecture](../docs/ARCHITECTURE.md)