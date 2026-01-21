# BUGS

- prefab ship (medium) needs more seats
- still buggy momentum on recently parked ship, but grid align fixes it? our fine grid align didnt work
- player does not get moved along with a ship, does not inherit velocity properly
- ship to ship collisions not working, temporarily disabled
- fix heads on walls display and colliders
- multiple ships wheels is buggy?
- ladder duplication bug
- loading colliders for really large ships sometimes goes wrong


- [x] investigate ships not being breakable sometimes?
- [x] collisions on prefab ships too forceful
- [x] recognize waterlogged blocks (in particular kelp) as water when doing flotation calculations
- [x] hay bales should be allowed blocks

# FEATURES

- info/help in ship wheel GUI
- implement furnaces (normal furnaces) on ships
- add TileEntity serialization for campfires, chiseled bookshelves, decorated pots
- custom ship stats:
  - base acceleration/rotation speed depends on total mass.
  - any blast furnaces connected via "copper network" to ships wheel will use fuel to increase acceleration/max speed/etc
  - stats (fuel, number of "engines") displayed in ship info

- allow setting extra colliders in a model. have this just be another list at the end, separate from blocks and items
  - this is useful for large balloons. the balloon might be a giant item display entity and we might want to have one or more large colliders for it



## MAYBE

- maybe: shulker taking damage destroys that block only? for custom block ships
- allow leading ships. not sure about this one. maybe only prefab ships?

# OPTIMIZATIONS

- make sure all "is ship moving" related checks happen in the same function
- make sure all right click checks handled by the same function
- despawn shulkers if no players nearby??
  - shulkers only need to be present when there is a player nearby. max ship size + configurable buffer?
- smarter conversion to minimize shulkers:
  - look for solid 2x2x2 or 3x3x3 blocks to convert to single shulker with adjusted size
  - dont spawn shulkers for "interior" blocks (those not touching the outisde)

## CODE QUALITY

- ShipInstance has lots of duplication of code for banner/player head rotation/position etc etc. refactor this mess.


# RECENTLY FIXED