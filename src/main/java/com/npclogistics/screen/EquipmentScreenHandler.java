package com.npclogistics.screen;

import com.npclogistics.data.CraftingTask;
import com.npclogistics.entity.LogisticsWorkerEntity;
import com.npclogistics.item.LocationTokenItem;
import com.npclogistics.item.WorkOrderScrollItem;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

public class EquipmentScreenHandler extends ScreenHandler {

    // ── Slot indices ──────────────────────────────────────────────────────────
    public static final int SLOT_HEAD     = 0;
    public static final int SLOT_CHEST    = 1;
    public static final int SLOT_LEGS     = 2;
    public static final int SLOT_FEET     = 3;
    public static final int SLOT_MAINHAND = 4;
    public static final int SLOT_OFFHAND  = 5;
    public static final int SLOT_WO1      = 6;
    public static final int SLOT_WO2      = 7;
    public static final int EQUIPMENT_SLOTS = 8;

    // Task slots: 8 tasks × 4 slots = 32 (source, recipe, craft, deposit)
    public static final int TASK_SLOTS_START = EQUIPMENT_SLOTS + 36; // after equip + inv + hotbar
    public static final int MAX_TASKS        = LogisticsWorkerEntity.MAX_TASKS;
    public static final int TASK_SOURCE      = 0; // offset within a task row
    public static final int TASK_RECIPE      = 1;
    public static final int TASK_CRAFT       = 2;
    public static final int TASK_DEPOSIT     = 3;

    // Slot Y positions — shared with EquipmentScreen for layout alignment
    public static final int INVENTORY_SLOT_Y = 163;
    public static final int HOTBAR_SLOT_Y    = 219;

    // Task slot panel-local X positions (col 0..3)
    public static final int[] TASK_SLOT_X = { 30, 50, 70, 90 };
    // Task slot panel-local Y: row i → TASK_ROW_Y + i * TASK_ROW_H
    public static final int TASK_ROW_Y    = 18;
    public static final int TASK_ROW_H    = 18;

    // ── Shared data (exposed to screen) ──────────────────────────────────────
    public final int     workerEntityId;
    public final String  workerName;
    public final String  workerSkinUrl;
    public final boolean canTakeWo1;
    public final boolean canTakeWo2;
    public final boolean isEmployer;
    public final String  workerEmployerName;

    // Task metadata (parallel to task item slots)
    public final boolean[] taskRunOnce    = new boolean[MAX_TASKS];
    public final boolean[] taskCompleted  = new boolean[MAX_TASKS];
    public final String[]  taskAddedByName = new String[MAX_TASKS];
    public final boolean[] taskIsOwn      = new boolean[MAX_TASKS]; // this player added the task

    private final SimpleInventory equipmentInventory;
    private final SimpleInventory taskInventory;

