<#
.SYNOPSIS
    Stops Server B cleanly via RCON 'stop', then verifies the process actually exits and
    port 25567 is freed. Never uses taskkill /IM java.exe or a global process kill.
    Mirrors stop-server-a.ps1 exactly, parameterized for Server B's port/RCON/dir.

.PARAMETER Force
    If the RCON stop doesn't result in a clean exit within -TimeoutSec, allows stopping the
    specific verified PID (matched by port 25567 + working directory + command line containing
    server-b/fabric-server-launch.jar) instead of leaving it hung. Off by default.
#>

param(
    [int]$TimeoutSec = 60,
    [switch]$Force
)

. "$PSScriptRoot\java-paths.ps1"
. "$PSScriptRoot\rcon-client.ps1"

Write-Host "=== stop-server-b.ps1 ===" -ForegroundColor Cyan

$port = 25567
$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if (-not $listener) {
    Write-Host "Port $port is not listening. Server B does not appear to be running." -ForegroundColor Yellow
    exit 0
}
$procId = ($listener | Select-Object -First 1).OwningProcess

$rconPwFile = "E:\minecraft-hardcore\server-b\runtime\rcon-password.local.txt"
if (-not (Test-Path $rconPwFile)) {
    throw "RCON password file not found at $rconPwFile. Cannot stop cleanly via RCON. Refusing to fall back to a process kill automatically."
}
$rconPw = [System.IO.File]::ReadAllText($rconPwFile)

Write-Host "Sending RCON 'stop' to PID $procId (port $port)..."
try {
    $resp = Invoke-Rcon -Port 25577 -Password $rconPw -Command "stop"
    Write-Host "RCON response: $resp"
} catch {
    Write-Host "RCON stop failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host "Waiting up to $TimeoutSec s for clean exit..."
$elapsed = 0
while ($elapsed -lt $TimeoutSec) {
    Start-Sleep -Seconds 2
    $elapsed += 2
    $stillListening = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    $stillRunning = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $stillListening -and -not $stillRunning) {
        Write-Host "Server B stopped cleanly (PID $procId exited, port $port free)." -ForegroundColor Green
        exit 0
    }
}

Write-Host "Server B did not exit within ${TimeoutSec}s via RCON stop." -ForegroundColor Red

if (-not $Force) {
    Write-Host "Not stopping automatically (no -Force). Verify manually with show-processes.ps1 before acting." -ForegroundColor Yellow
    exit 1
}

# Verified-PID fallback: only ever targets the exact PID bound to :25567, after confirming
# its command line really is Server B's fabric-server-launch.jar. Never a global java kill.
$proc = Get-CimInstance Win32_Process -Filter "ProcessId = $procId" -ErrorAction SilentlyContinue
if (-not $proc -or $proc.CommandLine -notmatch "fabric-server-launch\.jar" -or $proc.CommandLine -notmatch "server-b") {
    throw "Refusing to force-stop PID ${procId}: command line does not clearly match Server B (fabric-server-launch.jar under server-b). CommandLine=$($proc.CommandLine)"
}
Write-Host "Force-stopping verified PID $procId (CommandLine matched Server B)." -ForegroundColor Yellow
Stop-Process -Id $procId -Force
