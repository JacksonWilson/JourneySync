# Runs a Gradle task at below-normal CPU priority so it doesn't stutter a running Minecraft session.
#   .\dev.ps1 build                 compile, unit tests, jar in build\libs
#   .\dev.ps1 test                  unit tests only
#   .\dev.ps1 runServer             headless dev server for manual testing (stop it with "stop" or Ctrl+C)
#   .\dev.ps1 runClient             dev client window (opens a second Minecraft)
# Output also goes to build\dev-<task>.log.
param([Parameter(Mandatory, ValueFromRemainingArguments)][string[]]$GradleArgs)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# The GTNH build derives the version from the latest git tag. Until the first tag exists, use a fixed dev version.
if (-not (git tag 2>$null)) { $env:VERSION = '0.1.0-snapshot' }

New-Item -ItemType Directory -Force build | Out-Null
$log = "build\dev-$($GradleArgs[0]).log"
$p = Start-Process -FilePath "$PSScriptRoot\gradlew.bat" -ArgumentList ($GradleArgs + '--console=plain') `
    -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
$p.PriorityClass = 'BelowNormal'
$p.WaitForExit()
Get-Content $log -Tail 40
if ((Get-Item "$log.err").Length -gt 0) { Get-Content "$log.err" -Tail 40 }
exit $p.ExitCode
