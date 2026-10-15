[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$codexRoot=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$codexProject='codepal-codex-behavior-it-'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$codexContainer=$codexProject+'-mongo'
$codexImage='mongo@sha256:1f995ad6fdb93244a1addab1b58f934a0bc2f5643c38e02f5e9d7f0c7d227a7b'
$codexOwned=[System.IO.Path]::GetFullPath((Join-Path $codexRoot "target/codex-behavior-it/$codexProject"))
if(-not $codexOwned.StartsWith($codexRoot+[System.IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned path escaped worktree'}
$codexExit=$null;$codexCleanup=$false;$codexCreated=$false;$codexPort=$null
Push-Location -LiteralPath $codexRoot
try {
    [void](New-Item -ItemType Directory -Path $codexOwned -Force)
    $codexPort=[int](& wsl.exe -e python3 -c "import socket; s=socket.socket(); s.bind(('127.0.0.1',0)); print(s.getsockname()[1]); s.close()")
    if($LASTEXITCODE -ne 0 -or $codexPort -le 1024 -or $codexPort -eq 27017){throw 'Invalid fresh disposable port'}
    & wsl.exe -e docker run -d --pull never --name $codexContainer --label "com.codepal.codex.behavior-it=$codexProject" --network host --tmpfs /data/db:rw --tmpfs /data/configdb:rw --memory 512m --cpus 1 `
        $codexImage --bind_ip 127.0.0.1 --port $codexPort --auth --wiredTigerCacheSizeGB 0.25
    if($LASTEXITCODE -ne 0){throw 'Disposable Mongo failed to start'};$codexCreated=$true
    for($codexAttempt=0;$codexAttempt -lt 30;$codexAttempt++) {
        $codexPreviousErrorPreference=$ErrorActionPreference
        try {
            $ErrorActionPreference='Continue'
            $codexPing=& wsl.exe -e docker exec $codexContainer mongosh --quiet --port $codexPort --eval 'db.adminCommand({ping:1}).ok' 2>$null
            $codexPingExit=$LASTEXITCODE
        } finally { $ErrorActionPreference=$codexPreviousErrorPreference }
        if($codexPingExit -eq 0 -and $codexPing -match '1'){break}
        Start-Sleep -Milliseconds 500
    }
    if($codexPingExit -ne 0){throw 'Disposable Mongo readiness failed'}
    "db.getSiblingDB('admin').createUser({user:'codexFixtureAdmin',pwd:'codex-test-only-not-production',roles:[{role:'root',db:'admin'}]});" | Set-Content -LiteralPath (Join-Path $codexOwned 'bootstrap.js') -Encoding ASCII
    $codexMarker="db.getSiblingDB('admin').getCollection('_codex_fixture').insertOne({_id:'$codexProject',purpose:'behavior-verification'});"
    $codexMarker | Set-Content -LiteralPath (Join-Path $codexOwned 'marker.js') -Encoding ASCII
    $codexLinuxOwned=& wsl.exe -e wslpath -u $codexOwned
    & wsl.exe -e docker cp "$codexLinuxOwned/bootstrap.js" "${codexContainer}:/tmp/codex-bootstrap.js"
    if($LASTEXITCODE -ne 0){throw 'Disposable bootstrap copy failed'}
    & wsl.exe -e docker exec $codexContainer mongosh --quiet --port $codexPort /tmp/codex-bootstrap.js
    if($LASTEXITCODE -ne 0){throw 'Disposable bootstrap failed'}
    & wsl.exe -e docker cp "$codexLinuxOwned/marker.js" "${codexContainer}:/tmp/codex-marker.js"
    if($LASTEXITCODE -ne 0){throw 'Disposable marker copy failed'}
    & wsl.exe -e docker exec $codexContainer mongosh --quiet --port $codexPort -u codexFixtureAdmin -p codex-test-only-not-production --authenticationDatabase admin /tmp/codex-marker.js
    if($LASTEXITCODE -ne 0){throw 'Disposable identity marker failed'}
    $codexLinuxRoot=& wsl.exe -e wslpath -u $codexRoot
    & wsl.exe --cd $codexLinuxRoot -e env 'JAVA_TOOL_OPTIONS=-Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m' mvn -Pcodex-verification -o '-Dtest=ValidationDatabaseIT' '-Dcodepal.behavior.it=true' '-DfailIfNoTests=true' "-Dcodex.behavior.project=$codexProject" "-Dcodex.behavior.mongo.port=$codexPort" test
    $codexExit=$LASTEXITCODE;if($codexExit -ne 0){throw 'Disposable behavior checks failed'}
} finally {
    if($codexCreated) {
        $codexLabels=& wsl.exe -e docker inspect --format '{{json .Config.Labels}}' $codexContainer
        if($LASTEXITCODE -ne 0 -or ($codexLabels | ConvertFrom-Json).'com.codepal.codex.behavior-it' -ne $codexProject){throw 'Cannot prove disposable container ownership'}
        & wsl.exe -e docker rm -f $codexContainer
        if($LASTEXITCODE -ne 0){throw 'Disposable Mongo cleanup failed'}
    }
    $codexRemaining=@(& wsl.exe -e docker ps -aq --filter "label=com.codepal.codex.behavior-it=$codexProject")
    $codexCleanup=$LASTEXITCODE -eq 0 -and $codexRemaining.Count -eq 0
    [ordered]@{project=$codexProject;mongoPort=$codexPort;image=$codexImage;testExitCode=$codexExit;cleanupVerified=$codexCleanup;completedAt=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath "$codexOwned/result.json" -Encoding UTF8
    Pop-Location
    if(-not $codexCleanup){throw 'Disposable cleanup not verified'}
}
