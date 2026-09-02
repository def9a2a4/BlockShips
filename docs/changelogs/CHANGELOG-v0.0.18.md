# v0.0.18 - propulsion and flight

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
  design, so propulsion is what closes the last stretch.

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
- **A wheel the server refused to place vanished** instead of dropping.
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
