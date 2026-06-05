# SOON

- use `PlayerInput` event on newer versions where available, fall back to ProtocolLib if not. clear error in console and chat if neither is present
- Towny compat, see https://github.com/def9a2a4/BlockShips/issues/17
- destruction -- allow ships to just be fully destroyed, configurable. see https://github.com/def9a2a4/BlockShips/issues/27


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
- [ ] (pre-1.21.9) Rotation logic bug - delta rotation math in ShipInstance.java:1110-1130 has inconsistencies, displays may rotate incorrectly
- [ ] (pre-1.21.9) Custom ship spawnYaw mismatch - initial spawn uses vehicle.getYaw() but recovery uses model.initialRotation.x, causing rotation snap after restart
- [ ] (pre-1.21.9) spawnYaw not persisted to save data - ships rotated before restart may snap when loaded
- [ ] player does not get moved along with a ship, does not inherit velocity properly
- [ ] ship to ship collisions not working, temporarily disabled
- [ ] fix heads on walls display and colliders
- [ ] multiple ships wheels is buggy?

## UNCLEAR

- [ ] loading colliders for really large ships sometimes goes wrong?




# REFACTORING

- clean up code and use a unified "mechanism" class

# FEATURES

## HARDER

- [ ] ship-to-ship collisions

- [ ] custom ship stats:
  - base acceleration/rotation speed depends on total mass.
  - "sails" (banners, wool blocks) increase acceleration/rotation speed
  - any blast furnaces connected via "copper network" to ships wheel will use fuel to increase acceleration/max speed/etc
  - stats (fuel, number of "engines") displayed in ship info

- [ ] implement furnaces (normal furnaces) on ships
- [ ] add TileEntity serialization for campfires, chiseled bookshelves, decorated pots, signs, etc?

- [ ] allow setting extra colliders in a model. have this just be another list at the end, separate from blocks and items
  - this is useful for large balloons. the balloon might be a giant item display entity and we might want to have one or more large colliders for it


## MAYBE

- maybe: shulker taking damage destroys that block only? for custom block ships
- allow leading ships. not sure about this one. maybe only prefab ships?

# OPTIMIZATIONS

- make sure all "is ship moving" related checks happen in the same function
- make sure all right click checks handled by the same function
- smarter conversion to minimize shulkers:
  - dont spawn shulkers for "interior" blocks (those not touching the outisde)

## CODE QUALITY

- ShipInstance has lots of duplication of code for banner/player head rotation/position etc etc. refactor this mess.
- ServerVersion.java overflow at Minecraft 2.x - version number calculation assumes minor/patch < 100
- Silent health regen failures after first tick in ShipInstance.java - only logs once, subsequent failures silent
- Player disconnect handler in DisplayShip.java missing logging - hard to debug seat issues
- SteerPacketCompat.logged field should be volatile for thread safety


# RECENTLY DONE

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