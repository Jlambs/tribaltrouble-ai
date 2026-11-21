#!/usr/bin/env python3
"""What the home army does and where it dies, from runs made with --logs: python lab/fable/home.py RUN...

Reads HomeTrace's "HOME s" lines (a second: the threat as the military sees it, the warriors in role army by
why/zone/act, the enemy warriors near our buildings by zone/kind) and Military's "lost army ..." lines (with the
order the warrior had, its zone and the threat then). Per hour of game:
  - the home army's warrior-seconds by why (what gave the last order) and zone, and its deaths there, with the
    deaths per 1,000 warrior-seconds;
  - the deaths by why: foes per one of us, how long the warrior was under that why and how far from where it began;
  - the threat's seconds by level, decision and cells from our nearest building, with the strength the military
    counts against that of every enemy warrior within 15 cells of the threat's centre;
  - the enemy warriors killed, by who of ours was throwing at each half a second before it died ("HOME kill": a
    tower's throw counts 3, a peon's 0.2, a warrior's 1; none: a blast, or an axe on its way), and for the home army
    the kills for each death by why and zone;
  - the warriors of both sides dead by zone (from the game record: tower = within 16 cells of a standing tower of
    ours, base = within 28 of a building of ours, near = within 45).
Zones in the log lines: tower = within 16 cells of a manned tower of ours, base = within 28 of a building of ours,
near = within 45, far.
"""
import collections
import glob
import json
import os
import re
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
SEC = re.compile(r'^\s*([\d.]+) s0 HOME\s+s lvl (\d) eng (-?\d) str ([\d.]+) base ([\d.]+) real ([\d.]+) at (-?\d+),(-?\d+) '
                 r'own (-?\d+) mode (\w+) n (\d+)(?: post (-?\d+),(-?\d+))? \|([^|]*)\|([^|]*)(?:\|(.*))?$')
LOST = re.compile(r'^\s*([\d.]+) s0 AI\s+lost (\w+) at (\d+),(\d+): (\d+) foes within 14 \((\d+) parked\), (\d+) towers, '
                  r'(\d+) mates within 12, (-?\d+) cells to the army, (-?\d+) home, (-?\d+) to our nearest building, '
                  r'mode (\w+), state (\w+), order (\w+)(?: for (\d+) s to (\d+),(\d+) from (\d+),(\d+))?, zone (\w+), '
                  r'threat (\d) eng (-?\d) str ([\d.]+) at (\d+),(\d+)')
KILL = re.compile(r'^\s*([\d.]+) s0 HOME\s+kill at (\d+),(\d+) zone (\w+) by (.*)$')
WEIGHT = {'tower': 3.0, 'peon': 0.2}
ZONES = ('tower', 'base', 'near', 'far')
ODDS = ('<=0.5', '0.5-1', '1-2', '2-4', '4+')


def odds_bucket(foes, mates):
    o = foes / (mates + 1.0)
    return '<=0.5' if o <= .5 else '0.5-1' if o <= 1 else '1-2' if o <= 2 else '2-4' if o <= 4 else '4+'


def buildings(path):
    ours, open_b, deaths, end = [], {}, [], 0.0
    for line in open(path, encoding='utf-8'):
        d = json.loads(line)
        ev = d['ev']
        if ev == 'built' and d['s'] == 0:
            open_b[(d['x'], d['y'])] = (d['b'], d['t'])
        elif ev == 'razed' and d['s'] == 0:
            key = (d['x'], d['y'])
            if key in open_b:
                kind, t0 = open_b.pop(key)
                ours.append((d['x'], d['y'], kind, t0, d['t']))
        elif ev == 'deaths':
            deaths.append(d)
        elif ev == 'end':
            end = d['t']
    for (x, y), (kind, t0) in open_b.items():
        ours.append((x, y, kind, t0, 1e9))
    return ours, deaths, end


def record_zone(x, y, t, ours):
    best = 1e18
    for (bx, by, kind, t0, t1) in ours:
        if t0 <= t < t1:
            d = (bx - x) ** 2 + (by - y) ** 2
            if kind == 'tower' and d <= 16 * 16:
                return 'tower'
            best = min(best, d)
    return 'base' if best <= 28 * 28 else 'near' if best <= 45 * 45 else 'far'


