package com.npclogistics.item;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

public class LocationTokenItem extends Item {

    public enum TokenType {
        COLLECT("Collect Token", "Source: collect items from here"),
        CRAFT("Craft Token",     "Bench: craft items at this block"),
        DEPOSIT("Deposit Token", "Deposit: store crafted items here"),
        JOBSITE("Jobsite Token", "Jobsite: centre of the work area"),
        BED("Bed Token",         "Bed: NPC sleeps here at night");

        public final String defaultName;
        public final String description;
        TokenType(String n, String d) { defaultName = n; description = d; }
    }

    public final TokenType tokenType;

    public LocationTokenItem(Settings settings, TokenType type) {
        super(settings);
        this.tokenType = type;
    }

    // ── NBT helpers ──────────────────────────────────────────────────────────

    public static void stampPos(ItemStack stack, BlockPos pos, String blockName) {
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putInt("tokenX", pos.getX());
        nbt.putInt("tokenY", pos.getY());
        nbt.putInt("tokenZ", pos.getZ());
        nbt.putString("tokenBlock", blockName);
    }

    public static void stampCreator(ItemStack stack, String playerName) {
        stack.getOrCreateNbt().putString("createdBy", playerName);
    }

    public static BlockPos getPos(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains("tokenX")) return null;
        return new BlockPos(nbt.getInt("tokenX"), nbt.getInt("tokenY"), nbt.getInt("tokenZ"));
    }

    public static String getBlockName(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return (nbt != null && nbt.contains("tokenBlock")) ? nbt.getString("tokenBlock") : "";
    }

    public static boolean hasPos(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return nbt != null && nbt.contains("tokenX");
    }

    // ── Display name ──────────────────────────────────────────────────────────

    @Override
    public Text getName(ItemStack stack) {
        if (hasPos(stack)) {
            BlockPos pos = getPos(stack);
            String block = getBlockName(stack);
            String label = block.isEmpty() ? pos.toShortString() : block + " " + pos.toShortString();
            return Text.literal(label);
        }
        return Text.literal(tokenType.defaultName);
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
        BlockPos pos = getPos(stack);
        if (pos != null) {
            String block = getBlockName(stack);
            if (!block.isEmpty())
                tooltip.add(Text.literal(block).formatted(Formatting.WHITE));
            tooltip.add(Text.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ())
                    .formatted(Formatting.GRAY));
            NbtCompound nbt = stack.getNbt();
            if (nbt != null && nbt.contains("createdBy"))
                tooltip.add(Text.literal("Set by: " + nbt.getString("createdBy"))
                        .formatted(Formatting.DARK_AQUA));
        } else {
            tooltip.add(Text.literal("Right-click a block to record its location")
                    .formatted(Formatting.DARK_GRAY));
        }
        tooltip.add(Text.literal(tokenType.description).formatted(Formatting.DARK_GRAY));
    }
}
