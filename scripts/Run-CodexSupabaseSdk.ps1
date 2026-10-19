[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$sdkRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sdkOwner = 'codepal-codex-sdk-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$sdkOutput = [IO.Path]::GetFullPath((Join-Path $sdkRoot "target/codex-supabase-sdk-it/$sdkOwner"))
if (-not $sdkOutput.StartsWith($sdkRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'SDK fixture escaped workspace' }
[void](New-Item -ItemType Directory -Path $sdkOutput -Force)
function Invoke-SdkDocker {
    $sdkPrior = $ErrorActionPreference
    try { $ErrorActionPreference='Continue'; $sdkText = & wsl.exe -e docker @args 2>> "$sdkOutput/native-stderr.log"; $sdkExit=$LASTEXITCODE }
    finally { $ErrorActionPreference=$sdkPrior }
    if ($sdkExit -ne 0) { throw "SDK fixture command failed ($sdkExit); see owned native-stderr.log" }
    return $sdkText
}
try {
    $sdkLinuxOutput = & wsl.exe -e wslpath -u $sdkOutput
    if ($LASTEXITCODE -ne 0) { throw 'SDK path mapping unavailable' }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'fixtures/supabase-sdk.json') -Destination "$sdkOutput/sdk.json"
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Prepare-SupabaseSdk.cjs') -Destination "$sdkOutput/prepare.cjs"
    $sdkImage = (Get-Content -LiteralPath (Join-Path $sdkRoot 'src/main/resources/starters/runtime.json') -Raw | ConvertFrom-Json).image
    Invoke-SdkDocker run --rm --name $sdkOwner --label "com.codepal.codex.sdk-it=$sdkOwner" --pull never --network bridge --user 1000:1000 `
        --cpus 1 --memory 256m --cap-drop ALL --security-opt no-new-privileges `
        --mount "type=bind,source=$sdkLinuxOutput,target=/inputs,readonly" --mount "type=bind,source=$sdkLinuxOutput,target=/output" `
        $sdkImage node /inputs/prepare.cjs
    Push-Location -LiteralPath $sdkRoot
    try {
        & mvn.cmd -o -Pcodex-verification "-Dcodex.verification.directory=$($sdkRoot.Replace('\','/'))/../codex-archive/verification/agent-improvements" `
            '-Dtest=SupabaseSdkBrowserIT' '-Dcodepal.supabase.sdk.it=true' "-Dcodepal.supabase.sdk.directory=$sdkOutput" `
            '-Dcodepal.docker.command=wsl.exe' '-Dcodepal.docker.args=-e docker' -DfailIfNoTests=true test
        if ($LASTEXITCODE -ne 0) { throw 'SDK browser gate failed' }
    } finally { Pop-Location }
} finally {
    $sdkContainers = @(Invoke-SdkDocker ps -aq --filter "label=com.codepal.codex.sdk-it=$sdkOwner")
    foreach ($sdkContainer in $sdkContainers) {
        $sdkLabels = Invoke-SdkDocker inspect --format '{{json .Config.Labels}}' $sdkContainer | ConvertFrom-Json
        if ($sdkLabels.'com.codepal.codex.sdk-it' -ne $sdkOwner) { throw 'SDK fixture ownership mismatch' }
        [void](Invoke-SdkDocker rm -f $sdkContainer)
    }
    if (@(Invoke-SdkDocker ps -aq --filter "label=com.codepal.codex.sdk-it=$sdkOwner").Count -ne 0) { throw 'SDK fixture cleanup remains unverified' }
}
