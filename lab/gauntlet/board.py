#!/usr/bin/env python3
"""Scoreboard of many arms against one base run: python lab/gauntlet/board.py BASE PREFIX [--median]

Pairs BASE with every finished run (one with a summary.txt) whose name starts with PREFIX, game by game
(winproxy.py --pair), and prints one line per arm: games in common, games played identically (same checksum),
wins base -> arm, and the difference and z of elim, surv60, alive40, towers20 and wp; best (wp z + surv60 z) first.

A shared base carries its own draw into every comparison (NOTES.md, "Method: one base shared by many arms"): any
change re-rolls the games it touches, so over a sweep the arms' differences centre on the base's luck rather than on
zero. The head line gives the median difference of the arms that re-roll at least half the games, and --median
prints each arm against that median arm instead: (difference - median x the arm's share of changed games) and its
z, taking the arm's paired SE (the median of many arms is nearly a constant). Read a sweep that way before trusting a
first-block z. Games pair by key, player count and map.

The pairings are cached in aisim/board_cache.json (git-ignored; delete it to recompute).
"""
import json
import os
import statistics
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
ROOT = os.path.join(REPO, 'aisim', 'runs')
CACHE = os.path.join(REPO, 'aisim', 'board_cache.json')
METRICS = ('elim', 'surv60', 'alive40', 'towers20', 'wp')
SHOWN = {'elim': '%+.3f', 'surv60': '%+.2f', 'alive40': '%+.3f', 'towers20': '%+.2f', 'wp': '%+.4f'}


def rows(run):
    try:
        with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
            return {x['key']: x for x in map(json.loads, f)}
    except FileNotFoundError:
        return {}


def score(base, arm):
    """The pairing of one arm with the base: n, identical games, wins, and {metric: [diff, se]}."""
    ra, rb = rows(base), rows(arm)
    # the same game: same key, same number of players and the same map (a key alone matches across N)
    keys = sorted(k for k in set(ra) & set(rb)
                  if ra[k].get('slots') == rb[k].get('slots') and ra[k].get('map') == rb[k].get('map'))
    d = {'n': len(keys),
         'same': sum(ra[k].get('checksum') == rb[k].get('checksum') and ra[k].get('end') == rb[k].get('end')
                     for k in keys),
         'W': '%d->%d' % (sum(ra[k].get('result') == 'win' for k in keys),
                          sum(rb[k].get('result') == 'win' for k in keys))}
    out = subprocess.run([sys.executable, os.path.join(HERE, 'winproxy.py'), '--pair', base, arm],
                         capture_output=True, text=True, cwd=REPO).stdout.splitlines()
    for line in out:
        f = line.split()
        # metric, base mean, arm mean, diff, se, z
        if len(f) >= 6 and f[0] in METRICS:
            try:
                d[f[0]] = [float(f[3]), float(f[4])]
            except ValueError:
                pass
    return d


def z(diff, se):
    return diff / se if se and se > 0 else 0.0


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    vs_median = '--median' in sys.argv
    if len(args) != 2:
        sys.exit(__doc__)
    base, prefix = args
    cache = {}
    if os.path.exists(CACHE):
        with open(CACHE, encoding='utf-8') as f:
            cache = json.load(f)
    names = sorted(n for n in os.listdir(ROOT) if n.startswith(prefix) and n != base
                   and os.path.exists(os.path.join(ROOT, n, 'summary.txt')))
    res = []
    base_done = os.path.exists(os.path.join(ROOT, base, 'summary.txt'))
    for n in names:
        key = base + '|' + n
        if key not in cache or not base_done:
            cache[key] = score(base, n)
            if base_done:  # a base still running would freeze a partial pairing in the cache
                with open(CACHE, 'w', encoding='utf-8') as f:
                    json.dump(cache, f)
        res.append((n, cache[key]))
    apart = [n for n, d in res if not d['n']]
    res = [(n, d) for n, d in res if d['n']]
    if apart:
        print('no game in common with %s: %s' % (base, ' '.join(apart)))
    # the median over arms that re-roll most games: an arm that changes only a few carries little of the base's luck
    changing = [d for _, d in res if d['same'] <= d['n'] // 2]
    median = {m: statistics.median(d[m][0] for d in changing if m in d) if any(m in d for d in changing) else 0.0
              for m in METRICS}
    print('%s: %d arms, %d change play; median difference %s' % (
        base, len(res), len(changing), ', '.join(m + ' ' + SHOWN[m] % median[m] for m in METRICS)))
    if vs_median:
        print('(each arm against the median arm: difference - median, z on the arm\'s paired SE)')

    def shift(d, m):
        v = d.get(m)
        if not v:
            return None
        # an arm that re-rolls k of n games carries about k/n of the base's draw
        changed = (d['n'] - d['same']) / d['n'] if d['n'] else 0.0
        diff = v[0] - (median[m] * changed if vs_median else 0.0)
        return diff, z(diff, v[1])

    def rank(item):
        d = item[1]
        return -sum(shift(d, m)[1] for m in ('wp', 'surv60') if shift(d, m))

    for n, d in sorted(res, key=rank):
        cells = []
        for m in METRICS:
            s = shift(d, m)
            cells.append(m + ' ' + (SHOWN[m] % s[0] + ' z%+.1f' % s[1] if s else '-'))
        print('%-28s n=%3d same=%3d W %-7s %s' % (n, d['n'], d['same'], d['W'], ' | '.join(cells)))


if __name__ == '__main__':
    main()
