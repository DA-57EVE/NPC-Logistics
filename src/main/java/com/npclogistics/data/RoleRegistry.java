package com.npclogistics.data;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;

public class RoleRegistry {

    private static final Map<Item, NpcRole> ROLES = new HashMap<>();

    static {
        ROLES.put(Items.WOODEN_HOE,    NpcRole.FARMER);
        ROLES.put(Items.STONE_HOE,     NpcRole.FARMER);
        ROLES.put(Items.IRON_HOE,      NpcRole.FARMER);
        ROLES.put(Items.GOLDEN_HOE,    NpcRole.FARMER);
        ROLES.put(Items.DIAMOND_HOE,   NpcRole.FARMER);
        ROLES.put(Items.NETHERITE_HOE, NpcRole.FARMER);

        ROLES.put(Items.SHEARS,   NpcRole.SHEPHERD);
        ROLES.put(Items.BUCKET,   NpcRole.DAIRY);
        ROLES.put(Items.FEATHER,  NpcRole.CHICKEN);

        ROLES.put(Items.WOODEN_SWORD,    NpcRole.BUTCHER);
        ROLES.put(Items.STONE_SWORD,     NpcRole.BUTCHER);
        ROLES.put(Items.IRON_SWORD,      NpcRole.BUTCHER);
        ROLES.put(Items.GOLDEN_SWORD,    NpcRole.BUTCHER);
        ROLES.put(Items.DIAMOND_SWORD,   NpcRole.BUTCHER);
        ROLES.put(Items.NETHERITE_SWORD, NpcRole.BUTCHER);

        ROLES.put(Items.LEAD, NpcRole.BREEDER);

        ROLES.put(Items.FISHING_ROD, NpcRole.FISHER);
    }

    /** Returns the {@link NpcRole} for the given tool stack, or {@code null} if unrecognised. */
    public static NpcRole roleFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return ROLES.get(stack.getItem());
    }
}
