package com.npclogistics.data;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a work order assigned to a Logistics NPC.
 * A work order contains an ordered list of RouteStops. Each stop defines:
 *  - A chest/barrel position in the world
 *  - An item filter (which items to pick up OR deliver at that stop)
 *  - The action: COLLECT or DELIVER
 *
 * Workflow:
 *  1. NPC walks to each COLLECT stop in order and picks up filtered items.
 *  2. NPC walks to each DELIVER stop and deposits the matching items.
 *  3. When complete, the NPC returns to its home position and idles.
 */
public class WorkOrder {

    public enum StopAction {
        COLLECT,
        DELIVER,
        /** Deliver first, then collect, in a single visit. Uses two independent filters. */
        BOTH
    }

    /**
     * Per-item quantity rule within a filter. Decides which source slots are eligible to move.
     * A "full stack" is a slot whose count equals the item's max stack size.
     */
    public enum QtyMode {
        ALL("All", "moves every matching item"),
        FULL_STACKS("Stacks", "only moves full stacks (leaves partial slots)"),
        PARTIAL("Partial", "only moves partial, non-full stacks (leaves full stacks)");

        public final String label;
        public final String desc;
        QtyMode(String label, String desc) { this.label = label; this.desc = desc; }

        public QtyMode next() { return values()[(ordinal() + 1) % values().length]; }

        /** Whether a source slot's stack is eligible to be moved under this mode. */
        public boolean allowsSlot(ItemStack stack) {
            return switch (this) {
                case ALL -> true;
                case FULL_STACKS -> stack.getCount() >= stack.getMaxCount();
                case PARTIAL -> stack.getCount() < stack.getMaxCount();
            };
        }
    }

    public static class RouteStop {
        public final BlockPos pos;
        /**
         * For COLLECT and DELIVER stops this is THE filter. For a BOTH stop it is the
         * DELIVER filter (the collect pass then uses {@link #collectFilter}).
         * Empty list = accept all.
         */
        public final List<Item> itemFilter;
        /** Collect filter, consulted only when {@code action == BOTH}. Empty = accept all. */
        public final List<Item> collectFilter;
        /** Per-item quantity mode for {@link #itemFilter}; absent entry = {@link QtyMode#ALL}. */
        public final Map<Item, QtyMode> itemModes;
        /** Per-item quantity mode for {@link #collectFilter}; absent entry = {@link QtyMode#ALL}. */
        public final Map<Item, QtyMode> collectModes;
        public final StopAction action;
        public int maxAmount;                  // 0 = no limit

        /** Single-filter stop (collectFilter starts empty; only meaningful for BOTH stops). */
        public RouteStop(BlockPos pos, List<Item> itemFilter, StopAction action, int maxAmount) {
            this(pos, itemFilter, new ArrayList<>(), action, maxAmount);
        }

        public RouteStop(BlockPos pos, List<Item> itemFilter, List<Item> collectFilter,
                         StopAction action, int maxAmount) {
            this(pos, itemFilter, collectFilter, new HashMap<>(), new HashMap<>(), action, maxAmount);
        }

        public RouteStop(BlockPos pos, List<Item> itemFilter, List<Item> collectFilter,
                         Map<Item, QtyMode> itemModes, Map<Item, QtyMode> collectModes,
                         StopAction action, int maxAmount) {
            this.pos = pos;
            this.itemFilter = new ArrayList<>(itemFilter);
            this.collectFilter = new ArrayList<>(collectFilter);
            this.itemModes = new HashMap<>(itemModes);
            this.collectModes = new HashMap<>(collectModes);
            this.action = action;
            this.maxAmount = maxAmount;
        }

        public boolean doesCollect() { return action == StopAction.COLLECT || action == StopAction.BOTH; }
        public boolean doesDeliver() { return action == StopAction.DELIVER || action == StopAction.BOTH; }

        // Which (list, modes) pair each pass consults.
        private List<Item> collectList()      { return action == StopAction.BOTH ? collectFilter : itemFilter; }
        private Map<Item, QtyMode> collectMod(){ return action == StopAction.BOTH ? collectModes : itemModes; }

        private static QtyMode modeFor(List<Item> list, Map<Item, QtyMode> modes, ItemStack stack) {
            if (list.isEmpty()) return QtyMode.ALL;             // empty filter = accept everything
            if (!list.contains(stack.getItem())) return null;   // not in filter = rejected
            return modes.getOrDefault(stack.getItem(), QtyMode.ALL);
        }

        /** Quantity mode for an item in the collect pass, or null if the item is not accepted. */
        public QtyMode collectMode(ItemStack stack) { return modeFor(collectList(), collectMod(), stack); }
        /** Quantity mode for an item in the deliver pass, or null if the item is not accepted. */
        public QtyMode deliverMode(ItemStack stack) { return modeFor(itemFilter, itemModes, stack); }

