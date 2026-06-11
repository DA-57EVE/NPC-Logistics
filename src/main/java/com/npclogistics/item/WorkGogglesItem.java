package com.npclogistics.item;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.ArmorMaterial;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

/**
 * Work Goggles — equip in the helmet slot to see nearby workers' route lines and stop labels.
 *
 * No combat protection; purely a utility item.  Uses iron armor texture until custom art is made.
 */
public class WorkGogglesItem extends ArmorItem {

    private static final ArmorMaterial MATERIAL = new ArmorMaterial() {
        @Override public int getDurability(Type type) { return 200; }
        @Override public int getProtection(Type type) { return 0; }
        @Override public int getEnchantability() { return 0; }
        @Override public SoundEvent getEquipSound() { return SoundEvents.ITEM_ARMOR_EQUIP_LEATHER; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.ofItems(Items.GLASS_PANE); }
        @Override public String getName() { return "iron"; } // borrows iron texture until custom art is added
        @Override public float getToughness() { return 0; }
        @Override public float getKnockbackResistance() { return 0; }
    };

    public WorkGogglesItem(Settings settings) {
        super(MATERIAL, Type.HELMET, settings);
    }
}
