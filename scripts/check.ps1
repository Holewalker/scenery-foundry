[CmdletBinding()]
param(
    [ValidateSet("quick", "full")]
    [string]$Mode = "full"
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Split-Path -Parent $PSScriptRoot)).Path

function Assert-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' is not available."
    }
}

function Assert-FullToolchain {
    Assert-Command "java"
    Assert-Command "node"
    Assert-Command "npm"
    Assert-Command "uv"
    Assert-Command "docker"

    $javaVersion = (& java -version 2>&1 | Select-Object -First 1)
    if ($javaVersion -notmatch 'version "25(?:\.|\")') {
        throw "Full verification requires JDK 25; found: $javaVersion"
    }

    $nodeVersion = (& node --version).Trim()
    if ($nodeVersion -notmatch '^v(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)$') {
        throw "Full verification requires Node.js 24.19.0 or later in major 24; found: $nodeVersion"
    }
    $nodeMajor = [int]$Matches['major']
    $nodeMinor = [int]$Matches['minor']
    if ($nodeMajor -ne 24 -or $nodeMinor -lt 19) {
        throw "Full verification requires Node.js 24.19.0 or later in major 24; found: $nodeVersion"
    }

    $npmVersion = (& npm --version).Trim()
    if ($npmVersion -notmatch '^11\.17\.') {
        throw "Full verification requires npm 11.17; found: $npmVersion"
    }

    $uvVersion = (& uv --version).Trim()
    if ($uvVersion -notmatch '^uv 0\.12\.3(?:\s|$)') {
        throw "Full verification requires uv 0.12.3; found: $uvVersion"
    }

    Push-Location "$repo/geometry-worker"
    try {
        $pythonVersion = (& uv run --locked python --version 2>&1 | Select-Object -First 1)
    } finally {
        Pop-Location
    }
    if ($pythonVersion -notmatch '^Python 3\.14\.') {
        throw "Full verification requires the locked Python 3.14 runtime; found: $pythonVersion"
    }

    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Full verification requires a reachable Docker daemon."
    }
}

function Remove-RepoOutput([string]$RelativePath) {
    $target = [System.IO.Path]::GetFullPath((Join-Path $repo $RelativePath))
    if (-not $target.StartsWith($repo + [System.IO.Path]::DirectorySeparatorChar)) {
        throw "Refusing to clean outside the repository: $target"
    }
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
}

Assert-Command "docker"
Push-Location $repo
try {
    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw "Compose structure is invalid." }
} finally {
    Pop-Location
}

if ($Mode -eq "quick") {
    Write-Output "Quick structural checks passed. This is NOT full runtime proof."
    exit 0
}

$project = "scenery-foundry-check-$PID-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$compose = @("compose", "--project-name", $project)
try {
    Assert-FullToolchain
    Remove-RepoOutput "backend/target"
    Remove-RepoOutput "frontend/dist"
    Remove-RepoOutput "frontend/.vite"
    Remove-RepoOutput "geometry-worker/.pytest_cache"
    Remove-RepoOutput "geometry-worker/.ruff_cache"

    Push-Location "$repo/backend"
    try {
        .\mvnw.cmd clean test
        if ($LASTEXITCODE -ne 0) { throw "Backend verification failed." }
        $reportPath = "target/surefire-reports/TEST-com.product.PlatformMigrationIntegrationTest.xml"
        if (-not (Test-Path -LiteralPath $reportPath)) { throw "Missing PlatformMigrationIntegrationTest Surefire report." }
        [xml]$report = Get-Content -Raw -LiteralPath $reportPath
        $suite = $report.testsuite
        if ([int]$suite.tests -lt 1 -or [int]$suite.errors -ne 0 -or [int]$suite.failures -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "PlatformMigrationIntegrationTest requires executed tests with zero errors, failures, and skips."
        }
    } finally {
        Pop-Location
    }

    Push-Location "$repo/frontend"
    try {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw "Frontend dependency installation failed." }
        npm test
        if ($LASTEXITCODE -ne 0) { throw "Frontend tests failed." }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "Frontend build failed." }
    } finally {
        Pop-Location
    }

    Push-Location "$repo/geometry-worker"
    try {
        uv sync --locked
        if ($LASTEXITCODE -ne 0) { throw "Worker dependency synchronization failed." }
        uv run ruff check .
        if ($LASTEXITCODE -ne 0) { throw "Worker lint failed." }
        uv run ruff format --check .
        if ($LASTEXITCODE -ne 0) { throw "Worker formatting check failed." }
        uv run pytest -q
        if ($LASTEXITCODE -ne 0) { throw "Worker tests failed." }
    } finally {
        Pop-Location
    }

    Push-Location $repo
    try {
        & docker @compose build
        if ($LASTEXITCODE -ne 0) { throw "Container build failed." }
        & docker @compose up --wait
        if ($LASTEXITCODE -ne 0) { throw "Integrated stack health check failed." }
        Write-Output "Full verification passed: toolchains, tests, PostgreSQL boundary, images, and stack health."
    } finally {
        Pop-Location
    }
} finally {
    Push-Location $repo
    try {
        & docker @compose down --rmi local --volumes --remove-orphans
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Compose cleanup did not complete successfully."
        }
    } finally {
        Pop-Location
    }
}
