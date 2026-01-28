# BUGS

- wall banner rendering broken
- prefab ship (medium) needs more seats
  - two on the sides
  - add an extra mast collider at the top of the mast, set that as a seat

- ShipRegistry uses non-thread-safe HashMap but is accessed from multiple threads (chunk events, periodic saves). Use ConcurrentHashMap to prevent ConcurrentModificationException.
- (pre-1.21.9) Rotation logic bug - delta rotation math in ShipInstance.java:1110-1130 has inconsistencies, displays may rotate incorrectly
- (pre-1.21.9) Custom ship spawnYaw mismatch - initial spawn uses vehicle.getYaw() but recovery uses model.initialRotation.x, causing rotation snap after restart
- (pre-1.21.9) spawnYaw not persisted to save data - ships rotated before restart may snap when loaded
- player does not get moved along with a ship, does not inherit velocity properly
- ship to ship collisions not working, temporarily disabled
- fix heads on walls display and colliders
- multiple ships wheels is buggy?
- ladder duplication bug
- loading colliders for really large ships sometimes goes wrong


# FEATURES

- implement furnaces (normal furnaces) on ships
- add TileEntity serialization for campfires, chiseled bookshelves, decorated pots
- custom ship stats:
  - base acceleration/rotation speed depends on total mass.
  - "sails" (banners, wool blocks) increase acceleration/rotation speed
  - any blast furnaces connected via "copper network" to ships wheel will use fuel to increase acceleration/max speed/etc
  - stats (fuel, number of "engines") displayed in ship info

- allow setting extra colliders in a model. have this just be another list at the end, separate from blocks and items
  - this is useful for large balloons. the balloon might be a giant item display entity and we might want to have one or more large colliders for it

- set `camera_distance` attribute on shulkers the player rides to modify third person camera distance when riding ships.
  - configurable values for prefab ships
  - custom ships: possibly add buttons in the menu, or a separate menu GUI, for adjusting this, since trying to determine it from the ship is hard
  - see https://minecraft.wiki/w/Attribute#camera_distance


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


# RECENTLY FIXED

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