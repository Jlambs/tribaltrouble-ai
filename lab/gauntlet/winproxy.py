#!/usr/bin/env python3
"""Win proxy for 1-vs-N runs: the predicted chance of an eventual win from the state at 15, 20 and 25 minutes.

    python lab/gauntlet/winproxy.py RUN [RUN...]            # per run: wins, proxy-expected wins, elim, P15/P20/P25, wp
    python lab/gauntlet/winproxy.py --pair BASE VARIANT      # paired, game by game: diff, SE, z of every score
    python lab/gauntlet/winproxy.py --games RUN              # one line per game
    python lab/gauntlet/winproxy.py fit [RUN...] [--save F]  # refit the model (numpy), with seed-fold validation
    add --model F to any mode to use a model saved by fit --save instead of the built-in one

Scores of team A (our AI, slot 0 in the benchmark), per game (plus survival rows in --pair: surv60 = minutes until out,
capped at 60, a win counting 60; alive30 / alive40; hold20 = an armory of ours standing at the 20-min census):
  PT     predicted P(win) from the census at T = 15, 20, 25 min: a logistic model of
           x_out   copies out by T / N          x_home  copies in but homeless (no finished quarters or armory) / N
           lsr     log((ours + 10) / (all copies' + 10)) census strength
           lsr_h   the same against the housed copies only
           a_bld   our finished quarters + armories     a_towers  our finished towers
           a_iron  log(our iron harvested + 10)
         with one intercept per N (N outside the fitted range uses the nearest fitted N). A game already decided at T
         scores its result: 0 when we are out (or the game failed), 1 when we have won.
  wp     the mean of P15, P20 and P25: the recommended screening score, in win-rate units
  wpe    wp + 0.1 x elim (a small elim weight; slightly better recovery of true win differences at N=11)
  elim   copies out before we were out, as a share of N (1 for a win), as in score.py
In --pair mode the win difference is also split into when it happened: by 15 min (P15), 15-25 min (P25 - P15) and
after 25 min (win - P25); the three parts add up to the win difference.

Validation of the built-in model (all hv runs on the benchmark maps, 2026-09-28; report in the session scratchpad
maxn/proxy.md): held-out-seed AUC at N=11 0.95 / 0.98 / 0.99 for P15 / P20 / P25, calibrated per run (observed vs
expected wins over 60 N=11 runs within binomial noise), paired SE of wp over 200 games 0.0073 at N=11 (wins 0.0141).
The model reads the state at T and assumes the AI converts it as the fitted runs did; refit after big changes to
the late game (fit mode). Failed games count as losses.
"""
import json
import math
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
TIMES = (15, 20, 25)
FEATURES = ['x_out', 'x_home', 'lsr', 'lsr_h', 'a_bld', 'a_towers', 'a_iron']
ELIM_WEIGHT = 0.1

# Fitted on 23,010 hv games (152 runs, N=7..12; N=7 shared N=8's intercept), ridge 1 on standardized features;
# intercepts by N (N >= 12 uses N=11's until a refit has >= 10 wins at that N).
MODEL = {
    '15': {'coef': {'x_out': 6.0836, 'x_home': 4.5214, 'lsr': 3.148, 'lsr_h': 3.7686, 'a_bld': -0.0215,
                    'a_towers': 0.0486, 'a_iron': 1.2493},
           'intercept': {'8': -3.645, '9': -4.4925, '10': -4.6967, '11': -3.2044}},
    '20': {'coef': {'x_out': 6.3979, 'x_home': 6.1412, 'lsr': 3.0615, 'lsr_h': 2.0757, 'a_bld': 0.1571,
                    'a_towers': 0.0536, 'a_iron': 1.0644},
           'intercept': {'8': -5.4275, '9': -6.2262, '10': -6.2967, '11': -4.9985}},
    '25': {'coef': {'x_out': 5.7964, 'x_home': 6.272, 'lsr': 2.9149, 'lsr_h': 0.8747, 'a_bld': 0.2033,
                    'a_towers': 0.0686, 'a_iron': 0.5691},
           'intercept': {'8': -4.0652, '9': -4.6065, '10': -4.8534, '11': -3.663}},
}


