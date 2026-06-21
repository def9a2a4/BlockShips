# CI: chunk_persistence tests — root cause and fix (RESOLVED)

## Problem

The `chunk_persistence` and `chunk_persistence_airship` tests failed on 1.21.2+ CI,
reporting ships drifted 35-66 blocks after chunk unload/reload.

## Root Cause: stale mineflayer entity positions (test bug, not plugin bug)

The ship never drifts. The test was comparing **stale** pre-cycle shulker positions
against **fresh** post-cycle positions.

Shulkers are passengers of carrier ArmorStands. The MC server does not send position
update packets for passenger entities (the vanilla client positions them from the
vehicle). Mineflayer doesn't implement client-side passenger positioning, so
`bot.entities[id].position` for shulkers stays frozen at assembly-time coordinates.

After a chunk cycle, fresh entity spawn packets give the **true** position. The
apparent "drift" was the distance the ship traveled during the steering phase.

**CI evidence (run 27178642083, paper 1.21.4):**
```
Pre-cycle shulkers (stale):       Z ~ -1.7  (assembly position)
Bot dismount pos (server truth):  Z = -35.6  (moved 36.2 blocks during steer)
Post-cycle shulkers (fresh):      Z ~ -34.5 to -36.5  (true position from spawn packets)
```
Bot position matches post-cycle shulkers exactly. Pre-cycle data was stale.

## Why it "passed before"

The original test used `bot.entity.position` as the reference — this IS server truth
(plugin teleports the player to a safe position on dismount, and the test syncs with
`/tp @s ~ ~ ~`). Commit d3c190b switched the reference to `avgShulkerPos` to handle
the airship Y-offset, inadvertently using stale passenger positions.

## Fix

Use carrier positions instead of shulker positions: `getShipEntityPos(shulker)` reads
`shulker.vehicle?.position` (the carrier ArmorStand, a standalone entity with normal
position updates), falling back to `shulker.position` (correct post-reload when spawn
packets give fresh positions anyway).

- `test-bot/lib/helpers.js` — added `getShipEntityPos()` helper
- `test-bot/chunk-test.js` — `testPositionPersistenceBase()` uses carrier positions
  for pre-cycle reference, post-cycle comparison, and diagnostic logging

## Plugin defects found during investigation (separate, not the CI cause)

1. `recoverEntities()` starts the tick task immediately on partial recovery and
   overwrites the `task` field without cancelling any pre-existing task (duplicate
   tick task leak). Not harmful in practice but worth hardening.
2. `vehicle.setVelocity(displacement)` set while moving is never zeroed on stop,
   leaking into entity Motion NBT at chunk unload. Worth zeroing in
   `suspendForChunkUnload()`.
