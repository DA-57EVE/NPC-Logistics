package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.entity.LivestockTaggable;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
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
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ButcherBrain {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int    DISCOVERY_RADIUS = 64;   // blocks from jobsite to scan for herds
    private static final int    HERD_RADIUS      = 24;   // box around each herd jobsite
    // Minimum alive per herd by species — higher for wool/egg producers, lower for pure-meat animals
    private static final int    MIN_SHEEP        = 16;
    private static final int    MIN_COW          = 8;
    private static final int    MIN_PIG          = 6;
    private static final int    MIN_CHICKEN      = 10;
    // Feed deposit = 1 item per animal in the herd (= 2 per breeding pair, exactly one round of breeding)
    private static final int    FEED_TAKE_EACH   = 32;   // max wheat/carrot/seeds taken from home chest
    private static final double ARRIVAL_DIST     = 2.0;
    private static final double NAV_SPEED        = 0.9;
    private static final int    NAV_TIMEOUT      = 120;
    private static final int    CHEST_TICKS      = 30;
    private static final int    DROP_TICKS       = 15;   // ticks to wait after kill for drops to spawn
    private static final int    WAIT_INTERVAL    = 600;  // 30 s between rounds

    private enum Phase {
        LOADING,        // nav to home chest, take feed, discover herds
        VISITING_HERD,  // at current herd: kill excess animals and collect drops
        FEEDING_HERD,   // nav to herd's chest and deposit feed
        RETURNING,      // nav back to home chest
        UNLOADING,      // deposit everything at home chest
        WAITING
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final LogisticsWorkerEntity worker;

    private Phase            phase            = Phase.WAITING;
    private List<BlockPos>   herdJobsites     = new ArrayList<>();
    private int              herdIndex        = 0;
    private AnimalEntity     targetAnimal     = null;
    private BlockPos         dropWaitPos      = null;
    private BlockPos         currentHerdChest = null;
    private int              killTimer        = 0;
    private int              timer            = 0;
    private int              navTimer         = 0;

    public ButcherBrain(LogisticsWorkerEntity worker) { this.worker = worker; }

    // ── Main tick ─────────────────────────────────────────────────────────────

    public void tick(ServerWorld world) {
        BlockPos jobsite = worker.getJobsitePos();
        BlockPos deposit = worker.getDepositPos();
        if (jobsite == null || deposit == null) return;

        ItemStack roleTool = worker.getRoleTool();
        if (!roleTool.isEmpty()) {
            ItemStack cur = worker.getMainHandStack();
            if (cur.isEmpty() || cur.getItem() != roleTool.getItem())
                worker.setStackInHand(Hand.MAIN_HAND, roleTool.copy());
        }

        switch (phase) {
            case LOADING       -> tickLoading(world, jobsite, deposit);
            case VISITING_HERD -> tickVisitingHerd(world);
            case FEEDING_HERD  -> tickFeedingHerd(world);
            case RETURNING     -> tickReturning(world, deposit);
            case UNLOADING     -> tickUnloading(world, deposit);
            case WAITING       -> tickWaiting();
        }
    }

    // ── LOADING ───────────────────────────────────────────────────────────────

    private void tickLoading(ServerWorld world, BlockPos jobsite, BlockPos deposit) {
        BlockPos approach = findApproachPos(world, deposit, worker.getBlockPos());
        if (worker.getPos().distanceTo(approach.toCenterPos()) > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle())
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            return;
        }

        if (timer == 0) { openChest(world, deposit); timer = CHEST_TICKS; return; }
        if (--timer > 0) return;

        takeFeedFromChest(world, deposit);
        closeChest(world, deposit);

        herdJobsites     = discoverHerds(world, jobsite);
        herdIndex        = 0;
        currentHerdChest = null;
        targetAnimal     = null;

        if (herdJobsites.isEmpty()) {
            NPClogistics.LOGGER.info("{} butcher: no tagged herds in range — waiting",
                    worker.getName().getString());
            beginWaiting();
            return;
        }
        NPClogistics.LOGGER.info("{} butcher: found {} herd(s), starting circuit",
                worker.getName().getString(), herdJobsites.size());
        phase = Phase.VISITING_HERD;
        navTimer = 0;
    }

    // ── VISITING_HERD ─────────────────────────────────────────────────────────

    private void tickVisitingHerd(ServerWorld world) {
        // Wait for drops spawned by the last kill
        if (killTimer > 0) {
            if (--killTimer == 0) { collectDropsNear(world, dropWaitPos); dropWaitPos = null; }
            return;
        }

        BlockPos herdPos = herdJobsites.get(herdIndex);

        // Navigate to herd centre if not close enough to scan
        if (worker.getPos().distanceTo(herdPos.toCenterPos()) > HERD_RADIUS) {
            if (worker.getNavigation().isIdle())
                worker.getNavigation().startMovingTo(
                        herdPos.getX() + 0.5, herdPos.getY(), herdPos.getZ() + 0.5, NAV_SPEED);
            return;
        }

        List<AnimalEntity> herd = getHerdAnimals(world, herdPos);
        int minPop = minPopulationFor(herd);
        if (herd.size() <= minPop) {
            NPClogistics.LOGGER.info("{} butcher: herd at {} has {} animals (min {}), moving to feed",
                    worker.getName().getString(), herdPos, herd.size(), minPop);
            phase = Phase.FEEDING_HERD;
            timer = 0;
            targetAnimal = null;
            return;
        }

        // Pick nearest excess animal
        if (targetAnimal == null || !targetAnimal.isAlive()) {
            targetAnimal = herd.stream()
                    .min(Comparator.comparingDouble(a -> worker.distanceTo(a)))
                    .orElse(null);
            if (targetAnimal == null) { phase = Phase.FEEDING_HERD; return; }
            navTimer = 0;
        }

        if (!targetAnimal.isAlive()) { targetAnimal = null; return; }

        if (worker.distanceTo(targetAnimal) > 2.0) {
            if (++navTimer > NAV_TIMEOUT) {
                NPClogistics.LOGGER.info("{} butcher: can't reach target — skipping",
                        worker.getName().getString());
                targetAnimal = null;
                navTimer = 0;
                return;
            }
            if (worker.getNavigation().isIdle())
                worker.getNavigation().startMovingTo(
                        targetAnimal.getX(), targetAnimal.getY(), targetAnimal.getZ(), NAV_SPEED);
            return;
        }

        // Kill
        swingMainHand(world);
        dropWaitPos  = targetAnimal.getBlockPos();
        targetAnimal.damage(world.getDamageSources().mobAttack(worker), 20.0f);
        NPClogistics.LOGGER.info("{} butcher killed animal at {}",
                worker.getName().getString(), dropWaitPos);
        targetAnimal = null;
        killTimer    = DROP_TICKS;
        navTimer     = 0;
    }

    // ── FEEDING_HERD ──────────────────────────────────────────────────────────

    private void tickFeedingHerd(ServerWorld world) {
        BlockPos herdPos = herdJobsites.get(herdIndex);

        if (currentHerdChest == null) {
            currentHerdChest = findChestNear(world, herdPos);
            if (currentHerdChest == null) {
                NPClogistics.LOGGER.info("{} butcher: no chest near herd at {} — skipping feed",
                        worker.getName().getString(), herdPos);
                advanceHerd();
                return;
            }
        }

        BlockPos approach = findApproachPos(world, currentHerdChest, worker.getBlockPos());
        if (worker.getPos().distanceTo(approach.toCenterPos()) > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle())
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            return;
        }

        if (timer == 0) { openChest(world, currentHerdChest); timer = CHEST_TICKS; return; }
        if (--timer > 0) return;

        depositFeedForHerd(world, herdPos, currentHerdChest);
        closeChest(world, currentHerdChest);
        advanceHerd();
    }

    private void advanceHerd() {
        herdIndex++;
        currentHerdChest = null;
        targetAnimal     = null;
        if (herdIndex >= herdJobsites.size()) {
            phase = Phase.RETURNING;
            timer = 0;
        } else {
            phase    = Phase.VISITING_HERD;
            navTimer = 0;
        }
    }

    // ── RETURNING ─────────────────────────────────────────────────────────────

    private void tickReturning(ServerWorld world, BlockPos deposit) {
        BlockPos approach = findApproachPos(world, deposit, worker.getBlockPos());
        if (worker.getPos().distanceTo(approach.toCenterPos()) > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle())
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            return;
        }
        phase = Phase.UNLOADING;
        timer = 0;
    }

    // ── UNLOADING ─────────────────────────────────────────────────────────────

    private void tickUnloading(ServerWorld world, BlockPos deposit) {
        if (timer == 0) { openChest(world, deposit); timer = CHEST_TICKS; return; }
        if (--timer > 0) return;

        int deposited = depositAllToChest(world, deposit);
        closeChest(world, deposit);
        NPClogistics.LOGGER.info("{} butcher deposited {} item(s) to home chest",
                worker.getName().getString(), deposited);
        beginWaiting();
    }

    // ── WAITING ───────────────────────────────────────────────────────────────

    private void beginWaiting() {
        phase = Phase.WAITING;
        timer = WAIT_INTERVAL;
        worker.getNavigation().stop();
    }

    private void tickWaiting() {
        if (--timer <= 0) { phase = Phase.LOADING; timer = 0; }
    }

    // ── Herd discovery ────────────────────────────────────────────────────────

    private List<BlockPos> discoverHerds(ServerWorld world, BlockPos butcherJobsite) {
        Box box = new Box(
                butcherJobsite.getX() - DISCOVERY_RADIUS, butcherJobsite.getY() - 16, butcherJobsite.getZ() - DISCOVERY_RADIUS,
                butcherJobsite.getX() + DISCOVERY_RADIUS, butcherJobsite.getY() + 16, butcherJobsite.getZ() + DISCOVERY_RADIUS);
        Set<BlockPos> sites = new LinkedHashSet<>();
        world.getEntitiesByClass(AnimalEntity.class, box,
                a -> a instanceof LivestockTaggable t && t.npclogistics_isTagged())
                .forEach(a -> {
                    BlockPos pos = ((LivestockTaggable) a).npclogistics_getJobsite();
                    if (pos != null) sites.add(pos);
                });
        return new ArrayList<>(sites);
    }

    private List<AnimalEntity> getHerdAnimals(ServerWorld world, BlockPos herdPos) {
        Box box = new Box(
                herdPos.getX() - HERD_RADIUS, herdPos.getY() - 4, herdPos.getZ() - HERD_RADIUS,
                herdPos.getX() + HERD_RADIUS, herdPos.getY() + 4, herdPos.getZ() + HERD_RADIUS);
        return world.getEntitiesByClass(AnimalEntity.class, box,
                a -> a instanceof LivestockTaggable t
                        && t.npclogistics_isTagged()
                        && herdPos.equals(t.npclogistics_getJobsite()));
    }

    // ── Chest helpers ─────────────────────────────────────────────────────────

    private BlockPos findChestNear(ServerWorld world, BlockPos center) {
        for (BlockPos p : BlockPos.iterate(center.add(-5, -2, -5), center.add(5, 2, 5))) {
            Block b = world.getBlockState(p).getBlock();
            if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL)
                return p.toImmutable();
        }
        return null;
    }

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

    // ── Inventory operations ──────────────────────────────────────────────────

    private void takeFeedFromChest(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) return;
        Item[] feedTypes = { Items.WHEAT, Items.CARROT, Items.WHEAT_SEEDS };
        int[] taken = new int[feedTypes.length];
        for (int fi = 0; fi < feedTypes.length; fi++) {
            Item feedItem = feedTypes[fi];
            int remaining = FEED_TAKE_EACH;
            for (int j = 0; j < container.size() && remaining > 0; j++) {
                ItemStack slot = container.getStack(j);
                if (slot.isEmpty() || slot.getItem() != feedItem) continue;
                int take = Math.min(slot.getCount(), remaining);
                ItemStack toAdd = new ItemStack(feedItem, take);
                ItemStack leftover = worker.addToWorkerInventory(toAdd);
                int actual = take - (leftover.isEmpty() ? 0 : leftover.getCount());
                slot.decrement(actual);
                if (slot.isEmpty()) container.setStack(j, ItemStack.EMPTY);
                remaining -= actual;
                taken[fi] += actual;
                if (!leftover.isEmpty()) break;
            }
        }
        container.markDirty();
        worker.getWorkerInventory().markDirty();
        NPClogistics.LOGGER.info("{} butcher took feed: {}× wheat, {}× carrot, {}× seeds",
                worker.getName().getString(), taken[0], taken[1], taken[2]);
    }

    private void depositFeedForHerd(ServerWorld world, BlockPos herdPos, BlockPos chestPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, chestPos);
        if (container == null) return;

        // Determine which unique feed types this herd needs and how many animals need feeding
        List<AnimalEntity> herd = getHerdAnimals(world, herdPos);
        Set<Item> feedTypes = new LinkedHashSet<>();
        for (AnimalEntity a : herd) {
            Item fi = feedFor(a);
            if (fi != null) feedTypes.add(fi);
        }
        // 1 item per animal = 2 per pair = exactly one full round of breeding
        int feedAmount = herd.size();

        for (Item feedItem : feedTypes) {
            int toDeposit = Math.min(feedAmount, countInInventory(feedItem));
            if (toDeposit == 0) continue;
            for (int j = 0; j < container.size() && toDeposit > 0; j++) {
                ItemStack slot = container.getStack(j);
                if (slot.isEmpty()) {
                    container.setStack(j, new ItemStack(feedItem, toDeposit));
                    removeFromInventory(feedItem, toDeposit);
                    toDeposit = 0;
                } else if (slot.getItem() == feedItem) {
                    int space = slot.getMaxCount() - slot.getCount();
                    int move  = Math.min(space, toDeposit);
                    slot.increment(move);
                    removeFromInventory(feedItem, move);
                    toDeposit -= move;
                }
            }
            NPClogistics.LOGGER.info("{} butcher deposited feed ({}) at herd {}",
                    worker.getName().getString(), feedItem, herdPos);
        }
        container.markDirty();
        worker.getWorkerInventory().markDirty();
    }

    private int depositAllToChest(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) return 0;
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
        return deposited;
    }

    private void collectDropsNear(ServerWorld world, BlockPos pos) {
        if (pos == null) return;
        Box box = new Box(pos).expand(3);
        List<ItemEntity> drops = world.getEntitiesByClass(ItemEntity.class, box, e -> true);
        int collected = 0;
        for (ItemEntity drop : drops) {
            ItemStack stack = drop.getStack();
            if (stack.isEmpty()) continue;
            ItemStack leftover = worker.addToWorkerInventory(stack.copy());
            if (leftover.isEmpty()) {
                drop.discard();
                collected++;
            } else {
                stack.setCount(leftover.getCount());
            }
        }
        if (collected > 0)
            NPClogistics.LOGGER.info("{} butcher collected {} drop(s)", worker.getName().getString(), collected);
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

    private void removeFromInventory(Item item, int amount) {
        SimpleInventory inv = worker.getWorkerInventory();
        for (int i = 0; i < inv.size() && amount > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() != item) continue;
            int remove = Math.min(s.getCount(), amount);
            s.decrement(remove);
            amount -= remove;
            if (s.isEmpty()) inv.setStack(i, ItemStack.EMPTY);
        }
        inv.markDirty();
    }

    // ── Species helpers ───────────────────────────────────────────────────────

    private static Item feedFor(AnimalEntity animal) {
        if (animal instanceof SheepEntity || animal instanceof CowEntity) return Items.WHEAT;
        if (animal instanceof PigEntity)     return Items.CARROT;
        if (animal instanceof ChickenEntity) return Items.WHEAT_SEEDS;
        return null;
    }

    /** Returns the minimum live count for a herd, based on the dominant species. */
    private static int minPopulationFor(List<AnimalEntity> herd) {
        if (herd.isEmpty()) return MIN_PIG; // fallback
        AnimalEntity sample = herd.get(0);
        if (sample instanceof SheepEntity)   return MIN_SHEEP;
        if (sample instanceof CowEntity)     return MIN_COW;
        if (sample instanceof ChickenEntity) return MIN_CHICKEN;
        return MIN_PIG;
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private void swingMainHand(ServerWorld world) {
        worker.swingHand(Hand.MAIN_HAND);
        EntityAnimationS2CPacket pkt = new EntityAnimationS2CPacket(worker, 0);
        PlayerLookup.tracking(worker).forEach(p -> p.networkHandler.sendPacket(pkt));
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

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
