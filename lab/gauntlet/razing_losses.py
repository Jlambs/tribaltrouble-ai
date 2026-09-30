#!/usr/bin/env python3
"""Units we lose when one of our armories is razed: python lab/gauntlet/razing_losses.py RUN [RUN...] [--seeds A..B]

For every razing of a finished armory of ours (slot 0, the event log's `razed` events without `site`), the census
sample just before it and the first one after: units inside buildings before, and the drop in our unit count across
the step (units inside a razed building vanish with it, LandBuilding.removeDying). Per run: razings, mean inside
before, mean units lost across the step. Needs event logs (RUN/g/<key>.jsonl; the benchmark runs have them).

Reference (raid_evac / raid_bank smokes, 2026-09-30, N=14 seeds 6001..6006): cur7-bench-vs14-a 45.2 lost per razing
(inside 62.9; 49.2 and 67.0 over s6001..6100), raid_evac 22.3 (inside 37.0), the hop arm with raid_bank 23.1 (39.2)
against 34.5 (48.9) without.
"""
import glob
import json
import os
import sys


def main(argv):
    seeds = None
    runs = []
    i = 0
    while i < len(argv):
        if argv[i] == '--seeds':
            lo, hi = map(int, argv[i + 1].split('..'))
            seeds = (lo, hi)
            i += 2
            continue
        runs.append(argv[i])
        i += 1
    for run in runs:
        files = sorted(glob.glob(os.path.join(run, 'g', 's*-0.jsonl')))
        if seeds:
            files = [f for f in files if seeds[0] <= int(os.path.basename(f)[1:].split('-')[0]) <= seeds[1]]
        n = drop_sum = inside_sum = 0
        for f in files:
            census = []
            razings = []
            for line in open(f, encoding='utf-8'):
                r = json.loads(line)
                if r.get('s') != 0:
                    continue
                if r['ev'] == 'census':
                    census.append(r)
                elif r['ev'] == 'razed' and r.get('b') == 'armory' and not r.get('site'):
                    razings.append(r)
            out = []
            for z in razings:
                before = [c for c in census if c['t'] <= z['t']]
                after = [c for c in census if c['t'] > z['t']]
                if not before or not after:
                    continue
                b, a = before[-1], after[0]
                n += 1
                drop_sum += b['units'] - a['units']
                inside_sum += b['inside']
                out.append('%d s: inside %d, units %d -> %d' % (z['t'], b['inside'], b['units'], a['units']))
            if out:
                print('  %s  %s' % (os.path.basename(f)[:-6], '; '.join(out)))
        print('%s: %d armory razings, inside before %.1f, units lost across the step %.1f (per razing)' % (
            os.path.basename(os.path.normpath(run)), n, inside_sum / max(1, n), drop_sum / max(1, n)))


if __name__ == '__main__':
    main(sys.argv[1:])
