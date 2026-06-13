package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.data.CraftingTask;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.block.*;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.recipe.*;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * Executes a single {@link CraftingTask}: collect ingredients → craft → deposit output.
 *
 * Ingredient collection is batch-sized: the brain calculates how many full recipe cycles
 * the source chest can supply and collects all of them in one trip.  The craft step loops
 * until no complete ingredient set remains, so a partial collection still produces as many
 * items as possible.
 *
 * Crafting is simulated server-side via {@link RecipeManager} — the NPC navigates to the
 * craft block, plays an arm-swing animation and pauses, then consumes ingredients and
 * produces output directly.  Supported blocks: crafting table (shaped/shapeless),
 * furnace, blast furnace, smoker, stonecutter.
 */
public class CraftingTaskBrain {

    private static final double ARRIVAL_DIST = 2.5;
    private static final double NAV_SPEED    = 0.9;
    private static final int    OPEN_HOLD    = 20;   // ticks before items move (lid open)
    private static final int    CRAFT_TICKS  = 40;   // work pause at a crafting table
    private static final int    SMELT_TICKS  = 200;  // simulated smelting time (10 s)
    private static final int    CUT_TICKS    = 20;   // stonecutter work pause
    private static final int    MAX_CRAFTS   = 64;   // upper bound per trip

    private enum Phase { IDLE, COLLECTING, CRAFTING_NAV, CRAFTING_WORK, DEPOSITING }

    private final LogisticsWorkerEntity worker;
    private Phase    phase          = Phase.IDLE;
    private int      timer          = 0;
    private int      workDuration   = 0;
    private boolean  hasIngredients = false;

    public CraftingTaskBrain(LogisticsWorkerEntity worker) { this.worker = worker; }

    /** Maps the current phase to a stop index for the goggle overlay (0=source, 1=craft, 2=deposit, -1=idle). */
    public int getCurrentPhaseIndex() {
        return switch (phase) {
            case COLLECTING                    -> 0;
            case CRAFTING_NAV, CRAFTING_WORK   -> 1;
            case DEPOSITING                    -> 2;
            default                            -> -1;
        };
    }

    /** Reset all state when a new task is assigned. */
    public void reset() {
        phase          = Phase.IDLE;
        timer          = 0;
        workDuration   = 0;
        hasIngredients = false;
    }

    /** Called every server tick while the worker is in EXECUTING_TASK state. */
    public void tick(ServerWorld world, CraftingTask task) {
        switch (phase) {
            case IDLE          -> { phase = Phase.COLLECTING; timer = -1; }
            case COLLECTING    -> tickCollecting(world, task);
            case CRAFTING_NAV  -> tickCraftingNav(world, task);
            case CRAFTING_WORK -> tickCraftingWork(world, task);
            case DEPOSITING    -> tickDepositing(world, task);
        }
    }

    // ── COLLECTING ────────────────────────────────────────────────────────────

    private void tickCollecting(ServerWorld world, CraftingTask task) {
        double dist = worker.getPos().distanceTo(task.sourcePos.toCenterPos());
        if (dist > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle()) {
                BlockPos approach = findApproachPos(world, task.sourcePos, worker.getBlockPos());
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            }
            return;
        }

        if (timer == -1) {
            openContainer(world, task.sourcePos);
            timer = OPEN_HOLD;
            return;
        }
        if (--timer > 0) return;

