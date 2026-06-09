# CLAUDE.md

Guidance for working in the **NPClogistics** Fabric mod. For player-facing usage see `README.md`; this file is for editing the code.

## Build & run

Loom 1.13 requires Gradle to run on a **JDK 21**, even though the mod compiles to Java 17 bytecode and runs on a Java 17 client/server. On this machine JDK 21 is at `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot`.

```powershell
# Build the jar -> build/libs/npclogistics-1.0.0.jar
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"; .\gradlew.bat build --console=plain

# Launch the dev client (run in background; logs to run/logs/latest.log)
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot"; .\gradlew.bat runClient --console=plain
```

- Target: Minecraft **1.20.1**, Fabric Loader ≥ 0.15, Fabric API 0.92.6+1.20.1 (mappings: yarn 1.20.1+build.10).
- Project is at `C:\projects\NPClogistics` (moved from OneDrive to avoid file-locking issues).
- The dev client's `401 / Failed to verify authentication`, Realms `SignedJWT`, LWJGL JNI-version, and goat-horn missing-sound log lines are all expected offline-dev noise — **not** mod errors.
- This directory is **not a git repo**.

## Architecture

Two source sets — `src/main` (server/common) and `src/client` (client-only, `@Environment(CLIENT)`). Client code must not be referenced from `main`; cross the boundary with packets.

- `NPClogistics` — `ModInitializer`. Registers entities/items/server-packets/commands and the **`UseBlockCallback`** (see gotchas).
- `data/WorkOrder` — core model. `RouteStop` holds `pos`, `action` (`StopAction`: `COLLECT`/`DELIVER`/`BOTH`), `itemFilter`/`collectFilter` (`List<Item>`), parallel `itemModes`/`collectModes` (`Map<Item,QtyMode>`; absent = `ALL`), and `maxAmount`. `QtyMode` = `ALL`/`FULL_STACKS`/`PARTIAL` (per-slot stack-fullness rule via `allowsSlot`). Everything serializes to NBT and is backward-compatible (missing fields default sensibly).
- `entity/LogisticsWorkerEntity` — the NPC. State machine `IDLE`/`EXECUTING`/`RETURNING`. Holds the active `WorkOrder`, an 18-slot inventory, home pos. `interactMob` routes right-clicks (see interaction map). Collect/deliver inventory logic lives here.
- `ai/WorkOrderBrain` — tick-by-tick route execution (navigate → interact → advance). A `BOTH` stop **delivers then collects** on one container snapshot.
- `item/WorkOrderScrollItem` + `item/ModItems` — the Work Order Scroll. Stores a full `WorkOrder` NBT under key **`SCROLL_KEY` ("workOrder")** via `readOrder`/`writeOrder`. `getName` shows the route title in the hotbar.
- `command/WorkOrderCommand` — `/workorder` admin commands.
- `network/ModNetworking` (server: packet IDs, C2S receivers, S2C send helpers) ↔ `client/network/ClientNetworking` (S2C receivers, C2S send helpers).
- `client/screen/WorkOrderScreen` (route editor) + `WorkOrderStopFilterScreen` (per-stop item filter + quantity modes); `renderer/LogisticsWorkerRenderer`.

### Interaction map (worker NPC)
- Right-click, empty hand → open editor for the worker's order.
- Right-click holding a scroll → assign that scroll's route (consumes scroll; see creative gotcha).
- Sneak + right-click, empty hand → take the order back onto a scroll, worker goes `IDLE`.

### Scroll gestures
- Right-click chest/barrel → COLLECT stop; sneak+right-click → DELIVER stop (via `UseBlockCallback`).
- Right-click in air → open the editor on the scroll's route ("Save to Scroll" persists).

## Gotchas / conventions

- **Creative inventory is client-authoritative.** Server-side changes to a player's held stack (NBT mutation *or* `setStackInHand`) get reverted by the creative client. To actually change a held item in creative, push `ScreenHandlerSlotUpdateS2CPacket(-2, 0, slot, stack)` to the player (`-2` = the player's own inventory; slot = `selectedSlot` for main hand, `PlayerInventory.OFF_HAND_SLOT` for off-hand). See `WorkOrderScrollItem.useOnEntity`. In survival, plain `stack.decrement(1)` works.
- **Block right-clicks use `UseBlockCallback`, not `Item#useOnBlock`** — registered in `NPClogistics.onInitialize`. A container's own block interaction runs before `useOnBlock`, so the callback fires first and cancels the vanilla open to record a stop instead.
- **`RouteStop` is mostly immutable; when reconstructing it (e.g. flipping the action), pass `itemModes`/`collectModes` through** the 7-arg constructor or per-item quantity modes are silently lost. There are List-based convenience constructors that default modes to `ALL` — fine for new stops, wrong for edits.
- **Filter semantics:** `itemFilter` is the deliver list (and the sole list for COLLECT/DELIVER stops); `collectFilter` is only consulted when `action == BOTH`. Use `collectMode(stack)`/`deliverMode(stack)` (return the `QtyMode`, or `null` if rejected) rather than checking lists directly.
- **The brain ticks server-side only** (`tick()` early-returns on `world.isClient`). Worker actions are logged via `NPClogistics.LOGGER`.
- **Verifying changes:** server-side actions (route start/complete, collect/deliver counts, order taken back) appear in `run/logs/latest.log` and can be confirmed from logs. **GUI and player-inventory changes do not log** — those must be checked visually in the running client.
