# Fix audit findings — v0.0.16 release review (A–N)

## Context

Three review passes feed this list. **A–D** came from the stats-toggle review after landing the
`custom-ships.stats.enabled` toggle (`10ea778`), the unassembled-preview fuel fix (`0f29942`), and the
changelog (`b593eb7`). **E–I** came from a **five-agent deep bug review of the whole 0.0.16 release**
(stats, engine/fuel, rotation, destruction mode, tile-entity blocks, optional-ProtocolLib). **J–N** came
from a **third pass over the subsystems the first two left unaudited** (`/blockships give`, help book +
recipes, Towny naming, special-drowned, custom items) plus two minor verified leftovers. Every finding was
independently re-verified against the code and adversarially critiqued. The git tree is clean.

The rotation rewrite and the version/recipe/config-migration subsystems came back essentially clean — no
release blockers there. The defects cluster in engine/fuel and destruction/tile-entity handling.

**Priority order (severity, post re-review):** F (CRITICAL — immobile after *every* reload, prefab + sail-only)
→ E (CRITICAL — destroy-mode fuel dup) → A, G, G2, K (HIGH) → B, L (MEDIUM) → H, I, J, M, N (LOW).
A is re-rated HIGH (an exploit reachable without any GUI trick — see A). F was re-rated up from HIGH and
re-scoped to include prefab ships during re-review. K is the third-pass exploit (recipe-validation bypass).
Findings are independent and can land as separate commits.

**New-in-0.0.16 regressions:** A, B, E, F, G, G2, K, L1, N1. The rest (H, I, J, L2/L3, M, N2/N3) are
pre-existing latent issues the review surfaced — fix opportunistically.

**Scope:** A, B, D, E, F, G, G2, K in scope; L opportunistic (command polish); H, I, J, M, N as time
allows. **C (live `/blockships reload` for flying ships) is declined** — applying `stats.enabled` (and all
`stats.*` knobs) on next assemble/reassemble/restart is acceptable; that's the current behavior. C is kept
below as a recorded "won't fix" so the limitation stays documented.

---

## A — `countFueledEngines` counts non-burnable items as fuel (real bug — re-rated HIGH)

> **Severity:** the deep review re-rated this HIGH. It's an exploit reachable **without** any GUI trick:
> a hopper/dropper/dispenser, `/give`, or creative middle-click can load any non-burnable item into the
> pre-assembly blast furnace, and assembly grants permanent free thrust. The count-only fix below is the
> right one — it keeps the slot copy (so nothing is lost) and just stops non-burnable items from counting.
> This also fully closes the related **H** GUI gap as a *power* exploit (H drops to cosmetic).

**Problem.** `ShipWheelData.countFueledEngines` (ShipWheelData.java:200-218) counts an engine as fueled if any slot holds *any* non-AIR item — no burn-time check. Physics reads this (ShipPhysics.java:105) to grant `enginePower`, but `tickEngineFuel` (ShipPhysics.java:168-170) only burns items with `getBurnTime > 0`. Assembly copies all three blast-furnace slots (input/fuel/result) into the engine fuel slots regardless of burn time (ShipWheelManager.java:292-304). Net effect: a player can put a **non-burnable** item (e.g. raw iron in the smelt slot) into an engine before assembly and get permanent free engine thrust that never depletes. It also makes the assembled preview disagree with the new unassembled `hasFuel` helper (which correctly requires `getBurnTime > 0`).

**Fix.** In `countFueledEngines`, require the slot item to be burnable, matching `tickEngineFuel` and the detect-path `hasFuel`:

```java
if (item != null && item.getType() != Material.AIR
        && EngineMenuGUI.getBurnTime(item.getType()) > 0) {
    count++;
    break;
}
```

`EngineMenuGUI` is in the same package (`customships`), so no import needed. Update the method javadoc ("…have fuel" → "…hold burnable fuel"). This single change aligns physics, assembled detect, and unassembled detect on one definition of "fueled" and closes the exploit. The `burnTicks > 0` early-branch already implies real fuel was lit — leave it as-is.

