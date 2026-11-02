# Tests JourneySync in full GTNH: a local copy of the real 2.9.0 server and Prism test instances copied from the 2.9.0
# play instance. Close your game first: a full client and server need about 10 GB, two clients about 15 GB.
#   .\scripts\pack-test.ps1 -CopyServer     copy the server backup into -ServerCopy, local-only (127.0.0.1:25568, no
#                                           query listener, no web map server)
#   .\scripts\pack-test.ps1 -Setup          install this jar and RCON Fix into the server copy (removing any old
#                                           chunkoverlay jar), turn RCON on, and create the muted test instance with
#                                           this jar. -MapFrom <folder> copies an existing JourneyMap map into it.
#                                           -TwoClients also creates a second instance for an offline Prism account
#                                           (-Profile2) and puts the server copy in offline mode so it can join.
#   .\scripts\pack-test.ps1 -Server         boot the server copy in the background; log in <copy>\logs\console.txt
#   .\scripts\pack-test.ps1 -Launch [-Second]           launch a test instance through Prism. Join from the
#                                           multiplayer menu: its server list holds only the local test server.
#                                           -QuickJoin joins straight away with Prism's --server, but on an offline-mode
#                                           server that races Forge's login handshake (NullPointerException on
#                                           "playClientBlock"), so it only suits -Setup without -TwoClients.
#   .\scripts\pack-test.ps1 -Stop           stop the test clients and the server copy
#   .\scripts\pack-test.ps1 -WipeClientMap [-Second]    delete a test instance's JourneyMap data and JourneySync state
# Drive the server with: python ..\gtnh-rcon-fix\scripts\rcon.py --properties <copy>\server.properties "list"
# Nothing is read from or written to Akliz, and the play instance is only read. Restart Prism once after creating an
# instance, so it sees it.
param(
    [string]$Backup = "$env:USERPROFILE\GTNH-backups\akliz-2.9.0-2026-11-01\server",
    [string]$ServerCopy = "$env:USERPROFILE\gtnh-local-server-2.9.0",
    [string]$Source = "$env:APPDATA\PrismLauncher\instances\GT_New_Horizons_2.9.0_Java_17-26",
    [string]$Name = 'GTNH_2.9.0_JourneySync_Test',
    [string]$Name2 = 'GTNH_2.9.0_JourneySync_Test2',
    [string]$Profile2 = 'dev1',
    [string]$ServerName = 'LocalTest',
    [string]$Prism = "$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe",
    [string]$MapFrom = '',
    [int]$Port = 25568,
    [int]$RconPort = 25578,
    [string]$ServerMemory = '4G',
    [int]$ClientMemoryMB = 4608,
    [switch]$CopyServer,
    [switch]$Setup,
    [switch]$TwoClients,
    [switch]$Server,
    [switch]$Launch,
    [switch]$Second,
    [switch]$QuickJoin,
    [switch]$Stop,
    [switch]$WipeClientMap
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$instancesDir = Split-Path $Source
$rcon = Join-Path $root '..\gtnh-rcon-fix\scripts\rcon.py'

function Game-Dir([string]$instanceName) { Join-Path (Join-Path $instancesDir $instanceName) '.minecraft' }

function Latest-Jar([string]$dir, [string]$prefix) {
    $jar = Get-ChildItem $dir -Filter "$prefix-*.jar" | Where-Object { $_.Name -notmatch '-(dev|sources)\.jar$' } |
        Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $jar) { throw "no $prefix jar in $dir; build it first" }
    return $jar
}

# The folder JourneyMap uses for this server: the server-list name (or "Command Line" for a --server quick join), then
# the world id JourneyMap Server sends, with non-word characters turned into ~.
function Map-Folder([string]$game) {
    $cfg = Get-Content "$ServerCopy\config\JourneyMapServer\world.cfg" -Raw | ConvertFrom-Json
    $suffix = if ($cfg.UseWorldID) { '_' + ($cfg.WorldID -replace '\W+', '~') } else { '' }
    $listName = if ($QuickJoin) { 'Command Line' } else { $ServerName }
    return Join-Path $game ("journeymap\data\mp\" + ($listName -replace '\W+', '~') + $suffix)
}

function Set-Properties([string]$file, [hashtable]$values) {
    $keys = $values.Keys -join '|'
    $props = Get-Content $file | Where-Object { $_ -notmatch "^($keys)=" }
    $props + ($values.Keys | ForEach-Object { "$_=$($values[$_])" }) | Set-Content $file -Encoding ascii
}

function New-TestInstance([string]$instanceName, [string]$displayName) {
    $instance = Join-Path $instancesDir $instanceName
    if ($instance -eq $Source) { throw 'test instance would overwrite the play instance' }
    "copying the instance to $instanceName..."
    robocopy $Source $instance /MIR /XD (Join-Path $Source '.minecraft\saves') (Join-Path $Source '.minecraft\logs') `
        (Join-Path $Source '.minecraft\crash-reports') (Join-Path $Source '.minecraft\screenshots') `
        (Join-Path $Source '.minecraft\journeymap\data') (Join-Path $Source '.minecraft\config\journeysync') /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE)" }
    # Prism reads these from [General]; a running Prism rewrites instance.cfg for instances it has loaded.
    $settings = @("name=$displayName", "MaxMemAlloc=$ClientMemoryMB", 'OverrideMemory=true')
    $cfg = Get-Content "$instance\instance.cfg" | Where-Object { $_ -notmatch '^(name|MaxMemAlloc|OverrideMemory)=' }
    $out = foreach ($line in $cfg) { $line; if ($line -eq '[General]') { $settings } }
    $out | Set-Content "$instance\instance.cfg" -Encoding utf8

    $game = Join-Path $instance '.minecraft'
    # Muted and not paused when unfocused: test clients run in the background.
    $options = "$game\options.txt"
    $wanted = @{ soundCategory_master = '0.0'; soundCategory_music = '0.0'; pauseOnLostFocus = 'false' }
    $lines = @(Get-Content $options | Where-Object { $wanted.Keys -notcontains ($_ -split ':', 2)[0] })
    $lines += $wanted.Keys | ForEach-Object { "${_}:$($wanted[$_])" }
    Set-Content $options $lines

    # Only the local test server in the list, so nobody joins a real server from a test instance.
    python (Join-Path $PSScriptRoot 'servers_dat.py') "$game\servers.dat" $ServerName "127.0.0.1:$Port"

    Get-ChildItem "$game\mods" -Filter 'journeysync-*.jar' | Remove-Item
    Get-ChildItem "$game\mods" -Filter 'chunkoverlay-*.jar' | Remove-Item
    Copy-Item (Latest-Jar "$root\build\libs" 'journeysync').FullName "$game\mods\"
    return $game
}

