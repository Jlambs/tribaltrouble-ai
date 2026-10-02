#!/usr/bin/env python3
"""Does razing a copy's quarters stop it? Per copy whose finished quarters we razed while it was still in:

    python lab/gauntlet/quarters_gate.py RUN [RUN...]

counts copies that later had quarters again (census quarters > 0 or a placed quarters), trained a chieftain
again, and the buildings of ours razed by any copy before vs after (per minute), plus how long they lived on.
Times are game seconds (gtime); a copy is out at its first out or collapse event. The 60 s and 5 s margins are coarse
in old ludicrous files, whose events came every 4 game s.
"""
import json
import os
import sys

import gtime

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def main(runs):
    n = rebuilt = rechief = chief_alive = 0
    lived = []
    for run in runs:
        gdir = os.path.join(ROOT, run, 'g')
        for name in sorted(os.listdir(gdir)):
            if not name.endswith('.jsonl'):
                continue
            lines = [json.loads(l) for l in open(os.path.join(gdir, name), encoding='utf-8')]
            fac = gtime.factor(lines[0])  # the first line is the game event
            events = [e for e in lines if 't' in e]
            for e in events:
                e['t'] *= fac
            razed_q = {}
            outs = {}
            end = events[-1]['t']
            for e in events:
                if e['ev'] == 'razed' and e['s'] != 0 and e['b'] == 'quarters' and not e.get('site'):
                    razed_q.setdefault(e['s'], e['t'])
                elif e['ev'] in gtime.OUTS:
                    outs.setdefault(e['s'], e['t'])
            for s, t in razed_q.items():
                if s in outs and outs[s] - t < 60:
                    continue  # went out with it
                n += 1
                # chieftain alive at the time? (last chief event before t vs chief_died)
                alive = False
                for e in events:
                    if e['t'] > t:
                        break
                    if e.get('s') == s and e['ev'] == 'chief':
                        alive = True
                    elif e.get('s') == s and e['ev'] == 'chief_died':
                        alive = False
                chief_alive += alive
                later = [e for e in events if e['t'] > t + 5 and e.get('s') == s]
                rebuilt += any(e['ev'] == 'placed' and e['b'] == 'quarters' for e in later)
                rechief += any(e['ev'] == 'chief' for e in later)
                lived.append((outs.get(s, end) - t) / 60)
    if n:
        print('%d copies lost their quarters and stayed in: rebuilt quarters %d, trained a chieftain again %d, '
              'had a chieftain when razed %d; lived on %.1f min (mean)' % (n, rebuilt, rechief, chief_alive,
                                                                       sum(lived) / n))


if __name__ == '__main__':
    main(sys.argv[1:])
