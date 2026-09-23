<#
.SYNOPSIS
    Starts the Velocity proxy in the foreground. Always uses the canonical Java 25 path
    from java-paths.ps1 - never bare 'java', never Java 17/21.

.PARAMETER Xms
    Initial JVM heap size. Default: 512M.

.PARAMETER Xmx
    Maximum JVM heap size. Default: 1G.
#>

param(
    [string]$Xms = "512M",
    [string]$Xmx = "1G"
)

. "$PSScriptRoot\java-paths.ps1"

Write-Host "=== start-velocity.ps1 ===" -ForegroundColor Cyan

# 1-2. Java 25, canonical path only.
$javaExe = Assert-Java25
Write-Host "Java executable: $javaExe"
& $javaExe -version

# 3. Port check.
$port = 25565
$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    $procId = ($listener | Select-Object -First 1).OwningProcess
    throw "Port $port is already in use by PID $procId. Refusing to start a second Velocity instance. Verify with scripts\windows\show-processes.ps1 before proceeding."
}
Write-Host "Port $port is free."

# 4. Working directory.
$veloDir = "E:\minecraft-hardcore\proxy\velocity"
$jar = Get-ChildItem -Path $veloDir -Filter "velocity-*.jar" | Select-Object -First 1
if (-not $jar) {
    throw "No velocity-*.jar found in $veloDir."
}
if (-not (Test-Path (Join-Path $veloDir "velocity.toml"))) {
    throw "velocity.toml not found in $veloDir. Copy velocity.toml.template and configure it first."
}
if (-not (Test-Path (Join-Path $veloDir "forwarding.secret"))) {
    throw "forwarding.secret not found in $veloDir. Required for modern player-info forwarding."
}

Write-Host "Working directory: $veloDir"
Write-Host "Jar: $($jar.Name)"
Write-Host "JVM heap: -Xms$Xms -Xmx$Xmx"
Write-Host ""
Write-Host "This runs in the foreground. To stop cleanly, type 'end' at this console (or Ctrl+C)." -ForegroundColor Yellow
Write-Host "For a non-interactive/remote stop, use stop-velocity.ps1 (PID-verified; Velocity has no RCON)." -ForegroundColor Yellow

# 5-6. Launch with the canonical Java 25 executable. No bare 'java'.
Push-Location $veloDir
try {
    & $javaExe "-Xms$Xms" "-Xmx$Xmx" -jar $jar.Name
} finally {
    Pop-Location
}
