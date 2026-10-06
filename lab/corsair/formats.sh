#!/bin/bash
# Plays team A against a frozen opponent in several team formats, one batch after the other.
# usage: lab/corsair/formats.sh PREFIX "A-SPEC" "B-SPEC" [SEEDS] [extra batch args...]
# e.g.   lab/corsair/formats.sh v3 corsair @v2 1..15
prefix=$1; a=$2; b=$3; seeds=${4:-1..15}; shift 4
common="--size archipelago --terrain tropical --hills 0 --trees 10 --supplies 10 --minutes 60 --speed ludicrous"
for f in "2v2:$a*2 vs $b*2" "1v2:$a vs $b*2" "2v1:$a*2 vs $b" "3v3:$a*3 vs $b*3"; do
  name="$prefix-${f%%:*}"
  players="${f#*:}"
  [ -d "aisim/runs/$name" ] && continue
  ./aisim.sh batch --name "$name" --players "$players" --seeds "$seeds" $common "$@" 2>&1 | grep -E "^RESULT|failed|swallowed"
done
