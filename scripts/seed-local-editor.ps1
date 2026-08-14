param(
    [string]$RelativePath = "",
    [string]$UserId = "",
    [string]$ProjectId = "",
    [string]$AssetId = "",
    [string]$DataRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) "data"),
    [string]$ComposeProjectName = "scenery-foundry"
)

$ErrorActionPreference = "Stop"

function Resolve-SeedAssetPath {
    param(
        [Parameter(Mandatory)][string]$RelativePath,
        [Parameter(Mandatory)][string]$DataRoot,
        [scriptblock]$ReparsePointChecker
    )
    if ([string]::IsNullOrWhiteSpace($RelativePath)) { throw "Seed asset path is required." }
    if ([System.IO.Path]::IsPathRooted($RelativePath)) { throw "Seed asset path must be relative: $RelativePath" }
    if ($RelativePath -notmatch '(?i)\.stl$') { throw "Seed asset path must end with .stl: $RelativePath" }

    $rootFull = [System.IO.Path]::GetFullPath($DataRoot)
    $candidateFull = [System.IO.Path]::GetFullPath((Join-Path $rootFull $RelativePath))
    $rootPrefix = $rootFull.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidateFull.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Seed asset path escapes the data root: $RelativePath"
    }
    if (-not (Test-Path -LiteralPath $candidateFull -PathType Leaf)) {
        throw "Seed asset file does not exist: $RelativePath"
    }

    $checker = $ReparsePointChecker
    if (-not $checker) { $checker = { param($Path) ((Get-Item -LiteralPath $Path -Force).Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 } }
    if (& $checker $candidateFull) { throw "Seed asset path is a symlink, which is not permitted: $RelativePath" }

    [pscustomobject]@{ StorageKey = ($RelativePath -replace '\\', '/'); AbsolutePath = $candidateFull }
}

function Get-SeedAssetSha256([Parameter(Mandatory)][string]$AbsolutePath) {
    $bytes = [System.IO.File]::ReadAllBytes($AbsolutePath)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    ([System.BitConverter]::ToString($hash) -replace '-', '').ToLowerInvariant()
}

function Assert-SeedOwnership {
    param(
        [Parameter(Mandatory)][string]$UserId,
        [Parameter(Mandatory)][string]$ProjectId,
        [Parameter(Mandatory)][scriptblock]$UserExistsLookup,
        [Parameter(Mandatory)][scriptblock]$ProjectOwnerLookup
    )
    if (-not (& $UserExistsLookup $UserId)) { throw "Seed user does not exist: $UserId" }
    $ownerId = & $ProjectOwnerLookup $ProjectId
    if (-not $ownerId -or $ownerId -ne $UserId) { throw "Seed project is not owned by the given user: $ProjectId" }
}

function Invoke-SeedPsql([string]$ComposeProjectName, [string]$Sql) {
    $output = docker compose --project-name $ComposeProjectName exec -T postgres psql -v ON_ERROR_STOP=1 -U scenery -d scenery_foundry -Atqc $Sql
    if ($LASTEXITCODE -ne 0) { throw "psql command failed: $Sql" }
    return ($output | Select-Object -First 1)
}

if ($MyInvocation.InvocationName -ne '.') {
    if (-not $RelativePath -or -not $UserId -or -not $ProjectId -or -not $AssetId) {
        throw "RelativePath, UserId, ProjectId, and AssetId are required to seed a local editor fixture."
    }
    $resolved = Resolve-SeedAssetPath -RelativePath $RelativePath -DataRoot $DataRoot
    $sha = Get-SeedAssetSha256 -AbsolutePath $resolved.AbsolutePath
    Assert-SeedOwnership -UserId $UserId -ProjectId $ProjectId `
        -UserExistsLookup { param($id) [bool](Invoke-SeedPsql $ComposeProjectName "select 1 from users where id='$id'") } `
        -ProjectOwnerLookup { param($id) Invoke-SeedPsql $ComposeProjectName "select owner_id from projects where id='$id'" }
    Invoke-SeedPsql $ComposeProjectName ("insert into prepared_assets(id,project_id,processing_status,geometry_status,storage_key,original_sha256) values " +
        "('$AssetId','$ProjectId','READY','VALID_VOLUME','$($resolved.StorageKey)','$sha') " +
        "on conflict (id) do update set storage_key=excluded.storage_key, original_sha256=excluded.original_sha256") | Out-Null
    Write-Output "Seeded asset $AssetId -> $($resolved.StorageKey) ($sha)"
}
