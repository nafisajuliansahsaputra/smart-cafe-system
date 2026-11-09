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

# Explicit, one-time OWNER-LOCAL initialization of an EMPTY synthetic MySQL
# review schema only. This script never touches Vercel env vars, other DBs,
# payment adapters or local git files, and requires typed authorization.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $root 'backend'
$caPath = (Resolve-Path -LiteralPath $CaFile).Path
$null = Get-Command java -ErrorAction Stop

function Get-ReviewMaven {
    $installed = Get-Command mvn -ErrorAction SilentlyContinue
    if ($null -ne $installed) { return $installed.Source }

    $version = '3.9.16'
    $tools = Join-Path $env:LOCALAPPDATA 'SmartCafe\tools'
    $mavenDir = Join-Path $tools "apache-maven-$version"
    $mavenCmd = Join-Path $mavenDir 'bin\mvn.cmd'
    if (Test-Path -LiteralPath $mavenCmd -PathType Leaf) { return $mavenCmd }

    $base = "https://downloads.apache.org/maven/maven-3/$version/binaries"
    $zipName = "apache-maven-$version-bin.zip"
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) (
        'scs_maven_' + [System.Guid]::NewGuid().ToString('N'))
    New-Item -Path $temp -ItemType Directory -Force | Out-Null
    try {
        $archive = Join-Path $temp $zipName
        Write-Host "Maven not found. Downloading official Apache Maven $version with SHA-512 verification..."
        $checksumText = [string](Invoke-WebRequest -Uri "$base/$zipName.sha512" -UseBasicParsing -ErrorAction Stop).Content
        if ($checksumText -notmatch '(?i)^[0-9a-f]{128}(?:\s|$)') {
            throw 'Apache Maven SHA-512 checksum response was not in the expected format.'
        }
        $expected = $Matches[0].Trim().ToUpperInvariant()
        Invoke-WebRequest -Uri "$base/$zipName" -OutFile $archive -UseBasicParsing -ErrorAction Stop
        $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash.ToUpperInvariant()
        if ($actual -cne $expected) {
            throw 'Apache Maven archive integrity verification failed; refusing to execute it.'
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $temp -Force
        $expanded = Join-Path $temp "apache-maven-$version"
        if (-not (Test-Path -LiteralPath (Join-Path $expanded 'bin\mvn.cmd') -PathType Leaf)) {
            throw 'Verified Maven archive has an unexpected directory layout.'
        }
        New-Item -Path $tools -ItemType Directory -Force | Out-Null
        Move-Item -LiteralPath $expanded -Destination $mavenDir -ErrorAction Stop
        Write-Host "Verified Apache Maven $version is ready in the SmartCafe user directory."
    }
    finally {
        Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (-not (Test-Path -LiteralPath $mavenCmd -PathType Leaf)) {
        throw 'Portable Maven setup did not complete. Review Aiven database was not modified.'
    }
    return $mavenCmd
}
$maven = Get-ReviewMaven
if (-not (Test-Path -LiteralPath (Join-Path $backend 'pom.xml') -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $backend 'src/test/java/com/natsx/smartcafe/HostedAivenOwnerAdminProvision.java') -PathType Leaf)) {
    throw 'Owner-local bootstrap source is missing. Fetch the updated review branch first.'
}

Write-Host ''
Write-Host 'TARGET: ONLY the existing scs_review_natsx Aiven MySQL schema.'
Write-Host 'Review ADMIN creation requires V1-V31 validation and zero existing staff users.'
Write-Host 'The script FAILS CLOSED on any other database, username, migration mismatch, or existing staff.'
Write-Host 'No real payments, production database, Flyway clean or schema migration.'
Write-Host 'Rotate the previously exposed Aiven DB password before running this script.'
Write-Host 'The ADMIN login/password you choose must be retained privately for first sign-in.'
Write-Host ''
$approval = Read-Host 'Type CREATE-ONE-SYNTHETIC-REVIEW-ADMIN to approve exactly one ADMIN insert'
if ($approval -cne 'CREATE-ONE-SYNTHETIC-REVIEW-ADMIN') {
    throw 'Cancelled. No database changes were made.'
}
$username = Read-Host 'Choose review ADMIN login (letters/numbers/._-, 3 to 40 characters)'
if ($username -cnotmatch '^[A-Za-z][A-Za-z0-9._-]{2,39}$') {
    throw 'ADMIN login is invalid; no database changes were made.'
}
$secureDb = Read-Host 'Aiven DB password (hidden)' -AsSecureString
$secureAdmin = $null
$secureConfirm = $null

