#!/usr/bin/env bash
set -euo pipefail

# Build a single concatenated bundle of all statecharts source files
# for use with Scittle. Files must be in dependency order.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$PROJECT_ROOT/src/statecharts"
OUTPUT="$PROJECT_ROOT/dist/statecharts-bundle.cljc"

cat \
  "$SRC/utils.cljc" \
  "$SRC/clock.cljc" \
  "$SRC/delayed.cljc" \
  "$SRC/impl.cljc" \
  "$SRC/store.cljc" \
  "$SRC/scheduler.cljc" \
  "$SRC/sim.cljc" \
  "$SRC/service.cljc" \
  "$SRC/core.cljc" \
  "$SRC/viz.cljc" \
  > "$OUTPUT"

echo "Bundle written to dist/statecharts-bundle.cljc ($(wc -l < "$OUTPUT") lines)"
