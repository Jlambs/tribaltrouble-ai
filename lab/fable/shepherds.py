#!/usr/bin/env python3
"""What a shepherd's life buys: python lab/fable/shepherds.py RUN...   (runs with AI logs)

A life runs from `shepherd of sK:hard recruited` to its `lost` line (or the end of the log). Per life: what kind of
peon was recruited, whether it reached its spot, how long it lived and how many of its copy's launches went at a
shepherd meanwhile (`launch of sK:hard ... target unit_by_shepherd`). Prints the lives by the waves they drew, by the
recruit's kind and by the minute of the game, a game's means.
"""
import collections
import glob
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
REC = re.compile(r'^\s*([\d.]+) s\d+ AI\s+shepherd of (s\d+):hard recruited: (\S+) peon')
AT = re.compile(r'^\s*([\d.]+) s\d+ AI\s+shepherd of (s\d+):hard at spot')
LOST = re.compile(r'^\s*([\d.]+) s\d+ AI\s+shepherd of (s\d+):hard lost .*recruited (\d+) s ago, (\w+),')
LAUNCH = re.compile(r'^\s*([\d.]+) s\d+ AI\s+launch of (s\d+):hard from .*?: target (\w+),')


def main():
    lives = []
    games = 0
    for run in sys.argv[1:]:
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', '*-ai-s*.log'))):
            games += 1
            live = {}
            end = 0.0
            for line in open(path, encoding='utf-8'):
                if ' AI ' not in line:
                    continue
                m = REC.match(line)
                if m:
                    live[m.group(2)] = dict(t=float(m.group(1)), kind=m.group(3), spot=False, waves=0, lost=None,
                                            state=None)
                    lives.append(live[m.group(2)])
                    continue
                m = AT.match(line)
                if m:
                    if m.group(2) in live:
                        live[m.group(2)]['spot'] = True
                    continue
                m = LAUNCH.match(line)
                if m:
                    end = float(m.group(1))
                    if m.group(2) in live and m.group(3) == 'unit_by_shepherd':
                        live[m.group(2)]['waves'] += 1
                    continue
                m = LOST.match(line)
                if m and m.group(2) in live:
                    life = live.pop(m.group(2))
                    life['lost'] = float(m.group(1))
                    life['state'] = m.group(4)
    dead = [x for x in lives if x['lost'] is not None]
    print(f'{games} games: {len(lives) / games:.1f} shepherds recruited a game, {len(dead) / games:.1f} lost')

    def table(title, key, order=None):
        groups = collections.defaultdict(list)
        for x in dead:
            groups[key(x)].append(x)
        print(title)
        for k in (order or sorted(groups, key=lambda k: -len(groups[k]))):
            g = groups.get(k)
            if not g:
                continue
            ages = sorted(x['lost'] - x['t'] for x in g)
            print(f'  {str(k):14s} {len(g) / games:6.1f} a game = {100 * len(g) / len(dead):4.1f} %; reached a spot '
                  f'{100 * sum(x["spot"] for x in g) / len(g):3.0f} %; lived a median {ages[len(ages) // 2]:4.0f} s; '
                  f'waves drawn {sum(x["waves"] for x in g) / len(g):.2f} a life')

    table('by the waves it drew before it was lost:', lambda x: min(x['waves'], 3), [0, 1, 2, 3])
    table('by the peon recruited:', lambda x: x['kind'])
    table('by the state it was lost in:', lambda x: x['state'])
    table('by the minute it was recruited:', lambda x: '0-5' if x['t'] < 300 else '5-10' if x['t'] < 600 else '10-20'
          if x['t'] < 1200 else '20-40' if x['t'] < 2400 else '40+', ['0-5', '5-10', '10-20', '20-40', '40+'])
    none = [x for x in dead if x['waves'] == 0]
    print(f'lost with no wave drawn: {len(none) / games:.1f} a game; of them {100 * sum(not x["spot"] for x in none) / len(none):.0f} % '
          f'never reached a spot, median life {sorted(x["lost"] - x["t"] for x in none)[len(none) // 2]:.0f} s')


if __name__ == '__main__':
    main()
