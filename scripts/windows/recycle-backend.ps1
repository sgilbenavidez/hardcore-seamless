<#
.SYNOPSIS
    Fully recycles one backend (A or B): confirms zero players, stops it cleanly, deletes its
    world directory, assigns a new seed, restarts it, and verifies it comes back healthy with the
    new seed applied. Intended to be invoked by the HardcoreCoordinator Velocity plugin as an
    external process (see FASE 5 spec section 7), but is fully usable and testable standalone.

.PARAMETER Backend
    Must be exactly "A" or "B". Never accepts a raw path - the backend directory is resolved
    internally from a fixed whitelist (see $BackendRoots below). This is a deliberate security
    boundary: nothing this script's caller supplies is ever used directly as a filesystem path.

.OUTPUTS
    Human-readable progress on stdout throughout, and exactly one final machine-readable line:
        RECYCLE_RESULT status=READY backend=A oldPid=1234 newPid=5678 oldSeed=... newSeed=... durationMs=...
    or on failure:
        RECYCLE_RESULT status=FAILED backend=A reason=<REASON> durationMs=...
    Callers (e.g. the plugin) should parse only that last line.
#>

param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("A", "B")]
    [string]$Backend
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\java-paths.ps1"
. "$PSScriptRoot\rcon-client.ps1"

function Write-Progress2 {
    param([string]$Message)
    Write-Host "[recycle-backend] $Message"
}

function Emit-Result {
    param([hashtable]$Fields)
    $parts = $Fields.Keys | ForEach-Object { "$_=$($Fields[$_])" }
    Write-Host ("RECYCLE_RESULT " + ($parts -join " "))
}

$startTime = Get-Date

# --- Step 1: resolve backend identity from a FIXED whitelist. Never from caller-supplied path. ---
$ProjectRoot = "E:\minecraft-hardcore"
$BackendRoots = @{
    "A" = Join-Path $ProjectRoot "server-a"
    "B" = Join-Path $ProjectRoot "server-b"
}
$BackendPorts = @{ "A" = 25566; "B" = 25567 }
$BackendRconPorts = @{ "A" = 25576; "B" = 25577 }
$StartScripts = @{
    "A" = Join-Path $PSScriptRoot "start-server-a.ps1"
    "B" = Join-Path $PSScriptRoot "start-server-b.ps1"
}
$StopScripts = @{
    "A" = Join-Path $PSScriptRoot "stop-server-a.ps1"
    "B" = Join-Path $PSScriptRoot "stop-server-b.ps1"
}

$backendRoot = $BackendRoots[$Backend]
$gamePort = $BackendPorts[$Backend]
$rconPort = $BackendRconPorts[$Backend]

# Defense in depth: even though $backendRoot came from the fixed map above (never from $Backend
# directly interpolated into a path), assert the canonical resolved path is exactly what we expect
# before doing anything destructive.
$canonicalBackendRoot = [System.IO.Path]::GetFullPath($backendRoot)
if ($canonicalBackendRoot -ne $backendRoot) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "BACKEND_ROOT_CANONICALIZATION_MISMATCH"; durationMs = 0 }
    exit 1
}
if (-not (Test-Path $backendRoot)) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "BACKEND_ROOT_NOT_FOUND"; durationMs = 0 }
    exit 1
}

Write-Progress2 "backend=$Backend root=$backendRoot gamePort=$gamePort rconPort=$rconPort"

# --- Step 2: read current server.properties (level-seed, level-name) and RCON password. ---
$propertiesPath = Join-Path $backendRoot "server.properties"
if (-not (Test-Path $propertiesPath)) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "SERVER_PROPERTIES_NOT_FOUND"; durationMs = 0 }
    exit 1
}
$propLines = Get-Content -LiteralPath $propertiesPath

function Get-PropertyValue {
    param([string[]]$Lines, [string]$Key)
    $line = $Lines | Where-Object { $_ -match "^\s*$([regex]::Escape($Key))\s*=" } | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -split "=", 2)[1].Trim()
}

$levelName = Get-PropertyValue -Lines $propLines -Key "level-name"
if ([string]::IsNullOrWhiteSpace($levelName)) { $levelName = "world" }
$oldSeedRaw = Get-PropertyValue -Lines $propLines -Key "level-seed"

$rconPwFile = Join-Path $backendRoot "runtime\rcon-password.local.txt"
if (-not (Test-Path $rconPwFile)) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "RCON_PASSWORD_NOT_FOUND"; durationMs = 0 }
    exit 1
}
$rconPassword = [System.IO.File]::ReadAllText($rconPwFile)

