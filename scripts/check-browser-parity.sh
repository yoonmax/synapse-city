#!/usr/bin/env bash
# Runs the same simulations on the JVM and as compiled JavaScript in Node, and compares full state hashes.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/backend"
mvn -q -Pbrowser process-classes
status=0
for spec in "GRID 300 300 20261030" "GRID 900 300 7" "RADIAL 480 300 1309" "ORGANIC 440 300 4242"; do
  # shellcheck disable=SC2086
  jvm="$(java -cp target/classes com.synapsecity.simulation.scenarios.HeadlessRunner hash $spec)"
  # shellcheck disable=SC2086
  js="$(node -e "
    const m = {exports: {}};
    new Function('module', 'exports', require('fs').readFileSync('target/browser/synapse-sim.js', 'utf8'))(m, m.exports);
    m.exports.main(process.argv.slice(1));
  " hash $spec)"
  if [ "$jvm" = "$js" ]; then echo "match    $spec  $jvm"; else echo "DIFFER   $spec  jvm=$jvm js=$js"; status=1; fi
done
rm -rf target/classes/com/synapsecity/browser
exit $status
