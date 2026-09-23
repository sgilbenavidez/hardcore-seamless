<#
.SYNOPSIS
    Stops the Velocity proxy. Velocity has no RCON or remote console protocol out of the box
    (unlike the Fabric backends), and we deliberately did not install a plugin to add one (see
    ARCHITECTURE.md - no extra plugins in FASE 3). Its own console command for a graceful stop
    is 'end', but that only works if you're typed into its actual console (see start-velocity.ps1).

    This script therefore stops Velocity via a PID-verified Stop-Process - NOT a graceful 'end' -
    but only ever targets the exact PID bound to :25565 after confirming its command line really
    is the Velocity jar. Never a global 'taskkill /IM java.exe', and never touches Server A/B.

.PARAMETER Force
    Required to actually terminate the process, since this is a non-graceful stop by necessity.
    Without it, the script only reports what it would do.
#>

param(
    [switch]$Force
)

Write-Host "=== stop-velocity.ps1 ===" -ForegroundColor Cyan

$port = 25565
$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if (-not $listener) {
    Write-Host "Port $port is not listening. Velocity does not appear to be running." -ForegroundColor Yellow
    exit 0
}
$procId = ($listener | Select-Object -First 1).OwningProcess

$proc = Get-CimInstance Win32_Process -Filter "ProcessId = $procId" -ErrorAction SilentlyContinue
if (-not $proc -or $proc.CommandLine -notmatch "velocity-.*\.jar") {
    throw "Refusing to stop PID ${procId}: command line does not clearly match Velocity (velocity-*.jar). CommandLine=$($proc.CommandLine)"
}

Write-Host "Verified PID $procId is Velocity: $($proc.CommandLine)"

if (-not $Force) {
    Write-Host "This would be a non-graceful stop (no RCON/console channel available externally)." -ForegroundColor Yellow
    Write-Host "Prefer typing 'end' at Velocity's own console if it is running attached/foreground." -ForegroundColor Yellow
    Write-Host "Re-run with -Force to actually stop PID $procId now." -ForegroundColor Yellow
    exit 1
}

Stop-Process -Id $procId -Force
Start-Sleep -Seconds 2
$still = Get-Process -Id $procId -ErrorAction SilentlyContinue
if ($still) {
    Write-Host "PID $procId still running after Stop-Process." -ForegroundColor Red
    exit 1
} else {
    Write-Host "Velocity (PID $procId) stopped." -ForegroundColor Green
}
