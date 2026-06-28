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
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class LivestockCollarRenderer {

    private static final int SEGMENTS = 24;

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(LivestockCollarRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;
        if (!(client.player.getEquippedStack(EquipmentSlot.HEAD).getItem() instanceof WorkGogglesItem)) return;

        Vec3d camPos = context.camera().getPos();
        float tickDelta = context.tickDelta();
        Box viewBox = new Box(camPos.subtract(64, 64, 64), camPos.add(64, 64, 64));

        List<MobEntity> tagged = new ArrayList<>();
        for (Entity e : client.world.getEntitiesByClass(MobEntity.class, viewBox, mob ->
                mob instanceof LivestockTaggable t && t.npclogistics_isTagged())) {
            tagged.add((MobEntity) e);
        }
        if (tagged.isEmpty()) return;

        MatrixStack matrices = context.matrixStack();

        Tessellator tes = Tessellator.getInstance();
        BufferBuilder buf = tes.getBuffer();

        RenderSystem.setShader(GameRenderer::getRenderTypeLinesProgram);
        RenderSystem.lineWidth(2.5f);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f posMatrix  = matrices.peek().getPositionMatrix();
        Matrix3f normMatrix = matrices.peek().getNormalMatrix();

        buf.begin(VertexFormat.DrawMode.LINES, VertexFormats.LINES);

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
                float nx = (float) Math.cos((a1 + a2) * 0.5);
                float nz = (float) Math.sin((a1 + a2) * 0.5);

                buf.vertex(posMatrix, x1, y, z1).color(colR, colG, colB, 1.0f).normal(normMatrix, nx, 0, nz).next();
                buf.vertex(posMatrix, x2, y, z2).color(colR, colG, colB, 1.0f).normal(normMatrix, nx, 0, nz).next();
            }
        }

        tes.draw();
        matrices.pop();

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);
    }
}