def main(runs):
    for run in runs:
        hours = 0.0
        games = 0
        ws = collections.Counter()      # (why, zone) -> warrior-seconds
        acts = collections.Counter()    # (why, act) -> warrior-seconds
        dead = collections.Counter()    # (why, zone) -> deaths
        dead_odds = collections.Counter()
        dead_mode = collections.Counter()
        ages = collections.defaultdict(list)
        walked = collections.defaultdict(list)
        threat = collections.Counter()  # (level, eng, own bucket) -> seconds
        under = collections.Counter()   # same -> seconds with real >= 2 x counted
        ratio = collections.defaultdict(list)
        foes = collections.Counter()    # (zone, kind) -> enemy warrior-seconds
        rec = collections.Counter()     # (side, zone) -> warriors dead
        modes = collections.Counter()
        kills = collections.Counter()   # (label, zone) -> credited kills
        harassed = collections.Counter()  # (peon state, own bucket) -> enemy-seconds at level 1
        posts = collections.Counter()   # seconds with a post, its moves and the cells it moved
        for log in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-ai-s0.log'))):
            games += 1
            ours, deaths, end = buildings(log[:-len('-ai-s0.log')] + '.jsonl')
            hours += end / 3600
            last_post = None
            for d in deaths:
                n = d['rock'] + d['iron'] + d['rubber']
                if n:
                    rec[('ours' if d['s'] == 0 else 'theirs', record_zone(d['x'], d['y'], d['t'], ours))] += n
            for line in open(log, encoding='utf-8', errors='replace'):
                if ' HOME ' in line:
                    m = SEC.match(line.rstrip('\n'))
                    if not m:
                        m = KILL.match(line.rstrip('\n'))
                        if m:
                            parts = [part.rsplit('=', 1) for part in m.group(5).split()]
                            parts = [('reinforce' if k.startswith('reinforce:') else k, n) for k, n in parts]
                            total = sum(WEIGHT.get(k, 1.0) * int(n) for k, n in parts)
                            for k, n in parts:
                                kills[(k, m.group(4))] += WEIGHT.get(k, 1.0) * int(n) / total
                        continue
                    level, eng = int(m.group(2)), int(m.group(3))
                    counted, real, own = float(m.group(4)), float(m.group(6)), int(m.group(9))
                    modes[m.group(10)] += int(m.group(11))
                    for part in m.group(14).split():
                        key, n = part.rsplit('=', 1)
                        why, zone, act = key.split('/')
                        ws[(why, zone)] += int(n)
                        acts[(why, act)] += int(n)
                    for part in m.group(15).split():
                        key, n = part.rsplit('=', 1)
                        foes[tuple(key.split('/'))] += int(n)
                    if m.group(12) is not None and int(m.group(12)) >= 0:
                        post = (int(m.group(12)), int(m.group(13)))
                        posts['seconds'] += 1
                        if last_post is not None and float(m.group(1)) - last_post[0] < 3 and post != last_post[1]:
                            posts['moves'] += 1
                            posts['cells'] += ((post[0] - last_post[1][0]) ** 2 + (post[1] - last_post[1][1]) ** 2) ** .5
                        last_post = (float(m.group(1)), post)
                    if level == 1 and m.group(16):
                        ob = '<=28' if own <= 28 else '<=45' if own <= 45 else '<=100' if own <= 100 else '>100'
                        for part in m.group(16).split():
                            key, n = part.rsplit('=', 1)
                            harassed[(key, ob)] += int(n)
                    if level > 0:
                        ob = '<=16' if own <= 16 else '<=28' if own <= 28 else '<=45' if own <= 45 else '<=100' if own <= 100 else '>100'
                        k = (level, 'engage' if eng > 0 else 'fall back', ob)
                        threat[k] += 1
                        if real >= 2 * counted:
                            under[k] += 1
                        ratio[k].append(real / max(counted, .1))
                elif ' lost army ' in line:
                    m = LOST.match(line)
                    if not m:
                        continue
                    x, y = int(m.group(3)), int(m.group(4))
                    why, zone = m.group(14), m.group(20)
                    dead[(why, zone)] += 1
                    dead_odds[(why, odds_bucket(int(m.group(5)), int(m.group(8))))] += 1
                    dead_mode[(why, m.group(12))] += 1
                    if m.group(15) is not None:
                        ages[why].append(int(m.group(15)))
                        walked[why].append(((x - int(m.group(18))) ** 2 + (y - int(m.group(19))) ** 2) ** .5)
        h = max(hours, 1e-9)
        print(f'== {run}: {games} logged games, {hours:.1f} h of game; per hour of game')
        whys = sorted({w for w, _ in ws} | {w for w, _ in dead}, key=lambda w: -sum(dead[(w, z)] for z in ZONES))
        print('  the home army by what gave its last order: warrior-seconds / deaths (deaths per 1,000 warrior-seconds), by zone')
        print(f'    {"why":<10}' + ''.join(f'{z:>24}' for z in ZONES) + f'{"all":>24}')
        for w in whys + ['all']:
            row = ''
            for z in ZONES + ('all',):
                s = sum(ws[(a, b)] for (a, b) in ws if (w == 'all' or a == w) and (z == 'all' or b == z))
                d = sum(dead[(a, b)] for (a, b) in dead if (w == 'all' or a == w) and (z == 'all' or b == z))
                row += f'{s / h:9.0f} /{d / h:6.1f} ({1000 * d / s if s else 0:5.1f})'
            print(f'    {w:<10}{row}')
        if kills:
            print('  enemy warriors killed, by who was throwing at them, by zone (tower base near far all); for the home army the kills for each death')
            labels = sorted({k for k, _ in kills}, key=lambda k: -sum(kills[(k, z)] for z in ZONES))
            for k in labels:
                tot = sum(kills[(k, z)] for z in ZONES)
                if tot / h < .5:
                    continue
                row = ''.join(f'{kills[(k, z)] / h:8.1f}' for z in ZONES) + f'{tot / h:8.1f}'
                if k.startswith('army:'):
                    w = k[5:]
                    d = sum(dead[(w, z)] for z in ZONES)
                    row += '   | ' + ' '.join(
                        f'{z} {kills[(k, z)] / dead[(w, z)]:.2f}' if dead[(w, z)] else f'{z} -' for z in ZONES)
                    row += f' all {tot / d:.2f}' if d else ''
                print(f'    {k:<16}{row}')
        print('  what they do (share of warrior-seconds): fight / walk / idle')
        for w in whys:
            tot = sum(acts[(w, a)] for a in ('fight', 'walk', 'idle'))
            if tot:
                print(f'    {w:<10}' + ' / '.join(f'{100 * acts[(w, a)] / tot:3.0f} %' for a in ('fight', 'walk', 'idle')))
        print('  home army warrior-seconds by mode: ' + ', '.join(f'{k} {v / h:.0f}' for k, v in modes.most_common()))
        print('  deaths by why: foes within 14 per one of us ' + ' '.join(ODDS) + '; by mode; seconds under that why and cells from where it began (quartiles)')
        for w in whys:
            n = sum(dead[(w, z)] for z in ZONES)
            if not n:
                continue
            qs = ''
            if ages[w]:
                a, c = sorted(ages[w]), sorted(walked[w])
                qs = ' | ' + '/'.join(f'{a[int(q * (len(a) - 1))]}' for q in (.25, .5, .75)) + ' s, ' + '/'.join(
                    f'{c[int(q * (len(c) - 1))]:.0f}' for q in (.25, .5, .75)) + ' cells'
            ms = ', '.join(f'{mo} {dead_mode[(w, mo)] / h:.1f}' for mo in ('ATTACK', 'HOME', 'MUSTER', 'RETREAT') if dead_mode[(w, mo)])
            print(f'    {w:<10}{n / h:6.1f}: ' + ' '.join(f'{dead_odds[(w, o)] / h:6.1f}' for o in ODDS) + f' | {ms}{qs}')
        print('  the threat: seconds by level, decision and the centre\'s cells from our nearest building; share with twice the counted strength within 15 cells; median real / counted')
        for k in sorted(threat, key=lambda k: (k[0], k[1], ('<=16', '<=28', '<=45', '<=100', '>100').index(k[2]))):
            print(f'    level {k[0]} {k[1]:<9} {k[2]:>6}: {threat[k] / h:7.0f} s, {100 * under[k] / threat[k]:3.0f} %, {statistics.median(ratio[k]):4.1f}')
        if harassed:
            print('  threat level 1: the enemies away from the base, enemy-seconds by the state of the peon each stands by and the cells from the threat to our nearest building (<=28 <=45 <=100 >100)')
            for k in sorted({k for k, _ in harassed}, key=lambda k: -sum(harassed[(k, o)] for o in ('<=28', '<=45', '<=100', '>100'))):
                print(f'    {k:<16}' + ''.join(f'{harassed[(k, o)] / h:8.0f}' for o in ('<=28', '<=45', '<=100', '>100')))
        if posts['seconds']:
            print(f"  the post (hold_ratio): {posts['seconds'] / h:.0f} s, moved to another building {posts['moves'] / h:.0f} times, "
                  f"{posts['cells'] / max(1, posts['moves']):.0f} cells a move, once in {posts['seconds'] / max(1, posts['moves']):.0f} s")
        print('  enemy warriors near our buildings, warrior-seconds: ' + ', '.join(
            f'{z}/{k} {foes[(z, k)] / h:.0f}' for z in ZONES for k in ('parked', 'hunt', 'hunt_b', 'hunt_w', 'hunt_p', 'walk', 'other') if foes[(z, k)]))
        print('  warriors dead by zone (the game record), theirs / ours: ' + ', '.join(
            f'{z} {rec[("theirs", z)] / h:.0f} / {rec[("ours", z)] / h:.0f}' for z in ZONES))


if __name__ == '__main__':
    main(sys.argv[1:])
