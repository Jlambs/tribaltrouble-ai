package com.oddlabs.tt.render;

import com.oddlabs.geometry.AnimationInfo;
import com.oddlabs.tt.camera.CameraState;
import com.oddlabs.tt.global.Headless;
import com.oddlabs.tt.render.state.RenderContext;
import com.oddlabs.tt.resource.Resources;
import com.oddlabs.tt.resource.SpriteFile;
import com.oddlabs.tt.util.BoundingBox;
import com.oddlabs.tt.util.Target;
import com.oddlabs.util.Utils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class RenderQueues implements AutoCloseable {
    private final List<@NonNull SpriteRenderer> sprite_renderers = new ArrayList<>();
    private final List<@NonNull SpriteRenderer> blend_sprite_renderers = new ArrayList<>();
    private final List<@NonNull SpriteRenderer> plant_renderers = new ArrayList<>();

    private final List<@NonNull SpriteRenderer> sprite_list_lookup = new ArrayList<>();
    private final List<@NonNull ShadowListRenderer> shadow_renderer_lookup = new ArrayList<>();
    private final Map<@NonNull Supplier<@NonNull Texture @NonNull []>, @NonNull ShadowListKey> desc_to_shadow_key = new HashMap<>();
    private final List<@NonNull Texture> texture_lookup = new ArrayList<>();
    // Headless (Headless.ENABLED) the queues only hand out keys: nothing is loaded into the GPU, no shader is built,
    // and a sprite key carries only what the simulation reads, its animation types.
    private final @Nullable InstancedSpriteRenderer spriteRenderer = Headless.ENABLED ? null : new InstancedSpriteRenderer();
    /** Headless: the animation types of each sprite file, loaded once. */
    private final Map<@NonNull SpriteFile, int @NonNull []> headless_animation_types = new HashMap<>();

    public RenderQueues() {
    }

    public @NonNull TextureKey registerTexture(@NonNull Supplier<Texture[]> desc, int index) {
        TextureKey key = new TextureKey(texture_lookup.size());
        if (Headless.ENABLED) {
            texture_lookup.add(null);
            return key;
        }
        Texture[] textures = Resources.findResource(desc);
        texture_lookup.add(textures[index]);
        return key;
    }

    public @NonNull TextureKey registerTexture(@NonNull Supplier<Texture> desc) {
        TextureKey key = new TextureKey(texture_lookup.size());
        texture_lookup.add(Headless.ENABLED ? null : Resources.findResource(desc));
        return key;
    }

    @NonNull
    Texture getTexture(@NonNull TextureKey key) {
        return texture_lookup.get(key.getKey());
    }

    public @NonNull ShadowListKey registerRespondRenderer(@NonNull Supplier<@NonNull Texture @NonNull []> desc) {
        ShadowListKey key = desc_to_shadow_key.get(desc);
        if (key != null)
            return key;
        ShadowListRenderer renderer = Headless.ENABLED ? null : new TargetRespondRenderer(desc);
        return register(desc, renderer);
    }

    private @NonNull ShadowListKey register(@NonNull Supplier<@NonNull Texture @NonNull []> desc,
            @Nullable ShadowListRenderer renderer) {
        int index = shadow_renderer_lookup.size();
        shadow_renderer_lookup.add(renderer);
        ShadowListKey key = new ShadowListKey(index);
        desc_to_shadow_key.put(desc, key);
        return key;
    }

    public @NonNull ShadowListKey registerSelectableShadowList(@NonNull Supplier<@NonNull Texture @NonNull []> desc) {
        ShadowListKey key = desc_to_shadow_key.get(desc);
        if (key != null)
            return key;
        return register(desc, Headless.ENABLED ? null : new SelectableShadowRenderer(desc));
    }

    @NonNull
    ShadowListRenderer getShadowRenderer(@NonNull ShadowListKey key) {
        return shadow_renderer_lookup.get(key.getKey());
    }

    public @NonNull SpriteKey register(@NonNull SpriteFile sprite_file) {
        return register(sprite_file, 0);
    }

    public @NonNull SpriteKey register(@NonNull SpriteFile sprite_file, int tex_index) {
        int index = sprite_list_lookup.size();
        if (Headless.ENABLED) {
            sprite_list_lookup.add(null);
            int[] types = headless_animation_types.computeIfAbsent(sprite_file, RenderQueues::loadAnimationTypes);
            // the bounds are for drawing only (Model.updateBounds, which headless skips)
            BoundingBox[] bounds = new BoundingBox[types.length];
            Arrays.setAll(bounds, i -> new BoundingBox());
            return new SpriteKey(index, bounds, types);
        }
        SpriteList sprite_list = Resources.findResource(sprite_file);
        SpriteRenderer sprite_renderer = new SpriteRenderer(sprite_list, tex_index, spriteRenderer);
        sprite_list_lookup.add(sprite_renderer);
        registerSpriteRenderer(sprite_renderer, sprite_file.getLocation());
        AnimationInfo.AnimationType[] animation_types = sprite_list.getAnimationTypes();
        int[] type_array = new int[animation_types.length];
        for (int i = 0; i < animation_types.length; i++) {
            type_array[i] = animation_types[i].ordinal();
        }
        return new SpriteKey(index, sprite_list.getBounds(), type_array);
    }

    /** The animation types of a sprite file, as its SpriteList reads them, without building the sprites. */
    private static int @NonNull [] loadAnimationTypes(@NonNull SpriteFile sprite_file) {
        Object[] sprites_and_animations = Utils.loadObject(sprite_file.getURL());
        AnimationInfo[] animation_infos = (AnimationInfo[]) sprites_and_animations[1];
        int[] type_array = new int[animation_infos.length];
        for (int i = 0; i < animation_infos.length; i++) {
            type_array[i] = animation_infos[i].getType().ordinal();
        }
        return type_array;
    }

    public @NonNull SpriteRenderer getRenderer(@NonNull SpriteKey key) {
        return sprite_list_lookup.get(key.getKey());
    }

    public @NonNull InstancedSpriteRenderer getInstancedRenderer() {
        return spriteRenderer;
    }

    private void registerSpriteRenderer(@NonNull SpriteRenderer sprite_renderer, @NonNull String location) {
        if (sprite_renderer.getSpriteList().getSprite(0).modulateColor()) {
            blend_sprite_renderers.add(sprite_renderer);
        } else if (location.contains("plant") || location.contains("leaf")) {
            plant_renderers.add(sprite_renderer);
        } else {
            sprite_renderers.add(sprite_renderer);
        }
    }

    void getAllPicks(@NonNull List<@NonNull Target> pick_list) {
        for (SpriteRenderer spriteRenderer : sprite_renderers) {
            spriteRenderer.getAllPicks(pick_list);
        }
        for (SpriteRenderer spriteRenderer : plant_renderers) {
            spriteRenderer.getAllPicks(pick_list);
        }
    }

    void renderAll(@NonNull RenderContext context, @NonNull CameraState camera_state,
            @NonNull MatrixStack projectionStack) {
        for (SpriteRenderer spriteRenderer : sprite_renderers) {
            spriteRenderer.renderAll();
        }
        spriteRenderer.renderAll(context, camera_state, projectionStack);
    }

    void renderPlants(@NonNull RenderContext context, @NonNull CameraState camera_state,
            @NonNull MatrixStack projectionStack) {
        for (SpriteRenderer spriteRenderer : plant_renderers) {
            spriteRenderer.renderAll();
        }
        spriteRenderer.renderAll(context, camera_state, projectionStack);
    }

    void renderEmitterSprites(@NonNull RenderContext context, @NonNull CameraState camera_state,
            @NonNull MatrixStack projectionStack) {
        // Sprite-based particles (building debris) are deferred to SpriteListRenderer during
        // the emitter pass, which runs after the main renderAll(). Flush them here.
        for (SpriteRenderer spriteRenderer : sprite_renderers) {
            spriteRenderer.renderAll();
        }
        spriteRenderer.renderAll(context, camera_state, projectionStack);
    }

    void renderBlends(@NonNull RenderContext context, @NonNull CameraState camera_state,
            @NonNull MatrixStack projectionStack) {
        for (SpriteRenderer blendSpriteRenderer : blend_sprite_renderers) {
            blendSpriteRenderer.renderAll();
        }
        spriteRenderer.renderAll(context, camera_state, projectionStack);
    }

    void renderNoDetail() {
        for (SpriteRenderer spriteRenderer : sprite_renderers) {
            spriteRenderer.renderNoDetail();
        }
        for (SpriteRenderer spriteRenderer : plant_renderers) {
            spriteRenderer.renderNoDetail();
        }
        for (SpriteRenderer blendSpriteRenderer : blend_sprite_renderers) {
            blendSpriteRenderer.renderNoDetail();
        }
    }

    void renderShadows(@NonNull RenderContext context, @NonNull LandscapeRenderer renderer,
            @NonNull MatrixStack modelViewStack, @NonNull MatrixStack projectionStack) {
        for (ShadowListRenderer shadowListRenderer : shadow_renderer_lookup) {
            shadowListRenderer.renderShadows(context, renderer, modelViewStack, projectionStack);
        }
    }

    @Override
    public void close() {
        if (Headless.ENABLED)
            return; // nothing was created
        spriteRenderer.close();
        for (SpriteList spriteList : sprite_list_lookup.stream().map(
                SpriteRenderer::getSpriteList).distinct().toList()) {
            spriteList.close();
        }
        for (ShadowListRenderer shadowListRenderer : shadow_renderer_lookup) {
            shadowListRenderer.close();
        }
    }
}
