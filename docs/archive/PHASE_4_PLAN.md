# Phase 4 Implementation Plan

**Status:** 🎯 **READY TO START**
**Phase:** 4 - Optional MCP Features
**Current Completion:** Phase 3 Complete (100% core MCP 2025-06-18 compliant)
**Date:** January 12, 2025
**Estimated Duration:** 7-8 days total

---

## Overview

Phase 4 implements **optional MCP 2025-06-18 specification features** that enhance the protocol but are not required for basic compliance. These features enable:

1. **Rich media content** (images, audio)
2. **LLM integration** (sampling)
3. **Filesystem safety** (roots)

All features are **low priority** but provide valuable capabilities for advanced use cases.

---

## Current Status

✅ **Phase 3 Complete:**
- 61 tests, 331 assertions, 0 failures
- 100% MCP 2025-06-18 core spec compliant
- Malli schemas, Builder API, Elicitation, Completions, logging/setLevel all working

📊 **Missing Optional Features:**
- ❌ ImageContent (Base64 image support)
- ❌ AudioContent (Base64 audio support)
- ❌ Sampling (LLM coordination)
- ❌ Roots (Filesystem boundaries)

---

## Phase 4 Features

### 4.1 ImageContent Support (1 day) 🖼️

**Priority:** Low
**Complexity:** Low
**Effort:** 1 day
**Dependencies:** None

#### What is ImageContent?

Allows tools to return images (diagrams, charts, screenshots) as part of their response using Base64 encoding.

#### MCP Spec Format

```json
{
  "type": "image",
  "data": "base64-encoded-image-data",
  "mimeType": "image/png"
}
```

#### Implementation Tasks

1. **Add ImageContent helper to `src/defport/util/content.cljc`**
   ```clojure
   (ns defport.util.content
     "Content type utilities for MCP responses.")

   (defn base64-encode
     "Encode binary data to Base64 string.
     Platform-specific implementation."
     [data]
     #?(:clj (-> (java.util.Base64/getEncoder)
                 (.encodeToString data))
        :cljs (.toString (.from js/Buffer data) "base64")))

   (defn base64-decode
     "Decode Base64 string to binary data."
     [base64-str]
     #?(:clj (-> (java.util.Base64/getDecoder)
                 (.decode base64-str))
        :cljs (-> js/Buffer
                  (.from base64-str "base64"))))

   (defn image-content
     "Create ImageContent for MCP response.

     Args:
       data - Binary image data (byte array)
       mime-type - MIME type (e.g., 'image/png', 'image/jpeg')

     Returns:
       ImageContent map

     Example:
       (image-content png-bytes \"image/png\")"
     [data mime-type]
     {:type "image"
      :data (base64-encode data)
      :mimeType mime-type})

   (defn load-image-file
     "Load image from file and create ImageContent.

     Example:
       (load-image-file \"diagram.png\")"
     [file-path]
     #?(:clj (let [bytes (-> file-path
                             java.io.File.
                             slurp
                             .getBytes)]
               (image-content bytes
                             (guess-mime-type file-path)))
        :cljs (throw (ex-info "Not implemented for Node.js yet"
                              {:file-path file-path}))))

   (defn guess-mime-type
     "Guess MIME type from file extension."
     [file-path]
     (let [ext (-> file-path
                   (clojure.string/split #"\.")
                   last
                   clojure.string/lower-case)]
       (case ext
         "png" "image/png"
         "jpg" "image/jpeg"
         "jpeg" "image/jpeg"
         "gif" "image/gif"
         "webp" "image/webp"
         "svg" "image/svg+xml"
         "application/octet-stream")))
   ```

2. **Update DSL to support image returns**
   ```clojure
   ;; In src/defport/dsl.cljc
   (require '[defport.util.content :as content])

   ;; Example tool returning image
   (mcp/deftool generate-diagram
     "Generate architecture diagram"
     [component :- :string]
     (let [png-data (create-diagram component)]
       {:content [(content/image-content png-data "image/png")]}))
   ```

