#!/usr/bin/env python3
"""Two AIs paired over the maps both played, each side made of several runs: python lab/fable/highn.py BASE VAR

BASE and VAR are comma-separated run names (a run of 100 maps and one of 900 more, say). Prints the wins of each with
Wilson intervals, the 2x2 table of the maps (both won, only one, neither) with the sign test on the maps that differ,
and paired means of the minutes survived (uncapped: a won or drawn game counts as its length) and of the enemy copies
put out by minutes 15, 20 and 30 and by the end of the game. Per-game numbers come from stats.py's cache.
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stats  # noqa: E402


def side(names):
    games = {}
    for run in names.split(','):
        for key, g in stats.load(run).items():
            games[key] = g
    return games


def paired(diffs):
    n = len(diffs)
    m = sum(diffs) / n
    sd = math.sqrt(sum((d - m) ** 2 for d in diffs) / max(1, n - 1))
    return m, (m / (sd / math.sqrt(n)) if sd > 0 else 0.0)


def main(base_names, var_names):
    a, b = side(base_names), side(var_names)
    keys = sorted(set(a) & set(b))
    n = len(keys)
    won = lambda g: g['result'] == 'win'  # noqa: E731
    wa = sum(won(a[k]) for k in keys)
    wb = sum(won(b[k]) for k in keys)
    both = sum(won(a[k]) and won(b[k]) for k in keys)
    only_a = sum(won(a[k]) and not won(b[k]) for k in keys)
    only_b = sum(won(b[k]) and not won(a[k]) for k in keys)
    draws_a = sum(a[k]['result'] == 'draw' for k in keys)
    draws_b = sum(b[k]['result'] == 'draw' for k in keys)
    lo_a, hi_a = stats.wilson(wa, n)
    lo_b, hi_b = stats.wilson(wb, n)
    z = (only_b - only_a) / math.sqrt(only_a + only_b) if only_a + only_b else 0.0
    print(f'{n} maps played by both')
    print(f'  {base_names}: {wa} won = {100.0 * wa / n:.1f} % [{100 * lo_a:.1f}, {100 * hi_a:.1f}], {draws_a} draws')
    print(f'  {var_names}: {wb} won = {100.0 * wb / n:.1f} % [{100 * lo_b:.1f}, {100 * hi_b:.1f}], {draws_b} draws')
    print(f'  maps won by both {both}, only by the second {only_b}, only by the first {only_a}, by neither '
          f'{n - both - only_a - only_b} (sign test z {z:+.1f})')

    # the batches stop when team A is out, so a game's length is how long we lasted (a won game: until it was won)
    for label, f in (('minutes survived', lambda g: g['end']),
                     ('copies out by minute 15', lambda g: g['outs'][15]),
                     ('copies out by minute 20', lambda g: g['outs'][20]),
                     ('copies out by minute 30', lambda g: g['outs'][30]),
                     ('copies out by the end', lambda g: len(g['out_times']))):
        xa = [f(a[k]) for k in keys]
        xb = [f(b[k]) for k in keys]
        m, zz = paired([y - x for x, y in zip(xa, xb)])
        print(f'  {label:24s} {sum(xa) / n:6.2f} -> {sum(xb) / n:6.2f}  ({m:+.2f}, z {zz:+.1f})')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
