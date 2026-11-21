#!/usr/bin/env python3
"""What becomes of the reinforcements on their way, from runs made with --logs: python lab/fable/reinforce.py RUN...

Reads ReinforceTrace's "REIN start / s / end" lines (a group from the armory to the army, a line a second) and
HomeTrace's "HOME kill" lines (the enemy warriors a group's members were throwing at when they died), per hour of game:

- the groups sent, their size, the army's strength and distance then, and what became of the warriors: joined, dead on
  the way, turned back (the attack ended first); the seconds a warrior is on its way;
- where a group's members stand when it is sent: their cells from its middle member, by what had given each its last
  order before;
- how far a group is strung out before it has met anyone (len, the widest distance between two of its members, by
  its age in seconds);
- its meetings: a meeting is a run of seconds with three and more enemy warriors within 14 cells of a member (a gap
  of up to 5 s does not end it). By what the enemy was doing when it began (HomeTrace's kind of the most of them:
  parked, walk, hunt_p a peon, hunt_w a warrior, hunt_b a building of ours), by the enemy's strength for one of the
  group there, by how strung out the group was, by the cells to our nearest building and by our other warriors about:
  meetings, the group's deaths in them, the enemy warriors it killed, kills for a death, the seconds they last;
- the deaths outside any meeting.
"""
import collections
import glob
import json
import os
import re
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
START = re.compile(r'^\s*([\d.]+) s0 REIN\s+start g (\d+) n (\d+) str ([\d.]+) at (\d+),(\d+) army ([\d.]+) dist (-?\d+) '
                   r'own (-?\d+)(?: staging (\d+),(\d+) \|(.*))?$')
SEC = re.compile(r'^\s*([\d.]+) s0 REIN\s+s g (\d+) age (\d+) way (\d+) joined (\d+) dead (\d+) back (\d+) c (\d+),(\d+) '
                 r'len (\d+) r50 (\d+) fight (\d+) walk (\d+) idle (\d+) foes (\d+) fstr ([\d.]+) near (\d+) '
                 r'towers (\d+) friends (\d+) own (-?\d+) army (-?\d+) \|(.*)$')
END = re.compile(r'^\s*([\d.]+) s0 REIN\s+end g (\d+) age (\d+) sent (\d+) joined (\d+) dead (\d+) back (\d+)')
KILL = re.compile(r'^\s*([\d.]+) s0 HOME\s+kill at (\d+),(\d+) zone (\w+) by (.*)$')
WEIGHT = {'tower': 3.0, 'peon': 0.2}
MEET_FOES = 3
MEET_GAP = 5


def bucket(v, edges, labels):
    for e, label in zip(edges, labels):
        if v <= e:
            return label
    return labels[-1]


def quart(vs):
    vs = sorted(vs)
    if not vs:
        return '-'
    return f'{vs[len(vs) // 4]:.0f} / {vs[len(vs) // 2]:.0f} / {vs[3 * len(vs) // 4]:.0f}'


