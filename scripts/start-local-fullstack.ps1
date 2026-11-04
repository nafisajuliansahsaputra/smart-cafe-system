#Requires -Version 5.1
<#
.SYNOPSIS
  Safe first-run local full-stack Smart Cafe portfolio demo on Windows.
.DESCRIPTION
  Requires Docker Desktop, a fresh checkout with no .env, and an operator-chosen
  ADMIN password. Uses a uniquely named Compose project and loopback-only ports.
  Does not enable merchant payments or real message dispatch.
#>
[CmdletBinding()]
param(
  [switch] $CheckOnly,
  [string] $AdminLogin = "demo-owner"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$composeFile = Join-Path $root "compose.yaml"
$envFile = Join-Path $root ".env"
$fixture = Join-Path $root "infra/fixtures/local-portfolio-catalog.sql"
$project = "smart-cafe-local-portfolio"
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Compose {
  param([string[]] $CommandParts)
  & docker compose --project-name $project --file $composeFile @CommandParts
  if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed on $($CommandParts -join ' ')" }
}

function RandomSecret {
  $bytes = New-Object byte[] 36
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $rng.GetBytes($bytes)
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
  } finally {
    $rng.Dispose()
    [Array]::Clear($bytes,0,$bytes.Length)
  }
}

function WaitForUrl {
  param([string] $Url)
  for ($i=0; $i -lt 90; $i++) {
    try {
      $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
      if ($response.StatusCode -eq 200) { return }
    } catch { Start-Sleep -Seconds 2 }
  }
  throw "Not ready: $Url. Inspect the local isolated Docker logs."
}

function RemoveBootstrapSecrets {
  if (-not (Test-Path -LiteralPath $envFile)) { return }
  $content = [System.IO.File]::ReadAllText($envFile)
  foreach($key in @("SPRING_PROFILES_ACTIVE","SCS_BOOTSTRAP_USERNAME","SCS_BOOTSTRAP_PASSWORD","SCS_BOOTSTRAP_LOCAL_ACK")) {
    $content = [regex]::Replace($content, "(?m)^" + $key + "=.*(?:\r?\n|$)", "")
  }
  [System.IO.File]::WriteAllText($envFile,$content,$utf8)
}

if (-not (Test-Path -LiteralPath $composeFile) -or -not (Test-Path -LiteralPath $fixture)) {
  throw "This script needs the complete Smart Cafe repository checkout."
}
& docker compose version | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker Compose is unavailable." }
& docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Docker Desktop is not running." }
if ($CheckOnly) {
  Write-Host "Docker and source checks passed. No files or containers changed."
  return
}

if (Test-Path -LiteralPath $envFile) {
  throw "Refused: .env exists. Start from a clean copy so no current settings are touched."
}
$containers = @(& docker ps --all --filter "label=com.docker.compose.project=$project" --quiet)
if ($LASTEXITCODE -ne 0) { throw "Cannot inspect isolated Compose project." }
if (@($containers | Where-Object { $_ -and $_.Trim() }).Count -gt 0) {
  throw "Refused: this local demo Compose project already has containers."
}
$volumes = @(& docker volume ls --filter "label=com.docker.compose.project=$project" --quiet)
if ($LASTEXITCODE -ne 0) { throw "Cannot inspect isolated Docker volumes." }
if (@($volumes | Where-Object { $_ -and $_.Trim() }).Count -gt 0) {
  throw "Refused: an existing isolated demo volume must not be reused for bootstrap."
}
if ($AdminLogin -notmatch '^[a-zA-Z][a-zA-Z0-9._-]{2,39}$') {
  throw "Admin login must be 3-40 safe ASCII characters starting with a letter."
}

Write-Host "Starting isolated local PostgreSQL, Spring Boot and Next.js on 127.0.0.1."
Write-Host "No real bank transactions, external receipt messages or public listeners."
$secure = Read-Host -AsSecureString "Choose a UNIQUE local admin password (16-72 letters/numbers/_/-)"
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
if ($password -cnotmatch '^[A-Za-z0-9_-]{16,72}$') {
  throw "Use 16-72 ASCII letters, digits, underscores or hyphens, without spaces."
}

$lines = @(
  "# Isolated portfolio demo ONLY. Do not reuse with production.",
  "POSTGRES_DB=smart_cafe",
  "POSTGRES_USER=smart_cafe",
  "POSTGRES_PASSWORD=$(RandomSecret)",
  "API_DOCS_ENABLED=false",
  "SCS_JOIN_THROTTLE_SECRET=$(RandomSecret)",
  "SCS_MENU_IMAGE_CLOUD_NAME=",
  "SPRING_PROFILES_ACTIVE=dev-bootstrap",
  "SCS_BOOTSTRAP_USERNAME=$AdminLogin",
  "SCS_BOOTSTRAP_PASSWORD=$password",
  "SCS_BOOTSTRAP_LOCAL_ACK=I_ACCEPT_LOCAL_ADMIN_BOOTSTRAP"
)
$password = $null
[System.IO.File]::WriteAllText($envFile, ($lines -join [Environment]::NewLine) + [Environment]::NewLine, $utf8)
$lines = $null

$bootstrapSucceeded = $false
try {
  Compose -CommandParts @("config","--quiet")
  Compose -CommandParts @("up","--build","--detach")
  WaitForUrl "http://127.0.0.1:8080/api/v1/system/status"
  $bootstrapSucceeded = $true
} finally {
  # Remove the one-time ADMIN password/profile from the persistent environment,
  # even if Docker startup fails. Never leave bootstrap active by accident.
  RemoveBootstrapSecrets
  if (-not $bootstrapSucceeded) {
    # Also stop any bootstrapping API container to prevent an on-disk
    # temporary credential from remaining active inside that process.
    & docker compose --project-name $project --file $composeFile stop api 2>$null | Out-Null
  }
}

# The first run created exactly one administrator. Restart API with the normal
# profile so it cannot attempt to provision another account on future starts.
Compose -CommandParts @("up","--detach","--force-recreate","api")
WaitForUrl "http://127.0.0.1:8080/api/v1/system/status"
# Synthetic catalog has no personal data, accounts, token or payment.
Get-Content -LiteralPath $fixture -Raw -Encoding UTF8 |
  & docker compose --project-name $project --file $composeFile exec -T db psql -X -v ON_ERROR_STOP=1 -U smart_cafe -d smart_cafe
if ($LASTEXITCODE -ne 0) { throw "Synthetic catalog seeding failed. Check local logs." }
WaitForUrl "http://127.0.0.1:3000/staff/login"
Write-Host "Ready: http://localhost:3000"
Write-Host "Staff: http://localhost:3000/staff/login"
Write-Host "User:  $AdminLogin"
Write-Host "Use the password you entered. Never enter real customer details."
Write-Host "Stop: docker compose --project-name $project --file compose.yaml down"
