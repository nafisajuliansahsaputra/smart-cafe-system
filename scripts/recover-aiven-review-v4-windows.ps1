[CmdletBinding()]
param(
    [string]$HostName = 'mysql-1e5b1eb2-nafisajuliansahsaputra-811e.b.aivencloud.com',
    [ValidateRange(1,65535)][int]$Port = 12886,
    [string]$CaFile = "$env:LOCALAPPDATA\SmartCafe\ca.pem",
    [string]$LoginPath = 'smartcafe_review'
)

# Recover precisely failed V4 in an EMPTY synthetic-only Aiven schema.
# No credentials in code, command-line arguments, Git or Vercel logs.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$schema = 'scs_review_natsx'
$approval = 'RECOVER_EMPTY_REVIEW_V4'

if ($HostName -notmatch '^[a-zA-Z0-9.-]+\.aivencloud\.com$') {
    throw 'Only an explicit Aiven host is permitted.'
}
if (-not (Test-Path -LiteralPath $CaFile -PathType Leaf)) {
    throw "CA certificate missing at $CaFile"
}
$ca = (Resolve-Path -LiteralPath $CaFile).Path
$mysql = Get-Command mysql -ErrorAction Stop
$java = Get-Command java -ErrorAction Stop
$mvn = Get-Command mvn -ErrorAction Stop
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $repo 'backend'
if (-not (Test-Path (Join-Path $backend 'pom.xml'))) {
    throw 'Missing backend/pom.xml: run from the Smart Cafe repo.'
}

Write-Host 'Checking trusted connection and read-only Flyway V4 state...'
$query = @'
SELECT version,success
FROM flyway_schema_history
ORDER BY installed_rank;
SELECT 'receipts' AS object_name,COUNT(*) AS object_count
FROM information_schema.tables
WHERE table_schema=DATABASE() AND table_name='receipts';
SELECT trigger_name FROM information_schema.triggers
WHERE trigger_schema=DATABASE()
AND trigger_name IN ('receipt_no_update','receipt_no_delete','order_snapshot_no_update')
ORDER BY trigger_name;
'@
$opts = @(
    "--login-path=$LoginPath",
    '--ssl-mode=VERIFY_IDENTITY',
    "--ssl-ca=$ca",
    "--database=$schema",
    '--batch',
    '-e',
    $query
)
& $mysql.Source @opts
if ($LASTEXITCODE -ne 0) {
    throw 'Verified TLS read-only check failed. No migration changes made.'
}

Write-Host ''
Write-Host 'Review schema: scs_review_natsx ONLY'
Write-Host 'The Java recovery will independently refuse ANY nonempty business table,'
Write-Host 'unexpected Flyway history, missing TLS, other database, or unknown V4 state.'
Write-Host 'It may drop ONLY empty partial-V4 receipts/trigger objects, run the'
Write-Host 'official Flyway repair, then migrate and validate versions V4-V31.'
$answer = Read-Host "Type $approval to approve THIS isolated, empty review migration"
if ($answer -cne $approval) {
    throw 'Recovery canceled: no change made.'
}

# JDBC does not read mysql_config_editor's local login path.
# The password is entered hidden, passed only via local Maven child process
# environment variables, then cleared immediately, including the BSTR.
$secret = Read-Host 'Aiven DB password (hidden, never sent to ChatGPT)' -AsSecureString
$bstr = [IntPtr]::Zero
$plaintext = $null
try {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    $plaintext = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    if ([string]::IsNullOrWhiteSpace($plaintext)) {
        throw 'Blank password refused.'
    }
    $env:SCS_V4_APPROVAL = $approval
    $env:SCS_V4_HOST = $HostName
    $env:SCS_V4_PORT = $Port.ToString()
    $env:SCS_V4_USERNAME = 'avnadmin'
    $env:SCS_V4_PASSWORD = $plaintext
    $env:SCS_V4_CA = $ca

    Push-Location -LiteralPath $backend
    try {
        & $mvn.Source -B -ntp '-Dtest=HostedAivenV4Recovery' test
        if ($LASTEXITCODE -ne 0) {
            throw "MySQL V4 recovery failed; Maven exit code $LASTEXITCODE. Stop and inspect before retry."
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    foreach ($key in @('SCS_V4_APPROVAL','SCS_V4_HOST','SCS_V4_PORT',
                       'SCS_V4_USERNAME','SCS_V4_PASSWORD','SCS_V4_CA')) {
        Remove-Item "Env:$key" -ErrorAction SilentlyContinue
    }
    $plaintext = $null
    if ($bstr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
    if ($null -ne $secret) {
        $secret.Dispose()
    }
}
Write-Host 'PASS: 31 MySQL migrations validated on isolated Aiven review database.'
