#!/usr/bin/env bash
# Captures README screenshots from the running app with headless Chrome (GPU rendering).
# Prerequisites: backend on :8080 and frontend dev server on :5173.
set -euo pipefail
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
BASE="${BASE:-http://localhost:5173}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/docs/screenshots"
mkdir -p "$OUT"

shot() { # name, query, settle-ms
  # A fresh profile per shot keeps each capture in its own browser instance.
  local profile; profile="$(mktemp -d)"
  "$CHROME" --headless=new --user-data-dir="$profile" --no-first-run --hide-scrollbars \
    --window-size=1600,1000 --force-device-scale-factor=1 --use-angle=metal --ignore-gpu-blocklist \
    --virtual-time-budget="$3" --screenshot="$OUT/$1.png" "$BASE/?$2" >/dev/null 2>&1 &
  local pid=$!
  sleep $(( $3 / 1000 + 25 ))
  kill "$pid" 2>/dev/null || true
  rm -rf "$profile"
  echo "captured $OUT/$1.png"
}

shot overview "intro=skip" 30000
shot cinematic "intro=skip&panels=0" 20000
shot downtown "intro=skip&panels=0&cam=0,0,3.2" 20000
shot heatmap "intro=skip&panels=0&heat=1" 20000
shot intro "" 2600
