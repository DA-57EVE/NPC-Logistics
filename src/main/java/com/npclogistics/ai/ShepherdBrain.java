package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.DyeColor;
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

public class ShepherdBrain {

    private static final int    SCAN_RADIUS        = 24;
    private static final double ARRIVAL_DIST       = 1.5;
    private static final int    WORK_TICKS         = 15;
    private static final int    DEPOSIT_TICKS      = 40;
    private static final int    SCAN_INTERVAL      = 60;
    private static final double NAV_SPEED          = 0.9;
    private static final int    WAIT_INTERVAL      = 200; // ticks between scans while waiting at deposit chest
    private static final int    TRANSIT_TIMEOUT    = 400; // 20 s — ENTERING or EXITING
    private static final int    NAV_TIMEOUT        = 100; // 5 s — NAVIGATING_SHEEP
    private static final double GATE_OPEN_DIST     = 1.5; // open gate when this close
    private static final double GATE_CLOSE_DIST    = 4.5; // auto-close gate once NPC is this far past it
    private static final double ENTRY_DIST         = 2.0; // "inside pen" threshold
    private static final int    GATE_SEARCH_RADIUS = 8;

    private enum Phase {
        SCANNING, ENTERING, EXITING,
        NAVIGATING_SHEEP, NAVIGATING_ITEM,
        WORKING_SHEAR, WORKING_PICKUP, DEPOSITING, WAITING
    }

    private final LogisticsWorkerEntity worker;

    private Phase       phase              = Phase.SCANNING;
    private SheepEntity targetSheep        = null;
    private BlockPos    targetItemPos      = null;
    private int         timer              = 0;
    private boolean     isInsidePen        = false;

    // shared gate state for ENTERING / EXITING
    private BlockPos    managedGatePos     = null;
    private boolean     gateIsOpen         = false;
    private boolean     headingToGate      = true;
    private int         transitTimer       = 0;
    private int         navTimer           = 0; // stuck-detection for NAVIGATING_SHEEP

    public ShepherdBrain(LogisticsWorkerEntity worker) { this.worker = worker; }

    // -----------------------------------------------------------------------

    public void tick(ServerWorld world) {
        BlockPos jobsite = worker.getJobsitePos();
        BlockPos deposit = worker.getDepositPos();
        if (jobsite == null || deposit == null) return;

        ItemStack roleTool = worker.getRoleTool();
        if (!roleTool.isEmpty()) {
            ItemStack current = worker.getMainHandStack();
            if (current.isEmpty() || current.getItem() != roleTool.getItem())
                worker.setStackInHand(Hand.MAIN_HAND, roleTool);
        }

        switch (phase) {
            case SCANNING         -> tickScanning(world, jobsite, deposit);
            case ENTERING         -> tickEntering(world, jobsite);
            case EXITING          -> tickExiting(world, jobsite, deposit);
            case NAVIGATING_SHEEP -> tickNavigatingSheep();
            case NAVIGATING_ITEM  -> tickNavigatingItem();
            case WORKING_SHEAR    -> tickWorkingShear(world);
            case WORKING_PICKUP   -> tickWorkingPickup(world);
            case DEPOSITING       -> tickDepositing(world, deposit);
            case WAITING          -> tickWaiting(world, jobsite, deposit);
        }
    }

    // ── SCANNING ─────────────────────────────────────────────────────────────

