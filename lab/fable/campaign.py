#!/usr/bin/env python3
"""How the campaign spends minutes 5-30, from the AI logs of a run made with --logs: python lab/fable/campaign.py RUN

Per game: attacks launched, how they ended (retreat reasons), seconds in an attack, of those the seconds marching
(under a tenth of the army fighting), the seconds walking home after an attack ended, and the metres to the target
when a new target was picked (a jump of the distance in the 4-s battle lines).
"""
import glob
import json
import os
import re
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
LO, HI = 300.0, 1800.0
battle = re.compile(r'battle: army (\d+) \(([\d.]+), (\d+) far, (\d+) fighting.*?(\d+)m to target')


def main(run):
    results = {}
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        for line in f:
            if line.strip():
                r = json.loads(line)
                results[r['key']] = r
    rows = []
    reasons = {}
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', '*-ai-s0.log'))):
        key = os.path.basename(path).split('-ai-')[0]
        if key not in results:
            continue
        attacks = 0
        in_attack = march = home_walk = 0.0
        last_t = None
        last_d = None
        jumps = []
        ending = None
        ends = 0
        peak = 0
        with open(path, encoding='utf-8') as f:
            for line in f:
                if line.startswith('#'):
                    continue
                parts = line.split(None, 3)
                if len(parts) < 4 or parts[2] != 'AI':
                    continue
                t = float(parts[0])
                msg = parts[3]
                if t < LO or t > HI:
                    continue
                if msg.startswith('attack with'):
                    attacks += 1
                    last_t = last_d = None
                elif msg.startswith('retreat:') or msg.startswith('turning back'):
                    ending = t
                    ends += 1
                    why = 'turned back' if msg.startswith('turning') else 'worn' if 'worn' in msg else 'outmatched'
                    reasons[why] = reasons.get(why, 0) + 1
                elif msg.startswith('attack over'):
                    if ending is not None:
                        home_walk += t - ending
                    ending = None
                else:
                    m = battle.match(msg)
                    if m:
                        army, fighting, d = int(m.group(1)), int(m.group(4)), int(m.group(5))
                        peak = max(peak, army)
                        if last_t is not None and t - last_t < 10:
                            in_attack += t - last_t
                            if fighting * 10 < army:
                                march += t - last_t
                        if last_d is not None and d < 100000 and d - last_d > 60:
                            jumps.append(d)
                        last_t = t
                        if d < 100000:
                            last_d = d
        rows.append((results[key]['result'], attacks, ends, in_attack, march, home_walk, jumps, peak))
    for tag in ('win', 'loss'):
        sel = [r for r in rows if r[0] == tag]
        if not sel:
            continue
        jumps = [d for r in sel for d in r[6]]
        print('%-4s %2d games: attacks %.1f, ended %.1f, in attack %.0f s, marching %.0f s (%.0f%%), walking home %.0f s,'
              ' new targets %.1f a game at a median %.0f m (mean %.0f, over 300 m: %.0f%%), peak army %.0f'
              % (tag, len(sel), statistics.mean(r[1] for r in sel), statistics.mean(r[2] for r in sel),
                 statistics.mean(r[3] for r in sel), statistics.mean(r[4] for r in sel),
                 100 * sum(r[4] for r in sel) / max(1, sum(r[3] for r in sel)),
                 statistics.mean(r[5] for r in sel), len(jumps) / len(sel),
                 statistics.median(jumps) if jumps else 0, statistics.mean(jumps) if jumps else 0,
                 100 * sum(1 for d in jumps if d > 300) / max(1, len(jumps)), statistics.mean(r[7] for r in sel)))
    print('attack endings in minutes 5-30:', reasons)


if __name__ == '__main__':
    main(sys.argv[1])
