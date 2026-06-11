package com.npclogistics.ai;

import com.npclogistics.NPClogistics;
import com.npclogistics.data.CraftingTask;
import com.npclogistics.entity.LogisticsWorkerEntity;
import net.minecraft.block.*;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * Executes a single {@link CraftingTask}: collect ingredients → craft → deposit output.
 * Phases run sequentially; the brain calls {@link LogisticsWorkerEntity#onCraftingTaskComplete()}
 * when the deposit is done so the entity can advance to the next task.
 *
 * Crafting is simulated server-side via {@link RecipeManager} — the NPC navigates to the
 * craft block, pauses for a believable duration, then consumes ingredients and produces output
 * directly.  Supported blocks: crafting table (shaped/shapeless), furnace, blast furnace,
 * smoker, stonecutter.
 */
public class CraftingTaskBrain {

    private static final double ARRIVAL_DIST = 3.0;
    private static final double NAV_SPEED    = 1.0;
    private static final int    OPEN_HOLD    = 20;   // ticks before items move (lid open)
    private static final int    CRAFT_TICKS  = 40;   // work pause at a crafting table
    private static final int    SMELT_TICKS  = 200;  // simulated smelting time (10 s)
    private static final int    CUT_TICKS    = 20;   // stonecutter work pause

    private enum Phase { IDLE, COLLECTING, CRAFTING_NAV, CRAFTING_WORK, DEPOSITING }

    private final LogisticsWorkerEntity worker;
    private Phase   phase          = Phase.IDLE;
    private int     timer          = 0;
    private boolean hasIngredients = false;

    public CraftingTaskBrain(LogisticsWorkerEntity worker) { this.worker = worker; }

    /** Reset all state when a new task is assigned. */
    public void reset() {
        phase          = Phase.IDLE;
        timer          = 0;
        hasIngredients = false;
    }

    /** Called every server tick while the worker is in EXECUTING_TASK state. */
    public void tick(ServerWorld world, CraftingTask task) {
        switch (phase) {
            case IDLE         -> { phase = Phase.COLLECTING; timer = -1; }
            case COLLECTING   -> tickCollecting(world, task);
            case CRAFTING_NAV -> tickCraftingNav(world, task);
            case CRAFTING_WORK -> tickCraftingWork(world, task);
            case DEPOSITING   -> tickDepositing(world, task);
        }
    }

    // ── COLLECTING ────────────────────────────────────────────────────────────

    private void tickCollecting(ServerWorld world, CraftingTask task) {
        double dist = worker.getPos().distanceTo(task.sourcePos.toCenterPos());
        if (dist > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle()) {
                BlockPos approach = findApproachPos(world, task.sourcePos);
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
        phase = Phase.CRAFTING_NAV;
        timer = 0;
    }

    // ── CRAFTING_NAV ──────────────────────────────────────────────────────────

    private void tickCraftingNav(ServerWorld world, CraftingTask task) {
        double dist = worker.getPos().distanceTo(task.craftBlockPos.toCenterPos());
        if (dist <= ARRIVAL_DIST) {
            Block b = world.getBlockState(task.craftBlockPos).getBlock();
            timer = craftWorkTicks(b);
            phase = Phase.CRAFTING_WORK;
            return;
        }
        if (worker.getNavigation().isIdle()) {
            BlockPos approach = findApproachPos(world, task.craftBlockPos);
            worker.getNavigation().startMovingTo(
                    approach.getX() + 0.5, approach.getY(), approach.getZ() + 0.5, NAV_SPEED);
        }
    }

    // ── CRAFTING_WORK ─────────────────────────────────────────────────────────

    private void tickCraftingWork(ServerWorld world, CraftingTask task) {
        if (--timer > 0) return;
        if (hasIngredients) executeCraft(world, task);
        phase = Phase.DEPOSITING;
        timer = -1;
    }

    // ── DEPOSITING ────────────────────────────────────────────────────────────

    private void tickDepositing(ServerWorld world, CraftingTask task) {
        double dist = worker.getPos().distanceTo(task.depositPos.toCenterPos());
        if (dist > ARRIVAL_DIST) {
            if (worker.getNavigation().isIdle()) {
                BlockPos approach = findApproachPos(world, task.depositPos);
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

        boolean allCollected = true;
        for (Ingredient ing : needed) {
            if (ing.isEmpty()) continue;
            boolean taken = false;
            for (int si = 0; si < sourceInv.size(); si++) {
                ItemStack slot = sourceInv.getStack(si);
                if (!slot.isEmpty() && ing.test(slot)) {
                    ItemStack remainder = worker.addToWorkerInventory(slot.copyWithCount(1));
                    if (remainder.isEmpty()) {
                        slot.decrement(1);
                        if (slot.isEmpty()) sourceInv.setStack(si, ItemStack.EMPTY);
                        taken = true;
                        break;
                    }
                }
            }
            if (!taken) allCollected = false;
        }

        sourceInv.markDirty();
        NPClogistics.LOGGER.info("{} collected ingredients from {} (success={})",
                worker.getName().getString(), task.sourcePos, allCollected);
        return allCollected;
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

        // Match each non-empty ingredient slot to a worker inventory slot.
        // Track available count per slot so multiple ingredients can draw from the same stack
        // (e.g. bread needs 3 wheat, which may all be in one slot with count=3).
        int[] ingToWorkerSlot = new int[ingredients.size()];
        Arrays.fill(ingToWorkerSlot, -1);
        int[] available = new int[workerInv.size()];
        for (int wi = 0; wi < workerInv.size(); wi++) available[wi] = workerInv.getStack(wi).getCount();

        boolean allFound = true;
        for (int ingIdx = 0; ingIdx < ingredients.size(); ingIdx++) {
            Ingredient ing = ingredients.get(ingIdx);
            if (ing.isEmpty()) continue;
            boolean found = false;
            for (int wi = 0; wi < workerInv.size(); wi++) {
                if (available[wi] <= 0) continue;
                if (ing.test(workerInv.getStack(wi))) {
                    ingToWorkerSlot[ingIdx] = wi;
                    available[wi]--;
                    found = true;
                    break;
                }
            }
            if (!found) { allFound = false; break; }
        }

        if (!allFound) {
            NPClogistics.LOGGER.warn("{} missing ingredient(s) at craft time",
                    worker.getName().getString());
            return;
        }

        // Count how many to consume from each worker slot (multiple ingredients may share a slot).
        int[] toConsume = new int[workerInv.size()];
        for (int ingIdx = 0; ingIdx < ingredients.size(); ingIdx++) {
            int wi = ingToWorkerSlot[ingIdx];
            if (wi >= 0) toConsume[wi]++;
        }

        // Consume ingredients.
        for (int wi = 0; wi < workerInv.size(); wi++) {
            if (toConsume[wi] <= 0) continue;
            ItemStack s = workerInv.getStack(wi);
            s.decrement(toConsume[wi]);
            if (s.isEmpty()) workerInv.setStack(wi, ItemStack.EMPTY);
        }

        // Add crafted output.
        ItemStack output = recipe.getOutput(world.getRegistryManager()).copy();
        worker.addToWorkerInventory(output);
        NPClogistics.LOGGER.info("{} crafted {}", worker.getName().getString(), output);
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
        executeSingleInputRecipe(world, task, recipe);
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
        executeSingleInputRecipe(world, task, recipe);
    }

    /** Consume one matching ingredient from the worker inventory and produce one output stack. */
    private void executeSingleInputRecipe(ServerWorld world, CraftingTask task, Recipe<?> recipe) {
        DefaultedList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return;
        Ingredient ing = ingredients.get(0);
        SimpleInventory workerInv = worker.getWorkerInventory();
        for (int wi = 0; wi < workerInv.size(); wi++) {
            ItemStack s = workerInv.getStack(wi);
            if (!s.isEmpty() && ing.test(s)) {
                s.decrement(1);
                if (s.isEmpty()) workerInv.setStack(wi, ItemStack.EMPTY);
                ItemStack output = recipe.getOutput(world.getRegistryManager()).copy();
                worker.addToWorkerInventory(output);
                NPClogistics.LOGGER.info("{} produced {}", worker.getName().getString(), output);
                return;
            }
        }
        NPClogistics.LOGGER.warn("{} could not find input ingredient for single-input recipe",
                worker.getName().getString());
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

    /**
     * Returns the non-empty ingredients for the recipe that produces {@code task.recipeItem},
     * selecting the recipe type appropriate for the craft block.  Returns {@code null} if no
     * matching recipe exists.
     */
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

        // Default: crafting table
        return world.getRecipeManager().listAllOfType(RecipeType.CRAFTING).stream()
                .filter(r -> r.getOutput(world.getRegistryManager()).getItem() == target)
                .findFirst()
                .map(r -> new ArrayList<>(r.getIngredients()))
                .orElse(null);
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

    private static BlockPos findApproachPos(ServerWorld world, BlockPos targetPos) {
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos candidate = targetPos.offset(dir);
            if (isStandable(world, candidate)) return candidate;
        }
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos candidate = targetPos.offset(dir).up();
            if (isStandable(world, candidate)) return candidate;
        }
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

    private static int craftWorkTicks(Block b) {
        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) return SMELT_TICKS;
        if (b == Blocks.STONECUTTER) return CUT_TICKS;
        return CRAFT_TICKS;
    }
}
