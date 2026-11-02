# Starts the dev server and two dev clients that join it, for testing sync between players on one machine.
#   .\scripts\two-clients.ps1 -Start      server on 127.0.0.1:25570, then "Developer" (run\client) and "Alex" (run\client2)
#   .\scripts\two-clients.ps1 -Stop       stops the clients and the server
#   .\scripts\two-clients.ps1 -StopClients, then -Start -ClientsOnly    restart only the clients
# Test clients start muted (options.txt), so they don't play over a real game.
# Logs: build\two-clients-<name>.log. Drive the players over RCON with ..\gtnh-rcon-fix\scripts\rcon.py, which
# reads run\server\server.properties: python ..\gtnh-rcon-fix\scripts\rcon.py "tp Alex 2000 100 2000"
# Needs run\server\server.properties (127.0.0.1, online-mode=false, enable-rcon) and run\server\eula.txt, and the
# gtnh-rcon-fix repo built next to this one (vanilla 1.7.10 RCON drops the connection after login).
param([switch]$Start, [switch]$Stop, [switch]$StopClients, [switch]$ClientsOnly, [int]$HeapMb = 1536)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
New-Item -ItemType Directory -Force build | Out-Null
$pidFile = 'build\two-clients.pids'

function Start-Gradle([string]$name, [string[]]$gradleArgs) {
    $log = "build\two-clients-$name.log"
    $p = Start-Process -FilePath "$root\gradlew.bat" -ArgumentList ($gradleArgs + "-PheapMb=$HeapMb", '--console=plain') `
        -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $p.PriorityClass = 'BelowNormal'
    Add-Content $pidFile "$name $($p.Id)"
    return $log
}

function Wait-ForLine([string]$log, [string]$pattern, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $log) -and (Select-String -Path $log -Pattern $pattern -Quiet)) { return }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for '$pattern' in $log"
}

function Wait-ForJoins([string]$log, [int]$count, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        if (@(Select-String -Path $log -Pattern 'joined the game').Count -ge $count) { return }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for $count joins in $log"
}

function Set-TestClientOptions([string]$dir) {
    # Muted, unpaused when unfocused, short render distance: test clients run in the background next to a real game.
    $file = Join-Path $dir 'options.txt'
    New-Item -ItemType Directory -Force $dir | Out-Null
    $wanted = [ordered]@{ soundCategory_master = '0.0'; soundCategory_music = '0.0'; pauseOnLostFocus = 'false'; renderDistance = '6'; maxFps = '30' }
    $lines = if (Test-Path $file) { @(Get-Content $file | Where-Object { $wanted.Keys -notcontains ($_ -split ':', 2)[0] }) } else { @() }
    $lines += $wanted.Keys | ForEach-Object { "${_}:$($wanted[$_])" }
    Set-Content -Path $file -Value $lines
}

if ($Start) {
    if (Test-Path $pidFile) { Remove-Item $pidFile }
    Set-TestClientOptions 'run\client'
    Set-TestClientOptions 'run\client2'
    $serverLog = 'build\two-clients-server.log'
    if ($ClientsOnly) {
        # Clients that leave and rejoin show up again in the same server log; only count joins from now on.
        $joinedBefore = @(Select-String -Path $serverLog -Pattern 'joined the game').Count
    } else {
        $serverLog = Start-Gradle 'server' @('runServer')
        Wait-ForLine $serverLog 'Done \(' 600
        Write-Output 'Server is up on 127.0.0.1:25570'
        $joinedBefore = 0
    }
    $a = Start-Gradle 'developer' @('runClient', '-Pjoin=127.0.0.1:25570')
    Wait-ForJoins $serverLog ($joinedBefore + 1) 600
    Write-Output 'Developer joined'
    $b = Start-Gradle 'alex' @('runClient', '-Pjoin=127.0.0.1:25570', '-PclientDir=run/client2', '-Pusername=Alex')
    Wait-ForJoins $serverLog ($joinedBefore + 2) 600
    Write-Output 'Alex joined'
}

if ($StopClients) {
    # Game clients are the java processes started with this repo's run\client folders as --gameDir.
    $clients = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*--gameDir*$root\run\client*" }
    foreach ($c in $clients) { Stop-Process -Id $c.ProcessId -ErrorAction SilentlyContinue }
    Write-Output "Stopped $($clients.Count) client(s)"
}

if ($Stop) {
    # Gradle's JavaExec children (the game processes) outlive gradlew.bat, so stop them by command line. Only
    # processes whose command line names this repo match, which never includes a Prism instance.
    python "$root\..\gtnh-rcon-fix\scripts\rcon.py" 'stop'
    $games = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*$root*" }
    Start-Sleep -Seconds 5
    foreach ($g in $games) { Stop-Process -Id $g.ProcessId -ErrorAction SilentlyContinue }
    if (Test-Path $pidFile) {
        foreach ($line in Get-Content $pidFile) { Stop-Process -Id ([int]($line -split ' ')[1]) -ErrorAction SilentlyContinue }
        Remove-Item $pidFile
    }
    Write-Output "Stopped $($games.Count) game process(es)"
}
