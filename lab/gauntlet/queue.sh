#!/usr/bin/env bash
# Runs dev batches one after another: ./lab/gauntlet/queue.sh SEEDS "NAME|PLAYERS" ["NAME|PLAYERS" ...]
# Each batch uses 14 workers, so two queues fit side by side. Logs go to aisim/NAME.log.
set -uo pipefail
cd "$(dirname "$0")/../.."
seeds=$1; shift
for job in "$@"; do
  name=${job%%|*}; players=${job#*|}
  ./lab/gauntlet/dev.sh "$name" "$players" "$seeds" --workers 14 > "aisim/$name.log" 2>&1
done
