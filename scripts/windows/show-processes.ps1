<#
.SYNOPSIS
    Diagnostic-only script. Lists running java.exe processes with PID, working directory (best effort)
    and command line, so individual instances can be identified before any future stop/restart action.
    Does NOT kill or modify any process. See ARCHITECTURE.md, "Seguridad de procesos".
#>

Write-Host "=== show-processes.ps1 ===" -ForegroundColor Cyan

$procs = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'"

if (-not $procs) {
    Write-Host "No java.exe processes currently running." -ForegroundColor Green
    exit 0
}

foreach ($p in $procs) {
    Write-Host "`nPID:         $($p.ProcessId)"
    Write-Host "ParentPID:   $($p.ParentProcessId)"
    Write-Host "CommandLine: $($p.CommandLine)"
    try {
        $owner = Invoke-CimMethod -InputObject $p -MethodName GetOwner -ErrorAction Stop
        Write-Host "Owner:       $($owner.Domain)\$($owner.User)"
    } catch {
        Write-Host "Owner:       (unavailable)"
    }
}

Write-Host "`nReminder: never 'taskkill /IM java.exe'. Identify the exact instance by PID + working directory + port before acting on it." -ForegroundColor DarkYellow
