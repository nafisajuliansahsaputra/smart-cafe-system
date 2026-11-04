[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$DefaultsFile,

    [string]$MySqlClient = "mysql",

    [switch]$RunWriteProbe
)

# A disposable MySQL provider preflight. Never pass credentials as CLI args.
# Read-only by default; -RunWriteProbe creates and drops ONLY a random scs_probe_* database.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$mysqlExe = (Get-Command $MySqlClient -ErrorAction Stop).Source
$defaultsPath = (Resolve-Path -LiteralPath $DefaultsFile).Path
$mysqlArgs = @(
    "--defaults-extra-file=$defaultsPath",
    "--batch",
    "--raw",
    "--skip-column-names",
    "--connect-timeout=10"
)

function Invoke-SafeMySql {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [switch]$ShouldFail,
        [string]$ExpectedError = ""
    )

    $output = @(& $mysqlExe @mysqlArgs "--execute=$Sql" 2>&1)
    $exitCode = $LASTEXITCODE
    $result = ($output | ForEach-Object { [string]$_ }) -join "`n"

    if ($ShouldFail) {
        if ($exitCode -eq 0) {
            throw "A prohibited test statement unexpectedly succeeded. Database invariants NOT verified."
        }
        if ($ExpectedError -and $result.IndexOf(
                $ExpectedError, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "Statement failed for a different reason, not the expected invariant: $result"
        }
        return
    }

    if ($exitCode -ne 0) {
        throw "MySQL preflight statement failed (exit code $exitCode): $result"
    }
    return $result.Trim()
}

$version = Invoke-SafeMySql -Sql "SELECT VERSION()"
if ($version -notmatch "^8\.") {
    throw "Expected genuine MySQL 8.x, found '$version'. This does not certify MariaDB or TiDB."
}

$sslStatus = Invoke-SafeMySql -Sql "SHOW SESSION STATUS LIKE 'Ssl_cipher'"
if ($sslStatus -notmatch "(?i)^Ssl_cipher\s+(\S+)") {
    throw "MySQL connection has no verified active TLS cipher. Refusing further probes."
}
$cipher = $Matches[1]
if ($cipher -eq "NULL" -or $cipher -eq "0") {
    throw "MySQL TLS is not active."
}

$binlog = Invoke-SafeMySql -Sql "SELECT @@GLOBAL.log_bin, @@GLOBAL.log_bin_trust_function_creators"
Write-Host "MySQL version: $version"
Write-Host "TLS cipher: $cipher"
Write-Host "Binary logging / trigger-trust settings: $binlog"
Write-Host "Read-only checks passed. These do NOT prove DDL trigger permissions."

if (-not $RunWriteProbe) {
    Write-Host "To verify CREATE TRIGGER and financial CHECK enforcement, opt in with -RunWriteProbe."
    return
}

# Exactly one isolated, randomly named scratch schema; never touch existing cafe data.
$scratch = "scs_probe_" + [guid]::NewGuid().ToString("N").Substring(0, 20)
$databaseCreated = $false

try {
    Invoke-SafeMySql -Sql "CREATE DATABASE $scratch CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" | Out-Null
    $databaseCreated = $true

    Invoke-SafeMySql -Sql @"
CREATE TABLE $scratch.probe (
    id INT PRIMARY KEY,
    state VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    CONSTRAINT scs_probe_state_check CHECK (state IN ('PAID', 'PENDING'))
)
"@ | Out-Null

    # Single-statement trigger avoids CLI DELIMITER semantics and proves trigger creation.
    Invoke-SafeMySql -Sql @"
CREATE TRIGGER $scratch.scs_probe_reject
BEFORE INSERT ON $scratch.probe FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'SCS_TRIGGER_PROBE'
"@ | Out-Null

    Invoke-SafeMySql -Sql "INSERT INTO $scratch.probe VALUES (1, 'PAID')" `
        -ShouldFail -ExpectedError "SCS_TRIGGER_PROBE"
    Invoke-SafeMySql -Sql "DROP TRIGGER $scratch.scs_probe_reject" | Out-Null

    Invoke-SafeMySql -Sql "INSERT INTO $scratch.probe VALUES (1, 'PAID')" | Out-Null
    Invoke-SafeMySql -Sql "INSERT INTO $scratch.probe VALUES (2, 'paid')" `
        -ShouldFail -ExpectedError "scs_probe_state_check"
    $count = Invoke-SafeMySql -Sql "SELECT COUNT(*) FROM $scratch.probe"
    if ($count -ne "1") {
        throw "Expected exactly one valid row; actual count: $count"
    }

    Write-Host "PASS: trigger privilege, trigger execution and binary/case-sensitive CHECK enforcement."
    Write-Host "This is only a host preflight; full Flyway V1-V31 and business parity are still required."
}
finally {
    if ($databaseCreated) {
        try {
            Invoke-SafeMySql -Sql "DROP DATABASE $scratch" | Out-Null
            Write-Host "Disposable schema $scratch removed."
        }
        catch {
            throw "IMPORTANT: Cleanup of disposable schema $scratch failed. Remove ONLY that schema manually. $($_.Exception.Message)"
        }
    }
}
