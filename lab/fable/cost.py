#!/usr/bin/env python3
"""What a unit of each resource really costs: python lab/fable/cost.py RUN...   (runs played with --logs)

Per period, over the run's logged games: the mean number of iron and rock gatherers (STAT lines, once a minute) and
the gatherer-seconds per unit harvested (census), and the same for wood. The plan's model of a trip is the cyc= pair
of the STAT line; this is what the trips cost with everything that interrupts them.
"""
import glob
import json
import os
import re
import sys


def report(run):
    edges = [10, 14, 18, 22, 26, 30, 36, 44]
    acc = {i: [0.0, 0.0, 0.0, 0.0, 0.0, 0.0] for i in range(len(edges) - 1)}  # iron g-s, iron units, rock g-s, rock units, wood g-s, wood units
    pat = re.compile(r'g (\d+)/(\d+),(\d+)/(\d+),(\d+)/(\d+),')
    for log in glob.glob(f'aisim/runs/{run}/g/s*-ai-s0.log'):
        key = os.path.basename(log)[:-len('-ai-s0.log')]
        harv = {}
        for line in open(f'aisim/runs/{run}/g/{key}.jsonl', encoding='utf-8'):
            if '"census"' in line:
                d = json.loads(line)
                if d['s'] == 0:
                    harv[round(d['t'])] = (d['harvestedIron'], d['harvestedRock'], d['harvestedTree'])
        ts = sorted(harv)
        def at(sec):
            c = [t for t in ts if t <= sec]
            return harv[c[-1]] if c else None
        last_t = ts[-1] if ts else 0
        for line in open(log, encoding='utf-8', errors='replace'):
            if ' STAT ' not in line: continue
            m = pat.search(line)
            if not m: continue
            t = float(line.split()[0])
            for i in range(len(edges) - 1):
                if edges[i] * 60 <= t < edges[i + 1] * 60:
                    acc[i][0] += 60 * int(m.group(3)); acc[i][2] += 60 * int(m.group(5)); acc[i][4] += 60 * int(m.group(1))
        for i in range(len(edges) - 1):
            a, b = edges[i] * 60, min(edges[i + 1] * 60, last_t)
            if b <= a: continue
            ha, hb = at(a), at(b)
            if ha and hb:
                acc[i][1] += hb[0] - ha[0]; acc[i][3] += hb[1] - ha[1]; acc[i][5] += hb[2] - ha[2]
    print(run)
    print('minutes   iron: gatherers  s/unit | rock: gatherers  s/unit | wood: s/unit')
    n = len(glob.glob(f'aisim/runs/{run}/g/s*-ai-s0.log'))
    for i in range(len(edges) - 1):
        v = acc[i]; span = (edges[i + 1] - edges[i]) * 60 * n
        print(f'{edges[i]:2d}-{edges[i+1]:2d}     {v[0]/span:9.1f} {v[0]/max(1,v[1]):8.0f} | {v[2]/span:9.1f} {v[2]/max(1,v[3]):8.0f} | {v[4]/max(1,v[5]):6.0f}')


for name in sys.argv[1:]:
    report(name)
