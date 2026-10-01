#!/usr/bin/env python3
"""Freeze path-(c) cuts in logged games: python lab/gauntlet/cuts.py RUN

Needs a run played with --logs. Each armory cut the freeze squad started (FREEZE "cut on" lines): how far the copy's
armory site stood from its first quarters (the game record's built event), how many of its peons were outside, and
how the cut ended (frozen, the copy out, given up and why, or unfinished); then how many cuts froze or put the copy
out with the site within 15 cells of the quarters and beyond (lab/gauntlet/NOTES.md: cuts fail on peons outside, not
on site distance). A cut's time (its log stamp) is game seconds (gtime: the game file's factor).
"""
import json
import math
import os
import re
import sys

import gtime

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
CUT = re.compile(r'([\d.]+) s0 FREEZE cut on (\S+): armory site at (\d+),(\d+), (\d+) peons outside')


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    d = os.path.join(ROOT, sys.argv[1], 'g')
    rows = []
    for f in sorted(os.listdir(d)):
        if not f.endswith('-ai-s0.log'):
            continue
        key = f[:-len('-ai-s0.log')]
        quarters = {}  # copy name -> (x, y) of its first quarters (built event)
        names = {}
        fac = 1.0
        for line in open(os.path.join(d, key + '.jsonl'), encoding='utf-8'):
            x = json.loads(line)
            if x['ev'] == 'game':
                fac = gtime.factor(x)  # the log's stamps share the game file's factor
                for p in x['players']:
                    names[p['s']] = p['name']
            if x['ev'] == 'built' and x.get('b') == 'quarters' and x['s'] != 0:
                quarters.setdefault(names.get(x['s'], ''), (x['x'], x['y']))
        cut = None
        for line in open(os.path.join(d, f), encoding='utf-8'):
            m = CUT.search(line)
            if m:
                t, who = float(m.group(1)) * fac, m.group(2)
                ax, ay, n = int(m.group(3)), int(m.group(4)), int(m.group(5))
                q = quarters.get(who)
                dist = math.hypot(ax - q[0], ay - q[1]) if q else -1
                cut = dict(key=key, t=t, who=who, dist=dist, outside=n)
                continue
            if cut and ' FREEZE ' in line:
                if 'froze ' in line:
                    cut['outcome'] = 'frozen'
                elif 'given up' in line:
                    cut['outcome'] = 'failed: ' + line.split('given up in')[1].strip()[:60]
                elif 'is out' in line:
                    cut['outcome'] = 'out'
                else:
                    continue
                rows.append(cut)
                cut = None
        if cut:
            cut['outcome'] = 'unfinished'
            rows.append(cut)
    for r in sorted(rows, key=lambda r: r['dist']):
        print(f"{r['key']:8s} {r['who']:10s} site {r['dist']:5.1f} cells from quarters, {r['outside']:2d} outside "
              f"-> {r['outcome']}")
    near = [r for r in rows if 0 <= r['dist'] <= 15]
    far = [r for r in rows if r['dist'] > 15]
    for name, part in (('<=15', near), ('>15', far)):
        ok = sum(r['outcome'] in ('frozen', 'out') for r in part)
        print(f'{name}: {ok} of {len(part)} froze or out')


if __name__ == '__main__':
    main()
