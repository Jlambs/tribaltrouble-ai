#!/usr/bin/env bash
# Keeps K dev batches running at once: ./lab/gauntlet/pool.sh K JOBFILE
# JOBFILE lines: NAME|PLAYERS|SEEDS[|EXTRA AISIM ARGS]  (blank lines and lines starting with # are skipped).
# Batches use --workers auto (the harness splits the machine between them), so a new batch picks up the threads an
# old batch's tail frees. Lines appended to JOBFILE while the pool runs are picked up; a job whose run already has a
# summary is skipped, and one whose run folder exists without a summary is waited for (never deleted). A batch refused because the sources changed after the last build is retried a minute later.
# Logs go to aisim/NAME.log. The pool ends when every job has run.
set -uo pipefail
cd "$(dirname "$0")/../.."
k=$1; jobfile=$2
declare -A started=()
declare -A pids=()
retry_at() { echo $(( $(date +%s) + 60 )); }
declare -A wait_until=()
while true; do
  # reap finished batches; requeue refusals
  for name in "${!pids[@]}"; do
    if ! kill -0 "${pids[$name]}" 2>/dev/null; then
      unset "pids[$name]"
      if grep -q "changed after the last build" "aisim/$name.log" 2>/dev/null; then
        unset "started[$name]"
        wait_until[$name]=$(retry_at)
      fi
    fi
  done
  pending=0
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    [ -z "$line" ] && continue
    case "$line" in \#*) continue;; esac
    IFS='|' read -r name players seeds extra <<< "$line"
    [ -n "${started[$name]:-}" ] && continue
    if [ -f "aisim/runs/$name/summary.txt" ]; then started[$name]=done; continue; fi
    # a run folder without a summary is a batch still running elsewhere (or a dead one to clean up by hand): wait
    if [ -d "aisim/runs/$name" ]; then pending=1; continue; fi
    if [ -n "${wait_until[$name]:-}" ] && [ "$(date +%s)" -lt "${wait_until[$name]}" ]; then pending=1; continue; fi
    pending=1
    if [ "${#pids[@]}" -lt "$k" ]; then
      ./lab/gauntlet/dev.sh "$name" "$players" "$seeds" ${extra:-} > "aisim/$name.log" 2>&1 &
      pids[$name]=$!
      started[$name]=1
    fi
  done < "$jobfile"
  if [ "$pending" -eq 0 ] && [ "${#pids[@]}" -eq 0 ]; then
    break
  fi
  sleep 20
done
