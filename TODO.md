# BUGS

## SEMI-COMPLETE

- [x] banner rendering broken
  - floor banners not rotated correctly
  - position and rotation of wall banners is off -- this used to work at some point?
  - significant duplication in this code path, refactor it

- [x] fix health display on shulkers -- should show current/max health. Since some ships have a *lot* of health:
  - if total health under 20 hearts (40 HP?), show directly as hearts
  - if total health over 20 hearts, scale max health to 20 and show a percentage

- [x] ladder duplication bug: when supporting blocks get removed, the blocks supported by them get dropped as items
  - filter for blocks that need to be supported and remove those first (ladders, torches, signs, etc)
  - still edge cases where blocks that need support are supporting other blocks (eg signs on top of signs) but this is acceptable for now

## LONG TERM

- [ ] Re-enable bot tests for 26.x once `minecraft-protocol` supports it. Currently only server startup is tested.
  - https://github.com/PrismarineJS/minecraft-data/pull/1184
  - https://github.com/PrismarineJS/node-minecraft-protocol/pull/1481
- [ ] ShipRegistry uses non-thread-safe HashMap but is accessed from multiple threads (chunk events, periodic saves). Use ConcurrentHashMap to prevent ConcurrentModificationException.
- [ ] (pre-1.21.9) Rotation logic bug - delta rotation math in ShipInstance.java:1110-1130 has inconsistencies, displays may rotate incorrectly (see https://github.com/def9a2a4/BlockShips/issues/7)
  - the chunk-reload rotation snap is fixed in v0.0.16; the more severe collider/skin desync on legacy versions may remain
- [ ] (pre-1.21.9) Custom ship spawnYaw mismatch - initial spawn uses vehicle.getYaw() but recovery uses model.initialRotation.x, causing rotation snap after restart (see https://github.com/def9a2a4/BlockShips/issues/7)
- [ ] (pre-1.21.9) spawnYaw not persisted to save data - ships rotated before restart may snap when loaded (see https://github.com/def9a2a4/BlockShips/issues/7)
- [ ] player does not get moved along with a ship, does not inherit velocity properly (see https://github.com/def9a2a4/BlockShips/issues/13)
- [ ] ship to ship collisions not working, temporarily disabled
  - NOTE: issue https://github.com/def9a2a4/BlockShips/issues/19 is CLOSED but this behavior is currently disabled — tracker gap, candidate for reopening
- [ ] fix heads on walls display and colliders (see https://github.com/def9a2a4/BlockShips/issues/20)
- [ ] multiple ships wheels is buggy? (see https://github.com/def9a2a4/BlockShips/issues/21)
- [ ] deck physics: if "player is not sending any input + player is standing on collision shulker + ship is moving", then teleport the player with the ship (see https://github.com/def9a2a4/BlockShips/issues/13)
  - sneak is fine. maybe only teleport along if the player is sneaking?

## UNCLEAR

- [ ] loading colliders for really large ships sometimes goes wrong? (see https://github.com/def9a2a4/BlockShips/issues/8)




# REFACTORING

- clean up code and use a unified "mechanism" class

# FEATURES

## HARDER

- [ ] ship-to-ship collisions
  - NOTE: issue https://github.com/def9a2a4/BlockShips/issues/19 is CLOSED but collisions are currently disabled (see BUGS) — candidate for reopening

- [ ] land vehicles: drive block structures on land, "jump" up blocks like horses. see https://github.com/def9a2a4/BlockShips/issues/10 and design doc docs/wip/TODO-land-vehicles.md

- [ ] cannon fire control: cannons already fire (DisplayShip.fireAllCannons), but firing control/aiming/timing is unimplemented. see https://github.com/def9a2a4/BlockShips/issues/22

- [ ] partial / progressive destruction: damage individual blocks rather than whole-ship destroy (distinct from the shipped full-destruction mode, #27). see https://github.com/def9a2a4/BlockShips/issues/24

- [ ] implement furnaces (normal furnaces) on ships
- [ ] add TileEntity serialization for campfires, decorated pots, etc? (chiseled bookshelves, shelves, and signs done). see https://github.com/def9a2a4/BlockShips/issues/23 and design doc docs/wip/TODO-tileentities.md

- [ ] allow setting extra colliders in a model. have this just be another list at the end, separate from blocks and items
  - this is useful for large balloons. the balloon might be a giant item display entity and we might want to have one or more large colliders for it

- [ ] minecart ships: ride rails, assemble/disassemble via activator rails. design idea only, no issue filed yet — see docs/wip/TODO-minecart.md

## PLUGIN / VERSION COMPAT

- [ ] Folia support. analysis in docs/wip/TODO-folia.md. see https://github.com/def9a2a4/BlockShips/issues/14
- [ ] Movecraft integration. see https://github.com/def9a2a4/BlockShips/issues/16


## MAYBE

- maybe: shulker taking damage destroys that block only? for custom block ships
- allow leading ships. not sure about this one. maybe only prefab ships?

# OPTIMIZATIONS

- make sure all "is ship moving" related checks happen in the same function
- make sure all right click checks handled by the same function
- smarter conversion to minimize shulkers:
  - dont spawn shulkers for "interior" blocks (those not touching the outisde)
  - relates to large-ship performance, https://github.com/def9a2a4/BlockShips/issues/8 — design in docs/wip/TODO-skip-interior.md and docs/wip/TODO-profiling.md

## CODE QUALITY

- ShipInstance has lots of duplication of code for banner/player head rotation/position etc etc. refactor this mess.
- ServerVersion.java overflow at Minecraft 2.x - version number calculation assumes minor/patch < 100
- Silent health regen failures after first tick in ShipInstance.java - only logs once, subsequent failures silent
- Player disconnect handler in DisplayShip.java missing logging - hard to debug seat issues
- SteerPacketCompat.logged field should be volatile for thread safety
- BlockStructureScanner: Container/TileStateInventoryHolder double-match — Container extends TileStateInventoryHolder in Paper API, so both instanceof checks fire for every Container block. Harmless (isEmpty guard prevents data loss, double-restore is idempotent) but wasteful. Fix: restructure Container path to always serialize/clear regardless of StorageConfig, then use `else if` on TileStateInventoryHolder. Must handle brewing stand (Container with null StorageConfig).
- BlockStructureScanner: cache `block.getState()` in serialization loop — currently 6+ calls each creating a new snapshot. Cache into a local variable and use pattern matching for Container/TileStateInventoryHolder/Sign/Skull/Banner checks.
- PaperInputListener: constructor takes unused `JavaPlugin plugin` param — remove it and update call site in BlockShipsPlugin


# RECENTLY DONE

## v0.0.16

- [x] custom ship stats: power-to-mass ratio drives speed/rotation; sails (wool/banner) and fueled engines (blast furnaces) add power; stats shown in ship info. see https://github.com/def9a2a4/BlockShips/issues/18 — docs/wip/TODO-stats.md, docs/wip/TODO-stats-fixes.md
- [x] use `PlayerInput` event on newer versions (Paper 1.21.2+), fall back to ProtocolLib if not; clear console/chat error if neither present. ProtocolLib now optional. see https://github.com/def9a2a4/BlockShips/issues/28 — docs/wip/TODO-playerinput.md
- [x] Towny compat: ship entities set empty custom name to dodge Towny's mob removal timer. see https://github.com/def9a2a4/BlockShips/issues/17
- [x] destruction: configurable full-destroy mode (`custom-ships.destruction-mode: destroy`), drops stored items/fuel/leads instead of placing blocks. see https://github.com/def9a2a4/BlockShips/issues/27
- [x] tile entity support (partial): chiseled bookshelves, shelves, and sign text preserved across assembly. see https://github.com/def9a2a4/BlockShips/issues/23 — docs/wip/TODO-tileentities.md
- [x] lower default special-drowned spawn chance 5% → 2% so they don't overwhelm heavy-spawn biomes. see https://github.com/def9a2a4/BlockShips/issues/29

## earlier

- [x] when a player tries to right click on a prefab ship with a ship wheel, show an error message explaining the difference.

- [x] set `camera_distance` attribute on shulkers the player rides to modify third person camera distance when riding ships.
  - configurable values for prefab ships
  - custom ships: possibly add buttons in the menu, or a separate menu GUI, for adjusting this, since trying to determine it from the ship is hard
  - see https://minecraft.wiki/w/Attribute#camera_distance

- [x] prefab ship (medium) needs more seats
  - two on the sides
  - add an extra mast collider at the top of the mast, set that as a seat

- [x] ship info should show number of seats in ship info
  - add a "highlight seats" button which adds particles around seats (blocks or shulkers) when clicked. should work for both prefab and custom ships

- [x] use light blocks to fake like coming from light-emitting blocks on ships

- [x] Unsafe null dereference in DisplayShip.java getAttribute().getBaseValue() calls - added null checks
- [x] AttributeCompat.getMaxHealth() javadoc claimed "never null" but could return null - fixed docs
- [x] Reflection per packet in ShipSteeringListener - added version check + cached methods
- [x] TeleportCompat called 50+ times/tick with unnecessary passenger operations - optimized
- [x] Missing validity checks in ShipInstance.java passenger verification - added isValid() checks
- [x] info/help in ship wheel GUI
- [x] investigate ships not being breakable sometimes?
- [x] collisions on prefab ships too forceful
- [x] recognize waterlogged blocks (in particular kelp) as water when doing flotation calculations
- [x] hay bales should be allowed blocks
- [x] Orphaned carrier on spawn failure - if shulker spawn fails after carrier created, ArmorStand is leaked