3. **Update format-content in `src/defport/protocols/mcp.cljc`**
   ```clojure
   (defn format-content
     "Format result into MCP content array.
     Handles text, images, and structured data."
     [result]
     (cond
       ;; Tool returns content array directly
       (and (map? result) (contains? result :content))
       (:content result)

       ;; Image content (has :type "image")
       (and (map? result) (= "image" (:type result)))
       [result]

       ;; ... existing cases
       ))
   ```

4. **Add tests**
   ```clojure
   ;; test/defport/util/content_test.clj
   (deftest image-content-test
     (testing "base64 encoding"
       (let [data (.getBytes "test")]
         (is (string? (content/base64-encode data)))))

     (testing "image content creation"
       (let [ic (content/image-content (.getBytes "fake-png") "image/png")]
         (is (= "image" (:type ic)))
         (is (= "image/png" (:mimeType ic)))
         (is (string? (:data ic)))))

     (testing "MIME type guessing"
       (is (= "image/png" (content/guess-mime-type "test.png")))
       (is (= "image/jpeg" (content/guess-mime-type "test.jpg")))))
   ```

5. **Add example**
   ```clojure
   ;; examples/image_content_example.clj
   (ns image-content-example
     (:require [defport.dsl :as mcp]
               [defport.util.content :as content]))

   ;; Example 1: Generate diagram
   (mcp/deftool generate-architecture-diagram
     "Generate system architecture diagram"
     [components :- :array]
     (let [diagram-png (generate-diagram-png components)]
       {:content [(content/image-content diagram-png "image/png")]}))

   ;; Example 2: Screenshot tool
   (mcp/deftool take-screenshot
     "Capture screenshot of window"
     [window-name :- :string]
     (let [screenshot (capture-window window-name)]
       {:content [(content/image-content screenshot "image/png")]}))

   ;; Example 3: Chart generation
   (mcp/deftool create-chart
     "Create data visualization chart"
     [data :- :array
      chart-type :- :string]
     (let [chart-image (render-chart data chart-type)]
       {:content [(content/image-content chart-image "image/png")]}))
   ```

#### Files to Create
- `src/defport/util/content.cljc` (~200 lines)
- `test/defport/util/content_test.clj` (~100 lines)
- `examples/image_content_example.clj` (~150 lines)

#### Files to Modify
- `src/defport/protocols/mcp.cljc` - Update format-content (~20 lines)

#### Acceptance Criteria
- [ ] Base64 encoding/decoding works on JVM and Node.js
- [ ] Can create ImageContent from binary data
- [ ] Can load images from files
- [ ] MIME type guessing works
- [ ] format-content handles ImageContent
- [ ] All tests pass (new + existing)
- [ ] Example demonstrates image tools

---

### 4.2 AudioContent Support (1 day) 🔊

**Priority:** Low
**Complexity:** Low
**Effort:** 1 day
**Dependencies:** ImageContent (reuses Base64 utilities)

#### What is AudioContent?

Allows tools to return audio (speech, music, sound effects) using Base64 encoding.

#### MCP Spec Format

```json
{
  "type": "audio",
  "data": "base64-encoded-audio-data",
  "mimeType": "audio/wav"
}
```

#### Implementation Tasks

1. **Add to `src/defport/util/content.cljc`**
   ```clojure
   (defn audio-content
     "Create AudioContent for MCP response.

     Args:
       data - Binary audio data (byte array)
       mime-type - MIME type (e.g., 'audio/wav', 'audio/mp3')

     Returns:
       AudioContent map

     Example:
       (audio-content wav-bytes \"audio/wav\")"
     [data mime-type]
     {:type "audio"
      :data (base64-encode data)
      :mimeType mime-type})

   (defn load-audio-file
     "Load audio from file and create AudioContent."
     [file-path]
     #?(:clj (let [bytes (-> file-path
                             java.io.File.
                             slurp
                             .getBytes)]
               (audio-content bytes
                             (guess-audio-mime-type file-path)))
        :cljs (throw (ex-info "Not implemented for Node.js yet"
                              {:file-path file-path}))))

   (defn guess-audio-mime-type
     "Guess audio MIME type from file extension."
     [file-path]
     (let [ext (-> file-path
                   (clojure.string/split #"\.")
                   last
                   clojure.string/lower-case)]
       (case ext
         "wav" "audio/wav"
         "mp3" "audio/mpeg"
         "ogg" "audio/ogg"
         "m4a" "audio/mp4"
         "flac" "audio/flac"
         "application/octet-stream")))
   ```

