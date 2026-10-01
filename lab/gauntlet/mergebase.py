#!/usr/bin/env python3
"""A base run for a late-acting default change without replaying the games it cannot touch:
python lab/gauntlet/mergebase.py NEW OLD_PREFIX LATE_RUN [LATE_RUN...]

NEW gets every row of the runs named OLD_PREFIX-<letters> (e.g. cur7-bench-vs14 -> -a..-d), with the rows (and game
files) of the LATE_RUNs in place of the same keys. Use it when the new default acts only after some game time T and
the LATE_RUNs re-played exactly the old games that lasted past T with it (late.py checks their checksums agree up to T).
The game files are hard-linked where the file system allows, else copied. NEW then pairs like any finished run. Rows
and game files are copied as they are: each keeps its own clock (gtime converts those from before game time per row).
"""
import glob
import json
import os
import shutil
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
new, prefix, late = sys.argv[1], sys.argv[2], sys.argv[3:]


def link(src, dst):
    try:
        os.link(src, dst)
    except OSError:
        shutil.copyfile(src, dst)


out = os.path.join(ROOT, new)
os.makedirs(os.path.join(out, 'g'), exist_ok=True)
rows, src = {}, {}
for d in sorted(glob.glob(os.path.join(ROOT, prefix + '-*'))):
    name = os.path.basename(d)
    if name[len(prefix) + 1:].isalpha() and os.path.exists(os.path.join(d, 'results.jsonl')):
        for x in map(json.loads, open(os.path.join(d, 'results.jsonl'))):
            rows[x['key']], src[x['key']] = x, d
replaced = 0
for r in late:
    d = os.path.join(ROOT, r)
    for x in map(json.loads, open(os.path.join(d, 'results.jsonl'))):
        if x['key'] in rows:
            rows[x['key']], src[x['key']] = x, d
            replaced += 1
with open(os.path.join(out, 'results.jsonl'), 'w') as f:
    for k in sorted(rows):
        x = dict(rows[k], run=new)
        f.write(json.dumps(x) + '\n')
        g = os.path.join(src[k], 'g', k + '.jsonl')
        dst = os.path.join(out, 'g', k + '.jsonl')
        if os.path.exists(g) and not os.path.exists(dst):
            link(g, dst)
first = sorted(glob.glob(os.path.join(ROOT, prefix + '-*', 'run.json')))[0]
meta = json.load(open(first))
meta.update(name=new, merged={'old': prefix, 'late': late})
json.dump(meta, open(os.path.join(out, 'run.json'), 'w'))
wins = sum(1 for x in rows.values() if x.get('result') == 'win')
open(os.path.join(out, 'summary.txt'), 'w').write(
    f'merged base {new}: {len(rows)} games from {prefix}-*, {replaced} replaced from {", ".join(late)}; wins {wins}\n')
print(open(os.path.join(out, 'summary.txt')).read().strip())
