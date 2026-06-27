param(
    [string]$DropboxRoot = "",
    [string]$Remote = "origin"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message"
}

function Read-VersionName {
    param([string]$VersionFile)

    if (-not (Test-Path -LiteralPath $VersionFile)) {
        throw "version.properties was not found: $VersionFile"
    }

    $line = Get-Content -LiteralPath $VersionFile -Encoding UTF8 |
        Where-Object { $_ -match "^VERSION_NAME=" } |
        Select-Object -First 1

    if ([string]::IsNullOrWhiteSpace($line)) {
        throw "VERSION_NAME is missing in version.properties."
    }

    return ($line -replace "^VERSION_NAME=", "").Trim()
}

function Read-BuildNumber {
    param([string]$BuildFile)

    if (-not (Test-Path -LiteralPath $BuildFile)) {
        throw "build-number.txt was not found: $BuildFile"
    }

    $value = (Get-Content -LiteralPath $BuildFile -Encoding UTF8 -Raw).Trim()
    $number = 0
    if (-not [int]::TryParse($value, [ref]$number) -or $number -lt 1) {
        throw "build-number.txt must contain a positive integer."
    }

    return $number
}

function Resolve-DropboxRoot {
    param([string]$RequestedRoot)

    if (-not [string]::IsNullOrWhiteSpace($RequestedRoot)) {
        return $RequestedRoot
    }

    if (-not [string]::IsNullOrWhiteSpace($env:DROPBOX)) {
        return $env:DROPBOX
    }

    $profileDropbox = Join-Path $env:USERPROFILE "Dropbox"
    if (Test-Path -LiteralPath $profileDropbox) {
        return $profileDropbox
    }

    throw "Dropbox root was not found. Pass -DropboxRoot or set DROPBOX."
}

function Invoke-ReleaseBuild {
    param([string]$RepoRoot)

    $projectBuildScript = Join-Path $RepoRoot "scripts\build.ps1"
    $androidGradlew = Join-Path $RepoRoot "android\gradlew.bat"
    $rootGradlew = Join-Path $RepoRoot "gradlew.bat"

    if (Test-Path -LiteralPath $projectBuildScript) {
        & pwsh -ExecutionPolicy Bypass -File $projectBuildScript -Release
        return
    }

    if (Test-Path -LiteralPath $androidGradlew) {
        Push-Location (Join-Path $RepoRoot "android")
        try {
            & .\gradlew.bat assembleRelease
        } finally {
            Pop-Location
        }
        return
    }

    if (Test-Path -LiteralPath $rootGradlew) {
        Push-Location $RepoRoot
        try {
            & .\gradlew.bat assembleRelease
        } finally {
            Pop-Location
        }
        return
    }

    throw "No release build entry point was found. Expected scripts\build.ps1, android\gradlew.bat, or gradlew.bat."
}

function Get-ReleaseArtifacts {
    param([string]$RepoRoot)

    $patterns = @(
        "android\app\build\outputs\apk\release\*.apk",
        "android\app\build\outputs\bundle\release\*.aab",
        "app\build\outputs\apk\release\*.apk",
        "app\build\outputs\bundle\release\*.aab",
        "build\outputs\apk\release\*.apk",
        "build\outputs\bundle\release\*.aab"
    )

    $artifacts = New-Object System.Collections.Generic.List[string]
    foreach ($pattern in $patterns) {
        $fullPattern = Join-Path $RepoRoot $pattern
        Get-ChildItem -Path $fullPattern -File -ErrorAction SilentlyContinue |
            ForEach-Object { $artifacts.Add($_.FullName) }
    }

    if ($artifacts.Count -eq 0) {
        throw "No release artifacts were found."
    }

    return $artifacts
}

function Copy-ReleaseArtifacts {
    param(
        [string[]]$Artifacts,
        [string]$DestinationFile
    )

    $apk = $Artifacts |
        Where-Object { [System.IO.Path]::GetExtension($_).Equals(".apk", [System.StringComparison]::OrdinalIgnoreCase) } |
        Select-Object -First 1

    if ([string]::IsNullOrWhiteSpace($apk)) {
        throw "No release APK was found."
    }

    $destinationDir = Split-Path -Parent $DestinationFile
    New-Item -ItemType Directory -Force -Path $destinationDir | Out-Null
    Copy-Item -LiteralPath $apk -Destination $DestinationFile -Force
}

function Set-BuildNumber {
    param(
        [string]$BuildFile,
        [int]$NextBuildNumber
    )

    [System.IO.File]::WriteAllText($BuildFile, "$NextBuildNumber`n", [System.Text.UTF8Encoding]::new($false))
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$versionFile = Join-Path $repoRoot "version.properties"
$buildFile = Join-Path $repoRoot "build-number.txt"

Push-Location $repoRoot
try {
    $branch = (git branch --show-current).Trim()
    if ([string]::IsNullOrWhiteSpace($branch)) {
        throw "Current Git branch could not be determined."
    }

    $versionName = Read-VersionName -VersionFile $versionFile
    $buildNumber = Read-BuildNumber -BuildFile $buildFile
    $releaseName = "Cabinet-STRASSE-$versionName-build-$buildNumber"

    Write-Step "Release build: version $versionName build $buildNumber on $branch"
    Invoke-ReleaseBuild -RepoRoot $repoRoot

    Write-Step "Collect release artifacts"
    $artifacts = Get-ReleaseArtifacts -RepoRoot $repoRoot

    Write-Step "Copy artifacts to Dropbox"
    $resolvedDropboxRoot = Resolve-DropboxRoot -RequestedRoot $DropboxRoot
    $releaseFile = Join-Path $resolvedDropboxRoot "Cabinet-STRASSE\Cabinet-STRASSE-release.apk"
    Copy-ReleaseArtifacts -Artifacts $artifacts -DestinationFile $releaseFile

    Write-Step "Increment build number"
    Set-BuildNumber -BuildFile $buildFile -NextBuildNumber ($buildNumber + 1)

    Write-Step "Commit release changes"
    git add -u
    git add -- build-number.txt version.properties AGENTS.md scripts/release_build.ps1

    $status = git status --porcelain
    if ([string]::IsNullOrWhiteSpace($status)) {
        Write-Host "No Git changes to commit."
    } else {
        git commit -m "Release Cabinet-STRASSE $versionName build $buildNumber"
    }

    Write-Step "Push"
    git push $Remote $branch

    Write-Host ""
    Write-Host "Release completed: $releaseName"
    Write-Host "Copied to: $releaseFile"
} finally {
    Pop-Location
}
