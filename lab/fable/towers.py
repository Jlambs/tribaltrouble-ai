#!/usr/bin/env python3
"""What our units do within reach of manned enemy towers, from runs made with --logs: python lab/fable/towers.py RUN...

Reads TowerTrace's "TOWER end" lines (one per contact: from the first warrior of ours within 16 cells of a manned
enemy tower until the tower falls, loses its garrison, or has had none of ours in reach for 10 s). Per run, a game:
the contacts by how they end, the seconds they last, our warriors and peons dead in reach (and those that were the
garrison's target when they died), the warrior-seconds in reach by what the warrior was doing, what the garrison
threw at, and the contacts by the most warriors in reach at once.
"""
import collections
import glob
import re
import statistics
import sys

END = re.compile(
    r'(\S+) \S+ TOWER end #(\d+) (\w+) at (\d+),(\d+) owner (.*?) dur (\S+) first_dmg (\S+) hp (\d+)>(\d+) samples (\d+)'
    r' w \[([\d ]+)\] near \[([\d ]+)\] roles \[(\d+) (\d+) (\d+)\] sappers (\d+) swing (\d+) peons (\d+)'
    r' dead_w (\d+) by_gun (\d+) dead_p (\d+) by_gun (\d+) gun \[(\d+) (\d+) (\d+) (\d+)\] max_in (\d+) max_throw (\d+)'
    r' max_swing (\d+) foes (\d+)(?: gun_b (\d+))?')
STATES = ['throw_t', 'close_t', 'unit_w', 'unit_p', 'bld', 'walk', 'blocked', 'idle', 'other']


def load(run):
    files = sorted(glob.glob(f'aisim/runs/{run}/g/*-ai-s0.log'))
    rows = []
    for f in files:
        for line in open(f, encoding='utf-8', errors='replace'):
            if 'TOWER end' not in line:
                continue
            m = END.search(line)
            if not m:
                print('unparsed:', line.strip()[:160], file=sys.stderr)
                continue
            g = m.groups()
            rows.append(dict(
                file=f, t=float(g[0]), id=int(g[1]), why=g[2], x=int(g[3]), y=int(g[4]), owner=g[5], dur=float(g[6]),
                first_dmg=float(g[7]), hp0=int(g[8]), hp=int(g[9]), samples=int(g[10]),
                w=[int(v) for v in g[11].split()], near=[int(v) for v in g[12].split()],
                roles=[int(g[13]), int(g[14]), int(g[15])], sappers=int(g[16]), swing=int(g[17]), peons=int(g[18]),
                dead_w=int(g[19]), dead_w_gun=int(g[20]), dead_p=int(g[21]), dead_p_gun=int(g[22]),
                gun=[int(g[23]), int(g[24]), int(g[25]), int(g[26])], max_in=int(g[27]), max_throw=int(g[28]),
                max_swing=int(g[29]), foes=int(g[30]), gun_b=int(g[31] or 0)))
    return len(files), rows


