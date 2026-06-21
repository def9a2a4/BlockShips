---
status: in-progress
issue: 23
version: v0.0.16
commit: e9b6757
---

# Tile Entity Support Improvements

Relates to: https://github.com/def9a2a4/BlockShips/issues/23

## Problem

Several block types with tile entity data don't work properly and/or are disabled:
- Signs (work, but text lost on assembly/disassembly)
- Chiseled bookshelves (forbidden — not in blocks.yml)
- Shelves (1.21.9+, forbidden — not in blocks.yml)

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
| Shelf (12 wood variants, 1.21.9+) | `TileStateInventoryHolder` | `getInventory()` / `getSnapshotInventory()` | 3 slots. Items in tile entity, NOT blockdata. BlockDisplay shows empty shelf during flight. |
| ChiseledBookshelf | `TileStateInventoryHolder` | `getInventory()` / `getSnapshotInventory()` | 6 slots. `slot_X_occupied` booleans in blockdata show filled/empty. |
| Sign | `TileState` + `Colorable` | N/A | `getSide(Side)` → `SignSide.lines()`, `.getColor()`, `.isGlowingText()`, `.isWaxed()` |

- Both `Shelf` and `ChiseledBookshelf` implement `TileStateInventoryHolder` (NOT `Container`). One `instanceof TileStateInventoryHolder` check handles both.
- `GsonComponentSerializer` is available at runtime via Adventure transitive dependency (not in Paper sources jar directly)

## Plan

### Priority Order

1. Shelves + chiseled bookshelves (inventory preservation via unified `TileStateInventoryHolder` check)
2. Sign text preservation on disassembly (data integrity, no visual change)

---

### A. Shelves + Chiseled Bookshelves (unified via TileStateInventoryHolder)

**blocks.yml:**
```yaml
chiseled_bookshelf:
  allowed: true
  weight: 1
  collider: true

"*_shelf":
  allowed: true
  weight: 1
  collider: true
```

**BlockStructureScanner — serialization** (add after existing `instanceof Container` check):
```java
// Shelves and chiseled bookshelves (TileStateInventoryHolder, not Container)
if (blockState instanceof io.papermc.paper.block.TileStateInventoryHolder tileInv) {
    List<Map<String, Object>> tileItems = serializeInventory(tileInv.getSnapshotInventory());
    if (!tileItems.isEmpty()) {
        rawYaml.put("container_items", tileItems);
    }
    tileInv.getInventory().clear();
    tileInv.update();
}
```

**BlockStructureScanner — restoration** (add in `placeBlocks()` after Container restoration):
```java
if (part.rawYaml.containsKey("container_items")
        && block.getState() instanceof io.papermc.paper.block.TileStateInventoryHolder tileInv) {
    List<Map<String, Object>> itemsData = (List<Map<String, Object>>) part.rawYaml.get("container_items");
    ItemStack[] items = deserializeInventory(itemsData, tileInv.getSnapshotInventory().getSize());
    tileInv.getSnapshotInventory().setContents(items);
    tileInv.update();
}
```

Notes:
- One `instanceof TileStateInventoryHolder` check handles both `Shelf` (3 slots) and `ChiseledBookshelf` (6 slots)
- Shelves show empty during flight (items in tile entity, not blockdata). Items restored on disassembly.
- Chiseled bookshelves show filled/empty slot visuals from blockdata during flight.
- Inventory cleared before block removal to prevent item duplication.

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

- `blockships/src/main/resources/blocks.yml` — add chiseled_bookshelf + `*_shelf` wildcard entries
- `blockships/src/main/java/anon/def9a2a4/blockships/customships/BlockStructureScanner.java` — tile entity serialization + restoration

## Future Work (not in this PR)

- **Shelf item display during flight**: Spawn ItemDisplays for each of the 3 shelf slots so items are visible during movement. Requires extending the display index/tagging/recovery system to support multiple display entities per block (currently 1:1). SimpleShips reference: `Ship.java:1078-1150` — items at 0.25 scale, spaced 20/64 apart perpendicular to facing, different rotation for block vs non-block items.
- **Sign text display**: TextDisplay overlay on sign face — has rendering/transparency issues per SimpleShips dev. Revisit if Minecraft fixes display entity layering.
- **Decorated pots**: Sherd data is in BlockEntity NBT (not blockdata). Need `DecoratedPot.getSherds()`/`setSherd(Side, Material)` for preservation. Single-item inventory via `TileStateInventoryHolder`. BlockDisplay shows blank pot (no sherds) during flight.
- **Campfires**: 4 cooking slots via direct `getItem(int)`/`setItem(int, ItemStack)` (NOT InventoryHolder). Partial hitbox (7/16 = 0.4375 blocks tall). Cooking progress lost on disassembly.
- **Item frames**: Could use birch plank + terracotta + ItemDisplay approach from SimpleShips. Complex entity handling (not a block).
- **Beds**: Multi-block support needed. Low priority.
- **Lecterns**: Book + page state preservation.

## Testing

1. Shelf: place with items → assemble → move ship → disassemble → verify items restored
2. Shelf: verify no item duplication (inventory cleared before block removal)
3. Shelf: test empty shelf — should work as plain block
4. Chiseled bookshelf: place with books → assemble → move ship → disassemble → verify books restored
5. Chiseled bookshelf: verify BlockDisplay shows filled slots correctly during movement
6. Chiseled bookshelf: verify no item duplication
7. Chiseled bookshelf: test empty bookshelf — should work as plain block
8. Signs: place with text → assemble → disassemble → verify text, color, glow state preserved
9. Signs: test hanging signs, wall signs, standing signs
10. Signs: test blank sign (no text) — no crash on empty lines
11. Verify existing containers (chests, hoppers) still work
