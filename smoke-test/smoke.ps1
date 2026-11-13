# Smoke test (Windows): generates an environment with the CLI built from this repository, starts MPS through the
# generated .bat and checks that MPS comes up. Linux/macOS: smoke.sh (same checks, same environment variables).
#
# usage: pwsh smoke-test/smoke.ps1 <mps-version> [<project-dir>]
#   build the CLI first: ./gradlew :cli:installDist
#
# The .bat starts MPS with "start" in a console of its own and exits, so MPS output is not visible here: MPS is found
# by its command line (contains the environment folder) and "Start Failed" can only show up as a missing process.

param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Project = ""
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path "$PSScriptRoot/..").Path
$cli = if ($env:MPS_ENV_GEN) { $env:MPS_ENV_GEN } else { "$repo\modules\cli\build\install\mps-env-gen\bin\mps-env-gen.bat" }
$timeout = if ($env:SMOKE_TIMEOUT) { [int]$env:SMOKE_TIMEOUT } else { 120 }
# pass criterion and SMOKE_NODE_REF: see smoke.sh
$nodeRef = if ($env:SMOKE_NODE_REF) { $env:SMOKE_NODE_REF } else { "r%3Ab8dc92cc-9342-4627-878b-8909a6ba5364%28smoketest.sandbox%29%2F7901695510806614122" }
$work = if ($env:SMOKE_WORK) { $env:SMOKE_WORK } else { Join-Path ([IO.Path]::GetTempPath()) ("mps-smoke-" + [guid]::NewGuid().ToString("N").Substring(0, 8)) }
$name = "smoke"
$envDir = "$work\envs\$name"
$output = "$work\start-output.txt"
$log = "$envDir\mps\log\idea.log"
$summary = $env:GITHUB_STEP_SUMMARY

function Step([string]$text) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $text) }

if (-not (Test-Path $cli)) { Write-Error "CLI not found at $cli; run ./gradlew :cli:installDist" }
if ($Project -and -not (Test-Path $Project -PathType Container)) {
    Write-Host "WARNING: project $Project not found, starting without project (welcome screen, port check only)"
    $Project = ""
}
if (-not $Project) { $nodeRef = "" }

New-Item -ItemType Directory -Force -Path $work | Out-Null
Write-Host "Work folder: $work"

# 1. generate; from the work folder, see smoke.sh
$create = @("create", $Version, "--target", "$work\envs", "--name", $name, "--no-copy", "--no-debug", "--no-update-checks")
# a copy: MPS writes into the project it opens, the repository stays clean
if ($Project) {
    if (Test-Path "$work\project") { Remove-Item -Recurse -Force "$work\project" }
    Copy-Item -Recurse $Project "$work\project"
    $create += @("--project", "$work\project")
}
Write-Host "Generating: $cli $($create -join ' ')"
Push-Location $work
try { & $cli @create; $created = $LASTEXITCODE } finally { Pop-Location }
if ($created -ne 0) { Write-Host "FAIL: environment generation failed"; exit 1 }
Step "environment generated"
$script = "$envDir\startMPS_WINDOWS_$name.bat"
if (-not (Test-Path $script)) { Write-Host "FAIL: start script $script missing"; exit 1 }

# 2. start; NUL as input so a PAUSE in the script does not block
$empty = "$work\empty-input.txt"
Set-Content -Path $empty -Value "" -NoNewline
$started = Get-Date
Step "running $script"
# not -Wait: it also waits for all descendants, and MPS (started by the .bat with "start") is one of them
$launch = Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$script`"" -RedirectStandardOutput $output `
    -RedirectStandardError "$work\start-error.txt" -RedirectStandardInput $empty -NoNewWindow -PassThru
if (-not $launch.WaitForExit(30000)) { Write-Host "FAIL: the start script did not finish within 30s"; Get-Content $output; exit 1 }
Step "start script finished (exit code $($launch.ExitCode))"
Get-Content $output

function Get-MpsProcesses {
    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($envDir) }
}

function Stop-Mps {
    foreach ($p in Get-MpsProcesses) { & taskkill.exe /T /F /PID $p.ProcessId 2>&1 | Out-Null }
}

function Write-Report([string]$status, [string]$detail) {
    if (Test-Path $log) { Copy-Item $log "$work\idea.log" }
    $errors = if (Test-Path $log) { @(Select-String -Path $log -SimpleMatch " ERROR - ") } else { @() }
    $lines = @("### MPS $Version on WINDOWS: $status", "- $detail", "- ERROR lines in idea.log: $($errors.Count) (reported only)")
    if ($errors.Count -gt 0) {
        $lines += '```'
        $lines += $errors | Select-Object -First 20 | ForEach-Object { $_.Line.Substring(0, [Math]::Min(300, $_.Line.Length)) }
        $lines += '```'
    }
    $lines | ForEach-Object { Write-Host $_ }
    if ($summary) { $lines | Add-Content -Path $summary }
}

function Fail([string]$reason) {
    Stop-Mps
    Write-Host "FAIL: $reason"
    Write-Host "----- idea.log (last 100 lines) -----"
    if (Test-Path $log) { Get-Content $log -Tail 100 } else { Write-Host "(no idea.log)" }
    Write-Report "FAILED" $reason
    exit 1
}

# 3. watch: fail fast, pass as soon as MPS opens the node
$port = (Select-String -Path $output -Pattern "^Node URL port: (\d+)" | Select-Object -First 1).Matches.Groups[1].Value
if (-not $port) { Fail "the start script printed no node URL port" }
Step "waiting for MPS on node URL port $port"
$answer = "no answer"
$notFound = 0
$seenProcess = $false
while ($true) {
    $elapsed = [int]((Get-Date) - $started).TotalSeconds
    $alive = @(Get-MpsProcesses).Count -gt 0
    if ($alive) { $seenProcess = $true }
    # the launcher may need a few seconds to start java
    if (-not $alive -and ($seenProcess -or $elapsed -ge 30)) { Fail "MPS exited after ${elapsed}s" }
    $url = if ($nodeRef) { "http://127.0.0.1:$port/node?ref=$nodeRef" } else { "http://127.0.0.1:$port/" }
    try {
        $response = Invoke-WebRequest -Uri $url -TimeoutSec 10 -SkipHttpErrorCheck -UseBasicParsing
        if (-not $nodeRef) { break }
        $body = [string]$response.Content
        if ($body.Contains("has been opened")) { break }
        elseif ($body.Contains("No project is available")) { $answer = "no project open yet" }
        elseif ($body.Contains("has not been found")) {
            $answer = "node not found"
            $notFound++
            if ($notFound -ge 15) { Fail "node $nodeRef not found in the open project ($notFound tries)" }
        }
        else { $answer = "unexpected answer: " + $body.Substring(0, [Math]::Min(200, $body.Length)) }
    } catch { }
    if ($elapsed -ge $timeout) { Fail "timeout after ${timeout}s (node URL port: $port, last answer: $answer)" }
    Start-Sleep -Seconds 2
}
$elapsed = [int]((Get-Date) - $started).TotalSeconds
Write-Host "MPS is up after ${elapsed}s"

Stop-Mps
Write-Report "OK" ($(if ($nodeRef) { "node opened via node URL" } else { "node URL port answers" }) + " after ${elapsed}s")
exit 0
