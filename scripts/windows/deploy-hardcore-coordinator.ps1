<#
.SYNOPSIS
    Copies only the built HardcoreCoordinator plugin jar into proxy/velocity/plugins/ - never
    sources, tests, or the Gradle cache. Does NOT start/stop Velocity itself; the caller is
    responsible for stopping Velocity first and starting it again afterward (see FASE 4 report /
    ARCHITECTURE.md for the deploy sequence: A and B stay up throughout, only Velocity restarts).
#>

$projectDir = "E:\minecraft-hardcore\velocity-plugin\hardcore-coordinator"
$pluginsDir = "E:\minecraft-hardcore\proxy\velocity\plugins"

Write-Host "=== deploy-hardcore-coordinator.ps1 ===" -ForegroundColor Cyan

$jar = Get-ChildItem -Path (Join-Path $projectDir "build\libs") -Filter "hardcore-coordinator-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) {
    throw "No built jar found in $projectDir\build\libs. Run build-hardcore-coordinator.ps1 first."
}

New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null

# Remove any older hardcore-coordinator jar so Velocity never has two versions loaded at once.
Get-ChildItem -Path $pluginsDir -Filter "hardcore-coordinator-*.jar" -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "Removing stale jar: $($_.Name)" -ForegroundColor Yellow
    Remove-Item -LiteralPath $_.FullName -Force
}

$dest = Join-Path $pluginsDir $jar.Name
Copy-Item -LiteralPath $jar.FullName -Destination $dest -Force
Write-Host "Deployed: $dest" -ForegroundColor Green
