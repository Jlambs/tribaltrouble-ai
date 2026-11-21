#!/usr/bin/env python3
"""A model of the opening's breeding, to ask what a quarters should keep inside: python lab/fable/breed.py [check|flat|seed|search]

The engine's rule: a quarters breeds max(n, .5)^(1/3) / 11 peons a second with n inside, the newborn counts as inside
until it is let out, and letting out takes half a second. The model plays one opening second by second: thirteen
builders raise the first quarters, then the buildings one after the other (twenty builders to a site, the rest on the
next), every peon walking from where it is to where it works; six strikers come back, shepherds leave, and when the
armory stands the peons not building make weapons. What a quarters keeps is the policy under test. The constants
(540 builder-seconds a building, 32 s from the first quarters to the others, 7 s between those) are fitted to the
default with two inside and to what six did to it.

check   the model against the openings measured on 100 maps (os10..os26-vs16, lab/fable/openspeed.py)
flat    every flat hold while the opening builds
seed    builders sent into a quarters the moment it stands
search  the best hold for each stage, by coordinate search, for several things to maximise
"""
import sys

CAP = 250


def rate(n):
    return max(n, .5) ** (1.0 / 3.0) / 11.0


class Policy:
    """What the AI does now: hold_build while the opening builds without an armory, hold_early until 240 s,
    hold_mid until 175 units, hold_late until the cap."""

    def __init__(self, build=6, early=6, mid=4, late=8, ramp=0, seed=0, table=None, seed_table=None, tau=0.0,
                 tau_from=0):
        self.build, self.early, self.mid, self.late = build, early, mid, late
        self.ramp, self.seed_n, self.table, self.seed_table = ramp, seed, table, seed_table
        self.tau, self.tau_from = tau, tau_from

    def hold(self, sim, j):
        if sim.pop >= CAP - 2:
            return 0
        standing = len(sim.quarters)
        if sim.armory_done is None:
            if self.table is not None:
                return self.table[min(standing, len(self.table)) - 1]
            if self.ramp:
                return min(self.build, self.ramp * standing)
            return self.build
        if self.tau > 0 and sim.t >= self.tau_from:
            # the k-th peon inside pays back in 1 / (rate(k) - rate(k - 1)) seconds: kept while that is under tau
            # times the seconds left to the cap at the pace of now
            pace = sum(rate(q['n']) for q in sim.quarters)
            left = (CAP - sim.pop) / pace if pace > 0 else 0.0
            k = 0
            while k < 40 and self.tau * left * (rate(k + 1) - rate(k)) >= 1.0:
                k += 1
            return k
        if sim.pop > 175:
            return self.late
        return self.early if sim.t < 240 else self.mid

    def seed(self, sim, j):
        if self.seed_table is not None:
            return self.seed_table[min(j, len(self.seed_table) - 1)]
        return self.seed_n


