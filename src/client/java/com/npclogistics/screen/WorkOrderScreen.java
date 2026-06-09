package com.npclogistics.screen;

import com.npclogistics.client.network.ClientNetworking;
import com.npclogistics.data.WorkOrder;
import com.npclogistics.data.WorkOrder.RouteStop;
import com.npclogistics.data.WorkOrder.StopAction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/**
 * In-game GUI for viewing and editing a Work Order assigned to a Logistics Worker.
 *
 * Design goals: an uncluttered panel with roomy, well-spaced rows. Friendly hover
 * tooltips explain every control, so nothing needs an on-screen legend.
 *
 *  • Drag a stop row up/down to reorder it          (Roadmap #2)
 *  • "Filter" opens the per-stop item-filter editor (Roadmap #1)
 *  • Mouse wheel pages through long stop lists
 */
@Environment(EnvType.CLIENT)
public class WorkOrderScreen extends Screen {

    private static final int PANEL_W = 328;
    private static final int PANEL_H = 234;
    private static final int ROW_H   = 20;
    private static final int VISIBLE_STOPS = 5;

    // Button sizes (wide enough that the labels never overflow)
    private static final int BTN_H       = 18;
    private static final int ACTION_W    = 60;   // "COLLECT" / "DELIVER"
    private static final int FILTER_W    = 50;   // "Filter"
    private static final int DELETE_W    = 18;   // "✕"
    private static final int BTN_GAP     = 4;

    // Palette
    private static final int COL_PANEL   = 0xE6101317;
    private static final int COL_BORDER  = 0xFF3C4450;
    private static final int COL_DIVIDER = 0xFF2A3038;
    private static final int COL_TEXT    = 0xFFE8E8E8;
    private static final int COL_MUTED   = 0xFF9A9A9A;
    private static final int COL_FAINT   = 0xFF6F7681;
    private static final int COL_ACCENT  = 0xFFFFD479;
    private static final int COL_FILTER  = 0xFFAEE39B;
    private static final int ROW_ODD     = 0x22FFFFFF;
    private static final int ROW_HOVER   = 0x33FFD479;

    private final int workerEntityId;   // -1 when editing a scroll
    private final Hand scrollHand;      // null when editing a worker
    private final WorkOrder workOrder;

    private TextFieldWidget nameField;

    private int scrollOffset = 0;

    // Drag-to-reorder state
    private int draggingIndex = -1;
    private int dragMouseY = 0;

    /** Editor bound to a worker NPC: Confirm assigns the route to that worker. */
    public WorkOrderScreen(int workerEntityId, WorkOrder existingOrder) {
        super(Text.literal("Work Order Editor"));
        this.workerEntityId = workerEntityId;
        this.scrollHand = null;
        this.workOrder = existingOrder != null
                ? existingOrder
                : new WorkOrder("New Order", BlockPos.ORIGIN, false);
    }

    /** Editor bound to a held Work Order Scroll: Confirm writes the route back onto the scroll. */
    public WorkOrderScreen(Hand scrollHand, WorkOrder scrollOrder) {
        super(Text.literal("Work Order Editor"));
        this.workerEntityId = -1;
        this.scrollHand = scrollHand;
        this.workOrder = scrollOrder != null
                ? scrollOrder
                : new WorkOrder("Scroll Route", BlockPos.ORIGIN, false);
    }

    private boolean isScrollMode() { return scrollHand != null; }

    // -----------------------------------------------------------------------
    //  Layout helpers
    // -----------------------------------------------------------------------

    private int left() { return (width - PANEL_W) / 2; }
    private int top()  { return (height - PANEL_H) / 2; }
    private int listTop() { return top() + 86; }

    private int deleteX() { return left() + PANEL_W - 12 - DELETE_W; }
    private int filterX() { return deleteX() - BTN_GAP - FILTER_W; }
    private int actionX() { return filterX() - BTN_GAP - ACTION_W; }
    private int labelRight() { return actionX() - 6; }

    private int rowY(int absoluteIndex) {
        return listTop() + (absoluteIndex - scrollOffset) * ROW_H;
    }

    // -----------------------------------------------------------------------

