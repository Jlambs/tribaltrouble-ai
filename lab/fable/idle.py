#!/usr/bin/env python3
"""How long peons stand with no order: python lab/fable/idle.py BASE [RUN...]

Economy.countStanding counts a second for every peon that has no order at two allocations in a row (the economy's
tick is a second), by phase: idle_s_open before our first armory stood, idle_s_15 up to minute 15, idle_s_30 up to
minute 30, idle_s_late after. Per run: the wins, the mean and the 90th percentile of each counter, the games with 300
peon-seconds and more after minute 15 and how many of those are won, the median second the first armory stood, and
against BASE the games won by one side only. Runs are of the same seeds from the same seat.
"""
import json
import os
import statistics as st
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
KEYS = ('idle_s_open', 'idle_s_15', 'idle_s_30', 'idle_s_late')


def rows(run):
    out = {}
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        for line in f:
            if line.strip():
                r = json.loads(line)
                if r.get('result') is not None:
                    out[r['key']] = r
    return out


def first_armory(run, key):
    side = int(key.rsplit('-', 1)[1])
    with open(os.path.join(ROOT, run, 'g', key + '.jsonl'), encoding='utf-8') as f:
        for line in f:
            if '"ev":"built"' in line and '"b":"armory"' in line:
                d = json.loads(line)
                if d['s'] == side:
                    return d['t']
    return None


def main(argv):
    base = rows(argv[0])
    for run in argv:
        rs = rows(run)
        games = list(rs.values())
        won = sum(r['result'] == 'win' for r in games)
        print(f'{run}: {len(games)} games, {won} won, {sum(r["result"] == "draw" for r in games)} drawn, '
              f'mean length {st.mean(r["t"] for r in games) / 60:.0f} min')
        for k in KEYS:
            c = sorted(r['teams'][0]['counters'].get(k, 0) for r in games)
            print(f'    {k:12s} mean {st.mean(c):7.0f}  p90 {c[int(.9 * len(c))]:6d}  max {c[-1]:6d}  '
                  f'games with any {sum(x > 0 for x in c)}')
        late = [r for r in games if r['teams'][0]['counters'].get('idle_s_30', 0)
                + r['teams'][0]['counters'].get('idle_s_late', 0) >= 300]
        print(f'    300 peon-seconds and more after minute 15: {len(late)} games, '
              f'{sum(r["result"] == "win" for r in late)} won')
        arm = [a for a in (first_armory(run, k) for k in rs) if a is not None]
        if arm:
            arm.sort()
            print(f'    first armory: median {st.median(arm):.0f} s, p90 {arm[int(.9 * len(arm))]:.0f} s, '
                  f'none in {len(rs) - len(arm)} games')
        used = sum(r['teams'][0]['counters'].get('idle_builders', 0) > 0 for r in games)
        if used:
            print(f'    idle_builders fired in {used} games')
        if run != argv[0]:
            common = [k for k in rs if k in base]
            up = sum(base[k]['result'] != 'win' and rs[k]['result'] == 'win' for k in common)
            down = sum(base[k]['result'] == 'win' and rs[k]['result'] != 'win' for k in common)
            same = sum(base[k]['checksum'] == rs[k]['checksum'] for k in common)
            print(f'    against {argv[0]}: +{up} -{down} on {len(common)} games, {same} identical')


if __name__ == '__main__':
    main(sys.argv[1:])
