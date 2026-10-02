#!/usr/bin/env python3
"""Late-acting arms against the benchmark rows they re-run: python lab/gauntlet/late.py ARM BASE_PREFIX [T]

ARM re-plays a subset of the base's games (e.g. the cur7 benchmark games alive at 40 min) with a change that acts only
from T game seconds (default 2400). BASE_PREFIX names the base runs (cur7-bench-vs14 matches cur7-bench-vs14-a..d);
rows pair by key. Prints wins base -> arm, flips both ways, minutes-to-win for games won by both, the arm's result for
base losses and draws, and a check that each pair's census checksums agree up to T (the arm must not act earlier).
All times are game time at every --speed (gtime converts rows and game files from before the harness counted it).
Those old non-normal files took the census every 30 x factor game s (2 min at ludicrous): the check reads the last.
"""
import glob
import json
import os
import statistics
import sys

import gtime

arm, prefix = sys.argv[1], sys.argv[2]
T = float(sys.argv[3]) if len(sys.argv) > 3 else 2400.0
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def rows(run):
    return {x['key']: gtime.row(dict(x, run=run))
            for x in map(json.loads, open(os.path.join(ROOT, run, 'results.jsonl')))}


def checksum_at(run, key, t):
    last = None
    scale = 1.0  # game seconds per t of the file (gtime.factor of its header, the first line)
    for line in open(os.path.join(ROOT, run, 'g', key + '.jsonl')):
        if line.startswith('{"ev":"game"'):
            scale = gtime.factor(json.loads(line))
        elif '"census"' in line and '"s":0,' in line:
            e = json.loads(line)
            if e['t'] * scale > t:
                break
            last = e.get('checksum')
    return last


base = {}
for d in sorted(glob.glob(os.path.join(ROOT, prefix + '-*'))):
    name = os.path.basename(d)
    if os.path.exists(os.path.join(d, 'results.jsonl')) and name[len(prefix) + 1:].isalpha():
        base.update(rows(name))
a = rows(arm)
keys = sorted(set(a) & set(base))
res = lambda x: x.get('result', '?')
bw = [k for k in keys if res(base[k]) == 'win']
aw = [k for k in keys if res(a[k]) == 'win']
print(f'{arm}: {len(keys)} games paired with {prefix}-* | wins {len(bw)} -> {len(aw)}')
lost = [k for k in bw if res(a[k]) != 'win']
gained = [k for k in aw if res(base[k]) != 'win']
print(f'  wins lost {len(lost)} {lost}')
print(f'  wins gained {len(gained)} {[(k, res(base[k])) for k in gained]}')
both = [k for k in bw if res(a[k]) == 'win']
if both:
    d = [(a[k]['t'] - base[k]['t']) / 60 for k in both]
    print(f'  won by both: {len(both)}, minutes to win base {statistics.median(base[k]["t"] / 60 for k in both):.0f} -> arm '
          f'{statistics.median(a[k]["t"] / 60 for k in both):.0f} (median change {statistics.median(d):+.0f}, mean {statistics.mean(d):+.0f})')
    print('   ' + ' '.join(f'{k}:{base[k]["t"] / 60:.0f}->{a[k]["t"] / 60:.0f}' for k in both))
for r, rs in (('loss', 'losses'), ('draw', 'draws')):
    ks = [k for k in keys if res(base[k]) == r]
    if ks:
        c = {}
        for k in ks:
            c[res(a[k])] = c.get(res(a[k]), 0) + 1
        dt = statistics.median((a[k]['t'] - base[k]['t']) / 60 for k in ks)
        print(f'  base {rs} {len(ks)}: arm {c}, median survival change {dt:+.1f} min')
bad = [k for k in keys if checksum_at(a[k]['run'], k, T) != checksum_at(base[k]['run'], k, T)]
print(f'  checksums up to {T:.0f} s: {len(keys) - len(bad)} of {len(keys)} agree' + (f'; DIFFER {bad[:10]}' if bad else ''))
