---
status: planned
---

# Minecart Ship Feature - Design Document

## Overview

A special "ship minecart" that, when powered by an activator rail, scans the block structure above it and converts it into a passive display ship. The minecart rides on rails as normal; the ship follows along. A second activator rail signal disassembles the ship back into blocks. This enables train-like builds on rail networks.

---

## User Flow

1. Player crafts a **Ship Minecart** item (minecart + ship wheel + pistons)
2. Places it on a rail — spawns a minecart with a visible distinguishing block
3. Player builds a block structure above the placed minecart
4. Pushes the minecart onto a powered **activator rail** — blocks above are scanned and assembled into a display ship attached to the minecart
5. Minecart moves along rails; the ship display follows passively (no custom physics)
6. Minecart hits another powered activator rail — ship disassembles, blocks are placed back into the world
7. Breaking the minecart also disassembles the ship and drops the ship minecart item

---

## Architecture

### Key Insight: Reuse ShipInstance

`ShipInstance` already contains all the display entity spawning, collision box positioning, display transform updating, and cleanup logic needed. The only ArmorStand-specific code is:

- **Constructor** (lines 304-320): spawns an ArmorStand as the root vehicle entity
- **Health system** (~12 call sites across ShipInstance, DisplayShip, ShipWheelManager): calls `vehicle.getHealth()`, `vehicle.getAttribute()`, `vehicle.setHealth()` — these are `LivingEntity` methods that `Minecart` does not implement
- **Recovery** (lines 1980-1981, 2021): `instanceof ArmorStand` casts when recovering entity references after chunk reload

Everything else — display entity spawning, collision shulker spawning, `updateCollisionPositions()`, display transform matrix computation, `destroy()`, passenger mounting — uses generic `Entity` methods (`getLocation`, `getYaw`, `isValid`, `addPassenger`, etc.).

Rather than creating a parallel class hierarchy or extracting shared code, we widen `ShipInstance.vehicle` from `ArmorStand` to `Entity`, add an `isMinecartShip` flag, and short-circuit `tick()` for minecart mode. This gives us **zero code duplication** and **one new file** (the lifecycle manager).

### New Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `MinecartShipManager` | `minecartships/MinecartShipManager.java` (NEW) | Lifecycle: placement, activator rail toggle, disassembly, events |
| `ship_minecart` item | `config.yml` custom-items section | Craftable item, PLAYER_HEAD with ship wheel texture |

### Modified Components

| Component | Change |
|-----------|--------|
| `ShipInstance` | Widen vehicle type, add minecart constructor + tick path |
| `DisplayShip` | Guard health call sites with `instanceof LivingEntity` |
| `ShipWheelManager` | Guard health call sites with `instanceof LivingEntity` |
| `ShipCollisionCoordinator` | Skip minecart ships in collision processing |
| `ShipPersistence` | Add `vehicleType` field to `ShipState` |
| `BlockShipsPlugin` | Register `MinecartShipManager` |

---

## Detailed Implementation

### 1. Ship Minecart Custom Item

**File:** `blockships/src/main/resources/config.yml`

Add to the `custom-items` section:

```yaml
ship_minecart:
  display-name: "Ship Minecart"
  base-material: PLAYER_HEAD
  texture-set: SHIP_WHEEL_SET       # Reuse ship wheel texture; replace later
  variant-source: null
  stackable: false
  recipe:
    pattern:
      - "P P"
      - "PWP"
      - " M "
    ingredients:
      P: [PISTON]
      W: ["blockships:ship_wheel"]
      M: [MINECART]
    result-name: "Ship Minecart"
    result-item: PLAYER_HEAD
    result-texture-set: SHIP_WHEEL_SET
```

The existing `CustomItem` system creates PLAYER_HEAD items with PDC tag `custom_item_id: "ship_minecart"`. The existing `/blockships give ship_minecart` command, crafting recipe registration, and item detection all work automatically.

---

### 2. ShipInstance: Widen Vehicle Type

**File:** `blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java`

#### 2a. Change field type

```java
// Line 72: was ArmorStand, now Entity
public Entity vehicle;  // Root entity: ArmorStand (normal ships) or Minecart (minecart ships)

// New field
public final boolean isMinecartShip;
```

#### 2b. Add minecart constructor

A second constructor that receives an existing Minecart entity instead of spawning an ArmorStand:

