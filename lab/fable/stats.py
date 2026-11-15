#!/usr/bin/env python3
"""Per-run and paired statistics for 1-vs-N runs (team A = slot 0 against allied Hard copies).

    python lab/fable/stats.py RUN [RUN...]          one line per run, plus the win/loss split
    python lab/fable/stats.py --pair BASE VAR...    paired on the games both runs counted

Per game it reads results.jsonl and g/<key>.jsonl (times are game seconds):
  outs@T   enemy copies out (out or collapse event) by minute T
  surv     minutes until team A is out, capped at CAP (a win or draw counts as CAP)
  w10      our warriors (garrisons included) at 10:00
  a1lost   minute our first finished armory fell (None if never)
Wins are rare events with wide error bars; outs@T and surv move earlier and are what the paired mode tests (z = mean
paired difference / its standard error over games).
"""
import json
import math
import os
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
CAP = 60.0
MARKS = (8, 10, 12, 15, 20, 25, 30)
CACHE = {}


def game_stats(run, key, row):
    path = os.path.join(ROOT, run, 'g', key + '.jsonl')
    outs = {}
    a_out = None
    a1lost = None
    w10 = None
    units = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            if '"ev":"deaths"' in line or '"ev":"stunned"' in line or '"ev":"cast"' in line:
                continue
            e = json.loads(line)
            ev = e['ev']
            if ev in ('out', 'collapse'):
                if e['s'] == 0:
                    a_out = a_out if a_out is not None else e['t']
                else:
                    outs.setdefault(e['s'], e['t'])
            elif ev == 'razed' and e.get('s') == 0 and e.get('b') == 'armory' and not e.get('site'):
                a1lost = a1lost if a1lost is not None else e['t']
            elif ev == 'census' and e['s'] == 0:
                if w10 is None and e['t'] >= 600:
                    w10 = e['rock'] + e['iron'] + e['rubber'] + e['garrison']
                units[int(e['t'])] = e['units']
    times = sorted(outs.values())
    end = row['t']
    result = row['result']
    surv = CAP if result != 'loss' else min(CAP, (a_out if a_out is not None else end) / 60.0)
    return {
        'key': key, 'result': result, 'end': end / 60.0, 'surv': surv, 'out_times': [t / 60.0 for t in times],
        'outs': {m: sum(1 for t in times if t <= m * 60) for m in MARKS}, 'w10': w10,
        'a1lost': None if a1lost is None else a1lost / 60.0, 'n': row['slots'] - 1,
        'kills': row['teams'][0]['kills'], 'lost': row['teams'][0]['lost'],
        'iron': row['teams'][0]['harvestedIron'],
    }


