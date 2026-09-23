package com.oddlabs.tt.player.ai.sim;

import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.model.Action;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.model.behaviour.AttackController;
import com.oddlabs.tt.model.behaviour.HuntController;
import com.oddlabs.tt.model.behaviour.IdleController;
import com.oddlabs.tt.pathfinder.UnitGrid;
import com.oddlabs.tt.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Staged fights between two groups on an empty patch of a generated map, to measure how battles play out.
 *
 * <p>Armies are written like "19i" or "7r4i3c10p": counts of rock, iron and chicken warriors and peons.
 */
final class BattleLab {
    enum Mode {
        BOTH_ATTACK,
        A_ATTACKS,
        B_ATTACKS,
        BOTH_ATTACK_A_REORDERED
    }

    /**
     * Runs one fight between army a, placed south of (x, y), and army b placed north of it, gap cells apart.
     * Returns {survivors a, survivors b, seconds}.
     */
    static int[] fight(World world, int x, int y, int gap, String a_spec, String b_spec, Mode mode) {
        Player[] players = world.getPlayers();
        List<Unit> a = spawn(players[0], x, y - gap / 2, a_spec);
        List<Unit> b = spawn(players[1], x, y + gap / 2, b_spec);
        boolean a_attacks = mode != Mode.B_ATTACKS;
        boolean b_attacks = mode != Mode.A_ATTACKS;
        if (a_attacks)
            order(players[0], a, x, y + gap / 2);
        if (b_attacks)
            order(players[1], b, x, y - gap / 2);
        int ticks = 0;
        int max_ticks = (int) (120f / AnimationManager.ANIMATION_SECONDS_PER_TICK);
        while (ticks < max_ticks && alive(a) > 0 && alive(b) > 0) {
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
            ticks++;
            if (ticks % 50 != 0)
                continue;
            if (mode == Mode.BOTH_ATTACK_A_REORDERED)
                reorder(players[0], a, b, true);
            else if (a_attacks)
                reorder(players[0], a, b, false);
            if (b_attacks)
                reorder(players[1], b, a, false);
        }
        int[] result = {alive(a), alive(b), (int) (ticks * AnimationManager.ANIMATION_SECONDS_PER_TICK)};
        kill(a);
        kill(b);
        for (int i = 0; i < 100; i++)
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
        return result;
    }

    private static List<Unit> spawn(Player p, int x, int y, String spec) {
        List<Unit> units = new ArrayList<>();
        int count = 0;
        for (int k = 0; k < spec.length(); k++) {
            char ch = spec.charAt(k);
            if (Character.isDigit(ch)) {
                count = count * 10 + (ch - '0');
                continue;
            }
            int template = switch (ch) {
                case 'r' -> Race.UNIT_WARRIOR_ROCK;
                case 'i' -> Race.UNIT_WARRIOR_IRON;
                case 'c' -> Race.UNIT_WARRIOR_RUBBER;
                case 'p' -> Race.UNIT_PEON;
                default -> throw new IllegalArgumentException(spec);
            };
            for (int i = 0; i < count; i++)
                units.add(new Unit(p, UnitGrid.coordinateFromGrid(x), UnitGrid.coordinateFromGrid(y), null,
                        p.getRace().getUnitTemplate(template)));
            count = 0;
        }
        return units;
    }

    private static void order(Player p, List<Unit> units, int x, int y) {
        p.setLandscapeTarget(units.toArray(new Selectable<?>[0]), x, y, Action.ATTACK, true);
    }

    /** Sends idle units (or, when all is set, every unit not already fighting) at the middle of the enemy. */
    private static void reorder(Player p, List<Unit> units, List<Unit> enemies, boolean all) {
        int ex = 0;
        int ey = 0;
        int n = 0;
        for (Unit e : enemies) {
            if (!e.isDead()) {
                ex += e.getGridX();
                ey += e.getGridY();
                n++;
            }
        }
        if (n == 0)
            return;
        List<Selectable<?>> free = new ArrayList<>();
        for (Unit u : units) {
            if (u.isDead())
                continue;
            var c = u.getCurrentController();
            if (c instanceof HuntController || c instanceof AttackController)
                continue;
            if (!all && !(u.getPrimaryController() instanceof IdleController && c instanceof IdleController))
                continue;
            free.add(u);
        }
        if (!free.isEmpty())
            p.setLandscapeTarget(free.toArray(new Selectable<?>[0]), ex / n, ey / n, Action.ATTACK, true);
    }

    private static int alive(List<Unit> units) {
        int n = 0;
        for (Unit u : units)
            if (!u.isDead())
                n++;
        return n;
    }

    private static void kill(List<Unit> units) {
        for (Unit u : units)
            if (!u.isDead())
                u.hit(1000, 0f, 1f, u.getOwner());
    }

    /**
     * Army a attacks army b, which waits spread on an arc facing it. A's idle and walking units are re-ordered every
     * second with the given tactic, the way the AI drives its army.
     */
    static int[] arcFight(World world, int x, int y, int gap, String a_spec, String b_spec, Tactics.Engage how) {
        return arcFight(world, x, y, gap, a_spec, b_spec, how, null);
    }

