#!/usr/bin/env python3
"""How the opening plan differs between runs: python lab/fable/plans.py [--maps] BASE RUN...

All runs are logged ones with the "ore map" lines (a minute is enough). Per run: the maps whose armory site differs
from BASE's, and over all maps the armory's walk from the start and the iron nodes within 300 m of walking from it.
With --maps a line per map that differs in any run.
"""
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import oremap  # noqa: E402


def summary(o):
    out = {}
    for seed, m in o.items():
        if m['armory'] is None:
            continue
        out[seed] = (m['armory'][0], m['armory'][1], m['armory'][2], sum(1 for n in m['iron'] if 0 <= n[3] <= 300),
                     sum(1 for n in m['iron'] if 0 <= n[3] <= 200))
    return out


def main(args):
    maps = '--maps' in args
    runs = [a for a in args if a != '--maps']
    data = {r: summary(oremap.ore(r)) for r in runs}
    base = data[runs[0]]
    keys = sorted(k for k in base if all(k in data[r] for r in runs))
    print(f'{len(keys)} maps')
    print(f'{"run":<18} moved | walk: mean  >260  >400  >520 | iron within 300 m: mean  <20  20-29  30-39  40+ | '
          'within 200 m: mean')
    for r in runs:
        d = data[r]
        moved = sum(1 for k in keys if d[k][:2] != base[k][:2])
        walks = [d[k][2] for k in keys]
        iron = [d[k][3] for k in keys]
        print(f'{r:<18} {moved:5d} | {sum(walks) / len(walks):10.0f} {sum(1 for w in walks if w > 260):5d} '
              f'{sum(1 for w in walks if w > 400):5d} {sum(1 for w in walks if w > 520):5d} | '
              f'{sum(iron) / len(iron):23.1f} {sum(1 for i in iron if i < 20):4d} '
              f'{sum(1 for i in iron if 20 <= i < 30):6d} {sum(1 for i in iron if 30 <= i < 40):6d} '
              f'{sum(1 for i in iron if i >= 40):4d} | {sum(d[k][4] for k in keys) / len(keys):18.1f}')
    if maps:
        print('seed  ' + ' | '.join(f'{r[:14]:>14}' for r in runs) + '   (walk from the start m / iron within 300 m)')
        for k in keys:
            if any(data[r][k][:2] != base[k][:2] for r in runs):
                print(k + ' ' + ' | '.join(f'{data[r][k][2]:8d} /{data[r][k][3]:4d}' for r in runs))


if __name__ == '__main__':
    main(sys.argv[1:])
