#!/usr/bin/env python3
"""Do the copies rebuild what we raze: python lab/fable/rebuild.py [--window 10] RUN...

Per window of the games still running: the enemy quarters, armories and towers razed and built a game, the copies
that went out, and of the copies that lost their last quarters or armory, how many had one again within five minutes.
"""
import collections
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def main(argv):
    window = 10
    runs = []
    it = iter(argv)
    for a in it:
        if a == '--window':
            window = int(next(it))
        else:
            runs.append(a)
    acc = collections.defaultdict(collections.Counter)
    games = collections.Counter()
    for run in runs:
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
            end = 0.0
            houses = collections.Counter()  # finished quarters and armories a copy
            unhoused = {}  # copy -> when it lost its last one
            for line in open(path, encoding='utf-8'):
                if '"ev":"end"' in line:
                    end = json.loads(line)['t']
                    continue
                if '"ev":"out"' in line:
                    d = json.loads(line)
                    if d['s'] != 0:
                        acc[int(d['t'] // (window * 60)) * window]['out'] += 1
                        unhoused.pop(d['s'], None)
                    continue
                if '"ev":"built"' not in line and '"ev":"razed"' not in line:
                    continue
                d = json.loads(line)
                if d['s'] == 0:
                    continue
                w = int(d['t'] // (window * 60)) * window
                site = d.get('site', 0)
                if d['ev'] == 'built':
                    acc[w]['built_' + d['b']] += 1
                    if d['b'] in ('quarters', 'armory'):
                        houses[d['s']] += 1
                        t0 = unhoused.pop(d['s'], None)
                        if t0 is not None:
                            acc[int(t0 // (window * 60)) * window]['rehoused_5' if d['t'] - t0 <= 300 else 'rehoused_later'] += 1
                elif not site:
                    acc[w]['razed_' + d['b']] += 1
                    if d['b'] in ('quarters', 'armory'):
                        houses[d['s']] -= 1
                        if houses[d['s']] <= 0:
                            acc[w]['unhoused'] += 1
                            unhoused[d['s']] = d['t']
            for w in range(0, 400, window):
                if end >= (w + window) * 60:
                    games[w] += 1
    print('window | games | razed a game: quarters armories towers | built a game: quarters armories towers | '
          'copies out a game | copies that lost their last quarters or armory a game, housed again within 5 min, later')
    for w in sorted(games):
        n = games[w]
        if n < 10:
            break
        a = acc[w]
        print(f'{w:3d}-{w + window:<3d} | {n:3d} | {a["razed_quarters"] / n:5.2f} {a["razed_armory"] / n:5.2f} '
              f'{a["razed_tower"] / n:5.2f} | {a["built_quarters"] / n:5.2f} {a["built_armory"] / n:5.2f} '
              f'{a["built_tower"] / n:5.2f} | {a["out"] / n:5.2f} | {a["unhoused"] / n:5.2f}, '
              f'{a["rehoused_5"] / n:5.2f}, {a["rehoused_later"] / n:5.2f}')


if __name__ == '__main__':
    main(sys.argv[1:])
