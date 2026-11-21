#!/usr/bin/env python3
"""Where the opening's peon time goes: python lab/fable/opentrace.py [--until S] [--maps] RUN...

Reads the "OPEN s" lines of logged games (OpeningTrace, a line a second for ten minutes) and the "placed" and
"completed" lines. Prints, over all games of the runs: the mean units and where they are by half minute (inside the
quarters and the armory, and outside by what they do); the peon-seconds of the first S seconds (300) by what they
went on; and every opening building in the order it stood: when it was placed, when its first builder came, when it
stood, its builders and what they did with their time, how far from it they chopped and the trees about it.
"""
import glob
import os
import re
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
ACTS = ['strike', 'shep', 'place', 'seek', 'chop', 'carry', 'hammer', 'bstand', 'bblock', 'enter', 'move', 'idle',
        'gt', 'gi', 'gr', 'gc', 'other']
LINE = re.compile(r'^\s*([\d.]+) s0 OPEN  s pop (\d+) q(.*?) a(.*?) w (\d+) \|(.*?)\|(.*)$')
PLACED = re.compile(r'^\s*([\d.]+) s0 AI    placed (\w+)#(\d+) ')
DONE = re.compile(r'^\s*([\d.]+) s0 AI    completed (\w+)#(\d+) ')


def load(path):
    rows = []
    placed = {}
    done = {}
    for line in open(path, encoding='utf-8', errors='replace'):
        if ' OPEN ' in line:
            m = LINE.match(line)
            if not m:
                continue
            t = float(m.group(1))
            q = [tuple(int(v) for v in x.split('/')) for x in m.group(3).split()]
            a = [int(x) for x in m.group(4).split()]
            w = m.group(6).split()
            acts = {w[i]: int(w[i + 1]) for i in range(0, len(w), 2)}
            sites = {}
            for s in m.group(7).split():
                f = s.split(':')
                sites[int(f[0])] = (f[1], [int(v) for v in f[2:]])
            rows.append((t, int(m.group(2)), q, a, int(m.group(5)), acts, sites))
        elif ' AI    placed ' in line:
            m = PLACED.match(line)
            if m and int(m.group(3)) not in placed:
                placed[int(m.group(3))] = (float(m.group(1)), m.group(2))
        elif ' AI    completed ' in line:
            m = DONE.match(line)
            if m and int(m.group(3)) not in done:
                done[int(m.group(3))] = (float(m.group(1)), m.group(2))
    return rows, placed, done


def mean(xs):
    xs = list(xs)
    return statistics.mean(xs) if xs else float('nan')


