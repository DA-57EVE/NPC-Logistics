package com.npclogistics.item;

import com.npclogistics.data.WorkOrder;
import com.npclogistics.data.WorkOrder.RouteStop;
import com.npclogistics.data.WorkOrder.StopAction;
import com.npclogistics.entity.LogisticsWorkerEntity;
import com.npclogistics.network.ModNetworking;
import net.minecraft.inventory.Inventory;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Work Order Scroll item.
 *
 * Usage flow:
 *  1. Right-click a chest/barrel to add a COLLECT stop.
 *  2. Sneak + right-click a chest/barrel to add a DELIVER stop.
 *  3. Right-click in the air to open the route in the Work Order editor (set filters, reorder).
 *  4. Right-click a Logistics Worker NPC to assign the order (the scroll is consumed).
 *
 * The route is stored on the scroll as a full {@link WorkOrder} NBT (under {@link #SCROLL_KEY}),
 * so item filters and ordering set in the editor persist on the scroll and carry over to the
 * worker on assignment.
 *
 * The block right-click is handled by a {@code UseBlockCallback} registered in the mod
 * initializer — NOT by {@link #useOnBlock}. That is deliberate: a chest's own block
 * interaction runs before {@code Item#useOnBlock} and would otherwise just open the
 * chest. The callback fires first and lets us cancel that. The {@code static} helpers
 * below ({@link #isContainer} / {@link #addStop}) are the shared logic it calls.
 */
public class WorkOrderScrollItem extends Item {

    /** NBT key under which the scroll's {@link WorkOrder} is stored. */
    public static final String SCROLL_KEY = "workOrder";

    public WorkOrderScrollItem(Settings settings) {
        super(settings);
    }

    // -----------------------------------------------------------------------
    //  Right-click in the air (open the route in the editor)
    // -----------------------------------------------------------------------

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        // Right-click (or sneak + right-click) in the air opens the editor on the scroll's
        // route. The server tells the client which hand to write edits back to. Block/entity
        // clicks are handled elsewhere (UseBlockCallback / useOnEntity) and never reach here.
        if (!world.isClient && user instanceof ServerPlayerEntity serverPlayer) {
            ModNetworking.sendOpenScrollEditor(serverPlayer, hand, readOrder(stack));
        }
        // SUCCESS on the client (arm swing), CONSUME on the server (no double-swing).
        return TypedActionResult.success(stack, world.isClient());
    }

    // -----------------------------------------------------------------------
    //  Scroll <-> WorkOrder storage
    // -----------------------------------------------------------------------

    /** Reads the route stored on the scroll, or a fresh empty order if the scroll is blank. */
    public static WorkOrder readOrder(ItemStack stack) {
        if (stack.hasNbt() && stack.getNbt().contains(SCROLL_KEY)) {
            return WorkOrder.fromNbt(stack.getNbt().getCompound(SCROLL_KEY));
        }
        return new WorkOrder("Scroll Route", BlockPos.ORIGIN, false);
    }

    /** Writes a route onto the scroll, replacing whatever was there. */
    public static void writeOrder(ItemStack stack, WorkOrder order) {
        stack.getOrCreateNbt().put(SCROLL_KEY, order.toNbt());
    }

    // -----------------------------------------------------------------------
    //  Right-click on entity (assign to NPC)
    // -----------------------------------------------------------------------

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity player, LivingEntity entity, Hand hand) {
        if (!(entity instanceof LogisticsWorkerEntity worker)) {
            return ActionResult.PASS;
        }
        if (player.getWorld().isClient) {
            return ActionResult.SUCCESS;
        }
        WorkOrder order = readOrder(stack);
        if (order.getStops().isEmpty()) {
            player.sendMessage(Text.literal("Add at least one stop first — right-click chests or barrels.")
                    .formatted(Formatting.RED), true);
            return ActionResult.FAIL;
        }
        order.setHomePos(worker.getBlockPos());
        worker.setHomePos(worker.getBlockPos());
        // Keep a copy of the configured scroll on the NPC so the route survives
        // after completion and can be retrieved via sneak+right-click.
        worker.setWoScroll1(stack.copy());
        worker.startWorkOrder(order);
        player.sendMessage(Text.literal("Assigned '" + order.getName() + "' with "
                + order.getStops().size() + " stops.").formatted(Formatting.GREEN), true);
        // The order now lives on the worker — don't leave a duplicate behind. In survival the
        // scroll is consumed. Creative is client-authoritative for the hotbar, so a plain
        // server-side change is reverted by the client; we replace the stack AND push an
        // explicit slot update (syncId -2 = the player's own inventory) to force the client.
        if (player.getAbilities().creativeMode) {
            ItemStack blank = new ItemStack(this);
            player.setStackInHand(hand, blank);
            if (player instanceof ServerPlayerEntity serverPlayer) {
                int slot = hand == Hand.MAIN_HAND
                        ? player.getInventory().selectedSlot
                        : PlayerInventory.OFF_HAND_SLOT;
                serverPlayer.networkHandler.sendPacket(
                        new ScreenHandlerSlotUpdateS2CPacket(-2, 0, slot, blank));
            }
        } else {
            stack.decrement(1);
        }
        return ActionResult.SUCCESS;
    }

    // -----------------------------------------------------------------------
    //  Display name – show the recorded order's title so hotbar slots differ
    // -----------------------------------------------------------------------

    @Override
    public Text getName(ItemStack stack) {
        WorkOrder order = readOrder(stack);
        String title = order.getName();
        if (!order.getStops().isEmpty() && title != null && !title.isBlank()) {
            return Text.literal("Work Order: ").append(Text.literal(title).formatted(Formatting.GOLD));
        }
        return super.getName(stack);
    }

    // -----------------------------------------------------------------------
    //  Tooltip
    // -----------------------------------------------------------------------

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext ctx) {
        List<RouteStop> stops = readOrder(stack).getStops();
        if (stops.isEmpty()) {
            tooltip.add(Text.literal("Right-click a chest/barrel → COLLECT stop").formatted(Formatting.GRAY));
            tooltip.add(Text.literal("Sneak + right-click → DELIVER stop").formatted(Formatting.GRAY));
            tooltip.add(Text.literal("Right-click in air → open editor").formatted(Formatting.GRAY));
            tooltip.add(Text.literal("Right-click a Logistics Worker → assign").formatted(Formatting.GRAY));
            return;
        }
        tooltip.add(Text.literal("Stops recorded: " + stops.size()).formatted(Formatting.AQUA));
        for (int i = 0; i < stops.size(); i++) {
            RouteStop stop = stops.get(i);
            String filterNote = stop.itemFilter.isEmpty() ? "" : "  (" + stop.itemFilter.size() + " items)";
            tooltip.add(Text.literal("  [" + i + "] " + stop.action.name() + " @ " + stop.pos.toShortString() + filterNote)
                    .formatted(Formatting.DARK_GRAY));
        }
        tooltip.add(Text.literal("Right-click in air to edit · right-click a worker to assign").formatted(Formatting.GRAY));
    }

    // -----------------------------------------------------------------------
    //  Shared logic for the UseBlockCallback (mod initializer)
    // -----------------------------------------------------------------------

    /** True if the block at {@code pos} is any inventory-capable container. */
    public static boolean isContainer(World world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof Inventory;
    }

    /** Outcome of {@link #addStop}, so the caller can give the right feedback. */
    public enum AddResult { ADDED, UPDATED, DUPLICATE }

    /**
     * Records a stop on the scroll. Coordinates are unique: clicking a container that is
     * already on the scroll updates its action (COLLECT↔DELIVER) instead of stacking a
     * duplicate entry, and re-clicking with the same action is a no-op. Any item filter
     * already set on that stop is preserved when the action flips.
     */
    public static AddResult addStop(ItemStack stack, BlockPos pos, StopAction action) {
        WorkOrder order = readOrder(stack);
        List<RouteStop> stops = order.getStops();

        for (int i = 0; i < stops.size(); i++) {
            RouteStop existing = stops.get(i);
            if (existing.pos.equals(pos)) {
                if (existing.action == action) {
                    return AddResult.DUPLICATE;
                }
                stops.set(i, new RouteStop(existing.pos, existing.itemFilter, existing.collectFilter,
                        existing.itemModes, existing.collectModes, action, existing.maxAmount));
                writeOrder(stack, order);
                return AddResult.UPDATED;
            }
        }

        order.addStop(new RouteStop(pos, new ArrayList<>(), action, 0));
        writeOrder(stack, order);
        return AddResult.ADDED;
    }
}
