# v0.0.19 -- prefab ship rendering bugfix


## Fixes

- **Prefab ships (Small Ship, Large Ship, Small Airship) drew about 2 blocks below their hitboxes** in
  0.0.18, so you collided with and stood on an invisible hull above the visible one. Their models were made
  for the old engine, which never corrected for the height a display rides above the ship's invisible
  armor stand. DefCoreLib does correct for it, so the models now carry that height themselves. Custom ships
  were never affected.

  **Prefab ships placed on 0.0.18 keep the low displays.** Each ship saves its display positions, so the fix
  applies to ships placed from now on, and to 0.0.17 ships in areas nobody has loaded since the upgrade.
  To fix an existing one, take everything out of its chests, then destroy it (bring its health to 0),
  ideally near the shore: it drops its kit, and placing the kit again gives a fixed ship.

- **A destroyed Small Airship's kit lost its balloon colour**, so placing it again gave the default balloon.
  The kit now keeps it.
