#!/usr/bin/env python3
"""Misevaluation scan: known pathologies of our AI, game by game, after a batch.

    python lab/gauntlet/quirks.py RUN [RUN...]         # per run: how many games show each signature, top games
    python lab/gauntlet/quirks.py --game RUN KEY        # one game: its flagged stretches as a timeline
    options: --top K (flagged games listed per run, default 15), --all (list every flagged game)

Our AI is the first player of the game header (team A, slot 0 in the benchmark runs; teammates are ignored); every
player on another team is an enemy copy, N is their number. Signatures are read from the census (every 30 s, so they
work on unlogged runs) and the result row's counters; when the game has a decision log (g/<key>-ai-s<slot>.log) the
log adds detail. A census sample stands for the 30 s after it, so a stretch of k samples lasts k/2 minutes.

Signatures and thresholds (per game; the constants below the docstring hold them):
  wood_lock      from 30 min on: units >= 240, inside >= 100 and warriors (census rock+iron+rubber, garrisons not
                 counted) <= 10, in a stretch lasting >= 5 min (gaps of <= 2 samples bridged: the census flickers at
                 the cap). Reports locked minutes (all locked samples) and the stretches. Log: STAT minutes with tree
                 gatherers <= 1 against a want >= 40, W >= 80 and armory wood < 2 (late/statlock.py), the first
                 STAT with cyc >= 90 after 30 min (no tree left within 60 cells of the armory), the share of locked
                 STAT with cyc >= 90, and the attacks launched while locked. Before 30 min the same state is an
                 early bank at the cap (s79 at N=12), counted as peon_trap.
  stuck_army     >= 60 warriors whose census army centre stays within 10 cells of where the stretch began for
                 >= 10 min, >= 50 cells from every finished building of ours, while no enemy copy goes out (an
                 out ends the stretch). Log: the attack that was running, attack-end lines in the stretch
                 (attack over / stalled / retreat / turning back / calling home), battle lines with fighting and
                 with enemy warriors near, warrior jam lines.
  warrior_jam    runs with the jam counters only (2026-09-29 on; older runs print n/a). counters warrior_jam per
                 minute alive >= 2.0 and >= 100 in the game (clusters of >= 4 blocked walking warriors within 4
                 cells, counted every 5 s; N=11 runs: median 0.2/min, p90 0.7, p99 ~10; s98's stuck army 22-108).
                 Log: jam episodes, the "jam: N warriors blocked around X,Y" lines grouped by spot (within 6 cells
                 of the episode's first line, <= 60 s apart; the log writes at most one jam line per 20 s for both
                 kinds together); episodes of >= 3 lines are reported, and one lasting >= 5 min flags the game even
                 below the rate.
  peon_trap      either the bank: inside >= 60 while warriors <= 10 and no weapon in stock, for >= 5 min (gaps of
                 1 sample bridged; wood-lock samples excluded, so a lock does not count twice; mostly a dying base
                 or a lock below the unit cap); or the packed razing: >= 150 units vanish in one census interval
                 holding a razing of our finished armory or quarters (units drop minus the lost counter's rise,
                 late/vanish.py; each interval counted once). >= 100 would flag 13-28 % of N=11-13 games: ~100
                 units vanish per game by 20 min at N=12. Log: STAT minutes with W >= want + 20 and armory iron < 2
                 and rock < 2 (idle workers with no ore to forge) and the worst W/want.
  peon_jam       as warrior_jam for peons: >= 0.5 jams per minute alive and >= 25 in the game (N=11 runs: median
                 0, p90 0.05, p99 ~0.8). Log: the episodes, each with the distance to our nearest armory (the
                 gatherers' hub) and quarters, the gatherers per resource at the time (STAT g tree, iron, rock) and
                 the gatherers the unstick re-sent meanwhile. The log names no gatherer target, so the hub
                 distance is the cross-check it allows against peons queueing to harvest behind a narrow gap.
  tower_decay    our tower count falls by >= 4 within 10 min with < 2 towers completed in that window, the
                 window starting at >= 25 min, the base (a finished quarters or armory) still standing 5 min
                 after it, and quarters + armories + towers + sites < 20 at its end (room to rebuild); reports the
                 tower sites placed in the window too. The 15-25-min attrition is left out: it matches ~98 % of
                 N=11-12 games (8-9 towers razed and ~1 completed from 15 to 25 min), so the run summary prints it
                 as a per-game mean instead.
  dry_spell      >= 30 min between enemy outs (from the start, to our out or the end) while >= 3 copies are
                 alive. Reports the longest. Common in long games (35-67 % of games > 40 min in the N=11-12 bases);
                 the known stalemates run 68-330 min.
  homeless_alive >= 10 min alive with no finished quarters or armory (after we had one): an orphan army or
                 peons wandering until they die.
  stall_churn    counters target_stalled or stall_retarget >= 3, or >= 3 "attack stalled" lines in the log.
  draw_at_cap    the game ended by timeout.
Every game line also gives result, length, N and copies out (by our out, or all for a win). The flagged list ranks
games by signature weight (wood_lock, stuck_army 3; warrior_jam, peon_jam, stall_churn, draw_at_cap 2; the rest 1),
then by flagged minutes (lock + stuck + bank + homeless + jam episodes + half the dry spell), then by seed. "long"
games last > 40 min.

Validation (2026-09-29; share of games / of long games in the bases: cur2-vs12-hv and -b N=12, st120b2-vs11-hv
and -b N=11, cur2-vs13-hv N=13):
  wood_lock      every known lock: draw-replays-vs11-hv s315 (56-224 and 310-360 min, log 58-224, 316-360);
                 late-replays-vs12-hv s60 (50-90, log 50-92) and s63 (96-154, log 86-154); cur2-vs12-hv s63, s60;
                 cur2-vs12-hv-b s399 (80-127); st120b2-vs11-hv s188 (40-102), s135, s122, s127, s97; -b s228, s276,
                 s315. Not flagged: s64 (the saved near-miss). 0-2.5 % of games.
  stuck_army     s98 in draw-replays-vs11-hv and st120b2-vs11-hv (306 min from 48 min at 177,59-71, 0 attack-end
                 lines, 0 battle lines with enemy warriors near). 0 games in the N=12-13 bases.
  peon_trap      2.5-16 % of games (the packed armories of s60 162, s399 169, s64 182, s276 188, s328 191).
  tower_decay    1-12 % of games, 46-78 % of long games.
  dry_spell      0.5-7.5 % of games; the long wins s63 81 min, s399 102, s228 89; the draws s315 328.
  homeless_alive 0-1.5 % (s13 at N=12: 21-37 min).
  stall_churn    0 in the bases; weapon-sync-vs11-hv s97 (236 stalls with the army stuck 300 min).
  warrior_jam    play-jam-s98 (21.9/min, 37 jam lines at 178,52-53 from 48 min); 0.5-2 % of the first runs with the
                 counters (weapon-sync-vs11-hv, stall-peons-vs11-hv and -b). peon_jam 2-3 % of them (s9, s93, s192
                 in more than one config).
Scan speed: 2-3 s per 200-game unlogged run with the files cached, ~10 s cold.
"""
import bisect
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')

