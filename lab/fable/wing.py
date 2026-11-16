#!/usr/bin/env python3
"""What the second force (split) does, from runs made with --logs: python lab/fable/wing.py RUN...

Per run, a game: splits, and for each split its minute, the warriors sent, the distance and defense of its first
target, how long it lasted, how it ended (the merge's reason, or wiped), how many came back, and the copies it left
without quarters and armory. Then, over the games of the run, the copies housed and our warriors by minute.
"""
import collections
import glob
import json
import os
import re
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
SPLIT = re.compile(r'^\s*([\d.]+) s0 AI\s+split: (\d+) \(([\d.]+)\) of the army\'s ([\d.]+) go for (\S+) (\w+) at (\d+),(\d+), '
                   r'(\d+) cells out, defense ([\d.]+)')
MERGE = re.compile(r'^\s*([\d.]+) s0 AI\s+wing: (\d+) of (\d+) merge after (\d+) s \((\w+)\)')
DONE = re.compile(r'^\s*([\d.]+) s0 AI\s+wing: (\S+) has no quarters or armory left')
NONE = re.compile(r'^\s*([\d.]+) s0 AI\s+wing: none left')
MINUTES = (12, 15, 18, 20, 22, 25, 30)


def main(runs):
    for run in runs:
        splits = []
        games = 0
        census = collections.defaultdict(list)
        for log in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-ai-s0.log'))):
            games += 1
            cur = None
            for line in open(log, encoding='utf-8'):
                m = SPLIT.match(line)
                if m:
                    cur = {'t': float(m.group(1)), 'n': int(m.group(2)), 'army': float(m.group(4)),
                           'cells': int(m.group(9)), 'defense': float(m.group(10)), 'copies': 0, 'end': 'running',
                           'back': 0, 'secs': 0}
                    splits.append(cur)
                    continue
                if cur is None:
                    continue
                m = DONE.match(line)
                if m:
                    cur['copies'] += 1
                    continue
                m = MERGE.match(line)
                if m:
                    cur['end'] = m.group(5)
                    cur['back'] = int(m.group(2))
                    cur['secs'] = int(m.group(4))
                    cur = None
                    continue
                m = NONE.match(line)
                if m:
                    cur['end'] = 'wiped'
                    cur['secs'] = float(m.group(1)) - cur['t']
                    cur = None
            housed = collections.Counter()
            ours = {}
            for line in open(log[:-len('-ai-s0.log')] + '.jsonl', encoding='utf-8'):
                if '"ev":"census"' not in line:
                    continue
                d = json.loads(line)
                m = d['t'] / 60.0
                if abs(m - round(m)) > 0.02 or int(round(m)) not in MINUTES:
                    continue
                m = int(round(m))
                if d['s'] == 0:
                    ours[m] = d['rock'] + d['iron'] + d['rubber']
                elif d.get('alive') and (d['quarters'] or d['armories']):
                    housed[m] += 1
            for m in ours:
                census[m].append((housed[m], ours[m]))
        n = max(1, games)
        print(f'== {run}: {games} games, {len(splits) / n:.1f} splits a game')
        if splits:
            ends = collections.Counter(s['end'] for s in splits)
            print(f"  a split: minute {statistics.mean(s['t'] for s in splits) / 60:.1f} (the earliest at "
                  f"{min(s['t'] for s in splits) / 60:.1f}), "
                  f"{statistics.mean(s['n'] for s in splits):.0f} warriors of an army of "
                  f"{statistics.mean(s['army'] for s in splits):.0f}, target {statistics.mean(s['cells'] for s in splits):.0f} "
                  f"cells out with defense {statistics.mean(s['defense'] for s in splits):.0f}")
            print(f"  it lasts {statistics.mean(s['secs'] for s in splits):.0f} s, leaves "
                  f"{statistics.mean(s['copies'] for s in splits):.2f} copies without quarters and armory "
                  f"({sum(s['copies'] for s in splits) / n:.2f} a game), and {statistics.mean(s['back'] for s in splits):.0f} "
                  f"warriors merge again ({sum(s['n'] - s['back'] for s in splits) / n:.0f} lost or still out a game)")
            print('  ends: ' + ', '.join(f'{k} {v / len(splits):.0%}' for k, v in ends.most_common()))
        print('  minute: copies housed / our warriors: ' + '  '.join(
            f'{m}: {statistics.mean(x[0] for x in census[m]):.1f} / {statistics.mean(x[1] for x in census[m]):.0f}'
            for m in MINUTES if census[m]))


if __name__ == '__main__':
    main(sys.argv[1:])
