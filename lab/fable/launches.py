#!/usr/bin/env python3
"""What an attack buys by its size: python lab/fable/launches.py [--from MIN] RUN...

Needs logged runs. Every attack from its launch line to "attack over": the army at the launch, the warriors lost in
the attack and on the way to it (roles attack, reinforce), the buildings we razed meanwhile (census) and its length,
by the minute of the launch and the size of the army.
"""
import collections
import glob
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
LAUNCH = re.compile(r'^\s*([0-9.]+) s0 AI\s+attack with ([0-9.]+) on (\w+)')
OVER = re.compile(r'^\s*([0-9.]+) s0 AI\s+attack over')
LOST = re.compile(r'^\s*([0-9.]+) s0 AI\s+lost (attack|reinforce) at')
REIN = re.compile(r'^\s*([0-9.]+) s0 AI\s+reinforcing the attack \(([0-9.]+)\) with ([0-9.]+)')


def main(args):
    start = 0
    if '--from' in args:
        i = args.index('--from')
        start = int(args[i + 1])
        del args[i:i + 2]
    rows = []
    for run in args:
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0-ai-s0.log'))):
            razed = []
            for line in open(path[:-len('-ai-s0.log')] + '.jsonl', encoding='utf-8'):
                if '"census"' in line and '"s":0,' in line:
                    d = json.loads(line)
                    if d['s'] == 0:
                        razed.append((d['t'], d['razed'], d['kills']))

            def at(t, k):
                best = 0
                for r in razed:
                    if r[0] <= t:
                        best = r[k]
                    else:
                        break
                return best
            cur = None
            for line in open(path, encoding='utf-8', errors='replace'):
                m = LAUNCH.match(line)
                if m:
                    if cur is None:
                        cur = {'t': float(m.group(1)), 'army': float(m.group(2)), 'lost': 0, 'sent': 0.0}
                    continue
                if cur is None:
                    continue
                if LOST.match(line):
                    cur['lost'] += 1
                    continue
                m = REIN.match(line)
                if m:
                    cur['sent'] += float(m.group(3))
                    continue
                m = OVER.match(line)
                if m:
                    t1 = float(m.group(1))
                    cur['len'] = t1 - cur['t']
                    cur['razed'] = at(t1 + 30, 1) - at(cur['t'], 1)
                    cur['kills'] = at(t1 + 30, 2) - at(cur['t'], 2)
                    rows.append(cur)
                    cur = None
    print(f'{len(rows)} attacks')
    print('launched at   army   | attacks | army  reinforced | lost  razed  per 10 lost | minutes | kills meanwhile')
    for lo, hi in ((0, 20), (20, 30), (30, 45), (45, 60), (60, 90), (90, 400)):
        if hi * 60 <= start * 60:
            continue
        for alo, ahi in ((0, 12), (12, 25), (25, 45), (45, 80), (80, 999)):
            rs = [r for r in rows if lo * 60 <= r['t'] < hi * 60 and alo <= r['army'] < ahi]
            if not rs:
                continue
            n = len(rs)
            lost = sum(r['lost'] for r in rs)
            razed = sum(r['razed'] for r in rs)
            print(f'{lo:3d}-{hi:<3d} min  {alo:3d}-{ahi:<3d} | {n:5d}   | {sum(r["army"] for r in rs) / n:5.1f} '
                  f'{sum(r["sent"] for r in rs) / n:6.1f}     | {lost / n:5.1f} {razed / n:5.2f}  '
                  f'{10.0 * razed / max(1, lost):5.2f}       | {sum(r["len"] for r in rs) / n / 60:5.1f}   | '
                  f'{sum(r["kills"] for r in rs) / n:6.1f}')


if __name__ == '__main__':
    main(sys.argv[1:])
