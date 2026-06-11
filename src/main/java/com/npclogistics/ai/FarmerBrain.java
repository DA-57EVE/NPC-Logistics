package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.minecraft.block.*;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class FarmerBrain {

    private static final int    SCAN_RADIUS   = 24;
    private static final int    HOE_RADIUS    = 10; // cap farm expansion to a sensible size
    private static final double ARRIVAL_DIST  = 2.5;
    private static final int    WORK_TICKS    = 20;
    private static final int    DEPOSIT_TICKS = 40;
    private static final int    SCAN_INTERVAL = 60;
    private static final double NAV_SPEED     = 0.6; // realistic walking pace

    private enum FarmerPhase { SCANNING, NAVIGATING, WORKING, DEPOSITING }
    private enum WorkType    { HARVEST, PLANT, PICKUP, HOE }

    private record WorkTarget(BlockPos pos, WorkType type, Block cropBlock) {}

    private static final int HARVESTS_PER_DEPOSIT = 6; // batch this many harvests before a deposit run

    private final LogisticsWorkerEntity worker;
    private FarmerPhase phase              = FarmerPhase.SCANNING;
    private WorkTarget  target             = null;
    private int         timer              = 0;
    private int         harvestsSinceDeposit = 0;

    public FarmerBrain(LogisticsWorkerEntity worker) { this.worker = worker; }

    // -----------------------------------------------------------------------

    public void tick(ServerWorld world) {
        BlockPos jobsite = worker.getJobsitePos();
        BlockPos deposit = worker.getDepositPos();
        if (jobsite == null || deposit == null) return;

        // Keep the role tool visible in the NPC's hand at all times while the role is active
        ItemStack roleTool = worker.getRoleTool();
        if (!roleTool.isEmpty()) {
            ItemStack current = worker.getMainHandStack();
            if (current.isEmpty() || current.getItem() != roleTool.getItem()) {
                worker.setStackInHand(Hand.MAIN_HAND, roleTool);
            }
        }

        switch (phase) {
            case SCANNING   -> tickScanning(world, jobsite);
            case NAVIGATING -> tickNavigating(world);
            case WORKING    -> tickWorking(world);
            case DEPOSITING -> tickDepositing(world, deposit);
        }
    }

    // ── SCANNING ──────────────────────────────────────────────────────────────

    private void tickScanning(ServerWorld world, BlockPos center) {
        if (--timer > 0) return;

        // Deposit after a batch of harvests, or when the farm is exhausted
        if (isInventoryFull() || harvestsSinceDeposit >= HARVESTS_PER_DEPOSIT) {
            harvestsSinceDeposit = 0;
            beginDeposit();
            return;
        }

        WorkTarget found = findNearestWork(world, center);
        if (found != null) {
            target = found;
            phase  = FarmerPhase.NAVIGATING;
            worker.getNavigation().stop();
            NPClogistics.LOGGER.info("{} farmer targeting {} at {}", worker.getName().getString(), found.type(), found.pos());
        } else {
            if (hasItemsToDeposit()) { harvestsSinceDeposit = 0; beginDeposit(); return; }
            timer = SCAN_INTERVAL;
        }
    }

    private void beginDeposit() {
        phase  = FarmerPhase.DEPOSITING;
        target = null;
        timer  = -1; // -1 = "haven't opened container yet"
        worker.getNavigation().stop();
    }

    // ── NAVIGATING ────────────────────────────────────────────────────────────

    private void tickNavigating(ServerWorld world) {
        if (target == null) { phase = FarmerPhase.SCANNING; return; }

        double dist = worker.getPos().distanceTo(target.pos().toCenterPos());
        if (dist <= ARRIVAL_DIST) {
            BlockState state = world.getBlockState(target.pos());
            boolean valid = (target.type() == WorkType.HARVEST && isMatureCrop(state))
                    || (target.type() == WorkType.PLANT && state.isAir()
                            && world.getBlockState(target.pos().down()).getBlock() == Blocks.FARMLAND)
                    || target.type() == WorkType.PICKUP
                    || (target.type() == WorkType.HOE
                            && (state.getBlock() == Blocks.DIRT || state.getBlock() == Blocks.GRASS_BLOCK));
            if (valid) {
                phase = FarmerPhase.WORKING;
                timer = WORK_TICKS;
            } else {
                phase = FarmerPhase.SCANNING;
                timer = 0;
            }
        } else if (worker.getNavigation().isIdle()) {
            if (target.type() == WorkType.PICKUP) {
                // Item entities on farmland report getBlockPos() == y=64 (inside solid block).
                // Navigate to y+1 so the pathfinder targets accessible air above the item.
                worker.getNavigation().startMovingTo(
                        target.pos().getX() + 0.5,
                        target.pos().getY() + 1.0,
                        target.pos().getZ() + 0.5,
                        NAV_SPEED);
            } else {
                BlockPos approach = findApproachPos(world, target.pos());
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            }
        }
    }

    // ── WORKING ───────────────────────────────────────────────────────────────

    private void tickWorking(ServerWorld world) {
        if (--timer > 0) return;
        if (target == null) { phase = FarmerPhase.SCANNING; return; }

        BlockState state = world.getBlockState(target.pos());
        if (target.type() == WorkType.HARVEST && isMatureCrop(state)) {
            harvestCrop(world, target.pos(), target.cropBlock());
        } else if (target.type() == WorkType.PLANT && state.isAir()
                && world.getBlockState(target.pos().down()).getBlock() == Blocks.FARMLAND) {
            plantCrop(world, target.pos(), target.cropBlock());
        } else if (target.type() == WorkType.PICKUP) {
            pickupNearbyItems(world);
        } else if (target.type() == WorkType.HOE) {
            hoeBlock(world, target.pos());
        }

        phase = FarmerPhase.SCANNING;
        timer = 5;
    }

    // ── DEPOSITING ────────────────────────────────────────────────────────────

    private void tickDepositing(ServerWorld world, BlockPos depositPos) {
        double dist = worker.getPos().distanceTo(depositPos.toCenterPos());
        if (dist > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle()) {
                BlockPos approach = findApproachPos(world, depositPos);
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            }
            return;
        }

        if (timer == -1) {
            // Open container
            BlockState state = world.getBlockState(depositPos);
            Block block = state.getBlock();
            world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                    block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_CHEST_OPEN,
                    SoundCategory.BLOCKS, 0.4f, 1.0f);
            world.addSyncedBlockEvent(depositPos, block, 1, 1);
            timer = DEPOSIT_TICKS;
        } else if (--timer <= 0) {
            // Deposit all items then close container
            doDeposit(world, depositPos);
            BlockState state = world.getBlockState(depositPos);
            Block block = state.getBlock();
            world.playSound(null, depositPos.getX() + 0.5, depositPos.getY() + 0.5, depositPos.getZ() + 0.5,
                    block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE,
                    SoundCategory.BLOCKS, 0.4f, 1.0f);
            world.addSyncedBlockEvent(depositPos, block, 1, 0);
            phase = FarmerPhase.SCANNING;
            timer = 10;
        }
    }

    // ── Scan ─────────────────────────────────────────────────────────────────

    private WorkTarget findNearestWork(ServerWorld world, BlockPos center) {
        WorkTarget best = null;
        double bestDist = Double.MAX_VALUE;

        // Pass 0: pick up dropped item entities in the jobsite area (highest priority — items despawn)
        Box scanBox = new Box(
                center.getX() - SCAN_RADIUS, center.getY() - 4, center.getZ() - SCAN_RADIUS,
                center.getX() + SCAN_RADIUS, center.getY() + 4, center.getZ() + SCAN_RADIUS
        );
        List<ItemEntity> dropped = world.getEntitiesByClass(ItemEntity.class, scanBox, e -> !e.getStack().isEmpty());
        for (ItemEntity ie : dropped) {
            double d = worker.getPos().distanceTo(ie.getPos());
            if (d < bestDist) {
                best = new WorkTarget(ie.getBlockPos(), WorkType.PICKUP, null);
                bestDist = d;
            }
        }
        if (best != null) return best; // item pickup before crop work

        // Pass 1: look for harvestable crops
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (isMatureCrop(state)) {
                        double d = worker.getPos().distanceTo(pos.toCenterPos());
                        if (d < bestDist) {
                            best = new WorkTarget(pos, WorkType.HARVEST, state.getBlock());
                            bestDist = d;
                        }
                    }
                }
            }
        }

        // Pass 2: look for empty farmland to plant on (only if no harvest found)
        if (best == null) {
            Block seedCrop = getCropBlockFromInventory();
            if (seedCrop != null) {
                for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                    for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                        for (int dy = -3; dy <= 3; dy++) {
                            BlockPos farmland = center.add(dx, dy, dz);
                            if (world.getBlockState(farmland).getBlock() != Blocks.FARMLAND) continue;
                            BlockPos cropPos = farmland.up();
                            if (!world.getBlockState(cropPos).isAir()) continue;
                            double d = worker.getPos().distanceTo(cropPos.toCenterPos());
                            if (d < bestDist) {
                                best = new WorkTarget(cropPos, WorkType.PLANT, seedCrop);
                                bestDist = d;
                            }
                        }
                    }
                }
            }
        }

        // Pass 3: hoe bare dirt/grass adjacent to existing farmland (idle activity — natural "pottering" feel)
        if (best == null) {
            List<BlockPos> hoeable = new ArrayList<>();
            for (int dx = -HOE_RADIUS; dx <= HOE_RADIUS; dx++) {
                for (int dz = -HOE_RADIUS; dz <= HOE_RADIUS; dz++) {
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos pos = center.add(dx, dy, dz);
                        Block b = world.getBlockState(pos).getBlock();
                        if (b != Blocks.DIRT && b != Blocks.GRASS_BLOCK) continue;
                        if (!world.getBlockState(pos.up()).isAir()) continue;
                        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                            if (world.getBlockState(pos.offset(dir)).getBlock() == Blocks.FARMLAND) {
                                hoeable.add(pos);
                                break;
                            }
                        }
                    }
                }
            }
            if (!hoeable.isEmpty()) {
                // Random pick gives natural clumping rather than always targeting the same block
                best = new WorkTarget(hoeable.get(worker.getRandom().nextInt(hoeable.size())), WorkType.HOE, null);
            }
        }

        return best;
    }

    // ── Crop actions ─────────────────────────────────────────────────────────

    private void swingMainHand(ServerWorld world) {
        EntityAnimationS2CPacket packet = new EntityAnimationS2CPacket(worker, EntityAnimationS2CPacket.SWING_MAIN_HAND);
        for (net.minecraft.server.network.ServerPlayerEntity p : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(worker)) {
            p.networkHandler.sendPacket(packet);
        }
    }

    private void harvestCrop(ServerWorld world, BlockPos pos, Block cropBlock) {
        swingMainHand(world);
        world.setBlockState(pos, Blocks.AIR.getDefaultState());
        world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.BLOCK_GRASS_BREAK, SoundCategory.BLOCKS, 1.0f, 1.0f);

        for (ItemStack drop : getCropDrops(cropBlock))
            worker.addToWorkerInventory(drop.copy());

        // Replant if we have seed in inventory
        Item seed = getSeedForCrop(cropBlock);
        if (seed != null && hasItem(seed)) {
            consumeItem(seed);
            world.setBlockState(pos, cropBlock.getDefaultState());
        }
        harvestsSinceDeposit++;
        NPClogistics.LOGGER.info("{} harvested {} at {}", worker.getName().getString(), cropBlock, pos);
    }

    private void plantCrop(ServerWorld world, BlockPos pos, Block cropBlock) {
        Item seed = getSeedForCrop(cropBlock);
        if (seed == null || !hasItem(seed)) return;
        swingMainHand(world);
        consumeItem(seed);
        world.setBlockState(pos, cropBlock.getDefaultState());
        world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.BLOCK_GRASS_PLACE, SoundCategory.BLOCKS, 1.0f, 1.0f);
        NPClogistics.LOGGER.info("{} planted {} at {}", worker.getName().getString(), cropBlock, pos);
    }

    private void hoeBlock(ServerWorld world, BlockPos pos) {
        Block current = world.getBlockState(pos).getBlock();
        if (current != Blocks.DIRT && current != Blocks.GRASS_BLOCK) return;
        swingMainHand(world);
        world.setBlockState(pos, Blocks.FARMLAND.getDefaultState());
        world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                SoundEvents.BLOCK_GRASS_BREAK, SoundCategory.BLOCKS, 1.0f, 1.0f);
        NPClogistics.LOGGER.info("{} tilled {} at {}", worker.getName().getString(), current, pos);
    }

    // ── Deposit ───────────────────────────────────────────────────────────────

    private void doDeposit(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) {
            NPClogistics.LOGGER.warn("{} farmer: no container at deposit pos {}",
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
        NPClogistics.LOGGER.info("{} deposited {} items at {}",
                worker.getName().getString(), deposited, depositPos);

        // Restock seeds from the chest so the NPC can replant on the next scan cycle
        restockSeeds(container);
    }

    /** Take up to 16 of each seed type back from the deposit chest into the NPC's inventory. */
    private void restockSeeds(Inventory container) {
        SimpleInventory npcInv = worker.getWorkerInventory();
        for (int j = 0; j < container.size(); j++) {
            ItemStack slot = container.getStack(j);
            if (slot.isEmpty()) continue;
            Item item = slot.getItem();
            if (item != Items.WHEAT_SEEDS && item != Items.CARROT
                    && item != Items.POTATO && item != Items.BEETROOT_SEEDS) continue;
            int take = Math.min(slot.getCount(), 16);
            ItemStack toTake = slot.copy();
            toTake.setCount(take);
            ItemStack remainder = worker.addToWorkerInventory(toTake);
            int taken = take - remainder.getCount();
            if (taken > 0) {
                slot.decrement(taken);
                if (slot.isEmpty()) container.setStack(j, ItemStack.EMPTY);
            }
        }
        container.markDirty();
        worker.getWorkerInventory().markDirty();
    }

    /** Pick up all item entities within 2 blocks of the NPC and add them to its inventory. */
    private void pickupNearbyItems(ServerWorld world) {
        Vec3d pos = worker.getPos();
        Box reach = new Box(pos.x - 2, pos.y - 0.5, pos.z - 2,
                            pos.x + 2, pos.y + 2,   pos.z + 2);
        List<ItemEntity> nearby = world.getEntitiesByClass(ItemEntity.class, reach,
                e -> !e.getStack().isEmpty());
        int picked = 0;
        for (ItemEntity ie : nearby) {
            ItemStack stack = ie.getStack().copy();
            ItemStack remainder = worker.addToWorkerInventory(stack);
            int added = stack.getCount() - remainder.getCount();
            if (added > 0) {
                picked += added;
                if (remainder.isEmpty()) {
                    ie.discard();
                } else {
                    ie.setStack(remainder);
                }
            }
        }
        if (picked > 0)
            NPClogistics.LOGGER.info("{} picked up {} dropped items",
                    worker.getName().getString(), picked);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static BlockPos findApproachPos(ServerWorld world, BlockPos targetPos) {
        // Pass 1: open air at the same level as the target (normal case)
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos candidate = targetPos.offset(dir);
            if (isStandable(world, candidate)) return candidate;
        }
        // Pass 2: standing on top of a solid block adjacent to the target
        // (handles chests surrounded by farmland — NPC stands on the farmland beside it)
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos candidate = targetPos.offset(dir).up();
            if (isStandable(world, candidate)) return candidate;
        }
        // Pass 3: two blocks out if tightly enclosed
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos candidate = targetPos.offset(dir, 2);
            if (isStandable(world, candidate)) return candidate;
        }
        return targetPos;
    }

    private static boolean isStandable(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isAir()
                && world.getBlockState(pos.up()).isAir()
                && !world.getBlockState(pos.down()).isAir();
    }

    private static boolean isMatureCrop(BlockState state) {
        return state.getBlock() instanceof CropBlock crops && crops.isMature(state);
    }

    /** Returns the crop Block matching a seed item the NPC currently has in inventory. */
    private Block getCropBlockFromInventory() {
        if (hasItem(Items.WHEAT_SEEDS))    return Blocks.WHEAT;
        if (hasItem(Items.CARROT))         return Blocks.CARROTS;
        if (hasItem(Items.POTATO))         return Blocks.POTATOES;
        if (hasItem(Items.BEETROOT_SEEDS)) return Blocks.BEETROOTS;
        return null;
    }

    private boolean hasItem(Item item) {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++)
            if (inv.getStack(i).getItem() == item) return true;
        return false;
    }

    private void consumeItem(Item item) {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() == item) {
                s.decrement(1);
                if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
                return;
            }
        }
    }

    private boolean isInventoryFull() {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++)
            if (inv.getStack(i).isEmpty()) return false;
        return true;
    }

    private boolean isInventoryEmpty() {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++)
            if (!inv.getStack(i).isEmpty()) return false;
        return true;
    }

    /** True when the inventory holds produce worth depositing (ignores seeds and small carrot/potato replant stock). */
    private boolean hasItemsToDeposit() {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Item item = s.getItem();
            if (item == Items.WHEAT_SEEDS || item == Items.BEETROOT_SEEDS) continue;
            if ((item == Items.CARROT || item == Items.POTATO) && s.getCount() <= 16) continue;
            return true;
        }
        return false;
    }

    private static List<ItemStack> getCropDrops(Block cropBlock) {
        if (cropBlock == Blocks.WHEAT)     return List.of(new ItemStack(Items.WHEAT),    new ItemStack(Items.WHEAT_SEEDS));
        if (cropBlock == Blocks.CARROTS)   return List.of(new ItemStack(Items.CARROT,  2));
        if (cropBlock == Blocks.POTATOES)  return List.of(new ItemStack(Items.POTATO,  2));
        if (cropBlock == Blocks.BEETROOTS) return List.of(new ItemStack(Items.BEETROOT), new ItemStack(Items.BEETROOT_SEEDS));
        return List.of();
    }

    private static Item getSeedForCrop(Block cropBlock) {
        if (cropBlock == Blocks.WHEAT)     return Items.WHEAT_SEEDS;
        if (cropBlock == Blocks.CARROTS)   return Items.CARROT;
        if (cropBlock == Blocks.POTATOES)  return Items.POTATO;
        if (cropBlock == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        return null;
    }
}
