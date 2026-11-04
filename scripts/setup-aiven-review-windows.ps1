[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9.-]+\.aivencloud\.com$')]
    [string]$HostName,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int]$Port,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$CaFile,

    [switch]$RunWriteProbe
)

# Interactive local setup only. Does not persist passwords in the repository,
# touch an existing cafe database or deploy a Spring Boot application.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$mysqlClient = Get-Command "mysql" -ErrorAction SilentlyContinue
$editor = Get-Command "mysql_config_editor" -ErrorAction SilentlyContinue
if (-not $mysqlClient -or -not $editor) {
    throw "MySQL 8 client tools (mysql and mysql_config_editor) are required in PATH."
}

$caPath = (Resolve-Path -LiteralPath $CaFile).Path
$preflight = Join-Path -Path $PSScriptRoot -ChildPath "test-mysql-host-preflight.ps1"
if (-not (Test-Path -LiteralPath $preflight -PathType Leaf)) {
    throw "Cannot find the audited MySQL host preflight script in $PSScriptRoot."
}
# Verify the child script parses before prompting for a password. A previous
# malformed preflight file failed only after credentials had been entered.
$parseTokens = $null
$parseErrors = $null
[void][System.Management.Automation.Language.Parser]::ParseFile(
    $preflight, [ref]$parseTokens, [ref]$parseErrors
)
if ($parseErrors.Count -gt 0) {
    $messages = @($parseErrors | ForEach-Object { $_.Message }) -join '; '
    throw "Preflight script contains PowerShell syntax errors. Update the repository before entering credentials: $messages"
}


# Credentials are prompted directly by the official MySQL client editor.
# Password must NEVER be supplied as an argument, environment value, file in
# the Git checkout or GitHub Actions variable.
$loginPath = "smartcafe_review"
Write-Host "Target: $HostName (port $Port) / login path: $loginPath"
Write-Host "Confirm this is your isolated Aiven MySQL FREE preview service."
Write-Host "MySQL will prompt for the Aiven password locally. It will not be printed."
& $editor.Source set "--login-path=$loginPath" "--host=$HostName" "--port=$Port" "--user=avnadmin" "--password"
if ($LASTEXITCODE -ne 0) {
    throw "MySQL login-path setup failed. Nothing was deployed or migrated."
}

$arguments = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $preflight,
    "-LoginPath", $loginPath,
    "-CaFile", $caPath,
    "-ExpectedHost", $HostName
)
if ($RunWriteProbe) {
    $confirmation = Read-Host "Type SCRATCH-ONLY to authorize temporary CREATE/DROP DATABASE on this Aiven host"
    if ($confirmation -cne "SCRATCH-ONLY") {
        throw "Remote scratch-schema test cancelled; no database was written."
    }
    $arguments += "-RunWriteProbe"
}

# Use a child PowerShell process so failures propagate as a proper exit status.
& powershell.exe @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Aiven host preflight failed. See non-secret diagnostics above; do not deploy."
}
Write-Host "Host preflight completed. This is NOT full Spring Boot/MySQL readiness."
