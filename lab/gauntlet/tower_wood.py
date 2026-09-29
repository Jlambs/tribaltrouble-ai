#!/usr/bin/env python3
"""Tower site build times by trees near the site, from logged runs (tower_wood_drop mechanism check).

    python lab/gauntlet/tower_wood.py RUN [RUN ...] [--trees K] [--from S] [--to S]

For each run (aisim/runs/RUN, played with --logs) and game, reads the decision log lines
"plan tower#N at x,y trees=K", "placed tower#N at x,y" and "completed tower#N ... after Ss", and the counters
tower_wood_* from results.jsonl. A site counts as treeless when the plan line's 7-cell count is <= --trees (default
0) and it was placed where it was planned (a site moved after its plan line has an unknown count and is skipped).
Build time = completed - placed. Sites placed between --from and --to seconds (default all) are counted.
Prints per game: treeless sites placed / completed / median build s, the same for sites with trees, and the counters;
then the pooled medians per run.
"""
import json
import os
import re
import statistics
import sys

PLAN = re.compile(r"^\s*([0-9.]+) s\d+ AI\s+plan tower#(\d+) at (\d+),(\d+) trees=(\d+)")
PLACED = re.compile(r"^\s*([0-9.]+) s\d+ AI\s+placed tower#(\d+) at (\d+),(\d+)")
DONE = re.compile(r"^\s*([0-9.]+) s\d+ AI\s+completed tower#(\d+) at (\d+),(\d+) after (\d+)s")


def med(xs):
    return statistics.median(xs) if xs else float("nan")


def game_sites(path, max_trees, t0, t1):
    plans, placed, done = {}, {}, {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = PLAN.match(line)
            if m:
                plans[m.group(2)] = (int(m.group(3)), int(m.group(4)), int(m.group(5)))
                continue
            m = PLACED.match(line)
            if m:
                placed[m.group(2)] = (float(m.group(1)), int(m.group(3)), int(m.group(4)))
                continue
            m = DONE.match(line)
            if m:
                done[m.group(2)] = float(m.group(1))
    out = {"bare": [], "trees": [], "bare_n": 0, "trees_n": 0, "skipped": 0}
    for pid, (t, x, y) in placed.items():
        if not (t0 <= t <= t1) or pid not in plans:
            continue
        px, py, k = plans[pid]
        if (px, py) != (x, y):
            out["skipped"] += 1
            continue
        kind = "bare" if k <= max_trees else "trees"
        out[kind + "_n"] += 1
        if pid in done:
            out[kind].append(done[pid] - t)
    return out


def main():
    args = sys.argv[1:]
    max_trees, t0, t1 = 0, 0.0, 1e9
    runs = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--trees":
            max_trees = int(args[i + 1]); i += 2
        elif a == "--from":
            t0 = float(args[i + 1]); i += 2
        elif a == "--to":
            t1 = float(args[i + 1]); i += 2
        else:
            runs.append(a); i += 1
    for run in runs:
        d = run if os.path.isdir(run) else os.path.join("aisim", "runs", run)
        rows = {}
        with open(os.path.join(d, "results.jsonl"), encoding="utf-8") as f:
            for line in f:
                r = json.loads(line)
                rows[r["key"]] = r
        print(f"== {os.path.basename(d.rstrip('/'))} (treeless: trees<={max_trees}, placed {t0:g}..{t1:g} s)")
        print(f"{'game':10} {'bare pl/done':>12} {'med s':>6} {'trees pl/done':>13} {'med s':>6}  counters")
        pool_bare, pool_trees = [], []
        tot = {"bare_n": 0, "trees_n": 0, "bare": 0, "trees": 0}
        for key in sorted(rows, key=lambda k: rows[k]["seed"]):
            log = os.path.join(d, "g", key + "-ai-s0.log")
            if not os.path.exists(log):
                continue
            g = game_sites(log, max_trees, t0, t1)
            pool_bare += g["bare"]
            pool_trees += g["trees"]
            for k in ("bare_n", "trees_n"):
                tot[k] += g[k]
            tot["bare"] += len(g["bare"])
            tot["trees"] += len(g["trees"])
            c = rows[key]["teams"][0].get("counters", {})
            cs = " ".join(f"{k[len('tower_wood_'):]}={v}" for k, v in sorted(c.items()) if k.startswith("tower_wood"))
            print(f"{key:10} {g['bare_n']:5}/{len(g['bare']):<6} {med(g['bare']):6.0f} {g['trees_n']:6}/{len(g['trees']):<6}"
                  f" {med(g['trees']):6.0f}  {cs} {rows[key]['result']} t={rows[key]['t']:.0f}")
        print(f"{'all':10} {tot['bare_n']:5}/{tot['bare']:<6} {med(pool_bare):6.0f} {tot['trees_n']:6}/{tot['trees']:<6}"
              f" {med(pool_trees):6.0f}  mean bare {statistics.mean(pool_bare) if pool_bare else float('nan'):.0f}"
              f" mean trees {statistics.mean(pool_trees) if pool_trees else float('nan'):.0f}")


if __name__ == "__main__":
    main()