function Read-ReviewSecureString([Security.SecureString]$secret) {
    $ptr = [IntPtr]::Zero
    try {
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    }
    finally {
        if ($ptr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        }
    }
}

$rawDb = $null
$rawAdmin = $null
$rawConfirm = $null
try {
    $rawDb = Read-ReviewSecureString $secureDb
    if ([string]::IsNullOrWhiteSpace($rawDb)) {
        throw 'Database password must not be blank.'
    }
    # A typo at confirmation must not force the owner to fetch the branch,
    # authorize the action, or retype the Aiven DB password all over again.
    # No database connection or write happens before these checks pass.
    $acceptedAdminPassword = $false
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $secureAdmin = Read-Host 'NEW review ADMIN password, 24-128 chars (hidden)' -AsSecureString
        $secureConfirm = Read-Host 'Confirm NEW review ADMIN password (hidden)' -AsSecureString
        $rawAdmin = Read-ReviewSecureString $secureAdmin
        $rawConfirm = Read-ReviewSecureString $secureConfirm
        if ($rawAdmin.Length -ge 24 -and $rawAdmin.Length -le 128 -and
            $rawAdmin -cne '' -and $rawAdmin -ceq $rawConfirm) {
            $acceptedAdminPassword = $true
            break
        }
        Write-Warning "Password too short, too long or confirmation differs. Attempt $attempt of 3."
        $rawAdmin = $null
        $rawConfirm = $null
        $secureAdmin.Dispose()
        $secureConfirm.Dispose()
        $secureAdmin = $null
        $secureConfirm = $null
    }
    if (-not $acceptedAdminPassword) {
        throw 'Three invalid ADMIN password confirmations; no database changes were made.'
    }
    # Temporary inherited variables are limited to this owner-local Maven
    # process, then deleted even when compilation, TLS or SQL checks fail.
    $env:SCS_OWNER_PROVISION_AUTHORIZATION = 'CREATE_ONE_SYNTHETIC_AIVEN_ADMIN'
    $env:SCS_OWNER_AIVEN_HOST = $HostName
    $env:SCS_OWNER_AIVEN_PORT = $Port.ToString()
    $env:SCS_OWNER_AIVEN_USERNAME = 'avnadmin'
    $env:SCS_OWNER_AIVEN_PASSWORD = $rawDb
    $env:SCS_OWNER_AIVEN_CA = $caPath
    $env:SCS_OWNER_ADMIN_USERNAME = $username
    $env:SCS_OWNER_ADMIN_PASSWORD = $rawAdmin
    Push-Location -LiteralPath $backend
    try {
        & $maven '-B' '-ntp' '-Dtest=HostedAivenOwnerAdminProvision' test
        if ($LASTEXITCODE -ne 0) {
            throw "Review ADMIN provisioning failed (Maven exit code $LASTEXITCODE). Do not rerun blindly."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    foreach ($key in @(
        'SCS_OWNER_PROVISION_AUTHORIZATION','SCS_OWNER_AIVEN_HOST',
        'SCS_OWNER_AIVEN_PORT','SCS_OWNER_AIVEN_USERNAME',
        'SCS_OWNER_AIVEN_PASSWORD','SCS_OWNER_AIVEN_CA',
        'SCS_OWNER_ADMIN_USERNAME','SCS_OWNER_ADMIN_PASSWORD'
    )) {
        Remove-Item "Env:$key" -ErrorAction SilentlyContinue
    }
    $rawDb = $null
    $rawAdmin = $null
    $rawConfirm = $null
    $secureDb.Dispose()
    if ($null -ne $secureAdmin) { $secureAdmin.Dispose() }
    if ($null -ne $secureConfirm) { $secureConfirm.Dispose() }
}
Write-Host 'PASS: first synthetic Aiven review ADMIN is provisioned. Do not run again.'
