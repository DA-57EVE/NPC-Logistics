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

public class WorkOrderBrain {

    private static final double ARRIVAL_DISTANCE    = 3.0;
    private static final int INTERACTION_COOLDOWN   = 10;
    /** Ticks the lid stays open before items move. */
    private static final int OPEN_HOLD_TICKS        = 20;
    /** Ticks after the lid closes before moving to the next stop. */
    private static final int CLOSE_HOLD_TICKS       = 10;

    private enum Phase { IDLE, OPENING, CLOSING }

    private final LogisticsWorkerEntity worker;
    private Phase phase    = Phase.IDLE;
    private int phaseTimer = 0;

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

        RouteStop stop = order.getStops().get(idx);

        switch (phase) {
            case IDLE -> {
                navigateToStop(stop);
                if (isAtStop(stop) && worker.getInteractionCooldown() == 0) {
                    openContainer(world, stop);
                    phase = Phase.OPENING;
                    phaseTimer = OPEN_HOLD_TICKS;
                }
            }
            case OPENING -> {
                if (--phaseTimer <= 0) {
                    boolean ok = doInteract(world, stop);
                    if (!ok) {
                        NPClogistics.LOGGER.warn("{} could not interact with container at {}; skipping.",
                                worker.getName().getString(), stop.pos);
                    }
                    closeContainer(world, stop);
                    worker.setInteractionCooldown(INTERACTION_COOLDOWN);
                    phase = Phase.CLOSING;
                    phaseTimer = CLOSE_HOLD_TICKS;
                }
            }
            case CLOSING -> {
                if (--phaseTimer <= 0) {
                    phase = Phase.IDLE;
                    advanceToNextStop(order);
                }
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
    //  Container open / close (sound + lid animation)
    // -----------------------------------------------------------------------

    private void openContainer(ServerWorld world, RouteStop stop) {
        SoundEvent sound = world.getBlockState(stop.pos).isOf(Blocks.BARREL)
                ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_CHEST_OPEN;
        world.playSound(null, stop.pos.getX() + 0.5, stop.pos.getY() + 0.5, stop.pos.getZ() + 0.5,
                sound, SoundCategory.BLOCKS, 0.4f, 1.0f);
        // Sends a viewer-count block event (type 1) so the chest lid animates open on clients.
        world.addSyncedBlockEvent(stop.pos, world.getBlockState(stop.pos).getBlock(), 1, 1);
    }

    private void closeContainer(ServerWorld world, RouteStop stop) {
        SoundEvent sound = world.getBlockState(stop.pos).isOf(Blocks.BARREL)
                ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE;
        world.playSound(null, stop.pos.getX() + 0.5, stop.pos.getY() + 0.5, stop.pos.getZ() + 0.5,
                sound, SoundCategory.BLOCKS, 0.4f, 1.0f);
        world.addSyncedBlockEvent(stop.pos, world.getBlockState(stop.pos).getBlock(), 1, 0);
    }

    // -----------------------------------------------------------------------
    //  Item transfer
    // -----------------------------------------------------------------------

    private boolean doInteract(ServerWorld world, RouteStop stop) {
        BlockEntity be = world.getBlockEntity(stop.pos);
        if (!(be instanceof Inventory inv)) return false;

        int size = inv.size();
        SimpleInventory snapshot = new SimpleInventory(size);
        for (int i = 0; i < size; i++) snapshot.setStack(i, inv.getStack(i).copy());

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
            worker.getNavigation().stop();
        }
    }
}