    // ── Server-side constructor ───────────────────────────────────────────────
    public EquipmentScreenHandler(int syncId, PlayerInventory playerInventory,
                                   LogisticsWorkerEntity worker) {
        super(ModScreenHandlers.EQUIPMENT, syncId);
        this.workerEntityId = worker.getId();
        this.workerName     = worker.getCustomName() != null ? worker.getCustomName().getString() : "";
        this.workerSkinUrl  = worker.getCustomSkinUrl();
        this.canTakeWo1          = true;
        this.canTakeWo2          = true;
        this.isEmployer          = true;
        this.workerEmployerName  = worker.getEmployerName();

        this.equipmentInventory = new SimpleInventory(EQUIPMENT_SLOTS);
        equipmentInventory.setStack(SLOT_HEAD,     worker.getEquippedStack(EquipmentSlot.HEAD).copy());
        equipmentInventory.setStack(SLOT_CHEST,    worker.getEquippedStack(EquipmentSlot.CHEST).copy());
        equipmentInventory.setStack(SLOT_LEGS,     worker.getEquippedStack(EquipmentSlot.LEGS).copy());
        equipmentInventory.setStack(SLOT_FEET,     worker.getEquippedStack(EquipmentSlot.FEET).copy());
        equipmentInventory.setStack(SLOT_MAINHAND, worker.getEquippedStack(EquipmentSlot.MAINHAND).copy());
        equipmentInventory.setStack(SLOT_OFFHAND,  worker.getEquippedStack(EquipmentSlot.OFFHAND).copy());
        equipmentInventory.setStack(SLOT_WO1,      worker.getWoScroll1().copy());
        equipmentInventory.setStack(SLOT_WO2,      worker.getWoScroll2().copy());

        this.taskInventory = new SimpleInventory(MAX_TASKS * 4);
        for (int i = 0; i < MAX_TASKS; i++) {
            CraftingTask t = worker.getTask(i);
            if (t != null) {
                // Source and deposit positions are stored in LocationToken items
                taskInventory.setStack(i * 4 + TASK_SOURCE,  makeTokenForPos(t.sourcePos,    LocationTokenItem.TokenType.COLLECT));
                taskInventory.setStack(i * 4 + TASK_RECIPE,  t.recipeItem.copy());
                taskInventory.setStack(i * 4 + TASK_CRAFT,   makeTokenForPos(t.craftBlockPos, LocationTokenItem.TokenType.CRAFT));
                taskInventory.setStack(i * 4 + TASK_DEPOSIT, makeTokenForPos(t.depositPos,    LocationTokenItem.TokenType.DEPOSIT));
                taskRunOnce[i]     = t.runOnce;
                taskCompleted[i]   = t.completed;
                taskAddedByName[i] = t.addedByName;
            }
        }

        addEquipmentSlots();
        addPlayerInventorySlots(playerInventory);
        addTaskSlots();
    }

    private static ItemStack makeTokenForPos(BlockPos pos, LocationTokenItem.TokenType type) {
        if (pos == null) return ItemStack.EMPTY;
        com.npclogistics.item.ModItems mi = null; // access via registry
        net.minecraft.item.Item item = switch (type) {
            case COLLECT -> com.npclogistics.item.ModItems.LOCATION_TOKEN_COLLECT;
            case CRAFT   -> com.npclogistics.item.ModItems.LOCATION_TOKEN_CRAFT;
            case DEPOSIT -> com.npclogistics.item.ModItems.LOCATION_TOKEN_DEPOSIT;
        };
        ItemStack stack = new ItemStack(item);
        LocationTokenItem.stampPos(stack, pos, "");
        return stack;
    }

    // ── Client-side constructor ───────────────────────────────────────────────
    public EquipmentScreenHandler(int syncId, PlayerInventory playerInventory, PacketByteBuf buf) {
        super(ModScreenHandlers.EQUIPMENT, syncId);
        this.workerEntityId = buf.readInt();
        this.workerName     = buf.readString(64);
        this.workerSkinUrl  = buf.readString(512);
        this.canTakeWo1         = buf.readBoolean();
        this.canTakeWo2         = buf.readBoolean();
        this.isEmployer         = buf.readBoolean();
        this.workerEmployerName = buf.readString(64);

        for (int i = 0; i < MAX_TASKS; i++) {
            taskRunOnce[i]     = buf.readBoolean();
            taskCompleted[i]   = buf.readBoolean();
            taskAddedByName[i] = buf.readString(64);
            taskIsOwn[i]       = buf.readBoolean();
        }

        this.equipmentInventory = new SimpleInventory(EQUIPMENT_SLOTS);
        this.taskInventory      = new SimpleInventory(MAX_TASKS * 4);
        addEquipmentSlots();
        addPlayerInventorySlots(playerInventory);
        addTaskSlots();
    }

    // ── Slot registration ─────────────────────────────────────────────────────

