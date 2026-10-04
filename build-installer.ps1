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
$TauriCli  = '2.12.1'

# Modules the backend's libraries load by reflection, which jdeps cannot see.
$ReflectiveModules = @('java.sql', 'java.naming', 'java.management', 'jdk.unsupported')

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
$JavaMajor = [int](& (Join-Path $JavaBin 'java.exe') -XshowSettings:properties -version 2>&1 |
    Select-String 'java\.specification\.version = (\d+)').Matches[0].Groups[1].Value
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

Step 'Desktop shell and installer: Tauri'
$InstalledCli = (& cargo tauri --version 2>$null)
if ($LASTEXITCODE -ne 0 -or $InstalledCli -notmatch [regex]::Escape($TauriCli)) {
    Invoke-Checked 'cargo' @('install', 'tauri-cli', '--version', $TauriCli, '--locked')
}
Invoke-Checked 'cargo' @('tauri', 'icon', (Join-Path $Desktop 'app-icon.png')) $TauriDir
Invoke-Checked 'cargo' @('tauri', 'build') $TauriDir

$Installer = Get-ChildItem (Join-Path $TauriDir 'target\release\bundle\nsis\*.exe') |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
Step "Done: $($Installer.FullName)"
