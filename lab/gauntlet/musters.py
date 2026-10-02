#!/usr/bin/env python3
"""Campaign target choices in logged games: python lab/gauntlet/musters.py RUN

Needs a run played with --logs. For each game: our five nearest copies by start distance (cells, and when each went
out), then every muster of the first 25 minutes (army, defense, and whose start the target is nearest, with that
copy's rank by distance among the copies still in) and the retreats and attack ends between them. Tells whether the
campaign marches past near copies to far ones (Military.chooseTarget; the "muster candidates" log line next to each
muster gives the score parts of the best few candidates). Times are game seconds (gtime: the log's stamps take its game
file's factor); a copy is out at its first out or collapse event.
"""
import json
import math
import os
import re
import sys

import gtime

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
HORIZON = 1500.0
MUSTER = re.compile(r'\s*([\d.]+) s0 AI\s+muster: army ([\d.]+) \+ stock ([\d.]+) vs defense ([\d.]+) at (\d+),(\d+)')
END = re.compile(r'\s*([\d.]+) s0 AI\s+(retreat: .*|attack over.*)')


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    d = os.path.join(ROOT, sys.argv[1], 'g')
    for f in sorted(os.listdir(d)):
        if not f.endswith('-ai-s0.log'):
            continue
        key = f[:-len('-ai-s0.log')]
        record = os.path.join(d, key + '.jsonl')
        g = json.loads(open(record, encoding='utf-8').readline())
        fac = gtime.factor(g)
        me = g['players'][0]
        starts = {p['s']: (p['x'], p['y']) for p in g['players'][1:]}
        outs = {}
        for line in open(record, encoding='utf-8'):
            x = json.loads(line)
            if x['ev'] in gtime.OUTS:
                outs.setdefault(x['s'], x['t'] * fac)

        def cells(s):
            return math.hypot(starts[s][0] - me['x'], starts[s][1] - me['y'])

        rank = sorted(starts, key=cells)
        print(f'== {key}: nearest copies ' + ', '.join(
            f"s{s}:{cells(s):.0f}c" + (f"(out {outs[s]:.0f})" if s in outs else '') for s in rank[:5]))
        for line in open(os.path.join(d, f), encoding='utf-8'):
            m = MUSTER.match(line)
            if not m:
                m2 = END.match(line)
                if m2 and float(m2.group(1)) * fac < HORIZON:
                    print(f'   {float(m2.group(1)) * fac:6.0f}s {m2.group(2)[:70]}')
                continue
            t, army, dfn = float(m.group(1)) * fac, float(m.group(2)), float(m.group(4))
            x, y = int(m.group(5)), int(m.group(6))
            if t > HORIZON:
                continue
            own = min(starts, key=lambda s: math.hypot(starts[s][0] - x, starts[s][1] - y))
            alive = [s for s in rank if s not in outs or outs[s] > t]
            pos = alive.index(own) + 1 if own in alive else -1
            dist = math.hypot(x - me['x'], y - me['y'])
            print(f'   {t:6.0f}s muster {army:5.1f} vs def {dfn:5.1f} -> s{own} ({dist:.0f} cells from our start; '
                  f'rank {pos} among living; nearest living: s{alive[0]} {cells(alive[0]):.0f}c)')


if __name__ == '__main__':
    main()
