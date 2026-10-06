<#
.SYNOPSIS
    Builds the Budget Windows installer: one per-user .exe with everything bundled.

.DESCRIPTION
    tests -> frontend build -> backend jar -> trimmed Java runtime (jlink) ->
    backend app image (jpackage) -> Tauri NSIS installer.

    Needs, installed once: a JDK 21 or newer (JAVA_HOME), Node LTS, the Rust toolchain and
    the Visual Studio C++ Build Tools. Maven comes from the wrapper in backend/.

.PARAMETER SkipTests
    Skip both test suites. For quick local packaging only — never for a build you hand out.
#>
[CmdletBinding()]
param([switch]$SkipTests)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root      = $PSScriptRoot
$Frontend  = Join-Path $Root 'frontend'
$Backend   = Join-Path $Root 'backend'
$Desktop   = Join-Path $Root 'desktop'
$TauriDir  = Join-Path $Desktop 'src-tauri'
$BuildDir  = Join-Path $Desktop 'build'
$AppInput  = Join-Path $Backend 'server\target\app'
$MainJar   = 'budget-server.jar'
$ImageName = 'budget-backend'   # must match BACKEND_EXE in desktop/src-tauri/src/main.rs
$MinJava   = 21
$TauriCli  = '2.11.5'   # keep in step with tauri in desktop/src-tauri/Cargo.toml; at least 14 days old

# Modules the backend's libraries load by reflection, which jdeps cannot see.
# jdk.zipfs: Jetty 12 opens the jar as a file system to serve the UI from it; without the
# module the backend cannot start at all.
$ReflectiveModules = @('java.sql', 'java.naming', 'java.management', 'jdk.unsupported', 'jdk.zipfs')

function Step([string]$Message) { Write-Host "`n==> $Message" -ForegroundColor Cyan }

# Runs a native command and stops the build if it fails (PowerShell 5.1 does not do this itself).
function Invoke-Checked([string]$Exe, [string[]]$Arguments, [string]$WorkingDir = $Root) {
    Push-Location $WorkingDir
    try {
        & $Exe @Arguments
        if ($LASTEXITCODE -ne 0) { throw "'$Exe $($Arguments -join ' ')' failed with exit code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
}

function Assert-Tool([string]$Name, [string]$Hint) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) { throw "$Name was not found. $Hint" }
}

Step 'Checking tools'
if (-not $env:JAVA_HOME) { throw "JAVA_HOME is not set. Point it at a JDK $MinJava or newer." }
$JavaBin = Join-Path $env:JAVA_HOME 'bin'
foreach ($tool in 'java', 'jdeps', 'jlink', 'jpackage') {
    if (-not (Test-Path (Join-Path $JavaBin "$tool.exe"))) { throw "$tool.exe is missing from $JavaBin. JAVA_HOME must be a full JDK." }
}
# Read the version from the JDK's `release` file rather than running `java -version`: that
# prints to stderr, which Windows PowerShell 5.1 turns into a terminating error.
$ReleaseFile = Join-Path $env:JAVA_HOME 'release'
$VersionLine = if (Test-Path $ReleaseFile) { Select-String -Path $ReleaseFile -Pattern '^JAVA_VERSION="(\d+)' } else { $null }
if (-not $VersionLine) { throw "Could not read the Java version from $ReleaseFile. JAVA_HOME must be a full JDK." }
$JavaMajor = [int]$VersionLine.Matches[0].Groups[1].Value
if ($JavaMajor -lt $MinJava) { throw "JAVA_HOME is Java $JavaMajor; Budget needs $MinJava or newer." }
Assert-Tool 'npm'   'Install Node LTS.'
Assert-Tool 'cargo' 'Install the Rust toolchain from rustup.rs (and the Visual Studio C++ Build Tools).'

Step 'Frontend: install, test, build'
Invoke-Checked 'npm' @('ci', '--no-audit') $Frontend
if (-not $SkipTests) { Invoke-Checked 'npm' @('test') $Frontend }
Invoke-Checked 'npm' @('run', 'build') $Frontend

Step 'Backend: test and package (the frontend build is bundled into the jar)'
$MavenArgs = @('-B', 'clean', 'verify')
if ($SkipTests) { $MavenArgs += '-DskipTests' }
Invoke-Checked (Join-Path $Backend 'mvnw.cmd') $MavenArgs $Backend

