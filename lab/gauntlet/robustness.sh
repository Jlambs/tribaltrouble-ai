#!/usr/bin/env bash
# Robustness sweep of a gauntlet spec over lineups and maps the benchmark never plays: every map setting random
# (all sizes and terrains), our AI as natives, in team B, allied with Hard, in a free-for-all, 1v1 against itself.
# ./lab/gauntlet/robustness.sh SPEC TAG   (e.g. @g-shep shep) -> runs robust-TAG-*, then grep their summaries for
# failed games and swallowed errors.
set -uo pipefail
spec=$1; tag=$2
cd "$(dirname "$0")/../.."
run() { ./aisim.sh batch --name "robust-$tag-$1" --players "$2" --seeds "$3" --workers 7 ${4:-} > "aisim/robust-$tag-$1.log" 2>&1; }
run duel "$spec vs hard" 1..30 &
run natives "$spec/n vs hard*3" 31..50 &
run teamb "hard vs $spec" 51..70 &
run allied "$spec hard vs hard*3" 71..85 &
wait
run ffa "$spec vs hard vs normal vs easy" 86..95 "--rng 1" &
run mirror "$spec vs $spec" 96..105 "--rng 1" &
run medium6 "$spec vs hard/n*6" 106..125 "--size small,medium" &
run huge "$spec vs hard*4" 126..135 "--size huge --minutes 90" &
wait
for f in aisim/runs/robust-$tag-*/summary.txt; do echo "== $f"; grep -E "^games|^RESULT|swallowed" "$f" | cut -c1-200; done
