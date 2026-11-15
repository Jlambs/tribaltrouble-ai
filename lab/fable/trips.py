"""Where the iron gatherers' time goes, from the gtrip lines of logged games (TripTrace).

    python lab/fable/trips.py RUN [MINUTES_PER_BIN]

Each line of the output is a period: the delivered trips (number a game, seconds out and back, cells from the armory
at the pick-up) and the share of all iron gatherer-seconds that ended in a delivered trip, in a recall by an old
armory (entering, no threat by, linked to an old armory), in a flight (entering with a threat within 11 cells), in
another order into a building (linked to the main armory, no threat by), in the engine giving up on the supply
(TransferUnitController), in death, and in other work (shepherd, sapper, builder).
"""
import glob
import re
import sys

run = sys.argv[1]
width = int(sys.argv[2]) if len(sys.argv) > 2 else 4
ok = re.compile(r'^\s*([\d.]+) s0 \S+\s+gtrip ok out (\d+) back (\d+) picked (-?\d+) far (-?\d+) retargets (\d+)')
end = re.compile(r'^\s*([\d.]+) s0 \S+\s+gtrip (\w+) after (\d+) loaded (\w+) far (-?\d+) retargets (\d+) at (\d+),(\d+)'
                 r'(?: (\S+) threat (\w+) level (\d+) (\S+))?')
bins = {}
games = 0
for f in sorted(glob.glob(f'aisim/runs/{run}/g/*-ai-s0.log')):
    games += 1
    for line in open(f, encoding='utf-8', errors='replace'):
        if ' gtrip ' not in line:
            continue
        m = ok.match(line)
        if m:
            d = bins.setdefault(int(float(m.group(1)) // (60 * width)) * width, {})
            r = d.setdefault('ok', [0, 0.0, 0.0, 0.0])
            r[0] += 1
            r[1] += int(m.group(2))
            r[2] += int(m.group(3))
            r[3] += int(m.group(4))
            continue
        m = end.match(line)
        if not m:
            continue
        d = bins.setdefault(int(float(m.group(1)) // (60 * width)) * width, {})
        how, secs, ctrl, threat, link = m.group(2), int(m.group(3)), m.group(9), m.group(10), m.group(12)
        if how in ('transit', 'inside') and m.group(4) == 'true':
            # a loaded gatherer that walks into an armory delivers its load there (EnterController)
            r = d.setdefault('ok', [0, 0.0, 0.0, 0.0])
            r[0] += 1
            r[1] += secs
            r[3] += max(0, int(m.group(5)))
            continue
        if how == 'dead':
            k = 'dead'
        elif how in ('transit', 'inside'):
            if ctrl == 'TransferUnitController':
                k = 'gave up'
            elif threat == 'true':
                k = 'flight'
            elif link == 'old':
                k = 'recall'
            elif ctrl is None:
                k = 'transit'
            else:
                k = 'ordered in'
        elif how == 'move':
            k = 'flight' if threat == 'true' else 'other'
        else:
            k = 'other'
        r = d.setdefault(k, [0, 0.0])
        r[0] += 1
        r[1] += secs
keys = ['recall', 'flight', 'ordered in', 'gave up', 'transit', 'dead', 'other']
print(f'{run}: {games} games')
print('min | trips/game  out back cells | delivered ' + ' '.join(f'{k:>10}' for k in keys) + '   (share of gatherer-seconds; ends a game)')
for b in sorted(bins):
    if b > 36:
        break
    d = bins[b]
    o = d.get('ok', [0, 0, 0, 0])
    n = max(1, o[0])
    tot = o[1] + o[2] + sum(v[1] for k, v in d.items() if k != 'ok')
    s = f'{b:3d} | {o[0] / games:9.0f} {o[1] / n:5.0f} {o[2] / n:4.0f} {o[3] / n:5.0f} | {100 * (o[1] + o[2]) / max(1, tot):8.0f}% '
    for k in keys:
        v = d.get(k, [0, 0])
        s += f' {100 * v[1] / max(1, tot):3.0f}%/{v[0] / games:<5.0f}'
    print(s)
