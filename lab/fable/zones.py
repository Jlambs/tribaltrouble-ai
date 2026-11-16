#!/usr/bin/env python3
"""Where the war is fought, and how many warriors the copies hold: python lab/fable/zones.py RUN...

Per run, from minute 12, a game: our buildings razed at home (within 70 cells of our start) and forward of it, and the
warriors and peons that die at home, in the middle (70-200 cells) and far away. Then the copies' warriors by minute,
all of them and per copy that still has a quarters or armory, over the games alive in every run.
"""
import collections
import glob
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
MINUTES = [12, 15, 18, 20, 22, 25, 30, 35]
FROM = 720


def main(runs):
    stock = {}
    for run in runs:
        acc = collections.Counter()
        games = {}
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*.jsonl'))):
            start = None
            foes = collections.defaultdict(lambda: [0, 0])
            for line in open(path, encoding='utf-8'):
                d = json.loads(line)
                ev = d['ev']
                if ev == 'game':
                    start = (d['players'][0]['x'], d['players'][0]['y'])
                elif ev == 'razed' and d['s'] == 0 and d['t'] >= FROM:
                    far = (d['x'] - start[0]) ** 2 + (d['y'] - start[1]) ** 2 > 70 * 70
                    acc[(d['b'], 'forward' if far else 'home')] += 1
                elif ev == 'deaths' and d['t'] >= FROM:
                    war = d['rock'] + d['iron'] + d['rubber']
                    dist = ((d['x'] - start[0]) ** 2 + (d['y'] - start[1]) ** 2) ** .5
                    zone = 'home' if dist <= 70 else 'middle' if dist <= 200 else 'far'
                    if d['s'] == 0:
                        acc[('our warriors', zone)] += war
                        acc[('our peons', zone)] += d.get('peon', 0)
                    else:
                        acc[('enemy warriors', zone)] += war
                elif ev == 'census' and d['s'] != 0:
                    m = d['t'] / 60.0
                    if abs(m - round(m)) <= 0.02:
                        a = foes[int(round(m))]
                        a[0] += d['rock'] + d['iron'] + d['rubber']
                        a[1] += 1 if d.get('alive') and (d['quarters'] or d['armories']) else 0
            games[os.path.basename(path)[:-6]] = foes
        stock[run] = games
        n = max(1, len(games))
        print(f'== {run}: {len(games)} games, from minute {FROM // 60}, a game')
        for what, label in (('tower', 'towers'), ('quarters', 'quarters'), ('armory', 'armories')):
            print(f"  {label + ' razed':<19} home {acc[(what, 'home')] / n:5.1f}   forward {acc[(what, 'forward')] / n:5.1f}")
        for what in ('enemy warriors', 'our warriors', 'our peons'):
            print(f"  {what + ' dead':<19} home {acc[(what, 'home')] / n:5.1f}   middle {acc[(what, 'middle')] / n:6.1f}"
                  f"   far {acc[(what, 'far')] / n:6.1f}")
    print('the copies\' warriors, all (per housed copy):')
    print('min ' + ' | '.join(f'{r[:14]:<14}' for r in runs) + ' | games')
    for m in MINUTES:
        keys = [k for k in stock[runs[0]] if all(k in stock[r] and m in stock[r][k] for r in runs)]
        if not keys:
            continue
        row = []
        for r in runs:
            tot = sum(stock[r][k][m][0] for k in keys) / len(keys)
            housed = sum(stock[r][k][m][1] for k in keys) / len(keys)
            row.append(f'{tot:5.0f} ({tot / max(housed, 1e-9):4.1f})  ')
        print(f'{m:3d} ' + ' | '.join(row) + f' | {len(keys)}')


if __name__ == '__main__':
    main(sys.argv[1:])
