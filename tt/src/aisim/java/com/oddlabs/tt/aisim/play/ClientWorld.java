package com.oddlabs.tt.aisim.play;

import com.oddlabs.matchmaking.Game;
import com.oddlabs.tt.audio.AbstractAudioPlayer;
import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.global.GlobalsInit;
import com.oddlabs.tt.global.Headless;
import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.landscape.AudioImplementation;
import com.oddlabs.tt.landscape.HeightMap;
import com.oddlabs.tt.landscape.LandscapeResources;
import com.oddlabs.tt.landscape.NotificationListener;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.landscape.WorldParameters;
import com.oddlabs.tt.model.RacesResources;
import com.oddlabs.tt.player.PlayerInfo;
import com.oddlabs.tt.procedural.Landscape;
import com.oddlabs.tt.render.RenderQueues;
import com.oddlabs.tt.resource.BlendInfo;
import com.oddlabs.tt.resource.NativeResource;
import com.oddlabs.tt.resource.StructureBlend;
import com.oddlabs.tt.resource.WorldInfo;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4fc;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

/**
 * The world of one game, built exactly as the client builds it for the same skirmish menu settings: the map as
 * TerrainMenu and IslandGenerator make it, the world and its players as Client makes them. When upstream changes
 * those, follow it here. Closing it frees the world's GL textures, which it has only when not headless.
 */
record ClientWorld(@NonNull World world, @NonNull Landscape landscape) implements AutoCloseable {

    /** Each player's starting units: the skirmish menu's default. */
    static final int STARTING_UNITS = Game.DEFAULT_INITIAL_UNIT_COUNT;

    // Loaded once per worker JVM by boot(), and shared by its games.
    private static @Nullable LandscapeResources landscape_resources;
    private static @Nullable RacesResources races_resources;

    /**
     * Readies the calling thread for worlds: silent settings and the game's resources. Workers run {@link Headless}:
     * the resources are then only what the simulation reads, with no GL context, textures, models or sounds. Without
     * it (-Dcom.oddlabs.tt.headless=false in AISIM_JAVA_OPTS) they are the client's, in a hidden GL context, with
     * OpenAL.
     */
    static void boot() {
        Settings settings = new Settings();
        settings.play_sfx = false;
        settings.play_music = false;
        Settings.setSettings(settings);
        if (!Headless.ENABLED) {
            openHiddenGlWindow();
            GlobalsInit.init();
        }
        RenderQueues queues = new RenderQueues();
        landscape_resources = World.loadCommon(queues);
        races_resources = World.loadInGame(queues);
    }

    /** Makes a hidden 64x64 OpenGL 4.1 core window's context current on this thread. */
    private static void openHiddenGlWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("glfwInit failed: aisim needs a desktop session with OpenGL 4.1");
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long window = GLFW.glfwCreateWindow(64, 64, "aisim", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new IllegalStateException("cannot create a hidden OpenGL 4.1 core window");
        }
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

    /** A new world for {@code job} with its players and their starting units, but no AIs yet. */
    static @NonNull ClientWorld create(@NonNull Job job) {
        Landscape landscape = newLandscape(job);
        return new ClientWorld(newWorld(job, landscape), landscape);
    }

    /** The map as TerrainMenu passes the menu settings: the seed squared, fixed start positions, no archipelago. */
    private static @NonNull Landscape newLandscape(@NonNull Job job) {
        float hills = job.hills() / 10f;
        float trees = job.trees() / 10f;
        float supplies = job.supplies() / 10f;
        int landscape_seed = job.seed() * job.seed();
        return new Landscape(job.slots(), job.meters(), job.terrainType(), detailAlpha(), hills, trees, supplies,
                landscape_seed, STARTING_UNITS, 0f, false);
    }

