#!/usr/bin/env python3
"""The opening per map against the result: python lab/fable/openwin.py [--maps] [--csv FILE] RUN...

Per game of the runs (all pooled): where the first quarters and the armory went (cells from the start), when each
was placed and stood, when the second to sixth quarters stood, our peons lost in the first 2 and 4 minutes, units at
3, 5 and 8 minutes, warriors at 8 and 10, and whether the game was won. Then the wins by each milestone's quintile.
Needs no logs.
"""
import glob
import json
import math
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def load(run):
    rows = []
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
        seed = os.path.basename(path)[1:6]
        start = None
        enemies = []
        placed = []
        built = []
        census = {}
        dead = [0, 0]
        won = None
        for line in open(path, encoding='utf-8'):
            if line.startswith('{"ev":"game"'):
                d = json.loads(line)
                for p in d['players']:
                    if isinstance(p, dict):
                        if p['s'] == 0:
                            start = (p['x'], p['y'])
                        else:
                            enemies.append((p['x'], p['y']))
            elif '"s":0,' in line:
                if '"ev":"placed"' in line or '"ev":"built"' in line:
                    d = json.loads(line)
                    (placed if d['ev'] == 'placed' else built).append((d['t'], d['b'], d['x'], d['y']))
                elif '"ev":"census"' in line:
                    d = json.loads(line)
                    if d['t'] <= 905:
                        census[round(d['t'] / 30) * 30] = d
                elif '"ev":"deaths"' in line:
                    d = json.loads(line)
                    if d['t'] <= 120:
                        dead[0] += d['peon']
                    if d['t'] <= 240:
                        dead[1] += d['peon']
            elif line.startswith('{"ev":"end"'):
                d = json.loads(line)
                won = 1 if d.get('winnerTeam') == 0 and d.get('end') == 'elim' else 0
        if start is None or won is None:
            continue

        def dist(x, y):
            return math.hypot(x - start[0], y - start[1])

        qb = sorted(b for b in built if b[1] == 'quarters')
        ab = sorted(b for b in built if b[1] == 'armory')
        tb = sorted(b for b in built if b[1] == 'tower')
        qp = sorted(p for p in placed if p[1] == 'quarters')
        ap = sorted(p for p in placed if p[1] == 'armory')

        def units(t):
            c = census.get(t)
            return c['units'] if c else 0

        def warriors(t):
            c = census.get(t)
            return c['rock'] + c['iron'] + c['rubber'] if c else 0

        def iron(t):
            c = census.get(t)
            return c['harvestedIron'] if c else 0

        near = sorted(math.hypot(e[0] - start[0], e[1] - start[1]) for e in enemies)
        row = {
            'run': run, 'seed': seed, 'won': won,
            'q1_cells': dist(qp[0][2], qp[0][3]) if qp else 999,
            'q1_placed': qp[0][0] if qp else 999,
            'q1': qb[0][0] if qb else 999,
            'q2': qb[1][0] if len(qb) > 1 else 999,
            'q4': qb[3][0] if len(qb) > 3 else 999,
            'q6': qb[5][0] if len(qb) > 5 else 999,
            'arm_cells': dist(ap[0][2], ap[0][3]) if ap else 999,
            'arm_placed': ap[0][0] if ap else 999,
            'arm': ab[0][0] if ab else 999,
            'tower1': tb[0][0] if tb else 999,
            'dead2': dead[0], 'dead4': dead[1],
            'units3': units(180), 'units5': units(300), 'units8': units(480),
            'war8': warriors(480), 'war10': warriors(600), 'war12': warriors(720),
            'iron8': iron(480), 'iron12': iron(720),
            'enemy1': near[0] if near else 999, 'enemy3': near[2] if len(near) > 2 else 999,
        }
        rows.append(row)
    return rows


COLS = ('q1_cells', 'q1_placed', 'q1', 'q2', 'q4', 'q6', 'arm_cells', 'arm_placed', 'arm', 'tower1', 'dead2',
        'dead4', 'units3', 'units5', 'units8', 'war8', 'war10', 'war12', 'iron8', 'iron12', 'enemy1', 'enemy3')


def main(args):
    maps = '--maps' in args
    csv = None
    runs = []
    it = iter(a for a in args if a != '--maps')
    for a in it:
        if a == '--csv':
            csv = next(it)
        else:
            runs.append(a)
    rows = []
    for r in runs:
        rows += load(r)
    n = len(rows)
    wins = sum(r['won'] for r in rows)
    print(f'{n} games, {wins} won ({100 * wins / n:.1f} %)')
    if csv:
        with open(csv, 'w', encoding='utf-8') as f:
            f.write('run,seed,won,' + ','.join(COLS) + '\n')
            for r in rows:
                f.write(f"{r['run']},{r['seed']},{r['won']}," + ','.join(f'{r[c]:.0f}' for c in COLS) + '\n')
    if maps:
        print('seed  won ' + ' '.join(f'{c:>9}' for c in COLS))
        for r in rows:
            print(f"{r['seed']} {r['won']:3d}  " + ' '.join(f'{r[c]:9.0f}' for c in COLS))
    print('wins by quintile of each column (low to high): bounds, then win %')
    for c in COLS:
        s = sorted(rows, key=lambda r: r[c])
        parts = [s[i * n // 5:(i + 1) * n // 5] for i in range(5)]
        mean_w = wins / n
        mx = sum(r[c] for r in rows) / n
        sxy = sum((r[c] - mx) * (r['won'] - mean_w) for r in rows)
        sxx = sum((r[c] - mx) ** 2 for r in rows)
        syy = sum((r['won'] - mean_w) ** 2 for r in rows)
        corr = sxy / math.sqrt(sxx * syy) if sxx > 0 and syy > 0 else 0
        print(f'  {c:<11}' + ' '.join(f'{p[0][c]:5.0f}-{p[-1][c]:<5.0f}' for p in parts) + '  | '
              + ' '.join(f'{100 * sum(r["won"] for r in p) / len(p):4.0f}' for p in parts) + f'  | r {corr:+.2f}')


if __name__ == '__main__':
    main(sys.argv[1:])
