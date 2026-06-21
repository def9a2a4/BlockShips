# Ship Stats System — Remaining Fixes

## UI Math Issues

### 1. Hardcoded sail cap ratio in stats lore
**File:** `ShipWheelMenu.java:525, 556`

`sailCapPoints` and `cappedSailPower` use hardcoded `0.8f * info.mass` instead of `info.sailCapRatio * info.mass`. The speed% calculation (line 564) was already fixed to use `info.sailCapRatio`, but these two were missed.

**Impact:** If the sail cap ratio is changed in config, the lore will show wrong cap points and wrong effective power, but actual physics will use the config value correctly.

### 2. Density calculation mismatch between menu and physics
**File:** `ShipWheelMenu.java:362`

```java
float density = blockCount > 0 ? (float) totalWeight / blockCount : 0;
```

`blockCount` here is `getLastDetectedBlockCount()` which is `shipBlocks.size()` — the **total number of blocks** including null-weight blocks. But `ShipModel.getDensity()` uses `blockCount` which is `weightedBlockCount` — only blocks that have a weight value in blocks.yml.

The assembly path (ShipWheelManager:270) passes `model.parts.size()` (all blocks) as blockCount, while the model's internal `blockCount` is `weightedBlockCount`.

**Impact:** Density shown in the menu is lower than the density used for actual buoyancy/airship detection, because it divides by a larger number. This means the menu might show a density that looks like a water ship, but the actual physics treats it as an airship.

**Fix:** Store `weightedBlockCount` separately in ShipWheelData, or compute density from the ShipModel directly for assembled ships.

---

## Engine Fuel GUI Issues

### 3. Pre-assembly engine GUI reads wrong furnace slots
**File:** `EngineMenuGUI.java:270-273`

```java
org.bukkit.inventory.Inventory blockInv = container.getSnapshotInventory();
for (int i = 0; i < FUEL_SLOTS.length && i < blockInv.getSize(); i++) {
    gui.setItem(FUEL_SLOTS[i], blockInv.getItem(i));
}
```

Blast furnace container has 3 slots: [0]=smelt input, [1]=fuel, [2]=output. The code loads ALL three into the engine GUI's fuel slots. Items in the input/output slots from vanilla mechanics or hoppers would incorrectly appear as "fuel."

**Fix:** Only read from the blast furnace's fuel slot (index 1), or use all 3 container slots exclusively for engine fuel (acceptable since vanilla smelting is suppressed on engine blocks). If using all 3, this should be documented.

### 4. saveBlockFuelState clears entire furnace container
**File:** `EngineMenuGUI.java:302`

`blockInv.clear()` wipes all 3 slots of the blast furnace before writing fuel back. If a hopper loaded items into the vanilla fuel slot while the GUI was open, those items are lost.

**Fix:** Only clear/write the slots we actually use, or accept this behavior since smelting is suppressed and the custom GUI is the intended interface.

### 5. Engine GUI status doesn't update live
**File:** `EngineMenuGUI.java:112-165`

The status item (Running/Ready/Idle) is set once when the GUI opens and never updates. If the ship is sailing and fuel is burning while the GUI is open, the status item doesn't reflect changes until the GUI is reopened.

**Impact:** Minor UX issue — the status is stale while the GUI is open.

---

## Naming Inconsistencies

### 6. `lastDetectedPositiveWeight` not renamed to match `mass`
**File:** `ShipWheelData.java:42, 137, 144`

`ShipModel.totalPositiveWeight` was renamed to `mass`, but `ShipWheelData.lastDetectedPositiveWeight` and its getter `getLastDetectedPositiveWeight()` still use the old name. Functionally correct, but inconsistent.

### 7. `blockCount` means different things in different contexts
- `ShipModel.blockCount` = weighted block count (blocks with non-null weight)
- `ShipWheelData.lastDetectedBlockCount` = total block count (all blocks)
- `model.parts.size()` = total block count

This caused bug #2 above and is a source of confusion.

---

## Edge Cases

### 8. Pre-assembly fuel detection shows 0 for all engines
**File:** `ShipWheelMenu.java:411`, `ShipWheelManager.java` (detection)