# ---------------------------------------------------------------------------------------------------- thresholds
SAMPLE = 30.0            # census interval (s)
LONG_GAME = 2400.0       # "long" games last more than this (s)

LOCK_UNITS = 240         # wood_lock: at the unit cap ...
LOCK_INSIDE = 100        # ... with this many inside buildings ...
LOCK_WARRIORS = 10       # ... and at most this many warriors
LOCK_MIN = 300.0         # longest stretch (s)
LOCK_GAP = 2             # bridged gap (samples)
LOCK_FROM = 1800.0       # samples from this time on (s); an earlier bank at the cap is a peon trap

STUCK_WARRIORS = 60      # stuck_army: army size
STUCK_RADIUS = 10.0      # cells the centre may wander from where the stretch began
STUCK_AWAY = 50.0        # cells from our nearest finished building
STUCK_MIN = 600.0        # stretch (s)

TRAP_INSIDE = 60         # peon_trap bank: inside ...
TRAP_WARRIORS = 10       # ... with at most this many warriors and no weapon in stock
TRAP_MIN = 300.0         # stretch (s)
TRAP_GAP = 1
TRAP_VANISH = 150        # packed razing: units vanishing in one census interval
TRAP_LOG_EXCESS = 20     # log: W >= want + this, with iron < 2 and rock < 2

JAM_KEYS = ('peon_blocked', 'warrior_blocked', 'peon_jam', 'warrior_jam')  # counters of runs since 2026-09-29
WJAM_RATE = 2.0          # warrior_jam: jam samples per minute alive ...
WJAM_TOTAL = 100         # ... and at least this many in the game
PJAM_RATE = 0.5          # peon_jam: the same for peons
PJAM_TOTAL = 25
JAM_SPOT = 6.0           # log: jam lines within this many cells of an episode's first line are one spot ...
JAM_GAP = 60.0           # ... when at most this many s apart (lines come at most every 20 s)
JAM_LINES = 3            # an episode of at least this many lines is reported
JAM_PERSIST = 300.0      # an episode lasting this long (s) flags the game even below the rate

TOWER_DROP = 4           # tower_decay: fall in towers ...
TOWER_WINDOW = 600.0     # ... within this window (s) ...
TOWER_BUILT = 2          # ... with fewer completions than this
TOWER_FROM = 1500.0      # window start (s)
TOWER_BASE_AFTER = 300.0  # base still standing this long after the window (s)
BUILDING_CAP = 20

DRY_MIN = 1800.0         # dry_spell: s without an out ...
DRY_ALIVE = 3            # ... while at least this many copies are alive

HOMELESS_MIN = 600.0     # homeless_alive (s)

CHURN = 3                # stall_churn: stalls

SIGS = ['wood_lock', 'stuck_army', 'warrior_jam', 'peon_trap', 'peon_jam', 'tower_decay', 'dry_spell',
        'homeless_alive', 'stall_churn', 'draw_at_cap']
JAM_SIGS = ('warrior_jam', 'peon_jam')  # only in runs whose counters have them
# ranking weight of each signature: the rare, clearly silly ones count more than the ones common in long games
SEVERITY = {'wood_lock': 3, 'stuck_army': 3, 'warrior_jam': 2, 'peon_jam': 2, 'stall_churn': 2, 'draw_at_cap': 2,
            'homeless_alive': 1, 'peon_trap': 1, 'tower_decay': 1, 'dry_spell': 1}

CENSUS_FIELDS = ('t', 'alive', 'units', 'peons', 'rock', 'iron', 'rubber', 'inside', 'garrison', 'quarters',
                 'armories', 'towers', 'sites', 'kills', 'lost', 'strength', 'armyX', 'armyY', 'stockRock',
                 'stockIron', 'stockRubber')
(T, ALIVE, UNITS, PEONS, ROCK, IRON, RUBBER, INSIDE, GARRISON, QUARTERS, ARMORIES, TOWERS, SITES, KILLS, LOST,
 STRENGTH, ARMYX, ARMYY, STOCKROCK, STOCKIRON, STOCKRUBBER) = range(len(CENSUS_FIELDS))


def warriors(c):
    return c[ROCK] + c[IRON] + c[RUBBER]


def base(c):
    return c[QUARTERS] + c[ARMORIES]


def mins(t):
    return t / 60.0


# ---------------------------------------------------------------------------------------------------------- reading

def read_rows(run):
    rows = []
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except ValueError:
                pass  # a row being written by a running batch
    return rows


