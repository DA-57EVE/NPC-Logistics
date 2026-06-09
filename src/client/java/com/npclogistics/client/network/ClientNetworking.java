package com.npclogistics.client.network;

import com.npclogistics.data.WorkOrder;
import com.npclogistics.network.ModNetworking;
import com.npclogistics.screen.WorkOrderScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Hand;

@Environment(EnvType.CLIENT)
public class ClientNetworking {

    public static void registerClientPackets() {

        // OPEN_WORK_ORDER_SCREEN – server tells us to open the editor
        ClientPlayNetworking.registerGlobalReceiver(ModNetworking.OPEN_WORK_ORDER_SCREEN, (client, handler, buf, sender) -> {
            int workerEntityId = buf.readInt();
            boolean hasOrder = buf.readBoolean();
            WorkOrder existingOrder = hasOrder ? WorkOrder.fromNbt(buf.readNbt()) : null;

            client.execute(() -> {
                client.setScreen(new WorkOrderScreen(workerEntityId, existingOrder));
            });
        });

        // OPEN_SCROLL_EDITOR – server tells us to edit the route on a held scroll
        ClientPlayNetworking.registerGlobalReceiver(ModNetworking.OPEN_SCROLL_EDITOR, (client, handler, buf, sender) -> {
            int handOrdinal = buf.readInt();
            WorkOrder scrollOrder = WorkOrder.fromNbt(buf.readNbt());
            Hand hand = handOrdinal == Hand.OFF_HAND.ordinal() ? Hand.OFF_HAND : Hand.MAIN_HAND;

            client.execute(() -> {
                client.setScreen(new WorkOrderScreen(hand, scrollOrder));
            });
        });

        // WORK_ORDER_SYNC – update HUD / open screen progress indicator
        ClientPlayNetworking.registerGlobalReceiver(ModNetworking.WORK_ORDER_SYNC, (client, handler, buf, sender) -> {
            int workerEntityId = buf.readInt();
            int currentStop = buf.readInt();
            String state = buf.readString();
            // Could be used to update an open WorkOrderScreen if it references this worker
            // For now, just log – extend as needed
        });
    }

    // -----------------------------------------------------------------------
    //  C2S send helpers (called from screen)
    // -----------------------------------------------------------------------

    public static void sendAssignWorkOrder(int workerEntityId, WorkOrder order) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(workerEntityId);
        buf.writeNbt(order.toNbt());
        ClientPlayNetworking.send(ModNetworking.ASSIGN_WORK_ORDER, buf);
    }

    public static void sendCancelWorkOrder(int workerEntityId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(workerEntityId);
        ClientPlayNetworking.send(ModNetworking.CANCEL_WORK_ORDER, buf);
    }

    /** Writes the edited route back onto the scroll the player is holding in {@code hand}. */
    public static void sendUpdateScroll(Hand hand, WorkOrder order) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(hand.ordinal());
        buf.writeNbt(order.toNbt());
        ClientPlayNetworking.send(ModNetworking.UPDATE_SCROLL, buf);
    }

    /** Asks the server to open the equipment HandledScreen for the given worker. */
    public static void sendOpenEquipmentScreen(int workerEntityId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(workerEntityId);
        ClientPlayNetworking.send(ModNetworking.OPEN_EQUIPMENT_SCREEN, buf);
    }

    /** Applies a new display name and skin URL to the given worker on the server. */
    public static void sendUpdateWorkerProfile(int workerEntityId, String name, String skinUrl) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(workerEntityId);
        buf.writeString(name);
        buf.writeString(skinUrl);
        ClientPlayNetworking.send(ModNetworking.UPDATE_WORKER_PROFILE, buf);
    }

    /** Toggles the runOnce flag on a task row. */
    public static void sendTaskToggleOnce(int workerEntityId, int taskIndex, boolean runOnce) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(workerEntityId);
        buf.writeInt(taskIndex);
        buf.writeBoolean(runOnce);
        ClientPlayNetworking.send(ModNetworking.TASK_TOGGLE_ONCE, buf);
    }

    /** Deletes (clears) a task row on the server. */
    public static void sendTaskDelete(int workerEntityId, int taskIndex) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(workerEntityId);
        buf.writeInt(taskIndex);
        ClientPlayNetworking.send(ModNetworking.TASK_DELETE, buf);
    }
}
