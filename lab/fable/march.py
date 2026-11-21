#!/usr/bin/env python3
"""What the attacking army does with its time, from runs made with --logs: python lab/fable/march.py RUN...

Reads MarchTrace's "MARCH s" lines (a second of the army while an attack is out) and "MARCH target" lines (its target
changed), per hour of game:

- the army's warrior-seconds by the branch of Military.attack that gave the round's orders (phase), with what the
  units did in them (throwing, closing on a hunt's target, walking, blocked, idle, stunned);
- the march: how fast the army's centre moves and how fast the walking distance to the target falls, by the army's
  size; the seconds by the centre's speed, with what the units did in them; the length of the column;
- the engage phases by how many enemy warriors stand within 14 cells of any unit, with the share of the army that
  has one within 14 cells;
- the legs: from a target's choice to the next, by why it was chosen (launch, the last one fell, switched), whether
  it belongs to the same copy as the last, and the walking distance to it then: how many, how long, and the seconds
  of each phase in them.
"""
import collections
import glob
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
SEC = re.compile(r'^\s*([\d.]+) s0 MARCH\s+s phase (\w+) n (\d+) c (\d+),(\d+) tgt (\w+) line (-?\d+) pivot (-?\d+) '
                 r'front (-?\d+) tail (-?\d+) far (\d+) \| throw (\d+) close (\d+) walk (\d+) blocked (\d+) idle (\d+) '
                 r'stun (\d+) other (\d+) \| hunt w (\d+) p (\d+) t (\d+) b (\d+) \| near ew (\d+) et (\d+) eb (\d+) '
                 r'ep (\d+) about (\d+) units (\d+)(?: step (-?[\d.]+) gain (-?[\d.]+))?')
TGT = re.compile(r'^\s*([\d.]+) s0 MARCH\s+target (\w+) of (.+?) at (\d+),(\d+) why (\w+) walk (-?\d+) line (-?\d+) '
                 r'army (\d+) at (\d+),(\d+)')
OVER = re.compile(r'^\s*([\d.]+) s0 MARCH\s+over')
PICK = re.compile(r'^\s*([\d.]+) s0 MARCH\s+pick from (\d+),(\d+) army (\d+) of (\d+): (.*)$')
CAND = re.compile(r'([\d.]+) (.+?) (\w+) at (\d+),(\d+): d (\d+) \+ pri (\d+) \+ def (\d+)')
ACTS = ('throw', 'close', 'walk', 'blocked', 'idle', 'stun', 'other')


def bucket(v, edges, labels):
    for e, label in zip(edges, labels):
        if v <= e:
            return label
    return labels[-1]