if ($CopyServer) {
    'copying the server...'
    robocopy $Backup $ServerCopy /MIR /XD "$Backup\logs" "$Backup\World-uppercase-empty-stub" /XF session.lock /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed ($LASTEXITCODE)" }
    # Local only, so nothing listens on the network and Windows Firewall never prompts.
    Set-Properties "$ServerCopy\server.properties" @{ 'server-ip' = '127.0.0.1'; 'server-port' = $Port; 'enable-query' = 'false'; 'motd' = 'GTNH 2.9.0 local test copy' }
    $dynmap = "$ServerCopy\dynmap\configuration.txt"
    if (Test-Path $dynmap) {
        (Get-Content $dynmap) -replace '^disable-webserver: false', 'disable-webserver: true' | Set-Content $dynmap -Encoding utf8
    }
    "server copy ready at $ServerCopy (127.0.0.1:$Port)"
    exit 0
}

if ($Setup) {
    if (-not (Test-Path "$ServerCopy\server.properties")) { throw "no server copy at $ServerCopy; run with -CopyServer first" }
    foreach ($prefix in 'journeysync', 'rconfix', 'chunkoverlay') {
        Get-ChildItem "$ServerCopy\mods" -Filter "$prefix-*.jar" | Remove-Item
    }
    Copy-Item (Latest-Jar "$root\build\libs" 'journeysync').FullName "$ServerCopy\mods\"
    Copy-Item (Latest-Jar "$root\..\gtnh-rcon-fix\build\libs" 'rconfix').FullName "$ServerCopy\mods\"
    $password = -join ((1..32) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
    # An offline Prism account can only join a server in offline mode.
    Set-Properties "$ServerCopy\server.properties" @{
        'enable-rcon' = 'true'; 'rcon.port' = $RconPort; 'rcon.password' = $password
        'online-mode' = $(if ($TwoClients) { 'false' } else { 'true' })
    }

    $game = New-TestInstance $Name 'GTNH 2.9.0 JourneySync Test'
    if ($MapFrom) {
        $target = Map-Folder $game
        robocopy $MapFrom $target /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
        if ($LASTEXITCODE -ge 8) { throw "robocopy of the map failed ($LASTEXITCODE)" }
        "copied the map from $MapFrom to $target"
    }
    if ($TwoClients) { New-TestInstance $Name2 "GTNH 2.9.0 JourneySync Test ($Profile2)" | Out-Null }
    'test instances ready. Restart Prism once so it sees new instances.'
    exit 0
}

if ($Server) {
    $java = Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -Filter 'temurin-8*' |
        ForEach-Object { Join-Path $_.FullName 'bin\java.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
    $forge = Get-ChildItem $ServerCopy -Filter 'forge-1.7.10-*-universal.jar' | Select-Object -First 1
    New-Item -ItemType Directory -Force "$ServerCopy\logs" | Out-Null
    $p = Start-Process -FilePath $java -WorkingDirectory $ServerCopy -NoNewWindow -PassThru `
        -ArgumentList "-Xms2G -Xmx$ServerMemory -XX:+UseG1GC -XX:+UseStringDeduplication -Dfml.readTimeout=180 -jar $($forge.Name) nogui" `
        -RedirectStandardOutput "$ServerCopy\logs\console.txt" -RedirectStandardError "$ServerCopy\logs\console-err.txt"
    $p.PriorityClass = 'BelowNormal'
    "server copy starting (pid $($p.Id)); wait for 'Done (' in $ServerCopy\logs\console.txt"
    exit 0
}

if ($Launch) {
    $instanceName = if ($Second) { $Name2 } else { $Name }
    $prismArgs = @('--launch', $instanceName)
    if ($QuickJoin) { $prismArgs += @('--server', "127.0.0.1:$Port") }
    if ($Second) { $prismArgs += @('--profile', $Profile2) }
    & $Prism @prismArgs
    'launched; the client log is ' + (Join-Path (Game-Dir $instanceName) 'logs\fml-client-latest.log')
    exit 0
}

if ($Stop) {
    Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
        Where-Object { $_.CommandLine -like "*$Name*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -ErrorAction SilentlyContinue }
    try { python $rcon --properties "$ServerCopy\server.properties" 'stop' } catch { 'server copy not reachable over RCON' }
    exit 0
}

if ($WipeClientMap) {
    $game = Game-Dir $(if ($Second) { $Name2 } else { $Name })
    Remove-Item -Recurse -Force "$game\journeymap\data", "$game\config\journeysync" -ErrorAction SilentlyContinue
    "wiped the map and JourneySync state in $game"
    exit 0
}

'use -CopyServer, -Setup, -Server, -Launch, -Stop or -WipeClientMap'
