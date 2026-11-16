#!/usr/bin/env python3
"""On whose ground warriors die: python lab/fable/ground.py RUN...

Per run, a game, in three spans of minutes: the warriors of both sides that die
  tower   within 17 cells of a standing tower of ours,
  base    else within 40 cells of a standing building of ours,
  theirs  else within 30 cells of a standing enemy building (tower: within 17 cells of an enemy tower),
  field   anywhere else,
and the exchange (enemy warriors dead per one of ours) on each ground.
"""
import collections
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
SPANS = ((12, 20), (20, 30), (30, 60))
GROUNDS = ('tower', 'base', 'field', 'theirs', 'their tower')


def nearest2(x, y, t, buildings, kinds=None):
    best = 1e18
    for (bx, by, kind, t0, t1) in buildings:
        if t0 <= t < t1 and (kinds is None or kind in kinds):
            d = (bx - x) ** 2 + (by - y) ** 2
            if d < best:
                best = d
    return best


def main(runs):
    for run in runs:
        acc = collections.Counter()
        alive = collections.Counter()
        games = 0
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
            games += 1
            ours, theirs, open_b, deaths = [], [], {}, []
            end = 1e9
            for line in open(path, encoding='utf-8'):
                d = json.loads(line)
                ev = d['ev']
                if ev == 'built':
                    open_b[(d['s'], d['x'], d['y'])] = (d['b'], d['t'])
                elif ev == 'razed':
                    key = (d['s'], d['x'], d['y'])
                    if key in open_b:
                        kind, t0 = open_b.pop(key)
                        (ours if d['s'] == 0 else theirs).append((d['x'], d['y'], kind, t0, d['t']))
                elif ev == 'deaths':
                    deaths.append(d)
                elif ev == 'end':
                    end = d['t']
            for (s, x, y), (kind, t0) in open_b.items():
                (ours if s == 0 else theirs).append((x, y, kind, t0, 1e9))
            for lo, hi in SPANS:
                if end > lo * 60:
                    alive[(lo, hi)] += 1
            for d in deaths:
                war = d['rock'] + d['iron'] + d['rubber']
                if not war:
                    continue
                m = d['t'] / 60.0
                span = next(((lo, hi) for lo, hi in SPANS if lo <= m < hi), None)
                if span is None:
                    continue
                x, y, t = d['x'], d['y'], d['t']
                if nearest2(x, y, t, ours, ('tower',)) <= 17 * 17:
                    ground = 'tower'
                elif nearest2(x, y, t, ours) <= 40 * 40:
                    ground = 'base'
                elif nearest2(x, y, t, theirs, ('tower',)) <= 17 * 17:
                    ground = 'their tower'
                elif nearest2(x, y, t, theirs) <= 30 * 30:
                    ground = 'theirs'
                else:
                    ground = 'field'
                acc[(span, ground, 'ours' if d['s'] == 0 else 'enemy')] += war
        print(f'== {run}: {games} games; warriors dead a game (enemy / ours = exchange)')
        print('  minutes  ' + ''.join(f'{g:>22}' for g in GROUNDS))
        for span in SPANS:
            n = max(1, alive[span])
            row = ''
            for g in GROUNDS:
                e, o = acc[(span, g, 'enemy')] / n, acc[(span, g, 'ours')] / n
                row += f'{e:7.1f} /{o:6.1f} ={e / max(o, .01):5.1f}'
            print(f'  {span[0]:2d}-{span[1]:<2d} ({alive[span]:2d})' + row)


if __name__ == '__main__':
    main(sys.argv[1:])
