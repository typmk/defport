# Test defnet stdio startup for noise/bleed
# This simulates what Cursor/VSCode MCP client sees

Write-Host "Testing defnet stdio startup for noise..."
Write-Host ""

# Test defnet --stdio startup and capture raw output
Write-Host "=== Starting defnet in stdio mode ==="
Write-Host "Sending initialize request and capturing all output..."
Write-Host ""

# Create the initialize request with Content-Length framing
$initRequest = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}'
$contentLength = [System.Text.Encoding]::UTF8.GetByteCount($initRequest)
$framedRequest = "Content-Length: $contentLength`r`n`r`n$initRequest"

Write-Host "Sending request:"
Write-Host $framedRequest
Write-Host ""

# Start defnet and send the request
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "clj"
$psi.Arguments = "-M:stdio"
$psi.WorkingDirectory = "C:\GitHub\defnet"
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true

$process = [System.Diagnostics.Process]::Start($psi)

# Send the initialize request
$process.StandardInput.Write($framedRequest)
$process.StandardInput.Flush()

# Wait a bit for response
Start-Sleep -Seconds 5

# Read whatever output is available
$output = ""
while ($process.StandardOutput.Peek() -ge 0) {
    $output += [char]$process.StandardOutput.Read()
}

$stderr = ""
while ($process.StandardError.Peek() -ge 0) {
    $stderr += [char]$process.StandardError.Read()
}

# Kill the process
$process.Kill()
$process.WaitForExit()

Write-Host "=== RAW STDOUT ==="
Write-Host $output
Write-Host "=== END STDOUT ==="
Write-Host ""

Write-Host "=== STDERR (should contain logs) ==="
Write-Host $stderr
Write-Host "=== END STDERR ==="
Write-Host ""

# Analyze stdout
if ($output.Length -eq 0) {
    Write-Host "WARNING: No stdout output received (might need more time)"
} elseif ($output -match "^Content-Length:") {
    Write-Host "PASS: stdout starts with Content-Length header (no noise)"
} else {
    Write-Host "FAIL: stdout has noise before Content-Length"
    Write-Host "First 200 chars:"
    Write-Host $output.Substring(0, [Math]::Min(200, $output.Length))
}

# Check for common noise patterns in stdout
$noisePatterns = @("Clojure", "Loading", "WARNING", "nREPL", "user=>", "Exception", "Error")
$foundNoise = $false
foreach ($pattern in $noisePatterns) {
    if ($output -match $pattern) {
        Write-Host "FAIL: Found noise pattern in stdout: $pattern"
        $foundNoise = $true
    }
}
if (-not $foundNoise -and $output.Length -gt 0) {
    Write-Host "PASS: No noise patterns in stdout"
}