```java
public ShipInstance(JavaPlugin plugin, Minecart minecart, ShipModel model) {
    this.plugin = plugin;
    this.shipType = "custom";  // Minecart ships are always custom-scanned
    this.model = model;
    this.customization = ShipCustomization.empty();
    this.id = UUID.randomUUID();
    this.driverSeatIndex = 0;
    this.isMinecartShip = true;
    this.config = ShipConfig.load(plugin, "custom");
    this.isAirship = false;  // Minecart ships don't fly

    this.vehicle = minecart;
    minecart.addScoreboardTag(ShipTags.shipRootTag(id));

    // No physics or collision delegates — minecart handles movement
    this.physics = null;
    this.collision = null;

    // Initialize rotation state
    this.initialRotRadX = 0;
    this.initialRotRadY = 0;
    this.initialRotRadZ = 0;
    cachedR_initial.identity();

    this.previousVehicleLocation = minecart.getLocation().clone();
    this.previousYaw = minecart.getYaw();
    this.previousPitch = minecart.getPitch();
    this.spawnYaw = minecart.getYaw();

    // Chunk tracking
    this.currentChunkX = minecart.getLocation().getBlockX() >> 4;
    this.currentChunkZ = minecart.getLocation().getBlockZ() >> 4;

    // === Display + collider spawning (lines 355-917) ===
    // This code is IDENTICAL to the existing constructor — it only uses:
    //   this.id, this.model, this.config, this.customization, this.shipType
    //   and the vehicle's location (via generic Entity.getLocation())
    // No ArmorStand-specific calls.
    // ... (same display spawning, collision shulker spawning, inventory restoration)

    // Mount parent to minecart (same as mounting to ArmorStand)
    // vehicle.addPassenger(parent);

    // Start tick task (same as existing, will dispatch to tickMinecart())
}
```

#### 2c. Existing constructor

Add `this.isMinecartShip = false;` to the existing ArmorStand-based constructor.

---

### 3. Minecart Tick Path

**File:** `blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java`

Add early dispatch at the top of `tick()`:

```java
void tick() {
    if (isMinecartShip) {
        tickMinecart();
        return;
    }
    // ... existing full physics tick (health, steering, collision, physics, display) ...
}
```

New method — dramatically simpler than the full tick:

```java
/**
 * Simplified tick for minecart ships.
 * The minecart handles all movement via rails. We just:
 * 1. Update collision box positions to follow the minecart
 * 2. Update display entity transforms if the minecart moved/rotated
 *
 * Skipped: health regen, steering input, collision detection,
 * physics (acceleration/drag/buoyancy/rotation), collision response,
 * velocity sync, idle-to-sleep transition.
 */
private void tickMinecart() {
    cachedVehicleLoc = vehicle.getLocation();
    if (!cachedVehicleLoc.isChunkLoaded()) return;
    if (vehicle.isDead() || !vehicle.isValid()) {
        destroyWithPersistenceCleanup();
        return;
    }

    // Sync collision boxes to follow the minecart
    updateCollisionPositions();

    // Check if minecart moved or rotated
    Location currentLoc = cachedVehicleLoc;
    float yaw = vehicle.getYaw();
    float pitch = vehicle.getPitch();

    boolean hasMoved = hasMovedSinceLastTick(currentLoc, yaw, pitch);
    if (!hasMoved && !firstTick) {
        previousVehicleLocation = currentLoc.clone();
        previousYaw = yaw;
        previousPitch = pitch;
        return;
    }

    firstTick = false;
    ticksSinceLastMovement = 0;

    // Update chunk tracking
    int newChunkX = currentLoc.getBlockX() >> 4;
    int newChunkZ = currentLoc.getBlockZ() >> 4;
    if (currentChunkX != newChunkX || currentChunkZ != newChunkZ) {
        if (plugin instanceof BlockShipsPlugin bsp && bsp.getDisplayShip() != null) {
            ShipWorldData worldData = bsp.getDisplayShip().getShipWorldData();
            worldData.updateChunkIndex(currentLoc.getWorld(), this.id,
                currentChunkX, currentChunkZ, newChunkX, newChunkZ);
        }
        currentChunkX = newChunkX;
        currentChunkZ = newChunkZ;
    }

    // Update display transforms (same logic as lines 1300-1341)
    previousVehicleLocation = currentLoc.clone();
    previousYaw = yaw;
    previousPitch = pitch;

    // Build rotation + apply to displays
    // (reuse existing buildRotationMatrix() and display transform code)
}
```

---

### 4. Guard Health Code

All `vehicle.getHealth()`, `vehicle.getAttribute()`, `vehicle.setHealth()` calls must be guarded since `Minecart` is not a `LivingEntity`.

