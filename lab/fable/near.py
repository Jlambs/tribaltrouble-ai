#!/usr/bin/env python3
"""A copy starting next to us: python lab/fable/near.py [--set NAME --under 40] RUN [RUN...]

Per game (any seat: the row's `side` is ours) the straight distance in cells from our start to the nearest copy's, from
the game event's starts. Prints, by bands of that distance: the games, the wins, how often a copy is out by minute 4
(the opening strike's kill), and our units and losses at 2:00. With --set it also writes aisim/NAME.json ("N,seat" ->
seeds) and aisim/NAME-info.json ("N,seat,seed" -> [cells, result, minutes]) of the games under --under cells, for
lab/fable/subset.py to replay with another spec.
"""
import argparse
import collections
import json
import math
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim')
EDGES = [0, 20, 30, 40, 50, 60, 70, 80, 100, 400]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('runs', nargs='+')
    ap.add_argument('--set', default=None)
    ap.add_argument('--under', type=float, default=40)
    args = ap.parse_args()
    rows = []
    for run in args.runs:
        path = os.path.join(ROOT, 'runs', run, 'results.jsonl')
        if not os.path.exists(path):
            continue
        for line in open(path, encoding='utf-8'):
            r = json.loads(line)
            side = r['side']
            starts = {}
            first_out = units = lost = None
            for ev in open(os.path.join(ROOT, 'runs', run, 'g', r['key'] + '.jsonl'), encoding='utf-8'):
                if '"ev":"game"' in ev:
                    starts = {q['s']: (q['x'], q['y']) for q in json.loads(ev)['players']}
                elif '"ev":"out"' in ev or '"ev":"collapse"' in ev:
                    d = json.loads(ev)
                    if d['s'] != side and first_out is None:
                        first_out = d['t']
                elif '"ev":"census"' in ev and f'"s":{side},' in ev:
                    d = json.loads(ev)
                    if d['t'] >= 120 and units is None:
                        units, lost = d['units'], d['lost']
                    if d['t'] >= 480:
                        break
            near = min(math.dist(starts[side], starts[s]) for s in starts if s != side)
            rows.append({'near': near, 'win': r['result'] == 'win', 'out': first_out, 'units': units or 0,
                         'lost': lost or 0, 'n': r['slots'] - 1, 'seat': side, 'seed': r['seed'],
                         'result': r['result'], 'end': r['t'] / 60.0})
    print('nearest copy (cells): games, won, a copy out by minute 4, our units and losses at 2:00 (means)')
    for lo, hi in zip(EDGES[:-1], EDGES[1:]):
        sel = [x for x in rows if lo <= x['near'] < hi]
        if sel:
            n = len(sel)
            print('  %3d..%-3d %4d games  %4d won = %5.1f %%   strike kill %5.1f %%   units %5.1f  lost %4.1f' % (
                lo, hi - 1, n, sum(x['win'] for x in sel), 100.0 * sum(x['win'] for x in sel) / n,
                100.0 * sum(1 for x in sel if x['out'] is not None and x['out'] <= 240) / n,
                sum(x['units'] for x in sel) / n, sum(x['lost'] for x in sel) / n))
    if args.set:
        games = collections.defaultdict(list)
        info = {}
        for x in rows:
            if x['near'] < args.under:
                games[f'{x["n"]},{x["seat"]}'].append(x['seed'])
                info[f'{x["n"]},{x["seat"]},{x["seed"]}'] = [x['near'], x['result'], x['end']]
        json.dump(games, open(os.path.join(ROOT, args.set + '.json'), 'w'))
        json.dump(info, open(os.path.join(ROOT, args.set + '-info.json'), 'w'))
        print(f'{len(info)} games under {args.under:.0f} cells written to aisim/{args.set}.json')


if __name__ == '__main__':
    main()
