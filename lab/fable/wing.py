#!/usr/bin/env python3
"""What the second force (split) does, from runs made with --logs: python lab/fable/wing.py RUN...

Per run: the splits a game and, for a split, its minute, the warriors sent, the distance and defense of its first
target, how long it lasted, how it ended and how many came back. Then the rates while split, a minute: the quarters
and armories razed and the warriors lost by the second force and by the main army, against the same games' unsplit
army in minutes 12-20. Then the copies housed and our warriors by minute (on maps 21001..21012 seventeen
near-default runs read 12.2 / 10.8 / 9.6 / 8.9 / 8.5 / 7.9 / 7.3 copies and 111 / 128 / 116 / 94 / 72 / 47 / 39
warriors at 12 / 15 / 18 / 20 / 22 / 25 / 30 min; one such run varies by 0.28 copies and 8 warriors at 20 min).
"""
import collections
import glob
import json
import os
import re
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
SPLIT = re.compile(r"^\s*([\d.]+) s0 AI\s+split: (\d+) \(([\d.]+)\) of the army's ([\d.]+) go for (\S+?)'s (\w+) at "
                   r"(\d+),(\d+), (\d+) cells out, defense ([\d.]+)")
GOON = re.compile(r"^\s*([\d.]+) s0 AI\s+wing: \d+ \([\d.]+\) go on to (\S+?)'s")
MERGE = re.compile(r'^\s*([\d.]+) s0 AI\s+wing: (\d+) of (\d+) merge after (\d+) s \((\w+)\)')
NONE = re.compile(r'^\s*([\d.]+) s0 AI\s+wing: none left')
LOST = re.compile(r'^\s*([\d.]+) s0 AI\s+lost (\w+) at')
MINUTES = (12, 15, 18, 20, 22, 25, 30)


def main(runs):
    for run in runs:
        splits = []
        games = 0
        census = collections.defaultdict(list)
        tot = collections.Counter()
        for log in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-ai-s0.log'))):
            games += 1
            cur = None
            mine = []
            lost = []
            for line in open(log, encoding='utf-8'):
                m = SPLIT.match(line)
                if m:
                    cur = {'t': float(m.group(1)), 'n': int(m.group(2)), 'army': float(m.group(4)),
                           'cells': int(m.group(9)), 'defense': float(m.group(10)), 'end': 'running', 'back': 0,
                           'until': None, 'owners': {m.group(5)}}
                    mine.append(cur)
                    continue
                m = LOST.match(line)
                if m:
                    lost.append((float(m.group(1)), m.group(2)))
                    continue
                if cur is None:
                    continue
                m = GOON.match(line)
                if m:
                    cur['owners'].add(m.group(2))
                    continue
                m = MERGE.match(line)
                if m:
                    cur['end'] = m.group(5)
                    cur['back'] = int(m.group(2))
                    cur['until'] = float(m.group(1))
                    cur = None
                    continue
                m = NONE.match(line)
                if m:
                    cur['end'] = 'wiped'
                    cur['until'] = float(m.group(1))
                    cur = None
            names = {}
            razed = []
            housed = collections.Counter()
            ours = {}
            end = 2400.0
            for line in open(log[:-len('-ai-s0.log')] + '.jsonl', encoding='utf-8'):
                if '"ev":"census"' in line:
                    d = json.loads(line)
                    m = d['t'] / 60.0
                    if abs(m - round(m)) > 0.02 or int(round(m)) not in MINUTES:
                        continue
                    m = int(round(m))
                    if d['s'] == 0:
                        ours[m] = d['rock'] + d['iron'] + d['rubber']
                    elif d.get('alive') and (d['quarters'] or d['armories']):
                        housed[m] += 1
                elif '"ev":"game"' in line:
                    names = {p['s']: p['name'] for p in json.loads(line)['players']}
                elif '"ev":"razed"' in line:
                    d = json.loads(line)
                    if d['s'] != 0 and not d.get('site') and d['b'] in ('quarters', 'armory'):
                        razed.append((d['t'], names.get(d['s'], '?')))
                elif '"ev":"end"' in line:
                    end = json.loads(line)['t']
            for m in ours:
                census[m].append((housed[m], ours[m]))
            for s in mine:
                t0, t1 = s['t'], s['until'] if s['until'] is not None else end
                s['secs'] = t1 - t0
                tot['secs'] += t1 - t0
                tot['wing lost'] += sum(1 for t, r in lost if t0 <= t <= t1 and r == 'wing')
                tot['main lost'] += sum(1 for t, r in lost if t0 <= t <= t1 and r == 'attack')
                tot['wing razed'] += sum(1 for t, n in razed if t0 <= t <= t1 and n in s['owners'])
                tot['main razed'] += sum(1 for t, n in razed if t0 <= t <= t1 and n not in s['owners'])
            splits.extend(mine)

            def unsplit(t):
                return not any(s['t'] <= t <= (s['until'] if s['until'] is not None else end) for s in mine)

            tot['other secs'] += sum(1 for t in range(720, 1200) if unsplit(t) and t <= end)
            tot['other lost'] += sum(1 for t, r in lost if 720 <= t <= 1200 and r == 'attack' and unsplit(t))
            tot['other razed'] += sum(1 for t, n in razed if 720 <= t <= 1200 and unsplit(t))
        n = max(1, games)
        print(f'== {run}: {games} games, {len(splits) / n:.1f} splits a game')
        if splits:
            ends = collections.Counter(s['end'] for s in splits)
            print(f"  a split: minute {statistics.mean(s['t'] for s in splits) / 60:.1f} (the earliest at "
                  f"{min(s['t'] for s in splits) / 60:.1f}), {statistics.mean(s['n'] for s in splits):.0f} warriors "
                  f"of an army of {statistics.mean(s['army'] for s in splits):.0f}, target "
                  f"{statistics.mean(s['cells'] for s in splits):.0f} cells out with defense "
                  f"{statistics.mean(s['defense'] for s in splits):.0f}; it lasts "
                  f"{statistics.mean(s['secs'] for s in splits):.0f} s; split {tot['secs'] / n / 60:.1f} min a game")
            print('  ends: ' + ', '.join(f'{k} {v / len(splits):.0%}' for k, v in ends.most_common()))
            secs = max(1.0, tot['secs'])
            print(f"  while split, a minute: the second force razes {60 * tot['wing razed'] / secs:.2f} quarters and "
                  f"armories and loses {60 * tot['wing lost'] / secs:.1f} warriors, the main army "
                  f"{60 * tot['main razed'] / secs:.2f} and {60 * tot['main lost'] / secs:.1f}: together "
                  f"{60 * (tot['wing razed'] + tot['main razed']) / secs:.2f} and "
                  f"{60 * (tot['wing lost'] + tot['main lost']) / secs:.1f}")
        other = max(1.0, tot['other secs'])
        print(f"  unsplit in minutes 12-20, a minute: {60 * tot['other razed'] / other:.2f} razed, "
              f"{60 * tot['other lost'] / other:.1f} lost")
        print('  minute: copies housed / our warriors: ' + '  '.join(
            f'{m}: {statistics.mean(x[0] for x in census[m]):.1f} / {statistics.mean(x[1] for x in census[m]):.0f}'
            for m in MINUTES if census[m]))


if __name__ == '__main__':
    main(sys.argv[1:])
