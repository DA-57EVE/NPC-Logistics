package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.LivestockTaggable;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
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

public class BreederBrain {

    private static final int    SCAN_RADIUS   = 24;
    private static final double ARRIVAL_DIST  = 2.0;
    private static final int    CHEST_TICKS   = 30;
    private static final int    FEED_TICKS    = 20;   // pause at animal before feeding
    private static final int    WAIT_INTERVAL = 2400; // 2 min — covers most of breeding cooldown
    private static final int    FOOD_TAKE_MAX = 16;   // max of each food type taken per trip
    private static final double NAV_SPEED     = 0.9;
    private static final int    NAV_TIMEOUT   = 100;

    private enum Phase { COLLECTING, SCANNING, NAVIGATING, FEEDING, WAITING }

    private final LogisticsWorkerEntity worker;

    private Phase        phase          = Phase.WAITING;
    private AnimalEntity targetAnimal   = null;
    private int          timer          = 0;
    private int          navTimer       = 0;
    private boolean      initialTagDone = false;

    public BreederBrain(LogisticsWorkerEntity worker) { this.worker = worker; }

    // ── Main tick ─────────────────────────────────────────────────────────────

    public void tick(ServerWorld world) {
        BlockPos jobsite = worker.getJobsitePos();
        BlockPos deposit = worker.getDepositPos();
        if (jobsite == null || deposit == null) return;

        if (!initialTagDone) {
            initialTagDone = true;
            tagNearbyAnimals(world, jobsite);
        }

        ItemStack roleTool = worker.getRoleTool();
        if (!roleTool.isEmpty()) {
            ItemStack cur = worker.getMainHandStack();
            if (cur.isEmpty() || cur.getItem() != roleTool.getItem())
                worker.setStackInHand(Hand.MAIN_HAND, roleTool.copy());
        }

        switch (phase) {
            case COLLECTING -> tickCollecting(world, deposit);
            case SCANNING   -> tickScanning(world, jobsite);
            case NAVIGATING -> tickNavigating(world, jobsite);
            case FEEDING    -> tickFeeding(world, jobsite);
            case WAITING    -> tickWaiting(world, jobsite);
        }
    }

    // ── COLLECTING ────────────────────────────────────────────────────────────

    private void tickCollecting(ServerWorld world, BlockPos deposit) {
        BlockPos approach = findApproachPos(world, deposit, worker.getBlockPos());
        if (worker.getPos().distanceTo(approach.toCenterPos()) > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle())
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            return;
        }

        if (timer == 0) { openChest(world, deposit); timer = CHEST_TICKS; return; }
        if (--timer > 0) return;

        int taken = takeFoodFromChest(world, deposit);
        closeChest(world, deposit);

