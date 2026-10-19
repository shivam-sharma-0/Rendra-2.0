[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$codexRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$codexOwner = 'codepal-codex-outcomes-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$codexOwned = [IO.Path]::GetFullPath((Join-Path $codexRoot "target/codex-outcome-it/$codexOwner"))
if (-not $codexOwned.StartsWith($codexRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture escaped workspace' }
[void](New-Item -ItemType Directory -Path $codexOwned -Force)
$codexResults = @()
$codexCleanup = $false
$codexCases = @('working','routed-contact','external-contact-link','no-feedback','loading-only','hidden-feedback','missing-field-name','no-persistence','broken-update','broken-delete')
function Invoke-OutcomeWsl {
    $codexPreviousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $codexOutput = & wsl.exe @args 2>> "$codexOwned/native-stderr.log"
        $codexExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $codexPreviousPreference }
    if ($codexExit -ne 0) { throw "Outcome fixture command failed ($codexExit); see owned native-stderr.log" }
    return $codexOutput
}
try {
    $codexImage = Invoke-OutcomeWsl -e docker image inspect codepal-browser:efeb16a78e4c --format '{{.Id}}'
    if ($codexImage -notmatch '^sha256:[a-f0-9]{64}$') { throw 'Cached Chromium fixture unavailable' }
    $codexProbe = Invoke-OutcomeWsl -e wslpath -u (Join-Path $codexRoot 'src/main/resources/sandbox/browser/probe.mjs')
    $codexRelay = Invoke-OutcomeWsl -e wslpath -u (Join-Path $codexRoot 'src/main/resources/sandbox/browser/relay.mjs')
    $codexOutcomes = Invoke-OutcomeWsl -e wslpath -u (Join-Path $codexRoot 'src/main/resources/sandbox/browser/outcomes.mjs')
    $codexTemplate = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'fixtures/feature-outcomes.html'))
    $codexData = @{schema=1;actions=@();outcomes=@(
        @{id='contact';kind='FORM_FEEDBACK';field=$null}, @{id='tasks';kind='BROWSER_CRUD';field='title'}
    );outcomeBudgetMs=20000;outcomeStepTimeoutMs=2000} | ConvertTo-Json -Depth 5 -Compress
    $codexEncoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($codexData))
    foreach ($codexCase in $codexCases) {
        $codexCaseRoot = Join-Path $codexOwned $codexCase
        [void](New-Item -ItemType Directory -Path $codexCaseRoot -Force)
        [IO.File]::WriteAllText((Join-Path $codexCaseRoot 'index.html'),$codexTemplate.Replace('__DEFECT__',$codexCase),[Text.UTF8Encoding]::new($false))
        $codexLinuxCase = Invoke-OutcomeWsl -e wslpath -u $codexCaseRoot
        $codexOutput = Invoke-OutcomeWsl -e docker run --rm --name "$codexOwner-$codexCase" --label "com.codepal.codex.outcome-it=$codexOwner" --pull never --network none `
            --user 1000:1000 --cpus 1 --memory 512m --cap-drop ALL --security-opt no-new-privileges `
            --mount "type=bind,source=$codexLinuxCase,target=/site,readonly" `
            --mount "type=bind,source=$codexProbe,target=/opt/codepal/probe.mjs,readonly" `
            --mount "type=bind,source=$codexRelay,target=/opt/codepal/relay.mjs,readonly" `
            --mount "type=bind,source=$codexOutcomes,target=/opt/codepal/outcomes.mjs,readonly" `
            --entrypoint node $codexImage /opt/codepal/probe.mjs /site 200 $codexEncoded
        $codexLine = @($codexOutput | Where-Object { $_.StartsWith('{') })[-1]
        $codexReport = $codexLine | ConvertFrom-Json
        $codexReport | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $codexCaseRoot 'report.json') -Encoding UTF8
        if (-not $codexReport.loaded -or $codexReport.outcomes.Count -ne 2 -or @($codexReport.issues | Where-Object { $_.kind -ne 'visual' }).Count -ne 0) { throw "Unreadable/broken outcome fixture: $codexCase" }
        $codexExpectedContact = if ($codexCase -in @('no-feedback','loading-only','hidden-feedback','external-contact-link')) { 'FAIL' } else { 'PASS' }
        $codexExpectedTasks = if ($codexCase -in @('missing-field-name','no-persistence','broken-update','broken-delete')) { 'FAIL' } else { 'PASS' }
        if ($codexReport.outcomes[0].id -ne 'contact' -or $codexReport.outcomes[0].outcome -ne $codexExpectedContact `
            -or $codexReport.outcomes[1].id -ne 'tasks' -or $codexReport.outcomes[1].outcome -ne $codexExpectedTasks) { throw "Unexpected independent outcome: $codexCase" }
        if ($codexCase -eq 'missing-field-name' -and ($codexReport.outcomes[1].phase -ne 'prepare' -or $codexReport.outcomes[1].reason -ne 'FIELD_MISSING')) { throw 'Missing field did not retain its exact repair reason' }
        $codexResults += @{case=$codexCase;report=$codexReport}
    }
} finally {
    $codexContainers = @(Invoke-OutcomeWsl -e docker ps -aq --filter "label=com.codepal.codex.outcome-it=$codexOwner")
    foreach ($codexContainer in $codexContainers) {
        $codexLabels = Invoke-OutcomeWsl -e docker inspect --format '{{json .Config.Labels}}' $codexContainer | ConvertFrom-Json
        if ($codexLabels.'com.codepal.codex.outcome-it' -ne $codexOwner) { throw 'Outcome container ownership mismatch' }
        [void](Invoke-OutcomeWsl -e docker rm -f $codexContainer)
    }
    $codexRemaining = @(Invoke-OutcomeWsl -e docker ps -aq --filter "label=com.codepal.codex.outcome-it=$codexOwner")
    $codexCleanup = $codexRemaining.Count -eq 0
    [ordered]@{owner=$codexOwner;cases=$codexResults;complete=$codexResults.Count -eq $codexCases.Count;cleanupVerified=$codexCleanup} `
        | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$codexOwned/result.json" -Encoding UTF8
    if (-not $codexCleanup) { throw 'Outcome cleanup unverified' }
}
Write-Output "Independent outcome evidence: $codexOwned/result.json"