    private void tickScanning(ServerWorld world, BlockPos jobsite, BlockPos deposit) {
        if (--timer > 0) return;

        if (!isInsidePen) {
            managedGatePos = null;
            gateIsOpen     = false;
            headingToGate  = true;
            transitTimer   = 0;
            phase = Phase.ENTERING;
            return;
        }

        if (isInventoryFull()) { beginExit(); return; }

        Box scanBox = new Box(
                jobsite.getX() - SCAN_RADIUS, jobsite.getY() - 4, jobsite.getZ() - SCAN_RADIUS,
                jobsite.getX() + SCAN_RADIUS, jobsite.getY() + 4, jobsite.getZ() + SCAN_RADIUS);

        List<ItemEntity> droppedWool = world.getEntitiesByClass(ItemEntity.class, scanBox,
                e -> isWoolItem(e.getStack().getItem()));
        if (!droppedWool.isEmpty()) {
            droppedWool.stream()
                    .min(Comparator.comparingDouble(e -> worker.getPos().distanceTo(e.getPos())))
                    .ifPresent(ie -> { targetItemPos = ie.getBlockPos(); phase = Phase.NAVIGATING_ITEM; });
            if (phase == Phase.NAVIGATING_ITEM) { worker.getNavigation().stop(); return; }
        }

        List<SheepEntity> unsheared = world.getEntitiesByClass(SheepEntity.class, scanBox, SheepEntity::isShearable);
        if (!unsheared.isEmpty()) {
            unsheared.stream()
                    .min(Comparator.comparingDouble(s -> worker.getPos().distanceTo(s.getPos())))
                    .ifPresent(sheep -> {
                        targetSheep = sheep;
                        navTimer    = 0;
                        phase = Phase.NAVIGATING_SHEEP;
                        NPClogistics.LOGGER.info("{} shepherd targeting sheep at {}",
                                worker.getName().getString(), sheep.getBlockPos());
                    });
            if (phase == Phase.NAVIGATING_SHEEP) { worker.getNavigation().stop(); return; }
        }

        // All sheep shorn — exit pen whether or not we have wool (deposit or just wait)
        beginExit();
    }

    private void beginExit() {
        phase = Phase.EXITING;
        managedGatePos = null;
        gateIsOpen     = false;
        headingToGate  = true;
        transitTimer   = 0;
        timer          = 0;
        worker.getNavigation().stop();
    }

    // ── ENTERING ─────────────────────────────────────────────────────────────
    // Two sub-stages controlled by headingToGate:
    //   true  → navigate to gate from outside (gate CLOSED)
    //   false → gate is open; navigate to jobsite inside; close gate on arrival

    private void tickEntering(ServerWorld world, BlockPos jobsite) {
        // Already inside?
        if (worker.getPos().distanceTo(jobsite.toCenterPos()) <= ENTRY_DIST) {
            cleanupGate(world);
            isInsidePen  = true;
            transitTimer = 0;
            phase = Phase.SCANNING;
            timer = 0;
            return;
        }

        if (++transitTimer > TRANSIT_TIMEOUT) {
            cleanupGate(world);
            transitTimer = 0;
            phase = Phase.SCANNING;
            timer = SCAN_INTERVAL;
            NPClogistics.LOGGER.info("{} shepherd: entering pen timed out — rescanning",
                    worker.getName().getString());
            return;
        }

        if (managedGatePos == null) {
            managedGatePos = findNearestGate(world, jobsite);
            if (managedGatePos == null) {
                phase = Phase.SCANNING;
                timer = SCAN_INTERVAL;
                NPClogistics.LOGGER.warn("{} shepherd: no fence gate found near jobsite {}",
                        worker.getName().getString(), jobsite);
                return;
            }
            NPClogistics.LOGGER.info("{} shepherd entering pen (gate={})",
                    worker.getName().getString(), managedGatePos);
        }

        if (headingToGate) {
            // Navigate to gate (closed); open when close enough
            double distToGate = worker.getPos().distanceTo(managedGatePos.toCenterPos());
            if (distToGate <= GATE_OPEN_DIST) {
                openGate(world, managedGatePos);
                headingToGate = false;
                worker.getNavigation().stop(); // recalculate path through open gate next tick
                return;
            }
            if (worker.getNavigation().isIdle()) {
                worker.getNavigation().startMovingTo(
                        managedGatePos.getX() + 0.5, managedGatePos.getY(),
                        managedGatePos.getZ() + 0.5, NAV_SPEED);
            }
        } else {
            // Gate open — close it as soon as NPC has crossed through (moved >2 blocks from gate),
            // then continue to jobsite. Don't leave it open the whole walk.
            if (gateIsOpen && managedGatePos != null) {
                double distToGate = worker.getPos().distanceTo(managedGatePos.toCenterPos());
                if (distToGate > 2.0) {
                    closeGate(world, managedGatePos);
                    managedGatePos = null;
                }
            }

            double distToJobsite = worker.getPos().distanceTo(jobsite.toCenterPos());
            if (distToJobsite <= ENTRY_DIST) {
                cleanupGate(world);
                isInsidePen  = true;
                transitTimer = 0;
                phase = Phase.SCANNING;
                timer = 0;
                NPClogistics.LOGGER.info("{} shepherd entered pen", worker.getName().getString());
                return;
            }
            if (worker.getNavigation().isIdle()) {
                worker.getNavigation().startMovingTo(
                        jobsite.getX() + 0.5, jobsite.getY(), jobsite.getZ() + 0.5, NAV_SPEED);
            }
        }
    }