2. **Update format-content in `mcp.cljc`**
   ```clojure
   (defn format-content
     [result]
     (cond
       ;; ... existing cases

       ;; Audio content (has :type "audio")
       (and (map? result) (= "audio" (:type result)))
       [result]

       ;; ... rest
       ))
   ```

3. **Add tests** (similar to ImageContent tests)

4. **Add example**
   ```clojure
   ;; examples/audio_content_example.clj
   (mcp/deftool text-to-speech
     "Convert text to speech audio"
     [text :- :string
      voice :- :string]
     (let [audio-wav (synthesize-speech text voice)]
       {:content [(content/audio-content audio-wav "audio/wav")]}))

   (mcp/deftool record-audio
     "Record audio from microphone"
     [duration-seconds :- :number]
     (let [recording (record-mic duration-seconds)]
       {:content [(content/audio-content recording "audio/wav")]}))
   ```

#### Files to Modify
- `src/defport/util/content.cljc` (~100 lines added)
- `src/defport/protocols/mcp.cljc` (~10 lines added)
- `test/defport/util/content_test.clj` (~50 lines added)

#### Files to Create
- `examples/audio_content_example.clj` (~100 lines)

#### Acceptance Criteria
- [ ] Can create AudioContent from binary data
- [ ] Can load audio from files
- [ ] Audio MIME type guessing works
- [ ] format-content handles AudioContent
- [ ] All tests pass
- [ ] Example demonstrates audio tools

---

### 4.3 Roots Support (2 days) 📁

**Priority:** Low
**Complexity:** Medium
**Effort:** 2 days
**Dependencies:** None

#### What are Roots?

Roots allow MCP clients to define filesystem boundaries that servers can query. This enables:
- Safe file operations within allowed directories
- Multiple workspace support
- Permission boundaries

#### MCP Spec Methods

- `roots/list` - List client-defined filesystem roots
- `notifications/roots/list_changed` - Notify when roots change

#### Implementation Tasks

1. **Add roots tracking to `src/defport/protocols/mcp.cljc`**
   ```clojure
   ;; Root definition
   (defonce client-roots* (atom []))

   (defn handle-roots-list
     "Handle roots/list request - returns client filesystem roots.

     This is typically called BY the server TO the client,
     but we need to track roots the client has shared with us.

     Returns:
       {:roots [{:uri \"file:///workspace\" :name \"Project Root\"}]}"
     [params context]
     {:roots @client-roots*})

   (defn update-client-roots!
     "Update the list of client roots (called when client notifies us).

     Called when client sends notifications/roots/list_changed."
     [new-roots]
     (reset! client-roots* new-roots))

   (defn is-path-in-roots?
     "Check if a file path is within any client root.

     Example:
       (is-path-in-roots? \"/workspace/src/foo.clj\")
       ;; => true if /workspace is a root"
     [file-path]
     (let [roots @client-roots*]
       (some (fn [root]
               (let [root-path (:uri root)]
                 (.startsWith file-path root-path)))
             roots)))

   (defn validate-file-access
     "Validate that file access is within allowed roots.

     Throws exception if file is outside roots."
     [file-path]
     (when-not (is-path-in-roots? file-path)
       (throw (ex-info "File access denied: outside allowed roots"
                       {:file-path file-path
                        :roots @client-roots*}))))
   ```

2. **Add roots support to DSL**
   ```clojure
   ;; In src/defport/dsl.cljc

   (defn get-roots
     "Get current client filesystem roots."
     []
     @mcp/client-roots*)

   (defn validate-file!
     "Validate file is within allowed roots.

     Example:
       (mcp/deftool read-file
         [path :- :string]
         (mcp/validate-file! path)  ; Throws if outside roots
         (slurp path))"
     [file-path]
     (mcp/validate-file-access file-path))
   ```

3. **Add to initialize response**
   ```clojure
   (defn handle-initialize
     [params context]
     {:protocolVersion protocol-version
      :capabilities {:tools {}
                     :prompts {}
                     :resources {}
                     :roots {:supportsRoots true}  ; NEW
                     ;; ...
                     }
      ;; ...
      })
   ```

