#!/usr/bin/env python3
"""How bare is our start, and does it decide the game: python lab/fable/barren.py LOGRUN RESULTRUN [LOGRUN RESULTRUN...]

LOGRUN is a run with AI logs of the same games (a `--logs --minutes 1` run is enough: the plan and the ore map are
logged in the first tick), RESULTRUN the run whose results are judged. Per game: the walk from the start to the planned
first armory (the `plan armory#1 ... from start=` line) and the iron nodes within 300 m of walking from the start and
from the armory site (the `ore map iron` line: x,y,walk from start,walk from site,...). Prints the wins by each.
"""
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def features(run, key):
    side = key.rsplit('-', 1)[1]
    path = os.path.join(ROOT, run, 'g', f'{key}-ai-s{side}.log')
    walk = None
    near_start = near_site = None
    with open(path, encoding='utf-8') as f:
        for line in f:
            if ' plan armory#' in line and walk is None:
                walk = int(re.search(r'from start=(\d+)m', line).group(1))
            elif 'ore map iron' in line:
                ents = [e.split(',') for e in line.split('ore map iron ')[1].split()]
                near_start = sum(1 for e in ents if 0 <= int(e[2]) <= 300)
                near_site = sum(1 for e in ents if 0 <= int(e[3]) <= 300)
                break
            elif line[:10].strip().startswith('1.'):
                break
    return walk, near_start, near_site


def load(log_run, result_run):
    out = []
    for line in open(os.path.join(ROOT, result_run, 'results.jsonl'), encoding='utf-8'):
        if line.strip():
            r = json.loads(line)
            walk, near_start, near_site = features(log_run, r['key'])
            out.append({'key': r['key'], 'win': r['result'] == 'win', 'walk': walk if walk is not None else 9999,
                        'start': near_start or 0, 'site': near_site or 0, 'end': r['t'] / 60.0})
    return out


def table(title, games, f, edges):
    print(title)
    for lo, hi in zip(edges[:-1], edges[1:]):
        sel = [g for g in games if lo <= f(g) < hi]
        if sel:
            w = sum(g['win'] for g in sel)
            print(f'   {lo:5d}..{hi - 1:<5d} {len(sel):4d} games, {w:4d} won = {100.0 * w / len(sel):5.1f} %')


def main(argv):
    games = []
    for i in range(0, len(argv), 2):
        games += load(argv[i], argv[i + 1])
    print(f'{len(games)} games, {sum(g["win"] for g in games)} won')
    table(' by the walk from the start to the planned armory, m', games, lambda g: g['walk'],
          [0, 150, 250, 350, 500, 700, 9999, 10000])
    table(' by iron nodes within 300 m of walking from the start', games, lambda g: g['start'],
          [0, 5, 10, 20, 30, 50, 1000])
    table(' by iron nodes within 300 m of walking from the armory site', games, lambda g: g['site'],
          [0, 20, 30, 40, 50, 60, 1000])


if __name__ == '__main__':
    main(sys.argv[1:])