    // ── EXITING ───────────────────────────────────────────────────────────────
    // Two sub-stages driven by gateIsOpen:
    //   false → navigate to gate from inside (gate closed)
    //   true  → gate is open; walk into the gate block space, then step 1 block past it.
    // MC pathfinding can reach the open gate block from inside the pen but cannot route
    // further outward, so the crossing itself is a minimal 1-block setPos.

    private void tickExiting(ServerWorld world, BlockPos jobsite, BlockPos deposit) {
        if (++transitTimer > TRANSIT_TIMEOUT) {
            cleanupGate(world);
            isInsidePen  = false;
            transitTimer = 0;
            phase = Phase.DEPOSITING;
            timer = -1;
            NPClogistics.LOGGER.info("{} shepherd: exiting pen timed out — proceeding to deposit",
                    worker.getName().getString());
            return;
        }

        if (managedGatePos == null) {
            managedGatePos = findNearestGate(world, jobsite);
            if (managedGatePos == null) {
                isInsidePen = false;
                phase = Phase.DEPOSITING;
                timer = -1;
                return;
            }
        }

        double distToGate = worker.getPos().distanceTo(managedGatePos.toCenterPos());

        if (!gateIsOpen) {
            // Stage 1: walk up to the closed gate and open it.
            if (distToGate <= GATE_OPEN_DIST) {
                openGate(world, managedGatePos);
                worker.getNavigation().stop();
            } else if (worker.getNavigation().isIdle()) {
                worker.getNavigation().startMovingTo(
                        managedGatePos.getX() + 0.5, managedGatePos.getY(),
                        managedGatePos.getZ() + 0.5, NAV_SPEED);
            }
        } else {
            // Stage 2: gate open — walk into the gate block, then step 1 block through.
            // Arm a 3-second fallback on first tick (beginExit resets timer to 0).
            if (timer == 0) timer = 60;
            // Trigger when Dave walks close enough OR fallback timer expires.
            if (distToGate <= 0.6 || --timer <= 0) {
                BlockPos outside = gateExitPos(jobsite, managedGatePos);
                worker.setPos(outside.getX() + 0.5, worker.getY(), outside.getZ() + 0.5);
                worker.setVelocity(0, worker.getVelocity().y, 0);
                worker.velocityModified = true;
                closeGate(world, managedGatePos);
                managedGatePos = null;
                gateIsOpen     = false;
                isInsidePen    = false;
                transitTimer   = 0;
                worker.getNavigation().stop();
                phase = Phase.DEPOSITING;
                timer = -1;
                NPClogistics.LOGGER.info("{} shepherd exited pen → depositing",
                        worker.getName().getString());
                return;
            }
            if (worker.getNavigation().isIdle()) {
                // Navigate into the open gate block — passable when open, reachable from inside pen.
                worker.getNavigation().startMovingTo(
                        managedGatePos.getX() + 0.5, managedGatePos.getY(),
                        managedGatePos.getZ() + 0.5, NAV_SPEED);
            }
        }
    }

