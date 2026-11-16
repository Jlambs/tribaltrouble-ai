#!/usr/bin/env python3
"""What our towers do, by period: python lab/fable/towerkills.py RUN...

Per game: enemy warriors that die within 17 cells of a standing tower of ours, our own warriors that die there, our
towers and other buildings razed, and all enemy warriors killed, from the recorded games (aisim/runs/RUN/g).
"""
import collections
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
BUCKETS = [(5, 9), (9, 12), (12, 15), (15, 20), (20, 25), (25, 30), (30, 35), (35, 40)]


def main(runs):
    for run in runs:
        acc = {b: collections.Counter() for b in BUCKETS}
        games = {b: 0.0 for b in BUCKETS}
        n = 0
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*.jsonl'))):
            towers = {}
            deaths = []
            razed = []
            end = 0.0
            for line in open(path, encoding='utf-8'):
                d = json.loads(line)
                ev = d['ev']
                if ev == 'built' and d['s'] == 0 and d['b'] == 'tower':
                    towers[(d['x'], d['y'])] = [d['t'], 1e9]
                elif ev == 'razed' and d['s'] == 0:
                    if d['b'] == 'tower' and (d['x'], d['y']) in towers:
                        towers[(d['x'], d['y'])][1] = d['t']
                    razed.append(d)
                elif ev == 'deaths':
                    deaths.append(d)
                elif ev == 'end':
                    end = d['t'] / 60
            if end == 0.0:
                continue
            n += 1
            for d in deaths:
                war = d['rock'] + d['iron'] + d['rubber']
                if not war:
                    continue
                t = d['t']
                for b in BUCKETS:
                    if b[0] * 60 <= t < b[1] * 60:
                        near = any(t0 <= t <= t1 and (x - d['x']) ** 2 + (y - d['y']) ** 2 <= 17 * 17
                                   for (x, y), (t0, t1) in towers.items())
                        if d['s'] != 0:
                            acc[b]['foes'] += war
                            if near:
                                acc[b]['foes_at_towers'] += war
                        else:
                            acc[b]['ours'] += war
                            if near:
                                acc[b]['ours_at_towers'] += war
            for r in razed:
                for b in BUCKETS:
                    if b[0] * 60 <= r['t'] < b[1] * 60:
                        acc[b]['towers_razed' if r['b'] == 'tower' else 'others_razed'] += 1
            for b in BUCKETS:
                if end >= b[1]:
                    games[b] += 1
                elif end > b[0]:
                    games[b] += (end - b[0]) / (b[1] - b[0])
        print(f'== {run}: {n} games; per game: enemy warriors killed by our towers / all killed | ours lost there / '
              'everywhere | towers razed | other buildings razed')
        for b in BUCKETS:
            g = max(games[b], 1e-9)
            a = acc[b]
            print(f"  {b[0]:>2}-{b[1]:<2} min: {a['foes_at_towers'] / g:6.1f} / {a['foes'] / g:6.1f} | "
                  f"{a['ours_at_towers'] / g:5.1f} / {a['ours'] / g:5.1f} | {a['towers_razed'] / g:5.2f} | "
                  f"{a['others_razed'] / g:5.2f}")


if __name__ == '__main__':
    main(sys.argv[1:])
