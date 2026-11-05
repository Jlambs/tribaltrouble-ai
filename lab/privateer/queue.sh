#!/bin/bash
# Runs lab/privateer/eval.sh for "NAME:PARAMS" variants of a frozen tag, one after another (a batch already uses
# every core). usage: lab/privateer/queue.sh TAG OPPONENT FORMATS NAME:PARAMS [NAME:PARAMS...]
tag=$1; opp=$2; formats=$3; shift 3
for v in "$@"; do
  n=${v%%:*}; p=${v#*:}
  spec="@$tag"; [ -n "$p" ] && spec="@$tag:$p"
  echo "== $n ($spec vs $opp)"
  lab/privateer/eval.sh "$n" "$spec" "$opp" "$formats"
done
