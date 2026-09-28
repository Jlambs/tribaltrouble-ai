#!/usr/bin/env python3
"""The army's campaign in games with decision logs: time in each mode, attacks, how they ended, and eliminations.

    python lab/gauntlet/campaign.py RUN [from_min=0] [to_min=30]

Reads g/<key>-ai-s0.log (batch --logs or --logs lost) and g/<key>.jsonl. Per game and in total: seconds spent in
HOME / MUSTER / ATTACK / RETREAT (from the STAT lines every 30 s), musters and attacks started, attack ends by kind
(retreat, worn down, turned back, called home), copies out in the window, and the army size at each attack.
"""
import json
import os
import re
import sys
from collections import Counter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def main(run, lo=0.0, hi=30.0):
    lo *= 60
    hi *= 60
    gdir = os.path.join(ROOT, run, 'g')
    total_modes = Counter()
    total_events = Counter()
    games = 0
    for name in sorted(os.listdir(gdir)):
        if not name.endswith('-ai-s0.log'):
            continue
        key = name[:-len('-ai-s0.log')]
        games += 1
        modes = Counter()
        events = Counter()
        sizes = []
        with open(os.path.join(gdir, name), encoding='utf-8') as f:
            for line in f:
                parts = line.split()
                if not parts or parts[0].startswith('#'):
                    continue
                try:
                    t = float(parts[0])
                except ValueError:
                    continue
                if t < lo or t > hi:
                    continue
                if ' STAT ' in line:
                    m = re.search(r'mode=([A-Z]+)', line)
                    if m:
                        modes[m.group(1)] += 30
                elif 'muster:' in line:
                    events['muster'] += 1
                elif 'attack with' in line:
                    events['attack'] += 1
                    m = re.search(r'attack with ([0-9.]+)', line)
                    if m:
                        sizes.append(float(m.group(1)))
                elif 'retreat: local' in line:
                    events['retreat_local'] += 1
                elif 'retreat: worn' in line:
                    events['retreat_worn'] += 1
                elif 'turning back' in line:
                    events['turned_back'] += 1
                elif 'calling the army home' in line:
                    events['called_home'] += 1
                elif 'reinforcing the attack' in line:
                    events['reinforce'] += 1
        outs = 0
        result = None
        with open(os.path.join(gdir, key + '.jsonl'), encoding='utf-8') as g:
            for line in g:
                e = json.loads(line)
                if e['ev'] == 'out' and e['s'] != 0 and lo <= e['t'] <= hi:
                    outs += 1
                elif e['ev'] == 'end':
                    result = 'win' if e.get('winnerTeam') == 0 else 'loss'
        events['copies_out'] += outs
        total_modes.update(modes)
        total_events.update(events)
        print('%-9s %-4s modes %s | %s | attack sizes %s' % (key, result, dict(modes), dict(events),
                                                            [int(s) for s in sizes]))
    if games:
        print('\n%d games, window %d-%d min, per game:' % (games, lo / 60, hi / 60))
        print('  modes (s): ' + ', '.join('%s %.0f' % (k, v / games) for k, v in total_modes.most_common()))
        print('  events: ' + ', '.join('%s %.2f' % (k, v / games) for k, v in total_events.most_common()))


if __name__ == '__main__':
    a = sys.argv[1:]
    main(a[0], float(a[1]) if len(a) > 1 else 0.0, float(a[2]) if len(a) > 2 else 30.0)
