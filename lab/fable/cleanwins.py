#!/usr/bin/env python3
"""Wins of scan runs, raw and clean: python lab/fable/cleanwins.py RUN...

Per run: the wins, those with no stuck copy (a copy alive at 20 minutes with no armory, lab/fable/spawns.py), those with
no stuck copy and no two copies starting under 20 cells apart (Gauntlet's standard for its record), the effective N of
the wins (copies minus stuck ones) and the clean seeds. Run from the repository root.
"""
import sys, json, os, math, collections
sys.path.insert(0, 'lab/fable')
import spawns
ROOT = spawns.ROOT
for run in sys.argv[1:]:
    keys = [json.loads(l)['key'] for l in open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') if l.strip() and json.loads(l)['result'] == 'win']
    rows = []
    for key in sorted(keys):
        starts, armory, out = {}, {}, {}
        for line in open(os.path.join(ROOT, run, 'g', key + '.jsonl'), encoding='utf-8'):
            if '"ev":"game"' in line:
                for p in json.loads(line)['players']: starts[p['s']] = (p['x'], p['y'])
            elif '"ev":"built"' in line and '"b":"armory"' in line:
                d = json.loads(line); armory.setdefault(d['s'], d['t'])
            elif '"ev":"out"' in line or '"ev":"collapse"' in line:
                d = json.loads(line); out.setdefault(d['s'], d['t'])
        copies = [s for s in starts if s != 0]
        stuck = [c for c in copies if (c not in armory or armory[c] > 1200) and out.get(c, 1e9) > 1200]
        close = sum(1 for i, a in enumerate(copies) for b in copies[i + 1:] if math.dist(starts[a], starts[b]) < 20)
        rows.append((key, len(copies), len(stuck), close))
    n = len(rows)
    nostuck = [r for r in rows if r[2] == 0]
    clean = [r for r in rows if r[2] == 0 and r[3] == 0]
    eff = collections.Counter(r[1] - r[2] for r in rows)
    print(f'{run}: {n} wins; no stuck copy {len(nostuck)}; no stuck copy and no pair under 20 cells {len(clean)}; effective N of the wins {dict(sorted(eff.items()))}')
    print('   clean wins:', ' '.join(r[0][:-2] for r in clean))
