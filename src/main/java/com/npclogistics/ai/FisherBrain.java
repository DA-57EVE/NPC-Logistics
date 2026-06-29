package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

public class FisherBrain {

    private static final double NAV_SPEED     = 0.9;
    private static final double ARRIVAL_DIST  = 2.5;
    private static final int    CAST_TICKS    = 15;
    private static final int    MIN_BITE      = 100;
    private static final int    MAX_BITE      = 200;
    private static final int    REEL_TICKS    = 20;
    private static final int    DEPOSIT_TICKS = 40;
    private static final int    FISH_PER_TRIP = 6;

    private enum Phase { TRAVELLING, CASTING, WAITING, REELING, RETURNING, DEPOSITING }

    private final LogisticsWorkerEntity worker;
    private Phase    phase       = Phase.RETURNING; // deposit stale cargo on first activation
    private int      timer       = -1;
    private int      catchCount  = 0;
    private BlockPos fishingPost = null; // standable spot at/near jobsite
    private BlockPos waterTarget = null; // nearest water block to face while fishing

    public FisherBrain(LogisticsWorkerEntity worker) { this.worker = worker; }

    // ── TICK ──────────────────────────────────────────────────────────────────

    public void tick(ServerWorld world) {
        BlockPos jobsite = worker.getJobsitePos();
        BlockPos deposit = worker.getDepositPos();
        if (jobsite == null || deposit == null) return;

        ItemStack roleTool = worker.getRoleTool();
        if (!roleTool.isEmpty() && worker.getMainHandStack().getItem() != roleTool.getItem())
            worker.setStackInHand(Hand.MAIN_HAND, roleTool);

        switch (phase) {
            case TRAVELLING -> tickTravelling(world, jobsite);
            case CASTING    -> tickCasting(world);
            case WAITING    -> tickWaiting(world);
            case REELING    -> tickReeling(world);
            case RETURNING  -> tickReturning(world, deposit);
            case DEPOSITING -> tickDepositing(world, deposit);
        }
    }

    // ── TRAVELLING ────────────────────────────────────────────────────────────

    private void tickTravelling(ServerWorld world, BlockPos jobsite) {
        if (fishingPost == null) fishingPost = findFishingPost(world, jobsite);
        if (worker.getPos().distanceTo(fishingPost.toCenterPos()) <= ARRIVAL_DIST) {
            if (waterTarget == null) waterTarget = findWaterTarget(world, fishingPost);
            phase = Phase.CASTING;
            timer = CAST_TICKS;
            return;
        }
        if (worker.getNavigation().isIdle())
            worker.getNavigation().startMovingTo(
                    fishingPost.getX() + 0.5, fishingPost.getY(), fishingPost.getZ() + 0.5, NAV_SPEED);
    }

    // ── CASTING ───────────────────────────────────────────────────────────────

    private void tickCasting(ServerWorld world) {
        lookAtWater();
        if (timer == CAST_TICKS) {
            swingMainHand(world);
            world.playSound(null, worker.getX(), worker.getY(), worker.getZ(),
                    SoundEvents.ENTITY_FISHING_BOBBER_THROW, SoundCategory.NEUTRAL, 0.5f, 0.5f);
        }
        if (--timer <= 0) {
            phase = Phase.WAITING;
            timer = MIN_BITE + worker.getRandom().nextInt(MAX_BITE - MIN_BITE);
            NPClogistics.LOGGER.info("{} fisher: line in, waiting {}t for bite",
                    worker.getName().getString(), timer);
        }
    }

    // ── WAITING ───────────────────────────────────────────────────────────────

    private void tickWaiting(ServerWorld world) {
        lookAtWater();
        if (--timer <= 0) {
            world.playSound(null, worker.getX(), worker.getY(), worker.getZ(),
                    SoundEvents.ENTITY_FISHING_BOBBER_SPLASH, SoundCategory.NEUTRAL, 0.25f, 1.0f);
            phase = Phase.REELING;
            timer = REEL_TICKS;
        }
    }

    // ── REELING ───────────────────────────────────────────────────────────────

    private void tickReeling(ServerWorld world) {
        if (--timer > 0) return;
        swingMainHand(world);
        world.playSound(null, worker.getX(), worker.getY(), worker.getZ(),
                SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.NEUTRAL, 1.0f, 1.0f);
        ItemStack fish = rollFishLoot(worker.getRandom());
        worker.addToWorkerInventory(fish.copy());
        catchCount++;
        NPClogistics.LOGGER.info("{} fisher: caught {} (trip catch #{})",
                worker.getName().getString(), fish.getItem().getName().getString(), catchCount);
        if (catchCount >= FISH_PER_TRIP || isInventoryFull()) {
            catchCount = 0;
            phase = Phase.RETURNING;
            timer = -1;
        } else {
            phase = Phase.CASTING;
            timer = CAST_TICKS;
        }
    }

    // ── RETURNING ─────────────────────────────────────────────────────────────

