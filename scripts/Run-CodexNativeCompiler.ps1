[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [ValidatePattern('^/home/[A-Za-z0-9_-]+/codepal-codex-e2e/codex_e2e_[a-f0-9]{12}/[a-f0-9-]{36}/\.codepal/changesets/[a-f0-9-]{36}/epoch-[0-9]+/workspace$')]
    [string]$Source
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$owner='codepal-codex-native-'+[guid]::NewGuid().ToString('N').Substring(0,12)
$folder=[IO.Path]::GetFullPath((Join-Path $root ('target/codex-native-it/'+$owner)))
if(-not $folder.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Fixture escaped workspace'}
[void](New-Item -ItemType Directory -Path $folder -Force)
$runtime=Get-Content -LiteralPath (Join-Path $root 'src/main/resources/starters/runtime.json') -Raw | ConvertFrom-Json
if($runtime.image -notmatch '^node@sha256:[a-f0-9]{64}$'){throw 'Reviewed pinned Node image required'}
$receipt=[ordered]@{owner=$owner;source=$Source;image=$runtime.image;installPassed=$false;offlineBuildPassed=$false;cleanupVerified=$false}
$created=$false
$install="set -eu`ncp -R /source/. /checks/`ncd /checks/client`nnpm ci --prefer-online --include=optional --ignore-scripts --no-audit --no-fund --loglevel=error`n"
$build="set -eu`ncd /checks/client`nnode node_modules/vite/bin/vite.js --version`ncd /checks`nnode client/node_modules/vite/bin/vite.js build client`n"
[IO.File]::WriteAllText((Join-Path $folder 'install.sh'),$install,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $folder 'build.sh'),$build,[Text.UTF8Encoding]::new($false))
function Invoke-NativeWsl {
    $previousPreference=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        $output=& wsl.exe @args 2>> (Join-Path $folder 'native-stderr.log')
        $commandExit=$LASTEXITCODE
    } finally { $ErrorActionPreference=$previousPreference }
    if($commandExit -ne 0){throw "Owned native fixture command failed ($commandExit); see $folder"}
    return $output
}
try {
    $linuxEvidence=Invoke-NativeWsl -e wslpath -u $folder
    [void](Invoke-NativeWsl -e docker volume create --label "com.codepal.codex.native-it=$owner" $owner)
    $created=$true
    Invoke-NativeWsl -e docker run --rm --name "$owner-install" --label "com.codepal.codex.native-it=$owner" --pull never --network bridge `
        --memory 2048m --cpus 2 --cap-drop ALL --security-opt no-new-privileges `
        --mount "type=bind,source=$Source,target=/source,readonly" --mount "type=bind,source=$linuxEvidence,target=/evidence,readonly" `
        --mount "type=volume,source=$owner,target=/checks" $runtime.image sh /evidence/install.sh |
        Set-Content -LiteralPath (Join-Path $folder 'install.log') -Encoding UTF8
    $receipt.installPassed=$true
    Invoke-NativeWsl -e docker run --rm --name "$owner-build" --label "com.codepal.codex.native-it=$owner" --pull never --network none `
        --memory 2048m --cpus 2 --cap-drop ALL --security-opt no-new-privileges `
        --mount "type=bind,source=$linuxEvidence,target=/evidence,readonly" --mount "type=volume,source=$owner,target=/checks" `
        $runtime.image sh /evidence/build.sh | Set-Content -LiteralPath (Join-Path $folder 'build.log') -Encoding UTF8
    $receipt.offlineBuildPassed=$true
} finally {
    foreach($container in @(Invoke-NativeWsl -e docker ps -aq --filter "label=com.codepal.codex.native-it=$owner")) {
        $proof=Invoke-NativeWsl -e docker inspect --format '{{json .Config.Labels}}' $container | ConvertFrom-Json
        if($proof.'com.codepal.codex.native-it' -ne $owner){throw 'Native container ownership mismatch'}
        [void](Invoke-NativeWsl -e docker rm -f $container)
    }
    if($created) {
        $proof=Invoke-NativeWsl -e docker volume inspect --format '{{json .Labels}}' $owner | ConvertFrom-Json
        if($proof.'com.codepal.codex.native-it' -ne $owner){throw 'Native volume ownership mismatch'}
        [void](Invoke-NativeWsl -e docker volume rm $owner)
    }
    $left=@(Invoke-NativeWsl -e docker ps -aq --filter "label=com.codepal.codex.native-it=$owner")
    $volumes=@(Invoke-NativeWsl -e docker volume ls -q --filter "label=com.codepal.codex.native-it=$owner")
    $receipt.cleanupVerified=$left.Count -eq 0 -and $volumes.Count -eq 0
    $receipt | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $folder 'result.json') -Encoding UTF8
    if(-not $receipt.cleanupVerified){throw 'Native compiler cleanup not verified'}
}
Write-Output "Native compiler evidence: $folder/result.json"