class Sim:
    """Buildings in order: 0 the first quarters, then the later ones; 'A' is the armory."""

    def __init__(self, policy, chain=False, order='QQQQAQQ', work_first=590.0, work=540.0, hop=32.0, leg=7.0,
                 weapon=.0405, post_caps=(10, 7), dt=0.5, horizon=600.0):
        self.p, self.dt, self.horizon = policy, dt, horizon
        self.order = order
        self.work_left = [work_first] + [work] * (len(order) - 1)
        self.done = [None] * len(order)
        # seconds of walking between the sites: the first quarters is a hop from the rest
        n = len(order)
        self.walk = [[0.0 if a == b else leg for b in range(n)] for a in range(n)]
        if chain:
            # two quarters at even steps along the hop
            pos = [0.0, hop / 3, 2 * hop / 3] + [hop] * (n - 3)
            for a in range(n):
                for b in range(n):
                    if a != b:
                        self.walk[a][b] = max(leg if min(a, b) >= 3 else 0.0, abs(pos[a] - pos[b])) + 2.0
        else:
            for b in range(1, n):
                self.walk[0][b] = self.walk[b][0] = hop
        self.weapon, self.post_caps = weapon, post_caps
        self.t = 0.0
        self.quarters = []  # dicts: site, n, acc
        self.at = [0] * n  # builders standing at each site, free to be given work
        self.on = [0] * n  # builders working at each site
        self.coming = []  # (arrive, site, count, to_work)
        self.at[0] = 0
        self.on[0] = 12
        self.scout_from = 78.0  # the scout, once the sites are placed, builds the last one alone
        self.coming.append((150.0, 1, 5, False))  # the strike's survivors
        self.away = 6  # the strikers
        self.shepherds = 0.0
        self.workers = 0.0  # at the armory, not builders
        self.warriors = 0.0
        self.weapons = 0.0
        self.armory_done = None
        self.pop = 20
        self.trace = {}
        self.work_done = 0.0

    @staticmethod
    def shepherds_at(t):
        pts = [(135, 0), (165, 4.6), (195, 9.3), (225, 12.2), (255, 13.7), (285, 14.5), (315, 16.1), (345, 17.8),
               (375, 19.1), (405, 21.1), (435, 22.5), (465, 22.9), (9999, 22.9)]
        if t <= pts[0][0]:
            return 0.0
        for (t0, s0), (t1, s1) in zip(pts, pts[1:]):
            if t <= t1:
                return s0 + (s1 - s0) * (t - t0) / (t1 - t0)
        return pts[-1][1]

    @staticmethod
    def tower_builders(t):
        return 0.0 if t < 300 else min(17.0, 17.0 * (t - 300) / 30.0)

    def front(self):
        """The sites that take builders now, in order, with how many each may have."""
        out = []
        for k, c in enumerate(self.order):
            if self.done[k] is not None:
                continue
            if self.armory_done is None:
                out.append((k, 20))
            else:
                out.append((k, self.post_caps[min(len(out), len(self.post_caps) - 1)]))
        return out

    def assign(self):
        """Free peons take the first site with room, walking there; with the armory up the rest go to work in it."""
        front = self.front()
        booked = {k: self.on[k] + sum(c for (_, s, c, w) in self.coming if s == k and not w) for k, _ in front}
        armory = self.order.index('A')
        for a in range(len(self.at)):
            while self.at[a] > 0:
                target = None
                for k, cap in front:
                    if booked[k] < cap:
                        target = k
                        break
                if target is None:
                    if self.armory_done is None:
                        break  # nothing to do but wait (does not happen with 20 a site)
                    self.at[a] -= 1
                    self.coming.append((self.t + self.walk[a][armory] + 1.0, armory, 1, True))
                    continue
                self.at[a] -= 1
                booked[target] += 1
                if target == a:
                    self.on[a] += 1
                else:
                    self.coming.append((self.t + self.walk[a][target], target, 1, False))

    def step(self):
        dt = self.dt
        t = self.t
        # arrivals
        rest = []
        for arrive, site, count, to_work in self.coming:
            if arrive <= t:
                if to_work:
                    self.workers += count
                elif self.done[site] is None:
                    self.on[site] += count
                else:
                    self.at[site] += count
                if site == 1 and not to_work and arrive == 150.0:
                    self.away -= count
            else:
                rest.append((arrive, site, count, to_work))
        self.coming = rest
        # building
        last = len(self.order) - 1
        for k in range(len(self.order)):
            hands = self.on[k] + (1 if k == last and t >= self.scout_from and self.scout_from >= 0 else 0)
            if self.done[k] is None and hands > 0:
                self.work_left[k] -= hands * dt
                if self.work_left[k] <= 0:
                    self.done[k] = t
                    free = self.on[k]
                    self.on[k] = 0
                    if k == last and self.scout_from >= 0 and t >= self.scout_from:
                        free += 1
                        self.scout_from = -1.0
                    if self.order[k] == 'Q':
                        q = {'site': k, 'n': 0, 'acc': 0.0}
                        self.quarters.append(q)
                        s = min(free, self.p.seed(self, len(self.quarters) - 1), self.p.hold(self, len(self.quarters) - 1))
                        q['n'] = s
                        free -= s
                    else:
                        self.armory_done = t
                    self.at[k] += free
        # breeding and letting out
        whole = abs(t - round(t)) < dt / 2
        for j, q in enumerate(self.quarters):
            if self.pop < CAP:
                q['acc'] += rate(q['n']) * dt
                while q['acc'] >= 1.0 and self.pop < CAP:
                    q['acc'] -= 1.0
                    q['n'] += 1
                    self.pop += 1
            else:
                q['acc'] = 0.0
            if whole:
                h = self.p.hold(self, j)
                if q['n'] > h:
                    self.at[q['site']] += q['n'] - h
                    q['n'] = h
        # shepherds leave from whoever is free or working
        want = self.shepherds_at(t)
        while self.shepherds + 1 <= want:
            if self.workers >= 1:
                self.workers -= 1
            else:
                k = max(range(len(self.on)), key=lambda i: self.on[i] + self.at[i])
                if self.at[k] > 0:
                    self.at[k] -= 1
                elif self.on[k] > 0:
                    self.on[k] -= 1
                else:
                    break
            self.shepherds += 1
        if whole:
            self.assign()
        # the weapon line
        if self.armory_done is not None and t > self.armory_done + 20:
            # weapons come at .04 x the square root of the peons on the line (gatherers and forgers): measured, the
            # army grows .25 to .37 a second with 45 to 98 on it, and 12 fewer cost .04 a second (ot3log, os22-vs16)
            line = max(0.0, self.workers - self.tower_builders(t))
            self.work_done += line * dt
            self.weapons += self.weapon * line ** .5 * dt
            while self.weapons >= 1.0 and self.workers >= 1:
                self.weapons -= 1.0
                self.workers -= 1
                self.warriors += 1
        if whole and int(round(t)) % 30 == 0:
            self.trace[int(round(t))] = (self.pop, self.warriors, sum(q['n'] for q in self.quarters))
        self.t += dt

    def run(self):
        while self.t <= self.horizon:
            self.step()
        return self

    def row(self):
        """As openspeed.py: units at 3..8 min, warriors at 6, 8, 10, and when the buildings stood."""
        quarters = [self.done[k] for k, c in enumerate(self.order) if c == 'Q']
        quarters = sorted(600.0 if x is None else x for x in quarters)
        return {'u180': self.trace[180][0], 'u240': self.trace[240][0], 'u300': self.trace[300][0],
                'u360': self.trace[360][0], 'u420': self.trace[420][0], 'u480': self.trace[480][0],
                'w360': self.trace[360][1], 'w480': self.trace[480][1], 'w600': self.trace[600][1],
                'q2': quarters[1], 'q4': quarters[3], 'q6': quarters[5],
                'armory': 600.0 if self.armory_done is None else self.armory_done, 'work': self.work_done}


