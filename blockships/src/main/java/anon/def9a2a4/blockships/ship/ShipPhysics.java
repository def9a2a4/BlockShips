package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShipConfig;
import anon.def9a2a4.blockships.ShipTags;
import anon.def9a2a4.blockships.util.TeleportCompat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles movement and physics logic for a ship.
 * Owns velocity state and applies movement, drag, buoyancy, and rotation.
 */
public class ShipPhysics {
    private final ShipInstance ship;

    // Grid snap resolution (4.0 = quarter block, i.e. 1/4 = 0.25)
    private static final double FINE_GRID_RESOLUTION = 4.0;

    // Velocity state
    public float currentSpeed = 0.0f;
    public float currentYVelocity = 0.0f;
    public float currentRotationVelocity = 0.0f;
    public Vector3f collisionForce = new Vector3f(0, 0, 0);

    // Internal yaw tracking - vehicle yaw is frozen at spawnYaw to avoid the entity
    // tracker's byte-precision (~1.4 deg) rotation packets conflicting with our float-precision
    // position sync packets, which causes periodic jitter every 3 ticks.
    // All rotation is applied via display entity transformations + interpolation instead.
    public float currentYaw;

    // Track vertical movement state for carrier refresh on stop
    private boolean wasVerticallyMoving = false;

    // Effective stats (computed from power-to-mass ratio for custom ships, or config defaults for prefab)
    private float effectiveMaxSpeed;
    private float effectiveAcceleration;
    private float effectiveRotationSpeed;
    private float effectiveRotationAcceleration;
    private float effectiveRotationDeceleration;
    private float effectiveMaxVerticalSpeed;
    private float effectiveLiftAcceleration;
    private float effectiveDescendAcceleration;
    private boolean statsComputed = false;

    // ── Live propulsion ──────────────────────────────────────────────────────
    // Sails and mass are fixed for a voyage; thrust is not. A propeller loses power when an engine
    // runs dry, and a thruster stops when its fuel does — so the thrust half of the stats is
    // recomputed rather than cached at assembly.

    /** Thrust as of the last recompute, before spool-down. */
    private anon.def9a2a4.blockships.ShipThrust.Totals targetThrust =
        anon.def9a2a4.blockships.ShipThrust.Totals.NONE;
    /** Thrust actually applied, ramping toward the target — propellers have inertia. */
    private float spooledAxial, spooledTurning, spooledVertical;
    /** Set by defCoreLib's re-solve event, or by the periodic poll. */
    private boolean thrustDirty = true;
    private int thrustPollCounter = 0;
    /** Last computed lift ratio, for the flight model and the readouts. */
    private float lastLiftRatio = 0f;

    /** Force a thrust recompute on the next stats pass (defCoreLib re-solved this ship's network). */
    public void markThrustDirty() {
        thrustDirty = true;
        statsComputed = false;
    }

    /**
     * Per-tick propulsion upkeep, called from the ship tick before {@link #update()}.
     *
     * <p>Two reasons this exists rather than relying on the re-solve event alone. The event only
     * fires when a SOURCE flips, which misses nothing today but is not a contract worth betting the
     * flight model on; and spool-down has to advance every tick regardless, or thrust would step
     * instead of ramp.
     */
    private void tickPropulsion() {
        if (ship.model == null) return;
        if (!ship.model.thrustBlocks.isEmpty()) {
            // Cheap backstop poll; the event does the timely work.
            if (++thrustPollCounter >= 20) {
                thrustPollCounter = 0;
                thrustDirty = true;
            }
        }
        // Recompute every tick, thrust or not. This used to return early for a ship with no thrust
        // blocks, which quietly froze the SAIL stats too: turnRatio scales with current speed, and the
        // only other place statsComputed is cleared is a defCoreLib re-solve. So a pure sailing ship
        // computed its stats once, at assembly, with currentSpeed == 0 — and a hull carrying two
        // hundred points of canvas turned exactly like a bare one at every speed, for the whole voyage.
        statsComputed = false;
    }

    /** How much lift the ship is currently producing, relative to its weight. 1.0 = neutral. */
    public float liftRatio() {
        if (!statsComputed) computeEffectiveStats();
        return lastLiftRatio;
    }

    /** Thrust blocks currently running, and how many there are — for the driver readout. */
    public anon.def9a2a4.blockships.ShipThrust.Totals thrustTotals() {
        return targetThrust;
    }

    /**
     * Thrust totals with spool-down applied.
     *
     * <p>Rotation power is all-or-nothing per network, so one engine running dry cuts every propeller
     * on it in the same tick. Ramping over {@code thrust-spool-ticks} turns that from a ship falling
     * out of the sky instantly into a couple of seconds of decaying lift — physically justified
     * (propellers do not stop dead) and enough time to react.
     */
    private anon.def9a2a4.blockships.ShipThrust.Totals liveThrust() {
        if (thrustDirty) {
            targetThrust = anon.def9a2a4.blockships.ShipThrust.totalsFor(
                (anon.def9a2a4.blockships.BlockShipsPlugin) ship.plugin, ship.mechanism, ship.model);
            thrustDirty = false;
        }
        int spoolTicks = Math.max(1, ship.config.thrustSpoolTicks);
        float step = 1.0f / spoolTicks;
        spooledAxial = approach(spooledAxial, targetThrust.axial(), step);
        spooledTurning = approach(spooledTurning, targetThrust.turning(), step);
        spooledVertical = approach(spooledVertical, targetThrust.vertical(), step);
        // The spooled turning total is carried in `perpendicular` with `turnOnly` zero: the two are
        // only ever consumed through turning(), which sums them, and they spool as one quantity.
        return new anon.def9a2a4.blockships.ShipThrust.Totals(
            Math.round(spooledAxial), Math.round(spooledTurning), Math.round(spooledVertical), 0,
            targetThrust.powered(), targetThrust.total());
    }