**Also align the engine status display (same root inconsistency).** `EngineMenuGUI.createStatusItem` (EngineMenuGUI.java:122-132) computes `hasFuelItems` with the identical non-AIR-only scan (line 127), so after the count fix an engine holding a non-burnable item would still show "has fuel" while contributing 0 power. Add the same `&& EngineMenuGUI.getBurnTime(item.getType()) > 0` guard there so the status item agrees with `countFueledEngines`. Leave the slot-copy/serialization scans (EngineMenuGUI.java:237,319 and ShipWheelData.java:432) alone — those preserve whatever's in the slot and must not filter, or items would be lost.

---

## B — Stats UI & detect chat mislead when `stats.enabled: false`

**Problem.** When the system is off, physics uses fixed `config.maxSpeed` etc. and engines don't burn fuel, but the wheel menu and detect chat still recompute and show power ratio, speed %, sail/engine points, and "add sails!" hints — none of which affect a disabled ship. None of these read `statsEnabled`. Purely cosmetic, but reads as "the toggle didn't work."

**Fix.** Thread `statsEnabled` into the display layer (already in scope via the freshly-loaded `config`):

1. Add a `boolean statsEnabled` field to the `ShipInfo` record (ShipWheelMenu.java:40-72) and pass `config.statsEnabled` when it's constructed in `getShipInfo` (~L424; config loaded at L368).
2. In `createInfoItem` (ShipWheelMenu.java:436-490, ~L475-479) and `createStatsItem` (ShipWheelMenu.java:506-579, ~L518-570): when `!info.statsEnabled`, replace the ratio / speed % / effective-power / sail+engine-points / "add sails" lines with a single line like `Stats system disabled — fixed speed`. Keep the accurate lines (blocks, weight, density, mass, health).
3. In the detection chat in `ShipWheelManager`, guard the ratio/speed/points messages on
   `config.statsEnabled`, printing a brief "stats disabled" note otherwise. **There are TWO such blocks,
   not one** — patch both: the **assembled-ship** branch at ShipWheelManager.java:774-795 *and* the
   **unassembled/placed-block** branch at ShipWheelManager.java:901-918 (config loaded at ~L880). The
   original draft of B listed only the second.

Keep it to suppression + one note; do not derive display values from effective stats (unassembled ships have no `ShipPhysics`).

---

## C — `/blockships reload` doesn't apply per-ship config to flying ships — WON'T FIX (accepted)

**Problem.** `ShipInstance.config` is `public final`, loaded once at construction (ShipInstance.java:121,204,310). The reload handler (BlockShipsPlugin.java:221-246) refreshes globals but never per-ship config, so `stats.enabled` and every `custom-ships.stats.*`/`controls.*`/buoyancy/sounds knob only applies to ships assembled/recovered after reload.

**Decision: not fixing.** Applying the toggle (and all per-ship knobs) on next assemble / reassemble / chunk-recovery / restart is acceptable behavior, not a defect. No code change; keep it documented — the changelog's "applies to newly assembled ships and after a server restart" line already states this accurately, so leave it.

*(If this is ever revisited: de-finalize `ShipInstance.config`/`isAirship`, add a `reloadConfig(plugin)` that reassigns config, recomputes `isAirship`, and calls `physics.recomputeStats()`, then loop `ShipRegistry.getAllShips()` in the reload handler. Consumers read `ship.config.X` live each tick, so it would propagate cleanly — except `displayInterpolationDuration`, which is baked into display entities at creation and would still need a re-assemble.)*

---

## D — Changelog polish

