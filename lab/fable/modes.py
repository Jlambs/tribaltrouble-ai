#!/usr/bin/env python3
"""Where the army's time goes between attacks, from runs made with --logs: python lab/fable/modes.py [--from MIN]
[--to MIN] RUN...

Rebuilds the army's mode (home, muster, attack, retreat) from the AI's decision lines and splits the time at home by
what kept it there (the "hold:" lines: the wait between attacks, a threat at the base, an army under the smallest
size, a target whose defense outweighs it, no target). Per run, for the window (minutes 10 to 45 unless given):
  - the share of the time in each mode, the home time by reason, the army's strength at home by reason;
  - the musters: how many end gathered and how many on the 45-second timeout, how long they waited, by the threat at
    the base when they began;
  - the attacks: how they end (the army gone, turned back before contact, outmatched, worn, wedged, called home, no
    target left), how long they last, what they launch with and what comes home;
  - the gap from the end of an attack's fighting (the retreat's start or the attack's end) to the next launch, split
    into the walk home, the time at home by reason and the muster.
"""
import collections
import glob
import os
import re
import statistics
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')
LINE = re.compile(r'^\s*([0-9.]+) s0 AI\s+(.*)$')
MUSTER = re.compile(r'^muster: army ([0-9.]+) \+ stock ([0-9.]+) vs defense ([0-9.]+)')
MUSTER_END = re.compile(r'^muster ends after (\d+) s \((\w+)\): (\d+) of (\d+) by the staging point')
ATTACK = re.compile(r'^attack with ([0-9.]+) on ')
HOLD = re.compile(r'^hold: (\w+), army ([0-9.]+), threat (\d) of ([0-9.]+) at the base')
THREAT = re.compile(r'^threat (\d): ([0-9.]+) at')
RETREATS = [('retreat: local', 'outmatched'), ('retreat: worn', 'worn'), ('turning back before contact', 'precontact'),
            ('attack wedged', 'wedged'), ('retreat capped', None)]
STAT = re.compile(r'^\s*([0-9.]+) s0 STAT .* mode=(\w+) army=(\d+) atk=(\d+)')


def game(path):
    """The mode's spells [(start, end, mode, why)], the musters, the attacks and the game's last second."""
    spells = []
    musters = []
    attacks = []
    mode = 'home'
    why = 'start'
    since = 0.0
    threat = 0
    threat_s = 0.0
    last = 0.0
    muster = None
    attack = None
    retreat_from = None
    home_army = collections.defaultdict(list)

    def switch(t, new_mode, new_why):
        nonlocal mode, why, since
        if new_mode == mode and new_why == why:
            return
        if t > since:
            spells.append((since, t, mode, why))
        mode, why, since = new_mode, new_why, t

    for line in open(path, encoding='utf-8', errors='replace'):
        m = LINE.match(line)
        if not m:
            s = STAT.match(line)
            if s:
                last = float(s.group(1))
            continue
        t = float(m.group(1))
        msg = m.group(2)
        last = max(last, t)
        x = THREAT.match(msg)
        if x:
            threat = int(x.group(1))
            threat_s = float(x.group(2))
            continue
        if msg.startswith('threat over'):
            threat = 0
            threat_s = 0.0
            continue
        x = HOLD.match(msg)
        if x:
            if mode in ('home',):
                switch(t, 'home', x.group(1))
                home_army[x.group(1)].append(float(x.group(2)))
            continue
        x = MUSTER.match(msg)
        if x or msg.startswith(('away strike:', 'push:', 'all-in:', 'soft push:')):
            if mode != 'muster':
                muster = {'t': t, 'threat': threat, 'threat_s': threat_s,
                          'army': float(x.group(1)) if x else 0.0, 'defense': float(x.group(3)) if x else 0.0}
                switch(t, 'muster', '')
            continue
        x = MUSTER_END.match(msg)
        if x and muster is not None:
            muster.update(wait=float(x.group(1)), end=x.group(2), near=int(x.group(3)), total=int(x.group(4)))
            musters.append(muster)
            muster = None
            continue
        if msg.startswith('muster dropped'):
            switch(t, 'home', 'unknown')
            muster = None
            continue
        x = ATTACK.match(msg)
        if x:
            if float(x.group(1)) > 0:
                attack = {'t': t, 'strength': float(x.group(1)), 'end': None, 'fight_end': None}
                attacks.append(attack)
                switch(t, 'attack', '')
            else:
                switch(t, 'home', 'unknown')
            continue
        if msg.startswith('calling the army home'):
            if attack is not None and attack['end'] is None:
                attack['end'] = 'called home'
            continue
        hit = False
        for prefix, name in RETREATS:
            if msg.startswith(prefix):
                hit = True
                if name is not None and attack is not None and attack['end'] is None:
                    attack['end'] = name
                    attack['fight_end'] = t
                    switch(t, 'retreat', '')
                break
        if hit:
            continue
        if msg.startswith('attack over, back home'):
            if attack is not None:
                if attack['end'] is None:
                    attack['end'] = 'over'
                if attack['fight_end'] is None:
                    attack['fight_end'] = t
                attack['home'] = t
            attack = None
            switch(t, 'home', 'unknown')
            continue
    switch(last, 'end', '')
    return spells, musters, attacks, last, home_army