    /**
     * Move {@code current} toward {@code target} by a fraction of the full range per call.
     *
     * <p>The range is the LARGER of where we are and where we are going, not just the target. Scaling
     * by the target alone makes spool-down nearly free-running: cutting power sets target 0, so the
     * per-call step collapses to {@code 1 * step} — at the default 40 spool ticks that is 0.025/tick,
     * and 30 points of vertical thrust take ~1200 ticks (a minute) to decay. Losing every propeller
     * on a network then had almost no effect on how fast the ship came down, which is the opposite of
     * what the spool exists to model. With this, a ramp takes {@code thrustSpoolTicks} in EITHER
     * direction, which is what the config key claims.
     */
    private static float approach(float current, float target, float step) {
        float delta = target - current;
        float maxStep = Math.max(1f, Math.max(Math.abs(current), Math.abs(target))) * step;
        if (Math.abs(delta) <= maxStep) return target;
        return current + Math.signum(delta) * maxStep;
    }

    /**
     * The ship's actual top speed after the power-to-mass ratio is applied.
     *
     * <p>Use this, not {@code config.maxSpeed}, for anything player-facing: the raw config value is
     * the ratio-1.0 reference, so a lightly-rigged ship measured against it reads as far slower than
     * it actually is relative to what it can do.
     */
    public float effectiveMaxSpeed() {
        if (!statsComputed) computeEffectiveStats();
        return effectiveMaxSpeed;
    }

    // Sound cooldown (ticks until next sound can play)
    private int soundCooldown = 0;

    // Reusable Locations for physics calculations - reduces GC pressure
    private Location workLocation = null;
    private Location workLocation2 = null;  // Second work location for buoyancy (hull check vs water scan)
    // Third work location, for the falling ground sweep. Kept separate because the sweep runs from
    // inside handleBuoyancy and from the flight path, both of which sit downstream of hullInWater
    // (workLocation) and findWaterSurfaceY (workLocation2) — sharing one would put two probes on the
    // same object in a single tick, which is the sort of thing that works until someone reorders a call.
    private Location workLocation3 = null;

    public ShipPhysics(ShipInstance ship) {
        this.ship = ship;
        // Stats are computed by caller after construction.
        // For custom ships, recomputeStats() is called again after wheelData is linked.
    }

    /**
     * Public wrapper to recompute effective stats after wheelData is linked.
     * Must be called after ship.wheelData is assigned (assembly or recovery).
     */
    public void recomputeStats() {
        computeEffectiveStats();
    }

    /**
     * Computes effective stats from the ship's power-to-mass ratio.
     * For custom ships, uses linear interpolation between floor/default/cap.
     * For prefab ships, uses config values directly.
     */
    private void computeEffectiveStats() {
        ShipConfig config = ship.config;

        if (!"custom".equals(ship.shipType) || !config.statsEnabled) {
            // Prefab ships, or stats system disabled: use config values directly, no ratio system
            effectiveMaxSpeed = config.maxSpeed;
            effectiveAcceleration = config.acceleration;
            effectiveRotationSpeed = config.rotationSpeed;
            effectiveRotationAcceleration = config.rotationAcceleration;
            effectiveRotationDeceleration = config.rotationDeceleration;
            effectiveMaxVerticalSpeed = config.maxVerticalSpeed;
            effectiveLiftAcceleration = config.liftAcceleration;
            effectiveDescendAcceleration = config.descendAcceleration;
            // lastLiftRatio is deliberately left at 0 here, and nothing on this branch reads it:
            // isThrustLifted() is false whenever stats are off, and a prefab airship is carried by
            // ship.isAirship, which short-circuits both the flight dispatch and the displacementLift
            // branch inside applyAirshipVerticalPhysics. An earlier version set it to 1.0f as a
            // "supported by fiat" sentinel, which backfired badly: 1.0 is the exact value meaning ZERO
            // SURPLUS everywhere downstream, so a stats-off ship with a vertical propeller took the
            // flight path (lift >= 1) and then got climbCap 0 AND sinkFrac 0 — it could not climb, could
            // not fall, and could not float. A one-way elevator.
            statsComputed = true;
            return;
        }

        // Custom ships: compute ratio from sail power and mass. One shared calculator (ShipStats) so
        // physics and every readout agree — they used to each roll their own copy of this.
        // speedFrac comes from the PREVIOUS tick's top speed: sails aid turning in proportion to how
        // fast the ship is already moving, and effectiveMaxSpeed is only assigned below.
        float prevTop = Math.max(0.0001f, effectiveMaxSpeed > 0 ? effectiveMaxSpeed : config.maxSpeed);
        float speedFrac = Math.abs(currentSpeed) / prevTop;
        // Call liveThrust() EXACTLY ONCE per computeEffectiveStats: it is not a getter, it advances the
        // spool ramp by one step each time. A second call here would spool at double rate.
        anon.def9a2a4.blockships.ShipThrust.Totals live = liveThrust();
        anon.def9a2a4.blockships.ShipStats stats =
            anon.def9a2a4.blockships.ShipStats.of(config, ship.model, live, speedFrac);
        float ratio = stats.forwardRatio;
        float turnRatio = stats.turnRatio;
        this.lastLiftRatio = stats.liftRatio;

        // Compute horizontal stats
        effectiveMaxSpeed = config.computeStat(ratio, config.maxSpeed,
            config.floorMaxSpeed, config.capMaxSpeed);
        effectiveAcceleration = config.computeStat(ratio, config.acceleration,
            config.floorAcceleration, config.capAcceleration);
        // Rotation runs off turnRatio, which in ratio3 mode is a different number from forward.
        effectiveRotationSpeed = config.computeStat(turnRatio, config.rotationSpeed,
            config.floorRotationSpeed, config.capRotationSpeed);
        effectiveRotationAcceleration = config.computeStat(turnRatio, config.rotationAcceleration,
            config.floorRotationAcceleration, config.capRotationAcceleration);
        effectiveRotationDeceleration = config.computeStat(turnRatio, config.rotationDeceleration,
            config.floorRotationDeceleration, config.capRotationDeceleration);

        // Vertical stats. No airship branch here any more — buoyant and heavier-than-air custom hulls
        // share one formula. (Prefabs never reach this line: they take the early-out above and keep the
        // raw config values, which is what makes them bit-identical to before the rework.)
        //
        // This answers "how fast does it move up and down", which is a different question from
        // stats.liftRatio's "can it hold itself up at all". Lift decides whether you fly; this decides
        // how quickly, and the two compose — a helicopter at exactly lift 1.0 has a high ceiling and no
        // surplus with which to use it, while a balloon gains speed from bolting on fans.
        //
        // Three contributions, all power-over-weight like the other two ratios:
        //   buoyancy — max(0, -density), NOT |density|. The absolute value used to hand a HEAVY hull a
        //              vertical bonus for being heavy; only lighter-than-air should count. Identical
        //              for real airships, whose density is negative already.
        //   sails    — a rigged airship rises faster than a bare one. Never lets a heavy hull fly:
        //              that is lift's job, and lift ignores sails entirely.
        //   thrust   — fans, propellers and thrusters pointing vertically, so adding them speeds up a
        //              lighter-than-air ship too, not just a heavier-than-air one.
        float density = ship.model.getDensity();
        int vMass = Math.max(1, ship.model.mass);
        float verticalRatio = Math.min(1.0f,
            Math.max(0f, -density) * config.verticalDensityScale
            + ship.model.sailPower * config.sailVerticalFactor / vMass
            + (float) live.vertical() / vMass);

        effectiveMaxVerticalSpeed = config.computeStat(verticalRatio, config.maxVerticalSpeed,
            config.floorMaxVerticalSpeed, config.capMaxVerticalSpeed);
        effectiveLiftAcceleration = config.computeStat(verticalRatio, config.liftAcceleration,
            config.floorVerticalAcceleration, config.capVerticalAcceleration);
        effectiveDescendAcceleration = config.computeStat(verticalRatio, config.descendAcceleration,
            config.floorVerticalAcceleration, config.capVerticalAcceleration);

        statsComputed = true;
    }

