#!/usr/bin/env python3
"""Counters and team statistics per hour of game time, run against run: python lab/fable/rates.py [--re REGEX] BASE ARM...

On the games every run played (same seeds, same seat): the wins, each arm's games won and lost against BASE, the
mean game length, and per hour of game time (the sum over the games divided by their summed length, so a run of
longer games is not counted as doing more) kills, losses, buildings lost, the harvest, and every AI counter that
matches REGEX (all with a rate of 1 an hour or more without it). No logs needed.

Iron per gatherer comes from harvestedIron against gather_s_iron (gatherer-seconds).
"""
import json
import re
import sys

args = sys.argv[1:]
pat = None
if '--re' in args:
    i = args.index('--re')
    pat = re.compile(args[i + 1])
    del args[i:i + 2]


def load(run):
    out = {}
    for line in open(f'aisim/runs/{run}/results.jsonl', encoding='utf-8'):
        if line.strip():
            r = json.loads(line)
            if r.get('result') is not None:
                out[r['seed']] = r
    return out


runs = [load(r) for r in args]
common = sorted(set.intersection(*[set(r) for r in runs]))
print(f'{len(common)} common games; wins ' + ', '.join(
    f'{n} {sum(r[s]["result"] == "win" for s in common)}' for n, r in zip(args, runs)))
base = runs[0]
for n, r in list(zip(args, runs))[1:]:
    up = sum(r[s]['result'] == 'win' and base[s]['result'] != 'win' for s in common)
    dn = sum(r[s]['result'] != 'win' and base[s]['result'] == 'win' for s in common)
    print(f'  {n}: +{up} -{dn} against {args[0]}')
hours = [sum(r[s]['t'] for s in common) / 3600.0 for r in runs]
cpu = [sum(r[s].get('cpu') or 0 for s in common) for r in runs]
print(f'{"":36s}' + ''.join(f'{n[:14]:>15s}' for n in args))
print(f'{"game minutes a game":36s}' + ''.join(f'{60 * h / len(common):15.1f}' for h in hours))
print(f'{"cpu seconds an hour of game":36s}' + ''.join(f'{c / h:15.1f}' for c, h in zip(cpu, hours)))
print('-- per hour of game time --')
for k in ['kills', 'lost', 'lostInside', 'buildingsLost', 'harvestedIron', 'harvestedTree', 'harvestedRock']:
    print(f'{k:36s}' + ''.join(
        f'{sum(r[s]["teams"][0].get(k, 0) for s in common) / h:15.1f}' for r, h in zip(runs, hours)))
keys = set()
for r in runs:
    for s in common:
        keys |= set((r[s]['teams'][0].get('counters') or {}).keys())
for k in sorted(keys):
    if pat and not pat.search(k):
        continue
    vals = [sum((r[s]['teams'][0].get('counters') or {}).get(k, 0) for s in common) / h for r, h in zip(runs, hours)]
    if not pat and max(vals) < 1:
        continue
    print(f'{k:36s}' + ''.join(f'{v:15.1f}' for v in vals))
