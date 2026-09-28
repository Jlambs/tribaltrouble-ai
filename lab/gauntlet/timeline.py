#!/usr/bin/env python3
"""When team A's base falls and when the copies go out, in won and lost games.

    python lab/gauntlet/timeline.py RUN [RUN...]

Medians in minutes: our first building razed, first finished quarters and armory razed, the first and second
copies out, the game's end; for lost games also how many copies were out before our first armory fell.
"""
import json
import os
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def med(xs):
    xs = [x for x in xs if x is not None]
    return round(statistics.median(xs) / 60, 1) if xs else None


def main(runs):
    rows = []
    for run in runs:
        with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
            results = {json.loads(l)['key']: json.loads(l).get('result') for l in f if l.strip()}
        for key, result in results.items():
            path = os.path.join(ROOT, run, 'g', key + '.jsonl')
            if result is None or not os.path.exists(path):
                continue
            first_razed = first_q = first_a = end = None
            outs = []
            with open(path, encoding='utf-8') as g:
                for line in g:
                    e = json.loads(line)
                    if e['ev'] == 'razed' and e.get('s') == 0:
                        first_razed = first_razed or e['t']
                        if e['b'] == 'quarters' and not e.get('site'):
                            first_q = first_q or e['t']
                        if e['b'] == 'armory' and not e.get('site'):
                            first_a = first_a or e['t']
                    elif e['ev'] == 'out' and e['s'] != 0:
                        outs.append(e['t'])
                    elif e['ev'] == 'end':
                        end = e['t']
            rows.append((result, first_razed, first_q, first_a, sorted(outs), end))
    for tag in ('win', 'loss'):
        sel = [r for r in rows if r[0] == tag]
        if not sel:
            continue
        print('%s %d: first razed %s, first quarters lost %s, first armory lost %s, first copy out %s, second %s, end %s'
              % (tag, len(sel), med([r[1] for r in sel]), med([r[2] for r in sel]), med([r[3] for r in sel]),
                 med([r[4][0] if r[4] else None for r in sel]), med([r[4][1] if len(r[4]) > 1 else None for r in sel]),
                 med([r[5] for r in sel])))
        if tag == 'loss':
            before = [len([o for o in r[4] if o < r[3]]) for r in sel if r[3]]
            if before:
                print('  copies out before our armory fell: mean %.2f over %d games' % (sum(before) / len(before),
                                                                                       len(before)))


if __name__ == '__main__':
    main(sys.argv[1:])
