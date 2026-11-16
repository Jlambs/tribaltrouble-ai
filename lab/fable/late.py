#!/usr/bin/env python3
"""What we do in a long game: python lab/fable/late.py [--window MIN] [--game KEY] RUN

Needs a logged run (--logs). Per window of game time (10 minutes by default), means of the AI's 30-second STAT lines
and the census: buildings, peons by job (idle, building, in transit, wood, iron, rock gatherers against the wanted
numbers), peons inside the armory against the wanted, stock, warriors by role, the mode's share, iron harvested a
minute, kills and losses a minute, and the enemy copies housed with their warriors. One table a game, or with
--game only that one; without it also the mean over all games by window.
"""
import collections
import glob
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
STAT = re.compile(
    r'^\s*([0-9.]+) s0 STAT\s+Q(\d+)\+(\d+) A(\d+)\+(\d+) T(\d+)\+(\d+) proj=(\d+) Is=(-?\d+) Rs=(-?\d+)( RS)? '
    r'held=(\d+) peons=(\d+) \(idle (\d+) bld (\d+) tr (\d+) g (\d+)/(-?\d+),(\d+)/(-?\d+),(\d+)/(-?\d+),'
    r'(\d+)/(-?\d+)\)(?: W=(\d+)/(-?\d+) res=(\d+),(\d+),(\d+),(\d+) wpn=(\d+),(\d+) cyc=(-?\d+)/(-?\d+))?'
    r' trx=(\d+)/(\d+)/(\d+) mode=(\w+) army=(\d+) atk=(\d+) raid=(\d+) twr=(\d+) str=([0-9.]+) '
    r'thr=(\d+)/([0-9.]+) park=(\d+)/(\d+) man=(\d+)/(\d+)')
FIELDS = ['t', 'Q', 'Qs', 'A', 'As', 'T', 'Ts', 'proj', 'Is', 'Rs', 'RS', 'held', 'peons', 'idle', 'bld', 'tr',
          'gt', 'wt', 'gi', 'wi', 'gr', 'wr', 'gc', 'wc', 'W', 'Ww', 'res_t', 'res_r', 'res_i', 'res_u', 'wpn_i',
          'wpn_u', 'cyc_t', 'cyc_i', 'trx_n', 'trx_f', 'trx_x', 'mode', 'army', 'atk', 'raid', 'twr', 'str', 'thr',
          'thr_s', 'park_o', 'park_i', 'man', 'man_of']


def stats(path):
    rows = []
    for line in open(path, encoding='utf-8', errors='replace'):
        if ' STAT ' not in line:
            continue
        m = STAT.match(line)
        if not m:
            continue
        d = {}
        for k, v in zip(FIELDS, m.groups()):
            if k in ('mode', 'RS'):
                d[k] = v
            elif v is None:
                d[k] = 0.0
            else:
                d[k] = float(v)
        rows.append(d)
    return rows


def census(path):
    ours = {}
    foes = collections.defaultdict(collections.Counter)
    end = None
    deaths = []
    for line in open(path, encoding='utf-8'):
        if '"ev":"deaths","s":0,' in line or ('"deaths"' in line and '"s":0,' in line):
            d = json.loads(line)
            if d['s'] == 0:
                deaths.append((d['t'], d['peon'], d['n'] - d['peon']))
        elif '"census"' in line:
            d = json.loads(line)
            t = int(round(d['t']))
            war = d['rock'] + d['iron'] + d['rubber']
            if d['s'] == 0:
                d['war'] = war
                ours[t] = d
            else:
                f = foes[t]
                f['war'] += war
                if d.get('alive') and (d['quarters'] or d['armories']):
                    f['housed'] += 1
        elif '"ev":"end"' in line:
            end = json.loads(line)
    return ours, foes, end, deaths


