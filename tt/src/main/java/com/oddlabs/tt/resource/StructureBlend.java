package com.oddlabs.tt.resource;

import com.oddlabs.tt.global.Globals;
import com.oddlabs.tt.global.Headless;
import com.oddlabs.tt.render.Texture;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

public final class StructureBlend extends BlendInfo {
    // null headless, where nothing is drawn
    private final @Nullable Texture structure_map;
    private final @Nullable Texture normal_map;

    private @NonNull Texture createStructureMap(GLIntImage structure_image) {
        return new Texture(new GLIntImage[]{structure_image}, GL11.GL_RGB, GL11.GL_LINEAR, GL11.GL_LINEAR,
                GL11.GL_REPEAT, GL11.GL_REPEAT);
    }

    public StructureBlend(GLIntImage structure_image, GLIntImage normal_image, @NonNull GLByteImage alpha_image) {
        super(alpha_image, Globals.COMPRESSED_A_FORMAT);
        structure_map = Headless.ENABLED ? null : createStructureMap(structure_image);
        normal_map = Headless.ENABLED ? null : createStructureMap(normal_image);
    }

    public @Nullable Texture getStructureMap() {
        return structure_map;
    }

    public @Nullable Texture getNormalMap() {
        return normal_map;
    }

    private void bindStructure() {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, structure_map.getHandle());
    }

    @Override
    public void setup() {
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        bindAlpha();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        bindStructure();
    }

    @Override
    public void reset() {
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

    }
}