Step 'Java runtime: trimmed with jlink'
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Path $BuildDir | Out-Null
$Detected = & (Join-Path $JavaBin 'jdeps.exe') --ignore-missing-deps --multi-release $MinJava `
    --print-module-deps --class-path (Join-Path $AppInput '*') (Join-Path $AppInput $MainJar)
if ($LASTEXITCODE -ne 0) { throw 'jdeps could not work out which Java modules the backend needs.' }
$Modules = (@($Detected.Trim() -split ',') + $ReflectiveModules | Sort-Object -Unique) -join ','
Write-Host "Modules: $Modules"
$Runtime = Join-Path $BuildDir 'runtime'
Invoke-Checked (Join-Path $JavaBin 'jlink.exe') @(
    '--add-modules', $Modules, '--strip-debug', '--no-header-files', '--no-man-pages',
    '--compress', 'zip-6', '--output', $Runtime)

Step 'Backend app image: jpackage'
# --win-console so the launcher has a real stdout for the READY line; the shell hides the window.
Invoke-Checked (Join-Path $JavaBin 'jpackage.exe') @(
    '--type', 'app-image', '--name', $ImageName, '--input', $AppInput, '--main-jar', $MainJar,
    '--runtime-image', $Runtime, '--dest', $BuildDir, '--win-console',
    '--java-options', '-Dfile.encoding=UTF-8')

Step 'Backend app image: start it once'
# The tests run on the full JDK, so only this catches a module missing from the trimmed
# runtime. Starts the image exactly as the shell does, on an empty throwaway data folder, and
# requires both the READY line and the UI to be served.
$SmokeData = Join-Path $BuildDir 'smoke-data'
$SmokeOut  = Join-Path $BuildDir 'smoke-out.log'
$SmokeEnv  = @{ BUDGET_DATA_DIR = $SmokeData; BUDGET_PORT = '4990'; BUDGET_PARENT_PID = "$PID" }
$Previous  = @{}
foreach ($name in $SmokeEnv.Keys) {
    $Previous[$name] = [Environment]::GetEnvironmentVariable($name)
    [Environment]::SetEnvironmentVariable($name, $SmokeEnv[$name])
}
try {
    $Smoke = Start-Process (Join-Path $BuildDir "$ImageName\$ImageName.exe") -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $SmokeOut -RedirectStandardError (Join-Path $BuildDir 'smoke-err.log')
} finally {
    foreach ($name in $SmokeEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $Previous[$name]) }
}
try {
    $Ready = $null
    for ($waited = 0; $waited -lt 45 -and -not $Ready -and -not $Smoke.HasExited; $waited++) {
        Start-Sleep -Seconds 1
        $Ready = Select-String -Path $SmokeOut -Pattern '^READY port=(\d+)' -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if (-not $Ready) { throw "The packaged backend did not start. See $SmokeData\logs\budget.log and $SmokeOut." }
    $SmokePort = $Ready.Matches[0].Groups[1].Value
    # 127.0.0.1 is the backend's own address on this PC, the one the shell opens.
    $Page = Invoke-WebRequest -Uri "http://127.0.0.1:$SmokePort/" -UseBasicParsing
    if ($Page.StatusCode -ne 200) { throw "The packaged backend started but did not serve the UI (status $($Page.StatusCode))." }
    Write-Host "Started and served the UI on port $SmokePort."
} finally {
    if (-not $Smoke.HasExited) { Stop-Process -Id $Smoke.Id -Force }
    $Smoke.WaitForExit()
    Remove-Item -Recurse -Force $SmokeData -ErrorAction SilentlyContinue
}

Step 'Desktop shell and installer: Tauri'
$InstalledCli = if (Get-Command 'cargo-tauri' -ErrorAction SilentlyContinue) { & cargo tauri --version } else { '' }
if ($InstalledCli -notmatch [regex]::Escape($TauriCli)) {
    Invoke-Checked 'cargo' @('install', 'tauri-cli', '--version', $TauriCli, '--locked')
}
Invoke-Checked 'cargo' @('tauri', 'icon', (Join-Path $Desktop 'app-icon.png')) $TauriDir
Invoke-Checked 'cargo' @('tauri', 'build') $TauriDir

$Installer = Get-ChildItem (Join-Path $TauriDir 'target\release\bundle\nsis\*.exe') |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Step "Done: $($Installer.FullName)"
