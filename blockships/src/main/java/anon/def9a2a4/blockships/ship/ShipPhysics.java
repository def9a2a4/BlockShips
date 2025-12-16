package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShipConfig;
import anon.def9a2a4.blockships.ShipTags;
import org.bukkit.Location;
import org.bukkit.Material;
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

    public ShipPhysics(ShipInstance ship) {
        this.ship = ship;
    }

    /**
     * Main physics update. Applies acceleration, drag, buoyancy, and movement.
     * Called from ShipInstance.tick().
     */
    public void update() {
        if (!ship.vehicle.isValid() || ship.vehicle.isDead()) return;

        Location vehicleLoc = ship.vehicle.getLocation();
        Material below = vehicleLoc.clone().subtract(0, 0.5, 0).getBlock().getType();
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

            // Apply extra drag in water
            if (below == Material.WATER) {
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
            Location newLoc = vehicleLoc.clone();
            if (hasHorizontalMovement) {
                newLoc.add(forwardX * currentSpeed, 0, forwardZ * currentSpeed);
            }
            if (hasVerticalMovement) {
                newLoc.add(0, currentYVelocity, 0);
            }
            ship.vehicle.teleport(newLoc);
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
            ship.vehicle.teleport(newLoc);
        }
    }

    /**
     * Handle buoyancy physics for water-based ships.
     */
    private void handleBuoyancy(Location vehicleLoc) {
        ShipConfig config = ship.config;
        Material below = vehicleLoc.clone().subtract(0, 0.5, 0).getBlock().getType();

        // For custom ships, check water at the ship's lowest point (hull), not at the wheel
        double hullCheckY = vehicleLoc.getY() + ship.model.minY;
        Location hullCheckLoc = vehicleLoc.clone();
        hullCheckLoc.setY(hullCheckY);
        Material atHull = hullCheckLoc.getBlock().getType();
        Material belowHull = hullCheckLoc.clone().subtract(0, 1, 0).getBlock().getType();
        boolean inWater = (atHull == Material.WATER || belowHull == Material.WATER);

        if (inWater) {
            // Find water surface Y level by scanning a fixed column
            Location waterCheckLoc = vehicleLoc.clone();
            int startY = (int) Math.floor(vehicleLoc.getY()) + config.waterScanAbove;
            int endY = (int) Math.floor(hullCheckY) - config.waterScanBelow;
            waterCheckLoc.setY(startY);

            double waterSurfaceY = waterCheckLoc.getY();

            // Scan downward to find air-water boundary
            for (int y = startY; y >= endY; y--) {
                waterCheckLoc.setY(y);
                Material blockType = waterCheckLoc.getBlock().getType();
                if (blockType == Material.WATER) {
                    waterSurfaceY = y + 1;
                    break;
                }
            }

            // Target Y position: water surface + float offset
            double floatOffset;
            if ("custom".equals(ship.shipType) && ship.model.blockCount > 0) {
                // Interpolation-based buoyancy
                float meanDensity = ship.model.getDensity();
                float airDensity = config.airDensity;
                float waterDensity = config.waterDensity;

                float t = (meanDensity - airDensity) / (waterDensity - airDensity);
                float referenceY = ship.model.minY;
                float waterlineY = referenceY + t * (ship.model.centerOfVolume.y - referenceY);
                floatOffset = -waterlineY;
            } else {
                floatOffset = ship.model.waterFloatOffset;
            }

            double targetY = waterSurfaceY + floatOffset;
            double currentY = vehicleLoc.getY();
            double yDifference = targetY - currentY;

            // Proportional approach with damping
            if (Math.abs(yDifference) < 0.02) {
                currentYVelocity = 0.0f;
            } else {
                float targetVelocity = (float) (yDifference * config.buoyancyStrength);
                currentYVelocity = currentYVelocity * (1.0f - config.buoyancyDamping) + targetVelocity * config.buoyancyDamping;
            }
        } else {
            // Check ground at ship's lowest point (hull), not at the wheel
            // Use small offset (0.1) so hull settles just into the ground block
            Material belowHullBlock = hullCheckLoc.clone().subtract(0, 0.1, 0).getBlock().getType();
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
        }
    }

    /**
     * Apply ship velocity to players standing on deck.
     * Called per-collider from updateCollisionPositions().
     */
    public void applyDeckPhysics(CollisionBox cb, Vector3f velocity, boolean isFirstTick) {
        if (!ship.hasPlayersNearby || isFirstTick) return;

        for (Entity nearby : cb.entity.getNearbyEntities(1.5, 1.5, 1.5)) {
            if (nearby instanceof Player player) {
                if (isPlayerSeatedOnShip(player)) {
                    continue;
                }
                pushPlayerOutOfShulker(player, cb.entity);
            }
        }
    }

    /**
     * Check if a player is seated on any shulker belonging to this ship.
     */
    private boolean isPlayerSeatedOnShip(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof Shulker shulker) {
            return ShipTags.extractShipId(shulker.getScoreboardTags()) != null
                && ShipTags.extractShipId(shulker.getScoreboardTags()).equals(ship.id);
        }
        return false;
    }

    /**
     * If player is clipping into shulker and above its center, push them up.
     */
    private void pushPlayerOutOfShulker(Player player, Shulker shulker) {
        org.bukkit.util.BoundingBox playerBox = player.getBoundingBox();
        org.bukkit.util.BoundingBox shulkerBox = shulker.getBoundingBox();

        if (!playerBox.overlaps(shulkerBox)) {
            return;
        }

        double playerFeetY = playerBox.getMinY();
        double shulkerCenterY = shulkerBox.getCenterY();

        if (playerFeetY < shulkerCenterY) {
            return;
        }

        double shulkerTopY = shulkerBox.getMaxY();
        double targetY = shulkerTopY + 0.05;

        Location playerLoc = player.getLocation();
        playerLoc.setY(targetY);
        player.teleport(playerLoc);
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
        float yaw = loc.getYaw() % 360;
        if (yaw < 0) yaw += 360;
        float snappedYaw = Math.round(yaw / 5.0f) * 5.0f;
        if (snappedYaw >= 360) snappedYaw = 0;

        float pitch = loc.getPitch();

        Location snapped = new Location(loc.getWorld(), x, y, z, snappedYaw, pitch);
        ship.vehicle.teleport(snapped);

        // Update collision positions to sync with new location
        ship.updateCollisionPositions();
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
        float yaw = loc.getYaw() % 360;
        if (yaw < 0) yaw += 360;
        int cardinal = Math.round(yaw / 90.0f) * 90;
        float snappedYaw = cardinal % 360;
        float snappedPitch = 0.0f;

        // Find players standing on this ship's shulkers BEFORE moving
        Map<Player, Shulker> playersOnDeck = new HashMap<>();
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

        // Set the new aligned location
        Location aligned = new Location(loc.getWorld(), x, y, z, snappedYaw, snappedPitch);
        ship.vehicle.teleport(aligned);

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
                shulker.getBoundingBox().getMaxY() + 0.1,
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
