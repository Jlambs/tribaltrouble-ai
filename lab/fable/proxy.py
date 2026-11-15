#!/usr/bin/env python3
"""A lower-noise reading of an arm: python lab/fable/proxy.py [--minute 20] [--train RUN,RUN] BASE ARM...

A logistic model of "won" from the state at minute T (copies with a quarters or armory, enemy warriors, our
warriors, peons, iron harvested, towers, quarters, armories), fitted on the games of the training runs (default: the
exam and dev games of fable's defaults at N=15). For every arm: the wins, paired with BASE, and the mean of the
model's win chance over the common games (a game already lost at T counts 0), with the paired standard error. The
model sees nothing after minute T and knows only what the training runs varied: an arm that trades something the
model does not weigh (a building given up on purpose, say) reads wrong here, so the wins decide.
"""
import glob
import json
import math
import os
import sys

import numpy as np

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
NAMES = ['homed', 'ewar', 'war', 'peons', 'iron', 'towers', 'quarters', 'armories']
CACHE = {}


def load(run, minute):
    """key -> (features or None when out by then, won)."""
    if (run, minute) in CACHE:
        return CACHE[(run, minute)]
    won = {}
    for line in open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8'):
        if line.strip():
            r = json.loads(line)
            won[r['key']] = r['result'] == 'win'
    out = {}
    for path in glob.glob(os.path.join(ROOT, run, 'g', 's*.jsonl')):
        key = os.path.basename(path)[:-6]
        if key not in won:
            continue
        census = {}
        for line in open(path, encoding='utf-8'):
            if '"census"' not in line:
                continue
            d = json.loads(line)
            if abs(d['t'] - minute * 60) < 1:
                census[d['s']] = d
        me = census.get(0)
        if me is None or not me['alive']:
            out[key] = (None, won[key])
            continue
        foes = [v for s, v in census.items() if s != 0 and v['alive']]
        out[key] = ([sum(1 for v in foes if v['quarters'] + v['armories'] > 0),
                     sum(v['rock'] + v['iron'] + v['rubber'] for v in foes),
                     me['rock'] + me['iron'] + me['rubber'], me['peons'] + me['inside'], me['harvestedIron'],
                     me['towers'], me['quarters'], me['armories']], won[key])
    CACHE[(run, minute)] = out
    return out


def fit(runs, minute):
    xs, ys = [], []
    for run in runs:
        for feats, won in load(run, minute).values():
            if feats is not None:
                xs.append(feats)
                ys.append(1.0 if won else 0.0)
    x = np.array(xs, dtype=float)
    y = np.array(ys)
    mu, sd = x.mean(0), x.std(0) + 1e-9
    z = (x - mu) / sd
    w = np.zeros(z.shape[1])
    b = 0.0
    for _ in range(6000):
        p = 1 / (1 + np.exp(-(z @ w + b)))
        w -= 0.3 * (z.T @ (p - y) / len(y) + 0.01 * w)
        b -= 0.3 * (p - y).mean()
    p = 1 / (1 + np.exp(-(z @ w + b)))
    acc = ((p > .5) == (y > .5)).mean()
    return (mu, sd, w, b), len(y), acc


def chance(model, feats):
    if feats is None:
        return 0.0
    mu, sd, w, b = model
    z = (np.array(feats, dtype=float) - mu) / sd
    return float(1 / (1 + math.exp(-(z @ w + b))))


def main(argv):
    minute = 20
    train = ['exam-f2-vs15', 'cnt-vs15']
    while argv and argv[0].startswith('--'):
        if argv[0] == '--minute':
            minute = int(argv[1])
        elif argv[0] == '--train':
            train = argv[1].split(',')
        argv = argv[2:]
    base_name, arms = argv[0], argv[1:]
    model, n, acc = fit(train, minute)
    print(f'model at minute {minute}: {n} games of {", ".join(train)}, accuracy {acc:.2f}; log-odds per unit: '
          + ', '.join(f'{name} {w / s:+.4f}' for name, w, s in zip(NAMES, model[2], model[1])))
    base = load(base_name, minute)
    for arm in arms:
        try:
            run = load(arm, minute)
        except FileNotFoundError:
            print(f'{arm:22} no results yet')
            continue
        keys = sorted(k for k in run if k in base)
        if not keys:
            print(f'{arm:22} no common games')
            continue
        d = np.array([chance(model, run[k][0]) - chance(model, base[k][0]) for k in keys])
        wa = sum(1 for k in keys if base[k][1])
        wb = sum(1 for k in keys if run[k][1])
        plus = sum(1 for k in keys if run[k][1] and not base[k][1])
        minus = sum(1 for k in keys if base[k][1] and not run[k][1])
        se = d.std(ddof=1) / math.sqrt(len(d)) if len(d) > 1 else float('nan')
        pb = np.mean([chance(model, base[k][0]) for k in keys])
        print(f'{arm:22} n={len(keys):3}  wins {wa} -> {wb} (+{plus} -{minus})   model {100 * pb:.1f} % -> '
              f'{100 * (pb + d.mean()):.1f} % ({100 * d.mean():+.1f} +- {100 * se:.1f}, z {d.mean() / se:+.1f})')


if __name__ == '__main__':
    main(sys.argv[1:])
