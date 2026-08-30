#!/usr/bin/env bash
#
# Pin the app to a published duocb release: download that tag's
# libduocb-android.zip, compute its sha256, and rewrite gradle.properties
# (duocb.releaseTag, duocb.releaseSha256, duocb.versionName from the tag's
# numeric part; duocb.versionCode is bumped by one).
#
# Usage: scripts/bump-jnilibs.sh v0.0.44
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

tag="${1:-}"
if [ -z "$tag" ]; then
  echo "usage: $0 <release tag, e.g. v0.0.44>" >&2
  exit 1
fi
url="https://github.com/andrewtheguy/duocb/releases/download/$tag/libduocb-android.zip"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

echo "Downloading $url"
curl -fsSL -o "$tmp" "$url"
if command -v sha256sum >/dev/null 2>&1; then
  sha="$(sha256sum "$tmp" | cut -d' ' -f1)"
else
  sha="$(shasum -a 256 "$tmp" | cut -d' ' -f1)"   # macOS
fi
version="${tag#v}"
code="$(sed -n 's/^duocb.versionCode=\([0-9]*\)$/\1/p' gradle.properties)"
code=$((${code:-0} + 1))

# -i.bak works on both GNU and BSD sed (bare -i does not).
sed -i.bak \
  -e "s|^duocb.releaseTag=.*|duocb.releaseTag=$tag|" \
  -e "s|^duocb.releaseSha256=.*|duocb.releaseSha256=$sha|" \
  -e "s|^duocb.versionName=.*|duocb.versionName=$version|" \
  -e "s|^duocb.versionCode=.*|duocb.versionCode=$code|" \
  gradle.properties
rm -f gradle.properties.bak

echo "Pinned $tag (sha256 $sha), versionName $version, versionCode $code"
grep '^duocb\.' gradle.properties
