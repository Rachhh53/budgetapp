<#
.SYNOPSIS
    Fails if the project depends on a package version published less than 14 days ago.

.DESCRIPTION
    A brand-new release has not been out long enough for a hijacked or vulnerable version to
    be noticed and reported, so nothing younger than -MinDays may be depended on.

    Checks every ecosystem the app is built from, looking up each version's publish date:
      Maven  - every library in backend/target/bom.json (direct and transitive), which
               `mvnw package org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom` writes
      npm    - frontend/package-lock.json
      Cargo  - desktop/src-tauri/Cargo.lock

    A version that is already in the lockfile on -BaseRef was checked when it was added and
    only gets older, so for npm and Cargo only versions this branch adds or changes are looked
    up. That keeps the check quick and well inside the registries' rate limits. If -BaseRef
    cannot be read, everything is checked.

    The one way in for a younger version is a named entry in dependency-age-exceptions.json,
    which is only for a version that fixes a real vulnerability when no older version is free
    of it and the package cannot be dropped or replaced. An exception is temporary by
    construction: every entry is looked up on every run, and the check fails as soon as its
    version is old enough (the entry must be deleted) or is no longer depended on.

    This is a build-time tool. It talks to the package registries; the app itself never does.

.PARAMETER MinDays
    Youngest allowed age, in days.

.PARAMETER BaseRef
    The git ref to compare lockfiles against.

.PARAMETER ExceptionsFile
    The list of named exceptions, relative to the repository root.
