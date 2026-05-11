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

    // Track vertical movement state for carrier refresh on stop
    private boolean wasVerticallyMoving = false;

    // Sound cooldown (ticks until next sound can play)
    private int soundCooldown = 0;

    // Reusable Locations for physics calculations - reduces GC pressure
    private Location workLocation = null;
    private Location workLocation2 = null;  // Second work location for buoyancy (hull check vs water scan)

    public ShipPhysics(ShipInstance ship) {
        this.ship = ship;
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

        // Apply acceleration/deceleration based on input state
        if (ship.isForwardPressed) {
            currentSpeed = Math.min(currentSpeed + config.acceleration, config.maxSpeed);
        } else if (ship.isBackwardPressed) {
            if (currentSpeed > 0) {
                currentSpeed = Math.max(currentSpeed - config.activeDeceleration, 0.0f);
            } else {
                currentSpeed = Math.max(currentSpeed - config.acceleration, -config.maxSpeed);
            }
        }

        // Apply drag based on player presence (unless actively pressing W/S)
        if (!ship.isForwardPressed && !ship.isBackwardPressed) {
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

        // Stop if speed is very small
        if (Math.abs(currentSpeed) < config.minMovementThreshold) {
            currentSpeed = 0.0f;
        }

        // Calculate forward direction vector from vehicle yaw
        float yawRad = (float) Math.toRadians(-ship.vehicle.getYaw());
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

        // Update rotation based on input state
        if (ship.isLeftPressed) {
            currentRotationVelocity = Math.max(
                currentRotationVelocity - config.rotationAcceleration,
                -config.rotationSpeed
            );
        } else if (ship.isRightPressed) {
            currentRotationVelocity = Math.min(
                currentRotationVelocity + config.rotationAcceleration,
                config.rotationSpeed
            );
        } else {
            // No input - apply momentum decay
            if (currentRotationVelocity > 0) {
                currentRotationVelocity = Math.max(
                    currentRotationVelocity - config.rotationDeceleration,
                    0.0f
                );
            } else if (currentRotationVelocity < 0) {
                currentRotationVelocity = Math.min(
                    currentRotationVelocity + config.rotationDeceleration,
                    0.0f
                );
            }
        }

        // Apply rotation
        if (Math.abs(currentRotationVelocity) > 0.01f) {
            float newYaw = ship.vehicle.getYaw() + currentRotationVelocity;
            Location newLoc = ship.vehicle.getLocation();
            newLoc.setYaw(newYaw);
            TeleportCompat.teleport(ship.vehicle, newLoc);
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
                // Close enough — don't move or teleport. Prevents carrier jitter
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

        if (ship.isSpacePressed) {
            currentYVelocity = Math.min(currentYVelocity + config.liftAcceleration, config.maxVerticalSpeed);
            if (Math.abs(currentSpeed) < config.verticalForwardNudge) {
                currentSpeed = config.verticalForwardNudge;
            }
        } else if (ship.isSprintPressed) {
            currentYVelocity = Math.max(currentYVelocity - config.descendAcceleration, -config.maxVerticalSpeed);
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

        // Snap yaw to nearest 5 degrees
        float yaw = ShipTags.normalizeYaw(loc.getYaw());
        float snappedYaw = Math.round(yaw / 5.0f) * 5.0f;
        if (snappedYaw >= 360) snappedYaw = 0;

        float pitch = loc.getPitch();

        Location snapped = new Location(loc.getWorld(), x, y, z, snappedYaw, pitch);
        TeleportCompat.teleport(ship.vehicle, snapped);

        // Update collision positions to sync with new location
        ship.updateCollisionPositions();
    }

    /**
     * Finds players standing on this ship's shulkers.
     * @return Map of player to the shulker they're standing on
     */
    public Map<Player, Shulker> findPlayersOnDeck() {
        Map<Player, Shulker> playersOnDeck = new HashMap<>();
        Location loc = ship.vehicle.getLocation();
        String shipTag = ShipTags.shipTag(ship.id);

        for (Player player : loc.getWorld().getPlayers()) {
            if (player.getLocation().distance(loc) > 32) continue;

            for (Entity nearby : player.getNearbyEntities(2, 2, 2)) {
                if (!(nearby instanceof Shulker shulker)) continue;
                if (!shulker.getScoreboardTags().contains(shipTag)) continue;

                org.bukkit.util.BoundingBox playerBox = player.getBoundingBox();
                org.bukkit.util.BoundingBox shulkerBox = shulker.getBoundingBox();

                double playerFeetY = playerBox.getMinY();
                double shulkerTopY = shulkerBox.getMaxY();

                boolean withinHorizontalBounds =
                    playerBox.getMinX() < shulkerBox.getMaxX() &&
                    playerBox.getMaxX() > shulkerBox.getMinX() &&
                    playerBox.getMinZ() < shulkerBox.getMaxZ() &&
                    playerBox.getMaxZ() > shulkerBox.getMinZ();

                boolean onTop = playerFeetY >= shulkerTopY - 0.1 && playerFeetY <= shulkerTopY + 0.3;

                if (withinHorizontalBounds && onTop) {
                    playersOnDeck.put(player, shulker);
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

        // Snap yaw to nearest 90 degrees
        float yaw = ShipTags.normalizeYaw(loc.getYaw());
        int cardinal = Math.round(yaw / 90.0f) * 90;
        float snappedYaw = cardinal % 360;
        float snappedPitch = 0.0f;

        // Find players standing on deck BEFORE moving
        Map<Player, Shulker> playersOnDeck = findPlayersOnDeck();

        // Set the new aligned location
        Location aligned = new Location(loc.getWorld(), x, y, z, snappedYaw, snappedPitch);
        TeleportCompat.teleport(ship.vehicle, aligned);

        // Update collision positions immediately
        ship.updateCollisionPositions();

        // Teleport players to their shulker's new position
        for (Map.Entry<Player, Shulker> entry : playersOnDeck.entrySet()) {
            Player player = entry.getKey();
            Shulker shulker = entry.getValue();
            Location shulkerLoc = shulker.getLocation();
            Location playerLoc = player.getLocation();
            player.teleport(new Location(
                shulkerLoc.getWorld(),
                shulkerLoc.getX(),
                shulker.getBoundingBox().getMaxY() + ship.config.assemblyNudgeHeight,
                shulkerLoc.getZ(),
                playerLoc.getYaw(),
                playerLoc.getPitch()
            ));
        }

        // Reset velocity and rotation
        ship.vehicle.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        currentSpeed = 0.0f;
        currentRotationVelocity = 0.0f;
        currentYVelocity = 0.0f;
        collisionForce.set(0, 0, 0);
    }
}
