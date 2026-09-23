<#
.SYNOPSIS
    Canonical Java executable paths for this project. Dot-source this file from any
    launch script instead of relying on the global 'java' on PATH or JAVA_HOME.

    Rationale: this machine's global 'java' resolves to Java 8, and JAVA_HOME is unset
    by design (see VERSION_LOCK.md, section "Java runtimes por componente"). Fabric
    backends (Server A / Server B) and Velocity each need a specific, different major
    Java version, so every launch script must reference the exact JDK it needs.

.USAGE
    . "$PSScriptRoot\java-paths.ps1"
    & $Java17Exe -jar fabric-server-launch.jar nogui
#>

# --- Fabric backends (Server A / Server B) - Minecraft 1.20.1 target: Java 17 ---
$Java17Home = "E:\minecraft-hardcore\tools\java17\jdk-17.0.20.1+1"
$Java17Exe  = Join-Path $Java17Home "bin\java.exe"

# --- Velocity proxy - requires Java 25 (per official docs.papermc.io/velocity/getting-started). ---
$Java25Home = "E:\minecraft-hardcore\tools\java25\jdk-25.0.4.1+1"
$Java25Exe  = Join-Path $Java25Home "bin\java.exe"

function Assert-Java17 {
    if (-not (Test-Path $Java17Exe)) {
        throw "Java 17 not found at expected path: $Java17Exe. Do not fall back to the global 'java' (Java 8) - Fabric 1.20.1 backends require Java 17 exactly."
    }
    return $Java17Exe
}

function Assert-Java25 {
    if (-not (Test-Path $Java25Exe)) {
        throw "Java 25 not found at expected path: $Java25Exe. Do not fall back to the global 'java' or to Java 17/21 - Velocity 4.2.0 requires Java 25 exactly."
    }
    return $Java25Exe
}
