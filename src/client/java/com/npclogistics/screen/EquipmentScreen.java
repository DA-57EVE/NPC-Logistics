package com.npclogistics.screen;

import com.npclogistics.client.network.ClientNetworking;
import com.npclogistics.data.WorkOrder;
import com.npclogistics.data.WorkOrder.RouteStop;
import com.npclogistics.item.LocationTokenItem;
import com.npclogistics.item.WorkOrderScrollItem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

public class EquipmentScreen extends HandledScreen<EquipmentScreenHandler> {

    // ── Tabs ──────────────────────────────────────────────────────────────────
    private static final int TAB_EQUIPMENT  = 0;
    private static final int TAB_ORDERS     = 1;
    private static final int TAB_TASKS      = 2;
    private int activeTab = TAB_EQUIPMENT;

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final int C_PANEL    = 0xEE0E1216;
    private static final int C_BORDER   = 0xFF3C4450;
    private static final int C_SLOT     = 0xFF1A1E25;
    private static final int C_FIELD    = 0xFF0D1014;
    private static final int C_INV      = 0xFF141820;
    private static final int C_TEXT     = 0xFFE8E8E8;
    private static final int C_MUTED    = 0xFF9A9A9A;
    private static final int C_ACCENT   = 0xFF5B8DD9;
    private static final int C_LOCKED   = 0xFF994444;
    private static final int C_WO_BG    = 0xFF0A1520;
    private static final int C_DIVIDER  = 0xFF2A3038;
    private static final int C_TASK_BG  = 0xFF0C1018;
    private static final int C_LOOP     = 0xFF44BB44;   // green = looping
    private static final int C_ONCE     = 0xFFDDAA22;   // amber = run once
    private static final int C_DONE     = 0xFF555555;   // grey = completed/dormant
    private static final int C_DEL      = 0xFF993333;

    // ── Dimensions ────────────────────────────────────────────────────────────
    private static final int W = 176;
    private static final int H = 240;

    // ── Equipment slot Y positions ────────────────────────────────────────────
    private static final int[] ARMOR_Y      = { 18, 38, 58, 78 };
    private static final String[] ARMOR_LBL = { "Head", "Chest", "Legs", "Feet" };

    // ── Layout constants ──────────────────────────────────────────────────────
    private static final int SEP1_Y      = 118;
    private static final int NAME_FY     = 120;
    private static final int URL_FY      = 134;
    private static final int SEP2_Y      = 148;
    private static final int INV_LABEL_Y = 151;
    private static final int GRID_Y      = EquipmentScreenHandler.INVENTORY_SLOT_Y;
    private static final int HOTBAR_Y    = EquipmentScreenHandler.HOTBAR_SLOT_Y;

    // ── Task layout (mirrors handler) ─────────────────────────────────────────
    private static final int[] TX      = EquipmentScreenHandler.TASK_SLOT_X; // {30,50,70,90}
    private static final int   TROW_Y  = EquipmentScreenHandler.TASK_ROW_Y;  // 18
    private static final int   TROW_H  = EquipmentScreenHandler.TASK_ROW_H;  // 18
    private static final int   MAX_T   = EquipmentScreenHandler.MAX_TASKS;   // 8
    // Toggle button sits just right of the deposit slot
    private static final int   TOGGLE_X = TX[3] + 20; // 110
    // Delete button
    private static final int   DELETE_X = TOGGLE_X + 16; // 126

    // ── Widgets ───────────────────────────────────────────────────────────────
    private TextFieldWidget nameField;
    private TextFieldWidget skinUrlField;
    private ButtonWidget applyButton;
    private ButtonWidget tabEquip;
    private ButtonWidget tabOrders;
    private ButtonWidget tabTasks;

    // Per-task-row toggle and delete buttons (built in init)
    private final ButtonWidget[] toggleButtons = new ButtonWidget[MAX_T];
    private final ButtonWidget[] deleteButtons = new ButtonWidget[MAX_T];

    // Client-side mirror of runOnce/completed (optimistic updates)
    private final boolean[] runOnce    = new boolean[MAX_T];
    private final boolean[] completed  = new boolean[MAX_T];

