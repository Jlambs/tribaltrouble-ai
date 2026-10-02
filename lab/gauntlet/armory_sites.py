#!/usr/bin/env python3
"""Where team A put its first armory, and how that relates to winning.

    python lab/gauntlet/armory_sites.py RUN [RUN...]

Per game: the first armory site of slot 0 (distance in cells from its start, and from the nearest enemy start, as
a share of the start-to-nearest-enemy distance), when it was placed and built, and the result. Then win rates by
distance band. Times are game seconds (event t through gtime).
"""
import json
import math
import os
import sys

import gtime

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def game(run, key, result):
    starts = {}
    placed = built = None
    gf = 1.
    with open(os.path.join(ROOT, run, 'g', key + '.jsonl'), encoding='utf-8') as f:
        for line in f:
            e = json.loads(line)
            if e['ev'] == 'game':
                gf = gtime.factor(e)
                for p in e['players']:
                    starts[p['s']] = (p['x'], p['y'], p['team'])
                continue
            if 't' in e:
                e['t'] *= gf
            if e['ev'] == 'placed' and e.get('s') == 0 and e.get('b') == 'armory' and placed is None:
                placed = (e['t'], e['x'], e['y'])
            elif e['ev'] == 'built' and e.get('s') == 0 and e.get('b') == 'armory' and built is None:
                built = e['t']
    sx, sy, team = starts[0]
    enemies = [(x, y) for s, (x, y, t) in starts.items() if t != team]
    near = min(math.hypot(x - sx, y - sy) for x, y in enemies)
    if placed is None:
        return None
    t, ax, ay = placed
    d = math.hypot(ax - sx, ay - sy)
    de = min(math.hypot(x - ax, y - ay) for x, y in enemies)
    return {'key': key, 'res': result, 'd': d, 'share': d / near, 'enemy': de, 'placed': t, 'built': built}


def main(runs):
    rows = []
    for run in runs:
        with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
            for line in f:
                r = json.loads(line)
                if r.get('result') is None:
                    continue
                g = game(run, r['key'], r['result'])
                if g:
                    rows.append(g)
    bands = [(0, 40), (40, 70), (70, 100), (100, 140), (140, 1000)]
    print('armory distance from start (cells): games, win%, median built s, median cells to nearest enemy start')
    for lo, hi in bands:
        sel = [g for g in rows if lo <= g['d'] < hi]
        if not sel:
            continue
        w = sum(1 for g in sel if g['res'] == 'win')
        bt = sorted(g['built'] for g in sel if g['built'] is not None)
        en = sorted(g['enemy'] for g in sel)
        print('  %3d-%-4d %3d games  win %5.1f%%  built %s  enemy %d' % (lo, hi, len(sel), 100.0 * w / len(sel),
              bt[len(bt) // 2] if bt else '-', en[len(en) // 2]))


if __name__ == '__main__':
    main(sys.argv[1:])
