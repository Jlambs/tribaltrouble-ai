"""Game time for the lab tools: every time a tool works with is game seconds.

Since the harness counts game time (headless 001fd17c), rows, game files and AI log stamps hold game seconds at every
speed. Non-normal runs from before then hold world ticks / 50 instead (a quarter of game time at ludicrous). Convert
once, where a tool reads them:

- row(r): a results row, in place. An old row has a speed but no ticks member.
- factor(header): the multiplier for the t of an old game file (its first line) and of the AI logs beside it; 1 for
  every new file and every normal-speed file. Harness games never change speed, so one factor covers the game.
- OUTS: the events that put a player out. The collapse rule writes only a collapse event (no out), so a tool that
  counts outs reads both and keeps a player's first.

Old non-normal files also sampled their census every 30 world-tick seconds (120 game s at ludicrous) and polled events
every 50 world ticks; new files take the census every 30 game seconds and poll every game second.
"""

SPEED_FACTOR = {'slow': .5, 'normal': 1., 'fast': 1.75, 'ludicrous': 4.}
OUTS = ('out', 'collapse')
T40 = 2400.  # a game still alive at 40 game minutes


def row(r):
    """Converts a results row's times to game seconds, in place and once; returns the row."""
    if '_gt' not in r:
        f = SPEED_FACTOR.get(r.get('speed') or 'normal', 1.) if 'ticks' not in r else 1.
        r['_gt'] = f
        if f != 1.:
            if r.get('t') is not None:
                r['t'] *= f
            for team in r.get('teams') or []:
                if team.get('out') is not None:
                    team['out'] *= f
    return r


def factor(header):
    """Game seconds per t unit of a game file whose first line (the game event) is header."""
    if header.get('clock') == 'game':
        return 1.
    # .01 / .02 / .035 / .08 game seconds per world tick -> .5 / 1 / 1.75 / 4
    return round(float(header.get('secondsPerTick', .02)) * 1000) / 20