    private static @NonNull World newWorld(@NonNull Job job, @NonNull Landscape landscape) {
        int meters = job.meters();
        // IslandGenerator.generate without the GPU texture bake (the world never reads the textures):
        int colormap = colormapSize(meters);
        int chunks = colormap / 512; // IslandGenerator.TEXELS_PER_CHUNK
        WorldInfo info = new WorldInfo(meters, landscape.getSeaLevelMeters(), colormap, chunks, null, null, null,
                landscape.getHeight(), landscape.getTrees(), landscape.getPalmtrees(), landscape.getRock(),
                landscape.getIron(), landscape.getPlants(), landscape.getAccessGrid(), landscape.getDockGrid(),
                landscape.getWaterGrid(), landscape.getBuildGrid(), landscape.getIslandIds(),
                landscape.getIslandInfos(), landscape.getStartingLocations(), landscape.getBlendInfos());
        // spotless:off
        WorldParameters parameters = WorldParameters.builder()
                .initialGameSpeed(job.gameSpeed())
                .mapcode(job.mapcode())
                .initialUnitCount(STARTING_UNITS)
                .maxUnitCount(Game.DEFAULT_MAX_UNIT_COUNT)
                .mapSize(job.size())
                .maxBuildingCount(Game.DEFAULT_MAX_BUILDING_COUNT)
                .ships(false)
                .build();
        // spotless:on
        PlayerInfo[] players = new PlayerInfo[job.slots()];
        for (int slot = 0; slot < players.length; slot++) {
            players[slot] = playerInfo(job, slot);
        }
        AudioImplementation silent_audio = params -> new AbstractAudioPlayer(null, params) {
        };
        NotificationListener no_notifications = new NotificationListener() {
        };
        return World.newWorld(silent_audio, landscape_resources, races_resources, no_notifications, parameters, info,
                job.terrainType(), players, Landscape.getFogInfo(job.terrainType(), meters), colors(players.length));
    }

    /**
     * The players' colours: the client's palette by slot, as World.newWorld takes it, repeated for the slots past its
     * end (the menu's 12 players), which would otherwise get none. Colours only draw the game; they never change it.
     */
    private static @NonNull Vector4fc @NonNull [] colors(int players) {
        Vector4fc[] palette = Settings.getSettings().team_colours;
        Vector4fc[] colors = new Vector4fc[players];
        for (int slot = 0; slot < players; slot++) {
            colors[slot] = palette[slot % palette.length];
        }
        return colors;
    }

    /** The colormap size IslandGenerator computes: its grid units times getTexelsPerGridUnit(). */
    private static int colormapSize(int meters) {
        int grid_units = meters / HeightMap.METERS_PER_UNIT_GRID;
        int mip_shift = Globals.TEXTURE_MIP_SHIFT[Settings.getSettings().graphic_detail];
        int texels_per_grid_unit = Globals.TEXELS_PER_GRID_UNIT / (int) Math.pow(2, mip_shift);
        return grid_units * texels_per_grid_unit;
    }

    /** The detail alpha IslandGenerator computes; it only shapes the detail texture, but stays identical. */
    private static float detailAlpha() {
        // 256 and .15f are IslandGenerator's IDEAL_TEXELS_PER_DETAIL and IDEAL_DETAIL_ALPHA
        int detail_mip_level = 256 / Globals.DETAIL_SIZE - 1;
        return .15f * (float) Math.pow(Globals.LANDSCAPE_DETAIL_FADEOUT_FACTOR,
                Math.max(detail_mip_level - Globals.LANDSCAPE_DETAIL_FADEOUT_BASE_LEVEL, 0));
    }

    /**
     * The player of {@code slot}, its team and race as the job seats it, named as the game file and AI logs show it.
     */
    private static @NonNull PlayerInfo playerInfo(@NonNull Job job, int slot) {
        int race = job.vikings(slot) ? RacesResources.RACE_VIKINGS : RacesResources.RACE_NATIVES;
        String name = "s" + slot + ":" + job.spec(slot);
        return new PlayerInfo(job.team(slot), race, name);
    }

    /** Frees the world's GL textures (only the client's render loop would); headless it has none. */
    @Override
    public void close() {
        if (Headless.ENABLED) {
            return;
        }
        world.getHeightMap().getHeightTexture().close();
        for (BlendInfo blend : landscape.getBlendInfos()) {
            blend.getAlphaMap().close();
            if (blend instanceof StructureBlend structure) {
                structure.getStructureMap().close();
                structure.getNormalMap().close();
            }
        }
        NativeResource.processGLCleanupTasks();
    }
}
