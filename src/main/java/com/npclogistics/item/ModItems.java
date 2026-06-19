package com.npclogistics.item;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.ModEntities;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModItems {

    public static final Item WORK_ORDER_SCROLL = Registry.register(
            Registries.ITEM,
            new Identifier(NPClogistics.MOD_ID, "work_order_scroll"),
            new WorkOrderScrollItem(new FabricItemSettings().maxCount(1))
    );

    /** Blue — records a chest/barrel position as a source to collect from. */
    public static final LocationTokenItem LOCATION_TOKEN_COLLECT = Registry.register(
            Registries.ITEM,
            new Identifier(NPClogistics.MOD_ID, "location_token_collect"),
            new LocationTokenItem(new FabricItemSettings().maxCount(1), LocationTokenItem.TokenType.COLLECT)
    );

    /** Orange — records a crafting block (table, furnace, anvil, etc.) position. */
    public static final LocationTokenItem LOCATION_TOKEN_CRAFT = Registry.register(
            Registries.ITEM,
            new Identifier(NPClogistics.MOD_ID, "location_token_craft"),
            new LocationTokenItem(new FabricItemSettings().maxCount(1), LocationTokenItem.TokenType.CRAFT)
    );

    /** Green — records a chest/barrel position as a destination to deposit into. */
    public static final LocationTokenItem LOCATION_TOKEN_DEPOSIT = Registry.register(
            Registries.ITEM,
            new Identifier(NPClogistics.MOD_ID, "location_token_deposit"),
            new LocationTokenItem(new FabricItemSettings().maxCount(1), LocationTokenItem.TokenType.DEPOSIT)
    );

    /** Purple — records a position as the centre of a role worker's job site (e.g. farm centre). */
    public static final LocationTokenItem LOCATION_TOKEN_JOBSITE = Registry.register(
            Registries.ITEM,
            new Identifier(NPClogistics.MOD_ID, "location_token_jobsite"),
            new LocationTokenItem(new FabricItemSettings().maxCount(1), LocationTokenItem.TokenType.JOBSITE)
    );

    /** Light blue — records a bed position for the NPC to sleep in at night. */
    public static final LocationTokenItem LOCATION_TOKEN_BED = Registry.register(
            Registries.ITEM,
            new Identifier(NPClogistics.MOD_ID, "location_token_bed"),
            new LocationTokenItem(new FabricItemSettings().maxCount(1), LocationTokenItem.TokenType.BED)
    );

    /** Helmet-slot utility item — when worn, renders nearby workers' route overlays. */
    public static final WorkGogglesItem WORK_GOGGLES = Registry.register(
            Registries.ITEM,
            new Identifier(NPClogistics.MOD_ID, "work_goggles"),
            new WorkGogglesItem(new FabricItemSettings().maxCount(1))
    );

    /** Spawn egg for the Logistics Worker NPC. Primary: steel blue, secondary: gold. */
    public static final SpawnEggItem LOGISTICS_WORKER_SPAWN_EGG = Registry.register(
            Registries.ITEM,
            new Identifier(NPClogistics.MOD_ID, "logistics_worker_spawn_egg"),
            new SpawnEggItem(ModEntities.LOGISTICS_WORKER, 0x3D6E8C, 0xE8C040, new FabricItemSettings())
    );

    public static void register() {
        NPClogistics.LOGGER.info("Registering mod items...");
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(WORK_ORDER_SCROLL);
            entries.add(LOCATION_TOKEN_COLLECT);
            entries.add(LOCATION_TOKEN_CRAFT);
            entries.add(LOCATION_TOKEN_DEPOSIT);
            entries.add(LOCATION_TOKEN_JOBSITE);
            entries.add(LOCATION_TOKEN_BED);
            entries.add(WORK_GOGGLES);
            entries.add(LOGISTICS_WORKER_SPAWN_EGG);
        });
    }
}