    public EquipmentScreen(EquipmentScreenHandler handler, PlayerInventory inv, Text title) {
        super(handler, inv, title);
        backgroundWidth  = W;
        backgroundHeight = H;
        // Initialise from handler
        for (int i = 0; i < MAX_T; i++) {
            runOnce[i]   = handler.taskRunOnce[i];
            completed[i] = handler.taskCompleted[i];
        }
    }

    @Override
    protected void init() {
        super.init();
        titleX = (W - textRenderer.getWidth(buildTitle())) / 2;
        titleY = 6;
        playerInventoryTitleY = INV_LABEL_Y;

        // Three tabs above the panel, fitting across the full width
        tabEquip = addDrawableChild(ButtonWidget.builder(
                Text.literal("Equipment"), btn -> switchTab(TAB_EQUIPMENT))
                .dimensions(x + 2, y - 14, 56, 13).build());

        tabOrders = addDrawableChild(ButtonWidget.builder(
                Text.literal("Orders"), btn -> switchTab(TAB_ORDERS))
                .dimensions(x + 60, y - 14, 55, 13).build());

        tabTasks = addDrawableChild(ButtonWidget.builder(
                Text.literal("Tasks"), btn -> switchTab(TAB_TASKS))
                .dimensions(x + 117, y - 14, 57, 13).build());

        // Profile fields (Equipment tab only)
        nameField = addDrawableChild(new TextFieldWidget(textRenderer,
                x + 36, y + NAME_FY, 132, 12, Text.empty()));
        nameField.setMaxLength(64);
        nameField.setPlaceholder(Text.literal("Worker name…"));
        nameField.setText(handler.workerName);
        nameField.setEditable(handler.isEmployer);

        skinUrlField = addDrawableChild(new TextFieldWidget(textRenderer,
                x + 36, y + URL_FY, 102, 12, Text.empty()));
        skinUrlField.setMaxLength(512);
        skinUrlField.setPlaceholder(Text.literal("Skin URL…"));
        skinUrlField.setText(handler.workerSkinUrl);
        skinUrlField.setEditable(handler.isEmployer);

        applyButton = addDrawableChild(ButtonWidget.builder(Text.literal("Apply"),
                btn -> ClientNetworking.sendUpdateWorkerProfile(
                        handler.workerEntityId,
                        nameField.getText().trim(),
                        skinUrlField.getText().trim()))
                .dimensions(x + 140, y + URL_FY - 1, 28, 14).build());
        applyButton.active = handler.isEmployer;

        // Task-row toggle and delete buttons
        for (int i = 0; i < MAX_T; i++) {
            final int idx = i;
            int ry = TROW_Y + i * TROW_H;

            toggleButtons[i] = addDrawableChild(ButtonWidget.builder(
                    Text.literal(runOnce[i] ? "1" : "∞"),
                    btn -> {
                        runOnce[idx] = !runOnce[idx];
                        if (!runOnce[idx]) completed[idx] = false;
                        btn.setMessage(Text.literal(runOnce[idx] ? "1" : "∞"));
                        ClientNetworking.sendTaskToggleOnce(handler.workerEntityId, idx, runOnce[idx]);
                    })
                    .dimensions(x + TOGGLE_X, y + ry, 14, 14).build());

            deleteButtons[i] = addDrawableChild(ButtonWidget.builder(
                    Text.literal("×"),
                    btn -> {
                        runOnce[idx]  = false;
                        completed[idx] = false;
                        toggleButtons[idx].setMessage(Text.literal("∞"));
                        ClientNetworking.sendTaskDelete(handler.workerEntityId, idx);
                    })
                    .dimensions(x + DELETE_X, y + ry, 14, 14).build());
        }

        switchTab(activeTab);
    }

    private void switchTab(int tab) {
        activeTab = tab;
        boolean equip  = (tab == TAB_EQUIPMENT);
        boolean tasks  = (tab == TAB_TASKS);

        nameField.visible    = equip;
        skinUrlField.visible = equip;
        applyButton.visible  = equip;
        applyButton.active   = equip && handler.isEmployer;

        for (int i = 0; i < MAX_T; i++) {
            toggleButtons[i].visible = tasks;
            deleteButtons[i].visible = tasks;
        }

        // Show/hide task slots (all hidden unless on Tasks tab).
        repositionTaskSlots(tasks);

        // Equipment: armor+hand visible, WO visible
        // Orders:    armor+hand hidden,  WO visible
        // Tasks:     all equipment hidden
        setEquipmentActive(equip, !tasks);
    }

