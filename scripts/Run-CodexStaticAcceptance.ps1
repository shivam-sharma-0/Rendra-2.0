[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$codexRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$codexProject = 'codepal-codex-static-it-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$codexOwned = [IO.Path]::GetFullPath((Join-Path $codexRoot "target/codex-static-it/$codexProject"))
if (-not $codexOwned.StartsWith($codexRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture escaped workspace' }
$codexInputs = Join-Path $codexRoot 'target/codex-static-inputs'
if (-not (Test-Path -LiteralPath "$codexInputs/starter-static.json")) { throw 'Export production inputs with StaticAssetBuildIT#exportsProductionInputsForTheLinuxCompilerGate first' }
[void](New-Item -ItemType Directory -Path "$codexOwned/inputs","$codexOwned/evidence" -Force)
Copy-Item -Path "$codexInputs/*" -Destination "$codexOwned/inputs" -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'fixtures/static-starter.html') -Destination "$codexOwned/inputs/fixture.html"
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Test-StaticStarter.cjs') -Destination "$codexOwned/inputs/check.cjs"
$codexEvidence = [ordered]@{project=$codexProject;passes=@();cleanupVerified=$false;complete=$false}
function Invoke-StaticWsl {
    $codexPreviousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $codexOutput = & wsl.exe @args 2>> "$codexOwned/native-stderr.log"
        $codexExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $codexPreviousPreference }
    if ($codexExit -ne 0) { throw "Static fixture command failed ($codexExit); see $codexOwned/native-stderr.log" }
    return $codexOutput
}
try {
    $codexMount = Invoke-StaticWsl -e wslpath -u $codexOwned
    $codexImage = (Get-Content -LiteralPath (Join-Path $codexRoot 'src/main/resources/starters/runtime.json') -Raw | ConvertFrom-Json).image
    $codexBrowser = Invoke-StaticWsl -e docker image inspect codepal-browser:efeb16a78e4c --format '{{.Id}}'
    $codexProbe = Invoke-StaticWsl -e wslpath -u (Join-Path $codexRoot 'src/main/resources/sandbox/browser/probe.mjs')
    $codexRelay = Invoke-StaticWsl -e wslpath -u (Join-Path $codexRoot 'src/main/resources/sandbox/browser/relay.mjs')
    $codexOutcomes = Invoke-StaticWsl -e wslpath -u (Join-Path $codexRoot 'src/main/resources/sandbox/browser/outcomes.mjs')
    [void](Invoke-StaticWsl -e docker volume create --label "com.codepal.codex.static-it=$codexProject" $codexProject)
    foreach ($codexPass in @('cold','warm')) {
        $codexNetwork = if ($codexPass -eq 'cold') { 'bridge' } else { 'none' }
        $codexArguments = @('-e','docker','run','--name',$codexProject,'--label',"com.codepal.codex.static-it=$codexProject",'--pull','never',
            '--network',$codexNetwork,'--cpus','2','--memory','1024m','--cap-drop','ALL','--security-opt','no-new-privileges',
            '--mount',"type=bind,source=$codexMount/inputs,target=/inputs,readonly",'--mount',"type=volume,source=$codexProject,target=/checks",
            $codexImage,'node','/inputs/check.cjs')
        if ($codexPass -eq 'warm') { $codexArguments += '--offline' }
        Invoke-StaticWsl @codexArguments
        [void](Invoke-StaticWsl -e docker cp "${codexProject}:/checks/result.json" "$codexMount/$codexPass.json")
        $codexEvidence.passes += Get-Content -LiteralPath "$codexOwned/$codexPass.json" -Raw | ConvertFrom-Json
        if ($codexPass -eq 'warm') { [void](Invoke-StaticWsl -e docker cp "${codexProject}:/checks/site/dist" "$codexMount/dist") }
        [void](Invoke-StaticWsl -e docker rm $codexProject)
    }
    $codexActions = @(
        @{id='name';kind='FILL';selector='#name';value='Fixture user'},
        @{id='save';kind='CLICK';selector='button[type=submit]'},
        @{id='confirm';kind='ASSERT_TEXT';selector='#status';value='Appointment saved'},
        @{id='record';kind='ASSERT_TEXT';selector='#appointments';value='Fixture user'},
        @{id='reload';kind='RELOAD'},
        @{id='persist';kind='ASSERT_TEXT';selector='#appointments';value='Fixture user'}
    )
    $codexEncoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((@{schema=1;actions=$codexActions} | ConvertTo-Json -Depth 5 -Compress)))
    $codexReport = Invoke-StaticWsl -e docker run --rm --name $codexProject --label "com.codepal.codex.static-it=$codexProject" --pull never --network none `
        --user 1000:1000 --cpus 1 --memory 512m --cap-drop ALL --security-opt no-new-privileges `
        --mount "type=bind,source=$codexMount/dist,target=/site,readonly" --mount "type=bind,source=$codexMount/evidence,target=/evidence" `
        --mount "type=bind,source=$codexProbe,target=/opt/codepal/probe.mjs,readonly" -e CODEPAL_BROWSER_EVIDENCE_DIR=/evidence `
        --mount "type=bind,source=$codexRelay,target=/opt/codepal/relay.mjs,readonly" `
        --mount "type=bind,source=$codexOutcomes,target=/opt/codepal/outcomes.mjs,readonly" `
        --entrypoint node $codexBrowser /opt/codepal/probe.mjs /site 200 $codexEncoded
    $codexBrowserResult = @($codexReport | Where-Object { $_.StartsWith('{') })[-1] | ConvertFrom-Json
    if (-not $codexBrowserResult.loaded -or $codexBrowserResult.issues.Count -ne 0 -or $codexBrowserResult.acceptance.Count -ne 6 `
        -or @($codexBrowserResult.acceptance | Where-Object { -not $_.passed }).Count -ne 0) { throw 'Static browser/persistence acceptance failed' }
    if ($codexBrowserResult.visual.captures.Count -ne 2) { throw 'Desktop/mobile captures missing' }
    $codexEvidence.browser = $codexBrowserResult
    $codexEvidence.complete = $true
} finally {
    $codexContainers = @(Invoke-StaticWsl -e docker ps -aq --filter "label=com.codepal.codex.static-it=$codexProject")
    foreach ($codexContainer in $codexContainers) {
        $codexLabels = Invoke-StaticWsl -e docker inspect --format '{{json .Config.Labels}}' $codexContainer | ConvertFrom-Json
        if ($codexLabels.'com.codepal.codex.static-it' -ne $codexProject) { throw 'Container ownership mismatch' }
        [void](Invoke-StaticWsl -e docker rm -f $codexContainer)
    }
    $codexVolumes = @(Invoke-StaticWsl -e docker volume ls -q --filter "label=com.codepal.codex.static-it=$codexProject")
    foreach ($codexVolume in $codexVolumes) {
        if ($codexVolume -ne $codexProject) { throw 'Volume ownership mismatch' }
        [void](Invoke-StaticWsl -e docker volume rm $codexVolume)
    }
    $codexRemaining = @(Invoke-StaticWsl -e docker ps -aq --filter "label=com.codepal.codex.static-it=$codexProject")
    $codexVolumes = @(Invoke-StaticWsl -e docker volume ls -q --filter "label=com.codepal.codex.static-it=$codexProject")
    $codexEvidence.cleanupVerified = $codexRemaining.Count -eq 0 -and $codexVolumes.Count -eq 0
    $codexEvidence | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath "$codexOwned/result.json" -Encoding UTF8
    if (-not $codexEvidence.cleanupVerified) { throw 'Fixture cleanup unverified' }
}
Write-Output "Static evidence: $codexOwned/result.json"
