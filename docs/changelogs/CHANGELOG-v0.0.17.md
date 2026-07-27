# v0.0.17 - WorldGuard integration & admin command hardening

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

## Other

- README now links to the related [SimpleShips](https://github.com/jemcdevitt/SimpleShips)
  plugin (4de59a1).