def table(key, rows, ours, foes, end, window, deaths=()):
    out = []
    last = max(r['t'] for r in rows) if rows else 0
    w = 0
    while w * window * 60 < last:
        lo, hi = w * window * 60, (w + 1) * window * 60
        rs = [r for r in rows if lo < r['t'] <= hi]
        if not rs:
            w += 1
            continue

        def mean(k):
            return sum(r[k] for r in rs) / len(rs)
        modes = collections.Counter(r['mode'] for r in rs)
        o_lo = ours.get(lo) or ours.get(min(ours)) if ours else None
        ts = [t for t in ours if lo < t <= hi]
        o_hi = ours[max(ts)] if ts else None
        span = (max(ts) - lo) / 60.0 if ts else 1
        iron = (o_hi['harvestedIron'] - (o_lo['harvestedIron'] if o_lo and lo else 0)) / span if o_hi else 0
        wood = (o_hi['harvestedTree'] - (o_lo['harvestedTree'] if o_lo and lo else 0)) / span if o_hi else 0
        dp = sum(d[1] for d in deaths if lo < d[0] <= hi) / span
        dw = sum(d[2] for d in deaths if lo < d[0] <= hi) / span
        rock = (o_hi['harvestedRock'] - (o_lo['harvestedRock'] if o_lo and lo else 0)) / span if o_hi else 0
        kills = (o_hi['kills'] - (o_lo['kills'] if o_lo and lo else 0)) / span if o_hi else 0
        lost = (o_hi['lost'] - (o_lo['lost'] if o_lo and lo else 0)) / span if o_hi else 0
        f = foes.get(max(ts)) if ts else None
        out.append({
            'w': w, 'min': f'{int(lo / 60)}-{int(hi / 60)}', 'Q': mean('Q'), 'A': mean('A'), 'T': mean('T'),
            'man': mean('man'), 'peons': mean('peons'), 'idle': mean('idle'), 'bld': mean('bld'), 'tr': mean('tr'),
            'gt': mean('gt'), 'wt': mean('wt'), 'gi': mean('gi'), 'wi': mean('wi'), 'gr': mean('gr'),
            'wr': mean('wr'), 'W': mean('W'), 'Ww': mean('Ww'), 'held': mean('held'), 'res_t': mean('res_t'),
            'res_i': mean('res_i'), 'res_r': mean('res_r'), 'wpn': mean('wpn_i'), 'army': mean('army'),
            'atk': mean('atk'), 'twr': mean('twr'), 'thr': mean('thr'), 'park': mean('park_o') + mean('park_i'),
            'home': modes['HOME'] / len(rs), 'iron': iron, 'rock': rock, 'wood': wood, 'dp': dp, 'dw': dw,
            'kills': kills, 'lost': lost,
            'housed': f['housed'] if f else 0, 'ewar': f['war'] if f else 0,
            'units': o_hi['units'] if o_hi else 0, 'inside': o_hi['inside'] if o_hi else 0,
            'modes': ' '.join(f'{k}:{v}' for k, v in modes.most_common(3)),
        })
        w += 1
    return out


HEAD = ('minutes | Q  A   T man | units peons idle  bld   tr | wood     iron     rock   | inside/want held | '
        'stock w/i/r axes | army  atk twr | iron rock /min | kills lost /min | housed their w | home thr park')


def show(rows):
    print(HEAD)
    for r in rows:
        print(f'{r["min"]:>7} | {r["Q"]:.0f} {r["A"]:2.0f} {r["T"]:3.0f} {r["man"]:3.0f} | {r["units"]:5.0f} '
              f'{r["peons"]:5.0f} {r["idle"]:4.0f} {r["bld"]:4.0f} {r["tr"]:4.0f} | {r["gt"]:3.0f}/{r["wt"]:<3.0f} '
              f'{r["gi"]:4.0f}/{r["wi"]:<3.0f} {r["gr"]:4.0f}/{r["wr"]:<3.0f} | {r["W"]:5.0f}/{r["Ww"]:<4.0f} '
              f'{r["held"]:4.0f} | {r["res_t"]:3.0f}/{r["res_i"]:2.0f}/{r["res_r"]:2.0f} {r["wpn"]:4.0f} | '
              f'{r["army"]:4.0f} {r["atk"]:4.0f} {r["twr"]:3.0f} | {r["wood"]:5.1f} {r["iron"]:4.1f} {r["rock"]:4.1f} | '
              f'{r["kills"]:5.1f} {r["dp"]:5.1f} {r["dw"]:5.1f} | {r["housed"]:3.0f} {r["ewar"]:6.0f} | {r["home"]:4.2f} '
              f'{r["thr"]:3.1f} {r["park"]:4.0f}')


def main(args):
    window = 10
    only = None
    if '--window' in args:
        i = args.index('--window')
        window = int(args[i + 1])
        del args[i:i + 2]
    if '--game' in args:
        i = args.index('--game')
        only = args[i + 1]
        del args[i:i + 2]
    run = args[0]
    acc = collections.defaultdict(list)
    for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0-ai-s0.log'))):
        key = os.path.basename(path)[:-len('-ai-s0.log')]
        if only and only not in key:
            continue
        rows = stats(path)
        ours, foes, end, deaths = census(path[:-len('-ai-s0.log')] + '.jsonl')
        result = '?'
        if end:
            result = ('won' if end.get('winnerTeam') == 0 else 'lost') + f' at {end["t"] / 60:.1f} min'
        t = table(key, rows, ours, foes, end, window, deaths)
        print(f'== {key} {result}')
        show(t)
        print()
        for r in t:
            acc[(end.get('winnerTeam') == 0 if end else None, r['w'])].append(r)
    if not only:
        for won in (True, False):
            ws = sorted(w for (k, w) in acc if k == won)
            if not ws:
                continue
            print(f'== mean over the games {"won" if won else "lost"} (games in the window in the first column)')
            rows = []
            for w in ws:
                rs = acc[(won, w)]
                m = {k: sum(r[k] for r in rs) / len(rs) for k in rs[0] if k not in ('min', 'modes', 'w')}
                m['min'] = f'{len(rs):2d} {rs[0]["min"]}'
                rows.append(m)
            show(rows)
            print()


if __name__ == '__main__':
    main(sys.argv[1:])