**ShipInstance.java** — health regen in `tick()` (lines 1179-1210):
Already skipped for minecart ships because `tickMinecart()` returns before reaching this code. No change needed here.

**DisplayShip.java** — damage handlers (~6 call sites at lines 1473, 1477, 1502, 1548, 1551, 1571, 1705, 1707):

```java
// Before:
double currentHealth = inst.vehicle.getHealth();

// After:
if (!(inst.vehicle instanceof LivingEntity lv)) return;
double currentHealth = lv.getHealth();
```

For minecart ships, damage events on collision shulkers are simply ignored (health system is deferred).

**ShipWheelManager.java** — ship info display (~2 call sites at lines 675, 677):

```java
// Before:
double currentHealth = ship.vehicle.getHealth();

// After:
if (ship.vehicle instanceof LivingEntity lv) {
    double currentHealth = lv.getHealth();
    // ... show health info
}
```

---

### 5. Update Recovery Code

**File:** `blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java`

`recoverVehicle()` (line 1976) — accept Minecart as well as ArmorStand:

```java
for (Entity entity : chunk.getEntities()) {
    if ((entity instanceof ArmorStand || entity instanceof Minecart)
        && entity.getScoreboardTags().contains(rootTag)) {
        this.vehicle = entity;
        return true;
    }
}
```

`recoverEntities()` (line 2003) — same change at line 2021:

```java
if ((e instanceof ArmorStand || e instanceof Minecart)
    && e.getScoreboardTags().contains(rootTag)) {
    vehicle = e;
    break;
}
```

---

### 6. Skip Minecart Ships in Collision Coordinator

**File:** `blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipCollisionCoordinator.java`

At line 111, in the ship iteration loop:

```java
for (ShipInstance ship : ShipRegistry.getAllShips()) {
    if (ship.vehicle == null || ship.vehicle.isDead()) continue;
    if (ship.isMinecartShip) continue;  // <-- NEW: skip minecart ships
    // ... existing collision processing
}
```

Minecart ships have collision boxes (for player walking), but don't generate or receive collision response forces. This is deferred to a future update.

---

### 7. MinecartShipManager

**New file:** `blockships/src/main/java/anon/def9a2a4/blockships/minecartships/MinecartShipManager.java`

#### 7a. State tracking

```java
public class MinecartShipManager implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, MinecartState> tracked = new HashMap<>();
    private BukkitRunnable tickTask;

    // Mutable state per tracked minecart
    private static class MinecartState {
        final Minecart minecart;
        ShipInstance assembledShip;       // null when unassembled
        ShipModel sourceModel;           // stored for disassembly block placement
        boolean wasOnPoweredActivatorRail; // edge detection flag

        MinecartState(Minecart minecart) {
            this.minecart = minecart;
            this.wasOnPoweredActivatorRail = false;
        }
    }
}
```

#### 7b. Placement

Listen for `PlayerInteractEvent` with `RIGHT_CLICK_BLOCK` action:

```java
@EventHandler
public void onPlaceMinecart(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
    ItemStack item = event.getItem();
    if (!isShipMinecartItem(item)) return;

    Block block = event.getClickedBlock();
    if (block == null) return;
    Material type = block.getType();
    if (type != Material.RAIL && type != Material.POWERED_RAIL
        && type != Material.DETECTOR_RAIL && type != Material.ACTIVATOR_RAIL) return;

    event.setCancelled(true);

    // Spawn minecart on the rail
    Location spawnLoc = block.getLocation().add(0.5, 0, 0.5);
    Minecart minecart = block.getWorld().spawn(spawnLoc, RideableMinecart.class, m -> {
        m.addScoreboardTag("blockships:ship_minecart");
        m.setDisplayBlockData(Bukkit.createBlockData(Material.LODESTONE));
        m.getPersistentDataContainer().set(
            new NamespacedKey(plugin, "ship_minecart"), PersistentDataType.BYTE, (byte) 1);
    });

    tracked.put(minecart.getUniqueId(), new MinecartState(minecart));

    // Consume one item from hand
    item.setAmount(item.getAmount() - 1);
}

private boolean isShipMinecartItem(ItemStack stack) {
    if (stack == null || !stack.hasItemMeta()) return false;
    PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
    NamespacedKey key = new NamespacedKey(plugin, "custom_item_id");
    return "ship_minecart".equals(pdc.get(key, PersistentDataType.STRING));
}
```

#### 7c. Activator rail toggle (tick task)

A repeating task checks all tracked minecarts for activator rail contact:

