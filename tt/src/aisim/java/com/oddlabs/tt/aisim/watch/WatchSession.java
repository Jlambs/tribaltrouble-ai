package com.oddlabs.tt.aisim.watch;

import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.aikit.harness.Watch;
import com.oddlabs.tt.aisim.Aisim;
import com.oddlabs.tt.animation.AnimationManager;
import com.oddlabs.tt.gui.GUI;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.render.UIRenderer;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * The harness side of watching a game in the client (aikit.harness.Watch, which creates it by name): reads the watch
 * file, loads the game ({@link WatchGame}) and hands its clock to the renderer as the per-frame task.
 */
public final class WatchSession implements Watch.Session {
    public WatchSession() {
    }

    @Override
    public @NonNull UIRenderer load(@NonNull NetworkSelector network, @NonNull GUI gui, @NonNull GUIRoot gui_root,
            @NonNull Path watch_file) {
        // the load takes a while: the frame clock must not see it as time to catch up with
        AnimationManager.freezeTime();
        WatchGame game = new WatchGame(network, gui_root, read(watch_file));
        Renderer.setFrameTask(game::frame);
        return game.viewer().getRenderer();
    }

    static @NonNull WatchFile read(@NonNull Path watch_file) {
        try {
            return Aisim.JSON.readValue(watch_file.toFile(), WatchFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the watch file " + watch_file, e);
        }
    }
}
