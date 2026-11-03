# v0.0.18 - propulsion and flight

> **⚠ Before upgrading: have your players dock every ship, and back up your world and the whole
> `plugins` folder.** Docked ships carry over untouched. A ship still out at sea when the server
> upgrades is docked for it, as blocks, where it stands, the first time its area loads, and has to
> be relaunched from its wheel. A ship in the air is left hanging there as blocks. Engine fuel in a
> ship at sea goes back into its furnaces. The server log says how many ships were left out at sea.
> There is no going back to 0.0.17 once ships have been relaunched on 0.0.18.
>
> **Install [DefCoreLib](https://modrinth.com/plugin/defcorelib) 0.5.0 first.**
> BlockShips now has a hard dependency on it and will not load without it, which also raises
> the minimum server to Paper 1.21.11. If DefCoreLib is too old, BlockShips says so at
> startup and turns itself off (if it is missing, Paper refuses to load BlockShips). Install
> [Mechanism](https://modrinth.com/plugin/mechanism) and
> [BetterBanners](https://modrinth.com/plugin/betterbanners) too if you want propulsion, the
> Glue Brush and the top two sail tiers to be craftable. The server log now says so at startup
> when either is missing.
>
> **Ship damage works differently.** A hit now counts once per ship instead of once for every block
> it touches, so ships take far less from explosions and splash potions, and poison no longer hurts
> them by default. Set `damage.mode: per-block` for the old behaviour. The server log says so at
> startup until `damage.mode` is set in config.yml.
>
> **DefCoreLib blocks now weigh their own mass.** Engines, windmills, propellers and wheels count at
> the mass DefCoreLib gives them instead of 1, so a boat carrying them sits lower and a flier lifts
> less than before.

- **Ships can be propelled.** Fans, propellers, thrusters and reaction wheels from
  Mechanism now drive a ship. **Which way you mount one decides what it does:** facing
  fore or aft adds speed, sideways adds turning, up or down adds lift. Rotation power is
  all-or-nothing, so split large propeller banks across separate networks rather than
  building one that can brown out.

- **You no longer need glowstone to fly.** Enough upward thrust lifts any hull. A ship
  with less lift than it needs **cannot climb at all** and sinks — faster the more it is
  short by, from a slow drift at 99% to a full-speed fall at zero. Holding Space when you
  cannot climb still points what thrust you have downward and slows the fall — how much it
  helps scales with the lift you have, so at very low lift it barely does and at zero lift
  it does nothing. Running out of fuel at altitude is now a real problem, and ditching over
  water floats you.

- **Sails come in four tiers:** wool 3 points, banner 7, large banner 20, huge banner 50.
  Sails raise top speed and — once you are moving — turning rate. They plateau at 80% by
  design, so propulsion is what closes the last stretch. The top two tiers need the
  [BetterBanners](https://modrinth.com/plugin/betterbanners) plugin installed.

- **Ships carry blocks they used to refuse.** Glue dirt, stone or grass to a ship with the
  Glue Brush (craftable: brush + slimeball, and it needs the Mechanism plugin installed)
  and it rides along with real weight.

- **The lock toggle freezes which cells are yours,** so blocks piled against a docked hull
  are not swallowed the next time you assemble. Broken cells are dropped from the set;
  re-freeze to take them back.

- **Cushions are seats** (Minecraft 26.3). Every cushion on your hull is a passenger seat.
  A cushion in the spot behind the wheel is the driver's seat. Sit on it before you assemble
  and you're already at the helm. When the ship docks you land back on the cushion. Item
  frames and paintings on the hull now sail with it too, instead of popping off as items.

- **Land vehicles** ([#10](https://github.com/def9a2a4/BlockShips/issues/10)). Hang wheels
  (DefCoreLib's wheel, large wheel and huge wheel, in every plank wood: four planks around a rotator make a
  wheel, and four planks around a wheel make the next size up) on the left and
  right sides of a hull and it
  drives on land with the normal Ship's Wheel. A wheel takes power through the block it hangs on,
  so run a shaft or engine along the axle to it (it draws power only while the vehicle is assembled). Powered wheels push it along. Unpowered ones
  still roll, so a sail cart works. It hops up steps instead of tilting. A small wheel climbs 1
  block, a large one 1.5 (fences and walls), a huge one 2 (each wheel's lore says), and the smallest
  wheel aboard sets the limit. It keeps its speed rolling down a slope. Detect tells you it's a land vehicle, which wheel limits its climb, and which wheels
  are mounted the wrong way.
  Tuning is under `custom-ships.land` in config.yml: ground drag, step-up speed, land speed, turning and
  the step height per wheel size.

- **Propellers spin when you use them.** Forward propellers follow the ship's speed (backwards
  going astern), side ones and reaction wheels the steering, lift propellers the climb. Only an
  assembled, powered ship spins them: a placed propeller draws no power and stays still, and a
  parked ship with a lit engine no longer sits there with everything turning. Wheels roll at road
  speed and backwards in reverse. Thruster flames follow the nozzle at speed, and a lift-held ship
  holds its height from the moment it's assembled.

- **Engines only burn fuel for power that's used.** A parked or docked ship burns nothing, and half
  throttle burns about half. Thrusters burn by their own use. Thruster exhaust and engine smoke now
  show on ships under way, and go quiet when the engine is burning nothing. The action bar shows how
  long your fuel lasts at the current burn ("fuel 2m10s"), turning yellow under two minutes and red
  under thirty seconds. An engine's empty bucket stays in the engine instead of falling overboard.

- **Propulsion has weight.** Propellers, thrusters, wheels, engines and windmills used to weigh 1,
  the same as a plank; they now weigh what DefCoreLib gives them (a huge propeller 10, an engine 8,
  a huge windmill 15). Ships carrying them sit lower and lift less than before, so a flier that was
  just barely airborne may need more lift.

- **Windmills aboard a ship give half their power, rounded down.** A small windmill gives nothing
  aboard; it used to round up to its full power, so a deck of small windmills ran a ship for free.

- **Only the wheels that touch the ground drive a land vehicle.** A spare wheel on a cabin wall
  added full thrust, and a wheel mounted the wrong way burned fuel the whole time.

- **Re-craft your Captain's Manual.** The book has six new sections covering sails,
  propulsion, mounting, heavy flight, gluing and locking, plus a rewritten Weight &
  Buoyancy — but a manual already in your
  inventory has its pages baked in from when it was crafted and will not update. The help
  button in the ship wheel menu is always current.

- **New: [the flight model](../flight-model.md)** documents how sail power, thrust and
  lift actually combine, with the numbers.

---

## Fixes

### A broken config.yml could be silently overwritten with an empty one

The config upgrade added this release would, on a `config.yml` containing a YAML error, replace the
file with a two-key stub — wiping every setting on the server, permanently, in a way a reinstall
could not undo. Bukkit answers a parse error with an *empty* configuration backed by the jar's
defaults, and `getInt("config-version", 0)` reads the section's own map without consulting those
defaults, so an unreadable file was indistinguishable from a pre-versioned one. The upgrade
"succeeded" against the empty stub and saved it.

`config.yml` is now parsed strictly before anything touches it. If it does not parse, the plugin says
so at SEVERE — naming the error, and stating plainly that every setting in the file is being ignored —
and leaves the file alone. This shipped in no release; it is fixed before it could reach one.

### Config edits that the plugin never reads are no longer silent ([#43](https://github.com/def9a2a4/BlockShips/issues/43))

Since v0.0.16, `blocks.yml` is read from the jar with an on-disk override only at
`plugins/BlockShips/config/blocks.yml`. A copy at the plugin folder root is not read — which looks
exactly like a plugin that ignores its config, and a reinstall cannot fix it because nothing is
broken. The path change was documented but nothing on a running server pointed at it.

- Startup and `/blockships reload` now state which copy of each content file is in force, and name
  the `config/` folder when everything came from the jar. The reload command reports it **in chat**,
  where the admin doing the editing actually is.
- A `config/blocks.yml` that does not parse produces a SEVERE naming the parse error and the real
  consequence — blocks you disallowed are permitted again while the bundled file stands in — instead
  of an empty configuration that silently forbade every block.
- On reload, a broken or empty override no longer discards the working block configuration.
- An override that parses but defines no blocks (the usual result of mis-indented edits) is now
  called out; previously it just logged "0 materials".
- `config/config.yml` is warned about: main settings are read from the plugin folder root, never
  from `config/`.
- Warnings about files the plugin is not reading are no longer gated behind `warn-config-mismatch`.
  That flag suppresses staleness nagging; it should never hide a file whose edits are being ignored.
- **An entry added at the end of your `blocks.yml` was ignored if an earlier one already covered that
  block** — the first match in the file won, so `oak_planks:` after the bundled `"#planks":` did nothing.
  Now the most specific entry wins (a block name over a `*wildcard` over a `#tag`), and between equally
  specific ones the later. In the bundled file this fixes iron and copper doors and trapdoors, which
  weighed 2 (the generic door entry) instead of their own 3.
- README and the `config.yml` / `blocks.yml` headers now document the override path, including how to
  extract a copy from the jar, and that an override replaces rather than merges.

Also corrected two figures in the `blocks.yml` header that the fix points users at: the weight scale
is -20 to 10 (not -2 to 10), and the shipped water density is 3 (not 2.5).

### A docked ship under-reported its own sails

Large and huge banners are display entities attached to a host block, not blocks in their
own right, so the flood fill that scans a docked ship was structurally blind to them — a
hull with four huge banners read 200 sail points docked and 400 the moment it assembled.
The player saw a low Speed %, assembled, and watched it jump. Both paths now share one
counter.

The same audit found five smaller disagreements between what a ship reports docked and
what it reports assembled, all fixed:

- **Max Health lost its cap when docked** — a 3000-mass hull read 3000 in the menu and
  1024 once assembled. This also fired for an *assembled* ship after any server restart.
- **The sail line described itself two ways.** The docked readout was hand-written and
  could not name a tier or get "1 banner" grammatical; both now go through one helper.
- **The stats page listed only wool and banners** above a Sail Power total that included
  the tiers, so the breakdown visibly failed to add up.
- **The predicted waterline was wrong for hulls bottomed in slabs or trapdoors** — the
  preview marker used raw block heights where the assembled ship uses the solid face.
- **An oversized locked ship previewed cleanly and then refused to assemble**, with
  nothing explaining why.

### Ship wheels keep their identity

A wheel is now identified by the record it carries rather than by what happens to be
standing at a remembered position. That closes a run of ways a ship could be stolen,
stranded or quietly lost.

**One breaking change for admins:** `forceclearwheel` now takes `<wheelId> <first8>` and
clears exactly that record. It used to act on whichever wheel was nearest, which meant a
mistyped command destroyed the wrong ship. If you have a runbook, update it.

Data loss, fixed:

- **Shutting the server down could save no wheel data at all.** One wheel in a world that
  had been unloaded at runtime made the whole save throw, silently, every time.
- **A disassembled ship could come back from the dead** — a queued write landing after the
  deletion resurrected the record, and the wheel then sat on "still loading" forever.
- **A wheel the server could not identify vanished** instead of dropping — a `/clone`d copy, a
  wheel whose registration never landed, or one whose ship file could not be read. Breaking any of those
  deleted the block and dropped nothing.
- **Landing on an occupied anchor cell dropped a generic wheel item**, losing the ship's id,
  glue and lock along with it. It now falls back to the protected-anchor path.
- **A `/clone`d wheel could adopt the original's glue anchor**, so an assembly reported
  success while silently leaving blocks behind.
- **Two records cached at one cell quarantined a sailing ship** out of reach for the
  session, and re-quarantined it on every boot.

Other people's builds, protected:

- **Every protection plugin is asked before a ship is assembled**, for each block it would take:
  GriefPrevention, Towny, Lands and the rest, not only WorldGuard. A wheel placed beside a claim
  used to carry the claimed wall off. WorldGuard is also checked on align, disassemble,
  force-disassemble, lock and fire.
- **Arrows, tridents and fireballs respect protection plugins.** A hit another plugin cancelled
  still damaged the ship; melee and explosions already respected it.
- **A menu left open after its wheel was destroyed** could flood-fill a stranger's build,
  overwrite their glue or spawn a marker in their base. Menus are keyed by wheel now.
- **A docked wheel pressed against your hull** is no longer swallowed into your ship.
  It is left out of your ship and stays where it is, record and all — whether you assemble
  straight away or lock first.
- **A copied wheel can no longer take over a ship.** A wheel duplicated with `/clone` or a
  structure block used to pull the original's ship onto itself the moment a mover touched it,
  handing the copier the menu.
- **The wheel is no longer drill-removable.**
- **Off-hand placement no longer plants an untracked wheel head** when your main hand is
  empty or holding a sword.

Also:

- **A docked wheel carried by a rotator, chain hoist or minecart now follows its block**,
  re-deriving its facing as it goes, instead of being left behind.
- `wheels list` gained a four-state health glyph, and two new messages (`CELL_RESERVED`,
  `STAMP_FAILED`) say why a placement was refused instead of failing mutely.
- `wheels adopt` carries a locked structure across, refuses to adopt a block of the wrong
  type, names the record it collided with, and no longer turns away a wheel that kept its
  DefCoreLib mark but lost its id — which is the one case adopt exists for.

### Ships that could be lost

- **Ships in worlds loaded by Multiverse (or another world manager) went dead after every
  restart.** BlockShips read its wheel records before those worlds existed and never looked
  again, so their ships could be neither steered nor docked, with nothing in the log. The
  records are now read when the world loads, and BlockShips loads after Multiverse-Core.
  This is the likely cause of [#46](https://github.com/def9a2a4/BlockShips/issues/46).
- **`/kill @e[type=!player]` or a lag-clear plugin deleted every loaded custom ship**, blocks
  and all. A ship whose vehicle is removed that way now puts its blocks back where it was.
- **A server crash no longer strands ships.** Minecraft writes each chunk to disk on its own
  timer, so after a crash the world, the ships and the plugins' files can be from different
  moments. Recovery now sorts that out:
  - A ship that was sailing comes back where its entities were last written. Before, a ship
    more than about 16 blocks from its last saved spot never came back, and its wheel read
    "still loading" forever.
  - A ship docked or sunk just before the crash is docked or sunk again, at exactly the spot it
    docked, instead of coming back as a ship that can't be steered or docked. Blocks that did
    reach disk are left alone, so a dock that was half written is finished, not duplicated. A
    ship docked and launched again before the crash is docked again only if the launch never
    reached disk.
  - A ship launched just before the crash is still standing at its dock, and its wheel works
    again instead of reading "still loading". Only if every block of it is still there: a launch
    that partly reached disk leaves the wheel for an admin rather than throwing the rest away.
  - A ship that comes back as two copies (it crossed a chunk border between that chunk's saves)
    keeps one; the other, which used to stand there frozen with invisible hitboxes, is removed.
    Hitboxes that didn't come back are recreated, instead of leaving holes in the deck.
  - Cargo is saved with the world rather than every time a chest closes (except when a player
    logs out with one open, when their inventory is saved too), so a crash no longer duplicates
    what was put in or taken out since.
- **The upgrade from 0.0.17 survives a crash during it.** Docking a ship that was at sea, and
  converting a prefab ship, now wait until the world is written before the old copy is thrown
  away, and the ship's engine fuel is kept until then.
- **A 0.0.17 ship docked with its wheel cell blocked** dropped a plain player head. It now drops
  a ship wheel.
- **Crafting a Captain's Manual could cost you your ship wheel** when your inventory was full,
  and could hand out wheels for clicks that crafted nothing. You get back exactly the wheels the
  craft used, at your feet if there is no room.
- **Clicking a ship chest opened it twice**, and clicking a propeller with the wrench moved a
  seated driver to another seat.

### One broken file no longer takes the plugin down

- **A ship save file that cannot be read** is now reported once, at SEVERE, with what to do
  about it: restore it from a backup, repair it, or move it aside. Nothing is renamed or
  deleted, nothing that would destroy the ship acts on it, and its wheel reads "still
  loading" until the file is fixed.
- **A broken prefab ship file** is skipped with a SEVERE instead of stopping the plugin from
  enabling, or emptying the prefab list on `/blockships reload`.

### One hit no longer counts once per block ([#47](https://github.com/def9a2a4/BlockShips/issues/47))

A ship's hitbox is one invisible collider per block, and the game damages each collider a hit reaches
separately. Every one of those came off the ship's HP in full, so one TNT was an instant kill and one
Poison I splash took an 836-HP ship to 60.

- **Each hit now counts once**, at the most any single block of the ship took from it. One explosion,
  one splash potion or one sword sweep is one hit, however many blocks it reaches.
- **Poison and Wither no longer tick on every block.** With the new `damage.poison` setting on, they
  apply once to the ship with vanilla timing: poison stops at 1 HP, wither can sink it. It is off by
  default. Poison already stuck on a ship from before this version is cleared the first time it ticks.
- **Protection plugins are respected.** A ship took damage that WorldGuard, Skript or another plugin
  had cancelled.
- **`/kill` on shulkers no longer sinks every ship in the world.**
- **A sinking ship is destroyed on the next tick**, not in the middle of the explosion that sank it.
  The damage sound and particles play once per hit, not once for every block it touched.
- **Thrown tridents that hit a ship are no longer deleted.**

New `damage:` settings in config.yml: `mode` (`per-ship` or `per-block`), `explosion-multiplier`,
`potions`, `poison` and `suffocation-multiplier` (was hardcoded to 0.25).

### Custom ships came back from a restart with at most 40 HP

Every custom ship reloaded after a restart or chunk reload had its max health reset to 40, whatever its
blocks gave it, along with a regen of 2. Ship save files now record max health. Saves from earlier
versions work it out again from the ship's mass, so ships already affected recover their real max health
the next time they load, and then regenerate up to it.

### Ship health settings

- `damage.multiplier` (default 1.0) scales all damage to ships, on top of the per-cause multipliers
  ([#48](https://github.com/def9a2a4/BlockShips/issues/48)). Set 0.5 for ships that take half damage. This
  is the way to make ships tougher: a ship's max health cannot go above 1024, Minecraft's limit.
- `custom-ships.health.regen-per-second` (default 1.0) replaces a hardcoded value.
- `custom-ships.health.preserve-on-disassemble` (**experimental**, off by default) makes a ship keep its
  HP when you take it apart and put it back together. Without it, disassembling and reassembling a
  damaged ship heals it to full. HP does not regenerate while docked, and the HP is kept on the wheel,
  so breaking a docked wheel and placing it again still resets it.

### TNT cannons ([#15](https://github.com/def9a2a4/BlockShips/issues/15), [#43](https://github.com/def9a2a4/BlockShips/issues/43))

TNT cannons are off by default (`cannons.tnt-enabled: false`). Before this version, a TNT-loaded cannon on
such a server threw its TNT out as a dropped item and gave no reason, which looked exactly like broken
cannons.

- **With TNT off, TNT now stays in the dispenser.** The cannon fires any other ammo it holds. If TNT is
  all it holds, the player who fired is told TNT cannons are turned off on this server.
- **Startup and `/blockships reload` log whether TNT cannons are on.**
- **A cannon fires a random loaded slot**, as a vanilla dispenser does. It used to always fire the first
  one, so fire charges in front of TNT meant only fire charges ever fired.

### Getting on and off

- **Getting off a seat is handled again.** It never was on the current engine: you now step off onto
  the deck instead of into it, carry the ship's momentum when you hop off a moving one, and the helm
  lets go of the controls. Before, a driver who got off holding forward left the throttle stuck, and
  the next player to board drove off without pressing anything.
- **A cushion on a chest, crafting table, cannon or fence is a seat.** Clicking it used to open the
  block or fire the cannon instead. Sneak-click it to open the ship menu.
- **Your camera distance applies however you board**, including sitting on a cushion before assembly
  and after a restart.
- **Detect counts cushion seats**, and no longer counts the seat behind the wheel twice, so the docked
  seat count matches the one the ship gets.

### Settings changed on upgrade

The first start on 0.0.18 rewrites a few lines of config.yml, and logs each change:

- **`custom-ships.stats.enabled` is turned on**, even where it said `false`. It shipped as `false` in
  every older config.yml, so the literal can't tell an admin's choice from the old default. Sails, engines
  and thrusters only count with stats on. Set it back to `false` afterwards if you really want it off.
- **`custom-ships.stats.lift-falloff-exponent` is removed.** Descent now scales with how much lift is
  missing (`sink-speed-exponent`), so the old value has no meaning.
- **`unmanned-drag` and `idle-drag` become one `no-driver-drag`** per ship type, carrying over your
  `idle-drag` (the one that was in force). An existing `no-driver-drag` wins. Without this, upgraded
  servers fell back to a built-in default and ships coasted to a stop much sooner.
- `fuel-burn-multiplier` and `vertical-engine-scale` are removed; nothing reads them.

### Others

- **A ship launched from the water leaves the sea behind.** The cells its hull leaves at or below the sea
  level beside it fill with water, instead of leaving a hull-shaped hole in the sea. Only real water beside the
  hull counts as the sea, so a pool on the deck changes nothing, and a hull with no water beside it leaves air.
  Docking does not pump water out of a hull; rooms below the waterline still flood (#41).
- **Quieter startup, and a debug switch.** Routine startup lines (block counts, version probes, wheel
  saves, ship recovery) now only appear with `debug: true` in config.yml, or after
  `/blockships debug on` (until restart). Turn it on, reproduce a problem, and share `logs/latest.log`.
- **A bed or door that can't land drops an item for each half** that is blocked, instead of disappearing.
  With both halves blocked you get two.
- **Don't `/reload` or PlugMan BlockShips or DefCoreLib.** It was never supported. One more reason: a ship
  docked just before a reload, followed by a crash before the world saves, can be lost.

- **Assembled `/detect` now reports seats**, which only the docked readout did.
- **Removed three dead stat helpers** that hardcoded turning and lift to zero. They are the
  exact shape that caused propulsion to fly a ship while showing up nowhere in its stats.
- **The controls help said "airships only"** for ascend and descend. Any flying ship uses
  the same controls now.
- **Detecting or assembling a ship no longer miscounts its large and huge banners** when
  you arrive beside it. Those banners are entities, and the server loads a chunk's entities
  a moment after its blocks, so a click landing in that window used to report zero tier
  sails — and an assembly in that window wrote the zero into the save file permanently.
  Both paths now recognise "not loaded yet" and say so instead of guessing.
- **Settings missing from config.yml fell back to values that did not match the shipped file**:
  water density 2.5 instead of 3, entity masses up to ten times the shipped ones, ship stats off instead
  of on, among others. A server whose config was missing a line, or whose config.yml failed to parse,
  floated and collided ships differently with nothing to say why. They now match.
- **Custom ships' base speed, turning, drag and collision settings are now in config.yml** under
  `ships.custom.controls` and `ships.custom.collision`. They were only in the code. The values are
  unchanged.
- **The Captain's Manual's Propulsion page was too long**, so its "next page" line could fall off the
  bottom. Re-craft the manual to get the new page.
- **The `special-drowned.drop-chance` comment was wrong.** 1.0 means the wheel drops every time *a player*
  kills one. Above 1.0 it drops however it dies, mob farms included. A `spawn-chance` outside 0–1 is now
  clamped.
- **A crash-redone dock kept only the wheel.** Engines, propellers and other custom heads on the
  hull were dropped when DefCoreLib re-landed a ship after a crash; now only a wheel that was
  already returned as an item is skipped.
- **Claims are checked on more actions.** Placing a ship wheel, spawning a prefab ship from a kit,
  firing a cannon by clicking it, and landing or force-landing a ship now ask every protection plugin,
  not only WorldGuard. A claimed cell counts like a WorldGuard-protected one: landing refuses, and
  force-landing drops that block as an item.
- **A ship kit used from the off hand** is consumed from the off hand, instead of the main-hand item.
- **Several Captain's Manual crafts in one tick** no longer refund extra wheels.
- **A 0.0.17 ship whose save file is missing keeps its root.** Its loose parts are cleared as before,
  but the root is kept (invulnerable) and the log warns once with where it is, so restoring the file
  still converts it.
- **The parts of a 0.0.17 ship that could not be converted** can no longer be killed for shulker shells.
- **Parked ships cost less.** A ship at rest no longer re-poses every block and hitbox every tick.
- **A ship sailing toward unloaded terrain** treats it as solid instead of loading chunks to check it.
