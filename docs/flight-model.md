# Flight and propulsion model

How a ship's speed, turning and lift are derived, and what a builder has to do to get each one. This
describes shipped behaviour — if you change the physics, change this file with it.

The numbers below are the shipped defaults. Everything is configurable in `config.yml` under
`custom-ships.stats`, and all the per-block thrust values under `custom-ships.thrust`.

## The three ratios

Every stat is a **ratio of power to weight**, clamped to 0..1, and each is fed through
`ShipConfig.computeStat` to produce an actual speed or acceleration.

```
forwardRatio = min(sailRatio, sailCap) + axialThrust / mass
turnRatio    = (baseTurn + sailPower × sailTurnFactor × speedFrac + turningThrust) / mass
liftRatio    = verticalThrust / totalWeight        (see "Lift" below)
```

- `mass` is the sum of **positive** block weights.
- `totalWeight` is the **signed** sum, so glowstone (−5) subtracts and oak (+2) adds. This is what
  makes buoyancy and thrust the same currency.
- `speedFrac` is current speed over top speed, so **sails only help you turn once you are moving**.
  A parked ship's menu shows the at-rest turn figure, which is the honest one to compare a reaction
  wheel against.

## Sails

| source | points each |
|---|---|
| wool | 3 |
| banner | 7 |
| large banner | 20 |
| huge banner | 50 |

Large and huge banners come from the BetterBanners plugin. They are **display entities attached to a
host block**, not blocks in their own right, so they are found by an entity query rather than by
walking the hull.

Sail power is **capped** at `sail-cap-ratio` (0.8). A fully rigged ship therefore reads 80% and not
100%: the last stretch is deliberately reserved for thrust, so that propulsion has something to do
that canvas cannot. Sails are cheap, plateau early, and need no power.

## Propulsion

| block | thrust | cost |
|---|---|---|
| fan | 10 | 2 rotation power |
| propeller | 32 | 5 |
| large propeller | 100 | 10 |
| huge propeller | 250 | 20 |
| thruster | 50 | fuel, no network |
| reaction wheel | 10 (turning only) | 1 |

**Mount direction decides the axis.** A block's facing is rotated into ship-local space and bucketed:

- fore/aft → **speed**
- sideways → **turning**
- up/down → **lift**
- a reaction wheel is turn-only regardless of how it sits

Propellers and fans act *away* from their mount, like a fan blows outward, so a floor-mounted one
pushes the ship **up**.

Magnitudes are unsigned. Two propellers pointing at each other **add** rather than cancel, there is no
reverse thrust, and moment arm is ignored — a bow thruster and a stern thruster turn the ship the same
way. This is a deliberate simplification, not an oversight.

Rotation power is **all-or-nothing**: a network that cannot meet its total demand runs nothing at all.
Split large banks across separate networks rather than building one that browns out. When power is
lost, thrust spools down over about two seconds rather than cutting instantly.

## Lift

`liftRatio` is measured against **signed** weight, so buoyancy and thrust are interchangeable.

- **1.0 means the ship exactly holds its own weight.** Above 1.0 is what climbs.
- A hull at or below zero weight already holds itself up, so it starts at 1.0 and every point of
  vertical thrust is surplus on top of that. A lighter-than-air ship is airborne with no thrust at
  all, and vertical thrusters still make it climb *faster*.
- A heavy hull needs `totalWeight` points of vertical thrust just to hover.

### Falling

Below 1.0 you **cannot climb at all**, and you sink — faster the more lift you are short by:

| lift | free sink | holding Space |
|---|---|---|
| 0.99 | 0.4 b/s | 0.14 b/s |
| 0.90 | 2.0 b/s | 0.83 b/s |
| 0.75 | 3.8 b/s | 1.9 b/s |
| 0.50 | 6.2 b/s | 4.2 b/s |
| 0.25 | 8.2 b/s | 6.9 b/s |
| 0.00 | 10.0 b/s | 10.0 b/s |

Holding Space when you cannot climb still points what thrust you have downward and slows the fall, on
a curve — at zero lift it does nothing, because there is nothing to point. Sprint always descends.

Above 1.0, only the **surplus** climbs: lift 1.05 climbs slowly, lift 1.25 and up gets the full
vertical speed. The ceiling scales, not the acceleration, so a marginal ship feels responsive rather
than mushy.

A ship that runs out of lift over water ditches and floats rather than sinking through the sea floor.

## Vertical speed

Separate from lift, and it applies to every flying ship:

```
verticalRatio = clamp01( max(0, -density) × verticalDensityScale     buoyancy
                       + sailPower × sailVerticalFactor / mass       sails
                       + verticalThrust / mass )                     fans/props/thrusters
```

**Lift decides whether you may climb; this decides how fast.** They compose — a helicopter sitting at
exactly lift 1.0 has a high ceiling and zero permission to use it. Sails and vertical thrust both raise
it, including on a lighter-than-air hull, so a glowstone airship with fans climbs faster than the same
airship without them.

## The craft this is meant to support

1. **A plain oak hull.** Sails on water, slowly.
2. **Oak plus wool or banner sails.** Faster, and turns better once moving.
3. **A glowstone airship with sails.** The above, plus faster climb and descent, and it keeps manual
   Space/Sprint control.
4. **Any of those with thrusters, fans, propellers or gyros.** A boost on whichever axis each block is
   mounted to.
5. **A "helicopter":** no buoyant blocks at all, flying on a floor-mounted propeller alone. Takes off,
   and stops climbing the moment power drops.

## Where this lives in the code

- `ShipStats` — the three ratios, one calculator for every readout.
- `ShipThrust` — classifies blocks by mount direction; `totalsFor` is live, `scanWorld` is potential.
- `ShipPhysics.applyAirshipVerticalPhysics` — the climb/sink model.
- `ShipConfig.computeStat` — the two-segment curve from a ratio to a speed.
