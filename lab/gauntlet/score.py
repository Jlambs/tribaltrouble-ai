#!/usr/bin/env python3
"""Continuous per-game scores for 1-vs-N runs, and paired comparisons built on them.

    python lab/gauntlet/score.py RUN [RUN...]          # one line per run: wins and the mean of every score
    python lab/gauntlet/score.py --pair BASE VARIANT    # paired, game by game (same key), mean diff and SE
    python lab/gauntlet/score.py --games RUN            # one line per game
    python lab/gauntlet/score.py --predict RUN [RUN...] # how well each score (at its minute) predicts a win

Scores of team A (our AI, slot 0 in the benchmark), per game:
  win      1 for a win (every opponent eliminated), else 0
  elim     opponents out before we were out (or the game ended), as a share of all opponents; 1 for a win
  prog     elim, plus 0.25 x the share of the 60 minutes we survived when we lost (0.25 when we lasted an hour);
           a win scores 1.25 plus up to 0.25 for speed (0.25 at 20 min, 0 at 120+ min)
  lsrM     log(our strength / all opponents' strength) at minute M (census 'strength'; last census if earlier)
  lsmM     log(our strength / the strongest opponent's strength) at minute M
  kd       log((kills + 1) / (lost + 1)) of team A at the end
  tmin     game length in minutes
Runs are read from aisim/runs/<run>/ (results.jsonl and g/<key>.jsonl). Failed games are listed and count as losses
with every score at its worst.
All times are game time at every --speed (gtime converts rows and game files from before the harness counted it).
Those old non-normal files took the census every 30 x factor game s (2 min at ludicrous): lsrM reads the last before M.
"""
import json
import math
import os
import sys

import gtime

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
MINUTES = (5, 10, 15, 20, 30)


def load_game(run, row):
    key = row['key']
    path = os.path.join(ROOT, run, 'g', key + '.jsonl')
    census = {}  # slot -> list of (t, strength, kills, lost)
    outs = {}
    header = None
    scale = 1.0  # game seconds per t of this file (gtime.factor of its header, the first line)
    with open(path, encoding='utf-8') as f:
        for line in f:
            e = json.loads(line)
            ev = e.get('ev')
            if ev == 'game':
                header = e
                scale = gtime.factor(e)
            elif ev == 'census':
                census.setdefault(e['s'], []).append((e['t'] * scale, e['strength'], e['kills'], e['lost']))
            elif ev in gtime.OUTS:
                if e['s'] not in outs:
                    outs[e['s']] = e['t'] * scale
    return header, census, outs


def at(series, t):
    """The last census sample at or before t (or the first after, if none)."""
    best = None
    for s in series:
        if s[0] <= t + 0.01:
            best = s
        else:
            break
    return best if best is not None else (series[0] if series else None)


def score_game(run, row):
    res = row.get('result')
    g = {'key': row['key'], 'seed': row['seed'], 'failed': res is None, 'problem': row.get('problem')}
    t_end = gtime.row(row).get('t') or 0.0
    g['tmin'] = t_end / 60.0
    if res is None:
        g.update(win=0, elim=0.0, prog=0.0, kd=-3.0)
        for m in MINUTES:
            g['lsr%d' % m] = -3.0
            g['lsm%d' % m] = -3.0
        return g
    header, census, outs = load_game(run, row)
    players = header['players']
    a_team = players[0]['team']
    a_slots = [p['s'] for p in players if p['team'] == a_team]
    b_slots = [p['s'] for p in players if p['team'] != a_team]
    a_out = min([outs[s] for s in a_slots if s in outs], default=None)
    if a_out is not None and len([s for s in a_slots if s in outs]) < len(a_slots):
        a_out = None  # team A had other players still in
    limit = a_out if a_out is not None else t_end + 1
    elim = sum(1 for s in b_slots if s in outs and outs[s] <= limit) / max(1, len(b_slots))
    win = 1 if res == 'win' else 0
    g['win'] = win
    g['elim'] = 1.0 if win else elim
    if win:
        g['prog'] = 1.25 + 0.25 * max(0.0, min(1.0, (120.0 - g['tmin']) / 100.0))
    else:
        surv = (a_out if a_out is not None else t_end) / 3600.0
        g['prog'] = elim + 0.25 * min(1.0, surv)
    for m in MINUTES:
        t = m * 60.0
        a = sum((at(census.get(s, []), t) or (0, 0, 0, 0))[1] for s in a_slots)
        bs = [(at(census.get(s, []), t) or (0, 0, 0, 0))[1] for s in b_slots]
        g['lsr%d' % m] = math.log((a + 10.0) / (sum(bs) + 10.0))
        g['lsm%d' % m] = math.log((a + 10.0) / (max(bs) + 10.0))
    kills = sum((census.get(s) or [(0, 0, 0, 0)])[-1][2] for s in a_slots)
    lost = sum((census.get(s) or [(0, 0, 0, 0)])[-1][3] for s in a_slots)
    g['kd'] = math.log((kills + 1.0) / (lost + 1.0))
    return g


