#!/usr/bin/env bash
# Runs dev batches one after another: ./lab/gauntlet/queue.sh SEEDS "NAME|PLAYERS" ["NAME|PLAYERS" ...]
# Each batch uses $WORKERS workers (default 14; use 7 for more than 12 players), so two queues fit side by side.
# Extra aisim args (e.g. --logs) go in $EXTRA. Logs go to aisim/NAME.log. A batch refused because
# the sources changed after the last build (an edit in progress) is retried every minute for up to 20 minutes.
set -uo pipefail
cd "$(dirname "$0")/../.."
seeds=$1; shift
for job in "$@"; do
  name=${job%%|*}; players=${job#*|}
  for try in $(seq 1 20); do
    ./lab/gauntlet/dev.sh "$name" "$players" "$seeds" --workers "${WORKERS:-14}" ${EXTRA:-} > "aisim/$name.log" 2>&1
    grep -q "changed after the last build" "aisim/$name.log" || break
    sleep 60
  done
done
