# NPClogistics – NPC Logistics (Fabric 1.20.1)

A Minecraft Fabric mod that adds **Logistics Worker NPCs** capable of executing
item-collection and delivery routes across chests and barrels, and **Crafting Tasks**
that direct workers to collect materials, craft items, and deposit the results.

---

## Requirements

| Tool | Version |
|------|---------|
| Minecraft | 1.20.1 |
| Fabric Loader | ≥ 0.15.0 |
| Fabric API | 0.92.6+1.20.1 |
| Java (mod runtime/target) | 17 |
| JDK to **build** | 21 — required to run Gradle / `fabric-loom` 1.13 |

The mod itself compiles to Java 17 bytecode and runs on a Java 17 client/server;
only the Gradle build toolchain needs a JDK 21 present.

---

## Building

```bash
# Gradle must run on a JDK 21 (Loom 1.13 requirement)
JAVA_HOME=/path/to/jdk-21 ./gradlew build
# Output: build/libs/npclogistics-1.0.0.jar
```

Run the dev client with `./gradlew runClient`.

---

## Project Structure

```
src/
├── main/java/com/npclogistics/
│   ├── NPClogistics.java               # Server entrypoint
│   ├── ai/
│   │   └── WorkOrderBrain.java         # Tick-based route execution AI
│   ├── command/
│   │   └── WorkOrderCommand.java       # /workorder admin commands
│   ├── data/
│   │   ├── WorkOrder.java              # Core data: route stops, filters, NBT
│   │   └── CraftingTask.java           # Crafting task data (source/craft/deposit locations)
│   ├── entity/
│   │   ├── LogisticsWorkerEntity.java  # NPC entity (inventory, state machine)
│   │   └── ModEntities.java            # Entity type + attribute registration
│   ├── item/
│   │   ├── WorkOrderScrollItem.java    # In-hand tool for recording routes
│   │   ├── LocationTokenItem.java      # Location token item (Collect / Craft / Deposit)
│   │   └── ModItems.java               # Item registration
│   ├── screen/
│   │   ├── EquipmentScreenHandler.java # Server-side handler for NPC Worker GUI
│   │   └── ModScreenHandlers.java      # Screen handler registration
│   └── network/
│       └── ModNetworking.java          # C2S / S2C packet definitions
│
└── client/java/com/npclogistics/
    ├── NPClogisticsClient.java         # Client entrypoint
    ├── client/network/
    │   └── ClientNetworking.java       # Client packet handlers
    ├── renderer/
    │   └── LogisticsWorkerRenderer.java # Biped renderer for worker NPC
    └── screen/
        ├── EquipmentScreen.java        # NPC Worker GUI (Equipment / Orders / Cargo / Tasks tabs)
        ├── WorkOrderScreen.java        # In-game route editor GUI (drag to reorder)
        └── WorkOrderStopFilterScreen.java # Per-stop item-filter editor
```

---

## Items

### Work Order Scroll

The primary tool for recording and assigning delivery routes. Found in the creative
*Tools & Utilities* tab or via `/give @s npclogistics:work_order_scroll`.

### Location Tokens

Three 3D coin-shaped items used to mark locations for **Crafting Tasks**.
Each token records a block position when right-clicked on a container or workstation.

| Token | Colour | Purpose |
|-------|--------|---------|
| **Collect Token** | Blue | Marks the chest/barrel to collect raw materials from |
| **Craft Token** | Amber | Marks the crafting station (crafting table, furnace, etc.) |
| **Deposit Token** | Green | Marks the chest/barrel to deposit finished items into |

Tokens are obtained from the creative *Tools & Utilities* tab. Right-click a block to
record its position into the token; the tooltip shows the stored coordinates.
Place them in a worker's **Task slots** to define a crafting task route.

---

## Gameplay Usage

### Work Order Scroll (delivery routes)

1. Grab a **Work Order Scroll** from the creative *Tools & Utilities* tab.
2. **Right-click** a chest or barrel → adds a **COLLECT** stop.
3. **Sneak + Right-click** a chest or barrel → adds a **DELIVER** stop.
4. **Right-click in the air** → opens the scroll's route in the **Work Order editor**,
   where you can reorder stops, flip COLLECT/DELIVER, and set per-stop item filters.
