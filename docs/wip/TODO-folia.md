# Folia Compatibility: Comprehensive Analysis

## Context
Investigating what it would take to make BlockShips work on Folia. Free-standing carriers are a hard constraint (shulker collision snapping requires them). This is a feasibility/pain-point analysis.

## Assumptions
- Free-standing carriers cannot be eliminated
- Ships almost always fit in one region (default 16×16 chunks = 256×256 blocks)
- Cross-region split is a rare edge case → bail-out via force-disassemble
- Folia regions are dynamic and merge when adjacent; a moving ship's chunks stay in one region as long as they're loaded

---

## Pain Points (ranked by severity)

### 1. Teleportation Model — HARD (possibly a blocker)

**Current code:** `TeleportCompat.teleport(cb.carrier, loc)` called synchronously every tick for every carrier.

**Folia concern:** Research suggests `entity.teleport()` may not work on Folia and `teleportAsync()` (returns `CompletableFuture`) must be used instead. **This needs verification** — synchronous teleport may still work for same-region entities on the correct thread. If it doesn't, the entire per-tick physics loop becomes async, which is a fundamental redesign:
- Can't teleport 50 carriers per tick and wait for 50 futures
- Physics becomes non-deterministic (futures complete at different times)
- Passenger relationships break if carrier moves before shulker follows

**If synchronous same-region teleport works:** This is a non-issue for the normal case. Only cross-region teleport (the bail-out case) needs `teleportAsync()`.

**If it doesn't:** This is a blocker that requires rethinking the entire movement system.

**Verdict: Must be tested empirically before committing to Folia support.**

### 2. The Per-Ship Tick Loop — HARD

`ShipInstance.java:810-1026`

**Migration:** Replace `BukkitRunnable.runTaskTimer(..., 1L)` with `vehicle.getScheduler().runAtFixedRate()`. The entity scheduler **follows the entity across regions** — good, the task migrates automatically.

**Per-tick operations that need the correct thread:**
- 25-50 `getBlock()` calls for terrain collision/buoyancy (must be in owned region)
- Carrier teleportation (see above)
- Passenger relationship checks (`getPassengers()`, `addPassenger()`)
- Velocity setting on carriers

**Edge case:** If the vehicle moves to a new region mid-tick, the entity scheduler handles migration, but any block/entity access from the previous tick's thread context would fail. Need to verify Folia's guarantees here — does the task always run fully within one region's tick?

### 3. Force-Disassemble Bail-Out (cross-region edge case) — MODERATE

When `!Bukkit.isOwnedByCurrentRegion(carrier)` is detected for any carrier:

**What works:**
- `placeBlocks()` only reads model data, not entities — safe to split across regions via `regionScheduler.execute()`
- Block placement is already force-mode (skip hard conflicts, destroy fragile blocks)
- Persistence cleanup is file-based, doesn't need entities
- Players are auto-ejected when shulker is `remove()`d
- Entity removal via `entity.getScheduler().run(() -> entity.remove())` for foreign-region entities

**What breaks:**
- **Inventory sync** reads live shulker inventories (`inv.getItem(slot)`) — fails if shulker is in another region. Fallback: use last-saved inventory from periodic save task (up to 60 seconds stale).
- **Lead transfer** uses `getNearbyEntities(shulker.getLocation(), 10, 10, 10)` — fails if shulker is in another region. Fallback: leashed animals are lost (rare edge case on a rare edge case).
- **`notifyRidersOfDestruction()`** accesses seat shulkers — fails silently if in another region. Players still get ejected, just without a message.

**For the scheduled cross-region block placement + inventory restoration:**
- Pass serialized inventory bytes into the `regionScheduler` lambda
- Place block, then populate inventory in same scheduled task
- Acceptable that it completes ~1 tick after the local-region placement

### 4. Chunk Load/Unload Recovery — MODERATE

`DisplayShip.java:375-551`

**Current:** Async file I/O → `Bukkit.getScheduler().runTask()` for entity recovery.

**Folia fix:** Replace `runTask()` with `Bukkit.getRegionScheduler().execute(plugin, chunkLocation, ...)` to recover on the correct region thread.

