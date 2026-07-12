# Fix audit findings — v0.0.16 release review (A–V)

## Context

Three review passes feed this list. **A–D** came from the stats-toggle review after landing the
`custom-ships.stats.enabled` toggle (`10ea778`), the unassembled-preview fuel fix (`0f29942`), and the
changelog (`b593eb7`). **E–I** came from a **five-agent deep bug review of the whole 0.0.16 release**
(stats, engine/fuel, rotation, destruction mode, tile-entity blocks, optional-ProtocolLib). **J–N** came
from a **third pass over the subsystems the first two left unaudited** (`/blockships give`, help book +
recipes, Towny naming, special-drowned, custom items) plus two minor verified leftovers. Every finding was
independently re-verified against the code and adversarially critiqued. Later passes added the
assembly/disassembly lifecycle findings **Q–U** (the "hopper" crash sweep) and the engine-fuel
persistence finding **V**. Note **F and E have since landed** (`17abd3f`); E ships a narrow regression
(see E/V).

The rotation rewrite and the version/recipe/config-migration subsystems came back essentially clean — no
release blockers there. The defects cluster in engine/fuel and destruction/tile-entity handling.

> **⚠️ SUPERSEDED** — the block below predates findings Q–V and the ship decision. Use the
> **Stats-relevance triage** section (next) as the authoritative priority/scope; it accounts for the
> assembly-lifecycle crashes (Q/R/S), stats-off-by-default, and the E revert. The text below is kept for
> history only. (F and E have since **landed** in `17abd3f`; E carries a narrow regression — see E/V.)

