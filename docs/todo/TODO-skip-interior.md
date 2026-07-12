---
status: planned
issue: 8
---

# Skip Interior Collision Entities Entirely

## Context
Instead of spawning all 100 collision entities and skipping interior ones at runtime, **don't spawn carrier+shulker pairs for interior blocks at all**. A 100-collider ship might drop to ~35 perimeter colliders = 130 fewer entities (~305 → ~175). This saves per-tick teleports, getPassengers() checks, AND entity overhead — not just terrain collision.

## What's safe to skip

A block is "interior" if all 6 face-neighbors also have collision-enabled blocks. Players can never reach interior blocks (they're inside solid walls).

| System | Impact | Safe? |
|--------|--------|-------|
| **Disassembly (block placement)** | Iterates `model.parts`, not `colliders` | Yes |
| **Entity cleanup (`destroy()`)** | Iterates `colliders` — just fewer items | Yes |
| **Orphan cleanup** | Scans by scoreboard tag | Yes |
| **Player collision (walking)** | Interior blocks unreachable | Yes |
| **Display entities** | Spawned from `model.parts`, separate system | Yes |
| **Lead transfer** | Iterates `colliders`, skips missing | Yes |
| **Dynlight delegation** | Interior = invisible inside solid walls | Yes |
| **Inventory preservation** | Stored in `ShipInstance.storages` map + `part.rawYaml["container_items"]`, NOT on shulker. Disassembly restores from rawYaml. | Yes |

**Exception:** Storage containers always get a collider even if interior, so players can access inventory. (Defensive — a truly interior chest is unreachable, but avoids any edge case.)

**Recovery (old saves):** `tryAddEntity()` needs NO changes. Old saves have interior entities in the world + higher `expectedEntityCount`. Recovery finds all of them, `current >= expected` passes, ship works. Interior colliders naturally disappear on next destroy/respawn cycle. New saves use the lower count from `countEntities()`.

## Changes

### 1. Perimeter classification during assembly
**File:** ShipInstance.java:338-394

Extract `posMap` construction out of `if (SHIP_LIGHTS_ENABLED)` guard so it's always available. Add perimeter classification reusing the same `posMap` and 6-neighbor pattern from dynlight occlusion (line 354).

```java
// Build position index (reused by dynlight + perimeter classification)
Map<String, Integer> posMap = new HashMap<>();
for (int i = 0; i < model.parts.size(); i++) {
    Matrix4f m = model.parts.get(i).local;
    int x = Math.round(m.m30()), y = Math.round(m.m31()), z = Math.round(m.m32());
    posMap.put(x + "," + y + "," + z, i);
}

// Classify perimeter collision blocks (+ always include storage/seat/leadable/interaction)
Set<Integer> perimeterBlocks = new HashSet<>();
int[][] allNeighbors = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
for (int i = 0; i < model.parts.size(); i++) {
    ShipModel.ModelPart mp = model.parts.get(i);
    if (!mp.collision.enable) continue;

    // Always spawn colliders for interactive blocks
    if (mp.rawYaml.containsKey("storage") || mp.rawYaml.containsKey("seat_index")
            || mp.rawYaml.containsKey("leadable") || mp.rawYaml.containsKey("interaction")) {
        perimeterBlocks.add(i);
        continue;
    }

    Matrix4f m = mp.local;
    int x = Math.round(m.m30()), y = Math.round(m.m31()), z = Math.round(m.m32());
    for (int[] d : allNeighbors) {
        Integer ni = posMap.get((x+d[0]) + "," + (y+d[1]) + "," + (z+d[2]));
        if (ni == null || !model.parts.get(ni).collision.enable) {
            perimeterBlocks.add(i);
            break;
        }
    }
}
```

Dynlight code follows, reusing `posMap` (remove its duplicate construction from inside the `SHIP_LIGHTS_ENABLED` guard).

### 2. Skip spawning interior collision entities
**File:** ShipInstance.java:662-764

Gate the collision shulker spawn on perimeter membership:

```java
if (p.collision.enable && perimeterBlocks.contains(currentBlockIndex)) {
    // ... spawn carrier + shulker as before ...
}
```

Interior blocks simply don't get carrier+shulker pairs.

### 3. Fix countEntities()
**File:** ShipInstance.java:~2085

Replace `model.parts.stream().filter(collision).count() * 2` with `colliders.size() * 2` to reflect actual spawned count.

### 4. Micro-optimizations in calculateTerrainCollisionForce()
**File:** ShipCollision.java:305-363

**a. Reusable BoundingBox:** Line 321 allocates `new BoundingBox(0,0,0,1,1,1)` per call (100x/tick when moving). Add field `private final BoundingBox workBlockBox = new BoundingBox(...)` alongside existing `workTerrainForce` etc. Replace local alloc with field — safe because `resize()` is called before each use.

**b. Cache `block.getType()` in local:** Line 330 calls `block.getType()` twice on same block. Store in local `Material type = block.getType()`.

## Impact (100-collider ship, ~35 perimeter)

| Metric | Before | After |
|--------|--------|-------|
| Collision entities | 200 (100 carriers + 100 shulkers) | ~70 (35 + 35) |
| Total entities | ~305 | ~175 |
| Carrier teleports/tick | 100 | ~35 |
| getPassengers() checks/tick | 100 | ~35 |
| Terrain getBlockAt()/tick | ~1200 | ~420 |

Heavy case (5 ships x 500 colliders, ~25% perimeter):
- Entities: 5x1005 -> 5x255 = **3,750 fewer entities**
- Terrain lookups: 30,000 -> ~7,500/tick

## Verification
- `make build`
- Ship bounces off walls correctly (terrain collision works with perimeter-only colliders)
- Players can walk on all reachable surfaces
- Hollow/L-shaped/single-block ships classified correctly
- Disassembly places ALL blocks and restores ALL inventories (not just perimeter)
- Storage containers inside thick hulls still accessible (exception rule)
- Dynlight tags still applied correctly after posMap extraction
- Lead transfer works during disassembly
- Old saves load and recover correctly (interior entities adopted harmlessly)
