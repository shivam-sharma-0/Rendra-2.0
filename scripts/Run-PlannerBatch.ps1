param(
    [string]$VerificationDirectory = 'target/agent-generation-gate-20261019',
    [string]$Fixture = 'scripts/fixtures/planner-batch.json',
    [string]$OutputDirectory = ('target/planner-batch-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $repoRoot
try {
    $verification = [IO.Path]::GetFullPath($VerificationDirectory)
    $report = Join-Path $verification 'surefire-reports/TEST-com.code.pal.agent.usage.MeteredLlmClientTest.xml'
    [xml]$xml = Get-Content -LiteralPath $report -Raw
    $classpath = ($xml.testsuite.properties.property | Where-Object {$_.name -eq 'java.class.path'}).value
    $javaHome = ($xml.testsuite.properties.property | Where-Object {$_.name -eq 'java.home'}).value
    $mainClass = Join-Path $verification 'test-classes/com/code/pal/agent/contract/PlannerBatchHarness.class'
    if (!(Test-Path -LiteralPath $mainClass)) { throw 'Compile current test sources with the offline *Test gate first' }
    $source = Join-Path $repoRoot 'src/test/java/com/code/pal/agent/contract/PlannerBatchHarness.java'
    if ((Get-Item -LiteralPath $mainClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $source).LastWriteTimeUtc) {
        throw 'Planner batch classes are stale; rerun the offline gate'
    }
    # Foreground CLI only; no application boot, containers, database, generation or preview commands.
    & (Join-Path $javaHome 'bin/java.exe') '-Xmx256m' '-XX:MaxMetaspaceSize=192m' '-XX:ActiveProcessorCount=2' `
        '-cp' $classpath 'com.code.pal.agent.contract.PlannerBatchHarness' $Fixture $OutputDirectory
    if ($LASTEXITCODE -ne 0) { throw "Planner batch exited with code $LASTEXITCODE" }
} finally { Pop-Location }
