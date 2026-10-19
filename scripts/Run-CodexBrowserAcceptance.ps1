[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$codexRoot=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$codexProject='codepal-codex-browser-it-'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$codexOwned=[System.IO.Path]::GetFullPath((Join-Path $codexRoot "target/codex-browser-it/$codexProject"))
if(-not $codexOwned.StartsWith($codexRoot+[System.IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Fixture escaped worktree'}
$codexResults=@();$codexCleanup=$false
try {
    [void](New-Item -ItemType Directory -Path $codexOwned -Force)
    $codexImage=& wsl.exe -e docker image inspect codepal-browser:efeb16a78e4c --format '{{.Id}}'
    if($LASTEXITCODE -ne 0 -or $codexImage -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Cached Chromium fixture image unavailable'}
    $codexProbe=Join-Path $codexRoot 'src/main/resources/sandbox/browser/probe.mjs'
    $codexLinuxProbe=& wsl.exe -e wslpath -u $codexProbe
    $codexLinuxRelay=& wsl.exe -e wslpath -u (Join-Path $codexRoot 'src/main/resources/sandbox/browser/relay.mjs')
    $codexLinuxOutcomes=& wsl.exe -e wslpath -u (Join-Path $codexRoot 'src/main/resources/sandbox/browser/outcomes.mjs')
    $codexActions=@(
        @{id='first';kind='FILL';selector='#first';value='2'},
        @{id='second';kind='FILL';selector='#second';value='3'},
        @{id='calculate';kind='CLICK';selector='#calculate';value=$null},
        @{id='result';kind='ASSERT_TEXT';selector='#result';value='5'}
    )
    foreach($codexCase in @('working','broken-result','missing-control','wrong-route','unlabelled-controls')) {
        $codexCaseRoot=Join-Path $codexOwned $codexCase;[void](New-Item -ItemType Directory -Path $codexCaseRoot -Force)
        $codexEvidenceRoot=Join-Path $codexCaseRoot 'evidence';[void](New-Item -ItemType Directory -Path $codexEvidenceRoot -Force)
        Copy-Item -LiteralPath (Join-Path $codexRoot 'src/main/resources/starters/design-system.css') -Destination (Join-Path $codexCaseRoot 'foundation.css')
        $codexOperation=if($codexCase -eq 'broken-result'){'-'}else{'+'}
        $codexButton=if($codexCase -eq 'missing-control'){'other'}else{'calculate'}
        $codexLabels=if($codexCase -eq 'unlabelled-controls'){"<input id='first'><input id='second'>"}else{"<label class='app-field'>First number<input id='first' inputmode='decimal'></label><label class='app-field'>Second number<input id='second' inputmode='decimal'></label>"}
        $codexHtml="<!doctype html><html><head><title>Calculator</title><meta name='viewport' content='width=device-width,initial-scale=1'><link rel='stylesheet' href='foundation.css'></head><body><main class='app-container app-section'><section class='app-card app-stack'><h1>Calculator</h1><p class='app-muted'>Add two numbers and see the result.</p>$codexLabels<button class='app-button' id='$codexButton'>Calculate</button><output id='result' aria-live='polite'>0</output></section></main><script>document.querySelector('button').onclick=()=>{document.querySelector('#result').textContent=String(Number(document.querySelector('#first').value)$codexOperation Number(document.querySelector('#second').value))}</script></body></html>"
        $codexHtml | Set-Content -LiteralPath (Join-Path $codexCaseRoot 'index.html') -Encoding ASCII
        $codexSelectedActions=if($codexCase -eq 'wrong-route'){@(@{id='route';kind='ASSERT_ROUTE';selector=$null;value='/missing'})}else{$codexActions}
        $codexJson=@{schema=1;actions=@($codexSelectedActions)} | ConvertTo-Json -Depth 6 -Compress
        $codexEncoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($codexJson))
        $codexLinuxCase=& wsl.exe -e wslpath -u $codexCaseRoot
        $codexLinuxEvidence=& wsl.exe -e wslpath -u $codexEvidenceRoot
        $codexName=$codexProject+'-'+$codexCase
        $codexOutput=& wsl.exe -e docker run --rm --name $codexName --label "com.codepal.codex.browser-it=$codexProject" --pull never --network none --user 1000:1000 --cpus 1 --memory 512m --cap-drop ALL --security-opt no-new-privileges `
            --mount "type=bind,source=$codexLinuxCase,target=/site,readonly" --mount "type=bind,source=$codexLinuxEvidence,target=/evidence" -e CODEPAL_BROWSER_EVIDENCE_DIR=/evidence --mount "type=bind,source=$codexLinuxProbe,target=/opt/codepal/probe.mjs,readonly" --mount "type=bind,source=$codexLinuxRelay,target=/opt/codepal/relay.mjs,readonly" --mount "type=bind,source=$codexLinuxOutcomes,target=/opt/codepal/outcomes.mjs,readonly" --entrypoint node $codexImage /opt/codepal/probe.mjs /site 200 $codexEncoded
        if($LASTEXITCODE -ne 0){throw "Browser fixture failed: $codexCase"}
        $codexLine=@($codexOutput | Where-Object { $_.StartsWith('{') })[-1]
        $codexReport=$codexLine | ConvertFrom-Json
        if(-not $codexReport.loaded -or $codexReport.acceptance.Count -ne @($codexSelectedActions).Count){throw 'Browser acceptance coverage missing'}
        $codexPassed=@($codexReport.acceptance | Where-Object { -not $_.passed }).Count -eq 0
        $codexExpectedPass=$codexCase -in @('working','unlabelled-controls')
        if($codexPassed -ne $codexExpectedPass){throw "Wrong acceptance outcome: $codexCase"}
        if($codexReport.visual.captures.Count -ne 2){throw 'Desktop/mobile visual evidence missing'}
        foreach($codexCapture in $codexReport.visual.captures) {
            if($codexCapture.name -notin @('desktop','mobile')){throw 'Unexpected capture name'}
            $codexCapturePath=Join-Path $codexEvidenceRoot ($codexCapture.name+'.png')
            if((Get-FileHash -LiteralPath $codexCapturePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $codexCapture.sha256){throw 'Capture hash mismatch'}
        }
        if($codexCase -eq 'working' -and $codexReport.visual.warnings.Count -ne 0){throw 'Labelled fixture falsely warned'}
        if($codexCase -eq 'unlabelled-controls' -and $codexReport.visual.warnings.Count -eq 0){throw 'Missing labels went undetected'}
        $codexResults+=@{case=$codexCase;expectedPass=$codexExpectedPass;actualPass=$codexPassed;steps=@($codexReport.acceptance);visual=$codexReport.visual}
    }
} finally {
    $codexContainers=@(& wsl.exe -e docker ps -aq --filter "label=com.codepal.codex.browser-it=$codexProject")
    if($LASTEXITCODE -ne 0){throw 'Could not inspect owned browser containers'}
    foreach($codexContainer in $codexContainers) {
        $codexLabels=& wsl.exe -e docker inspect --format '{{json .Config.Labels}}' $codexContainer
        if(($codexLabels | ConvertFrom-Json).'com.codepal.codex.browser-it' -ne $codexProject){throw 'Browser ownership mismatch'}
        & wsl.exe -e docker rm -f $codexContainer
        if($LASTEXITCODE -ne 0){throw 'Browser cleanup failed'}
    }
    $codexRemaining=@(& wsl.exe -e docker ps -aq --filter "label=com.codepal.codex.browser-it=$codexProject")
    $codexCleanup=$LASTEXITCODE -eq 0 -and $codexRemaining.Count -eq 0
    [ordered]@{project=$codexProject;image=$codexImage;probeHash=(Get-FileHash -LiteralPath $codexProbe -Algorithm SHA256).Hash;cases=$codexResults;cleanupVerified=$codexCleanup;completedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$codexOwned/result.json" -Encoding UTF8
    if(-not $codexCleanup){throw 'Browser cleanup unverified'}
}
Write-Output "Browser evidence: $codexOwned/result.json"