**Subtlety:** A ship spans multiple chunks. Chunks may load into different regions at different times. The incremental recovery logic (`collectEntitiesFromChunk()`) currently assumes single-threaded orchestration. On Folia, two chunks loading on different region threads could race on the same ship's recovery state.

**Fix:** Guard recovery state with synchronization (the recovery tracking maps in `ShipInstance`), or ensure recovery only triggers from one "anchor" chunk.

### 5. Player Disconnect While on Ship — MODERATE

`DisplayShip.java:1420-1444`

`PlayerQuitEvent`/`PlayerKickEvent` handlers call `dismountPlayer(player)` which:
- `player.getVehicle()` → reads entity state
- `shulker.getScoreboardTags()` → reads entity state
- `inst.freeSeat()` → may teleport vehicle
- `physics.snapToFineGrid()` → accesses world blocks

**Folia concern:** PlayerQuitEvent fires on a connection thread or global thread, NOT necessarily the ship's region thread. Accessing ship entities from the wrong thread → `IllegalStateException`.

**Fix:** Schedule the actual dismount logic on the ship entity's scheduler:
```java
ship.vehicle.getScheduler().run(plugin, task -> {
    inst.freeSeat(...);
    physics.snapToFineGrid();
}, null);
```

### 6. ProtocolLib / Steering Input — MODERATE (solvable)

**Best path:** Replace with `PlayerInputEvent` (Paper 1.21.3+), fires on correct region thread.

**If keeping ProtocolLib:** Schedule all entity reads to player's entity scheduler. Make steering booleans `volatile`. Switch `ShipRegistry` to `ConcurrentHashMap`.

### 7. Static Shared State — LOW (but pervasive)

**ShipRegistry:** Two static `HashMap`s accessed from multiple region threads → `ConcurrentHashMap`.

**ShipWorldData:** Chunk indices accessed during concurrent chunk load events from different region threads → needs synchronization.

**ShipInstance recovery state:** `pendingCarriers`, `pendingShulkers`, `recoveredDisplayIndices` — accessed during incremental recovery from potentially different region threads → needs synchronization or single-anchor recovery.

### 8. All Other Scheduler Usages — LOW

Mechanical migration:
- Periodic save task → `Bukkit.getGlobalRegionScheduler().runAtFixedRate()` (not tied to a region)
- Wheel particle tasks → `Bukkit.getRegionScheduler().runAtFixedRate()` at wheel location
- Player remount delay → `player.getScheduler().runDelayed()`
- Console commands → already run on global thread; player commands run on player's region thread (fine for most cases)

---

## Critical Unknowns (must test before committing)

| Question | Why it matters | How to test |
|----------|---------------|-------------|
| Does synchronous `entity.teleport()` work on Folia for same-region entities? | If not, entire physics loop needs async redesign | Spawn two entities in same region, call teleport() from entity scheduler |
| Does `entity.getScheduler().runAtFixedRate()` guarantee the task runs fully within one tick? | Physics loop assumes atomic per-tick execution | Read Folia source or test with logging |
| When a passenger's parent is teleported within-region, does the passenger follow synchronously? | Shulkers ride carriers; need them to move together | Test with carrier+passenger teleport |
| Does `getNearbyEntities()` work at all on Folia from a region thread? | Used in entity recovery | Test from entity scheduler callback |
| What happens to an entity scheduler task when the entity's chunk unloads? | Ship suspension depends on this | Test: schedule repeating task, unload chunk, reload |

---

## Recommended Approach

1. **Verify the teleport question first.** If synchronous same-region teleport doesn't work, Folia support may not be feasible without a major physics rewrite.

2. **If teleport works:** The migration is painful but tractable:
   - Replace all schedulers (entity/region/global as appropriate)
   - Add `Bukkit.isOwnedByCurrentRegion()` checks in the tick loop for carriers
   - Implement force-disassemble bail-out with cross-region scheduling
   - Switch to `PlayerInputEvent` for steering
   - Make `ShipRegistry` and recovery state thread-safe
   - Fix player disconnect handler to schedule on ship's region

3. **Ship-to-ship collision** (currently commented out): If re-enabled, would need cross-region coordination. Flag for future consideration.

4. **Testing strategy:** Folia's region boundaries are hard to control in testing. Best approach is to test with artificially small region sizes or by placing ships at the edge of render distance.
