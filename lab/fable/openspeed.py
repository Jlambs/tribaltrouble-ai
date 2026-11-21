#!/usr/bin/env python3
"""How fast an opening is, run against run: python lab/fable/openspeed.py BASE ARM...

For the maps every run played (ten-minute games are enough, no logs needed): our units at 3, 4, 5, 6, 7 and 8
minutes, our warriors at 6, 8 and 10 minutes, the iron harvested by minute 10, our peons lost by minute 5, and when
the second, fourth and sixth quarters and the first armory stood (600 for one that did not). A line per run with the
means, then per arm the paired difference to BASE with its standard error.
"""
import glob
import json
import math
import os
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
COLS = ['u180', 'u240', 'u300', 'u360', 'u420', 'u480', 'w360', 'w480', 'w600', 'iron600', 'lost300', 'q2', 'q4',
        'q6', 'armory', 'tw600']
LIMIT = 600.0


def load(run):
    out = {}
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
        seed = os.path.basename(path)[1:6]
        row = dict.fromkeys(COLS, 0.0)
        quarters = []
        armory = None
        lost = 0
        for line in open(path, encoding='utf-8'):
            if '"s":0,' not in line:
                continue
            if '"ev":"census"' in line:
                d = json.loads(line)
                t = int(round(d['t']))
                if t > LIMIT:
                    break
                if 'u%d' % t in row:
                    row['u%d' % t] = d['units']
                if 'w%d' % t in row:
                    row['w%d' % t] = d['rock'] + d['iron'] + d['rubber']
                if t == 600:
                    row['iron600'] = d['harvestedIron']
                    row['tw600'] = d['towers']
            elif '"ev":"built"' in line:
                d = json.loads(line)
                if d['b'] == 'quarters':
                    quarters.append(d['t'])
                elif d['b'] == 'armory' and armory is None:
                    armory = d['t']
            elif '"ev":"deaths"' in line:
                d = json.loads(line)
                if d['t'] <= 300:
                    lost += d['peon']
        quarters.sort()
        for k in (2, 4, 6):
            row['q%d' % k] = min(LIMIT, quarters[k - 1]) if len(quarters) >= k else LIMIT
        row['armory'] = min(LIMIT, armory) if armory is not None else LIMIT
        row['lost300'] = lost
        out[seed] = row
    return out


def main():
    runs = sys.argv[1:]
    data = [load(r) for r in runs]
    seeds = sorted(set.intersection(*[set(d) for d in data]))
    print('%d maps in all runs' % len(seeds))
    print('%-22s' % 'run' + ' '.join('%7s' % c for c in COLS))
    for r, d in zip(runs, data):
        print('%-22s' % r + ' '.join('%7.1f' % statistics.mean(d[s][c] for s in seeds) for c in COLS))
    base = data[0]
    for r, d in zip(runs[1:], data[1:]):
        print('%-22s' % ('  ' + r + ' - base') + ' '.join(
            '%+7.1f' % statistics.mean(d[s][c] - base[s][c] for s in seeds) for c in COLS))
        print('%-22s' % '    +- se' + ' '.join('%7.1f' % (
            statistics.stdev([d[s][c] - base[s][c] for s in seeds]) / math.sqrt(len(seeds)) if len(seeds) > 1 else 0)
                                              for c in COLS))


if __name__ == '__main__':
    main()
