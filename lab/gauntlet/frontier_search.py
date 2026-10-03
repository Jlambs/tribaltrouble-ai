#!/usr/bin/env python3
"""Frontier seed search: python lab/gauntlet/frontier_search.py JOBFILE LOG [first_N] [PREFIX] [POOL_SLOTS]

The user's rule (2026-10-02): search each N with 2,000 games (four 500-seed blocks); with no win there, extend it to
5,000 (six more blocks); an N with a win is beaten (its unstarted blocks are cancelled) and the next N starts; keep
two N values in flight; stop once two consecutive N values record 0 wins in 5,000 games each. Blocks are pool.sh job
lines (cur15 defaults, 0/10/10 through dev.sh) appended to JOBFILE; the pool is restarted whenever it has exited
with work left. Events (WIN, EXTEND, FAILED, START, STOP) go to LOG, one line each. Run from the repository root,
detached (nohup); it exits after STOP once nothing of the search is still running.
"""
import json
import os
import re
import subprocess
import sys
import time

JOBS, LOG = sys.argv[1], sys.argv[2]
FIRST = int(sys.argv[3]) if len(sys.argv) > 3 else 21
# the run-name prefix (the AI version searched: its defaults are the spec) and the pool's batch slots
PREFIX = sys.argv[4] if len(sys.argv) > 4 else 'cur15'
SLOTS = sys.argv[5] if len(sys.argv) > 5 else '4'
RUNS = 'aisim/runs'
FIRST_BLOCKS = [6001, 6501, 7001, 8001]
MORE_BLOCKS = [7501, 8501, 9001, 9501, 10001, 10501]
SPEC = 'gauntlet'


def name(n, start):
    # the first cur15 blocks of N=21 and N=22 were queued by hand under these names
    if PREFIX == 'cur15' and start == 6001 and n in (21, 22):
        return f'cur15-flat-vs{n}'
    return f'{PREFIX}-flat-vs{n}-{start}'


def log(msg):
    with open(LOG, 'a', encoding='utf-8') as f:
        f.write(time.strftime('%H:%M:%S ') + msg + '\n')


def rows(run):
    p = os.path.join(RUNS, run, 'results.jsonl')
    if not os.path.exists(p):
        return []
    with open(p, encoding='utf-8') as f:
        return [json.loads(line) for line in f if line.strip()]


def done(run):
    return os.path.exists(os.path.join(RUNS, run, 'summary.txt'))


def started(run):
    return os.path.isdir(os.path.join(RUNS, run))


def job_lines():
    with open(JOBS, encoding='utf-8') as f:
        return f.read().splitlines()


def queued(run):
    return any(line.split('|')[0] == run for line in job_lines())


def append(n, start):
    run = name(n, start)
    if queued(run) or started(run):
        return
    with open(JOBS, 'a', encoding='utf-8', newline='\n') as f:
        f.write(f'{run}|{SPEC} vs hard*{n}|{start}..{start + 499}|--speed ludicrous\n')


def cancel_unstarted(n):
    lines = job_lines()
    out = []
    for line in lines:
        run = line.split('|')[0]
        if re.fullmatch(rf'{re.escape(PREFIX)}-flat-vs{n}(-\d+)?', run) and not started(run):
            out.append('#frontier-done ' + line)
        else:
            out.append(line)
    with open(JOBS, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(out) + '\n')


def pool_alive():
    # Git Bash's ps can fail under load (cmalloc): an unreadable process list counts as alive, so a second pool is
    # never started on the same job file
    try:
        r = subprocess.run(['ps', '-ef'], capture_output=True, text=True)
    except OSError:
        return True
    if r.returncode != 0 or not r.stdout.strip():
        return True
    return any('pool.sh' in line and os.path.basename(JOBS) in line for line in r.stdout.splitlines())


def ensure_pool():
    if not pool_alive():
        kw = {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {'start_new_session': True}
        subprocess.Popen(['bash', './lab/gauntlet/pool.sh', SLOTS, JOBS], stdout=open(LOG + '.pool', 'a'),
                         stderr=subprocess.STDOUT, **kw)
        log('pool restarted')


state = {}  # N -> 'searching' | 'extended' | 'beaten' | 'failed'
blocks = {}  # N -> list of block starts queued


def start_n(n):
    state[n] = 'searching'
    blocks[n] = list(FIRST_BLOCKS)
    for s in FIRST_BLOCKS:
        append(n, s)
    log(f'START N={n}: {len(FIRST_BLOCKS)} blocks')


def stop_reached():
    return any(state.get(n) == 'failed' and state.get(n + 1) == 'failed' for n in state)


log(f'controller up, first N {FIRST}')
start_n(FIRST)
start_n(FIRST + 1)
stopped = False
while True:
    for n in sorted(state):
        if state[n] in ('beaten', 'failed'):
            continue
        runs = [name(n, s) for s in blocks[n]]
        wins = [(r, x['key'], round(x['t'] / 60)) for r in runs for x in rows(r) if x.get('result') == 'win']
        games = sum(len(rows(r)) for r in runs)
        if wins:
            state[n] = 'beaten'
            cancel_unstarted(n)
            log(f'WIN N={n} after {games} games: ' + ', '.join(f'{r} {k} {m} min' for r, k, m in wins))
            continue
        if all(done(r) for r in runs):
            if state[n] == 'searching':
                state[n] = 'extended'
                blocks[n] += MORE_BLOCKS
                for s in MORE_BLOCKS:
                    append(n, s)
                log(f'EXTEND N={n}: 0 wins in {games} games, {len(MORE_BLOCKS)} more blocks')
            else:
                state[n] = 'failed'
                log(f'FAILED N={n}: 0 wins in {games} games')
    if not stopped and stop_reached():
        stopped = True
        for n in state:
            if state[n] in ('searching', 'extended'):
                cancel_unstarted(n)
        log('STOP: two consecutive N values with 0 wins in 5,000 games ' + json.dumps(state))
    if not stopped:
        live = [n for n in state if state[n] in ('searching', 'extended')]
        while len(live) < 2:
            nxt = max(state) + 1
            start_n(nxt)
            live.append(nxt)
    if stopped:
        live_runs = [name(n, s) for n in state for s in blocks[n] if started(name(n, s)) and not done(name(n, s))]
        if not live_runs:
            log('controller exits')
            break
    ensure_pool()
    time.sleep(60)
