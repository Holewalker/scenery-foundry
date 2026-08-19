[CmdletBinding()]
param(
    [string]$BackendPort = "18089",
    [int]$HealthTimeoutSeconds = 180
)

<#
Proves PR-1's EXDEV fix end-to-end: brings up the real `postgres` + `backend` containers from
compose.yml (SCENE_DATA_ROOT=/data, a bind-mounted filesystem distinct from the container's
internal writable layer where the JVM default temp dir lives), performs one authenticated STL
upload over real HTTP, and asserts the published file exists — byte-identical — under the host's
bind-mounted ./data directory. Before PR-1, AssetIntakeService staged the upload in the JVM
default temp dir and StorageResolver.publish()'s Files.createLink() crossed a filesystem boundary,
throwing EXDEV. This script only passes when that boundary is closed.
#>

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Split-Path -Parent $PSScriptRoot)).Path
$project = "sf-e2e-asset-upload-$PID"
$compose = @("compose", "--project-name", $project)
$env:BACKEND_PORT = $BackendPort
$baseUrl = "http://127.0.0.1:$BackendPort"

$testUserId = [guid]::NewGuid().ToString()
$testEmail = "e2e-upload-$testUserId@example.com"
$testPassword = "e2e-upload-secret"
# BCryptPasswordEncoder(12).encode("e2e-upload-secret") — precomputed offline with the same
# spring-security-crypto version this backend uses; round-trip verified via .matches() before use.
$testPasswordHash = '$2a$12$E0hb.8JAuDz936F1/OoVS.m1Jm0E3.qUmsLx7cc.YNM6Nyxctt9Ry'

function Wait-BackendHealthy([string]$Url, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri "$Url/actuator/health" -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200) { return }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "Backend did not become healthy within $TimeoutSeconds seconds."
}

function New-MultipartFormBody([string]$FieldName, [string]$FileName, [byte[]]$Content, [string]$Boundary) {
    $header = "--$Boundary`r`nContent-Disposition: form-data; name=`"$FieldName`"; filename=`"$FileName`"`r`nContent-Type: application/octet-stream`r`n`r`n"
    $footer = "`r`n--$Boundary--`r`n"
    $headerBytes = [System.Text.Encoding]::UTF8.GetBytes($header)
    $footerBytes = [System.Text.Encoding]::UTF8.GetBytes($footer)
    $body = New-Object byte[] ($headerBytes.Length + $Content.Length + $footerBytes.Length)
    [Array]::Copy($headerBytes, 0, $body, 0, $headerBytes.Length)
    [Array]::Copy($Content, 0, $body, $headerBytes.Length, $Content.Length)
    [Array]::Copy($footerBytes, 0, $body, $headerBytes.Length + $Content.Length, $footerBytes.Length)
    return ,$body
}

function Invoke-Psql([string]$Sql) {
    $output = docker @compose exec -T postgres psql -v ON_ERROR_STOP=1 -U scenery -d scenery_foundry -Atqc $Sql
    if ($LASTEXITCODE -ne 0) { throw "psql command failed: $Sql" }
    return ($output | Select-Object -First 1)
}

Push-Location $repo
try {
    docker @compose up -d --build postgres backend
    if ($LASTEXITCODE -ne 0) { throw "Failed to start postgres/backend for the E2E asset upload proof." }

    Wait-BackendHealthy -Url $baseUrl -TimeoutSeconds $HealthTimeoutSeconds

    $insertUserSql = "insert into users(id,email,password_hash) values ('" + $testUserId + "','" + $testEmail + "','" + $testPasswordHash + "');"
    Invoke-Psql $insertUserSql | Out-Null

    $csrf = Invoke-RestMethod -Uri "$baseUrl/api/csrf" -SessionVariable session
    Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -WebSession $session `
        -Headers @{ $csrf.headerName = $csrf.token } -ContentType "application/json" `
        -Body (@{ email = $testEmail; password = $testPassword } | ConvertTo-Json) | Out-Null

    $boundary = [guid]::NewGuid().ToString()
    $stlContent = [System.Text.Encoding]::UTF8.GetBytes("solid e2e-exdev-proof`nendsolid e2e-exdev-proof`n")
    $body = New-MultipartFormBody -FieldName "file" -FileName "e2e-proof.stl" -Content $stlContent -Boundary $boundary

    $uploadResponse = Invoke-RestMethod -Uri "$baseUrl/api/assets" -Method Post -WebSession $session `
        -Headers @{ $csrf.headerName = $csrf.token } -ContentType "multipart/form-data; boundary=$boundary" -Body $body

    if ($uploadResponse.processingStatus -ne "UPLOADED") {
        throw "Expected the upload to report UPLOADED status; got: $($uploadResponse.processingStatus)"
    }

    $assetId = $uploadResponse.assetId
    $storageKey = Invoke-Psql "select storage_key from assets where id='$assetId';"
    if ([string]::IsNullOrWhiteSpace($storageKey)) { throw "No storage_key was persisted for uploaded asset $assetId." }

    $relativePath = $storageKey.Trim().Replace('/', [System.IO.Path]::DirectorySeparatorChar)
    $publishedPath = Join-Path (Join-Path $repo "data") $relativePath
    if (-not (Test-Path -LiteralPath $publishedPath -PathType Leaf)) {
        throw "EXDEV regression: no published file found under SCENE_DATA_ROOT at $publishedPath"
    }

    $publishedBytes = [System.IO.File]::ReadAllBytes($publishedPath)
    if ($publishedBytes.Length -ne $stlContent.Length -or (Compare-Object $publishedBytes $stlContent -SyncWindow 0)) {
        throw "Published file content at $publishedPath does not match the uploaded bytes."
    }

    Write-Output "E2E PASS: STL upload succeeded under compose.yml; published file verified byte-identical at $publishedPath (EXDEV fixed)."
} finally {
    Pop-Location
    Push-Location $repo
    try {
        docker @compose down --volumes --remove-orphans | Out-Null
    } finally {
        Pop-Location
    }
}
