# Checks that JourneySync is optional on both sides, with real dev runs:
#   A. Server with JourneySync, client "Plain" without it: the client joins, and the server sends it nothing.
#   B. Server without JourneySync, client "Developer" with it: the client joins and gives up waiting for the server.
# Uses the same dev server folder and settings as two-clients.ps1; "Plain" plays in run\client3. Prints PASS or FAIL
# per check and stops everything at the end. Exit code 1 if any check failed.
param([int]$HeapMb = 1536)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
New-Item -ItemType Directory -Force build | Out-Null
$rcon = "$root\..\gtnh-rcon-fix\scripts\rcon.py"
$failed = 0

function Start-Gradle([string]$name, [string[]]$gradleArgs) {
    $log = "build\optional-$name.log"
    if (Test-Path $log) { Remove-Item $log }
    $p = Start-Process -FilePath "$root\gradlew.bat" -ArgumentList ($gradleArgs + "-PheapMb=$HeapMb", '--console=plain') `
        -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $p.PriorityClass = 'BelowNormal'
    return $log
}

function Wait-ForLine([string]$log, [string]$pattern, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $log) -and (Select-String -Path $log -Pattern $pattern -Quiet)) { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Check([string]$what, [bool]$ok) {
    Write-Output ("{0}  {1}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $what)
    if (-not $ok) { $script:failed++ }
}

function Stop-All {
    python $rcon 'stop' 2>$null | Out-Null
    Start-Sleep -Seconds 5
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -like "*$root*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
}

# Muted, like the other test clients.
New-Item -ItemType Directory -Force run\client3 | Out-Null
Set-Content run\client3\options.txt @('soundCategory_master:0.0', 'soundCategory_music:0.0', 'pauseOnLostFocus:false', 'renderDistance:6', 'maxFps:30')

Write-Output '--- A: server with JourneySync, client without'
$server = Start-Gradle 'server-with' @('runServer')
if (-not (Wait-ForLine $server 'Done \(' 600)) { throw 'Server did not start' }
$client = Start-Gradle 'client-without' @('runClient', '-Pjoin=127.0.0.1:25570', '-PclientDir=run/client3', '-Pusername=Plain', '-PwithoutJourneySync')
Check 'Plain (no JourneySync) joins a server that has it' (Wait-ForLine $server 'Plain joined the game' 600)
Check 'Plain loaded without JourneySync' (-not (Select-String -Path run\client3\logs\fml-client-latest.log -Pattern 'journeysync\.JourneySyncLateMixins|Registered \d+ network packets' -Quiet))
Start-Sleep -Seconds 20
Check 'Server logged no JourneySync session for Plain' (-not (Select-String -Path $server -Pattern 'Plain joined with JourneySync' -Quiet))
Check 'Nobody was refused' (-not (Select-String -Path $server -Pattern 'Rejecting connection|Mod rejections' -Quiet))
Stop-All

Write-Output '--- B: server without JourneySync, client with'
$server = Start-Gradle 'server-without' @('runServer', '-PwithoutJourneySync')
if (-not (Wait-ForLine $server 'Done \(' 600)) { throw 'Server did not start' }
Check 'Server loaded without JourneySync' (-not (Select-String -Path $server -Pattern 'Shared map .*revision|Registered \d+ network packets on channel journeysync' -Quiet))
$client = Start-Gradle 'client-with' @('runClient', '-Pjoin=127.0.0.1:25570')
Check 'Developer (with JourneySync) joins a server without it' (Wait-ForLine $server 'Developer joined the game' 600)
Check "Developer's JourneySync gives up waiting for the server" (Wait-ForLine run\client\logs\fml-client-latest.log "doesn't have JourneySync" 90)
Stop-All

Write-Output ("{0} check(s) failed" -f $failed)
exit ([int]($failed -gt 0))