    private void addEquipmentSlots() {
        addSlot(new ArmorSlot(equipmentInventory,     SLOT_HEAD,     8,   18, EquipmentSlot.HEAD));
        addSlot(new ArmorSlot(equipmentInventory,     SLOT_CHEST,    8,   38, EquipmentSlot.CHEST));
        addSlot(new ArmorSlot(equipmentInventory,     SLOT_LEGS,     8,   58, EquipmentSlot.LEGS));
        addSlot(new ArmorSlot(equipmentInventory,     SLOT_FEET,     8,   78, EquipmentSlot.FEET));
        addSlot(new HandSlot(equipmentInventory,      SLOT_MAINHAND, 152, 38));
        addSlot(new HandSlot(equipmentInventory,      SLOT_OFFHAND,  152, 58));
        addSlot(new WorkOrderSlot(equipmentInventory, SLOT_WO1,      152, 78));
        addSlot(new WorkOrderSlot(equipmentInventory, SLOT_WO2,      152, 98));
    }

    private void addPlayerInventorySlots(PlayerInventory playerInventory) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, INVENTORY_SLOT_Y + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInventory, col, 8 + col * 18, HOTBAR_SLOT_Y));
    }

    private void addTaskSlots() {
        for (int i = 0; i < MAX_TASKS; i++) {
            int ry = TASK_ROW_Y + i * TASK_ROW_H;
            addSlot(new DisablableTokenSlot(taskInventory, i*4+TASK_SOURCE,  TASK_SLOT_X[0], ry, LocationTokenItem.TokenType.COLLECT));
            addSlot(new DisablableSlot(taskInventory,      i*4+TASK_RECIPE,  TASK_SLOT_X[1], ry));
            addSlot(new DisablableTokenSlot(taskInventory, i*4+TASK_CRAFT,   TASK_SLOT_X[2], ry, LocationTokenItem.TokenType.CRAFT));
            addSlot(new DisablableTokenSlot(taskInventory, i*4+TASK_DEPOSIT, TASK_SLOT_X[3], ry, LocationTokenItem.TokenType.DEPOSIT));
        }
    }

    // ── Depositor stamping (WO slots) ─────────────────────────────────────────

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        super.onSlotClick(slotIndex, button, actionType, player);
        if (slotIndex == SLOT_WO1 || slotIndex == SLOT_WO2) {
            ItemStack s = slots.get(slotIndex).getStack();
            if (!s.isEmpty()) stampDepositor(s, player);
        }
    }

    private static void stampDepositor(ItemStack scroll, PlayerEntity player) {
        NbtCompound nbt = scroll.getOrCreateNbt();
        if (!nbt.containsUuid("depositedBy")) {
            nbt.putUuid("depositedBy", player.getUuid());
            nbt.putString("depositedByName", player.getName().getString());
        }
    }

    // ── Task metadata mutators (called from network handlers) ─────────────────

    public void setTaskRunOnce(int idx, boolean value) {
        if (idx < 0 || idx >= MAX_TASKS) return;
        taskRunOnce[idx]   = value;
        if (!value) taskCompleted[idx] = false; // switching to loop resets completed
    }

    public void clearTaskSlots(int idx) {
        if (idx < 0 || idx >= MAX_TASKS) return;
        for (int j = 0; j < 4; j++) taskInventory.setStack(idx * 4 + j, ItemStack.EMPTY);
        taskRunOnce[idx]     = false;
        taskCompleted[idx]   = false;
        taskAddedByName[idx] = "";
        taskIsOwn[idx]       = false;
    }

    // ── Screen close ─────────────────────────────────────────────────────────

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        if (player.getWorld().isClient) return;

        if (!(player.getWorld().getEntityById(workerEntityId) instanceof LogisticsWorkerEntity worker)) {
            for (int i = 0; i < EQUIPMENT_SLOTS; i++) {
                ItemStack s = equipmentInventory.getStack(i);
                if (!s.isEmpty() && !player.getInventory().insertStack(s)) player.dropItem(s, false);
            }
            for (int i = 0; i < MAX_TASKS * 4; i++) {
                ItemStack s = taskInventory.getStack(i);
                if (!s.isEmpty() && !player.getInventory().insertStack(s)) player.dropItem(s, false);
            }
            return;
        }

        worker.equipStack(EquipmentSlot.HEAD,     equipmentInventory.getStack(SLOT_HEAD));
        worker.equipStack(EquipmentSlot.CHEST,    equipmentInventory.getStack(SLOT_CHEST));
        worker.equipStack(EquipmentSlot.LEGS,     equipmentInventory.getStack(SLOT_LEGS));
        worker.equipStack(EquipmentSlot.FEET,     equipmentInventory.getStack(SLOT_FEET));
        worker.equipStack(EquipmentSlot.MAINHAND, equipmentInventory.getStack(SLOT_MAINHAND));
        worker.equipStack(EquipmentSlot.OFFHAND,  equipmentInventory.getStack(SLOT_OFFHAND));
        worker.setWoScroll1(equipmentInventory.getStack(SLOT_WO1).copy());
        worker.setWoScroll2(equipmentInventory.getStack(SLOT_WO2).copy());

        // Save task data back to entity
        for (int i = 0; i < MAX_TASKS; i++) {
            ItemStack srcStack  = taskInventory.getStack(i * 4 + TASK_SOURCE);
            ItemStack recStack  = taskInventory.getStack(i * 4 + TASK_RECIPE);
            ItemStack craftStack = taskInventory.getStack(i * 4 + TASK_CRAFT);
            ItemStack depStack  = taskInventory.getStack(i * 4 + TASK_DEPOSIT);

            boolean hasContent = !srcStack.isEmpty() || !recStack.isEmpty()
                    || !craftStack.isEmpty() || !depStack.isEmpty();

            if (!hasContent) { worker.clearTask(i); continue; }

            BlockPos srcPos   = LocationTokenItem.getPos(srcStack);
            BlockPos craftPos = LocationTokenItem.getPos(craftStack);
            BlockPos depPos   = LocationTokenItem.getPos(depStack);

            CraftingTask existing = worker.getTask(i);
            UUID addedBy   = (existing != null) ? existing.addedBy   : player.getUuid();
            String addedName = (existing != null) ? existing.addedByName : player.getName().getString();

            worker.setTask(i, new CraftingTask(srcPos, recStack.copy(), craftPos, depPos,
                    taskRunOnce[i], taskCompleted[i], addedBy, addedName));
        }

        worker.resumeAfterGUI();
    }

    // ── Quick-move (shift-click) ──────────────────────────────────────────────

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasStack()) return ItemStack.EMPTY;

        ItemStack stack    = slot.getStack();
        ItemStack original = stack.copy();

        int playerStart = EQUIPMENT_SLOTS;
        int playerEnd   = TASK_SLOTS_START;
        int taskEnd     = TASK_SLOTS_START + MAX_TASKS * 4;

        if (slotIndex < EQUIPMENT_SLOTS) {
            if (!insertItem(stack, playerStart, playerEnd, true)) return ItemStack.EMPTY;
        } else if (slotIndex >= TASK_SLOTS_START && slotIndex < taskEnd) {
            if (!insertItem(stack, playerStart, playerEnd, true)) return ItemStack.EMPTY;
        } else if (stack.getItem() instanceof WorkOrderScrollItem) {
            if (!slots.get(SLOT_WO1).hasStack()) {
                if (!insertItem(stack, SLOT_WO1, SLOT_WO1 + 1, false)) return ItemStack.EMPTY;
                stampDepositor(slots.get(SLOT_WO1).getStack(), player);
            } else if (!slots.get(SLOT_WO2).hasStack()) {
                if (!insertItem(stack, SLOT_WO2, SLOT_WO2 + 1, false)) return ItemStack.EMPTY;
                stampDepositor(slots.get(SLOT_WO2).getStack(), player);
            } else {
                return ItemStack.EMPTY;
            }
        } else if (stack.getItem() instanceof LocationTokenItem token) {
            // Shift-click token to first matching empty task slot
            int col = switch (token.tokenType) {
                case COLLECT -> TASK_SOURCE;
                case CRAFT   -> TASK_CRAFT;
                case DEPOSIT -> TASK_DEPOSIT;
            };
            for (int i = 0; i < MAX_TASKS; i++) {
                int target = TASK_SLOTS_START + i * 4 + col;
                if (!slots.get(target).hasStack()) {
                    if (!insertItem(stack, target, target + 1, false)) return ItemStack.EMPTY;
                    break;
                }
            }
        } else {
            EquipmentSlot preferred = MobEntity.getPreferredEquipmentSlot(stack);
            int target = switch (preferred) {
                case HEAD    -> SLOT_HEAD;
                case CHEST   -> SLOT_CHEST;
                case LEGS    -> SLOT_LEGS;
                case FEET    -> SLOT_FEET;
                case OFFHAND -> SLOT_OFFHAND;
                default      -> SLOT_MAINHAND;
            };
            if (!insertItem(stack, target, target + 1, false)) return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY); else slot.markDirty();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTakeItem(player, stack);
        return original;
    }

    @Override public boolean canUse(PlayerEntity player) { return true; }

    // ── Custom slot types ─────────────────────────────────────────────────────

    private static class ArmorSlot extends DisablableSlot {
        private final EquipmentSlot equipmentSlot;
        ArmorSlot(Inventory inv, int index, int x, int y, EquipmentSlot slot) {
            super(inv, index, x, y, true); this.equipmentSlot = slot;
        }
        @Override public boolean canInsert(ItemStack s) {
            return MobEntity.getPreferredEquipmentSlot(s) == equipmentSlot;
        }
        @Override public int getMaxItemCount() { return 1; }
    }

    private static class HandSlot extends DisablableSlot {
        HandSlot(Inventory inv, int index, int x, int y) { super(inv, index, x, y, true); }
        @Override public int getMaxItemCount() { return 1; }
        @Override public boolean canTakeItems(PlayerEntity player) { return true; }
    }

    private static class WorkOrderSlot extends DisablableSlot {
        WorkOrderSlot(Inventory inv, int index, int x, int y) { super(inv, index, x, y, true); }
        @Override public boolean canInsert(ItemStack s) { return s.getItem() instanceof WorkOrderScrollItem; }
        @Override public boolean canTakeItems(PlayerEntity player) {
            ItemStack s = getStack();
            if (s.isEmpty()) return true;
            NbtCompound nbt = s.getNbt();
            if (nbt == null || !nbt.containsUuid("depositedBy")) return true;
            return nbt.getUuid("depositedBy").equals(player.getUuid());
        }
        @Override public int getMaxItemCount() { return 1; }
    }

    /** Base for any slot the screen can show/hide per tab via setActive(). */
    public static class DisablableSlot extends Slot {
        private boolean active;
        DisablableSlot(Inventory inv, int index, int x, int y) { this(inv, index, x, y, false); }
        DisablableSlot(Inventory inv, int index, int x, int y, boolean initialActive) {
            super(inv, index, x, y); this.active = initialActive;
        }
        @Override public boolean isEnabled() { return active; }
        public void setActive(boolean v)     { active = v; }
    }

    public static class DisablableTokenSlot extends DisablableSlot {
        private final LocationTokenItem.TokenType requiredType;
        DisablableTokenSlot(Inventory inv, int index, int x, int y, LocationTokenItem.TokenType type) {
            super(inv, index, x, y); this.requiredType = type;
        }
        @Override public boolean canInsert(ItemStack s) {
            return s.getItem() instanceof LocationTokenItem t && t.tokenType == requiredType;
        }
        @Override public int getMaxItemCount() { return 1; }
    }
}
