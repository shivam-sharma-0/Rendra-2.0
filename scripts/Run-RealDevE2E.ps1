[CmdletBinding()]
param([ValidateSet('static','vite','cancel','reject','mern','stop-retry')][string]$Scenario='static')
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceRoot=[IO.Path]::GetFullPath((Join-Path $taskRoot '../Code-Pal'))
$taskId='codex_e2e_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
if($taskId -notmatch '^codex_e2e_[a-f0-9]{12}$'){throw 'Invalid copy identity'}
$owned=[IO.Path]::GetFullPath((Join-Path $taskRoot "target/real-e2e/$taskId"))
if(-not $owned.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Evidence escaped workspace'}
[void](New-Item -ItemType Directory -Path $owned -Force)
$previousPassword=$env:PGPASSWORD;$previousWslEnv=$env:WSLENV;$previousDbPassword=$env:POSTGRES_PASSWORD
$appProcess=$null;$copyCreated=$false;$redisCreated=$false;$evidence=[ordered]@{id=$taskId;sourceDatabase='codepal';copyDatabase=$taskId;scenario=$Scenario;success=$false;cleanupVerified=$false;workspaceRetention='owned temporary workspace retained for audit'}
function Wsl-Check([string[]]$Argv){& wsl.exe -e @Argv;if($LASTEXITCODE -ne 0){throw "WSL operation failed (exit $LASTEXITCODE)"}}
try {
    $dbLine=[IO.File]::ReadAllLines((Join-Path $sourceRoot '.env')) | Where-Object {$_ -match '^POSTGRES_PASSWORD='} | Select-Object -First 1
    $env:PGPASSWORD=($dbLine -split '=',2)[1].Trim().Trim('"').Trim("'");$env:POSTGRES_PASSWORD=$env:PGPASSWORD;$env:WSLENV=($previousWslEnv+':PGPASSWORD/u').Trim(':')
    $identity=& wsl.exe -e psql -h 127.0.0.1 -U admin -d codepal -w -At -c 'select current_database(),current_user'
    if($LASTEXITCODE -ne 0 -or $identity -ne 'codepal|admin'){throw 'Unexpected source DB identity'}
    $dump=Join-Path $owned 'source.dump';$linuxDump=& wsl.exe -e wslpath -u $dump
    Wsl-Check @('pg_dump','-h','127.0.0.1','-U','admin','-d','codepal','-w','--no-owner','--no-privileges','-Fc','-f',$linuxDump)
    $ports=& wsl.exe -e python3 -c "import socket; s=[socket.socket() for _ in range(5)]; [x.bind(('127.0.0.1',0)) for x in s]; print(' '.join(str(x.getsockname()[1]) for x in s)); [x.close() for x in s]"
    if($ports -notmatch '^(\d+) (\d+) (\d+) (\d+) (\d+)$'){throw 'No isolated ports'};$appPort=[int]$Matches[1];$redisPort=[int]$Matches[2];$pgPort=[int]$Matches[3];$gatewayPort=[int]$Matches[4];$mongoPort=[int]$Matches[5]
    Wsl-Check @('docker','run','-d','--name',($taskId+'-pg'),'--label',"com.codepal.real-e2e=$taskId",'--network','host','--tmpfs','/var/lib/postgresql/data','-e','POSTGRES_USER=codepal_codex_it','-e','POSTGRES_PASSWORD=disposable-test-only','-e',"POSTGRES_DB=$taskId",'postgres:17-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826','-c',"port=$pgPort",'-c','listen_addresses=127.0.0.1');$copyCreated=$true
    # A foreground Linux client keeps this WSL fixture alive while Windows Java waits on a model.
    # It ends when our PostgreSQL container ends; no service/system/idle-timeout setting is changed.
    $wslKeeper=Start-Process -FilePath 'wsl.exe' -ArgumentList @('-e','docker','wait',($taskId+'-pg')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $owned 'wsl-wait.log') -RedirectStandardError (Join-Path $owned 'wsl-wait-error.log')
    for($i=0;$i -lt 30;$i++){& wsl.exe -e docker exec ($taskId+'-pg') pg_isready -p $pgPort -U codepal_codex_it | Out-Null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Seconds 1}
    $env:PGPASSWORD='disposable-test-only';$env:POSTGRES_PASSWORD=$env:PGPASSWORD
    Wsl-Check @('pg_restore','-h','127.0.0.1','-p',"$pgPort",'-U','codepal_codex_it','-d',$taskId,'-w','--no-owner','--no-privileges','--exit-on-error',$linuxDump)
    Remove-Item -LiteralPath $dump -Force
    # Never execute copied active jobs or retain their live queue/outbox ownership.
    Wsl-Check @('psql','-h','127.0.0.1','-p',"$pgPort",'-U','codepal_codex_it','-d',$taskId,'-w','-c',"update agent_runs set status='FAILED' where status in ('RUNNING','QUEUED','COMMITTING');")
    # Copied journals refer to source-owned processes, not this fixture. Never reconcile them
    # against the shared Docker daemon. The SQL below changes only the disposable database copy.
    $isolateCopy=@'
DO $codex$
BEGIN
 IF to_regclass('agent_project_io') IS NOT NULL THEN EXECUTE 'update agent_project_io set finished_at=clock_timestamp() where finished_at is null'; END IF;
 IF to_regclass('agent_promotions') IS NOT NULL THEN EXECUTE 'update agent_promotions set state=''ROLLED_BACK'',saved_at=null where state not in (''SAVED'',''ROLLED_BACK'')'; END IF;
 IF to_regclass('agent_code_restores') IS NOT NULL THEN EXECUTE 'update agent_code_restores set state=''ROLLED_BACK'' where state not in (''RESTORED'',''ROLLED_BACK'')'; END IF;
 IF to_regclass('agent_dispatch_outbox') IS NOT NULL THEN EXECUTE 'update agent_dispatch_outbox set dispatched_at=clock_timestamp() where dispatched_at is null'; END IF;
 IF to_regclass('agent_project_leases') IS NOT NULL THEN EXECUTE 'delete from agent_project_leases'; END IF;
END $codex$;
'@
    Wsl-Check @('psql','-h','127.0.0.1','-p',"$pgPort",'-U','codepal_codex_it','-d',$taskId,'-w','-v','ON_ERROR_STOP=1','-c',$isolateCopy)
    $linuxHome=& wsl.exe -e printenv HOME;$distro=& wsl.exe -e printenv WSL_DISTRO_NAME
    if($linuxHome -notmatch '^/home/[A-Za-z0-9_-]+$' -or $distro -notmatch '^[A-Za-z0-9_.-]+$'){throw 'Unexpected WSL workspace authority'}
    $linuxBase="$linuxHome/codepal-codex-e2e/$taskId";Wsl-Check @('mkdir','-p',$linuxBase)
    $uncBase='\\wsl.localhost\'+$distro+($linuxBase -replace '/','\')
    $evidence.workspace=$uncBase
    Wsl-Check @('docker','run','-d','--name',$taskId,'--label',"com.codepal.real-e2e=$taskId",'--network','host','--tmpfs','/data','redis:7-bookworm@sha256:4fa24486b8bcca8eec45ee0eb166edc674795e53a2b53d1a9ef263eecebaac85','redis-server','--bind','127.0.0.1','--port',"$redisPort",'--appendonly','no');$redisCreated=$true
    # Java classpath from the recent isolated suite; exclude test classes from production scanning.
    $report=Join-Path $taskRoot 'target/codex-verify/surefire-reports/TEST-com.code.pal.agent.usage.MeteredLlmClientTest.xml'
    [xml]$xml=Get-Content -LiteralPath $report -Raw
    $classpath=($xml.testsuite.properties.property | Where-Object {$_.name -eq 'java.class.path'}).value
    $classpath=($classpath -split ';' | Where-Object {$_ -notlike '*test-classes'}) -join ';'
    if($classpath -notmatch 'spring-boot-flyway'){throw 'Classpath lacks Flyway auto-configuration'}
    $sourcePath=$sourceRoot.Replace('\','/')
    $appPort=0
    $appArgs=@('-Dspring.devtools.restart.enabled=false','-Xmx256m','-Xss512k','-XX:ActiveProcessorCount=2','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-XX:MaxMetaspaceSize=192m','-cp',$classpath,'com.code.pal.CodePalApplication',"--server.port=$appPort",'--server.address=127.0.0.1','--spring.profiles.active=dev',"--spring.datasource.url=jdbc:postgresql://127.0.0.1:$pgPort/$taskId",'--spring.datasource.username=codepal_codex_it',"--spring.config.import=optional:file:$sourcePath/.secrets/secrets.properties", "--jwt.rsa.private-key=file:$sourcePath/.secrets/jwt-private.pem", "--jwt.rsa.public-key=file:$sourcePath/.secrets/jwt-public.pem", "--spring.data.redis.port=$redisPort", "--app.agent.workspace-base-path=$uncBase", "--app.agent.sandbox.host-workspace-path=$linuxBase",'--app.agent.billing-mode=FREE','--app.agent.worker-concurrency=1',"--app.agent.sandbox.container-prefix=$taskId-",'--app.agent.llm.max-attempts=1','--management.health.mongo.enabled=false','--logging.level.org.springframework.web=INFO','--logging.level.org.hibernate.SQL=INFO','--logging.level.org.springframework.jdbc=INFO')
    $appArgs+='--app.agent.deleted-project-sweep-ms=86400000'
    if($Scenario -eq 'reject'){$appArgs+=@('--app.agent.models.free-build=mistral:codestral-latest','--app.agent.models.free-chat=mistral:codestral-latest','--app.agent.providers.mistral.api-keys[0]=invalid-e2e-rejection-test','--app.agent.providers.mistral.api-key=invalid-e2e-rejection-test')}
    if($Scenario -eq 'mern'){$appArgs+=@("--app.agent.sandbox.apps-network=$taskId-apps","--app.agent.sandbox.apps-database-container=$taskId-mongo","--app.agent.sandbox.apps-database-volume=$taskId-mongo-data","--app.agent.sandbox.gateway-container=$taskId-gateway","--app.agent.sandbox.gateway-port=$gatewayPort","--app.agent.sandbox.apps-database-port=$mongoPort",'--app.agent.sandbox.apps-database-password=disposable-test-only')}
    # Start-Process string parsing needs quoted arguments; reject quotes before composing its command line.
    if($appArgs | Where-Object {$_ -match '"'}){throw 'Unsupported quote in launch argument'}
    $launchArgs=($appArgs | ForEach-Object {'"'+$_+'"'}) -join ' '
    $javaHome=($xml.testsuite.properties.property | Where-Object {$_.name -eq 'java.home'}).value
    $javaExecutable=Join-Path $javaHome 'bin/java.exe'
    $appProcess=Start-Process -FilePath $javaExecutable -ArgumentList $launchArgs -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $owned 'app.log') -RedirectStandardError (Join-Path $owned 'app-error.log')
    $evidence.pid=$appProcess.Id;$evidence.port=$appPort
    $ready=$false;for($i=0;$i -lt 90;$i++){$appProcess.Refresh();if($appProcess.HasExited){throw 'Spring Boot exited before readiness'};if($appPort -eq 0){$bootText=Get-Content -LiteralPath (Join-Path $owned 'app.log') -Raw;if($bootText -match 'Tomcat started on port (\d+)'){$appPort=[int]$Matches[1];$evidence.port=$appPort}};try {$response=Invoke-WebRequest "http://127.0.0.1:$appPort/actuator/health" -UseBasicParsing -TimeoutSec 1;if($response.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 1}
    if(-not $ready){throw 'Spring Boot readiness timeout'}
    $testScript=if($Scenario -eq 'mern'){'Test-FreshMernE2E.cjs'}else{'Test-RealDevE2E.cjs'}
    & node (Join-Path $PSScriptRoot $testScript) "http://127.0.0.1:$appPort" $uncBase $owned $Scenario
    $scenarioExit=$LASTEXITCODE
    $build=Get-Content -LiteralPath (Join-Path $owned 'build.json') -Raw | ConvertFrom-Json
    if($build.runId -notmatch '^[a-f0-9-]{36}$'){throw 'Unexpected owned run identity'}
    $sql="select json_build_object('attempts',count(*),'reservedInput',coalesce(sum(input_bound),0),'reservationTotal',coalesce(sum(reserved_tokens),0),'unknownCharge',coalesce(sum(case when state='UNKNOWN' then unknown_charge_tokens else 0 end),0),'reportedCharge',coalesce(sum(case when state='REPORTED' then prompt_tokens+completion_tokens else 0 end),0),'unknownCalls',count(*) filter(where state='UNKNOWN'),'held',coalesce(sum(case when state in ('SENT','RESERVED') then reserved_tokens else 0 end),0)) from ai_token_attempts where run_id='$($build.runId)'"
    $usage=& wsl.exe -e psql -h 127.0.0.1 -p $pgPort -U codepal_codex_it -d $taskId -w -At -c $sql
    if($LASTEXITCODE -ne 0){throw 'Cannot verify copied DB accounting'}
    $accounting=$usage | ConvertFrom-Json;$usage | Set-Content -LiteralPath (Join-Path $owned 'accounting.json') -Encoding UTF8
    if($Scenario -eq 'mern'){
        $contract=& wsl.exe -e psql -h 127.0.0.1 -p $pgPort -U codepal_codex_it -d $taskId -w -At -c "select contract_json from agent_build_contracts where run_id='$($build.runId)'"
        if($LASTEXITCODE -ne 0){throw 'Cannot retain owned contract evidence'}
        if($contract){$contract | Set-Content -LiteralPath (Join-Path $owned 'contract.json') -Encoding UTF8}
        $outputs=& wsl.exe -e psql -h 127.0.0.1 -p $pgPort -U codepal_codex_it -d $taskId -w -At -c "select coalesce(json_agg(json_build_object('unitId',unit_id,'output',output_json::json,'state',state) order by created_at),'[]'::json) from agent_unit_outputs where run_id='$($build.runId)'"
        if($LASTEXITCODE -ne 0){throw 'Cannot retain owned unit evidence'}
        $outputs | Set-Content -LiteralPath (Join-Path $owned 'units.json') -Encoding UTF8
    }
    if($Scenario -eq 'cancel' -and ($accounting.unknownCalls -lt 1 -or $accounting.unknownCharge -ne $accounting.reservedInput -or $accounting.held -ne 0)){throw 'Cancelled input-only accounting failed'}
    if($Scenario -eq 'reject' -and ($accounting.attempts -lt 1 -or $accounting.reportedCharge -ne 0 -or $accounting.unknownCharge -ne 0 -or $accounting.held -ne 0)){throw 'Rejected zero-charge accounting failed'}
    if($scenarioExit -ne 0){throw 'Real free-model build failed; build/accounting evidence retained'}
    $evidence.success=$true
} catch {
    $evidence.failure=$_.Exception.Message
    if($appProcess){$appProcess.Refresh();$evidence.jvmExitedBeforeCleanup=$appProcess.HasExited;if($appProcess.HasExited){$evidence.jvmExitCode=$appProcess.ExitCode}}
    $states=@();foreach($resource in @($taskId,($taskId+'-pg'),($taskId+'-mongo'),($taskId+'-gateway'))){
        $present=@(& wsl.exe -e docker ps -aq --filter "name=^/$resource`$")
        if($LASTEXITCODE -ne 0 -or $present.Count -eq 0){continue}
        $state=& wsl.exe -e docker inspect --format '{{json .State}}' $resource 2>$null
        if($LASTEXITCODE -eq 0){$value=$state | ConvertFrom-Json;$states+=@{name=$resource;status=$value.Status;running=$value.Running;oomKilled=$value.OOMKilled;exitCode=$value.ExitCode;finishedAt=$value.FinishedAt}}
    }
    $states | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $owned 'resources-before-cleanup.json') -Encoding UTF8
    throw
} finally {
    if($appProcess){$appProcess.Refresh();if(-not $appProcess.HasExited){Stop-Process -Id $appProcess.Id -Force;if(-not $appProcess.WaitForExit(5000)){throw 'Owned JVM did not stop; retain its DB'}}}
    $created=Join-Path $owned 'project.json'
    if(Test-Path -LiteralPath $created){$projectIdentity=(Get-Content -LiteralPath $created -Raw | ConvertFrom-Json).id;if($projectIdentity -notmatch '^[a-f0-9-]{36}$'){throw 'Invalid owned project identity'};$projectContainers=@(& wsl.exe -e docker ps -aq --filter "label=com.codepal.project=$projectIdentity");foreach($container in $projectContainers){$labels=& wsl.exe -e docker inspect --format '{{json .Config.Labels}}' $container;if(($labels | ConvertFrom-Json).'com.codepal.project' -ne $projectIdentity){throw 'Project container ownership mismatch'};& wsl.exe -e docker rm -fv $container}}
    $ownedContainers=@(& wsl.exe -e docker ps -aq --filter "name=$taskId")
    foreach($container in $ownedContainers){$name=& wsl.exe -e docker inspect --format '{{.Name}}' $container;if($name -notmatch "^/$taskId(?:-|$)"){throw 'Container ownership mismatch'};& wsl.exe -e docker rm -fv $container}
    $remaining=@(& wsl.exe -e docker ps -aq --filter "name=$taskId")
    if($Scenario -eq 'mern'){
        if($projectIdentity){
            $privateNetworks=@(& wsl.exe -e docker network ls -q --filter "label=com.codepal.project=$projectIdentity")
            foreach($privateNetwork in $privateNetworks){
                $proof=& wsl.exe -e docker network inspect --format '{{json .Labels}}' $privateNetwork
                if($LASTEXITCODE -ne 0 -or ($proof | ConvertFrom-Json).'com.codepal.project' -ne $projectIdentity){throw 'Private fixture network ownership mismatch'}
                & wsl.exe -e docker network rm $privateNetwork | Out-Null
                if($LASTEXITCODE -ne 0){throw 'Private fixture network cleanup failed'}
            }
        }
        $networks=@(& wsl.exe -e docker network ls --format '{{.Name}}' --filter "name=^$taskId-apps$");if($LASTEXITCODE -ne 0){throw 'Cannot verify owned app network'}
        if($networks.Count -gt 0){if($networks.Count -ne 1 -or $networks[0] -ne "$taskId-apps"){throw 'App network ownership mismatch'};& wsl.exe -e docker network rm "$taskId-apps" | Out-Null;if($LASTEXITCODE -ne 0){throw 'Owned app network cleanup failed'}}
        $volumes=@(& wsl.exe -e docker volume ls --format '{{.Name}}' --filter "name=^$taskId-mongo-data$");if($LASTEXITCODE -ne 0){throw 'Cannot verify owned Mongo volume'}
        if($volumes.Count -gt 0){if($volumes.Count -ne 1 -or $volumes[0] -ne "$taskId-mongo-data"){throw 'Mongo volume ownership mismatch'};& wsl.exe -e docker volume rm "$taskId-mongo-data" | Out-Null;if($LASTEXITCODE -ne 0){throw 'Owned Mongo volume cleanup failed'}}
    }
    if($wslKeeper){if(-not $wslKeeper.WaitForExit(5000)){Stop-Process -Id $wslKeeper.Id -Force;[void]$wslKeeper.WaitForExit(5000)}}
    $evidence.cleanupVerified=$remaining.Count -eq 0 -and (-not $appProcess -or $appProcess.HasExited) -and (-not $wslKeeper -or $wslKeeper.HasExited)
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $owned 'result.json') -Encoding UTF8
    if($dump -and (Test-Path -LiteralPath $dump -PathType Leaf)){Remove-Item -LiteralPath $dump -Force}
    $env:PGPASSWORD=$previousPassword;$env:POSTGRES_PASSWORD=$previousDbPassword;$env:WSLENV=$previousWslEnv
}
