#!/usr/bin/env python3
"""How our warriors die on each ground, from runs made with --logs: python lab/fable/lostfar.py RUN...

Reads the AI's "lost ROLE at x,y: F foes within 14 (P parked), T towers, M mates within 12, D cells to the army, ..."
lines from minute 12 and tabulates them, a game, per ground (as lab/fable/ground.py: within 17 cells of a tower of
ours, else within 40 cells of a building of ours, else within 17 cells of an enemy tower, else within 30 cells of an
enemy building, else the field): by role, by company (mates of the same role within 12 cells), by odds (foes within 14
against mates + 1), by cells to the army and by the army's mode. Then, for the field alone, role by company by foes.
"""
import collections
import glob
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
LOST = re.compile(r'^\s*([\d.]+) s0 AI\s+lost (\w+) at (\d+),(\d+): (\d+) foes within 14 \((\d+) parked\), (\d+) towers, '
                  r'(\d+) mates within 12, (-?\d+) cells to the army, (-?\d+) home, (-?\d+) to our nearest building, '
                  r'mode (\w+), state (\w+)')
GROUNDS = ('tower', 'base', 'field', 'theirs', 'their tower')
WARRIORS = ('attack', 'army', 'reinforce', 'raid', 'tower', 'chase', 'sweep', 'strike')
FROM = 720


def bucket(v, edges, labels):
    for e, label in zip(edges, labels):
        if v <= e:
            return label
    return labels[-1]


def nearest2(x, y, t, buildings, kinds=None):
    best = 1e18
    for (bx, by, kind, t0, t1) in buildings:
        if t0 <= t < t1 and (kinds is None or kind in kinds):
            d = (bx - x) ** 2 + (by - y) ** 2
            if d < best:
                best = d
    return best


def buildings(path):
    ours, theirs, open_b = [], [], {}
    for line in open(path, encoding='utf-8'):
        if '"built"' not in line and '"razed"' not in line:
            continue
        d = json.loads(line)
        if d['ev'] == 'built':
            open_b[(d['s'], d['x'], d['y'])] = (d['b'], d['t'])
        elif d['ev'] == 'razed':
            key = (d['s'], d['x'], d['y'])
            if key in open_b:
                kind, t0 = open_b.pop(key)
                (ours if d['s'] == 0 else theirs).append((d['x'], d['y'], kind, t0, d['t']))
    for (s, x, y), (kind, t0) in open_b.items():
        (ours if s == 0 else theirs).append((x, y, kind, t0, 1e9))
    return ours, theirs


def ground(x, y, t, ours, theirs):
    if nearest2(x, y, t, ours, ('tower',)) <= 17 * 17:
        return 'tower'
    if nearest2(x, y, t, ours) <= 40 * 40:
        return 'base'
    if nearest2(x, y, t, theirs, ('tower',)) <= 17 * 17:
        return 'their tower'
    if nearest2(x, y, t, theirs) <= 30 * 30:
        return 'theirs'
    return 'field'


def main(runs):
    for run in runs:
        tables = collections.defaultdict(collections.Counter)
        field = collections.Counter()
        games = 0
        for log in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-ai-s0.log'))):
            games += 1
            ours, theirs = buildings(log[:-len('-ai-s0.log')] + '.jsonl')
            for line in open(log, encoding='utf-8'):
                m = LOST.match(line)
                if not m or float(m.group(1)) < FROM or m.group(2) not in WARRIORS:
                    continue
                t = float(m.group(1))
                role = m.group(2)
                x, y = int(m.group(3)), int(m.group(4))
                foes, parked, towers, mates, army = (int(m.group(i)) for i in (5, 6, 7, 8, 9))
                g = ground(x, y, t, ours, theirs)
                company = bucket(mates, (2, 7, 15, 30), ('0-2', '3-7', '8-15', '16-30', '31+'))
                foe_b = bucket(foes, (0, 3, 9, 19), ('0', '1-3', '4-9', '10-19', '20+'))
                tables['role'][(role, g)] += 1
                tables['mates within 12'][(company, g)] += 1
                odds = foes / (mates + 1.0)
                tables['foes per one of us'][(bucket(odds, (.5, 1, 2, 4), ('<=0.5', '0.5-1', '1-2', '2-4', '4+')),
                                              g)] += 1
                tables['foes within 14'][(foe_b, g)] += 1
                tables['cells to the army'][(bucket(army, (-1, 12, 40, 100), ('no army', '0-12', '13-40', '41-100',
                                                                           '100+')), g)] += 1
                tables['mode'][(m.group(12), g)] += 1
                tables['minute'][(bucket(t / 60, (20, 30, 40), ('12-20', '20-30', '30-40', '40+')), g)] += 1
                if g == 'field':
                    field[(role, bucket(mates, (2, 15), ('alone 0-2', 'few 3-15', 'many 16+')),
                           bucket(foes, (3, 9), ('0-3', '4-9', '10+')))] += 1
        n = max(1, games)
        print(f'== {run}: {games} logged games, our warriors lost from minute {FROM // 60}, a game')
        for name, table in tables.items():
            keys = sorted({k for k, _ in table}, key=lambda k: -sum(table[(k, z)] for z in GROUNDS))
            print(f'  {name:<20}' + ''.join(f'{z:>12}' for z in GROUNDS))
            for k in keys:
                print(f'    {k:<18}' + ''.join(f'{table[(k, z)] / n:12.1f}' for z in GROUNDS))
        print('  the field: role, company, then foes within 14: 0-3, 4-9, 10+')
        for role in WARRIORS:
            for company in ('alone 0-2', 'few 3-15', 'many 16+'):
                row = [field[(role, company, f)] / n for f in ('0-3', '4-9', '10+')]
                if sum(row) >= .5:
                    print(f'    {role:<10} {company:<10}' + ''.join(f'{v:8.1f}' for v in row))


if __name__ == '__main__':
    main(sys.argv[1:])
