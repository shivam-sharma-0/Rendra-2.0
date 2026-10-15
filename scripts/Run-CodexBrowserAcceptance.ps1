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
    $codexActions=@(
        @{id='first';kind='FILL';selector='#first';value='2'},
        @{id='second';kind='FILL';selector='#second';value='3'},
        @{id='calculate';kind='CLICK';selector='#calculate';value=$null},
        @{id='result';kind='ASSERT_TEXT';selector='#result';value='5'}
    )
    foreach($codexCase in @('working','broken-result','missing-control','wrong-route')) {
        $codexCaseRoot=Join-Path $codexOwned $codexCase;[void](New-Item -ItemType Directory -Path $codexCaseRoot -Force)
        $codexOperation=if($codexCase -eq 'broken-result'){'-'}else{'+'}
        $codexButton=if($codexCase -eq 'missing-control'){'other'}else{'calculate'}
        $codexHtml="<!doctype html><h1>Calculator</h1><input id='first'><input id='second'><button id='$codexButton'>Calculate</button><output id='result'>0</output><script>document.querySelector('button').onclick=()=>{document.querySelector('#result').textContent=String(Number(document.querySelector('#first').value)$codexOperation Number(document.querySelector('#second').value))}</script>"
        $codexHtml | Set-Content -LiteralPath (Join-Path $codexCaseRoot 'index.html') -Encoding ASCII
        $codexSelectedActions=if($codexCase -eq 'wrong-route'){@(@{id='route';kind='ASSERT_ROUTE';selector=$null;value='/missing'})}else{$codexActions}
        $codexJson=@{schema=1;actions=@($codexSelectedActions)} | ConvertTo-Json -Depth 6 -Compress
        $codexEncoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($codexJson))
        $codexLinuxCase=& wsl.exe -e wslpath -u $codexCaseRoot
        $codexName=$codexProject+'-'+$codexCase
        $codexOutput=& wsl.exe -e docker run --rm --name $codexName --label "com.codepal.codex.browser-it=$codexProject" --pull never --network none --user 1000:1000 --cpus 1 --memory 512m --cap-drop ALL --security-opt no-new-privileges `
            --mount "type=bind,source=$codexLinuxCase,target=/site,readonly" --mount "type=bind,source=$codexLinuxProbe,target=/opt/codepal/probe.mjs,readonly" --entrypoint node $codexImage /opt/codepal/probe.mjs /site 200 $codexEncoded
        if($LASTEXITCODE -ne 0){throw "Browser fixture failed: $codexCase"}
        $codexLine=@($codexOutput | Where-Object { $_.StartsWith('{') })[-1]
        $codexReport=$codexLine | ConvertFrom-Json
        if(-not $codexReport.loaded -or $codexReport.acceptance.Count -ne @($codexSelectedActions).Count){throw 'Browser acceptance coverage missing'}
        $codexPassed=@($codexReport.acceptance | Where-Object { -not $_.passed }).Count -eq 0
        if($codexPassed -ne ($codexCase -eq 'working')){throw "Wrong acceptance outcome: $codexCase"}
        $codexResults+=@{case=$codexCase;expectedPass=($codexCase -eq 'working');actualPass=$codexPassed;steps=@($codexReport.acceptance)}
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
