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
    /** Race of each player slot: v for vikings, n for natives. */
    private static String races = "vv";

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
        int wins_a = 0;
        int wins_b = 0;
        for (int g = 0; g < games; g++) {
            // Alternate sides so neither AI always gets the same start.
            boolean swap = g % 2 == 1;
            int game_seed = seed + g / 2;
            int result = play(game_seed, minutes, swap ? b : a, swap ? a : b, size, dump_dir, verbose);
            int winner = result == 0 ? (swap ? 2 : 1) : result == 1 ? (swap ? 1 : 2) : 0;
            score_a += swap ? -last_log_ratio : last_log_ratio;
            if (winner == 1)
                wins_a++;
            else if (winner == 2)
                wins_b++;
            System.out.println(
                    "GAME " + g + " seed=" + game_seed + " swap=" + swap + " winner=" + (winner == 1 ? a + "(A)" : winner == 2 ? b + "(B)" : "draw"));
        }
        System.out.println(
                "SUMMARY " + a + "(A) " + wins_a + " - " + wins_b + " " + b + "(B), draws " + (games - wins_a - wins_b) + String.format(
                        " score %+.2f", score_a / games));
        System.exit(0);
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
        return races.charAt(slot) == 'n' ? RacesResources.RACE_NATIVES : RacesResources.RACE_VIKINGS;
    }

    /** Plays one game and returns the index of the winning player, or -1 for a draw. */
    private static int play(int seed, int minutes, String ai0, String ai1, int size, String dump_dir,
            boolean verbose) throws IOException {
        WorldGenerator generator = new IslandGenerator(size, terrain, hills, trees, supplies, seed * seed, false);
        PlayerInfo[] infos = new PlayerInfo[]{new PlayerInfo(0, raceOf(0), ai0 + "#0"), new PlayerInfo(1, raceOf(1),
                ai1 + "#1")};
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
        players[0].setAI(create(ai0, players[0], unit_info));
        players[1].setAI(create(ai1, players[1], unit_info));
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
                boolean alive0 = players[0].isAlive() && hasBase(players[0]);
                boolean alive1 = players[1].isAlive() && hasBase(players[1]);
                if (!alive0 || !alive1) {
                    winner = alive0 ? 0 : alive1 ? 1 : -1;
                    break;
                }
            }
            if (econ && tick % ticks_per_minute == 0 && tick > 0) {
                int minute = tick / ticks_per_minute;
                if (minute % 2 == 0)
                    System.out.println(String.format("ECON seed=%d m=%d %s=%.0f/%d %s=%.0f/%d", seed, minute,
                            players[0], potential(players[0]), players[0].getUnitCountContainer().getNumSupplies(),
                            players[1], potential(players[1]), players[1].getUnitCountContainer().getNumSupplies()));
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
        float v0 = material(players[0]);
        float v1 = material(players[1]);
        last_log_ratio = winner == 0 ? 3f : winner == 1 ? -3f : Math.clamp((float) Math.log((v0 + 1f) / (v1 + 1f)), -3f,
                3f);
        if (winner == -1) {
            // Out of time: the side with clearly more material wins.
            if (v0 > 1.5f * v1)
                winner = 0;
            else if (v1 > 1.5f * v0)
                winner = 1;
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
            default -> throw new IllegalArgumentException("unknown ai " + type);
        };
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
