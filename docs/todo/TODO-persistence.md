# TODO: Eliminate YAML for persistence

Move all runtime ship/wheel state out of plugin-written YAML and into Minecraft-native storage
(entity & block `PersistentDataContainer` + scoreboard-tag scanning), so the plugin writes **zero**
persistence files.

## Motivation

Persistence YAML is the last thing the plugin writes to disk, and it is largely redundant with
Minecraft's own chunk-NBT persistence:

- The carrier `ArmorStand`, every child `BlockDisplay`, and the collision `Shulker`s are spawned
  `setPersistent(true)`
  ([ShipInstance.java:324-343](../../blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java#L324),
  [556-695](../../blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java#L556)).
  They survive a server restart inside chunk NBT — Minecraft persists them, not us.
- Recovery already **re-adopts** those entities by scoreboard tag (`displayship:<uuid>:root`, etc.)
  rather than respawning them from YAML
  ([recoverEntities():2135](../../blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java#L2135),
  [fromState():241](../../blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java#L241)).
- Ship wheels are real `PLAYER_HEAD` blocks with a `Skull` tile entity, which exposes a PDC.

So the irreducible metadata can ride along inside the entities/blocks the plugin already re-adopts:
carrier-entity PDC for ship metadata, wheel-skull PDC for wheel state — both saved with chunk NBT —
with discovery done by scanning entities/tiles on `ChunkLoadEvent`.

**Goal:** after this work the data-folder root holds no plugin-written persistence files
(`worlds/`, `ship_wheels.yml`, `ships.yml` all gone).

## Current state — what gets written today

| File | Writer | Contents |
|---|---|---|
| `worlds/<world>/ships/<uuid>.yml` | [ShipWorldData.buildShipMetadataConfig():170](../../blockships/src/main/java/anon/def9a2a4/blockships/ShipWorldData.java#L170) | `id`, `ship_type`, `model_path` (prefab) / `model_data` (custom, ~200–400 KB), `wood_type`, `balloon_color`, `banner` (Base64), `inventories` (blockIndex → Base64 items), `current_yaw` |
| `worlds/<world>/chunks.yml` | [ShipWorldData.saveAllChunkIndicesAsync():232](../../blockships/src/main/java/anon/def9a2a4/blockships/ShipWorldData.java#L232) | `chunkKey` → `[shipUUID]` index |
| `ship_wheels.yml` | [ShipWheelManager:70](../../blockships/src/main/java/anon/def9a2a4/blockships/customships/ShipWheelManager.java#L70) | per-wheel `ShipWheelData` |
| `ships.yml` | [ShipPersistence.saveAll():33](../../blockships/src/main/java/anon/def9a2a4/blockships/ShipPersistence.java#L33) | legacy global ship list |

Reusable serialization that already exists (keep it, just retarget the sink):

- `ShipState` + `toMap()` / `fromMap()`
  ([ShipPersistence.java:206-380](../../blockships/src/main/java/anon/def9a2a4/blockships/ShipPersistence.java#L206))
- `ShipModel.toMap()` / `fromMap()`

Existing PDC keys already in use elsewhere (precedent for the pattern): `ship_type`, `wood_type`,
`banner_data`, `balloon_color`, `custom_item_id`.

## Target design

### A. Ship metadata → carrier `ArmorStand` PDC

Store a serialized `ShipState` on the root ArmorStand under a single key
(`NamespacedKey(plugin, "ship_state")`), via a `PersistentDataType` (BYTE_ARRAY, or a YAML/JSON
string). Drop the position fields — the entity already provides position; keep `ship_type`,
`model_path` / `model_data`, `wood_type`, `balloon_color`, `banner`, `current_yaw`, `entity_count`,
and `inventories`.

Write on the **same triggers as today** (creation, inventory change/close, disassemble → remove,
periodic / chunk-unload). The difference: PDC persists automatically with chunk NBT, so there is no
separate file write.

**Recommended optimization — don't store `model_data` at all.** For custom ships, reconstruct the
`ShipModel` from the persistent child `BlockDisplay`s on recovery: each carries its `blockData`,
transform, and `displayIndex` tag. This removes the ~200–400 KB per-ship blob entirely; only
lightweight metadata stays in the carrier PDC. Collider/storage/seat flags come from the existing
entity tags (or a compact per-block descriptor list) rather than full matrices.

**Inventories.** Store them in the carrier PDC blob (simplest — single source of truth), matching
today's YAML cadence. Alternative: push each storage block's contents into its own collision
`Shulker`'s PDC.

### B. Discovery without `chunks.yml`

On `ChunkLoadEvent`, scan `chunk.getEntities()` for a root-tagged `ArmorStand` not yet in
`ShipRegistry` → read its PDC → `ShipInstance.fromState()` → `recoverEntities(chunk)`. Multi-chunk
ships pick up their remaining entities as those chunks load via the existing
`collectEntitiesFromChunk`. At `onEnable`, iterate already-loaded chunks/worlds (reuse the existing
`recoverUnregisteredShips` spawn-chunk path).

Optional perf guard: set a marker on the **Chunk** PDC for chunks that contain a ship root, giving a
cheap "skip empty chunk" pre-check; otherwise the (small) per-chunk entity-list scan is acceptable.

### C. Wheels → skull block PDC

The wheel `PLAYER_HEAD`'s `Skull` `TileState` exposes a PDC. Store: `facing`, `assembledShipUUID`,
`engineFuelSlots` (serialized `ItemStack[]` per engine index), `engineBurnTicks`,
`lastCurrent/MaxHealth`, `cameraDistance`. Drop the transient `lastDetected*` preview fields — they
are recomputed on the next detection. Discover on `ChunkLoadEvent` via `chunk.getTileEntities()`,
filtered to skulls carrying the wheel PDC marker, and register them in `ShipWheelManager`. Block
removal is already handled by the break logic, so no file cleanup is needed.

### D. Remove writers + one-version migration

Delete the file I/O in `ShipWorldData`, `ShipPersistence` (`ships.yml`), and `ShipWheelManager`.
Keep a migration shim for one release, then remove it: if legacy YAML exists, import lazily — when a
ship's chunk loads and its carrier has no `ship_state` PDC but a legacy
`worlds/.../ships/<uuid>.yml` record exists, import it into PDC; likewise import each wheel from
`ship_wheels.yml` as its chunk loads. Delete the legacy files once imported.

## Files to touch (when implemented)

- [ShipWorldData.java](../../blockships/src/main/java/anon/def9a2a4/blockships/ShipWorldData.java) — remove YAML save/load; replace with PDC read/write helpers.
- [ShipPersistence.java](../../blockships/src/main/java/anon/def9a2a4/blockships/ShipPersistence.java) — keep the `ShipState` DTO + `toMap`/`fromMap` for PDC serialization; drop `ships.yml` I/O.
- [ShipInstance.java](../../blockships/src/main/java/anon/def9a2a4/blockships/ship/ShipInstance.java) — add `write/readStateToCarrierPDC()`; optional model reconstruction from displays; `fromState`/`recoverEntities` stay structurally the same.
- [ShipWheelManager.java](../../blockships/src/main/java/anon/def9a2a4/blockships/customships/ShipWheelManager.java) / [ShipWheelData.java](../../blockships/src/main/java/anon/def9a2a4/blockships/customships/ShipWheelData.java) — add `to/fromSkullPDC`; remove `ship_wheels.yml` I/O.
- [DisplayShip.java](../../blockships/src/main/java/anon/def9a2a4/blockships/DisplayShip.java) — schedule PDC writes; `ChunkLoadEvent` discovery; `onEnable` loaded-chunk scan; drop `saveAll` on shutdown (flush any pending PDC writes instead).
- New: a PDC serialization util + `NamespacedKey`s (`ship_state`, `wheel_state`, optional chunk marker).

## Risks / tradeoffs

- **No human-readable backup.** State lives only in chunk NBT; corruption is unrecoverable. Mitigation: an optional debug export command.
- **Large per-entity PDC** if the full `model_data` blob is kept — avoided by reconstructing the model from the child displays.
- **Loss of "enumerate ships/wheels in unloaded chunks."** The `chunks.yml` index gave cross-world listing without loading chunks. Audit any `/ship list`-style command for this dependency before removing it.
- **Crash before a world save** loses changes since the last save — the same guarantee as vanilla. Ensure PDC writes happen at the same change points the YAML writes do today.
- **Folia / threading.** Entity & block PDC writes must run on the owning region thread (see [TODO-folia.md](TODO-folia.md)).
- **Migration correctness** across the one-version import window.

## Verification (for the eventual implementation)

- `make build`.
- Create a custom ship, restart the server: it recovers with **no** `worlds/` or `ship_wheels.yml`
  written; inventories, engine fuel, health, and yaw are preserved.
- Confirm the data folder holds no persistence YAML after a run.
- Migration: start with legacy YAML present → confirm it is imported, then the files are removed.
- Multi-chunk ship: confirm adoption as each chunk loads.

## Out of scope

- The config/resource-loading refactor (separate task — stop extracting `blocks.yml` / `items.yml` /
  prefab ships, read from jar or `config/`).
- Changes to the live rendering/physics pipeline.
