#!/usr/bin/env python3
"""What enemy spells cost team A: deaths of slot 0 within W s and R cells after each enemy cast, by spell.

    python lab/gauntlet/spell_losses.py RUN [window_s=25] [radius_cells=25]

Also our total deaths, so the spells' share shows. Deaths events carry counts by kind (r i c p C).
"""
import json
import math
import os
import sys
from collections import defaultdict

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def main(run, window=25.0, radius=25.0):
    casts_n = defaultdict(int)
    lost = defaultdict(lambda: defaultdict(float))
    total = defaultdict(float)
    games = 0
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        keys = [json.loads(l)['key'] for l in f if l.strip()]
    for key in keys:
        path = os.path.join(ROOT, run, 'g', key + '.jsonl')
        if not os.path.exists(path):
            continue
        games += 1
        team = {}
        casts = []
        deaths = []
        with open(path, encoding='utf-8') as g:
            for line in g:
                e = json.loads(line)
                ev = e['ev']
                if ev == 'game':
                    for p in e['players']:
                        team[p['s']] = p['team']
                elif ev == 'cast' and team.get(e['s']) != team[0]:
                    casts.append((e['t'], e['magic'], e['x'], e['y']))
                elif ev == 'deaths' and e['s'] == 0:
                    kinds = {'r': e.get('rock', 0), 'i': e.get('iron', 0), 'c': e.get('rubber', 0), 'p': e.get('peon', 0), 'C': e.get('chief', 0)}
                    deaths.append((e['t'], e['x'], e['y'], e['n'], kinds))
        for t, n in ((d[0], d[3]) for d in deaths):
            total['all'] += n
        used = set()
        for (ct, magic, cx, cy) in casts:
            casts_n[magic] += 1
            for i, (t, x, y, n, kinds) in enumerate(deaths):
                if i in used or t < ct or t > ct + window:
                    continue
                if math.hypot(x - cx, y - cy) > radius:
                    continue
                used.add(i)
                lost[magic]['all'] += n
                for k, v in kinds.items():
                    lost[magic][k] += v
    print('%s: %d games, our deaths %.1f per game' % (run, games, total['all'] / max(1, games)))
    for magic in sorted(casts_n):
        l = lost[magic]
        print('  %-15s %5.1f casts/game  deaths after them %5.1f/game (%s)' % (
            magic, casts_n[magic] / games, l['all'] / games,
            ' '.join('%s %.1f' % (k, l[k] / games) for k in ('p', 'r', 'i', 'c', 'C') if l[k])))


if __name__ == '__main__':
    a = sys.argv[1:]
    main(a[0], float(a[1]) if len(a) > 1 else 25.0, float(a[2]) if len(a) > 2 else 25.0)
