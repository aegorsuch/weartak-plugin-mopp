#!/usr/bin/env bash
# Builds local derived SDK inputs from verified, pinned canonical source.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
repo="$(sed -n 's/^REPOSITORY=//p' "$root/sdk-source.properties" | tr -d '\r')"
revision="$(sed -n 's/^REVISION=//p' "$root/sdk-source.properties" | tr -d '\r')"
[ "$repo" = "https://git.tak.gov/weartak-plugins/weartak-plugin-sdk.git" ] &&
    [[ "$revision" =~ ^[0-9a-f]{40}$ ]] || {
    echo "ERROR: invalid canonical SDK source pin." >&2; exit 1;
}
auth=""
if [ -n "${SDK_READ_TOKEN:-}" ]; then
    auth="Authorization: Basic $(printf 'oauth2:%s' "$SDK_READ_TOKEN" | base64 | tr -d '\n')"
elif [ -n "${CI_JOB_TOKEN:-}" ]; then
    auth="Authorization: Basic $(printf 'gitlab-ci-token:%s' "$CI_JOB_TOKEN" | base64 | tr -d '\n')"
fi
sdk_git() {
    if [ -n "$auth" ]; then git -c http.extraHeader="$auth" "$@"; else git "$@"; fi
}
source="$root/.gradle/mopp-sdk-source"
if [ ! -d "$source" ]; then
    mkdir -p "$root/.gradle"
    sdk_git clone --quiet --filter=blob:none "$repo" "$source"
fi
[ "$(git -C "$source" remote get-url origin)" = "$repo" ] || {
    echo "ERROR: SDK source origin mismatch." >&2; exit 1;
}
[ -z "$(sdk_git -C "$source" status --porcelain --untracked-files=no)" ] || {
    echo "ERROR: SDK source has local changes; refusing to overwrite." >&2; exit 1;
}
sdk_git -C "$source" fetch --quiet --depth 1 origin "$revision"
sdk_git -C "$source" checkout --quiet --detach "$revision"
[ "$(git -C "$source" rev-parse HEAD)" = "$revision" ] || {
    echo "ERROR: SDK source revision mismatch." >&2; exit 1;
}
[ -f "$source/settings.gradle.kts" ] || {
    echo "ERROR: pinned SDK source checkout is incomplete." >&2; exit 1;
}
chmod +x "$source/gradlew"
"$source/gradlew" -p "$source" --no-daemon --console=plain assembleDebug assembleRelease
mkdir -p "$root/app/libs"
for variant in debug release; do
    cp "$source/build/outputs/aar/weartak-plugin-sdk-$variant.aar" "$root/app/libs/"
done
echo "Built SDK source $revision (local derived inputs, not signed SDK releases)."
sha256sum "$root/app/libs/"*.aar