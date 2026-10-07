#!/bin/bash
# A candidate against the sparring league, 1v1 on seeds 1..30 (60 games each) and 2v2 on seeds 1..15:
# the last release, it without the drill (a stand-in for AIs that do not exploit ships), the stock Hard, and the
# blitz, turtle and raid styles. usage: lab/corsair/league.sh PREFIX "A-SPEC" [RELEASE_TAG] [STYLE_TAG]
p=$1; a=$2; rel=${3:-@v4}; sty=${4:-@p12}
common="--size archipelago --terrain tropical --hills 0 --trees 10 --supplies 10 --minutes 60 --speed ludicrous --rng 1 --stale-ok"
run() { n=$1; shift; [ -f aisim/runs/$n/summary.txt ] && return; rm -rf aisim/runs/$n; ./aisim.sh batch --name $n "$@" $common 2>&1 | grep -E "^RESULT|failed|swallowed errors [1-9]"; }
for o in "rel:$rel" "nodrill:$rel:ratchet=false" "hard:hard" "blitz:$sty:style=blitz" "turtle:$sty:style=turtle" "raid:$sty:raid=7"; do
  tag=${o%%:*}; opp=${o#*:}
  run "$p-$tag-1v1" --players "$a vs $opp" --seeds 1..30
  run "$p-$tag-2v2" --players "$a*2 vs $opp*2" --seeds 1..15
done
