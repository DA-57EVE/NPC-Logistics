package com.npclogistics.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.npclogistics.item.WorkGogglesItem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders route overlays when the player is wearing Work Goggles.
 *
 * For each nearby worker with an active route the renderer draws:
 *  - Coloured line segments connecting consecutive stops (green=collect, red=deliver, gold=both)
 *  - A wireframe box at each stop position
 *  - A billboard text label above each stop (sign text from an adjacent sign, or coordinates)
 *
 * Route data is pushed by the server every second via {@code ROUTE_DATA_SYNC} packets.
 * Entries older than 3 s are automatically skipped so stale routes fade away.
 */
public class RouteOverlayRenderer {

    // -- Data model ----------------------------------------------------------

    public record StopRenderData(BlockPos pos, int actionOrdinal, String label) {}

    public record RouteRenderData(
            String workerName,
            int currentStopIndex,
            List<StopRenderData> stops,
            long timestampMs) {}

    private static final Map<Integer, RouteRenderData> ROUTE_CACHE = new ConcurrentHashMap<>();
    private static final long STALE_MS = 3_000;

    public static void updateRoute(int entityId, RouteRenderData data) {
        ROUTE_CACHE.put(entityId, data);
    }

    public static void clearAll() {
        ROUTE_CACHE.clear();
    }

