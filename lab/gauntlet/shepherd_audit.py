#!/usr/bin/env python3
"""Shepherd mechanism audit: per-game means of what the shepherds do, from the counters of every game and, for logged
runs, from the decision logs (Shepherd's recruited / at spot / lost / spot jumps / wave / launch lines).

    python lab/gauntlet/shepherd_audit.py [--seeds A..B] RUN [RUN...]     (folders in aisim/runs, or paths)

Counter lines (all games):
  recruits, arrivals (shepherd_at_spot), lost, jumps (spot jumps > 30 cells), launches seen, drawn (wave_drawn), base
  (wave_to_base), to_shep (target within 6 cells of any live shepherd)
  time: shares of flock-tends (every 0.5 s per copy, from shepherd_time) with the shepherd on its spot (3 cells),
  walking to it, fleeing, with no spot, or with no shepherd (shepherd_t_* counters, from 2026-09-29)
Log lines (logged games only):
  flips10     A -> B -> A spot jumps within 10 s (the second jump lands within 8 cells of the first one's start)
  lost_pre    shepherds lost before they ever reached a spot
  home_orig   launches whose origin is within 40 cells of the copy's start (its home), and home_base: those that go
              to our base
  disp_*      mean change of the wave's distance from our start (target minus origin, cells; > 0 = away from us):
              drawn waves, waves caught by a shepherd off its spot (target by a shepherd, not drawn), base waves
  base by own shepherd (launch lines with the origin columns, from 2026-09-29): at the base-bound launch the copy's
              own shepherd was away from its spot, had no spot, or there was none
All times are game seconds (gtime): results rows and log stamps of old non-normal runs are converted as they are read.
"""
import json
import math
import os
import re
import sys
from collections import defaultdict

import gtime

RUNS = os.path.join(os.path.dirname(__file__), '..', '..', 'aisim', 'runs')

COUNTERS = [('recruits', 'shepherd_recruit'), ('arrivals', 'shepherd_at_spot'), ('lost', 'shepherd_lost'),
            ('jumps', 'shepherd_spot_jump'), ('drawn', 'wave_drawn'), ('base', 'wave_to_base'),
            ('decoy', 'wave_to_decoy'), ('elsewhere', 'wave_elsewhere'), ('to_shep', 'wave_to_shepherd')]
TIME = [('at', 'shepherd_t_atspot'), ('walk', 'shepherd_t_walk'), ('flee', 'shepherd_t_flee'),
        ('nospot', 'shepherd_t_nospot'), ('none', 'shepherd_t_none')]

T = r'^\s*([\d.]+) s0 AI\s+'
RE_REC = re.compile(T + r'shepherd of (\S+) recruited')
RE_AT = re.compile(T + r'shepherd of (\S+) at spot after')
RE_LOST = re.compile(T + r'shepherd of (\S+) lost at')
RE_JUMP = re.compile(T + r'spot of (\S+) jumps \d+ cells from (\d+),(\d+) \(origin \w+\) to (\d+),(\d+)')
RE_LAUNCH = re.compile(T + r'launch of (\S+) from (\d+),(\d+) \(previous wave (\w+)\) to (\d+),(\d+): target ([a-z_]+)'
                       r'(?:.*; origin: shepherd (\w+))?')
RE_WAVE = re.compile(T + r'wave of (\S+) (drawn to|goes to) (\d+),(\d+)( \(our decoy\)| \(our base\))?')


def dist(ax, ay, bx, by):
    return math.hypot(ax - bx, ay - by)


def game_starts(path):
    """The players' starts of the game file at path, and its gtime factor (its AI logs' stamps share it)."""
    with open(path) as fh:
        first = json.loads(fh.readline())
    return {p['name']: (p['x'], p['y']) for p in first['players']}, gtime.factor(first)


