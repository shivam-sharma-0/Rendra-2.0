[CmdletBinding()]
param([ValidatePattern('^[A-Za-z0-9_,.]+$')][string]$Tests='CommandIsolationIT')
$ErrorActionPreference='Stop'
$codexRoot=[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$codexProject='codepal-codex-command-it-'+[Guid]::NewGuid().ToString('N').Substring(0,12)
$codexOwned=[System.IO.Path]::GetFullPath((Join-Path $codexRoot "target/codex-command-it/$codexProject"))
if(-not $codexOwned.StartsWith($codexRoot+[System.IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Owned directory escaped workspace' }
$codexBase=Join-Path $codexOwned 'workspaces'
$codexExit=$null
$codexCleanup=$false
Push-Location -LiteralPath $codexRoot
try {
    [void](New-Item -ItemType Directory -Path $codexBase -Force)
    $codexHostBase=& wsl.exe -e wslpath -u $codexBase
    if($LASTEXITCODE -ne 0) { throw 'Disposable path mapping failed' }
    $codexImage=& wsl.exe -e docker image inspect --format '{{.Id}}' node:22-bookworm-slim
    if($LASTEXITCODE -ne 0 -or $codexImage -notmatch '^sha256:[a-f0-9]{64}$') { throw 'Cached Node image missing' }
    $codexPriorPreference=$ErrorActionPreference
    try {
    $ErrorActionPreference='Continue'
    & mvn.cmd -Pcodex-verification -o "-Dtest=$Tests" '-Dcodepal.command.it=true' '-DfailIfNoTests=true' `
        "-Dcodex.command.project=$codexProject" "-Dcodex.command.base=$codexBase" "-Dcodex.command.host-base=$codexHostBase" "-Dcodex.command.image=$codexImage" test
    $codexExit=$LASTEXITCODE
    } finally { $ErrorActionPreference=$codexPriorPreference }
    if($codexExit -ne 0) { throw 'Disposable command checks failed' }
} finally {
    try {
        $codexProjects=Join-Path $codexOwned 'projects.txt'
        if(Test-Path -LiteralPath $codexProjects) {
            foreach($codexId in Get-Content -LiteralPath $codexProjects) {
                if($codexId -notmatch '^[a-f0-9-]{36}$') { throw 'Invalid owned project ID' }
                $codexContainers=@(& wsl.exe -e docker ps -aq --filter "label=com.codepal.project=$codexId")
                if($LASTEXITCODE -ne 0) { throw 'Could not verify owned containers' }
                foreach($codexContainer in $codexContainers) {
                    $codexLabelsJson=& wsl.exe -e docker inspect --format '{{json .Config.Labels}}' $codexContainer
                    if($LASTEXITCODE -ne 0) { throw 'Could not read container ownership' }
                    $codexLabels=$codexLabelsJson | ConvertFrom-Json
                    if($codexLabels.'com.codepal.project' -ne $codexId) { throw 'Container ownership mismatch' }
                    & wsl.exe -e docker rm -f $codexContainer
                    if($LASTEXITCODE -ne 0) { throw 'Owned container cleanup failed' }
                }
                $codexRemaining=@(& wsl.exe -e docker ps -aq --filter "label=com.codepal.project=$codexId")
                if($LASTEXITCODE -ne 0 -or $codexRemaining.Count -ne 0) { throw 'Owned command containers remain' }
            }
        }
        $codexLatencyFile=Join-Path $codexOwned 'latency.txt'
        $codexLatency=if(Test-Path -LiteralPath $codexLatencyFile) { @(Get-Content -LiteralPath $codexLatencyFile | ForEach-Object { '' + $_ }) } else { @() }
        # Resolved target checked above; only this fresh runner directory is removed.
        Remove-Item -LiteralPath $codexOwned -Recurse -Force
        $codexCleanup=-not (Test-Path -LiteralPath $codexOwned)
    } finally {
        [ordered]@{project=$codexProject;testExitCode=$codexExit;cleanupVerified=$codexCleanup;latency=$codexLatency;completedAt=[DateTime]::UtcNow.ToString('o')} |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $codexRoot "target/codex-command-it/$codexProject.json") -Encoding UTF8
        Pop-Location
    }
}
