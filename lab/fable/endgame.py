#!/usr/bin/env python3
"""How long games are decided: python lab/fable/endgame.py [--games] [--csv FILE] RUN...

Over all games of the runs (slot 0 is ours): how long won and lost games last, the share won by the copies still
housed (a quarters or an armory) at several minutes, and for the lost games how near they came: the fewest copies
housed at any time, when our army was last 40 strong, how long we lasted after that. --games lists every game.
"""
import collections
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
MARKS = [15, 20, 30, 40, 50, 60, 80, 100]


def load(run):
    """One dict a game: key, won, minutes, and by census time (seconds) our line and the enemy's sums."""
    won = {}
    for line in open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8'):
        if line.strip():
            r = json.loads(line)
            won[r['key']] = (r['result'] == 'win', r['t'] / 60.0, r.get('via'))
    games = []
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
        key = os.path.basename(path)[:-6]
        if key not in won:
            continue
        ours = {}
        foes = collections.defaultdict(lambda: collections.Counter())
        for line in open(path, encoding='utf-8'):
            if '"census"' not in line:
                continue
            d = json.loads(line)
            t = int(round(d['t']))
            war = d['rock'] + d['iron'] + d['rubber']
            if d['s'] == 0:
                d['war'] = war
                ours[t] = d
            else:
                f = foes[t]
                f['war'] += war
                f['units'] += d['units']
                f['towers'] += d['towers']
                if d.get('alive'):
                    f['alive'] += 1
                    if d['quarters'] or d['armories']:
                        f['housed'] += 1
                        f['housed_war'] += war
                    elif d['towers']:
                        f['towered'] += 1
        games.append({'run': run, 'key': key, 'seed': int(key[1:].split('-')[0]), 'won': won[key][0],
                      'minutes': won[key][1], 'via': won[key][2], 'ours': ours, 'foes': foes})
    return games


def at(g, minute):
    t = minute * 60
    return (g['ours'].get(t), g['foes'].get(t))


def main(args):
    listing = '--games' in args
    csv = None
    if '--csv' in args:
        csv = args[args.index('--csv') + 1]
        args = [a for i, a in enumerate(args) if a != '--csv' and (i == 0 or args[i - 1] != '--csv')]
    runs = [a for a in args if not a.startswith('--')]
    games = []
    for run in runs:
        games += load(run)
    wins = [g for g in games if g['won']]
    losses = [g for g in games if not g['won']]
    print(f'{len(games)} games, {len(wins)} won')
    print('length (minutes)  won  lost')
    edges = [0, 15, 20, 30, 40, 50, 60, 80, 100, 130, 170, 400]
    for lo, hi in zip(edges, edges[1:]):
        w = sum(1 for g in wins if lo <= g['minutes'] < hi)
        l = sum(1 for g in losses if lo <= g['minutes'] < hi)
        print(f'  {lo:3d}-{hi:<3d}        {w:4d}  {l:4d}')
    print()
    print('share won by copies housed at a minute (games still running then): won/games')
    head = 'minute ' + ''.join(f'{k:>9}' for k in ['0-1', '2', '3', '4', '5', '6', '7', '8', '9', '10+']) + '   all'
    print(head)

    def bucket(h):
        return 0 if h <= 1 else (9 if h >= 10 else h - 1)
    for m in MARKS:
        cells = [[0, 0] for _ in range(10)]
        tot = [0, 0]
        for g in games:
            o, f = at(g, m)
            if o is None or f is None:
                continue
            b = bucket(f['housed'])
            cells[b][0] += g['won']
            cells[b][1] += 1
            tot[0] += g['won']
            tot[1] += 1
        print(f'{m:6d} ' + ''.join(f'{c[0]:>4}/{c[1]:<4}' for c in cells) + f'  {tot[0]}/{tot[1]}')
    print()
    print('our state by minute in games still running: warriors, peons, quarters, armories, towers, iron a minute'
          ' | enemy copies housed, warriors')
    for label, group in (('won', wins), ('lost', losses)):
        for m in MARKS:
            rows = []
            for g in group:
                o, f = at(g, m)
                p = g['ours'].get(m * 60 - 300)
                if o is None or f is None or p is None:
                    continue
                rows.append((o['war'], o['peons'], o['quarters'], o['armories'], o['towers'],
                             (o['harvestedIron'] - p['harvestedIron']) / 5.0, f['housed'], f['war']))
            if not rows:
                continue
            mean = [sum(r[i] for r in rows) / len(rows) for i in range(8)]
            print(f'  {label:<4} {m:4d} min {len(rows):4d} games | {mean[0]:5.0f} {mean[1]:5.0f} {mean[2]:4.1f} '
                  f'{mean[3]:4.1f} {mean[4]:5.1f} {mean[5]:5.1f} | {mean[6]:4.1f} {mean[7]:5.0f}')
    print()
    # the lost games: how near
    print('lost games by the fewest copies housed at any census: games, median length, median minute of that low')
    low = collections.defaultdict(list)
    rows = []
    for g in games:
        best, when = 99, 0
        last40 = 0
        peak = 0
        for t in sorted(g['foes']):
            f = g['foes'][t]
            o = g['ours'].get(t)
            if t >= 600 and f['housed'] < best:
                best, when = f['housed'], t
            if o is not None:
                if o['war'] >= 40:
                    last40 = t
                peak = max(peak, o['war'])
        g['low'], g['low_t'], g['last40'], g['peak'] = best, when / 60.0, last40 / 60.0, peak
        if not g['won']:
            low[best].append(g)
        rows.append(g)

    def med(v):
        v = sorted(v)
        return v[len(v) // 2] if v else 0
    for k in sorted(low):
        gs = low[k]
        print(f'  {k:3d} copies: {len(gs):4d} games, length {med([g["minutes"] for g in gs]):6.1f}, low at '
              f'{med([g["low_t"] for g in gs]):6.1f}, army last 40 strong at {med([g["last40"] for g in gs]):6.1f}')
    if listing:
        print()
        print('run key result minutes | fewest housed at | army peak, last 40 strong at')
        for g in sorted(rows, key=lambda g: (g['won'], g['low'], g['minutes'])):
            print(f'  {g["run"]:<18} {g["key"]:<10} {"won " if g["won"] else "lost"} {g["minutes"]:6.1f} | '
                  f'{g["low"]:2d} at {g["low_t"]:6.1f} | {g["peak"]:4d} {g["last40"]:6.1f}')
    if csv:
        with open(csv, 'w', encoding='utf-8') as out:
            out.write('run,key,seed,won,minutes,low,low_t,peak,last40\n')
            for g in rows:
                out.write(f'{g["run"]},{g["key"]},{g["seed"]},{int(g["won"])},{g["minutes"]:.1f},{g["low"]},'
                          f'{g["low_t"]:.1f},{g["peak"]},{g["last40"]:.1f}\n')


if __name__ == '__main__':
    main(sys.argv[1:])