# ---------------------------------------------------------------------------------------------------------- reading

def read_game(run, row):
    """Features at each T (None when the game is decided by then) and the per-game facts, from g/<key>.jsonl."""
    key = row['key']
    g = {'key': key, 'seed': row.get('seed', 0), 'win': 1 if row.get('result') == 'win' else 0,
         'failed': row.get('result') is None, 't_end': row.get('t') or 0.0}
    path = os.path.join(ROOT, run, 'g', key + '.jsonl')
    if g['failed'] or not os.path.exists(path):
        g.update(failed=True, N=max(1, (row.get('slots') or 2) - 1), elim=0.0, feats={t: None for t in TIMES},
                 surv=0.0, hold20=0)
        return g
    want = {t * 60 for t in TIMES}
    census = {}
    outs = {}
    header = None
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('{"ev":"census"'):
                e = json.loads(line)
                t = int(round(e['t']))
                if t in want:
                    census[(e['s'], t)] = e
            elif line.startswith('{"ev":"out"') or line.startswith('{"ev":"collapse"'):
                e = json.loads(line)
                outs.setdefault(e['s'], e['t'])
            elif line.startswith('{"ev":"game"'):
                header = json.loads(line)
    players = header['players']
    a_team = players[0]['team']
    a_slots = [p['s'] for p in players if p['team'] == a_team]
    b_slots = [p['s'] for p in players if p['team'] != a_team]
    n = len(b_slots)
    g['N'] = n
    a_out = min([outs[s] for s in a_slots if s in outs], default=None)
    if a_out is not None and len([s for s in a_slots if s in outs]) < len(a_slots):
        a_out = None
    limit = a_out if a_out is not None else g['t_end'] + 1
    # survival: seconds until we are out (a win or a game we survive to its end counts as surviving it)
    g['surv'] = float(a_out) if a_out is not None and not g['win'] else max(g['t_end'], 3600.0)
    us20 = [census.get((s, 1200)) for s in a_slots]
    g['hold20'] = 1 if g['surv'] > 1200 and all(u is not None for u in us20) and sum(u['armories'] for u in us20) > 0 else 0
    g['elim'] = 1.0 if g['win'] else sum(1 for s in b_slots if s in outs and outs[s] <= limit) / max(1, n)
    g['feats'] = {}
    for tm in TIMES:
        t = tm * 60
        us = [census.get((s, t)) for s in a_slots]
        if g['t_end'] < t - 0.01 or (a_out is not None and a_out <= t) or any(u is None for u in us):
            g['feats'][tm] = None  # decided by T
            continue
        a_str = sum(u['strength'] for u in us)
        e_out = e_home = 0
        e_str = e_str_h = 0.0
        for s in b_slots:
            if s in outs and outs[s] <= t:
                e_out += 1
                continue
            e = census.get((s, t))
            if e is None:
                continue
            e_str += e['strength']
            if e['quarters'] + e['armories'] == 0:
                e_home += 1
            else:
                e_str_h += e['strength']
        g['feats'][tm] = {
            'x_out': e_out / n, 'x_home': e_home / n,
            'lsr': math.log((a_str + 10.0) / (e_str + 10.0)), 'lsr_h': math.log((a_str + 10.0) / (e_str_h + 10.0)),
            'a_bld': sum(u['quarters'] + u['armories'] for u in us), 'a_towers': sum(u['towers'] for u in us),
            'a_iron': math.log(sum(u['harvestedIron'] for u in us) + 10.0),
        }
    return g


def read_rows(run):
    rows = []
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def read_run(run, jobs=1):
    rows = read_rows(run)
    if jobs > 1:
        from multiprocessing import Pool
        with Pool(jobs) as pool:
            gs = pool.starmap(read_game, [(run, r) for r in rows], chunksize=8)
    else:
        gs = [read_game(run, r) for r in rows]
    return gs


# ---------------------------------------------------------------------------------------------------------- scoring

