#!/bin/bash
# Standard evaluation of a team-A spec against a frozen opponent: 1v1 on seeds 1..50 and 2v2 on seeds 1..25
# (100 games each), archipelago 0/10/10 at ludicrous, 60 min, --rng 1 (so a mirror of the opponent is a valid base).
# usage: lab/corsair/eval.sh NAME "A-SPEC" OPPONENT [formats: 1v1,2v2,1v2,2v1,3v3]
name=$1; a=$2; b=$3; formats=${4:-1v1,2v2}
common="--size archipelago --terrain tropical --hills 0 --trees 10 --supplies 10 --minutes 60 --speed ludicrous --rng 1"
# frozen tags only: a source edit after the last build does not change them (and must not stall a queued sweep)
[[ "$a" == @* && "$b" != corsair* ]] && common="$common --stale-ok"
for f in ${formats//,/ }; do
  case $f in
    1v1) players="$a vs $b"; seeds=1..50 ;;
    2v2) players="$a*2 vs $b*2"; seeds=1..25 ;;
    1v2) players="$a vs $b*2"; seeds=1..20 ;;
    2v1) players="$a*2 vs $b"; seeds=1..20 ;;
    3v3) players="$a*3 vs $b*3"; seeds=1..15 ;;
    2v3) players="$a*2 vs $b*3"; seeds=1..12 ;;
    3v2) players="$a*3 vs $b*2"; seeds=1..12 ;;
  esac
  run="$name-$f"
  [ ${#run} -gt 40 ] && { echo "run name too long: $run"; exit 2; }
  [ -f "aisim/runs/$run/summary.txt" ] && continue
  rm -rf "aisim/runs/$run"
  ./aisim.sh batch --name "$run" --players "$players" --seeds "$seeds" $common 2>&1 | grep -E "^RESULT|failed|swallowed errors [1-9]"
done