def quart(vs, fmt='{:.2f}'):
    vs = sorted(vs)
    if not vs:
        return '-'
    return ' / '.join(fmt.format(vs[i]) for i in (len(vs) // 4, len(vs) // 2, 3 * len(vs) // 4))


def game_end(path):
    end = 0.0
    for line in open(path, encoding='utf-8'):
        if '"end"' in line:
            end = json.loads(line)['t']
    return end


def main(runs):
    for run in runs:
        hours = 0.0
        games = 0
        ws = collections.Counter()                         # phase -> warrior-seconds
        secs = collections.Counter()                       # phase -> seconds
        acts = collections.defaultdict(collections.Counter)  # phase -> act -> warrior-seconds
        speed = collections.defaultdict(list)              # army bucket -> centre cells a second, in march
        gain = collections.defaultdict(list)               # army bucket -> metres of pivot distance a second
        slow = collections.defaultdict(collections.Counter)  # speed bucket -> act -> warrior-seconds, and 'secs', 'n'
        column = collections.defaultdict(list)
        engage = collections.defaultdict(collections.Counter)
        where = collections.defaultdict(collections.Counter)  # phase -> line bucket -> seconds
        steps = collections.defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])  # phase -> step x n, n, gain x n, n
        legs = []
        picks = []
        for log in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-ai-s0.log'))):
            games += 1
            hours += game_end(log[:-len('-ai-s0.log')] + '.jsonl') / 3600
            prev = None
            leg = None
            last_owner = None
            for line in open(log, encoding='utf-8', errors='replace'):
                if ' MARCH ' not in line:
                    continue
                m = SEC.match(line)
                if m:
                    t = float(m.group(1))
                    phase = m.group(2)
                    n = int(m.group(3))
                    x, y = int(m.group(4)), int(m.group(5))
                    pivot, front, tail = int(m.group(8)), int(m.group(9)), int(m.group(10))
                    a = [int(m.group(i)) for i in range(12, 19)]
                    ew, about, units = int(m.group(23)), int(m.group(27)), int(m.group(28))
                    ws[phase] += n
                    secs[phase] += 1
                    line_m = int(m.group(7))
                    where[phase][bucket(line_m, (-1, 40, 120), ('no target', '<=40', '41-120', '121+'))] += 1
                    if m.group(29) is not None:
                        st, gn = float(m.group(29)), float(m.group(30))
                        walkers = a[2]
                        if st >= 0 and walkers:
                            steps[phase][0] += st * walkers
                            steps[phase][1] += walkers
                        if gn > -90 and walkers:
                            steps[phase][2] += gn * walkers
                            steps[phase][3] += walkers
                    for name, v in zip(ACTS, a):
                        acts[phase][name] += v
                    if leg is not None:
                        leg['secs'] += 1
                        leg['phase'][phase.split('_')[0]] += 1
                        leg['ws'] += n
                    if phase in ('march', 'column'):
                        b = bucket(n, (19, 39, 79), ('<20', '20-39', '40-79', '80+'))
                        if front >= 0 and tail >= 0:
                            column[b].append(tail - front)
                        if prev is not None and prev[1] in ('march', 'column') and 0.8 <= t - prev[0] <= 1.3:
                            v = ((x - prev[2]) ** 2 + (y - prev[3]) ** 2) ** .5 / (t - prev[0])
                            speed[b].append(v)
                            if pivot >= 0 and prev[4] >= 0 and abs(pivot - prev[4]) < 40:
                                gain[b].append((prev[4] - pivot) / (t - prev[0]))
                            sb = bucket(v, (.5, 1.0, 1.5), ('under 0.5', '0.5-1.0', '1.0-1.5', '1.5 and more'))
                            slow[sb]['secs'] += 1
                            slow[sb]['n'] += n
                            for name, val in zip(ACTS, a):
                                slow[sb][name] += val
                            slow[sb]['about'] += about
                            slow[sb]['far'] += int(m.group(11))
                    elif phase.startswith('engage'):
                        k = (phase, bucket(about, (0, 2, 9), ('0', '1-2', '3-9', '10+')))
                        engage[k]['secs'] += 1
                        engage[k]['n'] += n
                        engage[k]['units'] += units
                        for name, val in zip(ACTS, a):
                            engage[k][name] += val
                    prev = (t, phase, x, y, pivot)
                    continue
                m = TGT.match(line)
                if m:
                    if leg is not None:
                        leg['end'] = m.group(6)
                        legs.append(leg)
                    owner = m.group(3)
                    leg = dict(kind=m.group(2), why=m.group(6), walk=int(m.group(7)), line=int(m.group(8)),
                               army=int(m.group(9)), same=owner == last_owner, secs=0, ws=0,
                               phase=collections.Counter(), end='open')
                    last_owner = owner
                    continue
                m = PICK.match(line)
                if m:
                    cands = [CAND.match(part.strip()) for part in m.group(6).split('; ')]
                    cands = [(float(k.group(1)), k.group(2), k.group(3), int(k.group(6)), int(k.group(7)),
                              int(k.group(8))) for k in cands if k]
                    if cands:
                        picks.append(cands)
                    continue
                if OVER.match(line):
                    if leg is not None:
                        leg['end'] = 'over'
                        legs.append(leg)
                        leg = None
                    last_owner = None
                    prev = None
            if leg is not None:
                legs.append(leg)
        h = max(hours, 1e-9)
        total = sum(ws.values())
        print(f'== {run}: {games} logged games, {hours:.1f} h of game; an hour of game')
        print(f'  the attacking army: {total / h:.0f} warrior-seconds ({total / h / 3600:.1f} warriors), out '
              f'{sum(secs.values()) / h:.0f} s')
        print('  by phase: seconds, warrior-seconds, share, and of them ' + ' / '.join(ACTS) + ' (%)')
        for p, v in ws.most_common():
            row = ' '.join(f'{100 * acts[p][a] / max(1, v):5.1f}' for a in ACTS)
            print(f'    {p:<18}{secs[p] / h:7.0f}{v / h:9.0f}{100 * v / total:6.1f} %   {row}')
        print('  where the army is by phase: seconds at <=40 / 41-120 / 121+ m (straight line) from its target; and of '
              'its walking units the mean cells a second and the mean metres a second of walking distance gained')
        for p, v in ws.most_common():
            w = where[p]
            st = steps[p]
            print(f'    {p:<18}{w["<=40"] / h:7.0f}{w["41-120"] / h:7.0f}{w["121+"] / h:7.0f}'
                  f'{st[0] / st[1] if st[1] else float("nan"):9.2f}{st[2] / st[3] if st[3] else float("nan"):8.2f}')
        print('  the march: the centre\'s cells a second / the pivot\'s metres a second / the column in metres (quartiles), '
              'by the army')
        for b in ('<20', '20-39', '40-79', '80+'):
            if speed[b]:
                print(f'    {b:<6} {quart(speed[b])}   {quart(gain[b])}   {quart(column[b], "{:.0f}")}   ({len(speed[b]) / h:.0f} s)')
        print('  the march by the centre\'s speed: seconds, the army, far from the centre, enemy warriors about, and '
              + ' / '.join(ACTS) + ' (%)')
        for sb in ('under 0.5', '0.5-1.0', '1.0-1.5', '1.5 and more'):
            c = slow[sb]
            if not c['secs']:
                continue
            row = ' '.join(f'{100 * c[a] / max(1, c["n"]):5.1f}' for a in ACTS)
            print(f'    {sb:<14}{c["secs"] / h:7.0f}{c["n"] / c["secs"]:7.1f}{100 * c["far"] / max(1, c["n"]):6.1f} %'
                  f'{c["about"] / c["secs"]:7.1f}   {row}')
        print('  engaging, by the enemy warriors within 14 cells of any unit: seconds, the army, the share of it with one '
              'within 14 cells, and ' + ' / '.join(ACTS) + ' (%)')
        for k, c in sorted(engage.items(), key=lambda kv: -kv[1]['n']):
            row = ' '.join(f'{100 * c[a] / max(1, c["n"]):5.1f}' for a in ACTS)
            print(f'    {k[0]:<18}{k[1]:<5}{c["secs"] / h:7.0f}{c["n"] / c["secs"]:7.1f}{100 * c["units"] / max(1, c["n"]):6.1f} %'
                  f'   {row}')
        if picks:
            # the first is the one taken (lowest score); the nearest of the best eight is the alternative
            n = len(picks)
            longer = [p[0][3] - min(k[3] for k in p) for p in picks]
            over = [p for p in picks if p[0][3] - min(k[3] for k in p) >= 60]
            print(f'  picks after a target fell: {n / h:.1f} an hour; the one taken is {sum(p[0][3] for p in picks) / n:.0f} m '
                  f'off (straight line) with a defense term of {sum(p[0][5] for p in picks) / n:.0f} m and a priority of '
                  f'{sum(p[0][4] for p in picks) / n:.0f} m; the nearest of its best eight is '
                  f'{sum(min(k[3] for k in p) for p in picks) / n:.0f} m off')
            print(f'    taken 60 m and more past a nearer one: {len(over) / h:.1f} an hour, by {sum(p[0][3] - min(k[3] for k in p) for p in over) / max(1, len(over)):.0f} m; '
                  f'the nearer one has a defense term of {sum(min(p, key=lambda k: k[3])[5] for p in over) / max(1, len(over)):.0f} m '
                  f'and a priority of {sum(min(p, key=lambda k: k[3])[4] for p in over) / max(1, len(over)):.0f} m, the one taken '
                  f'{sum(p[0][5] for p in over) / max(1, len(over)):.0f} and {sum(p[0][4] for p in over) / max(1, len(over)):.0f}')
            kinds = collections.Counter(p[0][2] for p in picks)
            print('    taken: ' + ', '.join(f'{k} {v / h:.1f}' for k, v in kinds.most_common()))
        print(f'  legs {len(legs) / h:.1f} an hour: why chosen, same copy as the last, walking distance then: legs, '
              'seconds a leg, seconds an hour, of them march / engage / other, and how they end')
        groups = collections.defaultdict(list)
        for l in legs:
            groups[(l['why'], 'same copy' if l['same'] else 'another',
                    bucket(l['walk'], (-1, 60, 150, 300), ('no way', '<=60', '61-150', '151-300', '300+')))].append(l)
        for k, ls in sorted(groups.items(), key=lambda kv: -sum(l['secs'] for l in kv[1])):
            n = len(ls)
            s = sum(l['secs'] for l in ls)
            mar = sum(l['phase']['march'] + l['phase']['column'] for l in ls)
            eng = sum(l['phase']['engage'] for l in ls)
            ends = collections.Counter(l['end'] for l in ls)
            print(f'    {k[0]:<9}{k[1]:<10}{k[2]:<8}{n / h:6.1f}{s / max(1, n):7.0f}{s / h:8.0f}   {mar / h:6.0f} / '
                  f'{eng / h:6.0f} / {(s - mar - eng) / h:6.0f}   ' + ', '.join(f'{e} {c}' for e, c in ends.most_common()))


if __name__ == '__main__':
    main(sys.argv[1:])
