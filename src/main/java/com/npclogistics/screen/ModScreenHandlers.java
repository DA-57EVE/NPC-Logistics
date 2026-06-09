package com.npclogistics.screen;

import com.npclogistics.NPClogistics;
import net.fabricmc.fabric.api.screenhandler.v1.ScreenHandlerRegistry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;

public class ModScreenHandlers {

    public static ScreenHandlerType<EquipmentScreenHandler> EQUIPMENT;

    public static void register() {
        EQUIPMENT = ScreenHandlerRegistry.registerExtended(
                new Identifier(NPClogistics.MOD_ID, "equipment"),
                EquipmentScreenHandler::new
        );
    }
}