def load_run(run):
    rows = []
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    games = {}
    for row in rows:
        games[row['key']] = score_game(run, row)
    return games


FIELDS = ['win', 'elim', 'prog', 'lsr10', 'lsr15', 'lsr20', 'lsm15', 'kd', 'tmin']


def mean(xs):
    return sum(xs) / len(xs) if xs else float('nan')


def se(xs):
    n = len(xs)
    if n < 2:
        return float('nan')
    m = mean(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (n - 1) / n)


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    r = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - r) / d, (c + r) / d)


def summary(runs):
    head = ['run', 'n', 'W', 'win%', 'wilson95'] + FIELDS[1:] + ['fail']
    out = [head]
    for run in runs:
        games = load_run(run)
        gs = list(games.values())
        n = len(gs)
        w = sum(g['win'] for g in gs)
        lo, hi = wilson(w, n)
        line = [run, str(n), str(w), '%.1f' % (100.0 * w / max(1, n)), '[%.1f,%.1f]' % (100 * lo, 100 * hi)]
        for f in FIELDS[1:]:
            line.append('%.3f' % mean([g[f] for g in gs]))
        line.append(str(sum(1 for g in gs if g['failed'])))
        out.append(line)
    print_table(out)


def pair(base, variant):
    a = load_run(base)
    b = load_run(variant)
    keys = sorted(set(a) & set(b), key=lambda k: (a[k]['seed'], k))
    print('paired %s -> %s on %d common games' % (base, variant, len(keys)))
    out = [['metric', base, variant, 'diff', 'se', 'z']]
    for f in FIELDS:
        xa = [a[k][f] for k in keys]
        xb = [b[k][f] for k in keys]
        d = [y - x for x, y in zip(xa, xb)]
        s = se(d)
        z = mean(d) / s if s and s == s and s > 0 else float('nan')
        out.append([f, '%.3f' % mean(xa), '%.3f' % mean(xb), '%+.3f' % mean(d), '%.3f' % s,
                    ('%+.1f' % z) + (' *' if abs(z) > 2 else '')])
    print_table(out)
    gained = [k for k in keys if b[k]['win'] > a[k]['win']]
    lost = [k for k in keys if b[k]['win'] < a[k]['win']]
    print('wins gained %d, lost %d (sign test on %d discordant)' % (len(gained), len(lost), len(gained) + len(lost)))


def games(run):
    gs = load_run(run)
    out = [['key'] + FIELDS + ['problem']]
    for k in sorted(gs, key=lambda k: gs[k]['seed']):
        g = gs[k]
        out.append([k] + ['%.3f' % g[f] if isinstance(g[f], float) else str(g[f]) for f in FIELDS]
                   + [str(g['problem'] or '')])
    print_table(out)


def predict(runs):
    """Point-biserial correlation of each score with the win, and the win rate by score tercile."""
    gs = []
    for run in runs:
        gs.extend(load_run(run).values())
    fields = ['elim', 'prog', 'kd'] + ['lsr%d' % m for m in MINUTES] + ['lsm%d' % m for m in MINUTES]
    wins = [g['win'] for g in gs]
    out = [['score', 'corr with win', 'win% low third', 'mid third', 'high third']]
    for f in fields:
        xs = [g[f] for g in gs]
        mx, mw = mean(xs), mean(wins)
        sx = math.sqrt(sum((x - mx) ** 2 for x in xs) / len(xs)) or 1e-9
        sw = math.sqrt(sum((w - mw) ** 2 for w in wins) / len(wins)) or 1e-9
        r = sum((x - mx) * (w - mw) for x, w in zip(xs, wins)) / len(xs) / sx / sw
        order = sorted(range(len(xs)), key=lambda i: xs[i])
        thirds = [order[:len(order) // 3], order[len(order) // 3:2 * len(order) // 3], order[2 * len(order) // 3:]]
        out.append([f, '%+.3f' % r] + ['%.1f' % (100.0 * mean([wins[i] for i in t])) for t in thirds])
    print('%d games, %d wins' % (len(gs), sum(wins)))
    print_table(out)


def print_table(rows):
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    for r in rows:
        print('  '.join(c.ljust(w) for c, w in zip(r, widths)).rstrip())


if __name__ == '__main__':
    args = sys.argv[1:]
    if not args:
        print(__doc__)
    elif args[0] == '--pair':
        pair(args[1], args[2])
    elif args[0] == '--games':
        games(args[1])
    elif args[0] == '--predict':
        predict(args[1:])
    else:
        summary(args)
