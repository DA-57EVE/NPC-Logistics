package com.npclogistics.renderer;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders the Logistics Worker with the standard Minecraft biped (player) model.
 * Each entity can have its own skin URL (stored as TrackedData); textures are downloaded
 * once per URL and cached.  Falls back to the local texture while a download is in flight.
 */
@Environment(EnvType.CLIENT)
public class LogisticsWorkerRenderer
        extends BipedEntityRenderer<LogisticsWorkerEntity, BipedEntityModel<LogisticsWorkerEntity>> {

    private static final Identifier FALLBACK =
            new Identifier("npclogistics", "textures/entity/logistics_worker.png");

    /** URL → registered texture identifier.  Populated on the client thread after download. */
    private static final Map<String, Identifier> SKIN_CACHE = new ConcurrentHashMap<>();
    /** URLs currently being downloaded — prevents duplicate in-flight requests. */
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    public LogisticsWorkerRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public Identifier getTexture(LogisticsWorkerEntity entity) {
        String url = entity.getCustomSkinUrl();
        Identifier cached = SKIN_CACHE.get(url);
        if (cached != null) return cached;

        // Not cached yet — trigger a load (once) and use the fallback in the meantime.
        if (LOADING.add(url)) {
            loadSkin(url);
        }
        return FALLBACK;
    }

    private static void loadSkin(String url) {
        CompletableFuture.runAsync(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(10_000);
                conn.setRequestProperty("User-Agent", "NPClogistics/1.0 Minecraft-Fabric-Mod");
                byte[] data;
                try (InputStream in = conn.getInputStream()) {
                    data = in.readAllBytes();
                }
                MinecraftClient.getInstance().execute(() -> {
                    try {
                        NativeImage img = NativeImage.read(data);
                        NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
                        Identifier id = MinecraftClient.getInstance()
                                .getTextureManager()
                                .registerDynamicTexture("npclogistics_skin_" + Math.abs(url.hashCode()), tex);
                        SKIN_CACHE.put(url, id);
                        NPClogistics.LOGGER.info("[NPClogistics] Skin loaded: {}", url);
                    } catch (Exception e) {
                        SKIN_CACHE.put(url, FALLBACK);
                        NPClogistics.LOGGER.warn("[NPClogistics] Could not apply skin image ({}): {}", url, e.getMessage());
                    }
                });
            } catch (Exception e) {
                SKIN_CACHE.put(url, FALLBACK);
                NPClogistics.LOGGER.warn("[NPClogistics] Skin download failed ({}): {}", url, e.getMessage());
            }
        });
    }
}
