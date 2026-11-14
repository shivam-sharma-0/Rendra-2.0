param(
    [string]$VerificationDirectory = 'target/agent-generation-gate-20261019',
    [string]$Fixture = 'scripts/fixtures/benchmark.json',
    [string]$OutputDirectory = '',
    [string[]]$Only = @()
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$OutputDirectory) { $OutputDirectory = 'target/benchmark-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
$out = [IO.Path]::GetFullPath((Join-Path $repo $OutputDirectory))
New-Item -ItemType Directory -Force $out | Out-Null
$evidenceRoot = Join-Path $repo 'target/real-e2e'
$cases = (Get-Content -LiteralPath (Join-Path $repo $Fixture) -Raw | ConvertFrom-Json).cases
$summary = @()
foreach ($case in $cases) {
    if ($Only.Count -gt 0 -and $Only -notcontains $case.name) { continue }
    $promptFile = Join-Path $out ($case.name + '-prompt.json')
    @{ prompt = $case.prompt } | ConvertTo-Json | Set-Content -LiteralPath $promptFile -Encoding UTF8
    $before = @(Get-ChildItem -LiteralPath $evidenceRoot -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
    $failure = $null
    try {
        & (Join-Path $PSScriptRoot 'Run-RealDevE2E.ps1') -Scenario mern -IsolatedClasses -CodexAll `
            -VerificationDirectory (Join-Path $repo $VerificationDirectory) -PromptFile $promptFile -BenchmarkName $case.name `
            *> (Join-Path $out ($case.name + '.log'))
    } catch { $failure = $_.Exception.Message }
    $evidence = Get-ChildItem -LiteralPath $evidenceRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $before -notcontains $_.Name } | Sort-Object LastWriteTime | Select-Object -Last 1
    $row = [ordered]@{ name = $case.name; evidence = $evidence.FullName; failure = $failure }
    if ($evidence) {
        $build = Join-Path $evidence.FullName 'build.json'
        if (Test-Path -LiteralPath $build) {
            $result = Get-Content -LiteralPath $build -Raw | ConvertFrom-Json
            $row.status = $result.status; $row.preview = $result.previewStatus; $row.seconds = $result.seconds; $row.error = $result.error
        }
        $appLog = Join-Path $evidence.FullName 'app.log'
        if (Test-Path -LiteralPath $appLog) {
            $calls = @(Select-String -LiteralPath $appLog -Pattern 'codex_call_finished .*elapsedMs=(\d+) inputTokens=(\d+) cachedTokens=(\d+) outputTokens=(\d+)')
            $row.calls = $calls.Count
            $row.modelSeconds = [math]::Round(($calls | ForEach-Object { [long]$_.Matches[0].Groups[1].Value } | Measure-Object -Sum).Sum / 1000)
            $row.inputTokens = ($calls | ForEach-Object { [long]$_.Matches[0].Groups[2].Value } | Measure-Object -Sum).Sum
            $row.cachedTokens = ($calls | ForEach-Object { [long]$_.Matches[0].Groups[3].Value } | Measure-Object -Sum).Sum
            $row.outputTokens = ($calls | ForEach-Object { [long]$_.Matches[0].Groups[4].Value } | Measure-Object -Sum).Sum
            $row.failedCalls = @(Select-String -LiteralPath $appLog -Pattern 'codex_call_finished .*exit=[1-9]').Count
        }
        foreach ($shot in @('desktop.png', 'mobile.png')) {
            $source = Join-Path $evidence.FullName $shot
            if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $out ($case.name + '-' + $shot)) }
        }
    }
    $row.passed = $row.status -eq 'DONE' -and $row.preview -eq 'READY'
    $summary += [pscustomobject]$row
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $out 'summary.json') -Encoding UTF8
    Write-Output ("BENCHMARK {0} passed={1} status={2} seconds={3} calls={4} input={5} cached={6} output={7}" -f
        $case.name, $row.passed, $row.status, $row.seconds, $row.calls, $row.inputTokens, $row.cachedTokens, $row.outputTokens)
}
