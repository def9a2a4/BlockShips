# v0.0.17 - tweaks and fixes

- **Cheaper craftable recipes:** the Ship Balloon now uses a glowstone block (was a
  phantom membrane) and the Ship Wheel uses a lead (was a blast furnace). Custom heads
  (balloon, wheel, ship kits) also render correctly in the recipe book again.

- **WorldGuard region protection is now respected:** ships that disassemble or crash-land
  inside a WorldGuard-protected region no longer write blocks into it past the region's
  build permission. Optional, soft-dependency — no effect unless WorldGuard is installed.
  Enabled by default (`plugins.worldguard.enabled: true`).

- **Destructive admin commands are now failure-aware:** `forcedisassembleall` and
  `killentities` no longer silently swallow persistence/cleanup errors, and
  `/blockships info` gained a real loaded-vs-unloaded ship/wheel breakdown.

- **New permission nodes:** `blockships.info` (default true) and `blockships.highlight`
  (default op). If you manage permissions explicitly, grant these.


---

## New Features

### WorldGuard Region Build Permissions (80ba661)

BlockShips edits the world directly (it never fires a `BlockPlaceEvent`), so until now a
ship disassembling or crash-landing inside a WorldGuard region wrote its blocks straight
in, ignoring the region's build flag. A new optional integration makes every plugin
block-edit respect the acting player's build permission.

Active only when WorldGuard is installed **and** `plugins.worldguard.enabled: true`
(default). Behavior:

- **Disassembly:** cells landing in a region the player can't build in become conflicts.
  The player is warned and can still Force Disassemble; on force those blocks **drop as
  items** instead of being placed — preserving engine/wheel custom-item identity and
  container contents. A protected wheel anchor drops the wheel item and deregisters
  instead of planting a head.
- **Assembly:** denied if any scanned cell (across the whole flood-fill) is protected —
  closing a block-laundering dupe.