def overlap(a, b, lo, hi):
    return max(0.0, min(b, hi) - max(a, lo))


def main():
    args = sys.argv[1:]
    lo, hi = 600.0, 2700.0
    while args and args[0] in ('--from', '--to'):
        if args[0] == '--from':
            lo = 60 * float(args[1])
        else:
            hi = 60 * float(args[1])
        args = args[2:]
    for run in args:
        paths = sorted(glob.glob(os.path.join(ROOT, run, 'g', '*-ai-s0.log')))
        share = collections.Counter()
        total = 0.0
        musters = []
        attacks = []
        gaps = []
        home_army = collections.defaultdict(list)
        games = 0
        for path in paths:
            spells, ms, ats, last, ha = game(path)
            if last < lo:
                continue
            games += 1
            end = min(hi, last)
            total += end - lo
            for a, b, mode, why in spells:
                o = overlap(a, b, lo, end)
                if o > 0:
                    share[mode if mode != 'home' else 'home/' + why] += o
            musters += [m for m in ms if lo <= m['t'] < end]
            ats_in = [a for a in ats if lo <= a['t'] < end]
            attacks += ats_in
            for k, v in ha.items():
                home_army[k] += v
            # the gap after each attack's fighting to the next launch
            for i, a in enumerate(ats):
                if a['fight_end'] is None or not lo <= a['fight_end'] < end or i + 1 >= len(ats):
                    continue
                nxt = ats[i + 1]['t']
                parts = collections.Counter()
                for s0, s1, mode, why in spells:
                    o = overlap(s0, s1, a['fight_end'], nxt)
                    if o > 0:
                        parts[mode if mode != 'home' else 'home/' + why] += o
                gaps.append((nxt - a['fight_end'], parts, a['end']))
        print('%s: %d games, minutes %d to %d (%.0f game-minutes)' % (run, games, lo / 60, hi / 60, total / 60))
        if not total:
            continue
        print('  time by mode:')
        for k, v in sorted(share.items(), key=lambda kv: -kv[1]):
            army = home_army.get(k[5:]) if k.startswith('home/') else None
            print('    %-18s %5.1f %%  %6.1f s a game%s' % (
                k, 100 * v / total, v / games, '   army %.0f' % statistics.mean(army) if army else ''))
        print('  musters: %d (%.1f a game)' % (len(musters), len(musters) / games))
        by = collections.defaultdict(list)
        for m in musters:
            if 'end' in m:
                by[(m['threat'], m['end'])].append(m)
        for (threat, end), ms in sorted(by.items()):
            print('    threat %d, %-8s %4d  waited %4.1f s, %2.0f of %2.0f by the staging point, army %.0f, threat %.0f' % (
                threat, end, len(ms), statistics.mean(m['wait'] for m in ms), statistics.mean(m['near'] for m in ms),
                statistics.mean(m['total'] for m in ms), statistics.mean(m['army'] for m in ms),
                statistics.mean(m['threat_s'] for m in ms)))
        print('  attacks: %d (%.1f a game), launched with %.1f' % (
            len(attacks), len(attacks) / games, statistics.mean(a['strength'] for a in attacks) if attacks else 0))
        by = collections.defaultdict(list)
        for a in attacks:
            by[a['end'] or 'running'].append(a)
        for end, ats in sorted(by.items(), key=lambda kv: -len(kv[1])):
            lasted = [a['fight_end'] - a['t'] for a in ats if a['fight_end'] is not None]
            walk = [a['home'] - a['fight_end'] for a in ats if a.get('home') is not None]
            print('    %-12s %4d  launched with %5.1f, fights %5.0f s (median %4.0f), then %4.0f s to "back home"' % (
                end, len(ats), statistics.mean(a['strength'] for a in ats), statistics.mean(lasted) if lasted else 0,
                statistics.median(lasted) if lasted else 0, statistics.mean(walk) if walk else 0))
        if gaps:
            print('  from the end of an attack\'s fighting to the next launch: %d gaps, %.0f s (median %.0f)' % (
                len(gaps), statistics.mean(g[0] for g in gaps), statistics.median(g[0] for g in gaps)))
            parts = collections.Counter()
            for g in gaps:
                parts.update(g[1])
            for k, v in sorted(parts.items(), key=lambda kv: -kv[1]):
                print('    %-18s %6.1f s a gap' % (k, v / len(gaps)))
            by = collections.defaultdict(list)
            for g in gaps:
                by[g[2]].append(g[0])
            for k, v in sorted(by.items(), key=lambda kv: -len(kv[1])):
                print('    after %-12s %4d gaps of %4.0f s (median %4.0f)' % (
                    k, len(v), statistics.mean(v), statistics.median(v)))


if __name__ == '__main__':
    main()