    /** As above; when b_how is set, b does not wait but closes in with that tactic too. */
    static int[] arcFight(World world, int x, int y, int gap, String a_spec, String b_spec, Tactics.Engage how,
            Tactics.Engage b_how) {
        Player[] players = world.getPlayers();
        List<Unit> a = spawn(players[0], x, y - gap / 2, a_spec);
        List<Unit> b = spawnArc(players[1], x, y + gap / 2, b_spec, 10);
        int ticks = 0;
        int max_ticks = (int) (120f / AnimationManager.ANIMATION_SECONDS_PER_TICK);
        while (ticks < max_ticks && alive(a) > 0 && alive(b) > 0) {
            if (ticks % 50 == 0) {
                List<Unit> free = new ArrayList<>();
                for (Unit u : a) {
                    if (u.isDead())
                        continue;
                    var c = u.getCurrentController();
                    if (c instanceof HuntController || c instanceof AttackController)
                        continue;
                    free.add(u);
                }
                Tactics.engage(players[0], free, b, how);
                if (b_how != null) {
                    List<Unit> b_free = new ArrayList<>();
                    for (Unit u : b) {
                        if (u.isDead())
                            continue;
                        var c = u.getCurrentController();
                        if (c instanceof HuntController || c instanceof AttackController)
                            continue;
                        b_free.add(u);
                    }
                    Tactics.engage(players[1], b_free, a, b_how);
                }
            }
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
            ticks++;
        }
        int[] result = {alive(a), alive(b), (int) (ticks * AnimationManager.ANIMATION_SECONDS_PER_TICK)};
        kill(a);
        kill(b);
        for (int i = 0; i < 100; i++)
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
        return result;
    }

    /**
     * Spawns an army along a half circle of the given radius around (x, y), hollow side towards smaller y, so that
     * an attacker coming from there walks into its embrace.
     */
    private static List<Unit> spawnArc(Player p, int x, int y, String spec, int radius) {
        List<Unit> units = spawn(p, x, y, spec);
        int n = units.size();
        for (int i = 0; i < n; i++) {
            double angle = Math.PI * (i + .5) / n;
            int ux = x + (int) Math.round(Math.cos(angle) * radius);
            int uy = y - (int) Math.round(radius * .3) + (int) Math.round(Math.sin(angle) * radius * .6);
            p.setLandscapeTarget(Selectable.newArray(units.get(i)), ux, uy, Action.MOVE, false);
        }
        // Let them walk into place.
        for (int t = 0; t < 250; t++)
            p.getWorld().tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
        return units;
    }

    static void run(World world, String[] matchups) {
        // The start of player 0 on the beach tends to be open and flat.
        Player p0 = world.getPlayers()[0];
        int x = UnitGrid.toGridCoordinate(p0.getStartX());
        int y = UnitGrid.toGridCoordinate(p0.getStartY());
        System.out.println(String.format("arena at %d,%d heights: a %.1f b %.1f", x, y,
                world.getHeightMap().getHeight(x, y - 15), world.getHeightMap().getHeight(x, y + 15)));
        for (String m : matchups) {
            String[] parts = m.split(":");
            String[] sides = parts[0].split("v");
            if (parts.length > 1 && parts[1].startsWith("ARC_")) {
                // ARC_<ours> waits in an arc; ARC_<ours>_<theirs> closes in from the arc with the second tactic.
                String[] tactics = parts[1].substring(4).split("_");
                Tactics.Engage how = Tactics.Engage.valueOf(tactics[0]);
                Tactics.Engage b_how = tactics.length > 1 ? Tactics.Engage.valueOf(tactics[1]) : null;
                int w0 = 0;
                int sum0 = 0;
                int sum1 = 0;
                int runs = 8;
                for (int r = 0; r < runs; r++) {
                    int[] res = arcFight(world, x, y, 40, sides[0], sides[1], how, b_how);
                    sum0 += res[0];
                    sum1 += res[1];
                    if (res[0] > 0 && res[1] == 0)
                        w0++;
                }
                System.out.println(String.format(
                        "%-14s attacks arc %-10s %-8s vs %-8s wins %d/%d, avg survivors %.1f" + " vs %.1f", sides[0],
                        sides[1], how, b_how == null ? "waiting" : b_how, w0, runs,
                        sum0 / (float) runs, sum1 / (float) runs));
                continue;
            }
            Mode mode = parts.length > 1 ? Mode.valueOf(parts[1]) : Mode.BOTH_ATTACK;
            int runs = 12;
            int w0 = 0;
            int w1 = 0;
            int sum0 = 0;
            int sum1 = 0;
            for (int r = 0; r < runs; r++) {
                // Alternate which side stands where to cancel out the terrain.
                boolean flip = r % 2 == 1;
                int[] res = fight(world, x, y, 30, flip ? sides[1] : sides[0], flip ? sides[0] : sides[1],
                        flip ? flip(mode) : mode);
                int s0 = flip ? res[1] : res[0];
                int s1 = flip ? res[0] : res[1];
                sum0 += s0;
                sum1 += s1;
                if (s0 > 0 && s1 == 0)
                    w0++;
                else if (s1 > 0 && s0 == 0)
                    w1++;
            }
            System.out.println(String.format("%-14s vs %-14s %-24s wins %2d-%2d, avg survivors %.1f vs %.1f",
                    sides[0], sides[1], mode, w0, w1, sum0 / (float) runs, sum1 / (float) runs));
        }
    }

    private static Mode flip(Mode mode) {
        return switch (mode) {
            case A_ATTACKS -> Mode.B_ATTACKS;
            case B_ATTACKS -> Mode.A_ATTACKS;
            default -> mode;
        };
    }

    private BattleLab() {
    }
}
