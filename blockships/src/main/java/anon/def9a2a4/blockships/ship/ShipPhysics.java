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

    // Sound cooldown (ticks until next sound can play)
    private int soundCooldown = 0;

    // Reusable Locations for physics calculations - reduces GC pressure
    private Location workLocation = null;
    private Location workLocation2 = null;  // Second work location for buoyancy (hull check vs water scan)

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
            statsComputed = true;
            return;
        }

        // Custom ships: compute ratio from sail power and mass
        float sailRatio = ship.model.getSailRatio(config.basePower);
        // Apply sail cap: non-engine contribution capped at sailCapRatio
        float nonEngineRatio = Math.min(sailRatio, config.sailCapRatio);
        float ratio = Math.min(nonEngineRatio, 1.0f);

        // Compute horizontal stats
        effectiveMaxSpeed = config.computeStat(ratio, config.maxSpeed,
            config.floorMaxSpeed, config.capMaxSpeed);
        effectiveAcceleration = config.computeStat(ratio, config.acceleration,
            config.floorAcceleration, config.capAcceleration);
        effectiveRotationSpeed = config.computeStat(ratio, config.rotationSpeed,
            config.floorRotationSpeed, config.capRotationSpeed);
        effectiveRotationAcceleration = config.computeStat(ratio, config.rotationAcceleration,
            config.floorRotationAcceleration, config.capRotationAcceleration);
        effectiveRotationDeceleration = config.computeStat(ratio, config.rotationDeceleration,
            config.floorRotationDeceleration, config.capRotationDeceleration);

        // Compute vertical stats (airships only, density-based)
        if (ship.isAirship) {
            float density = ship.model.getDensity();
            float densityMag = Math.abs(density);
            float verticalRatio = Math.min(densityMag * config.verticalDensityScale, 1.0f);

            effectiveMaxVerticalSpeed = config.computeStat(verticalRatio, config.maxVerticalSpeed,
                config.floorMaxVerticalSpeed, config.capMaxVerticalSpeed);
            effectiveLiftAcceleration = config.computeStat(verticalRatio, config.liftAcceleration,
                config.floorVerticalAcceleration, config.capVerticalAcceleration);
            effectiveDescendAcceleration = config.computeStat(verticalRatio, config.descendAcceleration,
                config.floorVerticalAcceleration, config.capVerticalAcceleration);
        } else {
            effectiveMaxVerticalSpeed = config.maxVerticalSpeed;
            effectiveLiftAcceleration = config.liftAcceleration;
            effectiveDescendAcceleration = config.descendAcceleration;
        }

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

    /**
     * Main physics update. Applies acceleration, drag, buoyancy, and movement.
     * Called from ShipInstance.tick().
     */
    public void update() {
        if (!ship.vehicle.isValid() || ship.vehicle.isDead()) return;

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
            float dragMultiplier;
            if (ship.hasDriver) {
                dragMultiplier = config.mountedDrag;
            } else if (ship.hasPlayersNearby) {
                dragMultiplier = config.unmannedDrag;
            } else {
                dragMultiplier = config.idleDrag;
            }

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

        // Apply vertical physics based on ship type
        if (ship.isAirship) {
            applyAirshipVerticalPhysics();
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
     * Handle buoyancy physics for water-based ships.
     */
    private void handleBuoyancy(Location vehicleLoc) {
        ShipConfig config = ship.config;

        // For custom ships, check water at the ship's lowest point (hull), not at the wheel
        // Use workLocation for hull check position
        double hullCheckY = vehicleLoc.getY() + ship.model.minY;
        Location hullCheckLoc = reuseLocation(vehicleLoc);
        hullCheckLoc.setY(hullCheckY);

        // Check water at hull and one block below
        Location belowHullLoc = hullCheckLoc.clone();
        belowHullLoc.subtract(0, 1, 0);
        boolean inWater = isWaterOrWaterlogged(hullCheckLoc.getBlock())
            || isWaterOrWaterlogged(belowHullLoc.getBlock());

        if (inWater) {
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
            // Check ground at ship's lowest point (hull), not at the wheel
            // Use small offset (0.1) so hull settles just into the ground block
            // hullCheckLoc (workLocation) still has hull Y, use workLocation2 for below check
            Location belowCheck = reuseLocation2(hullCheckLoc);
            belowCheck.subtract(0, 0.1, 0);
            Material belowHullBlock = belowCheck.getBlock().getType();
            if (belowHullBlock == Material.AIR || !belowHullBlock.isSolid()) {
                // Fall if hull not on ground
                currentYVelocity -= 0.08f;  // Gravity
            } else {
                // Hull on solid ground
                currentYVelocity = 0.0f;
            }
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
    private void applyAirshipVerticalPhysics() {
        ShipConfig config = ship.config;

        if (ship.hasDriver && ship.isSpacePressed) {
            currentYVelocity = Math.min(currentYVelocity + effectiveLiftAcceleration, effectiveMaxVerticalSpeed);
            if (Math.abs(currentSpeed) < config.verticalForwardNudge) {
                currentSpeed = config.verticalForwardNudge;
            }
        } else if (ship.hasDriver && ship.isSprintPressed) {
            currentYVelocity = Math.max(currentYVelocity - effectiveDescendAcceleration, -effectiveMaxVerticalSpeed);
            if (Math.abs(currentSpeed) < config.verticalForwardNudge) {
                currentSpeed = config.verticalForwardNudge;
            }
        } else {
            if (!ship.hasDriver) {
                currentYVelocity = 0.0f;
            } else {
                currentYVelocity *= config.verticalDrag;
            }
        }

        if (Math.abs(currentYVelocity) < 0.01f) {
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
     * Delegated (defCoreLib) analogue of {@link #findPlayersOnDeck()}. defCoreLib owns the colliders, so
     * the native {@code colliders} list is empty and the shulkers carry {@code corelib:mech:} tags rather
     * than {@code shipTag}. Detects deck standers against the mechanism's collider boxes instead, using the
     * identical horizontal-overlap + feet-height-band test, and returns the matched BLOCK INDEX (stable
     * across recovery) so {@link #alignToGrid()} can re-query the box after the snap.
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
