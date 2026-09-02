## Known bugs

- steering doesnt work for bots. bots appear to mount, move forward, and then unmount and look around instead of just steering. steering for players works fine.
  - the initial implementation of the controls test did have them steering properly, check the git log
- runway fixes:
  - no water or walls for airships, just a two thick stone platform
  - two thick bottom to avoid particle lag
  - set air 1 wider in each direction

## Accepted coverage gaps

(New section — the file above is a bug list; this is deliberately-uncovered ground, recorded so it is
a known limitation rather than an assumption of coverage.)

Chat output is now assertable (`waitForChat` in `lib/helpers.js`) and `weird_ship` uses it to check the
docked sail readout. Everything below is still uncovered.

- **Large/huge banner sail counts.** The highest-value assertion for the docked/assembled parity work,
  and it is unreachable here: those banners are BetterBanners display entities, and CI stages only the
  root defCoreLib core jar (`checks.yml` runs `./gradlew :shadowJar` — root only). With no `bbanners`
  plugin on the server the tiers cannot exist, both code paths correctly report 0, and an assertion
  would pass whether or not the fix is present. Covering it means adding `bbanners` (and `mech`) to CI
  staging in `checks.yml`, `server-test.yml` and `Makefile`'s plugin copy list.
- **Propulsion, thrust classification, lift and sink rate.** Same root cause plus two more: the `mech`
  plugin is not installed either, so no propulsion block exists; the runway builder places only
  stone/water/air, so there is nowhere to build a powered rotation network; and bot steering is broken
  (see above), which blocks measuring vertical behaviour at all.
- **Glue authoring.** Needs a Glue Brush in the inventory and a per-cell left-click sequence against a
  live session. No harness for it.
- **The lock toggle.** `clickWheelMenu(bot, log, 'lock')` now works, but nothing exposes `isLocked()` to
  a bot, so a test could only prove the click landed — not that the ship froze. The valuable assertion
  is the destructive one: lock, dock, pile blocks against the hull, re-assemble, and check the ship did
  not swallow them.

Glue and lock are the only paths that irreversibly rewrite a player's build, so they are the two worth
covering first if this list is ever worked through.