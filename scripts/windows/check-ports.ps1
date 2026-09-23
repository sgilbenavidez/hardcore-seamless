<#
.SYNOPSIS
    Diagnostic-only script. Reports whether the project's internal ports are free or in use.
    Does NOT open, close, or forward any port. See ARCHITECTURE.md for the port map.
#>

Write-Host "=== check-ports.ps1 ===" -ForegroundColor Cyan

$ports = @{
    25565 = "Velocity (public entrypoint)"
    25566 = "Server A (internal backend)"
    25567 = "Server B (internal backend)"
}

foreach ($port in $ports.Keys | Sort-Object) {
    $label = $ports[$port]
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        $procId = ($listener | Select-Object -First 1).OwningProcess
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        $procName = if ($proc) { $proc.ProcessName } else { "unknown" }
        Write-Host "Port $port [$label] : IN USE by PID $procId ($procName)" -ForegroundColor Yellow
    } else {
        Write-Host "Port $port [$label] : FREE" -ForegroundColor Green
    }
}
