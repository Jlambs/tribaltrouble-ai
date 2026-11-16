#!/usr/bin/env python3
"""Our army and economy by minute, run against run on the maps both played:

    python lab/fable/flow.py [--minutes 10,15,20,25,30,40] BASE ARM...

Per run and minute, the mean over the common games still alive there in every run: warriors (of them rock), peons
(inside), harvest per minute over the last five minutes (iron, rock, wood), units lost and killed per minute, and
the copies still housed. The last column counts the games.
"""
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def load(run):
    games = {}
    for path in glob.glob(os.path.join(ROOT, run, 'g', 's*.jsonl')):
        key = os.path.basename(path)[:-6]
        me, foes = {}, {}
        for line in open(path, encoding='utf-8'):
            if '"census"' not in line:
                continue
            d = json.loads(line)
            m = d['t'] / 60.0
            if abs(m - round(m)) > 0.02:
                continue
            if d['s'] == 0:
                me[round(m)] = d
            elif d['alive'] and d['quarters'] + d['armories'] > 0:
                foes[round(m)] = foes.get(round(m), 0) + 1
        games[key] = (me, foes)
    return games


def main(argv):
    minutes = [10, 15, 20, 25, 30, 40]
    if argv and argv[0] == '--minutes':
        minutes = [int(x) for x in argv[1].split(',')]
        argv = argv[2:]
    runs = {name: load(name) for name in argv}
    keys = sorted(set.intersection(*[set(g) for g in runs.values()]))
    won = {}
    for name in argv:
        won[name] = 0
        for line in open(os.path.join(ROOT, name, 'results.jsonl'), encoding='utf-8'):
            if line.strip():
                r = json.loads(line)
                if r['key'] in keys and r['result'] == 'win':
                    won[name] += 1
    print(f'{len(keys)} common games; wins ' + ', '.join(f'{n} {won[n]}' for n in argv))
    print('min run                     | war (rock) | peons (in) | iron rock wood /min | lost kills /min | housed | n')
    for m in minutes:
        alive = [k for k in keys if all(m in runs[n][k][0] and m - 5 in runs[n][k][0] and runs[n][k][0][m]['alive']
                                        for n in argv)]
        if not alive:
            continue
        for name in argv:
            rows = []
            for k in alive:
                a, b = runs[name][k][0][m], runs[name][k][0][m - 5]
                rows.append((a['rock'] + a['iron'] + a['rubber'], a['rock'], a['peons'] + a['inside'], a['inside'],
                             (a['harvestedIron'] - b['harvestedIron']) / 5, (a['harvestedRock'] - b['harvestedRock']) / 5,
                             (a['harvestedTree'] - b['harvestedTree']) / 5, (a['lost'] - b['lost']) / 5,
                             (a['kills'] - b['kills']) / 5, runs[name][k][1].get(m, 0)))
            v = [sum(r[i] for r in rows) / len(rows) for i in range(10)]
            print(f'{m:3d} {name:24s}| {v[0]:4.0f} ({v[1]:3.0f})  | {v[2]:4.0f} ({v[3]:3.0f}) | {v[4]:5.1f} {v[5]:5.1f} '
                  f'{v[6]:5.1f}    | {v[7]:5.1f} {v[8]:5.1f}     | {v[9]:5.1f}  | {len(alive)}')


if __name__ == '__main__':
    main(sys.argv[1:])
