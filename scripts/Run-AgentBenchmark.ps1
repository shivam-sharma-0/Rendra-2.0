[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$VerificationDirectory)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$commit = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[a-f0-9]{40}$') { throw 'Cannot identify the benchmark source commit' }
$date = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'India Standard Time').ToString('yyyy-MM-dd')
$output = Join-Path ([IO.Path]::GetFullPath((Join-Path $root '../codex-archive/benchmarks'))) "$date-$($commit.Substring(0,12)).json"
if (Test-Path -LiteralPath $output) { throw 'Benchmark result already exists; archive it before repeating this commit/date' }
$running = Get-CimInstance Win32_Process | Where-Object {
    ($_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -like '*com.code.pal.CodePalApplication*') -or
    ($_.Name -eq 'node.exe' -and $_.CommandLine -match 'vite[\\/]|vite\.js|[\\/]vite\s')
}
if ($running) { throw 'Stop the development backend and Vite before the benchmark' }
if (-not (Test-Path -LiteralPath (Join-Path $root 'target/classes/com/code/pal/CodePalApplication.class'))) {
    throw 'Claude must compile the intended source into target/classes first; this harness never builds the platform'
}
& (Join-Path $PSScriptRoot 'Run-RealDevE2E.ps1') -AgentBenchmark -VerificationDirectory $VerificationDirectory `
    -BenchmarkResultPath $output -BenchmarkCommit $commit
Write-Output "Benchmark artifact: $output"
