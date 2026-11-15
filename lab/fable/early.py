#!/usr/bin/env python3
"""The opening of a run next to a base run, on the games both played: python lab/fable/early.py BASE RUN...

From the 30-s census: our towers, warriors and units at minutes 6-20, the copies with a quarters or an armory, and
our buildings razed by minute 15, 20 and 25; then wins, paired.
"""
import glob
import json
import os
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
MINUTES = [6, 8, 10, 12, 15, 20]


def load(run):
    games = {}
    results = {}
    path = os.path.join(ROOT, run, 'results.jsonl')
    if os.path.exists(path):
        for line in open(path, encoding='utf-8'):
            if line.strip():
                r = json.loads(line)
                results[r['key']] = r['result']
    for path in glob.glob(os.path.join(ROOT, run, 'g', 's*.jsonl')):
        key = os.path.basename(path)[:-6]
        if key not in results:
            continue
        census = {}
        razed = []
        for line in open(path, encoding='utf-8'):
            d = json.loads(line)
            if d['ev'] == 'census':
                census.setdefault(round(d['t'] / 30.0) * 30, {})[d['s']] = d
            elif d['ev'] == 'razed' and d['s'] == 0:
                razed.append(d['t'])
        row = {'win': results[key] == 'win'}
        for m in MINUTES:
            c = census.get(m * 60)
            if not c or 0 not in c or not c[0]['alive']:
                continue
            me = c[0]
            row[m] = (me['towers'], me['rock'] + me['iron'] + me['rubber'], me['units'],
                      sum(1 for s, v in c.items() if s != 0 and v['alive'] and v['quarters'] + v['armories'] > 0))
        for m in (15, 20, 25):
            row['razed%d' % m] = sum(1 for t in razed if t <= m * 60)
        games[key] = row
    return games


def main(base_name, names):
    base = load(base_name)
    for name in names:
        run = load(name)
        keys = sorted(k for k in run if k in base)
        if not keys:
            print(name, 'no common games')
            continue
        print(f'== {name} against {base_name}: {len(keys)} common games')
        for m in MINUTES:
            both = [k for k in keys if m in base[k] and m in run[k]]
            if not both:
                continue
            cells = []
            for i, label in enumerate(('towers', 'warriors', 'units', 'homed')):
                a = statistics.mean(base[k][m][i] for k in both)
                b = statistics.mean(run[k][m][i] for k in both)
                cells.append(f'{label} {a:.1f} -> {b:.1f}')
            print(f'  min {m:>2} (n={len(both)}): ' + ', '.join(cells))
        for m in (15, 20, 25):
            a = statistics.mean(base[k]['razed%d' % m] for k in keys)
            b = statistics.mean(run[k]['razed%d' % m] for k in keys)
            print(f'  our buildings razed by min {m}: {a:.2f} -> {b:.2f}')
        wa = sum(1 for k in keys if base[k]['win'])
        wb = sum(1 for k in keys if run[k]['win'])
        plus = sum(1 for k in keys if run[k]['win'] and not base[k]['win'])
        minus = sum(1 for k in keys if base[k]['win'] and not run[k]['win'])
        print(f'  wins {wa} -> {wb} (+{plus} -{minus})')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])
