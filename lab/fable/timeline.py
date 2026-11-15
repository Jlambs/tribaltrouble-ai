#!/usr/bin/env python3
"""Won and lost games side by side, minute by minute: python lab/fable/timeline.py RUN [RUN...]

From the 30-s census of every recorded game (aisim/runs/RUN/g/*.jsonl): our warriors, peons outside, units inside,
towers, quarters, armories, iron harvested so far, kills and losses so far; the copies still alive, those with a
quarters or an armory ("homed"), and their warriors in all. Means over the games still running at that minute.
"""
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
MINUTES = [3, 5, 6, 8, 10, 12, 15, 18, 20, 25, 30, 40]
COLS = ['n', 'war', 'peon', 'in', 'tow', 'qu', 'arm', 'iron', 'rock', 'kills', 'lost', 'alive', 'homed', 'ewar',
        'eunits', 'etow']


def game(path):
    """minute -> row, from the census nearest to each minute."""
    census = {}
    result = None
    for line in open(path, encoding='utf-8'):
        d = json.loads(line)
        if d['ev'] == 'census':
            census.setdefault(round(d['t'] / 30.0) * 30, {})[d['s']] = d
        elif d['ev'] == 'end':
            result = d
    rows = {}
    for m in MINUTES:
        c = census.get(m * 60)
        if not c or 0 not in c or not c[0]['alive']:
            continue
        me = c[0]
        foes = [v for s, v in c.items() if s != 0 and v['alive']]
        rows[m] = {
            'n': 1,
            'war': me['rock'] + me['iron'] + me['rubber'],
            'peon': me['peons'],
            'in': me['inside'],
            'tow': me['towers'],
            'qu': me['quarters'],
            'arm': me['armories'],
            'iron': me['harvestedIron'],
            'rock': me['harvestedRock'],
            'kills': me['kills'],
            'lost': me['lost'],
            'alive': len(foes),
            'homed': sum(1 for v in foes if v['quarters'] + v['armories'] > 0),
            'ewar': sum(v['rock'] + v['iron'] + v['rubber'] for v in foes),
            'eunits': sum(v['units'] for v in foes),
            'etow': sum(v['towers'] for v in foes),
        }
    return rows, result


def main(runs):
    for run in runs:
        won = {}
        for line in open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8'):
            if line.strip():
                r = json.loads(line)
                won[r['key']] = r['result'] == 'win'
        groups = {True: {}, False: {}}
        counts = {True: 0, False: 0}
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*.jsonl'))):
            key = os.path.basename(path)[:-6]
            if key not in won:
                continue
            rows, _ = game(path)
            counts[won[key]] += 1
            for m, row in rows.items():
                acc = groups[won[key]].setdefault(m, dict.fromkeys(COLS, 0.0))
                for k in COLS:
                    acc[k] += row[k]
        print(f'== {run}: {counts[True]} won, {counts[False]} lost')
        print('min  ' + ''.join(f'{c:>13}' for c in COLS))
        for m in MINUTES:
            cells = []
            for c in COLS:
                parts = []
                for w in (True, False):
                    acc = groups[w].get(m)
                    if not acc or not acc['n']:
                        parts.append('-')
                    elif c == 'n':
                        parts.append(f"{acc['n']:.0f}")
                    else:
                        parts.append(f"{acc[c] / acc['n']:.0f}" if c not in ('tow', 'qu', 'arm', 'alive', 'homed')
                                     else f"{acc[c] / acc['n']:.1f}")
                cells.append(f"{parts[0] + '/' + parts[1]:>13}")
            print(f'{m:>3}  ' + ''.join(cells))


if __name__ == '__main__':
    main(sys.argv[1:])
