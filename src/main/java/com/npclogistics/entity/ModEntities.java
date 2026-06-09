package com.npclogistics.entity;

import com.npclogistics.NPClogistics;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModEntities {

    public static final EntityType<LogisticsWorkerEntity> LOGISTICS_WORKER =
            Registry.register(
                Registries.ENTITY_TYPE,
                new Identifier(NPClogistics.MOD_ID, "logistics_worker"),
                FabricEntityTypeBuilder.<LogisticsWorkerEntity>create(
                        SpawnGroup.MISC, LogisticsWorkerEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6f, 1.8f))
                    .build()
            );

    public static void register() {
        NPClogistics.LOGGER.info("Registering mod entities...");
        // Registration of the entity type happens in the static field above; calling
        // this method ensures the class is loaded and the static initialiser runs.
        // Living entities also require their attributes to be registered, otherwise
        // spawning the worker throws.
        FabricDefaultAttributeRegistry.register(LOGISTICS_WORKER, LogisticsWorkerEntity.createAttributes());
    }
}
