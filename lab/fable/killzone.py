#!/usr/bin/env python3
"""Waves aimed under our towers, from a run made with --logs: python lab/fable/killzone.py RUN [MINUTES]

Every launch (Shepherd's "launch of" log line) whose target cell lies within 15 cells of a standing tower of ours
before MINUTES (30): by what it was aimed at (a shepherd, a tower, ...), how far along an axis the nearest tower
stands (under 9: the wave sees it), and the warriors of both sides that die within 12 / 16 cells of the target in the
next 150 s. Then the totals by target kind.
"""
import collections
import glob
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
LAUNCH = re.compile(r'^\s*([\d.]+) s0 AI\s+launch of (\S+) from (\d+),(\d+) \(.*?\) to (\d+),(\d+): target (\w+)')


def main(run, minutes):
    total = collections.defaultdict(lambda: [0, 0, 0, 0])
    games = 0
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
        key = os.path.basename(path)[:-6]
        log = os.path.join(ROOT, run, 'g', key + '-ai-s0.log')
        if not os.path.exists(log):
            continue
        games += 1
        towers = {}
        deaths = []
        for line in open(path, encoding='utf-8'):
            d = json.loads(line)
            if d['ev'] == 'built' and d['s'] == 0 and d['b'] == 'tower':
                towers[(d['x'], d['y'])] = [d['t'], 1e9]
            elif d['ev'] == 'razed' and d['s'] == 0 and d['b'] == 'tower' and (d['x'], d['y']) in towers:
                towers[(d['x'], d['y'])][1] = d['t']
            elif d['ev'] == 'deaths':
                deaths.append(d)
        for line in open(log, encoding='utf-8'):
            m = LAUNCH.match(line)
            if not m:
                continue
            t = float(m.group(1))
            if t > minutes * 60:
                break
            tx, ty = int(m.group(5)), int(m.group(6))
            near = [(x, y) for (x, y), (t0, t1) in towers.items()
                    if t0 <= t <= t1 and (x - tx) ** 2 + (y - ty) ** 2 <= 15 * 15]
            if not near:
                continue
            axis = min(max(abs(x - tx), abs(y - ty)) for x, y in near)
            foes = sum(d['rock'] + d['iron'] + d['rubber'] for d in deaths
                       if d['s'] != 0 and 0 <= d['t'] - t <= 150 and (d['x'] - tx) ** 2 + (d['y'] - ty) ** 2 <= 12 * 12)
            ours = sum(d['rock'] + d['iron'] + d['rubber'] for d in deaths
                       if d['s'] == 0 and 0 <= d['t'] - t <= 150 and (d['x'] - tx) ** 2 + (d['y'] - ty) ** 2 <= 16 * 16)
            kind = m.group(7) + (' clear' if axis >= 9 else ' seen')
            row = total[kind]
            row[0] += 1
            row[1] += foes
            row[2] += ours
            row[3] += len(near)
    print(f'{run}: {games} logged games, launches aimed within 15 cells of a tower of ours before minute {minutes}')
    for kind, (n, foes, ours, cover) in sorted(total.items(), key=lambda kv: -kv[1][0]):
        print(f'  {kind:28} {n / games:5.1f} a game, towers in reach {cover / n:.1f}; within 150 s enemy warriors dead '
              f'there {foes / n:5.1f}, ours {ours / n:4.1f} a launch')


if __name__ == '__main__':
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 30)
