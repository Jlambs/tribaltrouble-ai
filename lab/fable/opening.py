#!/usr/bin/env python3
"""The opening's milestones per map: python lab/fable/opening.py [--maps] [--minute M] RUN...

For the maps all runs played: when our first and sixth quarters and the armory stood (999: not in the game), and at
minute M (8) our towers, warriors, peons and the iron harvested. With --maps a line per map, else only the means and,
per run, how many maps have the sixth quarters after 330 s. Needs no logs.
"""
import glob
import json
import os
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
NAMES = ('first quarters', 'sixth quarters', 'armory', 'towers', 'warriors', 'peons', 'iron')


def load(run, minute):
    out = {}
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
        key = os.path.basename(path)[1:6]
        quarters, armories, towers = [], [], []
        census = None
        for line in open(path, encoding='utf-8'):
            if '"ev":"built"' in line:
                d = json.loads(line)
                if d['s'] == 0:
                    (quarters if d['b'] == 'quarters' else armories if d['b'] == 'armory' else towers).append(d['t'])
            elif '"ev":"census"' in line and '"s":0,' in line:
                d = json.loads(line)
                if abs(d['t'] - minute * 60) < 1:
                    census = d
        quarters.sort()
        if census is None:
            census = {'rock': 0, 'iron': 0, 'rubber': 0, 'peons': 0, 'inside': 0, 'harvestedIron': 0}
        out[key] = (quarters[0] if quarters else 999, quarters[5] if len(quarters) > 5 else 999,
                    min(armories) if armories else 999, len([t for t in towers if t <= minute * 60]),
                    census['rock'] + census['iron'] + census['rubber'], census['peons'] + census['inside'],
                    census['harvestedIron'])
    return out


def main(args):
    maps = '--maps' in args
    minute = 8
    runs = []
    it = iter(a for a in args if a != '--maps')
    for a in it:
        if a == '--minute':
            minute = int(next(it))
        else:
            runs.append(a)
    data = {r: load(r, minute) for r in runs}
    keys = sorted(k for k in data[runs[0]] if all(k in data[r] for r in runs))
    print(f'{len(keys)} maps; runs: ' + ', '.join(runs) + f'; towers, warriors, peons, iron at minute {minute}')
    if maps:
        for k in keys:
            print(k + ' | ' + ' | '.join(' '.join(f'{data[r][k][i]:4.0f}' for r in runs) for i in range(len(NAMES))))
    for i, name in enumerate(NAMES):
        print(f'  {name:<15}' + ''.join(f'{statistics.mean(min(data[r][k][i], 600) for k in keys):8.1f}' for r in runs)
              + ('   (capped at 600 s)' if i < 3 else ''))
    print('  sixth quarters after 330 s, maps:' + ''.join(f'{sum(1 for k in keys if data[r][k][1] > 330):5d}'
                                                           for r in runs))


if __name__ == '__main__':
    main(sys.argv[1:])
