#!/usr/bin/env python3
"""What the enemy's towers cost us: python lab/fable/etowers.py RUN...

Per run, a game: the enemy towers built, razed and left standing; our warriors and peons that die within 17 cells of
a standing enemy tower (a death in reach of two towers counts for both); the seconds of dying (the time between such
deaths, gaps over a minute left out) and the rate while it lasts. Then the towers with ten or more of our warriors
dead in reach: how many, their share of those deaths, and the time from the first death to the raze.
Needs no logs: it reads the recorded games.
"""
import collections
import glob
import json
import os
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
REACH = 17


def main(runs):
    for run in runs:
        games = 0
        rows = []
        tot = collections.Counter()
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0.jsonl'))):
            games += 1
            towers = {}
            deaths = []
            end = 0
            for line in open(path, encoding='utf-8'):
                d = json.loads(line)
                ev = d['ev']
                if ev == 'built' and d['s'] != 0 and d['b'] == 'tower':
                    towers[(d['s'], d['x'], d['y'])] = [d['t'], None]
                elif ev == 'razed' and d['s'] != 0 and d['b'] == 'tower' and (d['s'], d['x'], d['y']) in towers:
                    towers[(d['s'], d['x'], d['y'])][1] = d['t']
                elif ev == 'deaths' and d['s'] == 0:
                    deaths.append(d)
                elif ev == 'end':
                    end = d['t']
            tot['built'] += len(towers)
            for (s, x, y), (t0, t1) in towers.items():
                razed = t1 is not None
                t1 = t1 if razed else end
                near = [d for d in deaths
                        if t0 <= d['t'] <= t1 + 2 and (d['x'] - x) ** 2 + (d['y'] - y) ** 2 <= REACH * REACH]
                war = sum(d['rock'] + d['iron'] + d['rubber'] for d in near)
                peon = sum(d.get('peon', 0) for d in near)
                tot['razed' if razed else 'standing'] += 1
                tot['war'] += war
                tot['peon'] += peon
                if war + peon:
                    ts = sorted(d['t'] for d in near)
                    span = sum(b - a for a, b in zip(ts, ts[1:]) if b - a <= 60)
                    rows.append((war, span, razed, (t1 - ts[0]) if razed else None))
                    tot['span'] += span
        n = max(1, games)
        print(f"== {run}: {games} games, a game: enemy towers built {tot['built'] / n:.1f}, razed {tot['razed'] / n:.1f}, "
              f"standing at the end {tot['standing'] / n:.1f}")
        print(f"  ours dead within {REACH} cells of one: warriors {tot['war'] / n:.1f}, peons {tot['peon'] / n:.1f}; "
              f"{tot['span'] / n:.0f} s of dying, {tot['war'] / max(1.0, tot['span']):.2f} warriors a second; "
              f"{tot['war'] / max(1, tot['razed']):.1f} warriors a razed tower")
        big = [r for r in rows if r[0] >= 10]
        waits = [r[3] for r in big if r[3] is not None]
        if big:
            print(f"  towers with 10+ warriors dead in reach: {len(big) / n:.1f} a game with "
                  f"{100.0 * sum(r[0] for r in big) / max(1, tot['war']):.0f} % of them; first death to raze: median "
                  f"{statistics.median(waits) if waits else float('nan'):.0f} s")


if __name__ == '__main__':
    main(sys.argv[1:])
