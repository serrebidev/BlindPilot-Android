$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskSource = Join-Path $taskRoot '.deps/llama.cpp'
$taskRevision = '3bb63cbcbfb62056c84fe9e521b0f3d756d29c32'
if (!(Test-Path -LiteralPath (Join-Path $taskSource '.git'))) {
    git clone --no-checkout https://github.com/ggml-org/llama.cpp.git $taskSource
    if ($LASTEXITCODE) { throw 'Cannot clone llama.cpp.' }
}
$taskChanges = git -C $taskSource status --porcelain
if ($LASTEXITCODE -or $taskChanges) { throw 'The llama.cpp dependency has changes; preserve them before running setup.' }
git -C $taskSource fetch --depth 1 origin $taskRevision
if ($LASTEXITCODE) { throw 'Cannot fetch the pinned llama.cpp revision.' }
git -C $taskSource checkout --detach $taskRevision
if ($LASTEXITCODE) { throw 'Cannot check out the pinned llama.cpp revision.' }
Write-Output "llama.cpp ready: $taskRevision"
