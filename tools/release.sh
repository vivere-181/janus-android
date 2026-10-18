#!/bin/bash
# Makes a Janus release: sets the version, builds and checks the signed APK, commits and tags. With --publish it also
# pushes and creates the GitHub release (needs the GitHub CLI, `gh`). The signing key stays on this computer.
#
#   tools/release.sh <version> [--publish] [--notes <file>]      e.g. tools/release.sh 1.0.0
#
# Version codes are 100000 + major·10000 + minor·100 + patch (1.0.0 → 110000), above the KDE Connect codes earlier
# builds had, so they install over them.
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:?usage: tools/release.sh <version> [--publish] [--notes <file>]}"; shift
publish=false; notes=""
while [ $# -gt 0 ]; do
  case "$1" in
    --publish) publish=true ;;
    --notes) notes="${2:?--notes needs a file}"; shift ;;
    *) echo "unknown option $1" >&2; exit 1 ;;
  esac
  shift
done
[[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { echo "version must look like 1.2.3" >&2; exit 1; }
code=$((100000 + BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))
[ -f keystore.properties ] || { echo "keystore.properties is missing: releases must be signed with the Janus key" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "commit or stash your changes first" >&2; exit 1; }
git rev-parse -q --verify "refs/tags/v$version" >/dev/null && { echo "v$version already exists" >&2; exit 1; }

sed -i '' -E "s/versionCode = [0-9]+/versionCode = $code/; s/versionName = \"[^\"]+\"/versionName = \"$version\"/" build.gradle.kts
./gradlew -q testDebugUnitTest assembleRelease

apk=build/outputs/apk/release/janus-android-release.apk
sdk="$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null || true)"; sdk="${sdk:-${ANDROID_HOME:-}}"
apksigner="$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
if [ -n "$apksigner" ]; then
  "$apksigner" verify --print-certs "$apk" | grep -i "SHA-256" | head -1
else
  echo "(apksigner not found: signature not checked)"
fi
mkdir -p build/release
out="build/release/Janus-$version.apk"
cp "$apk" "$out"
git commit -qam "Janus $version"
git tag -a "v$version" -m "Janus $version"
echo "Built $out ($(du -h "$out" | cut -f1)), committed and tagged v$version."

if $publish; then
  git push -q origin HEAD "v$version"
  gh release create "v$version" "$out" --title "Janus $version" ${notes:+--notes-file "$notes"} ${notes:---generate-notes}
else
  echo "To publish: git push origin HEAD v$version && gh release create v$version $out --title \"Janus $version\" --generate-notes"
fi