4. **Add tests**
   ```clojure
   ;; test/defport/protocols/mcp_roots_test.clj
   (deftest roots-list-test
     (testing "roots/list returns current roots"
       (let [adapter (mcp/create-mcp-adapter {})
             roots [{:uri "file:///workspace" :name "Main"}]]
         (mcp/update-client-roots! roots)
         (let [response (mcp/handle-roots-list {} {})]
           (is (= roots (:roots response)))))))

   (deftest path-validation-test
     (testing "validates paths within roots"
       (mcp/update-client-roots! [{:uri "file:///allowed"}])
       (is (true? (mcp/is-path-in-roots? "file:///allowed/foo.txt")))
       (is (false? (mcp/is-path-in-roots? "file:///forbidden/bar.txt"))))

     (testing "throws on invalid paths"
       (is (thrown? Exception
             (mcp/validate-file-access "file:///forbidden/bar.txt")))))
   ```

5. **Add example**
   ```clojure
   ;; examples/roots_example.clj
   (ns roots-example
     (:require [defport.dsl :as mcp]))

   ;; Safe file reader - validates against roots
   (mcp/deftool read-file-safe
     "Read file (only within allowed roots)"
     [path :- :string]
     (mcp/validate-file! path)  ; Throws if outside roots
     {:content (slurp path)})

   ;; List files in allowed directories
   (mcp/deftool list-workspace-files
     "List files in workspace roots"
     []
     (let [roots (mcp/get-roots)
           files (mapcat list-directory-files roots)]
       {:files files}))

   ;; Example: Tool that respects boundaries
   (mcp/deftool write-file-safe
     "Write file (only within allowed roots)"
     [path :- :string
      content :- :string]
     (mcp/validate-file! path)
     (spit path content)
     {:status "written"})
   ```

#### Files to Modify
- `src/defport/protocols/mcp.cljc` (~150 lines added)
- `src/defport/dsl.cljc` (~30 lines added)

#### Files to Create
- `test/defport/protocols/mcp_roots_test.clj` (~100 lines)
- `examples/roots_example.clj` (~150 lines)

#### Acceptance Criteria
- [ ] Can track client roots
- [ ] Can validate file paths against roots
- [ ] roots/list handler works
- [ ] DSL helpers work (get-roots, validate-file!)
- [ ] Capability reported in initialize
- [ ] All tests pass
- [ ] Example demonstrates safe file operations

---

### 4.4 Sampling Support (3-4 days) 🤖

**Priority:** Low
**Complexity:** High
**Effort:** 3-4 days
**Dependencies:** None (but most complex)

#### What is Sampling?

Sampling allows MCP **servers** to request LLM completions from **clients** during tool execution. This enables:
- Server-side AI reasoning
- Multi-step agentic workflows
- Reflection and self-correction

#### MCP Spec Methods

- `sampling/createMessage` - Request LLM completion from client
- Client responds with LLM-generated message

#### Why is this Complex?

1. **Bidirectional communication** - Server initiates request to client
2. **Async coordination** - Wait for LLM response
3. **Context management** - Pass conversation context
4. **Token management** - Handle limits and costs

#### Implementation Tasks

1. **Add sampling state to `src/defport/protocols/mcp.cljc`**
   ```clojure
   (defonce sampling-state* (atom {}))

   (defn create-sampling-request
     "Create a sampling request to send to client.

     Args:
       messages - Conversation messages
       model-preferences - Optional model hints
       system-prompt - Optional system prompt
       max-tokens - Token limit

     Returns:
       Sampling request ID (for tracking response)"
     [messages & {:keys [model-preferences system-prompt max-tokens]}]
     (let [request-id (proto-util/generate-call-id)
           request {:messages messages
                    :modelPreferences model-preferences
                    :systemPrompt system-prompt
                    :maxTokens (or max-tokens 1000)}]

       ;; Store request
       (swap! sampling-state* assoc request-id
         {:request request
          :status :pending
          :timestamp (System/currentTimeMillis)})

       request-id))

   (defn send-sampling-request
     "Send sampling request to client via transport.

     Returns promise that resolves when client responds."
     [transport request-id]
     (let [request (get-in @sampling-state* [request-id :request])]
       ;; Send to client
       (core/transport-send transport
         {:jsonrpc "2.0"
          :id request-id
          :method "sampling/createMessage"
          :params request})

       ;; Return promise/deferred
       #?(:clj (let [p (promise)]
                 (swap! sampling-state* assoc-in [request-id :promise] p)
                 p)
          :cljs (let [p (js/Promise.
                          (fn [resolve reject]
                            (swap! sampling-state* assoc-in
                              [request-id :promise] resolve)))]
                  p))))

   (defn handle-sampling-response
     "Handle client's response to sampling request.

     Called when client returns LLM completion."
     [request-id response]
     (let [promise (get-in @sampling-state* [request-id :promise])]
       ;; Update state
       (swap! sampling-state* update request-id assoc
         :status :completed
         :response response)

       ;; Resolve promise
       #?(:clj (deliver promise response)
          :cljs (promise response))))
   ```