#>
[CmdletBinding()]
param(
    [int]$MinDays = 14,
    [string]$BaseRef = 'origin/main',
    [string]$ExceptionsFile = 'dependency-age-exceptions.json'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# Windows PowerShell 5.1 draws a progress bar per download, which slows requests badly.
$ProgressPreference = 'SilentlyContinue'

$Root        = $PSScriptRoot
$Bom         = 'backend/target/bom.json'
$PackageLock = 'frontend/package-lock.json'
$CargoLock   = 'desktop/src-tauri/Cargo.lock'
$OwnGroup    = 'app.budget'   # our own modules: not published anywhere
# crates.io asks for an identifying User-Agent and at most one request a second.
$UserAgent   = 'budget-app-dependency-age-check (github.com/Rachhh53/budgetapp)'
$Now         = [DateTimeOffset]::UtcNow

function Step([string]$Message) { Write-Host "`n==> $Message" -ForegroundColor Cyan }

# The file as it is on the base ref, or $null if it is not there (or the ref is unknown).
function Get-BaseFile([string]$Path) {
    # git reports a missing file on stderr; Windows PowerShell 5.1 would turn that into a
    # terminating error under 'Stop', so it is relaxed just for this call.
    $ErrorActionPreference = 'Continue'
    $text = & git -C $Root show "${BaseRef}:$Path" 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($text -join "`n")
}

# "name@version" for every package in a package-lock.json that came from the npm registry.
# Read as text: the file's first key is "" (the project itself), which Windows PowerShell 5.1
# cannot parse as JSON. npm always writes "version" then "resolved", so the shape is dependable.
function Get-NpmPackages([string]$LockJson) {
    # Keys look like "node_modules/a/node_modules/@scope/b": the name is what follows the last "node_modules/".
    $pattern = '"(?:[^"]*/)?node_modules/((?:@[^"/]+/)?[^"/]+)":\s*\{\s*"version":\s*"([^"]+)",\s*"resolved":\s*"https://registry\.npmjs\.org/'
    foreach ($match in [regex]::Matches($LockJson, $pattern)) {
        "$($match.Groups[1].Value)@$($match.Groups[2].Value)"
    }
}

# "name@version" for every crate in a Cargo.lock that came from a registry.
function Get-CargoPackages([string]$LockText) {
    $pattern = '(?m)^\[\[package\]\]\r?\nname = "([^"]+)"\r?\nversion = "([^"]+)"\r?\nsource = "registry\+'
    foreach ($match in [regex]::Matches($LockText, $pattern)) {
        "$($match.Groups[1].Value)@$($match.Groups[2].Value)"
    }
}

# The packages to look up: all of them, or only those the base ref does not already have.
function Select-New([string[]]$Current, $BaseText, [scriptblock]$Parse, [string]$Label) {
    # $BaseText is left untyped on purpose: a [string] parameter would turn $null into "".
    if ($null -eq $BaseText) {
        Write-Host "$Label`: no copy on $BaseRef to compare with, so checking all $($Current.Count)."
        return $Current
    }
    $known = [System.Collections.Generic.HashSet[string]]::new([string[]]@(& $Parse $BaseText))
    $new = @($Current | Where-Object { -not $known.Contains($_) })
    Write-Host "$Label`: $($new.Count) of $($Current.Count) added or changed since $BaseRef."
    return $new
}

function Split-Package([string]$Package) {
    # Split on the last "@" so scoped npm names ("@scope/name@1.0.0") keep their leading "@".
    $at = $Package.LastIndexOf('@')
    return $Package.Substring(0, $at), $Package.Substring($at + 1)
}

function Get-NpmPublished([string]$Package) {
    $name, $version = Split-Package $Package
    $url = 'https://registry.npmjs.org/' + $name.Replace('/', '%2F')
    $body = (Invoke-WebRequest -Uri $url -UseBasicParsing).Content
    # The registry's "time" map is the only place a version maps straight to a date. Matching
    # it as text avoids parsing documents of several megabytes, which PowerShell 5.1 cannot.
    $match = [regex]::Match($body, '"' + [regex]::Escape($version) + '"\s*:\s*"(\d{4}-\d{2}-\d{2}T[^"]+)"')
    if (-not $match.Success) { throw "npm has no publish date for $Package." }
    return [DateTimeOffset]::Parse($match.Groups[1].Value, [cultureinfo]::InvariantCulture)
}

function Get-CargoPublished([string]$Package) {
    $name, $version = Split-Package $Package
    Start-Sleep -Milliseconds 1100
    $reply = Invoke-RestMethod -Uri "https://crates.io/api/v1/crates/$name/$version" -UserAgent $UserAgent
    return [DateTimeOffset]::Parse([string]$reply.version.created_at, [cultureinfo]::InvariantCulture)
}

function Get-MavenPublished([string]$Package) {
    $coordinates, $version = Split-Package $Package
    $group, $artifact = $coordinates.Split(':')
    $url = 'https://repo.maven.apache.org/maven2/{0}/{1}/{2}/{1}-{2}.pom' -f $group.Replace('.', '/'), $artifact, $version
    $reply = Invoke-WebRequest -Uri $url -Method Head -UseBasicParsing
    # Maven Central never replaces a published file, so Last-Modified is the release date.
    return [DateTimeOffset]::Parse([string]$reply.Headers['Last-Modified'], [cultureinfo]::InvariantCulture)
}

# The named exceptions, each as Ecosystem / Package ("name@version") / Fixes / Reason.
# An entry that does not say what it fixes and why is refused: the file is the record of the decision.
function Read-Exceptions {
    $path = Join-Path $Root $ExceptionsFile
    if (-not (Test-Path $path)) { return }
    foreach ($entry in @((Get-Content $path -Raw | ConvertFrom-Json).exceptions)) {
        $fields = @{}
        foreach ($field in 'ecosystem', 'package', 'version', 'fixes', 'reason') {
            $property = $entry.PSObject.Properties[$field]
            if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
                throw "$ExceptionsFile`: every exception needs ecosystem, package, version, fixes and reason ('$field' is missing)."
            }
            $fields[$field] = ([string]$property.Value).Trim()
        }
        $ecosystem = $Ecosystems.Keys | Where-Object { $_ -eq $fields.ecosystem }
        if (-not $ecosystem) {
            throw "$ExceptionsFile`: unknown ecosystem '$($fields.ecosystem)'. Use one of: $($Ecosystems.Keys -join ', ')."
        }
        [pscustomobject]@{
            Ecosystem = $ecosystem
            Package   = "$($fields.package)@$($fields.version)"
            Fixes     = $fields.fixes
            Reason    = $fields.reason
        }
    }
}

# Looks up each package and returns the ones that are too young, or whose date could not be found.
# A package with an exception is skipped here; Test-Exceptions deals with those.
function Find-TooYoung([string]$Ecosystem, [string[]]$Packages) {
    $GetPublished = $Ecosystems[$Ecosystem]
    foreach ($package in $Packages) {
        if ($Excepted.Contains("$Ecosystem|$package")) { continue }
        try {
            $published = & $GetPublished $package
            $age = [int][math]::Floor(($Now - $published).TotalDays)
            if ($age -lt $MinDays) {
                [pscustomobject]@{ Ecosystem = $Ecosystem; Package = $package; Published = $published.ToString('yyyy-MM-dd'); Problem = "$age days old" }
            }
        } catch {
            # Not knowing is not the same as being old enough: report it rather than let it through.
            [pscustomobject]@{ Ecosystem = $Ecosystem; Package = $package; Published = '?'; Problem = "could not be checked: $($_.Exception.Message)" }
        }
    }
}

