$ErrorActionPreference = "Stop"
. "$PSScriptRoot/seed-local-editor.ps1"

function Assert-Equal([object]$Actual, [object]$Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message. Expected '$Expected', got '$Actual'." }
}

function Assert-Throws([scriptblock]$Action, [string]$Message) {
    $threw = $false
    try { & $Action | Out-Null } catch { $threw = $true }
    if (-not $threw) { throw "$Message. Expected an error to be thrown." }
}

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("seed-local-editor-test-" + [guid]::NewGuid().ToString("N"))
$seedDir = Join-Path $testRoot "seed"
New-Item -ItemType Directory -Path $seedDir -Force | Out-Null
try {
    $validFile = Join-Path $seedDir "fixture.stl"
    Set-Content -LiteralPath $validFile -Value "solid fixture endsolid fixture" -NoNewline

    $resolved = Resolve-SeedAssetPath -RelativePath "seed/fixture.stl" -DataRoot $testRoot
    Assert-Equal -Actual $resolved.StorageKey -Expected "seed/fixture.stl" -Message "Valid fixture must normalize its storage key"
    Assert-Equal -Actual $resolved.AbsolutePath -Expected ([System.IO.Path]::GetFullPath($validFile)) -Message "Valid fixture must resolve under the data root"

    $expectedShaBytes = [System.Security.Cryptography.SHA256]::Create().ComputeHash([System.IO.File]::ReadAllBytes($validFile))
    $expectedSha = ([System.BitConverter]::ToString($expectedShaBytes) -replace '-', '').ToLowerInvariant()
    Assert-Equal -Actual (Get-SeedAssetSha256 -AbsolutePath $resolved.AbsolutePath) -Expected $expectedSha -Message "Valid fixture must compute its SHA-256 checksum"

    Assert-Throws -Action { Resolve-SeedAssetPath -RelativePath "seed/missing.stl" -DataRoot $testRoot } -Message "Missing seed file must be rejected"
    Assert-Throws -Action { Resolve-SeedAssetPath -RelativePath $validFile -DataRoot $testRoot } -Message "Absolute seed path must be rejected"
    Assert-Throws -Action { Resolve-SeedAssetPath -RelativePath "../outside.stl" -DataRoot $seedDir } -Message "Traversal outside the data root must be rejected"
    Assert-Throws -Action { Resolve-SeedAssetPath -RelativePath "seed/fixture.txt" -DataRoot $testRoot } -Message "Non-STL seed path must be rejected"
    Assert-Throws -Action {
        Resolve-SeedAssetPath -RelativePath "seed/fixture.stl" -DataRoot $testRoot -ReparsePointChecker { param($Path) $true }
    } -Message "Symlinked seed path must be rejected"
    Assert-Throws -Action {
        Resolve-SeedAssetPath -RelativePath "seed/fixture.stl" -DataRoot $testRoot -ReparsePointChecker {
            param($Path) $Path -eq (Join-Path $testRoot "seed")
        }
    } -Message "Symlinked ancestor directory must be rejected even when the final file itself is not a symlink"

    Assert-Throws -Action {
        Assert-SeedOwnership -UserId ([guid]::NewGuid()) -ProjectId ([guid]::NewGuid()) `
            -UserExistsLookup { param($id) $false } -ProjectOwnerLookup { param($id) $null }
    } -Message "Unknown seed user must be rejected"

    $projectId = [guid]::NewGuid()
    $ownerId = [guid]::NewGuid()
    $strangerId = [guid]::NewGuid()
    Assert-Throws -Action {
        Assert-SeedOwnership -UserId $strangerId -ProjectId $projectId `
            -UserExistsLookup { param($id) $true } -ProjectOwnerLookup { param($id) $ownerId }
    } -Message "Mismatched seed project owner must be rejected"

    Assert-SeedOwnership -UserId $ownerId -ProjectId $projectId `
        -UserExistsLookup { param($id) $true } -ProjectOwnerLookup { param($id) $ownerId }

    Assert-Throws -Action {
        Confirm-SeedAssetInserted -InsertedId "" -AssetId ([guid]::NewGuid())
    } -Message "Cross-project asset id conflict (no row returned) must be rejected"
    Assert-Throws -Action {
        Confirm-SeedAssetInserted -InsertedId $null -AssetId ([guid]::NewGuid())
    } -Message "Missing insert result must be rejected"
    $confirmedId = [guid]::NewGuid().ToString()
    Assert-Equal -Actual (Confirm-SeedAssetInserted -InsertedId $confirmedId -AssetId $confirmedId) -Expected $confirmedId `
        -Message "A returned id must confirm the seed insert succeeded"
} finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output "Local editor seed checks passed."
