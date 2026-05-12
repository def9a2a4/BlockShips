# Custom Ship Stats System - Design Document

## Overview

Ship speed, acceleration, and rotation scale with a **power-to-mass ratio**. Sails and engines provide power points; block weights provide mass. The ratio maps linearly to stats, with sails capped so engines are needed to reach maximum performance.

---

## Power Points

Every ship starts with **2 free power points** (ensures even tiny ships have some base ratio).

| Source | Points | Mass (weight) | Notes |
|--------|--------|---------------|-------|
| Base | 2 | — | Free, every ship |
| Wool (any color) | 3 | 1 | Tagged `#wool` in blocks.yml |
| Banner (any type) | 7 | 1 | Tagged `#banners` in blocks.yml |
| Ship Engine (fueled) | ~30 | ~3 | Custom blast furnace, only when fueled |
| Ship Engine (unfueled) | 0 | ~3 | Dead weight — adds mass, no power |

All other blocks contribute 0 power points but still contribute their weight as mass.

---

## Horizontal Stats Formula

### Core Ratio

```
sail_power = wool_blocks * 3 + banner_blocks * 7
engine_power = fueled_engines * 30

// Sails can push ratio up to 0.8 at most; engines push past it
non_engine_ratio = min((BASE_POWER + sail_power) / mass, 0.8)
ratio = min(non_engine_ratio + engine_power / mass, 1.0)
```

### Ratio → Stats (Linear)

```
ratio 0.0 → absolute floor
ratio 0.7 → current default stats
ratio 1.0 → absolute cap (1.5× current defaults)
```

Linear interpolation between these anchor points:

```java
// For a given stat:
float floor = absoluteMin;          // e.g., 0.05 blocks/tick for speed
float defaultVal = currentDefault;  // e.g., 0.55 blocks/tick
float cap = currentDefault * 1.5;   // e.g., 0.825 blocks/tick

float stat;
if (ratio <= 0.7) {
    // Interpolate floor → default over ratio 0.0 → 0.7
    stat = floor + (ratio / 0.7f) * (defaultVal - floor);
} else {
    // Interpolate default → cap over ratio 0.7 → 1.0
    stat = defaultVal + ((ratio - 0.7f) / 0.3f) * (cap - defaultVal);
}
```

### Absolute Floors and Caps

| Stat | Floor | Default (ratio 0.7) | Cap (ratio 1.0) |
|------|-------|---------------------|-----------------|
| `maxSpeed` | 0.05 blocks/tick (1 block/sec) | 0.55 blocks/tick | 0.825 blocks/tick |
| `acceleration` | configurable | 0.02 | 0.03 |
| `rotationSpeed` | 0.6 deg/tick (30s/revolution) | 1.5 deg/tick | 2.25 deg/tick |
| `rotationAcceleration` | configurable | 0.3 | 0.45 |

All configurable in `config.yml`.

### Example Ships

Assuming all non-sail blocks are wood (weight 2):

| Ship | Power | Mass | Raw Ratio | Effective Ratio | Speed |
|------|-------|------|-----------|-----------------|-------|
| 90 wood, 0 wool | 2 | 180 | 0.01 | 0.01 | ~floor |
| 60 wood, 30 wool | 2+90=92 | 150 | 0.61 | 0.61 | ~87% of default |
| 56 wood, 34 wool | 2+102=104 | 146 | 0.71 | 0.71 | ~default |
| 45 wood, 45 wool | 2+135=137 | 135 | 1.01→**0.80** | 0.80 (sail cap) | above default |
| 60 wood, 30 wool, 1 eng | 92+30=122 | 153 | — | 0.80 | sail cap |
| 60 wood, 30 wool, 2 eng | 92+60=152 | 156 | — | 0.97 | near max |
| 45 wood, 45 wool, 1 eng | 137+30=167 | 138 | — | **1.0** (capped) | max |
| 60 wood, 30 wool, 1 eng (unfueled) | 92+0=92 | 153 | 0.60 | 0.60 | below default |

---

## Airship Vertical Stats

### Behavior
Vertical speed and acceleration scale with **density magnitude** and **fueled engines**. Sails do not affect vertical stats.

**Design decisions:**
- Density magnitude affects both max vertical speed and vertical acceleration
- Engines provide a vertical bonus (separate from horizontal ratio)
- Symmetric: density affects ascent and descent equally
- Density > 0 = water ship, no vertical controls

### Formula

```
densityMag = abs(density)              // e.g., 0.1 to 5.0
engineRatio = engine_points / mass     // same engine points as horizontal

verticalRatio = clamp(densityMag * DENSITY_SCALE + engineRatio * ENGINE_VERTICAL_SCALE, 0, 1)

// Then same linear mapping as horizontal:
// verticalRatio 0.0 → vertical floor
// verticalRatio 0.7 → current vertical defaults
// verticalRatio 1.0 → vertical cap
```

Tuning constants (`DENSITY_SCALE`, `ENGINE_VERTICAL_SCALE`) configurable in `config.yml`.

---

## Engine Fuel System

### Fuel Consumption
Fuel ticks down whenever the **W key is held** (accelerating or maintaining speed). Releasing W = no fuel consumption, even while coasting.

