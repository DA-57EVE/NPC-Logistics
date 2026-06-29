package com.npclogistics;

import com.npclogistics.client.network.ClientNetworking;
import com.npclogistics.client.renderer.LivestockCollarRenderer;
import com.npclogistics.client.renderer.RouteOverlayRenderer;
import com.npclogistics.client.renderer.TokenBeamRenderer;
import com.npclogistics.entity.ModEntities;
import com.npclogistics.renderer.LogisticsWorkerRenderer;
import com.npclogistics.screen.EquipmentScreen;
import com.npclogistics.screen.ModScreenHandlers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

public class NPClogisticsClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.LOGISTICS_WORKER, LogisticsWorkerRenderer::new);
        ClientNetworking.registerClientPackets();
        HandledScreens.register(ModScreenHandlers.EQUIPMENT, EquipmentScreen::new);
        RouteOverlayRenderer.register();
        LivestockCollarRenderer.register();
        TokenBeamRenderer.register();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RouteOverlayRenderer.clearAll());
    }
}
