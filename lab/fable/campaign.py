#!/usr/bin/env python3
"""The campaign's rhythm, from runs made with --logs: python lab/fable/campaign.py [MINUTES] RUN...

For every move of the army from one copy to the next before MINUTES (22): the gap from the old copy's last quarters or
armory to the new copy's first razed building, split into lingering at the old copy (until the army's centre has
moved 12 cells), the march (until the centre is within 20 cells of that first building) and the time from arrival to
the first raze; then the raze itself (the copy's first razed building to its last quarters or armory). Medians and
means, the straight distance, and the march's speed. The army's centre comes from the AI's "battle:" lines (4 s).
"""
import collections
import glob
import json
import os
import re
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
BATTLE = re.compile(r'^\s*([\d.]+) s0 AI\s+battle: army (\d+) \(([\d.]+), (\d+) far, (\d+) fighting, (\d+) stunned\) '
                    r'at (\d+),(\d+) h\S+ (\d+)m to target; near: (\d+) warriors')


def main(run, maxmin):
    rows = []
    copies = []
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
        razed = collections.defaultdict(list)
        for line in open(path, encoding='utf-8'):
            if '"ev":"razed"' in line:
                d = json.loads(line)
                if d['s'] != 0 and not d.get('site'):
                    razed[d['s']].append((d['t'], d['b'], d['x'], d['y']))
        battle = []
        for line in open(path[:-6] + '-ai-s0.log', encoding='utf-8'):
            m = BATTLE.match(line)
            if m:
                battle.append((float(m.group(1)), int(m.group(2)), int(m.group(4)), int(m.group(5)), int(m.group(7)),
                               int(m.group(8))))
        done = []
        for s, lst in razed.items():
            core = [r for r in lst if r[1] in ('quarters', 'armory')]
            if core:
                done.append((max(r[0] for r in core), min(r[0] for r in lst), s, lst))
        done.sort()
        copies.append(sum(1 for d in done if d[0] <= maxmin * 60))
        prev = None
        for lastcore, first, s, lst in done:
            if lastcore > maxmin * 60:
                break
            if prev is not None and first > prev:
                t0, t1 = prev, first
                fx, fy = min(lst)[2], min(lst)[3]
                seg = [b for b in battle if t0 <= b[0] <= t1]
                if len(seg) >= 3:
                    x0, y0 = seg[0][4], seg[0][5]
                    arrive = next((b[0] for b in seg if (b[4] - fx) ** 2 + (b[5] - fy) ** 2 <= 20 * 20), t1)
                    depart = next((b[0] for b in seg if (b[4] - x0) ** 2 + (b[5] - y0) ** 2 > 12 * 12), t1)
                    depart = min(depart, arrive)
                    dist = ((fx - x0) ** 2 + (fy - y0) ** 2) ** .5
                    rows.append((t1 - t0, depart - t0, arrive - depart, t1 - arrive, dist, lastcore - first))
            prev = lastcore
    if not rows:
        print(f'{run}: no moves')
        return

    def both(i):
        return f'{statistics.median(r[i] for r in rows):.0f} ({statistics.mean(r[i] for r in rows):.0f})'

    speed = statistics.median(r[4] / max(r[2], 1) for r in rows if r[2] > 5)
    print(f'{run}: {statistics.mean(copies):.2f} copies without quarters and armory by minute {maxmin:.0f} a game; '
          f'{len(rows)} moves, median (mean) seconds:')
    print(f'  gap {both(0)} = lingering {both(1)} + march {both(2)} + arrival to first raze {both(3)}; the raze '
          f'{both(5)}; distance {both(4)} cells, {speed:.2f} cells a second of march')


if __name__ == '__main__':
    minutes = 22.0
    runs = []
    for a in sys.argv[1:]:
        try:
            minutes = float(a)
        except ValueError:
            runs.append(a)
    for r in runs:
        main(r, minutes)
