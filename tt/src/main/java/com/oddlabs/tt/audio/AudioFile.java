package com.oddlabs.tt.audio;

import com.oddlabs.tt.global.Headless;
import com.oddlabs.tt.resource.File;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class AudioFile extends File<Audio> {
    public AudioFile(@NonNull String location) {
        super(location);
    }

    /** Every sound, headless: the simulation only passes sounds on to players, which play nothing there. */
    private static final Audio SILENT = new Audio() {
    };

    @Override
    public @NonNull Audio get() throws UncheckedIOException {
        if (Headless.ENABLED) {
            return SILENT; // opens no audio device and decodes nothing
        }
        try {
            return AudioManager.getManager().createAudio(getURL());
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not load " + this.getURL(), ex);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof AudioFile && super.equals(o);
    }
}