def main():
    args = sys.argv[1:]
    until = 300
    per_map = False
    while args and args[0].startswith('--'):
        if args[0] == '--until':
            until = int(args[1])
            args = args[2:]
        elif args[0] == '--maps':
            per_map = True
            args = args[1:]
        else:
            raise SystemExit('unknown option ' + args[0])
    games = []
    for run in args:
        for path in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-0-ai-s0.log'))):
            g = load(path)
            if g[0]:
                games.append((os.path.basename(path)[1:6], g))
    print('%d games' % len(games))

    # 1. by half minute
    cols = ['pop', 'inQ', 'inA', 'war'] + ACTS
    print('\nunits by half minute (means over the games)')
    print('   t ' + ' '.join('%6s' % c for c in cols))
    for t0 in range(0, 600, 30):
        acc = {c: [] for c in cols}
        for _, (rows, _, _) in games:
            sel = [r for r in rows if t0 <= r[0] < t0 + 30]
            if not sel:
                continue
            acc['pop'].append(mean(r[1] for r in sel))
            acc['inQ'].append(mean(sum(x[0] for x in r[2]) for r in sel))
            acc['inA'].append(mean(sum(r[3]) for r in sel))
            acc['war'].append(mean(r[4] for r in sel))
            for a in ACTS:
                acc[a].append(mean(r[5].get(a, 0) for r in sel))
        print('%4d ' % t0 + ' '.join('%6.1f' % mean(acc[c]) for c in cols))

    # 2. the ledger
    print('\npeon-seconds of the first %d s, a game' % until)
    tot = {c: 0.0 for c in ['inQ', 'inA'] + ACTS}
    for _, (rows, _, _) in games:
        for r in rows:
            if r[0] >= until:
                break
            tot['inQ'] += sum(x[0] for x in r[2])
            tot['inA'] += sum(r[3])
            for a in ACTS:
                tot[a] += r[5].get(a, 0)
    n = len(games)
    whole = sum(tot.values())
    for c in sorted(tot, key=lambda c: -tot[c]):
        if tot[c] > 0:
            print('  %-7s %7.0f  %5.1f %%' % (c, tot[c] / n, 100 * tot[c] / whole))
    print('  %-7s %7.0f' % ('all', whole / n))

    # 3. the buildings, in the order they stood
    print('\nopening buildings in the order they stood (quarters and the first armory)')
    print('  k kind      n | placed first-builder stood | wait build | builders: mean max | seek chop carry hammer '
          'other (%) | cells trees8 at placing, at the end | peon-s')
    order = {}
    for seed, (rows, placed, done) in games:
        ids = sorted((d for d in done if done[d][1] in ('quarters', 'armory') and done[d][0] < 600),
                     key=lambda d: done[d][0])
        seen_armory = False
        k = 0
        for d in ids:
            kind = done[d][1]
            if kind == 'armory':
                if seen_armory:
                    continue
                seen_armory = True
            first = None
            acts = [0, 0, 0, 0, 0]
            builders = []
            cells = []
            trees = []
            for r in rows:
                s = r[6].get(d)
                if s is None or s[1][0] < 0:
                    continue
                v = s[1]
                trees.append(v[8])
                if v[1] > 0:
                    if first is None:
                        first = r[0]
                    builders.append(v[1])
                    for i in range(5):
                        acts[i] += v[2 + i]
                    if v[7] >= 0:
                        cells.append(v[7])
            p = placed.get(d, (float('nan'),))[0]
            key = (k, kind)
            order.setdefault(key, []).append((p, first if first is not None else float('nan'), done[d][0], builders,
                                              acts, cells, trees, seed))
            k += 1
    for key in sorted(order):
        xs = order[key]
        if len(xs) < max(3, len(games) // 8):
            continue
        tot_acts = [sum(x[4][i] for x in xs) for i in range(5)]
        s = max(1, sum(tot_acts))
        print('  %d %-8s %3d | %5.0f %5.0f %5.0f | %4.0f %4.0f | %5.1f %5.1f | %4.0f %4.0f %4.0f %4.0f %4.0f | '
              '%4.1f %4.1f %4.1f | %5.0f' % (
                  key[0], key[1], len(xs), mean(x[0] for x in xs), mean(x[1] for x in xs), mean(x[2] for x in xs),
                  mean(x[1] - x[0] for x in xs), mean(x[2] - x[1] for x in xs),
                  mean(mean(x[3]) for x in xs if x[3]), mean(max(x[3]) for x in xs if x[3]),
                  100 * tot_acts[0] / s, 100 * tot_acts[1] / s, 100 * tot_acts[2] / s, 100 * tot_acts[3] / s,
                  100 * tot_acts[4] / s, mean(mean(x[5]) for x in xs if x[5]),
                  mean(x[6][0] for x in xs if x[6]), mean(x[6][-1] for x in xs if x[6]),
                  mean(sum(x[4]) for x in xs)))
    if per_map:
        print('\nper game: seed, then for each building in order: kind placed/first/stood')
        for seed, (rows, placed, done) in games:
            ids = sorted((d for d in done if done[d][1] in ('quarters', 'armory') and done[d][0] < 600),
                         key=lambda d: done[d][0])
            pop = {int(r[0] // 60): r[1] for r in rows if int(r[0]) % 60 == 0}
            print(seed, ' '.join('%s%d:%.0f/%.0f' % (done[d][1][0], d, placed.get(d, (0,))[0], done[d][0])
                                 for d in ids[:8]), '| pop', pop.get(3), pop.get(5), pop.get(8))


if __name__ == '__main__':
    main()
