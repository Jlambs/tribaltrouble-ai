"""A two-level screen of several params at once: eight in 16 runs or four in 8 (resolution IV: no main effect is
confounded with a two-factor interaction), every run on the same maps.

    python lab/fable/screen.py plan NAME                # prints the queue jobs (NAME-00, NAME-01, ...)
    python lab/fable/screen.py read NAME                # each factor's effect on wins, paired by map
    python lab/fable/screen.py model NAME [MINUTE]      # the same on proxy.py's win chance at MINUTE (20)

NAME picks the screen from SCREENS; add an entry there for another one.
"""
import json
import math
import os
import sys

# A screen by its NAME: the factors (label, spec fragment of the low level, of the high level) and the maps.
# Eight factors make 16 runs (2^(8-4): E = BCD, F = ACD, G = ABC, H = ABD), four make 8 (2^(4-1): D = ABC).
SCREENS = {
    # third session, on the defaults of then (the three gatherer systems off)
    'scr1': ([
        ('satellite', '', 'satellite=true'),
        ('evade', '', 'evade_cells=30'),
        ('iron_real', '', 'iron_real=3'),
        ('route_clear', '', 'gather_route_clear=true'),
        ('reinforce_multi', '', 'reinforce_multi=true'),
        ('capped_min_60', '', 'capped_min_strength=60'),
        ('worn_ratio_35', '', 'worn_ratio=0.35'),
        ('hold_late_4', '', 'hold_late=4'),
    ], '21001..21040'),
    # the rock line's settings, every run with the line on
    'rk1': ([
        ('value_0.9', 'rock_real=0.6', 'rock_real=0.9'),
        ('share_0.85', 'rock_real_share=0.55', 'rock_real_share=0.85'),
        ('tree_real', '', 'tree_real=2.5'),
        ('iron_real_off', '', 'iron_real=0'),
    ], '21001..21100'),
    # a rock line of the peons a lower hold frees; three factors make the full 8 runs (rk2-00 is the default)
    'rk2': ([
        ('hold_late_3', '', 'hold_late=3'),
        ('rock_line_30', '', 'rock_real=0.6,rock_real_share=0.55,rock_real_peons=30'),
        ('tree_real', '', 'tree_real=2.5'),
    ], '21001..21100'),
    # the towers' place and the shepherds' direction: 12 logged games each could not tell (sb1-00 is the default)
    'sb1': ([
        ('bastion_5', '', 'bastion=5'),
        ('army_weight_5', '', 'shepherd_army_weight=5'),
        ('tower_parallel_3', '', 'tower_parallel_late=3'),
    ], '21001..21100'),
    # the three drills of the pace work, which 12 logged games each could not call (pc1-00 is the default)
    'pc1': ([
        ('raze_12', '', 'raze=12'),
        ('breach', '', 'breach=true'),
        ('hold_multi', '', 'hold_ratio=0.3,hold_multi=true'),
    ], '21001..21100'),
}
FACTORS, SEEDS = SCREENS['scr1']
ENEMIES = 15


def design():
    """The runs' levels: a full design in the first factors, the others their products."""
    k = len(FACTORS)
    runs = []
    if k == 3:
        return [[(i >> n) & 1 for n in range(3)] for i in range(8)]
    if k == 4:
        for i in range(8):
            a, b, c = [(i >> n) & 1 for n in range(3)]
            runs.append([a, b, c, (a + b + c) % 2])
        return runs
    for i in range(16):
        a, b, c, d = [(i >> n) & 1 for n in range(4)]
        runs.append([a, b, c, d, (b + c + d) % 2, (a + c + d) % 2, (a + b + c) % 2, (a + b + d) % 2])
    return runs


def spec(levels):
    parts = [f[2] if lv else f[1] for f, lv in zip(FACTORS, levels)]
    parts = [p for p in parts if p]
    return 'fable' + (':' + ','.join(parts) if parts else '')


def main():
    global FACTORS, SEEDS
    cmd, name = sys.argv[1], sys.argv[2]
    FACTORS, SEEDS = SCREENS[name]
    runs = design()
    n_runs = len(runs)
    if cmd == 'plan':
        for i, levels in enumerate(runs):
            print(f'"{name}-{i:02d}|{spec(levels)} vs hard*{ENEMIES}|{SEEDS}"')
        return
    if cmd == 'model':
        model_read(name, runs, int(sys.argv[3]) if len(sys.argv) > 3 else 20)
        return
    wins = {}
    for i in range(n_runs):
        p = f'aisim/runs/{name}-{i:02d}/results.jsonl'
        if not os.path.exists(p):
            print('missing', p)
            continue
        for line in open(p):
            r = json.loads(line)
            wins[(i, r['key'])] = 1.0 if r['winnerTeam'] == 0 else 0.0
    keys = sorted({k for _, k in wins})
    full = [k for k in keys if all((i, k) in wins for i in range(n_runs))]
    print(f'{len(full)} maps played by all {n_runs} runs ({len(wins)} games)')
    per_run = [sum(wins[(i, k)] for k in full) / max(1, len(full)) for i in range(n_runs)]
    print('wins by run: ' + ' '.join(f'{100 * w:.0f}' for w in per_run) + f'   mean {100 * sum(per_run) / n_runs:.1f} %')
    for j, f in enumerate(FACTORS):
        diffs = []
        for k in full:
            hi = sum(wins[(i, k)] for i in range(n_runs) if runs[i][j]) / (n_runs / 2)
            lo = sum(wins[(i, k)] for i in range(n_runs) if not runs[i][j]) / (n_runs / 2)
            diffs.append(hi - lo)
        n = len(diffs)
        mean = sum(diffs) / max(1, n)
        sd = math.sqrt(sum((d - mean) ** 2 for d in diffs) / max(1, n - 1)) if n > 1 else 0.0
        se = sd / math.sqrt(max(1, n))
        print(f'{f[0]:16s} wins {100 * mean:+5.1f} +- {100 * se:.1f}  (z {mean / se if se else 0:+.1f})')


def model_read(name, runs, minute):
    """Each factor's effect on the state model's win chance at the minute (a game lost by then counts 0)."""
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import proxy

    model, n, acc = proxy.fit(['exam-f2-vs15', 'cnt-vs15'], minute)
    print(f'model at minute {minute}: {n} games, accuracy {acc:.2f}')
    chance = {}
    n_runs = len(runs)
    for i in range(n_runs):
        for k, (feats, _) in proxy.load(f'{name}-{i:02d}', minute).items():
            chance[(i, k)] = proxy.chance(model, feats)
    keys = sorted({k for _, k in chance})
    full = [k for k in keys if all((i, k) in chance for i in range(n_runs))]
    per_run = [sum(chance[(i, k)] for k in full) / max(1, len(full)) for i in range(n_runs)]
    print(f'{len(full)} maps; model by run: ' + ' '.join(f'{100 * w:.0f}' for w in per_run))
    for j, f in enumerate(FACTORS):
        diffs = []
        for k in full:
            hi = sum(chance[(i, k)] for i in range(n_runs) if runs[i][j]) / (n_runs / 2)
            lo = sum(chance[(i, k)] for i in range(n_runs) if not runs[i][j]) / (n_runs / 2)
            diffs.append(hi - lo)
        n = len(diffs)
        mean = sum(diffs) / max(1, n)
        sd = math.sqrt(sum((d - mean) ** 2 for d in diffs) / max(1, n - 1)) if n > 1 else 0.0
        se = sd / math.sqrt(max(1, n))
        print(f'{f[0]:16s} model {100 * mean:+5.1f} +- {100 * se:.1f}  (z {mean / se if se else 0:+.1f})')


main()
