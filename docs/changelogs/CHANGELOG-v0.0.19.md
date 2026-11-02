# v0.0.19 - damage and cannons (unreleased)

> **Ship damage works differently.** A hit now counts once per ship instead of once for every block it
> touches. Ships take far less from explosions and splash potions, and poison no longer hurts them by
> default. Servers that want the old behaviour can set `damage.mode: per-block`. An upgraded server logs
> a line about this at startup until `damage.mode` is set in its config.yml.

## Fixes

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

### Smaller fixes

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
