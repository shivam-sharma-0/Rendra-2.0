[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$VerificationDirectory, [switch]$StructuredContracts,
    [switch]$IsolatedClasses, [switch]$GenerationImprovements, [ValidatePattern('^[a-f0-9]{12}$')][string]$CandidateId)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$commit = (& git -c core.safecrlf=false -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[a-f0-9]{40}$') { throw 'Cannot identify the benchmark source commit' }
$date = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'India Standard Time').ToString('yyyy-MM-dd')
$output = Join-Path ([IO.Path]::GetFullPath((Join-Path $root '../codex-archive/benchmarks'))) "$date-$($commit.Substring(0,12))$(if($StructuredContracts){'-structured'})$(if($CandidateId){'-candidate-'+$CandidateId}).json"
if (Test-Path -LiteralPath $output) { throw 'Benchmark result already exists; archive it before repeating this commit/date' }
$running = Get-CimInstance Win32_Process | Where-Object {
    ($_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -like '*com.code.pal.CodePalApplication*') -or
    ($_.Name -eq 'node.exe' -and $_.CommandLine -match 'vite[\\/]|vite\.js|[\\/]vite\s')
}
if ($running -and -not $IsolatedClasses) { throw 'Stop development processes or select separately verified IsolatedClasses before the benchmark' }
$classes = if ($IsolatedClasses) { Join-Path ([IO.Path]::GetFullPath($VerificationDirectory)) 'classes' } else { Join-Path $root 'target/classes' }
if (-not (Test-Path -LiteralPath (Join-Path $classes 'com/code/pal/CodePalApplication.class'))) {
    throw 'Compile the intended source into the selected class directory first; this harness never builds the platform'
}
& (Join-Path $PSScriptRoot 'Run-RealDevE2E.ps1') -AgentBenchmark -VerificationDirectory $VerificationDirectory `
    -BenchmarkResultPath $output -BenchmarkCommit $commit -StructuredContracts:$StructuredContracts `
    -IsolatedClasses:$IsolatedClasses -GenerationImprovements:$GenerationImprovements
Write-Output "Benchmark artifact: $output"
