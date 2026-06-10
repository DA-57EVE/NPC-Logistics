package com.npclogistics.entity;

import com.npclogistics.NPClogistics;
import com.npclogistics.ai.WorkOrderBrain;
import com.npclogistics.data.CraftingTask;
import com.npclogistics.data.WorkOrder;
import com.npclogistics.data.WorkOrder.QtyMode;
import com.npclogistics.data.WorkOrder.RouteStop;
import com.npclogistics.data.WorkOrder.StopAction;
import com.npclogistics.item.ModItems;
import com.npclogistics.item.WorkOrderScrollItem;
import com.npclogistics.network.ModNetworking;
import com.npclogistics.screen.EquipmentScreenHandler;
import com.npclogistics.screen.ModScreenHandlers;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Logistics Worker NPC.
 *
 * Behaviour state machine:
 *  IDLE      – waiting in place for a work order (e.g. after its order was taken back off)
 *  EXECUTING – following the route defined in its active WorkOrder
 *  RETURNING – route complete (or cancelled), walking back to homePos, then IDLE
 *
 * The actual movement & chest-interaction logic lives in WorkOrderBrain.
 */
public class LogisticsWorkerEntity extends PathAwareEntity {

    public enum WorkerState { IDLE, EXECUTING, RETURNING, EXECUTING_TASK }

    /** Default skin URL — used for all workers unless overridden per-entity. */
    public static final String DEFAULT_SKIN_URL =
            "https://www.minecraftskins.com/uploads/skins/2026/04/23/mole-rat-construction-worker-24011706.png?v951";

    private static final TrackedData<String> SKIN_URL_DATA =
            DataTracker.registerData(LogisticsWorkerEntity.class, TrackedDataHandlerRegistry.STRING);

    // -----------------------------------------------------------------------
    //  Fields
    // -----------------------------------------------------------------------

    /** Internal inventory (18 slots – same as a double-chest row). */
    private final SimpleInventory inventory = new SimpleInventory(18);

    /** The currently active work order, or null when idle. */
    private WorkOrder activeWorkOrder = null;

    /** Index of the stop the NPC is currently travelling to. */
    private int currentStopIndex = 0;

    private WorkerState state = WorkerState.IDLE;

    /** Position the NPC considers "home" – returned to after completing a route. */
    private BlockPos homePos = BlockPos.ORIGIN;

    /** Tick counter used to throttle interaction attempts. */
    private int interactionCooldown = 0;

    // The dedicated AI brain for route execution
    private final WorkOrderBrain workOrderBrain;

    // Work-order scroll slots — items placed by players via the equipment GUI.
    // Depositor UUID is stamped into the scroll's own NBT ("depositedBy").
    private ItemStack woScroll1 = ItemStack.EMPTY;
    private ItemStack woScroll2 = ItemStack.EMPTY;
    // Tracks which WO slot the NPC is currently executing (1 or 2).
    private int currentWoSlot = 1;

    // ── Employer ─────────────────────────────────────────────────────────────
    // First player to interact becomes the employer.  Only the employer may rename the NPC.
    private UUID employerUUID = null;
    private String employerName = "";

    // ── Crafting tasks ────────────────────────────────────────────────────────
    public static final int MAX_TASKS = 6;
    private final CraftingTask[] tasks = new CraftingTask[MAX_TASKS];
    private int currentTaskIndex = -1;
    private int taskTickDelay = 0;

    // ── GUI pause / resume ────────────────────────────────────────────────────
    private WorkerState pausedState     = null;
    private WorkOrder   pausedWorkOrder = null;
    private int         pausedStopIndex = 0;
    private int         pausedTaskIndex = -1;

    // ── Auto-fire scheduling (3× per game day) ────────────────────────────────
    // Fires at morning (1000), midday (6000) and evening (13000) ticks.
    private long lastCheckedDay    = -1;
    private final boolean[] firedThisDay = new boolean[3];

    // -----------------------------------------------------------------------
    //  Constructor & static attribute factory
    // -----------------------------------------------------------------------

