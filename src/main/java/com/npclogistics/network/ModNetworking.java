package com.npclogistics.network;

import com.npclogistics.NPClogistics;
import com.npclogistics.data.WorkOrder;
import com.npclogistics.entity.LogisticsWorkerEntity;
import com.npclogistics.item.WorkOrderScrollItem;
import com.npclogistics.screen.EquipmentScreenHandler;
import com.npclogistics.screen.ModScreenHandlers;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

/**
 * Centralises all custom network packets.
 *
 * C2S (client → server):
 *   ASSIGN_WORK_ORDER  – sent from the Work Order GUI when the player confirms.
 *   CANCEL_WORK_ORDER  – cancel active order on a worker.
 *   UPDATE_SCROLL      – sent from the GUI when editing a scroll; writes edits back to the held scroll.
 *
 * S2C (server → client):
 *   OPEN_WORK_ORDER_SCREEN – tells the client to open the editor GUI for a specific worker.
 *   OPEN_SCROLL_EDITOR     – tells the client to open the editor GUI on a held scroll's route.
 *   WORK_ORDER_SYNC        – pushes the current work order state to the client for display.
 */
public class ModNetworking {

    // Packet identifiers
    public static final Identifier ASSIGN_WORK_ORDER      = new Identifier(NPClogistics.MOD_ID, "assign_work_order");
    public static final Identifier CANCEL_WORK_ORDER      = new Identifier(NPClogistics.MOD_ID, "cancel_work_order");
    public static final Identifier UPDATE_SCROLL          = new Identifier(NPClogistics.MOD_ID, "update_scroll");
    public static final Identifier OPEN_WORK_ORDER_SCREEN = new Identifier(NPClogistics.MOD_ID, "open_work_order_screen");
    public static final Identifier OPEN_SCROLL_EDITOR     = new Identifier(NPClogistics.MOD_ID, "open_scroll_editor");
    public static final Identifier WORK_ORDER_SYNC        = new Identifier(NPClogistics.MOD_ID, "work_order_sync");
    /** C2S: client requests the server to open the equipment HandledScreen for a worker. */
    public static final Identifier OPEN_EQUIPMENT_SCREEN  = new Identifier(NPClogistics.MOD_ID, "open_equipment_screen");
    /** C2S: client applies a new name and/or skin URL to a worker. */
    public static final Identifier UPDATE_WORKER_PROFILE  = new Identifier(NPClogistics.MOD_ID, "update_worker_profile");
    /** C2S: toggle the runOnce flag on one task row. */
    public static final Identifier TASK_TOGGLE_ONCE       = new Identifier(NPClogistics.MOD_ID, "task_toggle_once");
    /** C2S: clear all slots + metadata for one task row (delete). */
    public static final Identifier TASK_DELETE            = new Identifier(NPClogistics.MOD_ID, "task_delete");
    /** S2C: pushes route stop data to a goggle-wearing player for overlay rendering. */
    public static final Identifier ROUTE_DATA_SYNC        = new Identifier(NPClogistics.MOD_ID, "route_data_sync");
    /** C2S: toggle the ignoreDark flag on a worker (work through the night). */
    public static final Identifier TOGGLE_IGNORE_DARK     = new Identifier(NPClogistics.MOD_ID, "toggle_ignore_dark");

    // -----------------------------------------------------------------------
    //  Server-side packet handlers
    // -----------------------------------------------------------------------

    public static void registerServerPackets() {

        // ASSIGN_WORK_ORDER
        // Payload: workerEntityId (int), workOrder (NbtCompound)
        ServerPlayNetworking.registerGlobalReceiver(ASSIGN_WORK_ORDER, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            NbtCompound orderNbt = buf.readNbt();

            server.execute(() -> {
                if (player.getWorld().getEntityById(entityId) instanceof LogisticsWorkerEntity worker) {
                    if (worker.squaredDistanceTo(player) > 64) return; // sanity distance check
                    WorkOrder order = WorkOrder.fromNbt(orderNbt);
                    worker.startWorkOrder(order);
                    NPClogistics.LOGGER.info("{} assigned work order '{}' to {}",
                            player.getName().getString(), order.getName(), worker.getName().getString());
                }
            });
        });

