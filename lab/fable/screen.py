"""A two-level screen of eight params at once: 16 runs of a 2^(8-4) design (resolution IV: no main effect is
confounded with a two-factor interaction), every run on the same maps.

    python lab/fable/screen.py plan NAME                # prints the 16 queue jobs (NAME-00 .. NAME-15)
    python lab/fable/screen.py read NAME                # each factor's effect on wins, paired by map

Edit FACTORS for another screen: (label, the spec fragment of the low level or '', of the high level).
"""
import json
import math
import os
import sys

FACTORS = [
    ('satellite', '', 'satellite=true'),
    ('evade', '', 'evade_cells=30'),
    ('iron_real', '', 'iron_real=3'),
    ('route_clear', '', 'gather_route_clear=true'),
    ('reinforce_multi', '', 'reinforce_multi=true'),
    ('capped_min_60', '', 'capped_min_strength=60'),
    ('worn_ratio_35', '', 'worn_ratio=0.35'),
    ('hold_late_4', '', 'hold_late=4'),
]
SEEDS = '21001..21040'
ENEMIES = 15


def design():
    """The 16 runs: levels of A B C D, then E = BCD, F = ACD, G = ABC, H = ABD."""
    runs = []
    for i in range(16):
        a, b, c, d = [(i >> k) & 1 for k in range(4)]
        runs.append([a, b, c, d, (b + c + d) % 2, (a + c + d) % 2, (a + b + c) % 2, (a + b + d) % 2])
    return runs


def spec(levels):
    parts = [f[2] if lv else f[1] for f, lv in zip(FACTORS, levels)]
    parts = [p for p in parts if p]
    return 'fable' + (':' + ','.join(parts) if parts else '')


def main():
    cmd, name = sys.argv[1], sys.argv[2]
    runs = design()
    if cmd == 'plan':
        for i, levels in enumerate(runs):
            print(f'"{name}-{i:02d}|{spec(levels)} vs hard*{ENEMIES}|{SEEDS}"')
        return
    wins = {}
    for i in range(16):
        p = f'aisim/runs/{name}-{i:02d}/results.jsonl'
        if not os.path.exists(p):
            print('missing', p)
            continue
        for line in open(p):
            r = json.loads(line)
            wins[(i, r['key'])] = 1.0 if r['winnerTeam'] == 0 else 0.0
    keys = sorted({k for _, k in wins})
    full = [k for k in keys if all((i, k) in wins for i in range(16))]
    print(f'{len(full)} maps played by all 16 runs ({len(wins)} games)')
    per_run = [sum(wins[(i, k)] for k in full) / max(1, len(full)) for i in range(16)]
    print('wins by run: ' + ' '.join(f'{100 * w:.0f}' for w in per_run) + f'   mean {100 * sum(per_run) / 16:.1f} %')
    for j, f in enumerate(FACTORS):
        diffs = []
        for k in full:
            hi = sum(wins[(i, k)] for i in range(16) if runs[i][j]) / 8
            lo = sum(wins[(i, k)] for i in range(16) if not runs[i][j]) / 8
            diffs.append(hi - lo)
        n = len(diffs)
        mean = sum(diffs) / max(1, n)
        sd = math.sqrt(sum((d - mean) ** 2 for d in diffs) / max(1, n - 1)) if n > 1 else 0.0
        se = sd / math.sqrt(max(1, n))
        print(f'{f[0]:16s} wins {100 * mean:+5.1f} +- {100 * se:.1f}  (z {mean / se if se else 0:+.1f})')


main()
