# BUGS

- still buggy moment on recently parked ship, but grid align fixes it? our fine grid align didnt work
- player does not get moved along with a ship, does not inherit velocity properly
- ship to ship collisions not working, temporarily disabled

# FEATURES

- allow setting extra colliders in a model. have this just be another list at the end, separate from blocks and items
  - this is useful for large balloons. the balloon might be a giant item display entity and we might want to have one or more large colliders for it

- custom ship stats:
  - acceleration/rotation speed depends on total mass.
  - any blast furnaces connected via "copper pipe" to ship will use fuel to increase acceleration/max speed/etc


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

