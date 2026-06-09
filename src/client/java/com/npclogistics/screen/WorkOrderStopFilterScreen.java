package com.npclogistics.screen;

import com.npclogistics.data.WorkOrder;
import com.npclogistics.data.WorkOrder.QtyMode;
import com.npclogistics.data.WorkOrder.RouteStop;
import com.npclogistics.data.WorkOrder.StopAction;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-stop item-filter editor (Roadmap #1).
 *
 * Opened from {@link WorkOrderScreen} via the "Filter" button on a stop row. Shows a
 * searchable grid of every registered item; left-clicking an item toggles it in or
 * out of the active filter (highlighted green when included). An empty filter means
 * the stop accepts ALL items, matching {@link RouteStop#acceptsForCollect}/
 * {@link RouteStop#acceptsForDeliver}.
 *
 * For a BOTH stop there are two lists — a deliver list and a collect list — and a toggle
 * button switches which one this screen is editing. COLLECT/DELIVER stops have a single
 * list ({@link RouteStop#itemFilter}).
 *
 * The grid keeps the chrome minimal and leans on a friendly hover tooltip per item to
 * tell the player exactly what a click will do. Below the grid, an "in filter" strip
 * shows the items currently in the active list; drag one out of the strip (or click it)
 * to remove it. The strip scrolls horizontally with the mouse wheel when it overflows.
 *
 * The active filter list is mutated in place, so the change is already part of the
 * WorkOrder when the parent screen sends it to the server.
 */
@Environment(EnvType.CLIENT)
public class WorkOrderStopFilterScreen extends Screen {

    private static final int PANEL_W = 200;
    private static final int PANEL_H = 252;
    private static final int COLS = 9;
    private static final int CELL = 18;
    private static final int VISIBLE_ROWS = 7;
    private static final int STRIP_COLS = 9;   // slots shown in the "in filter" row

    private static final int COL_TEXT     = 0xFFE8E8E8;
    private static final int COL_MUTED    = 0xFF9A9A9A;
    private static final int COL_ACCENT   = 0xFFFFD479;
    private static final int COL_STRIP_BG = 0xFF181C20;
    private static final int COL_SLOT_BG  = 0xFF22262B;

    private final Screen parent;
    private final WorkOrder workOrder;
    private final int stopIndex;
    private final RouteStop stop;

    private TextFieldWidget searchField;
    private final List<Item> matches = new ArrayList<>();
    private int scrollRow = 0;

    /** Horizontal scroll offset of the "in filter" strip. */
    private int stripScroll = 0;

    /** Item currently being dragged out of the strip to remove it, or null. */
    private Item draggingFilterItem = null;
    private int dragX, dragY;

    /** For BOTH stops: false = editing the deliver list, true = editing the collect list. */
    private boolean editingCollect = false;

    public WorkOrderStopFilterScreen(Screen parent, WorkOrder workOrder, int stopIndex) {
        super(Text.literal("Edit Item Filter"));
        this.parent = parent;
        this.workOrder = workOrder;
        this.stopIndex = stopIndex;
        this.stop = workOrder.getStops().get(stopIndex);
    }

    /** The filter list this screen is currently editing. */
    private List<Item> activeFilter() {
        if (stop.action == StopAction.BOTH) {
            return editingCollect ? stop.collectFilter : stop.itemFilter;
        }
        return stop.itemFilter;
    }

    /** Per-item quantity modes for the list this screen is currently editing. */
    private Map<Item, QtyMode> activeModes() {
        if (stop.action == StopAction.BOTH) {
            return editingCollect ? stop.collectModes : stop.itemModes;
        }
        return stop.itemModes;
    }

    private QtyMode modeOf(Item item) { return activeModes().getOrDefault(item, QtyMode.ALL); }

    private static String badge(QtyMode m) {
        return switch (m) { case ALL -> ""; case FULL_STACKS -> "S"; case PARTIAL -> "P"; };
    }

    private static int badgeColor(QtyMode m) {
        return switch (m) { case ALL -> 0; case FULL_STACKS -> 0xFFFFE08A; case PARTIAL -> 0xFF8AD8FF; };
    }

    /** Draws the quantity-mode badge in a slot's corner (nothing for ALL). */
    private void drawBadge(DrawContext ctx, Item item, int x, int y) {
        QtyMode m = modeOf(item);
        if (m != QtyMode.ALL) {
            ctx.drawTextWithShadow(textRenderer, badge(m), x + 11, y + 9, badgeColor(m));
        }
    }

    /** Removes an item from the active filter, keeping its mode map in sync. */
    private void removeFromFilter(Item item) {
        activeFilter().remove(item);
        activeModes().remove(item);
    }

    @Override
    protected void init() {
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;

        boolean both = stop.action == StopAction.BOTH;
        int searchW = both ? PANEL_W - 16 - 60 : PANEL_W - 16;

        searchField = new TextFieldWidget(textRenderer, left + 8, top + 24, searchW, 16, Text.literal("Search items"));
        searchField.setPlaceholder(Text.literal("Search items…").formatted(Formatting.DARK_GRAY));
        searchField.setChangedListener(s -> { scrollRow = 0; updateMatches(); });
        searchField.setTooltip(Tooltip.of(Text.literal("Type a name or id, e.g. \"iron\" or \"minecraft:cobblestone\".")));
        addDrawableChild(searchField);
        setInitialFocus(searchField);

        if (both) {
            addDrawableChild(ButtonWidget.builder(
                    Text.literal(editingCollect ? "Collect" : "Deliver"),
                    b -> {
                        editingCollect = !editingCollect;
                        stripScroll = 0;
                        b.setMessage(Text.literal(editingCollect ? "Collect" : "Deliver"));
                    })
                    .tooltip(Tooltip.of(Text.literal("Which list you're editing.\n"
                            + "Deliver = items dropped off here.\nCollect = items picked up here.\nClick to switch.")))
                    .dimensions(left + 8 + searchW + 4, top + 24, PANEL_W - 16 - searchW - 4, 18).build());
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Clear"),
                b -> { activeFilter().clear(); activeModes().clear(); })
                .tooltip(Tooltip.of(Text.literal("Remove every item from this list — it will accept anything.")))
                .dimensions(left + 8, top + PANEL_H - 26, 60, 18).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .tooltip(Tooltip.of(Text.literal("Back to the route editor. Choices are saved.")))
                .dimensions(left + PANEL_W - 8 - 70, top + PANEL_H - 26, 70, 18).build());

        updateMatches();
    }

    private void updateMatches() {
        matches.clear();
        String q = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) continue;
            if (q.isEmpty()) { matches.add(item); continue; }
            Identifier id = Registries.ITEM.getId(item);
            if (id.toString().contains(q)) { matches.add(item); continue; }
            String name = new ItemStack(item).getName().getString().toLowerCase(Locale.ROOT);
            if (name.contains(q)) matches.add(item);
        }
    }

    private int maxScrollRow() {
        int totalRows = (matches.size() + COLS - 1) / COLS;
        return Math.max(0, totalRows - VISIBLE_ROWS);
    }

    private int gridLeft() { return (width - PANEL_W) / 2 + 8; }
    private int gridTop()  { return (height - PANEL_H) / 2 + 48; }

    private int stripLeft() { return gridLeft(); }
    private int stripTop()  { return (height - PANEL_H) / 2 + 190; }
    private int maxStripScroll() { return Math.max(0, activeFilter().size() - STRIP_COLS); }

    /** Item under the cursor in the grid, or null. */
    private Item itemAt(double mx, double my) {
        int gl = gridLeft(), gt = gridTop();
        if (mx < gl || mx >= gl + COLS * CELL) return null;
        if (my < gt || my >= gt + VISIBLE_ROWS * CELL) return null;
        int col = (int) ((mx - gl) / CELL);
        int row = (int) ((my - gt) / CELL);
        int index = (scrollRow + row) * COLS + col;
        if (index < 0 || index >= matches.size()) return null;
        return matches.get(index);
    }

    /** True if the cursor is over the "in filter" strip region. */
    private boolean overStrip(double mx, double my) {
        int sl = stripLeft(), st = stripTop();
        return mx >= sl && mx < sl + STRIP_COLS * CELL && my >= st && my < st + CELL;
    }

    /** Filter item under the cursor in the strip, or null. */
    private Item stripItemAt(double mx, double my) {
        if (!overStrip(mx, my)) return null;
        List<Item> filter = activeFilter();
        int col = (int) ((mx - stripLeft()) / CELL);
        int index = stripScroll + col;
        if (index < 0 || index >= filter.size()) return null;
        return filter.get(index);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // Right-click an item that's in the filter (strip or grid) to cycle its quantity mode.
        if (button == 1) {
            Item target = stripItemAt(mouseX, mouseY);
            if (target == null) {
                Item g = itemAt(mouseX, mouseY);
                if (g != null && activeFilter().contains(g)) target = g;
            }
            if (target != null) {
                activeModes().put(target, modeOf(target).next());
                return true;
            }
        }

        if (button == 0) {
            // Press on a strip item begins a drag-out; releasing removes it (see mouseReleased).
            Item stripped = stripItemAt(mouseX, mouseY);
            if (stripped != null) {
                draggingFilterItem = stripped;
                dragX = (int) mouseX;
                dragY = (int) mouseY;
                return true;
            }
            // Click in the grid toggles the item in/out of the filter.
            Item clicked = itemAt(mouseX, mouseY);
            if (clicked != null) {
                if (activeFilter().contains(clicked)) {
                    removeFromFilter(clicked);
                } else {
                    activeFilter().add(clicked);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (draggingFilterItem != null && button == 0) {
            dragX = (int) mouseX;
            dragY = (int) mouseY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingFilterItem != null && button == 0) {
            // Drag the item out of the strip (or just click it) to remove it from the filter.
            removeFromFilter(draggingFilterItem);
            draggingFilterItem = null;
            if (stripScroll > maxStripScroll()) stripScroll = maxStripScroll();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (overStrip(mouseX, mouseY)) {
            if (amount < 0 && stripScroll < maxStripScroll()) stripScroll++;
            else if (amount > 0 && stripScroll > 0) stripScroll--;
            return true;
        }
        if (amount < 0 && scrollRow < maxScrollRow()) scrollRow++;
        else if (amount > 0 && scrollRow > 0) scrollRow--;
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        int left = (width - PANEL_W) / 2;
        int top = (height - PANEL_H) / 2;

        ctx.fill(left, top, left + PANEL_W, top + PANEL_H, 0xE6101317);
        ctx.drawBorder(left, top, PANEL_W, PANEL_H, 0xFF3C4450);

        String listLabel = stop.action == StopAction.BOTH ? (editingCollect ? " · Collect list" : " · Deliver list") : "";
        ctx.drawCenteredTextWithShadow(textRenderer,
                "Item Filter — stop [" + stopIndex + "] " + stop.action.name() + listLabel,
                left + PANEL_W / 2, top + 8, COL_ACCENT);

        // Search field + Clear/Done buttons are drawable children.
        super.render(ctx, mouseX, mouseY, delta);

        List<Item> filter = activeFilter();

        // Item grid
        int gl = gridLeft(), gt = gridTop();
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int index = (scrollRow + row) * COLS + col;
                if (index >= matches.size()) continue;
                Item item = matches.get(index);
                int x = gl + col * CELL;
                int y = gt + row * CELL;
                boolean picked = filter.contains(item);
                ctx.fill(x, y, x + 16, y + 16, picked ? 0xFF2E7D32 : 0xFF22262B);
                if (picked) ctx.drawBorder(x - 1, y - 1, 18, 18, 0xFF7BE06B);
                ctx.drawItem(new ItemStack(item), x, y);
                if (picked) drawBadge(ctx, item, x, y);
            }
        }

        // --- "In filter" strip: the items currently in the active list ---
        if (stripScroll > maxStripScroll()) stripScroll = maxStripScroll();
        int sl = stripLeft(), st = stripTop();

        ctx.drawTextWithShadow(textRenderer,
                filter.isEmpty()
                        ? "In filter: none → accepts every item"
                        : "In filter: " + filter.size() + " — drag=remove · right-click=qty",
                left + 8, st - 12, COL_MUTED);

        ctx.fill(sl - 1, st - 1, sl + STRIP_COLS * CELL + 1, st + CELL + 1, COL_STRIP_BG);
        for (int col = 0; col < STRIP_COLS; col++) {
            int x = sl + col * CELL;
            ctx.fill(x, st, x + 16, st + 16, COL_SLOT_BG);
            int index = stripScroll + col;
            if (index < filter.size()) {
                Item item = filter.get(index);
                if (item == draggingFilterItem) continue;   // hidden in its slot while dragging
                ctx.drawItem(new ItemStack(item), x, st);
                drawBadge(ctx, item, x, st);
            }
        }
        if (stripScroll > 0)               ctx.drawTextWithShadow(textRenderer, "◂", sl - 7, st + 4, COL_ACCENT);
        if (stripScroll < maxStripScroll()) ctx.drawTextWithShadow(textRenderer, "▸", sl + STRIP_COLS * CELL + 1, st + 4, COL_ACCENT);

        // --- hover tooltips (suppressed while dragging) ---
        if (draggingFilterItem == null) {
            Item gridHover = itemAt(mouseX, mouseY);
            Item stripHover = stripItemAt(mouseX, mouseY);
            if (gridHover != null) {
                List<Text> lines = new ArrayList<>();
                lines.add(new ItemStack(gridHover).getName());
                if (filter.contains(gridHover)) {
                    QtyMode m = modeOf(gridHover);
                    lines.add(Text.literal("✓ In filter · qty: " + m.label + " — click to remove").formatted(Formatting.GREEN));
                    lines.add(Text.literal("Right-click: change quantity").formatted(Formatting.DARK_GRAY));
                } else {
                    lines.add(Text.literal("Click to add to filter").formatted(Formatting.GRAY));
                }
                ctx.drawTooltip(textRenderer, lines, mouseX, mouseY);
            } else if (stripHover != null) {
                QtyMode m = modeOf(stripHover);
                ctx.drawTooltip(textRenderer, List.of(
                        new ItemStack(stripHover).getName(),
                        Text.literal("Quantity: " + m.label).formatted(Formatting.AQUA),
                        Text.literal(m.desc).formatted(Formatting.GRAY),
                        Text.literal("Right-click: change quantity").formatted(Formatting.DARK_GRAY),
                        Text.literal("Drag out or click: remove").formatted(Formatting.DARK_GRAY)),
                        mouseX, mouseY);
            }
        }

        // --- drag ghost following the cursor ---
        if (draggingFilterItem != null) {
            ctx.drawItem(new ItemStack(draggingFilterItem), dragX - 8, dragY - 8);
        }
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