In `docs/changelogs/CHANGELOG-v0.0.16.md`: add the commit hash to the detection-fix header (~line 227) to match every sibling Bug Fixes header — `### Unassembled Preview Showed Engines as Unfueled (0f29942)`. Optionally scope the "no config reset is needed" line to "for this toggle" to avoid surface tension with the top-of-file "reset your configs!" line. Add new Bug Fixes entries for **A, B, E, F, G** (and H/I if landed) as they land, each citing its commit (no entry for C — won't fix). Optional: a one-line note in the "Engine Stats Display and Fuel Lifecycle" section that the fueled/unfueled detect-chat breakdown now also covers unassembled previews.

---

## E — Engine fuel duplicated on destroy-mode death (CRITICAL)

**Problem.** An engine is a `BLAST_FURNACE`, which is a `Container`, so `createStorageConfig`
(BlockStructureScanner.java:992-1006) returns a CHEST `StorageConfig` for it. At assembly the engine's
fuel is therefore serialized into `container_items` → rebuilt into the in-memory `storages` map
(ShipInstance.java:709-736) **and** transferred into `wheelData` engine slots (ShipWheelManager.java:304)
— `container_items` is never cleared after the transfer. On a **destroy-mode** death the path drops
`storages.values()` (ShipInstance.java:1950) **and** separately drops `wheelData.getAllEngineFuelSlots()`
(ShipInstance.java:1981) → fuel dropped twice. Worse, the `storages` copy is the stale assembly-time
snapshot, so it ignores fuel burned while sailing — it can drop *more* fuel than the player ever had.
Disassemble mode is safe (its engine loop overwrites `container_items` from wheelData before placing, so
it drops once). Requires the crafted `ship_engine` (a tagged blast furnace), not any furnace.

**Fix.**
1. **Load-bearing (correctness + backward-compat):** guard **both** destroy-mode drop loops to skip
   engine indices — the `storages.values()` loop at ShipInstance.java:1950 *and* the TileState
   `container_items` fallback at ShipInstance.java:1960-1979 (skip parts whose index is in
   `model.engineBlockIndices`). `wheelData` is the authoritative engine-fuel source, dropped separately
   at line 1981. **This guard is the only part that fixes ships saved before the fix** — an engine's
   `storages` entry is rebuilt from persisted `container_items` at load, so a scanner-only change does
   nothing for existing saves.
2. **Cleanup (stops *new* saves carrying duplicate data):** exclude engines from the in-memory `storages`
   map at ShipInstance.java:709 (skip when index ∈ `engineBlockIndices`) and clear the engine part's
   `container_items` after the wheelData transfer at ShipWheelManager.java:304. **Keep** the scanner's
   serialize+clear for engines — it is required for the pre-assembly fuel transfer *and* to stop
   `setType(AIR, true)` from spilling the furnace contents on the ground. (Clearing `container_items`
   after construction does not empty the already-built storages inventory for the current session, which
   is why the step-1 guard is still required.)

**Do NOT** simply skip the scanner serialize/clear for engines — that loses pre-assembly fuel and spills
it on the ground. Engine right-click→`EngineMenuGUI` reads `wheelData`, not the storages map, so removing
the storages entry doesn't break the GUI.

> **Note:** E and G edit the same scanner block (BlockStructureScanner.java:427-449) — land them together.

---

## F — Prefab & sail-only ships immobile after chunk recovery (CRITICAL, regression) — re-review: re-scoped + re-rated

**Problem.** `computeEffectiveStats()` was deferred out of the constructor (commit 5da91fd); the spawn
path calls `recomputeStats()` (ShipInstance.java:365) and `c8ef29c` fixed prefab ships the same way — but
the **recovery** path was missed. `recoverEntities` (ShipInstance.java:2135) and the `fromState` recovery
constructor (~L225) never call `recomputeStats()`/`resolveWheelData()`. After a chunk reload the only
incidental recompute triggers are `tickEngineFuel` and `spawnEngineSmoke`, both gated on `shipType ==
"custom"` **and** `engineBlockIndices` non-empty (spawnEngineSmoke returns at L1401 *before* its
`resolveWheelData()` call). So two whole classes of ship never recompute after recovery and stay at
`effective* == 0.0f` (immovable, can't turn, airships can't ascend/descend):
- **Sail-only / engineless custom ships** — no engine trigger.
- **All prefab ships** (`shipType != "custom"`) — `update()`'s recompute path (ShipPhysics.java:234-235)
  and `spawnEngineSmoke` both require `"custom"`, so prefabs *never* self-heal, **regardless of
  `stats.enabled`**.

Only engine'd *custom* ships self-heal (within one tick, via `tick()`→`spawnEngineSmoke()`→
`resolveWheelData()`, driver-independent). Re-review verdict: **CONFIRMED CRITICAL** — triggers on **every
chunk reload and every server restart** (the instance is unregistered on unload, DisplayShip.java:382-396,
and rebuilt via `fromState` on load). Re-rated from the original draft's "sail-only / HIGH": prefab ships
are equally affected, so in practice nearly every ship is dead-in-the-water after a reload. This is the
top release blocker, above E. `statsComputed` (ShipPhysics.java:52) is written but never read, so there is
no lazy guard to fall back on.

