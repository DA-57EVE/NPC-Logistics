package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.data.WorkOrder;
import com.npclogistics.data.WorkOrder.RouteStop;
import com.npclogistics.entity.LogisticsWorkerEntity;
import com.npclogistics.entity.LogisticsWorkerEntity.WorkerState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.world.ServerWorld;
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
    private static final double ARRIVAL_DISTANCE = 2.0;
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
        if (!isAtStop(stop)) {
            double dist = worker.getPos().distanceTo(stop.pos.toCenterPos());
            if (dist > ARRIVAL_DISTANCE + 0.5) {
                // Navigate to the block adjacent to the chest (same Y, one step away)
                worker.getNavigation().startMovingTo(
                        stop.pos.getX() + 0.5,
                        stop.pos.getY(),
                        stop.pos.getZ() + 0.5,
                        1.0
                );
            }
        }
    }

    private boolean isAtStop(RouteStop stop) {
        return worker.getPos().distanceTo(stop.pos.toCenterPos()) <= ARRIVAL_DISTANCE;
    }

    // -----------------------------------------------------------------------
    //  Container interaction
    // -----------------------------------------------------------------------

    /**
     * Attempts to interact with the chest or barrel at the stop's position.
     * @return true if the container was found and interaction occurred (even if 0 items moved)
     */
    private boolean interactWithContainer(ServerWorld world, RouteStop stop) {
        BlockEntity be = world.getBlockEntity(stop.pos);
        SimpleInventory container = null;

        if (be instanceof ChestBlockEntity chest) {
            // Wrap the chest's inventory into a SimpleInventory view for uniform access
            container = inventorySnapshot(chest);
        } else if (be instanceof BarrelBlockEntity barrel) {
            container = inventorySnapshot(barrel);
        }

        if (container == null) return false;

        // Deliver before collecting so a BOTH stop restocks the container and then grabs
        // outputs in one visit. Both passes operate on the same snapshot; sync back once.
        if (stop.doesDeliver()) {
            int given = worker.deliverItemsToInventory(container, stop);
            NPClogistics.LOGGER.info("{} delivered {} items to {}",
                    worker.getName().getString(), given, stop.pos);
        }
        if (stop.doesCollect()) {
            int taken = worker.collectItemsFromInventory(container, stop);
            NPClogistics.LOGGER.info("{} collected {} items from {}",
                    worker.getName().getString(), taken, stop.pos);
        }
        syncContainerBack(be, container);

        return true;
    }

    /**
     * Creates a mutable SimpleInventory snapshot of a chest or barrel so we
     * can read/write it uniformly, then sync it back.
     */
    private SimpleInventory inventorySnapshot(BlockEntity be) {
        if (be instanceof ChestBlockEntity chest) {
            SimpleInventory snap = new SimpleInventory(chest.size());
            for (int i = 0; i < chest.size(); i++) snap.setStack(i, chest.getStack(i).copy());
            return snap;
        }
        if (be instanceof BarrelBlockEntity barrel) {
            SimpleInventory snap = new SimpleInventory(barrel.size());
            for (int i = 0; i < barrel.size(); i++) snap.setStack(i, barrel.getStack(i).copy());
            return snap;
        }
        return null;
    }

    /** Writes the mutated snapshot back into the real container block entity. */
    private void syncContainerBack(BlockEntity be, SimpleInventory snap) {
        if (be instanceof ChestBlockEntity chest) {
            for (int i = 0; i < chest.size(); i++) chest.setStack(i, snap.getStack(i));
            chest.markDirty();
        } else if (be instanceof BarrelBlockEntity barrel) {
            for (int i = 0; i < barrel.size(); i++) barrel.setStack(i, snap.getStack(i));
            barrel.markDirty();
        }
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
