#!/bin/bash
# Runs lab/corsair/eval.sh for a list of "NAME:PARAMS" variants of a frozen tag against an opponent, N at a time.
# usage: lab/corsair/sweep.sh TAG OPPONENT PARALLEL FORMATS NAME:PARAMS [NAME:PARAMS...]
tag=$1; opp=$2; par=$3; formats=$4; shift 4
for v in "$@"; do
  n=${v%%:*}; p=${v#*:}
  while [ $(jobs -r | wc -l) -ge $par ]; do sleep 10; done
  lab/corsair/eval.sh "$n" "@$tag:$p" "$opp" "$formats" > "../eval-$n.log" 2>&1 &
  sleep 20
done
wait
