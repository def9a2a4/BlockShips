# Land Vehicle Support (Issue #10)

## Context

A user wants to drive block structures on land (e.g., rovers on a Mars dimension). Currently BlockShips supports water ships (buoyancy) and airships (vertical controls). Land vehicles should "jump" up blocks like horses rather than implementing pitch/roll physics.

A new **Land Vehicle Wheel** custom item determines the vehicle type at assembly time, mirroring how the existing Ship Wheel works.

## Critical Design Decision: Keep `shipType = "custom"`

The following systems all hardcode `"custom".equals(shipType)`:
- **ShipConfig.load()** — 7 checks routing to `custom-ships.*` config sections
- **ShipPersistence** — model deserialization on restart (lines 77, 127, 282)
- **ShipPhysics** — `computeEffectiveStats()` (line 83), `calculateFloatOffset()` (line 463), engine fuel tick (line 234)
- **ShipInstance** — 9+ checks for blockdata, leads, display handling

Introducing a new shipType like `"custom_land"` would break all of these. Instead:
- Keep `shipType = "custom"` for all custom ships
- Add `isLandVehicle` boolean to `ShipInstance` (set from wheel data)
- Derive `isLandVehicle` from `ShipWheelData.isLandWheel` on assembly and recovery (via `resolveWheelData()`) — avoids any ShipPersistence/ShipState changes

---

## Changes

### 1. ShipWheelData — track wheel type + persistence

**ShipWheelData.java**

Add field + getter/setter:
```java
private boolean isLandWheel = false;
public boolean isLandWheel() { return isLandWheel; }
public void setLandWheel(boolean v) { this.isLandWheel = v; }
```

Persist in `toMap()`:
```java
if (isLandWheel) map.put("land_wheel", true);
```

Restore in `fromMap()`:
```java
if (map.containsKey("land_wheel"))
    data.setLandWheel(Boolean.TRUE.equals(map.get("land_wheel")));
```

### 2. ShipWheelManager — placement, assembly, detection messages

**ShipWheelManager.java**

**`placeWheel()`** — add `boolean isLandWheel` parameter:
```java
public boolean placeWheel(Location location, BlockFace facing, boolean isLandWheel) {
    ShipWheelData wheelData = new ShipWheelData(location, facing);
    wheelData.setLandWheel(isLandWheel);
    ...
}
```

**`assembleShip()` (~line 236)** — after creating ShipInstance, set land flag:
```java
ShipInstance ship = new ShipInstance(plugin, "custom", model, wheelLoc, ShipCustomization.empty());
if (wheelData.isLandWheel()) ship.isLandVehicle = true;
```

**`detectShip()` (~line 873)** — add land vehicle detection message before the airship check:
```java
if (wheelData.isLandWheel()) {
    player.sendMessage("§6⚙ This is a LAND VEHICLE — drives on the ground!");
    player.sendMessage("§7  Automatically climbs 1-block obstacles");
} else if (isAirship) { ... }
```

**`breakWheelBlock()` (line 164)** — drop correct wheel type:
```java
ItemStack wheelItem = wheelData.isLandWheel()
    ? bsp.getDisplayShip().createLandVehicleWheelItem()
    : bsp.getDisplayShip().createShipWheelItem();
```

### 3. ShipInstance — `isLandVehicle` flag + recovery

**ShipInstance.java**

Add field (next to `isAirship` at line 142):
```java
public boolean isLandVehicle = false;
```

In `resolveWheelData()` (~line 91) — set land flag on recovery:
```java
wheelData = bsp.getShipWheelManager().getWheelByShipUUID(id);
if (wheelData != null && physics != null) {
    physics.recomputeStats();
    isLandVehicle = wheelData.isLandWheel();  // <-- add this
}
```

`freeSeat()` (line 1846) — no changes needed; current code just zeroes Y velocity and snaps to grid, which is correct for land vehicles.

**Ship destruction drop** (~line 2055) — drop correct wheel type:
```java
ItemStack shipWheel = isLandVehicle
    ? bsp.getDisplayShip().createLandVehicleWheelItem()
    : bsp.getDisplayShip().createShipWheelItem();
```

### 4. ShipConfig — add land vehicle config fields

**ShipConfig.java**

Add 3 new fields:
```java
public final float stepUpHeight;   // default: 1.0
public final float groundDrag;     // default: 0.97
public final float stepUpSpeed;    // default: 0.15
```

Add to Builder (fields + setters). Load in `ShipConfig.load()`:
```java
.stepUpHeight((float) cfg.getDouble(
    "custom".equals(shipType) ? "custom-ships.land-controls.step-up-height"
                              : p + "controls.step-up-height", 1.0))
.groundDrag((float) cfg.getDouble(
    "custom".equals(shipType) ? "custom-ships.land-controls.ground-drag"
                              : p + "controls.ground-drag", 0.97))
.stepUpSpeed((float) cfg.getDouble(
    "custom".equals(shipType) ? "custom-ships.land-controls.step-up-speed"
                              : p + "controls.step-up-speed", 0.15))
```