        public boolean acceptsForCollect(ItemStack stack) { return collectMode(stack) != null; }
        public boolean acceptsForDeliver(ItemStack stack) { return deliverMode(stack) != null; }

        public NbtCompound toNbt() {
            NbtCompound tag = new NbtCompound();
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.putString("action", action.name());
            tag.putInt("maxAmount", maxAmount);
            tag.put("filter", filterToNbt(itemFilter, itemModes));
            tag.put("collectFilter", filterToNbt(collectFilter, collectModes));
            return tag;
        }

        public static RouteStop fromNbt(NbtCompound tag) {
            BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
            StopAction action = StopAction.valueOf(tag.getString("action"));
            int maxAmount = tag.getInt("maxAmount");
            List<Item> filter = new ArrayList<>();
            Map<Item, QtyMode> filterModes = new HashMap<>();
            filterFromNbt(tag.getList("filter", NbtElement.COMPOUND_TYPE), filter, filterModes);
            List<Item> collectFilter = new ArrayList<>();
            Map<Item, QtyMode> collectModes = new HashMap<>();
            filterFromNbt(tag.getList("collectFilter", NbtElement.COMPOUND_TYPE), collectFilter, collectModes);
            return new RouteStop(pos, filter, collectFilter, filterModes, collectModes, action, maxAmount);
        }

        private static NbtList filterToNbt(List<Item> filter, Map<Item, QtyMode> modes) {
            NbtList list = new NbtList();
            for (Item item : filter) {
                NbtCompound itemTag = new NbtCompound();
                itemTag.putString("id", Registries.ITEM.getId(item).toString());
                itemTag.putString("mode", modes.getOrDefault(item, QtyMode.ALL).name());
                list.add(itemTag);
            }
            return list;
        }

        private static void filterFromNbt(NbtList list, List<Item> outFilter, Map<Item, QtyMode> outModes) {
            for (NbtElement el : list) {
                NbtCompound itemTag = (NbtCompound) el;
                Identifier id = new Identifier(itemTag.getString("id"));
                Registries.ITEM.getOrEmpty(id).ifPresent(item -> {
                    outFilter.add(item);
                    if (itemTag.contains("mode")) {
                        try { outModes.put(item, QtyMode.valueOf(itemTag.getString("mode"))); }
                        catch (IllegalArgumentException ignored) { /* old/unknown → ALL */ }
                    }
                });
            }
        }
    }

    // -----------------------------------------------------------------------

    private final UUID id;
    private String name;
    private final List<RouteStop> stops;
    private boolean repeating;   // if true, NPC loops the route indefinitely
    private BlockPos homePos;    // where the NPC returns after completing the order

    public WorkOrder(String name, BlockPos homePos, boolean repeating) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.homePos = homePos;
        this.repeating = repeating;
        this.stops = new ArrayList<>();
    }

    private WorkOrder(UUID id, String name, BlockPos homePos, boolean repeating, List<RouteStop> stops) {
        this.id = id;
        this.name = name;
        this.homePos = homePos;
        this.repeating = repeating;
        this.stops = stops;
    }

    // --- Accessors ---

    public UUID getId()                 { return id; }
    public String getName()             { return name; }
    public void setName(String name)    { this.name = name; }
    public List<RouteStop> getStops()   { return stops; }
    public boolean isRepeating()        { return repeating; }
    public void setRepeating(boolean r) { repeating = r; }
    public BlockPos getHomePos()        { return homePos; }
    public void setHomePos(BlockPos p)  { homePos = p; }

    public void addStop(RouteStop stop) { stops.add(stop); }
    public void removeStop(int index)   { stops.remove(index); }

    // --- NBT Serialization ---

    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putUuid("id", id);
        tag.putString("name", name);
        tag.putBoolean("repeating", repeating);
        tag.putInt("homeX", homePos.getX());
        tag.putInt("homeY", homePos.getY());
        tag.putInt("homeZ", homePos.getZ());

        NbtList stopList = new NbtList();
        for (RouteStop stop : stops) {
            stopList.add(stop.toNbt());
        }
        tag.put("stops", stopList);
        return tag;
    }

    public static WorkOrder fromNbt(NbtCompound tag) {
        UUID id = tag.getUuid("id");
        String name = tag.getString("name");
        boolean repeating = tag.getBoolean("repeating");
        BlockPos home = new BlockPos(tag.getInt("homeX"), tag.getInt("homeY"), tag.getInt("homeZ"));

        List<RouteStop> stops = new ArrayList<>();
        NbtList stopList = tag.getList("stops", NbtElement.COMPOUND_TYPE);
        for (NbtElement el : stopList) {
            stops.add(RouteStop.fromNbt((NbtCompound) el));
        }
        return new WorkOrder(id, name, home, repeating, stops);
    }
}