2. **Add DSL helper**
   ```clojure
   ;; In src/defport/dsl.cljc

   (defn sample!
     "Request LLM completion from client during tool execution.

     Args:
       messages - Conversation messages (vector of maps)
       opts - Options map with :model, :max-tokens, :system-prompt

     Returns:
       LLM response message

     Example:
       (mcp/deftool analyze-code
         [code :- :string]
         (let [analysis (mcp/sample!
                          [{:role \"user\"
                            :content {:type \"text\"
                                     :text (str \"Analyze: \" code)}}]
                          {:max-tokens 500})]
           {:analysis (:content analysis)}))"
     [messages & [opts]]
     (let [transport (:transport @server-state*)
           request-id (mcp/create-sampling-request
                        messages
                        :model-preferences (:model opts)
                        :system-prompt (:system-prompt opts)
                        :max-tokens (:max-tokens opts))
           response-promise (mcp/send-sampling-request transport request-id)]

       ;; Block waiting for response
       @response-promise))
   ```

3. **Add capability to initialize**
   ```clojure
   (defn handle-initialize
     [params context]
     {:capabilities {:tools {}
                     :sampling {}  ; NEW - indicates server can request sampling
                     ;; ...
                     }})
   ```

4. **Add tests**
   ```clojure
   ;; test/defport/protocols/mcp_sampling_test.clj
   (deftest sampling-request-test
     (testing "creates sampling request"
       (let [request-id (mcp/create-sampling-request
                          [{:role "user" :content "test"}]
                          :max-tokens 100)]
         (is (string? request-id))
         (is (= :pending (get-in @mcp/sampling-state*
                                 [request-id :status])))))

     (testing "handles response"
       (let [request-id (mcp/create-sampling-request
                          [{:role "user" :content "test"}])
             response {:role "assistant" :content "response"}]
         (mcp/handle-sampling-response request-id response)
         (is (= :completed (get-in @mcp/sampling-state*
                                   [request-id :status]))))))
   ```

5. **Add example**
   ```clojure
   ;; examples/sampling_example.clj
   (ns sampling-example
     (:require [defport.dsl :as mcp]))

   ;; Example 1: Code analysis with LLM
   (mcp/deftool analyze-code
     "Analyze code using LLM"
     [code :- :string]
     (let [response (mcp/sample!
                      [{:role "user"
                        :content {:type "text"
                                 :text (str "Analyze this code:\n\n" code)}}]
                      {:max-tokens 500})]
       {:analysis (:content response)}))

   ;; Example 2: Multi-step reasoning
   (mcp/deftool solve-problem
     "Solve problem with multi-step reasoning"
     [problem :- :string]
     ;; Step 1: Generate plan
     (let [plan-response (mcp/sample!
                           [{:role "user"
                             :content {:type "text"
                                      :text (str "Create a plan to solve: " problem)}}])
           plan (:content plan-response)

           ;; Step 2: Execute plan with LLM help
           solution-response (mcp/sample!
                               [{:role "user"
                                 :content {:type "text"
                                          :text (str "Given plan: " plan
                                                    "\n\nExecute the plan")}}])]
       {:plan plan
        :solution (:content solution-response)}))

   ;; Example 3: Self-reflection
   (mcp/deftool check-work
     "Generate answer and verify it"
     [question :- :string]
     (let [;; Generate initial answer
           answer (mcp/sample!
                    [{:role "user"
                      :content {:type "text"
                               :text question}}])

           ;; Ask LLM to verify
           verification (mcp/sample!
                          [{:role "user"
                            :content {:type "text"
                                     :text (str "Verify this answer:\n"
                                               "Q: " question "\n"
                                               "A: " (:content answer))}}])]
       {:answer (:content answer)
        :verification (:content verification)}))
   ```

