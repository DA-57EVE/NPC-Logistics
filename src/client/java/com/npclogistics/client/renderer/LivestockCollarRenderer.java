package com.npclogistics.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.npclogistics.entity.LivestockTaggable;
import com.npclogistics.item.WorkGogglesItem;
import net.minecraft.entity.EquipmentSlot;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class LivestockCollarRenderer {

    private static final int   SEGMENTS  = 24;
    private static final float BAND_HALF = 0.05f; // 10 cm band height

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(LivestockCollarRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;
        if (!(client.player.getEquippedStack(EquipmentSlot.HEAD).getItem() instanceof WorkGogglesItem)) return;

        Vec3d camPos    = context.camera().getPos();
        float tickDelta = context.tickDelta();
        Box viewBox     = new Box(camPos.subtract(64, 64, 64), camPos.add(64, 64, 64));

        List<MobEntity> tagged = new ArrayList<>();
        for (Entity e : client.world.getEntitiesByClass(MobEntity.class, viewBox,
                mob -> mob instanceof LivestockTaggable t && t.npclogistics_isTagged())) {
            tagged.add((MobEntity) e);
        }
        if (tagged.isEmpty()) return;

        MatrixStack  matrices = context.matrixStack();
        Tessellator  tes      = Tessellator.getInstance();
        BufferBuilder buf     = tes.getBuffer();

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f posMatrix = matrices.peek().getPositionMatrix();

        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (MobEntity mob : tagged) {
            int   packed = ((LivestockTaggable) mob).npclogistics_getOwnerColor();
            float colR   = ((packed >> 16) & 0xFF) / 255f;
            float colG   = ((packed >>  8) & 0xFF) / 255f;
            float colB   = ( packed        & 0xFF) / 255f;

            Vec3d pos    = mob.getLerpedPos(tickDelta);
            float neckY  = (float) (mob.getHeight() * 0.75);
            float radius = mob.getWidth() * 0.5f;
            float y      = (float) pos.y + neckY;

            for (int i = 0; i < SEGMENTS; i++) {
                float a1 = (float) (i       * 2 * Math.PI / SEGMENTS);
                float a2 = (float) ((i + 1) * 2 * Math.PI / SEGMENTS);
                float x1 = (float) pos.x + (float) Math.cos(a1) * radius;
                float z1 = (float) pos.z + (float) Math.sin(a1) * radius;
                float x2 = (float) pos.x + (float) Math.cos(a2) * radius;
                float z2 = (float) pos.z + (float) Math.sin(a2) * radius;

                buf.vertex(posMatrix, x1, y - BAND_HALF, z1).color(colR, colG, colB, 0.9f).next();
                buf.vertex(posMatrix, x2, y - BAND_HALF, z2).color(colR, colG, colB, 0.9f).next();
                buf.vertex(posMatrix, x2, y + BAND_HALF, z2).color(colR, colG, colB, 0.9f).next();
                buf.vertex(posMatrix, x1, y + BAND_HALF, z1).color(colR, colG, colB, 0.9f).next();
            }
        }

        tes.draw();
        matrices.pop();

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
