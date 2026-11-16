#!/usr/bin/env python3
"""The maps whose game is still open late: python lab/fable/band.py [--minute 30] [--lo 6] [--hi 9] [--seeds] RUN...

A change that acts only late in the game leaves every game identical up to then, so it can only turn the games that
are still open at that minute. Over the base runs (a later run replaces an earlier one's game on the same seed), the
games by the copies housed (a quarters or an armory) at the minute, and the band lo..hi: its maps and the base's wins
there. --seeds prints only the band's seeds, comma separated, for dev.sh.

    python lab/fable/band.py --against VARIANT RUN...   the variant's games against the base's on the same seeds
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import endgame  # noqa: E402


def base_games(runs):
    games = {}
    for run in runs:
        for g in endgame.load(run):
            games[g['seed']] = g
    return games


def housed_at(g, minute):
    f = g['foes'].get(minute * 60)
    return None if f is None else f['housed']


def main(args):
    opts = {'--minute': 30, '--lo': 6, '--hi': 9}
    for k in list(opts):
        if k in args:
            i = args.index(k)
            opts[k] = int(args[i + 1])
            del args[i:i + 2]
    against = None
    if '--against' in args:
        i = args.index('--against')
        against = args[i + 1]
        del args[i:i + 2]
    only_seeds = '--seeds' in args
    runs = [a for a in args if not a.startswith('--')]
    games = base_games(runs)
    minute, lo, hi = opts['--minute'], opts['--lo'], opts['--hi']
    band = sorted(s for s, g in games.items() if housed_at(g, minute) is not None and lo <= housed_at(g, minute) <= hi)
    if only_seeds:
        print(','.join(str(s) for s in band))
        return
    if against:
        var = {g['seed']: g for g in endgame.load(against)}
        both = sorted(s for s in var if s in games)
        up = [s for s in both if var[s]['won'] and not games[s]['won']]
        down = [s for s in both if not var[s]['won'] and games[s]['won']]
        bw = sum(games[s]['won'] for s in both)
        vw = sum(var[s]['won'] for s in both)
        print(f'{against}: {len(both)} maps, base {bw} won, variant {vw} won, +{len(up)} -{len(down)}')
        for m in (30, 40, 50, 60, 80):
            rows = []
            for s in both:
                a, b = endgame.at(games[s], m), endgame.at(var[s], m)
                if a[0] is None or b[0] is None or a[1] is None or b[1] is None:
                    continue
                pa, pb = games[s]['ours'].get(m * 60 - 600), var[s]['ours'].get(m * 60 - 600)
                if pa is None or pb is None:
                    continue
                rows.append((a[0]['war'], b[0]['war'], a[1]['housed'], b[1]['housed'], a[1]['war'], b[1]['war'],
                             (a[0]['harvestedIron'] - pa['harvestedIron']) / 10.0,
                             (b[0]['harvestedIron'] - pb['harvestedIron']) / 10.0,
                             a[0]['towers'], b[0]['towers'], a[0]['quarters'], b[0]['quarters']))
            if rows:
                mean = [sum(r[i] for r in rows) / len(rows) for i in range(12)]
                print(f'  minute {m:3d}, {len(rows):3d} games running in both | our warriors {mean[0]:5.1f} -> '
                      f'{mean[1]:5.1f} | copies housed {mean[2]:4.2f} -> {mean[3]:4.2f} | their warriors '
                      f'{mean[4]:5.0f} -> {mean[5]:5.0f} | iron a minute {mean[6]:4.1f} -> {mean[7]:4.1f} | towers '
                      f'{mean[8]:4.1f} -> {mean[9]:4.1f} | quarters {mean[10]:3.1f} -> {mean[11]:3.1f}')
        print('  to a win:', ' '.join(str(s) for s in up))
        print('  to a loss:', ' '.join(str(s) for s in down))
        return
    print(f'{len(games)} base games, {sum(g["won"] for g in games.values())} won')
    print(f'copies housed at minute {minute}: games, won')
    rows = {}
    for g in games.values():
        h = housed_at(g, minute)
        k = 'over' if h is None else h
        r = rows.setdefault(k, [0, 0])
        r[0] += 1
        r[1] += g['won']
    for k in sorted(rows, key=lambda k: (isinstance(k, str), k)):
        print(f'  {k!s:>5} {rows[k][0]:4d} {rows[k][1]:4d}')
    won = sum(games[s]['won'] for s in band)
    print(f'band {lo}..{hi}: {len(band)} maps, {won} won ({100.0 * won / max(1, len(band)):.1f} %)')


if __name__ == '__main__':
    main(sys.argv[1:])
