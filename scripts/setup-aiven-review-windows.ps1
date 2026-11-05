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

# One-time synthetic ONLY: never run on production, existing business data,
# defaultdb or any real customer / merchant system. No Flyway clean or DDL.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $root 'backend'
$caPath = (Resolve-Path -LiteralPath $CaFile).Path
if ($HostName -cne 'mysql-1e5b1eb2-nafisajuliansahsaputra-811e.b.aivencloud.com' -or $Port -ne 12886) {
    throw 'Refusing an unexpected Aiven host or port.'
}
if (-not (Test-Path (Join-Path $backend 'src/test/java/com/natsx/smartcafe/HostedAivenReviewOnboarding.java'))) {
    throw 'Owner-local synthetic review setup source missing. Fetch the correct review branch.'
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -Like 'jdk-21*' | Sort-Object Name -Descending | Select-Object -First 1
    if ($null -eq $jdk) { throw 'Java 21 was not found.' }
    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    $mavenBin = "$HOME\tools\apache-maven-3.9.11\bin"
    if (-not (Test-Path (Join-Path $mavenBin 'mvn.cmd'))) {
        throw 'Maven 3.9.11 was not found at the previously installed path.'
    }
    $env:Path = "$mavenBin;$env:Path"
}
$maven = Get-Command mvn -ErrorAction Stop

Write-Host ''
Write-Host 'Only scs_review_natsx. This can create exactly one synthetic ADMIN,'
Write-Host 'five cafe tables and seven menu items. It never executes a payment.'
Write-Host 'It refuses writes if ANY pre-existing business record is found.'
Write-Host 'No public or production database will be touched.'
Write-Host ''
$ack = Read-Host 'Type INITIALIZE-SYNTHETIC-REVIEW to authorize this one-time operation'
if ($ack -cne 'INITIALIZE-SYNTHETIC-REVIEW') {
    throw 'Review onboarding was not authorized. No writes attempted.'
}
$login = Read-Host 'Choose your admin username (3-40 letters/numbers/dot/underscore/hyphen)'
if ($login -cnotmatch '^[A-Za-z][A-Za-z0-9._-]{2,39}$') {
    throw 'Invalid admin username. No writes attempted.'
}
$adminSecure = Read-Host 'Choose a UNIQUE review admin password (24-72 ASCII characters)' -AsSecureString
$againSecure = Read-Host 'Repeat review admin password' -AsSecureString
$dbSecure = Read-Host 'Aiven database password (local only)' -AsSecureString

$ptrAdmin = [IntPtr]::Zero
$ptrAgain = [IntPtr]::Zero
$ptrDb = [IntPtr]::Zero
$adminPlain = $null
$againPlain = $null
$dbPlain = $null
try {
    $ptrAdmin = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($adminSecure)
    $ptrAgain = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($againSecure)
    $ptrDb = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($dbSecure)
    $adminPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptrAdmin)
    $againPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptrAgain)
    $dbPlain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptrDb)

    if ($adminPlain -cne $againPlain) { throw 'Admin password confirmation differs.' }
    if ($adminPlain.Length -lt 24 -or $adminPlain.Length -gt 72 -or
        $adminPlain -cnotmatch '^[\x21-\x7E]+$') {
        throw 'Admin password must contain 24-72 printable ASCII characters without spaces.'
    }
    if ([string]::IsNullOrWhiteSpace($dbPlain)) {
        throw 'Aiven database password is required.'
    }

    $env:SCS_ONBOARD_ACK = 'INITIALIZE_SYNTHETIC_REVIEW'
    $env:SCS_ONBOARD_HOST = $HostName
    $env:SCS_ONBOARD_PORT = $Port.ToString()
    $env:SCS_ONBOARD_CA = $caPath
    $env:SCS_ONBOARD_DB_USERNAME = 'avnadmin'
    $env:SCS_ONBOARD_DB_PASSWORD = $dbPlain
    $env:SCS_ONBOARD_LOGIN = $login
    $env:SCS_ONBOARD_ADMIN_PASSWORD = $adminPlain

    Push-Location $backend
    try {
        & $maven.Source '-B' '-ntp' '-Dtest=HostedAivenReviewOnboarding' test
        if ($LASTEXITCODE -ne 0) { throw 'Onboarding failed. Do not attempt blind retries or SQL cleanup.' }
    } finally {
        Pop-Location
    }
    Write-Host 'PASS: review admin and synthetic cafe data initialized.'
    Write-Host 'Log in through the preview /staff/login page with the username/password you entered locally.'
}
finally {
    foreach ($key in @('SCS_ONBOARD_ACK','SCS_ONBOARD_HOST','SCS_ONBOARD_PORT','SCS_ONBOARD_CA',
                       'SCS_ONBOARD_DB_USERNAME','SCS_ONBOARD_DB_PASSWORD','SCS_ONBOARD_LOGIN',
                       'SCS_ONBOARD_ADMIN_PASSWORD')) {
        Remove-Item "Env:$key" -ErrorAction SilentlyContinue
    }
    $adminPlain = $null
    $againPlain = $null
    $dbPlain = $null
    if ($ptrAdmin -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptrAdmin) }
    if ($ptrAgain -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptrAgain) }
    if ($ptrDb -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptrDb) }
    if ($null -ne $adminSecure) { $adminSecure.Dispose() }
    if ($null -ne $againSecure) { $againSecure.Dispose() }
    if ($null -ne $dbSecure) { $dbSecure.Dispose() }
}
