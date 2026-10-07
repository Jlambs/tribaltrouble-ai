#!/bin/bash
# Paired comparisons of finished screens against their bases: lab/corsair/collect.sh BASE VAR [VAR...]
# Each name is an eval.sh prefix (formats found for both are compared), or a whole run name.
base=$1; shift
cmp() { # BASE_RUN VAR_RUN LABEL
  r=$(grep -o 'W[0-9]* L[0-9]* D[0-9]*' aisim/runs/$2/summary.txt | head -1)
  ./aisim.sh compare $1 $2 --force 2>&1 | awk -v n="$3 ($r)" '/^(score|elim|kd30|w15|margin) /{printf "%s %s %s %s %s%s", (NR==0?"":""), $1, $4, $5, $6, ($7=="*"?"* ":"  ")} END{print "  <- " n}'
}
for v in "$@"; do
  if [ -f aisim/runs/$base/summary.txt ] && [ -f aisim/runs/$v/summary.txt ]; then
    cmp $base $v $v
    continue
  fi
  for f in 1v1 2v2 1v2 2v1 3v3 2v3 3v2; do
    [ -f aisim/runs/$base-$f/summary.txt ] && [ -f aisim/runs/$v-$f/summary.txt ] || continue
    cmp $base-$f $v-$f "$v $f"
  done
done