    /**
     * Copies source into target (lazy init: clones if target is null).
     * Reuses the same Location object to avoid allocations.
     */
    private static Location reuseLocationInto(Location target, Location source) {
        if (target == null) {
            return source.clone();
        }
        target.setWorld(source.getWorld());
        target.setX(source.getX());
        target.setY(source.getY());
        target.setZ(source.getZ());
        target.setYaw(source.getYaw());
        target.setPitch(source.getPitch());
        return target;
    }

    private Location reuseLocation(Location source) {
        workLocation = reuseLocationInto(workLocation, source);
        return workLocation;
    }

    private Location reuseLocation2(Location source) {
        workLocation2 = reuseLocationInto(workLocation2, source);
        return workLocation2;
    }

    private Location reuseLocation3(Location source) {
        workLocation3 = reuseLocationInto(workLocation3, source);
        return workLocation3;
    }

    /**
     * Trim a downward step so the hull lands on the first solid block it would pass through.
     *
     * <p>The old check sampled a single point 0.1 blocks under the hull <em>before</em> the move, which
     * only works while the step is smaller than the probe. It never was, quite: at the old 0.3 cap a
     * ship already buried itself up to 0.2 blocks on every landing, and anything faster passes clean
     * through a one-block floor — the probe sees air above it on one tick and air below it on the next.
     * Since the whole point of the flight rework is to let ships fall faster, sweeping is a prerequisite
     * rather than a refinement.
     *
     * <p>Returns the largest (least negative) step that keeps the hull above the block it would hit, or
     * {@code vy} unchanged when the path is clear. Positive velocities pass through untouched.
     *
     * <p>Known limitation, unchanged from the check it replaces: this probes the wheel's column only, so
     * a hull with an overhanging keel can still clip terrain outside that column. Making it
     * footprint-aware means sampling the model's bottom blocks and belongs with the collision work.
     */
    private float clampFallToGround(Location vehicleLoc, float vy) {
        if (vy >= 0f) return vy;
        double hullY = vehicleLoc.getY() + ship.model.minY;
        int from = (int) Math.floor(hullY - 0.001);   // the block the hull is standing in/on right now
        int to = (int) Math.floor(hullY + vy);        // the block it would end up in
        Location probe = reuseLocation3(vehicleLoc);
        for (int y = from; y >= to; y--) {
            probe.setY(y);
            if (!probe.getBlock().getType().isSolid()) continue;
            // Rest exactly on this block's top face rather than wherever the step happened to end.
            float allowed = (float) ((y + 1) - hullY);
            // A block whose top is ABOVE the hull is not a floor under it — the hull is inside terrain.
            // Keep looking rather than treating it as ground: clamping to 0 here pinned such a ship at
            // its altitude permanently, unable to descend by any input, after it flew into a hillside
            // or was recovered inside a block. Let it fall to a real surface; ShipCollision handles the
            // horizontal extraction.
            if (allowed > 0f) continue;
            // Snap to rest rather than returning a hair's-breadth negative. The float rounding of
            // (y+1)-hullY, and the 0.001 tolerance above, can both leave a residue of ~1e-9 to -0.001 —
            // too small to move the ship (update() ignores steps under 0.001) but enough to fail the
            // settle test, which pins wasVerticallyMoving true for the rest of the voyage and stops
            // refreshCarrierTracking ever firing on landing.
            return allowed > -0.001f ? 0f : allowed;
        }
        return vy;
    }

