package com.oddlabs.tt.aikit.harness;

import com.oddlabs.net.NetworkSelector;
import com.oddlabs.tt.form.ProgressForm;
import com.oddlabs.tt.global.Settings;
import com.oddlabs.tt.gui.GUI;
import com.oddlabs.tt.gui.GUIRoot;
import com.oddlabs.tt.render.UIRenderer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

/**
 * Watching an AI-only harness game in the game client ({@code ./aisim.sh watch RUN KEY}). The script starts the game
 * in developer mode with the system property {@value #PROPERTY} set to a watch file, which the harness wrote; the game
 * then loads that game in place of the main menu, with no human seated, so every player keeps the start slot it had in
 * the harness.
 *
 * <p>The session itself lives in the harness source set ({@value #SESSION_CLASS}), which the game reaches only through
 * {@link Session}, loaded by name: the game compiles and runs ({@code gradlew :tt:run}) without the harness, and
 * without the property nothing here does anything.
 */
public final class Watch {
    /** The system property naming the watch file; aisim.sh watch sets it. */
    public static final String PROPERTY = "aisim.watch";
    /** The harness class that implements {@link Session}, with a public no-argument constructor. */
    static final String SESSION_CLASS = "com.oddlabs.tt.aisim.watch.WatchSession";

    /** What the harness implements: loads the watched game and shows it. */
    public interface Session {
        /**
         * Builds the world and its viewer for the game the watch file describes, on the GL thread inside a
         * ProgressForm, and returns the viewer's renderer (as a LoadCallback does).
         */
        @NonNull
        UIRenderer load(@NonNull NetworkSelector network, @NonNull GUI gui, @NonNull GUIRoot gui_root,
                @NonNull Path watch_file);
    }

    private Watch() {
    }

    /** Whether this start of the game watches a harness game: the property is set and the game is in developer mode. */
    public static boolean requested() {
        return System.getProperty(PROPERTY) != null && Settings.getSettings().inDeveloperMode();
    }

    /**
     * Called by Renderer.run in place of the main menu when {@link #requested()}: sets the progress form that loads the
     * watched game and returns its load task, as the main menu's setup does.
     */
    public static @Nullable Runnable start(@NonNull NetworkSelector network, @NonNull GUI gui) {
        Path watch_file = Path.of(System.getProperty(PROPERTY)).toAbsolutePath();
        Session session = newSession();
        return ProgressForm.setProgressForm(network, gui, (GUIRoot gui_root) -> session.load(network, gui, gui_root,
                watch_file), true);
    }

    private static @NonNull Session newSession() {
        try {
            return Class.forName(SESSION_CLASS).asSubclass(Session.class).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            // the harness classes are only on the class path when ./aisim.sh watch starts the game
            throw new IllegalStateException(PROPERTY + " is set, but the harness classes are missing", e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof InvocationTargetException target ? target.getCause() : e;
            throw new IllegalStateException("cannot create " + SESSION_CLASS, cause);
        }
    }
}
