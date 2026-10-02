#!/usr/bin/env python3
"""Paired comparison of runs on the same seeds: ./lab/gauntlet/pair.py BASE ARM [ARM ...]

Per arm: wins/losses/draws on the common games, flips against the base (gained, lost), identical games (same checksum
and length), the median win time on wins both runs share, and a sign test z for the flips. Times are game minutes
(rows of non-normal runs from before the harness counted game time are converted)."""
import json, math, os, statistics, sys

import gtime

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def rows(run):
    out = {}
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        for line in f:
            r = json.loads(line)
            if r.get('result') is None:
                continue
            r['_min'] = gtime.row(r)['t'] / 60
            out[r['key']] = r
    return out


def tally(rs):
    res = [r['result'] for r in rs]
    return res.count('win'), res.count('loss'), res.count('draw')


def main(base_run, arms):
    base = rows(base_run)
    for arm_run in arms:
        arm = rows(arm_run)
        keys = sorted(set(base) & set(arm))
        b = [base[k] for k in keys]
        a = [arm[k] for k in keys]
        gained = [k for k in keys if arm[k]['result'] == 'win' and base[k]['result'] != 'win']
        lost = [k for k in keys if base[k]['result'] == 'win' and arm[k]['result'] != 'win']
        same = sum(1 for k in keys if arm[k]['checksum'] == base[k]['checksum'] and arm[k]['t'] == base[k]['t'])
        both = [k for k in keys if arm[k]['result'] == 'win' and base[k]['result'] == 'win']
        n = len(gained) + len(lost)
        z = (len(gained) - len(lost)) / math.sqrt(n) if n else 0.0
        bw, bl, bd = tally(b)
        aw, al, ad = tally(a)
        # survival: game minutes capped at 60 (a win counts 60), paired
        def surv(r):
            return 60.0 if r['result'] == 'win' else min(60.0, r['_min'])
        diffs = [surv(arm[k]) - surv(base[k]) for k in keys]
        md = statistics.mean(diffs) if diffs else 0.0
        sd = statistics.pstdev(diffs) if len(diffs) > 1 else 0.0
        zs = md / (sd / math.sqrt(len(diffs))) if sd > 0 else 0.0
        a40 = sum(1 for k in keys if surv(arm[k]) >= 40) - sum(1 for k in keys if surv(base[k]) >= 40)
        med = ' | surv60 %+.2f min (z %.1f), alive40 %+d' % (md, zs, a40)
        if both:
            med += ' | shared wins median %.1f -> %.1f min' % (statistics.median(base[k]['_min'] for k in both),
                                                            statistics.median(arm[k]['_min'] for k in both))
        print('%s vs %s: %d games, %d identical | W/L/D %d/%d/%d -> %d/%d/%d | gained %d lost %d (z %.1f)%s' % (
            arm_run, base_run, len(keys), same, bw, bl, bd, aw, al, ad, len(gained), len(lost), z, med))


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2:])
