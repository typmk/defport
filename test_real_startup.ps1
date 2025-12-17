# Test what actually gets written to stdout during Clojure startup
# This simulates what an MCP client sees

Write-Host "Testing Clojure startup stdout noise..."
Write-Host ""

# Test 1: What does clojure print on startup with minimal code?
Write-Host "=== Test 1: Minimal startup ==="
$output1 = & clj -M -e '(print "MARKER")' 2>$null
Write-Host "Output: [$output1]"
if ($output1 -eq "MARKER") {
    Write-Host "PASS: Clean startup, only our output"
} else {
    Write-Host "FAIL: Extra output before MARKER"
}
Write-Host ""

# Test 2: What happens with requires?
Write-Host "=== Test 2: With requires ==="
$output2 = & clj -M -e '
(require (quote [cheshire.core :as json]))
(print "MARKER")
' 2>$null
Write-Host "Output: [$output2]"
if ($output2 -eq "MARKER") {
    Write-Host "PASS: Clean after requires"
} else {
    Write-Host "FAIL: Noise from requires"
}
Write-Host ""

# Test 3: Real stdio transport test - write directly to System/out
Write-Host "=== Test 3: Direct System/out write ==="
$output3 = & clj -M -e '
(require (quote [cheshire.core :as json]))

(defn write-framed [msg]
  (let [content (json/generate-string msg)
        content-bytes (.getBytes content "utf-8")
        header (str "Content-Length: " (count content-bytes) "\r\n\r\n")]
    (.write System/out (.getBytes header "US-ASCII"))
    (.write System/out content-bytes)
    (.flush System/out)))

(write-framed {:jsonrpc "2.0" :id 1 :result {:ok true}})
' 2>$null
Write-Host "Output: [$output3]"
if ($output3 -match "^Content-Length:") {
    Write-Host "PASS: Output starts with Content-Length"
} else {
    Write-Host "FAIL: Noise before Content-Length"
}
Write-Host ""

# Test 4: Full transport with System/out
Write-Host "=== Test 4: Full transport to System/out ==="
$output4 = & clj -M -e '
(require (quote [defport.transports.stdio :as stdio])
         (quote [defport.core :as core]))

(let [transport (stdio/create-stdio-transport)]
  (core/transport-start transport (constantly nil))
  (core/transport-send transport {:jsonrpc "2.0" :id 1 :result {:ok true}})
  (Thread/sleep 200)
  (core/transport-stop transport))
' 2>$null
Write-Host "Raw output bytes: $($output4.Length)"
Write-Host "Output: [$output4]"
if ($output4 -match "^Content-Length:") {
    Write-Host "PASS: Transport output starts with Content-Length"
} else {
    Write-Host "FAIL: Transport has noise"
    Write-Host "First chars: $($output4.Substring(0, [Math]::Min(50, $output4.Length)))"
}