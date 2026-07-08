# v0.0.16 - Ship Stats, Sails & Engines, more block types, wider compatibility, bugfixes

# You probably want to reset your configs!

- **Ship speed now dependent on engines and sails:** Ships now have a power-to-mass ratio system that scales speed and rotation based on block composition ([#18](https://github.com/def9a2a4/BlockShips/issues/18))
  - Wool blocks act as sails (3 power), banners as upgraded sails (7 power), engines provide 30 power when fueled
  - Heavier ships are slower unless you add more power sources
  - Sail-only power is capped — engines required to push ships past 80% of max ratio
  - Engines use fuel, emit smoke when working
  - Ship info UI shows power ratio, effective speed percentage, and color-coded stats
  - Server admins who prefer the old fixed-speed behavior can turn the whole system off with `custom-ships.stats.enabled: false` — custom ships then use fixed default speeds and ignore sails/engines/mass
  - **The stats system is disabled by default** (`custom-ships.stats.enabled: false`) pending a rework in a future update. Set it to `true` to opt in

- **More blocks usable in custom ships:** Chiseled bookshelves, shelves (1.21.9+), and signs can now be part of a ship — their contents and sign text are preserved across assembly and disassembly ([#23](https://github.com/def9a2a4/BlockShips/issues/23))

- **ProtocolLib is now optional on Paper 1.21.2+:** Ship controls use Minecraft's native input event on newer servers, so you no longer need to install ProtocolLib there. Pre-1.21.2 servers still require it. Now builds against and supports up to 1.21.11 ([#28](https://github.com/def9a2a4/BlockShips/issues/28))

- **Ship rotation is now smooth and jitter-free!**
  - a recent performance update made this worse, now it's fixed!

- **Alternate (optional) destruction mode:** Ships can be configured to permanently destroy on death instead of disassembling ([#27](https://github.com/def9a2a4/BlockShips/issues/27))
  - Set `custom-ships.destruction-mode: destroy` in config.yml
  - Blocks are lost, but inventory contents, engine fuel, and mob leads are dropped
  - default is still to disassemble them into blocks on destruction

- **Towny compatibility:** ship entities now set an empty custom name to prevent Towny's mob removal timer from deleting them ([#17](https://github.com/def9a2a4/BlockShips/issues/17))
  - Previously, Towny would remove ship shulkers (collision/seat entities) in town chunks, ejecting players and ghosting the ship
  - Workaround config options for Towny are documented in the issue thread

- **Drowned captains are rarer:** the default `special-drowned` spawn chance is lowered from 5% to 2% so they don't overwhelm biomes where drowned spawn heavily ([#29](https://github.com/def9a2a4/BlockShips/issues/29))
- **New blocks now work without resetting configs:** `blocks.yml`, `items.yml`, and prefab ship models are read from the jar instead of a stale on-disk copy, so newly-supported blocks (like shelves) work on upgraded servers automatically. To customize, drop an edited copy under a new `config/` subfolder
- other various bugfixes and improvements

---

## New Features

### Ship Stats System (5da91fd) — [#18](https://github.com/def9a2a4/BlockShips/issues/18)

Ships now have a power-to-mass ratio that scales their speed and rotation rate. Every block contributes mass; specific blocks contribute power. The ratio between total power and total mass determines how fast the ship can move and turn.

Power sources:
- Base power: 2 free points per ship (ensures minimum performance)
- Wool blocks: 3 power points each (sails)
- Banner blocks: 7 power points each (upgraded sails)
- Engines: 30 power points each (when fueled)

The ratio maps linearly between a floor (minimum speed/rotation) and a cap (maximum multiplier over defaults). A ratio of 0.7 reproduces the previous default behavior. Sail-only power is capped at ratio 0.8 — engines are required to exceed it.

Airship vertical speed scales separately with density magnitude (total mass / block count), independent of the horizontal power ratio.

The system can be disabled entirely with `custom-ships.stats.enabled: false`: custom ships then fall back to fixed default stats and block composition no longer affects performance (engines also stop consuming fuel). The key **defaults to `false`** pending a rework of the stats system in a future update — set it to `true` to opt in. It takes effect on newly assembled ships and after a server restart, like the other `stats` settings.

Ship info UI updated to show wool count, banner count, engine count, power ratio, and effective speed as a percentage (f542112, 8c7c2e7). Ship info hover simplified to show only speed %; detailed breakdown moved to a new Ship Stats banner item at slot 20 in the menu. Speed % uses sail cap (0.8) as 100% baseline — over 100% means engines are contributing. Speed and density values are color-coded in the UI (8c7c2e7). Floor acceleration default corrected from 0.005 to 0.015 (f9ed87f, f542112).

New config values:

```yaml
stats:
  enabled: true
  base-power: 2
  engine-power: 30
  wool-power: 3
  banner-power: 7
  sail-cap-ratio: 0.8
  default-ratio: 0.7
  max-ratio-multiplier: 1.5
```

### Configurable Stats (2a9757e)

Previously hardcoded stat values are now exposed as config options, enabling tuning without code changes.

New config values:

```yaml
stats:
  wool-power: 3
  banner-power: 7
  fuel-burn-multiplier: 1.0
  floor-rotation-deceleration: 0.05
  cap-rotation-deceleration: -1
```

### Engine System (16e7a07, 8dd07a7)

New custom item: Ship Engine. Crafted from 8 copper ingots surrounding a blast furnace (shaped recipe). Appears as a blast furnace with enchant glint (f542112 — new `enchant-glint` config field for custom items).

When a player places an engine, a BlockPlaceEvent listener transfers PDC tags to the block's TileState. The BlockStructureScanner detects tagged blast furnaces as engines and includes them in stats calculations. Vanilla smelting is suppressed on engine blocks.

**Fuel system:** Right-clicking an engine block on an assembled ship opens the EngineMenuGUI with 3 fuel slots. Only vanilla furnace fuels are accepted (validated on click). Fuel burns 1 item per tick while any movement input is held (W/A/D/Space/Sprint, not just W — d001055). When the current fuel item is exhausted, the next slot is auto-consumed. Lava bucket fuel returns an empty bucket (vanilla parity — d001055). Ship stats are recomputed whenever fuel state changes.

Pre-assembly fuel in blast furnace containers is transferred into wheelData on assembly; fuel is written back to containers on disassembly (c3e0705 — previously silently lost). Fuel deserialization is crash-safe with per-item try-catch. Click-to-refresh on engine status item shows live fuel state.

CAMPFIRE_COSY_SMOKE particles are emitted at fueled engine positions every 5 ticks while the ship has a driver.

Per-engine fuel slots and burn ticks are serialized to `ship_wheels.yml` as Base64-encoded ItemStack bytes. Engine block indices and local positions are tracked in ShipModel for click detection on assembled ships.

Breaking an engine block drops the custom ship engine item (with PDC tag and glint) instead of a vanilla blast furnace (d4f4ee7). Explosions (EntityExplodeEvent, BlockExplodeEvent) also drop the custom item (232aac0).

### Smooth Ship Rotation (82bda96, 55a6cc6, 59dcb85, 1648bb5, 3995b4d)

Complete rewrite of ship rotation across 5 commits to eliminate visible jitter.

**Root cause:** The entity tracker sends vehicle rotation at byte precision (~1.4° quantization) every 3 ticks, conflicting with float-precision position sync packets sent every tick. This created a visible snap every 3 ticks.

**Solution:** The vehicle's yaw is frozen at its spawn rotation. Actual rotation is tracked internally as `ShipPhysics.currentYaw` (float precision). All visual rotation is applied via display entity transformation matrices with `setInterpolationDelay(0)` for smooth client-side interpolation over 2 ticks.

All consumers updated to use `physics.currentYaw` instead of `vehicle.getYaw()`:
- Forward direction calculations in ShipPhysics
- Collision direction in ShipCollision
- Snap-to-grid and snap-to-cardinal methods
- Display rotation matrices (now use delta yaw: currentYaw - spawnYaw)

`currentYaw` is persisted in ship metadata for chunk recovery. Yaw normalized to [0, 360) to prevent drift.

Persisting `currentYaw` across chunk recovery also addresses the chunk-reload symptom from [#7](https://github.com/def9a2a4/BlockShips/issues/7), where a parked ship would snap to north (collider rotates, display stays put) after its chunk unloaded and reloaded. This is a partial fix — the more severe collider/skin desync some users reported on older versions may still persist.

### Destruction Mode (3507fa0, dc0dfc6, 6288eed) — closes [#27](https://github.com/def9a2a4/BlockShips/issues/27)

Ships can now be configured to permanently destroy on death instead of disassembling.

New config value:

```yaml
custom-ships:
  destruction-mode: disassemble  # or "destroy"
```

**Disassemble mode (default):** Blocks placed back into world at original positions. Wheel block broken and dropped. All stored items returned. Engine fuel dropped.

**Destroy mode:** Ship blocks permanently lost. Wheel block removed from world and tracking map (new method `ShipWheelManager.destroyWheelBlock(Location)` — removes without dropping the item). Stored inventory contents dropped as loose items. Engine fuel items dropped as loose items.

Lead preservation: Before destroying collision shulkers, iterates all leashable entities within 12-block radius. Any entity leashed to a ship shulker gets one LEAD item dropped, matching vanilla fence-post-broken behavior. Uses Paper's `Leashable` interface instead of `Mob` to also catch leashable non-mob entities like boats in 1.21.2+ (6288eed). Leash is detached after dropping to prevent `tickLeash` from dropping a duplicate lead when the shulker holder is removed by `destroy()`.

NPE guard added: `getDisplayShip()` null-checked before `destroyWithCleanup()`, with fallback to bare `destroy()`.

### Config System Cleanup (1c8155e)

An earlier dev build (31855a0) added `ConfigValidator.migrateConfig()` which attempted to detect missing keys in the user's config.yml and write them in from the bundled default. This was removed before release for several reasons:

1. **It was fundamentally broken.** It used `diskConfig.contains(key)` to check for missing keys, but Bukkit's `contains()` also checks in-memory defaults loaded by `saveDefaultConfig()`. Every key appeared to already exist, so the migration never wrote anything.
2. **Even if fixed, `saveConfig()` strips all YAML comments.** The first successful migration would destroy every inline comment in the user's config file — all the documentation explaining what each setting does.
3. **It's unnecessary.** Bukkit's defaults system already makes new keys available via `getConfig()` / `getConfigurationSection()`. Code reading config values gets the bundled default automatically when a key isn't in the user's file. Recipe registration discovers new items (ship_engine, captains_manual) through defaults without needing them on disk.

The `auto-migrate-config` setting was also removed.

Scoped the outdated-file check to only resource files (blocks.yml, items.yml, prefab ships) where it's useful. Previously, any config.yml customization triggered a misleading "delete your config" warning. Softened warning message for the remaining files.

Recipe registration hardened (1c8155e):
- Ingredient parse errors bail out immediately (no half-built recipes)
- `Bukkit.addRecipe()` wrapped in try-catch so one bad recipe can't cascade-fail all subsequent registrations
- Shaped recipes validate that every non-space pattern character has a corresponding ingredient defined before attempting registration

### /blockships give Expansion (2b53f05)

The `/blockships give` command now supports all custom items:
- `captains_manual` (written help book)
- Any `custom-items` entry from config (ship_engine, balloon, etc.)
- Ship wheels and ship kits (existing)

Tab completion includes all giveable item IDs. Item listing extracted into shared helpers to avoid duplication.

### Help Book Extraction (16e7a07, f9ed87f)

Captain's manual content moved from hardcoded Java in ShipWheelMenu into `help_book.yml`. Content can be updated without recompiling. Help book content reloads on `/blockships reload` (f9ed87f). Shapeless recipe support added to ItemUtil.registerItemRecipe() (f9ed87f). Various content fixes and stats tuning applied.

The Captain's Manual is now a craftable item — a shapeless recipe combining a Ship's Wheel with a book yields the written guide. It can also be obtained via `/blockships give captains_manual`.

### Towny Compatibility (856419d) — [#17](https://github.com/def9a2a4/BlockShips/issues/17)

Towny's mob removal timer periodically scans town chunks and removes hostile mobs. Shulkers are on the default removal list, and BlockShips uses shulker entities as invisible collision boxes and seats. When the timer fires, ship collision entities get deleted, players get ejected, and the ship becomes a ghost (display entities survive since they're not mobs).

Fix: All ship entities (shulkers, armor stands) now have their custom name set to `Component.empty()` and `customNameVisible` set to false. Towny's removal logic skips named entities, so this prevents ship entities from being culled while remaining invisible to players.

Applied to:
- ShipWheelManager: collision shulkers spawned during ship detection
- ShipInstance: root armor stand vehicle, carrier armor stands, and seat shulkers spawned during assembly and chunk recovery

Server admins running Towny can also configure workarounds on the Towny side — see [#17](https://github.com/def9a2a4/BlockShips/issues/17) for details.

### Optional ProtocolLib & Wider Version Support (e15f794, 13e84cb, 7da6074) — [#28](https://github.com/def9a2a4/BlockShips/issues/28)

ProtocolLib is no longer a hard dependency. It is now declared as a `softdepend` in `plugin.yml` (and `compileOnly` in the build), so the plugin loads with or without it.

Ship steering picks an input path based on the server:

- **Paper 1.21.2+:** uses Minecraft's native `PlayerInputEvent` via the new `PaperInputListener`. This runs on the main thread and requires **no ProtocolLib**.
- **Pre-1.21.2:** falls back to the existing ProtocolLib packet interception (`ShipSteeringListener`). ProtocolLib is still required here.

This also fixes the **1.21.3–1.21.8** window where ProtocolLib's reflection on the player input record had stopped working (breaking WASD controls), and removes a race condition where input booleans were written from the netty thread while physics read them on the main thread.

The plugin now builds against `paper-api:1.21.11`, so the effective supported range is roughly **1.21.2–1.21.11** on Paper without ProtocolLib (older versions remain supported with ProtocolLib installed).

### More Custom-Ship Blocks: Shelves, Bookshelves, Signs (e9b6757) — partially addresses [#23](https://github.com/def9a2a4/BlockShips/issues/23)

Three new block types can now be part of a custom ship, with their state preserved across assembly and disassembly:

- **Chiseled bookshelves** (6 book slots)
- **Shelves** (1.21.9+, 3 item slots)
- **Signs** (standing, wall, and hanging) — front and back text, text color, glow state, and waxed flag are all retained

Shelf and chiseled bookshelf contents are serialized through a new `TileStateInventoryHolder` path in `BlockStructureScanner` (these store items in the tile entity rather than block data). `blocks.yml` gains `chiseled_bookshelf` and `*_shelf` entries, both flagged `display_rotation: true` so they face the correct direction in flight.

Note: due to a `BlockDisplay` limitation, shelf/bookshelf items and sign text are not visible while the ship is flying — they reappear when the ship is disassembled. This is a partial fix for [#23](https://github.com/def9a2a4/BlockShips/issues/23): decorated pots, other data blocks, and wall-mounted heads are still unsupported, and in-flight sign-text rendering is not yet implemented.

## Bug Fixes

### Circular resolveWheelData Call (65112f3)

`computeEffectiveStats` called `resolveWheelData` which called `computeEffectiveStats` again, creating infinite recursion in certain code paths. Broken by restructuring the call chain to avoid the cycle.

### Engine Stats Display and Fuel Lifecycle (cab8a4f, c3e0705)

Ship info display showed stale stats data and incorrect fuel counts. Stats display referenced wrong fuel state, and detection chat messages did not reflect engine-adjusted power ratios. Fixed stats consumers to read live fuel state and recompute on change.

Engine fuel lifecycle fixes (c3e0705):
- GUI fuel slots remapped {1,2,3} → {0,1,2} for direct 1:1 mapping with blast furnace container indices
- Pre-assembly fuel from blast furnace containers now transferred into wheelData on assembly (was silently lost)
- Fuel written back to containers on disassembly (reverse direction)
- Stale fuel/burn entries cleared on disassembly
- Stop clearing entire blast furnace on save — targeted slot writes only
- Crash-safe fuel deserialization with per-item try-catch
- Click-to-refresh on engine status item
- Detection chat now shows output for assembled ship detection (was completely silent) with live fuel state (fueled/unfueled engine breakdown)
- Density display uses weighted block count (matches physics — was using total block count which included weightless blocks like trapdoors)
- Standardized chat terminology: "power" → "pts"

### Unassembled Preview Showed Engines as Unfueled

The Ship Info panel and the detection chat message always reported **0 fueled engines** for a parked (unassembled) ship, even when its engines' blast furnaces held fuel — so the projected speed/ratio understated a fueled ship. Detection now reads each engine furnace's contents during the structure scan and counts engines holding burnable fuel, so the preview panel and detect message show the real fueled count and an engine-adjusted speed estimate. (Assembled ships were already correct; physics was never affected.)

### Ship Physics Timing (d001055)

Multiple physics issues fixed in one pass:
- Stats timing: `computeEffectiveStats()` was called in constructor before wheelData was linked, so first computation always saw 0 fueled engines. Now deferred until after assembly, with lazy recomputation on recovery
- Movement threshold: low-ratio ships were unable to move because `minMovementThreshold` zeroed speed even when W/S were held. Now only applies when no movement input is active
- Fuel burn: lava bucket fuel returned nothing when consumed — now returns empty bucket (vanilla parity)
- Fuel burn: only triggered on W key — now triggers on any movement input (A/D/Space/Sprint)
- Fuel burn multiplier from config was not being applied
- Rotation deceleration: did not scale with power ratio — heavy ships now retain rotation momentum longer

### Mass/Weight Semantics (c76e5f6)

Renamed `totalPositiveWeight` → `mass` (sum of max(0, weight) per block). This is the correct denominator for the power-to-mass ratio — it represents how much solid material sails need to push, excluding negative-weight floatation blocks.

Fixes airships getting zero sail benefit: `getSailRatio()` previously returned 0 for negative `totalWeight`. Now uses `mass`, so airships with sails correctly get horizontal speed benefit.

Also fixed:
- `engineBlockIndices` changed from Set to List so iteration order matches engine positions (fixes smoke particles appearing at wrong engine / IOOBE)
- Lazy `resolveWheelData()` for chunk-recovered ships — looks up via `ShipWheelManager.getWheelByShipUUID()` on first access so fuel state is correctly loaded instead of assuming all engines fueled
- Shift-click non-fuel items into engine GUI now blocked
- Dried kelp burn time 4001 → 4000 ticks
- YAML key renamed to "mass" with backwards-compat read of old "total_positive_weight" key

### Stats Bugs (1015cea, d4f4ee7)

Multiple stats system bugs fixed:
- Engine blocks dropped as vanilla blast furnace on break — now drop custom ship engine item with PDC tag and glint
- `totalPositiveWeight` field added to ShipModel so assembled ships report correct positive weight instead of passing clamped maxHealth
- `computeStat()` divide-by-zero when `defaultRatio >= 1.0` — added guard
- Scanner reference not set early enough in ship assembly

### Fuel Validation and Explosion Drops (232aac0)

Engine GUI exploit prevention:
- Double-click item collect with non-fuel items now blocked
- Number-key hotbar swaps with non-fuel items now blocked
- InventoryDragEvent handler added to prevent drag-placing non-fuel
- Stats recomputed on engine GUI close so fuel changes take effect immediately without requiring movement

Engine explosion handling:
- EntityExplodeEvent and BlockExplodeEvent handlers added for engine blocks — drop custom ship engine item instead of vanilla blast furnace

Null safety: guard null/invalid shulkers in camera distance update loop.

ShipModel cleanup: removed dead `engineLocalPositions` field (populated but never read; smoke particles use collision shulker positions). `woolPower`/`bannerPower` now accepted as constructor params so sail power calculation uses config values.

### Prefab Ships Unable to Move (c8ef29c)

Commit 5da91fd moved `computeEffectiveStats()` out of the ShipPhysics constructor to avoid a circular call with wheelData (needed for custom ship fuel state). But `recomputeStats()` was only called from custom-ship paths (assembly, wheelData resolution, engine menu close). Prefab ships never hit any of those paths, so `effectiveMaxSpeed`, `effectiveAcceleration`, and all other effective stat fields stayed at 0.0f. Ships could spawn and be mounted but couldn't move at all.

Fix: call `physics.recomputeStats()` in the ShipInstance constructor after delegates are initialized. For prefab ships this sets the final config-based values. For custom ships it produces a conservative initial ratio (0 fueled engines), which gets recomputed after wheelData is linked.

### Ships Immovable After Chunk Recovery

A follow-on to the fix above. Deferring `computeEffectiveStats()` out of the constructor (5da91fd) and restoring it on the spawn path (c8ef29c) both missed the chunk-**recovery** path: `recoverEntities()` never called `recomputeStats()`, so any ship rebuilt from persistence came back with all effective stat fields at 0.0f. Only engine'd custom ships self-healed (via the per-tick fuel/smoke path, which links wheelData); prefab ships and sail-only custom ships stayed frozen — immovable, unable to turn, airships unable to ascend or descend. This triggered on every chunk reload and every server restart, regardless of `stats.enabled`.

Fix: call `resolveWheelData()` then `physics.recomputeStats()` at the end of `recoverEntities()`, mirroring the spawn path. `computeEffectiveStats` reads only config/model/wheelData (null-guarded) and is idempotent, so it is safe to run once per recovery.

### Rotation Bugs (55a6cc6, 59dcb85, 1648bb5, 3995b4d)

Series of fixes following the smooth rotation rewrite:
- Chunk recovery did not restore `currentYaw`, causing ships to snap to spawn yaw on chunk reload
- Yaw values could drift outside [0, 360) over time, causing display glitches
- `alignToGrid` used vehicle yaw instead of internal yaw, snapping to wrong position
- Idle sync teleport caused unnecessary jitter — removed
- Two rotation invariant bugs where forward direction disagreed with visual rotation in edge cases

### Container Item Duplication on Assembly (19e0bc7)

Inventory blocks duplicated their contents when a ship was assembled. The block's items were serialized into the ship data, but the snapshot inventory was never cleared before the block was set to air — and Bukkit drops a block's remaining items to the world on `setType(AIR)`. So every chest/furnace/hopper (and shelf/chiseled bookshelf) effectively spilled a second copy of its contents onto the ground.

Fixed by clearing the snapshot inventory and pushing the empty state to the world (`getSnapshotInventory().clear()` + `update()`) before removing the block, on both the `Container` and `TileStateInventoryHolder` paths.

### Engine Fuel Duplicated on Destruction (destroy mode)

A crafted engine is a blast furnace — a `Container` — so at assembly its fuel was serialized into `container_items` and rebuilt into the in-memory `storages` map, *and* separately transferred into the wheel's engine slots. On a **destroy-mode** death the drop path iterated both `storages` and `wheelData`, so engine fuel dropped twice. Worse, the `storages` copy was the stale assembly-time snapshot, so it ignored fuel burned while sailing and could drop more fuel than the player ever had. (Disassemble mode was unaffected — it overwrites `container_items` from the wheel before placing.)

Fixed by skipping engine block indices in the destroy-mode `storages` drop loop, leaving `wheelData` as the single authoritative source of dropped engine fuel. This also corrects ships saved before the fix, whose engine `container_items` are rebuilt into `storages` at load.

### Shelf/Bookshelf Rotation & Destroy Drops (63bc339)

Two issues with the newly supported shelf and chiseled bookshelf blocks:

- They rendered without their facing direction in flight. Adding `display_rotation: true` to their `blocks.yml` entries applies the correct Y-axis rotation, matching how chests and barrels already work.
- Their contents were not dropped when a ship was destroyed. Unlike containers, these blocks store items in `container_items` rather than the `storages` map, so they were missed by the destroy path. They now drop their items at the ship's death location.

### Newly-Added Blocks Missing on Upgraded Servers (662292f)

On servers upgraded from an earlier version, newly-supported blocks — most visibly shelves (1.21.9+) — were rejected during ship assembly. `blocks.yml` was extracted to the plugin data folder on first run and then only ever read from that disk copy, never merging new defaults. A server that generated `blocks.yml` before shelves existed kept a stale copy without the `*_shelf` entry: the bundled default had it, but the on-disk file hid it. `items.yml` and the prefab ship models had the same stale-copy pattern.

These content files are no longer written to disk. They're read straight from the jar (via a new `ConfigResources` helper), so every update ships current defaults automatically. Admins who want to customize a file drop an edited copy under a new `config/` subfolder (`config/blocks.yml`, `config/items.yml`, `config/prefab_ships/*.yml`), which is read in preference to the bundled copy but never overwritten by the plugin.

`ConfigValidator` was reworked to match: it now warns when a legacy copy still sits at the data-folder root (that location is no longer read — move it to `config/` or delete it), and when a `config/blocks.yml` override is missing entries the bundled default has (an override going stale). `config.yml` is unchanged — Bukkit already layers bundled defaults under the user's file, so it never suffered this problem.

### Controls Warning Clarity (6236198)

The startup warning shown when WASD controls cannot be initialized (no Paper input event and no ProtocolLib) was reworded to lead with the impact before the cause:

```
WASD ship controls will not work!
Paper PlayerInputEvent not available, ProtocolLib not found.
```

### Rarer Drowned Captains — [#29](https://github.com/def9a2a4/BlockShips/issues/29)

The default `special-drowned.spawn-chance` is lowered from `0.05` (5%) to `0.02` (2%). In biomes where drowned spawn heavily, the special ship-wheel-dropping drowned were appearing in overwhelming numbers. The feature can still be disabled entirely with `special-drowned.enabled: false`, or the rate tuned back up.

## Known Issues / Not Yet Addressed

These are tracked but not resolved in v0.0.16:

- **Proper tile entity support ([#23](https://github.com/def9a2a4/BlockShips/issues/23)):** decorated pots and other data-bearing blocks still aren't supported, and sign text is preserved but not rendered while a ship is in flight.
- **Wall heads ([#20](https://github.com/def9a2a4/BlockShips/issues/20)):** player/mob heads placed on walls still display incorrectly and lack colliders. Floor-mounted heads work.
- **Partial destruction ([#24](https://github.com/def9a2a4/BlockShips/issues/24)):** only whole-ship destroy/disassemble (#27) is implemented; per-block / progressive destruction is not.
- **Older-version visual desync ([#7](https://github.com/def9a2a4/BlockShips/issues/7)):** the chunk-reload rotation snap is fixed, but the more severe collider/skin desync reported on legacy versions may still occur.
- **ProtocolLib reports ([#28](https://github.com/def9a2a4/BlockShips/issues/28)):** the underlying cause should be resolved by the optional-ProtocolLib path on 1.21.2+, but the issue remains open pending reporter confirmation.
