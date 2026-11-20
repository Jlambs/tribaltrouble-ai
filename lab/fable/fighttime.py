#!/usr/bin/env python3
"""What warriors do while an opposing warrior is near, from runs made with --logs: python lab/fable/fighttime.py RUN...

Reads FightTrace's FIGHT lines (a warrior-second for every warrior outside a tower with an opposing warrior within 8
cells, 8-14 or 14-30, by what it does). Per run: for our attack (roles attack and reinforce), our other warriors
(home) and the enemy's, the warrior-seconds a game in each zone and the share of them spent throwing, walking up to a
hunt's target (close), walking otherwise, blocked on the walk, idle and stunned. Then the same by period.
"""
import collections
import glob
import re
import sys

PART = re.compile(r'(attack|home|enemy) in(\d+): throw (\d+) close (\d+) walk (\d+) blocked (\d+) idle (\d+) stunned (\d+) other (\d+)')
STATES = ['throw', 'close', 'walk', 'blocked', 'idle', 'stunned', 'other']


def show(title, tab, games):
    print(title)
    print(f"  {'':14s} {'w-s a game':>10s} " + ' '.join(f'{s:>8s}' for s in STATES))
    for group in ('attack', 'home', 'enemy'):
        for zone in (8, 14, 30):
            v = tab[(group, zone)]
            total = sum(v)
            if not total:
                continue
            print(f"  {group:7s} in{zone:<4d} {total / games:10.0f} " + ' '.join(f'{100 * x / total:7.0f}%' for x in v))


for run in sys.argv[1:]:
    files = sorted(glob.glob(f'aisim/runs/{run}/g/*-ai-s0.log'))
    games = len(files)
    allt = collections.defaultdict(lambda: [0] * len(STATES))
    by = collections.defaultdict(lambda: collections.defaultdict(lambda: [0] * len(STATES)))
    for f in files:
        for line in open(f, encoding='utf-8', errors='replace'):
            if ' FIGHT ' not in line:
                continue
            t = float(line.split()[0])
            period = '0-15' if t < 900 else '15-30' if t < 1800 else '30-60' if t < 3600 else '60+'
            for m in PART.finditer(line):
                key = (m.group(1), int(m.group(2)))
                for i in range(len(STATES)):
                    n = int(m.group(3 + i))
                    allt[key][i] += n
                    by[period][key][i] += n
    show(f'== {run}: {games} logged games, warrior-seconds a game by the nearest opposing warrior', allt, games)
    for period in ('0-15', '15-30', '30-60', '60+'):
        if period in by:
            show(f'-- minutes {period}', by[period], games)
