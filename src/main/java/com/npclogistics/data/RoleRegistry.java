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
    }

    /** Returns the {@link NpcRole} for the given tool stack, or {@code null} if unrecognised. */
    public static NpcRole roleFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return ROLES.get(stack.getItem());
    }
}
