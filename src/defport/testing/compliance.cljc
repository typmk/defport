(ns defport.testing.compliance
  "MCP 2025-06-18 and JSON-RPC 2.0 compliance validators.

  Validates responses against the MCP specification to ensure 100% compliance.
  Each validation function returns nil on success, or a map describing the violation."
  (:require [clojure.string :as str]
            [clojure.set :as set]))

;; ============================================================================
;; JSON-RPC 2.0 Envelope Validation
;; ============================================================================

(defn validate-jsonrpc-version
  "Validate that jsonrpc field is exactly '2.0'."
  [response]
  (let [version (:jsonrpc response)]
    (when-not (= "2.0" version)
      {:error :invalid-jsonrpc-version
       :message "jsonrpc field must be exactly '2.0'"
       :expected "2.0"
       :actual version})))

(defn validate-response-id
  "Validate that response id matches request id."
  [response request-id]
  (let [response-id (:id response)]
    (when-not (= request-id response-id)
      {:error :id-mismatch
       :message "Response id must match request id"
       :expected request-id
       :actual response-id})))

(defn validate-result-or-error
  "Validate that response has either result OR error, never both."
  [response]
  (let [has-result? (contains? response :result)
        has-error? (contains? response :error)]
    (cond
      (and has-result? has-error?)
      {:error :both-result-and-error
       :message "Response cannot have both result and error"}

      (and (not has-result?) (not has-error?))
      {:error :missing-result-and-error
       :message "Response must have either result or error"}

      :else nil)))

(defn validate-error-object
  "Validate error object structure."
  [error-obj]
  (when error-obj
    (cond
      (not (map? error-obj))
      {:error :error-not-map
       :message "Error must be a map"
       :actual (type error-obj)}

      (not (contains? error-obj :code))
      {:error :missing-error-code
       :message "Error must have code field"}

      (not (integer? (:code error-obj)))
      {:error :error-code-not-integer
       :message "Error code must be an integer"
       :actual (type (:code error-obj))}

      (not (contains? error-obj :message))
      {:error :missing-error-message
       :message "Error must have message field"}

      (not (string? (:message error-obj)))
      {:error :error-message-not-string
       :message "Error message must be a string"
       :actual (type (:message error-obj))}

      :else nil)))

(defn validate-jsonrpc-envelope
  "Validate complete JSON-RPC 2.0 envelope.

  Returns nil on success, or a map describing the first violation found."
  ([response]
   (validate-jsonrpc-envelope response nil))
  ([response request-id]
   (or (validate-jsonrpc-version response)
       (when request-id (validate-response-id response request-id))
       (validate-result-or-error response)
       (when (:error response)
         (validate-error-object (:error response))))))

;; ============================================================================
;; Field Naming Validation
;; ============================================================================

