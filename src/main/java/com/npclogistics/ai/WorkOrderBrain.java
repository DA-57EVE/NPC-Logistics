package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.data.WorkOrder;
import com.npclogistics.data.WorkOrder.RouteStop;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.util.math.Direction;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class WorkOrderBrain {

    private static final double ARRIVAL_DISTANCE    = 3.0;
    private static final int INTERACTION_COOLDOWN   = 10;
    /** Ticks the lid stays open before items move. */
    private static final int OPEN_HOLD_TICKS        = 20;
    /** Ticks after the lid closes before moving to the next stop. */
    private static final int CLOSE_HOLD_TICKS       = 10;

    private enum Phase { IDLE, OPENING, CLOSING }

    private final LogisticsWorkerEntity worker;
    private Phase phase         = Phase.IDLE;
    private int phaseTimer      = 0;
    private boolean strictArrival = true; // false when no adjacent approach pos was found

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
                navigateToStop(world, stop);
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

    private void navigateToStop(ServerWorld world, RouteStop stop) {
        if (!isAtStop(stop) && worker.getNavigation().isIdle()) {
            BlockPos approach = findApproachPos(world, stop.pos);
            if (approach.equals(stop.pos)) {
                // No clear adjacent position found (chest in a corner, etc.); fall back to
                // the old behaviour — NPC may end up on top but will still interact.
                strictArrival = false;
                worker.getNavigation().startMovingTo(
                        stop.pos.getX() + 0.5, stop.pos.getY(), stop.pos.getZ() + 0.5, 1.0);
            } else {
                strictArrival = true;
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, 1.0);
            }
        }
    }

    /**
     * Returns a walkable position adjacent to {@code containerPos} so the NPC stands
     * beside the container rather than on top of it. Checks the four cardinal neighbors;
     * the first one with two clear blocks above a solid floor is chosen. Falls back to
     * the container's own position if none is found (e.g. chest surrounded on all sides).
     */
    private static BlockPos findApproachPos(ServerWorld world, BlockPos containerPos) {
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos candidate = containerPos.offset(dir);
            BlockPos below = candidate.down();
            if (world.getBlockState(candidate).isAir()
                    && world.getBlockState(candidate.up()).isAir()
                    && !world.getBlockState(below).isAir()) {
                return candidate;
            }
        }
        return containerPos;
    }

    private boolean isAtStop(RouteStop stop) {
        if (worker.getPos().distanceTo(stop.pos.toCenterPos()) > ARRIVAL_DISTANCE) return false;
        // Reject arrival if the NPC is on top of the container and a proper adjacent approach
        // position was found. Skipped when no adjacent position exists (strictArrival = false).
        if (strictArrival && worker.getBlockPos().getY() > stop.pos.getY()) return false;
        return true;
    }

    // -----------------------------------------------------------------------
    //  Container open / close (sound + lid animation)
    // -----------------------------------------------------------------------

    private void openContainer(ServerWorld world, RouteStop stop) {
        BlockState state = world.getBlockState(stop.pos);
        Block block = state.getBlock();
        SoundEvent sound = block == Blocks.BARREL
                ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_CHEST_OPEN;
        world.playSound(null, stop.pos.getX() + 0.5, stop.pos.getY() + 0.5, stop.pos.getZ() + 0.5,
                sound, SoundCategory.BLOCKS, 0.4f, 1.0f);
        world.addSyncedBlockEvent(stop.pos, block, 1, 1);
        // Double chests: also animate the other half.
        if (block instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            world.addSyncedBlockEvent(stop.pos.offset(otherChestHalf(state)), block, 1, 1);
        }
    }

    private void closeContainer(ServerWorld world, RouteStop stop) {
        BlockState state = world.getBlockState(stop.pos);
        Block block = state.getBlock();
        SoundEvent sound = block == Blocks.BARREL
                ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE;
        world.playSound(null, stop.pos.getX() + 0.5, stop.pos.getY() + 0.5, stop.pos.getZ() + 0.5,
                sound, SoundCategory.BLOCKS, 0.4f, 1.0f);
        world.addSyncedBlockEvent(stop.pos, block, 1, 0);
        if (block instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            world.addSyncedBlockEvent(stop.pos.offset(otherChestHalf(state)), block, 1, 0);
        }
    }

    /** Returns the direction from one chest half to the other (LEFT→clockwise, RIGHT→counter-clockwise). */
    private static Direction otherChestHalf(BlockState state) {
        Direction facing = ChestBlock.getFacing(state);
        return state.get(ChestBlock.CHEST_TYPE) == ChestType.LEFT
                ? facing.rotateYClockwise()
                : facing.rotateYCounterclockwise();
    }

    // -----------------------------------------------------------------------
    //  Item transfer — Inventory path (vanilla + double chests)
    // -----------------------------------------------------------------------

    private boolean doInteract(ServerWorld world, RouteStop stop) {
        Inventory inv = resolveInventory(world, stop.pos);
        if (inv != null) {
            return doInteractInventory(inv, stop);
        }
        // Fallback: Fabric Transfer API for mod storage blocks that don't implement Inventory.
        @SuppressWarnings("UnstableApiUsage")
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(world, stop.pos, null);
        if (storage != null) {
            return doInteractTransferApi(storage, stop);
        }
        return false;
    }

    /**
     * Resolves an {@link Inventory} for the given position.
     * Uses {@link ChestBlock#getInventory} for chest blocks so that a double chest
     * returns the full 54-slot combined view rather than one half's 27 slots.
     * Falls back to a direct BlockEntity cast for barrels, hoppers, etc.
     */
    private static Inventory resolveInventory(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            // ignoreBlocked=true: NPC can access a chest even if a cat is sitting on it.
            return ChestBlock.getInventory(chestBlock, state, world, pos, true);
        }
        BlockEntity be = world.getBlockEntity(pos);
        return be instanceof Inventory i ? i : null;
    }

    private boolean doInteractInventory(Inventory inv, RouteStop stop) {
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
    //  Item transfer — Fabric Transfer API path (mod storage blocks)
    // -----------------------------------------------------------------------

    /**
     * Interact with a storage that exposes the Fabric Transfer API but does not implement
     * {@link Inventory}. We snapshot the storage contents into a {@link SimpleInventory},
     * run the same collect/deliver logic, then diff before-vs-after by item type and apply
     * the changes back via a single committed {@link Transaction}.
     *
     * Limitation: NBT on stacks is not tracked through the diff; plain item counts are used.
     * This matches the rest of the system (filters are {@code List<Item>}, not NBT-aware).
     */
    @SuppressWarnings("UnstableApiUsage")
    private boolean doInteractTransferApi(Storage<ItemVariant> storage, RouteStop stop) {
        // --- Read snapshot ---
        // Storage<T> extends Iterable so iteration needs no transaction.
        ArrayList<ItemStack> snapList = new ArrayList<>();
        for (StorageView<ItemVariant> view : storage.nonEmptyViews()) {
            if (view.isResourceBlank() || view.getAmount() <= 0) continue;
            ItemVariant v = view.getResource();
            long remaining = view.getAmount();
            int maxCount = v.getItem().getMaxCount();
            while (remaining > 0) {
                int take = (int) Math.min(remaining, maxCount);
                snapList.add(v.toStack(take));
                remaining -= take;
            }
        }

        SimpleInventory snapshot = new SimpleInventory(Math.max(snapList.size(), 1));
        for (int i = 0; i < snapList.size(); i++) snapshot.setStack(i, snapList.get(i));

        Map<Item, Integer> before = countByItem(snapshot);

        if (stop.doesDeliver()) {
            int given = worker.deliverItemsToInventory(snapshot, stop);
            NPClogistics.LOGGER.info("{} delivered {} items to {} (Transfer API)",
                    worker.getName().getString(), given, stop.pos);
        }
        if (stop.doesCollect()) {
            int taken = worker.collectItemsFromInventory(snapshot, stop);
            NPClogistics.LOGGER.info("{} collected {} items from {} (Transfer API)",
                    worker.getName().getString(), taken, stop.pos);
        }

        // --- Flush diffs ---
        Map<Item, Integer> after = countByItem(snapshot);
        try (Transaction tx = Transaction.openOuter()) {
            for (Map.Entry<Item, Integer> entry : before.entrySet()) {
                Item item = entry.getKey();
                int delta = after.getOrDefault(item, 0) - entry.getValue();
                if (delta > 0) {
                    storage.insert(ItemVariant.of(item), delta, tx);
                } else if (delta < 0) {
                    storage.extract(ItemVariant.of(item), -delta, tx);
                }
            }
            // Items present in 'after' but absent from 'before' were newly delivered.
            for (Map.Entry<Item, Integer> entry : after.entrySet()) {
                if (!before.containsKey(entry.getKey())) {
                    storage.insert(ItemVariant.of(entry.getKey()), entry.getValue(), tx);
                }
            }
            tx.commit();
        }
        return true;
    }

    private static Map<Item, Integer> countByItem(Inventory inv) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        return counts;
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