def audit_log(log_path, starts, gf=1.):
    """gf: game seconds per log stamp unit (gtime.factor of the sibling game file)."""
    me = starts.get('s0:gauntlet') or next(iter(starts.values()))
    g = defaultdict(float)
    arrived = {}
    last_jump = {}
    pending = {}  # copy -> the wave line's verdict for the launch line that follows it
    disp = defaultdict(list)
    with open(log_path, errors='replace') as fh:
        for line in fh:
            m = RE_REC.match(line)
            if m:
                arrived[m.group(2)] = False
                continue
            m = RE_AT.match(line)
            if m:
                arrived[m.group(2)] = True
                continue
            m = RE_LOST.match(line)
            if m:
                if not arrived.get(m.group(2), True):
                    g['lost_pre'] += 1
                continue
            m = RE_JUMP.match(line)
            if m:
                t = float(m.group(1)) * gf
                c = m.group(2)
                a = (int(m.group(3)), int(m.group(4)))
                b = (int(m.group(5)), int(m.group(6)))
                prev = last_jump.get(c)
                if prev and t - prev[0] <= 10 and dist(*b, *prev[1]) <= 8:
                    g['flips10'] += 1
                last_jump[c] = (t, a, b)
                continue
            m = RE_WAVE.match(line)
            if m:
                verdict = 'drawn' if m.group(3) == 'drawn to' else (
                    'base' if m.group(6) == ' (our base)' else 'decoy' if m.group(6) else 'elsewhere')
                pending[m.group(2)] = verdict
                continue
            m = RE_LAUNCH.match(line)
            if m:
                c = m.group(2)
                ox, oy, tx, ty = int(m.group(3)), int(m.group(4)), int(m.group(6)), int(m.group(7))
                target = m.group(8)
                verdict = pending.pop(c, '?')
                d = dist(tx, ty, *me) - dist(ox, oy, *me)
                if verdict == 'drawn':
                    disp['drawn'].append(d)
                elif target == 'unit_by_shepherd':
                    disp['caught'].append(d)
                if verdict == 'base':
                    disp['base'].append(d)
                    if m.group(9):
                        g['base_' + m.group(9)] += 1
                home = starts.get(c)
                if home and dist(ox, oy, *home) <= 40:
                    g['home_orig'] += 1
                    if verdict == 'base':
                        g['home_base'] += 1
                continue
    for k, v in disp.items():
        g['disp_' + k + '_sum'] = sum(v)
        g['disp_' + k + '_n'] = len(v)
    return g


def audit_run(name, seeds=None):
    folder = name if os.path.isdir(name) else os.path.join(RUNS, name)
    name = os.path.basename(os.path.normpath(folder))
    rows = [gtime.row(json.loads(l)) for l in open(os.path.join(folder, 'results.jsonl')) if l.strip()]
    if seeds:
        rows = [r for r in rows if seeds[0] <= r['seed'] <= seeds[1]]
    n = len(rows)
    sums = defaultdict(float)
    for r in rows:
        c = r['teams'][0].get('counters') or {}
        for col, key in COUNTERS + TIME:
            sums[col] += c.get(key, 0)
    launches = sums['drawn'] + sums['base'] + sums['decoy'] + sums['elsewhere']
    out = [f'{name}: {n} games']
    out.append('  per game: ' + ', '.join(f'{col} {sums[col] / max(1, n):.1f}' for col, _ in COUNTERS)
               + f', launches {launches / max(1, n):.1f}')
    minutes = sum(r.get('t', 0) for r in rows) / 60 / max(1, n)
    out.append(f'  rates: minutes {minutes:.1f}, base share {sums["base"] / max(1, launches):.3f}, drawn share '
               f'{sums["drawn"] / max(1, launches):.3f}, arrivals/recruit {sums["arrivals"] / max(1, sums["recruits"]):.2f}, '
               f'lost/recruit {sums["lost"] / max(1, sums["recruits"]):.2f}, lost/10min {10 * sums["lost"] / max(1, n) / max(1, minutes):.1f}, '
               f'base/10min {10 * sums["base"] / max(1, n) / max(1, minutes):.1f}')
    tends = sum(sums[col] for col, _ in TIME)
    if tends:
        out.append('  time: ' + ', '.join(f'{col} {100 * sums[col] / tends:.1f} %' for col, _ in TIME))
    logs = defaultdict(float)
    nl = 0
    gdir = os.path.join(folder, 'g')
    for r in rows:
        log = os.path.join(gdir, r['key'] + '-ai-s0.log')
        jl = os.path.join(gdir, r['key'] + '.jsonl')
        if not (os.path.exists(log) and os.path.exists(jl)):
            continue
        nl += 1
        for k, v in audit_log(log, *game_starts(jl)).items():
            logs[k] += v
    if nl:
        def mean(k):
            return logs['disp_' + k + '_sum'] / logs['disp_' + k + '_n'] if logs['disp_' + k + '_n'] else float('nan')
        out.append(f'  logs ({nl} games): flips10 {logs["flips10"] / nl:.1f}, lost_pre {logs["lost_pre"] / nl:.1f}, '
                   f'home_orig {logs["home_orig"] / nl:.1f}, home_base {logs["home_base"] / nl:.1f}, '
                   f'disp_drawn {mean("drawn"):+.1f} (n {logs["disp_drawn_n"] / nl:.1f}), '
                   f'disp_caught {mean("caught"):+.1f} (n {logs["disp_caught_n"] / nl:.1f}), '
                   f'disp_base {mean("base"):+.1f}')
        if any(k.startswith('base_') for k in logs):
            out.append('  base by own shepherd: ' + ', '.join(
                f'{k[5:]} {logs[k] / nl:.1f}' for k in ('base_atspot', 'base_away', 'base_nospot', 'base_none')))
    print('\n'.join(out))


if __name__ == '__main__':
    args = sys.argv[1:]
    seeds = None
    if len(args) >= 2 and args[0] == '--seeds':
        a, b = args[1].split('..')
        seeds = (int(a), int(b))
        args = args[2:]
    for run in args:
        audit_run(run, seeds)
