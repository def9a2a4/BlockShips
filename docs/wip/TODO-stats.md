# Custom Ship Stats System - Design Document

## Overview

A dynamic stats system where **sails** and **engines** increase ship performance, while **mass** decreases it. The goal is an intuitive system where players naturally understand: more sails = faster, heavier ship = slower, engines = power boost.

---

## Components

### Sails (Passive Propulsion)
| Block Type | Points | Notes |
|------------|--------|-------|
| Wool (any color) | 1 | Already tagged `#wool` in blocks.yml |
| Banner (any type) | 3 | Already tagged `#banners` in blocks.yml |

Sails are passive - they always contribute to stats. Banners are worth more because they're more decorative/fragile and feel more "sail-like".

### Ship Engines (Active Propulsion)
- **Item:** Blast furnace with custom NBT (name, lore, enchantment glint)
- **Points:** 10 per engine (only when fueled)
- **Fuel:** Standard furnace fuels (coal, charcoal, lava bucket, etc.)
- When unfueled: contributes 0 points (dead weight - adds mass but no power)

**Engine Detection:**
- During ship scan, check for blast furnaces with specific custom model data or NBT
- Could use: custom name containing "[Ship Engine]", or custom model data, or enchantment glint

### Mass
- Use existing `totalPositiveWeight` from BlockStructureScanner (same as health calculation)
- Range: 1 to 1024 (capped)
- Typical values:
  - Small ship (50 blocks): ~100-150 mass
  - Medium ship (200 blocks): ~400-600 mass
  - Large ship (500+ blocks): ~800-1024 mass

---

## Stats Affected

| Stat | Current Default | Description |
|------|-----------------|-------------|
| `maxSpeed` | 0.55 | Maximum forward/backward speed (blocks/tick) |
| `acceleration` | 0.02 | Speed increase per tick when W/S held |
| `rotationSpeed` | 1.5 | Maximum rotation speed (degrees/tick) |
| `rotationAcceleration` | 0.3 | Rotation speed increase per tick |

**Question:** Should all stats scale equally, or should sails affect speed more and engines affect acceleration/rotation more?

---

## Math Formula Options

### Option A: Power-to-Weight Ratio (Recommended)

```
power = sail_points + fueled_engine_points
ratio = power / mass
multiplier = clamp(BASE + ratio * SCALE, MIN, MAX)
```

**Example tuning:**
```
BASE = 0.5      // ship with zero sails runs at 50% stats
SCALE = 50      // scaling factor
MIN = 0.3       // floor (30% of base stats)
MAX = 1.5       // ceiling (150% of base stats)

// Example: 20 sail points, 200 mass
multiplier = 0.5 + (20/200) * 50 = 0.5 + 5 = 1.5 (capped)

// Example: 5 sail points, 500 mass
multiplier = 0.5 + (5/500) * 50 = 0.5 + 0.5 = 1.0

// Example: 0 sail points, 100 mass
multiplier = 0.5 + 0 = 0.5
```

**Pros:**
- Intuitive: "I need sails proportional to my ship size"
- Self-balancing: big ships need big sails
- Like real vehicles (power-to-weight matters)

**Cons:**
- Tiny ships with one wool block become very fast (need MIN cap)
- Need to tune SCALE carefully

---

### Option B: Diminishing Returns

```
sail_bonus = sqrt(sail_points) * SAIL_SCALE
engine_bonus = fueled_engine_points * ENGINE_SCALE
mass_penalty = sqrt(mass) * MASS_SCALE
multiplier = clamp(BASE + sail_bonus + engine_bonus - mass_penalty, MIN, MAX)
```

**Example tuning:**
```
BASE = 0.8
SAIL_SCALE = 0.05      // sqrt(100 sails) * 0.05 = 0.5 bonus
ENGINE_SCALE = 0.02    // 30 engine points * 0.02 = 0.6 bonus
MASS_SCALE = 0.02      // sqrt(400 mass) * 0.02 = 0.4 penalty
MIN = 0.3
MAX = 1.5

// Example: 100 sail points, 30 engine points, 400 mass
multiplier = 0.8 + 0.5 + 0.6 - 0.4 = 1.5

// Example: 25 sail points, 0 engines, 100 mass
multiplier = 0.8 + 0.25 + 0 - 0.2 = 0.85
```