        if (taken == 0 && !hasAnyFood()) {
            NPClogistics.LOGGER.info("{} breeder: no food in chest — waiting", worker.getName().getString());
            beginWaiting();
            return;
        }
        NPClogistics.LOGGER.info("{} breeder: loaded {} food item(s)", worker.getName().getString(), taken);
        phase = Phase.SCANNING;
        timer = 0;
    }

    // ── SCANNING ─────────────────────────────────────────────────────────────

    private void tickScanning(ServerWorld world, BlockPos jobsite) {
        List<AnimalEntity> breedable = getBreedableAnimals(world, jobsite);

        if (breedable.isEmpty()) {
            if (hasAnyFood()) {
                NPClogistics.LOGGER.info("{} breeder: no breedable animals — waiting",
                        worker.getName().getString());
            }
            beginWaiting();
            return;
        }

        // Pick nearest animal we actually have food for
        targetAnimal = breedable.stream()
                .filter(a -> countInInventory(feedFor(a)) > 0)
                .min(Comparator.comparingDouble(a -> worker.distanceTo(a)))
                .orElse(null);

        if (targetAnimal == null) {
            NPClogistics.LOGGER.info("{} breeder: no matching food for breedable animals — waiting",
                    worker.getName().getString());
            beginWaiting();
            return;
        }

        phase    = Phase.NAVIGATING;
        navTimer = 0;
    }

    // ── NAVIGATING ────────────────────────────────────────────────────────────

    private void tickNavigating(ServerWorld world, BlockPos jobsite) {
        if (targetAnimal == null || !targetAnimal.isAlive()) {
            phase = Phase.SCANNING;
            timer = 0;
            return;
        }

        if (worker.distanceTo(targetAnimal) <= ARRIVAL_DIST) {
            phase = Phase.FEEDING;
            timer = FEED_TICKS;
            return;
        }

        if (++navTimer > NAV_TIMEOUT) {
            NPClogistics.LOGGER.info("{} breeder: can't reach target — skipping",
                    worker.getName().getString());
            targetAnimal = null;
            phase    = Phase.SCANNING;
            navTimer = 0;
            return;
        }

        if (worker.getNavigation().isIdle())
            worker.getNavigation().startMovingTo(
                    targetAnimal.getX(), targetAnimal.getY(), targetAnimal.getZ(), NAV_SPEED);
    }

    // ── FEEDING ───────────────────────────────────────────────────────────────

    private void tickFeeding(ServerWorld world, BlockPos jobsite) {
        if (--timer > 0) return;

        if (targetAnimal == null || !targetAnimal.isAlive() || targetAnimal.isInLove() || targetAnimal.isBaby()) {
            targetAnimal = null;
            phase = Phase.SCANNING;
            timer = 0;
            return;
        }

        Item food = feedFor(targetAnimal);
        if (food == null || countInInventory(food) == 0) {
            targetAnimal = null;
            phase = Phase.SCANNING;
            timer = 0;
            return;
        }

        // Consume one food item and put the animal in love mode
        removeOneFromInventory(food);
        targetAnimal.lovePlayer(null);
        NPClogistics.LOGGER.info("{} breeder fed {} at {}",
                worker.getName().getString(),
                targetAnimal.getName().getString(),
                targetAnimal.getBlockPos());

        targetAnimal = null;
        phase = Phase.SCANNING;
        timer = 5;
    }

    // ── WAITING ───────────────────────────────────────────────────────────────

    private void beginWaiting() {
        phase = Phase.WAITING;
        timer = WAIT_INTERVAL;
        worker.getNavigation().stop();
    }

    private void tickWaiting(ServerWorld world, BlockPos jobsite) {
        if (--timer <= 0) {
            tagNearbyAnimals(world, jobsite);
            phase = Phase.COLLECTING;
            timer = 0;
        }
    }

    // ── Tagging ───────────────────────────────────────────────────────────────

    private void tagNearbyAnimals(ServerWorld world, BlockPos jobsite) {
        Box box = scanBox(jobsite);
        int count = 0;
        for (AnimalEntity animal : world.getEntitiesByClass(AnimalEntity.class, box,
                a -> a instanceof SheepEntity || a instanceof CowEntity
                        || a instanceof PigEntity || a instanceof ChickenEntity)) {
            if (!(animal instanceof LivestockTaggable t)) continue;
            if (!t.npclogistics_isTagged()) {
                t.npclogistics_setTagged(true, jobsite);
                t.npclogistics_setOwnerColor(LivestockTaggable.colorForOwner(worker.getUuid()));
                count++;
            }
        }
        if (count > 0)
            NPClogistics.LOGGER.info("{} breeder tagged {} animals", worker.getName().getString(), count);
    }

    // ── Animal helpers ────────────────────────────────────────────────────────

    private List<AnimalEntity> getBreedableAnimals(ServerWorld world, BlockPos jobsite) {
        return world.getEntitiesByClass(AnimalEntity.class, scanBox(jobsite),
                a -> !a.isBaby()
                        && !a.isInLove()
                        && a instanceof LivestockTaggable t
                        && t.npclogistics_isTagged()
                        && jobsite.equals(t.npclogistics_getJobsite())
                        && feedFor(a) != null);
    }

    private static Item feedFor(AnimalEntity animal) {
        if (animal instanceof SheepEntity || animal instanceof CowEntity) return Items.WHEAT;
        if (animal instanceof PigEntity)     return Items.CARROT;
        if (animal instanceof ChickenEntity) return Items.WHEAT_SEEDS;
        return null;
    }

    // ── Inventory helpers ─────────────────────────────────────────────────────

    private int takeFoodFromChest(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) return 0;
        Item[] foodTypes = { Items.WHEAT, Items.CARROT, Items.WHEAT_SEEDS };
        int total = 0;
        for (Item food : foodTypes) {
            int remaining = FOOD_TAKE_MAX - countInInventory(food); // top up, don't over-take
            if (remaining <= 0) continue;
            for (int j = 0; j < container.size() && remaining > 0; j++) {
                ItemStack slot = container.getStack(j);
                if (slot.isEmpty() || slot.getItem() != food) continue;
                int take = Math.min(slot.getCount(), remaining);
                ItemStack toAdd  = new ItemStack(food, take);
                ItemStack leftover = worker.addToWorkerInventory(toAdd);
                int actual = take - (leftover.isEmpty() ? 0 : leftover.getCount());
                slot.decrement(actual);
                if (slot.isEmpty()) container.setStack(j, ItemStack.EMPTY);
                remaining -= actual;
                total     += actual;
                if (!leftover.isEmpty()) break;
            }
        }
        container.markDirty();
        worker.getWorkerInventory().markDirty();
        return total;
    }

    private boolean hasAnyFood() {
        return countInInventory(Items.WHEAT) > 0
                || countInInventory(Items.CARROT) > 0
                || countInInventory(Items.WHEAT_SEEDS) > 0;
    }

    private int countInInventory(Item item) {
        if (item == null) return 0;
        SimpleInventory inv = worker.getWorkerInventory();
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() == item) count += s.getCount();
        }
        return count;
    }

    private void removeOneFromInventory(Item item) {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() != item) continue;
            s.decrement(1);
            if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
            inv.markDirty();
            return;
        }
    }

    // ── Chest helpers ─────────────────────────────────────────────────────────

    private void openChest(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_CHEST_OPEN,
                SoundCategory.BLOCKS, 0.4f, 1.0f);
        world.addSyncedBlockEvent(pos, block, 1, 1);
    }

    private void closeChest(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE,
                SoundCategory.BLOCKS, 0.4f, 1.0f);
        world.addSyncedBlockEvent(pos, block, 1, 0);
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
        Direction[] all    = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
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
