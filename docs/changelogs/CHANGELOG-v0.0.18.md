# v0.0.18 - propulsion and flight

> **Upgrading from 0.0.17: install [DefCoreLib](https://modrinth.com/plugin/defcorelib) first.**
> BlockShips now has a hard dependency on it and will not load without it, which also raises
> the minimum server to Paper 1.21.9. Existing ships carry over. Docked ships need nothing,
> and a ship left out at sea is converted the first time its chunk loads. Install
> [Mechanism](https://modrinth.com/plugin/mechanism) and
> [BetterBanners](https://modrinth.com/plugin/betterbanners) too if you want propulsion, the
> Glue Brush and the top two sail tiers to be craftable. The server log now says so at startup
> when either is missing.
>
> **Ship damage works differently.** A hit now counts once per ship instead of once for every block
> it touches, so ships take far less from explosions and splash potions, and poison no longer hurts
> them by default. Set `damage.mode: per-block` for the old behaviour. The server log says so at
> startup until `damage.mode` is set in config.yml.

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

- **GriefPrevention and Towny are honoured** on right-click, and WorldGuard on align,
  disassemble, force-disassemble, lock and fire.
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

- `custom-ships.health.regen-per-second` (default 1.0) replaces a hardcoded value.
- `custom-ships.health.preserve-on-disassemble` (**experimental**, off by default) makes a ship keep its
  HP when you take it apart and put it back together. Without it, disassembling and reassembling a
  damaged ship heals it to full. HP does not regenerate while docked, and the HP is kept on the wheel,
  so breaking a docked wheel and placing it again still resets it.

### TNT cannons ([#15](https://github.com/def9a2a4/BlockShips/issues/15), [#22](https://github.com/def9a2a4/BlockShips/issues/22), [#43](https://github.com/def9a2a4/BlockShips/issues/43))

TNT cannons are off by default (`cannons.tnt-enabled: false`). Before this version, a TNT-loaded cannon on
such a server threw its TNT out as a dropped item and gave no reason, which looked exactly like broken
cannons.

- **With TNT off, TNT now stays in the dispenser.** The cannon fires any other ammo it holds. If TNT is
  all it holds, the player who fired is told TNT cannons are turned off on this server.
- **Startup and `/blockships reload` log whether TNT cannons are on.**
- **A cannon fires a random loaded slot**, as a vanilla dispenser does. It used to always fire the first
  one, so fire charges in front of TNT meant only fire charges ever fired.

### Others

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
