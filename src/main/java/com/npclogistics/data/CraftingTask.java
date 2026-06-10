package com.npclogistics.data;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/** One crafting task row: source chest → recipe → craft block → deposit chest. */
public class CraftingTask {

    public final BlockPos sourcePos;
    public final ItemStack recipeItem;
    public final BlockPos craftBlockPos;
    public final BlockPos depositPos;
    public final boolean runOnce;
    public final boolean completed;
    public final UUID addedBy;
    public final String addedByName;

    public CraftingTask(BlockPos sourcePos, ItemStack recipeItem, BlockPos craftBlockPos,
                        BlockPos depositPos, boolean runOnce, boolean completed,
                        UUID addedBy, String addedByName) {
        this.sourcePos    = sourcePos;
        this.recipeItem   = recipeItem == null ? ItemStack.EMPTY : recipeItem;
        this.craftBlockPos = craftBlockPos;
        this.depositPos   = depositPos;
        this.runOnce      = runOnce;
        this.completed    = completed;
        this.addedBy      = addedBy;
        this.addedByName  = addedByName != null ? addedByName : "";
    }

    public CraftingTask withRunOnce(boolean value) {
        return new CraftingTask(sourcePos, recipeItem, craftBlockPos, depositPos,
                value, completed, addedBy, addedByName);
    }

    public CraftingTask withCompleted(boolean value) {
        return new CraftingTask(sourcePos, recipeItem, craftBlockPos, depositPos,
                runOnce, value, addedBy, addedByName);
    }

    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        if (sourcePos != null) {
            tag.putInt("srcX", sourcePos.getX());
            tag.putInt("srcY", sourcePos.getY());
            tag.putInt("srcZ", sourcePos.getZ());
        }
        if (!recipeItem.isEmpty()) tag.put("recipe", recipeItem.writeNbt(new NbtCompound()));
        if (craftBlockPos != null) {
            tag.putInt("craftX", craftBlockPos.getX());
            tag.putInt("craftY", craftBlockPos.getY());
            tag.putInt("craftZ", craftBlockPos.getZ());
        }
        if (depositPos != null) {
            tag.putInt("depX", depositPos.getX());
            tag.putInt("depY", depositPos.getY());
            tag.putInt("depZ", depositPos.getZ());
        }
        tag.putBoolean("runOnce",   runOnce);
        tag.putBoolean("completed", completed);
        if (addedBy != null)         tag.putUuid("addedBy",      addedBy);
        if (!addedByName.isEmpty())  tag.putString("addedByName", addedByName);
        return tag;
    }

    public static CraftingTask fromNbt(NbtCompound tag) {
        BlockPos src   = tag.contains("srcX")   ? new BlockPos(tag.getInt("srcX"),   tag.getInt("srcY"),   tag.getInt("srcZ"))   : null;
        BlockPos craft = tag.contains("craftX") ? new BlockPos(tag.getInt("craftX"), tag.getInt("craftY"), tag.getInt("craftZ")) : null;
        BlockPos dep   = tag.contains("depX")   ? new BlockPos(tag.getInt("depX"),   tag.getInt("depY"),   tag.getInt("depZ"))   : null;
        ItemStack recipe = tag.contains("recipe") ? ItemStack.fromNbt(tag.getCompound("recipe")) : ItemStack.EMPTY;
        UUID addedBy = tag.containsUuid("addedBy") ? tag.getUuid("addedBy") : null;
        String name  = tag.contains("addedByName") ? tag.getString("addedByName") : "";
        return new CraftingTask(src, recipe, craft, dep,
                tag.getBoolean("runOnce"), tag.getBoolean("completed"), addedBy, name);
    }

    /** Whether any slot has been filled (task row is not blank). */
    public boolean hasAnyContent() {
        return sourcePos != null || !recipeItem.isEmpty() || craftBlockPos != null || depositPos != null;
    }

    /** Whether all four slots are filled — required before the task may fire. */
    public boolean hasAllContent() {
        return sourcePos != null && !recipeItem.isEmpty() && craftBlockPos != null && depositPos != null;
    }
}
