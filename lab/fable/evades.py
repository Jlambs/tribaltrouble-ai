#!/usr/bin/env python3
"""Gatherers that step out of an enemy's way, from runs made with --logs: python lab/fable/evades.py RUN...

Reads Military's "evade start" / "evade end" lines (a serial number a gatherer) and its "flight" lines (a peon sent to
shelter). Per run, a game: the steps by what the nearest enemy was doing (parked, walking to attack, hunting this
peon, hunting another), how they end, how long they last, how soon the same gatherer steps away again after going
back to work (the chains), and how much gatherer time the chains hold. Then the flights by what the peon did, what the
enemy did and how far the shelter was.
"""
import collections
import glob
import re
import statistics as st
import sys

START = re.compile(r'^\s*([\d.]+) s0 \S+\s+evade start #(\d+) (\w+) at (\d+),(\d+) loaded (\w+) supply (\S+) \((-?\d+) cells\)'
                   r' home (-?\d+) cells foe (\w+) at (\d+),(\d+) (\d+) cells, (\d+) within 14 \((\d+) parked\), foe to supply (-?\d+)')
END = re.compile(r'^\s*([\d.]+) s0 \S+\s+evade end #(-?\d+) (\w+) after ([\d.]+)(?: at (\d+),(\d+))?(?: supply (\S+) same (\w+))?')
FLIGHT = re.compile(r'^\s*([\d.]+) s0 \S+\s+flight (\S+) at (\d+),(\d+) loaded (\w+) supply (-?\d+) cells shelter (\w+) (\d+) cells'
                    r' foe (\w+) (-?\d+) cells')


def table(title, rows, games, keyf, order=None):
    c = collections.Counter(keyf(r) for r in rows)
    keys = order or [k for k, _ in c.most_common()]
    print(f'  {title}: ' + ', '.join(f'{k} {c[k] / games:.0f}' for k in keys if c[k]))


def med(values):
    values = list(values)
    return st.median(values) if values else -1


for run in sys.argv[1:]:
    files = sorted(glob.glob(f'aisim/runs/{run}/g/*-ai-s0.log'))
    games = len(files)
    starts, ends, flights, chains = [], [], [], []
    for f in files:
        last_end = {}   # id -> (time, how)
        chain = {}      # id -> [steps, first start, last end]
        for line in open(f, encoding='utf-8', errors='replace'):
            if ' evade ' in line:
                m = START.match(line)
                if m:
                    t, i = float(m.group(1)), int(m.group(2))
                    r = dict(t=t, id=i, kind=m.group(3), loaded=m.group(6) == 'true', supply_d=int(m.group(8)),
                             home=int(m.group(9)), foe=m.group(10), foe_d=int(m.group(13)), around=int(m.group(14)),
                             parked=int(m.group(15)), foe_supply=int(m.group(16)))
                    prev = last_end.get(i)
                    r['gap'] = t - prev[0] if prev and prev[1] == 'resumed' else None
                    if r['gap'] is not None and r['gap'] <= 10 and i in chain:
                        chain[i][0] += 1
                    else:
                        if i in chain:
                            chains.append(chain[i])
                        chain[i] = [1, t, t]
                    starts.append(r)
                    continue
                m = END.match(line)
                if m:
                    t, i = float(m.group(1)), int(m.group(2))
                    ends.append(dict(t=t, id=i, how=m.group(3), secs=float(m.group(4)), same=m.group(8)))
                    last_end[i] = (t, m.group(3))
                    if i in chain:
                        chain[i][2] = t
            elif ' flight ' in line:
                m = FLIGHT.match(line)
                if m:
                    flights.append(dict(t=float(m.group(1)), state=m.group(2), loaded=m.group(5) == 'true',
                                        shelter=m.group(7), shelter_d=int(m.group(8)), foe=m.group(9),
                                        foe_d=int(m.group(10))))
        chains.extend(chain.values())
    print(f'== {run}: {games} logged games; a game: {len(starts) / games:.0f} steps out of the way, '
          f'{len(flights) / games:.0f} flights to shelter')
    table('the nearest enemy', starts, games, lambda r: r['foe'])
    table('what it gathered', starts, games, lambda r: r['kind'])
    table('by minute', starts, games, lambda r: f"{int(r['t'] // 600) * 10}+", [f'{k}+' for k in range(0, 200, 10)])
    table('cells from its armory', starts, games,
          lambda r: '<30' if r['home'] < 30 else '30-59' if r['home'] < 60 else '60-99' if r['home'] < 100 else '100+',
          ['<30', '30-59', '60-99', '100+'])
    table('enemies within 14', starts, games,
          lambda r: '1' if r['around'] <= 1 else '2-4' if r['around'] <= 4 else '5-14' if r['around'] <= 14 else '15+',
          ['1', '2-4', '5-14', '15+'])
    table('loaded', starts, games, lambda r: r['loaded'])
    table('ends', ends, games, lambda r: r['how'])
    res = [e for e in ends if e['how'] == 'resumed']
    if res:
        print(f"  a resumed step lasts {st.mean(e['secs'] for e in res):.1f} s (median {st.median(e['secs'] for e in res):.1f}); "
              f"sent to the same supply again: {100 * sum(e['same'] == 'true' for e in res) / len(res):.0f} %")
    again = [r for r in starts if r['gap'] is not None]
    for lim in (2, 5, 10, 30):
        n = sum(r['gap'] <= lim for r in again)
        print(f"  steps away again within {lim:2d} s of going back to work: {n / games:.0f} a game"
              f" ({100 * n / max(1, len(starts)):.0f} % of all steps)")
    by = collections.Counter()
    secs = collections.Counter()
    for n, t0, t1 in chains:
        k = '1' if n == 1 else '2-3' if n <= 3 else '4-9' if n <= 9 else '10+'
        by[k] += 1
        secs[k] += t1 - t0
    print('  chains (steps of one gatherer at most 10 s apart): ' + ', '.join(
        f'{k}: {by[k] / games:.0f} a game, {secs[k] / games:.0f} s' for k in ['1', '2-3', '4-9', '10+']))
    for foe in ['parked', 'attack_walk', 'hunts_me', 'hunts_peon', 'hunts_other', 'attacks', 'walk']:
        rs = [r for r in starts if r['foe'] == foe]
        if not rs:
            continue
        rep = [r for r in rs if r['gap'] is not None and r['gap'] <= 5]
        print(f"    {foe:12s} {len(rs) / games:6.0f} a game, again within 5 s {100 * len(rep) / len(rs):3.0f} %, "
              f"median {med(r['foe_d'] for r in rs):.0f} cells off, {med(r['around'] for r in rs):.0f} within 14, "
              f"supply {med(r['supply_d'] for r in rs if r['supply_d'] >= 0):.0f} cells on, "
              f"enemy to supply {med(r['foe_supply'] for r in rs if r['foe_supply'] >= 0):.0f}")
    if flights:
        print(f'  flights to shelter, a game: {len(flights) / games:.0f}')
        table('what the peon did', flights, games, lambda r: r['state'])
        table('the nearest enemy', flights, games, lambda r: r['foe'])
        table('cells to the shelter', flights, games,
              lambda r: '<10' if r['shelter_d'] < 10 else '10-19' if r['shelter_d'] < 20 else '20-29' if r['shelter_d'] < 30 else '30+',
              ['<10', '10-19', '20-29', '30+'])
        table('shelter', flights, games, lambda r: r['shelter'])
        table('by minute', flights, games, lambda r: f"{int(r['t'] // 600) * 10}+", [f'{k}+' for k in range(0, 200, 10)])
