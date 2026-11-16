#!/usr/bin/env python3
"""What the pace of the campaign is worth: python lab/fable/pace.py RUN...

Over all games of the runs: the share won by the number of copies that still have a quarters or an armory at minute
20, and then, by minute, those copies and both sides' warriors in the games won and in the others.
"""
import collections
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
MINUTES = [6, 8, 10, 12, 14, 16, 18, 20, 22, 25, 30]


def main(runs):
    by_housed = collections.defaultdict(lambda: [0, 0])
    acc = {True: collections.defaultdict(list), False: collections.defaultdict(list)}
    for run in runs:
        won = {}
        for line in open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8'):
            if line.strip():
                r = json.loads(line)
                won[r['key']] = r['result'] == 'win'
        for path in glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl')):
            key = os.path.basename(path)[:-6]
            if key not in won:
                continue
            housed = collections.Counter()
            ours = {}
            foes = collections.Counter()
            for line in open(path, encoding='utf-8'):
                if '"census"' not in line:
                    continue
                d = json.loads(line)
                m = d['t'] / 60.0
                if abs(m - round(m)) > 0.02 or int(round(m)) not in MINUTES:
                    continue
                m = int(round(m))
                war = d['rock'] + d['iron'] + d['rubber']
                if d['s'] == 0:
                    ours[m] = war
                else:
                    foes[m] += war
                    if d.get('alive') and (d['quarters'] or d['armories']):
                        housed[m] += 1
            if 20 in ours:
                by_housed[housed[20]][0] += won[key]
                by_housed[housed[20]][1] += 1
            else:
                by_housed['out by 20'][1] += 1
            for m in MINUTES:
                if m in ours:
                    acc[won[key]][m].append((housed[m], ours[m], foes[m]))
    print('copies housed at minute 20: games, share won')
    for k in sorted(by_housed, key=lambda k: (isinstance(k, str), k)):
        w, n = by_housed[k]
        print(f'  {k!s:<10} {n:4d}  {100.0 * w / max(1, n):5.1f} %')
    print('minute | copies housed, won / not | our warriors | enemy warriors | games')
    for m in MINUTES:
        a, b = acc[True][m], acc[False][m]
        if not a or not b:
            continue

        def mean(rows, i):
            return sum(r[i] for r in rows) / len(rows)

        print(f'  {m:3d}  | {mean(a, 0):5.2f} / {mean(b, 0):5.2f}           | {mean(a, 1):4.0f} / {mean(b, 1):4.0f}  | '
              f'{mean(a, 2):4.0f} / {mean(b, 2):4.0f}    | {len(a)} / {len(b)}')


if __name__ == '__main__':
    main(sys.argv[1:])
