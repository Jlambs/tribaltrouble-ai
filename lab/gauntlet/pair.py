#!/usr/bin/env python3
"""Paired comparison of runs on the same seeds: ./lab/gauntlet/pair.py BASE ARM [ARM ...]

Per arm: wins/losses/draws on the common games, flips against the base (gained, lost), identical games (same checksum
and length), the median win time on wins both runs share, and a sign test z for the flips. Times are game minutes
(harness minutes times 4 for --speed ludicrous runs)."""
import json, math, os, statistics, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'aisim', 'runs')


def rows(run):
    out = {}
    with open(os.path.join(ROOT, run, 'results.jsonl'), encoding='utf-8') as f:
        for line in f:
            r = json.loads(line)
            if r.get('result') is None:
                continue
            r['_min'] = r['t'] * (4 if r.get('speed') == 'ludicrous' else 1) / 60
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
        med = ''
        if both:
            med = ' | shared wins median %.1f -> %.1f min' % (statistics.median(base[k]['_min'] for k in both),
                                                            statistics.median(arm[k]['_min'] for k in both))
        print('%s vs %s: %d games, %d identical | W/L/D %d/%d/%d -> %d/%d/%d | gained %d lost %d (z %.1f)%s' % (
            arm_run, base_run, len(keys), same, bw, bl, bd, aw, al, ad, len(gained), len(lost), z, med))


if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2:])
