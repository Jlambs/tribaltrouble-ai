#!/usr/bin/env python3
"""How our warriors die by zone, from runs made with --logs: python lab/fable/lostfar.py RUN...

Reads the AI's "lost ROLE at x,y: F foes within 14 (P parked), T towers, M mates within 12, D cells to the army, ..."
lines from minute 12 and tabulates them, a game, per zone (home: within 70 cells of our start, middle: 70-200, far):
by role, by company (mates within 12 cells), by odds (foes within 14 against mates + 1), by enemy towers in reach, by
cells to the army and by the army's mode.
"""
import collections
import glob
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
START = re.compile(r'AI\s+map \d+ cells, start (\d+),(\d+)')
LOST = re.compile(r'^\s*([\d.]+) s0 AI\s+lost (\w+) at (\d+),(\d+): (\d+) foes within 14 \((\d+) parked\), (\d+) towers, '
                  r'(\d+) mates within 12, (-?\d+) cells to the army, (-?\d+) home, (-?\d+) to our nearest building, '
                  r'mode (\w+), state (\w+)')
ZONES = ('home', 'middle', 'far')
FROM = 720


def bucket(v, edges, labels):
    for e, label in zip(edges, labels):
        if v <= e:
            return label
    return labels[-1]


def main(runs):
    for run in runs:
        tables = collections.defaultdict(collections.Counter)
        games = 0
        for log in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-ai-s0.log'))):
            games += 1
            start = None
            for line in open(log, encoding='utf-8'):
                if start is None:
                    m = START.search(line)
                    if m:
                        start = (int(m.group(1)), int(m.group(2)))
                    continue
                m = LOST.match(line)
                if not m or float(m.group(1)) < FROM:
                    continue
                role = m.group(2)
                if role in ('peon', 'gatherer', 'builder', 'shepherd', 'sapper', 'chicken', 'transit', 'idle'):
                    continue
                x, y = int(m.group(3)), int(m.group(4))
                foes, parked, towers, mates, army = (int(m.group(i)) for i in (5, 6, 7, 8, 9))
                dist = ((x - start[0]) ** 2 + (y - start[1]) ** 2) ** .5
                zone = 'home' if dist <= 70 else 'middle' if dist <= 200 else 'far'
                tables['role'][(role, zone)] += 1
                tables['mates within 12'][(bucket(mates, (2, 7, 15, 30), ('0-2', '3-7', '8-15', '16-30', '31+')),
                                           zone)] += 1
                odds = foes / (mates + 1.0)
                tables['foes per one of us'][(bucket(odds, (.5, 1, 2, 4), ('<=0.5', '0.5-1', '1-2', '2-4', '4+')),
                                              zone)] += 1
                tables['foes within 14'][(bucket(foes, (0, 3, 9, 19), ('0', '1-3', '4-9', '10-19', '20+')), zone)] += 1
                tables['enemy towers'][('none' if towers == 0 else '1' if towers == 1 else '2+', zone)] += 1
                tables['cells to the army'][(bucket(army, (-1, 12, 40, 100), ('no army', '0-12', '13-40', '41-100',
                                                                           '100+')), zone)] += 1
                tables['mode'][(m.group(12), zone)] += 1
                tables['parked foes'][('none' if parked == 0 else 'some', zone)] += 1
        n = max(1, games)
        print(f'== {run}: {games} logged games, our warriors lost from minute {FROM // 60}, a game')
        for name, table in tables.items():
            keys = sorted({k for k, _ in table}, key=lambda k: -sum(table[(k, z)] for z in ZONES))
            print(f'  {name:<20}' + ''.join(f'{z:>8}' for z in ZONES))
            for k in keys:
                print(f'    {k:<18}' + ''.join(f'{table[(k, z)] / n:8.1f}' for z in ZONES))


if __name__ == '__main__':
    main(sys.argv[1:])