    // -- Registration --------------------------------------------------------

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RouteOverlayRenderer::render);
    }

    // -- Render entry --------------------------------------------------------

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (!(client.player.getEquippedStack(EquipmentSlot.HEAD).getItem() instanceof WorkGogglesItem)) return;
        if (ROUTE_CACHE.isEmpty()) return;

        long now = System.currentTimeMillis();
        Vec3d camPos = context.camera().getPos();
        MatrixStack matrices = context.matrixStack();
        float pulse = (float) (Math.sin(now / 400.0) * 0.3 + 0.7); // 0.4 – 1.0

        drawRouteLines(matrices, camPos, pulse, now);
        drawStopBoxes(matrices, camPos, pulse, now);
        drawStopLabels(matrices, camPos, context, now, client.textRenderer);
    }

    // -- Route lines ---------------------------------------------------------

    private static void drawRouteLines(MatrixStack matrices, Vec3d camPos, float pulse, long now) {
        Tessellator tes = Tessellator.getInstance();
        BufferBuilder buf = tes.getBuffer();

        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        RenderSystem.lineWidth(2.5f);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f pos = matrices.peek().getPositionMatrix();
        Matrix3f norm = matrices.peek().getNormalMatrix();

        buf.begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);

        for (RouteRenderData route : ROUTE_CACHE.values()) {
            if (now - route.timestampMs() > STALE_MS) continue;
            List<StopRenderData> stops = route.stops();
            if (stops.size() < 2) continue;

            for (int i = 0; i < stops.size(); i++) {
                StopRenderData from = stops.get(i);
                StopRenderData to = stops.get((i + 1) % stops.size());

                float x1 = from.pos().getX() + 0.5f, y1 = from.pos().getY() + 1.0f, z1 = from.pos().getZ() + 0.5f;
                float x2 = to.pos().getX() + 0.5f,   y2 = to.pos().getY() + 1.0f,   z2 = to.pos().getZ() + 0.5f;

                boolean activeLeg = route.currentStopIndex() == i;
                float[] c = actionColor(from.actionOrdinal());
                float bright = activeLeg ? pulse : 0.65f;

                float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
                float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 0.001f) { dx /= len; dy /= len; dz /= len; }

                buf.vertex(pos, x1, y1, z1).color(c[0]*bright, c[1]*bright, c[2]*bright, 0.85f).normal(norm, dx, dy, dz).next();
                buf.vertex(pos, x2, y2, z2).color(c[0]*bright, c[1]*bright, c[2]*bright, 0.85f).normal(norm, dx, dy, dz).next();
            }
        }

        tes.draw();
        matrices.pop();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
    }

    // -- Stop boxes ----------------------------------------------------------

    private static void drawStopBoxes(MatrixStack matrices, Vec3d camPos, float pulse, long now) {
        Tessellator tes = Tessellator.getInstance();
        BufferBuilder buf = tes.getBuffer();

        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        RenderSystem.lineWidth(1.5f);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f pos = matrices.peek().getPositionMatrix();
        Matrix3f norm = matrices.peek().getNormalMatrix();

        buf.begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);

        for (RouteRenderData route : ROUTE_CACHE.values()) {
            if (now - route.timestampMs() > STALE_MS) continue;
            for (int i = 0; i < route.stops().size(); i++) {
                StopRenderData stop = route.stops().get(i);
                float[] c = actionColor(stop.actionOrdinal());
                boolean active = route.currentStopIndex() == i;
                float bright = active ? pulse : 0.55f;

                float m = 0.05f;
                float x1 = stop.pos().getX() + m,     y1 = stop.pos().getY() + m,     z1 = stop.pos().getZ() + m;
                float x2 = stop.pos().getX() + 1 - m, y2 = stop.pos().getY() + 1 - m, z2 = stop.pos().getZ() + 1 - m;
                drawWireBox(buf, pos, norm, x1, y1, z1, x2, y2, z2, c[0]*bright, c[1]*bright, c[2]*bright, 0.9f);
            }
        }

        tes.draw();
        matrices.pop();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
    }

    // -- Stop labels ---------------------------------------------------------

    private static void drawStopLabels(MatrixStack matrices, Vec3d camPos,
                                        WorldRenderContext context, long now,
                                        TextRenderer textRenderer) {
        VertexConsumerProvider.Immediate textVcp =
                MinecraftClient.getInstance().getBufferBuilders().getEntityVertexConsumers();

        for (RouteRenderData route : ROUTE_CACHE.values()) {
            if (now - route.timestampMs() > STALE_MS) continue;
            for (StopRenderData stop : route.stops()) {
                String label = stop.label().isEmpty()
                        ? stop.pos().getX() + ", " + stop.pos().getY() + ", " + stop.pos().getZ()
                        : stop.label();

                double wx = stop.pos().getX() + 0.5 - camPos.x;
                double wy = stop.pos().getY() + 2.2  - camPos.y;
                double wz = stop.pos().getZ() + 0.5 - camPos.z;

                matrices.push();
                matrices.translate(wx, wy, wz);
                matrices.multiply(context.camera().getRotation());
                matrices.scale(-0.025f, -0.025f, 0.025f);

                float tx = -textRenderer.getWidth(label) / 2.0f;
                textRenderer.draw(label, tx, 0, 0xFFFFFF, false,
                        matrices.peek().getPositionMatrix(),
                        textVcp,
                        TextRenderer.TextLayerType.SEE_THROUGH,
                        0x55000000,
                        LightmapTextureManager.MAX_LIGHT_COORDINATE);

                textVcp.draw();
                matrices.pop();
            }
        }
    }

    // -- Helpers -------------------------------------------------------------

    private static void drawWireBox(BufferBuilder buf, Matrix4f pos, Matrix3f norm,
                                     float x1, float y1, float z1,
                                     float x2, float y2, float z2,
                                     float r, float g, float b, float a) {
        // Bottom face
        line(buf, pos, norm, x1, y1, z1, x2, y1, z1, r, g, b, a);
        line(buf, pos, norm, x2, y1, z1, x2, y1, z2, r, g, b, a);
        line(buf, pos, norm, x2, y1, z2, x1, y1, z2, r, g, b, a);
        line(buf, pos, norm, x1, y1, z2, x1, y1, z1, r, g, b, a);
        // Top face
        line(buf, pos, norm, x1, y2, z1, x2, y2, z1, r, g, b, a);
        line(buf, pos, norm, x2, y2, z1, x2, y2, z2, r, g, b, a);
        line(buf, pos, norm, x2, y2, z2, x1, y2, z2, r, g, b, a);
        line(buf, pos, norm, x1, y2, z2, x1, y2, z1, r, g, b, a);
        // Vertical edges
        line(buf, pos, norm, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(buf, pos, norm, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(buf, pos, norm, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(buf, pos, norm, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private static void line(BufferBuilder buf, Matrix4f pos, Matrix3f norm,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              float r, float g, float b, float a) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 0.001f) { dx /= len; dy /= len; dz /= len; }
        buf.vertex(pos, x1, y1, z1).color(r, g, b, a).normal(norm, dx, dy, dz).next();
        buf.vertex(pos, x2, y2, z2).color(r, g, b, a).normal(norm, dx, dy, dz).next();
    }

    private static float[] actionColor(int ordinal) {
        return switch (ordinal) {
            case 1  -> new float[]{ 0.9f, 0.25f, 0.2f }; // DELIVER — red
            case 2  -> new float[]{ 1.0f, 0.75f, 0.1f }; // BOTH — gold
            default -> new float[]{ 0.2f, 0.85f, 0.3f }; // COLLECT — green
        };
    }
}