**Pros:**
- Prevents sail stacking exploits (diminishing returns)
- Engines have linear scaling (rewarding)
- Mass penalty grows slower than mass (big ships aren't crippled)
- Very tunable

**Cons:**
- Less intuitive for players
- More constants to balance

---

### Option C: Composition Percentage

```
sail_ratio = sail_points / mass
engine_ratio = fueled_engine_points / mass
multiplier = clamp(BASE + sail_ratio * SAIL_SCALE + engine_ratio * ENGINE_SCALE, MIN, MAX)
```

**Example tuning:**
```
BASE = 0.6
SAIL_SCALE = 5.0       // 10% sail composition = 0.5 bonus
ENGINE_SCALE = 10.0    // 5% engine composition = 0.5 bonus
MIN = 0.3
MAX = 1.5

// Example: 50 sail points, 20 engine points, 500 mass (10% sails, 4% engines)
multiplier = 0.6 + 0.5 + 0.4 = 1.5

// Example: 10 sail points, 0 engines, 200 mass (5% sails)
multiplier = 0.6 + 0.25 = 0.85
```

**Pros:**
- Rewards efficient ship design
- Scales naturally with ship size
- Encourages thinking about composition

**Cons:**
- Can punish large ships that are "realistic" (lots of wood, few sails)
- Players might game it by removing solid blocks

---

### Option D: Hybrid (Power-to-Weight with Diminishing Sails)

```
effective_sails = sqrt(sail_points) * 3    // diminishing returns
effective_engines = fueled_engine_points   // linear
power = effective_sails + effective_engines
ratio = power / sqrt(mass)                 // mass penalty also diminishes
multiplier = clamp(BASE + ratio * SCALE, MIN, MAX)
```

This combines the intuitive power-to-weight concept with diminishing returns to prevent exploits.

---

## Per-Stat Scaling

Not all stats need to scale equally. Possible approach:

| Stat | Sail Influence | Engine Influence |
|------|----------------|------------------|
| `maxSpeed` | 100% | 100% |
| `acceleration` | 50% | 100% |
| `rotationSpeed` | 30% | 70% |
| `rotationAcceleration` | 30% | 70% |

**Rationale:** Sails help you go fast but don't help you turn or accelerate quickly. Engines provide raw power for everything.

```java
float speedMultiplier = calculateMultiplier(sailPoints, enginePoints, mass);
float accelMultiplier = calculateMultiplier(sailPoints * 0.5, enginePoints, mass);
float rotationMultiplier = calculateMultiplier(sailPoints * 0.3, enginePoints * 0.7, mass);
```

---

## Engine Fuel System

### Fuel Consumption Options

**A. When Ship is Moving**
- Fuel ticks down while `currentSpeed != 0`
- Stopped ships don't consume fuel
- Intuitive: engines run when ship moves

**B. When Accelerating Only**
- Fuel only consumed while W/S/A/D pressed
- Coasting uses no fuel
- More tactical, rewards momentum management

**C. Constant When Driver Present**
- Engines burn fuel whenever someone is at the wheel
- Simplest mental model
- Encourages parking ship when AFK

**D. Proportional to Power Output**
- Fuel consumption scales with how much the engine contributes
- Higher speeds = more fuel
- Most realistic but complex

### Fuel Values
Use existing Minecraft fuel tick values:
| Fuel | Ticks | Real Time |
|------|-------|-----------|
| Coal | 1600 | 80 sec |
| Coal Block | 16000 | 13.3 min |
| Charcoal | 1600 | 80 sec |
| Blaze Rod | 2400 | 120 sec |
| Lava Bucket | 20000 | 16.7 min |

### Engine GUI
- Custom inventory menu (not the normal blast furnace smelting UI)
- Shows: fuel slot, fuel remaining bar, engine status
- Only accepts valid furnace fuels

---

## Copper Network (Optional Enhancement)

From the TODO: "any blast furnaces connected via copper network to ships wheel will use fuel"

### If Implemented:
- Engines must connect to the ship wheel via copper blocks
- Path-finding from wheel to engine through copper blocks
- Encourages interesting ship designs with visible "wiring"

### Simpler Alternative:
- Engines work anywhere on the ship
- No connection required
- Much simpler to implement

**Recommendation:** Start without copper network, add it later as an optional enhancement.

---

## Ship Info Display

The ship info menu should show:
```
=== Ship Stats ===
Mass: 450
Sails: 35 points (12 wool, 8 banners)
Engines: 2 (1 fueled, 1 empty)

Speed: 120% (base 0.55 -> 0.66)
Acceleration: 95%
Rotation: 85%

[Highlight Sails] [Manage Engines]
```

---

## Implementation Phases

### Phase 1: Detection & Data Storage
1. Count wool blocks and banners during ship scan
2. Detect ship engines (custom blast furnaces)
3. Add fields to `ShipModel`: `sailPoints`, `engineLocations`
4. Add fields to `ShipWheelData`: `engineFuelLevels` (Map<BlockPos, Integer>)

### Phase 2: Basic Stat Calculation
5. Implement chosen math formula
6. Apply multipliers in `ShipPhysics.update()`
7. Test with various ship configurations

### Phase 3: Engine Fuel System
8. Create ship engine item (custom blast furnace recipe?)
9. Create engine fuel GUI
10. Implement fuel consumption
11. Real-time stat updates when fuel runs out

### Phase 4: UI & Polish
12. Update ship info menu with new stats
13. Add engine management to menu
14. Visual feedback (particles when engines running?)

---

## Key Files

| File | Changes |
|------|---------|
| `BlockStructureScanner.java` | Count sails, detect engines |
| `ShipModel.java` | Store sail/engine data |
| `ShipWheelData.java` | Persist engine fuel state |
| `ShipPhysics.java` | Apply stat multipliers |
| `ShipConfig.java` | Base multiplier config values |
| New: `ShipEngine.java` | Engine item/logic |
| New: `EngineMenuGUI.java` | Fuel management GUI |

---

## Open Questions

1. **Math formula:** Which option (A/B/C/D)?
2. **Per-stat scaling:** Should sails affect rotation less than speed?
3. **Fuel consumption:** When does fuel tick down?
4. **Copper network:** Implement now, later, or never?
5. **Engine crafting:** Recipe? Or just NBT on any blast furnace?
6. **Visual feedback:** Particles/sounds when engines running?