# --- Step 3: path-safety validation for the world directory we are about to delete. ---
# level-name must be a bare directory name - never a path, never "..".
if ($levelName -match '[\\/]' -or $levelName -match '\.\.') {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "UNSAFE_LEVEL_NAME"; durationMs = 0 }
    exit 1
}
$worldPath = [System.IO.Path]::GetFullPath((Join-Path $backendRoot $levelName))
$backendRootWithSep = $backendRoot.TrimEnd('\') + '\'
if (-not $worldPath.StartsWith($backendRootWithSep, [System.StringComparison]::OrdinalIgnoreCase)) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "WORLD_PATH_ESCAPES_BACKEND_ROOT"; durationMs = 0 }
    exit 1
}
if ($worldPath.TrimEnd('\').Equals($backendRoot.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "WORLD_PATH_EQUALS_BACKEND_ROOT"; durationMs = 0 }
    exit 1
}
Write-Progress2 "validated world path: $worldPath (level-name=$levelName)"

# --- Step 4: confirm zero players on THIS backend directly via RCON (independent of whatever the
# caller/plugin already checked via Velocity - defense against a player connected directly to the
# backend, bypassing the proxy; see VERSION_LOCK.md "Hallazgos de FASE 3", DIRECT_BACKEND_JOIN). ---
try {
    $listResp = Invoke-Rcon -Port $rconPort -Password $rconPassword -Command "list"
} catch {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "RCON_UNREACHABLE_PRECHECK"; durationMs = 0 }
    exit 1
}
Write-Progress2 "pre-check: $listResp"
if ($listResp -notmatch "There are 0 of a max") {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "PLAYERS_REMAIN"; durationMs = 0 }
    exit 1
}
Write-Progress2 "ZERO_PLAYERS_CONFIRMED backend=$Backend"

# Query the REAL current seed via RCON while the server is still alive - server.properties'
# level-seed is often blank (random-seed-at-first-boot backends never had it written), so it is
# not a reliable source for "old seed". This is the actual seed in use right now.
try {
    $oldSeedResp = Invoke-Rcon -Port $rconPort -Password $rconPassword -Command "seed"
    if ($oldSeedResp -match '\[(-?\d+)\]') { $oldSeedRaw = $Matches[1] }
} catch {
    # Non-fatal: fall back to whatever server.properties had (possibly blank). Old-seed is used
    # only for the differs-from-old check and for reporting, never for correctness of the recycle.
}
Write-Progress2 "old seed (live, pre-stop): $oldSeedRaw"

# --- Step 5: capture old PID, stop cleanly via the certified stop script. ---
$listener = Get-NetTCPConnection -LocalPort $gamePort -State Listen -ErrorAction SilentlyContinue
$oldPid = if ($listener) { ($listener | Select-Object -First 1).OwningProcess } else { $null }

& $StopScripts[$Backend]
if ($LASTEXITCODE -ne 0) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "STOP_FAILED"; durationMs = [int]((Get-Date) - $startTime).TotalMilliseconds }
    exit 1
}

# Belt-and-suspenders: confirm both the game port AND the RCON port are actually free.
$gameStillUp = Get-NetTCPConnection -LocalPort $gamePort -State Listen -ErrorAction SilentlyContinue
$rconStillUp = Get-NetTCPConnection -LocalPort $rconPort -State Listen -ErrorAction SilentlyContinue
if ($gameStillUp -or $rconStillUp) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "PORTS_NOT_FREED_AFTER_STOP"; durationMs = [int]((Get-Date) - $startTime).TotalMilliseconds }
    exit 1
}
Write-Progress2 "BACKEND_STOPPED backend=$Backend oldPid=$oldPid"

# --- Step 6: delete the world directory (only after stop confirmed + path validated above). ---
if (Test-Path $worldPath) {
    Remove-Item -LiteralPath $worldPath -Recurse -Force
}
if (Test-Path $worldPath) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "WORLD_DELETE_FAILED"; durationMs = [int]((Get-Date) - $startTime).TotalMilliseconds }
    exit 1
}
Write-Progress2 "WORLD_DELETED backend=$Backend path=$worldPath"

# --- Step 7: generate a new 64-bit seed, distinct from the old one, write it into server.properties
# without touching any other line. ---
function New-RandomSeed64 {
    $rng = [System.Security.Cryptography.RNGCryptoServiceProvider]::new()
    $bytes = New-Object byte[] 8
    $rng.GetBytes($bytes)
    return [System.BitConverter]::ToInt64($bytes, 0)
}

