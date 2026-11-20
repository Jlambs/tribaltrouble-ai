#!/usr/bin/env python3
"""The moments with no armory: python lab/fable/noarmory.py RUN...

From the built and razed events of our armories (g/KEY.jsonl): the games with a moment of no armory after the first
one stood and how many of them are won, against the games with an armory at every moment; the episodes (rebuilt after
how long, or never); how long we stood at exactly one armory before the first such moment; and the share of the time
from minute 10 on with none, one and two or more, in the games won and in the others.
"""
import json
import os
import statistics as st
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def timeline(run, key):
    """[(second, armories standing after the event)] and the game's last second."""
    side = int(key.rsplit('-', 1)[1])
    n = 0
    events = []
    end = None
    with open(os.path.join(ROOT, run, 'g', key + '.jsonl'), encoding='utf-8') as f:
        for line in f:
            if '"b":"armory"' in line and ('"ev":"built"' in line or '"ev":"razed"' in line):
                d = json.loads(line)
                if d['s'] != side:
                    continue
                if d['ev'] == 'built':
                    n += 1
                    events.append((d['t'], n))
                elif not d.get('site'):
                    n -= 1
                    events.append((d['t'], n))
            elif '"ev":"end"' in line:
                end = json.loads(line)['t']
    return events, end


def quartiles(xs):
    xs = sorted(xs)
    return f'{st.median(xs):.0f} (quartiles {xs[len(xs) // 4]:.0f}-{xs[3 * len(xs) // 4]:.0f})'


def main(runs):
    for run in runs:
        with_none = [0, 0]
        without = [0, 0]
        rebuilt = []
        never = []
        at_one = []
        first_none = []
        shares = {True: [], False: []}
        with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
            games = [json.loads(line) for line in f if line.strip()]
        for r in games:
            if r.get('result') is None:
                continue
            won = r['result'] == 'win'
            events, end = timeline(run, r['key'])
            end = end if end is not None else r['t']
            had = False
            start = None
            one_since = None
            first = True
            episodes = 0
            for t, c in events:
                if c >= 1:
                    had = True
                if c == 1 and one_since is None:
                    one_since = t
                if c >= 2:
                    one_since = None
                if c == 0 and had and start is None:
                    start = t
                    episodes += 1
                    if first:
                        first = False
                        first_none.append(t / 60.0)
                        at_one.append(t - (one_since if one_since is not None else t))
                if c >= 1 and start is not None:
                    rebuilt.append(t - start)
                    start = None
                    one_since = t
            if start is not None:
                never.append(end - start)
            side = with_none if episodes else without
            side[0] += 1
            side[1] += won
            dur = [0.0, 0.0, 0.0]
            prev_t, prev_c = 600.0, 0
            for t, c in events:
                if t <= 600.0:
                    prev_c = c
                    continue
                dur[min(prev_c, 2)] += t - prev_t
                prev_t, prev_c = t, c
            dur[min(prev_c, 2)] += max(0.0, end - prev_t)
            if sum(dur) > 0:
                shares[won].append([d / sum(dur) for d in dur])
        print(f'{run}: an armory at every moment after the first: {without[0]} games, {without[1]} won; '
              f'a moment with none: {with_none[0]} games, {with_none[1]} won')
        if rebuilt or never:
            print(f'    episodes: {len(rebuilt)} rebuilt' + (f' after {quartiles(rebuilt)} s' if rebuilt else '')
                  + f', {len(never)} never' + (f' ({quartiles(never)} s to the end)' if never else ''))
        if at_one:
            print(f'    first moment with none at minute {quartiles(first_none)}, after {quartiles(at_one)} s at '
                  f'exactly one armory')
        for won in (True, False):
            g = shares[won]
            if g:
                print(f'    {"won" if won else "not won"}: {len(g)} games, from minute 10 on none '
                      f'{100 * st.mean(x[0] for x in g):.0f} %, one {100 * st.mean(x[1] for x in g):.0f} %, '
                      f'two or more {100 * st.mean(x[2] for x in g):.0f} % of the time')


if __name__ == '__main__':
    main(sys.argv[1:])
