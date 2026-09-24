package com.oddlabs.tt.player.ai.sim;

import com.oddlabs.matchmaking.Game;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.audio.AbstractAudioPlayer;
import com.oddlabs.tt.global.GlobalsInit;
import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.landscape.AudioImplementation;
import com.oddlabs.tt.landscape.LandscapeResources;
import com.oddlabs.tt.landscape.NotificationListener;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.landscape.WorldParameters;
import com.oddlabs.tt.model.Abilities;
import com.oddlabs.tt.model.Building;
import com.oddlabs.tt.model.Race;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.model.Selectable;
import com.oddlabs.tt.model.Unit;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.AdvancedAI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.PlayerInfo;
import com.oddlabs.tt.player.UnitInfo;
import com.oddlabs.tt.player.ai.ExpertAI;
import com.oddlabs.tt.procedural.Landscape;
import com.oddlabs.tt.render.RenderQueues;
import com.oddlabs.tt.resource.IslandGenerator;
import com.oddlabs.tt.resource.WorldGenerator;
import com.oddlabs.tt.resource.WorldInfo;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;

/**
 * Developer harness that plays AI-vs-AI matches without rendering, as fast as the simulation allows.
 *
 * <pre>
 * ./gradlew :tt:aiMatch --args="--games 4 --seed 1 --minutes 30 --a expert --b hard --size large --verbose"
 * </pre>
 *
 * <p>With {@code --vs 3} one A player faces three allied B players ({@code --ffa} puts everyone on their own team); A
 * takes each start position in turn.
 */
public final class AIMatchRunner {
    private static LandscapeResources landscape_resources;
    private static String overrides_a = "";
    private static boolean econ = false;
    /** Log of the material ratio of player 0 to player 1 at the end of the last game; ±3 for an elimination. */
    private static float last_log_ratio;
    private static float score_a;
    // Practice maps: tropical, as many trees and resources as the sliders allow, few hills.
    private static Landscape.TerrainType terrain = Landscape.TerrainType.NATIVE;
    private static float hills = .2f;
    private static float trees = 1f;
    private static float supplies = 1f;
    private static String overrides_b = "";
    private static RacesResources races_resources;
    /** Directory for the expert AIs' game logs, as in real games, or null. */
    private static String log_dir;
    /** Race of each player slot: v for vikings, n for natives; slots beyond the string are vikings. */
    private static String races = "vv";
    /** In lineup games: each team's material at the end of the last game, and whether teams are ranked by it. */
    private static boolean lineup_mode;
    private static float[] last_team_material = new float[0];

