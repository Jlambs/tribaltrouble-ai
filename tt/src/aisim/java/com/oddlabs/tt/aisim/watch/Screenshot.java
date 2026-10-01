package com.oddlabs.tt.aisim.watch;

import com.oddlabs.tt.render.Renderer;
import com.oddlabs.tt.window.Window;
import org.jspecify.annotations.NonNull;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImageWrite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Saves the last rendered frame as a PNG. Called from the frame task, which runs before the next frame is drawn, the
 * frame just drawn is still in the back buffer then (the window swaps buffers right before drawing).
 */
final class Screenshot {
    private Screenshot() {
    }

    static void save(@NonNull Path file) throws IOException {
        Window window = Renderer.getRenderer().getWindow();
        int width = window.getWidth();
        int height = window.getHeight();
        ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        // opaque: the back buffer's alpha is whatever the last blend left
        for (int i = 3; i < pixels.capacity(); i += 4) {
            pixels.put(i, (byte) 0xFF);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        STBImageWrite.stbi_flip_vertically_on_write(true);
        if (!STBImageWrite.stbi_write_png(file.toString(), width, height, 4, pixels, width * 4)) {
            throw new IOException("cannot write " + file);
        }
        Clock.resetRenderContext();
    }
}