        hasIngredients = collectIngredients(world, task);
        closeContainer(world, task.sourcePos);
        if (!hasIngredients) {
            worker.advanceTask();
            return;
        }
        phase = Phase.CRAFTING_NAV;
        timer = 0;
    }

    // ── CRAFTING_NAV ──────────────────────────────────────────────────────────

    private void tickCraftingNav(ServerWorld world, CraftingTask task) {
        double dist = worker.getPos().distanceTo(task.craftBlockPos.toCenterPos());
        if (dist <= ARRIVAL_DIST) {
            Block b = world.getBlockState(task.craftBlockPos).getBlock();
            workDuration = craftWorkTicks(b);
            timer        = workDuration;
            phase        = Phase.CRAFTING_WORK;
            return;
        }
        if (worker.getNavigation().isIdle()) {
            BlockPos approach = findApproachPos(world, task.craftBlockPos, worker.getBlockPos());
            worker.getNavigation().startMovingTo(
                    approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
        }
    }

    // ── CRAFTING_WORK ─────────────────────────────────────────────────────────

    private void tickCraftingWork(ServerWorld world, CraftingTask task) {
        // Swing arm when arriving at the block (placing ingredients) and at the midpoint.
        if (timer == workDuration)          swingMainHand(world);
        if (timer == workDuration / 2 + 1)  swingMainHand(world);

        if (--timer > 0) return;

        // Item-pickup sound signals the craft completing.
        world.playSound(null, worker.getX(), worker.getY(), worker.getZ(),
                SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS,
                0.6f, 0.9f + worker.getRandom().nextFloat() * 0.4f);

        if (hasIngredients) executeCraft(world, task);
        phase = Phase.DEPOSITING;
        timer = -1;
    }

    // ── DEPOSITING ────────────────────────────────────────────────────────────

    private void tickDepositing(ServerWorld world, CraftingTask task) {
        double dist = worker.getPos().distanceTo(task.depositPos.toCenterPos());
        if (dist > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle()) {
                BlockPos approach = findApproachPos(world, task.depositPos, worker.getBlockPos());
                worker.getNavigation().startMovingTo(
                        approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
            }
            return;
        }

        if (timer == -1) {
            openContainer(world, task.depositPos);
            timer = OPEN_HOLD;
            return;
        }
        if (--timer > 0) return;

        depositOutput(world, task.depositPos);
        closeContainer(world, task.depositPos);
        worker.onCraftingTaskComplete();
    }

    // ── Ingredient collection ─────────────────────────────────────────────────

    /**
     * Takes one full batch of ingredients from {@code task.sourcePos} — enough for as many
     * recipe cycles as the source chest can supply (up to {@link #MAX_CRAFTS}).  Returns
     * {@code true} if at least some ingredients were collected; {@code false} only when the
     * source is empty or the recipe is unknown (so the craft step can be skipped entirely).
     */
    private boolean collectIngredients(ServerWorld world, CraftingTask task) {
        Inventory sourceInv = WorkOrderBrain.resolveInventory(world, task.sourcePos);
        if (sourceInv == null) {
            NPClogistics.LOGGER.warn("{} crafting task: no container at source {}",
                    worker.getName().getString(), task.sourcePos);
            return false;
        }

        List<Ingredient> needed = findRequiredIngredients(world, task);
        if (needed == null) {
            NPClogistics.LOGGER.warn("{} crafting task: no recipe found for {}",
                    worker.getName().getString(), task.recipeItem.getItem());
            return false;
        }

        int maxCrafts = Math.min(calculateMaxCraftsFromSource(sourceInv, needed), MAX_CRAFTS);
        if (maxCrafts == 0) {
            NPClogistics.LOGGER.info("{} source chest has no ingredients for {} — returning home",
                    worker.getName().getString(), task.recipeItem.getItem());
            return false;
        }

        // Collect maxCrafts × each ingredient slot from the source chest.
        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            int remaining = maxCrafts;
            for (int si = 0; si < sourceInv.size() && remaining > 0; si++) {
                ItemStack slot = sourceInv.getStack(si);
                if (!slot.isEmpty() && ing.test(slot)) {
                    int take = Math.min(slot.getCount(), remaining);
                    ItemStack rem = worker.addToWorkerInventory(slot.copyWithCount(take));
                    int added = take - rem.getCount();
                    slot.decrement(added);
                    if (slot.isEmpty()) sourceInv.setStack(si, ItemStack.EMPTY);
                    remaining -= added;
                }
            }
        }

        sourceInv.markDirty();
        NPClogistics.LOGGER.info("{} collected ingredients for up to {} craft(s) of {} from {}",
                worker.getName().getString(), maxCrafts, task.recipeItem.getItem(), task.sourcePos);
        return true;
    }

    /**
     * Simulates how many complete recipe cycles can be filled from {@code sourceInv}.
     * Handles recipes where multiple ingredient slots require the same item (e.g. bread = 3 wheat).
     */
    private int calculateMaxCraftsFromSource(Inventory sourceInv, List<Ingredient> ingredients) {
        Map<Item, Integer> available = new HashMap<>();
        for (int i = 0; i < sourceInv.size(); i++) {
            ItemStack s = sourceInv.getStack(i);
            if (!s.isEmpty()) available.merge(s.getItem(), s.getCount(), Integer::sum);
        }

        int maxCrafts = 0;
        Map<Item, Integer> reserved = new HashMap<>();

        outer:
        for (int craft = 0; craft < MAX_CRAFTS; craft++) {
            Map<Item, Integer> attempt = new HashMap<>(reserved);
            for (Ingredient ing : ingredients) {
                if (ing.isEmpty()) continue;
                boolean found = false;
                for (Map.Entry<Item, Integer> entry : available.entrySet()) {
                    Item item = entry.getKey();
                    int avail = entry.getValue() - attempt.getOrDefault(item, 0);
                    if (avail > 0 && ing.test(new ItemStack(item))) {
                        attempt.merge(item, 1, Integer::sum);
                        found = true;
                        break;
                    }
                }
                if (!found) break outer;
            }
            reserved = attempt;
            maxCrafts++;
        }
        return maxCrafts;
    }

    // ── Recipe execution ──────────────────────────────────────────────────────

    private void executeCraft(ServerWorld world, CraftingTask task) {
        Block b = world.getBlockState(task.craftBlockPos).getBlock();
        if (b == Blocks.FURNACE)       { executeCookingRecipe(world, task, RecipeType.SMELTING);  return; }
        if (b == Blocks.BLAST_FURNACE) { executeCookingRecipe(world, task, RecipeType.BLASTING);  return; }
        if (b == Blocks.SMOKER)        { executeCookingRecipe(world, task, RecipeType.SMOKING);   return; }
        if (b == Blocks.STONECUTTER)   { executeStoneCutRecipe(world, task);                       return; }
        executeCraftingTableRecipe(world, task);
    }

    /**
     * Loops crafting table recipes until the worker's inventory can no longer supply a full
     * ingredient set.  Each iteration consumes one recipe's worth of ingredients and produces
     * one output stack, naturally handling recipes that yield more than 1 item per craft.
     */
    private void executeCraftingTableRecipe(ServerWorld world, CraftingTask task) {
        Item target = task.recipeItem.getItem();
        Optional<CraftingRecipe> recipeOpt = world.getRecipeManager()
                .listAllOfType(RecipeType.CRAFTING).stream()
                .filter(r -> r.getOutput(world.getRegistryManager()).getItem() == target)
                .findFirst();

        if (recipeOpt.isEmpty()) {
            NPClogistics.LOGGER.warn("{} no crafting recipe for {}",
                    worker.getName().getString(), target);
            return;
        }

        CraftingRecipe recipe = recipeOpt.get();
        DefaultedList<Ingredient> ingredients = recipe.getIngredients();
        SimpleInventory workerInv = worker.getWorkerInventory();
        int totalCrafted = 0;

        while (true) {
            // Match each non-empty ingredient slot to a worker inventory slot, tracking
            // remaining count so multiple slots can share the same stack.
            int[] ingToWorkerSlot = new int[ingredients.size()];
            Arrays.fill(ingToWorkerSlot, -1);
            int[] avail = new int[workerInv.size()];
            for (int wi = 0; wi < workerInv.size(); wi++) avail[wi] = workerInv.getStack(wi).getCount();

            boolean allFound = true;
            for (int ingIdx = 0; ingIdx < ingredients.size(); ingIdx++) {
                Ingredient ing = ingredients.get(ingIdx);
                if (ing.isEmpty()) continue;
                boolean found = false;
                for (int wi = 0; wi < workerInv.size(); wi++) {
                    if (avail[wi] <= 0) continue;
                    if (ing.test(workerInv.getStack(wi))) {
                        ingToWorkerSlot[ingIdx] = wi;
                        avail[wi]--;
                        found = true;
                        break;
                    }
                }
                if (!found) { allFound = false; break; }
            }
            if (!allFound) break;

            // Consume ingredients (multiple slots may map to the same worker slot).
            int[] toConsume = new int[workerInv.size()];
            for (int ingIdx = 0; ingIdx < ingredients.size(); ingIdx++) {
                int wi = ingToWorkerSlot[ingIdx];
                if (wi >= 0) toConsume[wi]++;
            }
            for (int wi = 0; wi < workerInv.size(); wi++) {
                if (toConsume[wi] <= 0) continue;
                ItemStack s = workerInv.getStack(wi);
                s.decrement(toConsume[wi]);
                if (s.isEmpty()) workerInv.setStack(wi, ItemStack.EMPTY);
            }

            // Produce output; stop if worker inventory is full.
            ItemStack output = recipe.getOutput(world.getRegistryManager()).copy();
            ItemStack rem = worker.addToWorkerInventory(output);
            totalCrafted++;
            if (!rem.isEmpty()) break; // inventory full
        }

        ItemStack outputRef = recipe.getOutput(world.getRegistryManager());
        if (totalCrafted > 0) {
            NPClogistics.LOGGER.info("{} crafted {}×{} {}",
                    worker.getName().getString(), totalCrafted,
                    outputRef.getCount(), outputRef.getItem());
        } else {
            NPClogistics.LOGGER.warn("{} no ingredients for craft at workstation",
                    worker.getName().getString());
        }
    }

    private void executeCookingRecipe(ServerWorld world, CraftingTask task,
                                      RecipeType<? extends AbstractCookingRecipe> type) {
        Item target = task.recipeItem.getItem();
        @SuppressWarnings("unchecked")
        Collection<AbstractCookingRecipe> candidates =
                (Collection<AbstractCookingRecipe>) (Collection<?>) world.getRecipeManager().listAllOfType(type);
        AbstractCookingRecipe recipe = candidates.stream()
                .filter(r -> r.getOutput(world.getRegistryManager()).getItem() == target)
                .findFirst().orElse(null);

        if (recipe == null) {
            NPClogistics.LOGGER.warn("{} no cooking recipe for {} (type={})",
                    worker.getName().getString(), target, type);
            return;
        }
        executeSingleInputRecipe(world, recipe);
    }

    private void executeStoneCutRecipe(ServerWorld world, CraftingTask task) {
        Item target = task.recipeItem.getItem();
        StonecuttingRecipe recipe = world.getRecipeManager()
                .listAllOfType(RecipeType.STONECUTTING).stream()
                .filter(r -> r.getOutput(world.getRegistryManager()).getItem() == target)
                .findFirst().orElse(null);

        if (recipe == null) {
            NPClogistics.LOGGER.warn("{} no stonecutting recipe for {}",
                    worker.getName().getString(), target);
            return;
        }
        executeSingleInputRecipe(world, recipe);
    }

    /** Consumes one input per cycle, producing one output, until input is exhausted or inventory full. */
    private void executeSingleInputRecipe(ServerWorld world, Recipe<?> recipe) {
        DefaultedList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return;
        Ingredient ing = ingredients.get(0);
        SimpleInventory workerInv = worker.getWorkerInventory();
        int totalCrafted = 0;

        outer:
        for (int wi = 0; wi < workerInv.size(); wi++) {
            ItemStack s = workerInv.getStack(wi);
            while (!s.isEmpty() && ing.test(s)) {
                s.decrement(1);
                if (s.isEmpty()) workerInv.setStack(wi, ItemStack.EMPTY);
                ItemStack rem = worker.addToWorkerInventory(recipe.getOutput(world.getRegistryManager()).copy());
                totalCrafted++;
                if (!rem.isEmpty()) break outer;
            }
        }

        if (totalCrafted > 0) {
            NPClogistics.LOGGER.info("{} produced {}×{}",
                    worker.getName().getString(), totalCrafted,
                    recipe.getOutput(world.getRegistryManager()).getItem());
        } else {
            NPClogistics.LOGGER.warn("{} no input ingredient for single-input recipe",
                    worker.getName().getString());
        }
    }

    // ── Deposit ───────────────────────────────────────────────────────────────

    private void depositOutput(ServerWorld world, BlockPos depositPos) {
        Inventory container = WorkOrderBrain.resolveInventory(world, depositPos);
        if (container == null) {
            NPClogistics.LOGGER.warn("{} crafting task: no container at deposit {}",
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
        NPClogistics.LOGGER.info("{} deposited {} crafted items at {}",
                worker.getName().getString(), deposited, depositPos);
    }

    // ── Recipe lookup ─────────────────────────────────────────────────────────

    private List<Ingredient> findRequiredIngredients(ServerWorld world, CraftingTask task) {
        Block b = world.getBlockState(task.craftBlockPos).getBlock();
        Item target = task.recipeItem.getItem();

        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) {
            RecipeType<? extends AbstractCookingRecipe> type =
                    b == Blocks.BLAST_FURNACE ? RecipeType.BLASTING :
                    b == Blocks.SMOKER         ? RecipeType.SMOKING :
                                                RecipeType.SMELTING;
            @SuppressWarnings("unchecked")
            Collection<AbstractCookingRecipe> candidates =
                    (Collection<AbstractCookingRecipe>) (Collection<?>) world.getRecipeManager().listAllOfType(type);
            return candidates.stream()
                    .filter(r -> r.getOutput(world.getRegistryManager()).getItem() == target)
                    .findFirst()
                    .map(r -> new ArrayList<>(r.getIngredients()))
                    .orElse(null);
        }

        if (b == Blocks.STONECUTTER) {
            return world.getRecipeManager().listAllOfType(RecipeType.STONECUTTING).stream()
                    .filter(r -> r.getOutput(world.getRegistryManager()).getItem() == target)
                    .findFirst()
                    .map(r -> new ArrayList<>(r.getIngredients()))
                    .orElse(null);
        }

        return world.getRecipeManager().listAllOfType(RecipeType.CRAFTING).stream()
                .filter(r -> r.getOutput(world.getRegistryManager()).getItem() == target)
                .findFirst()
                .map(r -> new ArrayList<>(r.getIngredients()))
                .orElse(null);
    }

    // ── Animation ─────────────────────────────────────────────────────────────

    private void swingMainHand(ServerWorld world) {
        EntityAnimationS2CPacket packet = new EntityAnimationS2CPacket(
                worker, EntityAnimationS2CPacket.SWING_MAIN_HAND);
        for (ServerPlayerEntity p : PlayerLookup.tracking(worker)) {
            p.networkHandler.sendPacket(packet);
        }
    }

    // ── Container sounds ──────────────────────────────────────────────────────

    private static void openContainer(ServerWorld world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_OPEN : SoundEvents.BLOCK_CHEST_OPEN,
                SoundCategory.BLOCKS, 0.4f, 1.0f);
        world.addSyncedBlockEvent(pos, block, 1, 1);
    }

    private static void closeContainer(ServerWorld world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        world.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                block == Blocks.BARREL ? SoundEvents.BLOCK_BARREL_CLOSE : SoundEvents.BLOCK_CHEST_CLOSE,
                SoundCategory.BLOCKS, 0.4f, 1.0f);
        world.addSyncedBlockEvent(pos, block, 1, 0);
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private static BlockPos findApproachPos(ServerWorld world, BlockPos targetPos, BlockPos npcPos) {
        Direction[] hDirs = npcSidedDirections(targetPos, npcPos);
        for (Direction dir : hDirs) {
            BlockPos candidate = targetPos.offset(dir);
            if (isStandable(world, candidate)) return candidate;
        }
        for (Direction dir : hDirs) {
            BlockPos candidate = targetPos.offset(dir).up();
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
        Direction[] all    = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
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

    private static int craftWorkTicks(Block b) {
        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) return SMELT_TICKS;
        if (b == Blocks.STONECUTTER) return CUT_TICKS;
        return CRAFT_TICKS;
    }
}