def table(title, rows, games):
    n = len(rows)
    if not n:
        return
    ws = [sum(r['w'][i] for r in rows) / 2 for i in range(len(STATES))]
    near = [sum(r['near'][i] for r in rows) / 2 for i in range(len(STATES))]
    tot = sum(ws) or 1
    razed = [r for r in rows if r['why'] == 'razed']
    fd = [r['first_dmg'] for r in rows if r['first_dmg'] >= 0]
    print(f"{title}: {n / games:.1f} contacts a game ({len(razed) / games:.1f} razed), {sum(r['dur'] for r in rows) / games:.0f} s, "
          f"dead warriors {sum(r['dead_w'] for r in rows) / games:.1f} ({sum(r['dead_w_gun'] for r in rows) / games:.1f} the garrison's target), "
          f"peons {sum(r['dead_p'] for r in rows) / games:.1f} ({sum(r['dead_p_gun'] for r in rows) / games:.1f})")
    print(f"  warrior-seconds in reach a game {tot / games:.0f} (within 9 cells {sum(near) / games:.0f}): "
          + ', '.join(f'{s} {100 * v / tot:.0f}%' for s, v in zip(STATES, ws)))
    print('  within 9 cells: ' + ', '.join(f'{s} {100 * v / (sum(near) or 1):.0f}%' for s, v in zip(STATES, near)))
    roles = [sum(r['roles'][i] for r in rows) for i in range(3)]
    print(f"  roles: attack {100 * roles[0] / (sum(roles) or 1):.0f}%, reinforce {100 * roles[1] / (sum(roles) or 1):.0f}%, "
          f"other {100 * roles[2] / (sum(roles) or 1):.0f}%; sapper-seconds {sum(r['sappers'] for r in rows) / 2 / games:.0f} "
          f"(swinging {sum(r['swing'] for r in rows) / 2 / games:.0f}), other peons {sum(r['peons'] for r in rows) / 2 / games:.0f}; "
          f"enemy warriors in reach, mean {sum(r['foes'] for r in rows) / max(1, sum(r['samples'] for r in rows)):.1f}")
    gun = [sum(r['gun'][i] for r in rows) / 2 for i in range(4)]
    print(f"  the garrison, s a game: at a warrior {gun[0] / games:.0f}, at a peon {gun[1] / games:.0f}, at nothing {gun[2] / games:.0f}, "
          f"stunned {gun[3] / games:.0f}, at a building {sum(r['gun_b'] for r in rows) / 2 / games:.0f}")
    if fd:
        print(f"  first damage after {statistics.median(fd):.1f} s (median of {len(fd)}; mean {statistics.mean(fd):.1f}); "
              f"razed: median {statistics.median([r['dur'] for r in razed]) if razed else 0:.1f} s, "
              f"{sum(r['dead_w'] for r in razed) / max(1, len(razed)):.2f} warriors and "
              f"{sum(r['dead_p'] for r in razed) / max(1, len(razed)):.2f} peons dead each")


for run in sys.argv[1:]:
    games, rows = load(run)
    print(f'== {run}: {games} games')
    table('all', rows, games)
    for why in ('razed', 'left', 'unmanned'):
        table(f'ended {why}', [r for r in rows if r['why'] == why], games)
    print('by the most warriors in reach at once:')
    for lo, hi in ((1, 3), (4, 10), (11, 25), (26, 60), (61, 999)):
        sel = [r for r in rows if lo <= r['max_in'] <= hi]
        if not sel:
            continue
        print(f"  {lo:3d}-{hi:<3d}: {len(sel) / games:5.1f} contacts a game, {sum(r['dur'] for r in sel) / games:5.0f} s, "
              f"razed {100 * sum(r['why'] == 'razed' for r in sel) / len(sel):3.0f}%, dead warriors {sum(r['dead_w'] for r in sel) / games:5.1f}, "
              f"peons {sum(r['dead_p'] for r in sel) / games:4.1f}, most throwing at once (median) "
              f"{statistics.median([r['max_throw'] for r in sel]):.0f}, swinging {statistics.median([r['max_swing'] for r in sel]):.0f}")
    print('by period:')
    for name, lo, hi in (('0-12', 0, 720), ('12-20', 720, 1200), ('20-30', 1200, 1800), ('30-45', 1800, 2700), ('45+', 2700, 1e9)):
        sel = [r for r in rows if lo <= r['t'] < hi]
        if sel:
            print(f"  {name:6s}: {len(sel) / games:5.1f} contacts a game, {sum(r['dur'] for r in sel) / games:5.0f} s, "
                  f"razed {100 * sum(r['why'] == 'razed' for r in sel) / len(sel):3.0f}%, dead warriors "
                  f"{sum(r['dead_w'] for r in sel) / games:5.1f}, peons {sum(r['dead_p'] for r in sel) / games:4.1f}")
