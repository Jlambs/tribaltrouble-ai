#!/usr/bin/env python3
"""The iron around the planned armory against the result: python lab/fable/oremap.py ORERUN RUN... [--maps]

ORERUN is a logged run (a minute is enough) whose logs hold the "ore map" lines and the plan; RUN... are full runs of
the same maps (their results and censuses). Per map: iron nodes within 60/100/150/200/300/400 m of walking from the
armory site, how many of them no enemy start is nearer to (straight line) than we are, and the same from the start.
Then the wins, the iron by 12 min and the warriors at 12 min by each count.
"""
import glob
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import openwin  # noqa: E402

ROOT = openwin.ROOT
BANDS = (60, 100, 150, 200, 300, 400)


def ore(run):
    out = {}
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0-ai-s0.log'))):
        seed = os.path.basename(path)[1:6]
        iron = []
        rock = []
        armory = None
        start = None
        for line in open(path, encoding='utf-8'):
            if ' ore map iron' in line or ' ore map rock' in line:
                nodes = [tuple(int(v) for v in n.split(',')) for n in line.split('ore map ')[1].split()[1:]]
                if ' ore map iron' in line:
                    iron = nodes
                else:
                    rock = nodes
                    break
            elif ' plan armory#' in line:
                m = re.search(r'at (\d+),(\d+) .*from start=(\d+)m', line)
                armory = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
            elif ' AI    map ' in line:
                m = re.search(r'start (\d+),(\d+)', line)
                start = (int(m.group(1)), int(m.group(2)))
        enemies = []
        game = os.path.join(ROOT, run, 'g', f's{seed}-0.jsonl')
        with open(game, encoding='utf-8') as f:
            d = json.loads(f.readline())
            for p in d['players']:
                if isinstance(p, dict) and p['s'] != 0:
                    enemies.append((p['x'], p['y']))
        out[seed] = {'iron': iron, 'rock': rock, 'armory': armory, 'start': start, 'enemies': enemies}
    return out


def features(o):
    f = {}
    arm = o['armory']
    if arm is None:
        return None
    f['arm_walk'] = arm[2]
    f['enemy_arm'] = min(2 * math.hypot(e[0] - arm[0], e[1] - arm[1]) for e in o['enemies'])
    for kind in ('iron', 'rock'):
        for b in BANDS:
            ours = [n for n in o[kind] if 0 <= n[3] <= b]
            f[f'{kind}{b}'] = len(ours)
            # free: no enemy start is nearer (straight line, meters) than our walk
            f[f'{kind}{b}free'] = sum(1 for n in ours if all(2 * math.hypot(e[0] - n[0], e[1] - n[1]) > n[3]
                                                             for e in o['enemies']))
            f[f'{kind}{b}start'] = sum(1 for n in o[kind] if 0 <= n[2] <= b)
    # walk to the 5th, 10th, 20th, 30th nearest iron node from the armory site
    walks = sorted(n[3] for n in o['iron'] if n[3] >= 0)
    for k in (3, 5, 10, 20, 30):
        f[f'iron_k{k}'] = walks[k - 1] if len(walks) >= k else 900
    walks = sorted(n[2] for n in o['iron'] if n[2] >= 0)
    for k in (5, 10, 20, 30):
        f[f'start_k{k}'] = walks[k - 1] if len(walks) >= k else 900
    return f


def table(rows, col, cuts, outs=('won', 'iron8', 'iron12', 'war12', 'arm')):
    print(f'  by {col}:')
    lo = -1e9
    for hi in list(cuts) + [1e9]:
        sel = [r for r in rows if lo <= r[col] < hi]
        if sel:
            print(f'    {lo if lo > -1e9 else 0:5.0f}-{hi if hi < 1e9 else 999:<5.0f} n {len(sel):3d}  '
                  + '  '.join(f'{c} {sum(min(r[c], 600) for r in sel) / len(sel) * (100 if c == "won" else 1):5.1f}'
                              for c in outs))
        lo = hi


def corr(rows, a, b):
    n = len(rows)
    ma = sum(r[a] for r in rows) / n
    mb = sum(r[b] for r in rows) / n
    sab = sum((r[a] - ma) * (r[b] - mb) for r in rows)
    saa = sum((r[a] - ma) ** 2 for r in rows)
    sbb = sum((r[b] - mb) ** 2 for r in rows)
    return sab / math.sqrt(saa * sbb) if saa > 0 and sbb > 0 else 0


def main(args):
    maps = '--maps' in args
    args = [a for a in args if a != '--maps']
    o = ore(args[0])
    rows = []
    for run in args[1:]:
        for r in openwin.load(run):
            f = features(o[r['seed']]) if r['seed'] in o else None
            if f is None:
                continue
            r.update(f)
            rows.append(r)
    print(f'{len(rows)} games, {sum(r["won"] for r in rows)} won')
    feats = [k for k in rows[0] if k.startswith(('iron6', 'iron1', 'iron2', 'iron3', 'iron4', 'iron_k', 'start_k',
                                                 'rock1', 'rock2', 'rock4', 'enemy_arm', 'arm_walk'))
             and k not in ('iron12', 'iron8')]
    print('correlations with won / iron12 / war12 / iron8:')
    for k in feats:
        print(f'  {k:<14} {corr(rows, k, "won"):+.2f} {corr(rows, k, "iron12"):+.2f} {corr(rows, k, "war12"):+.2f} '
              f'{corr(rows, k, "iron8"):+.2f}')
    table(rows, 'iron100', (2, 4, 6, 8, 11, 15))
    table(rows, 'iron200', (5, 10, 15, 20, 25, 30, 40))
    table(rows, 'iron200free', (3, 6, 10, 15, 20, 30))
    table(rows, 'iron300', (10, 20, 30, 40, 50, 65))
    table(rows, 'iron300free', (5, 10, 15, 20, 30, 40))
    table(rows, 'iron400', (20, 30, 40, 50, 65, 80))
    table(rows, 'iron_k10', (60, 80, 100, 130, 160, 200, 300))
    table(rows, 'iron_k20', (100, 130, 160, 200, 250, 300, 400))
    table(rows, 'iron_k30', (130, 160, 200, 250, 300, 400, 500))
    table(rows, 'start_k20', (100, 150, 200, 250, 300, 400, 500))
    table(rows, 'iron200start', (2, 5, 10, 15, 20, 30))
    table(rows, 'iron400start', (10, 20, 30, 40, 50, 65))
    table(rows, 'enemy_arm', (80, 100, 120, 140, 160, 200))
    table(rows, 'arm_walk', (50, 80, 110, 150, 200, 250))
    if maps:
        cols = ('won', 'arm_walk', 'enemy_arm', 'iron100', 'iron200', 'iron200free', 'iron300', 'iron400',
                'iron_k10', 'iron_k20', 'iron_k30', 'start_k20', 'iron200start', 'iron400start', 'arm', 'iron8',
                'iron12', 'war12')
        print('seed  ' + ' '.join(f'{c:>12}' for c in cols))
        for r in sorted(rows, key=lambda r: r['iron200']):
            print(r['seed'] + ' ' + ' '.join(f'{r[c]:12.0f}' for c in cols))


if __name__ == '__main__':
    main(sys.argv[1:])