def read_game(run, row):
    """Our census rows, our building events and every slot's first out, from g/<key>.jsonl."""
    key = row['key']
    path = os.path.join(ROOT, run, 'g', key + '.jsonl')
    teams = row.get('teams') or []
    g = {'run': run, 'key': key, 'seed': row.get('seed', 0), 'result': row.get('result'), 'end': row.get('end'),
         't_end': float(row.get('t') or 0.0), 'N': max(1, (row.get('slots') or 2) - 1), 'slot': 0, 'enemies': [],
         'census': [], 'bld': [], 'outs': {}, 'counters': (teams[0].get('counters') or {}) if teams else {},
         'failed': row.get('result') is None or not os.path.exists(path)}
    if g['failed']:
        return g
    census = g['census']
    bld = g['bld']
    outs = g['outs']
    tag = ',"s":0,'
    with open(path, encoding='utf-8') as f:
        for line in f:
            k = line[7:11]
            if k == 'deat':
                continue
            if k == 'cens':
                if tag in line[:48]:
                    e = json.loads(line)
                    census.append(tuple(e.get(c, 0) for c in CENSUS_FIELDS))
            elif k in ('buil', 'raze', 'plac'):
                e = json.loads(line)
                if e['s'] == g['slot']:
                    bld.append((e['t'], e['ev'], e['b'], e['x'], e['y'], e.get('site', 0)))
            elif k == 'out"' or k == 'coll':
                e = json.loads(line)
                outs.setdefault(e['s'], e['t'])
            elif k == 'game':
                e = json.loads(line)
                players = e['players']
                g['slot'] = players[0]['s']
                team = players[0]['team']
                g['enemies'] = [p['s'] for p in players if p['team'] != team]
                g['N'] = len(g['enemies'])
                tag = ',"s":%d,' % g['slot']
    # our census while we are alive
    a_out = outs.get(g['slot'])
    g['a_out'] = a_out
    g['census'] = [c for c in census if c[ALIVE] and (a_out is None or c[T] <= a_out)]
    g['e_outs'] = sorted(outs[s] for s in g['enemies'] if s in outs)
    limit = a_out if a_out is not None and g['result'] != 'win' else g['t_end'] + 1
    g['copies_out'] = sum(1 for t in g['e_outs'] if t <= limit)
    g['alive_s'] = min(limit, g['t_end'])
    g['jam_known'] = any(k in g['counters'] for k in JAM_KEYS)
    logp = os.path.join(ROOT, run, 'g', '%s-ai-s%d.log' % (key, g['slot']))
    g['log_path'] = logp if os.path.exists(logp) else None
    return g


STAT_RE = {
    'q': re.compile(r'Q(\d+)\+(\d+) A(\d+)\+(\d+) T(\d+)\+(\d+)'),
    'peons': re.compile(r'peons=(\d+) \(idle (\d+) bld (\d+) tr (\d+) g (\d+)/(\d+),(\d+)/(\d+),(\d+)/(\d+),(\d+)/(\d+)\)'),
    'W': re.compile(r' W=(\d+)/(\d+)'),
    'res': re.compile(r' res=(\d+),(\d+),(\d+),(\d+)'),
    'cyc': re.compile(r' cyc=(\d+)/(\d+)'),
    'mode': re.compile(r' mode=(\w+) army=(\d+) atk=(\d+)'),
    'thr': re.compile(r' thr=(\d+)/([\d.]+)'),
}
LOG_LINE = re.compile(r'^\s*([\d.]+) s\d+ (\w+)\s+(.*)$')
BATTLE = re.compile(r'battle: army (\d+) \(([\d.]+), (\d+) far, (\d+) fighting, (\d+) stunned\) at (\d+),(\d+) \S+ '
                    r'(-?\d+)m to target; near: (\d+) warriors')
EVENT_KEYS = ('attack with', 'attack over', 'attack stalled', 'retreat', 'turning back', 'calling the army home',
              'muster')
JAM = re.compile(r'jam: (\d+) (\w+) blocked around (\d+),(\d+)')
ATTACK_WITH = re.compile(r'attack with ([\d.]+)')
UNSTUCK = re.compile(r'(\d+) stuck gatherers re-sent so far')


def read_log(path):
    """STAT samples, attack events, battle lines, jam lines and the unstick count of one decision log."""
    stats = []
    events = []
    battles = []
    jams = []  # (t, kind 'warriors' | 'peons', units, x, y)
    unstuck = []  # (t, gatherers re-sent so far)
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = LOG_LINE.match(line)
            if not m:
                continue
            t = float(m.group(1))
            topic = m.group(2)
            text = m.group(3)
            if topic == 'STAT':
                s = {'t': t, 'text': text.strip()}
                for k, rx in STAT_RE.items():
                    mm = rx.search(text)
                    if mm:
                        s[k] = mm.groups()
                stats.append(s)
            elif topic == 'AI':
                if text.startswith('battle: '):
                    mm = BATTLE.match(text)
                    if mm:
                        b = mm.groups()
                        battles.append((t, int(b[0]), int(b[3]), int(b[5]), int(b[6]), int(b[7]), int(b[8])))
                elif text.startswith('jam: '):
                    mm = JAM.match(text)
                    if mm:
                        jams.append((t, mm.group(2), int(mm.group(1)), int(mm.group(3)), int(mm.group(4))))
                elif 'stuck gatherers re-sent' in text:
                    mm = UNSTUCK.search(text)
                    if mm:
                        unstuck.append((t, int(mm.group(1))))
                else:
                    for k in EVENT_KEYS:
                        if text.startswith(k):
                            events.append((t, k, text.strip()))
                            break
    return {'stats': stats, 'events': events, 'battles': battles, 'jams': jams, 'unstuck': unstuck}


# ---------------------------------------------------------------------------------------------------------- helpers

def stretches(census, pred, gap):
    """Runs of census samples where pred holds, bridging up to gap failing samples; [(i0, i1, n_true)]."""
    out = []
    start = last = None
    n = 0
    for i, c in enumerate(census):
        if pred(c):
            if start is not None and i - last - 1 > gap:
                out.append((start, last, n))
                start = None
            if start is None:
                start = i
                n = 0
            last = i
            n += 1
    if start is not None:
        out.append((start, last, n))
    return out


def span(census, i0, i1):
    return census[i1][T] - census[i0][T] + SAMPLE