def load(run):
    if run in CACHE:
        return CACHE[run]
    cache_path = os.path.join(ROOT, run, 'fable-stats.json')
    res_path = os.path.join(ROOT, run, 'results.jsonl')
    rows = {}
    with open(res_path, encoding='utf-8') as f:
        for line in f:
            if line.strip():
                r = json.loads(line)
                if r.get('result') is not None:
                    rows[r['key']] = r
    cached = {}
    if os.path.exists(cache_path):
        with open(cache_path, encoding='utf-8') as f:
            cached = json.load(f)
    games = {}
    dirty = False
    for key, row in rows.items():
        if key in cached:
            g = cached[key]
            g['outs'] = {int(k): v for k, v in g['outs'].items()}
        else:
            g = game_stats(run, key, row)
            dirty = True
        games[key] = g
    if dirty:
        with open(cache_path, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(games, f)
    CACHE[run] = games
    return games


def mean(xs):
    xs = [x for x in xs if x is not None]
    return sum(xs) / len(xs) if xs else float('nan')


def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else float('nan')


def wilson(w, n, z=1.96):
    if n == 0:
        return (0.0, 0.0)
    p = w / n
    d = 1 + z * z / n
    c = p + z * z / (2 * n)
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return ((c - h) / d, (c + h) / d)


def describe(name, games):
    gs = list(games.values())
    n = len(gs)
    w = sum(1 for g in gs if g['result'] == 'win')
    l = sum(1 for g in gs if g['result'] == 'loss')
    d = n - w - l
    lo, hi = wilson(w, n)
    print('%-28s n=%3d W %3d L %3d D %2d  win %5.1f%% [%4.1f, %4.1f]  surv %5.1f  outs@10/15/20/30 %4.2f %4.2f %4.2f %4.2f'
          '  w10 %5.1f  iron %4.0f  kills %5.0f lost %4.0f'
          % (name, n, w, l, d, 100.0 * w / max(n, 1), 100 * lo, 100 * hi, mean([g['surv'] for g in gs]),
             mean([g['outs'][10] for g in gs]), mean([g['outs'][15] for g in gs]), mean([g['outs'][20] for g in gs]),
             mean([g['outs'][30] for g in gs]), mean([g['w10'] for g in gs]), mean([g['iron'] for g in gs]),
             mean([g['kills'] for g in gs]), mean([g['lost'] for g in gs])))
    for tag in ('win', 'loss'):
        sel = [g for g in gs if g['result'] == tag]
        if not sel:
            continue
        kth = []
        for k in range(6):
            kth.append(med([g['out_times'][k] if len(g['out_times']) > k else None for g in sel]))
        print('    %-4s %3d: end %6.1f  a1lost %5.1f  outs@10/15/20/30 %4.2f %4.2f %4.2f %4.2f  k-th copy out (median min): %s'
              % (tag, len(sel), med([g['end'] for g in sel]), med([g['a1lost'] for g in sel]),
                 mean([g['outs'][10] for g in sel]), mean([g['outs'][15] for g in sel]),
                 mean([g['outs'][20] for g in sel]), mean([g['outs'][30] for g in sel]),
                 ' '.join('%.1f' % t for t in kth)))


def zstat(diffs):
    n = len(diffs)
    if n < 2:
        return 0.0, 0.0
    m = sum(diffs) / n
    var = sum((x - m) ** 2 for x in diffs) / (n - 1)
    se = math.sqrt(var / n)
    return m, (m / se if se > 0 else 0.0)


def pair(base_name, var_name):
    a, b = load(base_name), load(var_name)
    keys = sorted(set(a) & set(b))
    if not keys:
        print('%s vs %s: no common games' % (base_name, var_name))
        return
    wa = sum(1 for k in keys if a[k]['result'] == 'win')
    wb = sum(1 for k in keys if b[k]['result'] == 'win')
    gained = sum(1 for k in keys if a[k]['result'] != 'win' and b[k]['result'] == 'win')
    lost = sum(1 for k in keys if a[k]['result'] == 'win' and b[k]['result'] != 'win')
    # sign test z for discordant pairs
    disc = gained + lost
    zw = (gained - lost) / math.sqrt(disc) if disc else 0.0
    parts = ['%-26s n=%3d W %3d -> %3d (+%d -%d, z %+.1f)' % (var_name, len(keys), wa, wb, gained, lost, zw)]
    m, z = zstat([b[k]['surv'] - a[k]['surv'] for k in keys])
    parts.append('surv %+.2f (z %+.1f)' % (m, z))
    for mark in (10, 15, 20, 30):
        m, z = zstat([b[k]['outs'][mark] - a[k]['outs'][mark] for k in keys])
        parts.append('outs@%d %+.2f (z %+.1f)' % (mark, m, z))
    m, z = zstat([(b[k]['w10'] or 0) - (a[k]['w10'] or 0) for k in keys])
    parts.append('w10 %+.1f (z %+.1f)' % (m, z))
    print('  '.join(parts))


def main(argv):
    if argv and argv[0] == '--pair':
        base = argv[1]
        describe(base, load(base))
        for var in argv[2:]:
            pair(base, var)
        return
    for run in argv:
        describe(run, load(run))


if __name__ == '__main__':
    main(sys.argv[1:])