$newSeed = New-RandomSeed64
$attempts = 0
while ($oldSeedRaw -and ($newSeed.ToString() -eq $oldSeedRaw) -and $attempts -lt 5) {
    $newSeed = New-RandomSeed64
    $attempts++
}

$newPropLines = $propLines | ForEach-Object {
    if ($_ -match "^\s*level-seed\s*=") { "level-seed=$newSeed" } else { $_ }
}
# Atomic-ish write: temp file then move, so a crash mid-write never leaves a truncated properties file.
$tempPropsPath = "$propertiesPath.tmp"
[System.IO.File]::WriteAllLines($tempPropsPath, $newPropLines)
Move-Item -LiteralPath $tempPropsPath -Destination $propertiesPath -Force
Write-Progress2 "NEW_SEED_ASSIGNED backend=$Backend newSeed=$newSeed"

# --- Step 8: start the backend detached (non-blocking - start-server-X.ps1 runs in the foreground
# by design, which doesn't fit here since this script needs to keep running after launch). ---
function Start-BackendDetached {
    $javaExe = Assert-Java17
    $logPath = Join-Path $backendRoot "runtime\recycle-boot.log"
    New-Item -ItemType Directory -Force -Path (Split-Path $logPath) | Out-Null
    # -Dhardcore.backendId must match what start-server-a.ps1/start-server-b.ps1 pass, or
    # HardcoreDeathSignal loses its identity after every recycle (see FASE 7 spec section 11).
    $backendIdMap = @{ "A" = "server-a"; "B" = "server-b" }
    $backendIdArg = "-Dhardcore.backendId=$($backendIdMap[$Backend])"
    $proc = Start-Process -FilePath $javaExe `
        -ArgumentList $backendIdArg, "-Xms2G", "-Xmx4G", "-jar", "fabric-server-launch.jar", "nogui" `
        -WorkingDirectory $backendRoot `
        -RedirectStandardOutput $logPath `
        -RedirectStandardError "$logPath.err" `
        -PassThru -WindowStyle Hidden
    return $proc.Id
}

function Wait-BackendReady {
    param([int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $l = Get-NetTCPConnection -LocalPort $gamePort -State Listen -ErrorAction SilentlyContinue
        if ($l) {
            try {
                $resp = Invoke-Rcon -Port $rconPort -Password $rconPassword -Command "list" -TimeoutMs 2000
                if ($resp) { return $true }
            } catch { }
        }
        Start-Sleep -Milliseconds 1000
    }
    return $false
}

$newPid = $null
$ready = $false
for ($attempt = 1; $attempt -le 2 -and -not $ready; $attempt++) {
    Write-Progress2 "start attempt $attempt"
    $newPid = Start-BackendDetached
    $ready = Wait-BackendReady -TimeoutSec 60
    if (-not $ready) {
        Write-Progress2 "attempt $attempt did not become ready within timeout"
        $proc = Get-Process -Id $newPid -ErrorAction SilentlyContinue
        if ($proc) { Stop-Process -Id $newPid -Force -ErrorAction SilentlyContinue }
    }
}

if (-not $ready) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "START_TIMEOUT"; durationMs = [int]((Get-Date) - $startTime).TotalMilliseconds }
    exit 1
}
Write-Progress2 "BACKEND_STARTED backend=$Backend newPid=$newPid"

# --- Step 9: verify the actual applied seed matches what we requested. ---
try {
    $seedResp = Invoke-Rcon -Port $rconPort -Password $rconPassword -Command "seed"
} catch {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "SEED_QUERY_FAILED"; durationMs = [int]((Get-Date) - $startTime).TotalMilliseconds }
    exit 1
}
if ($seedResp -notmatch '\[(-?\d+)\]') {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "SEED_RESPONSE_UNPARSEABLE"; durationMs = [int]((Get-Date) - $startTime).TotalMilliseconds }
    exit 1
}
$actualSeed = $Matches[1]
if ($actualSeed -ne $newSeed.ToString()) {
    Emit-Result @{ status = "FAILED"; backend = $Backend; reason = "SEED_MISMATCH"; durationMs = [int]((Get-Date) - $startTime).TotalMilliseconds }
    exit 1
}

$durationMs = [int]((Get-Date) - $startTime).TotalMilliseconds
Write-Progress2 "READY backend=$Backend seed=$actualSeed durationMs=$durationMs"
Emit-Result @{
    status    = "READY"
    backend   = $Backend
    oldPid    = $oldPid
    newPid    = $newPid
    oldSeed   = $oldSeedRaw
    newSeed   = $actualSeed
    durationMs = $durationMs
}
exit 0