**Fix.** At the end of `recoverEntities()` (after entity refs are restored), call `resolveWheelData()`
then `physics.recomputeStats()` unconditionally. `computeEffectiveStats` reads only
config/model/shipType/wheelData (wheelData null-guarded), touches no entity refs, and is idempotent — no
NPE risk, no reentrancy loop (it reads `ship.wheelData` directly, avoiding the documented circular call).
Cheap; runs once per recovery.

---

## G — Brewing stand contents lost on disassemble / spilled on assembly (HIGH)

**Problem.** `brewing_stand` is `allowed: true` (blocks.yml:716) but has no `storage:` entry and no
`BREWING_STAND` case in `createStorageConfig`, so it returns `null`. `BrewingStand` *is* a `Container`,
so the serialize+clear block (BlockStructureScanner.java:431-448) is skipped: (a) `container_items` is
never written → the stand comes back empty on disassemble (potions/ingredient/blaze powder lost), and
(b) the snapshot is never cleared → `setType(AIR, true)` spills the items on the ground at the assembly
location. It is also not a `TileStateInventoryHolder`, so the secondary path misses it too. In destroy
mode the items silently vanish.

**Fix (future-proof).** In `BlockStructureScanner`, serialize+clear the snapshot for **any** `Container`
regardless of whether a `StorageConfig` was produced — move the serialize/clear/update out from under the
`if (storage != null)` guard, gating only the `storages`-map entry on a non-null config. The **restore
side already works**: BlockStructureScanner.java:875-886 repopulates any `Container` from
`container_items` generically — no companion change needed. Destroy-mode drop is covered by the existing
TileState fallback (ShipInstance.java:1960-1979, `part.storage == null && container_items present`).

**Combined E+G logic (same block):** serialize+clear for any Container (covers brewing_stand, beacon,
furnaces, engines); gate the `storages`-map entry on `storage != null`; additionally exclude **engines**
from the map (E cleanup). **Newly affected blocks (verified):** only `brewing_stand` (target) and
`beacon` (1-slot payment inventory, normally empty — benign). `lectern`/`chiseled_bookshelf` are
`TileStateInventoryHolder`, not `Container` — already handled, do **not** add a redundant case.

---

## G2 — Furnace/smoker virtual 27-slot chest loses items 3–26 on disassembly (HIGH, silent item loss, default config) — re-review: CONFIRMED

**Problem.** Distinct from G (which is about a *missing* storage config). Plain `FURNACE`/`SMOKER` (and
`BLAST_FURNACE`) are explicitly mapped to `StorageType.CHEST` = **27 slots** in `createStorageConfig`
(BlockStructureScanner.java:1002-1006, comment even notes "Furnaces have 3 slots but we'll use CHEST
type"). They aren't interaction blocks, so in flight a plain furnace/smoker opens a 27-slot chest GUI
(DisplayShip.java:1325-1328) and a player can fill all 27 slots. On disassembly the restore truncates to
the real block's inventory size (`deserializeInventory(itemsData, snapshot.getSize())`,
BlockStructureScanner.java:875-886, drops `slot >= size`), so everything in slots 3–26 is silently
destroyed — not returned, not dropped. Blast-furnace engines route through the fuel GUI, so this bites
plain furnaces/smokers specifically. The E+G "serialize any Container" change does **not** fix this — it's
a slot-count mismatch, not a missing serialize.

**Fix.** Give furnace/smoker a real 3-slot `StorageType` (preferred), or constrain the in-flight virtual
inventory to the block's true slot count, or drop the truncated overflow at the disassembly location
instead of discarding it. A dedicated 3-slot furnace storage type also makes the in-flight GUI honest.

---

## H — `SWAP_OFFHAND` bypasses engine-GUI fuel validation (LOW after A)

