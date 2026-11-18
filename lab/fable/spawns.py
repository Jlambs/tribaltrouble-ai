#!/usr/bin/env python3
"""Are a win's opponents all real: python lab/fable/spawns.py RUN [KEY...]   (default: every win of the run)

Gauntlet qualified its record the same way (lab/gauntlet/NOTES.md, "Broken spawns and the wins"): about 1 copy in 300
is stuck for good by its spawn and never arms, so a win against N copies may be one against N-1. Per game this prints
the closest pair of starts (cells), our three nearest copies, how many copies had an armory by 8, 12 and 20 minutes,
and which copies were still alive at 20 minutes with no armory ("stuck"), with the effective N without them. A copy
put out before minute 20 is not stuck: that is a kill. Start cells come from the game event, the rest from the
'built' and 'out' events of g/KEY.jsonl.
"""
import json
import math
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
ARMED_BY = (8, 12, 20)


def check(run, key):
    starts = {}
    armory = {}
    out = {}
    result = None
    with open(os.path.join(ROOT, run, 'g', key + '.jsonl'), encoding='utf-8') as f:
        for line in f:
            if '"ev":"game"' in line:
                for p in json.loads(line)['players']:
                    starts[p['s']] = (p['x'], p['y'])
            elif '"ev":"built"' in line and '"b":"armory"' in line:
                d = json.loads(line)
                armory.setdefault(d['s'], d['t'])
            elif '"ev":"out"' in line or '"ev":"collapse"' in line:
                d = json.loads(line)
                out.setdefault(d['s'], d['t'])
            elif '"ev":"end"' in line:
                result = json.loads(line)
    copies = [s for s in starts if s != 0]
    pairs = sorted((math.dist(starts[a], starts[b]), a, b) for i, a in enumerate(copies) for b in copies[i + 1:])
    mine = sorted((math.dist(starts[0], starts[c]), c) for c in copies)
    stuck = [c for c in copies if (c not in armory or armory[c] > 1200) and out.get(c, 1e9) > 1200]
    armed = [sum(1 for c in copies if c in armory and armory[c] <= m * 60) for m in ARMED_BY]
    n = len(copies)
    end = result['t'] / 60.0 if result else float('nan')
    print(f'{run} {key}: N={n}, ends at {end:.1f} min ({result.get("end") if result else "?"})')
    print(f'  closest pair of copies {pairs[0][0]:.0f} cells (s{pairs[0][1]}, s{pairs[0][2]}), '
          f'{sum(1 for p in pairs if p[0] < 20)} pairs under 20 cells; nearest to us '
          + ', '.join(f's{c} {d:.0f}' for d, c in mine[:3]))
    print('  copies with an armory by ' + ', '.join(f'{m} min: {a}' for m, a in zip(ARMED_BY, armed)))
    print(f'  stuck (alive at 20 min with no armory): {len(stuck)} '
          + ('(' + ', '.join(f's{c}' for c in stuck) + ')' if stuck else '') + f' -> effective N {n - len(stuck)}')


def main(argv):
    run = argv[0]
    keys = argv[1:]
    if not keys:
        for line in open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8'):
            if line.strip():
                r = json.loads(line)
                if r['result'] == 'win':
                    keys.append(r['key'])
    for key in sorted(keys):
        check(run, key)


if __name__ == '__main__':
    main(sys.argv[1:])
