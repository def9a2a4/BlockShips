# Tile Entity Support Improvements

Relates to: https://github.com/def9a2a4/BlockShips/issues/23

## Problem

Several block types with tile entity data don't work properly and/or are disabled:
- Signs (work, but text lost on assembly/disassembly)
- Chiseled bookshelves (forbidden — not in blocks.yml)
- Decorated pots (forbidden — not in blocks.yml)
- Campfires (forbidden — not in blocks.yml)

## Reference

- SimpleShips implementation: `~/projects/minecraft/temp/SimpleShips/src/main/java/simpleships/Ship.java`
  - Shelf handling: lines 1078-1150 (uses `instanceof Shelf`, ItemDisplays for contents)
  - Sign handling: lines 943-1036 (wood planks as visual, preserves text for restoration)
- GitHub discussion: https://github.com/jemcdevitt/SimpleShips/issues/1

## Key Insights (from SimpleShips dev + research)

1. **Chiseled bookshelves**: BlockData encodes `slot_0_occupied` through `slot_5_occupied` — the BlockDisplay already shows correct filled/empty slot visuals. NO extra ItemDisplays needed. Just serialize inventory for restoration on disassembly.

2. **Decorated pots**: Sherd appearance is stored in the blockdata string (already preserved by our scanner). On 1.21+ they have a single-item inventory. Just add to blocks.yml + serialize that one item.

3. **Campfires**: Cooking items visible on the campfire. Need to serialize 4 item slots. Cooking progress (per-slot tick timers) will be lost on disassembly — acceptable tradeoff.

4. **Signs**: Text CANNOT be displayed on BlockDisplay entities (Minecraft limitation). SimpleShips dev confirmed: attempted TextDisplay overlay but got transparency/rendering issues where the text became transparent and showed water behind it instead of the sign. For now, just serialize text and restore on disassembly.

5. **Item duplication prevention**: Must clear inventory from block BEFORE removing it with `setType(AIR)`. Some blocks may drop items when set to air.

## Plan

### Priority Order

1. Chiseled bookshelves (most requested, straightforward)
2. Decorated pots (blockdata handles visuals, just needs blocks.yml + inventory)
3. Campfires (cooking items)
4. Sign text preservation on disassembly (data integrity, no visual change)

---

### A. Chiseled Bookshelves

**blocks.yml:**
```yaml
chiseled_bookshelf:
  allowed: true
  weight: 1
  collider: true
```

**BlockStructureScanner** — add after existing `instanceof Container` check (~line 443):
```java
// Chiseled bookshelves (InventoryHolder but not Container)
if (blockState instanceof org.bukkit.block.ChiseledBookshelf shelf) {
    serializeInventory(shelf.getInventory(), blockYaml);
    shelf.getInventory().clear();
    shelf.update();
}
```

**Disassembly** — restore inventory to placed block after `setBlockData()`.

Note: `ChiseledBookshelf` implements `InventoryHolder` but NOT `Container`. Must use explicit type check, not broad `instanceof InventoryHolder` (which would match custom menu holders).

---

### B. Decorated Pots

**blocks.yml:**
```yaml
decorated_pot:
  allowed: true
  weight: 1
  collider:
    size: [0.75, 0.75, 0.75]
    offset: [0.125, 0.0, 0.125]
```

**BlockStructureScanner:**
```java
// Decorated pots (single item inventory on 1.21+)
if (blockState instanceof org.bukkit.block.DecoratedPot pot) {
    if (pot instanceof InventoryHolder ih) {
        serializeInventory(ih.getInventory(), blockYaml);
        ih.getInventory().clear();
        pot.update();
    }
}
```

Sherd appearance is already in the blockdata string — no extra visual work needed. The BlockDisplay will show the correct sherds.

---

### C. Campfires

**blocks.yml:**
```yaml
campfire:
  allowed: true
  weight: 2
  collider: true

soul_campfire:
  allowed: true
  weight: 2
  collider: true
```

**BlockStructureScanner:**
```java
// Campfires (4 cooking item slots)
if (blockState instanceof org.bukkit.block.Campfire campfire) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = 0; i < campfire.getSize(); i++) {
        ItemStack item = campfire.getItem(i);
        if (item != null) {
            items.add(Map.of("slot", i, "data", serializeItemStack(item)));
            campfire.setItem(i, null);
        }
    }
    if (!items.isEmpty()) {
        blockYaml.put("campfire_items", items);
    }
    campfire.update();
}
```

Note: Campfire uses `org.bukkit.block.Campfire` interface (not InventoryHolder). Has `getItem(int)` / `setItem(int, ItemStack)` for 4 slots. Cooking tick progress is NOT serialized (acceptable loss).

---

### D. Sign Text Preservation

**BlockStructureScanner:**
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

**Disassembly restoration:**
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

Note: Uses 1.20+ Sign API (`getSide(Side.FRONT/BACK)`, `lines()`). Adventure component serialized as JSON string for YAML storage.

---

## Future Work (not in this PR)

- **Sign text display**: TextDisplay overlay on sign face — has rendering/transparency issues per SimpleShips dev. Revisit if Minecraft fixes display entity layering.
- **Item frames**: Could use birch plank + terracotta + ItemDisplay approach from SimpleShips. Complex entity handling (not a block).
- **Beds**: Multi-block support needed. Low priority.
- **Lecterns**: Book + page state preservation.

## Files to Modify

- `blockships/src/main/resources/blocks.yml` — add new block entries
- `blockships/src/main/java/anon/def9a2a4/blockships/customships/BlockStructureScanner.java` — tile entity serialization
- `blockships/src/main/java/anon/def9a2a4/blockships/customships/ShipWheelManager.java` — disassembly restoration
- `blockships/src/main/java/anon/def9a2a4/blockships/ShipModel.java` — possibly extend model for new data types

## Testing

1. Each block: place with content → assemble → move ship → disassemble → verify content restored
2. Verify no item duplication (critical — check inventories cleared before block removal)
3. Verify BlockDisplay renders correctly (chiseled bookshelf slots, pot sherds, campfire items)
4. Test with empty variants (empty bookshelf, empty pot, unlit campfire, blank sign)
