#!/usr/bin/env python3
"""Frontier scan: python lab/fable/scan.py [--first 21] [--last 31] [--tag f3] [--seeds 6001..11000] [--flight 2] [--dry]

The user's rule (2026-11-17): play 5000 maps at each N above 20 until two N in a row have no wins, the search with
which Gauntlet's record was made (lab/gauntlet/frontier_search.py: seeds 6001..11000, 0/10/10, ludicrous; its record is
N=24, one win in 4,320 games, seed 9902, and N=25 and N=26 both had none in 5,000). Every N is one batch
`sc-TAG-vsN` of `@TAG vs hard*N` on the seeds through lab/fable/dev.sh; `flight` N run at once, the next one starting
when one has finished. Events go to aisim/scan-TAG.log, one line each: START, WIN (as it happens), DONE (wins of the
games counted), STOP. After a stop the batches still running are cancelled (aisim/runs/NAME/STOP).
Run from the repository root, detached (nohup); it exits after STOP once nothing of the scan is still running.
"""
import argparse
import json
import os
import subprocess
import sys
import time

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
POLL_SECONDS = 60
# a batch whose process ended with fewer games than this share counted is a failed one (the scan then stops)
MIN_SHARE = 0.995
# the harness takes up to 32 players
MAX_N = 31


def expand(seeds):
    if '..' in seeds:
        a, b = seeds.split('..')
        return int(b) - int(a) + 1
    return len(seeds.split(','))


def rows(run):
    path = os.path.join(ROOT, 'aisim', 'runs', run, 'results.jsonl')
    if not os.path.exists(path):
        return []
    out = []
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.strip():
                try:
                    r = json.loads(line)
                except ValueError:
                    continue  # a line still being written
                if r.get('result') is not None:
                    out.append(r)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--first', type=int, default=21)
    ap.add_argument('--last', type=int, default=MAX_N)
    ap.add_argument('--tag', default='f3')
    ap.add_argument('--prefix', default=None, help='run names are PREFIX-vsN (default sc-TAG)')
    ap.add_argument('--seeds', default='6001..11000')
    ap.add_argument('--flight', type=int, default=2)
    ap.add_argument('--dry', action='store_true')
    args = ap.parse_args()
    expected = expand(args.seeds)
    log_path = os.path.join(ROOT, 'aisim', f'scan-{args.prefix or args.tag}.log')

    def log(msg):
        line = time.strftime('%H:%M:%S ') + msg
        print(line, flush=True)
        if not args.dry:
            with open(log_path, 'a', encoding='utf-8') as f:
                f.write(line + '\n')

    def name(n):
        return f'{args.prefix or "sc-" + args.tag}-vs{n}'

    started = {}  # n -> Popen or None (finished before this scan began)
    seen_wins = {}  # n -> keys already logged
    result = {}  # n -> (wins, draws, counted)
    next_n = args.first
    stopping = False

    def launch(n):
        run = name(n)
        cmd = ['bash', 'lab/fable/dev.sh', run, f'@{args.tag} vs hard*{n}', args.seeds]
        if os.path.isdir(os.path.join(ROOT, 'aisim', 'runs', run)):
            got = rows(run)
            if len(got) >= MIN_SHARE * expected:
                started[n] = None
                seen_wins[n] = set()
                log(f'START N={n} {run}: already played, {len(got)} games')
                return
            log(f'FAILED N={n} {run}: the run folder exists with {len(got)} of {expected} games; remove it first')
            raise SystemExit(2)
        log(f'START N={n} {run}: {" ".join(cmd[2:])}')
        seen_wins[n] = set()
        if args.dry:
            started[n] = None
            return
        out = open(os.path.join(ROOT, 'aisim', run + '.log'), 'w', encoding='utf-8')
        started[n] = subprocess.Popen(cmd, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT)

    while True:
        in_flight = [n for n in started if n not in result]
        while not stopping and len(in_flight) < args.flight and next_n <= min(args.last, MAX_N):
            launch(next_n)
            in_flight.append(next_n)
            next_n += 1
        if args.dry:
            return
        for n in list(in_flight):
            got = rows(name(n))
            for r in got:
                if r['result'] == 'win' and r['key'] not in seen_wins[n]:
                    seen_wins[n].add(r['key'])
                    log(f'WIN N={n} {r["key"]} at {r["t"] / 60.0:.1f} min ({len(got)} games counted)')
            proc = started[n]
            if proc is not None and proc.poll() is None:
                continue
            wins = sum(1 for r in got if r['result'] == 'win')
            draws = sum(1 for r in got if r['result'] == 'draw')
            result[n] = (wins, draws, len(got))
            if len(got) < MIN_SHARE * expected and stopping:
                log(f'CANCELLED N={n} after {len(got)} of {expected} games: {wins} wins, {draws} draws')
                continue
            if len(got) < MIN_SHARE * expected:
                log(f'FAILED N={n}: process ended with {len(got)} of {expected} games; see aisim/{name(n)}.log')
                stopping = True
            log(f'DONE N={n}: {wins} wins, {draws} draws in {len(got)} games')
        done = sorted(n for n in result if result[n][2] >= MIN_SHARE * expected)
        for n in done:
            if n + 1 in result and result[n][0] == 0 and result[n + 1][0] == 0 and result[n + 1][2] >= MIN_SHARE * expected:
                if not stopping:
                    log(f'STOP: N={n} and N={n + 1} both have no wins in {expected} games')
                stopping = True
        if not stopping and next_n > min(args.last, MAX_N) and not [n for n in started if n not in result]:
            log(f'STOP: reached N={min(args.last, MAX_N)}')
            stopping = True
        if stopping:
            for n in started:
                if n not in result and started[n] is not None:
                    stop = os.path.join(ROOT, 'aisim', 'runs', name(n), 'STOP')
                    if os.path.isdir(os.path.dirname(stop)) and not os.path.exists(stop):
                        open(stop, 'w').close()
                        log(f'cancel N={n}')
            if all(n in result for n in started):
                break
        time.sleep(POLL_SECONDS)
    log('SUMMARY ' + ', '.join(f'N={n}: {result[n][0]} wins/{result[n][2]}' for n in sorted(result)))


if __name__ == '__main__':
    sys.exit(main())
