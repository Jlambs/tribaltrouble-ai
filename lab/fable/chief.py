#!/usr/bin/env python3
"""What our chieftain does with his blast, from runs made with --logs: python lab/fable/chief.py RUN...

Reads ChiefTrace's lines. Per run, an hour of game: his lives and the seconds he is there, charged and not; the
charged seconds by what the mission does with him; the blasts by kind with the enemy warriors in reach at the order
and when the blast takes its targets (2.15 s later), the dead, his own hit points lost in the cast; where he loses
hit points and where he dies; and how often a blast would have reached 8, 15, 25, 40 enemy warriors from where he
stood, from the best cell a 6 s walk away and from the best a 15 s walk away (all of them, the parked, those hunting
him, those hunting another unit of ours).
"""
import collections
import glob
import re
import statistics
import sys

STATES = ['charging', 'weak', 'awake', 'noblob', 'nopoint', 'walk', 'blast', 'cast', 'stunned', 'refresh', 'press', 'none']
KINDS = ['parked', 'at_him', 'at_other', 'walking', 'other']
POPS = ['all', 'parked', 'at_him', 'at_other']
LEVELS = [8, 15, 25, 40]
WHERE = ['here', 'a 6 s walk', 'a 15 s walk']

HEAD = re.compile(r'^\s*(\S+) \S+ CHIEF (\w+) (.*)$')
BLAST = re.compile(
    r'#(\d+) at (\d+),(\d+) before (\S+) (\S*) ?order \[([\d ]+)\] cap (-?\d+) \[([\d ]+)\] rock (\d+) killed (\d+)'
    r' rock (\d+) ours (\d+) ours_dead (\d+) hp (\d+)>(\d+) dmg (-?\d+) after (\S+) cyc (.*)$')
CYC = re.compile(
    r'(\S+) states \[([\d ]+)\] path (\d+) walks (\d+) offs (\d+) max_here \[([\d ]+)\] max_near \[([\d ]+)\]'
    r' max_far \[([\d ]+)\]')
DEAD = re.compile(
    r'#(\d+) at (\d+),(\d+) home (-?\d+) state (\S+) charged (\w+) casting (\w+) hp_last (\d+) at_him9 (\d+)'
    r' at_him17 (\d+) tower (-?\d+) cyc (.*)$')
DMG = re.compile(r'hp (\d+)>(\d+) state (\S+) charged (\w+) at_him9 (\d+) at_him17 (\d+) tower (-?\d+) home (-?\d+)')
SUM = re.compile(
    r'lives (\d+) casts (\d+) alive (\d+) charged (\d+) states \[([\d ]+)\] charged_states \[([\d ]+)\]'
    r' dmg \[([\d ]+)\] levels \[([\d ]+)\](?: why \[([\d ]+)\])?(?: friends \{(.*)\})?')
WHY = ['nothing (he waits for more)', 'ground', 'seen within 5 cells', 'tower', 'friends', 'few']


def ints(s):
    return [int(v) for v in s.split()]


def cyc(s):
    m = CYC.match(s)
    if not m:
        return None
    return dict(dur=float(m.group(1)), states=ints(m.group(2)), path=int(m.group(3)), walks=int(m.group(4)),
                offs=int(m.group(5)), here=ints(m.group(6)), near=ints(m.group(7)), far=ints(m.group(8)))


