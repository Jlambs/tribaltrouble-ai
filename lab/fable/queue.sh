#!/usr/bin/env bash
# Runs dev batches one after another: ./lab/fable/queue.sh "NAME|PLAYERS|SEEDS[|EXTRA]" ...
# Several queues can run side by side; the harness shares the machine between their batches (--workers auto).
# A job whose run folder already exists is skipped. Logs go to aisim/NAME.log.
set -uo pipefail
cd "$(dirname "$0")/../.."
for job in "$@"; do
  IFS='|' read -r name players seeds extra <<< "$job"
  [ -d "aisim/runs/$name" ] && continue
  ./lab/fable/dev.sh "$name" "$players" "$seeds" ${extra:-} > "aisim/$name.log" 2>&1
done
