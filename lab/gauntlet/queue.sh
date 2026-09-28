#!/usr/bin/env bash
# Runs dev batches one after another: ./lab/gauntlet/queue.sh SEEDS "NAME|PLAYERS" ["NAME|PLAYERS" ...]
# Each batch uses 14 workers, so two queues fit side by side. Logs go to aisim/NAME.log. A batch refused because
# the sources changed after the last build (an edit in progress) is retried every minute for up to 20 minutes.
set -uo pipefail
cd "$(dirname "$0")/../.."
seeds=$1; shift
for job in "$@"; do
  name=${job%%|*}; players=${job#*|}
  for try in $(seq 1 20); do
    ./lab/gauntlet/dev.sh "$name" "$players" "$seeds" --workers 14 > "aisim/$name.log" 2>&1
    grep -q "changed after the last build" "aisim/$name.log" || break
    sleep 60
  done
done