### 5. ShipPhysics — land physics, step-up, drag, sounds

**ShipPhysics.java**

**Route in `update()` (line 281):**
```java
if (ship.isAirship) {
    applyAirshipVerticalPhysics();
} else if (ship.isLandVehicle) {
    handleLandPhysics(vehicleLoc);
} else {
    handleBuoyancy(vehicleLoc);
}
```

**New `handleLandPhysics()` method** — same ground detection pattern as `handleBuoyancy()`'s non-water branch (reuses `reuseLocation`/`reuseLocation2` for zero-alloc):
```java
private void handleLandPhysics(Location vehicleLoc) {
    double hullCheckY = vehicleLoc.getY() + ship.model.minY;
    Location hullCheckLoc = reuseLocation(vehicleLoc);
    hullCheckLoc.setY(hullCheckY);

    Location belowCheck = reuseLocation2(hullCheckLoc);
    belowCheck.subtract(0, 0.1, 0);
    Material belowBlock = belowCheck.getBlock().getType();
    boolean onGround = belowBlock.isSolid() && belowBlock != Material.WATER;

    if (onGround) {
        if (currentYVelocity < 0) currentYVelocity = 0.0f;
        if (Math.abs(currentSpeed) > ship.config.minMovementThreshold) {
            if (detectStepUp(vehicleLoc)) {
                currentYVelocity = ship.config.stepUpSpeed;
            }
        }
    } else {
        currentYVelocity -= 0.08f;
    }
}
```

**New `detectStepUp()` method:**
```java
private boolean detectStepUp(Location vehicleLoc) {
    float yawRad = (float) Math.toRadians(-currentYaw);
    float direction = Math.signum(currentSpeed);  // flip for reverse
    double probeX = vehicleLoc.getX() + Math.sin(yawRad) * direction * (Math.abs(currentSpeed) + 0.5);
    double probeZ = vehicleLoc.getZ() + Math.cos(yawRad) * direction * (Math.abs(currentSpeed) + 0.5);
    double hullY = vehicleLoc.getY() + ship.model.minY;
    World world = vehicleLoc.getWorld();

    int bx = (int) Math.floor(probeX);
    int by = (int) Math.floor(hullY);
    int bz = (int) Math.floor(probeZ);

    Block frontBlock = world.getBlockAt(bx, by, bz);
    if (!frontBlock.getType().isSolid()) return false;

    float stepHeight = (float)((by + 1) - hullY);
    if (stepHeight <= 0 || stepHeight > ship.config.stepUpHeight) return false;

    return !world.getBlockAt(bx, by + 1, bz).getType().isSolid();
}
```

**Ground drag (line 261-264)** — add land vehicle branch:
```java
if (ship.isLandVehicle) {
    dragMultiplier *= config.groundDrag;
} else if (isWaterOrWaterlogged(reuseLocation(vehicleLoc).subtract(0, 0.5, 0).getBlock())) {
    dragMultiplier *= 0.98f;
}
```

**Movement sounds (lines 349-357)** — land vehicles use minecart sound:
```java
float minSpeed = ship.isAirship ? config.airshipSoundMinSpeed : config.soundMinSpeed;
if (totalSpeed >= minSpeed && soundCooldown == 0) {
    Location loc = ship.vehicle.getLocation();
    Sound sound;
    float baseVolume;
    int interval;
    if (ship.isAirship) {
        sound = Sound.ITEM_ELYTRA_FLYING;
        baseVolume = config.airshipSoundVolume;
        interval = config.airshipSoundIntervalTicks;
    } else if (ship.isLandVehicle) {
        sound = Sound.ENTITY_MINECART_RIDING;
        baseVolume = config.soundVolume;
        interval = config.soundIntervalTicks;
    } else {
        sound = Sound.ENTITY_BOAT_PADDLE_WATER;
        baseVolume = config.soundVolume;
        interval = config.soundIntervalTicks;
    }
    float movementVolume = (float) ship.plugin.getConfig().getDouble("sounds.movement-volume", 0.5);
    loc.getWorld().playSound(loc, sound, baseVolume * movementVolume, config.soundPitch);
    soundCooldown = interval;
}
```

### 6. ShipCollision — skip step-up blocks

**ShipCollision.java — `calculateTerrainCollisionForce()` (after line 232)**

After the `!isSolid()` check, before the overlap test:
```java
if (ship.isLandVehicle) {
    double blockTopY = y + 1;
    double hullBottomY = shulkerBox.getMinY();
    double stepHeight = blockTopY - hullBottomY;
    if (stepHeight > 0 && stepHeight <= ship.config.stepUpHeight) {
        Block above = world.getBlockAt(x, y + 1, z);
        if (!above.getType().isSolid()) {
            continue;
        }
    }
}
```

Performance: the `ship.isLandVehicle` boolean check short-circuits for non-land-vehicles — zero overhead for existing ships.

### 7. DisplayShip — recognize and handle land vehicle wheels

**DisplayShip.java**

