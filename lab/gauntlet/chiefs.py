#!/usr/bin/env python3
"""Enemy chieftain gating in recorded games: python lab/gauntlet/chiefs.py RUN [RUN...]

A Hard copy past wave size 20 launches only with an active chieftain (AdvancedAI), so the time a copy spends without
one after its first chieftain was born is time it cannot send a wave. Per run, over the first 20 minutes of each game
(from the game records' chief / chief_died / out / end events): enemy chieftain births and deaths per game, the median
first birth, the copy-minutes after a first birth ("gated") and the share of them without a chieftain; then the games
in terciles of that share, with their median length and wins. All times are game seconds (gtime), and a copy is out at
its first out or collapse event.
"""
import json
import os
import statistics as st
import sys

import gtime

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
HORIZON = 1200.0


def game(path, horizon=HORIZON):
    births = {}  # slot -> birth times
    deaths = {}
    outs = {}
    end = None
    f = None
    for line in open(path, encoding='utf-8'):
        x = json.loads(line)
        if f is None:
            f = gtime.factor(x)  # the first line is the game event
        e = x.get('ev')
        if e == 'chief':
            births.setdefault(x['s'], []).append(x['t'] * f)
        elif e == 'chief_died':
            deaths.setdefault(x['s'], []).append(x['t'] * f)
        elif e in gtime.OUTS:
            outs.setdefault(x['s'], x['t'] * f)
        elif e == 'end':
            end = x['t'] * f
    if end is None:
        return None
    h = min(horizon, end)
    gated = 0.0
    chiefless = 0.0
    n_births = 0
    n_deaths = 0
    first = []
    for s in births:
        if s == 0:
            continue  # our own chieftain
        b = sorted(t for t in births[s] if t < h)
        d = sorted(t for t in deaths.get(s, []) if t < h)
        if not b:
            continue
        first.append(b[0])
        n_births += len(b)
        n_deaths += len(d)
        stop = min(h, outs.get(s, h))
        # walk the timeline from the first birth: a chieftain is alive between a birth and the next death
        alive = 0
        t_prev = b[0]
        for t, k in sorted([(t, 1) for t in b] + [(t, -1) for t in d]):
            if t > stop:
                break
            if alive <= 0 and t > t_prev:
                chiefless += t - t_prev
            alive += k
            t_prev = t
        if alive <= 0 and stop > t_prev:
            chiefless += stop - t_prev
        gated += max(0.0, stop - b[0])
    return dict(births=n_births, deaths=n_deaths, gated=gated, chiefless=chiefless, end=end,
                first=st.median(first) if first else None)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for run in sys.argv[1:]:
        rows = {x['key']: x for x in map(json.loads, open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8'))}
        gs = []
        for key, r in rows.items():
            g = game(os.path.join(ROOT, run, 'g', key + '.jsonl'))
            if g:
                g['win'] = r['result'] == 'win'
                gs.append(g)
        if not gs:
            print(f'{run}: no finished game records')
            continue
        tot_g = sum(g['gated'] for g in gs)
        tot_c = sum(g['chiefless'] for g in gs)
        firsts = [g['first'] for g in gs if g['first']]
        print(f"{run}: {len(gs)} games; per game by 20 min: enemy chief births {st.mean(g['births'] for g in gs):.1f}, "
              f"deaths {st.mean(g['deaths'] for g in gs):.1f}; first birth median "
              f"{st.median(firsts) if firsts else float('nan'):.0f}s; chieftain-gated copy-minutes "
              f"{tot_g / 60 / len(gs):.1f}/game, chiefless share {tot_c / max(tot_g, 1):.2f}")
        # games split by their chiefless share
        sh = sorted(gs, key=lambda g: g['chiefless'] / max(g['gated'], 1))
        q = len(sh) // 3
        for name, part in (('low', sh[:q]), ('mid', sh[q:2 * q]), ('high', sh[2 * q:])):
            if part:
                print(f"  chiefless tercile {name}: share "
                      f"{st.mean(g['chiefless'] / max(g['gated'], 1) for g in part):.2f}, "
                      f"game min {st.median(g['end'] for g in part) / 60:.1f}, wins {sum(g['win'] for g in part)}")


if __name__ == '__main__':
    main()
