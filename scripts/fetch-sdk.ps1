# Builds ignored SDK inputs from the exact canonical source revision.
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$pin = Get-Content (Join-Path $root "sdk-source.properties") -Raw | ConvertFrom-StringData
if ($pin.REPOSITORY -ne "https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git" -or
    $pin.REVISION -notmatch "^[0-9a-f]{40}$") {
    throw "Invalid canonical SDK source pin."
}
$source = Join-Path $root ".gradle\mopp-sdk-source"
if (!(Test-Path $source)) {
    git clone --quiet --filter=blob:none $pin.REPOSITORY $source
    if ($LASTEXITCODE -ne 0) { throw "SDK clone failed - check git.tak.gov access." }
}
$remote = git -C $source remote get-url origin
if ($LASTEXITCODE -ne 0 -or $remote -ne $pin.REPOSITORY) { throw "SDK source origin mismatch." }
$changes = git -C $source status --porcelain --untracked-files=no
if ($LASTEXITCODE -ne 0 -or $changes) { throw "SDK source has local changes; refusing to overwrite." }
git -C $source fetch --quiet --depth 1 origin $pin.REVISION
if ($LASTEXITCODE -ne 0) { throw "SDK source fetch failed." }
git -C $source checkout --quiet --detach $pin.REVISION
if ($LASTEXITCODE -ne 0) { throw "SDK source checkout failed." }
$actual = git -C $source rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $actual -ne $pin.REVISION) { throw "SDK source revision mismatch." }
& (Join-Path $source "gradlew.bat") -p $source --no-daemon --console=plain assembleDebug assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Pinned SDK source build failed." }
$dest = Join-Path $root "app\libs"
New-Item -ItemType Directory -Force $dest | Out-Null
foreach ($variant in @("debug", "release")) {
    Copy-Item (Join-Path $source "build\outputs\aar\weartak-plugin-sdk-$variant.aar") $dest -Force
}
"Built SDK source $actual (local derived inputs, not signed SDK releases)."
Get-FileHash (Join-Path $dest "*.aar") -Algorithm SHA256 | Select-Object Hash, Path