#!/usr/bin/env bash
# Build the current working tree, including uncommitted source edits.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

# Remove only the two known APK outputs so a previous signing mode cannot leave
# a stale APK beside this build. Gradle will regenerate its missing output.
apk_dir=app/build/outputs/apk/release
rm -f -- "$apk_dir/app-release.apk" "$apk_dir/app-release-unsigned.apk"
./gradlew --no-daemon :app:assembleRelease "$@"

if [[ -f "$apk_dir/app-release.apk" ]]; then
    printf '\nSigned release APK: %s/%s/app-release.apk\n' "$PWD" "$apk_dir"
elif [[ -f "$apk_dir/app-release-unsigned.apk" ]]; then
    printf '\nUnsigned release APK: %s/%s/app-release-unsigned.apk\n' "$PWD" "$apk_dir"
    printf 'Signing is required before installation. See docs/building.md.\n'
else
    printf 'Build finished without the expected release APK in %s\n' "$apk_dir" >&2
    exit 1
fi