For unassembled ships, `fueledEngines = 0` is hardcoded. The blast furnace containers might have fuel loaded via hoppers or the custom placed-block GUI, but the code doesn't check.

**Impact:** Misleading — player loads fuel via hoppers or the placed-engine GUI, but ship info still says all engines are unfueled until assembly.

**Fix (planned):** During detection in `ShipWheelManager.detectShip()`, for each engine blast furnace, check its container inventory for fuel items. Count engines with fuel and pass `fueledEngines` to `setLastDetectedStats()` (needs a new parameter). `getShipInfo()` unassembled path should use the detected fueled count instead of hardcoded 0.

### 9. Stale fuel entries not cleared on disassembly
**File:** `ShipWheelData.java`

`engineFuelSlots` and `engineBurnTicks` maps are never cleared when a ship is disassembled. If the player rebuilds with different engine block indices, old entries for old indices remain in the maps.

**Impact:** Mostly harmless (old indices won't match new engine indices), but wastes memory and could cause confusion if block indices happen to overlap.

---

## Chat / Lore Mismatch

### 18. Detection chat messages don't match stats lore
**File:** `ShipWheelManager.java:770-796`

Stats banner lore shows: wool/banner breakdown, sail power with cap, fueled/unfueled engines, mass, effective power, power ratio, speed%. Chat detection messages show: sails (using "power" not "pts"), engines (always unfueled), speed% — but no mass, no effective power, no power ratio. Terminology inconsistent ("power" vs "pts").

### 19. Assembled ship detect produces no chat output
**File:** `ShipWheelManager.java:674-689`

Clicking Detect on an assembled ship updates `lastDetected*` fields and returns `true` silently. No chat messages sent. User gets no feedback that detection ran.

**Fix:** Add chat messages matching the lore format for the assembled path, including live fuel state.

---

## Engine Fuel Behavior Issues

### 10. Status item doesn't refresh when clicked
**File:** `DisplayShip.java` `onEngineMenuClick`, `EngineMenuGUI.java`

The status slot (Running/Ready/Idle) click is cancelled as a non-fuel slot. Should detect STATUS_SLOT click, save current fuel state from GUI to wheelData, regenerate the status item, and update it in the inventory. All status lore variants should include "Click to refresh" hint.

### 11. Fuel only burns when W (forward) is held
**File:** `ShipPhysics.java:204-208`

`tickEngineFuel()` is guarded by `ship.isForwardPressed` only. Turning (A/D), reversing (S), ascending (Space), and descending (Sprint) don't consume fuel. Fuel should burn whenever any movement input is active.

**Fix:** Change guard to `ship.isForwardPressed || ship.isBackwardPressed || ship.isLeftPressed || ship.isRightPressed || ship.isSpacePressed || ship.isSprintPressed`.

### 12. Smoke appears even when ship is stationary
**File:** `ShipInstance.java` `spawnEngineSmoke()`, `ShipPhysics.java`

Smoke checks `burnTicks > 0` which persists even when parked. Should only emit smoke when fuel is actively being consumed (i.e. movement keys are held). Add a `fuelBurningThisTick` flag on ShipPhysics, set in `tickEngineFuel()`, checked in `spawnEngineSmoke()`.

### 13. Fuel burn times not configurable
**File:** `EngineMenuGUI.java:148-170`

`VALID_FUELS` set and `getBurnTime()` switch are hardcoded. Server admins can't add/remove fuels or tweak burn times without recompiling. Consider moving to config.yml or at minimum using Bukkit's fuel API if available.

### 14. Hopper fuel loading only reaches furnace slot 1
**File:** `EngineMenuGUI.java` (pre-assembly GUI)

Vanilla hoppers push items into the blast furnace's fuel slot (container index 1). But the engine GUI treats all 3 container slots as fuel. Items loaded by hoppers only go into slot 1 — slots 0 and 2 are unreachable by hoppers. This means only 1 of the 3 fuel slots can be hopper-fed pre-assembly.

**Decision needed:** Either accept this (1 hopper-loadable slot + 2 manual slots), or use only the fuel slot for hopper compat and use the other 2 slots for something else (or remove them).

### 15. GUI fuel slots should be {0,1,2} not {1,2,3}
**File:** `EngineMenuGUI.java`

Current `FUEL_SLOTS = {1, 2, 3}`, `STATUS_SLOT = 5`. Changing to `{0, 1, 2}` and `STATUS_SLOT = 4`:
- Simplifies `openForBlock`/`saveBlockFuelState` — blast furnace container slots 0,1,2 map directly to GUI slots
- Cleaner layout: fuel leftmost, status in middle, filler on right
- Only fill slots 3-8 with glass panes (skip fuel slots 0-2)

### 16. Pre-assembly fuel not transferred to wheelData on assembly
**File:** `ShipWheelManager.java` (assembly path, ~line 266)

When a ship is assembled, blast furnace container inventories are serialized into `rawYaml.container_items` by BlockStructureScanner. But `wheelData.engineFuelSlots` is never populated from these containers. Fuel loaded pre-assembly (via hoppers or the placed-engine GUI) is trapped in rawYaml and inaccessible until disassembly.

**Impact:** Player loads fuel into engines before assembly, assembles ship, opens engine GUI — fuel slots are empty. Fuel reappears only after disassembly.

**Fix:** During assembly (after `ship.wheelData = wheelData`), iterate `model.engineBlockIndices`, read each engine's container inventory from the scanned model parts' rawYaml `container_items`, and populate `wheelData.engineFuelSlots` with those items.

### 17. Engine block destroyed by explosion drops vanilla blast furnace
**File:** `DisplayShip.java`

`onBreakShipEngine` only handles `BlockBreakEvent` (player-initiated). Explosions (`EntityExplodeEvent`, `BlockExplodeEvent`) bypass this handler and drop a vanilla blast furnace, losing the PDC tag and glint.

**Fix:** Add `EntityExplodeEvent`/`BlockExplodeEvent` handlers that check for engine blocks in the exploded block list, remove them, and drop the custom engine item instead.

### 18. Number-key hotbar swap bypasses fuel validation
**File:** `DisplayShip.java` `onEngineMenuClick`

The click handler validates cursor placement and shift-clicks, but pressing number keys (1-9) while hovering a fuel slot swaps the hotbar item in without any fuel check. Double-click collect could also pull non-fuel items.

**Fix:** Check `event.getAction()` for `HOTBAR_SWAP` / `HOTBAR_MOVE_AND_READD` and validate the hotbar item. For double-click, cancel `COLLECT_TO_CURSOR` actions when the top inventory is an engine GUI.

### 18b. InventoryDragEvent not handled for engine GUI
**File:** `DisplayShip.java`

`InventoryDragEvent` is a separate Bukkit event from `InventoryClickEvent`. Click-dragging to distribute items across multiple slots is not intercepted at all. A player can drag any item across fuel slots unchecked.

**Fix:** Add an `InventoryDragEvent` handler that checks if any of the dragged-to slots are in an engine GUI. If the dragged item is not valid fuel, cancel. If any non-fuel slots are in the drag set, cancel.

### 19. Stats not recomputed when fuel is added via GUI while stationary
**File:** `ShipPhysics.java`

`computeEffectiveStats()` is called at construction and when `tickEngineFuel()` detects a fuel change. But `tickEngineFuel()` only runs while W is held. If a player adds fuel via the GUI while the ship is parked, the effective stats aren't recomputed until they start moving. The ship appears to still have the old (lower) stats until the first fuel tick fires.

**Fix:** Call `computeEffectiveStats()` when the engine GUI closes (after `saveFuelState`), or add a dirty flag checked each tick.

### 20. computeEffectiveStats() runs before wheelData is linked on construction/recovery
**File:** `ShipPhysics.java:56`

`computeEffectiveStats()` is called in the ShipPhysics constructor, which runs inside the ShipInstance constructor — before `ship.wheelData` is set (ShipWheelManager:267). So the first stat computation always sees 0 fueled engines. After server restart, a ship with fuel and active burnTicks acts as unfueled until the player presses W (triggering `tickEngineFuel` → fuel change → recompute).

Related to #19 but different trigger — #19 is about GUI close, this is about construction/recovery timing.

**Fix:** Defer initial `computeEffectiveStats()` to first `update()` tick, or call it again after wheelData is linked.

### 21. `engineLocalPositions` is dead data
**Files:** `ShipModel.java:57`, `BlockStructureScanner.java:320,396`

`engineLocalPositions` (List<Vector3f>) is populated during scan, serialized to YAML, and deserialized on load — but never read. The smoke particle code was rewritten to use collision shulker positions instead. This field, its constructor parameter, and its serialization/deserialization are all dead weight.

**Fix:** Remove `engineLocalPositions` from ShipModel, BlockStructureScanner, and the YAML serialization. Keep `engineBlockIndices` (still used for click detection and fuel tracking).

### 22. Fuel loaded while assembled lost on disassembly
**File:** `ShipWheelManager.java:375-398` (disassembly path)

On disassembly, the code syncs `ship.storages` (chest/hopper inventories) back to `rawYaml.container_items` before placing blocks. But engine fuel stored in `wheelData.engineFuelSlots` is NOT synced back to the engine's container slots. The engine block is placed with whatever was in rawYaml from the original scan, losing any fuel loaded via the engine GUI while assembled.

This is the reverse of #16:
- **#16:** fuel loaded pre-assembly → lost on assembly (container → wheelData gap)
- **#22:** fuel loaded while assembled → lost on disassembly (wheelData → container gap)

**Fix:** Before `placeBlocks()`, iterate `model.engineBlockIndices`, read fuel from `wheelData.engineFuelSlots`, and write it into the engine part's `rawYaml.container_items`.

### 23. Lava bucket consumed without returning empty bucket
**File:** `ShipPhysics.java:150-152`

When a lava bucket is burned as fuel, its amount is decremented to 0 and the slot is set to null. Vanilla furnaces return an empty bucket. Players lose their bucket.

**Fix:** After decrementing, if the consumed item was `LAVA_BUCKET`, set the slot to `new ItemStack(Material.BUCKET)` instead of null.

### 24. Fuel ItemStack deserialization not crash-safe
**File:** `ShipWheelData.java` — `fromMap()` engine fuel deserialization

`ItemStack.deserializeBytes()` is called without a try-catch. If the serialized data is corrupted or references a removed material (e.g., after a Minecraft version upgrade), this throws an exception and crashes the entire wheel data load, potentially losing all wheel data for that world.

**Fix:** Wrap in try-catch per item, log a warning, and skip corrupted entries.

### 25. Wool and banner power points hardcoded, not configurable
**Files:** `ShipModel.java`, `ShipWheelMenu.java`, `ShipWheelManager.java`

`base-power` (2) and `engine-power` (30) are configurable in `config.yml`, but wool power (3) and banner power (7) are hardcoded as `woolCount * 3 + bannerCount * 7` in at least three places: ShipModel constructor, ShipWheelMenu.getShipInfo(), and ShipWheelManager detection chat.

**Fix:** Add `wool-power: 3` and `banner-power: 7` to `config.yml` under `custom-ships.stats`. Load into ShipConfig. Pass to ShipModel or compute sailPower from config values everywhere.

### 26. minMovementThreshold zeros speed while player is pressing W
**File:** `ShipPhysics.java:241`

The threshold check runs unconditionally, even while the player is pressing W/S. We bumped `floorAcceleration` to 0.015 (above the 0.01 threshold) as a workaround, but if someone configures a lower floor they'd hit the same bug — acceleration gets zeroed every tick.

**Fix:** Skip the threshold zero when `ship.isForwardPressed || ship.isBackwardPressed`.

### 27. activeDeceleration and rotationDeceleration not scaled by ratio
**File:** `ShipPhysics.java:215, 290, 295`

`activeDeceleration` (braking) and `rotationDeceleration` (rotation decay) use raw config values, not ratio-scaled effective values. A slow heavy ship brakes at the same rate as a fast light one.

**Impact:** May be intentional (consistent braking), but inconsistent with acceleration/speed scaling. Low priority.