    public LogisticsWorkerEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        workOrderBrain = new WorkOrderBrain(this);
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        dataTracker.startTracking(SKIN_URL_DATA, DEFAULT_SKIN_URL);
    }

    public String getCustomSkinUrl() { return dataTracker.get(SKIN_URL_DATA); }
    public void setCustomSkinUrl(String url) { dataTracker.set(SKIN_URL_DATA, url); }

    // WO scroll slot access
    public ItemStack getWoScroll1() { return woScroll1; }
    public ItemStack getWoScroll2() { return woScroll2; }
    public void setWoScroll1(ItemStack s) { woScroll1 = s.isEmpty() ? ItemStack.EMPTY : s; }
    public void setWoScroll2(ItemStack s) { woScroll2 = s.isEmpty() ? ItemStack.EMPTY : s; }

    // Employer access
    public UUID getEmployerUUID()           { return employerUUID; }
    public String getEmployerName()         { return employerName; }
    public boolean isEmployer(UUID uuid)    { return employerUUID == null || employerUUID.equals(uuid); }

    /** Claim employer on first interaction; subsequent calls are no-ops. */
    public void claimEmployer(UUID uuid, String name) {
        if (employerUUID == null) { employerUUID = uuid; employerName = name; }
    }

    // Task access
    public CraftingTask getTask(int i)              { return (i >= 0 && i < MAX_TASKS) ? tasks[i] : null; }
    public void setTask(int i, CraftingTask task)   { if (i >= 0 && i < MAX_TASKS) tasks[i] = task; }
    public void clearTask(int i)                    { if (i >= 0 && i < MAX_TASKS) tasks[i] = null; }

    // ── GUI pause / resume ────────────────────────────────────────────────────

    /** Freeze the NPC when a player opens the GUI so it doesn't wander mid-task. */
    public void pauseForGUI() {
        if (state == WorkerState.EXECUTING || state == WorkerState.RETURNING
                || state == WorkerState.EXECUTING_TASK) {
            pausedState      = state;
            pausedWorkOrder  = activeWorkOrder;
            pausedStopIndex  = currentStopIndex;
            pausedTaskIndex  = currentTaskIndex;
        }
        getNavigation().stop();
        state           = WorkerState.IDLE;
        activeWorkOrder = null;
    }

    /** Restore execution state after the GUI closes. */
    public void resumeAfterGUI() {
        if (pausedState == WorkerState.EXECUTING && pausedWorkOrder != null) {
            activeWorkOrder  = pausedWorkOrder;
            currentStopIndex = pausedStopIndex;
            state            = WorkerState.EXECUTING;
        } else if (pausedState == WorkerState.EXECUTING_TASK) {
            currentTaskIndex = pausedTaskIndex;
            state            = WorkerState.EXECUTING_TASK;
        }
        // RETURNING is intentionally not restored — if the player just placed a WO scroll
        // while the NPC was heading home, we want it to start the new order immediately.
        pausedState     = null;
        pausedWorkOrder = null;
        // If nothing was restored (idle, returning, or freshly opened), try to start work
        if (state == WorkerState.IDLE || state == WorkerState.RETURNING) {
            state = WorkerState.IDLE;
            activateWorkOrders();
        }
    }

    // ── Auto-fire scheduling ──────────────────────────────────────────────────

    private static final long[] FIRE_TIMES = { 1000L, 6000L, 13000L }; // morning/midday/evening

    private void checkAutoFire(ServerWorld world) {
        long absTime  = world.getTime();
        long day      = absTime / 24000;
        long dayTime  = absTime % 24000;

        if (day != lastCheckedDay) {
            lastCheckedDay = day;
            firedThisDay[0] = false;
            firedThisDay[1] = false;
            firedThisDay[2] = false;
        }

        for (int slot = 0; slot < FIRE_TIMES.length; slot++) {
            if (!firedThisDay[slot] && dayTime >= FIRE_TIMES[slot] && dayTime < FIRE_TIMES[slot] + 200) {
                firedThisDay[slot] = true;
                NPClogistics.LOGGER.info("{} auto-fire slot {} (dayTime={})", getName().getString(), slot, dayTime);
                activateWorkOrders();
                return;
            }
        }
    }

    // ── Task chain ────────────────────────────────────────────────────────────

    /** Begin the crafting-task list from the first eligible task. Employer tasks run first. */
    public void startTaskChain() {
        // Employer tasks first (index-stable, employer tasks identified by addedBy == employerUUID)
        if (tryStartTask(true)) return;
        tryStartTask(false);
    }

    private boolean tryStartTask(boolean employerOnly) {
        for (int i = 0; i < MAX_TASKS; i++) {
            CraftingTask t = tasks[i];
            if (t == null || !t.hasAllContent()) continue;
            if (t.runOnce && t.completed) continue;
            boolean isEmployerTask = (employerUUID != null && employerUUID.equals(t.addedBy));
            if (employerOnly && !isEmployerTask) continue;
            currentTaskIndex = i;
            executeTask(i);
            return true;
        }
        return false;
    }

    /** Advance to the next eligible task after currentTaskIndex. */
    public void advanceTask() {
        for (int i = currentTaskIndex + 1; i < MAX_TASKS; i++) {
            CraftingTask t = tasks[i];
            if (t == null || !t.hasAllContent()) continue;
            if (t.runOnce && t.completed) continue;
            currentTaskIndex = i;
            executeTask(i);
            return;
        }
        // No more tasks — return home
        currentTaskIndex = -1;
        state            = WorkerState.RETURNING;
        NPClogistics.LOGGER.info("{} task chain complete, returning home.", getName().getString());
    }

    private void executeTask(int idx) {
        CraftingTask t = tasks[idx];
        state          = WorkerState.EXECUTING_TASK;
        taskTickDelay  = 60; // brief pause before executing (calm, steady workers)
        NPClogistics.LOGGER.info("{} starting crafting task slot {}.", getName().getString(), idx);
        // Mark completed if run-once (actual crafting logic is in CraftingTaskBrain — stub for now)
        if (t.runOnce) tasks[idx] = t.withCompleted(true);
    }

    private void tickExecutingTask() {
        if (taskTickDelay > 0) { taskTickDelay--; return; }
        // TODO: delegate to CraftingTaskBrain when implemented
        advanceTask();
    }

    /**
     * Called server-side when the equipment GUI closes.
     * Reads work orders from the WO scroll slots and starts execution if idle.
     */
    public void activateWorkOrders() {
        if (state != WorkerState.IDLE) return;
        // Anchor home to the NPC's current position the first time we activate.
        if (homePos.equals(BlockPos.ORIGIN)) homePos = getBlockPos();
        currentWoSlot = 1;
        if (!woScroll1.isEmpty()) {
            WorkOrder order = WorkOrderScrollItem.readOrder(woScroll1);
            if (order != null && !order.getStops().isEmpty()) { startWorkOrder(order); return; }
        }
        if (!woScroll2.isEmpty()) {
            WorkOrder order = WorkOrderScrollItem.readOrder(woScroll2);
            if (order != null && !order.getStops().isEmpty()) { currentWoSlot = 2; startWorkOrder(order); return; }
        }
        // No valid WOs — fall through to task chain
        startTaskChain();
    }

    // -----------------------------------------------------------------------
    //  Goal registration
    // -----------------------------------------------------------------------

    @Override
    protected void initGoals() {
        // Low-priority vanilla goals that activate only when IDLE
        goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        goalSelector.add(9, new LookAroundGoal(this));
    }

    // -----------------------------------------------------------------------
    //  Tick
    // -----------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) return;

        if (interactionCooldown > 0) interactionCooldown--;

        switch (state) {
            case EXECUTING       -> workOrderBrain.tick((ServerWorld) getWorld());
            case RETURNING       -> tickReturning();
            case EXECUTING_TASK  -> tickExecutingTask();
            case IDLE            -> checkAutoFire((ServerWorld) getWorld());
        }
    }

    private void tickReturning() {
        if (homePos == null) { state = WorkerState.IDLE; return; }

        double dist = getPos().distanceTo(homePos.toCenterPos());
        if (dist > 1.5) {
            getNavigation().startMovingTo(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5, 1.0);
        } else {
            getNavigation().stop();
            state = WorkerState.IDLE;

            // If repeating, restart
            if (activeWorkOrder != null && activeWorkOrder.isRepeating()) {
                startWorkOrder(activeWorkOrder);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Player interaction – right-click opens the work order screen
    // -----------------------------------------------------------------------

    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

        ItemStack held = player.getStackInHand(hand);

        // Holding a scroll → let WorkOrderScrollItem.useOnEntity assign the route directly.
        if (held.getItem() instanceof WorkOrderScrollItem) return ActionResult.PASS;

        // Sneak + empty hand → cancel active order and return it as a scroll.
        if (player.isSneaking() && held.isEmpty()) {
            if (!getWorld().isClient && player instanceof ServerPlayerEntity serverPlayer) {
                WorkOrder retrieved = retrieveWorkOrder();
                ItemStack scrollStack = new ItemStack(ModItems.WORK_ORDER_SCROLL);
                if (retrieved != null) WorkOrderScrollItem.writeOrder(scrollStack, retrieved);
                if (!serverPlayer.getInventory().insertStack(scrollStack))
                    serverPlayer.dropItem(scrollStack, false);
                // Creative inventory is client-authoritative; push explicit slot update.
                if (serverPlayer.isCreative()) {
                    int slot = serverPlayer.getInventory().selectedSlot;
                    serverPlayer.networkHandler.sendPacket(
                        new net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket(
                            -2, 0, slot, serverPlayer.getInventory().getStack(slot)));
                }
            }
            return ActionResult.SUCCESS;
        }

        // Empty hand, no sneak → open the Equipment GUI.
        if (!getWorld().isClient && player instanceof ServerPlayerEntity serverPlayer) {
            // First player to right-click becomes the employer.
            claimEmployer(serverPlayer.getUuid(), serverPlayer.getName().getString());
            pauseForGUI();
            serverPlayer.openHandledScreen(new ExtendedScreenHandlerFactory() {
                @Override public Text getDisplayName() {
                    return Text.literal("NPC ").append(Text.literal("NPCLogistics").formatted(Formatting.ITALIC));
                }

                @Override
                public ScreenHandler createMenu(int syncId, PlayerInventory inv, PlayerEntity p) {
                    return new EquipmentScreenHandler(syncId, inv, LogisticsWorkerEntity.this);
                }

                @Override
                public void writeScreenOpeningData(ServerPlayerEntity p, PacketByteBuf buf) {
                    buf.writeInt(getId());
                    buf.writeString(getCustomName() != null ? getCustomName().getString() : "");
                    buf.writeString(getCustomSkinUrl());
                    buf.writeBoolean(canPlayerTakeScroll(p, woScroll1));
                    buf.writeBoolean(canPlayerTakeScroll(p, woScroll2));
                    buf.writeBoolean(isEmployer(p.getUuid()));
                    buf.writeString(employerName);   // shown as "Employed by: [name]"
                    // Task metadata (item stacks are synced automatically by ScreenHandler)
                    for (int i = 0; i < MAX_TASKS; i++) {
                        CraftingTask t = tasks[i];
                        buf.writeBoolean(t != null && t.runOnce);
                        buf.writeBoolean(t != null && t.completed);
                        buf.writeString(t != null ? t.addedByName : "");
                        buf.writeBoolean(t != null && p.getUuid().equals(t.addedBy));
                    }
                }
            });
        }
        return ActionResult.SUCCESS;
    }

    private static boolean canPlayerTakeScroll(ServerPlayerEntity player, ItemStack scroll) {
        if (scroll.isEmpty()) return true;
        var nbt = scroll.getNbt();
        if (nbt == null || !nbt.containsUuid("depositedBy")) return true;
        return nbt.getUuid("depositedBy").equals(player.getUuid());
    }

    // -----------------------------------------------------------------------
    //  Work-order lifecycle
    // -----------------------------------------------------------------------

    public void startWorkOrder(WorkOrder order) {
        activeWorkOrder = order;
        currentStopIndex = 0;
        state = WorkerState.EXECUTING;
        NPClogistics.LOGGER.info("{} starting work order '{}'", getName().getString(), order.getName());
    }

    public void cancelWorkOrder() {
        activeWorkOrder = null;
        currentStopIndex = 0;
        state = WorkerState.RETURNING;
    }

    /**
     * Removes the active order and idles the worker in place.
     * Falls back to the persisted WO scroll slots when no order is actively executing
     * (e.g. after the route already completed). Returns the removed order, or null.
     */
    public WorkOrder retrieveWorkOrder() {
        WorkOrder removed = activeWorkOrder;
        if (removed == null) {
            // Route may have already completed; try to recover the order from the WO slots.
            if (!woScroll1.isEmpty()) {
                WorkOrder candidate = WorkOrderScrollItem.readOrder(woScroll1);
                if (candidate != null && !candidate.getStops().isEmpty()) removed = candidate;
            }
            if (removed == null && !woScroll2.isEmpty()) {
                WorkOrder candidate = WorkOrderScrollItem.readOrder(woScroll2);
                if (candidate != null && !candidate.getStops().isEmpty()) removed = candidate;
            }
        }
        activeWorkOrder  = null;
        woScroll1        = ItemStack.EMPTY;
        woScroll2        = ItemStack.EMPTY;
        currentStopIndex = 0;
        state = WorkerState.IDLE;
        getNavigation().stop();
        if (removed != null) {
            NPClogistics.LOGGER.info("{} had work order '{}' taken back; now idle.",
                    getName().getString(), removed.getName());
        }
        return removed;
    }

    public void onRouteComplete() {
        NPClogistics.LOGGER.info("{} completed route (WO slot {}).", getName().getString(), currentWoSlot);

        if (activeWorkOrder != null && activeWorkOrder.isRepeating()) {
            startWorkOrder(activeWorkOrder);
            return;
        }
        // If WO slot 1 just finished, try slot 2
        if (currentWoSlot == 1 && !woScroll2.isEmpty()) {
            WorkOrder order2 = WorkOrderScrollItem.readOrder(woScroll2);
            if (order2 != null && !order2.getStops().isEmpty()) {
                currentWoSlot = 2;
                startWorkOrder(order2);
                return;
            }
        }
        currentWoSlot   = 1;
        activeWorkOrder = null;
        // Try the task chain; if nothing to do, walk home.
        startTaskChain();
        if (state == WorkerState.IDLE) {
            state = WorkerState.RETURNING;
        }
    }

    // -----------------------------------------------------------------------
    //  Inventory helpers
    // -----------------------------------------------------------------------

    public SimpleInventory getWorkerInventory() { return inventory; }

    /** Collect filtered items from a container at the given stop. */
    public int collectItemsFromInventory(SimpleInventory container, RouteStop stop) {
        int collected = 0;
        for (int i = 0; i < container.size(); i++) {
            ItemStack stack = container.getStack(i);
            if (stack.isEmpty()) continue;
            QtyMode mode = stop.collectMode(stack);
            if (mode == null || !mode.allowsSlot(stack)) continue;

            int canTake = stop.maxAmount > 0
                    ? Math.min(stack.getCount(), stop.maxAmount - collected)
                    : stack.getCount();
            if (canTake <= 0) break;

            ItemStack toAdd = stack.copy();
            toAdd.setCount(canTake);
            ItemStack remainder = addToInventory(toAdd);
            int taken = canTake - remainder.getCount();
            stack.decrement(taken);
            if (stack.isEmpty()) container.setStack(i, ItemStack.EMPTY);
            collected += taken;
        }
        container.markDirty();
        return collected;
    }

    /** Deliver filtered items from worker inventory into the destination container. */
    public int deliverItemsToInventory(SimpleInventory container, RouteStop stop) {
        int delivered = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;
            QtyMode mode = stop.deliverMode(stack);
            if (mode == null || !mode.allowsSlot(stack)) continue;

            int canGive = stop.maxAmount > 0
                    ? Math.min(stack.getCount(), stop.maxAmount - delivered)
                    : stack.getCount();
            if (canGive <= 0) break;

            ItemStack toInsert = stack.copy();
            toInsert.setCount(canGive);
            ItemStack remainder = insertIntoContainer(container, toInsert);
            int given = canGive - remainder.getCount();
            stack.decrement(given);
            if (stack.isEmpty()) inventory.setStack(i, ItemStack.EMPTY);
            delivered += given;
        }
        inventory.markDirty();
        return delivered;
    }

    private ItemStack addToInventory(ItemStack stack) {
        for (int i = 0; i < inventory.size() && !stack.isEmpty(); i++) {
            ItemStack slot = inventory.getStack(i);
            if (slot.isEmpty()) {
                inventory.setStack(i, stack.copy());
                return ItemStack.EMPTY;
            } else if (ItemStack.canCombine(slot, stack)) {
                int space = slot.getMaxCount() - slot.getCount();
                int move = Math.min(space, stack.getCount());
                slot.increment(move);
                stack.decrement(move);
            }
        }
        return stack;
    }

    private ItemStack insertIntoContainer(SimpleInventory container, ItemStack stack) {
        for (int i = 0; i < container.size() && !stack.isEmpty(); i++) {
            ItemStack slot = container.getStack(i);
            if (slot.isEmpty()) {
                container.setStack(i, stack.copy());
                return ItemStack.EMPTY;
            } else if (ItemStack.canCombine(slot, stack)) {
                int space = slot.getMaxCount() - slot.getCount();
                int move = Math.min(space, stack.getCount());
                slot.increment(move);
                stack.decrement(move);
            }
        }
        return stack;
    }

    // -----------------------------------------------------------------------
    //  Getters / Setters
    // -----------------------------------------------------------------------

    public WorkOrder getActiveWorkOrder()                { return activeWorkOrder; }
    public int getCurrentStopIndex()                     { return currentStopIndex; }
    public void setCurrentStopIndex(int i)               { currentStopIndex = i; }
    public WorkerState getWorkerState()                  { return state; }
    public void setWorkerState(WorkerState s)            { state = s; }
    public BlockPos getHomePos()                         { return homePos; }
    public void setHomePos(BlockPos p)                   { homePos = p; }
    public int getInteractionCooldown()                  { return interactionCooldown; }
    public void setInteractionCooldown(int t)            { interactionCooldown = t; }

    // -----------------------------------------------------------------------
    //  NBT persistence
    // -----------------------------------------------------------------------

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putString("workerState", state.name());
        nbt.putInt("currentStop", currentStopIndex);
        nbt.putInt("homeX", homePos.getX());
        nbt.putInt("homeY", homePos.getY());
        nbt.putInt("homeZ", homePos.getZ());

        nbt.putString("skinUrl", getCustomSkinUrl());
        nbt.putInt("currentWoSlot", currentWoSlot);
        if (!woScroll1.isEmpty()) nbt.put("woScroll1", woScroll1.writeNbt(new NbtCompound()));
        if (!woScroll2.isEmpty()) nbt.put("woScroll2", woScroll2.writeNbt(new NbtCompound()));

        if (employerUUID != null) {
            nbt.putUuid("employerUUID", employerUUID);
            nbt.putString("employerName", employerName);
        }
        NbtList taskList = new NbtList();
        for (int i = 0; i < MAX_TASKS; i++) {
            NbtCompound slot = new NbtCompound();
            slot.putInt("i", i);
            if (tasks[i] != null) slot.put("task", tasks[i].toNbt());
            taskList.add(slot);
        }
        nbt.put("craftingTasks", taskList);

        if (activeWorkOrder != null) {
            nbt.put("workOrder", activeWorkOrder.toNbt());
        }

        NbtList invList = new NbtList();
        for (int i = 0; i < inventory.size(); i++) {
            NbtCompound slotTag = new NbtCompound();
            slotTag.putByte("slot", (byte) i);
            inventory.getStack(i).writeNbt(slotTag);
            invList.add(slotTag);
        }
        nbt.put("workerInventory", invList);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        try { state = WorkerState.valueOf(nbt.getString("workerState")); }
        catch (IllegalArgumentException e) { state = WorkerState.IDLE; }
        currentStopIndex = nbt.getInt("currentStop");
        homePos = new BlockPos(nbt.getInt("homeX"), nbt.getInt("homeY"), nbt.getInt("homeZ"));

        if (nbt.contains("skinUrl")) setCustomSkinUrl(nbt.getString("skinUrl"));
        currentWoSlot = nbt.contains("currentWoSlot") ? nbt.getInt("currentWoSlot") : 1;
        woScroll1 = nbt.contains("woScroll1") ? ItemStack.fromNbt(nbt.getCompound("woScroll1")) : ItemStack.EMPTY;
        woScroll2 = nbt.contains("woScroll2") ? ItemStack.fromNbt(nbt.getCompound("woScroll2")) : ItemStack.EMPTY;

        if (nbt.containsUuid("employerUUID")) {
            employerUUID = nbt.getUuid("employerUUID");
            employerName = nbt.getString("employerName");
        }
        if (nbt.contains("craftingTasks")) {
            NbtList taskList = nbt.getList("craftingTasks", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < taskList.size(); i++) {
                NbtCompound slot = taskList.getCompound(i);
                int idx = slot.getInt("i");
                if (idx >= 0 && idx < MAX_TASKS) {
                    tasks[idx] = slot.contains("task") ? CraftingTask.fromNbt(slot.getCompound("task")) : null;
                }
            }
        }

        if (nbt.contains("workOrder")) {
            activeWorkOrder = WorkOrder.fromNbt(nbt.getCompound("workOrder"));
        }

        NbtList invList = nbt.getList("workerInventory", 10);
        for (int i = 0; i < invList.size(); i++) {
            NbtCompound slotTag = invList.getCompound(i);
            int slot = slotTag.getByte("slot") & 0xFF;
            inventory.setStack(slot, ItemStack.fromNbt(slotTag));
        }
    }
}