# Checks every exception on every run, whatever the base ref has, so that one cannot outlive
# its reason. Prints the ones still in force and returns the ones that must be removed.
function Test-Exceptions($InUse) {
    foreach ($exception in $Exceptions) {
        $label = "$($exception.Package) (fixes $($exception.Fixes))"
        if ($InUse[$exception.Ecosystem] -notcontains $exception.Package) {
            [pscustomobject]@{ Ecosystem = $exception.Ecosystem; Package = $exception.Package; Problem = 'no longer depended on' }
            continue
        }
        try {
            $published = & $Ecosystems[$exception.Ecosystem] $exception.Package
        } catch {
            throw "The exception for $($exception.Package) could not be checked: $($_.Exception.Message)"
        }
        $age = [int][math]::Floor(($Now - $published).TotalDays)
        if ($age -ge $MinDays) {
            [pscustomobject]@{ Ecosystem = $exception.Ecosystem; Package = $exception.Package; Problem = "now $age days old, so no longer an exception" }
        } else {
            $until = $published.AddDays($MinDays).ToString('yyyy-MM-dd')
            Write-Host "Allowed early: $($exception.Ecosystem) $label, $age days old. Remove the entry on $until."
            Write-Host "  $($exception.Reason)"
        }
    }
}

$Ecosystems = [ordered]@{
    Maven = ${function:Get-MavenPublished}
    npm   = ${function:Get-NpmPublished}
    Cargo = ${function:Get-CargoPublished}
}
$Exceptions = @(Read-Exceptions)
$Excepted   = [System.Collections.Generic.HashSet[string]]::new([string[]]@($Exceptions | ForEach-Object { "$($_.Ecosystem)|$($_.Package)" }))

$BomPath = Join-Path $Root $Bom
if (-not (Test-Path $BomPath)) {
    throw "$Bom is missing. In backend/, run: mvnw -DskipTests package org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom"
}

Step "Maven ($Bom)"
$maven = @((Get-Content $BomPath -Raw | ConvertFrom-Json).components |
    Where-Object { $_.group -ne $OwnGroup } |
    ForEach-Object { "$($_.group):$($_.name)@$($_.version)" } | Sort-Object -Unique)
# The dependency list is generated, not committed, so there is no base copy: check them all.
Write-Host "Maven: checking all $($maven.Count)."
$tooYoung = @(Find-TooYoung 'Maven' $maven)

Step "npm ($PackageLock)"
$npm = @(Get-NpmPackages (Get-Content (Join-Path $Root $PackageLock) -Raw) | Sort-Object -Unique)
$npmNew = @(Select-New $npm (Get-BaseFile $PackageLock) ${function:Get-NpmPackages} 'npm')
$tooYoung += @(Find-TooYoung 'npm' $npmNew)

Step "Cargo ($CargoLock)"
$cargo = @(Get-CargoPackages (Get-Content (Join-Path $Root $CargoLock) -Raw) | Sort-Object -Unique)
$cargoNew = @(Select-New $cargo (Get-BaseFile $CargoLock) ${function:Get-CargoPackages} 'Cargo')
$tooYoung += @(Find-TooYoung 'Cargo' $cargoNew)

Step "Exceptions ($ExceptionsFile)"
if ($Exceptions.Count -eq 0) { Write-Host 'None.' }
$expired = @(Test-Exceptions @{ Maven = $maven; npm = $npm; Cargo = $cargo })

if ($tooYoung.Count -gt 0) {
    Step "Not allowed yet: published less than $MinDays days ago, or not checkable"
    $tooYoung | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
    Write-Host "Use the newest version that is at least $MinDays days old, or wait until these are." -ForegroundColor Yellow
}
if ($expired.Count -gt 0) {
    Step "Exceptions to remove from $ExceptionsFile"
    $expired | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
    Write-Host 'These are ordinary dependencies now (or gone): delete their entries.' -ForegroundColor Yellow
}
if ($tooYoung.Count + $expired.Count -gt 0) { exit 1 }
Step "Done: every dependency checked is at least $MinDays days old, or a named exception."
