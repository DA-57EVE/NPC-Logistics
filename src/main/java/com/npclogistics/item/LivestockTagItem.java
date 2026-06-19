package com.npclogistics.item;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public class LivestockTagItem extends Item {

    public LivestockTagItem(Settings settings) {
        super(settings);
    }

    public static boolean hasPos(ItemStack stack) {
        return stack.hasNbt() && stack.getNbt().contains("pos");
    }

    @Nullable
    public static BlockPos getPos(ItemStack stack) {
        if (!hasPos(stack)) return null;
        NbtCompound pos = stack.getNbt().getCompound("pos");
        return new BlockPos(pos.getInt("x"), pos.getInt("y"), pos.getInt("z"));
    }

    public static void setPos(ItemStack stack, BlockPos pos) {
        NbtCompound posNbt = new NbtCompound();
        posNbt.putInt("x", pos.getX());
        posNbt.putInt("y", pos.getY());
        posNbt.putInt("z", pos.getZ());
        stack.getOrCreateNbt().put("pos", posNbt);
    }

    @Override
    public Text getName(ItemStack stack) {
        if (hasPos(stack)) {
            BlockPos pos = getPos(stack);
            return Text.literal("Livestock Tag [" + pos.toShortString() + "]");
        }
        return super.getName(stack);
    }
}
