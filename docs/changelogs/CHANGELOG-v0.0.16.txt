# v0.0.16 - Ship Stats, Engines & Smooth Rotation

- Ships now have a power-to-mass ratio system that scales speed and rotation based on block composition ([#18](https://github.com/def9a2a4/BlockShips/issues/18))
  - Wool blocks act as sails (3 power), banners as upgraded sails (7 power), engines provide 30 power when fueled
  - Heavier ships are slower unless you add more power sources
  - Sail-only power is capped — engines required to push ships past 80% of max ratio
  - Ship info UI shows power ratio, effective speed percentage, and color-coded stats
- New custom item: Ship Engine
  - Crafted from 8 copper + 1 blast furnace
  - Right-click on an assembled ship to open fuel GUI (3 fuel slots per engine)
  - Accepts any vanilla furnace fuel (coal, logs, planks, blaze rods, lava buckets, etc.)
  - Fuel consumed while W key held; smoke particles emitted from fueled engines
  - Fuel state persisted across server restarts
- Ship rotation is now smooth and jitter-free
  - Previously, entity tracker quantization (~1.4° byte precision) caused visible jitter every 3 ticks
  - Rotation now tracked internally at float precision and applied via display entity transforms with client-side interpolation
- Ships can be configured to permanently destroy on death instead of disassembling ([#27](https://github.com/def9a2a4/BlockShips/issues/27))
  - Set `custom-ships.destruction-mode: destroy` in config.yml
  - Blocks are lost, but inventory contents, engine fuel, and mob leads are dropped
- New config keys are automatically available on plugin update via Bukkit's defaults system — no need to delete config.yml
- `/blockships give` now supports all custom items (captains_manual, ship_engine, balloon, etc.) with tab completion
- Captain's manual content extracted to YAML — server admins can customize help text without recompiling
- Towny compatibility: ship entities now set an empty custom name to prevent Towny's mob removal timer from deleting them ([#17](https://github.com/def9a2a4/BlockShips/issues/17))
  - Previously, Towny would remove ship shulkers (collision/seat entities) in town chunks, ejecting players and ghosting the ship
  - Workaround config options for Towny are documented in the issue thread

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

Ship info UI updated to show wool count, banner count, engine count, power ratio, and effective speed as a percentage (f542112, 8c7c2e7). Ship info hover simplified to show only speed %; detailed breakdown moved to a new Ship Stats banner item at slot 20 in the menu. Speed % uses sail cap (0.8) as 100% baseline — over 100% means engines are contributing. Speed and density values are color-coded in the UI (8c7c2e7). Floor acceleration default corrected from 0.005 to 0.015 (f9ed87f, f542112).

New config values:

```yaml
stats:
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

CAMPFIRE_SIGNAL_SMOKE particles are emitted at fueled engine positions every 5 ticks while the ship has a driver.

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

### Towny Compatibility (856419d) — [#17](https://github.com/def9a2a4/BlockShips/issues/17)

Towny's mob removal timer periodically scans town chunks and removes hostile mobs. Shulkers are on the default removal list, and BlockShips uses shulker entities as invisible collision boxes and seats. When the timer fires, ship collision entities get deleted, players get ejected, and the ship becomes a ghost (display entities survive since they're not mobs).

Fix: All ship entities (shulkers, armor stands) now have their custom name set to `Component.empty()` and `customNameVisible` set to false. Towny's removal logic skips named entities, so this prevents ship entities from being culled while remaining invisible to players.

Applied to:
- ShipWheelManager: collision shulkers spawned during ship detection
- ShipInstance: root armor stand vehicle, carrier armor stands, and seat shulkers spawned during assembly and chunk recovery

Server admins running Towny can also configure workarounds on the Towny side — see [#17](https://github.com/def9a2a4/BlockShips/issues/17) for details.

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

### Prefab Ships Unable to Move (c8ef29c)

Commit 5da91fd moved `computeEffectiveStats()` out of the ShipPhysics constructor to avoid a circular call with wheelData (needed for custom ship fuel state). But `recomputeStats()` was only called from custom-ship paths (ShipWheelManager assembly, resolveWheelData, engine menu close). Prefab ships never hit any of those paths, so `effectiveMaxSpeed`, `effectiveAcceleration`, `effectiveRotationSpeed`, etc. stayed at 0.0f. Ships could spawn and be mounted but couldn't move.

Fix: call `physics.recomputeStats()` in the ShipInstance constructor after delegates are initialized. For prefab ships this sets the final config-based values. For custom ships it produces a conservative initial ratio (0 fueled engines), which gets recomputed after wheelData is linked.

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

### Rotation Bugs (55a6cc6, 59dcb85, 1648bb5, 3995b4d)

Series of fixes following the smooth rotation rewrite:
- Chunk recovery did not restore `currentYaw`, causing ships to snap to spawn yaw on chunk reload
- Yaw values could drift outside [0, 360) over time, causing display glitches
- `alignToGrid` used vehicle yaw instead of internal yaw, snapping to wrong position
- Idle sync teleport caused unnecessary jitter — removed
- Two rotation invariant bugs where forward direction disagreed with visual rotation in edge cases
