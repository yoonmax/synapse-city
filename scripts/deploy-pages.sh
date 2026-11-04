#!/usr/bin/env bash
# Builds the static site and publishes it to the gh-pages branch of origin. The branch holds only build output.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/build-pages.sh"

ORIGIN="$(git -C "$ROOT" remote get-url origin)"
NAME="$(git -C "$ROOT" config user.name)"
EMAIL="$(git -C "$ROOT" config user.email)"
SOURCE="$(git -C "$ROOT" describe --always --dirty)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cp -R "$ROOT/frontend/dist-pages/." "$TMP/"
cd "$TMP"
git init -q -b gh-pages
git add -A
git -c user.name="$NAME" -c user.email="$EMAIL" commit -q -m "Deploy site from $SOURCE"
git push -q --force "$ORIGIN" gh-pages
echo "Published to the gh-pages branch of $ORIGIN"
