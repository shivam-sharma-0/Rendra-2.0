[CmdletBinding()]
param([ValidatePattern('^[A-Za-z0-9_,.]+$')][string]$Tests = 'DisposableServicesIT', [switch]$UseWslMaven, [switch]$UseHostLoopback)

$ErrorActionPreference = 'Stop'
$codexRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$codexCompose = Join-Path $codexRoot $(if($UseHostLoopback){'infra/docker-compose.codex-test-native.yml'}else{'infra/docker-compose.codex-test.yml'})
if($UseHostLoopback -and -not $UseWslMaven){throw 'Native loopback requires WSL Maven'}
$codexProject = 'codepal-codex-it-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$codexStarted = $false
$codexRunFailed = $false
$codexCleanupVerified = $false
$codexTestExitCode = $null
$codexPostgresPort = $null
$codexRedisPort = $null

function Invoke-CodexDocker {
    param([string[]]$DockerArguments)
    $codexPriorErrorPreference=$ErrorActionPreference
    try {
        # Compose writes normal progress to stderr. Preserve it as evidence and use the native exit code.
        $ErrorActionPreference='Continue'
        if($UseHostLoopback) {
            $codexOutput = & wsl.exe -e env "CODEPAL_CODEX_PG_PORT=$codexPostgresPort" "CODEPAL_CODEX_REDIS_PORT=$codexRedisPort" docker @DockerArguments 2>> $codexDockerLog
        } else { $codexOutput = & wsl.exe -e docker @DockerArguments 2>> $codexDockerLog }
        $codexDockerExit=$LASTEXITCODE
    } finally { $ErrorActionPreference=$codexPriorErrorPreference }
    if ($codexDockerExit -ne 0) { throw "Disposable Docker command failed (exit $codexDockerExit); see $codexDockerLog" }
    return $codexOutput
}

function Get-CodexPort {
    param([string]$Service, [string]$ContainerPort)
    if($UseHostLoopback){if($Service -eq 'postgres'){return $codexPostgresPort}else{return $codexRedisPort}}
    $codexBinding = Invoke-CodexDocker -DockerArguments @('compose', '-p', $codexProject, '-f', $codexLinuxCompose, 'port', $Service, $ContainerPort)
    if ($codexBinding -notmatch '^127\.0\.0\.1:(\d+)$') {
        throw "Expected a loopback-only disposable port for $Service"
    }
    return [int]$Matches[1]
}

Push-Location -LiteralPath $codexRoot
 $codexLogDirectory=Join-Path $codexRoot 'target/codex-it'
 [void](New-Item -ItemType Directory -Path $codexLogDirectory -Force)
 $codexDockerLog=Join-Path $codexLogDirectory "$codexProject.docker.log"
