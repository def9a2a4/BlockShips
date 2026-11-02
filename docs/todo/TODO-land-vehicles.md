---
status: implemented, awaiting in-game verification
issue: 10
---

# Land vehicles (issue #10)

A ship with **wheels on the ground** drives on land. Wheels are DefCoreLib blocks (`mech:wheel`,
`mech:large_wheel`, `mech:huge_wheel`). There is no separate steering item: the Ship's Wheel steers.
The vehicle steps up blocks like a horse and never tilts. Player-facing behaviour is in
[the flight model](../flight-model.md#land-vehicles).

This replaces an earlier design built around a second "Land Vehicle Wheel" steering item. That design
was dropped because a second head-block type with its own drop and recognition rules would have had to
survive corelib drops and legacy-skin matching.

## Pieces

| piece | where |
|---|---|
| Wheel blocks (octagon: 2 cubes + hub, 3 display entities), recipes, power, mass | defCoreLib `rotation-blocks.yml` (end of file), `rotation-config.yml`, `mass.yml`, `RotationBlocks.overlayPropulsionConsumers` |
| `Axis.GROUND`; wheel classification (axle across the hull, else ignored) | `ShipThrust.classifyFacing` |
| Ground wheels, `landBottomY`, support sample points | `ShipModel` constructor (derived, not persisted) |
| Ground support, stepping, routing ahead of flight, drag, sound, "too steep" | `ShipPhysics.computeSupport` / `columnSupport` / `handleLandPhysics` |
| Collision ignores blocks the vehicle is climbing onto | `ShipCollision.calculateTerrainCollisionForce` |
| Ground thrust only while grounded; car-like turning | `ShipStats.withThrust(..., grounded)` |
| Config | `custom-ships.land.*` in config.yml / `ShipConfig` |
| Detect and menu readouts | `ShipWheelManager.sendLandSummary`, `ShipWheelMenu` |
| Spin and fuel demand by use | `ship/ShipPropulsionUse` → `Mechanism.setBlockSpinScale/Speed`, `setBlockDemand` |
| Burn-on-load engines | defCoreLib `EngineBurn`, `RotationNetwork.loadFraction`, `RotationBlocks` engine tick, `MechanismRotationDriver.tickEngines` |
| Particles aboard mechanisms | defCoreLib `MechanismRegistry.tickMechanismParticles` |

## Tests

- Unit: `LandVehicleTest`, `ship/LandSupportTest` (BlockShips); `EngineBurnTest`,
  `DisplayAnimationSpinTest` (defCoreLib).
- Bot: `test-bot.js` `land_vehicle` (`node test-bot.js --only=land_vehicle`). A sail cart with four
  wheels on dry ground drives onto a 1-block plateau.

## Known limitations

- No pitch or roll; a slope is a staircase of steps.
- Support is sampled at the wheels, the footprint's corners, its edge midpoints and its centre. A narrow
  pillar between samples is not stepped onto, so collision handles it as a wall.
- Unpowered wheels don't visibly roll. An idle state has no animation for the spin override to drive.
- Visual wheel spin is capped at 40°/tick to avoid aliasing.
- Steam pistons still burn at a fixed rate; only engines and thrusters burn by load.
- Aboard a mechanism, WORK-mode load (only counting just after real work) is tracked for the
  millstone/press/sieve. Drills, placers and suction hoppers count whenever powered.
- Downgrading BlockShips drops the GROUND axis when a ship loads. Re-saving loses the wheels until the
  ship is reassembled.

## Still to do

- In-game pass: the bot test, plus manual checks of powered wheels (engine and shaft), large/huge wheels
  on fences and 2-block rises, landing gear on an airship, a boat with gunwale wheels, and fuel
  counters on a parked ship.
- A proper wheel head texture (the item currently uses `@chainwheel`).
- Balance pass on wheel thrust, power, step heights and `burn-multiplier`.