- **Wheel placement** and **breaking a wheel/engine** are denied inside a protected region
  (these paths edit blocks programmatically or past WorldGuard's own cancel).
- **System paths** (crash disassemble, `forcedisassembleall`) act as a non-member, so
  unattended ships drop items rather than griefing the region. Because a system path has no
  player identity, this applies even inside the ship's own owner's claim or a spawn region —
  admins who prefer the old behavior can set `system-disassembly-in-region: place-anyway`
  (default `drop-items`) to have unattended disassembly write blocks back instead of dropping
  them. Player-driven Force Disassemble is unaffected and always respects the player's own
  build permission.

Implementation notes: new `integration` package — `WorldGuardHook` (static holder, no-op
default), `NoOpWorldGuardHook`, `WorldGuardHookImpl`. The query uses the built-in `BUILD`
flag via `RegionQuery`, treats any result `!= ALLOW` as denied, uses a `NON_MEMBER`
associable for the player-less path, checks bypass explicitly, null-guards the region
lookup, and **fails open with throttled logging**. `BlockStructureScanner` gains a
`protectedCount` conflict category, player-aware validate/place overloads gated by an
O(1) "world has regions" check, and a `dropPartAsItems` helper. WorldGuard is
`compileOnly` (never shaded), declared `softdepend` in `plugin.yml`; the hook is
(re)installed on enable and reload and only touches WorldGuard classes behind a
`Class.forName` guard, so the plugin loads and behaves identically without WorldGuard.

New config values:

```yaml
plugins:
  worldguard:
    enabled: true
    system-disassembly-in-region: drop-items   # or place-anyway
```

### `/blockships info` Stats Rework (07df6a5)

`/blockships info` now reports a loaded-vs-unloaded breakdown of prefab/custom/all ships,
sourced from the in-memory registry (loaded) and the chunk index (persisted), plus
unassembled wheels, orphaned wheel links, and a duplicate-wheel-link warning. A shared
`classifyWheels()` feeds both the stats view and the `forcedisassembleall` confirmation so
their counts agree by construction; destructive commands now print a stats + entity
preview before the confirm step.

## Hardening & Fixes

### Destructive Admin Commands Now Surface Failures (07df6a5)

`forcedisassembleall` and `killentities` used to ignore persistence and cleanup errors.
Now:

- `ShipWorldData.removeShip` and `saveAllChunkIndices` return a boolean and log on failure
  (failed delete / `chunks.yml` write), so callers can detect and report the failure.
- `ShipWheelManager.saveAll` returns a boolean; `disassembleShip` gains a 4-arg overload
  with a `DisassembleOutcome` holder, so a post-disassembly save failure is reported
  separately from disassembly success.
- `forcedisassembleall` / `killentities` isolate per-ship / per-entity failures in
  try/catch, count them, and report `destroyFailed` / `saveErrors` / `cleanupFailed` to
  the admin. `killentities` force-unregisters a ship whose `destroy()` throws, so no
  phantom entry lingers in the registry.

### Command Surface Cleanup (07df6a5)

- New permission nodes: **`blockships.info`** (default true) and **`blockships.highlight`**
  (default op), gating the info and highlight handlers and their help + tab-complete
  listings. `blockships.give` now also covers `spawndrowned`.
- `reload` re-registers/unregisters the special-drowned listener to match the toggled
  config, instead of requiring a full restart.
- The dead multi-line `plugin.yml` usage block collapses to `/blockships help` (the
  in-game help is the single source of truth).
- `highlightcolliders` guarded against empty/invalid colliders; denial wording and
  success-message coloring unified.

### WorldGuard Drop-Path Data Fidelity & Robustness (cb47a01, 2a388d8)

Follow-up review passes on the WorldGuard integration, tightening the drop path and the
assembly gate:

- **Decorated blocks keep their identity when they drop.** Player-head textures, banner
  patterns, and custom (anvil-renamed) names are copied onto items dropped into a protected
  region, on top of the existing engine/wheel custom-item and container-content preservation.
  (Sign text still can't ride a vanilla item and is not preserved.) Decoration transfer is
  wrapped so corrupted/hand-edited model data yields an undecorated, logged drop rather than
  losing the whole block.
- **No more duplicated or vanished multi-cell blocks.** Door/tall-plant upper halves and bed
  heads no longer drop a second item; upside-down stairs and top-hung trapdoors (single-cell
  blocks that merely *look* like a stacked top half) are explicitly kept, so they no longer
  silently disappear on a protected force-disassembly.
- **The assembly gate fails closed on a WorldGuard fault.** A transient query error during
  the assembly scan can no longer reopen the block-laundering exploit; destructive/drop paths
  still fail open. The gate also distinguishes a world with region support *disabled*
  (assembly proceeds normally) from region data that *failed to load* (fails closed), instead
  of blocking assembly in every regions-disabled world.
- **Breaking a wheel/engine respects other protection plugins.** The break handlers run at
  `HIGHEST` and bail if the event is already cancelled, instead of manually breaking and
  dropping after another plugin has denied it.
- **Misconfiguration no longer renders as garbage.** A `sail-cap-ratio` of `0` no longer
  displays an `Infinity`/`2147483647%` speed (it falls back to the honest percentage), and an
  unrecognized `system-disassembly-in-region` value logs a warning and uses `drop-items`.
- `/blockships give ship_engine` is gated on `custom-ships.stats.enabled` (matching the recipe
  gate) and hidden from give help + tab-complete when stats are off. Documented that a
  `__global__` region is required for WorldGuard's world-wide build-deny to be honored.

### Waterlogging Preserved on Disassembly (54eb636)

Ships float, so hull cells sit in water source blocks. A waterloggable block (fence, stairs, slab,
wall, ...) disassembling back over a water source was previously placed dry, overwriting the water
and leaving an unnatural air pocket underwater. The destination cell is now authoritative:
`placeBlocks` clears waterlogged on every waterloggable block and re-sets it only when the target is
a water **source** (level 0 — transient flowing water is excluded); `scanStructure` clears
waterlogged at capture so stored ship models never carry water. Because the clear is unconditional,
this also **self-heals** ships already saved with `waterlogged=true` baked into their block data,
which would otherwise re-waterlog on dry land forever.

## Other

- **Recipe tweaks (23c8562):** Ship Balloon now crafts with a glowstone block instead of a
  phantom membrane (a mob drop that's awkward to farm); Ship Wheel now takes a lead instead of
  a blast furnace — cheaper for a single wheel and a better nautical fit alongside the compass.
- **Recipe-book head textures (9a5d013):** custom heads (balloon, ship wheel, ship kits) were
  showing as blank Steve heads in the recipe book because the result icon never resolved its
  skin through Mojang's session server. The raw texture is now embedded directly on the
  profile (on a deterministic, texture-derived UUID so identical heads stack), so these render
  client-side without a lookup. The same fix now extends to **ingredient slots**: a custom-item
  ingredient (e.g. the balloons in the airship recipe) is matched with an `ExactChoice` over its
  craftable variants — every dye colour for wool-sourced items — so the recipe book shows the real
  textured item instead of a blank head, while still matching by full item meta.
- README now links to the related [SimpleShips](https://github.com/jemcdevitt/SimpleShips)
  plugin (4de59a1).
