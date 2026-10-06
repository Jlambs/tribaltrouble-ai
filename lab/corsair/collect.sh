#!/bin/bash
# Paired comparisons of finished screens against their bases: lab/corsair/collect.sh BASE VAR [VAR...]
# Each name is an eval.sh prefix; formats found for both are compared.
base=$1; shift
for v in "$@"; do
  for f in 1v1 2v2 1v2 2v1 3v3; do
    [ -f aisim/runs/$base-$f/summary.txt ] && [ -f aisim/runs/$v-$f/summary.txt ] || continue
    r=$(grep -o 'W[0-9]* L[0-9]* D[0-9]*' aisim/runs/$v-$f/summary.txt | head -1)
    ./aisim.sh compare $base-$f $v-$f --force 2>&1 | awk -v n="$v $f ($r)" '/^(score|elim|kd30|w15|margin) /{printf "%s %s %s %s %s%s", (NR==0?"":""), $1, $4, $5, $6, ($7=="*"?"* ":"  ")} END{print "  <- " n}'
  done
done
