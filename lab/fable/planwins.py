#!/usr/bin/env python3
"""Wins of opening-plan variants over a set of maps, reusing games: python lab/fable/planwins.py PAIR... -- PLAN...

A PAIR is PLANRUN=RUN[+RUN...]: a logged run with the plan of every map (a minute is enough) and full runs played
with the same parameters on some or all of those maps. A game depends on the parameters of the armory search only
through the armory's site, so a map's result under a plan is the result of any game on that map with that site.
For each PLAN (a logged plan run) the wins over the maps whose site has a game, how many maps have none, and the
games that differ from the first PAIR's plan (won there / won here).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import openwin  # noqa: E402
import oremap  # noqa: E402
import plans  # noqa: E402


def main(args):
    cut = args.index('--')
    pairs = [a.split('=') for a in args[:cut]]
    targets = args[cut + 1:]
    known = {}
    site_of = {}
    for plan, runs in pairs:
        site_of[plan] = plans.summary(oremap.ore(plan))
        for run in runs.split('+'):
            for r in openwin.load(run):
                s = site_of[plan].get(r['seed'])
                if s is not None:
                    known[(r['seed'], s[0], s[1])] = r['won']
    base = site_of[pairs[0][0]]
    seeds = sorted(base)
    for t in targets:
        if t not in site_of:
            site_of[t] = plans.summary(oremap.ore(t))
    print(f'{len(seeds)} maps')
    print(f'{"plan":<16} with a game  won   share | sites other than the first plan\'s: maps, won before -> now, '
          'changed to a win / to a loss')
    for t in targets:
        d = site_of[t]
        have = [k for k in seeds if k in d and (k, d[k][0], d[k][1]) in known]
        won = sum(known[(k, d[k][0], d[k][1])] for k in have)
        moved = [k for k in have if d[k][:2] != base[k][:2] and (k, base[k][0], base[k][1]) in known]
        before = sum(known[(k, base[k][0], base[k][1])] for k in moved)
        now = sum(known[(k, d[k][0], d[k][1])] for k in moved)
        up = sum(1 for k in moved if known[(k, d[k][0], d[k][1])] > known[(k, base[k][0], base[k][1])])
        down = sum(1 for k in moved if known[(k, d[k][0], d[k][1])] < known[(k, base[k][0], base[k][1])])
        print(f'{t:<16} {len(have):11d} {won:4d} {100 * won / max(1, len(have)):6.1f} % | {len(moved):4d} maps, '
              f'{before} -> {now}, +{up} -{down}')


if __name__ == '__main__':
    main(sys.argv[1:])