    @Override
    protected void init() {
        int left = left();
        int top  = top();

        nameField = new TextFieldWidget(textRenderer, left + 56, top + 24, PANEL_W - 56 - 16, 16, Text.literal("Order Name"));
        nameField.setText(workOrder.getName());
        nameField.setMaxLength(40);
        nameField.setTooltip(Tooltip.of(Text.literal("Give this route a memorable name.")));
        addDrawableChild(nameField);

        addDrawableChild(ButtonWidget.builder(
                Text.literal(workOrder.isRepeating() ? "☑ Repeating" : "☐ Repeating"),
                btn -> {
                    workOrder.setRepeating(!workOrder.isRepeating());
                    btn.setMessage(Text.literal(workOrder.isRepeating() ? "☑ Repeating" : "☐ Repeating"));
                    btn.setTooltip(Tooltip.of(repeatingTooltip()));
                })
                .tooltip(Tooltip.of(repeatingTooltip()))
                .dimensions(left + 12, top + 48, 130, BTN_H)
                .build());

        if (!isScrollMode()) {
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("⚙ Equipment"),
                    btn -> ClientNetworking.sendOpenEquipmentScreen(workerEntityId))
                    .tooltip(Tooltip.of(Text.literal("Open the equipment editor for this worker.\nAssign armour, main-hand and off-hand items.")))
                    .dimensions(left + PANEL_W - 12 - 104, top + 48, 104, BTN_H)
                    .build());
        }

        addDrawableChild(ButtonWidget.builder(
                Text.literal(isScrollMode() ? "✔ Save to Scroll" : "✔ Confirm"), btn -> {
            workOrder.setName(nameField.getText());
            if (isScrollMode()) {
                ClientNetworking.sendUpdateScroll(scrollHand, workOrder);
            } else {
                ClientNetworking.sendAssignWorkOrder(workerEntityId, workOrder);
            }
            close();
        })
                .tooltip(Tooltip.of(Text.literal(isScrollMode()
                        ? "Save these edits onto the scroll.\nRight-click a worker to assign it."
                        : "Save this route and put the worker to work.")))
                .dimensions(left + 40, top + PANEL_H - 30, 110, 20).build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal(isScrollMode() ? "Discard" : "Cancel Order"), btn -> {
            if (!isScrollMode()) {
                ClientNetworking.sendCancelWorkOrder(workerEntityId);
            }
            close();
        })
                .tooltip(Tooltip.of(Text.literal(isScrollMode()
                        ? "Close without saving changes to the scroll."
                        : "Stop the current job and send the worker home.")))
                .dimensions(left + PANEL_W - 40 - 120, top + PANEL_H - 30, 120, 20).build());

        rebuildStopButtons();
    }

    private Text repeatingTooltip() {
        return Text.literal(workOrder.isRepeating()
                ? "Looping: the worker runs this route over and over."
                : "Runs once, then the worker returns home.\nClick to loop forever.");
    }

    private Text actionTooltip(StopAction a) {
        return switch (a) {
            case COLLECT -> Text.literal("COLLECT — picks items UP here.\nClick → DELIVER.");
            case DELIVER -> Text.literal("DELIVER — drops items OFF here.\nClick → BOTH.");
            case BOTH    -> Text.literal("BOTH — delivers, then collects, in one visit.\n"
                    + "Use Filter to set the deliver and collect lists.\nClick → COLLECT.");
        };
    }

    private Text filterTooltip(RouteStop stop) {
        if (stop.action == StopAction.BOTH) {
            return Text.literal("Deliver: " + filterCount(stop.itemFilter)
                    + " · Collect: " + filterCount(stop.collectFilter) + "\nClick to edit both lists.");
        }
        return Text.literal(stop.itemFilter.isEmpty()
                ? "Accepts every item.\nClick to pick specific items."
                : "Handling " + stop.itemFilter.size() + " chosen item(s).\nClick to edit.");
    }

    private static String filterCount(java.util.List<?> filter) {
        return filter.isEmpty() ? "all" : String.valueOf(filter.size());
    }

    private void rebuildStopButtons() {
        var stops = workOrder.getStops();
        int end = Math.min(scrollOffset + VISIBLE_STOPS, stops.size());

        for (int i = scrollOffset; i < end; i++) {
            final int idx = i;
            RouteStop stop = stops.get(i);
            int by = rowY(i) + (ROW_H - BTN_H) / 2;

            addDrawableChild(ButtonWidget.builder(
                    Text.literal(stop.action.name()),
                    btn -> {
                        RouteStop s = workOrder.getStops().get(idx);
                        StopAction next = switch (s.action) {
                            case COLLECT -> StopAction.DELIVER;
                            case DELIVER -> StopAction.BOTH;
                            case BOTH -> StopAction.COLLECT;
                        };
                        workOrder.getStops().set(idx, new RouteStop(s.pos, s.itemFilter, s.collectFilter,
                                s.itemModes, s.collectModes, next, s.maxAmount));
                        rebuildScreen();
                    })
                .tooltip(Tooltip.of(actionTooltip(stop.action)))
                .dimensions(actionX(), by, ACTION_W, BTN_H).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("Filter"), btn -> {
                if (client != null) client.setScreen(new WorkOrderStopFilterScreen(this, workOrder, idx));
            })
                .tooltip(Tooltip.of(filterTooltip(stop)))
                .dimensions(filterX(), by, FILTER_W, BTN_H).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("✕"), btn -> {
                workOrder.removeStop(idx);
                if (scrollOffset > 0 && scrollOffset + VISIBLE_STOPS > workOrder.getStops().size()) {
                    scrollOffset--;
                }
                rebuildScreen();
            })
                .tooltip(Tooltip.of(Text.literal("Remove this stop from the route.")))
                .dimensions(deleteX(), by, DELETE_W, BTN_H).build());
        }
    }

    private void rebuildScreen() {
        clearChildren();
        init();
    }

    // -----------------------------------------------------------------------
    //  Drag-to-reorder
    // -----------------------------------------------------------------------

    private int rowHandleAt(double mouseX, double mouseY) {
        var stops = workOrder.getStops();
        if (mouseX < left() + 8 || mouseX >= labelRight()) return -1;
        int end = Math.min(scrollOffset + VISIBLE_STOPS, stops.size());
        for (int i = scrollOffset; i < end; i++) {
            int y = rowY(i);
            if (mouseY >= y && mouseY < y + ROW_H) return i;
        }
        return -1;
    }

    private int dropIndexAt(double mouseY) {
        var stops = workOrder.getStops();
        int rel = (int) Math.floor((mouseY - listTop() + ROW_H / 2.0) / ROW_H);
        int insert = scrollOffset + rel;
        if (insert < 0) insert = 0;
        if (insert > stops.size()) insert = stops.size();
        return insert;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0) {
            int idx = rowHandleAt(mouseX, mouseY);
            if (idx >= 0) {
                draggingIndex = idx;
                dragMouseY = (int) mouseY;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (draggingIndex >= 0 && button == 0) {
            dragMouseY = (int) mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingIndex >= 0 && button == 0) {
            var stops = workOrder.getStops();
            int to = dropIndexAt(mouseY);
            RouteStop moved = stops.remove(draggingIndex);
            if (to > draggingIndex) to--;
            if (to < 0) to = 0;
            if (to > stops.size()) to = stops.size();
            stops.add(to, moved);
            draggingIndex = -1;
            rebuildScreen();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        var stops = workOrder.getStops();
        if (stops.size() > VISIBLE_STOPS) {
            if (amount < 0 && scrollOffset + VISIBLE_STOPS < stops.size()) { scrollOffset++; rebuildScreen(); return true; }
            if (amount > 0 && scrollOffset > 0) { scrollOffset--; rebuildScreen(); return true; }
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    // -----------------------------------------------------------------------
    //  Rendering
    // -----------------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        int left = left();
        int top  = top();

        context.fill(left, top, left + PANEL_W, top + PANEL_H, COL_PANEL);
        context.drawBorder(left, top, PANEL_W, PANEL_H, COL_BORDER);

        context.drawCenteredTextWithShadow(textRenderer, "Work Order Editor",
                left + PANEL_W / 2, top + 9, COL_ACCENT);

        context.drawTextWithShadow(textRenderer, "Name:", left + 14, top + 28, COL_MUTED);

        context.fill(left + 8, top + 72, left + PANEL_W - 8, top + 73, COL_DIVIDER);
        context.drawTextWithShadow(textRenderer, "Route Stops", left + 14, top + 76, COL_TEXT);

        var stops = workOrder.getStops();
        int listTop = listTop();

        if (stops.isEmpty()) {
            context.drawTextWithShadow(textRenderer, "No stops yet.", left + 14, listTop + 8, COL_MUTED);
            context.drawTextWithShadow(textRenderer,
                    "Use the Work Order Scroll on chests in the world to add some.",
                    left + 14, listTop + 22, COL_FAINT);
        } else {
            int end = Math.min(scrollOffset + VISIBLE_STOPS, stops.size());
            int hovered = (draggingIndex < 0) ? rowHandleAt(mouseX, mouseY) : -1;
            for (int i = scrollOffset; i < end; i++) {
                if (i == draggingIndex) continue;
                drawStopRow(context, stops.get(i), i, rowY(i), i == hovered, false);
            }

            if (draggingIndex >= 0) {
                int insert = dropIndexAt(dragMouseY);
                int lineY = listTop + (insert - scrollOffset) * ROW_H - 1;
                lineY = Math.max(listTop - 1, Math.min(lineY, listTop + VISIBLE_STOPS * ROW_H));
                context.fill(left + 10, lineY, labelRight(), lineY + 1, COL_ACCENT);
                drawStopRow(context, stops.get(draggingIndex), draggingIndex, dragMouseY - ROW_H / 2, false, true);
            }
        }

        if (stops.size() > VISIBLE_STOPS) {
            context.drawTextWithShadow(textRenderer,
                    (scrollOffset + 1) + "–" + Math.min(scrollOffset + VISIBLE_STOPS, stops.size()) + " / " + stops.size(),
                    left + PANEL_W - 70, top + 76, COL_MUTED);
        }

        context.fill(left + 8, top + PANEL_H - 38, left + PANEL_W - 8, top + PANEL_H - 37, COL_DIVIDER);

        super.render(context, mouseX, mouseY, delta);
        nameField.render(context, mouseX, mouseY, delta);

        if (draggingIndex < 0 && rowHandleAt(mouseX, mouseY) >= 0) {
            context.drawTooltip(textRenderer, Text.literal("Drag to reorder this stop"), mouseX, mouseY);
        }
    }

    private void drawStopRow(DrawContext context, RouteStop stop, int index, int y, boolean hovered, boolean dragging) {
        int left = left();
        int rowLeft = left + 10;
        int rowRight = labelRight();

        // Row background: striping, hover, drag
        int bg = dragging ? 0x55FFD479 : (hovered ? ROW_HOVER : ((index % 2 == 1) ? ROW_ODD : 0));
        if (bg != 0) {
            context.fill(rowLeft, y + 1, left + PANEL_W - 10, y + ROW_H - 1, bg);
        }

        // Drag handle glyph
        context.drawTextWithShadow(textRenderer, "☰", rowLeft + 2, y + 6, dragging ? COL_ACCENT : COL_FAINT);

        boolean filtered;
        String note;
        if (stop.action == StopAction.BOTH) {
            filtered = !stop.itemFilter.isEmpty() || !stop.collectFilter.isEmpty();
            note = filtered ? "  (D:" + filterCount(stop.itemFilter) + " C:" + filterCount(stop.collectFilter) + ")" : "";
        } else {
            filtered = !stop.itemFilter.isEmpty();
            note = filtered ? "  (" + stop.itemFilter.size() + " items)" : "";
        }
        String label = "[" + index + "] " + stop.action.name() + " @ " + stop.pos.toShortString() + note;
        int textLeft = rowLeft + 14;
        String shown = textRenderer.trimToWidth(label, rowRight - textLeft - 4);
        context.drawTextWithShadow(textRenderer, shown, textLeft, y + 6,
                dragging ? COL_ACCENT : (filtered ? COL_FILTER : COL_TEXT));
    }

    @Override
    public boolean shouldPause() { return false; }
}