        // CANCEL_WORK_ORDER
        // Payload: workerEntityId (int)
        ServerPlayNetworking.registerGlobalReceiver(CANCEL_WORK_ORDER, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            server.execute(() -> {
                if (player.getWorld().getEntityById(entityId) instanceof LogisticsWorkerEntity worker) {
                    if (worker.squaredDistanceTo(player) > 64) return;
                    worker.cancelWorkOrder();
                    NPClogistics.LOGGER.info("{} cancelled work order on {}",
                            player.getName().getString(), worker.getName().getString());
                }
            });
        });

        // UPDATE_SCROLL
        // Payload: hand ordinal (int), workOrder (NbtCompound)
        // Writes the edited route back onto the Work Order Scroll the player is holding.
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_SCROLL, (server, player, handler, buf, responseSender) -> {
            int handOrdinal = buf.readInt();
            NbtCompound orderNbt = buf.readNbt();
            server.execute(() -> {
                Hand hand = handOrdinal == Hand.OFF_HAND.ordinal() ? Hand.OFF_HAND : Hand.MAIN_HAND;
                ItemStack stack = player.getStackInHand(hand);
                if (stack.getItem() instanceof WorkOrderScrollItem && orderNbt != null) {
                    WorkOrderScrollItem.writeOrder(stack, WorkOrder.fromNbt(orderNbt));
                    // Creative mode is client-authoritative: push an explicit slot update so
                    // the edited route isn't reverted by the client's held-item sync.
                    int slot = hand == Hand.MAIN_HAND
                            ? player.getInventory().selectedSlot
                            : PlayerInventory.OFF_HAND_SLOT;
                    player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, 0, slot, stack));
                }
            });
        });

        // OPEN_EQUIPMENT_SCREEN
        // Payload: workerEntityId (int)
        // Client requests the server to open the equipment HandledScreen for a worker NPC.
        ServerPlayNetworking.registerGlobalReceiver(OPEN_EQUIPMENT_SCREEN, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            server.execute(() -> {
                if (!(player.getWorld().getEntityById(entityId) instanceof LogisticsWorkerEntity worker)) return;
                if (worker.squaredDistanceTo(player) > 64) return;
                player.openHandledScreen(new ExtendedScreenHandlerFactory() {
                    @Override
                    public Text getDisplayName() {
                        return Text.literal("NPC ").append(Text.literal("NPCLogistics").formatted(net.minecraft.util.Formatting.ITALIC));
                    }

                    @Override
                    public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                        return new EquipmentScreenHandler(syncId, inv, worker);
                    }

                    @Override
                    public void writeScreenOpeningData(ServerPlayerEntity p, PacketByteBuf b) {
                        b.writeInt(worker.getId());
                        b.writeString(worker.getCustomName() != null ? worker.getCustomName().getString() : "");
                        b.writeString(worker.getCustomSkinUrl());
                        b.writeBoolean(canPlayerTakeScroll(p, worker.getWoScroll1()));
                        b.writeBoolean(canPlayerTakeScroll(p, worker.getWoScroll2()));
                        b.writeBoolean(worker.isEmployer(p.getUuid()));
                        b.writeString(worker.getEmployerName());
                        b.writeBoolean(worker.isIgnoreDark());
                        for (int i = 0; i < com.npclogistics.entity.LogisticsWorkerEntity.MAX_TASKS; i++) {
                            com.npclogistics.data.CraftingTask t = worker.getTask(i);
                            b.writeBoolean(t != null && t.runOnce);
                            b.writeBoolean(t != null && t.completed);
                            b.writeString(t != null ? t.addedByName : "");
                            b.writeBoolean(t != null && p.getUuid().equals(t.addedBy));
                        }
                    }
                });
            });
        });

        // UPDATE_WORKER_PROFILE
        // Payload: workerEntityId (int), name (String ≤64), skinUrl (String ≤512)
        ServerPlayNetworking.registerGlobalReceiver(UPDATE_WORKER_PROFILE, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            String name    = buf.readString(64);
            String skinUrl = buf.readString(512);
            server.execute(() -> {
                if (!(player.getWorld().getEntityById(entityId) instanceof LogisticsWorkerEntity worker)) return;
                if (worker.squaredDistanceTo(player) > 64) return;
                // Only the employer may rename the worker.
                if (!worker.isEmployer(player.getUuid())) {
                    NPClogistics.LOGGER.info("{} tried to rename worker {} but is not the employer — denied.",
                            player.getName().getString(), worker.getId());
                    return;
                }
                if (!name.isBlank()) {
                    worker.setCustomName(Text.literal(name));
                    worker.setCustomNameVisible(true);
                } else {
                    worker.setCustomName(null);
                    worker.setCustomNameVisible(false);
                }
                if (!skinUrl.isBlank()) worker.setCustomSkinUrl(skinUrl);
                NPClogistics.LOGGER.info("{} updated worker {} profile: name='{}', skinUrl='{}'",
                        player.getName().getString(), worker.getId(), name, skinUrl);
            });
        });

        // TASK_TOGGLE_ONCE
        // Payload: workerEntityId (int), taskIndex (int), runOnce (boolean)
        ServerPlayNetworking.registerGlobalReceiver(TASK_TOGGLE_ONCE, (server, player, handler, buf, responseSender) -> {
            int entityId  = buf.readInt();
            int taskIndex = buf.readInt();
            boolean runOnce = buf.readBoolean();
            server.execute(() -> {
                if (!(player.getWorld().getEntityById(entityId) instanceof LogisticsWorkerEntity worker)) return;
                if (worker.squaredDistanceTo(player) > 64) return;
                com.npclogistics.data.CraftingTask t = worker.getTask(taskIndex);
                if (t == null) return;
                // Employer can toggle any task; others can only toggle their own.
                if (!worker.isEmployer(player.getUuid()) && !player.getUuid().equals(t.addedBy)) return;
                worker.setTask(taskIndex, t.withRunOnce(runOnce).withCompleted(false));
                // Update the open handler so close() saves correctly.
                if (player.currentScreenHandler instanceof com.npclogistics.screen.EquipmentScreenHandler eh)
                    eh.setTaskRunOnce(taskIndex, runOnce);
            });
        });

        // TOGGLE_IGNORE_DARK
        // Payload: workerEntityId (int), value (boolean)
        ServerPlayNetworking.registerGlobalReceiver(TOGGLE_IGNORE_DARK, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            boolean value = buf.readBoolean();
            server.execute(() -> {
                if (!(player.getWorld().getEntityById(entityId) instanceof LogisticsWorkerEntity worker)) return;
                if (worker.squaredDistanceTo(player) > 64) return;
                worker.setIgnoreDark(value);
                if (player.currentScreenHandler instanceof com.npclogistics.screen.EquipmentScreenHandler eh)
                    eh.setIgnoreDark(value);
                NPClogistics.LOGGER.info("{} set ignoreDark={} on worker {}",
                        player.getName().getString(), value, worker.getId());
            });
        });

        // TASK_DELETE
        // Payload: workerEntityId (int), taskIndex (int)
        ServerPlayNetworking.registerGlobalReceiver(TASK_DELETE, (server, player, handler, buf, responseSender) -> {
            int entityId  = buf.readInt();
            int taskIndex = buf.readInt();
            server.execute(() -> {
                if (!(player.getWorld().getEntityById(entityId) instanceof LogisticsWorkerEntity worker)) return;
                if (worker.squaredDistanceTo(player) > 64) return;
                com.npclogistics.data.CraftingTask t = worker.getTask(taskIndex);
                if (t != null && !worker.isEmployer(player.getUuid()) && !player.getUuid().equals(t.addedBy)) return;
                worker.clearTask(taskIndex);
                if (player.currentScreenHandler instanceof com.npclogistics.screen.EquipmentScreenHandler eh)
                    eh.clearTaskSlots(taskIndex);
                NPClogistics.LOGGER.info("{} deleted task {} on worker {}", player.getName().getString(), taskIndex, worker.getId());
            });
        });
    }

    // -----------------------------------------------------------------------
    //  S2C helper methods (called from server to push data to a player)
    // -----------------------------------------------------------------------

    private static boolean canPlayerTakeScroll(ServerPlayerEntity player, net.minecraft.item.ItemStack scroll) {
        if (scroll.isEmpty()) return true;
        net.minecraft.nbt.NbtCompound nbt = scroll.getNbt();
        if (nbt == null || !nbt.containsUuid("depositedBy")) return true;
        return nbt.getUuid("depositedBy").equals(player.getUuid());
    }

    /** Tells the client to open the Work Order editor for the given worker. */
    public static void sendOpenWorkOrderScreen(ServerPlayerEntity player, int workerEntityId, WorkOrder currentOrder) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(workerEntityId);
        buf.writeBoolean(currentOrder != null);
        if (currentOrder != null) {
            buf.writeNbt(currentOrder.toNbt());
        }
        ServerPlayNetworking.send(player, OPEN_WORK_ORDER_SCREEN, buf);
    }

    /** Tells the client to open the editor on the route stored on a held scroll. */
    public static void sendOpenScrollEditor(ServerPlayerEntity player, Hand hand, WorkOrder scrollOrder) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(hand.ordinal());
        buf.writeNbt(scrollOrder.toNbt());
        ServerPlayNetworking.send(player, OPEN_SCROLL_EDITOR, buf);
    }

    /** Syncs updated work order state to the client (e.g. progress). */
    public static void sendWorkOrderSync(ServerPlayerEntity player, int workerEntityId,
                                          int currentStopIndex, String workerState) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(workerEntityId);
        buf.writeInt(currentStopIndex);
        buf.writeString(workerState);
        ServerPlayNetworking.send(player, WORK_ORDER_SYNC, buf);
    }

    /**
     * Pushes route overlay data for one worker to a goggle-wearing player.
     * Called server-side every second for each eligible (worker, player) pair.
     *
     * Payload per stop: BlockPos + action ordinal (byte) + sign label (String ≤64).
     */
    public static void sendRouteData(ServerPlayerEntity player, LogisticsWorkerEntity worker,
                                      java.util.List<WorkOrder.RouteStop> stops, int currentStopIndex) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(worker.getId());
        buf.writeString(worker.getCustomName() != null
                ? worker.getCustomName().getString() : worker.getName().getString(), 48);
        buf.writeInt(currentStopIndex);
        buf.writeInt(stops.size());
        for (WorkOrder.RouteStop stop : stops) {
            buf.writeBlockPos(stop.pos);
            buf.writeByte(stop.action.ordinal());
            buf.writeString(readSignLabel(worker.getWorld(), stop.pos), 64);
        }
        ServerPlayNetworking.send(player, ROUTE_DATA_SYNC, buf);
    }

    /**
     * Pushes crafting-task route data (source → craft block → deposit) to a goggle-wearing player.
     * Reuses the same ROUTE_DATA_SYNC packet with synthetic stops so the client renders identically.
     */
    public static void sendTaskRouteData(ServerPlayerEntity player, LogisticsWorkerEntity worker,
                                          com.npclogistics.data.CraftingTask task, int phaseIndex) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(worker.getId());
        buf.writeString(worker.getCustomName() != null
                ? worker.getCustomName().getString() : worker.getName().getString(), 48);
        buf.writeInt(phaseIndex);
        buf.writeInt(3);
        // Source chest — green (COLLECT ordinal 0)
        buf.writeBlockPos(task.sourcePos);
        buf.writeByte(0);
        buf.writeString(readSignLabel(worker.getWorld(), task.sourcePos), 64);
        // Craft block — gold (BOTH ordinal 2, used to signal "processing")
        buf.writeBlockPos(task.craftBlockPos);
        buf.writeByte(2);
        buf.writeString(readSignLabel(worker.getWorld(), task.craftBlockPos), 64);
        // Deposit chest — red (DELIVER ordinal 1)
        buf.writeBlockPos(task.depositPos);
        buf.writeByte(1);
        buf.writeString(readSignLabel(worker.getWorld(), task.depositPos), 64);
        ServerPlayNetworking.send(player, ROUTE_DATA_SYNC, buf);
    }

    /** Reads the first non-empty line of a sign adjacent to {@code pos}, or empty string. */
    private static String readSignLabel(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos) {
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
            net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(pos.offset(dir));
            if (be instanceof net.minecraft.block.entity.SignBlockEntity sign) {
                for (int row = 0; row < 4; row++) {
                    String text = sign.getFrontText().getMessage(row, false).getString().trim();
                    if (!text.isEmpty()) return text;
                }
            }
        }
        return "";
    }
}