(def camel-case-pattern
  "Regex for camelCase field names."
  #"^[a-z][a-zA-Z0-9]*$")

(defn snake-case?
  "Check if string uses snake_case."
  [s]
  (and (string? s)
       (str/includes? s "_")))

(defn validate-field-naming
  "Validate that all keys in map use camelCase, not snake_case.

  Recursively validates nested maps and arrays."
  [data path]
  (cond
    (map? data)
    (or (some (fn [[k v]]
                (let [key-name (name k)]
                  (when (snake-case? key-name)
                    {:error :snake-case-field
                     :message "Field names must use camelCase, not snake_case"
                     :field (conj path k)
                     :value key-name})))
              data)
        (some (fn [[k v]]
                (validate-field-naming v (conj path k)))
              data))

    (sequential? data)
    (some (fn [item]
            (validate-field-naming item path))
          data)

    :else nil))

;; ============================================================================
;; Type Validation
;; ============================================================================

(defn validate-type
  "Validate that value matches expected type."
  [value expected-type path]
  (let [valid? (case expected-type
                 :string (string? value)
                 :number (number? value)
                 :integer (integer? value)
                 :boolean (boolean? value)
                 :object (map? value)
                 :array (sequential? value)
                 :null (nil? value)
                 true)]
    (when-not valid?
      {:error :type-mismatch
       :message (str "Field must be " (name expected-type))
       :path path
       :expected expected-type
       :actual (type value)})))

(defn validate-enum
  "Validate that value is one of allowed values."
  [value allowed-values path]
  (when-not (contains? (set allowed-values) value)
    {:error :invalid-enum-value
     :message "Value must be one of allowed values"
     :path path
     :allowed allowed-values
     :actual value}))

;; ============================================================================
;; Content Type Validation
;; ============================================================================

(defn validate-text-content
  "Validate TextContent structure."
  [content path]
  (or (validate-type content :object path)
      (when-not (= "text" (:type content))
        {:error :invalid-content-type
         :message "TextContent must have type: 'text'"
         :path path
         :expected "text"
         :actual (:type content)})
      (when-not (contains? content :text)
        {:error :missing-text-field
         :message "TextContent must have text field"
         :path path})
      (validate-type (:text content) :string (conj path :text))))

(defn validate-image-content
  "Validate ImageContent structure."
  [content path]
  (or (validate-type content :object path)
      (when-not (= "image" (:type content))
        {:error :invalid-content-type
         :message "ImageContent must have type: 'image'"
         :path path})
      (when-not (contains? content :data)
        {:error :missing-data-field
         :message "ImageContent must have data field"
         :path path})
      (validate-type (:data content) :string (conj path :data))
      (when-not (contains? content :mimeType)
        {:error :missing-mimetype-field
         :message "ImageContent must have mimeType field"
         :path path})
      (when-not (str/starts-with? (:mimeType content) "image/")
        {:error :invalid-image-mimetype
         :message "ImageContent mimeType must start with 'image/'"
         :path (conj path :mimeType)
         :actual (:mimeType content)})))

(defn validate-audio-content
  "Validate AudioContent structure."
  [content path]
  (or (validate-type content :object path)
      (when-not (= "audio" (:type content))
        {:error :invalid-content-type
         :message "AudioContent must have type: 'audio'"
         :path path})
      (when-not (contains? content :data)
        {:error :missing-data-field
         :message "AudioContent must have data field"
         :path path})
      (validate-type (:data content) :string (conj path :data))
      (when-not (contains? content :mimeType)
        {:error :missing-mimetype-field
         :message "AudioContent must have mimeType field"
         :path path})
      (when-not (str/starts-with? (:mimeType content) "audio/")
        {:error :invalid-audio-mimetype
         :message "AudioContent mimeType must start with 'audio/'"
         :path (conj path :mimeType)
         :actual (:mimeType content)})))

(defn validate-resource-content
  "Validate EmbeddedResource structure."
  [content path]
  (or (validate-type content :object path)
      (when-not (= "resource" (:type content))
        {:error :invalid-content-type
         :message "EmbeddedResource must have type: 'resource'"
         :path path})
      (when-not (contains? content :resource)
        {:error :missing-resource-field
         :message "EmbeddedResource must have resource field"
         :path path})
      (let [resource (:resource content)]
        (or (validate-type resource :object (conj path :resource))
            (when-not (contains? resource :uri)
              {:error :missing-uri-field
               :message "Resource must have uri field"
               :path (conj path :resource :uri)})
            (when-not (or (contains? resource :text)
                         (contains? resource :blob))
              {:error :missing-text-or-blob
               :message "Resource must have either text or blob field"
               :path (conj path :resource)})))))

(defn validate-content-object
  "Validate any Content object based on its type."
  [content path]
  (cond
    (not (map? content))
    {:error :content-not-map
     :message "Content must be a map"
     :path path
     :actual (type content)}

    (not (contains? content :type))
    {:error :missing-content-type
     :message "Content must have type field"
     :path path}

    :else
    (case (:type content)
      "text" (validate-text-content content path)
      "image" (validate-image-content content path)
      "audio" (validate-audio-content content path)
      "resource" (validate-resource-content content path)
      "object" {:error :object-content-not-in-spec
                :message "ObjectContent does not exist in MCP 2025-06-18 spec. Use TextContent with JSON."
                :path path
                :suggestion "Change type to 'text' and serialize data as JSON string"}
      {:error :invalid-content-type
       :message "Invalid content type"
       :path path
       :allowed ["text" "image" "audio" "resource"]
       :actual (:type content)})))

(defn validate-content-array
  "Validate array of Content objects."
  [content-array path]
  (cond
    (not (sequential? content-array))
    {:error :content-not-array
     :message "Content must be an array"
     :path path
     :actual (type content-array)}

    (empty? content-array)
    {:error :empty-content-array
     :message "Content array must not be empty"
     :path path}

    :else
    (some (fn [[idx content]]
            (validate-content-object content (conj path idx)))
          (map-indexed vector content-array))))

;; ============================================================================
;; MCP Method-Specific Validation
;; ============================================================================

(defn validate-initialize-response
  "Validate initialize method response."
  [result]
  (or (when-not (contains? result :protocolVersion)
        {:error :missing-protocol-version
         :message "initialize must return protocolVersion"})
      (validate-type (:protocolVersion result) :string [:protocolVersion])
      (when-not (re-matches #"\d{4}-\d{2}-\d{2}" (:protocolVersion result))
        {:error :invalid-protocol-version-format
         :message "protocolVersion must be in YYYY-MM-DD format"
         :actual (:protocolVersion result)})
      (when-not (contains? result :capabilities)
        {:error :missing-capabilities
         :message "initialize must return capabilities"})
      (validate-type (:capabilities result) :object [:capabilities])
      (when-not (contains? result :serverInfo)
        {:error :missing-server-info
         :message "initialize must return serverInfo"})
      (let [server-info (:serverInfo result)]
        (or (validate-type server-info :object [:serverInfo])
            (when-not (contains? server-info :name)
              {:error :missing-server-name
               :message "serverInfo must have name field"
               :path [:serverInfo :name]})
            (validate-type (:name server-info) :string [:serverInfo :name])
            (when-not (contains? server-info :version)
              {:error :missing-server-version
               :message "serverInfo must have version field"
               :path [:serverInfo :version]})
            (validate-type (:version server-info) :string [:serverInfo :version])))))

(defn validate-tools-list-response
  "Validate tools/list method response."
  [result]
  (or (when-not (contains? result :tools)
        {:error :missing-tools
         :message "tools/list must return tools array"})
      (validate-type (:tools result) :array [:tools])
      (some (fn [[idx tool]]
              (or (validate-type tool :object [:tools idx])
                  (when-not (contains? tool :name)
                    {:error :missing-tool-name
                     :message "Tool must have name field"
                     :path [:tools idx :name]})
                  (validate-type (:name tool) :string [:tools idx :name])
                  (when-not (contains? tool :inputSchema)
                    {:error :missing-input-schema
                     :message "Tool must have inputSchema field"
                     :path [:tools idx :inputSchema]})
                  (validate-type (:inputSchema tool) :object [:tools idx :inputSchema])))
            (map-indexed vector (:tools result)))
      (when (contains? result :nextCursor)
        (validate-type (:nextCursor result) :string [:nextCursor]))))

(defn validate-tools-call-response
  "Validate tools/call method response."
  [result]
  (or (when-not (contains? result :content)
        {:error :missing-content
         :message "tools/call must return content array"})
      (validate-content-array (:content result) [:content])
      (when (contains? result :isError)
        (validate-type (:isError result) :boolean [:isError]))))

(defn validate-prompts-list-response
  "Validate prompts/list method response."
  [result]
  (or (when-not (contains? result :prompts)
        {:error :missing-prompts
         :message "prompts/list must return prompts array"})
      (validate-type (:prompts result) :array [:prompts])
      (some (fn [[idx prompt]]
              (or (validate-type prompt :object [:prompts idx])
                  (when-not (contains? prompt :name)
                    {:error :missing-prompt-name
                     :message "Prompt must have name field"
                     :path [:prompts idx :name]})
                  (validate-type (:name prompt) :string [:prompts idx :name])
                  (when (contains? prompt :arguments)
                    (or (validate-type (:arguments prompt) :array [:prompts idx :arguments])
                        (some (fn [[arg-idx arg]]
                                (or (validate-type arg :object [:prompts idx :arguments arg-idx])
                                    (when-not (contains? arg :name)
                                      {:error :missing-argument-name
                                       :message "Argument must have name field"
                                       :path [:prompts idx :arguments arg-idx :name]})
                                    (validate-type (:name arg) :string [:prompts idx :arguments arg-idx :name])))
                              (map-indexed vector (:arguments prompt)))))))
            (map-indexed vector (:prompts result)))
      (when (contains? result :nextCursor)
        (validate-type (:nextCursor result) :string [:nextCursor]))))

(defn validate-prompts-get-response
  "Validate prompts/get method response."
  [result]
  (or (when-not (contains? result :messages)
        {:error :missing-messages
         :message "prompts/get must return messages array"})
      (validate-type (:messages result) :array [:messages])
      (when (empty? (:messages result))
        {:error :empty-messages-array
         :message "messages array must not be empty"
         :path [:messages]})
      (some (fn [[idx message]]
              (or (validate-type message :object [:messages idx])
                  (when-not (contains? message :role)
                    {:error :missing-role
                     :message "Message must have role field"
                     :path [:messages idx :role]})
                  (validate-enum (:role message) ["user" "assistant"] [:messages idx :role])
                  (when-not (contains? message :content)
                    {:error :missing-content
                     :message "Message must have content field"
                     :path [:messages idx :content]})
                  (validate-content-object (:content message) [:messages idx :content])))
            (map-indexed vector (:messages result)))))

(defn validate-resources-list-response
  "Validate resources/list method response."
  [result]
  (or (when-not (contains? result :resources)
        {:error :missing-resources
         :message "resources/list must return resources array"})
      (validate-type (:resources result) :array [:resources])
      (some (fn [[idx resource]]
              (or (validate-type resource :object [:resources idx])
                  (when-not (contains? resource :uri)
                    {:error :missing-uri
                     :message "Resource must have uri field"
                     :path [:resources idx :uri]})
                  (validate-type (:uri resource) :string [:resources idx :uri])
                  (when-not (contains? resource :name)
                    {:error :missing-resource-name
                     :message "Resource must have name field"
                     :path [:resources idx :name]})
                  (validate-type (:name resource) :string [:resources idx :name])))
            (map-indexed vector (:resources result)))
      (when (contains? result :nextCursor)
        (validate-type (:nextCursor result) :string [:nextCursor]))))

(defn validate-resources-read-response
  "Validate resources/read method response."
  [result]
  (or (when-not (contains? result :contents)
        {:error :missing-contents
         :message "resources/read must return contents array"})
      (validate-type (:contents result) :array [:contents])
      (when (empty? (:contents result))
        {:error :empty-contents-array
         :message "contents array must not be empty"
         :path [:contents]})
      (some (fn [[idx content]]
              (or (validate-type content :object [:contents idx])
                  (when-not (contains? content :uri)
                    {:error :missing-uri
                     :message "Content must have uri field"
                     :path [:contents idx :uri]})
                  (validate-type (:uri content) :string [:contents idx :uri])
                  (when-not (or (contains? content :text)
                               (contains? content :blob))
                    {:error :missing-text-or-blob
                     :message "Content must have either text or blob field"
                     :path [:contents idx]})))
            (map-indexed vector (:contents result)))))

;; ============================================================================
;; Pagination Validation
;; ============================================================================

(defn validate-cursor-format
  "Validate cursor is an opaque string (implementation-defined).

  Note: Cursors should be opaque to clients, but we can validate basic format."
  [cursor path]
  (when cursor
    (when-not (string? cursor)
      {:error :cursor-not-string
       :message "Cursor must be a string"
       :path path
       :actual (type cursor)})))

(defn validate-pagination
  "Validate pagination structure (nextCursor presence/absence).

  If there are exactly page-size items, nextCursor should be present.
  If there are fewer than page-size items, nextCursor should be absent."
  [result items-key page-size]
  (let [items (get result items-key)
        item-count (count items)
        has-next-cursor? (contains? result :nextCursor)]
    (cond
      ;; Full page but no cursor = suspicious (might have more data)
      (and (= item-count page-size) (not has-next-cursor?))
      {:error :missing-next-cursor
       :message "Full page should have nextCursor if more data exists"
       :path [:nextCursor]
       :item-count item-count
       :page-size page-size
       :note "This might be valid if it's exactly the last page"}

      ;; Empty results with cursor = invalid
      (and (zero? item-count) has-next-cursor?)
      {:error :next-cursor-with-empty-results
       :message "Empty results should not have nextCursor"
       :path [:nextCursor]
       :item-count 0}

      ;; Validate cursor format if present
      has-next-cursor?
      (validate-cursor-format (:nextCursor result) [:nextCursor])

      :else nil)))

;; ============================================================================
;; Error Code Validation
;; ============================================================================

(def standard-error-codes
  "Standard JSON-RPC 2.0 error codes."
  {-32700 "Parse error"
   -32600 "Invalid Request"
   -32601 "Method not found"
   -32602 "Invalid params"
   -32603 "Internal error"})

(def server-error-range
  "Server-defined error code range."
  [-32099 -32000])

(def mcp-error-codes
  "MCP-specific error codes."
  {-32800 "Operation cancelled"})

(defn validate-error-code
  "Validate error code is in valid range."
  [code]
  (let [[server-min server-max] server-error-range]
    (cond
      (contains? standard-error-codes code) nil
      (contains? mcp-error-codes code) nil
      (<= server-min code server-max) nil
      :else
      {:error :invalid-error-code
       :message "Error code not in valid range"
       :code code
       :valid-codes (merge standard-error-codes mcp-error-codes)
       :valid-range server-error-range})))

;; ============================================================================
;; High-Level Validation Functions
;; ============================================================================

(defn validate-response
  "Validate complete MCP response (JSON-RPC envelope + MCP-specific format).

  method: MCP method name (e.g., 'initialize', 'tools/list')
  response: Complete JSON-RPC response
  request-id: Optional request ID to validate against

  Returns nil on success, or a map describing the first violation found."
  ([method response]
   (validate-response method response nil))
  ([method response request-id]
   (or (validate-jsonrpc-envelope response request-id)
       (when (:error response)
         (validate-error-code (:code (:error response))))
       (when (:result response)
         (case method
           "initialize" (validate-initialize-response (:result response))
           "tools/list" (validate-tools-list-response (:result response))
           "tools/call" (validate-tools-call-response (:result response))
           "prompts/list" (validate-prompts-list-response (:result response))
           "prompts/get" (validate-prompts-get-response (:result response))
           "resources/list" (validate-resources-list-response (:result response))
           "resources/read" (validate-resources-read-response (:result response))
           nil))  ; Other methods not validated
       (validate-field-naming response []))))

(defn valid-response?
  "Returns true if response is valid, false otherwise."
  [method response & [request-id]]
  (nil? (validate-response method response request-id)))

(defn assert-valid-response
  "Throws exception if response is invalid."
  [method response & [request-id]]
  (when-let [error (validate-response method response request-id)]
    (throw (ex-info "Invalid MCP response"
                    (merge error {:method method
                                  :response response
                                  :request-id request-id})))))
