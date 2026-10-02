"""Win rate conditioned on reaching k remaining copies (alive) vs the fresh-N=k win rate, with our state and the remaining copies' at that moment (python lab/gauntlet/remaining.py; edit RUNS/FRESH for other runs).

Times are game seconds (gtime); a copy is out at its first out or collapse event. The state is the last census at or
before that moment, so up to one census interval old (30 game s; 120 in old ludicrous files)."""
import glob, json, os, statistics as st

import gtime

RUNS = {
    13: ['cur7-bench-vs13-a', 'cur7-bench-vs13-b', 'cur6-vs13-f1', 'cur6-vs13-f2', 'chief300-c6-vs13-f1', 'chief300-c6-vs13-f2'],
    14: ['cur7-bench-vs14-a', 'cur7-bench-vs14-b', 'cur7-bench-vs14-c', 'cur7-bench-vs14-d', 'cur6-vs14-f1', 'cur6-vs14-f2',
         'chief300-c6-vs14-f1', 'chief300-c6-vs14-f2', 'cur6-vs14-hv-d'],
    15: ['cur7-bench-vs15-a', 'cur7-bench-vs15-b', 'cur7-bench-vs15-c', 'cur7-bench-vs15-d', 'cur6-vs15-hv', 'cur6-vs15-hv-b',
         'cur6-vs15-fish1', 'cur6-vs15-fish2', 'cur6-vs15-fish3', 'cur6-vs15-fish4', 'cur6-vs15-fish5'],
}
FRESH = {8: ['cur6-vs8-hv-b'], 11: ['cur6-vs11-hv'], 12: ['slots32-c5-vs12-hv', 'slots32-c5-vs12-hv-b']}


def games(run):
    d = f'aisim/runs/{run}'
    if not os.path.exists(f'{d}/results.jsonl'):
        return
    for x in map(json.loads, open(f'{d}/results.jsonl')):
        f = f'{d}/g/{x["key"]}.jsonl'
        if os.path.exists(f):
            yield gtime.row(x), f


def parse(f):
    outs, cen, n_en, fac = {}, {}, 0, None
    for line in open(f):
        x = json.loads(line)
        if fac is None:
            fac = gtime.factor(x)  # the first line is the game event
        e = x['ev']
        if e == 'game':
            n_en = len(x['players']) - 1
        elif e in gtime.OUTS and x['s'] != 0:
            outs.setdefault(x['s'], x['t'] * fac)
        elif e == 'census':
            cen.setdefault(x['t'] * fac, {})[x['s']] = x
    return n_en, sorted(outs.values()), cen


def at(cen, t):
    ts = [u for u in cen if u <= t]
    return cen[max(ts)] if ts else None


print('fresh-start win rates:')
for n, runs in FRESH.items():
    res = [x['result'] == 'win' for r in runs for x, _ in games(r)]
    print(f'  N={n}: {sum(res)} of {len(res)} ({100 * sum(res) / max(1, len(res)):.1f} %)')

for N, runs in RUNS.items():
    print(f'\n=== N={N} ({sum(1 for r in runs for _ in games(r))} games)')
    rows = {}
    for r in runs:
        for x, f in games(r):
            n_en, outs, cen = parse(f)
            win = x['result'] == 'win'
            end = x['t']
            for k in range(N - 1, 1, -1):
                need = N - k  # outs needed to be at k remaining
                if len(outs) < need:
                    break
                t = outs[need - 1]
                if t >= end:
                    break
                c = at(cen, t)
                if not c or 0 not in c:
                    continue
                us = c[0]
                enemies = [v for s, v in c.items() if s != 0 and v.get('alive', 1)]
                en_w = [v.get('rock', 0) + v.get('iron', 0) + v.get('rubber', 0) for v in enemies]
                rows.setdefault(k, []).append(dict(win=win, t=t / 60, rest=(end - t) / 60, units=us['units'],
                                                   war=us.get('rock', 0) + us.get('iron', 0) + us.get('rubber', 0),
                                                   towers=us['towers'], arm=us['armories'],
                                                   en_war=sum(en_w), en_units=sum(v['units'] for v in enemies)))
    print(' k  reached  win%   t(min) med | us: units war towers arm | remaining copies: warriors units (sum)')
    for k in sorted(rows, reverse=True):
        rs = rows[k]
        w = sum(r['win'] for r in rs)
        m = lambda key: st.median(r[key] for r in rs)
        print(f'{k:2d} {len(rs):7d} {100 * w / len(rs):6.1f} {m("t"):8.1f} | {m("units"):5.0f} {m("war"):4.0f} {m("towers"):5.0f} '
              f'{m("arm"):3.0f} | {m("en_war"):6.0f} {m("en_units"):6.0f}')