**Priority order (severity, post re-review):** F (CRITICAL — immobile after *every* reload, prefab + sail-only)
→ E (CRITICAL — destroy-mode fuel dup) → A, G2, K (HIGH) → B, L, O2/O3, P1 (MEDIUM) → H, I, J, M, N, P2/P3 (LOW).
A is re-rated HIGH (an exploit reachable without any GUI trick — see A). F was re-rated up from HIGH and
re-scoped to include prefab ships during re-review. K is the third-pass exploit (recipe-validation bypass).
**G and O1 are both REFUTED** false positives (G: javap-proven; O1: `setType(AIR,true)` drops container
contents — pass #4). Findings are independent and can land as separate commits.

**New-in-0.0.16 regressions:** A, B, E, F, G2, K, L1. The rest (H, I, J, L2/L3, M2/M3, N2/N3, O2/O3, P) are
pre-existing latent issues the review surfaced — fix opportunistically. (**M1, N1, G, O1** are non-issues —
false positive / by-design — kept as records.)

**Scope:** A, B, D, E, F, G2, K in scope; L, O2/O3, P1 opportunistic; H, I, J, M, N, P2/P3 as time
allows. **C (live `/blockships reload` for flying ships) is declined** — applying `stats.enabled` (and all
`stats.*` knobs) on next assemble/reassemble/restart is acceptable; that's the current behavior. C is kept
below as a recorded "won't fix" so the limitation stays documented. (**G** kept as a refuted record.)

---

## Stats-relevance triage — 0.0.16 ship decision (supersedes the older priority order)

**Context.** 0.0.16 ships with the power-to-mass **stats system OFF by default**
(`custom-ships.stats.enabled: false`), and engines/power will be **completely refactored** in a
later release. So the release focus is defects that bite *regardless* of the toggle; stats/engine
bugs are largely dormant in the shipped config and can be deferred to the refactor.

Two related changes landed alongside this triage:
- **Engine crafting is now gated on `stats.enabled`** — the `ship_engine` recipe is not registered
  while stats are off (`ItemUtil.registerAllRecipes`; re-runs on `/blockships reload`). So no new
  engines can exist in the default config, which makes every engine-only bug dormant by default.
- The `statsEnabled` field comment in `ShipConfig.java:48` was corrected ("default: true" → false).

### Bin A — NOT stats-related (fix regardless of toggle) — release focus
- **Q (CRITICAL, NEW)** — **hopper crashes assembly** (`HOPPER(5)` size not a multiple of 9). Live crash;
  also destroys container contents + orphans entities via R/S. Top of the release list.
- **R (CRITICAL, NEW)** — assembly is **not exception-safe**: any throw after the scan-clear destroys all
  container contents. Q is its deterministic trigger.
- **F (CRITICAL, stats-independent)** — immobile after chunk recovery. `recomputeStats()` sets the
  fixed effective speeds even when stats are off, so this breaks ships in the **default** config.
  *(fix already landed, `17abd3f`.)*
- **S (HIGH, NEW)** — assembly throw **orphans spawned entities** (ghost blocks that multiply per retry).
- **G2 (HIGH)** — plain furnace/smoker silent item loss (slots 3–26) on disassembly. *(fix approach
  corrected — see G2; the old FURNACE(3)-via-size-based plan would crash like Q.)*
- **K (HIGH)** — Captain's Manual shapeless recipe eats a wrong ingredient / bypasses validation.
- **T (MEDIUM, NEW)** — disassembly can **half-place a ship** on bad sign/banner metadata.
- **O3** — `recoverEntities` throw aborts recovery of the rest of the batch / all ships at startup.
- **P1** — ghost-driver: unmanned ship cruises forever if the seat is lost without `VehicleExitEvent`.
- **L1/L2/L3** — give: duplicate entries, item loss on full inventory, crash on bad base-material.
- Lower/opportunistic (still toggle-independent): **U** (unguarded slot cast), **I1/I2/I3, J1, M2/M3,
  N2/N3, P2/P3, D**, G-comment.

*(New block-storage findings Q/R/S/T/U come from this session's "hopper massive issue" investigation +
a 3-agent lifecycle sweep; all are stats-independent → Bin A. Recommended order: Q → R → G2 → S → K → T.)*

### Bin B — stats-related, GAME-BREAKING (dormant with stats off)
- **E** — engine fuel on destroy. The applied fix (`17abd3f`) **fixed the double-drop** for normal ships
  but **introduced a NARROW no-drop** for crash/unclean-shutdown/legacy saves (verified this session —
  not the blanket "engines drop no fuel"). Only for crafted engines, which can't be crafted with stats
  off → dormant. **Optional: revert the `17abd3f` E hunk** + drop its changelog entry to not ship even a
  dormant regression; otherwise let the refactor supersede it. (See **E**.)
- **V (NEW)** — engine-fuel **persistence fragility**: live `wheelData` fuel is saved only on
  disable/wheel-break, so an unclean shutdown loses it. Root of E's narrow regression. Dormant; fix in
  the refactor (single source of truth — see the note under **V**).

### Bin C — stats-related, MINOR (defer to refactor)
- **A** — non-burnable items counted as fuel (free-thrust exploit).
- **B** — stats UI/detect chat still shows ratio/speed% when disabled. *Cosmetic, but visible in the
  **default off** config* — the one Bin C item worth doing for release polish.
- **H** — `SWAP_OFFHAND` bypasses engine fuel validation.
- **J2** — `fuel-burn-multiplier` can round a valid fuel to 0 burn ticks.
- **O2** — engine GUI refunds burned fuel on close (narrow).

**Recommended release scope:** Bin A serious items (F, G2, K, O3, P1, L1–L3) + B for off-state
polish + revert E; defer the rest of Bin B/C to the engine refactor.

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

> **Critique (in-depth, pass) — SOUND, two notes.** These are exactly the two remaining "is-fueled" scans (`hasFuel` at ShipWheelManager.java:962 was already burn-checked in `0f29942`). Confirmed `getBurnTime` returns >0 for every real fuel (no false-negative that would make a legit engine read unfueled) and a non-burnable can never set `burnTicks>0` (ignition is gated on `getBurnTime>0`), so the `burnTicks>0` early-branch stays safe. **The parked non-fuel item is dropped twice on a destroy-mode death — but that is finding E** (engine fuel double-drop), not new to A; A's count fix + E's destroy-loop guard together resolve it, and A alone needn't touch the drop path. *Optional hardening:* extract a shared `isBurnableFuel(ItemStack)` helper reused by all scan sites so a future 4th site can't reintroduce the bug.

---

## B — Stats UI & detect chat mislead when `stats.enabled: false`

**Problem.** When the system is off, physics uses fixed `config.maxSpeed` etc. and engines don't burn fuel, but the wheel menu and detect chat still recompute and show power ratio, speed %, sail/engine points, and "add sails!" hints — none of which affect a disabled ship. None of these read `statsEnabled`. Purely cosmetic, but reads as "the toggle didn't work."

**Fix.** Thread `statsEnabled` into the display layer (already in scope via the freshly-loaded `config`):

1. Add a `boolean statsEnabled` field to the `ShipInfo` class (ShipWheelMenu.java:40-72 — it's a plain class, not a record) and pass `config.statsEnabled` when it's constructed in `getShipInfo` (~L424; config loaded at L368).
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

In `docs/changelogs/CHANGELOG-v0.0.16.md`: add the commit hash to the detection-fix header (~line 227) to match every sibling Bug Fixes header — `### Unassembled Preview Showed Engines as Unfueled (0f29942)`. Optionally scope the "no config reset is needed" line to "for this toggle" to avoid surface tension with the top-of-file "reset your configs!" line. Add new Bug Fixes entries for the findings that land (Q/R/S/T/G2/K/O3/P1/L…), each citing its commit (no entry for C — won't fix; no entry for G — refuted false positive). Optional: a one-line note in the "Engine Stats Display and Fuel Lifecycle" section that the fueled/unfueled detect-chat breakdown now also covers unassembled previews.

> **Status:** F and E entries **already exist** (CHANGELOG-v0.0.16.md:284 "Ships Immovable After Chunk
> Recovery"; :305 "Engine Fuel Duplicated on Destruction"). If **E is reverted** (see E), **delete the
> :305 entry** — the shipped fix carries a narrow regression, so its changelog claim is inaccurate as-is.

---

## E — Engine fuel dual-source: double-drop (FIXED) → narrow no-drop (OPEN) on destroy-mode death

> **Status (this-session verification):** partial fix landed in `17abd3f` — a single engine-skip on the
> `storages` drop loop. It fixes the double-drop for normal ships but introduces a **narrow** no-drop for
> crash/legacy saves (below). Dormant in the shipped config (engines can't be crafted with stats off).
> The proper fix is a single source of truth — **defer to the engine/power refactor; see V + the refactor
> note there.** Do not add more reconciliation logic here.

**Root cause (dual source of truth).** An engine is a `BLAST_FURNACE` (a `Container`), so
`createStorageConfig` (BlockStructureScanner.java:979, arm :1002-1006) gives it a CHEST `StorageConfig`.
At assembly its fuel is serialized into `container_items` → rebuilt into **both** the in-memory `storages`
map (ShipInstance.java:709-736) **and** `wheelData.engineFuelSlots` (ShipWheelManager.java:283-304).
Thereafter they diverge: physics burns and the GUI edit **only** `wheelData`; the `storages` copy is a
frozen assembly-time snapshot (the code calls it "stale" at ShipInstance.java:1951-1952).

**What shipped (verified `17abd3f`).** On a destroy-mode death, `destroyAndDropItem()` drops container
inventories and, separately, `wheelData.getAllEngineFuelSlots()` (ShipInstance.java:1986-1992). Before the
fix it dropped **both** the `storages` snapshot and wheelData → fuel dropped twice (and the pre-burn
snapshot could drop *more* than the player had). The fix adds an engine-index skip to the `storages` loop
**only** (guard ShipInstance.java:1954; loop 1953-1962), returning at :2023 before the general fallback at
:2045.
- ✅ **Correct for same-session / chunk-reload / clean-restart ships** — wheelData is populated there
  (both copies derive from the same `container_items`), so fuel drops exactly once.
- ⚠️ **Narrow no-drop regression for crash / unclean-shutdown / legacy saves** — there wheelData engine
  fuel is **empty** (never persisted post-assembly — see **V**) while the skipped `storages` snapshot
  still holds the stale fuel → destroy drops **ZERO**. This is *not* the blanket "engines drop no fuel";
  it requires a persistence divergence (finding **V**).

**Doc corrections (verified):**
- The earlier "guard **both** loops" is **over-specified** — the TileState `container_items` fallback
  (ShipInstance.java:1965-1984, gated on `part.storage == null`) is **unreachable by engines** (they have
  a non-null StorageConfig), so a second guard would be dead code. Only the one guard was needed and only
  it shipped.
- The step-2 **cleanup** (exclude engines from `storages` at :709; clear `container_items` after the
  transfer at ShipWheelManager:304) **did NOT ship** — new saves still carry duplicate engine fuel data.

**Decision.** Both the (fixed) double-drop and the (introduced) narrow no-drop are dormant with stats off.
The right fix is single-source-of-truth in the refactor. If you don't want to ship the partial fix, revert
the `@@ -1946` hunk of `17abd3f` (restores the prior double-drop) and drop the E changelog entry
(CHANGELOG-v0.0.16.md:305); otherwise leave it for the refactor to supersede. Either way it's cosmetic for
the default ship.

**Do NOT** skip the scanner serialize/clear for engines — that loses pre-assembly fuel and spills it via
`setType(AIR, true)`. Engine right-click→`EngineMenuGUI` reads `wheelData`, not `storages`.

---

## F — Prefab & sail-only ships immobile after chunk recovery (CRITICAL, regression) — re-review: re-scoped + re-rated

> **Status:** code fix **LANDED** in `17abd3f` (`resolveWheelData()` + `physics.recomputeStats()` at the
> end of `recoverEntities()`, ShipInstance.java:2322-2323). **Still outstanding:** the test is masked —
> `chunk-test.js:432` asserts only `moved > 1.0`; strengthen it to the westward `dx <= -2` check (see the
> re-review note at the end of this section).

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

**⚠️ Why the test suite didn't catch this (re-review).** The `chunk_steering` mineflayer test *does* reload
a chunk and steer a recovered prefab ship — but it only asserts total displacement `> 1.0` blocks, which is
satisfied by the dismount deck-placement offset (vertical + seat offset), **not** horizontal propulsion. The
real directional check (`west >= 2`) runs only on freshly-spawned ships. So the test is a **false negative**
and went green across the CI matrix while the ship was actually dead-in-the-water. When fixing F, also
strengthen `chunk_steering` to assert **directional horizontal movement after recovery**, or this regression
stays masked.

---

## G — Brewing stand contents lost on disassemble / spilled on assembly — REFUTED (false positive)

**Verdict: NOT a bug.** Independent `javap` on `paper-api-1.21.11` proves the load-bearing premise wrong:
`org.bukkit.block.Container extends io.papermc.paper.block.TileStateInventoryHolder`, and
`BrewingStand extends Container` — so a brewing stand **is** a `TileStateInventoryHolder`. The scanner's
two checks (BlockStructureScanner.java:429 and :453) are **independent `if`s**, so a brewing stand
correctly falls through the null-`StorageConfig` first branch and is caught by the second
(`instanceof TileStateInventoryHolder`) branch, which serializes `container_items` and clears the snapshot
(:453-462); restore is handled at :890-899. No loss, no spill, in either destroy or disassemble mode. Do
**not** make the proposed `createStorageConfig`/serialize change. Kept as a record (like C).

**Do this instead (LOW — comment fix, no behavior):** the comment at BlockStructureScanner.java:451-452
says these blocks "implement InventoryHolder but NOT Container," which is factually wrong (every Container
*is* a TileStateInventoryHolder). Brewing-stand safety relies on the two `if`s at :429 and :453 staying
**independent** — if a future refactor "dedupes" them into `if/else-if` believing they're disjoint, every
Container with a null `StorageConfig` (brewing stand, beacon) would silently lose contents. Correct the
comment to state `Container ⊂ TileStateInventoryHolder` and that the second `if` is the catch-all for
Containers lacking a `StorageConfig`.

> **Note:** G being refuted does **not** affect G2 — that's a separate slot-count truncation bug, still real.

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

**⚠️ Fix approach CORRECTED (the earlier "add `FURNACE(3)` via the size-based `createInventory`" plan was WRONG).** The hopper crash (finding **Q**) proves `Bukkit.createInventory(null, slots, name)` throws for any size that isn't a multiple of 9 — so `FURNACE(3)` would crash exactly like `HOPPER(5)`. And `InventoryType.FURNACE` is unsafe as free storage (its result slot is take-only + fuel-filtered — verified). So there is no clean honest 3-slot GUI. Corrected two-part fix:

1. **Shrink the furnace GUI from `CHEST(27)` → `DROPPER(9)`** (a plain 3×3 item grid, already used for real dispensers; 9 is a valid size). `BlockStructureScanner.java:1002-1006` — the `FURNACE/BLAST_FURNACE/SMOKER` arm: `StorageType.CHEST` → `StorageType.DROPPER`. Shrinks the virtual→real gap from 27→3 to 9→3 and gives a saner GUI. (Engines are unaffected — they route through the fuel GUI before this branch; the arm covers only non-engine blast furnaces.) This alone doesn't eliminate the mismatch (9>3), so:

2. **General truncation backstop (REQUIRED — the actual loss fix, shared with U).** The loss is where restore truncates `slot >= realSize`. That's `deserializeInventory` (BlockStructureScanner.java:1049-1069), reached from the **two** `placeBlocks` restore callers — `:881` (Container) **and** `:896` (TileStateInventoryHolder). **Implement the drop-to-world in the callers, NOT inside `deserializeInventory`** — that method is `(List, int)` with no `World`/`Location` in scope (critique). Have `deserializeInventory` **return** the overflow (items with `slot >= size`); each caller `world.dropItemNaturally`s them at the (already-placed) block location. This makes **every** virtual>real mismatch non-destructive: furnaces now (9→3), the existing fleet still saved as `CHEST(27)` (27→3), and any future mismatch. Confirmed it never fires on the assembly/chunk-load paths (`ShipInstance.java:286`/`:714` use their own inline `slot < virtualSize` loops; virtual ≥ stored there — no wrongful drop on load, no dup). Keep the `fromYaml` valid-types string listing every enum value.

**Not affected:** engines `return` before the storage branch (fuel GUI); real chests/barrels declare `storage:` and early-return before the switch; brewing stands use the TileStateInventoryHolder path; the destroy-on-death path drops the whole inventory regardless of size — leave it untouched.

*(Larger alternative, out of scope: size the StorageConfig from the block's actual `getSnapshotInventory().getSize()` at scan time, eliminating the whole enum-vs-reality mismatch class — bigger change to the persistence format.)*

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

> **H-adjacent (LOW, re-review, unconfirmed):** the *placed-block* engine GUI (`EngineBlockMenuHolder` /
> `saveBlockFuelState`) may not gate click validation the way the assembled-ship GUI does — the status/
> refresh branch at DisplayShip.java:2233 only matches `EngineMenuHolder`, so non-fuel could be parked in
> the furnace's slots 0-2. This is **subsumed by A's count/import fix** for the *power* exploit (non-fuel
> stops granting thrust); only cosmetic clutter remains. Confirm opportunistically while doing H.

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

- **J1 — Config default drift between `config.yml` and the key-missing fallbacks.** `water-density` is `3`
  in config.yml (L57) but the fallback is `2.5`; per-ship `max-speed` is `0.55` in config.yml (L286/L370)
  but the fallback is `0.5`. The fallback is only used when a key is **missing** from the user's config, so
  a user who deletes those keys silently gets different behavior than the shipped config. **⚠️ Fix target
  corrected in re-review:** the effective fallback is the **inline second arg of `getDouble`** —
  `ShipConfig.java:202` (`...,0.5`) and `:231` (`...,2.5`). The Builder field initializers at `:341`/`:359`
  are **dead code** (always overwritten by the builder calls), so editing them changes nothing. **Fix:**
  change the `getDouble` defaults at `:202`/`:231` to `0.55`/`3` (or align config.yml) — pick one source of
  truth.
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

**Fix (revised after in-depth critique).** In the shapeless branch of `onCraftShipKit` (DisplayShip.java:946-948), re-validate the matrix, with two corrections the naive version got wrong:
1. **Match custom-item ingredients by PDC, not by name-suffix.** `CustomItemIngredient.matches()` only checks base material + `getDisplayName().endsWith("Ship Wheel")` — so an anvil-renamed vanilla head literally named "Ship Wheel" still passes and yields a free manual, and (worse) it's then consumed **without refund** because the refund gate `onCraftNonConsumable` uses the PDC check `isShipWheel()` (DisplayShip.java:1832-1839), which *disagrees* with `matches()`. Validate the wheel ingredient by its `custom_item_id` PDC (reuse `isShipWheel`/the PDC read) so the prep-gate and refund-gate are consistent and the renamed-head hole is closed. (This also makes the `endsWith` color-code fragility moot.)
2. **Build the ingredient pool with `ingredients.<key>.get(0)` per key**, NOT by flattening the inner lists. Registration (ItemUtil.java:105-112) uses only `get(0)` per key (a list under one key is a *choice*, registered as one ingredient), so flattening would demand N matrix items where Bukkit registered 1 and break legit crafts. Match plain ingredients (BOOK) via the existing `VanillaIngredient.matches`.

Then, for each non-AIR matrix item consume one matching pool entry; on any unmatched item or leftover → `setResult(null); return;` (same mechanism the shaped path trusts — blocks both craft and consumption). Keep the `extractBanner(...)` call. A real Ship's Wheel + BOOK still produces the manual.

`ExactChoice` at registration is **not viable**: the wheel's randomized skin-profile UUID (ItemUtil.java:257) breaks full-ItemStack equality (would reject real wheels), and Paper has no predicate-based `RecipeChoice` for a PDC check — handler-side validation is the right route. Only one shapeless recipe exists today (captains_manual); the fix reads ingredients generically so future ones are covered.

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

## M — Help book robustness (LOW, mostly latent)

- **M1 — `createWrittenBook()` NPE on null `sections` — FALSE POSITIVE (re-review).** It does dereference
  `sections.length` (HelpBookContent.java:101) without the null fallback `getSections()` (:76-85) has, but
  the NPE is **not reachable**: `load()` runs unconditionally in `onEnable` (BlockShipsPlugin.java:51)
  before any caller (craft preview DisplayShip.java:996, give BlockShipsPlugin.java:277, `openBook`
  ShipWheelMenu.java:723), and **every** exit path of `load()` assigns `sections` non-null (fallback array
  on missing resource / empty list / normal `toArray`). The "malformed/throwing `load()`" hypothesis also
  fails — a throw in `load()` aborts `onEnable`, so no caller ever runs with a half-initialized state.
  Kept as a record (like G). **Optional hygiene only (not a bug fix):** have `createWrittenBook()` call
  `getSections()` for consistency.
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

- **N1 — Special drowned + nautilus mount don't get the Towny empty-name treatment — BY DESIGN, not a
  defect (re-review).** Factually true: the four ArmorStand/Shulker **ship** entities are covered, but the
  special drowned (`SpecialDrownedListener.java:122`) and its `ZOMBIE_NAUTILUS` mount (:174) never get
  `customName(Component.empty())`. But the Towny workaround exists to hide the *invisible structural* ship
  entities from Towny's plugin-entity culling; the drowned and nautilus are **real, visible mobs meant to
  be fought**, not structural markers. Empty-naming them would be semantically wrong and isn't the fix's
  intent. **Not a regression, not in scope** — reclassified to won't-fix (like C/G). Kept as a record.
- **N2 — spawn-chance / drop-chance not clamped to [0,1]** (pre-existing). Read raw at
  SpecialDrownedListener.java:74/78; a value >1.0 makes every drowned special. **Fix:** clamp on read.
- **N3 — `/blockships reload` can't *enable* the drowned listener at runtime** (pre-existing). `reloadConfig()`
  updates the flag (BlockShipsPlugin.java:242) but never registers/unregisters. **Re-review narrowed this:**
  only the **off→on** direction is broken — if the plugin starts with the feature disabled, the listener is
  never registered, so flipping to `true` + reload does nothing until a restart. The **on→off** direction
  already works via the `if (!enabled) return` guard at SpecialDrownedListener.java:88. No leak (registration
  is once-only). **Fix:** register/unregister based on the new `enabled` state in the reload branch.

---

## O — Engine/recovery leftovers from the catch-all sweep (MEDIUM) — re-review: O2 real-narrow, O3 real-edge, **O1 REFUTED (false positive)**

- **O1 — Placed engine block broken/exploded destroys its furnace fuel — REFUTED (false positive, pass #4
  resolved).** The claim was that `onBreakShipEngine` (DisplayShip.java:2368-2372) /
  `handleExplosionEngineDrops` (:2394) call `block.setType(Material.AIR)` and thereby destroy a placed
  engine's fuel. **Resolved against the code:** placed-engine fuel *does* live in the real furnace container
  (`EngineMenuGUI.openForBlock` :283-286 reads it, `saveBlockFuelState` :308-321 writes it via
  `blockInv.setItem`), **but** `block.setType(Material.AIR)` defaults to `applyPhysics=true` — identical to
  the scanner's `setType(Material.AIR, true)` (BlockStructureScanner.java:966/971), which triggers vanilla
  `onRemove` → `Containers.dropContents`. The scanner's own anti-spill code (clearing the snapshot first
  "to prevent item drops when removeBlocks() calls setType(AIR)", :437-440) is the codebase's evidence that
  `setType(AIR, true)` **drops** container contents. So a broken engine's fuel **drops naturally on the
  ground** — not destroyed. (Same premise finding **E** depends on, so the two are consistent.) The proposed
  "drop Container contents before setType(AIR)" fix would cause a **double drop** — do **not** apply it.
  Kept as a record (like G). Only loss in the original claim that survives is cosmetic: the fuel drops as
  loose items at the block rather than back into the broken engine — vanilla furnace-break behavior, fine.
- **O2 — Engine fuel GUI opened while sailing refunds burned fuel on close** (free fuel / desync; CONFIRMED
  ×2). `EngineMenuGUI.open` snapshots `wheelData` slots at open time (:97-101); `tickEngineFuel` burns the
  **live** `wheelData` array each tick while any movement key is held (ShipPhysics.java:151-191); on close
  `saveFuelState` overwrites the live array with the stale snapshot (:229-240) and never rewrites
  `engineBurnTicks` → consumed fuel restored + burn-ticks desync. Also fires on the status-slot
  click-refresh (DisplayShip.java:2234). **Reachability (re-review): NARROW** — the driver is seated and
  can't reopen the engine block mid-sail, so this needs a **non-driver** to hold the engine GUI open while
  the driver sails (fuel burning during the open window). Real but situational; MEDIUM stands with this
  caveat. **Fix:** merge GUI edits against the live array, or skip/re-sync while the engine is actively
  burning.
- **O3 — `recoverEntities` throws on an out-of-range block index, aborting batch recovery** (CONFIRMED).
  The bulk path throws `IllegalStateException` when `blockIdx >= model.parts.size()` (ShipInstance.java:2234-2237);
  the per-ship recovery loop has `try/finally` with no `catch` (DisplayShip.java:166-204), so one bad ship
  aborts recovery of the rest of that chunk's batch. The incremental `tryAddEntity` path already skips
  out-of-range indices. **Reachability (re-review): EDGE** — only when a ship's persisted `model.parts` no
  longer matches the live model (model edit / regen between save and load); not hit on a normal reload.
  Real robustness gap. **Fix:** replace the throw with `continue` + a warning, matching `tryAddEntity`.
  - **Broadening (pass #4):** the throw also propagates out of the **startup** recovery sweep
    (`recoverUnregisteredShips`, DisplayShip.java:118-128, no catch), aborting recovery of *all* remaining
    ships across every chunk/world at startup — not just one chunk's batch. Strengthens the case for the fix.

---

## P — Session/persistence leftovers (pass #4 gap-hunt)

- **P1 — Ghost-driver acceleration: horizontal physics not gated on `hasDriver` (MEDIUM).** ShipPhysics.java:240-251
  gates accel/drag only on `isForwardPressed`/`isBackwardPressed`, never on `ship.hasDriver`. Those flags are
  cleared only by `freeSeat()` ← `VehicleExitEvent` (DisplayShip.java:1415); there is no `EntityDismountEvent`/
  `PlayerDeathEvent`/`PlayerChangedWorldEvent` handler. If a driver loses the seat without `VehicleExitEvent`
  firing (death while seated, forced cross-world teleport), `isForwardPressed` stays `true` → line 240 pins
  `currentSpeed` to max and the drag/stop blocks (251, 270) are skipped → the ship cruises forever, unmanned.
  Vertical physics *is* gated on `hasDriver` (ShipPhysics.java:503/508/514) — a real asymmetry. **Fix:** gate
  the accel/drag branches on `ship.hasDriver` (reconciled to real passengers every tick in `handleSteeringInput`,
  ShipInstance.java:1489-1494), which removes the stuck-flag dependency entirely.
- **P2 — Orphaned per-world metadata `.yml` on wheel-break destroy (LOW, disk leak).** Breaking an assembled
  ship's wheel (DisplayShip.java:2201 → `ShipWheelManager.removeWheel`:139-150) calls `ship.destroy()` but never
  `shipWorldData.removeShip(...)`, unlike the disassemble path (ShipWheelManager.java:493). The chunk index
  self-heals, but `worlds/{world}/ships/{uuid}.yml` is never deleted and no orphan sweep exists → permanent
  dead-file accumulation (disk only; no dupe/gameplay effect). **Fix:** call `removeShip` in `removeWheel` when
  destroying an assembled ship.
- **P3 — Async metadata write can resurrect a just-deleted ship file (LOW, disk leak/race).** The 60s periodic
  task (DisplayShip.java:214-229) snapshots registered ships and submits async `{uuid}.yml` writes; if a ship is
  removed after snapshot but before the write lands, `removeShip()` deletes the file then the queued write
  recreates it. Index is authoritative on load (no live dupe). **Fix:** a tombstone set the IO executor checks,
  or route `removeShip`'s delete through the same `ioExecutor`.

---

## Q — Hopper (any non-9-multiple storage) crashes assembly (CRITICAL, stats-independent, NEW) — LIVE crash

**Problem.** Assembling any ship containing a **hopper** throws `IllegalArgumentException: Size for custom inventory must be a multiple of 9 between 9 and 54 slots (got 5)` at `ShipInstance.java:710` (assembly) and the identical call at `:286` (recovery/persistence-load). Both build the block's in-flight storage GUI with the **size-based** overload `Bukkit.createInventory(null, storage.type.slots, name)`, which Bukkit restricts to a multiple of 9 (9–54). `StorageType.HOPPER(5)` isn't → hard throw. It only works today for `CHEST(27)`/`DOUBLE_CHEST(54)`/`DROPPER(9)` because those are multiples of 9.

**Blast radius — worse than "won't assemble" (this is why it feels like "massive issues").** `scanStructure` runs first and **clears every container's world inventory** (`getSnapshotInventory().clear(); update()`, `BlockStructureScanner.java:437-441`/`458-461`), keeping the only copy in the in-memory model. The throw at `:710` then aborts the constructor; with **no try/catch** on the path (`ShipWheelManager.assembleShip:226-266`, caller `DisplayShip.java:2024`), the model is discarded → **every chest/dropper/shelf on that ship is permanently emptied**, and the display/collider/seat entities spawned before the hopper are left as **persistent orphaned ghosts** that multiply on each retry click + console spam. (These two secondary symptoms are the general findings **R** and **S**.)

**Fix (validated by 3 critique agents — correct + complete).** Route odd-size storage through the `InventoryType` overload — `Bukkit.createInventory(holder, InventoryType, title)` has **no** multiple-of-9 limit and `InventoryType.HOPPER` = 5 slots, a plain item grid (no restricted slots). Confirmed present in paper-api-1.21.11 via `javap`.
1. `ShipModel.StorageType` (`:480-491`) — add a nullable `InventoryType invType` field (import `org.bukkit.event.inventory.InventoryType`); set `HOPPER(5, InventoryType.HOPPER)`, others `(…, null)`.
2. Add a `ShipInstance.createStorageInventory(StorageConfig)` helper: type-based when `invType != null`, else size-based (keeps `Bukkit.createInventory` out of the `ShipModel` data class; `ShipInstance` already imports `Bukkit`/`Inventory`/`Component`).
3. Route **both** sites (`:286`, `:710`) through it — they are the only two storage-inventory creations; all consumers read `getSize()`, never `.slots`.

A type-based HOPPER inv returns `getSize()==5`, so serialize/restore stay consistent; the virtual inventory is inert (null holder, no world position → no hopper transfer logic runs); no migration concern (hopper ships never persisted — assembly always threw). **Compile check:** confirm the `(InventoryHolder, InventoryType, Component)` overload exists in the build's paper-api.

---

## R — Assembly is not exception-safe → any throw destroys container contents (CRITICAL, stats-independent, NEW)

**Problem.** `scanStructure` (called at `ShipWheelManager.assembleShip:235`) **clears every container's world inventory** (`BlockStructureScanner.java:437-441` Container, `:458-461` TileStateInventoryHolder) **before** `new ShipInstance` (`:251`), keeping the only copy in a model that is discarded on throw; `removeBlocks` (`:259`) runs after. There is **no try/catch** anywhere on the path (neither `assembleShip` nor the `DisplayShip.java:2024` ASSEMBLE handler). So *any* throw in that window permanently destroys all container contents. **Q** (hopper) is the 100%-reproducible trigger; other currently-unguarded throw sources in the same window: `Bukkit.createBlockData` (`ShipInstance.java:657`/`:681`), `Material.valueOf` (`:518`/`:683`), `DyeColor.valueOf` (`:534`), unchecked rawYaml casts (`:641`/`:721`), `createInventory` (`:710`). Low-probability on a normal ship, but the consequence is irreversible silent item loss in an undo-less action.

**Fix (verified SOUND by critique — "defer the clear").** Keep the **serialize** in `scanStructure` (the constructor reads container contents only from `rawYaml["container_items"]` at `:714`, never the live world; engine-fuel/lead transfer read the serialized copy / entities; double-chest coercion never writes back to the world — all confirmed). Move the two `clear()+update()` pairs out of scan and into `removeBlocks`, run **in the solids pass only** (containers are never attachable), per block just before its `setType(AIR, true)` (re-fetch `getState()` fresh). Result: any throw between scan and removeBlocks leaves world containers **full** → a failed assembly is a clean, retryable no-op.

**⚠️ Document the same-tick invariant.** This is safe *only because* scan→construct→removeBlocks is synchronous, same-tick — a still-full real hopper can't fire mid-construction. Add a code comment stating this; a future `runTaskLater` inserted between construct and removeBlocks would silently turn this into an item-**dupe** bug (a neighbor hopper could vacuum the still-full, already-serialized container → dupe on restore).

---

## S — Assembly throw orphans spawned entities (HIGH, stats-independent, NEW)

**Problem.** The assembly constructor spawns the vehicle armor stand (`ShipInstance.java:324`), parent BlockDisplay (`:383`), child displays (`:490`/`:650`/`:908`), and collider carriers + shulkers (`:753`/`:779`) as it goes. On a mid-constructor throw (see **R**), the `ShipInstance` reference is discarded (never returned), so there is **no handle to despawn them**, and no metadata was saved for orphan-recovery to adopt them → permanent invisible ghosts overlapping the still-present real blocks, **multiplying on every retry click**. The collider loop already cleans up its own per-block pair on failure, but nothing covers vehicle/parent/displays or a throw outside the collider try.

**Fix (critique-simplified).** Wrap the constructor's spawn sequence in try/catch; on failure reuse the **existing teardown** (`destroyWithCleanup`/`destroyWithPersistenceCleanup`) over the already-accumulated `this.vehicle/parent/displays/colliders/seatShulkers` fields, then rethrow — no new per-entity tracking needed. Removals are `isValid()`-guarded, so there's no double-cleanup conflict with the collider loop's inner catch. With **R + S**, a failed assembly leaves neither lost items nor ghosts.

---

## T — Disassembly can half-place a ship on bad metadata (MEDIUM, stats-independent, NEW)

**Problem.** `placeBlocks` restores each block, then its metadata; the metadata restores are **unguarded**: `DyeColor.valueOf` (`BlockStructureScanner.java:844` banner / `:917` sign), gson component parse (`:912`), unchecked `(List)/(Map)` casts (`:834`/`:904`), the container/TileState restores (`:875-899`), and the skull-profile restore (`:821`). A throw mid-loop leaves some blocks placed, the rest not, and the ship entity **still registered** → corruption / duplication. (The blockdata parse at `:803-811` is already guarded; the metadata restores are not.)

**Fix.** Wrap each per-block metadata restore in its own try/catch that logs and skips *that block's metadata* while still placing the block (already placed with default state at `:806`/`:814` → consistent). Scope: banner/sign color, gson, casts, **plus the container/TileState restores (`:875-899`) and skull profile (`:821`)** — a throw there also strands items mid-loop.

---

## U — Unguarded `(Integer)` slot cast in restore (LOW, stats-independent, NEW)

**Problem.** `deserializeInventory` (`BlockStructureScanner.java:1054`) and `ShipInstance.java:721` do `(Integer) itemData.get("slot")`. YAML normally round-trips ints as `Integer`, but a migrated/hand-edited model with a `Long`/`Double` slot throws `ClassCastException` and aborts the whole restore (uncaught, unlike the per-item deserialize try/catch). Same class as O3/L3. **Fix:** `((Number) itemData.get("slot")).intValue()` — the codebase's convention elsewhere (e.g. `ShipWheelData.java:459`). Folds naturally into **T** and into **G2**'s backstop edit (same method).

---

## V — Engine fuel persistence is fragile: live fuel lost on unclean shutdown (stats-related, NEW) — dormant

**Problem (verified this session).** `wheelData.engineFuelSlots` is the ONLY live/authoritative engine
fuel (physics burns it, the GUI edits it), but it is persisted ONLY by `ShipWheelManager.saveAll()`,
which has exactly three call sites — `BlockShipsPlugin.java:99` (onDisable), `ShipWheelManager.java:169`
(breakWheelBlock), `:182` (destroyWheelBlock). There is **no assembly-time save and no periodic save**.
Meanwhile the `storages` engine snapshot is flushed every ~60s and on every chunk unload via
`saveShipMetadata` (ShipWorldData.java:200-220; DisplayShip.java:213-229, :382-397) — but nothing ever
reads it back into `wheelData`. So on a **crash / SIGKILL / any unclean shutdown**, a ship
assembled-and-sailed since the last clean disable has NO persisted `engines` entry → on reload
`wheelData.engineFuelSlots` is empty, and the recovered ship reads as unfueled even though the stale
pre-burn snapshot survives (unread) in `storages`.

**Scenario matrix.** Fresh / chunk-reload / clean-restart: wheelData LIVE-CORRECT (fine). Crash /
unclean-shutdown / legacy save (no `engines` key): wheelData EMPTY, storages STALE — divergence. This is
the persistence root of **E**'s narrow no-drop regression.

**Relationship.** Shares E's dual-source root cause but is a distinct consequence (crash-loss vs destroy
double-drop); not covered by **P** (the separate ship-metadata `.yml` system). Dormant with stats off
(engines can't be crafted). Defer the real fix to the refactor below; do not add band-aids now.

### Engine/power refactor guidance — single source of truth ("engine is a container")

The whole cluster — **E** (double-drop), **V** (crash-loss), **O2** (GUI refund), the staleness
over-drop, and the **G2**-adjacent furnace-slot mismatch — stems from engine fuel living in two places.
The clean cure the coming refactor should adopt: treat the engine as what it is, a **3-slot furnace
container** in the existing `storages` system. Physics and the GUI operate on that one live inventory
directly; it persists with the ship on the robust metadata path; destroy/disassembly reuse the generic
container drop/restore. Delete `wheelData.engineFuelSlots` + `engineBurnTicks` and all their
special-casing. This collapses E, V, O2, and staleness into "engines are containers." Costs: a real
3-slot storage type that avoids the **Q** multiple-of-9 crash (reuse Q's `InventoryType` overload), a
one-time migration of the old `engines` save data, and burn-ticks become in-memory only (acceptable —
a mid-burn engine re-lights fresh after a reload).

---

## Verification

- `make build` after each finding.
- **Q (hopper):** hopper in a custom ship → assembles with **no exception**; 5-slot hopper GUI; items round-trip through assemble→disassemble; **no orphaned ghost entities**; neighbor chests keep their contents. Reload the chunk → recovers cleanly (exercises `:286`).
- **R (assembly item safety):** force a mid-assembly throw (a block with deliberately malformed blockdata) → assembly fails but **every container on the ship keeps its items** and the blocks remain in the world; retry works.
- **S (orphans):** same forced throw → **no leftover** display/collider/seat entities after the failure (count entities before/after).
- **T (disassembly):** disassemble a ship with a sign/banner carrying a deliberately bad color value → the block still places, only its metadata is skipped, and the ship fully disassembles + deregisters (no half-placed, still-registered state).
- **A:** on a server, load a blast furnace with a non-fuel item (raw iron in the smelt slot), assemble; confirm the ship is *not* faster than an empty-engine ship and the stats panel shows 0 fueled engines. Open the engine menu and confirm the status item no longer claims "has fuel". With real fuel, confirm it counts and burns.
- **B:** set `stats.enabled: false`, open the wheel menu and run detection on **both** an assembled and an
  unassembled ship; confirm ratio/speed%/points are replaced by the "disabled — fixed speed" note and no
  "add sails" hint in either path. Flip to `true` and confirm full stats return.
- **E:** (fix landed `17abd3f`; dormant with stats off.) Same-session: fueled engine, `destruction-mode:
  destroy`, destroy → exactly **one** drop (not two). Narrow-regression check: assemble a fueled engine,
  **hard-kill** the server (no clean shutdown) so the wheel fuel is never saved, restart, destroy →
  currently drops **zero** (the open no-drop). If E is reverted, this case drops the stale snapshot
  instead. (Both dormant unless stats are on.)
- **V:** assemble a fueled engine, sail to burn some fuel, **SIGKILL** the server, restart → the engine
  reads as unfueled (live fuel lost; only the stale pre-burn `storages` snapshot survives, unread). A
  clean restart (Ctrl-C / `/stop`) preserves it. Confirms the save-trigger fragility. Dormant with stats
  off; real fix is the container refactor.
- **U:** hand-edit a persisted model so a `container_items` entry's `slot` is a YAML float/long, load the
  ship → restore no longer aborts with `ClassCastException` (the whole inventory still restores).
- **F:** build a sail-only custom ship (wool, no engine), fly it, unload its chunk (move far away / reload)
  and return; confirm it still moves. Repeat with a **prefab** ship and with `stats.enabled=false`.
  **Also strengthen `chunk_steering`** to assert directional horizontal movement after recovery (it currently
  only checks total distance >1.0, a false negative — see F).
- **G:** REFUTED (false positive) — no test. (Optional regression: a brewing stand with potions round-trips
  through assemble→disassemble with no spill — confirms the existing TileStateInventoryHolder path.)
- **G2:** in flight, open a plain furnace/smoker on a ship → GUI now shows **9 slots** (was 27); fill slots
  3–8; disassemble → the overflow **drops at the block** (not vanish), slots 0–2 round-trip into the real
  furnace. Also load a furnace ship **saved before the fix** (still `type=CHEST(27)`), overfill, disassemble
  → overflow drops rather than being destroyed (proves the backstop covers the legacy fleet). Confirm nothing
  drops on chunk-load of a normal chest.
- **O1:** REFUTED (false positive) — no fix. (Optional confirmation: put fuel in a placed engine, break it —
  the fuel drops on the ground, confirming `setType(AIR,true)` drops contents, consistent with E's anti-spill
  rationale.)
- **P1:** mount and drive a ship, then lose the seat via a non-`VehicleExitEvent` path (die while seated, or
  force a cross-world teleport); confirm the ship stops (does not cruise on unmanned). Re-mount and confirm
  normal control.
- **O2:** with a second player, open the engine GUI while the driver holds W (engine burning), then close
  it — burned fuel must NOT be refunded. (O3 is build/trace-only.)
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
  G2, K** (and L/O/H/I if landed), each citing its commit. No entry for C (won't fix) or G (refuted).
