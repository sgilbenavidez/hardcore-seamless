<#
.SYNOPSIS
    Diagnostic-only script. Reports available Java installations and versions.
    Does NOT change PATH, JAVA_HOME, or any global environment variable.
#>

Write-Host "=== check-java.ps1 ===" -ForegroundColor Cyan

Write-Host "`n-- java on PATH --"
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if ($javaCmd) {
    Write-Host "Path: $($javaCmd.Source)"
    & java -version
} else {
    Write-Host "No 'java' found on PATH." -ForegroundColor Yellow
}

Write-Host "`n-- Known installation roots --"
$roots = @(
    "C:\Program Files\Java",
    "C:\Program Files (x86)\Java",
    "C:\Program Files\Eclipse Adoptium",
    "C:\Program Files\Zulu"
)
foreach ($root in $roots) {
    if (Test-Path $root) {
        Write-Host "`n$root :"
        Get-ChildItem $root -Directory | ForEach-Object {
            $javaExe = Join-Path $_.FullName "bin\java.exe"
            if (Test-Path $javaExe) {
                $verOutput = & $javaExe -version 2>&1 | Select-Object -First 1
                Write-Host "  $($_.Name)  ->  $verOutput"
            }
        }
    }
}

Write-Host "`n-- Target for this project: Java 17 (see VERSION_LOCK.md) --" -ForegroundColor Cyan
Write-Host "This script only reports. It does not modify JAVA_HOME or PATH." -ForegroundColor DarkGray
