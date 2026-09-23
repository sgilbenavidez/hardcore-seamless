<#
.SYNOPSIS
    Compares server-a/mods and server-b/mods by filename + SHA-256 hash.
    Diagnostic only - does not copy or modify anything.

.OUTPUTS
    Prints MODSET_PARITY = PASS if both directories contain exactly the same filenames
    with matching hashes, otherwise MODSET_PARITY = FAIL with the differences.
#>

Write-Host "=== compare-server-mods.ps1 ===" -ForegroundColor Cyan

function Get-ModHashes {
    param([string]$Dir)
    $result = @{}
    if (-not (Test-Path $Dir)) { return $result }
    Get-ChildItem -Path $Dir -Filter "*.jar" -File | ForEach-Object {
        $hash = (Get-FileHash -Path $_.FullName -Algorithm SHA256).Hash.ToLower()
        $result[$_.Name] = $hash
    }
    return $result
}

$modsA = Get-ModHashes "E:\minecraft-hardcore\server-a\mods"
$modsB = Get-ModHashes "E:\minecraft-hardcore\server-b\mods"

$allNames = ($modsA.Keys + $modsB.Keys) | Sort-Object -Unique
$mismatch = $false

foreach ($name in $allNames) {
    $hashA = $modsA[$name]
    $hashB = $modsB[$name]
    if (-not $hashA) {
        Write-Host "MISSING_IN_A: $name (only in B, hash $hashB)" -ForegroundColor Red
        $mismatch = $true
    } elseif (-not $hashB) {
        Write-Host "MISSING_IN_B: $name (only in A, hash $hashA)" -ForegroundColor Red
        $mismatch = $true
    } elseif ($hashA -ne $hashB) {
        Write-Host "HASH_MISMATCH: $name  A=$hashA  B=$hashB" -ForegroundColor Red
        $mismatch = $true
    } else {
        Write-Host "OK: $name  ($hashA)" -ForegroundColor Green
    }
}

if ($mismatch) {
    Write-Host "MODSET_PARITY = FAIL" -ForegroundColor Red
    exit 1
} else {
    Write-Host "MODSET_PARITY = PASS ($($allNames.Count) jar(s) compared)" -ForegroundColor Green
    exit 0
}