COLS = ['u180', 'u240', 'u300', 'u360', 'u420', 'u480', 'w360', 'w480', 'w600', 'q2', 'q4', 'q6', 'armory']
# measured on 100 maps cut at minute 10 (lab/fable/openspeed.py): hold_build, hold_early, hold_mid, ramp
MEASURED = [
    ('os10', dict(build=2, early=4), [39.8, 64.7, 105.9, 152.4, 202.3, 236.8, 26.2, 58.9, 94.8, 134.5, 211.1, 286.6, 233.0]),
    ('os13', dict(build=3, early=4), [41.7, 68.0, 109.3, 155.6, 206.2, 236.4, 27.3, 60.9, 96.2, 131.0, 212.6, 290.4, 232.5]),
    ('os14', dict(build=4, early=4), [43.0, 70.1, 111.4, 157.6, 208.6, 237.4, 27.4, 60.9, 96.5, 132.9, 214.7, 288.7, 237.9]),
    ('os15', dict(build=6, early=4), [44.8, 73.5, 113.5, 159.7, 211.2, 238.0, 28.2, 63.6, 97.1, 135.3, 216.4, 295.5, 241.4]),
    ('os16', dict(build=8, early=4), [45.9, 75.8, 114.8, 160.9, 212.1, 237.9, 27.8, 62.8, 97.3, 135.4, 218.8, 304.3, 246.2]),
    ('os17', dict(build=10, early=4), [46.5, 77.5, 115.5, 161.0, 212.7, 237.3, 27.6, 63.2, 97.3, 135.4, 220.9, 311.8, 250.5]),
    ('os18', dict(build=6, early=6), [45.0, 74.7, 115.3, 161.7, 212.8, 238.2, 28.9, 64.1, 98.7, 131.2, 215.8, 294.5, 238.3]),
    ('os22', dict(build=6, early=6, mid=6), [45.0, 74.7, 120.5, 174.4, 227.0, 240.7, 26.6, 65.7, 102.7, 131.2, 215.8, 294.4, 238.3]),
    ('os23', dict(build=6, early=6, mid=8), [45.0, 74.7, 124.4, 183.1, 232.0, 242.1, 24.4, 67.3, 103.4, 131.2, 215.8, 295.4, 238.3]),
    ('os24', dict(build=6, early=6, ramp=2), [42.8, 73.1, 113.4, 160.2, 211.3, 238.3, 28.2, 63.4, 98.7, 130.5, 211.9, 293.1, 235.3]),
    ('os26', dict(build=6, early=6, ramp=3), [43.9, 74.0, 114.3, 161.1, 212.2, 238.2, 28.9, 64.0, 98.3, 134.9, 213.2, 294.1, 239.5]),
]


