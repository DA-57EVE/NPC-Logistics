package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.LivestockTaggable;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.server.world.ServerWorld;

import java.util.Comparator;
import java.util.List;

public class ChickenBrain {

    private static final int    SCAN_RADIUS   = 24;
    private static final double ARRIVAL_DIST  = 1.5;
    private static final int    DEPOSIT_TICKS = 40;
    private static final int    WAIT_INTERVAL = 300; // 15 s — eggs drop slowly
    private static final double NAV_SPEED     = 0.9;

    private enum Phase { SCANNING, NAVIGATING_EGG, WORKING_PICKUP, DEPOSITING, WAITING, BREEDING }

    private final LogisticsWorkerEntity worker;

    private Phase         phase          = Phase.SCANNING;
    private BlockPos      targetEggPos   = null;
    private int           timer          = 0;
    private boolean       initialTagDone = false;
    private ChickenEntity breedTarget    = null;
    private int           breedNavTimer  = 0;

    public ChickenBrain(LogisticsWorkerEntity worker) { this.worker = worker; }

    public void tick(ServerWorld world) {
        BlockPos jobsite = worker.getJobsitePos();
        BlockPos deposit = worker.getDepositPos();
        if (jobsite == null || deposit == null) return;

        if (!initialTagDone) {
            initialTagDone = true;
            tagNearbyChickens(world, jobsite);
        }

        // Hold feather in main hand
        ItemStack roleTool = worker.getRoleTool();
        if (!roleTool.isEmpty()) {
            ItemStack current = worker.getMainHandStack();
            if (current.isEmpty() || current.getItem() != roleTool.getItem())
                worker.setStackInHand(Hand.MAIN_HAND, roleTool);
        }

        switch (phase) {
            case SCANNING       -> tickScanning(world, jobsite, deposit);
            case NAVIGATING_EGG -> tickNavigatingEgg();
            case WORKING_PICKUP -> tickWorkingPickup(world);
            case DEPOSITING     -> tickDepositing(world, deposit, jobsite);
            case WAITING        -> tickWaiting(world, jobsite, deposit);
            case BREEDING       -> tickBreeding(world, jobsite);
        }
    }

    // ── SCANNING ─────────────────────────────────────────────────────────────

    private void tickScanning(ServerWorld world, BlockPos jobsite, BlockPos deposit) {
        if (--timer > 0) return;

        Box box = scanBox(jobsite);
        List<ItemEntity> eggs = world.getEntitiesByClass(ItemEntity.class, box,
                e -> e.getStack().getItem() == Items.EGG);

        if (!eggs.isEmpty()) {
            eggs.stream()
                    .min(Comparator.comparingDouble(e -> worker.getPos().distanceTo(e.getPos())))
                    .ifPresent(ie -> {
                        targetEggPos = ie.getBlockPos();
                        phase = Phase.NAVIGATING_EGG;
                        worker.getNavigation().stop();
                    });
            return;
        }

        // No eggs on the ground
        if (!isInventoryEmpty()) {
            phase = Phase.DEPOSITING;
            timer = -1;
        } else {
            beginWaiting();
        }
    }

    // ── NAVIGATING_EGG ────────────────────────────────────────────────────────

    private void tickNavigatingEgg() {
        if (targetEggPos == null) { phase = Phase.SCANNING; timer = 0; return; }
        if (worker.getPos().distanceTo(targetEggPos.toCenterPos()) <= ARRIVAL_DIST) {
            phase = Phase.WORKING_PICKUP;
            timer = 5;
            return;
        }
        if (worker.getNavigation().isIdle()) {
            worker.getNavigation().startMovingTo(
                    targetEggPos.getX() + 0.5, targetEggPos.getY() + 1.0,
                    targetEggPos.getZ() + 0.5, NAV_SPEED);
        }
    }

    // ── WORKING_PICKUP ────────────────────────────────────────────────────────

    private void tickWorkingPickup(ServerWorld world) {
        if (--timer > 0) return;
        pickupNearbyEggs(world);
        targetEggPos = null;
        phase = Phase.SCANNING;
        timer = 5;
    }

    // ── DEPOSITING ────────────────────────────────────────────────────────────

    private void tickDepositing(ServerWorld world, BlockPos depositPos, BlockPos jobsite) {
        BlockPos approach = findApproachPos(world, depositPos, worker.getBlockPos());
        if (worker.getPos().distanceTo(approach.toCenterPos()) > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle())
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            return;
        }