**Problem.** The engine menu click handler (DisplayShip.java:2216) blocks `NUMBER_KEY` (L2242),
cursor-place (L2253), shift-click (L2263), and drag (L2279) — but not `ClickType.SWAP_OFFHAND` (press F).
With an empty cursor, the offhand item is swapped into a fuel slot, inserting any non-fuel item. Applies
to both the assembled-ship and placed-block GUIs (same handler).

**Severity.** Once **A** lands, non-burnable items no longer grant power, so this is no longer a power
exploit — only cosmetic clutter (arbitrary items parked in fuel slots, retrievable). Fix opportunistically.

**Fix.** In the fuel-slot branch, also cancel when `event.getClick() == ClickType.SWAP_OFFHAND` and the
player's offhand item (`player.getInventory().getItemInOffHand()`) is non-AIR and not valid fuel — mirror
the existing NUMBER_KEY guard at DisplayShip.java:2242-2247.

---

## I — Low-severity hardening (opportunistic one-liners)

- **I1 — ProtocolLib input fields not `volatile`.** The six `isForwardPressed`/…/`isSprintPressed`
  booleans (ShipInstance.java:132-139) are plain fields. The new Paper-input path is main-thread-safe,
  but the still-shipping pre-1.21.2 ProtocolLib path writes them from the netty thread while physics
  reads on the main thread. The e15f794 "race fixed" claim only holds for the new path. **Fix:** mark the
  six fields `volatile` (visibility fix; torn-snapshot is acceptable for per-tick booleans).
- **I2 — Paper input gated at 1.21.2 but `PlayerInputEvent` is a 1.21.3 API.** BlockShipsPlugin.java:54.
  Made safe today by the `Class.forName` guard, so this is correctness/doc only. **Fix:** change the
  guard to `isAtLeast(1,21,3)` and/or correct the "1.21.2+" wording in changelog/comments.
- **I3 (optional) — `previousYaw` initialized from un-normalized `vehicle.getYaw()`** (ShipInstance.java
  ~L369). No observable runtime effect (every consumer wraps the diff in `normalizeAngle`). **Fix:** set
  `previousYaw = spawnYaw` for consistency. Defer unless touching that code anyway.

---

## J — Minor verified leftovers (LOW)

- **J1 — Config default drift between `config.yml` and the `ShipConfig` builder defaults.** `water-density`
  is `3` in config.yml (L57) but the builder default is `2.5f` (ShipConfig.java:359); per-ship `max-speed`
  is `0.55` in config.yml (L286/L370) but the builder default is `0.5f` (ShipConfig.java:341). The builder
  default is only used when a key is **missing** from the user's config, so a user who deletes those keys
  silently gets different behavior than the shipped config. **Fix:** align the builder defaults to the
  shipped config.yml values (`2.5f`→`3f`, `0.5f`→`0.55f`), or vice-versa — pick one source of truth.
