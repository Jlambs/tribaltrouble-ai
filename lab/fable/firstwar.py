#!/usr/bin/env python3
"""The first campaign, run against run: python lab/fable/firstwar.py [--at S] BASE ARM...

For the maps every run played (games cut at S seconds or longer, 600 unless --at; no logs needed): when our armory
stood, our warriors at minutes 5, 6, 7, 8 and at S, the warriors we lost by minute 8 and by S, when the first enemy
building fell (S for none), the enemy buildings and armories down by minute 8 and by S, the copies out or collapsed by
S (those of the first two minutes, the opening strike's, left out), the copies with a quarters or an armory at S and
those with an armory, the copies' warriors at S and their units killed by S. A line per run with the means, then per
arm the paired difference to BASE with its standard error.
"""
import glob
import json
import math
import os
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
COLS = ['armory', 'w300', 'w360', 'w420', 'w480', 'wS', 'lost480', 'lostS', 'raz1', 'raz480', 'razS', 'armS',
        'outS', 'housed', 'armed', 'ewS', 'killS']


def load(run, limit):
    out = {}
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
        seed = os.path.basename(path)[1:6]
        row = dict.fromkeys(COLS, 0.0)
        row['armory'] = row['raz1'] = limit
        armory = None
        housed = {}
        armed = {}
        enemy_war = {}
        last = 0.0
        for line in open(path, encoding='utf-8'):
            if not line.startswith('{"ev":"'):
                continue
            d = json.loads(line)
            ev = d['ev']
            t = d.get('t', 0.0)
            if ev == 'end':
                last = t
            if t > limit + .5:
                continue
            if ev == 'census':
                ti = int(round(t))
                if d['s'] == 0:
                    w = d['rock'] + d['iron'] + d['rubber']
                    if 'w%d' % ti in row:
                        row['w%d' % ti] = w
                    if ti == int(limit):
                        row['wS'] = w
                        row['killS'] = d['kills']
                elif ti == int(limit):
                    housed[d['s']] = d['quarters'] + d['armories'] > 0
                    armed[d['s']] = d['armories'] > 0
                    enemy_war[d['s']] = d['rock'] + d['iron'] + d['rubber']
            elif ev == 'built':
                if d['s'] == 0 and d['b'] == 'armory' and armory is None:
                    armory = t
            elif ev == 'deaths':
                if d['s'] == 0:
                    n = d['rock'] + d['iron'] + d['rubber']
                    row['lostS'] += n
                    if t <= 480:
                        row['lost480'] += n
            elif ev == 'razed':
                if d['s'] != 0:
                    row['raz1'] = min(row['raz1'], t)
                    row['razS'] += 1
                    if t <= 480:
                        row['raz480'] += 1
                    if d['b'] == 'armory':
                        row['armS'] += 1
            elif ev in ('out', 'collapse'):
                if d['s'] != 0 and t > 120:
                    row['outS'] += 1
        if last + .5 < limit:
            continue  # the game ended before S: we were out
        if armory is not None:
            row['armory'] = armory
        row['housed'] = sum(housed.values())
        row['armed'] = sum(armed.values())
        row['ewS'] = sum(enemy_war.values())
        out[seed] = row
    return out


def main():
    args = sys.argv[1:]
    limit = 600.0
    if args and args[0] == '--at':
        limit = float(args[1])
        args = args[2:]
    data = [load(r, limit) for r in args]
    seeds = sorted(set.intersection(*[set(d) for d in data]))
    print('%d maps in all runs, read at %d s' % (len(seeds), limit))
    print('%-22s' % 'run' + ' '.join('%7s' % c for c in COLS))
    for r, d in zip(args, data):
        print('%-22s' % r + ' '.join('%7.2f' % statistics.mean(d[s][c] for s in seeds) for c in COLS))
    base = data[0]
    for r, d in zip(args[1:], data[1:]):
        print('%-22s' % ('  ' + r + ' - base') + ' '.join(
            '%+7.2f' % statistics.mean(d[s][c] - base[s][c] for s in seeds) for c in COLS))
        print('%-22s' % '    +- se' + ' '.join('%7.2f' % (
            statistics.stdev([d[s][c] - base[s][c] for s in seeds]) / math.sqrt(len(seeds)) if len(seeds) > 1 else 0)
                                              for c in COLS))


if __name__ == '__main__':
    main()