class Meeting:
    def __init__(self, t, rec):
        self.t0 = self.t1 = t
        self.first = rec
        self.dead0 = rec['dead']
        self.dead1 = rec['dead']
        self.kills = 0.0
        self.peak = rec['fstr']
        self.way0 = rec['way']


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
        tot = collections.Counter()
        sizes, army0, dist0, ages = [], [], [], []
        strung = collections.defaultdict(list)       # age -> len of groups that have met nobody yet
        strung_r = collections.defaultdict(list)
        acts = collections.Counter()
        tables = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))
        outside = collections.Counter()
        before = collections.Counter()      # (why before, cells from the group's middle member) -> members
        from_staging = []
        for log in sorted(glob.glob(os.path.join(ROOT, run, 'g', 's*-ai-s0.log'))):
            games += 1
            hours += game_end(log[:-len('-ai-s0.log')] + '.jsonl') / 3600
            last = {}        # group -> last second's record
            met = set()      # groups that have met anyone
            meeting = {}     # group -> open meeting
            done = []

            def close(g):
                m = meeting.pop(g, None)
                if m is not None:
                    done.append(m)

            for line in open(log, encoding='utf-8', errors='replace'):
                if ' REIN ' in line:
                    line = line.rstrip('\n')
                    m = SEC.match(line)
                    if m:
                        t = float(m.group(1))
                        g = int(m.group(2))
                        rec = dict(age=int(m.group(3)), way=int(m.group(4)), joined=int(m.group(5)),
                                   dead=int(m.group(6)), back=int(m.group(7)), len=int(m.group(10)),
                                   r50=int(m.group(11)), fight=int(m.group(12)), walk=int(m.group(13)),
                                   idle=int(m.group(14)), foes=int(m.group(15)), fstr=float(m.group(16)),
                                   near=int(m.group(17)), towers=int(m.group(18)), friends=int(m.group(19)),
                                   own=int(m.group(20)), army=int(m.group(21)), kinds={})
                        for part in m.group(22).split():
                            k, n = part.rsplit('=', 1)
                            rec['kinds'][k] = int(n)
                        prev = last.get(g)
                        died = rec['dead'] - (prev['dead'] if prev else 0)
                        last[g] = rec
                        acts['fight'] += rec['fight']
                        acts['walk'] += rec['walk']
                        acts['idle'] += rec['idle']
                        acts['with foes within 14'] += rec['way'] if rec['foes'] else 0
                        if rec['foes']:
                            met.add(g)
                        elif g not in met and rec['joined'] == 0 and rec['age'] in (5, 10, 20, 30, 45, 60, 90, 120):
                            strung[rec['age']].append(rec['len'])
                            strung_r[rec['age']].append(rec['r50'])
                        mt = meeting.get(g)
                        if rec['foes'] >= MEET_FOES:
                            if mt is None:
                                mt = meeting[g] = Meeting(t, rec)
                                mt.dead0 = prev['dead'] if prev else 0
                                mt.way0 = prev['way'] if prev else rec['way']
                            mt.t1 = t
                            mt.dead1 = rec['dead']
                            mt.peak = max(mt.peak, rec['fstr'])
                        elif mt is not None:
                            mt.dead1 = rec['dead']
                            if t - mt.t1 > MEET_GAP:
                                close(g)
                        elif died:
                            outside['deaths'] += died
                        continue
                    m = START.match(line)
                    if m:
                        tot['groups'] += 1
                        tot['sent'] += int(m.group(3))
                        sizes.append(int(m.group(3)))
                        army0.append(float(m.group(7)))
                        if int(m.group(8)) >= 0:
                            dist0.append(int(m.group(8)))
                        if m.group(12):
                            members = [part.split(',') for part in m.group(12).split()]
                            xs = sorted(int(p[0]) for p in members)
                            ys = sorted(int(p[1]) for p in members)
                            mx, my = xs[len(xs) // 2], ys[len(ys) // 2]
                            from_staging.append(((mx - int(m.group(10))) ** 2 + (my - int(m.group(11))) ** 2) ** .5)
                            for p in members:
                                d = ((int(p[0]) - mx) ** 2 + (int(p[1]) - my) ** 2) ** .5
                                before[(p[2], bucket(d, (10, 20, 40), ('<=10', '11-20', '21-40', '41+')))] += 1
                        continue
                    m = END.match(line)
                    if m:
                        g = int(m.group(2))
                        tot['joined'] += int(m.group(5))
                        tot['dead'] += int(m.group(6))
                        tot['back'] += int(m.group(7))
                        ages.append(int(m.group(3)))
                        mt = meeting.get(g)
                        prev = last.get(g)
                        died = int(m.group(6)) - (prev['dead'] if prev else 0)
                        if mt is not None:
                            mt.dead1 = int(m.group(6))
                            close(g)
                        elif died:
                            outside['deaths'] += died
                        last.pop(g, None)
                    continue
                if ' HOME ' in line and ' kill at ' in line:
                    m = KILL.match(line.rstrip('\n'))
                    if not m:
                        continue
                    parts = [part.rsplit('=', 1) for part in m.group(5).split()]
                    total = sum(WEIGHT.get(k, 1.0) * int(n) for k, n in parts)
                    for k, n in parts:
                        if k.startswith('reinforce:'):
                            g = int(k.split(':')[1])
                            share = int(n) / total
                            tot['kills'] += share
                            if g in meeting:
                                meeting[g].kills += share
                            else:
                                outside['kills'] += share
            for g in list(meeting):
                close(g)
            for mt in done:
                f = mt.first
                dead = mt.dead1 - mt.dead0
                kinds = f['kinds']
                kind = max(kinds, key=lambda k: kinds[k]) if kinds else 'none'
                ratio = mt.peak / max(1, mt.way0)
                keys = {
                    'what the enemy was doing': kind,
                    'enemy strength (peak) for one of the group': bucket(ratio, (.5, 1, 2, 4), ('<=0.5', '0.5-1', '1-2',
                                                                                               '2-4', '4+')),
                    'the group there': bucket(mt.way0, (2, 5, 9), ('1-2', '3-5', '6-9', '10+')),
                    'len of the group': bucket(f['len'], (8, 16, 30), ('<=8', '9-16', '17-30', '31+')),
                    'cells to our nearest building': bucket(f['own'], (28, 45, 100), ('<=28', '29-45', '46-100',
                                                                                      '100+')),
                    'cells to the army': bucket(f['army'], (-1, 40, 80, 140), ('none', '<=40', '41-80', '81-140',
                                                                               '141+')),
                    'our other warriors within 14': bucket(f['friends'], (0, 4, 14), ('0', '1-4', '5-14', '15+')),
                    'enemy towers within 17': bucket(f['towers'], (0,), ('0', '1+')),
                    'kind x strength': kind + ' / ' + bucket(ratio, (1, 2), ('<=1', '1-2', '2+')),
                    'len x strength': bucket(f['len'], (12,), ('len <=12', 'len 13+')) + ' / ' + bucket(
                        ratio, (1, 2), ('<=1', '1-2', '2+')),
                }
                for name, key in keys.items():
                    c = tables[name][key]
                    c['n'] += 1
                    c['dead'] += dead
                    c['kills'] += mt.kills
                    c['secs'] += mt.t1 - mt.t0 + 1
                    c['way'] += mt.way0
        h = max(hours, 1e-9)
        print(f'== {run}: {games} logged games, {hours:.1f} h of game; an hour of game')
        print(f'  groups {tot["groups"] / h:.1f} of {statistics.mean(sizes) if sizes else 0:.1f} '
              f'(quartiles {quart(sizes)}); the army then {quart(army0)} strong, {quart(dist0)} cells away')
        print(f'  warriors sent {tot["sent"] / h:.0f}: joined {tot["joined"] / h:.0f}, dead on the way '
              f'{tot["dead"] / h:.0f}, turned back {tot["back"] / h:.0f}; enemy warriors they kill on the way '
              f'{tot["kills"] / h:.0f}')
        print(f'  a group is on its way {quart(ages)} s; warrior-seconds on the way: '
              + ', '.join(f'{k} {v / h:.0f}' for k, v in acts.items()))
        print('  strung out before it has met anyone: age, groups, len quartiles, r50 quartiles')
        for age in sorted(strung):
            print(f'    {age:>4} s {len(strung[age]):>5}   {quart(strung[age]):<16} {quart(strung_r[age])}')
        if before:
            cols = ('<=10', '11-20', '21-40', '41+')
            print(f'  the middle of a group is {quart(from_staging)} cells from the staging point; its members by what gave '
                  f'them their last order before, and their cells from the middle:' + ''.join(f'{c:>8}' for c in cols))
            for why in sorted({k for k, _ in before}, key=lambda w: -sum(before[(w, c)] for c in cols)):
                print(f'    {why:<12}' + ''.join(f'{before[(why, c)] / h:8.1f}' for c in cols))
            print(f'    {"all":<12}' + ''.join(f'{sum(v for (w, cc), v in before.items() if cc == c) / h:8.1f}'
                                              for c in cols))
        print(f'  outside any meeting: deaths {outside["deaths"] / h:.1f}, kills {outside["kills"] / h:.1f}')
        for name, table in tables.items():
            print(f'  {name}: meetings, the group there, its deaths, its kills, kills for a death, seconds')
            for k, c in sorted(table.items(), key=lambda kv: -kv[1]['dead']):
                ex = c['kills'] / c['dead'] if c['dead'] else float('nan')
                print(f'    {k:<22}{c["n"] / h:8.1f}{c["way"] / max(1, c["n"]):8.1f}{c["dead"] / h:8.1f}'
                      f'{c["kills"] / h:8.1f}{ex:8.2f}{c["secs"] / max(1, c["n"]):8.0f}')


if __name__ == '__main__':
    main(sys.argv[1:])
