#!/usr/bin/env bash
# One line per paired comparison: ./lab/gauntlet/pairs.sh "BASE ARM" ["BASE ARM" ...]
# Each line: games in common, wins base -> arm, and diff and z of elim, surv60, alive40, arm25, towers20 and wp from
# winproxy.py --pair. For a sweep of many arms against one base, board.py reads each arm against the median arm too.
cd "$(dirname "$0")/../.." || exit 1
for p in "$@"; do
  set -- $p
  python - "$1" "$2" <<'PY'
import json, subprocess, sys
a, b = sys.argv[1], sys.argv[2]
def rows(r):
    try:
        return {x['key']: x for x in map(json.loads, open(f'aisim/runs/{r}/results.jsonl'))}
    except FileNotFoundError:
        return {}
ra, rb = rows(a), rows(b)
k = sorted(set(ra) & set(rb))
if not k:
    print(f'{b:30s} (no common games with {a})')
    sys.exit()
wa = sum(ra[x].get('result') == 'win' for x in k)
wb = sum(rb[x].get('result') == 'win' for x in k)
out = subprocess.run([sys.executable, 'lab/gauntlet/winproxy.py', '--pair', a, b], capture_output=True,
                     text=True).stdout.splitlines()
d = {}
for l in out:
    f = l.split()
    # metric, base mean, arm mean, diff, se, z
    if len(f) >= 6 and f[0] in ('elim', 'surv60', 'alive40', 'towers20', 'wp', 'arm25'):
        d[f[0]] = f'{f[3]} z{f[5]}'
print(f'{b:30s} vs {a:22s} n={len(k):3d} W {wa}->{wb} | elim {d.get("elim")} | surv60 {d.get("surv60")} | '
      f'alive40 {d.get("alive40")} | arm25 {d.get("arm25")} | towers20 {d.get("towers20")} | wp {d.get("wp")}')
PY
done
