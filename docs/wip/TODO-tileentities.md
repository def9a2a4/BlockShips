# Tile Entity Support Improvements

Relates to: https://github.com/def9a2a4/BlockShips/issues/23

## Problem

Several block types with tile entity data don't work properly and/or are disabled:
- Signs (work, but text lost on assembly/disassembly)
- Chiseled bookshelves (forbidden — not in blocks.yml)

## Reference

- SimpleShips implementation: `~/projects/minecraft/temp/SimpleShips/src/main/java/simpleships/Ship.java`
  - Shelf handling: lines 1078-1150 (uses `instanceof Shelf`, ItemDisplays for contents)
  - Sign handling: lines 943-1036 (wood planks as visual, preserves text for restoration)
- GitHub discussion: https://github.com/jemcdevitt/SimpleShips/issues/1

## Key Insights (from SimpleShips dev + research)

1. **Chiseled bookshelves**: BlockData encodes `slot_0_occupied` through `slot_5_occupied` — the BlockDisplay already shows correct filled/empty slot visuals. NO extra ItemDisplays needed. Just serialize inventory for restoration on disassembly.

2. **Signs**: Text CANNOT be displayed on BlockDisplay entities (Minecraft limitation). SimpleShips dev confirmed: attempted TextDisplay overlay but got transparency/rendering issues where the text became transparent and showed water behind it instead of the sign. For now, just serialize text and restore on disassembly.

3. **Item duplication prevention**: Must clear inventory from block BEFORE removing it with `setType(AIR)`. Some blocks may drop items when set to air. NOTE: the existing Container serialization code also has this bug — containers are serialized but never cleared before block removal. Should fix for all containers.

## Paper API Notes (verified against 1.21.11)

| Block Type | Implements | Inventory Access | Special Data |
|---|---|---|---|
| ChiseledBookshelf | `TileStateInventoryHolder` (extends `BlockInventoryHolder` → `InventoryHolder`) | `getInventory()` / `getSnapshotInventory()` | slot occupancy in blockdata |
| Sign | `TileState` + `Colorable` | N/A | `getSide(Side)` → `SignSide.lines()`, `.getColor()`, `.isGlowingText()`, `.isWaxed()` |

- `GsonComponentSerializer` is available at runtime via Adventure transitive dependency (not in Paper sources jar directly)
- `ChiseledBookshelf` does NOT implement `Container` — the existing `instanceof Container` restoration path will NOT match it. Needs its own serialization and restoration blocks.

## Plan

### Priority Order