    /**
     * Main physics update. Applies acceleration, drag, buoyancy, and movement.
     * Called from ShipInstance.tick().
     */
    public void update() {
        if (!ship.vehicle.isValid() || ship.vehicle.isDead()) return;

        tickPropulsion();

        Location vehicleLoc = ship.vehicle.getLocation();
        ShipConfig config = ship.config;

        // Only a real driver produces thrust. Without this, an input flag left stuck true after a
        // seat loss that skipped VehicleExitEvent (death while seated, forced cross-world teleport)
        // would drive the ship forever unmanned.
        boolean throttling = ship.hasDriver && (ship.isForwardPressed || ship.isBackwardPressed);

        // Apply acceleration/deceleration based on input state
        if (throttling && ship.isForwardPressed) {
            currentSpeed = Math.min(currentSpeed + effectiveAcceleration, effectiveMaxSpeed);
        } else if (throttling && ship.isBackwardPressed) {
            if (currentSpeed > 0) {
                currentSpeed = Math.max(currentSpeed - config.activeDeceleration, 0.0f);
            } else {
                currentSpeed = Math.max(currentSpeed - effectiveAcceleration, -effectiveMaxSpeed);
            }
        }

        // Apply drag when not actively throttling (a driverless ship always drags -> coasts to a stop)
        if (!throttling) {
            // Two no-driver drags (unmanned = a player is nearby, idle = nobody is watching) used to live
            // here. The distinction was already dead: hasPlayersNearby was only ever written by
            // updateCollisionPositions, whose sole call chain runs at driver dismount — so the flag was one
            // stale sample taken when the driver stood up, and the whole coast-down after that used it.
            // Collapsed to idle-drag's value, the nobody-watching one.
            float dragMultiplier = ship.hasDriver ? config.mountedDrag : config.noDriverDrag;

            // Apply extra drag in water (reuse work location)
            if (isWaterOrWaterlogged(reuseLocation(vehicleLoc).subtract(0, 0.5, 0).getBlock())) {
                dragMultiplier *= 0.98f;
            }

            currentSpeed *= dragMultiplier;
        }

        // Stop if speed is very small (only when not actively accelerating)
        if (!throttling && Math.abs(currentSpeed) < config.minMovementThreshold) {
            currentSpeed = 0.0f;
        }

        // Calculate forward direction vector from internal yaw
        float yawRad = (float) Math.toRadians(-currentYaw);
        double forwardX = Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);

        // Vertical physics, three ways.
        //
        // Water is tested FIRST for anything that is not a balloon, and that ordering is the whole
        // guard: applyAirshipVerticalPhysics never looks at water, so a hull that reaches it while
        // floating would sink straight through the seabed. It also means a powered-down flier that
        // comes down over the sea ditches and floats instead of continuing to the ocean floor — but a
        // ship still making full lift flies on over the water rather than being captured by it.
        //
        // The flight test is "is this hull built to fly" (isThrustLifted), not "is it producing lift
        // right now". Keying it on live lift meant a single fan lying flat on a heavy deck — lift
        // 0.005, but greater than zero — pulled an ordinary boat off the buoyancy path the moment that
        // fan got power, and dropped it back when the power died.
        boolean flies = ship.isAirship || (isThrustLifted() && (liftRatio() >= 1f || !hullInWater(vehicleLoc)));
        if (flies) {
            applyAirshipVerticalPhysics(vehicleLoc);
        } else {
            handleBuoyancy(vehicleLoc);
        }

        // Move the vehicle
        boolean hasHorizontalMovement = Math.abs(currentSpeed) > 0.001;
        boolean hasVerticalMovement = Math.abs(currentYVelocity) > 0.001f;

        if (hasHorizontalMovement || hasVerticalMovement) {
            Location newLoc = reuseLocation(vehicleLoc);
            if (hasHorizontalMovement) {
                newLoc.add(forwardX * currentSpeed, 0, forwardZ * currentSpeed);
            }
            if (hasVerticalMovement) {
                newLoc.add(0, currentYVelocity, 0);
            }
            TeleportCompat.teleport(ship.vehicle, newLoc);

            // Vehicle velocity is set in ShipInstance.tick() after collision response,
            // using actual displacement to match carrier velocity computation.
        }

        // Update rotation based on input state. Gate only the active-turn arms on hasDriver (not
        // `throttling`) so a driver can still steer with A/D alone; the decay `else` stays always-on
        // so a driverless ship's spin winds down instead of turning forever.
        if (ship.hasDriver && ship.isLeftPressed) {
            currentRotationVelocity = Math.max(
                currentRotationVelocity - effectiveRotationAcceleration,
                -effectiveRotationSpeed
            );
        } else if (ship.hasDriver && ship.isRightPressed) {
            currentRotationVelocity = Math.min(
                currentRotationVelocity + effectiveRotationAcceleration,
                effectiveRotationSpeed
            );
        } else {
            // No input - apply momentum decay
            if (currentRotationVelocity > 0) {
                currentRotationVelocity = Math.max(
                    currentRotationVelocity - effectiveRotationDeceleration,
                    0.0f
                );
            } else if (currentRotationVelocity < 0) {
                currentRotationVelocity = Math.min(
                    currentRotationVelocity + effectiveRotationDeceleration,
                    0.0f
                );
            }
        }

