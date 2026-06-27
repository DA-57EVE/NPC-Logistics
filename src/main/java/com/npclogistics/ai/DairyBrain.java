package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.LivestockTaggable;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class DairyBrain {

    private static final int    SCAN_RADIUS   = 24;
    private static final double ARRIVAL_DIST  = 1.8;
    private static final int    WORK_TICKS    = 20;
    private static final int    CHEST_TICKS   = 30;
    private static final int    WAIT_INTERVAL = 400; // 20 s between milking rounds
    private static final double NAV_SPEED     = 0.9;
    private static final int    NAV_TIMEOUT   = 100;

    private enum Phase { COLLECTING, SCANNING, NAVIGATING_COW, WORKING_MILK, DEPOSITING, WAITING }

    private final LogisticsWorkerEntity worker;

    private Phase     phase          = Phase.DEPOSITING; // return any stale cargo on first tick
    private CowEntity targetCow      = null;
    private int       timer          = 0;
    private int       navTimer       = 0;
    private boolean   initialTagDone = false;
    private final Set<UUID> milkedThisRound = new HashSet<>();

    public DairyBrain(LogisticsWorkerEntity worker) { this.worker = worker; }

    public void tick(ServerWorld world) {
        BlockPos jobsite = worker.getJobsitePos();
        BlockPos deposit = worker.getDepositPos();
        if (jobsite == null || deposit == null) return;

        if (!initialTagDone) {
            initialTagDone = true;
            tagNearbyCows(world, jobsite);
        }

        // Hold bucket in main hand visually
        ItemStack roleTool = worker.getRoleTool();
        if (!roleTool.isEmpty()) {
            ItemStack current = worker.getMainHandStack();
            if (current.isEmpty() || current.getItem() != roleTool.getItem())
                worker.setStackInHand(Hand.MAIN_HAND, roleTool);
        }

        switch (phase) {
            case COLLECTING     -> tickCollecting(world, deposit);
            case SCANNING       -> tickScanning(world, jobsite);
            case NAVIGATING_COW -> tickNavigatingCow();
            case WORKING_MILK   -> tickWorkingMilk(world);
            case DEPOSITING     -> tickDepositing(world, deposit);
            case WAITING        -> tickWaiting(world, jobsite, deposit);
        }
    }

    // ── COLLECTING ────────────────────────────────────────────────────────────
    // Navigate to chest, take empty buckets, then start milking round.

    private void tickCollecting(ServerWorld world, BlockPos depositPos) {
        BlockPos approach = findApproachPos(world, depositPos, worker.getBlockPos());
        if (worker.getPos().distanceTo(approach.toCenterPos()) > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle())
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            return;
        }

        if (timer == 0) {
            BlockState state = world.getBlockState(depositPos);
            Block block = state.getBlock();
            world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                    block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_CHEST_OPEN,
                    SoundCategory.BLOCKS, 0.4f, 1.0f);
            world.addSyncedBlockEvent(depositPos, block, 1, 1);
            timer = CHEST_TICKS;
            return;
        }

        if (--timer > 0) return;

        // Take empty buckets from chest
        int taken = takeBucketsFromChest(world, depositPos);

        BlockState state = world.getBlockState(depositPos);
        Block block = state.getBlock();
        world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE,
                SoundCategory.BLOCKS, 0.4f, 1.0f);
        world.addSyncedBlockEvent(depositPos, block, 1, 0);

        if (taken > 0) {
            NPClogistics.LOGGER.info("{} dairy collected {} empty buckets", worker.getName().getString(), taken);
            phase = Phase.SCANNING;
            timer = 0;
        } else {
            NPClogistics.LOGGER.info("{} dairy: no empty buckets in chest — waiting", worker.getName().getString());
            beginWaiting();
        }
    }

    private int takeBucketsFromChest(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) return 0;
        SimpleInventory npcInv = worker.getWorkerInventory();
        int taken = 0;
        for (int j = 0; j < container.size(); j++) {
            ItemStack slot = container.getStack(j);
            if (slot.isEmpty() || slot.getItem() != Items.BUCKET) continue;
            while (!slot.isEmpty()) {
                ItemStack one = new ItemStack(Items.BUCKET);
                ItemStack remainder = worker.addToWorkerInventory(one);
                if (!remainder.isEmpty()) break; // cargo full
                slot.decrement(1);
                taken++;
            }
            if (slot.isEmpty()) container.setStack(j, ItemStack.EMPTY);
        }
        container.markDirty();
        npcInv.markDirty();
        return taken;
    }

    // ── SCANNING ─────────────────────────────────────────────────────────────

    private void tickScanning(ServerWorld world, BlockPos jobsite) {
        if (--timer > 0) return;

        // If no empty buckets left in cargo, deposit milk and done
        if (countInInventory(Items.BUCKET) == 0) {
            phase = Phase.DEPOSITING;
            timer = -1;
            return;
        }

        tagNearbyCows(world, jobsite);

        Box box = scanBox(jobsite);
        List<CowEntity> unmilked = world.getEntitiesByClass(CowEntity.class, box,
                cow -> !milkedThisRound.contains(cow.getUuid()));

        if (!unmilked.isEmpty()) {
            unmilked.stream()
                    .min(Comparator.comparingDouble(c -> worker.getPos().distanceTo(c.getPos())))
                    .ifPresent(cow -> {
                        targetCow = cow;
                        navTimer  = 0;
                        phase = Phase.NAVIGATING_COW;
                        NPClogistics.LOGGER.info("{} dairy targeting cow at {}",
                                worker.getName().getString(), cow.getBlockPos());
                    });
            return;
        }

        // All cows milked for this round
        phase = Phase.DEPOSITING;
        timer = -1;
    }

    // ── NAVIGATING_COW ────────────────────────────────────────────────────────

    private void tickNavigatingCow() {
        if (targetCow == null || !targetCow.isAlive()) { phase = Phase.SCANNING; timer = 0; return; }
        if (worker.distanceTo(targetCow) <= ARRIVAL_DIST) {
            phase = Phase.WORKING_MILK;
            timer = WORK_TICKS;
            navTimer = 0;
            return;
        }
        if (++navTimer > NAV_TIMEOUT) {
            NPClogistics.LOGGER.info("{} dairy: can't reach cow — skipping", worker.getName().getString());
            milkedThisRound.add(targetCow.getUuid());
            targetCow = null;
            phase = Phase.SCANNING;
            timer = 0;
            navTimer = 0;
            return;
        }
        if (worker.getNavigation().isIdle()) {
            BlockPos cp = targetCow.getBlockPos();
            worker.getNavigation().startMovingTo(cp.getX() + 0.5, cp.getY(), cp.getZ() + 0.5, NAV_SPEED);
        }
    }

    // ── WORKING_MILK ─────────────────────────────────────────────────────────

    private void tickWorkingMilk(ServerWorld world) {
        if (--timer > 0) return;
        if (targetCow == null || !targetCow.isAlive()) { phase = Phase.SCANNING; timer = 0; return; }

        // Swap one empty bucket → one milk bucket
        if (removeOneFromInventory(Items.BUCKET)) {
            worker.addToWorkerInventory(new ItemStack(Items.MILK_BUCKET));
            milkedThisRound.add(targetCow.getUuid());
            world.playSound(null, targetCow.getX(), targetCow.getY(), targetCow.getZ(),
                    SoundEvents.ENTITY_COW_MILK, SoundCategory.NEUTRAL, 1.0f, 1.0f);
            NPClogistics.LOGGER.info("{} milked cow at {}", worker.getName().getString(), targetCow.getBlockPos());
        }

        targetCow = null;
        phase = Phase.SCANNING;
        timer = 5;
    }

    // ── DEPOSITING ────────────────────────────────────────────────────────────

    private void tickDepositing(ServerWorld world, BlockPos depositPos) {
        BlockPos approach = findApproachPos(world, depositPos, worker.getBlockPos());
        if (worker.getPos().distanceTo(approach.toCenterPos()) > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle())
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            return;
        }

        if (timer == -1) {
            if (countInInventory(Items.MILK_BUCKET) == 0 && countInInventory(Items.BUCKET) == 0) { beginWaiting(); return; }
            BlockState state = world.getBlockState(depositPos);
            Block block = state.getBlock();
            world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                    block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_CHEST_OPEN,
                    SoundCategory.BLOCKS, 0.4f, 1.0f);
            world.addSyncedBlockEvent(depositPos, block, 1, 1);
            timer = CHEST_TICKS;
        } else if (--timer <= 0) {
            int deposited = depositMilkBuckets(world, depositPos);
            BlockState state = world.getBlockState(depositPos);
            Block block = state.getBlock();
            world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                    block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE,
                    SoundCategory.BLOCKS, 0.4f, 1.0f);
            world.addSyncedBlockEvent(depositPos, block, 1, 0);
            NPClogistics.LOGGER.info("{} deposited {} milk at {}", worker.getName().getString(), deposited, depositPos);
            beginWaiting();
        }
    }

    private int depositMilkBuckets(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) return 0;
        SimpleInventory npcInv = worker.getWorkerInventory();
        int deposited = 0;
        for (int i = 0; i < npcInv.size(); i++) {
            ItemStack s = npcInv.getStack(i);
            if (s.isEmpty()) continue;
            boolean isMilk   = s.getItem() == Items.MILK_BUCKET;
            boolean isEmpty  = s.getItem() == Items.BUCKET;
            if (!isMilk && !isEmpty) continue;
            for (int j = 0; j < container.size(); j++) {
                ItemStack slot = container.getStack(j);
                if (slot.isEmpty()) {
                    container.setStack(j, s.copy());
                    if (isMilk) deposited += s.getCount();
                    npcInv.setStack(i, ItemStack.EMPTY);
                    break;
                } else if (isEmpty && ItemStack.canCombine(slot, s)) {
                    int space = slot.getMaxCount() - slot.getCount();
                    int move  = Math.min(space, s.getCount());
                    slot.increment(move);
                    s.decrement(move);
                    if (s.isEmpty()) { npcInv.setStack(i, ItemStack.EMPTY); break; }
                }
            }
        }
        container.markDirty();
        npcInv.markDirty();
        return deposited;
    }

    // ── WAITING ───────────────────────────────────────────────────────────────

    private void beginWaiting() {
        phase = Phase.WAITING;
        timer = WAIT_INTERVAL;
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

        milkedThisRound.clear();
        tagNearbyCows(world, jobsite);
        phase = Phase.COLLECTING;
        timer = 0;
    }

    // ── Tagging ───────────────────────────────────────────────────────────────

    private void tagNearbyCows(ServerWorld world, BlockPos jobsite) {
        List<CowEntity> cows = world.getEntitiesByClass(CowEntity.class, scanBox(jobsite), e -> true);
        int count = 0;
        for (CowEntity cow : cows) {
            if (!(cow instanceof LivestockTaggable t)) continue;
            if (!t.npclogistics_isTagged()) { t.npclogistics_setTagged(true, jobsite); count++; }
        }
        if (count > 0)
            NPClogistics.LOGGER.info("{} dairy tagged {} cows", worker.getName().getString(), count);
    }

    // ── Inventory helpers ─────────────────────────────────────────────────────

    private int countInInventory(net.minecraft.item.Item item) {
        SimpleInventory inv = worker.getWorkerInventory();
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() == item) count += s.getCount();
        }
        return count;
    }

    private boolean removeOneFromInventory(net.minecraft.item.Item item) {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() == item) {
                s.decrement(1);
                if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
                inv.markDirty();
                return true;
            }
        }
        return false;
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

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
}
