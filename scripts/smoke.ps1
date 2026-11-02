# Runs the chunk layer smoke test end to end: a demo dev server (127.0.0.1:25570, with a furnace force-loaded for
# "Steve"), a muted dev client that joins it, checks the layer on the fullscreen map and the minimap, and quits; then
# the server is stopped. Prints the report.
#   .\scripts\smoke.ps1
#   .\scripts\smoke.ps1 -JourneyMapJar libs\journeymap-1.7.10-5.2.23-fairplay.jar
# Screenshots land in run\client\screenshots, the report in run\client\journeysync-smoke\report.txt.
param([string]$JourneyMapJar = '')

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
Set-Location $root

$extra = @()
if ($JourneyMapJar) { $extra += "-PjourneymapJar=$((Resolve-Path $JourneyMapJar).Path)" }

# Muted: test clients usually run next to a real game.
$options = 'run\client\options.txt'
New-Item -ItemType Directory -Force run\client | Out-Null
$lines = if (Test-Path $options) { @(Get-Content $options | Where-Object { $_ -notmatch '^(soundCategory_master|soundCategory_music):' }) } else { @() }
Set-Content $options ($lines + 'soundCategory_master:0.0', 'soundCategory_music:0.0')

$serverLog = "$root\run\server\logs\latest.log"
$startedAt = Get-Date
$server = Start-Process powershell -ArgumentList (@('-NoProfile', '-File', "$root\dev.ps1", 'runServer', '-Pdemo') + $extra) `
    -WorkingDirectory $root -WindowStyle Hidden -PassThru
try {
    while (-not ((Test-Path $serverLog) -and (Get-Item $serverLog).LastWriteTime -gt $startedAt -and
            (Select-String -Path $serverLog -Pattern 'Done \(' -Quiet))) {
        if ($server.HasExited) { throw "dev server exited during boot, see $serverLog" }
        if (((Get-Date) - $startedAt).TotalSeconds -gt 600) { throw 'dev server not up after 600 s' }
        Start-Sleep -Seconds 2
    }
    'dev server up'
    Remove-Item run\client\journeysync-smoke\report.txt -ErrorAction SilentlyContinue
    & "$root\dev.ps1" runClient '-Pjoin=127.0.0.1:25570' -Psmoke @extra | Out-Null
} finally {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -match 'GradleStartServer' -and $_.CommandLine -match [regex]::Escape($root) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    'dev server stopped'
}
Get-Content run\client\journeysync-smoke\report.txt
if (-not (Select-String -Path run\client\journeysync-smoke\report.txt -Pattern 'RESULT PASS' -Quiet)) { exit 1 }
