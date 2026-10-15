[CmdletBinding()]
param([switch]$GenerateLocks, [switch]$ContractScaffold, [switch]$ContractBehavior)
$ErrorActionPreference='Stop'
$codexRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$codexProject='codepal-codex-starter-it-'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$codexOwned=[IO.Path]::GetFullPath((Join-Path $codexRoot "target/codex-starter-it/$codexProject"))
if(-not $codexOwned.StartsWith($codexRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Disposable path escaped workspace' }
$codexImage='node@sha256:43ac6c60b8f89723f746e8a92ce91abd5017e627ce1ddfe4238355d3a30b772c'
$codexContainer=$codexProject
$codexVolume=$codexProject
$codexEvidence=[ordered]@{project=$codexProject;image=$codexImage;generateLocks=[bool]$GenerateLocks;cleanupVerified=$false;complete=$false;passes=@()}
if($ContractBehavior -and -not $ContractScaffold){throw 'Contract behavior requires exported structured fixture inputs'}
$codexPrivateNetwork=$codexProject+'-private'
$codexMongo=$codexProject+'-mongo'
function Invoke-StarterWsl {
    $codexPriorPreference=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        & wsl.exe @args 2>> (Join-Path $codexOwned 'native-stderr.log')
        $codexNativeExit=$LASTEXITCODE
    } finally { $ErrorActionPreference=$codexPriorPreference }
    $global:LASTEXITCODE=$codexNativeExit
    if($codexNativeExit -ne 0){throw "Disposable WSL command failed (exit $codexNativeExit); see $codexOwned/native-stderr.log"}
}
try {
    [void](New-Item -ItemType Directory -Path "$codexOwned/vite/src","$codexOwned/server/src","$codexOwned/cache" -Force)
    foreach($codexFile in @('package.json','tsconfig.json','vite.config.ts','index.html','src/main.tsx','src/index.css')) {
        Copy-Item -LiteralPath (Join-Path $codexRoot "src/main/resources/starters/vite-react/$codexFile") -Destination (Join-Path $codexOwned "vite/$codexFile")
    }
    foreach($codexFile in @('package.json','tsconfig.json','src/index.ts')) {
        Copy-Item -LiteralPath (Join-Path $codexRoot "src/main/resources/starters/mern/server/$codexFile") -Destination (Join-Path $codexOwned "server/$codexFile")
    }
    if(-not $GenerateLocks) {
        Copy-Item -LiteralPath (Join-Path $codexRoot 'src/main/resources/starters/vite-react/package-lock.json') -Destination "$codexOwned/vite/package-lock.json"
        Copy-Item -LiteralPath (Join-Path $codexRoot 'src/main/resources/starters/mern/server/package-lock.json') -Destination "$codexOwned/server/package-lock.json"
    }
    if($ContractScaffold) {
        if($GenerateLocks){throw 'Contract fixture verification must use the existing reviewed lockfiles'}
        $codexFixtureRoot=Join-Path $codexRoot $(if($ContractBehavior){'target/codex-structured-app-fixtures'}else{'target/codex-contract-scaffold'})
        if(-not (Test-Path -LiteralPath "$codexFixtureRoot/mern/server/src/index.ts" -PathType Leaf)){throw 'Run ContractScaffoldTest before checking generated packages'}
        Copy-Item -Path "$codexFixtureRoot/vite/*" -Destination "$codexOwned/vite" -Recurse -Force
        Copy-Item -Path "$codexFixtureRoot/mern/server/*" -Destination "$codexOwned/server" -Recurse -Force
    } else {
        "export default function App() { return <main className='p-4'>Starter verification</main> }" | Set-Content -LiteralPath "$codexOwned/vite/src/App.tsx" -Encoding UTF8
    }
    [ordered]@{project=$codexProject;generateLocks=[bool]$GenerateLocks} | ConvertTo-Json | Set-Content -LiteralPath "$codexOwned/owned.json" -Encoding ASCII
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Test-StarterPackages.cjs') -Destination "$codexOwned/check.cjs"
    if($ContractBehavior){Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Test-ContractBehavior.cjs') -Destination "$codexOwned/behavior.cjs"}
    $codexMount=Invoke-StarterWsl -e wslpath -u $codexOwned
    if($LASTEXITCODE -ne 0) { throw 'Mount path mapping failed' }
    Invoke-StarterWsl -e docker volume create --label "com.codepal.codex.starter-it=$codexProject" $codexVolume
    if($LASTEXITCODE -ne 0) { throw 'Disposable Linux volume creation failed' }
    foreach($codexPass in @('cold','warm')) {
        $codexNetwork=if($codexPass -eq 'cold') {'bridge'} else {'none'}
        $codexArgs=@('-e','docker','run','--name',$codexContainer,'--label',"com.codepal.codex.starter-it=$codexProject",'--pull','never','--cpus','2','--memory','2048m','--cap-drop','ALL','--security-opt','no-new-privileges','--network',$codexNetwork,'--mount',"type=bind,source=$codexMount,target=/inputs,readonly",'--mount',"type=volume,source=$codexVolume,target=/checks",'-e','HOME=/tmp',$codexImage,'node','/inputs/check.cjs')
        if($codexPass -eq 'warm') { $codexArgs+='--offline' }
        Invoke-StarterWsl @codexArgs
        if($LASTEXITCODE -ne 0) { throw "Starter $codexPass verification failed" }
        Invoke-StarterWsl -e docker cp "${codexContainer}:/checks/packages-result.json" "$codexMount/packages-result.json"
        if($LASTEXITCODE -ne 0) { throw 'Cannot copy verification evidence' }
        $codexEvidence.passes+=Get-Content -LiteralPath "$codexOwned/packages-result.json" -Raw | ConvertFrom-Json
        if($codexPass -eq 'warm' -and $GenerateLocks) {
            Invoke-StarterWsl -e docker cp "${codexContainer}:/checks/vite/package-lock.json" "$codexMount/vite/package-lock.json"
            if($LASTEXITCODE -ne 0) { throw 'Cannot copy verified client lock' }
            Invoke-StarterWsl -e docker cp "${codexContainer}:/checks/server/package-lock.json" "$codexMount/server/package-lock.json"
            if($LASTEXITCODE -ne 0) { throw 'Cannot copy verified server lock' }
        }
        Invoke-StarterWsl -e docker rm $codexContainer
        if($LASTEXITCODE -ne 0) { throw 'Finished container cleanup failed' }
    }
    if($GenerateLocks) {
        Copy-Item -LiteralPath "$codexOwned/vite/package-lock.json" -Destination (Join-Path $codexRoot 'src/main/resources/starters/vite-react/package-lock.json')
        Copy-Item -LiteralPath "$codexOwned/server/package-lock.json" -Destination (Join-Path $codexRoot 'src/main/resources/starters/mern/server/package-lock.json')
    }
    if($ContractBehavior) {
        Invoke-StarterWsl -e docker network create --internal --label "com.codepal.codex.starter-it=$codexProject" $codexPrivateNetwork
        if($LASTEXITCODE -ne 0){throw 'Private fixture network creation failed'}
        Invoke-StarterWsl -e docker run -d --name $codexMongo --label "com.codepal.codex.starter-it=$codexProject" --pull never --network $codexPrivateNetwork --network-alias codex-db --tmpfs /data/db:rw --tmpfs /data/configdb:rw --memory 512m --cpus 1 mongo@sha256:1f995ad6fdb93244a1addab1b58f934a0bc2f5643c38e02f5e9d7f0c7d227a7b --bind_ip_all --wiredTigerCacheSizeGB 0.25
        if($LASTEXITCODE -ne 0){throw 'Disposable private Mongo start failed'}
        Invoke-StarterWsl -e docker run --name $codexContainer --label "com.codepal.codex.starter-it=$codexProject" --pull never --network $codexPrivateNetwork --memory 512m --cpus 1 --cap-drop ALL --security-opt no-new-privileges --mount "type=bind,source=$codexMount,target=/inputs,readonly" --mount "type=volume,source=$codexVolume,target=/checks" $codexImage node /inputs/behavior.cjs
        if($LASTEXITCODE -ne 0){throw 'Generated contract behavior failed'}
        Invoke-StarterWsl -e docker cp "${codexContainer}:/checks/contract-behavior-result.json" "$codexMount/contract-behavior-result.json"
        if($LASTEXITCODE -ne 0){throw 'Cannot copy generated behavior evidence'}
        $codexEvidence.behavior=Get-Content -LiteralPath "$codexOwned/contract-behavior-result.json" -Raw | ConvertFrom-Json
    }
    $codexEvidence.complete=$true
} finally {
    $codexRemaining=@(Invoke-StarterWsl -e docker ps -aq --filter "label=com.codepal.codex.starter-it=$codexProject")
    if($LASTEXITCODE -ne 0) { throw 'Cannot inspect owned containers' }
    foreach($codexId in $codexRemaining) {
        $codexLabelJson=Invoke-StarterWsl -e docker inspect --format '{{json .Config.Labels}}' $codexId
        if($LASTEXITCODE -ne 0) { throw 'Cannot read container ownership' }
        $codexLabels=$codexLabelJson | ConvertFrom-Json
        if($codexLabels.'com.codepal.codex.starter-it' -ne $codexProject) { throw 'Container ownership mismatch' }
        Invoke-StarterWsl -e docker rm -f $codexId
        if($LASTEXITCODE -ne 0) { throw 'Owned container cleanup failed' }
    }
    $codexRemaining=@(Invoke-StarterWsl -e docker ps -aq --filter "label=com.codepal.codex.starter-it=$codexProject")
    if($LASTEXITCODE -ne 0) { throw 'Cannot verify remaining containers' }
    $codexVolumes=@(Invoke-StarterWsl -e docker volume ls -q --filter "label=com.codepal.codex.starter-it=$codexProject")
    if($LASTEXITCODE -ne 0) { throw 'Cannot inspect owned volumes' }
    foreach($codexName in $codexVolumes) {
        if($codexName -ne $codexVolume) { throw 'Volume ownership mismatch' }
        Invoke-StarterWsl -e docker volume rm $codexName
        if($LASTEXITCODE -ne 0) { throw 'Owned volume cleanup failed' }
    }
    $codexVolumes=@(Invoke-StarterWsl -e docker volume ls -q --filter "label=com.codepal.codex.starter-it=$codexProject")
    $codexNetworks=@(Invoke-StarterWsl -e docker network ls -q --filter "label=com.codepal.codex.starter-it=$codexProject")
    if($LASTEXITCODE -ne 0){throw 'Cannot inspect owned private networks'}
    foreach($codexNetworkId in $codexNetworks){
        $codexNetworkLabels=Invoke-StarterWsl -e docker network inspect --format '{{json .Labels}}' $codexNetworkId
        if($LASTEXITCODE -ne 0 -or ($codexNetworkLabels | ConvertFrom-Json).'com.codepal.codex.starter-it' -ne $codexProject){throw 'Private network ownership mismatch'}
        Invoke-StarterWsl -e docker network rm $codexNetworkId
        if($LASTEXITCODE -ne 0){throw 'Private network cleanup failed'}
    }
    $codexNetworks=@(Invoke-StarterWsl -e docker network ls -q --filter "label=com.codepal.codex.starter-it=$codexProject")
    $codexEvidence.cleanupVerified=$LASTEXITCODE -eq 0 -and $codexRemaining.Count -eq 0 -and $codexVolumes.Count -eq 0 -and $codexNetworks.Count -eq 0
    $codexEvidence.completedAt=[DateTime]::UtcNow.ToString('o')
    $codexEvidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $codexRoot "target/codex-starter-it/$codexProject.json") -Encoding UTF8
}