    public static void main(String[] args) throws IOException {
        int games = 1;
        int seed = 1;
        int minutes = 30;
        String a = "expert";
        String b = "hard";
        String dump_dir = null;
        boolean verbose = false;
        String battle = null;
        int size = 1024;
        int vs = 1;
        boolean ffa = false;
        String lineup = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--games" -> games = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Integer.parseInt(args[++i]);
                case "--minutes" -> minutes = Integer.parseInt(args[++i]);
                case "--a" -> a = args[++i];
                case "--b" -> b = args[++i];
                case "--dump" -> dump_dir = args[++i];
                case "--verbose" -> verbose = true;
                case "--econ" -> econ = true;
                case "--sites" -> ExpertAI.debugSites(true);
                case "--battle" -> battle = args[++i];
                case "--terrain" -> terrain = args[++i].equals(
                        "northern") ? Landscape.TerrainType.VIKING : Landscape.TerrainType.NATIVE;
                case "--hills" -> hills = Float.parseFloat(args[++i]);
                case "--trees" -> trees = Float.parseFloat(args[++i]);
                case "--supplies" -> supplies = Float.parseFloat(args[++i]);
                case "--pa" -> overrides_a = args[++i];
                case "--pb" -> overrides_b = args[++i];
                case "--races" -> races = args[++i];
                case "--log" -> log_dir = args[++i];
                case "--vs" -> vs = Integer.parseInt(args[++i]);
                case "--ffa" -> ffa = true;
                case "--lineup" -> lineup = args[++i];
                case "--size" -> size = switch (args[++i]) {
                    case "small" -> 256;
                    case "medium" -> 512;
                    case "enormous" -> 2048;
                    default -> 1024;
                };
                default -> throw new IllegalArgumentException("unknown flag " + args[i]);
            }
        }
        ExpertAI.debug = verbose;
        ExpertAI.strict = true;
        Settings.setSettings(new Settings());
        initHiddenContext();
        GlobalsInit.init();
        RenderQueues queues = new RenderQueues();
        landscape_resources = World.loadCommon(queues);
        races_resources = World.loadInGame(queues);

        if (battle != null) {
            BattleLab.run(createWorld(seed, size), battle.split(","));
            System.exit(0);
        }
        if (lineup != null) {
            playLineup(lineup, games, seed, minutes, size, dump_dir, verbose);
            System.exit(0);
        }
        int wins_a = 0;
        int wins_b = 0;
        int n = vs + 1;
        for (int g = 0; g < games; g++) {
            // Rotate A through the start positions so no AI always gets the same one.
            int a_slot = g % n;
            int game_seed = seed + g / n;
            String[] ais = new String[n];
            int[] teams = new int[n];
            for (int i = 0; i < n; i++) {
                ais[i] = i == a_slot ? a : b;
                teams[i] = ffa ? i : i == a_slot ? 0 : 1;
            }
            int result = play(game_seed, minutes, ais, teams, a_slot, size, dump_dir, verbose);
            int winner = result == -1 ? 0 : result == teams[a_slot] ? 1 : 2;
            score_a += last_log_ratio;
            if (winner == 1)
                wins_a++;
            else if (winner == 2)
                wins_b++;
            System.out.println(
                    "GAME " + g + " seed=" + game_seed + " swap=" + (a_slot != 0) + " slot=" + a_slot + " winner=" + (winner == 1 ? a + "(A)" : winner == 2 ? b + "(B)" : "draw"));
        }
        System.out.println(
                "SUMMARY " + a + "(A) " + wins_a + " - " + wins_b + " " + b + "(B), draws " + (games - wins_a - wins_b) + String.format(
                        " score %+.2f", score_a / games));
        System.exit(0);
    }

    /**
     * Team games from a lineup like {@code "expert,expert/class:x.UltraAI,class:x.UltraAI"}: teams separated by '/',
     * players within a team by ','. The players rotate through the start positions from game to game, so over as many
     * games as there are players each takes every position once.
     */
    private static void playLineup(String lineup, int games, int seed, int minutes, int size, String dump_dir,
            boolean verbose) throws IOException {
        lineup_mode = true;
        String[] team_specs = lineup.split("/");
        java.util.List<String> order = new java.util.ArrayList<>();
        java.util.List<Integer> order_teams = new java.util.ArrayList<>();
        String[] names = new String[team_specs.length];
        for (int t = 0; t < team_specs.length; t++) {
            String[] members = team_specs[t].split(",");
            StringBuilder name = new StringBuilder();
            for (String m : members) {
                order.add(m);
                order_teams.add(t);
                name.append(name.length() == 0 ? "" : "+").append(label(m));
            }
            names[t] = name.toString();
        }
        int n = order.size();
        int[] wins = new int[team_specs.length];
        int draws = 0;
        for (int g = 0; g < games; g++) {
            int shift = g % n;
            int game_seed = seed + g / n;
            String[] ais = new String[n];
            int[] teams = new int[n];
            for (int i = 0; i < n; i++) {
                ais[i] = order.get((i + shift) % n);
                teams[i] = order_teams.get((i + shift) % n);
            }
            int winner = play(game_seed, minutes, ais, teams, 0, size, dump_dir, verbose);
            if (winner >= 0)
                wins[winner]++;
            else
                draws++;
            StringBuilder material = new StringBuilder();
            for (int t = 0; t < last_team_material.length; t++)
                material.append(String.format(" %s=%.0f", names[t], last_team_material[t]));
            System.out.println(
                    "GAME " + g + " seed=" + game_seed + " shift=" + shift + " winner=" + (winner >= 0 ? "team" + winner + "(" + names[winner] + ")" : "draw") + " material" + material);
        }
        StringBuilder line = new StringBuilder("LINEUP");
        for (int t = 0; t < names.length; t++)
            line.append(" team").append(t).append("(").append(names[t]).append(")=").append(wins[t]);
        line.append(" draws=").append(draws).append(" games=").append(games);
        System.out.println(line);
    }

    private static World createWorld(int seed, int size) {
        WorldGenerator generator = new IslandGenerator(size, terrain, 0f, trees, supplies, seed * seed, false);
        PlayerInfo[] infos = new PlayerInfo[]{new PlayerInfo(0, RacesResources.RACE_VIKINGS, "red"), new PlayerInfo(1,
                RacesResources.RACE_VIKINGS, "blue")};
        WorldParameters params = WorldParameters.builder().initialGameSpeed(Game.GAMESPEED_NORMAL).mapcode(
                "").initialUnitCount(Game.DEFAULT_INITIAL_UNIT_COUNT).maxUnitCount(Game.DEFAULT_MAX_UNIT_COUNT).mapSize(
                        Game.SIZE_LARGE).maxBuildingCount(Game.DEFAULT_MAX_BUILDING_COUNT).ships(false).build();
        WorldInfo world_info = generator.generate(infos.length, params.getInitialUnitCount(), .5f);
        AudioImplementation audio = audio_params -> new AbstractAudioPlayer(null, audio_params) {
        };
        return World.newWorld(audio, landscape_resources, races_resources, new NotificationListener() {
        }, params, world_info, generator.getTerrainType(), infos, generator.getFogInfo());
    }

    private static int raceOf(int slot) {
        return slot < races.length() && races.charAt(
                slot) == 'n' ? RacesResources.RACE_NATIVES : RacesResources.RACE_VIKINGS;
    }

    /**
     * Plays one game and returns the winning team, or -1 for a draw. Sets last_log_ratio from the point of view of
     * the player in a_slot.
     */
    private static int play(int seed, int minutes, String[] ais, int[] teams, int a_slot, int size, String dump_dir,
            boolean verbose) throws IOException {
        WorldGenerator generator = new IslandGenerator(size, terrain, hills, trees, supplies, seed * seed, false);
        PlayerInfo[] infos = new PlayerInfo[ais.length];
        for (int i = 0; i < ais.length; i++)
            infos[i] = new PlayerInfo(teams[i], raceOf(i), label(ais[i]) + "#" + i);
        int map_size = switch (size) {
            case 256 -> Game.SIZE_SMALL;
            case 512 -> Game.SIZE_MEDIUM;
            case 2048 -> Game.SIZE_ENORMOUS;
            default -> Game.SIZE_LARGE;
        };
        WorldParameters params = WorldParameters.builder().initialGameSpeed(Game.GAMESPEED_NORMAL).mapcode(
                "").initialUnitCount(Game.DEFAULT_INITIAL_UNIT_COUNT).maxUnitCount(Game.DEFAULT_MAX_UNIT_COUNT).mapSize(
                        map_size).maxBuildingCount(Game.DEFAULT_MAX_BUILDING_COUNT).ships(false).build();
        WorldInfo world_info = generator.generate(infos.length, params.getInitialUnitCount(), .5f);
        AudioImplementation audio = audio_params -> new AbstractAudioPlayer(null, audio_params) {
        };
        World world = World.newWorld(audio, landscape_resources, races_resources, new NotificationListener() {
        }, params, world_info, generator.getTerrainType(), infos, generator.getFogInfo());
        UnitInfo unit_info = new UnitInfo(false, false, 0, false, params.getInitialUnitCount(), 0, 0, 0);
        Player[] players = world.getPlayers();
        for (int i = 0; i < players.length; i++)
            players[i].setAI(create(ais[i], players[i], unit_info));
        for (Player p : players)
            if (log_dir != null && p.getAI() instanceof ExpertAI expert)
                expert.logTo(java.nio.file.Path.of(log_dir, "game_" + seed));
        if (dump_dir != null) {
            MapDump.write(world, new File(dump_dir, "map_" + seed + "_start.png"), 2);
            for (int i = 0; i < players.length; i++) {
                int sx = com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(players[i].getStartX());
                int sy = com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(players[i].getStartY());
                MapDump.write(world, new File(dump_dir, "area_" + seed + "_p" + i + ".png"), 3, sx - 128, sy - 128,
                        256);
            }
        }
        long start = System.nanoTime();
        int ticks_per_minute = (int) (60f / AnimationManager.ANIMATION_SECONDS_PER_TICK);
        int winner = -1;
        int tick;
        for (tick = 0; tick < minutes * ticks_per_minute; tick++) {
            world.tick(AnimationManager.ANIMATION_SECONDS_PER_TICK);
            if (tick % (ticks_per_minute / 2) == 0 && tick >= 5 * ticks_per_minute) {
                java.util.Set<Integer> alive = new java.util.LinkedHashSet<>();
                for (int i = 0; i < players.length; i++)
                    if (players[i].isAlive() && hasBase(players[i]))
                        alive.add(teams[i]);
                if (alive.size() <= 1) {
                    winner = alive.isEmpty() ? -1 : alive.iterator().next();
                    break;
                }
            }
            if (econ && tick % ticks_per_minute == 0 && tick > 0) {
                int minute = tick / ticks_per_minute;
                if (minute % 2 == 0) {
                    StringBuilder line = new StringBuilder(String.format("ECON seed=%d m=%d", seed, minute));
                    for (Player p : players)
                        line.append(String.format(" %s=%.0f/%d", p, potential(p),
                                p.getUnitCountContainer().getNumSupplies()));
                    System.out.println(line);
                }
            }
            if (verbose && tick % ticks_per_minute == 0) {
                System.out.println("t=" + tick / ticks_per_minute + "m");
                for (Player p : players)
                    System.out.println("   " + p + ": " + summary(p));
            }
            if (dump_dir != null && ExpertAI.debug_battle != null && tick % 100 == 0) {
                int[] b = ExpertAI.debug_battle;
                ExpertAI.debug_battle = null;
                MapDump.write(world, new File(dump_dir, "battle_" + seed + "_" + tick / 50 + "s.png"), 6, b[0] - 40,
                        b[1] - 40, 80);
            }
            if (dump_dir != null && verbose && tick % ticks_per_minute == 0 && tick > 0 && tick <= 6 * ticks_per_minute)
                for (int i = 0; i < players.length; i++) {
                    int sx = com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(players[i].getStartX());
                    int sy = com.oddlabs.tt.pathfinder.UnitGrid.toGridCoordinate(players[i].getStartY());
                    MapDump.write(world, new File(dump_dir,
                            "base_" + seed + "_p" + i + "_" + tick / ticks_per_minute + "m.png"), 6, sx - 64, sy - 64,
                            128);
                }
        }
        // Material of A's team against everyone else.
        float v0 = 0f;
        float v1 = 0f;
        int other_team = -1;
        for (int i = 0; i < players.length; i++) {
            if (teams[i] == teams[a_slot]) {
                v0 += material(players[i]);
            } else {
                v1 += material(players[i]);
                other_team = teams[i];
            }
        }
        last_log_ratio = winner == teams[a_slot] ? 3f : winner != -1 ? -3f : Math.clamp((float) Math.log(
                (v0 + 1f) / (v1 + 1f)), -3f, 3f);
        if (lineup_mode) {
            int nt = 0;
            for (int t : teams)
                nt = Math.max(nt, t + 1);
            last_team_material = new float[nt];
            for (int i = 0; i < players.length; i++)
                last_team_material[teams[i]] += material(players[i]);
            if (winner == -1) {
                // Out of time: the team with clearly more material than any other wins.
                int best = 0;
                for (int t = 1; t < nt; t++)
                    if (last_team_material[t] > last_team_material[best])
                        best = t;
                boolean clear = true;
                for (int t = 0; t < nt; t++)
                    if (t != best && last_team_material[best] <= 1.5f * last_team_material[t])
                        clear = false;
                if (clear)
                    winner = best;
            }
        } else if (winner == -1) {
            // Out of time: the side with clearly more material wins.
            if (v0 > 1.5f * v1)
                winner = teams[a_slot];
            else if (v1 > 1.5f * v0)
                winner = other_team;
        }
        System.out.println("END t=" + String.format("%.1f", tick / (float) ticks_per_minute) + "m");
        for (Player p : players)
            System.out.println("   " + p + ": " + summary(p));
        if (dump_dir != null)
            MapDump.write(world, new File(dump_dir, "map_" + seed + "_end.png"), 2);
        System.out.println("elapsed " + (System.nanoTime() - start) / 1_000_000 + "ms");
        for (Player p : players) {
            AI ai = p.getAI();
            if (ai != null) {
                world.getAnimationManagerGameTime().removeAnimation(ai);
                world.getAnimationManagerRealTime().removeAnimation(ai);
            }
        }
        return winner;
    }

    /** A player whose armory and quarters are all gone has lost for practical purposes. */
    private static boolean hasBase(Player p) {
        if (p.getUnitCountContainer().getNumSupplies() > 12)
            return true;
        for (Selectable<?> s : p.getUnits().getSet())
            if (s instanceof Building b && b.isComplete() && b.getTemplate().getTemplateID() != Race.BUILDING_TOWER)
                return true;
        return false;
    }

    private static float material(Player p) {
        return p.getUnitCountContainer().getNumSupplies() + 15f * p.getBuildingCountContainer().getNumSupplies() + potential(
                p);
    }

    /** Warriors outside plus weapons ready in armories, weighted like the AI weighs them. */
    static float potential(Player p) {
        float s = 0f;
        for (Selectable<?> sel : p.getUnits().getSet()) {
            if (sel instanceof Unit u && u.getAbilities().hasAbilities(Abilities.THROW)) {
                Class<?> t = u.getWeaponFactory().getType();
                s += t == com.oddlabs.tt.model.weapon.RubberAxeWeapon.class ? 2f : t == com.oddlabs.tt.model.weapon.RockAxeWeapon.class ? .6f : 1f;
            } else if (sel instanceof Building b && b.isComplete()
                    && b.getTemplate().getTemplateID() == Race.BUILDING_ARMORY) {
                        int workers = b.getUnitContainer().getNumSupplies();
                        int c = Math.min(workers, b.getSupplyContainer(
                                com.oddlabs.tt.model.weapon.RubberAxeWeapon.class).getNumSupplies());
                        int i = Math.min(workers - c, b.getSupplyContainer(
                                com.oddlabs.tt.model.weapon.IronAxeWeapon.class).getNumSupplies());
                        int r = Math.min(workers - c - i, b.getSupplyContainer(
                                com.oddlabs.tt.model.weapon.RockAxeWeapon.class).getNumSupplies());
                        s += 2f * c + i + .6f * r;
                    } else if (sel instanceof Building b && b.isComplete()
                            && b.getTemplate().getTemplateID() == Race.BUILDING_TOWER && b.getUnitCount() > 0) {
                                s += 4f;
                            }
        }
        return s;
    }

    private static String summary(Player p) {
        int warriors = 0;
        int peons = 0;
        for (Selectable<?> s : p.getUnits().getSet()) {
            if (s instanceof Unit u) {
                if (u.getAbilities().hasAbilities(Abilities.THROW))
                    warriors++;
                else if (u.getAbilities().hasAbilities(Abilities.HARVEST))
                    peons++;
            }
        }
        String s = String.format("pot=%.0f ", potential(
                p)) + "units=" + p.getUnitCountContainer().getNumSupplies() + " bld=" + p.getBuildingCountContainer().getNumSupplies() + " out(w=" + warriors + " p=" + peons + ") kills=" + p.getUnitsKilled() + " lost=" + p.getUnitsLost() + " bdes=" + p.getBuildingsDestroyed() + (p.hasActiveChieftain() ? " CHIEF" : "");
        if (p.getAI() instanceof ExpertAI expert)
            s += "\n      " + expert.debugStatus();
        return s;
    }

    private static AI create(String type, Player p, UnitInfo unit_info) {
        return switch (type) {
            case "expert" -> new ExpertAI(p, unit_info);
            case "expertA" -> ExpertAI.withOverrides(p, unit_info, overrides_a);
            case "expertB" -> ExpertAI.withOverrides(p, unit_info, overrides_b);
            case "hard" -> new AdvancedAI(p, unit_info, AdvancedAI.DIFFICULTY_HARD);
            case "normal" -> new AdvancedAI(p, unit_info, AdvancedAI.DIFFICULTY_NORMAL);
            default -> createByClass(type, p, unit_info);
        };
    }

    /**
     * {@code class:<fully.qualified.Name>} or {@code class:<Name>:<params>}: any AI with a public (Player, UnitInfo)
     * or (Player, UnitInfo, String) constructor, such as another project's AI compiled onto the classpath.
     */
    private static AI createByClass(String type, Player p, UnitInfo unit_info) {
        if (!type.startsWith("class:"))
            throw new IllegalArgumentException("unknown ai " + type);
        String spec = type.substring("class:".length());
        int colon = spec.indexOf(':');
        String name = colon < 0 ? spec : spec.substring(0, colon);
        try {
            Class<?> c = Class.forName(name);
            if (colon >= 0)
                return (AI) c.getConstructor(Player.class, UnitInfo.class, String.class).newInstance(p, unit_info,
                        spec.substring(colon + 1));
            return (AI) c.getConstructor(Player.class, UnitInfo.class).newInstance(p, unit_info);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot create " + type, e);
        }
    }

    /** Short player name for an AI spec: the class's simple name for class specs. */
    private static String label(String type) {
        if (type.equals("expert"))
            return "Expert";
        if (type.equals("hard"))
            return "Hard";
        if (!type.startsWith("class:"))
            return type;
        String spec = type.substring("class:".length());
        int colon = spec.indexOf(':');
        String name = colon < 0 ? spec : spec.substring(0, colon);
        // Other projects' AIs by project, not class: fable's is called HardAI, easily mistaken for the stock one.
        if (name.contains(".fable."))
            return "Fable";
        if (name.contains(".ultra."))
            return "Ultra";
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static void initHiddenContext() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit())
            throw new IllegalStateException("glfwInit failed");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(64, 64, "ai-sim", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL)
            throw new IllegalStateException("could not create hidden window");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    private AIMatchRunner() {
    }
}
