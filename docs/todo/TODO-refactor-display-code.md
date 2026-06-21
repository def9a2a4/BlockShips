---
status: planned
---

# Refactor: Extract Skull/Banner Transform Calculation

## Problem
The skull and banner transform calculation is duplicated in 3 places in `ShipInstance.java`:
1. **Lines 387-414**: Spawn transform (applied to entity via `setTransformationMatrix`)
2. **Lines 457-501**: `displayTransform` stored for tick updates
3. **Lines 1912-1943**: Similar code in another method (likely ship loading)

Every time we fix a bug (like the wall skull positioning), we have to update all 3 locations identically.

## Solution
Extract the transform calculation into helper methods that return a `Matrix4f`.

### New Helper Methods

```java
/**
 * Calculate the transform matrix for a skull display entity.
 * @param baseTransform The base local transform
 * @param rawYaml The block's raw YAML data containing skull_rotation or skull_facing
 * @return The complete transform matrix
 */
private Matrix4f calculateSkullTransform(Matrix4f baseTransform, Map<String, Object> rawYaml) {
    Matrix4f transform = new Matrix4f(baseTransform);

    float skullYaw = 0.0f;
    boolean isWallSkull = rawYaml.containsKey("skull_facing");

    if (rawYaml.containsKey("skull_rotation")) {
        BlockFace rotation = safeBlockFace(rawYaml, "skull_rotation", BlockFace.NORTH);
        skullYaw = getYawFromBlockFace(rotation);
    } else if (isWallSkull) {
        BlockFace facing = safeBlockFace(rawYaml, "skull_facing", BlockFace.NORTH);
        skullYaw = getYawFromBlockFace(facing);
    }

    if (isWallSkull) {
        // Wall skulls: +0.25 Y offset, +180° yaw, +0.25 Z toward wall
        transform.translate(0.5f, 0.5f + 0.25f, 0.5f);
        transform.rotateY((float) Math.toRadians(-skullYaw + 180));
        transform.translate(0.0f, 0.0f, 0.25f);
    } else {
        // Floor skulls: centered at block center
        transform.translate(0.5f, 0.5f, 0.5f);
        transform.rotateY((float) Math.toRadians(-skullYaw));
    }

    return transform;
}

/**
 * Calculate the transform matrix for a banner display entity.
 */
private Matrix4f calculateBannerTransform(Matrix4f baseTransform, Map<String, Object> rawYaml) {
    // Similar extraction for banner logic
}
```

### Usage
Replace all 3 locations with:
```java
Matrix4f skullTransform = calculateSkullTransform(finalTransform, p.rawYaml);
id.setTransformationMatrix(skullTransform);
```

and:
```java
displayTransform = calculateSkullTransform(new Matrix4f(p.local), p.rawYaml);
```

## Files to Modify
- `blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java`

## Implementation Steps
1. Add `calculateSkullTransform()` helper method
2. Add `calculateBannerTransform()` helper method
3. Replace spawn transform code (lines ~387-414) with helper call
4. Replace displayTransform code (lines ~457-501) with helper call
5. Replace third location (lines ~1912-1943) with helper call
6. Verify all 3 locations produce identical results
