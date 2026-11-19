#!/usr/bin/env python3
"""Any-seat ladder: python lab/fable/anyseat.py --name f3 [--spec "@f3"] --n 8..12 [--seeds 22001..22100]
                        [--flight 2] [--full] [--report]

The user's measure (2026-11-19): a map counts as won when our AI beats the N allied Hards from at least one of the
map's N+1 starts, and the aim is every map won at as high an N as possible. A map that is lost from the first start
alone may just have a bad first start; one lost from every start is a bad map, or shows a leak of ours.

The harness seats by rotation already (`--side S`: our AI in slot S, the Hards in the others), so a map's N+1 games
are N+1 batches' worth of `--side`. Played in rounds, the cheap way: seat 0 on every map, then further seats only on
the maps not won yet, 1, 2, 4, 8, ... seats a round (their batches side by side), until every map is won or the seats
are used up. `--full` plays every seat of every map instead (one round). A batch is `as-NAME-vsN-sS` through
lab/fable/dev.sh; one that exists already is read, not replayed, so a stopped ladder resumes. `--flight` N run at
once. Events go to aisim/anyseat-NAME.log; `--report` only prints the table of what has been played.

Per N the table has the maps won from some start, those won from seat 0 (the usual benchmark), the games it took, the
share of all games won, and the maps lost from every start.
"""
import argparse
import json
import os
import subprocess
import sys
import threading
import time

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
RUNS = os.path.join(ROOT, 'aisim', 'runs')


def expand(spec):
    out = []
    for part in spec.split(','):
        if '..' in part:
            a, b = part.split('..')
            out.extend(range(int(a), int(b) + 1))
        else:
            out.append(int(part))
    return out


def rows(run):
    path = os.path.join(RUNS, run, 'results.jsonl')
    out = []
    if os.path.exists(path):
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


class Ladder:
    def __init__(self, args):
        self.args = args
        self.seeds = expand(args.seeds)
        self.spec = args.spec or '@' + args.name
        self.log_path = os.path.join(ROOT, 'aisim', f'anyseat-{args.name}.log')
        self.lock = threading.Lock()

    def log(self, msg):
        line = time.strftime('%H:%M:%S ') + msg
        with self.lock:
            print(line, flush=True)
            if not self.args.report:
                with open(self.log_path, 'a', encoding='utf-8') as f:
                    f.write(line + '\n')

    def run_name(self, n, seat):
        return f'as-{self.args.name}-vs{n}-s{seat}'

    def played(self, n):
        """seed -> {seat: result} of everything on disk for this N and these seeds."""
        got = {s: {} for s in self.seeds}
        for seat in range(n + 1):
            for r in rows(self.run_name(n, seat)):
                if r['seed'] in got:
                    got[r['seed']][r['side']] = r['result']
        return got

    def batch(self, n, seat, seeds):
        """Plays seat `seat` on `seeds` unless the batch is there already; returns the Popen or None."""
        run = self.run_name(n, seat)
        if os.path.isdir(os.path.join(RUNS, run)):
            return None
        out = open(os.path.join(ROOT, 'aisim', run + '.log'), 'w', encoding='utf-8')
        env = dict(os.environ, SIDE=str(seat))
        cmd = ['bash', 'lab/fable/dev.sh', run, f'{self.spec} vs hard*{n}', ','.join(map(str, seeds))]
        return subprocess.Popen(cmd, cwd=ROOT, stdout=out, stderr=subprocess.STDOUT, env=env)

    def climb(self, n):
        seat = 0
        width = 1
        round_no = 0
        while seat <= n:
            got = self.played(n)
            open_maps = [s for s in self.seeds if 'win' not in got[s].values()]
            if not open_maps:
                break
            seats = list(range(seat, n + 1)) if self.args.full else list(range(seat, min(n, seat + width - 1) + 1))
            procs = []
            for st in seats:
                # a batch that exists was played on the maps open when it started: the same ones, as long as the
                # earlier batches are unchanged
                p = self.batch(n, st, self.seeds if self.args.full else open_maps)
                if p is not None:
                    procs.append((st, p))
            if procs:
                self.log(f'N={n} round {round_no}: seats {seats[0]}..{seats[-1]} on {len(open_maps)} maps')
            for st, p in procs:
                if p.wait() != 0:
                    self.log(f'FAILED N={n} seat {st}: see aisim/{self.run_name(n, st)}.log')
                    return False
            seat = seats[-1] + 1
            if round_no >= 1:
                width *= 2
            round_no += 1
        self.log('DONE ' + self.line(n))
        return True

    def line(self, n):
        got = self.played(n)
        maps = [s for s in self.seeds if got[s]]
        if not maps:
            return f'N={n}: nothing played'
        won = [s for s in maps if 'win' in got[s].values()]
        seat0 = [s for s in maps if got[s].get(0) == 'win']
        games = sum(len(got[s]) for s in maps)
        wins = sum(1 for s in maps for r in got[s].values() if r == 'win')
        draws = sum(1 for s in maps for r in got[s].values() if r == 'draw')
        # a map is settled when it is won or every start has been played
        unsettled = [s for s in maps if s not in won and len(got[s]) < n + 1]
        lost = [s for s in maps if s not in won and len(got[s]) == n + 1]
        text = (f'N={n}: {len(won)}/{len(maps)} maps won from some start ({len(seat0)} from seat 0), '
                f'{games} games, {wins} won ({100.0 * wins / games:.0f} %), {draws} drawn')
        if lost:
            text += '; lost from every start: ' + ','.join(map(str, lost))
        if unsettled:
            text += f'; {len(unsettled)} maps still open'
        return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--name', required=True, help='label in the run names')
    ap.add_argument('--spec', default=None, help='our player (default @NAME)')
    ap.add_argument('--n', required=True, help='N or a range FIRST..LAST or a list')
    ap.add_argument('--seeds', default='22001..22100')
    ap.add_argument('--flight', type=int, default=2)
    ap.add_argument('--full', action='store_true')
    ap.add_argument('--report', action='store_true')
    args = ap.parse_args()
    ladder = Ladder(args)
    ns = expand(args.n)
    if args.report:
        for n in ns:
            print(ladder.line(n))
        return 0
    gate = threading.Semaphore(args.flight)
    failed = []

    def work(n):
        with gate:
            if not ladder.climb(n):
                failed.append(n)

    threads = [threading.Thread(target=work, args=(n,)) for n in ns]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    ladder.log('SUMMARY')
    for n in ns:
        ladder.log('  ' + ladder.line(n))
    return 2 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
