#!/usr/bin/env bash
# Benchmark batch: ./lab/fable/dev.sh NAME "PLAYERS" SEEDS [extra aisim args...]
# The benchmark (user, 2026-11-15, the same as Gauntlet's): large tropical, hills 0, trees 10, supplies 10,
# ludicrous speed, vikings everywhere, our AI in slot 0. A game ends as soon as team A is out.
# Seeds: dev 21001..21999 (tuning), exam 36001..36999 (never tuned on; refused here unless EXAM=1).
set -euo pipefail
name=$1; players=$2; seeds=$3; shift 3
if [ "${EXAM:-0}" != 1 ]; then
  case "$seeds" in *36[0-9][0-9][0-9]*) echo "refusing exam seeds without EXAM=1: $seeds" >&2; exit 2;; esac
fi
cd "$(dirname "$0")/../.."
exec ./aisim.sh batch --name "$name" --players "$players" --size large --terrain tropical --hills 0 \
  --trees 10 --supplies 10 --speed ludicrous --seeds "$seeds" --side 0 --stop-when-a-out "$@"
