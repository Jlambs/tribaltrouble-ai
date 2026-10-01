#!/usr/bin/env python3
"""Which AI counters moved between two runs: python lab/gauntlet/counters.py BASE ARM [--max-min 40] [--top 20]

Per counter of team A, the mean over games of counter per game minute (each game weighs the same; games longer than
--max-min game minutes are left out, so that a few multi-hour games do not decide), in both runs on their common
games, and the z of the difference (two-sample). A refactor that keeps behaviour moves no counter beyond |z| ~2 even
when every game diverges; a cadence change shows as a block of counters moving together (the game-tick conversion at
ludicrous: shepherd_t_* +12 %, the 0.56 s rounds back at 0.5 s).
"""
import json
import math
import os
import statistics
import sys

import gtime

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def rows(run):
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        return {r['key']: gtime.row(r) for r in map(json.loads, f) if r.get('result')}


def rates(games, max_min):
    games = [r for r in games if 0 < r['t'] <= max_min * 60]
    keys = set()
    for r in games:
        keys |= set(r['teams'][0].get('counters') or {})
    return {k: [(r['teams'][0].get('counters') or {}).get(k, 0) / (r['t'] / 60) for r in games] for k in keys}, len(games)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    max_min, top = 40.0, 20
    if '--max-min' in argv:
        max_min = float(argv[argv.index('--max-min') + 1])
    if '--top' in argv:
        top = int(argv[argv.index('--top') + 1])
    base, arm = rows(argv[0]), rows(argv[1])
    keys = sorted(set(base) & set(arm))
    pa, na = rates([base[k] for k in keys], max_min)
    pb, nb = rates([arm[k] for k in keys], max_min)
    print('%s -> %s: %d common games, %d / %d of them <= %g game min' % (argv[0], argv[1], len(keys), na, nb, max_min))
    out = []
    for k in set(pa) | set(pb):
        xa, xb = pa.get(k, [0.0] * na), pb.get(k, [0.0] * nb)
        ma, mb = statistics.mean(xa), statistics.mean(xb)
        if max(ma, mb) < .05:
            continue
        se = math.sqrt(statistics.pvariance(xa) / len(xa) + statistics.pvariance(xb) / len(xb)) or 1e-9
        out.append(((mb - ma) / se, k, ma, mb))
    out.sort(key=lambda x: -abs(x[0]))
    for z, k, ma, mb in out[:top]:
        print('  %-32s %10.3f -> %10.3f per game min  z %+5.1f' % (k, ma, mb, z))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
