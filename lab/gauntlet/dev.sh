#!/usr/bin/env bash
# Benchmark-map batch: ./lab/gauntlet/dev.sh NAME "PLAYERS" SEEDS [extra aisim args...]
# The exam's map settings (large tropical, hills 0..2, trees 10, supplies 10, our AI in slot 0).
# Dev seeds only (1..29999); the exam seeds 30001..30100 are refused here.
set -euo pipefail
name=$1; players=$2; seeds=$3; shift 3
case "$seeds" in *300[0-9][0-9]*|*30100*) echo "refusing exam seeds: $seeds" >&2; exit 2;; esac
cd "$(dirname "$0")/../.."
exec ./aisim.sh batch --name "$name" --players "$players" --size large --terrain tropical --hills 0..2 \
  --trees 10 --supplies 10 --seeds "$seeds" --side 0 "$@"
