#!/usr/bin/env python3
"""A spec on a hand-picked set of ladder games: python lab/fable/subset.py SET.json PREFIX "SPEC" [--report]

SET.json (in aisim/) maps "N,seat" to the seeds to play, and SET-info.json "N,seat,seed" to [a number the set was
picked by, the result before, the minutes before] (lab/fable/near.py writes both for the games with a copy starting
near us). Plays `SPEC vs hard*N` from that seat on those seeds as PREFIX-vsN-sS through lab/fable/dev.sh, every batch
at once, and prints each game before and after, and the wins by the number's bands (--bands 0,20,30,40).
"""
import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('set')
    ap.add_argument('prefix')
    ap.add_argument('spec')
    ap.add_argument('--report', action='store_true')
    ap.add_argument('--bands', default='0,20,30,40')
    ap.add_argument('--quiet', action='store_true')
    args = ap.parse_args()
    games = json.load(open(os.path.join(ROOT, 'aisim', args.set + '.json')))
    info = json.load(open(os.path.join(ROOT, 'aisim', args.set + '-info.json')))
    procs = []
    for key, seeds in games.items():
        n, seat = key.split(',')
        run = f'{args.prefix}-vs{n}-s{seat}'
        if args.report or os.path.isdir(os.path.join(ROOT, 'aisim', 'runs', run)):
            continue
        out = open(os.path.join(ROOT, 'aisim', run + '.log'), 'w', encoding='utf-8')
        cmd = ['bash', 'lab/fable/dev.sh', run, f'{args.spec} vs hard*{n}', ','.join(map(str, seeds))]
        procs.append(subprocess.Popen(cmd, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT,
                                      env=dict(os.environ, SIDE=seat)))
    for p in procs:
        p.wait()
    rows = []
    for key, (num, res, end) in info.items():
        n, seat, seed = key.split(',')
        path = os.path.join(ROOT, 'aisim', 'runs', f'{args.prefix}-vs{n}-s{seat}', 'results.jsonl')
        if not os.path.exists(path):
            continue
        for line in open(path, encoding='utf-8'):
            r = json.loads(line)
            if str(r['seed']) == seed:
                rows.append((num, int(n), int(seat), seed, res, end, r['result'], r['t'] / 60.0,
                             r['teams'][0]['kills']))
    rows.sort()
    if not args.quiet:
        for x in rows:
            print('  %5.0f  N=%d seat %2d s%s  %-4s %6.1f min -> %-4s %6.1f min  kills %d' % x)
    edges = [float(b) for b in args.bands.split(',')]
    for lo, hi in zip(edges[:-1], edges[1:]):
        sel = [x for x in rows if lo <= x[0] < hi]
        if sel:
            print(f'{lo:.0f}..{hi:.0f}: {len(sel)} games, won {sum(x[4] == "win" for x in sel)} -> '
                  f'{sum(x[6] == "win" for x in sel)}; mean end {sum(x[5] for x in sel) / len(sel):.1f} -> '
                  f'{sum(x[7] for x in sel) / len(sel):.1f} min; out before minute 10: '
                  f'{sum(x[5] < 10 for x in sel)} -> {sum(x[7] < 10 for x in sel)}')


if __name__ == '__main__':
    sys.exit(main())
