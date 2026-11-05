[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9.-]+\.aivencloud\.com$')]
    [string]$HostName,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1,65535)]
    [int]$Port,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$CaFile
)

# One-time owner-local migration of scs_review_natsx only. Requires JDK 21,
# Maven, verified Aiven CA and a separate hidden credential prompt.
# The target is never inferred from an URL, env file or user input.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $root 'backend'
$caPath = (Resolve-Path -LiteralPath $CaFile).Path
$null = Get-Command java -ErrorAction Stop
$maven = Get-Command mvn -ErrorAction Stop
if (-not (Test-Path (Join-Path $backend 'pom.xml'))) {
    throw 'Backend source was not found. Run this from the expected Git branch.'
}
if (-not (Test-Path (Join-Path $backend 'src/test/java/com/natsx/smartcafe/HostedAivenPersistentReview.java'))) {
    throw 'HostedAivenPersistentReview.java is missing. Update to the review-migration branch.'
}

Write-Host 'The Java runner will verify Aiven TLS, schema identity and the absence of business records before any schema change.'

Write-Host ''
Write-Host 'TARGET: ONLY scs_review_natsx on this Aiven Free MySQL server.'
Write-Host 'First, every data table is checked for records. If any customer,'
Write-Host 'staff, menu, order or transaction rows exist, the script FAILS CLOSED.'
Write-Host 'Only then, if migrations are incomplete, the existing empty synthetic'
Write-Host 'schema is cleaned and Flyway V1 through V31 applied and validated.'
Write-Host 'defaultdb, Neon, real payment integrations and other projects are untouched.'
Write-Host ''
$confirmation = Read-Host 'Type RESET-EMPTY-SYNTHETIC-REVIEW to approve this isolated cleanup'
if ($confirmation -cne 'RESET-EMPTY-SYNTHETIC-REVIEW') {
    throw 'Operation cancelled without modifying Aiven.'
}

$securePassword = Read-Host 'Aiven DB password (hidden; local Maven process only)' -AsSecureString
$pointer = [IntPtr]::Zero
$plain = $null
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ([string]::IsNullOrWhiteSpace($plain)) {
        throw 'Database password cannot be blank.'
    }
    $env:SCS_REVIEW_MIGRATION_AUTHORIZATION = 'RESET_EMPTY_SYNTHETIC_REVIEW_ONLY'
    $env:SCS_REVIEW_HOST = $HostName
    $env:SCS_REVIEW_PORT = $Port.ToString()
    $env:SCS_REVIEW_USERNAME = 'avnadmin'
    $env:SCS_REVIEW_PASSWORD = $plain
    $env:SCS_REVIEW_CA = $caPath

    Push-Location -LiteralPath $backend
    try {
        & $maven.Source '-B' '-ntp' '-Dtest=HostedAivenPersistentReview' test
        if ($LASTEXITCODE -ne 0) {
            throw "Review migration failed (Maven exit code $LASTEXITCODE). Do not redeploy yet."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    foreach ($key in @(
        'SCS_REVIEW_MIGRATION_AUTHORIZATION','SCS_REVIEW_HOST','SCS_REVIEW_PORT',
        'SCS_REVIEW_USERNAME','SCS_REVIEW_PASSWORD','SCS_REVIEW_CA'
    )) {
        Remove-Item "Env:$key" -ErrorAction SilentlyContinue
    }
    $plain = $null
    if ($pointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
    if ($null -ne $securePassword) {
        $securePassword.Dispose()
    }
}
Write-Host 'PASS: isolated scs_review_natsx migrations are validated and persistent.'