#### Files to Modify
- `src/defport/protocols/mcp.cljc` (~200 lines added)
- `src/defport/dsl.cljc` (~50 lines added)

#### Files to Create
- `test/defport/protocols/mcp_sampling_test.clj` (~150 lines)
- `examples/sampling_example.clj` (~200 lines)

#### Acceptance Criteria
- [ ] Can create sampling requests
- [ ] Can send requests to client
- [ ] Can receive and handle responses
- [ ] Promise/deferred mechanism works
- [ ] `sample!` DSL helper works
- [ ] Capability reported in initialize
- [ ] All tests pass
- [ ] Example demonstrates multi-step reasoning

#### Challenges & Considerations

1. **Blocking vs Async** - Tools need to block waiting for LLM response
2. **Timeout handling** - What if client never responds?
3. **Error handling** - Client might reject or fail
4. **Token costs** - Should we track usage?

---

## Implementation Order

### Recommended Sequence

1. **ImageContent** (1 day) - Easiest, builds foundation
2. **AudioContent** (1 day) - Reuses ImageContent utilities
3. **Roots** (2 days) - Medium complexity, independent
4. **Sampling** (3-4 days) - Most complex, save for last

### Rationale

- Start with low-hanging fruit (Image/Audio)
- Build confidence with simple features
- Tackle complex Sampling when familiar with codebase

### Alternative: Parallel Development

If multiple developers:
- Dev 1: ImageContent + AudioContent (2 days)
- Dev 2: Roots (2 days)
- Dev 3: Sampling (3-4 days)

---

## Test Plan

### Test Coverage Goals

**Current:** 61 tests, 331 assertions
**Target:** 75+ tests, 400+ assertions

### New Tests Needed

| Feature | Tests | Assertions |
|---------|-------|------------|
| ImageContent | 5 | 15 |
| AudioContent | 5 | 15 |
| Roots | 8 | 25 |
| Sampling | 10 | 35 |
| **Total** | **28** | **90** |

### Testing Strategy

```bash
# After each feature
clj -M:test -m kaocha.runner

# Expected progression:
# ImageContent:  66 tests, 346 assertions
# AudioContent:  71 tests, 361 assertions
# Roots:         79 tests, 386 assertions
# Sampling:      89 tests, 421 assertions
```

---

## Documentation Updates

### Files to Update

1. **MCP_IMPLEMENTATION.md**
   - Mark Phase 4 features as complete
   - Update compliance table to include optional features
   - Add new capabilities section

2. **CHANGELOG.md**
   - Add Phase 4 section with all features
   - Update version to 0.4.0-SNAPSHOT

3. **README.md**
   - Update feature list
   - Add examples of new capabilities
   - Update status badges

4. **ROADMAP.md**
   - Mark Phase 4 complete
   - Add Phase 5 (Production Hardening) details

5. **Create PHASE_4_SUMMARY.md**
   - Document implementation details
   - Show before/after comparisons
   - Include performance notes

### New Examples

- `examples/image_content_example.clj` (150 lines)
- `examples/audio_content_example.clj` (100 lines)
- `examples/roots_example.clj` (150 lines)
- `examples/sampling_example.clj` (200 lines)

**Total:** 600 lines of examples

---

## Success Criteria

### Functional Requirements

- [ ] ImageContent works (Base64 encode/decode, file loading)
- [ ] AudioContent works (Base64 encode/decode, file loading)
- [ ] Roots work (list, validate, track client roots)
- [ ] Sampling works (request, response, async coordination)
- [ ] All DSL helpers work
- [ ] All existing tests still pass
- [ ] New tests added for all features

### Quality Requirements

- [ ] 100% test pass rate maintained
- [ ] No regressions in existing features
- [ ] Code quality maintained (no warnings)
- [ ] Examples demonstrate all features
- [ ] Documentation complete and accurate