**Add helpers** (near `isShipWheel()` at line 1832):
```java
private boolean isLandVehicleWheel(ItemStack stack) {
    if (stack == null || !stack.hasItemMeta()) return false;
    var pdc = stack.getItemMeta().getPersistentDataContainer();
    NamespacedKey key = new NamespacedKey(plugin, "custom_item_id");
    return pdc.has(key, PersistentDataType.STRING) &&
           "land_vehicle_wheel".equals(pdc.get(key, PersistentDataType.STRING));
}

private boolean isAnyWheel(ItemStack stack) {
    return isShipWheel(stack) || isLandVehicleWheel(stack);
}

public ItemStack createLandVehicleWheelItem() {
    return itemFactory.createItem("land_vehicle_wheel", null, null);
}
```

**All call sites to update:**

| Line | Current | Change |
|------|---------|--------|
| 1016 | `isShipWheel(item)` | `isAnyWheel(item)` — captain's manual recipe returns any wheel type |
| 1205 | `isShipWheel(player.getInventory()...)` | `isAnyWheel(...)` — info message when holding wheel near assembled ship |
| 1866 | `if (!isShipWheel(item)) return` | `if (!isAnyWheel(item)) return` — placement handler |
| 1946 | `manager.placeWheel(loc, facing)` | `manager.placeWheel(loc, facing, isLandVehicleWheel(item))` |
| 2208 | `createShipWheelItem()` | check `wheelData.isLandWheel()`, drop correct type |

### 8. BlockShipsPlugin — give command

**BlockShipsPlugin.java**

Add `land_vehicle_wheel` to:
- Give command handler (~line 269): create via `displayShip.createLandVehicleWheelItem()`
- Tab completion (~line 491): add to types list
- Help text (~line 254): list as available item

### 9. config.yml — item definition + land controls

**config.yml**

Add under `custom-items:`:
```yaml
  land_vehicle_wheel:
    display-name: "Land Vehicle Wheel"
    base-material: PLAYER_HEAD
    texture-set: SHIP_WHEEL_SET
    variant-source: GOLD_BLOCK
    stackable: false
    recipe:
      pattern:
        - "ICI"
        - "CRC"
        - "ICI"
      ingredients:
        I: [IRON_INGOT]
        C: [COPPER_INGOT]
        R: [REDSTONE]
      result-name: "Land Vehicle Wheel"
      result-item: PLAYER_HEAD
      result-texture-set: SHIP_WHEEL_SET
```

Add under `custom-ships:`:
```yaml
  land-controls:
    step-up-height: 1.0
    ground-drag: 0.97
    step-up-speed: 0.15
```

---

## Files modified

| File | Risk | What changes |
|------|------|-------------|
| ShipWheelData.java | Low | `isLandWheel` field + persistence |
| ShipWheelManager.java | Low | `placeWheel()` signature, assembly flag, detection messages, `breakWheelBlock()` drop |
| ShipInstance.java | Low | `isLandVehicle` field, `resolveWheelData()` recovery, destruction drop |
| ShipConfig.java | Low | 3 new fields + builder + load |
| ShipPhysics.java | Medium | `handleLandPhysics()`, `detectStepUp()`, drag branch, sound branch |
| ShipCollision.java | Low | Step-up block filter in terrain collision |
| DisplayShip.java | Low | `isAnyWheel()`, placement handler, break handler, item factory |
| BlockShipsPlugin.java | Low | Give command + tab completion |
| config.yml | Low | Land vehicle wheel item + land controls |

## What does NOT change

- **ShipPersistence / ShipState** — `isLandVehicle` is derived from wheel data via `resolveWheelData()`, not persisted separately
- **ShipWheelMenu.java** — fully generic, no type-specific logic
- **BlockStructureScanner.java** — scans blocks regardless of wheel type
- **ShipModel.java** — model creation is type-agnostic
- **SpecialDrownedListener.java** — drowned still drop regular ship wheels only
- **All `"custom".equals(shipType)` checks** — unchanged because shipType stays `"custom"`

## Existing functionality impact

- **Water ships / Airships**: Zero changes to physics, buoyancy, collision, sounds, or controls
- **Performance**: `ship.isLandVehicle` boolean check short-circuits — zero overhead for non-land-vehicles
- **Persistence**: Backward-compatible — `land_wheel` key in ShipWheelData is only written when true, missing key defaults to false

## Verification

1. `make build`
2. `/blockships give land_vehicle_wheel` — verify item is created
3. Place land vehicle wheel → build structure around it → detect → verify "LAND VEHICLE" message
4. Assemble → vehicle stays on ground with gravity, no buoyancy
5. WASD driving → moves, minecart sound plays
6. Drive into 1-block wall → vehicle steps up
7. Drive into 2+ block wall → collision stops vehicle
8. Drive off edge → falls with gravity
9. Driver exit → stops cleanly, snaps to grid
10. Break land wheel block → drops "Land Vehicle Wheel" item (not Ship Wheel)
11. Server restart → land vehicle restores correctly via `resolveWheelData()`
12. Existing water ship → unchanged behavior
13. Existing airship → unchanged behavior
