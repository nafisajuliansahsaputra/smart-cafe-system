[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9.-]+\.aivencloud\.com$')]
    [string]$HostName,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int]$Port,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$CaFile,

    [string]$LoginPath = 'smartcafe_review'
)

# Opt-in Flyway rehearsal against ONE random disposable Aiven database only.
# The Aiven PUBLIC CA is converted into a temporary PKCS12 store by Java.
# No MySQL password in files, source code or process arguments.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$preflight = Join-Path $PSScriptRoot 'test-mysql-host-preflight.ps1'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $repoRoot 'backend'
$caPath = (Resolve-Path -LiteralPath $CaFile).Path
$null = Get-Command 'mysql' -ErrorAction Stop
$java = Get-Command 'java' -ErrorAction Stop
$maven = Get-Command 'mvn' -ErrorAction Stop
if (-not (Test-Path (Join-Path $backend 'pom.xml'))) {
    throw 'Missing backend/pom.xml. Run from the Smart Cafe repository.'
}

# Preflight before prompting for a password or touching the remote schema.
& $java.Source -version
if ($LASTEXITCODE -ne 0) { throw 'Java is not working. Install JDK 21.' }
& $maven.Source -version
if ($LASTEXITCODE -ne 0) { throw 'Maven is not working. Install Maven 3.9+.' }

Write-Host "Verifying read-only Aiven connection to $HostName ..."
$probeArgs = @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $preflight,
    '-LoginPath', $LoginPath, '-CaFile', $caPath, '-ExpectedHost', $HostName
)
& powershell.exe @probeArgs
if ($LASTEXITCODE -ne 0) {
    throw 'Read-only Aiven preflight failed. Nothing migrated.'
}

Write-Host ''
Write-Host 'This creates a random scs_m8_* database, runs Flyway 14+17 migrations,'
Write-Host 'validates history and triggers, then drops ONLY the scratch database.'
Write-Host 'defaultdb, PostgreSQL/Neon and running application are not modified.'
$confirmation = Read-Host 'Type MIGRATE-SCRATCH-ONLY to approve this isolated DDL rehearsal'
if ($confirmation -cne 'MIGRATE-SCRATCH-ONLY') {
    throw 'Hosted Flyway rehearsal cancelled without changes.'
}

# JDBC cannot read MySQL's local login-path itself. Prompt for the
# password here, and pass it solely to the short-lived local Maven test
# process using inherited process environment variables.
$securePassword = Read-Host 'Aiven password (hidden, local only)' -AsSecureString
$bstr = [IntPtr]::Zero
$plainPassword = $null
try {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    if ([string]::IsNullOrWhiteSpace($plainPassword)) {
        throw 'An empty password is not accepted.'
    }

    $env:SCS_M8_RUN = 'SCRATCH_ONLY'
    $env:SCS_M8_HOST = $HostName
    $env:SCS_M8_PORT = $Port.ToString()
    $env:SCS_M8_USERNAME = 'avnadmin'
    $env:SCS_M8_PASSWORD = $plainPassword
    $env:SCS_M8_CA = $caPath

    Push-Location -LiteralPath $backend
    try {
        & $maven.Source -B -ntp '-Dtest=HostedAivenFlywayReview' test
        if ($LASTEXITCODE -ne 0) {
            throw "Hosted migration test failed (Maven exit $LASTEXITCODE). Do NOT deploy."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    # Remove inherited environment credentials immediately.
    foreach ($name in @(
        'SCS_M8_RUN','SCS_M8_HOST','SCS_M8_PORT',
        'SCS_M8_USERNAME','SCS_M8_PASSWORD','SCS_M8_CA'
    )) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
    $plainPassword = $null
    if ($bstr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
    if ($null -ne $securePassword) {
        $securePassword.Dispose()
    }
}
Write-Host 'PASS: hosted Flyway scratch rehearsal finished; no persistent migration deployed.'
