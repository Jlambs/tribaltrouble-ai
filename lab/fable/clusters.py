#!/usr/bin/env python3
"""Do our towers cover each other: python lab/fable/clusters.py [--minute 16] [--until 30] [--near 10] RUN...

For every tower of ours standing at --minute: the other towers of ours within --near cells then (a garrison reaches
15.9 cells, so towers that near each other shoot at whatever attacks either), whether it was razed by --until, and
the enemy warriors that died within 16 cells of it up to then, each death shared among the towers in reach. Then
the games by their mean number of neighbours, with the share won.
"""
import collections
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def main(argv):
    minute, until, near = 16, 30, 10
    runs = []
    it = iter(argv)
    for a in it:
        if a == '--minute':
            minute = int(next(it))
        elif a == '--until':
            until = int(next(it))
        elif a == '--near':
            near = int(next(it))
        else:
            runs.append(a)
    t0, t1 = minute * 60, until * 60
    by_n = collections.defaultdict(lambda: collections.Counter())
    games = []
    for run in runs:
        won = {}
        for line in open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8'):
            if line.strip():
                r = json.loads(line)
                won[r['key']] = r['result'] == 'win'
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
            key = os.path.basename(path)[:-6]
            if key not in won:
                continue
            ours = []
            deaths = []
            other = []
            housed = None
            copies = {}
            for line in open(path, encoding='utf-8'):
                if '"ev":"census"' in line:
                    if f'"t":{t0}.00' in line and '"s":0,' not in line:
                        d = json.loads(line)
                        copies[d['s']] = bool(d.get('alive')) and (d['quarters'] or d['armories'])
                    continue
                if '"ev":"deaths"' in line:
                    d = json.loads(line)
                    if d['s'] != 0 and t0 <= d['t'] <= t1:
                        n = d['rock'] + d['iron'] + d['rubber']
                        if n:
                            deaths.append((d['t'], d['x'], d['y'], n))
                    continue
                if '"s":0,' not in line or ('"ev":"built"' not in line and '"ev":"razed"' not in line):
                    continue
                d = json.loads(line)
                if d['ev'] == 'built':
                    o = {'b': d['b'], 'x': d['x'], 'y': d['y'], 't0': d['t'], 't1': None}
                    (ours if d['b'] == 'tower' else other).append(o)
                else:
                    for o in (ours if d['b'] == 'tower' else other):
                        if o['t1'] is None and abs(o['x'] - d['x']) <= 1 and abs(o['y'] - d['y']) <= 1:
                            o['t1'] = d['t']
                            break
            housed = sum(1 for v in copies.values() if v)
            standing = [o for o in ours if o['t0'] <= t0 and (o['t1'] is None or o['t1'] > t0)]
            if not standing:
                continue
            for o in standing:
                o['n'] = sum(1 for p in standing if p is not o and (p['x'] - o['x']) ** 2 + (p['y'] - o['y']) ** 2
                             <= near * near)
                o['kills'] = 0.0
                o['home'] = min([((p['x'] - o['x']) ** 2 + (p['y'] - o['y']) ** 2) ** .5 for p in other
                                 if p['t0'] <= t0 and (p['t1'] is None or p['t1'] > t0)] or [99])
            for t, x, y, n in deaths:
                reach = [o for o in standing if (o['t1'] is None or o['t1'] > t) and (o['x'] - x) ** 2 + (
                    o['y'] - y) ** 2 <= 256]
                for o in reach:
                    o['kills'] += n / len(reach)
            for o in standing:
                c = by_n[min(o['n'], 4)]
                c['towers'] += 1
                c['razed'] += o['t1'] is not None and o['t1'] <= t1
                c['kills'] += o['kills']
                c['minutes'] += (min(o['t1'] or t1, t1) - t0) / 60.0
                c['home'] += o['home']
            games.append((sum(o['n'] for o in standing) / len(standing), won[key], housed, len(standing),
                          sum(1 for o in standing if o['t1'] is not None and o['t1'] <= t1)))
    print(f'{len(games)} games; towers standing at minute {minute}, by the towers of ours within {near} cells')
    print('neighbours | towers | razed by minute %d | enemy warriors killed in reach, per tower / per tower-minute | '
          'cells to our nearest other building' % until)
    for n in sorted(by_n):
        c = by_n[n]
        print(f'  {n}{"+" if n == 4 else " "}        | {c["towers"]:6d} | {100.0 * c["razed"] / c["towers"]:5.1f} % | '
              f'{c["kills"] / c["towers"]:6.1f} / {c["kills"] / max(1.0, c["minutes"]):5.2f} | '
              f'{c["home"] / c["towers"]:5.1f}')
    print('games by mean neighbours a tower: games, won, towers, towers razed; and those with 7-9 copies housed')
    edges = [0, .5, 1, 1.5, 2, 3, 99]
    for lo, hi in zip(edges, edges[1:]):
        g = [x for x in games if lo <= x[0] < hi]
        b = [x for x in g if x[2] is not None and 7 <= x[2] <= 9]
        if g:
            print(f'  {lo:3.1f}-{hi:<4.1f} | {len(g):3d} games, won {sum(x[1] for x in g):3d} '
                  f'({100.0 * sum(x[1] for x in g) / len(g):3.0f} %), towers {sum(x[3] for x in g) / len(g):4.1f}, '
                  f'razed {sum(x[4] for x in g) / len(g):4.1f} | band {len(b):3d} games, won '
                  f'{sum(x[1] for x in b):3d} ({100.0 * sum(x[1] for x in b) / max(1, len(b)):3.0f} %)')


if __name__ == '__main__':
    main(sys.argv[1:])