```java
public void startTickTask() {
    tickTask = new BukkitRunnable() {
        @Override
        public void run() {
            Iterator<Map.Entry<UUID, MinecartState>> it = tracked.entrySet().iterator();
            while (it.hasNext()) {
                MinecartState state = it.next().getValue();
                if (!state.minecart.isValid() || state.minecart.isDead()) {
                    if (state.assembledShip != null) {
                        state.assembledShip.destroy();
                    }
                    it.remove();
                    continue;
                }

                boolean onPowered = isOnPoweredActivatorRail(state.minecart);

                // Rising edge detection: only trigger on transition from off -> on
                if (onPowered && !state.wasOnPoweredActivatorRail) {
                    if (state.assembledShip == null) {
                        assemble(state);
                    } else {
                        disassemble(state);
                    }
                }

                state.wasOnPoweredActivatorRail = onPowered;
            }
        }
    };
    tickTask.runTaskTimer(plugin, 0L, 1L);
}

private boolean isOnPoweredActivatorRail(Minecart minecart) {
    Block block = minecart.getLocation().getBlock();
    if (block.getType() != Material.ACTIVATOR_RAIL) return false;
    org.bukkit.block.data.BlockData data = block.getBlockData();
    if (data instanceof org.bukkit.block.data.type.RedstoneRail rr) {
        return rr.isPowered();
    }
    return false;
}
```

The edge detection guarantees:
- One activator rail press = one toggle (assemble OR disassemble, not both)
- The minecart must **leave** the powered activator rail before it can trigger again
- Multiple ticks on the same powered rail do NOT re-trigger

#### 7d. Assembly

```java
private void assemble(MinecartState state) {
    Location minecartLoc = state.minecart.getLocation();
    Location aboveMinecart = minecartLoc.clone().add(0, 1, 0);

    // Check there's actually a block to scan
    if (aboveMinecart.getBlock().getType().isAir()) return;

    // Derive facing from minecart velocity or rail direction
    BlockFace facing = deriveFacing(state.minecart);

    // Scan connected blocks (reuse existing flood-fill scanner)
    ShipModel model;
    try {
        model = BlockStructureScanner.scanStructure(aboveMinecart, facing);
    } catch (Exception e) {
        plugin.getLogger().warning("Minecart ship scan failed: " + e.getMessage());
        return;
    }

    if (model == null || model.parts.isEmpty()) return;

    // Create ship instance using the minecart as root vehicle
    ShipInstance ship = new ShipInstance(plugin, state.minecart, model);

    // Remove scanned blocks from the world
    BlockStructureScanner.removeBlocks(aboveMinecart, model);

    // Register with the ship system
    ShipRegistry.register(ship);

    // Track for disassembly
    state.assembledShip = ship;
    state.sourceModel = model;
}
```

#### 7e. Disassembly

```java
private void disassemble(MinecartState state) {
    if (state.assembledShip == null) return;

    Location minecartLoc = state.minecart.getLocation();
    float currentYaw = state.minecart.getYaw();

    // Place blocks back into the world
    BlockStructureScanner.placeBlocks(minecartLoc.clone().add(0, 1, 0),
        state.sourceModel, currentYaw, false);

    // Destroy ship entities (displays, colliders) and unregister
    state.assembledShip.destroy();

    // Minecart stays on the rail — revert to unassembled state
    state.assembledShip = null;
    state.sourceModel = null;
}
```

#### 7f. Event handlers

```java
@EventHandler
public void onMinecartDestroyed(VehicleDestroyEvent event) {
    if (!(event.getVehicle() instanceof Minecart minecart)) return;
    MinecartState state = tracked.get(minecart.getUniqueId());
    if (state == null) return;

    // Disassemble ship before minecart is destroyed
    if (state.assembledShip != null) {
        disassemble(state);
    }

    tracked.remove(minecart.getUniqueId());

    // Drop ship minecart item at the location
    Location loc = minecart.getLocation();
    ItemStack drop = createShipMinecartItem();
    loc.getWorld().dropItemNaturally(loc, drop);

    // Cancel the default minecart drop (we drop our custom item instead)
    event.setCancelled(true);
    minecart.remove();
}

@EventHandler
public void onPlayerEnterMinecart(VehicleEnterEvent event) {
    if (!(event.getVehicle() instanceof Minecart minecart)) return;
    if (!tracked.containsKey(minecart.getUniqueId())) return;
    // Prevent players from riding the minecart directly
    // They should use seat shulkers instead
    event.setCancelled(true);
}
```

---

### 8. Persistence

