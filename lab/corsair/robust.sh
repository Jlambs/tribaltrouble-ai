#!/bin/bash
# Robustness of a team-A spec on odd settings: failed games and swallowed errors matter most here.
# usage: lab/corsair/robust.sh PREFIX "A-SPEC" OPPONENT
p=$1; a=$2; b=$3
base="--size archipelago --terrain tropical --trees 10 --supplies 10 --speed ludicrous --minutes 60 --rng 1 --stale-ok"
run() { n=$1; shift; [ -f aisim/runs/$n/summary.txt ] && return; rm -rf aisim/runs/$n; ./aisim.sh batch --name $n "$@" 2>&1 | grep -E "^RESULT|failed|swallowed errors [1-9]|crash"; }
run $p-natives  --players "$a/n vs $b" --hills 0 --seeds 1..20 $base
run $p-vsnat    --players "$a vs $b/n" --hills 0 --seeds 1..20 $base
run $p-ffa3     --players "$a vs $b vs $b" --hills 0 --seeds 1..10 $base
run $p-4v4      --players "$a*4 vs $b*4" --hills 0 --seeds 1..5 $base
run $p-2v3      --players "$a*2 vs $b*3" --hills 0 --seeds 1..8 $base
run $p-hills3   --players "$a vs $b" --hills 3 --seeds 1..20 $base
run $p-normal   --players "$a vs $b" --hills 0 --seeds 1..10 --size archipelago --terrain tropical --trees 10 --supplies 10 --minutes 40 --rng 1
run $p-northern --players "$a vs $b" --hills 0 --seeds 1..10 --size archipelago --terrain northern --trees 10 --supplies 10 --speed ludicrous --minutes 60 --rng 1
