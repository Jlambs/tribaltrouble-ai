#!/usr/bin/env python3
"""Our units killed by a copy's start in the opening: python lab/fable/waylaid.py [--until 360] [--cells 30] RUN...

The stock AI sends its peons at any unit within 30 m of its first quarters or armory, and builders walking past do not
fight back. Per game, from the game file's deaths (no logs needed): our units lost before --until seconds within
--cells cells of a copy's start and more than 40 cells from ours. Prints the games by that count with their wins, and
with --maps the games with 6 and more: the seed, the count, the copy, where the first of them fell and the result.
"""
import argparse
import glob
import json
import math
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
HOME_CELLS = 40
BANDS = ((0, 0), (1, 5), (6, 11), (12, 999))


def games(run, until, cells):
    results = {}
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        for line in f:
            if line.strip():
                r = json.loads(line)
                if r.get('result') is not None:
                    results[r['key']] = r
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', '*.jsonl'))):
        key = os.path.basename(path)[:-len('.jsonl')]
        if key not in results:
            continue
        side = results[key].get('side', 0)
        starts = {}
        count = 0
        by = {}
        first = None
        with open(path, encoding='utf-8') as f:
            for line in f:
                if '"ev": "game"' in line or '"ev":"game"' in line:
                    for p in json.loads(line)['players']:
                        starts[p['s']] = (p['x'], p['y'])
                elif '"deaths"' in line:
                    e = json.loads(line)
                    if e['t'] >= until:
                        break
                    if e['s'] != side:
                        continue
                    ox, oy = starts[side]
                    if math.hypot(e['x'] - ox, e['y'] - oy) <= HOME_CELLS:
                        continue
                    near = min((math.hypot(e['x'] - x, e['y'] - y), s) for s, (x, y) in starts.items() if s != side)
                    if near[0] <= cells:
                        count += e['n']
                        by[near[1]] = by.get(near[1], 0) + e['n']
                        if first is None:
                            first = (e['t'], e['x'], e['y'])
        yield results[key], count, by, first


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('runs', nargs='+')
    ap.add_argument('--until', type=float, default=360)
    ap.add_argument('--cells', type=float, default=30)
    ap.add_argument('--maps', action='store_true')
    args = ap.parse_args()
    bands = {b: [0, 0] for b in BANDS}
    listed = []
    for run in args.runs:
        for r, count, by, first in games(run, args.until, args.cells):
            for b in BANDS:
                if b[0] <= count <= b[1]:
                    bands[b][0] += 1
                    bands[b][1] += r['result'] == 'win'
            if count >= 6:
                listed.append((run, r, count, by, first))
    total = sum(v[0] for v in bands.values())
    print(f'{total} games; our units lost before {args.until:.0f} s within {args.cells:.0f} cells of a copy\'s start:')
    for b in BANDS:
        n, w = bands[b]
        name = '0' if b == (0, 0) else f'{b[0]}-{b[1]}' if b[1] < 999 else f'{b[0]} and more'
        print(f'  {name:12s} {n:5d} games, won {w:5d} = {100.0 * w / n if n else 0:5.1f} %')
    if args.maps:
        for run, r, count, by, first in listed:
            copy = max(by, key=by.get)
            print(f'  {run} s{r["seed"]} side {r.get("side", 0)}: {count} lost, {by[copy]} by s{copy}, first at '
                  f'{first[0]:.0f} s at {first[1]},{first[2]}; {r["result"]} after {r["t"] / 60:.0f} min')


if __name__ == '__main__':
    main()
