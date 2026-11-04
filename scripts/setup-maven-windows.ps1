[CmdletBinding()]
param()

# Install the official Apache Maven ZIP without admin rights or machine PATH changes.
# Dot-source from the SAME PowerShell window so Maven becomes available there:
#   . .\scripts\setup-maven-windows.ps1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$version = '3.9.16'
$expectedSha512 = 'ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3'
$toolsRoot = Join-Path $env:LOCALAPPDATA 'SmartCafe\tools'
$archive = Join-Path $toolsRoot "apache-maven-$version-bin.zip"
$bin = Join-Path $toolsRoot "apache-maven-$version\bin"
$mvnCmd = Join-Path $bin 'mvn.cmd'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java JDK is not in PATH. Verify java -version first.'
}

New-Item -Path $toolsRoot -ItemType Directory -Force | Out-Null

if (-not (Test-Path -LiteralPath $mvnCmd -PathType Leaf)) {
    $url = "https://downloads.apache.org/maven/maven-3/$version/binaries/apache-maven-$version-bin.zip"
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
        Write-Host "Downloading official Apache Maven $version from Apache ..."
        Invoke-WebRequest -Uri $url -OutFile $archive -UseBasicParsing
    }

    $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actual -cne $expectedSha512) {
        Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
        throw 'Maven SHA-512 mismatch. The archive was rejected and deleted.'
    }

    Write-Host 'PASS: Apache-published SHA-512 checksum matches.'
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsRoot -Force
}

if (-not (Test-Path -LiteralPath $mvnCmd -PathType Leaf)) {
    throw "Maven was not found after extraction: $mvnCmd"
}

if (($env:Path -split ';') -notcontains $bin) {
    $env:Path = "$bin;$env:Path"
}
Write-Host "Maven activated for this PowerShell session: $mvnCmd"
& $mvnCmd -version
if ($LASTEXITCODE -ne 0) {
    throw 'Maven executable did not start. Confirm JDK and JAVA_HOME configuration.'
}