    private void tickReturning(ServerWorld world, BlockPos depositPos) {
        if (isInventoryEmpty()) {
            phase = Phase.TRAVELLING;
            worker.getNavigation().stop();
            return;
        }
        if (worker.getPos().distanceTo(depositPos.toCenterPos()) <= ARRIVAL_DIST) {
            phase = Phase.DEPOSITING;
            timer = -1;
            return;
        }
        if (worker.getNavigation().isIdle()) {
            BlockPos approach = findApproachPos(world, depositPos, worker.getBlockPos());
            worker.getNavigation().startMovingTo(
                    approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
        }
    }

    // ── DEPOSITING ────────────────────────────────────────────────────────────

    private void tickDepositing(ServerWorld world, BlockPos depositPos) {
        if (worker.getPos().distanceTo(depositPos.toCenterPos()) > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle()) {
                BlockPos approach = findApproachPos(world, depositPos, worker.getBlockPos());
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            }
            return;
        }
        if (timer == -1) {
            var block = world.getBlockState(depositPos).getBlock();
            world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                    block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_CHEST_OPEN,
                    SoundCategory.BLOCKS, 0.4f, 1.0f);
            world.addSyncedBlockEvent(depositPos, block, 1, 1);
            timer = DEPOSIT_TICKS;
        } else if (--timer <= 0) {
            doDeposit(world, depositPos);
            var block = world.getBlockState(depositPos).getBlock();
            world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                    block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE,
                    SoundCategory.BLOCKS, 0.4f, 1.0f);
            world.addSyncedBlockEvent(depositPos, block, 1, 0);
            world.playSound(null, worker.getX(), worker.getY(), worker.getZ(),
                    SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.NEUTRAL, 0.2f, 1.5f);
            worker.getNavigation().stop();
            phase = Phase.TRAVELLING;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void doDeposit(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) {
            NPClogistics.LOGGER.warn("{} fisher: no container at deposit pos {}",
                    worker.getName().getString(), depositPos);
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
                    int move  = Math.min(space, s.getCount());
                    slot.increment(move);
                    s.decrement(move);
                    deposited += move;
                    if (s.isEmpty()) { npcInv.setStack(i, ItemStack.EMPTY); break; }
                }
            }
        }
        container.markDirty();
        npcInv.markDirty();
        NPClogistics.LOGGER.info("{} fisher: deposited {} items", worker.getName().getString(), deposited);
    }

    /** Jobsite token is stamped on the dock/shore block; stand on top of it or adjacent. */
    private BlockPos findFishingPost(ServerWorld world, BlockPos jobsite) {
        if (isStandable(world, jobsite.up())) return jobsite.up();
        if (isStandable(world, jobsite))      return jobsite;
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos c = jobsite.offset(dir);
            if (isStandable(world, c)) return c;
        }
        return jobsite.up();
    }

    /** Scan outward from the fishing post for the nearest water block to face.
     *  Checks at the post's Y level first, then one block below (river water sits at ground level,
     *  one block below fishingPost which is the air above the bank block). */
    private static BlockPos findWaterTarget(ServerWorld world, BlockPos from) {
        for (int dy = 0; dy >= -1; dy--) {
            for (int dist = 1; dist <= 5; dist++) {
                for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    BlockPos c = from.add(dir.getOffsetX() * dist, dy, dir.getOffsetZ() * dist);
                    if (!world.getFluidState(c).isEmpty()) return c;
                }
            }
        }
        return from.north();
    }

    private void lookAtWater() {
        if (waterTarget == null) return;
        // Use the NPC's own eye height so it looks horizontally rather than steeply downward
        worker.getLookControl().lookAt(
                waterTarget.getX() + 0.5, worker.getEyeY(), waterTarget.getZ() + 0.5);
    }

    private static boolean isStandable(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isAir()
                && world.getBlockState(pos.up()).isAir()
                && !world.getBlockState(pos.down()).isAir();
    }

    private static BlockPos findApproachPos(ServerWorld world, BlockPos target, BlockPos npc) {
        Direction primary = primaryDir(target, npc);
        Direction[] dirs  = orderedDirs(primary);
        for (Direction d : dirs) { BlockPos c = target.offset(d);      if (isStandable(world, c)) return c; }
        for (Direction d : dirs) { BlockPos c = target.offset(d).up(); if (isStandable(world, c)) return c; }
        return target;
    }

    private static Direction primaryDir(BlockPos target, BlockPos npc) {
        int dx = npc.getX() - target.getX(), dz = npc.getZ() - target.getZ();
        return Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    private static Direction[] orderedDirs(Direction primary) {
        Direction[] all = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Direction[] out = new Direction[4]; out[0] = primary; int i = 1;
        for (Direction d : all) if (d != primary) out[i++] = d;
        return out;
    }

    private void swingMainHand(ServerWorld world) {
        var pkt = new EntityAnimationS2CPacket(worker, EntityAnimationS2CPacket.SWING_MAIN_HAND);
        for (var p : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(worker))
            p.networkHandler.sendPacket(pkt);
    }

    private boolean isInventoryFull() {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isEmpty()) return false;
        return true;
    }

    private boolean isInventoryEmpty() {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++) if (!inv.getStack(i).isEmpty()) return false;
        return true;
    }

    private static ItemStack rollFishLoot(Random rng) {
        int roll = rng.nextInt(100);
        if (roll < 60) return new ItemStack(Items.COD);
        if (roll < 85) return new ItemStack(Items.SALMON);
        if (roll < 98) return new ItemStack(Items.TROPICAL_FISH);
        return new ItemStack(Items.PUFFERFISH);
    }
}