    /** Enable/disable equipment slots. armorHand = slots 0-5; wo = slots 6-7. */
    private void setEquipmentActive(boolean armorHand, boolean wo) {
        for (int i = 0; i < EquipmentScreenHandler.SLOT_WO1; i++) {
            if (handler.slots.get(i) instanceof EquipmentScreenHandler.DisablableSlot ds)
                ds.setActive(armorHand);
        }
        for (int i = EquipmentScreenHandler.SLOT_WO1; i < EquipmentScreenHandler.EQUIPMENT_SLOTS; i++) {
            if (handler.slots.get(i) instanceof EquipmentScreenHandler.DisablableSlot ds)
                ds.setActive(wo);
        }
    }

    /** Enable/disable task slots depending on active tab.
     *  Disabled slots are neither rendered nor clickable. */
    private void repositionTaskSlots(boolean visible) {
        for (int i = 0; i < MAX_T; i++) {
            for (int j = 0; j < 4; j++) {
                Slot raw = handler.slots.get(EquipmentScreenHandler.TASK_SLOTS_START + i * 4 + j);
                if (raw instanceof EquipmentScreenHandler.DisablableSlot ds) ds.setActive(visible);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // Task slots are disabled (isEnabled=false) on non-Tasks tabs via DisablableSlot,
        // but an extra click-block guards against any edge cases.
        if (activeTab != TAB_TASKS) {
            for (int i = 0; i < MAX_T; i++) {
                for (int j = 0; j < 4; j++) {
                    Slot s = handler.slots.get(EquipmentScreenHandler.TASK_SLOTS_START + i * 4 + j);
                    if (isPointWithinBounds(s.x, s.y, 16, 16, mx, my)) return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    protected void drawMouseoverTooltip(DrawContext context, int mouseX, int mouseY) {
        // Bypass HandledScreen.focusedSlot (unreliable in custom screens) and do
        // a direct slot scan so both item tooltips and empty-slot hints work.
        if (handler.getCursorStack().isEmpty()) {
            for (Slot slot : handler.slots) {
                if (!slot.isEnabled()) continue;
                if (!isPointWithinBounds(slot.x, slot.y, 16, 16, mouseX, mouseY)) continue;
                if (slot.hasStack()) {
                    context.drawItemTooltip(textRenderer, slot.getStack(), mouseX, mouseY);
                    return;
                }
                // Empty task-slot type hints
                if (activeTab != TAB_TASKS) continue;
                int idx = handler.slots.indexOf(slot);
                if (idx < EquipmentScreenHandler.TASK_SLOTS_START) continue;
                int col = (idx - EquipmentScreenHandler.TASK_SLOTS_START) % 4;
                Formatting clr = switch (col) {
                    case EquipmentScreenHandler.TASK_SOURCE  -> Formatting.AQUA;
                    case EquipmentScreenHandler.TASK_CRAFT   -> Formatting.GOLD;
                    case EquipmentScreenHandler.TASK_DEPOSIT -> Formatting.GREEN;
                    default                                  -> Formatting.GRAY;
                };
                String title = switch (col) {
                    case EquipmentScreenHandler.TASK_SOURCE  -> "Collect Location";
                    case EquipmentScreenHandler.TASK_RECIPE  -> "Recipe Item";
                    case EquipmentScreenHandler.TASK_CRAFT   -> "Craft Location";
                    case EquipmentScreenHandler.TASK_DEPOSIT -> "Deposit Location";
                    default -> "";
                };
                String hint = switch (col) {
                    case EquipmentScreenHandler.TASK_SOURCE  -> "Place a Collect Token here";
                    case EquipmentScreenHandler.TASK_RECIPE  -> "The item to be crafted";
                    case EquipmentScreenHandler.TASK_CRAFT   -> "Place a Craft Token here";
                    case EquipmentScreenHandler.TASK_DEPOSIT -> "Place a Deposit Token here";
                    default -> "";
                };
                context.drawTooltip(textRenderer, List.of(
                        Text.literal(title).formatted(clr),
                        Text.literal(hint).formatted(Formatting.DARK_GRAY)
                ), mouseX, mouseY);
                return;
            }
        }
    }

    // ── Key capture ───────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        TextFieldWidget focused = null;
        if (nameField    != null && nameField.isFocused())         focused = nameField;
        else if (skinUrlField != null && skinUrlField.isFocused()) focused = skinUrlField;

        if (focused != null) {
            if (focused.keyPressed(keyCode, scanCode, modifiers)) return true;
            if (keyCode == 256) { focused.setFocused(false); return true; }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx);
        super.render(ctx, mx, my, delta);
        drawMouseoverTooltip(ctx, mx, my);
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mx, int my) {
        ctx.fill(x, y, x + W, y + H, C_PANEL);
        border(ctx, x, y, x + W, y + H);

        if      (activeTab == TAB_EQUIPMENT) drawEquipBg(ctx, mx, my);
        else if (activeTab == TAB_ORDERS)    drawOrdersBg(ctx, mx, my);
        else                                 drawTasksBg(ctx, mx, my);

        // Inventory section (all tabs)
        ctx.fill(x + 7, y + SEP2_Y,     x + 169, y + SEP2_Y + 1, C_BORDER);
        ctx.fill(x + 7, y + SEP2_Y + 1, x + 169, y + H - 4,      C_INV);
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                slotBg(ctx, x + 8 + col * 18, y + GRID_Y + row * 18);
        ctx.fill(x + 7, y + HOTBAR_Y - 4, x + 169, y + HOTBAR_Y - 3, C_BORDER);
        for (int col = 0; col < 9; col++)
            slotBg(ctx, x + 8 + col * 18, y + HOTBAR_Y);
    }

    private void drawEquipBg(DrawContext ctx, int mx, int my) {
        for (int ay : ARMOR_Y) slotBg(ctx, x + 8, y + ay);
        slotBg(ctx, x + 152, y + 38);
        slotBg(ctx, x + 152, y + 58);
        slotBg(ctx, x + 152, y + 78);
        slotBg(ctx, x + 152, y + 98);

        LivingEntity entity = workerEntity();
        if (entity != null)
            InventoryScreen.drawEntity(ctx, x + 88, y + 83, 24,
                    (float)(x + 88) - mx, (float)(y + 36) - my, entity);

        ctx.fill(x + 7, y + SEP1_Y,     x + 169, y + SEP1_Y + 1, C_BORDER);
        ctx.fill(x + 7, y + SEP1_Y + 1, x + 169, y + SEP2_Y,     C_FIELD);
    }

    private void drawOrdersBg(DrawContext ctx, int mx, int my) {
        ctx.fill(x + 7, y + 16, x + 148, y + SEP1_Y, C_WO_BG);
        border(ctx, x + 7, y + 16, x + 148, y + SEP1_Y);
        slotBg(ctx, x + 152, y + 78);
        slotBg(ctx, x + 152, y + 98);
        ctx.fill(x + 7, y + SEP1_Y,     x + 169, y + SEP1_Y + 1, C_BORDER);
        ctx.fill(x + 7, y + SEP1_Y + 1, x + 169, y + SEP2_Y,     C_PANEL);
    }

    private void drawTasksBg(DrawContext ctx, int mx, int my) {
        // Dark task area from title to inventory separator
        ctx.fill(x + 7, y + 16, x + 169, y + SEP2_Y, C_TASK_BG);
        border(ctx, x + 7, y + 16, x + 169, y + SEP2_Y);

        // Draw each task row background + its 4 type-coloured slot outlines
        for (int i = 0; i < MAX_T; i++) {
            int ry = TROW_Y + i * TROW_H;
            boolean hasContent = hasTaskContent(i);

            // Subtle row alternation
            if (i % 2 == 0)
                ctx.fill(x + 8, y + ry, x + 168, y + ry + TROW_H - 1, 0x08FFFFFF);

            // Slot backgrounds with type-coded border tint
            slotBgTinted(ctx, x + TX[0], y + ry, 0xFF334488); // blue  = collect
            slotBg(ctx,       x + TX[1], y + ry);              // neutral = recipe
            slotBgTinted(ctx, x + TX[2], y + ry, 0xFF885511); // orange = craft
            slotBgTinted(ctx, x + TX[3], y + ry, 0xFF226633); // green  = deposit

            // Mode indicator stripe (left edge of row)
            int stripe;
            if (!hasContent)         stripe = C_DIVIDER;
            else if (completed[i])   stripe = C_DONE;
            else if (runOnce[i])     stripe = C_ONCE;
            else                     stripe = C_LOOP;
            ctx.fill(x + 8, y + ry, x + 9, y + ry + TROW_H - 2, stripe);
        }
    }

    @Override
    protected void drawForeground(DrawContext ctx, int mx, int my) {
        Text t = buildTitle();
        ctx.drawText(textRenderer, t, (backgroundWidth - textRenderer.getWidth(t)) / 2, titleY, C_TEXT, false);

        if      (activeTab == TAB_EQUIPMENT) drawEquipFg(ctx);
        else if (activeTab == TAB_ORDERS)    drawOrdersFg(ctx);
        else                                 drawTasksFg(ctx);

        ctx.drawText(textRenderer, "Inventory", 8, INV_LABEL_Y, C_MUTED, false);
    }

    private void drawEquipFg(DrawContext ctx) {
        for (int i = 0; i < ARMOR_LBL.length; i++)
            ctx.drawText(textRenderer, ARMOR_LBL[i], 28, ARMOR_Y[i] + 5, C_MUTED, false);
        drawRight(ctx, "Main",    150, 43,  C_MUTED);
        drawRight(ctx, "Off",     150, 63,  C_MUTED);
        drawRight(ctx, "Order 1", 148, 83,  C_MUTED);
        drawRight(ctx, "Order 2", 148, 103, C_MUTED);

        // Employer line — italic, 80% scale, just above the profile separator
        String empLabel = handler.workerEmployerName.isBlank()
                ? "Employer: (unclaimed)" : "Employer: " + handler.workerEmployerName;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(8, SEP1_Y - 10, 0);
        ctx.getMatrices().scale(0.8f, 0.8f, 1.0f);
        ctx.drawText(textRenderer, Text.literal(empLabel).formatted(Formatting.ITALIC), 0, 0, C_ACCENT, false);
        ctx.getMatrices().pop();

        ctx.drawText(textRenderer, "Name", 8, NAME_FY + 2, handler.isEmployer ? C_MUTED : C_DONE, false);
        ctx.drawText(textRenderer, "Skin",  8, URL_FY  + 2, handler.isEmployer ? C_MUTED : C_DONE, false);
    }

    private void drawOrdersFg(DrawContext ctx) {
        drawRight(ctx, "Order 1", 148, 83,  handler.canTakeWo1 ? C_MUTED : C_LOCKED);
        drawRight(ctx, "Order 2", 148, 103, handler.canTakeWo2 ? C_MUTED : C_LOCKED);

        int lx = 10, ly = 19;
        ly = iLine(ctx, lx, ly, "WORK ORDERS", C_ACCENT);
        ly = iLine(ctx, lx, ly, handler.workerName.isBlank() ? "Unnamed Worker" : handler.workerName, C_TEXT);
        ly++;
        ctx.fill(x + lx - 2, y + ly, x + lx + 138, y + ly + 1, C_BORDER);
        ly += 3;
        ly = drawInvoice(ctx, lx, ly, EquipmentScreenHandler.SLOT_WO1, "Slot 1");
        ly += 3;
        drawInvoice(ctx, lx, ly, EquipmentScreenHandler.SLOT_WO2, "Slot 2");
    }

    private void drawTasksFg(DrawContext ctx) {
        for (int i = 0; i < MAX_T; i++) {
            int ry = TROW_Y + i * TROW_H;
            // Row number
            ctx.drawText(textRenderer, (i + 1) + ".", 10, ry + 5, C_MUTED, false);

            // Completed overlay
            if (completed[i]) {
                ctx.drawText(textRenderer, "✓", TX[1] + 3, ry + 5, C_DONE, false);
            }

            // Added-by label on the right edge (small text, only if row has content)
            if (hasTaskContent(i) && !handler.taskAddedByName[i].isEmpty()) {
                String label = handler.taskAddedByName[i];
                ctx.drawText(textRenderer, label, DELETE_X + 16, ry + 5, C_MUTED, false);
            }
        }
    }

    // ── Invoice helpers (Orders tab) ──────────────────────────────────────────

    private int drawInvoice(DrawContext ctx, int lx, int ly, int slot, String label) {
        ItemStack scroll = handler.slots.get(slot).getStack();
        if (scroll.isEmpty())
            return iLine(ctx, lx, ly, label + ": (empty)", C_MUTED);
        WorkOrder order = WorkOrderScrollItem.readOrder(scroll);
        if (order == null)
            return iLine(ctx, lx, ly, label + ": (unreadable)", C_LOCKED);
        String dep = (scroll.hasNbt() && scroll.getNbt().contains("depositedByName"))
                ? " [" + scroll.getNbt().getString("depositedByName") + "]" : "";
        ly = iLine(ctx, lx, ly, label + ": \"" + order.getName() + "\"" + dep, C_ACCENT);
        for (int i = 0; i < order.getStops().size() && ly < SEP1_Y - 9; i++) {
            RouteStop stop = order.getStops().get(i);
            ly = iLine(ctx, lx + 4, ly,
                    (i + 1) + ". " + stop.action.name() + " @ "
                    + stop.pos.getX() + "," + stop.pos.getY() + "," + stop.pos.getZ(),
                    C_MUTED);
        }
        return ly;
    }

    // ── Title builder ─────────────────────────────────────────────────────────

    private Text buildTitle() {
        Text suffix = Text.literal("NPCLogistics").formatted(Formatting.ITALIC);
        if (!handler.workerName.isBlank())
            return Text.literal(handler.workerName).formatted(Formatting.BOLD)
                    .append(Text.literal(" - ").formatted(Formatting.RESET))
                    .append(suffix);
        return suffix;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasTaskContent(int i) {
        int base = EquipmentScreenHandler.TASK_SLOTS_START + i * 4;
        for (int j = 0; j < 4; j++)
            if (!handler.slots.get(base + j).getStack().isEmpty()) return true;
        return false;
    }

    private int iLine(DrawContext ctx, int lx, int ly, String text, int color) {
        ctx.drawText(textRenderer, text, lx, ly, color, false);
        return ly + 9;
    }

    private void drawRight(DrawContext ctx, String text, int rx, int ry, int color) {
        ctx.drawText(textRenderer, text, rx - textRenderer.getWidth(text), ry, color, false);
    }

    private void slotBg(DrawContext ctx, int sx, int sy) {
        ctx.fill(sx,      sy,      sx + 18, sy + 18, C_SLOT);
        ctx.fill(sx,      sy,      sx + 18, sy + 1,  C_BORDER);
        ctx.fill(sx,      sy,      sx + 1,  sy + 18, C_BORDER);
        ctx.fill(sx + 17, sy,      sx + 18, sy + 18, C_BORDER);
        ctx.fill(sx,      sy + 17, sx + 18, sy + 18, C_BORDER);
    }

    private void slotBgTinted(DrawContext ctx, int sx, int sy, int borderColor) {
        ctx.fill(sx,      sy,      sx + 18, sy + 18, C_SLOT);
        ctx.fill(sx,      sy,      sx + 18, sy + 1,  borderColor);
        ctx.fill(sx,      sy,      sx + 1,  sy + 18, borderColor);
        ctx.fill(sx + 17, sy,      sx + 18, sy + 18, borderColor);
        ctx.fill(sx,      sy + 17, sx + 18, sy + 18, borderColor);
    }

    private void border(DrawContext ctx, int x1, int y1, int x2, int y2) {
        ctx.fill(x1,     y1,     x2,     y1 + 1, C_BORDER);
        ctx.fill(x1,     y2 - 1, x2,     y2,     C_BORDER);
        ctx.fill(x1,     y1,     x1 + 1, y2,     C_BORDER);
        ctx.fill(x2 - 1, y1,     x2,     y2,     C_BORDER);
    }

    private LivingEntity workerEntity() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return null;
        return mc.world.getEntityById(handler.workerEntityId) instanceof LivingEntity e ? e : null;
    }
}
