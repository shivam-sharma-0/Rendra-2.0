[CmdletBinding()]
param(
    [ValidateSet('static','vite','cancel','reject','mern','stop-retry')][string]$Scenario='static',
    [switch]$PhaseOneGate,
    [switch]$PhaseTwoGate,
    [switch]$AgentBenchmark,
    [switch]$ViteGeneration,
    [switch]$PlanningIntake,
    [switch]$StructuredContracts,
    [string]$BenchmarkResultPath,
    [string]$BenchmarkCommit,
    [string]$VerificationDirectory,
    [switch]$IsolatedClasses,
    [switch]$TracePlanning,
    [switch]$PersonalTodo,
    [switch]$GenerationImprovements
)
$ErrorActionPreference='Stop'
$selectedGates = @(@($PhaseOneGate, $PhaseTwoGate, $AgentBenchmark, $ViteGeneration, $PlanningIntake) | Where-Object { $_ })
if ($selectedGates.Count -gt 1) {
    throw 'Select one phase gate per owned fixture'
}
$phaseGate = $PhaseOneGate -or $PhaseTwoGate -or $AgentBenchmark -or $ViteGeneration -or $PlanningIntake
$syntheticGate = $PhaseTwoGate -or $AgentBenchmark -or $ViteGeneration -or $PlanningIntake -or $IsolatedClasses
if ($TracePlanning -and -not $IsolatedClasses) { throw 'Planner tracing requires sanitized synthetic data and isolated classes' }
if ($PersonalTodo -and ($Scenario -ne 'mern' -or -not $IsolatedClasses)) { throw 'Personal Todo requires the isolated MERN fixture' }
if ($phaseGate -and ($Scenario -ne 'static' -or -not $VerificationDirectory)) {
    throw 'A phase gate requires the static scenario and an explicit scratch verification directory'
}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sourceRoot=$taskRoot
$productionClasses = if ($IsolatedClasses) {
    if (-not $VerificationDirectory) { throw 'Isolated classes require a verification directory' }
    [IO.Path]::GetFullPath((Join-Path $VerificationDirectory 'classes'))
} else { [IO.Path]::GetFullPath((Join-Path $taskRoot 'target/classes')) }
if ($AgentBenchmark) {
    $benchmarkArchive = [IO.Path]::GetFullPath((Join-Path $taskRoot '../codex-archive/benchmarks'))
    $benchmarkOutput = [IO.Path]::GetFullPath($BenchmarkResultPath)
    $benchmarkPolicy = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'fixtures/agent-benchmark-policy.json') -Raw | ConvertFrom-Json
    if ($BenchmarkCommit -notmatch '^[a-f0-9]{40}$' -or [IO.Path]::GetDirectoryName($benchmarkOutput) -ne $benchmarkArchive `
            -or [IO.Path]::GetFileName($benchmarkOutput) -notmatch $benchmarkPolicy.reportFilePattern) {
        throw 'Invalid benchmark output/commit identity'
    }
    if (Test-Path -LiteralPath $benchmarkOutput) { throw 'Refusing to overwrite a benchmark result' }
}
$taskId='codex_e2e_'+[Guid]::NewGuid().ToString('N').Substring(0,12)
if($taskId -notmatch '^codex_e2e_[a-f0-9]{12}$'){throw 'Invalid copy identity'}
$owned=[IO.Path]::GetFullPath((Join-Path $taskRoot "target/real-e2e/$taskId"))
if(-not $owned.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Evidence escaped workspace'}
[void](New-Item -ItemType Directory -Path $owned -Force)
if ($AgentBenchmark -or $IsolatedClasses) {
    $verifiedClasses = $productionClasses
    $classProof = $null
    if ($IsolatedClasses) {
        function Get-CandidateClassProof([string]$Directory) {
            $entries = @(Get-ChildItem -LiteralPath $Directory -Recurse -Force)
            if (@($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) { throw 'Reparse point in verified class tree' }
            $manifest = ($entries | Where-Object { -not $_.PSIsContainer } | Sort-Object FullName | ForEach-Object {
                $_.FullName.Substring($Directory.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }) -join "`n"
            $digest = [Security.Cryptography.SHA256]::Create()
            try { return [BitConverter]::ToString($digest.ComputeHash([Text.Encoding]::UTF8.GetBytes($manifest))).Replace('-','').ToLowerInvariant() }
            finally { $digest.Dispose() }
        }
        $classProof = Get-CandidateClassProof $verifiedClasses
        $productionClasses = Join-Path $owned 'production-classes'
        [void](New-Item -ItemType Directory -Path $productionClasses -Force)
        Copy-Item -Path (Join-Path $verifiedClasses '*') -Destination $productionClasses -Recurse -Force
        if ($classProof -ne (Get-CandidateClassProof $verifiedClasses) -or $classProof -ne (Get-CandidateClassProof $productionClasses)) {
            throw 'Verified classes changed during candidate snapshot'
        }
    }
}
if ($AgentBenchmark) {
    $dirtyPaths = @(& git -c core.safecrlf=false -C $taskRoot diff --name-only HEAD)
    @{commit=$BenchmarkCommit;dirtyTrackedPaths=$dirtyPaths;verifiedClassesDirectory=$verifiedClasses;classesDirectory=$productionClasses;classesSha256=$classProof;
        generationImprovements=[bool]$GenerationImprovements;
        agentRunnerClassSha256=(Get-FileHash -LiteralPath (Join-Path $productionClasses 'com/code/pal/agent/AgentRunner.class') -Algorithm SHA256).Hash} |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $owned 'benchmark-source.json') -Encoding UTF8
}
$previousPassword=$env:PGPASSWORD;$previousWslEnv=$env:WSLENV;$previousDbPassword=$env:POSTGRES_PASSWORD
$appProcess=$null;$plannerTraceProcess=$null;$copyCreated=$false;$redisCreated=$false;$evidence=[ordered]@{id=$taskId;sourceDatabase='codepal';copyDatabase=$taskId;scenario=$Scenario;success=$false;cleanupVerified=$false;workspaceRetention='owned temporary workspace retained for audit'}
if ($IsolatedClasses) { $evidence.classesSha256=$classProof; $evidence.verifiedClassesDirectory=$verifiedClasses }
if ($PersonalTodo) { $evidence.fixtureProfile = 'personal-todo; accounts fixture retains its independent result' }
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
    if ($phaseGate) {
        $backupDirectory = [IO.Path]::GetFullPath((Join-Path $taskRoot '../codex-archive/dev-db-backups'))
        [void](New-Item -ItemType Directory -Path $backupDirectory -Force)
        $backup = Join-Path $backupDirectory "$taskId.dump"
        Copy-Item -LiteralPath $dump -Destination $backup
        $evidence.backup = $backup
        $evidence.backupSha256 = (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash
        $evidence.backupRestored = $true
    }
    Remove-Item -LiteralPath $dump -Force
    if ($syntheticGate) {
        # Keep the restored schema/history, but no copied application data may reach a model.
        # This executes only against the uniquely named disposable database, before Boot starts.
        $sanitizeCopy = @'
DO $codex$
DECLARE targets text; item record; row_count bigint;
BEGIN
 IF current_database() <> '__OWNED_DATABASE__' OR current_user <> 'codepal_codex_it' THEN
  RAISE EXCEPTION 'Refusing to sanitize an unowned database';
 END IF;
 SELECT string_agg(format('%I.%I',schemaname,tablename),',') INTO targets
 FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history';
 -- Append-only audit tables refuse TRUNCATE by trigger; this disposable copy disables user triggers just for the wipe.
 FOR item IN SELECT schemaname,tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history' LOOP
  EXECUTE format('ALTER TABLE %I.%I DISABLE TRIGGER USER',item.schemaname,item.tablename);
 END LOOP;
 IF targets IS NOT NULL THEN EXECUTE 'TRUNCATE TABLE ' || targets || ' RESTART IDENTITY CASCADE'; END IF;
 FOR item IN SELECT schemaname,tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history' LOOP
  EXECUTE format('ALTER TABLE %I.%I ENABLE TRIGGER USER',item.schemaname,item.tablename);
 END LOOP;
 FOR item IN SELECT schemaname,tablename FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history' LOOP
  EXECUTE format('SELECT count(*) FROM %I.%I',item.schemaname,item.tablename) INTO row_count;
  IF row_count <> 0 THEN RAISE EXCEPTION 'Copied application data remains'; END IF;
 END LOOP;
END $codex$;
'@
        $sanitizeCopy = $sanitizeCopy.Replace('__OWNED_DATABASE__', $taskId)
        Wsl-Check @('psql','-h','127.0.0.1','-p',"$pgPort",'-U','codepal_codex_it','-d',$taskId,'-w','-v','ON_ERROR_STOP=1','-c',$sanitizeCopy)
        $evidence.copiedApplicationRowsRemovedBeforeBoot = $true
        $evidence.modelPayload = 'Synthetic test prompts/history, fixture/generated source and platform prompts/tool schemas only; Phase Two also uses one synthetic pixel'
        $evidence.modelDestinations = @('https://openrouter.ai/api/v1', 'https://api.mistral.ai/v1', 'https://integrate.api.nvidia.com/v1')
    }
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
    if ($VerificationDirectory) {
        $report = Join-Path $VerificationDirectory 'surefire-reports/TEST-com.code.pal.agent.usage.MeteredLlmClientTest.xml'
    }
    [xml]$xml=Get-Content -LiteralPath $report -Raw
    $classpath=($xml.testsuite.properties.property | Where-Object {$_.name -eq 'java.class.path'}).value
    $classpath=($classpath -split ';' | Where-Object {$_ -notlike '*test-classes'}) -join ';'
    if ($phaseGate -or $IsolatedClasses) {
        $dependencies = $classpath -split ';' | Where-Object { $_ -like '*.jar' }
        $classpath = (@($productionClasses) + $dependencies) -join ';'
    }
    if($classpath -notmatch 'spring-boot-flyway'){throw 'Classpath lacks Flyway auto-configuration'}
    $sourcePath=$sourceRoot.Replace('\','/')
    $appPort=0
    $appArgs=@('-Dspring.devtools.restart.enabled=false','-Xmx256m','-Xss512k','-XX:ActiveProcessorCount=2','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-XX:MaxMetaspaceSize=192m','-cp',$classpath,'com.code.pal.CodePalApplication',"--server.port=$appPort",'--server.address=127.0.0.1','--spring.profiles.active=dev',"--spring.datasource.url=jdbc:postgresql://127.0.0.1:$pgPort/$taskId",'--spring.datasource.username=codepal_codex_it',"--spring.config.import=optional:file:$sourcePath/.secrets/secrets.properties", "--jwt.rsa.private-key=file:$sourcePath/.secrets/jwt-private.pem", "--jwt.rsa.public-key=file:$sourcePath/.secrets/jwt-public.pem", "--spring.data.redis.port=$redisPort", "--app.agent.workspace-base-path=$uncBase", "--app.agent.sandbox.host-workspace-path=$linuxBase",'--app.agent.billing-mode=FREE','--app.agent.worker-concurrency=1',"--app.agent.sandbox.container-prefix=$taskId-",'--app.agent.llm.max-attempts=1','--management.health.mongo.enabled=false','--logging.level.org.springframework.web=INFO','--logging.level.org.hibernate.SQL=INFO','--logging.level.org.springframework.jdbc=INFO')
    $appArgs+='--app.agent.deleted-project-sweep-ms=86400000'
    if($StructuredContracts){$appArgs+='--app.agent.structured-contracts=true'}
    if ($syntheticGate) {
        # Fixed known destinations and FREE routes; no environment override may select a paid/other provider.
        $planningFixture = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'fixtures/planner-intake.json') -Raw | ConvertFrom-Json
        $planningRouteOrder = @($planningFixture.routes | ForEach-Object { "$($_.provider):$($_.model)" })
        $requirementsRouteOrder = @($planningFixture.requirementsRoutes)
        if ($planningRouteOrder.Count -ne 3 -or $requirementsRouteOrder.Count -ne 2 `
                -or ($planningRouteOrder | Where-Object { $_ -notmatch '^(openrouter|nvidia|mistral):[A-Za-z0-9_./:-]+$' }) `
                -or ($requirementsRouteOrder | Where-Object { $_ -notin $planningRouteOrder })) {
            throw 'Unexpected synthetic planning route manifest'
        }
        $appArgs += @('--app.agent.providers.mistral.base-url=https://api.mistral.ai/v1',
            '--app.agent.providers.nvidia.base-url=https://integrate.api.nvidia.com/v1',
            '--app.agent.providers.openrouter.base-url=https://openrouter.ai/api',
            '--app.agent.models.free-chat=mistral:ministral-8b-latest,mistral:codestral-latest,nvidia:nvidia/nemotron-3-super-120b-a12b',
            ('--app.agent.models.free-plan=' + ($planningRouteOrder -join ',')),
            ('--app.agent.requirements.models=' + ($requirementsRouteOrder -join ',')),
            '--app.agent.planning.max-route-attempts=3',
            '--app.agent.models.free-build=mistral:codestral-latest,nvidia:nvidia/nemotron-3-super-120b-a12b',
            '--app.agent.models.vision=nvidia:nvidia/nemotron-3-nano-omni-30b-a3b-reasoning,nvidia:meta/llama-3.2-11b-vision-instruct')
    }
    if($Scenario -eq 'reject'){$appArgs+=@('--app.agent.models.free-build=mistral:codestral-latest','--app.agent.models.free-chat=mistral:codestral-latest','--app.agent.providers.mistral.api-keys[0]=invalid-e2e-rejection-test','--app.agent.providers.mistral.api-key=invalid-e2e-rejection-test')}
    if ($TracePlanning) {
        $traceScript = Join-Path $PSScriptRoot 'Trace-SyntheticPlanner.cjs'
        if ($traceScript.Contains('"') -or $owned.Contains('"')) { throw 'Unexpected trace argument quote' }
        $plannerTraceProcess = Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"'+$traceScript+'"'), ('"'+$owned+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $owned 'planner-trace.log') -RedirectStandardError (Join-Path $owned 'planner-trace-error.log')
        $traceReady = Join-Path $owned 'planner-trace-ready.json'
        for ($i=0; $i -lt 50 -and -not (Test-Path -LiteralPath $traceReady); $i++) {
            $plannerTraceProcess.Refresh()
            if ($plannerTraceProcess.HasExited) { throw 'Synthetic planner trace exited before readiness' }
            Start-Sleep -Milliseconds 100
        }
        if (-not (Test-Path -LiteralPath $traceReady)) { throw 'Synthetic planner trace readiness timeout' }
        $tracePort = (Get-Content -LiteralPath $traceReady -Raw | ConvertFrom-Json).port
        if ($tracePort -notmatch '^\d+$' -or [int]$tracePort -lt 1024 -or [int]$tracePort -gt 65535) { throw 'Invalid synthetic trace port' }
        $appArgs = @($appArgs | Where-Object { $_ -notlike '--app.agent.providers.openrouter.base-url=*' `
                -and $_ -notlike '--app.agent.providers.mistral.base-url=*' -and $_ -notlike '--app.agent.providers.nvidia.base-url=*' })
        $appArgs += "--app.agent.providers.openrouter.base-url=http://127.0.0.1:$tracePort/api"
        $appArgs += "--app.agent.providers.mistral.base-url=http://127.0.0.1:$tracePort/mistral"
        $appArgs += "--app.agent.providers.nvidia.base-url=http://127.0.0.1:$tracePort/nvidia"
        $evidence.syntheticProposalTrace = 'response content/tool arguments only; fixed OpenRouter/Mistral/NVIDIA upstreams; no requests, headers or reasoning'
    }
    if($Scenario -eq 'mern'){$appArgs+=@("--app.agent.sandbox.apps-network=$taskId-apps","--app.agent.sandbox.apps-database-container=$taskId-mongo","--app.agent.sandbox.apps-database-volume=$taskId-mongo-data","--app.agent.sandbox.gateway-container=$taskId-gateway","--app.agent.sandbox.gateway-port=$gatewayPort","--app.agent.sandbox.apps-database-port=$mongoPort",'--app.agent.sandbox.apps-database-password=disposable-test-only')}
    # Start-Process string parsing needs quoted arguments; reject quotes before composing its command line.
    if($appArgs | Where-Object {$_ -match '"'}){throw 'Unsupported quote in launch argument'}
    if ($GenerationImprovements) {
        $appArgs += '--app.agent.feature-outcomes.enabled=true'
        $appArgs += '--app.agent.compact-tool-catalog=true'
        $appArgs += '--app.agent.build-artifacts.enabled=true'
    }
    $launchArgs=($appArgs | ForEach-Object {'"'+$_+'"'}) -join ' '
    $javaHome=($xml.testsuite.properties.property | Where-Object {$_.name -eq 'java.home'}).value
    $javaExecutable=Join-Path $javaHome 'bin/java.exe'
    $appProcess=Start-Process -FilePath $javaExecutable -ArgumentList $launchArgs -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $owned 'app.log') -RedirectStandardError (Join-Path $owned 'app-error.log')
    $evidence.pid=$appProcess.Id;$evidence.port=$appPort
    $ready=$false;for($i=0;$i -lt 90;$i++){$appProcess.Refresh();if($appProcess.HasExited){throw 'Spring Boot exited before readiness'};if($appPort -eq 0){$bootText=Get-Content -LiteralPath (Join-Path $owned 'app.log') -Raw;if($bootText -match 'Tomcat started on port (\d+)'){$appPort=[int]$Matches[1];$evidence.port=$appPort}};try {$response=Invoke-WebRequest "http://127.0.0.1:$appPort/actuator/health" -UseBasicParsing -TimeoutSec 1;if($response.StatusCode -eq 200){$ready=$true;break}}catch{};Start-Sleep -Seconds 1}
    if(-not $ready){throw 'Spring Boot readiness timeout'}
    if ($phaseGate -or $IsolatedClasses) {
        $startup = Get-Content -LiteralPath (Join-Path $owned 'app.log') -Raw
        $expectedClasses = $productionClasses
        if (-not $startup.Contains($expectedClasses)) {
            throw 'Startup does not identify the selected verified production classes'
        }
        $evidence.startupClassesVerified = $expectedClasses
    }
    $testScript=if($PlanningIntake){'Test-PlannerIntake.cjs'}elseif($AgentBenchmark -or $ViteGeneration){'Test-AgentBenchmark.cjs'}elseif($PhaseTwoGate){'Test-PhaseTwoGate.cjs'}elseif($Scenario -eq 'mern'){'Test-FreshMernE2E.cjs'}else{'Test-RealDevE2E.cjs'}
    $driverScenario = if ($ViteGeneration) { 'vite-generation' } elseif ($PersonalTodo) { 'personal-todo' } else { $Scenario }
    $nodeArguments = @((Join-Path $PSScriptRoot $testScript), "http://127.0.0.1:$appPort", $uncBase, $owned, $driverScenario)
    if ($PhaseOneGate) {
        $nodeArguments += 'phase-one'
    }
    $priorNativePreference=$ErrorActionPreference
    try {
        # Native stderr must not skip durable usage export; the explicit exit code still fails the gate below.
        $ErrorActionPreference='Continue'
        & node @nodeArguments
        $scenarioExit=$LASTEXITCODE
    } finally { $ErrorActionPreference=$priorNativePreference }
    if ($AgentBenchmark -or $ViteGeneration -or $PlanningIntake) {
        $manifest = Join-Path $owned 'projects.json'
        $identities = @()
        if (Test-Path -LiteralPath $manifest) {
            $benchmarkProjects = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
            if ($benchmarkProjects.owner -ne $taskId) { throw 'Synthetic project manifest ownership mismatch' }
            $identities = @($benchmarkProjects.projects | ForEach-Object { $_.id })
        }
        if ($identities.Count -gt 10 -or ($identities | Where-Object { $_ -notmatch '^[a-f0-9-]{36}$' })) {
            throw 'Unexpected benchmark project identities'
        }
        if ($identities.Count) {
            $projectFilter = ($identities | ForEach-Object { "'$_'" }) -join ','
            $ledgerSql = "select coalesce(json_agg(x),'[]'::json) from (select a.id,a.logical_call_id,a.project_id,a.run_id,a.purpose,a.provider,a.model,a.state,a.sent_at,a.prompt_tokens,a.completion_tokens,a.unknown_charge_tokens,c.outcome,c.error,c.duration_ms from ai_token_attempts a left join agent_model_calls c on c.attempt_id=a.id where a.project_id in ($projectFilter) order by a.created_at,a.id) x"
            $ledger = & wsl.exe -e psql -h 127.0.0.1 -p "$pgPort" -U codepal_codex_it -d $taskId -w -At -c $ledgerSql
            if ($LASTEXITCODE -ne 0) { throw 'Cannot export benchmark call ledger' }
            $ledger | Set-Content -LiteralPath (Join-Path $owned 'benchmark-ledger.json') -Encoding UTF8
            $contractsSql = "select coalesce(json_agg(x),'[]'::json) from (select r.project_id,r.mode,a.contract_id from agent_run_contracts a join agent_runs r on r.id=a.run_id where r.project_id in ($projectFilter)) x"
            $contracts = & wsl.exe -e psql -h 127.0.0.1 -p "$pgPort" -U codepal_codex_it -d $taskId -w -At -c $contractsSql
            if ($LASTEXITCODE -ne 0) { throw 'Cannot export benchmark plan bindings' }
            $contracts | Set-Content -LiteralPath (Join-Path $owned 'benchmark-contracts.json') -Encoding UTF8
            $proposalSql = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'fixtures/agent-benchmark-proposals.sql')).Replace('{{projects}}',$projectFilter)
            $proposals = & wsl.exe -e psql -h 127.0.0.1 -p "$pgPort" -U codepal_codex_it -d $taskId -w -At -c $proposalSql
            if ($LASTEXITCODE -ne 0) { throw 'Cannot export durable benchmark proposal bindings' }
            $proposals | Set-Content -LiteralPath (Join-Path $owned 'benchmark-proposals.json') -Encoding UTF8
        }
        if ($scenarioExit -ne 0) { throw 'Generation driver stopped; partial journey evidence retained' }
        if ($ViteGeneration) {
            $viteResult = Get-Content -LiteralPath (Join-Path $owned 'vite-generation.json') -Raw | ConvertFrom-Json
            if ($viteResult.owner -ne $taskId -or -not $viteResult.passed -or ($viteResult.journeyIds -join ',') -ne '7,8') {
                throw 'Vite generation requires both declared saved-app browser journeys'
            }
        }
        if ($PlanningIntake) {
            $intakeResult = Get-Content -LiteralPath (Join-Path $owned 'planner-intake.json') -Raw | ConvertFrom-Json
            $intakeLedger = @(Get-Content -LiteralPath (Join-Path $owned 'benchmark-ledger.json') -Raw | ConvertFrom-Json)
            if ($intakeResult.owner -ne $taskId -or -not $intakeResult.passed -or $intakeLedger.Count -lt 1 -or $intakeLedger.Count -gt 2 `
                    -or $intakeLedger[-1].state -ne 'REPORTED' -or $intakeLedger[-1].outcome -ne 'OK') {
                throw 'Planning intake requires a reported successful plan within the configured primary/fallback bound'
            }
            for ($planningIndex = 0; $planningIndex -lt $intakeLedger.Count; $planningIndex++) {
                $planningAttempt = $intakeLedger[$planningIndex]
                if ($planningAttempt.purpose -ne 'REQUIREMENTS' -or "$($planningAttempt.provider):$($planningAttempt.model)" -ne $planningRouteOrder[$planningIndex] `
                        -or $planningAttempt.state -in @('RESERVED','SENT')) {
                    throw 'Planning intake attempted an unexpected route or left an unsettled transmission'
                }
            }
        }
        $evidence.success = $true
    } else {
        if ($PhaseTwoGate) {
            $ownedProject = (Get-Content -LiteralPath (Join-Path $owned 'project.json') -Raw | ConvertFrom-Json).id
            if ($ownedProject -notmatch '^[a-f0-9-]{36}$') { throw 'Unexpected Phase Two project identity' }
            $phaseSql = "select coalesce(json_agg(x),'[]'::json) from (select r.id,r.message_id,r.mode,r.status,r.code_saved_at,r.preview_status,count(a.id) as attempts,count(a.id) filter(where a.purpose='INTENT') as routing_calls,coalesce(sum(a.prompt_tokens+a.completion_tokens),0) as reported_tokens,coalesce(sum(case when a.state='UNKNOWN' then a.unknown_charge_tokens else 0 end),0) as unknown_charge,count(a.id) filter(where a.state in ('SENT','RESERVED')) as held from agent_runs r left join ai_token_attempts a on a.run_id=r.id where r.project_id='$ownedProject' group by r.id order by r.created_at) x"
            $phaseUsage = & wsl.exe -e psql -h 127.0.0.1 -p $pgPort -U codepal_codex_it -d $taskId -w -At -c $phaseSql
            if ($LASTEXITCODE -ne 0) { throw 'Cannot verify Phase Two call ledger' }
            $phaseUsage | Set-Content -LiteralPath (Join-Path $owned 'phase-two-accounting.json') -Encoding UTF8
            if ($scenarioExit -ne 0) { throw 'Phase Two real-model gate failed; scenario and call-ledger evidence retained' }
            $phaseRows = $phaseUsage | ConvertFrom-Json
            if ($phaseRows | Where-Object { $_.routing_calls -ne 0 -or $_.held -ne 0 }) {
                throw 'Phase Two has a routing model call or unsettled reservation'
            }
        }
        $buildPath = Join-Path $owned 'build.json'
        if (-not (Test-Path -LiteralPath $buildPath)) {
            throw 'Fixture stopped before generation; requirements and model-usage evidence retained'
        }
        $build=Get-Content -LiteralPath $buildPath -Raw | ConvertFrom-Json
        if($build.runId -notmatch '^[a-f0-9-]{36}$'){throw 'Unexpected owned run identity'}
        $sql="select json_build_object('attempts',count(*),'reservedInput',coalesce(sum(input_bound),0),'reservationTotal',coalesce(sum(reserved_tokens),0),'unknownCharge',coalesce(sum(case when state='UNKNOWN' then unknown_charge_tokens else 0 end),0),'reportedCharge',coalesce(sum(case when state='REPORTED' then prompt_tokens+completion_tokens else 0 end),0),'unknownCalls',count(*) filter(where state='UNKNOWN'),'held',coalesce(sum(case when state in ('SENT','RESERVED') then reserved_tokens else 0 end),0)) from ai_token_attempts where run_id='$($build.runId)'"
        $usage=& wsl.exe -e psql -h 127.0.0.1 -p $pgPort -U codepal_codex_it -d $taskId -w -At -c $sql
        if($LASTEXITCODE -ne 0){throw 'Cannot verify copied DB accounting'}
        $accounting=$usage | ConvertFrom-Json;$usage | Set-Content -LiteralPath (Join-Path $owned 'accounting.json') -Encoding UTF8
        if($Scenario -eq 'mern'){
            $contract=& wsl.exe -e psql -h 127.0.0.1 -p $pgPort -U codepal_codex_it -d $taskId -w -At -c "select contract_json from agent_build_contracts where run_id='$($build.runId)'"
            if($LASTEXITCODE -ne 0){throw 'Cannot retain owned contract evidence'}
            if($contract){$contract | Set-Content -LiteralPath (Join-Path $owned 'contract.json') -Encoding UTF8}
            if ($PersonalTodo -and $scenarioExit -eq 0) {
                if (-not $contract) { throw 'Saved personal Todo has no proved build contract' }
                $savedContract = $contract | ConvertFrom-Json
                if (@($savedContract.routes | ForEach-Object { $_.path } | Select-Object -Unique).Count -lt 3) {
                    throw 'Saved personal Todo lacks required multiple application pages'
                }
            }
            $outputs=& wsl.exe -e psql -h 127.0.0.1 -p $pgPort -U codepal_codex_it -d $taskId -w -At -c "select coalesce(json_agg(json_build_object('unitId',unit_id,'output',output_json::json,'state',state) order by created_at),'[]'::json) from agent_unit_outputs where run_id='$($build.runId)'"
            if($LASTEXITCODE -ne 0){throw 'Cannot retain owned unit evidence'}
            $outputs | Set-Content -LiteralPath (Join-Path $owned 'units.json') -Encoding UTF8
        }
        if($Scenario -eq 'cancel' -and ($accounting.unknownCalls -lt 1 -or $accounting.unknownCharge -ne $accounting.reservedInput -or $accounting.held -ne 0)){throw 'Cancelled input-only accounting failed'}
        if($Scenario -eq 'reject' -and ($accounting.attempts -lt 1 -or $accounting.reportedCharge -ne 0 -or $accounting.unknownCharge -ne 0 -or $accounting.held -ne 0)){throw 'Rejected zero-charge accounting failed'}
        if($scenarioExit -ne 0){throw 'Real free-model build failed; build/accounting evidence retained'}
        $evidence.success=$true
    }
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
    if ($plannerTraceProcess) {
        $plannerTraceProcess.Refresh()
        if (-not $plannerTraceProcess.HasExited) { Stop-Process -Id $plannerTraceProcess.Id -Force; [void]$plannerTraceProcess.WaitForExit(5000) }
        if (-not $plannerTraceProcess.HasExited) { throw 'Owned planner trace did not stop' }
    }
    $projectIdentities = @()
    $created = Join-Path $owned 'project.json'
    if (Test-Path -LiteralPath $created) { $projectIdentities += (Get-Content -LiteralPath $created -Raw | ConvertFrom-Json).id }
    $createdMany = Join-Path $owned 'projects.json'
    if (($AgentBenchmark -or $ViteGeneration -or $PlanningIntake) -and (Test-Path -LiteralPath $createdMany)) {
        $manifest = Get-Content -LiteralPath $createdMany -Raw | ConvertFrom-Json
        if ($manifest.owner -ne $taskId) { throw 'Benchmark cleanup ownership mismatch' }
        $projectIdentities += @($manifest.projects | ForEach-Object { $_.id })
    }
    $projectCleanupVerified = $true
    foreach ($projectIdentity in @($projectIdentities | Select-Object -Unique)) {
        if ($projectIdentity -notmatch '^[a-f0-9-]{36}$') { throw 'Invalid owned project identity' }
        $projectContainers = @(& wsl.exe -e docker ps -aq --filter "label=com.codepal.project=$projectIdentity")
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect owned project containers' }
        foreach ($container in $projectContainers) {
            $labels = & wsl.exe -e docker inspect --format '{{json .Config.Labels}}' $container
            if (($labels | ConvertFrom-Json).'com.codepal.project' -ne $projectIdentity) { throw 'Project container ownership mismatch' }
            & wsl.exe -e docker rm -fv $container
            if ($LASTEXITCODE -ne 0) { throw 'Owned project cleanup failed' }
        }
        $left = @(& wsl.exe -e docker ps -aq --filter "label=com.codepal.project=$projectIdentity")
        $projectCleanupVerified = $projectCleanupVerified -and $LASTEXITCODE -eq 0 -and $left.Count -eq 0
    }
    $ownedContainers=@(& wsl.exe -e docker ps -aq --filter "name=$taskId")
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect remaining fixture containers' }
    foreach($container in $ownedContainers){$name=& wsl.exe -e docker inspect --format '{{.Name}}' $container;if($name -notmatch "^/$taskId(?:-|$)"){throw 'Container ownership mismatch'};& wsl.exe -e docker rm -fv $container}
    $remaining=@(& wsl.exe -e docker ps -aq --filter "name=$taskId")
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify fixture container cleanup' }
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
    $evidence.cleanupVerified=$remaining.Count -eq 0 -and $projectCleanupVerified -and (-not $appProcess -or $appProcess.HasExited) -and (-not $wslKeeper -or $wslKeeper.HasExited)
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $owned 'result.json') -Encoding UTF8
    if($dump -and (Test-Path -LiteralPath $dump -PathType Leaf)){Remove-Item -LiteralPath $dump -Force}
    $env:PGPASSWORD=$previousPassword;$env:POSTGRES_PASSWORD=$previousDbPassword;$env:WSLENV=$previousWslEnv
    if ($AgentBenchmark) {
        & node (Join-Path $PSScriptRoot 'AgentBenchmarkReport.cjs') $owned $benchmarkOutput $BenchmarkCommit
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark artifact could not be written' }
    }
}