**File:** `blockships/src/main/java/anon/def9a2a4/blockships/ShipPersistence.java`

#### 8a. Add vehicleType to ShipState

```java
public static final class ShipState {
    // ... existing fields ...
    public final String vehicleType;  // "armor_stand" or "minecart"

    // Update constructor to include vehicleType
    // Default to "armor_stand" for backwards compatibility
}
```

#### 8b. Serialization

`fromInstance()`:

```java
String vehicleType = inst.isMinecartShip ? "minecart" : "armor_stand";
```

`toMap()` / `fromMap()`: serialize/deserialize the `vehicleType` field. `fromMap()` defaults to `"armor_stand"` if the field is missing (backwards compat with existing save files).

#### 8c. Loading

In `loadAll()`, after deserializing the ShipState:

```java
if ("minecart".equals(state.vehicleType)) {
    // Spawn a fresh minecart at the saved location
    Minecart minecart = world.spawn(loc, RideableMinecart.class, m -> {
        m.addScoreboardTag("blockships:ship_minecart");
        m.setDisplayBlockData(Bukkit.createBlockData(Material.LODESTONE));
    });

    // Create ship using minecart constructor
    ShipInstance instance = new ShipInstance(plugin, minecart, model);
    ShipRegistry.register(instance);

    // Also register with MinecartShipManager for rail toggle tracking
    minecartShipManager.registerLoadedMinecart(minecart, instance, model);
} else {
    // Existing ArmorStand-based loading path
    ShipInstance instance = new ShipInstance(plugin, state.shipType, model, loc, customization);
    // ...
}
```

#### 8d. Orphan cleanup

In `cleanupOrphanedEntities()`, also remove orphaned ship minecarts:

```java
if (entity instanceof Minecart
    && entity.getScoreboardTags().contains("blockships:ship_minecart")) {
    entity.remove();
    removedCount++;
}
```

---

### 9. Register Manager

**File:** `blockships/src/main/java/anon/def9a2a4/blockships/BlockShipsPlugin.java`

In `onEnable()`:

```java
MinecartShipManager minecartShipManager = new MinecartShipManager(this);
getServer().getPluginManager().registerEvents(minecartShipManager, this);
minecartShipManager.startTickTask();
```

Pass the manager reference to `ShipPersistence` for the load path (step 8c).

---

## Summary

| File | Change | Estimated Scope |
|------|--------|----------------|
| `ship/ShipInstance.java` | Widen vehicle to Entity, add minecart constructor + tick path, guard health | ~80 lines |
| `DisplayShip.java` | Guard ~6 health call sites with `instanceof LivingEntity` | ~12 lines |
| `customships/ShipWheelManager.java` | Guard ~2 health call sites with `instanceof LivingEntity` | ~4 lines |
| `ship/ShipCollisionCoordinator.java` | Skip `isMinecartShip` in collision loop | 1 line |
| `ShipPersistence.java` | Add `vehicleType` field, load path for minecart ships | ~30 lines |
| `BlockShipsPlugin.java` | Create and register `MinecartShipManager` | ~5 lines |
| `config.yml` | Add `ship_minecart` custom item + recipe | ~15 lines |
| **NEW** `minecartships/MinecartShipManager.java` | Placement, rail toggle, assembly, disassembly, events | ~250 lines |

**Total: 1 new file, ~400 lines of changes. Zero code duplication.**

---

## Deferred

- [ ] Collision response — terrain/ship collision forces applied to minecart velocity
- [ ] Train coupling — linking multiple minecart ships together
- [ ] Minecart speed limits when carrying a ship
- [ ] Health/damage system for minecart ships
- [ ] Custom texture for ship_minecart item (currently reuses ship wheel)

---

## Edge Cases

- **Empty scan**: no valid blocks above minecart when activator rail fires — do nothing, stay unassembled
- **Minecart destroyed while assembled**: disassemble first (place blocks), then destroy
- **Player disconnects while seated**: existing `PlayerQuitEvent` handler in DisplayShip already dismounts players from ship shulkers
- **Chunk unload/reload**: existing chunk recovery system handles entity references via scoreboard tags; recovery code updated to accept Minecart in addition to ArmorStand
- **Large ships**: collision boxes may extend beyond the rail corridor; acceptable for first pass (no collision response); players can walk on the ship deck via shulkers
- **Minecart yaw on curves**: changes abruptly on corner rails; display interpolation (`setInterpolationDuration(2)`) smooths this over 2 ticks
- **Rail direction for scan facing**: derive from the rail shape at the activator rail block, or from minecart velocity if nonzero
