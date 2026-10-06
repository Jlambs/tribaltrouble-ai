"""Archipelago map survey from corsair:survey=1 logs (SURVEY lines at 1 s).

usage: python lab/corsair/survey.py RUN [RUN...]
Per game: islands with resources, the nearest good island to each start, and how contested it is.
"""
import glob, math, os, re, sys
from collections import Counter

def parse(path):
    players, isles = [], []
    for line in open(path):
        if ' SURVEY ' not in line:
            continue
        w = line.split()
        i = w.index('SURVEY')
        rest = w[i + 1:]
        if rest[0] == 'player':
            players.append(dict(slot=int(rest[1]), team=int(rest[3]), x=int(rest[5]), y=int(rest[6])))
        elif rest[0] == 'isle':
            v = lambda k, o=1: int(rest[rest.index(k) + o])
            isles.append(dict(id=int(rest[1]), area=v('area'), trees=v('trees'), rocks=v('rocks'), iron=v('iron'),
                              cx=v('center'), cy=v('center', 2), shore=v('shore')))
    return players, isles

def good(i):
    return i['trees'] > 25 and i['rocks'] > 10 and i['iron'] > 10

def main():
    stats = Counter()
    rows = []
    for run in sys.argv[1:]:
        for f in sorted(glob.glob(f'aisim/runs/{run}/g/*-ai-s0.log')):
            players, isles = parse(f)
            if not players:
                continue
            n = len(players)
            goods = [i for i in isles if good(i)]
            areas = sorted((i['area'] for i in goods), reverse=True)
            # nearest good island per player by centre distance (rough)
            near = []
            for p in players:
                best = min(goods, key=lambda i: math.hypot(i['cx'] - p['x'], i['cy'] - p['y'])) if goods else None
                near.append(best['id'] if best else None)
            shared = sum(1 for c in Counter(near).values() if c > 1)
            dists = [min(math.hypot(i['cx'] - p['x'], i['cy'] - p['y']) for i in goods) for p in players] if goods else []
            rows.append((run, os.path.basename(f).split('-ai')[0], n, len(isles), len(goods), areas[:4],
                         sum(i['trees'] for i in goods), sum(i['iron'] for i in goods), shared,
                         [round(d) for d in dists]))
    for r in rows:
        print(*r)
    by_n = {}
    for r in rows:
        by_n.setdefault(r[2], []).append(r)
    for n, rs in sorted(by_n.items()):
        print(f'players {n}: games {len(rs)} isles {sum(r[3] for r in rs)/len(rs):.1f} good {sum(r[4] for r in rs)/len(rs):.1f}'
              f' contested-nearest {sum(r[8] for r in rs)/len(rs):.2f}'
              f' centre-dist to nearest good {sum(sum(r[9])/len(r[9]) for r in rs)/len(rs):.0f}')

main()