def load(run):
    games = []
    for f in sorted(glob.glob(f'aisim/runs/{run}/g/*-ai-s0.log')):
        g = dict(file=f, blasts=[], dead=[], dmg=[], sum=None, end=0.0)
        for line in open(f, encoding='utf-8', errors='replace'):
            m = HEAD.match(line)
            if not m:
                continue
            t, kind, rest = float(m.group(1)), m.group(2), m.group(3)
            g['end'] = max(g['end'], t)
            if kind == 'blast':
                b = BLAST.match(rest)
                if not b:
                    print('unparsed:', line.strip()[:200], file=sys.stderr)
                    continue
                v = b.groups()
                g['blasts'].append(dict(
                    t=t, before=v[3], kind=v[4] or '-', order=ints(v[5]), cap=int(v[6]), cap_kinds=ints(v[7]),
                    cap_rock=int(v[8]), killed=int(v[9]), killed_rock=int(v[10]), ours=int(v[11]), ours_dead=int(v[12]),
                    hp0=int(v[13]), hp1=int(v[14]), dmg=int(v[15]), after=float(v[16]), cyc=cyc(v[17])))
            elif kind == 'dead':
                d = DEAD.match(rest)
                if not d:
                    print('unparsed:', line.strip()[:200], file=sys.stderr)
                    continue
                v = d.groups()
                g['dead'].append(dict(t=t, home=int(v[3]), state=v[4], charged=v[5] == 'true', casting=v[6] == 'true',
                                      hp=int(v[7]), at9=int(v[8]), at17=int(v[9]), tower=int(v[10]), cyc=cyc(v[11])))
            elif kind == 'dmg':
                d = DMG.match(rest)
                if d:
                    v = d.groups()
                    g['dmg'].append(dict(t=t, lost=int(v[0]) - int(v[1]), state=v[2], charged=v[3] == 'true',
                                         at9=int(v[4]), at17=int(v[5]), tower=int(v[6]), home=int(v[7])))
            elif kind == 'sum':
                s = SUM.match(rest)
                if s:
                    v = s.groups()
                    g['sum'] = dict(lives=int(v[0]), casts=int(v[1]), alive=int(v[2]), charged=int(v[3]),
                                    states=ints(v[4]), charged_states=ints(v[5]), dmg=ints(v[6]), levels=ints(v[7]),
                                    why=ints(v[8]) if v[8] else [0] * 6,
                                    friends=dict((k, int(n)) for k, n in (kv.split('=') for kv in v[9].split(',') if kv)) if v[9] else {})
        if g['sum']:
            games.append(g)
    return games