def standing_buildings(bld):
    """Timeline of our finished buildings: sorted [(t, +1/-1, (b, x, y))]."""
    ev = []
    for t, kind, b, x, y, site in bld:
        if kind == 'built' and b in ('quarters', 'armory', 'tower'):
            ev.append((t, 1, (b, x, y)))
        elif kind == 'razed' and not site and b in ('quarters', 'armory', 'tower'):
            ev.append((t, -1, (b, x, y)))
    ev.sort(key=lambda e: (e[0], -e[1]))
    return ev


def buildings_at(ev, t):
    s = {}
    for te, d, k in ev:
        if te > t:
            break
        s[k] = s.get(k, 0) + d
        if s[k] <= 0:
            del s[k]
    return list(s)


def fmt_span(a, b):
    return '%.0f-%.0f' % (mins(a), mins(b))


# ---------------------------------------------------------------------------------------------------------- signatures

def wood_locked(c):
    return (c[T] >= LOCK_FROM and c[UNITS] >= LOCK_UNITS and c[INSIDE] >= LOCK_INSIDE
            and warriors(c) <= LOCK_WARRIORS)


def sig_wood_lock(g, log):
    C = g['census']
    total = sum(1 for c in C if wood_locked(c)) * SAMPLE
    runs = [(C[a][T], C[b][T] + SAMPLE) for a, b, n in stretches(C, wood_locked, LOCK_GAP)
            if span(C, a, b) >= LOCK_MIN]
    if not runs:
        return None
    r = {'minutes': total / 60.0, 'spans': runs, 'longest': max(b - a for a, b in runs) / 60.0}
    desc = 'wood_lock %.0fm (%s)' % (r['minutes'], ' '.join(fmt_span(a, b) for a, b in runs))
    if log:
        lk = []
        first_sat = None
        inside = sat = 0
        for s in log['stats']:
            cyc = int(s['cyc'][0]) if 'cyc' in s else 0
            if cyc >= 90 and s['t'] >= LOCK_FROM and first_sat is None:
                first_sat = s['t']
            if any(a <= s['t'] < b for a, b in runs):
                inside += 1
                sat += cyc >= 90
            if 'peons' in s and 'W' in s and 'res' in s:
                tree, want = int(s['peons'][4]), int(s['peons'][5])
                if tree <= 1 and want >= 40 and int(s['W'][0]) >= 80 and int(s['res'][0]) < 2:
                    lk.append(s['t'])
        r['log_minutes'] = len(lk) * SAMPLE / 60.0
        r['log_spans'] = merge_times(lk, 60.0)
        r['first_saturated'] = first_sat
        r['saturated_share'] = sat / inside if inside else None
        # attacks launched while locked: the empty army musters and turns back (s63, s315)
        att = sorted(float(m.group(1)) for e in log['events'] if e[1] == 'attack with'
                     and any(a <= e[0] < b for a, b in runs) for m in [ATTACK_WITH.match(e[2])] if m)
        r['locked_attacks'] = att
        desc += ' [log %.0fm %s%s%s%s]' % (
            r['log_minutes'], ' '.join(fmt_span(a, b + SAMPLE) for a, b in r['log_spans']),
            '' if first_sat is None else ', cyc>=90 from %.0f' % mins(first_sat),
            '' if not inside else ', cyc>=90 in %.0f%% of locked STAT' % (100.0 * sat / inside),
            '' if not att else ', %d attacks launched while locked (median strength %.1f)' % (
                len(att), att[len(att) // 2]))
    r['desc'] = desc
    r['weight'] = r['minutes']
    return r


def merge_times(ts, gap):
    out = []
    for t in ts:
        if out and t - out[-1][1] <= gap:
            out[-1][1] = t
        else:
            out.append([t, t])
    return [(a, b) for a, b in out]


def sig_stuck_army(g, log):
    C = g['census']
    ev = standing_buildings(g['bld'])
    e_outs = g['e_outs']
    runs = []
    i = 0
    n = len(C)

    def ok(c):
        return warriors(c) >= STUCK_WARRIORS and c[ARMYX] >= 0

    def away(c):
        bs = buildings_at(ev, c[T])
        if not bs:
            return True
        d2 = min((c[ARMYX] - x) ** 2 + (c[ARMYY] - y) ** 2 for _, x, y in bs)
        return d2 > STUCK_AWAY ** 2

    while i < n:
        c0 = C[i]
        if not ok(c0) or not away(c0):
            i += 1
            continue
        j = i
        while j + 1 < n:
            c = C[j + 1]
            if c[T] - C[j][T] > SAMPLE + 1 or not ok(c):
                break
            if (c[ARMYX] - c0[ARMYX]) ** 2 + (c[ARMYY] - c0[ARMYY]) ** 2 > STUCK_RADIUS ** 2:
                break
            k = bisect.bisect_right(e_outs, c0[T])
            if k < len(e_outs) and e_outs[k] <= c[T]:
                break
            if not away(c):
                break
            j += 1
        if span(C, i, j) >= STUCK_MIN:
            runs.append((i, j))
            i = j + 1
        else:
            i += 1
    if not runs:
        return None
    spans = [(C[a][T], C[b][T] + SAMPLE) for a, b in runs]
    total = sum(b - a for a, b in spans)
    size = max(warriors(C[a]) for a, _ in runs)
    parts = []
    for a, b in runs:
        parts.append('%s at %d,%d (%d warriors)' % (fmt_span(C[a][T], C[b][T] + SAMPLE), C[a][ARMYX], C[a][ARMYY],
                                                   warriors(C[a])))
    r = {'minutes': total / 60.0, 'spans': spans, 'size': size, 'where': [(C[a][ARMYX], C[a][ARMYY]) for a, _ in runs]}
    desc = 'stuck_army %.0fm (%s)' % (r['minutes'], '; '.join(parts))
    if log:
        notes = []
        for (t0, t1) in spans:
            att = [e for e in log['events'] if e[1] == 'attack with' and e[0] <= t0]
            ends = [e for e in log['events'] if t0 <= e[0] <= t1 and e[1] in
                    ('attack over', 'attack stalled', 'retreat', 'turning back', 'calling the army home')]
            bl = [b for b in log['battles'] if t0 <= b[0] <= t1]
            fight = sum(1 for b in bl if b[2] > 0)
            warn = sum(1 for b in bl if b[6] > 0)
            jl = [j for j in log['jams'] if t0 <= j[0] <= t1 and j[1].startswith('warrior')]
            big = max(jl, key=lambda j: (j[2], -j[0])) if jl else None
            notes.append('%s: attack from %s, %d end/stall lines, battle lines %d (fighting %d, enemy warriors near %d)%s'
                         % (fmt_span(t0, t1), '%.0f' % mins(att[-1][0]) if att else '-', len(ends), len(bl), fight,
                            warn, '' if not jl else ', %d warrior jam lines (up to %d at %d,%d)' % (
                                len(jl), big[2], big[3], big[4])))
        r['log'] = notes
        desc += ' [log ' + '; '.join(notes) + ']'
    r['desc'] = desc
    r['weight'] = r['minutes']
    return r


def sig_peon_trap(g, log):
    C = g['census']

    def bank(c):
        return (c[INSIDE] >= TRAP_INSIDE and warriors(c) <= TRAP_WARRIORS and not wood_locked(c)
                and c[STOCKROCK] + c[STOCKIRON] + c[STOCKRUBBER] == 0)
    banks = [(C[a][T], C[b][T] + SAMPLE, max(C[k][INSIDE] for k in range(a, b + 1)))
             for a, b, n in stretches(C, bank, TRAP_GAP) if span(C, a, b) >= TRAP_MIN]
    ts = [c[T] for c in C]
    seen = set()
    razings = []
    for t, kind, b, x, y, site in g['bld']:
        if kind != 'razed' or site or b not in ('armory', 'quarters'):
            continue
        i = bisect.bisect_right(ts, t) - 1
        if i < 0 or i + 1 >= len(C) or i in seen:
            continue
        seen.add(i)
        cb, ca = C[i], C[i + 1]
        v = max(0, (cb[UNITS] - ca[UNITS]) - (ca[LOST] - cb[LOST]))
        razings.append((t, b, v, cb[INSIDE], warriors(cb)))
    packed = [z for z in razings if z[2] >= TRAP_VANISH]
    vanished = sum(z[2] for z in razings)
    if not banks and not packed:
        return None
    parts = []
    if banks:
        parts.append('bank %s' % ' '.join('%s (%d in)' % (fmt_span(a, b), m) for a, b, m in banks))
    if packed:
        parts.append('packed %s' % ' '.join('%d vanished in %s at %.0f' % (z[2], z[1], mins(z[0])) for z in packed))
    r = {'banks': banks, 'packed': packed, 'vanished': vanished, 'minutes': sum(b - a for a, b, _ in banks) / 60.0}
    desc = 'peon_trap %s (vanished %d)' % ('; '.join(parts), vanished)
    if log:
        trap = []
        for s in log['stats']:
            if 'W' in s and 'res' in s:
                w, want = int(s['W'][0]), int(s['W'][1])
                if w >= want + TRAP_LOG_EXCESS and int(s['res'][2]) < 2 and int(s['res'][1]) < 2:
                    trap.append((s['t'], w, want))
        r['log_minutes'] = len(trap) * SAMPLE / 60.0
        if trap:
            mx = max(trap, key=lambda z: z[1] - z[2])
            desc += ' [log %.1fm idle bank with no ore, worst W=%d/%d at %.0f]' % (r['log_minutes'], mx[1], mx[2],
                                                                                  mins(mx[0]))
    r['desc'] = desc
    r['weight'] = r['minutes']
    return r


def sig_tower_decay(g, log):
    C = g['census']
    ts = [c[T] for c in C]
    done = sorted(t for t, kind, b, x, y, site in g['bld'] if kind == 'built' and b == 'tower')
    placed = sorted(t for t, kind, b, x, y, site in g['bld'] if kind == 'placed' and b == 'tower')
    wins = []
    for i, c in enumerate(C):
        if c[T] < TOWER_FROM:
            continue
        best = None
        for j in range(i + 1, len(C)):
            d = C[j]
            if d[T] - c[T] > TOWER_WINDOW:
                break
            drop = c[TOWERS] - d[TOWERS]
            if drop < TOWER_DROP:
                continue
            nb = bisect.bisect_right(done, d[T]) - bisect.bisect_right(done, c[T])
            if nb >= TOWER_BUILT or d[QUARTERS] + d[ARMORIES] + d[TOWERS] + d[SITES] >= BUILDING_CAP:
                continue
            k = bisect.bisect_left(ts, d[T] + TOWER_BASE_AFTER)
            after = C[k] if k < len(C) else (C[-1] if g['result'] == 'win' or g['end'] == 'timeout' else None)
            if after is None or base(after) == 0:
                continue
            if best is None or drop > best[2]:
                best = (i, j, drop, nb)
        if best:
            wins.append(best)
    if not wins:
        return None
    # merge overlapping windows into episodes
    eps = []
    for i, j, drop, nb in wins:
        if eps and i <= eps[-1][1]:
            e = eps[-1]
            eps[-1] = (e[0], max(e[1], j), max(e[2], drop))
        else:
            eps.append((i, j, drop))
    out = []
    for i, j, drop in eps:
        t0, t1 = C[i][T], C[j][T]
        nb = bisect.bisect_right(done, t1) - bisect.bisect_right(done, t0)
        npl = bisect.bisect_right(placed, t1) - bisect.bisect_right(placed, t0)
        out.append((t0, t1, C[i][TOWERS], min(C[k][TOWERS] for k in range(i, j + 1)), nb, npl))
    r = {'episodes': out, 'drop': max(e[2] - e[3] for e in out)}
    r['desc'] = 'tower_decay %s' % ' '.join('%s (%d->%d towers, %d placed, %d completed)' % (
        fmt_span(a, b), f, to, npl, nb) for a, b, f, to, nb, npl in out)
    r['weight'] = 0.0
    return r


def jam_rate(g, kind):
    """(jam samples, blocked-unit samples) per minute alive, from the counters."""
    m = max(g['alive_s'], 60.0) / 60.0
    return g['counters'].get(kind + '_jam', 0) / m, g['counters'].get(kind + '_blocked', 0) / m


def jam_episodes(log, kind):
    """Jam lines of one kind ('warrior' | 'peon') grouped by spot: an episode takes the lines within JAM_SPOT cells
    of its first line and at most JAM_GAP s after its previous one; [dict(t0, t1, x, y, lines, max)]."""
    done = []
    open_ = []
    for t, k, n, x, y in log['jams']:
        if not k.startswith(kind):
            continue
        for e in open_:
            if t - e['t1'] > JAM_GAP:
                done.append(e)
        open_ = [e for e in open_ if t - e['t1'] <= JAM_GAP]
        for e in open_:
            if (x - e['x']) ** 2 + (y - e['y']) ** 2 <= JAM_SPOT ** 2:
                e['t1'] = t
                e['lines'] += 1
                e['max'] = max(e['max'], n)
                break
        else:
            open_.append({'t0': t, 't1': t, 'x': x, 'y': y, 'lines': 1, 'max': n})
    done.extend(open_)
    done.sort(key=lambda e: (e['t0'], e['x'], e['y']))
    return [e for e in done if e['lines'] >= JAM_LINES]


def peon_jam_context(g, log, e):
    """What the log and the game file say about a peon jam spot: the nearest armory or quarters of ours (the
    gatherers' hub), the gatherers per resource at the time, and gatherers the unstick re-sent meanwhile. The log
    gives no per-gatherer target, so the hub distance is the cross-check available."""
    standing = buildings_at(standing_buildings(g['bld']), e['t0'])
    parts = []
    for kind in ('armory', 'quarters'):
        bs = sorted((round(((x - e['x']) ** 2 + (y - e['y']) ** 2) ** 0.5), x, y) for b, x, y in standing if b == kind)
        if bs:
            parts.append('%d cells from our %s at %d,%d' % (bs[0][0], kind, bs[0][1], bs[0][2]))
    st = [s for s in log['stats'] if s['t'] <= e['t0'] + SAMPLE and 'peons' in s]
    if st:
        p = st[-1]['peons']
        parts.append('gatherers tree %s/%s iron %s/%s rock %s/%s' % p[4:10])
    before = [u for t, u in log['unstuck'] if t <= e['t0']]
    after = [u for t, u in log['unstuck'] if t <= e['t1'] + 20]
    if after:
        parts.append('unstick re-sent %d' % (after[-1] - (before[-1] if before else 0)))
    return ', '.join(parts)


def sig_jam(g, log, kind, rate_min, total_min):
    if not g['jam_known']:
        return None
    jams = g['counters'].get(kind + '_jam', 0)
    rate, blocked = jam_rate(g, kind)
    eps = jam_episodes(log, kind) if log else []
    persist = [e for e in eps if e['t1'] - e['t0'] >= JAM_PERSIST]
    if not (rate >= rate_min and jams >= total_min) and not persist:
        return None
    r = {'rate': rate, 'jams': jams, 'blocked': blocked, 'episodes': eps,
         'minutes': sum(e['t1'] - e['t0'] + 20 for e in eps) / 60.0}
    desc = '%s_jam %.2f/min (%d jams, blocked %.1f/min)' % (kind, rate, jams, blocked)
    if log:
        top = sorted(eps, key=lambda e: (-(e['t1'] - e['t0']), e['t0']))[:3]
        notes = []
        for e in sorted(top, key=lambda e: e['t0']):
            s = 'at %d,%d %s (%d lines, up to %d)' % (e['x'], e['y'], fmt_span(e['t0'], e['t1'] + 20), e['lines'], e['max'])
            if kind == 'peon':
                s += ' ' + peon_jam_context(g, log, e)
            notes.append(s)
        r['notes'] = notes
        desc += ' [log %d episodes >= %d lines%s]' % (len(eps), JAM_LINES, (': ' + '; '.join(notes)) if notes else '')
    r['desc'] = desc
    r['weight'] = r['minutes']
    return r


def sig_warrior_jam(g, log):
    return sig_jam(g, log, 'warrior', WJAM_RATE, WJAM_TOTAL)


def sig_peon_jam(g, log):
    return sig_jam(g, log, 'peon', PJAM_RATE, PJAM_TOTAL)


def sig_dry_spell(g, log):
    end = g['a_out'] if g['a_out'] is not None else g['t_end']
    outs = [t for t in g['e_outs'] if t <= end]
    best = None
    prev = 0.0
    for k, t in enumerate(outs + [end]):
        alive = g['N'] - k
        if alive >= DRY_ALIVE and t - prev >= DRY_MIN and (best is None or t - prev > best[1] - best[0]):
            best = (prev, t, alive)
        prev = t
    if best is None:
        return None
    r = {'span': best, 'minutes': (best[1] - best[0]) / 60.0}
    r['desc'] = 'dry_spell %.0fm (%s, %d copies alive)' % (r['minutes'], fmt_span(best[0], best[1]), best[2])
    r['weight'] = r['minutes'] / 2.0
    return r


def sig_homeless_alive(g, log):
    C = g['census']
    had = False
    runs = []
    start = None
    for c in C:
        if base(c) > 0:
            had = True
            if start is not None:
                runs.append((start, c[T], True))
                start = None
        elif had and start is None:
            start = c[T]
    if start is not None:
        runs.append((start, g['a_out'] if g['a_out'] is not None else C[-1][T] + SAMPLE, False))
    runs = [r for r in runs if r[1] - r[0] >= HOMELESS_MIN]
    if not runs:
        return None
    r = {'spans': runs, 'minutes': max(b - a for a, b, _ in runs) / 60.0}
    r['desc'] = 'homeless_alive %.0fm (%s)' % (r['minutes'], ' '.join(
        fmt_span(a, b) + (' then rebuilt' if rebuilt else '') for a, b, rebuilt in runs))
    r['weight'] = r['minutes']
    return r


def sig_stall_churn(g, log):
    st = g['counters'].get('target_stalled', 0)
    rt = g['counters'].get('stall_retarget', 0)
    lines = sum(1 for e in log['events'] if e[1] == 'attack stalled') if log else 0
    if max(st, rt, lines) < CHURN:
        return None
    r = {'stalled': st, 'retarget': rt, 'lines': lines}
    r['desc'] = 'stall_churn (target_stalled %d, stall_retarget %d%s)' % (st, rt, ', log %d' % lines if log else '')
    r['weight'] = 0.0
    return r


def sig_draw_at_cap(g, log):
    if g['end'] != 'timeout':
        return None
    return {'desc': 'draw_at_cap (%s at %.0f min)' % (g['result'], mins(g['t_end'])), 'weight': 0.0}


DETECT = {'wood_lock': sig_wood_lock, 'stuck_army': sig_stuck_army, 'warrior_jam': sig_warrior_jam,
          'peon_trap': sig_peon_trap, 'peon_jam': sig_peon_jam, 'tower_decay': sig_tower_decay,
          'dry_spell': sig_dry_spell, 'homeless_alive': sig_homeless_alive, 'stall_churn': sig_stall_churn,
          'draw_at_cap': sig_draw_at_cap}


def tower_attrition(g):
    """Our towers razed and completed from 15 to 25 min (the universal attrition tower_decay leaves out)."""
    if g['a_out'] is not None and g['a_out'] < 900:
        return None
    razed = sum(1 for t, kind, b, x, y, site in g['bld'] if kind == 'razed' and b == 'tower' and not site
                and 900 < t <= 1500)
    built = sum(1 for t, kind, b, x, y, site in g['bld'] if kind == 'built' and b == 'tower' and 900 < t <= 1500)
    return razed, built


def scan_game(run, row, with_log=True):
    g = read_game(run, row)
    if g['failed']:
        g['flags'] = {}
        return g
    log = read_log(g['log_path']) if with_log and g['log_path'] else None
    g['has_log'] = log is not None
    g['flags'] = {}
    for s in SIGS:
        r = DETECT[s](g, log)
        if r:
            g['flags'][s] = r
    g['attrition'] = tower_attrition(g)
    g['census'] = None  # keep the result small
    g['bld'] = None
    return g


# ---------------------------------------------------------------------------------------------------------- reports

def game_line(g):
    res = {'win': 'W', 'loss': 'L', 'draw': 'D'}.get(g['result'], '?')
    head = '%-8s %s %5.1fm N=%-2d out %2d/%-2d' % (g['key'], res, mins(g['t_end']), g['N'], g['copies_out'], g['N'])
    return head + ': ' + ', '.join(g['flags'][s]['desc'] for s in SIGS if s in g['flags'])


def rank_key(g):
    return (-sum(SEVERITY[s] for s in g['flags']), -sum(f['weight'] for f in g['flags'].values()), g['seed'], g['key'])


def summary(runs, top, show_all):
    for run in runs:
        rows = read_rows(run)
        gs = [scan_game(run, r) for r in rows]
        ok = [g for g in gs if not g['failed']]
        long_ = [g for g in ok if g['t_end'] > LONG_GAME]
        ns = sorted({g['N'] for g in ok})
        wins = sum(1 for g in ok if g['result'] == 'win')
        draws = sum(1 for g in ok if g['result'] == 'draw')
        logged = sum(1 for g in ok if g.get('has_log'))
        print('%s: %d games (N=%s), %d long (> %.0f min), wins %d, draws %d, failed %d, logged %d' % (
            run, len(ok), ','.join(str(n) for n in ns), len(long_), mins(LONG_GAME), wins, draws,
            len(gs) - len(ok), logged))
        table = [['signature', 'games', 'share', 'long', 'long share']]
        known = [g for g in ok if g['jam_known']]
        known_long = [g for g in long_ if g['jam_known']]
        for s in SIGS:
            base_, base_long = (known, known_long) if s in JAM_SIGS else (ok, long_)
            if not base_:
                table.append([s, 'n/a', '(no jam counters)', '', ''])
                continue
            k = sum(1 for g in base_ if s in g['flags'])
            kl = sum(1 for g in base_long if s in g['flags'])
            table.append([s, str(k), pct(k, len(base_)), str(kl), pct(kl, len(base_long))])
        any_ = sum(1 for g in ok if g['flags'])
        table.append(['any', str(any_), pct(any_, len(ok)), str(sum(1 for g in long_ if g['flags'])),
                      pct(sum(1 for g in long_ if g['flags']), len(long_))])
        print_table(table, '  ')
        if known:
            for kind in ('warrior', 'peon'):
                rs = sorted(jam_rate(g, kind)[0] for g in known)
                bs = sorted(jam_rate(g, kind)[1] for g in known)
                print('  %s jams per minute alive: median %.2f, p90 %.2f, max %.2f (blocked median %.1f, p90 %.1f)%s'
                      % (kind, quantile(rs, .5), quantile(rs, .9), rs[-1], quantile(bs, .5), quantile(bs, .9),
                         '' if len(known) == len(ok) else ' over %d games with jam counters' % len(known)))
        att = [g['attrition'] for g in ok if g.get('attrition')]
        if att:
            print('  towers 15-25 min (games alive at 15 min): razed %.1f, completed %.1f per game' % (
                sum(a[0] for a in att) / len(att), sum(a[1] for a in att) / len(att)))
        flagged = sorted([g for g in ok if g['flags']], key=rank_key)
        if not show_all:
            flagged = flagged[:top]
        if flagged:
            print('  flagged games (by signature weight, then flagged minutes):')
            for g in flagged:
                print('    ' + game_line(g))
        print()


def quantile(xs, p):
    return xs[int(p * (len(xs) - 1))] if xs else float('nan')


def pct(k, n):
    return '%.1f%%' % (100.0 * k / n) if n else '-'


def print_table(rows, indent=''):
    widths = [max(len(r[i]) for r in rows) for i in range(len(rows[0]))]
    for r in rows:
        print(indent + '  '.join(c.ljust(w) for c, w in zip(r, widths)).rstrip())


# ---------------------------------------------------------------------------------------------------------- detail

def census_line(c):
    return ('units %3d peons %3d warriors %3d inside %3d garrison %2d Q%d A%d T%d sites %d army %s stock %d' % (
        c[UNITS], c[PEONS], warriors(c), c[INSIDE], c[GARRISON], c[QUARTERS], c[ARMORIES], c[TOWERS], c[SITES],
        '%d,%d' % (c[ARMYX], c[ARMYY]) if c[ARMYX] >= 0 else '-', c[STOCKROCK] + c[STOCKIRON] + c[STOCKRUBBER]))


def windows_of(sig, f):
    if sig in ('wood_lock', 'stuck_army'):
        return list(f['spans'])
    if sig == 'peon_trap':
        return [(a, b) for a, b, _ in f['banks']] + [(z[0] - 60, z[0] + 60) for z in f['packed']]
    if sig == 'tower_decay':
        return [e[:2] for e in f['episodes']]
    if sig == 'dry_spell':
        return [f['span'][:2]]
    if sig == 'homeless_alive':
        return [(a, b) for a, b, _ in f['spans']]
    if sig in JAM_SIGS:
        top = sorted(f['episodes'], key=lambda e: (-(e['t1'] - e['t0']), e['t0']))[:3]
        return sorted((e['t0'], e['t1'] + 20) for e in top)
    return []


def detail(run, key):
    rows = [r for r in read_rows(run) if r['key'] == key]
    if not rows:
        print('no game %s in %s' % (key, run))
        return
    row = rows[0]
    g = read_game(run, row)
    if g['failed']:
        print('%s %s: failed game (%s)' % (run, key, row.get('problem')))
        return
    log = read_log(g['log_path']) if g['log_path'] else None
    C = g['census']
    if not C:
        print('%s %s: no census of ours' % (run, key))
        return
    flags = {}
    for s in SIGS:
        r = DETECT[s](g, log)
        if r:
            flags[s] = r
    g['flags'] = flags
    print('%s %s' % (run, game_line(g)))
    print('  decision log: %s' % (os.path.relpath(g['log_path']) if g['log_path'] else 'none'))
    print('  enemy outs (min): %s' % ' '.join('%.0f' % mins(t) for t in g['e_outs']))
    ours = [e for e in g['bld'] if e[2] in ('armory', 'quarters') and e[1] in ('built', 'razed') and not e[5]]
    print('  our quarters/armories: %s' % ' '.join('%s %s %.0f' % ('+' if e[1] == 'built' else '-', e[2][0].upper(),
                                                                    mins(e[0])) for e in ours))
    if not flags:
        print('  no signature')
        return
    ts = [c[T] for c in C]

    def census_at(t):
        i = max(0, min(len(C) - 1, bisect.bisect_right(ts, t) - 1))
        return C[i]
    for s in SIGS:
        if s not in flags:
            continue
        f = flags[s]
        print()
        print('  == ' + f['desc'])
        for a, b in windows_of(s, f):
            print('    %s min' % fmt_span(a, b))
            step = max(SAMPLE, round((b - a) / 8.0 / SAMPLE) * SAMPLE)
            t = a
            while t < b:
                print('      %6.1f  %s' % (mins(t), census_line(census_at(t))))
                t += step
            print('      %6.1f  %s' % (mins(min(b, C[-1][T])), census_line(census_at(b))))
            if log:
                evs = [e for e in log['events'] if a - 60 <= e[0] <= b]
                for e in evs[:12]:
                    print('      log %7.1f  %s' % (e[0], e[2][:140]))
                if len(evs) > 12:
                    print('      log ... %d more event lines' % (len(evs) - 12))
                jl = [j for j in log['jams'] if a <= j[0] <= b]
                if s in JAM_SIGS or s == 'stuck_army':
                    for j in jl[:3] + (jl[-2:] if len(jl) > 5 else jl[3:]):
                        print('      jam %7.1f  %d %s blocked around %d,%d' % (j[0], j[2], j[1], j[3], j[4]))
                    if len(jl) > 5:
                        print('      jam ... %d jam lines in all' % len(jl))
                st = [x for x in log['stats'] if a <= x['t'] <= b]
                if st:
                    k = max(1, len(st) // 6)
                    for x in st[::k][:8]:
                        print('      STAT %7.1f  %s' % (x['t'], trim_stat(x['text'])))
        if s == 'stuck_army' and 'log' in f:
            for n in f['log']:
                print('    log: ' + n)
        if s == 'wood_lock' and 'log_minutes' in f:
            print('    log: locked %.1f min in %s; first cyc>=90 after 30 min: %s' % (
                f['log_minutes'], ' '.join(fmt_span(a, b + SAMPLE) for a, b in f['log_spans']) or '-',
                '%.1f min' % mins(f['first_saturated']) if f['first_saturated'] is not None else '-'))
        if s in JAM_SIGS and not log:
            print('    no decision log: the jam spots need a logged replay of this game (%s)' % row.get('replay', ''))
        if s in JAM_SIGS and f['episodes']:
            print('    jam episodes (>= %d lines at one spot):' % JAM_LINES)
            for e in f['episodes']:
                line = '      %s min at %d,%d: %d lines, up to %d blocked' % (
                    fmt_span(e['t0'], e['t1'] + 20), e['x'], e['y'], e['lines'], e['max'])
                if s == 'peon_jam':
                    line += '; ' + peon_jam_context(g, log, e)
                print(line)


def trim_stat(text):
    text = re.sub(r' pb=\[.*$', '', text)
    return re.sub(r' (proj|Is|Rs|trx|raid|twr|park)=\S+', '', text)


# ---------------------------------------------------------------------------------------------------------- main

def main(argv):
    top = 15
    show_all = False
    args = []
    i = 0
    while i < len(argv):
        if argv[i] == '--top':
            top = int(argv[i + 1])
            i += 2
        elif argv[i] == '--all':
            show_all = True
            i += 1
        else:
            args.append(argv[i])
            i += 1
    if not args:
        print(__doc__)
    elif args[0] == '--game':
        detail(args[1], args[2])
    else:
        summary(args, top, show_all)


if __name__ == '__main__':
    main(sys.argv[1:])
