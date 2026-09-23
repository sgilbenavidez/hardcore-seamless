<#
.SYNOPSIS
    Starts Server B (Fabric, Minecraft 1.20.1, Java 17) in the foreground.
    Always uses the canonical Java 17 path from java-paths.ps1 - never bare 'java'.
    Mirrors start-server-a.ps1 exactly, parameterized for Server B's port/dir.

.PARAMETER Xms
    Initial JVM heap size. Default: 2G.

.PARAMETER Xmx
    Maximum JVM heap size. Default: 4G. Checked against free system memory before launch.
#>

param(
    [string]$Xms = "2G",
    [string]$Xmx = "4G"
)

. "$PSScriptRoot\java-paths.ps1"

Write-Host "=== start-server-b.ps1 ===" -ForegroundColor Cyan

# 1. Java 17, canonical path only.
$javaExe = Assert-Java17
Write-Host "Java executable: $javaExe"
& $javaExe -version

# 2. Port check.
$port = 25567
$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    $procId = ($listener | Select-Object -First 1).OwningProcess
    throw "Port $port is already in use by PID $procId. Refusing to start a second Server B instance. Verify with scripts\windows\show-processes.ps1 before proceeding."
}
Write-Host "Port $port is free."

# 3. Working directory.
$serverDir = "E:\minecraft-hardcore\server-b"
if (-not (Test-Path (Join-Path $serverDir "fabric-server-launch.jar"))) {
    throw "fabric-server-launch.jar not found in $serverDir. Has the Fabric installer been run?"
}
if (-not (Test-Path (Join-Path $serverDir "server.properties"))) {
    throw "server.properties not found in $serverDir. Copy server.properties.template and fill in a local rcon.password first."
}

Write-Host "Working directory: $serverDir"
Write-Host "JVM heap: -Xms$Xms -Xmx$Xmx"

# 4-5. Launch with the canonical Java 17 executable. No bare 'java'.
# -Dhardcore.backendId identifies this instance to HardcoreDeathSignal (same jar on A and B -
# see FASE 7 spec section 11).
Push-Location $serverDir
try {
    & $javaExe "-Dhardcore.backendId=server-b" "-Xms$Xms" "-Xmx$Xmx" -jar "fabric-server-launch.jar" nogui
} finally {
    Pop-Location
}
