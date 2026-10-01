package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.harness.AiSpec;
import com.oddlabs.tt.aisim.build.Pool;
import com.oddlabs.tt.aisim.play.ClientWorld;
import com.oddlabs.tt.aisim.play.Job;
import com.oddlabs.tt.landscape.World;
import com.oddlabs.tt.player.AI;
import com.oddlabs.tt.player.Player;
import com.oddlabs.tt.player.UnitInfo;
import org.jspecify.annotations.NonNull;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AIs of a watched game's seats, made as Match.createAIs makes them (slot order, the starting units of
 * ClientWorld), except that an AI named by a watch file loader comes from that loader's jar (a frozen {@code @TAG}, or
 * with {@code --ai-from-run} the recording snapshot's own package). The watch session and rewindcheck share it, so
 * both play the same game.
 */
final class SeatAis {
    private SeatAis() {
    }

    /** Creates and sets the AI of every slot of {@code world}, in slot order. */
    static void create(@NonNull Job job, @NonNull World world, @NonNull List<WatchFile.Loader> loaders) {
        Map<String, URLClassLoader> class_loaders = new LinkedHashMap<>();
        Map<String, WatchFile.Loader> by_name = new LinkedHashMap<>();
        for (WatchFile.Loader loader : loaders) {
            by_name.put(loader.name(), loader);
            String tag = loader.name().startsWith("@") ? loader.name().substring(1) : loader.name();
            // one loader per AI, shared by its seats, as Match shares one per frozen tag
            class_loaders.put(loader.name(), new Pool(tag, loader.entry(), loader.packageName(), "", Path.of(
                    loader.jar())).newLoader());
        }
        UnitInfo unit_info = ClientWorld.startingUnits();
        for (int slot = 0; slot < job.slots(); slot++) {
            Player player = world.getPlayers()[slot];
            String spec = job.spec(slot);
            String name = AiSpec.nameOf(spec);
            WatchFile.Loader loader = by_name.get(name);
            AI ai;
            if (loader != null) {
                try {
                    Class<?> type = Class.forName(loader.entry(), true, class_loaders.get(name));
                    ai = AiSpec.create(type, AiSpec.paramsOf(spec), player, unit_info);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("no class " + loader.entry() + " in " + loader.jar(), e);
                }
            } else if (name.startsWith("@")) {
                throw new IllegalStateException(
                        "slot " + slot + " plays " + spec + ", but there is no loader for " + name);
            } else {
                ai = AiSpec.create(spec, player, unit_info);
            }
            player.setAI(ai);
        }
    }
}
