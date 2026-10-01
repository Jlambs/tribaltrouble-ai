package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.aikit.AiLog;
import com.oddlabs.tt.delegate.GameStatsDelegate;
import com.oddlabs.tt.delegate.InGameMainMenu;
import com.oddlabs.tt.gui.Group;
import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.viewer.InGameInfo;
import com.oddlabs.tt.viewer.WorldViewer;
import org.jspecify.annotations.NonNull;

/**
 * The game info of a watched game: single-player, unrated, fixed start positions. The Esc menu adds nothing to its
 * Resume, Options and Quit; ending the game (which nothing in the watch view offers) or closing its viewer quits the
 * program, as the window's close does: watching starts from the command line, and there is no menu world to go back
 * to.
 */
final class WatchInGameInfo implements InGameInfo {
    @Override
    public void addGUI(@NonNull WorldViewer viewer, @NonNull InGameMainMenu menu, @NonNull Group game_infos) {
    }

    @Override
    public void addGameOverGUI(@NonNull WorldViewer viewer, @NonNull GameStatsDelegate delegate, int header_y,
            @NonNull Group buttons) {
    }

    @Override
    public void abort(@NonNull WorldViewer viewer) {
        viewer.close();
    }

    @Override
    public void close(@NonNull WorldViewer viewer) {
        Renderer.setFrameTask(null);
        AiLog.endFor(viewer.getWorld());
        Renderer.shutdown();
    }

    @Override
    public boolean isMultiplayer() {
        return false;
    }

    @Override
    public boolean isRated() {
        return false;
    }

    @Override
    public float getRandomStartPosition() {
        return 0f;
    }
}