def intercept_for(model, tm, n):
    ib = model[str(tm)]['intercept']
    if str(n) in ib:
        return ib[str(n)]
    ns = sorted(int(k) for k in ib)
    near = min(ns, key=lambda k: (abs(k - n), -k))
    return ib[str(near)]


def prob(model, tm, n, feats):
    m = model[str(tm)]
    z = intercept_for(model, tm, n) + sum(m['coef'][k] * feats[k] for k in FEATURES)
    if z > 35:
        return 1.0
    return 1.0 / (1.0 + math.exp(-z))


def score(g, model):
    s = {'win': g['win'], 'elim': g['elim']}
    for tm in TIMES:
        f = g['feats'][tm]
        if f is None:
            # decided by T: the result (a loss when we are out, a win when the game is over and won)
            s['P%d' % tm] = float(g['win']) if (not g['failed'] and g['t_end'] < tm * 60 - 0.01) else 0.0
        else:
            s['P%d' % tm] = prob(model, tm, g['N'], f)
    s['wp'] = sum(s['P%d' % tm] for tm in TIMES) / len(TIMES)
    s['wpe'] = s['wp'] + ELIM_WEIGHT * s['elim']
    s['by15'] = s['P15']
    s['15-25'] = s['P25'] - s['P15']
    s['after25'] = s['win'] - s['P25']
    # survival rows for N >= 12, where the 25-40-min window that wp cannot see decides games
    s['surv60'] = min(g['surv'], 3600.0) / 60.0
    s['alive30'] = 1.0 if g['surv'] > 1800 else 0.0
    s['alive40'] = 1.0 if g['surv'] > 2400 else 0.0
    s['hold20'] = float(g['hold20'])
    return s


def scored_run(run, model, jobs=1):
    out = {}
    for g in read_run(run, jobs):
        s = score(g, model)
        s['seed'] = g['seed']
        s['failed'] = g['failed']
        out[g['key']] = s
    return out


# ---------------------------------------------------------------------------------------------------------- reports

def mean(xs):
    return sum(xs) / len(xs) if xs else float('nan')


def se(xs):
    n = len(xs)
    if n < 2:
        return float('nan')
    m = mean(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (n - 1) / n)


def print_table(rows):
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    for r in rows:
        print('  '.join(c.ljust(w) for c, w in zip(r, widths)).rstrip())


SUMMARY = ['elim', 'P15', 'P20', 'P25', 'wp', 'wpe']
PAIRED = ['win', 'elim', 'P15', 'P20', 'P25', 'wp', 'wpe', 'by15', '15-25', 'after25', 'surv60', 'alive30', 'alive40',
          'hold20']


def summary(runs, model, jobs):
    out = [['run', 'n', 'W', 'E[W]', 'win%', 'wp%'] + SUMMARY[:-2] + ['wp', 'wpe', 'fail']]
    for run in runs:
        gs = list(scored_run(run, model, jobs).values())
        n = len(gs)
        w = sum(g['win'] for g in gs)
        e = sum(g['wp'] for g in gs)
        line = [run, str(n), str(w), '%.1f' % e, '%.2f' % (100.0 * w / max(1, n)), '%.2f' % (100.0 * e / max(1, n))]
        line += ['%.4f' % mean([g[f] for g in gs]) for f in SUMMARY]
        line.append(str(sum(1 for g in gs if g['failed'])))
        out.append(line)
    print_table(out)
    print('E[W]: wins the proxy expects (sum of wp); wp%: the same as a rate')