    /** 1 block past the gate in the jobsite→gate direction — teleport landing spot for exit. */
    private static BlockPos gateExitPos(BlockPos jobsite, BlockPos gate) {
        int dx = gate.getX() - jobsite.getX();
        int dz = gate.getZ() - jobsite.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) { dz = 0; dx = Integer.signum(dx); }
        else                              { dx = 0; dz = Integer.signum(dz); }
        return gate.add(dx, 0, dz);
    }

    // ── NAVIGATING ────────────────────────────────────────────────────────────

    private void tickNavigatingSheep() {
        if (targetSheep == null || !targetSheep.isAlive() || !targetSheep.isShearable()) {
            phase = Phase.SCANNING; timer = 0; navTimer = 0; return;
        }
        if (worker.distanceTo(targetSheep) <= ARRIVAL_DIST) {
            phase = Phase.WORKING_SHEAR;
            timer = WORK_TICKS;
            navTimer = 0;
            return;
        }
        if (++navTimer > NAV_TIMEOUT) {
            NPClogistics.LOGGER.info("{} shepherd: can't reach sheep — rescanning",
                    worker.getName().getString());
            targetSheep = null;
            phase = Phase.SCANNING;
            timer = 0;
            navTimer = 0;
            return;
        }
        if (worker.getNavigation().isIdle()) {
            // Use block coordinates rather than entity reference for more reliable pathing
            BlockPos sp = targetSheep.getBlockPos();
            worker.getNavigation().startMovingTo(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, NAV_SPEED);
        }
    }

    private void tickNavigatingItem() {
        if (targetItemPos == null) { phase = Phase.SCANNING; return; }
        double dist = worker.getPos().distanceTo(targetItemPos.toCenterPos());
        if (dist <= ARRIVAL_DIST) {
            phase = Phase.WORKING_PICKUP;
            timer = 5;
        } else if (worker.getNavigation().isIdle()) {
            worker.getNavigation().startMovingTo(
                    targetItemPos.getX() + 0.5, targetItemPos.getY() + 1.0,
                    targetItemPos.getZ() + 0.5, NAV_SPEED);
        }
    }

    // ── WORKING ───────────────────────────────────────────────────────────────

    private void tickWorkingShear(ServerWorld world) {
        if (--timer > 0) return;
        if (targetSheep == null || !targetSheep.isAlive() || !targetSheep.isShearable()) {
            phase = Phase.SCANNING; timer = 0; return;
        }
        shearSheep(world, targetSheep);
        targetSheep = null;
        phase = Phase.SCANNING;
        timer = 5;
    }

    private void tickWorkingPickup(ServerWorld world) {
        if (--timer > 0) return;
        pickupNearbyWool(world);
        targetItemPos = null;
        phase = Phase.SCANNING;
        timer = 5;
    }

    // ── DEPOSITING ────────────────────────────────────────────────────────────

    private void tickDepositing(ServerWorld world, BlockPos depositPos) {
        BlockPos approach = findApproachPos(world, depositPos, worker.getBlockPos());
        double distToApproach = worker.getPos().distanceTo(approach.toCenterPos());
        if (distToApproach > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle()) {
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            }
            return;
        }

        if (timer == -1) {
            if (isInventoryEmpty()) { isInsidePen = false; beginWaiting(); return; }
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
            isInsidePen = false;
            beginWaiting();
        }
    }

    // ── WAITING ───────────────────────────────────────────────────────────────

    private void beginWaiting() {
        phase = Phase.WAITING;
        timer = 0;
        worker.getNavigation().stop();
    }

    private void tickWaiting(ServerWorld world, BlockPos jobsite, BlockPos depositPos) {
        if (worker.getNavigation().isIdle()) {
            double dist = worker.getPos().distanceTo(depositPos.toCenterPos());
            if (dist > ARRIVAL_DIST) {
                BlockPos approach = findApproachPos(world, depositPos, worker.getBlockPos());
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            }
        }

        if (--timer > 0) return;
        timer = WAIT_INTERVAL;

        Box scanBox = new Box(
                jobsite.getX() - SCAN_RADIUS, jobsite.getY() - 4, jobsite.getZ() - SCAN_RADIUS,
                jobsite.getX() + SCAN_RADIUS, jobsite.getY() + 4, jobsite.getZ() + SCAN_RADIUS);

        List<SheepEntity> unsheared = world.getEntitiesByClass(SheepEntity.class, scanBox, SheepEntity::isShearable);
        if (!unsheared.isEmpty()) {
            isInsidePen   = false;
            managedGatePos = null;
            gateIsOpen    = false;
            headingToGate = true;
            transitTimer  = 0;
            phase = Phase.ENTERING;
            return;
        }

        List<SheepEntity> allSheep = world.getEntitiesByClass(SheepEntity.class, scanBox, e -> true);
        if (!allSheep.isEmpty()) {
            NPClogistics.LOGGER.info("{} waiting: wool to grow back ({} sheep)",
                    worker.getName().getString(), allSheep.size());
        } else {
            NPClogistics.LOGGER.info("{} waiting: no sheep found", worker.getName().getString());
        }
    }

    // ── Gate helpers ─────────────────────────────────────────────────────────

    private void openGate(ServerWorld world, BlockPos gatePos) {
        BlockState state = world.getBlockState(gatePos);
        if (state.getBlock() instanceof FenceGateBlock && !state.get(FenceGateBlock.OPEN)) {
            world.setBlockState(gatePos, state.with(FenceGateBlock.OPEN, true));
            gateIsOpen = true;
            NPClogistics.LOGGER.info("{} opened gate at {}", worker.getName().getString(), gatePos);
        }
    }

    private void closeGate(ServerWorld world, BlockPos gatePos) {
        if (gatePos == null) return;
        BlockState state = world.getBlockState(gatePos);
        if (state.getBlock() instanceof FenceGateBlock && state.get(FenceGateBlock.OPEN)) {
            world.setBlockState(gatePos, state.with(FenceGateBlock.OPEN, false));
            gateIsOpen = false;
            NPClogistics.LOGGER.info("{} closed gate at {}", worker.getName().getString(), gatePos);
        }
    }

    private void cleanupGate(ServerWorld world) {
        if (managedGatePos != null) closeGate(world, managedGatePos);
        managedGatePos = null;
        gateIsOpen     = false;
    }

    private static BlockPos findNearestGate(ServerWorld world, BlockPos center) {
        int r = GATE_SEARCH_RADIUS;
        BlockPos nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = center.add(dx, dy, dz);
                    if (world.getBlockState(p).getBlock() instanceof FenceGateBlock) {
                        double d = p.getSquaredDistance(center);
                        if (d < nearestDistSq) { nearestDistSq = d; nearest = p.toImmutable(); }
                    }
                }
            }
        }
        return nearest;
    }

    // ── Shear / pickup ────────────────────────────────────────────────────────

    private void shearSheep(ServerWorld world, SheepEntity sheep) {
        swingMainHand(world);
        sheep.setSheared(true);
        world.playSound(null, sheep.getX(), sheep.getY(), sheep.getZ(),
                SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.PLAYERS, 1.0f, 1.0f);
        int amount = 1 + worker.getRandom().nextInt(3);
        worker.addToWorkerInventory(new ItemStack(woolItemForColor(sheep.getColor()), amount));
        NPClogistics.LOGGER.info("{} sheared {} {} wool",
                worker.getName().getString(), amount, sheep.getColor().getName());
    }

    private void pickupNearbyWool(ServerWorld world) {
        net.minecraft.util.math.Vec3d pos = worker.getPos();
        Box reach = new Box(pos.x - 2, pos.y - 0.5, pos.z - 2, pos.x + 2, pos.y + 2, pos.z + 2);
        List<ItemEntity> nearby = world.getEntitiesByClass(ItemEntity.class, reach,
                e -> isWoolItem(e.getStack().getItem()));
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
            NPClogistics.LOGGER.info("{} picked up {} wool", worker.getName().getString(), picked);
    }

    // ── Deposit ───────────────────────────────────────────────────────────────

    private void doDeposit(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) {
            NPClogistics.LOGGER.warn("{} shepherd: no container at deposit pos {}",
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
        NPClogistics.LOGGER.info("{} deposited {} wool at {}",
                worker.getName().getString(), deposited, depositPos);
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private static BlockPos findApproachPos(ServerWorld world, BlockPos targetPos, BlockPos npcPos) {
        Direction[] hDirs = npcSidedDirections(targetPos, npcPos);
        for (Direction dir : hDirs) {
            BlockPos candidate = targetPos.offset(dir);
            if (isStandable(world, candidate)) return candidate;
        }
        for (Direction dir : hDirs) {
            BlockPos candidate = targetPos.offset(dir, 2);
            if (isStandable(world, candidate)) return candidate;
        }
        return targetPos;
    }

    private static Direction[] npcSidedDirections(BlockPos target, BlockPos npc) {
        int dx = npc.getX() - target.getX();
        int dz = npc.getZ() - target.getZ();
        Direction primary = Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Direction[] all = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Direction[] sorted = new Direction[4];
        sorted[0] = primary;
        int i = 1;
        for (Direction d : all) if (d != primary) sorted[i++] = d;
        return sorted;
    }

    private static boolean isStandable(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isAir()
                && world.getBlockState(pos.up()).isAir()
                && !world.getBlockState(pos.down()).isAir();
    }

    private void swingMainHand(ServerWorld world) {
        EntityAnimationS2CPacket packet = new EntityAnimationS2CPacket(worker,
                EntityAnimationS2CPacket.SWING_MAIN_HAND);
        for (net.minecraft.server.network.ServerPlayerEntity p :
                net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(worker)) {
            p.networkHandler.sendPacket(packet);
        }
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

    // ── Item helpers ──────────────────────────────────────────────────────────

    private static boolean isWoolItem(Item item) {
        return item == Items.WHITE_WOOL      || item == Items.ORANGE_WOOL   || item == Items.MAGENTA_WOOL
            || item == Items.LIGHT_BLUE_WOOL || item == Items.YELLOW_WOOL   || item == Items.LIME_WOOL
            || item == Items.PINK_WOOL       || item == Items.GRAY_WOOL     || item == Items.LIGHT_GRAY_WOOL
            || item == Items.CYAN_WOOL       || item == Items.PURPLE_WOOL   || item == Items.BLUE_WOOL
            || item == Items.BROWN_WOOL      || item == Items.GREEN_WOOL    || item == Items.RED_WOOL
            || item == Items.BLACK_WOOL;
    }

    private static Item woolItemForColor(DyeColor color) {
        return switch (color) {
            case WHITE      -> Items.WHITE_WOOL;
            case ORANGE     -> Items.ORANGE_WOOL;
            case MAGENTA    -> Items.MAGENTA_WOOL;
            case LIGHT_BLUE -> Items.LIGHT_BLUE_WOOL;
            case YELLOW     -> Items.YELLOW_WOOL;
            case LIME       -> Items.LIME_WOOL;
            case PINK       -> Items.PINK_WOOL;
            case GRAY       -> Items.GRAY_WOOL;
            case LIGHT_GRAY -> Items.LIGHT_GRAY_WOOL;
            case CYAN       -> Items.CYAN_WOOL;
            case PURPLE     -> Items.PURPLE_WOOL;
            case BLUE       -> Items.BLUE_WOOL;
            case BROWN      -> Items.BROWN_WOOL;
            case GREEN      -> Items.GREEN_WOOL;
            case RED        -> Items.RED_WOOL;
            case BLACK      -> Items.BLACK_WOOL;
        };
    }
}