def report(run):
    games = load(run)
    hours = sum(g['end'] for g in games) / 3600
    if not hours:
        print(f'== {run}: no games')
        return
    blasts = [b for g in games for b in g['blasts']]
    dead = [d for g in games for d in g['dead']]
    dmg = [d for g in games for d in g['dmg']]

    def tot(key):
        return sum(g['sum'][key] for g in games)

    def vec(key):
        return [sum(g['sum'][key][i] for g in games) for i in range(len(games[0]['sum'][key]))]

    print(f'== {run}: {len(games)} games, {hours:.1f} h of game; an hour of game:')
    alive, charged = tot('alive') / 2 / hours, tot('charged') / 2 / hours
    print(f"  lives {tot('lives') / hours:.1f}, deaths {len(dead) / hours:.1f}, there {alive:.0f} s of 3600, charged and "
          f"not casting {charged:.0f} s, casts {tot('casts') / hours:.1f} (blasts followed {len(blasts) / hours:.1f})")
    cs = vec('charged_states')
    print('  charged seconds by state: ' + ', '.join(
        f'{s} {v / 2 / hours:.0f}' for s, v in zip(STATES, cs) if v))
    # blasts
    by = collections.defaultdict(list)
    for b in blasts:
        by[b['kind']].append(b)
    print('  blasts by kind: n an hour | in reach at the order (parked, at him, at another, walking, other) | taken by '
          'the blast | of them rock | dead | his hit points lost in the cast | ours in reach, dead | charged s before')
    for k, bs in sorted(by.items(), key=lambda kv: -len(kv[1])) + [('all', blasts)]:
        n = len(bs)
        if not n:
            continue
        order = [sum(b['order'][i] for b in bs) / n for i in range(5)]
        capk = [sum(b['cap_kinds'][i] for b in bs if b['cap'] >= 0) / max(1, sum(b['cap'] >= 0 for b in bs))
                for i in range(5)]
        capn = [b['cap'] for b in bs if b['cap'] >= 0]
        cy = [b['cyc']['dur'] for b in bs if b['cyc']]
        print(f"    {k:8s} {n / hours:5.1f} | {sum(order):5.1f} ({', '.join(f'{v:.1f}' for v in order)}) | "
              f"{statistics.mean(capn) if capn else 0:5.1f} ({', '.join(f'{v:.1f}' for v in capk)}) | "
              f"{sum(b['cap_rock'] for b in bs) / n:5.1f} | {sum(b['killed'] for b in bs) / n:5.1f} | "
              f"{sum(b['dmg'] for b in bs) / n:4.1f} | {sum(b['ours'] for b in bs) / n:4.1f}, "
              f"{sum(b['ours_dead'] for b in bs) / n:4.1f} | {statistics.median(cy) if cy else 0:5.1f} "
              f"(mean {statistics.mean(cy) if cy else 0:.0f})")
    print(f"  enemy warriors taken {sum(max(0, b['cap']) for b in blasts) / hours:.0f} an hour, dead "
          f"{sum(b['killed'] for b in blasts) / hours:.0f}; a cast that never took its targets "
          f"{sum(b['cap'] < 0 for b in blasts) / hours:.1f}")
    caps = sorted(max(0, b['cap']) for b in blasts)
    if caps:
        q = lambda p: caps[min(len(caps) - 1, int(p * len(caps)))]
        print(f'  taken a blast: quartiles {q(.25)} / {q(.5)} / {q(.75)}, most {caps[-1]}; under 8: '
              f'{100 * sum(c < 8 for c in caps) / len(caps):.0f} %')
    # what the charge saw before it was spent
    cyb = [b['cyc'] for b in blasts if b['cyc']]
    if cyb:
        for name, key in (('where he stood', 'here'), ('a 6 s walk away', 'near'), ('a 15 s walk away', 'far')):
            print(f"  the most a blast would have reached while that charge waited, {name}: "
                  + ', '.join(f'{p} {statistics.mean([c[key][i] for c in cyb]):.1f}' for i, p in enumerate(POPS)))
        print(f"  a charge before its blast: {statistics.mean([c['dur'] for c in cyb]):.0f} s, walked "
              f"{statistics.mean([c['path'] for c in cyb]):.0f} cells, approaches {statistics.mean([c['walks'] for c in cyb]):.1f} "
              f"(called off {statistics.mean([c['offs'] for c in cyb]):.1f})")
    # damage
    dv = vec('dmg')
    print(f"  hit points lost {sum(dv) / hours:.0f}: " + ', '.join(
        f'{s} {v / hours:.0f}' for s, v in zip(STATES, dv) if v))
    if dmg:
        tw = sum(d['lost'] for d in dmg if 0 <= d['tower'] <= 16)
        hunted = sum(d['lost'] for d in dmg if d['at17'] > 0)
        home = sum(d['lost'] for d in dmg if 0 <= d['home'] <= 25)
        allv = sum(d['lost'] for d in dmg)
        print(f"    of them with a manned enemy tower within 16 cells {100 * tw / allv:.0f} %, with a hunter of his "
              f"within 17 {100 * hunted / allv:.0f} %, within 25 cells of our armory {100 * home / allv:.0f} %")
    if dead:
        c = collections.Counter(d['state'].split('/')[0] for d in dead)
        print(f"  deaths by state: " + ', '.join(f'{k} {v / hours:.1f}' for k, v in c.most_common())
              + f"; charged {sum(d['charged'] for d in dead) / hours:.1f}, casting {sum(d['casting'] for d in dead) / hours:.1f}, "
              f"hunters of his within 17 cells (median) {statistics.median([d['at17'] for d in dead]):.0f}, "
              f"from our armory (median) {statistics.median([d['home'] for d in dead]):.0f} cells")
    wv = vec('why')
    if sum(wv):
        print(f'  charged with 8 and more in reach and no cast, {sum(wv) / 2 / hours:.0f} s: what rules the blast out: '
              + ', '.join(f'{k} {v / 2 / hours:.0f} s' for k, v in zip(WHY, wv) if v))
    fr = collections.Counter()
    for g in games:
        fr.update(g['sum']['friends'])
    if fr:
        n = wv[4] or 1
        print('    our units within 19 cells while they rule it out, a half second: '
              + ', '.join(f'{k} {v / n:.1f}' for k, v in fr.most_common(8)))
    # chances
    lv = vec('levels')
    ch = tot('charged') or 1
    print('  share of the charged seconds in which a blast would reach N enemy warriors and more:')
    for w, where in enumerate(WHERE):
        for p, pop in enumerate(POPS):
            row = lv[(w * len(POPS) + p) * len(LEVELS):(w * len(POPS) + p + 1) * len(LEVELS)]
            print(f"    {where:12s} {pop:9s} " + '  '.join(f'{l}+: {100 * v / ch:4.1f} %' for l, v in zip(LEVELS, row)))


for run in sys.argv[1:]:
    report(run)