def play(chain=False, **kw):
    return Sim(Policy(**kw), chain=chain).run().row()


def show(name, row, base=None):
    if base is None:
        print('%-26s' % name + ' '.join('%6.1f' % row[c] for c in COLS))
    else:
        print('%-26s' % name + ' '.join('%+6.1f' % (row[c] - base[c]) for c in COLS))


def check():
    print('%-26s' % '' + ' '.join('%6s' % c for c in COLS))
    base_model = base_real = None
    for name, kw, real in MEASURED:
        row = play(**kw)
        real = dict(zip(COLS, real))
        if base_model is None:
            base_model, base_real = row, real
            show(name + ' measured', real)
            show(name + ' model', row)
        else:
            show(name + ' measured - os10', real, base_real)
            show(name + ' model    - os10', row, base_model)


def flat():
    print('%-26s' % 'hold while building' + ' '.join('%6s' % c for c in COLS) + '   work')
    for chain in (False, True):
        for h in (0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 16):
            row = play(chain=chain, build=h, early=6)
            print('%-26s' % ('%s%d' % ('chain ' if chain else '', h)) + ' '.join('%6.1f' % row[c] for c in COLS)
                  + ' %6.0f' % row['work'])


def seeds():
    print('%-26s' % 'seeded / hold' + ' '.join('%6s' % c for c in COLS) + '   work')
    for chain in (False, True):
        for h in (4, 6, 8):
            base = play(chain=chain, build=h, early=6)
            for s in (0, 1, 2, 3, 4, 6, 8):
                if s > h:
                    continue
                row = play(chain=chain, build=h, early=6, seed=s)
                print('%-26s' % ('%s%d / %d' % ('chain ' if chain else '', s, h)) + ' '.join(
                    '%+6.1f' % (row[c] - base[c]) if s else '%6.1f' % row[c] for c in COLS) + ' %6.0f' % row['work'])


def search():
    goals = {
        'units at 5 min': lambda r: r['u300'],
        'warriors at 6 min': lambda r: r['w360'],
        'warriors at 8 min': lambda r: r['w480'],
        'warriors at 10 min': lambda r: r['w600'],
        'weapon work by 10 min': lambda r: r['work'],
        'warriors at 6 + 8 + 10': lambda r: r['w360'] + r['w480'] + r['w600'],
    }
    for chain in (False, True):
        print('chain' if chain else 'no chain')
        for name, goal in goals.items():
            # a hold for each number of standing quarters before the armory (1..4), then early, mid, late; and seeds
            table = [6, 6, 6, 6]
            rest = [6, 4, 8]
            seed = [0, 0, 0, 0, 0, 0]
            best = None

            def value():
                return goal(Sim(Policy(table=table, early=rest[0], mid=rest[1], late=rest[2], seed_table=seed),
                                chain=chain).run().row())

            best = value()
            for sweep in range(4):
                changed = False
                for vec, lo, hi in ((table, 0, 16), (rest, 0, 16), (seed, 0, 12)):
                    for i in range(len(vec)):
                        keep = vec[i]
                        for v in range(lo, hi + 1):
                            vec[i] = v
                            x = value()
                            if x > best + 1e-9:
                                best, keep, changed = x, v, True
                        vec[i] = keep
                if not changed:
                    break
            row = Sim(Policy(table=table, early=rest[0], mid=rest[1], late=rest[2], seed_table=seed), chain=chain).run().row()
            print('  %-24s %7.1f | holds while building %s, then %s | seeds %s | u300 %.0f w %.0f/%.0f/%.0f armory %.0f'
                  % (name, best, table, rest, seed, row['u300'], row['w360'], row['w480'], row['w600'], row['armory']))


if __name__ == '__main__':
    what = sys.argv[1] if len(sys.argv) > 1 else 'check'
    {'check': check, 'flat': flat, 'seed': seeds, 'search': search}[what]()