### Fuel Values
Standard Minecraft furnace fuels:
| Fuel | Ticks | Real Time |
|------|-------|-----------|
| Coal | 1600 | 80 sec |
| Coal Block | 16000 | 13.3 min |
| Charcoal | 1600 | 80 sec |
| Blaze Rod | 2400 | 120 sec |
| Lava Bucket | 20000 | 16.7 min |

### Engine GUI
- Custom inventory menu (not the normal blast furnace smelting UI)
- **Multiple fuel slots** — load up fuel in advance
- Shows fuel remaining and estimated burn time
- **Hopper-compatible** if feasible (auto-fuel from adjacent hoppers)

### Engine Detection
- Custom crafting recipe produces a blast furnace with PDC tag (`blockships:custom_item_id` = `"ship_engine"`)
- `BlockPlaceEvent` listener transfers PDC from item to placed block's TileState
- During ship scan, check each blast furnace's TileState PDC for the engine tag
- Suppress vanilla smelting on tagged blast furnaces (`FurnaceBurnEvent`/`FurnaceSmeltEvent` cancel)
- Need to verify/add PDC serialization in `BlockStructureScanner` so the tag survives assembly/disassembly

### Hopper Integration
- **Pre-assembly only:** hoppers feed fuel into engine blast furnaces using vanilla mechanics
- If engines run out of fuel mid-voyage, player must stop/disassemble and let hoppers refuel
- No custom hopper simulation on assembled ships

### Visual Feedback
- Running engines emit smoke particles

---

## Ship Info Display

The ship info menu should show:
```
=== Ship Stats ===
Mass: 150
Power: 92 (2 base + 90 sails)
  Wool: 30 blocks (90 pts)
  Banners: 0
  Engines: 0

Ratio: 0.61 / 1.00
Speed: 87% (0.48 blocks/tick)
Acceleration: 87%
Rotation: 87%
```

With engines:
```
Power: 122 (2 base + 90 sails + 30 engines)
  Engines: 1 fueled, 0 empty
```

For airships, also show:
```
Density: -1.5 (airship)
Vertical Speed: 85%
Vertical Accel: 85%
```

---

## Implementation Phases

### Phase 1: Detection & Data Storage
1. Count wool blocks and banners during ship scan in `BlockStructureScanner`
2. Detect ship engines (custom blast furnaces)
3. Add fields to `ShipModel`: `woolCount`, `bannerCount`, `engineLocations`
4. Compute and store `sailPower`, `totalPower`, `ratio`

### Phase 2: Stat Calculation & Physics
5. Implement ratio → stat linear interpolation
6. Apply multipliers in `ShipPhysics.update()` for horizontal stats
7. Apply vertical scaling in `ShipPhysics.applyAirshipVerticalPhysics()`
8. Enforce absolute floors and caps
9. Test with various ship configurations

### Phase 3: Engine Fuel System
10. Create ship engine crafting recipe and tagged block
11. Create engine fuel GUI (multiple slots, hopper-compatible)
12. Implement fuel consumption (ticks down while W held)
13. Real-time stat updates when fuel runs out (engine becomes dead weight)
14. Smoke particles on running engines

### Phase 4: UI & Polish
15. Update ship info menu with power/ratio/stat breakdown
16. Add engine management to menu

---

## Key Files

| File | Changes |
|------|---------|
| `BlockStructureScanner.java` | Count wool, banners, detect engines during scan |
| `ShipModel.java` | Store wool/banner counts, engine locations, computed ratio |
| `ShipWheelData.java` | Persist engine fuel state |
| `ShipPhysics.java` | Apply ratio → stat mapping, enforce floors/caps, vertical scaling |
| `ShipConfig.java` | Power point values, ratio anchors, floors/caps, vertical constants |
| `config.yml` | All tuning constants |
| New: `ShipEngine.java` | Engine crafting recipe, item, block tagging |
| New: `EngineMenuGUI.java` | Fuel management GUI |

---

## Resolved Decisions

- **Formula:** Power-to-mass ratio with sail cap at 0.8, linear mapping to stats
- **Ratio anchors:** 0.0 = floor, 0.7 = current default, 1.0 = 1.5× default (cap)
- **Sail cap:** Non-engine power capped at ratio 0.8; engines needed to reach 1.0
- **All horizontal stats use the same ratio** (no per-stat weighting for now)
- **Absolute floors:** 0.05 blocks/tick speed (1 block/sec), 0.6 deg/tick rotation (30s/revolution)
- **Absolute caps:** 1.5× current defaults
- **Unfueled engines:** Dead weight (0 power, still adds mass)
- **Fuel consumption:** While W key held (accelerating or maintaining speed)
- **Airship vertical:** Density magnitude + engine bonus, symmetric ascent/descent
- **Airship vertical:** Sails do NOT affect vertical stats
- **Copper network:** Cut
- **Engine particles:** Smoke when running

## Open Questions

1. **Vertical tuning:** `DENSITY_SCALE` and `ENGINE_VERTICAL_SCALE` values
2. **Acceleration floor:** Exact value (configurable, will tune in-game)
3. **Engine points:** ~30 confirmed, exact value may need tuning
