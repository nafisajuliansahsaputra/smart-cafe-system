[CmdletBinding(DefaultParameterSetName = 'OptionsFile')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'OptionsFile')]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$DefaultsFile,

    [Parameter(Mandatory = $true, ParameterSetName = 'LoginPath')]
    [ValidatePattern('^[A-Za-z0-9_-]{1,64}$')]
    [string]$LoginPath,

    [Parameter(Mandatory = $true, ParameterSetName = 'LoginPath')]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$CaFile,

    [ValidatePattern('^[A-Za-z0-9.-]+$')]
    [string]$ExpectedHost,

    [string]$MySqlClient = 'mysql',

    [switch]$RunWriteProbe
)

# Read-only by default. Never pass passwords as command-line arguments.
# -RunWriteProbe creates and removes ONE random scratch schema only.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($RunWriteProbe) {
    if ([string]::IsNullOrWhiteSpace($ExpectedHost)) {
        throw '-ExpectedHost is required for any write probe.'
    }
    if (-not $ExpectedHost.EndsWith('.aivencloud.com', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Write probes are restricted to an explicit Aiven review hostname.'
    }
}

$mysqlExe = (Get-Command $MySqlClient -ErrorAction Stop).Source

if ($PSCmdlet.ParameterSetName -eq 'LoginPath') {
    # A MySQL login-path obfuscates credentials in a local client file.
    $caPath = (Resolve-Path -LiteralPath $CaFile).Path
    $mysqlArgs = @("--login-path=$LoginPath", "--ssl-ca=$caPath")
}
else {
    # MySQL requires defaults-extra-file to appear before other options.
    $defaultsPath = (Resolve-Path -LiteralPath $DefaultsFile).Path
    $mysqlArgs = @("--defaults-extra-file=$defaultsPath")
}

$mysqlArgs += @(
    '--batch',
    '--raw',
    '--skip-column-names',
    '--connect-timeout=10',
    '--ssl-mode=VERIFY_IDENTITY'
)
if (-not [string]::IsNullOrWhiteSpace($ExpectedHost)) {
    $mysqlArgs += "--host=$ExpectedHost"
}

function Invoke-SafeMySql {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Sql,

        [switch]$ShouldFail,

        [string]$ExpectedError = ''
    )

    # Native stderr on Windows PowerShell 5.1 can surface as an error record.
    # We inspect the native process exit code rather than treating it as
    # a terminating PowerShell exception.
    $ErrorActionPreference = 'Continue'
    $lines = @(& $mysqlExe @mysqlArgs "--execute=$Sql" 2>&1)
    $exitCode = $LASTEXITCODE
    $result = ($lines | ForEach-Object { [string]$_ }) -join [Environment]::NewLine

    if ($ShouldFail) {
        if ($exitCode -eq 0) {
            throw 'A database operation that MUST be rejected unexpectedly succeeded.'
        }
        if ($ExpectedError -and $result.IndexOf(
                $ExpectedError, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "The operation failed for a different reason: $result"
        }
        return
    }

    if ($exitCode -ne 0) {
        throw "MySQL command failed (exit code $exitCode): $result"
    }
    return $result.Trim()
}

$version = Invoke-SafeMySql -Sql 'SELECT VERSION()'
if ($version -notmatch '^8\.') {
    throw "MySQL 8.x is required; the remote server reports '$version'."
}

$sslStatus = Invoke-SafeMySql -Sql "SHOW SESSION STATUS LIKE 'Ssl_cipher'"
if ($sslStatus -notmatch '(?i)^Ssl_cipher\s+(\S+)') {
    throw 'No active TLS cipher was reported by the remote MySQL session.'
}
$cipher = $Matches[1]
if ([string]::IsNullOrWhiteSpace($cipher) -or $cipher -in @('NULL', '0')) {
    throw 'MySQL TLS is not active. Refusing to continue.'
}

$binlog = Invoke-SafeMySql -Sql 'SELECT @@GLOBAL.log_bin, @@GLOBAL.log_bin_trust_function_creators'
Write-Host "MySQL version: $version"
Write-Host "Verified TLS cipher: $cipher"
Write-Host "Binary logging and trigger trust: $binlog"
Write-Host 'PASS: read-only server and TLS checks.'
Write-Host 'These metadata checks do not prove trigger privileges or application readiness.'

if (-not $RunWriteProbe) {
    Write-Host 'Optional scratch-only tests require explicit -RunWriteProbe.'
    return
}

$scratch = 'scs_probe_' + [guid]::NewGuid().ToString('N').Substring(0, 20)
$databaseCreated = $false

try {
    Invoke-SafeMySql -Sql "CREATE DATABASE $scratch CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" | Out-Null
    $databaseCreated = $true

    $createTable = @"
CREATE TABLE $scratch.probe (
    id INT PRIMARY KEY,
    state VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    CONSTRAINT scs_probe_state_check CHECK (state IN ('PAID', 'PENDING'))
)
"@
    Invoke-SafeMySql -Sql $createTable | Out-Null

    $createTrigger = @"
CREATE TRIGGER $scratch.scs_probe_reject
BEFORE INSERT ON $scratch.probe FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'SCS_TRIGGER_PROBE'
"@
    Invoke-SafeMySql -Sql $createTrigger | Out-Null

    Invoke-SafeMySql -Sql "INSERT INTO $scratch.probe VALUES (1, 'PAID')" -ShouldFail -ExpectedError 'SCS_TRIGGER_PROBE'
    Invoke-SafeMySql -Sql "DROP TRIGGER $scratch.scs_probe_reject" | Out-Null

    Invoke-SafeMySql -Sql "INSERT INTO $scratch.probe VALUES (1, 'PAID')" | Out-Null
    Invoke-SafeMySql -Sql "INSERT INTO $scratch.probe VALUES (2, 'paid')" -ShouldFail -ExpectedError 'scs_probe_state_check'

    $count = Invoke-SafeMySql -Sql "SELECT COUNT(*) FROM $scratch.probe"
    if ($count -ne '1') {
        throw "Unexpected scratch data count: $count"
    }

    Write-Host 'PASS: trigger creation, trigger execution and strict case-sensitive state constraint.'
    Write-Host 'Full Flyway and financial transaction parity remain separate release gates.'
}
finally {
    if ($databaseCreated) {
        try {
            Invoke-SafeMySql -Sql "DROP DATABASE $scratch" | Out-Null
            Write-Host "Disposable database $scratch deleted."
        }
        catch {
            throw "Cleanup failed for scratch database $scratch. Remove only this named database manually. $($_.Exception.Message)"
        }
    }
}