        // Apply rotation to internal yaw (vehicle yaw stays frozen at spawnYaw;
        // visual rotation is handled by display entity transformations)
        if (Math.abs(currentRotationVelocity) > 0.01f) {
            currentYaw += currentRotationVelocity;
            if (currentYaw >= 360f) currentYaw -= 360f;
            else if (currentYaw < 0f) currentYaw += 360f;
        }

        // Play movement sounds
        if (soundCooldown > 0) {
            soundCooldown--;
        }

        float totalSpeed = Math.abs(currentSpeed);
        if (ship.isAirship) {
            totalSpeed = Math.max(totalSpeed, Math.abs(currentYVelocity));
        }

        float minSpeed = ship.isAirship ? config.airshipSoundMinSpeed : config.soundMinSpeed;
        if (totalSpeed >= minSpeed && soundCooldown == 0) {
            Location loc = ship.vehicle.getLocation();
            Sound sound = ship.isAirship ? Sound.ITEM_ELYTRA_FLYING : Sound.ENTITY_BOAT_PADDLE_WATER;
            float baseVolume = ship.isAirship ? config.airshipSoundVolume : config.soundVolume;
            float movementVolume = (float) ship.plugin.getConfig().getDouble("sounds.movement-volume", 0.5);
            loc.getWorld().playSound(loc, sound, baseVolume * movementVolume, config.soundPitch);
            soundCooldown = ship.isAirship ? config.airshipSoundIntervalTicks : config.soundIntervalTicks;
        }
    }

    /**
     * Whether the hull's underside is in water — at its lowest point, not at the wheel, so a ship with
     * a deep keel is judged on its keel.
     *
     * <p>Extracted so the dispatch in {@link #update()} and {@link #handleBuoyancy} share one
     * definition. The dispatch has to ask the question before choosing a branch, because the flight
     * path never looks at water and would sink a floating hull straight through the seabed.
     */
    private boolean hullInWater(Location vehicleLoc) {
        double hullY = vehicleLoc.getY() + ship.model.minY;
        Location probe = reuseLocation(vehicleLoc);
        probe.setY(hullY);
        if (isWaterOrWaterlogged(probe.getBlock())) return true;
        probe.setY(hullY - 1);
        return isWaterOrWaterlogged(probe.getBlock());
    }

    /**
     * Handle buoyancy physics for water-based ships.
     */
    private void handleBuoyancy(Location vehicleLoc) {
        ShipConfig config = ship.config;

        double hullCheckY = vehicleLoc.getY() + ship.model.minY;

        if (hullInWater(vehicleLoc)) {
            double waterSurfaceY = findWaterSurfaceY(vehicleLoc, hullCheckY);
            double targetY = waterSurfaceY + calculateFloatOffset();
            double currentY = vehicleLoc.getY();
            double yDifference = targetY - currentY;

            // Proportional approach with damping
            if (Math.abs(yDifference) < 0.1) {
                currentYVelocity = 0.0f;
                // Close enough - don't move or teleport. Prevents carrier jitter
                // for players standing on deck after dismount.
            } else {
                float targetVelocity = (float) (yDifference * config.buoyancyStrength);
                currentYVelocity = currentYVelocity * (1.0f - config.buoyancyDamping) + targetVelocity * config.buoyancyDamping;
            }
        } else {
            // Out of water and unsupported: fall. Terminal velocity and the swept landing are shared
            // with the flight path, so a ship set down by either route falls at one rate and stops in
            // the same place — which is what GRAVITY_PER_TICK's comment has always claimed. Before
            // this, gravity here was an unclamped `-= 0.08f` that reached several blocks per tick from
            // altitude and simply teleported the hull through the ground.
            currentYVelocity = Math.max(currentYVelocity - GRAVITY_PER_TICK, -config.maxSinkSpeed);
            currentYVelocity = clampFallToGround(vehicleLoc, currentYVelocity);
        }
    }

    /**
     * Gets the neutral buoyancy Y position for water ships.
     * @return The target Y position if in water, null if not in water
     */
    public Double getNeutralBuoyancyY() {
        Location vehicleLoc = ship.vehicle.getLocation();
        ShipConfig config = ship.config;

        // Check water at hull
        double hullCheckY = vehicleLoc.getY() + ship.model.minY;
        Location hullCheckLoc = reuseLocation(vehicleLoc);
        hullCheckLoc.setY(hullCheckY);

        Location belowHullLoc = hullCheckLoc.clone();
        belowHullLoc.subtract(0, 1, 0);
        boolean inWater = isWaterOrWaterlogged(hullCheckLoc.getBlock())
            || isWaterOrWaterlogged(belowHullLoc.getBlock());

        if (!inWater) {
            return null;
        }

        return findWaterSurfaceY(vehicleLoc, hullCheckY) + calculateFloatOffset();
    }

    /**
     * Scans downward to find the water surface Y level.
     * Uses workLocation2 for the scan, so workLocation remains available.
     */
    private double findWaterSurfaceY(Location vehicleLoc, double hullCheckY) {
        ShipConfig config = ship.config;
        Location waterCheckLoc = reuseLocation2(vehicleLoc);
        int startY = (int) Math.floor(vehicleLoc.getY()) + config.waterScanAbove;
        int endY = (int) Math.floor(hullCheckY) - config.waterScanBelow;
        waterCheckLoc.setY(startY);

        double waterSurfaceY = waterCheckLoc.getY();

        for (int y = startY; y >= endY; y--) {
            waterCheckLoc.setY(y);
            if (isWaterOrWaterlogged(waterCheckLoc.getBlock())) {
                waterSurfaceY = y + 1;
                break;
            }
        }
        return waterSurfaceY;
    }

    /**
     * Calculates the buoyancy float offset based on ship density or config.
     */
    private double calculateFloatOffset() {
        ShipConfig config = ship.config;
        if ("custom".equals(ship.shipType) && ship.model.blockCount > 0) {
            float meanDensity = ship.model.getDensity();
            float airDensity = config.airDensity;
            float waterDensity = config.waterDensity;

            float t = (meanDensity - airDensity) / (waterDensity - airDensity);
            float referenceY = ship.model.minY;
            float waterlineY = referenceY + t * (ship.model.centerOfVolume.y - referenceY);
            return -waterlineY;
        } else {
            return ship.model.waterFloatOffset;
        }
    }

    /**
     * Check if a block is water or a waterlogged block (kelp, sea grass, etc.).
     */
    private boolean isWaterOrWaterlogged(org.bukkit.block.Block block) {
        Material type = block.getType();
        if (type == Material.WATER) {
            return true;
        }
        // Seagrass and kelp exist in water but don't implement Waterlogged
        if (type == Material.SEAGRASS || type == Material.TALL_SEAGRASS
                || type == Material.KELP || type == Material.KELP_PLANT) {
            return true;
        }
        if (block.getBlockData() instanceof Waterlogged waterlogged) {
            return waterlogged.isWaterlogged();
        }
        return false;
    }

    /**
     * Apply airship vertical physics (no gravity/buoyancy, manual vertical control).
     * Space to ascend, Sprint to descend.
     */
    /** Gravity per tick, shared with the buoyancy path so a falling ship falls at one rate. */
    private static final float GRAVITY_PER_TICK = 0.08f;

    /** Move {@code cur} toward {@code target} by at most {@code maxStep}, never overshooting. */
    private static float moveToward(float cur, float target, float maxStep) {
        if (maxStep <= 0f) return cur;
        float d = target - cur;
        return Math.abs(d) <= maxStep ? target : cur + Math.signum(d) * maxStep;
    }

    /**
     * Whether this hull is built to fly on thrust — regardless of whether the engines are running.
     *
     * <p>Two things this deliberately is NOT. It is not "is it producing lift right now": a ship whose
     * engines died is still an airframe and must come down on the airframe's terms, not drop out of the
     * flight model into the buoyancy path's fall. And it is not "does it carry any thrust block": a
     * sailing boat with two side propellers for steering has thrust blocks and no vertical thrust
     * whatsoever, and moving it onto a path that never checks for water would sink it through the
     * seabed. The question is specifically whether anything aboard pushes vertically.
     */
    private boolean isThrustLifted() {
        if (ship.model == null) return false;
        // Stats off means the whole ratio system is off, so propulsion does not fly anything — the ship
        // is an ordinary hull, exactly as it was before any of this existed. Without this, a stats-off
        // server's ships take the flight path with a lift ratio that was never computed.
        if (!ship.config.statsEnabled) return false;
        for (anon.def9a2a4.blockships.ShipModel.ThrustBlock tb : ship.model.thrustBlocks) {
            if (tb.axis() == anon.def9a2a4.blockships.ShipThrust.Axis.VERTICAL) return true;
        }
        return false;
    }

    private void applyAirshipVerticalPhysics(Location vehicleLoc) {
        ShipConfig config = ship.config;

        // Displacement-lifted hulls (prefab `type: airship`, or any negative-density build) keep the
        // original model exactly: full manual control, no gravity, no lift arithmetic. This split is
        // FIRST and gates the whole derivation on purpose — a prefab has no weight data to compute a
        // lift ratio from at all, and deriving the climb ceiling from lift unconditionally would give
        // every balloon in the game a ceiling of zero and ground the plugin's flagship ship. test-bot
        // asserts the prefab climbs; that assertion is the tripwire.
        boolean displacementLift = ship.isAirship;

        float lift = displacementLift ? 1.0f : Math.max(0f, liftRatio());
        // Only SURPLUS lift climbs. At or below 1.0 the ceiling is zero, so the Space branch below can
        // only ever compute min(v + a, 0) — a ship that cannot hold itself up cannot gain altitude by
        // any input, which falls out of the arithmetic rather than depending on gravity out-racing the
        // climb acceleration. Scaling the ceiling and not the acceleration keeps a marginal ship
        // responsive: it reaches its (small) climb rate at once, it just does not go far.
        float climbCap = displacementLift
            ? effectiveMaxVerticalSpeed
            : effectiveMaxVerticalSpeed
                * Math.min(1f, Math.max(0f, lift - 1f) / Math.max(0.01f, config.climbSurplusFull));
        // Sink is driven by how much lift is MISSING, so the whole range stays distinguishable. Keying
        // it on lift instead (the previous lift^n) saturated: lift 0.5 and lift 0 came out 14% apart,
        // so a half-powered ship and a dead one fell at nearly the same speed.
        float sinkFrac = lift >= 1f ? 0f
            : (float) Math.pow(1f - lift, Math.max(0.05f, config.sinkSpeedExponent));
        float terminalSink = displacementLift ? 0f : config.maxSinkSpeed * sinkFrac;
        float sinkAccel = displacementLift ? 0f : GRAVITY_PER_TICK * sinkFrac;

        // The climb branch runs ONLY when there is surplus to climb on. When climbCap is 0 the old code
        // still ran it as min(v + accel, 0), which reset the velocity to exactly zero every tick
        // whenever liftAcceleration exceeded the (small) sink acceleration — so holding Space on an
        // under-powered ship cut the descent to a single gravity step per tick and lift-hold-sink-factor
        // never applied at all. Measured at lift 0.95: 0.19 blocks/s against the 0.46 the model says.
        // Now Space falls through to drag, and the `hold` term below is the only thing it does.
        if (ship.hasDriver && ship.isSpacePressed && climbCap > 0f) {
            currentYVelocity = Math.min(currentYVelocity + effectiveLiftAcceleration, climbCap);
        } else if (ship.hasDriver && ship.isSprintPressed) {
            // Descending always works — there is no story in which lack of lift stops you going down.
            // The floor takes the larger of the two: without it, pressing Sprint on a failing ship
            // whose natural terminal exceeds the climb speed would SLOW the fall.
            //
            // And the result is taken as a MINIMUM against the current velocity, so Sprint can only ever
            // speed a descent up. `floor` is derived from the CURRENT lift while the velocity may still
            // carry a faster fall from a moment ago — relighting the engines while holding Sprint would
            // otherwise clamp a -0.5 fall up to -0.1, i.e. holding "descend" would brake by 80%.
            float floor = Math.max(effectiveMaxVerticalSpeed, terminalSink);
            float sprint = Math.max(currentYVelocity - effectiveDescendAcceleration, -floor);
            currentYVelocity = Math.min(currentYVelocity, sprint);
        } else if (!ship.hasDriver && displacementLift) {
            // A parked balloon holds station rather than drifting. Only balloons: doing this for a
            // thrust-lifted hull pinned its velocity to 0 every tick, so it could never integrate a
            // fall and an abandoned flier hovered indefinitely.
            currentYVelocity = 0.0f;
        } else {
            currentYVelocity *= config.verticalDrag;
        }

        // A driver touching either vertical control keeps a trickle of forward speed. Hoisted out of the
        // two branches above because Space on an under-powered ship now falls through to drag, and the
        // nudge should follow the INPUT, not which arm happened to handle it — it is also what keeps
        // currentSpeed above the movement threshold so the ship still counts as under way.
        if (ship.hasDriver && (ship.isSpacePressed || ship.isSprintPressed)
                && Math.abs(currentSpeed) < config.verticalForwardNudge) {
            currentSpeed = config.verticalForwardNudge;
        }

        // Gravity, as a pull toward the deficit's terminal at the deficit's rate. A no-op at lift >= 1
        // and for displacement hulls, so a balloon is untouched. Holding Space when you cannot climb
        // still points what thrust there is downward, easing the target — scaled by lift, so a ship
        // with none gets no help at all.
        if (terminalSink > 0f || sinkAccel > 0f) {
            float hold = (ship.hasDriver && ship.isSpacePressed)
                ? 1f - (1f - config.liftHoldSinkFactor) * Math.min(1f, lift)
                : 1f;
            currentYVelocity = moveToward(currentYVelocity, -terminalSink * hold, sinkAccel);
        }

        // Land rather than pass through. Outside the gravity branch on purpose: a Sprint descent at
        // lift >= 1 produces no residual gravity at all and previously got no ground check whatsoever.
        currentYVelocity = clampFallToGround(vehicleLoc, currentYVelocity);

        // Settle-to-rest, and the vertical path's only trigger for refreshCarrierTracking — keep it that
        // way (ShipInstance fires it too, but only on the movement→idle transition, which a ship still
        // coasting horizontally never reaches), or
        // deck-standers get collision jitter after a landing with nothing to clear it.
        //
        // The dead band applies only when nothing is pulling the ship down. It used to be
        // unconditional, which silently truncated the gentle end of the sink curve: at lift 0.99 the
        // per-tick acceleration is 0.003, so velocity was zeroed before it could ever accumulate toward
        // its 0.02 terminal and the ship hovered instead of drifting down at 0.4 blocks/s. That is
        // precisely the "slowly losing altitude" case the curve exists to express. A velocity of exactly
        // zero still settles, which is what clampFallToGround returns on touchdown.
        boolean settled = currentYVelocity == 0.0f
            || (Math.abs(currentYVelocity) < 0.01f && terminalSink <= 0f);
        if (settled) {
            currentYVelocity = 0.0f;
            if (wasVerticallyMoving) {
                wasVerticallyMoving = false;
                ship.refreshCarrierTracking();
            }
        } else {
            wasVerticallyMoving = true;
        }
    }

    /**
     * Snaps ship position to nearest 0.25 blocks and rotation to nearest 5 degrees.
     * Called when driver exits to eliminate floating-point jitter.
     */
    public void snapToFineGrid() {
        Location loc = ship.vehicle.getLocation();

        // Snap position to nearest quarter block
        double x = Math.round(loc.getX() * FINE_GRID_RESOLUTION) / FINE_GRID_RESOLUTION;
        double y = Math.round(loc.getY() * FINE_GRID_RESOLUTION) / FINE_GRID_RESOLUTION;
        double z = Math.round(loc.getZ() * FINE_GRID_RESOLUTION) / FINE_GRID_RESOLUTION;

        // Snap internal yaw to nearest 5 degrees
        float yaw = ShipTags.normalizeYaw(currentYaw);
        float snappedYaw = Math.round(yaw / 5.0f) * 5.0f;
        if (snappedYaw >= 360) snappedYaw = 0;
        currentYaw = snappedYaw;

        float pitch = loc.getPitch();

        // Keep vehicle yaw frozen - only snap position (visual rotation is via display transforms)
        Location snapped = new Location(loc.getWorld(), x, y, z, loc.getYaw(), pitch);
        TeleportCompat.teleport(ship.vehicle, snapped);

        // Update collision positions to sync with new location
        ship.updateCollisionPositions();
    }

    /**
     * Detects players standing on the ship's deck. defCoreLib owns the colliders, so the shulkers carry
     * {@code corelib:mech:} tags. Tests deck standers against the mechanism's collider boxes using a
     * horizontal-overlap + feet-height-band test, and returns the matched BLOCK INDEX (stable across
     * recovery) so {@link #alignToGrid()} can re-query the box after the snap.
     */
    private Map<Player, Integer> findPlayersOnDeckDelegated() {
        Map<Player, Integer> playersOnDeck = new HashMap<>();
        Location loc = ship.vehicle.getLocation();
        int n = ship.mechanism.blockCount();

        for (Player player : loc.getWorld().getPlayers()) {
            if (player.getLocation().distance(loc) > 32) continue;
            if (player.getVehicle() != null) continue;  // seated riders (delegated seats ARE colliders) ride the shulker; don't re-teleport/eject them

            org.bukkit.util.BoundingBox playerBox = player.getBoundingBox();
            double playerFeetY = playerBox.getMinY();

            for (int i = 0; i < n; i++) {
                org.bukkit.util.BoundingBox box = ship.mechanism.getColliderBoxByBlock(i);
                if (box == null) continue;  // most block indices have no collider; a shulker may be gone

                boolean withinHorizontalBounds =
                    playerBox.getMinX() < box.getMaxX() &&
                    playerBox.getMaxX() > box.getMinX() &&
                    playerBox.getMinZ() < box.getMaxZ() &&
                    playerBox.getMaxZ() > box.getMinZ();

                boolean onTop = playerFeetY >= box.getMaxY() - 0.1 && playerFeetY <= box.getMaxY() + 0.3;

                if (withinHorizontalBounds && onTop) {
                    playersOnDeck.put(player, i);
                    break;
                }
            }
        }

        return playersOnDeck;
    }

    /**
     * Snaps ship to block grid (integer coordinates, 90-degree rotation).
     * Handles players standing on deck by teleporting them with the ship.
     */
    public void alignToGrid() {
        Location loc = ship.vehicle.getLocation();

        // Snap position to nearest block corner
        double x = Math.round(loc.getX());
        double y = Math.round(loc.getY());
        double z = Math.round(loc.getZ());

        // Snap internal yaw to nearest 90 degrees
        float yaw = ShipTags.normalizeYaw(currentYaw);
        int cardinal = Math.round(yaw / 90.0f) * 90;
        float snappedYaw = cardinal % 360;
        currentYaw = snappedYaw;
        float snappedPitch = 0.0f;

        if (ship.mechanism != null) {
            // Delegated (M1): defCoreLib owns the colliders, so ship.updateCollisionPositions() is a no-op
            // (empty native `colliders` list) and findPlayersOnDeck's shipTag filter matches nothing. Capture
            // standers via the mechanism collider boxes, snap the vehicle, reposition the mechanism so its
            // colliders/displays land on the snapped pose, then re-seat standers onto the snapped colliders.
            Map<Player, Integer> onDeck = findPlayersOnDeckDelegated();

            // Delegated vehicle entity yaw MUST stay frozen at 0 — display passengers inherit it (1.21.9+)
            // and defCoreLib's addDrivenBaseOffset treats the display matrix translation as world-space
            // because the parent yaw is 0. All visual heading rides repositionDriven(currentYaw - spawnYaw)
            // below; setting the entity yaw to a cardinal would permanently rotate the displays off the
            // colliders. (Native re-baselines spawnYaw + updateDisplayTransforms, so it CAN set the entity
            // yaw; the delegated path deliberately does not re-baseline — mirror snapToFineGrid: keep loc's yaw.)
            Location aligned = new Location(loc.getWorld(), x, y, z, loc.getYaw(), snappedPitch);
            TeleportCompat.teleport(ship.vehicle, aligned);

            // Snap the mechanism to the aligned vehicle + snapped heading (relative to the as-built spawnYaw).
            // spawnYaw stays the assembly baseline — ShipInstance.alignToGrid does NOT re-baseline it for a
            // delegated ship — so (currentYaw - spawnYaw) is the true snapped orientation. This teleports the
            // collider carriers synchronously (repositionDriven -> rotate -> repositionColliders).
            ship.mechanism.repositionDriven(currentYaw - ship.spawnYaw);

            // Teleport standers to their collider's new position, keeping the player's own yaw/pitch.
            for (Map.Entry<Player, Integer> entry : onDeck.entrySet()) {
                Player player = entry.getKey();
                org.bukkit.util.BoundingBox box = ship.mechanism.getColliderBoxByBlock(entry.getValue());
                if (box == null) continue;  // collider went away between capture and re-query
                Location playerLoc = player.getLocation();
                player.teleport(new Location(
                    aligned.getWorld(),
                    box.getCenterX(),
                    box.getMaxY() + ship.config.assemblyNudgeHeight,
                    box.getCenterZ(),
                    playerLoc.getYaw(),
                    playerLoc.getPitch()
                ));
            }

            // Reset velocity and rotation (after repositionDriven, which set a setVelocity hint)
            ship.vehicle.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
            currentSpeed = 0.0f;
            currentRotationVelocity = 0.0f;
            currentYVelocity = 0.0f;
            collisionForce.set(0, 0, 0);
            return;
        }
    }
}