### MCP Spec Compliance

- [ ] **100% MCP 2025-06-18 compliant (all features)** 🎯
- [ ] All required features working (from Phase 3)
- [ ] All optional features working (Phase 4)

---

## Timeline

### Conservative Estimate (8 days)

- Day 1: ImageContent implementation + tests
- Day 2: AudioContent implementation + tests
- Day 3: Roots implementation
- Day 4: Roots tests + examples
- Day 5-6: Sampling implementation
- Day 7: Sampling tests
- Day 8: Documentation + polish

### Optimistic Estimate (7 days)

- Day 1: ImageContent + AudioContent (combined)
- Day 2: Roots implementation + tests
- Day 3-5: Sampling implementation + tests
- Day 6: Examples for all features
- Day 7: Documentation + polish

---

## Dependencies & Prerequisites

### External Dependencies

No new dependencies required! Use existing:
- `metosin/malli` - Already included for schemas
- `cheshire` - Already included for JSON
- JVM Base64 (built-in)
- Node.js Buffer (built-in)

### Platform Considerations

- **ImageContent/AudioContent:** JVM implementation complete, Node.js needs Buffer API
- **Roots:** Platform-agnostic (just string paths)
- **Sampling:** Needs promise/deferred mechanism (JVM: promise, CLJS: js/Promise)

---

## Risks & Mitigation

### Risk 1: Sampling Complexity

**Risk:** Bidirectional async communication is complex
**Impact:** High - could take longer than estimated
**Mitigation:**
- Start with synchronous mock implementation
- Add async layer incrementally
- Test thoroughly with timeouts

### Risk 2: Platform Portability

**Risk:** Base64 encoding differs between JVM/Node.js
**Impact:** Medium - could break ClojureScript builds
**Mitigation:**
- Use reader conditionals
- Test on both platforms
- Provide fallbacks

### Risk 3: Roots Security

**Risk:** Path validation might have edge cases
**Impact:** High - security implications
**Mitigation:**
- Thorough path normalization
- Test with tricky paths (../, symlinks, etc.)
- Conservative validation (reject when unsure)

---

## Next Steps

### To Start Phase 4

1. **Review this plan** - Understand scope and approach
2. **Run current tests** - Verify Phase 3 stability
3. **Create branch** - `feature/phase-4-optional-features`
4. **Start with ImageContent** - Easiest feature first

### First Commands

```bash
cd /c/Users/Apollo/CascadeProjects/defport

# Verify current state
clj -M:test -m kaocha.runner

# Create new branch
git checkout -b feature/phase-4-optional-features

# Create first file
touch src/defport/util/content.cljc
```

---

## Resources

### MCP Specification

- [MCP 2025-06-18 Spec](https://modelcontextprotocol.io/specification/2025-06-18)
- [Sampling Documentation](https://modelcontextprotocol.io/docs/concepts/sampling)
- [Roots Documentation](https://modelcontextprotocol.io/docs/concepts/roots)

### Code References

- [ImageContent Example (TypeScript SDK)](https://github.com/modelcontextprotocol/typescript-sdk/blob/main/src/types.ts#L123)
- [Sampling Example (Python SDK)](https://github.com/modelcontextprotocol/python-sdk/blob/main/src/mcp/server/sampling.py)

### Internal Documentation

- [MCP_IMPLEMENTATION.md](MCP_IMPLEMENTATION.md) - Current status
- [ROADMAP.md](ROADMAP.md) - Overall roadmap
- [CLAUDE.md](CLAUDE.md) - Project guidelines

---

## Phase 5 Preview

After Phase 4 completion, we'll move to **Production Hardening**:

1. **Performance optimization** - Benchmarking, profiling
2. **Security hardening** - Input validation, rate limiting
3. **Error handling** - Comprehensive error recovery
4. **Observability** - Logging, metrics, tracing
5. **Integration testing** - Real-world client testing

---

**Phase 4 Status:** 🎯 **READY TO START**

**First Task:** Create `src/defport/util/content.cljc` with Base64 utilities

**End Goal:** 100% MCP 2025-06-18 spec compliance (all optional features)

**Let's build it!** 🚀
