#!/usr/bin/env bash
# Builds the static site: the Java simulation compiled to JavaScript plus the client, in frontend/dist-pages.
# Set PAGES_BASE for a different hosting path (default /synapse-city/).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE="${PAGES_BASE:-/synapse-city/}"

cd "$ROOT/backend"
mvn -q -Pbrowser process-classes
cp target/browser/synapse-sim.js "$ROOT/frontend/public/synapse-sim.js"
# The browser entry point needs TeaVM on the classpath; keep it out of the server jar.
rm -rf target/classes/com/synapsecity/browser

cd "$ROOT/frontend"
[ -d node_modules ] || npm ci --no-audit --no-fund
npx tsc -b --noEmit
VITE_SYNAPSE_TRANSPORT=worker SYNAPSE_BASE="$BASE" npx vite build --outDir dist-pages --emptyOutDir
touch dist-pages/.nojekyll
echo "Built $ROOT/frontend/dist-pages for base $BASE"