5. **Right-click** a Logistics Worker NPC → assigns the order; the NPC starts immediately.
6. **Sneak + Right-click** a worker with an empty hand → takes the order back as a fresh
   scroll, leaving the worker idle.

Each container is recorded once — clicking one already on the scroll updates its action
instead of adding a duplicate. The scroll stores the full route including item filters;
filters edited on the scroll carry over to the worker on assignment. The scroll is
consumed on assignment (in creative it is replaced with a blank scroll).

#### Editing routes in the GUI

Open the editor by right-clicking a worker with an empty hand, or by right-clicking in
the air with a Work Order Scroll. Options:

- **Drag any stop row** up or down to reorder it using the `☰` handle; a yellow line
  shows the insertion point.
- **Click `Filter`** on a stop to open the item-filter editor: search the item grid,
  left-click items to add/remove them (green = included). The **"in filter" strip**
  lists everything in the filter — drag an item out or click it to remove it; scroll
  sideways with the mouse wheel when full. An empty filter accepts every item.
  - **Right-click** an item in the strip to cycle its quantity mode:
    **All**, **Stacks** (full stacks only, badge `S`), or **Partial** (non-full stacks, badge `P`).
- **Click the action button** to cycle a stop through **COLLECT → DELIVER → BOTH**.
  A BOTH stop delivers *then* collects in one visit. BOTH stops keep two independent
  filters; the Filter editor shows a **Deliver / Collect** toggle.
- **Scroll the mouse wheel** to page through long stop lists.

---

### NPC Worker GUI

**Right-click** any Logistics Worker NPC with an empty hand to open the worker's
management screen. The screen has four tabs:

#### Equipment tab

Manage the worker's equipped items and identity.

- **Armour slots** — head, chest, legs, feet.
- **Main hand / Off hand** — weapon or tool the worker carries.
- **Order 1 / Order 2** — Work Order Scroll slots. Drop a scroll here to queue a
  delivery route for the worker.
- **Name & Skin** fields — rename the worker and set a custom player-skin URL
  (employer only). Changes apply immediately via the *Apply* button.
- **Employer** label — shows who claimed the worker. An unclaimed worker can be
  taken by the first player to interact.

#### Orders tab

Displays the work order scrolls currently in the worker's Order slots and summarises
each route (stop count, positions, COLLECT/DELIVER/BOTH actions). Slots are
highlighted in gold if the scroll belongs to another player.

#### Cargo tab

Shows the worker's 18-slot internal inventory (2 rows × 9 slots). Items collected
during delivery runs appear here. Slots are **take-only** — click or shift-click to
retrieve items into your own inventory. You cannot place items into the cargo hold
directly; workers fill it themselves during collection stops.

#### Tasks tab

Set up **Crafting Tasks** — instructions for the worker to gather materials, craft
items, and store the output.

Each of the 8 task rows has four slots:

| Slot | Token | Meaning |
|------|-------|---------|
| **Collect** | Blue | Where to pick up raw materials |
| **Recipe** | — | The item to be crafted (place the output item here) |
| **Craft** | Amber | The crafting station to use |
| **Deposit** | Green | Where to deliver the finished items |

Place Location Tokens (or the recipe item) in the appropriate slots. Use the
**loop / once** toggle button on each row to set whether the task repeats or runs
once and marks itself complete. The **×** button deletes the row entirely.
Hovering over an empty slot shows a colour-coded hint describing what goes there.

---

### Admin Commands

```
# Spawn a worker (omit coords to spawn at your feet)
/workorder spawn
/workorder spawn <x y z>

# Add stops to the worker's current order (auto-creates one if none)
/workorder addstop <worker> <x y z> collect
/workorder addstop <worker> <x y z> deliver
/workorder addstop <worker> <x y z> both

# Start a named order
/workorder startorder <worker> "My Delivery Route"

# Cancel the active order (worker returns home)
/workorder cancel <worker>

# Check current state
/workorder status <worker>

# Toggle repeating mode
/workorder setrepeating <worker> true
/workorder setrepeating <worker> false

# Remove workers (vanilla /kill with entity selector)
/kill @e[type=npclogistics:logistics_worker]
/kill @e[type=npclogistics:logistics_worker,name="Ol Dave"]
```