try {
    if (-not (Test-Path -LiteralPath $codexCompose -PathType Leaf)) { throw 'Disposable Compose file missing' }
    $codexLinuxCompose = & wsl.exe -e wslpath -u $codexCompose
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the disposable Compose path in WSL' }
    if($UseHostLoopback) {
        $codexPair=& wsl.exe -e python3 -c "import socket; sockets=[socket.socket(),socket.socket()]; [s.bind(('127.0.0.1',0)) for s in sockets]; print(' '.join(str(s.getsockname()[1]) for s in sockets)); [s.close() for s in sockets]"
        if($LASTEXITCODE -ne 0 -or $codexPair -notmatch '^(\d+) (\d+)$'){throw 'Could not reserve native disposable ports'}
        $codexPostgresPort=[int]$Matches[1];$codexRedisPort=[int]$Matches[2]
        if($codexPostgresPort -lt 1024 -or $codexRedisPort -lt 1024 -or $codexPostgresPort -eq 5432 -or $codexRedisPort -eq 6379 -or $codexPostgresPort -eq $codexRedisPort){throw 'Invalid native disposable ports'}
    }
    # A fresh project id owns the network and containers; no app volumes are attached.
    $codexStarted = $true
    Invoke-CodexDocker -DockerArguments @('compose', '-p', $codexProject, '-f', $codexLinuxCompose, 'up', '-d', '--wait', '--wait-timeout', '60')
    $codexIds = @(Invoke-CodexDocker -DockerArguments @('compose', '-p', $codexProject, '-f', $codexLinuxCompose, 'ps', '-q'))
    if ($codexIds.Count -ne 2) { throw 'Expected exactly two disposable services' }
    foreach ($codexContainer in $codexIds) {
        $codexLabels = (Invoke-CodexDocker -DockerArguments @('inspect', '--format', '{{json .Config.Labels}}', $codexContainer)) | ConvertFrom-Json
        if ($codexLabels.'com.docker.compose.project' -ne $codexProject) { throw 'Container ownership mismatch' }
        if($UseHostLoopback) {
            $codexMode=Invoke-CodexDocker -DockerArguments @('inspect','--format','{{.HostConfig.NetworkMode}}',$codexContainer)
            if($codexMode -ne 'host'){throw 'Native disposable networking mismatch'}
        }
    }
    $codexPostgresPort = Get-CodexPort -Service 'postgres' -ContainerPort '5432'
    $codexRedisPort = Get-CodexPort -Service 'redis' -ContainerPort '6379'
    Write-Host "Disposable project: $codexProject; PostgreSQL port $codexPostgresPort; Redis port $codexRedisPort"
    $codexMavenArgs=@('-Pcodex-verification','-o',"-Dtest=$Tests",'-Dcodepal.disposable.it=true','-DfailIfNoTests=true',
        "-Dcodex.it.project=$codexProject","-Dcodex.it.postgres.port=$codexPostgresPort","-Dcodex.it.redis.port=$codexRedisPort",'test')
    if($UseWslMaven) {
        # Keep the same loopback-only services when Windows/WSL localhost forwarding is unavailable.
        $codexLinuxRoot=& wsl.exe -e wslpath -u $codexRoot
        if($LASTEXITCODE -ne 0) { throw 'Could not resolve owned WSL Maven directory' }
        $codexNativeVerification=& wsl.exe -e python3 "$codexLinuxRoot/scripts/Prepare-CodexNativeVerification.py" $codexLinuxRoot
        if($LASTEXITCODE -ne 0 -or $codexNativeVerification -notmatch '^/tmp/codepal-codex-verification/[a-f0-9]{16}$'){throw 'Could not verify owned native bytecode cache'}
        $codexMavenArgs=@("-Dcodex.verification.directory=$codexNativeVerification")+$codexMavenArgs
        $codexPriorMavenPreference=$ErrorActionPreference
        try {
            $ErrorActionPreference='Continue'
            & wsl.exe --cd $codexLinuxRoot -e env 'JAVA_TOOL_OPTIONS=-Xmx256m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -XX:TieredStopAtLevel=1' mvn @codexMavenArgs 2>> (Join-Path $codexLogDirectory "$codexProject.maven.stderr.log")
            $codexTestExitCode=$LASTEXITCODE
        } finally { $ErrorActionPreference=$codexPriorMavenPreference }
    } else {
        $codexPriorMavenPreference=$ErrorActionPreference
        try {
            $ErrorActionPreference='Continue'
            & mvn.cmd @codexMavenArgs 2>> (Join-Path $codexLogDirectory "$codexProject.maven.stderr.log")
            $codexTestExitCode=$LASTEXITCODE
        } finally { $ErrorActionPreference=$codexPriorMavenPreference }
    }
    if ($codexTestExitCode -ne 0) { throw 'Disposable integration tests failed' }
} catch {
    $codexRunFailed = $true
    throw
} finally {
    try {
        if ($codexStarted) {
            # This exact fresh Compose project is the only cleanup target.
            Invoke-CodexDocker -DockerArguments @('compose', '-p', $codexProject, '-f', $codexLinuxCompose, 'down', '--volumes', '--remove-orphans')
            $codexRemaining = @(Invoke-CodexDocker -DockerArguments @('ps', '-aq', '--filter', "label=com.docker.compose.project=$codexProject"))
            if ($codexRemaining.Count -ne 0) { throw "Disposable cleanup incomplete: $codexProject" }
            $codexNetworks = @(Invoke-CodexDocker -DockerArguments @('network', 'ls', '-q', '--filter', "label=com.docker.compose.project=$codexProject"))
            $codexVolumes = @(Invoke-CodexDocker -DockerArguments @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$codexProject"))
            if ($codexNetworks.Count -ne 0 -or $codexVolumes.Count -ne 0) { throw "Disposable resources remain: $codexProject" }
            $codexCleanupVerified = $true
        }
    } catch {
        if ($codexRunFailed) { Write-Warning "Cleanup also failed for $codexProject; inspect that project's resources only." }
        else { throw }
    } finally {
        $codexEvidenceDir = Join-Path $codexRoot 'target/codex-it'
        [void](New-Item -ItemType Directory -Path $codexEvidenceDir -Force)
        [ordered]@{
            project = $codexProject
            postgresPort = $codexPostgresPort
            redisPort = $codexRedisPort
            mavenRuntime = if($UseWslMaven) { 'WSL' } else { 'Windows' }
            nativeLoopback = [bool]$UseHostLoopback
            testExitCode = $codexTestExitCode
            cleanupVerified = $codexCleanupVerified
            completedAt = [DateTime]::UtcNow.ToString('o')
        } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $codexEvidenceDir "$codexProject.json") -Encoding UTF8
        Pop-Location
    }
}