- **J2 — `fuel-burn-multiplier` can round a valid fuel's burn time to 0.** `newBurnTicks =
  round(baseBurnTicks * fuelBurnMultiplier)` (ShipPhysics.java:168-182); at a tiny multiplier a low-burn
  fuel (e.g. BAMBOO=50) rounds to 0, so the `if (newBurnTicks > 0)` guard skips it — the item is never
  consumed yet the engine still counts as fueled. Admin-misconfig edge only. **Fix:** `newBurnTicks =
  Math.max(1, round(...))` when `baseBurnTicks > 0`.

---

## K — Captain's Manual shapeless recipe bypasses ingredient validation (HIGH, new in 0.0.16)

**Problem.** The shapeless craft path in `onCraftShipKit` (DisplayShip.java:946-948) skips ingredient
re-validation — comment: *"Shapeless: Bukkit handles validation."* But Bukkit only knows the
`RecipeChoice` returned by `CustomItemIngredient.getRecipeChoice()` (RecipeIngredient.java:248-257), which
is a plain `MaterialChoice(customItem.getBaseMaterial())`. `ship_wheel`'s base material is `PLAYER_HEAD`
(config.yml:208), so the recipe matches **any** player head — a mob head, a decorative head, or a
**balloon** (also `PLAYER_HEAD`-based, config.yml:190). The richer `matches()` PDC check
(`custom_item_id == "ship_wheel"`) is never consulted on the shapeless path. Result: any player head + a
book yields a free Captain's Manual, and the wrong ingredient is silently consumed (e.g. a balloon is
eaten — `onCraftNonConsumable`, DisplayShip.java:1015-1025, only refunds items where `isShipWheel` is
true). The captains_manual shapeless recipe and the shapeless support are both new in 0.0.16.

**Severity.** The only shapeless recipe today yields a cheap book, so the "free manual" itself is
low-value — the real harm is silent consumption of a valuable wrong ingredient (mob head / balloon), and
the validation-bypass is an architectural HIGH-risk for any future shapeless recipe. Treat as HIGH.

**Fix.** In the shapeless branch of `onCraftShipKit`, validate each matrix item against the parsed
`RecipeIngredient.matches()` (as the shaped path already does) and `setResult(null)` if a custom-item
ingredient doesn't truly match. Alternatively, make `CustomItemIngredient.getRecipeChoice()` return a
predicate-backed `ExactChoice`. This affects all custom-item shapeless ingredients, not just the manual.

---

## L — `/blockships give` defects (MEDIUM cluster)

- **L1 — Duplicate `ship_wheel`/`captains_manual` in the giveable list and tab completion** (new in
  0.0.16, extraction regression from `2b53f05`). `sendGiveableItems` (BlockShipsPlugin.java:506-522) and
  `getGiveableItemNames` (:524-537) add `ship_wheel` and `captains_manual` as hardcoded entries **and**
  iterate the `custom-items:` section, which also contains those keys (config.yml:206, :242) — so both
  appear twice in tab-complete and the usage message. **Fix:** de-dupe via a `LinkedHashSet`, or drop the
  two hardcoded `add(...)` lines since both are valid `custom-items` keys.
- **L2 — Items silently lost when the inventory is full** (pre-existing pattern, propagated to new give
  paths). Every give path calls `player.getInventory().addItem(...)` (BlockShipsPlugin.java:270, 278, 286,
  295) and discards the returned leftover map; Bukkit does **not** auto-drop overflow, so a full inventory
  loses the item while the "Gave you a …!" message still prints. **Fix:** capture leftovers and
  `dropItemNaturally` at the player's location (or message "inventory full").
- **L3 — Unhandled `Material.valueOf` on a misconfigured custom-item `base-material`** (latent). The
  give→`createItem`→`loadCustomItem` path (ItemFactory.java:92) throws `IllegalArgumentException` on an
  invalid/misspelled `base-material`, surfacing a raw "internal error" + console stack trace. The sibling
  `createShipKitPlaceholder` (:110-114) already wraps this in try/catch — the custom-item path doesn't.
  **Fix:** wrap in try/catch, log a warning, return null so the command reports "misconfigured item".

---

## M — Help book robustness (LOW/MEDIUM, mostly latent)

- **M1 — `createWrittenBook()` NPEs if `sections` is null.** It dereferences `sections.length`
  (HelpBookContent.java:101) without the null fallback that `getSections()` (:76-85) has, and is reachable
  independently of `load()` (craft preview DisplayShip.java:996, give BlockShipsPlugin.java:277). A
  malformed/throwing `load()` could leave `sections` null → NPE spamming during craft-preview. **Fix:**
  have `createWrittenBook()` call `getSections()` instead of reading the field; wrap `load()` in try/catch.
- **M2 — Pagination ignores Minecraft's real per-page caps.** `estimateSectionLines`
  (HelpBookContent.java:134-137) counts only `content.length()` — not the title, the per-section color-code
  overhead, or embedded `\n` — and nothing clamps to MC's ~256-char/page limit, so user-edited/added
  sections can silently truncate. Current bundled content fits, so this is latent. **Fix:** include
  title + color-code overhead, split on `\n`, clamp page text.
- **M3 — Missing `title:` renders a literal `"null"` header.** `String.valueOf(map.get("title"))`
  (HelpBookContent.java:60) yields `"null"` when a section omits a title. **Fix:** null/blank-guard like
  the `content` case does.
- **Verified OK:** reload is idempotent (recipes cleared+re-added, `addRecipe(recipe, true)` in try/catch);
  give and craft produce the same book; missing/empty `help_book.yml` falls back with a warning.

---

## N — Towny naming coverage + special-drowned (LOW/MEDIUM)

- **N1 — Special drowned + nautilus mount miss the Towny empty-name treatment** (gap in the 0.0.16 Towny
  fix `856439d`). The four ArmorStand/Shulker **ship** entities are all correctly covered, but the special
  drowned (`SpecialDrownedListener.java:122`) and its `ZOMBIE_NAUTILUS` mount (:174) are persistent mobs
  (`setRemoveWhenFarAway(false)`) that never get `customName(Component.empty())`. If the Towny fix's intent
  is "all BlockShips-spawned mobs survive Towny culling," these were missed and a Towny server could cull
  them. Judgment call (they're natural-ish world mobs). **Fix (if in scope):** set empty name +
  `customNameVisible=false` on both. **Verified OK:** ship entities are identified by scoreboard tags, not
  names, so forcing empty names breaks nothing; display entities correctly skip the treatment; the name is
  set inside the spawn lambda (no cull race).
- **N2 — spawn-chance / drop-chance not clamped to [0,1]** (pre-existing). Read raw at
  SpecialDrownedListener.java:74/78; a value >1.0 makes every drowned special. **Fix:** clamp on read.
- **N3 — `/blockships reload` doesn't (un)register the drowned listener on an `enabled` toggle**
  (pre-existing). `reloadConfig()` updates the flag (BlockShipsPlugin.java:242) but never registers/
  unregisters, so toggling `enabled` needs a full restart. No leak (registration is once-only). **Fix:**
  register/unregister based on the new `enabled` state in the reload branch.

---

## Verification

- `make build` after each finding.
- **A:** on a server, load a blast furnace with a non-fuel item (raw iron in the smelt slot), assemble; confirm the ship is *not* faster than an empty-engine ship and the stats panel shows 0 fueled engines. Open the engine menu and confirm the status item no longer claims "has fuel". With real fuel, confirm it counts and burns.
- **B:** set `stats.enabled: false`, open the wheel menu and run detection on **both** an assembled and an
  unassembled ship; confirm ratio/speed%/points are replaced by the "disabled — fixed speed" note and no
  "add sails" hint in either path. Flip to `true` and confirm full stats return.
- **E:** assemble a ship with a fueled engine, set `destruction-mode: destroy`, destroy it; confirm exactly
  one set of fuel items drops (not two) and that it reflects remaining fuel after burning, not the
  assembly-time amount. **Backward-compat (critical):** load a ship saved *before* this fix (engine
  `storage`+`container_items` already persisted), destroy it, confirm a single drop — proves the
  destroy-loop guard, not just the scanner change, is doing the work.
- **F:** build a sail-only custom ship (wool, no engine), fly it, unload its chunk (move far away / reload)
  and return; confirm it still moves. Repeat with `stats.enabled=false`.
- **G:** put a brewing stand with potions+ingredient on a ship, assemble; confirm nothing spills on the
  ground; disassemble; confirm contents restored. Test destroy mode drops them.
- **G2:** in flight, open a plain furnace/smoker on a ship, fill all 27 slots; disassemble; confirm items
  in slots 3–26 are returned (not destroyed).
- **H:** in the engine GUI, press F (offhand swap) with a non-fuel item in the offhand over a fuel slot —
  must be blocked.
- **I:** build-only / no behavioral test needed; confirm `make build` passes and the correct input path is
  still chosen on a 1.21.3+ server.
- **K:** try to craft a Captain's Manual with a mob head / decorative head / balloon + a book — must NOT
  produce a manual nor consume the head. With a real Ship's Wheel + book, it must.
- **L:** with `blockships.give` perms, tab-complete `/blockships give` — no duplicate `ship_wheel`/
  `captains_manual`. Give an item with a full inventory — it drops instead of vanishing. Misconfigure a
  custom-item `base-material` and give it — graceful error, no stack trace.
- **Regression:** the mineflayer test-bot suite (chunk_persistence, airship) still passes.
- **D:** re-read the edited changelog section for style/accuracy. Add Bug Fixes entries for **A, B, E, F,
  G, G2, K** (and L/H/I if landed), each citing its commit. No entry for C.
