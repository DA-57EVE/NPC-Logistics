package com.npclogistics.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.npclogistics.item.LocationTokenItem;
import com.npclogistics.item.WorkGogglesItem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class TokenBeamRenderer {

    private static final float BEAM_WIDTH  = 0.08f;
    private static final int   BEAM_HEIGHT = 20;

    private record BeamEntry(BlockPos pos, LocationTokenItem.TokenType type) {}

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(TokenBeamRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        if (!(client.player.getEquippedStack(EquipmentSlot.HEAD).getItem() instanceof WorkGogglesItem)) return;

        List<BeamEntry> beams = new ArrayList<>();
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);
            if (stack.getItem() instanceof LocationTokenItem token && LocationTokenItem.hasPos(stack)) {
                BlockPos pos = LocationTokenItem.getPos(stack);
                if (pos != null) beams.add(new BeamEntry(pos, token.tokenType));
            }
        }
        if (beams.isEmpty()) return;

        Vec3d camPos   = context.camera().getPos();
        MatrixStack ms = context.matrixStack();
        Tessellator   tes = Tessellator.getInstance();
        BufferBuilder buf = tes.getBuffer();

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        ms.push();
        ms.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f mat = ms.peek().getPositionMatrix();

        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        for (BeamEntry beam : beams) {
            float[] c  = beamColor(beam.type());
            float   cx = beam.pos().getX() + 0.5f;
            float   y0 = beam.pos().getY();
            float   y1 = beam.pos().getY() + BEAM_HEIGHT;
            float   cz = beam.pos().getZ() + 0.5f;
            float   a  = 0.55f;

            // X-axis face
            buf.vertex(mat, cx - BEAM_WIDTH, y0, cz).color(c[0], c[1], c[2], a).next();
            buf.vertex(mat, cx + BEAM_WIDTH, y0, cz).color(c[0], c[1], c[2], a).next();
            buf.vertex(mat, cx + BEAM_WIDTH, y1, cz).color(c[0], c[1], c[2], a).next();
            buf.vertex(mat, cx - BEAM_WIDTH, y1, cz).color(c[0], c[1], c[2], a).next();

            // Z-axis face
            buf.vertex(mat, cx, y0, cz - BEAM_WIDTH).color(c[0], c[1], c[2], a).next();
            buf.vertex(mat, cx, y0, cz + BEAM_WIDTH).color(c[0], c[1], c[2], a).next();
            buf.vertex(mat, cx, y1, cz + BEAM_WIDTH).color(c[0], c[1], c[2], a).next();
            buf.vertex(mat, cx, y1, cz - BEAM_WIDTH).color(c[0], c[1], c[2], a).next();
        }

        tes.draw();
        ms.pop();

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static float[] beamColor(LocationTokenItem.TokenType type) {
        return switch (type) {
            case COLLECT -> new float[]{ 0.2f, 0.85f, 0.3f };  // green
            case DEPOSIT -> new float[]{ 0.9f, 0.25f, 0.2f };  // red
            case JOBSITE -> new float[]{ 1.0f, 0.55f, 0.1f };  // orange
            case CRAFT   -> new float[]{ 1.0f, 0.75f, 0.1f };  // gold
            case BED     -> new float[]{ 0.5f, 0.3f, 0.95f };  // purple
        };
    }
}
