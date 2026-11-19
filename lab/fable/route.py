#!/usr/bin/env python3
"""Does the walk to the first armory pass a copy: python lab/fable/route.py LOGPREFIX RESULTPREFIX [--n 12..16]
                                                    [--seat 0] [--set NAME --under 40]

LOGPREFIX-vsN are runs with AI logs of the same games as RESULTPREFIX-vsN-sSEAT (a `--logs --minutes 1` run is
enough: the opening logs `route to armory#1 passes C cells from the start of sK after M m` and the planned site's walk
in its first tick). Per game: C, the walk, the nearest copy's start, our units lost by minute 5 and the result. Prints
the wins by C, the games with a copy starting next door (under 40 cells) apart, and with --set writes
aisim/NAME.json and aisim/NAME-info.json for lab/fable/subset.py: the games with C under --under and no copy next door.
"""
import argparse
import json
import math
import os
import re

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim')
RUNS = os.path.join(ROOT, 'runs')
NEXT_DOOR = 40
BANDS = ((0, 19), (20, 29), (30, 39), (40, 49), (50, 59), (60, 999))


def expand(spec):
    out = []
    for part in spec.split(','):
        if '..' in part:
            a, b = part.split('..')
            out.extend(range(int(a), int(b) + 1))
        else:
            out.append(int(part))
    return out


def early(run, key, side):
    """Our units lost by minute 5 and every start of the game."""
    starts = None
    with open(os.path.join(RUNS, run, 'g', key + '.jsonl'), encoding='utf-8') as f:
        for line in f:
            if '"ev":"game"' in line:
                starts = {p['s']: (p['x'], p['y']) for p in json.loads(line)['players']}
            elif '"ev":"census"' in line:
                d = json.loads(line)
                if d['s'] == side and d['t'] == 300.0:
                    return d['lost'], starts
                if d['t'] > 300.0:
                    break
    return 0, starts


def plan(run, key, side):
    clear = walk = None
    path = os.path.join(RUNS, run, 'g', f'{key}-ai-s{side}.log')
    if not os.path.exists(path):
        return None, None
    with open(path, encoding='utf-8') as f:
        for line in f:
            m = re.search(r'route to armory#1 passes (\d+) cells', line)
            if m:
                clear = int(m.group(1))
            m = re.search(r'plan armory#1 .* from start=(\d+)m', line)
            if m and walk is None:
                walk = int(m.group(1))
            if clear is not None and walk is not None:
                break
    return clear, walk


def show(label, games):
    if games:
        won = sum(g['win'] for g in games)
        print(f'{label}: {len(games)} games, won {won} = {100.0 * won / len(games):.0f} %, '
              f'lost by 5:00 {sum(g["lost"] for g in games) / len(games):.1f}, '
              f'6 and more lost in {sum(g["lost"] >= 6 for g in games)}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('logs')
    ap.add_argument('results')
    ap.add_argument('--n', default='12..16')
    ap.add_argument('--seat', type=int, default=0)
    ap.add_argument('--set')
    ap.add_argument('--under', type=int, default=40)
    args = ap.parse_args()
    games = []
    for n in expand(args.n):
        res = os.path.join(RUNS, f'{args.results}-vs{n}-s{args.seat}', 'results.jsonl')
        if not os.path.exists(res):
            continue
        for line in open(res, encoding='utf-8'):
            if not line.strip():
                continue
            r = json.loads(line)
            clear, walk = plan(f'{args.logs}-vs{n}', r['key'], args.seat)
            if clear is None:
                continue
            lost, starts = early(f'{args.results}-vs{n}-s{args.seat}', r['key'], args.seat)
            me = starts[args.seat]
            near = min(math.dist(me, starts[c]) for c in starts if c != args.seat)
            games.append(dict(n=n, seed=r['seed'], clear=clear, walk=walk, lost=lost, win=r['result'] == 'win',
                              near=near, result=r['result'], minutes=r['t'] / 60.0))
    print(f'{len(games)} games, seat {args.seat}')
    show(f'a copy starts under {NEXT_DOOR} cells from us', [g for g in games if g['near'] < NEXT_DOOR])
    rest = [g for g in games if g['near'] >= NEXT_DOOR]
    for lo, hi in BANDS:
        show(f'walk passes {lo}-{hi} cells from a copy', [g for g in rest if lo <= g['clear'] <= hi])
    for far in (False, True):
        for hot in (True, False):
            show(f'walk {"300 m and more" if far else "under 300 m"}, {"under" if hot else "at least"} 30 cells from a copy',
                 [g for g in rest if (g['walk'] >= 300) == far and (g['clear'] < 30) == hot])
    if args.set:
        picked = [g for g in rest if g['clear'] < args.under]
        sets = {}
        info = {}
        for g in picked:
            sets.setdefault(f'{g["n"]},{args.seat}', []).append(g['seed'])
            info[f'{g["n"]},{args.seat},{g["seed"]}'] = [g['clear'], g['result'], round(g['minutes'], 1)]
        json.dump(sets, open(os.path.join(ROOT, args.set + '.json'), 'w'))
        json.dump(info, open(os.path.join(ROOT, args.set + '-info.json'), 'w'))
        print(f'{len(picked)} games with the walk under {args.under} cells from a copy -> aisim/{args.set}.json')


if __name__ == '__main__':
    main()