def pair(base, variant, model, jobs):
    a = scored_run(base, model, jobs)
    b = scored_run(variant, model, jobs)
    keys = sorted(set(a) & set(b), key=lambda k: (a[k]['seed'], k))
    print('paired %s -> %s on %d common games' % (base, variant, len(keys)))
    out = [['metric', base, variant, 'diff', 'se', 'z']]
    zs = {}
    for f in PAIRED:
        xa = [a[k][f] for k in keys]
        xb = [b[k][f] for k in keys]
        d = [y - x for x, y in zip(xa, xb)]
        s = se(d)
        z = mean(d) / s if s and s == s and s > 0 else float('nan')
        zs[f] = (mean(d), s, z)
        if f == 'by15':
            out.append(['win split:', '', '', '', '', ''])
        if f == 'surv60':
            out.append(['survival:', '', '', '', '', ''])
        out.append([f, '%.4f' % mean(xa), '%.4f' % mean(xb), '%+.4f' % mean(d), '%.4f' % s,
                    ('%+.1f' % z) + (' *' if abs(z) > 2 else '')])
    print_table(out)
    gained = sum(1 for k in keys if b[k]['win'] > a[k]['win'])
    lost = sum(1 for k in keys if b[k]['win'] < a[k]['win'])
    print('wins gained %d, lost %d' % (gained, lost))
    m, s, z = zs['wp']
    print('wp: the variant changes the win rate by about %+.2f pp (95%% %+.2f..%+.2f), z %+.1f'
          % (100 * m, 100 * (m - 1.96 * s), 100 * (m + 1.96 * s), z))
    if zs['elim'][0] >= .01 and zs['elim'][2] >= 1.5 and zs['wp'][2] < 0.5:
        print('note: elim is up but wp is not: the extra outs do not look like wins (cf. finish_copies)')


def games(run, model, jobs):
    gs = scored_run(run, model, jobs)
    out = [['key', 'win', 'elim', 'P15', 'P20', 'P25', 'wp']]
    for k in sorted(gs, key=lambda k: gs[k]['seed']):
        g = gs[k]
        out.append([k, str(g['win'])] + ['%.4f' % g[f] for f in ('elim', 'P15', 'P20', 'P25', 'wp')])
    print_table(out)


# ---------------------------------------------------------------------------------------------------------- fitting

def eligible_runs():
    """Every gauntlet (or frozen @g...) vs hard*N run (vikings, N >= 8) on the benchmark maps at full length."""
    out = []
    for run in sorted(os.listdir(ROOT)):
        p = os.path.join(ROOT, run, 'run.json')
        if not os.path.exists(p) or not os.path.exists(os.path.join(ROOT, run, 'results.jsonl')):
            continue
        try:
            j = json.load(open(p, encoding='utf-8'))
        except (OSError, ValueError):
            continue
        m = re.match(r'^(\S+) vs hard\*(\d+)$', j.get('players', ''))
        cfg = j.get('config', '')
        if not m or not (m.group(1).startswith('gauntlet') or m.group(1).startswith('@g')):
            continue
        if int(m.group(2)) < 8 or '360 min' not in cfg or 'large tropical h0..2 t10 s10' not in cfg:
            continue
        out.append(run)
    return out


def irls(X, y, l2=1.0):
    import numpy as np
    mu = X.mean(0)
    sd = X.std(0)
    sd[sd == 0] = 1
    Z = np.column_stack([np.ones(len(X)), (X - mu) / sd])
    b = np.zeros(Z.shape[1])
    b[0] = math.log((y.mean() + 1e-6) / (1 - y.mean() + 1e-6))
    P = np.eye(Z.shape[1]) * l2
    P[0, 0] = 0
    for _ in range(60):
        p = 1 / (1 + np.exp(-np.clip(Z @ b, -35, 35)))
        W = p * (1 - p) + 1e-9
        step = np.linalg.solve((Z * W[:, None]).T @ Z + P, Z.T @ (y - p) - P @ b)
        b += step
        if np.abs(step).max() < 1e-8:
            break
    coef = b[1:] / sd
    return b[0] - (coef * mu).sum(), coef


