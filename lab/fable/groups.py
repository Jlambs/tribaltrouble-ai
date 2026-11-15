#!/usr/bin/env python3
"""Wins of runs by map difficulty: python lab/fable/groups.py RUN [RUN...]

The 100 dev maps 21001..21100 at N=14 split by how many of eight reference arms won them (the baseline and seven
single-param arms, see REF): 'hard' maps none won, 'mixed' 1-5, 'easy' 6-8. An arm that only reshuffles the mixed
maps is noise; one that wins hard maps has changed something.
"""
import json
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
REF = ['g16-lud-vs14', 'tdw2-vs14', 'tdw4-vs14', 'hold6-vs14', 'deny-vs14', 'qba2-vs14', 'rockidle10-vs14',
       'home18-mob3-vs14']


def load(run):
    out = {}
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        for line in f:
            if line.strip():
                r = json.loads(line)
                if r.get('result') is not None:
                    out[r['key']] = r['result']
    return out


def main(runs):
    ref = [load(r) for r in REF]
    keys = sorted(set.intersection(*[set(r) for r in ref]))
    wins = {k: sum(1 for r in ref if r[k] == 'win') for k in keys}
    groups = {'hard': [k for k in keys if wins[k] == 0], 'mixed': [k for k in keys if 1 <= wins[k] <= 5],
              'easy': [k for k in keys if wins[k] >= 6]}
    print('%-28s %s' % ('run', '  '.join('%s (%d)' % (g, len(ks)) for g, ks in groups.items())))
    for run in runs:
        res = load(run)
        cells = []
        for g, ks in groups.items():
            have = [k for k in ks if k in res]
            w = sum(1 for k in have if res[k] == 'win')
            cells.append('%2d/%2d' % (w, len(have)))
        total = sum(1 for k in keys if res.get(k) == 'win')
        print('%-28s %s   total %d/%d' % (run, '      '.join(cells), total, sum(1 for k in keys if k in res)))


if __name__ == '__main__':
    main(sys.argv[1:])
