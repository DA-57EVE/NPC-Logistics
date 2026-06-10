package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.data.WorkOrder;
import com.npclogistics.data.WorkOrder.RouteStop;
import com.npclogistics.entity.LogisticsWorkerEntity;
import com.npclogistics.entity.LogisticsWorkerEntity.WorkerState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

/**
 * Handles the tick-by-tick execution of a WorkOrder for a LogisticsWorkerEntity.
 *
 * Each tick:
 *  1. Determine the current RouteStop.
 *  2. Navigate toward the stop's chest/barrel.
 *  3. When adjacent, interact with the container (collect or deliver).
 *  4. Advance to the next stop; when all stops done, signal the entity.
 */
public class WorkOrderBrain {

    /** Distance (blocks) at which the NPC considers itself "at" a stop. */
    private static final double ARRIVAL_DISTANCE = 3.0;
    /** Ticks between interaction attempts at a container. */
    private static final int INTERACTION_COOLDOWN_TICKS = 10;

    private final LogisticsWorkerEntity worker;

    public WorkOrderBrain(LogisticsWorkerEntity worker) {
        this.worker = worker;
    }

    // -----------------------------------------------------------------------

    public void tick(ServerWorld world) {
        WorkOrder order = worker.getActiveWorkOrder();
        if (order == null || order.getStops().isEmpty()) {
            worker.onRouteComplete();
            return;
        }

        int idx = worker.getCurrentStopIndex();
        if (idx >= order.getStops().size()) {
            worker.onRouteComplete();
            return;
        }

        RouteStop currentStop = order.getStops().get(idx);
        navigateToStop(currentStop);

        if (isAtStop(currentStop) && worker.getInteractionCooldown() == 0) {
            boolean interacted = interactWithContainer(world, currentStop);
            if (interacted) {
                worker.setInteractionCooldown(INTERACTION_COOLDOWN_TICKS);
                advanceToNextStop(order);
            } else {
                // Container not found or full – skip this stop with a warning
                NPClogistics.LOGGER.warn("{} could not interact with container at {}; skipping.",
                        worker.getName().getString(), currentStop.pos);
                advanceToNextStop(order);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Navigation
    // -----------------------------------------------------------------------

    private void navigateToStop(RouteStop stop) {
        if (!isAtStop(stop) && worker.getNavigation().isIdle()) {
            worker.getNavigation().startMovingTo(
                    stop.pos.getX() + 0.5,
                    stop.pos.getY(),
                    stop.pos.getZ() + 0.5,
                    1.0
            );
        }
    }

    private boolean isAtStop(RouteStop stop) {
        return worker.getPos().distanceTo(stop.pos.toCenterPos()) <= ARRIVAL_DISTANCE;
    }

    // -----------------------------------------------------------------------
    //  Container interaction
    // -----------------------------------------------------------------------

    /**
     * Attempts to interact with any Inventory block entity at the stop's position.
     * Handles chests, barrels, hoppers, droppers, shulker boxes, etc.
     * @return true if a container was found and interaction occurred (even if 0 items moved)
     */
    private boolean interactWithContainer(ServerWorld world, RouteStop stop) {
        BlockEntity be = world.getBlockEntity(stop.pos);
        if (!(be instanceof Inventory inv)) return false;

        // Play an appropriate open sound for the container type.
        SoundEvent openSound  = world.getBlockState(stop.pos).isOf(Blocks.BARREL)
                ? SoundEvents.BLOCK_BARREL_OPEN  : SoundEvents.BLOCK_CHEST_OPEN;
        SoundEvent closeSound = world.getBlockState(stop.pos).isOf(Blocks.BARREL)
                ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE;
        world.playSound(null, stop.pos.getX() + 0.5, stop.pos.getY() + 0.5, stop.pos.getZ() + 0.5,
                openSound, SoundCategory.BLOCKS, 0.4f, 1.0f);

        // Take a mutable snapshot for uniform read/write, then sync back once.
        int size = inv.size();
        SimpleInventory snapshot = new SimpleInventory(size);
        for (int i = 0; i < size; i++) snapshot.setStack(i, inv.getStack(i).copy());

        // Deliver before collecting so a BOTH stop restocks the container first.
        if (stop.doesDeliver()) {
            int given = worker.deliverItemsToInventory(snapshot, stop);
            NPClogistics.LOGGER.info("{} delivered {} items to {}",
                    worker.getName().getString(), given, stop.pos);
        }
        if (stop.doesCollect()) {
            int taken = worker.collectItemsFromInventory(snapshot, stop);
            NPClogistics.LOGGER.info("{} collected {} items from {}",
                    worker.getName().getString(), taken, stop.pos);
        }

        for (int i = 0; i < size; i++) inv.setStack(i, snapshot.getStack(i));
        inv.markDirty();

        world.playSound(null, stop.pos.getX() + 0.5, stop.pos.getY() + 0.5, stop.pos.getZ() + 0.5,
                closeSound, SoundCategory.BLOCKS, 0.4f, 1.0f);
        return true;
    }

    // -----------------------------------------------------------------------
    //  Route advancement
    // -----------------------------------------------------------------------

    private void advanceToNextStop(WorkOrder order) {
        int next = worker.getCurrentStopIndex() + 1;
        if (next >= order.getStops().size()) {
            worker.onRouteComplete();
        } else {
            worker.setCurrentStopIndex(next);
            // Stop any current navigation so it recalculates toward the new stop
            worker.getNavigation().stop();
        }
    }
}