1. Chiseled bookshelves (most requested per #23, inventory preservation)
2. Sign text preservation on disassembly (data integrity, no visual change)

---

### A. Chiseled Bookshelves

**blocks.yml:**
```yaml
chiseled_bookshelf:
  allowed: true
  weight: 1
  collider: true
```

**BlockStructureScanner — serialization** (add after existing `instanceof Container` check):
```java
// Chiseled bookshelves (TileStateInventoryHolder, not Container)
if (blockState instanceof org.bukkit.block.ChiseledBookshelf shelf) {
    serializeInventory(shelf.getSnapshotInventory(), blockYaml);
    shelf.getInventory().clear();
    shelf.update();
}
```

**BlockStructureScanner — restoration** (add in `placeBlocks()` after Container restoration):
```java
if (blockState instanceof org.bukkit.block.ChiseledBookshelf shelf
        && blockYaml.containsKey("container_items")) {
    restoreInventory(shelf.getSnapshotInventory(), blockYaml);
    shelf.update();
}
```

Note: `ChiseledBookshelf` implements `TileStateInventoryHolder` (extends `BlockInventoryHolder` → `InventoryHolder`), NOT `Container`. The existing `instanceof Container` restoration path will NOT match it — needs its own restoration block.

---

### B. Sign Text Preservation

**BlockStructureScanner — serialization:**
```java
// Signs (preserve text for disassembly restoration)
if (blockState instanceof org.bukkit.block.Sign sign) {
    Map<String, Object> signData = new HashMap<>();
    for (org.bukkit.block.sign.Side side : org.bukkit.block.sign.Side.values()) {
        var signSide = sign.getSide(side);
        List<String> lines = new ArrayList<>();
        for (net.kyori.adventure.text.Component line : signSide.lines()) {
            lines.add(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
                .gson().serialize(line));
        }
        String key = side.name().toLowerCase();
        signData.put(key + "_lines", lines);
        signData.put(key + "_color", signSide.getColor().name());
        signData.put(key + "_glowing", signSide.isGlowingText());
    }
    signData.put("waxed", sign.isWaxed());
    blockYaml.put("sign_data", signData);
}
```

**BlockStructureScanner — restoration:**
```java
if (blockState instanceof org.bukkit.block.Sign sign && blockYaml.containsKey("sign_data")) {
    Map<String, Object> signData = (Map<String, Object>) blockYaml.get("sign_data");
    for (org.bukkit.block.sign.Side side : org.bukkit.block.sign.Side.values()) {
        var signSide = sign.getSide(side);
        String key = side.name().toLowerCase();
        List<String> lines = (List<String>) signData.get(key + "_lines");
        if (lines != null) {
            for (int i = 0; i < lines.size() && i < 4; i++) {
                signSide.line(i, GsonComponentSerializer.gson().deserialize(lines.get(i)));
            }
        }
        String color = (String) signData.get(key + "_color");
        if (color != null) signSide.setColor(DyeColor.valueOf(color));
        Boolean glowing = (Boolean) signData.get(key + "_glowing");
        if (glowing != null) signSide.setGlowingText(glowing);
    }
    Boolean waxed = (Boolean) signData.get("waxed");
    if (waxed != null) sign.setWaxed(waxed);
    sign.update();
}
```

Note: Uses 1.20+ Sign API (`getSide(Side.FRONT/BACK)`, `lines()`). Adventure component serialized as JSON string for YAML storage. Hanging signs use the same `Sign` interface — no special handling needed.

---

## API Corrections (from code review)

The pseudocode above uses simplified function names. Actual codebase patterns differ:

1. **`serializeInventory()`** — existing function takes ONE arg (`Inventory`) and returns `List<Map<String, Object>>`. NOT two args. Correct usage:
   ```java
   List<Map<String, Object>> items = serializeInventory(shelf.getSnapshotInventory());
   blockYaml.put("container_items", items);
   ```

2. **`restoreInventory()` does not exist** — the actual pattern (BlockStructureScanner.java ~line 842):
   ```java
   Container container = (Container) block.getState();
   ItemStack[] items = deserializeInventory(itemsData, container.getSnapshotInventory().getSize());
   container.getSnapshotInventory().setContents(items);
   container.update();
   ```

3. **BlockData capture ordering** — `block.getBlockData()` is called at line 328 (before serialization code runs). This captured `blockData` is stored as a string at line 471. Clearing inventory later does NOT affect the captured blockdata — so chiseled bookshelf `slot_X_occupied` fields in the stored blockdata correctly reflect the pre-clear state.

4. **Existing container duplication bug** — `removeBlocks()` (line 866) never clears inventories before `setType(AIR)`. The serialization path also never clears. Fix this for ALL containers in the same PR.

5. **No ShipModel changes needed** — all data goes through `rawYaml` (`Map<String, Object>`) which is freeform.

## Files to Modify

- `blockships/src/main/resources/blocks.yml` — add chiseled_bookshelf entry
- `blockships/src/main/java/anon/def9a2a4/blockships/customships/BlockStructureScanner.java` — tile entity serialization + restoration

## Future Work (not in this PR)

- **Sign text display**: TextDisplay overlay on sign face — has rendering/transparency issues per SimpleShips dev. Revisit if Minecraft fixes display entity layering.
- **Decorated pots**: Sherd data is in BlockEntity NBT (not blockdata). Need `DecoratedPot.getSherds()`/`setSherd(Side, Material)` for preservation. Single-item inventory via `TileStateInventoryHolder`. BlockDisplay shows blank pot (no sherds) during flight.
- **Campfires**: 4 cooking slots via direct `getItem(int)`/`setItem(int, ItemStack)` (NOT InventoryHolder). Partial hitbox (7/16 = 0.4375 blocks tall). Cooking progress lost on disassembly.
- **Item frames**: Could use birch plank + terracotta + ItemDisplay approach from SimpleShips. Complex entity handling (not a block).
- **Beds**: Multi-block support needed. Low priority.
- **Lecterns**: Book + page state preservation.

## Testing

1. Chiseled bookshelf: place with books → assemble → move ship → disassemble → verify books restored
2. Chiseled bookshelf: verify BlockDisplay shows filled slots correctly during movement
3. Chiseled bookshelf: verify no item duplication (inventory cleared before block removal)
4. Chiseled bookshelf: test empty bookshelf (no books) — should work as plain block
5. Signs: place with text → assemble → disassemble → verify text, color, glow state preserved
6. Signs: test hanging signs, wall signs, standing signs
7. Signs: test blank sign (no text) — no crash on empty lines
8. Verify existing containers (chests, hoppers) still work after the duplication bugfix
