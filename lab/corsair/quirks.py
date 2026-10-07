#!/usr/bin/env python3
"""Scans corsair AI logs of a run (batch --logs, or replays) for known pathologies.

usage: python lab/corsair/quirks.py [--who REGEX] RUN [RUN...]   (--who: players whose log header matches, default
corsair or a tag from @v9 on)

Reports, per log of a corsair player:
  woodlock   the sampled wood gatherers stand on the same cells for 3+ samples (WOOD lines, every 120 s)
  starved    the home island has no iron left and the armory holds none (ECON)
  idle_army  150+ free warriors at home and no op launched for 15+ minutes (WAIT and CAMP lines)
  shipless   no ships at all for 10+ minutes after minute 10 (STAT)
  unlanded   op ships that left with warriors still aboard (CAMP "leave with")
  slide      ShipSlideBehaviour seen on our own ships for 2+ minutes (SAIL)
"""
import glob
import os
import re
import sys


def scan(path, who):
    out = {}
    lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
    if not lines or not re.search(who, lines[0]):
        return None
    wood_prev, wood_same = None, 0
    last_launch, free_hi_since = 0.0, None
    shipless_since = None
    slide = 0
    for ln in lines:
        m = re.match(r'\s*([\d.]+) s\d+ (\w+)\s+(.*)', ln)
        if not m:
            continue
        t, topic, text = float(m.group(1)), m.group(2), m.group(3)
        if topic == 'WOOD':
            cells = re.findall(r'(\d+,\d+) tree', text)
            if cells and cells == wood_prev:
                wood_same += 1
                if wood_same >= 2:
                    out['woodlock'] = out.get('woodlock') or t
            else:
                wood_same = 0
            wood_prev = cells
        elif topic == 'ECON':
            mi = re.search(r'stock wood \d+ iron (\d+)', text)
            mn = re.search(r'home iron nodes (\d+)', text)
            if mi and mn and int(mn.group(1)) == 0 and int(mi.group(1)) < 5:
                out['starved'] = out.get('starved') or t
        elif topic == 'CAMP' and (' sails with ' in text or 'march on' in text):
            last_launch = t
        elif topic == 'CAMP' and 'leave with' in text:
            out['unlanded'] = out.get('unlanded', 0) + 1
        elif topic == 'WAIT':
            mf = re.match(r'free (\d+)', text)
            if mf and int(mf.group(1)) >= 150:
                if t - last_launch >= 900:
                    out['idle_army'] = out.get('idle_army') or t
        elif topic == 'STAT':
            ms = re.search(r'ships (\d+)', text)
            if ms and t >= 600:
                if int(ms.group(1)) == 0:
                    shipless_since = shipless_since or t
                    if t - shipless_since >= 600:
                        out['shipless'] = out.get('shipless') or shipless_since
                else:
                    shipless_since = None
        elif topic == 'SAIL' and 'ShipSlideBehaviour' in text:
            slide += 1
            if slide >= 12:
                out['slide'] = out.get('slide') or t
    return out


def main():
    args = sys.argv[1:]
    who = r'name=s\d+:(corsair|@v9|@v1\d)'
    if args[:1] == ['--who']:
        who, args = args[1], args[2:]
    for run in args:
        d = run if os.path.isdir(run) else os.path.join('aisim', 'runs', run)
        logs = sorted(glob.glob(os.path.join(d, 'g', '*-ai-*.log')) + glob.glob(os.path.join(d, 'replay', '*-ai-*.log')))
        counts = {}
        n = 0
        for f in logs:
            q = scan(f, who)
            if q is None:
                continue
            n += 1
            for k, v in q.items():
                counts[k] = counts.get(k, 0) + 1
                print(f'{os.path.basename(f):24s} {k:10s} {v}')
        print(f'{run}: {n} logs; ' + ', '.join(f'{k} {v}' for k, v in sorted(counts.items())))


if __name__ == '__main__':
    main()
