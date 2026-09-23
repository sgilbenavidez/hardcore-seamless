<#
.SYNOPSIS
    Builds the HardcoreCoordinator Velocity plugin via its Gradle wrapper, using the canonical
    Java 25 (matches Velocity's own runtime requirement and the plugin's toolchain target).
    JAVA_HOME/PATH are set for this process only - never touches the global environment.
#>

. "$PSScriptRoot\java-paths.ps1"

Write-Host "=== build-hardcore-coordinator.ps1 ===" -ForegroundColor Cyan

$javaExe = Assert-Java25
$javaHome = Split-Path (Split-Path $javaExe)
Write-Host "Using Java 25 toolchain: $javaHome"

$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$env:PATH"

$projectDir = "E:\minecraft-hardcore\velocity-plugin\hardcore-coordinator"
if (-not (Test-Path (Join-Path $projectDir "gradlew.bat"))) {
    throw "gradlew.bat not found in $projectDir."
}

Push-Location $projectDir
try {
    # 'clean build' (not just 'build') so build\libs never holds a stale jar from a previous
    # version number - Gradle's jar task doesn't delete old differently-named outputs on its own.
    & .\gradlew.bat clean build --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

$jars = Get-ChildItem -Path (Join-Path $projectDir "build\libs") -Filter "hardcore-coordinator-*.jar"
if ($jars.Count -eq 0) {
    throw "Build reported success but no jar was found in build\libs."
}
if ($jars.Count -gt 1) {
    throw "Build produced more than one jar in build\libs: $($jars.Name -join ', ')"
}
Write-Host "Built: $($jars[0].FullName)" -ForegroundColor Green