def fit(runs, save, jobs):
    import numpy as np
    if not runs:
        runs = eligible_runs()
    gs = []
    for i, run in enumerate(runs):
        for g in read_run(run, jobs):
            if g['seed'] >= 30001:  # never the exam seeds
                continue
            g['run'] = run
            gs.append(g)
        print('read %d/%d runs, %d games' % (i + 1, len(runs), len(gs)), file=sys.stderr)
    model = {}
    report = []
    for tm in TIMES:
        open_ = [g for g in gs if not g['failed'] and g['feats'][tm] is not None]
        wins_by_n = {}
        for g in open_:
            wins_by_n[g['N']] = wins_by_n.get(g['N'], 0) + g['win']
        ns = sorted(n for n, w in wins_by_n.items() if w >= 10)
        ref = max(ns, key=lambda n: sum(1 for g in open_ if g['N'] == n))
        dums = [n for n in ns if n != ref]

        def matrix(sel):
            rows = []
            for g in sel:
                f = g['feats'][tm]
                n = min(ns, key=lambda k: (abs(k - g['N']), -k))
                rows.append([f[k] for k in FEATURES] + [1.0 if n == d else 0.0 for d in dums])
            return np.array(rows), np.array([g['win'] for g in sel], float)
        X, y = matrix(open_)
        b0, b = irls(X, y)
        ib = {str(ref): round(float(b0), 4)}
        for d, bd in zip(dums, b[len(FEATURES):]):
            ib[str(d)] = round(float(b0 + bd), 4)
        model[str(tm)] = {'coef': {k: round(float(v), 4) for k, v in zip(FEATURES, b)}, 'intercept': ib}
        # seed-fold validation (5 folds by seed, so no seed is in its own fit)
        pred = {}
        for k in range(5):
            tr = [g for g in open_ if g['seed'] % 5 != k]
            te = [g for g in open_ if g['seed'] % 5 == k]
            Xt, yt = matrix(tr)
            c0, c = irls(Xt, yt)
            Xe, _ = matrix(te)
            for g, z in zip(te, Xe @ c + c0):
                pred[(g['run'], g['key'])] = 1 / (1 + math.exp(-max(-35.0, min(35.0, z))))
        report.append((tm, pred))
    text = json.dumps(model, indent=1)
    print('MODEL =', text)
    if save:
        with open(save, 'w', encoding='utf-8') as f:
            f.write(text)
        print('saved to', save)
    # validation: AUC and observed vs expected wins per N, over all games (decided games at their result)
    for tm, pred in report:
        print('P%d (held-out seeds):' % tm)
        by_n = {}
        for g in gs:
            p = pred.get((g['run'], g['key']))
            if p is None:
                p = float(g['win']) if (not g['failed'] and g['t_end'] < tm * 60 - 0.01) else 0.0
            by_n.setdefault(g['N'], []).append((p, g['win']))
        for n in sorted(by_n):
            ps = by_n[n]
            print('  N=%-2d games %5d  wins %4d  expected %7.1f  AUC %.3f' % (
                n, len(ps), sum(w for _, w in ps), sum(p for p, _ in ps), auc(ps)))


def auc(ps):
    pos = [p for p, w in ps if w]
    neg = [p for p, w in ps if not w]
    if not pos or not neg:
        return float('nan')
    allp = sorted([(p, 1) for p in pos] + [(p, 0) for p in neg])
    rank_sum = 0.0
    i = 0
    while i < len(allp):
        j = i
        while j + 1 < len(allp) and allp[j + 1][0] == allp[i][0]:
            j += 1
        r = (i + j) / 2 + 1
        rank_sum += r * sum(1 for x in allp[i:j + 1] if x[1])
        i = j + 1
    return (rank_sum - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))


# ---------------------------------------------------------------------------------------------------------- main

def main(argv):
    model = MODEL
    jobs = 1
    save = None
    args = []
    i = 0
    while i < len(argv):
        if argv[i] == '--model':
            with open(argv[i + 1], encoding='utf-8') as f:
                model = json.load(f)
            i += 2
        elif argv[i] == '--jobs':
            jobs = int(argv[i + 1])
            i += 2
        elif argv[i] == '--save':
            save = argv[i + 1]
            i += 2
        else:
            args.append(argv[i])
            i += 1
    if not args:
        print(__doc__)
    elif args[0] == 'fit':
        fit(args[1:], save, max(jobs, 1))
    elif args[0] == '--pair':
        pair(args[1], args[2], model, jobs)
    elif args[0] == '--games':
        games(args[1], model, jobs)
    else:
        summary(args, model, jobs)


if __name__ == '__main__':
    main(sys.argv[1:])