---

## Work Order – Data Model

```
WorkOrder
 ├── id         (UUID)
 ├── name       (String)
 ├── homePos    (BlockPos)  – where NPC returns when done
 ├── repeating  (boolean)   – if true, restarts after completion
 └── stops[]
      └── RouteStop
           ├── pos           (BlockPos)
           ├── action        (COLLECT | DELIVER | BOTH)
           ├── itemFilter    (List<Item>)        – deliver list; empty = accept all
           ├── collectFilter (List<Item>)        – collect list, BOTH only; empty = accept all
           ├── itemModes     (Map<Item,QtyMode>) – per-item quantity rule (absent = ALL)
           ├── collectModes  (Map<Item,QtyMode>) – per-item quantity rule for collectFilter
           └── maxAmount     (int)               – 0 = no limit

QtyMode = ALL | FULL_STACKS (only full stacks) | PARTIAL (only non-full stacks)
```

---

## Extending the Mod

### Adding item filters via code

```java
WorkOrder order = new WorkOrder("Iron Run", homePos, true);

order.addStop(new RouteStop(
    new BlockPos(10, 64, 20),
    List.of(Items.IRON_INGOT),
    StopAction.COLLECT,
    64
));

order.addStop(new RouteStop(
    new BlockPos(30, 64, 20),
    List.of(Items.IRON_INGOT),
    StopAction.DELIVER,
    0
));

worker.startWorkOrder(order);
```

### Replacing art assets

| Asset | Path | Size |
|-------|------|------|
| Work Order Scroll icon | `textures/item/work_order_scroll.png` | 16×16 |
| Worker skin | `textures/entity/logistics_worker.png` | 64×64 skin format |
| Collect Token icon | `textures/item/location_token_collect.png` | 32×32 |
| Collect Token face | `textures/item/location_token_collect_face.png` | 64×64 |
| Craft Token icon | `textures/item/location_token_craft.png` | 32×32 |
| Craft Token face | `textures/item/location_token_craft_face.png` | 64×64 |
| Deposit Token icon | `textures/item/location_token_deposit.png` | 32×32 |
| Deposit Token face | `textures/item/location_token_deposit_face.png` | 64×64 |

Token models use a 3D coin geometry (`models/item/location_token_base.json`): the
`_face.png` texture is mapped to the front and back of the disc; the `_icon.png` is
used as the flat `layer0` fallback.

---

## Roadmap / TODO

- [x] GUI: per-stop item filter editor (click to add/remove items)
- [x] GUI: drag-to-reorder stops
- [x] Combined COLLECT + DELIVER at a single stop (deliver then collect, two filters)
- [x] NPC Worker GUI (Equipment / Orders / Cargo / Tasks tabs)
- [x] Cargo tab — take-only view of the worker's 18-slot internal inventory
- [x] Location Tokens with 3D coin models (Collect / Craft / Deposit)
- [x] Crafting Task data model and task slot UI
- [x] Worker employer system (claim, rename, skin URL)
- [x] Sounds: chest/barrel open + close sounds on NPC container interaction
- [x] Any `Inventory` block entity accepted as a route stop (chests, barrels, hoppers, droppers, shulker boxes, etc.)
- [ ] CraftingTaskBrain: full navigation + crafting execution (currently stub)
- [ ] Pathfinding: NPC walks on top of storage blocks en route (vanilla pathfinder takes shortest path over solid surfaces; needs custom navigation to route around)
- [ ] Pathfinding: multi-dimension support
- [ ] Sounds: footstep and work-complete sounds
- [ ] Crafting recipe for Work Order Scroll and Location Tokens
- [ ] Visual route overlay (coloured position beams)
- [x] Support for double-chests
- [x] Support for other mod storage blocks via Fabric Transfer API
