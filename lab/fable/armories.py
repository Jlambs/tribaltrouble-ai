#!/usr/bin/env python3
"""Our armories through a game: python lab/fable/armories.py RUN...

Per five-minute window, the armories we build and lose a game in the games won and in the others; how many of our
armories stood at each minute; and for every armory razed in minutes 15-35 the towers of ours within 15 cells a
minute before, against the towers by the armories that stood through.
"""
import collections
import glob
import json
import math
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def main(runs):
    won = {}
    for run in runs:
        for line in open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8'):
            if line.strip():
                r = json.loads(line)
                won[(run, r['key'])] = r['result'] == 'win'
    built = {True: collections.Counter(), False: collections.Counter()}
    razed = {True: collections.Counter(), False: collections.Counter()}
    games = collections.Counter()
    total = collections.Counter()
    cover_razed = collections.Counter()
    cover_stood = collections.Counter()
    far_razed, far_stood = [], []
    for run in runs:
        for path in glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl')):
            key = os.path.basename(path)[:-6]
            w = won.get((run, key))
            if w is None:
                continue
            games[w] += 1
            start = None
            ours = []
            for line in open(path, encoding='utf-8'):
                if line.startswith('{"ev":"game"'):
                    p0 = [p for p in json.loads(line)['players'] if p['s'] == 0][0]
                    start = (p0['x'], p0['y'])
                    continue
                if '"s":0,' not in line or ('"ev":"built"' not in line and '"ev":"razed"' not in line):
                    continue
                d = json.loads(line)
                if d['s'] != 0:
                    continue
                if d['ev'] == 'built':
                    ours.append({'b': d['b'], 'x': d['x'], 'y': d['y'], 't0': d['t'], 't1': None})
                    if d['b'] == 'armory':
                        built[w][min(int(d['t'] // 300) * 5, 60)] += 1
                else:
                    for o in ours:
                        if o['t1'] is None and o['b'] == d['b'] and abs(o['x'] - d['x']) <= 1 and abs(
                                o['y'] - d['y']) <= 1:
                            o['t1'] = d['t']
                            break
                    if d['b'] == 'armory':
                        razed[w][min(int(d['t'] // 300) * 5, 60)] += 1
            arm = [o for o in ours if o['b'] == 'armory']
            total[(w, min(len(arm), 5))] += 1
            for a in arm:
                def towers_at(t):
                    return sum(1 for o in ours if o['b'] == 'tower' and o['t0'] <= t and (
                        o['t1'] is None or o['t1'] > t) and (o['x'] - a['x']) ** 2 + (o['y'] - a['y']) ** 2 <= 225)
                d0 = math.hypot(a['x'] - start[0], a['y'] - start[1]) if start else 0
                if a['t1'] is not None and 900 <= a['t1'] <= 2100:
                    cover_razed[min(towers_at(a['t1'] - 60), 3)] += 1
                    far_razed.append(d0)
                elif a['t0'] <= 1500 and (a['t1'] is None or a['t1'] > 2100):
                    cover_stood[min(towers_at(1500), 3)] += 1
                    far_stood.append(d0)
    print(f'{games[True]} games won, {games[False]} lost')
    print('our armories built / razed a game by five-minute window: won | lost')
    for b in range(0, 65, 5):
        print(f'  {b:3d}-{b + 5:<3d} | {built[True][b] / max(1, games[True]):.2f} / '
              f'{razed[True][b] / max(1, games[True]):.2f} | {built[False][b] / max(1, games[False]):.2f} / '
              f'{razed[False][b] / max(1, games[False]):.2f}')
    print('games by the armories built in all (1, 2, 3, 4, 5+): won', [total[(True, k)] for k in range(1, 6)],
          'lost', [total[(False, k)] for k in range(1, 6)], 'none', total[(False, 0)])
    print('armories razed in minutes 15-35 by our towers within 15 cells a minute before (0, 1, 2, 3+):',
          [cover_razed[k] for k in range(4)])
    print('armories that stood through, by the towers at minute 25:', [cover_stood[k] for k in range(4)])
    print('share that stood:', ' '.join(
        f'{k}{"+" if k == 3 else ""}: {100.0 * cover_stood[k] / max(1, cover_stood[k] + cover_razed[k]):.0f} %'
        for k in range(4)))
    if far_razed and far_stood:
        print(f'cells from our start, median: razed {sorted(far_razed)[len(far_razed) // 2]:.0f}, stood '
              f'{sorted(far_stood)[len(far_stood) // 2]:.0f}')


if __name__ == '__main__':
    main(sys.argv[1:])