        if (timer == -1) {
            if (isInventoryEmpty()) { beginWaiting(); return; }
            BlockState state = world.getBlockState(depositPos);
            Block block = state.getBlock();
            world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                    block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_CHEST_OPEN,
                    SoundCategory.BLOCKS, 0.4f, 1.0f);
            world.addSyncedBlockEvent(depositPos, block, 1, 1);
            timer = DEPOSIT_TICKS;
        } else if (--timer <= 0) {
            doDeposit(world, depositPos);
            BlockState state = world.getBlockState(depositPos);
            Block block = state.getBlock();
            world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                    block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE,
                    SoundCategory.BLOCKS, 0.4f, 1.0f);
            world.addSyncedBlockEvent(depositPos, block, 1, 0);
            afterDeposit(world, depositPos, jobsite);
        }
    }

    private void afterDeposit(ServerWorld world, BlockPos depositPos, BlockPos jobsite) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container != null) {
            boolean hasSeeds = false;
            for (int j = 0; j < container.size(); j++) {
                ItemStack slot = container.getStack(j);
                if (!slot.isEmpty() && slot.getItem() == Items.WHEAT_SEEDS) { hasSeeds = true; break; }
            }
            if (hasSeeds) {
                long breedable = world.getEntitiesByClass(ChickenEntity.class, scanBox(jobsite),
                        c -> !c.isBaby() && !c.isInLove()).size();
                if (breedable >= 2) {
                    int taken = takeItemFromChest(world, depositPos, Items.WHEAT_SEEDS, (int) breedable);
                    if (taken > 0) {
                        NPClogistics.LOGGER.info("{} chicken-farmer: took {} seeds to breed {} chickens",
                                worker.getName().getString(), taken, breedable);
                        phase = Phase.BREEDING;
                        breedTarget   = null;
                        breedNavTimer = 0;
                        return;
                    }
                }
            }
        }
        beginWaiting();
    }

    private void tickBreeding(ServerWorld world, BlockPos jobsite) {
        if (breedTarget != null && breedTarget.isAlive() && !breedTarget.isBaby() && !breedTarget.isInLove()) {
            if (worker.distanceTo(breedTarget) <= ARRIVAL_DIST) {
                removeOneFromInventory(Items.WHEAT_SEEDS);
                breedTarget.lovePlayer(null);
                NPClogistics.LOGGER.info("{} chicken-farmer bred chicken at {}",
                        worker.getName().getString(), breedTarget.getBlockPos());
                breedTarget   = null;
                breedNavTimer = 0;
                return;
            }
            if (++breedNavTimer <= 100) {
                if (worker.getNavigation().isIdle())
                    worker.getNavigation().startMovingTo(
                            breedTarget.getX(), breedTarget.getY(), breedTarget.getZ(), NAV_SPEED);
                return;
            }
            breedTarget   = null;
            breedNavTimer = 0;
        }

        breedTarget   = null;
        breedNavTimer = 0;

        if (countInInventory(Items.WHEAT_SEEDS) == 0) { beginWaiting(); return; }

        breedTarget = world.getEntitiesByClass(ChickenEntity.class, scanBox(jobsite),
                        c -> !c.isBaby() && !c.isInLove())
                .stream()
                .min(Comparator.comparingDouble(c -> worker.distanceTo(c)))
                .orElse(null);

        if (breedTarget == null) beginWaiting();
    }

    // ── WAITING ───────────────────────────────────────────────────────────────

    private void beginWaiting() {
        phase = Phase.WAITING;
        timer = 0;
        worker.getNavigation().stop();
    }

    private void tickWaiting(ServerWorld world, BlockPos jobsite, BlockPos depositPos) {
        if (worker.getNavigation().isIdle()) {
            if (worker.getPos().distanceTo(depositPos.toCenterPos()) > ARRIVAL_DIST) {
                BlockPos approach = findApproachPos(world, depositPos, worker.getBlockPos());
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            }
        }

        if (--timer > 0) return;
        timer = WAIT_INTERVAL;

        tagNearbyChickens(world, jobsite);

        Box box = scanBox(jobsite);
        List<ItemEntity> eggs = world.getEntitiesByClass(ItemEntity.class, box,
                e -> e.getStack().getItem() == Items.EGG);
        int chickens = world.getEntitiesByClass(ChickenEntity.class, box, e -> true).size();

        if (!eggs.isEmpty()) {
            phase = Phase.SCANNING;
            timer = 0;
        } else {
            NPClogistics.LOGGER.info("{} chicken: waiting ({} chickens, no eggs yet)",
                    worker.getName().getString(), chickens);
        }
    }

    // ── Tagging ───────────────────────────────────────────────────────────────

    private void tagNearbyChickens(ServerWorld world, BlockPos jobsite) {
        List<ChickenEntity> chickens = world.getEntitiesByClass(ChickenEntity.class, scanBox(jobsite), e -> true);
        int count = 0;
        int myColor = LivestockTaggable.colorForOwner(worker.getUuid());
        for (ChickenEntity chicken : chickens) {
            if (!(chicken instanceof LivestockTaggable t)) continue;
            if (!t.npclogistics_isTagged() || t.npclogistics_getOwnerColor() != myColor) {
                t.npclogistics_setTagged(true, jobsite);
                t.npclogistics_setOwnerColor(myColor);
                count++;
            }
        }
        if (count > 0)
            NPClogistics.LOGGER.info("{} chicken tagged {} chickens", worker.getName().getString(), count);
    }

    // ── Pickup / deposit ──────────────────────────────────────────────────────

    private void pickupNearbyEggs(ServerWorld world) {
        net.minecraft.util.math.Vec3d pos = worker.getPos();
        Box reach = new Box(pos.x - 2, pos.y - 0.5, pos.z - 2, pos.x + 2, pos.y + 2, pos.z + 2);
        List<ItemEntity> nearby = world.getEntitiesByClass(ItemEntity.class, reach,
                e -> e.getStack().getItem() == Items.EGG);
        int picked = 0;
        for (ItemEntity ie : nearby) {
            ItemStack stack = ie.getStack().copy();
            ItemStack remainder = worker.addToWorkerInventory(stack);
            int added = stack.getCount() - remainder.getCount();
            if (added > 0) {
                picked += added;
                if (remainder.isEmpty()) ie.discard(); else ie.setStack(remainder);
            }
        }
        if (picked > 0)
            NPClogistics.LOGGER.info("{} picked up {} eggs", worker.getName().getString(), picked);
    }

    private void doDeposit(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) {
            NPClogistics.LOGGER.warn("{} chicken: no container at deposit {}", worker.getName().getString(), depositPos);
            return;
        }
        SimpleInventory npcInv = worker.getWorkerInventory();
        int deposited = 0;
        for (int i = 0; i < npcInv.size(); i++) {
            ItemStack s = npcInv.getStack(i);
            if (s.isEmpty()) continue;
            for (int j = 0; j < container.size(); j++) {
                ItemStack slot = container.getStack(j);
                if (slot.isEmpty()) {
                    container.setStack(j, s.copy());
                    deposited += s.getCount();
                    npcInv.setStack(i, ItemStack.EMPTY);
                    break;
                } else if (ItemStack.canCombine(slot, s)) {
                    int space = slot.getMaxCount() - slot.getCount();
                    int move = Math.min(space, s.getCount());
                    slot.increment(move);
                    s.decrement(move);
                    deposited += move;
                    if (s.isEmpty()) { npcInv.setStack(i, ItemStack.EMPTY); break; }
                }
            }
        }
        container.markDirty();
        npcInv.markDirty();
        NPClogistics.LOGGER.info("{} deposited {} eggs at {}", worker.getName().getString(), deposited, depositPos);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Box scanBox(BlockPos jobsite) {
        return new Box(
                jobsite.getX() - SCAN_RADIUS, jobsite.getY() - 4, jobsite.getZ() - SCAN_RADIUS,
                jobsite.getX() + SCAN_RADIUS, jobsite.getY() + 4, jobsite.getZ() + SCAN_RADIUS);
    }

    private static BlockPos findApproachPos(ServerWorld world, BlockPos targetPos, BlockPos npcPos) {
        Direction[] dirs = npcSidedDirections(targetPos, npcPos);
        for (Direction d : dirs) {
            BlockPos c = targetPos.offset(d);
            if (isStandable(world, c)) return c;
        }
        for (Direction d : dirs) {
            BlockPos c = targetPos.offset(d, 2);
            if (isStandable(world, c)) return c;
        }
        return targetPos;
    }

    private static Direction[] npcSidedDirections(BlockPos target, BlockPos npc) {
        int dx = npc.getX() - target.getX(), dz = npc.getZ() - target.getZ();
        Direction primary = Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Direction[] all = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Direction[] sorted = new Direction[4]; sorted[0] = primary;
        int i = 1; for (Direction d : all) if (d != primary) sorted[i++] = d;
        return sorted;
    }

    private static boolean isStandable(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isAir()
                && world.getBlockState(pos.up()).isAir()
                && !world.getBlockState(pos.down()).isAir();
    }

    private boolean isInventoryEmpty() {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++) if (!inv.getStack(i).isEmpty()) return false;
        return true;
    }

    private int countInInventory(Item item) {
        SimpleInventory inv = worker.getWorkerInventory();
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() == item) count += s.getCount();
        }
        return count;
    }

    private boolean removeOneFromInventory(Item item) {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() != item) continue;
            s.decrement(1);
            if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
            inv.markDirty();
            return true;
        }
        return false;
    }

    private int takeItemFromChest(ServerWorld world, BlockPos depositPos, Item item, int maxAmount) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) return 0;
        SimpleInventory npcInv = worker.getWorkerInventory();
        int taken = 0;
        for (int j = 0; j < container.size() && taken < maxAmount; j++) {
            ItemStack slot = container.getStack(j);
            if (slot.isEmpty() || slot.getItem() != item) continue;
            int take = Math.min(slot.getCount(), maxAmount - taken);
            ItemStack toAdd    = new ItemStack(item, take);
            ItemStack leftover = worker.addToWorkerInventory(toAdd);
            int actual = take - (leftover.isEmpty() ? 0 : leftover.getCount());
            slot.decrement(actual);
            if (slot.isEmpty()) container.setStack(j, ItemStack.EMPTY);
            taken += actual;
            if (!leftover.isEmpty()) break;
        }
        container.markDirty();
        npcInv.markDirty();
        return taken;
    }
}
