#!/usr/bin/env python3
"""Two any-seat ladders from seat 0, map by map: python lab/fable/seat0.py BASE VAR [--n 1..16]

The ladders of anyseat.py play every map from seat 0 first (runs as-NAME-vsN-s0), so two ladders of the same seeds
are a paired comparison of their specs over 100 maps a rung. Per rung: the wins of each, the maps only one of them
wins, the identical games, the swallowed errors of VAR and its draws; then the totals with the sign test's z.
"""
import argparse
import json
import math
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def rows(run):
    path = os.path.join(ROOT, run, 'results.jsonl')
    if not os.path.exists(path):
        return None
    out = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.strip():
                r = json.loads(line)
                if r.get('result') is not None:
                    out[r['seed']] = r
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('base')
    ap.add_argument('var')
    ap.add_argument('--n', default='1..16')
    args = ap.parse_args()
    lo, hi = (int(x) for x in args.n.split('..'))
    up = down = games = 0
    for n in range(lo, hi + 1):
        a = rows(f'as-{args.base}-vs{n}-s0')
        b = rows(f'as-{args.var}-vs{n}-s0')
        if a is None or b is None:
            continue
        common = [s for s in a if s in b]
        if not common:
            continue
        u = sum(a[s]['result'] != 'win' and b[s]['result'] == 'win' for s in common)
        d = sum(a[s]['result'] == 'win' and b[s]['result'] != 'win' for s in common)
        same = sum(a[s]['checksum'] == b[s]['checksum'] for s in common)
        print(f'N={n:2d}: {sum(a[s]["result"] == "win" for s in common):3d} -> '
              f'{sum(b[s]["result"] == "win" for s in common):3d} of {len(common)} (+{u} -{d}), {same} identical, '
              f'errors {sum(b[s]["teams"][0].get("errors", 0) for s in common)}, '
              f'draws {sum(b[s]["result"] == "draw" for s in common)}')
        up += u
        down += d
        games += len(common)
    z = (up - down) / math.sqrt(up + down) if up + down else 0.0
    print(f'{args.base} -> {args.var}: +{up} -{down} over {games} games (z {z:+.1f})')


if __name__ == '__main__':
    main()
