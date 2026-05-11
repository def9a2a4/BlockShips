package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShipConfig;
import anon.def9a2a4.blockships.util.TeleportCompat;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.joml.Vector3f;

/**
 * Handles collision detection and response for a ship.
 * Detects terrain, entity, and ship-to-ship collisions and calculates appropriate forces.
 */
public class ShipCollision {
    private final ShipInstance ship;

    // Reusable vectors for detect() and applyResponse() - reduces GC pressure
    private final Vector3f workTotalForce = new Vector3f();
    private final Vector3f workHorizontalForce = new Vector3f();
    private final Vector3f workForwardDir = new Vector3f();
    private final Vector3f workForwardForce = new Vector3f();
    private final Vector3f workLateralForce = new Vector3f();
    private final Vector3f workForceDir = new Vector3f();
    private final Vector3f workSeparationNormal = new Vector3f();
    private final Vector3f workTerrainForce = new Vector3f();  // For calculateTerrainCollisionForce
    private final Vector3f workPenetrationForce = new Vector3f();  // For calculatePenetrationForce return value
    private final org.bukkit.util.BoundingBox workBlockBox = new org.bukkit.util.BoundingBox(0, 0, 0, 1, 1, 1);

    public ShipCollision(ShipInstance ship) {
        this.ship = ship;
    }

    /**
     * Detect all collisions and accumulate forces.
     * Only runs if ship is moving or has collision force from previous tick.
     */
    public void detect() {
        // Only check collisions if ship is moving or was recently bumped
        boolean isMoving = Math.abs(ship.physics.currentSpeed) > 0.001f ||
                          Math.abs(ship.physics.currentRotationVelocity) > 0.01f;
        boolean hasPreviousForce = ship.physics.collisionForce.lengthSquared() > 0.001f;

        if (!isMoving && !hasPreviousForce) {
            return; // Skip collision detection for stationary ships
        }

        // Reuse work vector instead of allocating new one
        workTotalForce.set(0, 0, 0);
        int collisionCount = 0;

        for (CollisionBox cb : ship.colliders) {
            // Check terrain collisions
            Vector3f terrainForce = calculateTerrainCollisionForce(cb);
            if (terrainForce.lengthSquared() > 0.001f) {
                workTotalForce.add(terrainForce);
                collisionCount++;
            }
        }

        // Ship-to-ship collision (resolved by global coordinator)
        ShipCollisionCoordinator coordinator = ShipCollisionCoordinator.getInstance();
        if (coordinator != null) {
            Vector3f shipForce = coordinator.getShipCollisionForce(ship.id);
            if (shipForce.lengthSquared() > 0.001f) {
                workTotalForce.add(shipForce);
                collisionCount++;
            }
        }

        // Update collision force
        if (collisionCount > 0) {
            // Average the forces and set as new collision force
            ship.physics.collisionForce.set(workTotalForce.div(collisionCount));
        } else {
            // No collisions - decay existing force
            ship.physics.collisionForce.mul(ship.config.collisionForceDecay);

            // Zero out if too small
            if (ship.physics.collisionForce.lengthSquared() < 0.001f) {
                ship.physics.collisionForce.set(0, 0, 0);
            }
        }
    }

    /**
     * Apply collision response to ship movement.
     */
    public void applyResponse() {
        Vector3f collisionForce = ship.physics.collisionForce;

        // Skip if no significant collision force
        if (collisionForce.lengthSquared() < 0.001f) {
            return;
        }

        ShipConfig config = ship.config;

        // Separate horizontal and vertical forces (reuse work vectors)
        workHorizontalForce.set(collisionForce.x, 0, collisionForce.z);
        float verticalForce = collisionForce.y;

        // Get forward direction vector (reuse work vector)
        float yawRad = (float) Math.toRadians(-ship.vehicle.getYaw());
        workForwardDir.set(
            (float) Math.sin(yawRad),
            0,
            (float) Math.cos(yawRad)
        );

        // Decompose force into forward and lateral components (reuse work vectors)
        float forwardComponent = workHorizontalForce.dot(workForwardDir);
        workForwardForce.set(workForwardDir).mul(forwardComponent);
        workLateralForce.set(workHorizontalForce).sub(workForwardForce);

        // Check if ship is stationary or slow-moving
        boolean isStationary = Math.abs(ship.physics.currentSpeed) < 0.05f;

        if (isStationary) {
            // Ship is stationary/slow - push it in the direction of the force
            // Convert horizontal force directly to velocity
            float forceMagnitude = workHorizontalForce.length();
            if (forceMagnitude > 0.001f) {
                // Normalize force direction and convert to speed (reuse work vector)
                workForceDir.set(workHorizontalForce).normalize();

                // Calculate speed change in force direction
                float speedChange = forceMagnitude * config.collisionResponseStrength * 0.5f; // Dampen conversion

                // Update speed in forward direction based on how aligned force is with forward dir
                float alignment = workForceDir.dot(workForwardDir);
                ship.physics.currentSpeed += alignment * speedChange;

                // Clamp speed to max
                ship.physics.currentSpeed = Math.max(-config.maxSpeed, Math.min(ship.physics.currentSpeed, config.maxSpeed));

                // Also apply direct positional push for immediate response
                Location vehicleLoc = ship.vehicle.getLocation();
                vehicleLoc.add(
                    workForceDir.x * config.collisionResponseStrength * 0.3f,
                    0,
                    workForceDir.z * config.collisionResponseStrength * 0.3f
                );
                TeleportCompat.teleport(ship.vehicle, vehicleLoc);
            }
        } else {
            // Ship is moving - apply resistance and sliding forces

            // Apply forward component to speed (resistance when hitting head-on)
            if (forwardComponent < 0 && ship.physics.currentSpeed > 0) {
                // Hitting obstacle while moving forward - reduce speed
                ship.physics.currentSpeed += forwardComponent * config.collisionResponseStrength;
                ship.physics.currentSpeed = Math.max(ship.physics.currentSpeed, 0); // Don't go negative
            } else if (forwardComponent > 0 && ship.physics.currentSpeed < 0) {
                // Hitting obstacle while moving backward - reduce speed
                ship.physics.currentSpeed += forwardComponent * config.collisionResponseStrength;
                ship.physics.currentSpeed = Math.min(ship.physics.currentSpeed, 0); // Don't go positive
            } else if (Math.abs(forwardComponent) > 0.001f) {
                // Force is pushing in same direction as movement - add to speed
                ship.physics.currentSpeed += forwardComponent * config.collisionResponseStrength * 0.3f;
                ship.physics.currentSpeed = Math.max(-config.maxSpeed, Math.min(ship.physics.currentSpeed, config.maxSpeed));
            }

            // Apply lateral force as position offset (sliding along obstacles)
            if (workLateralForce.lengthSquared() > 0.001f) {
                Location vehicleLoc = ship.vehicle.getLocation();
                vehicleLoc.add(
                    workLateralForce.x * config.collisionResponseStrength,
                    0, // No Y movement
                    workLateralForce.z * config.collisionResponseStrength
                );
                TeleportCompat.teleport(ship.vehicle, vehicleLoc);
            }
        }

        // Apply vertical collision response
        if (Math.abs(verticalForce) > 0.001f) {
            // Apply vertical force to Y velocity
            if (verticalForce > 0 && ship.physics.currentYVelocity < 0) {
                // Hitting ground while falling - stop downward velocity
                ship.physics.currentYVelocity = 0;
            } else if (verticalForce < 0 && ship.physics.currentYVelocity > 0) {
                // Hitting ceiling while rising - stop upward velocity
                ship.physics.currentYVelocity = 0;
            } else {
                // Apply resistance proportional to vertical force
                ship.physics.currentYVelocity += verticalForce * config.collisionResponseStrength * 0.5f;
            }
        }
    }

    // ===== Private Helper Methods =====

    /**
     * Calculate collision force from terrain (blocks).
     */
    // Maximum collisions to process per collision box (early termination optimization)
    private static final int MAX_COLLISIONS_PER_BOX = 4;

    private Vector3f calculateTerrainCollisionForce(CollisionBox cb) {
        org.bukkit.util.BoundingBox shulkerBox = cb.entity.getBoundingBox();
        World world = cb.entity.getWorld();
        workTerrainForce.set(0, 0, 0);  // Reuse work vector instead of allocating
        int collisionCount = 0;
        ShipConfig config = ship.config;

        // Check all blocks within the shulker's bounding box
        int minX = (int) Math.floor(shulkerBox.getMinX());
        int maxX = (int) Math.ceil(shulkerBox.getMaxX());
        int minY = (int) Math.floor(shulkerBox.getMinY());
        int maxY = (int) Math.ceil(shulkerBox.getMaxY());
        int minZ = (int) Math.floor(shulkerBox.getMinZ());
        int maxZ = (int) Math.ceil(shulkerBox.getMaxZ());

        // Reuse field-level BoundingBox to avoid allocations in tight loop
        org.bukkit.util.BoundingBox blockBox = workBlockBox;

        outerLoop:
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);

                    // Skip non-solid blocks
                    Material type = block.getType();
                    if (!type.isSolid() || type == Material.WATER) {
                        continue;
                    }

                    // Resize reusable bounding box to current block position
                    blockBox.resize(x, y, z, x + 1, y + 1, z + 1);

                    // Check if shulker overlaps with block
                    if (shulkerBox.overlaps(blockBox)) {
                        // Calculate penetration depth and separation normal
                        Vector3f force = calculatePenetrationForce(shulkerBox, blockBox);
                        if (force.lengthSquared() > config.minPenetrationDepth * config.minPenetrationDepth) {
                            // Scale force with ship speed (minimum 1.0 to preserve slow-ship behavior)
                            float speedFactor = Math.max(1.0f, Math.abs(ship.physics.currentSpeed) * config.terrainSpeedMultiplier);
                            workTerrainForce.add(force.mul(config.terrainCollisionStrength * speedFactor));
                            collisionCount++;

                            // Early termination: enough collision data gathered
                            if (collisionCount >= MAX_COLLISIONS_PER_BOX) {
                                break outerLoop;
                            }
                        }
                    }
                }
            }
        }

        // Average the force if multiple collisions
        if (collisionCount > 0) {
            workTerrainForce.div(collisionCount);
        }

        return workTerrainForce;
    }

    /**
     * Calculate penetration force between two bounding boxes.
     * Returns a force vector pointing away from the obstacle.
     */
    private Vector3f calculatePenetrationForce(org.bukkit.util.BoundingBox thisBox, org.bukkit.util.BoundingBox otherBox) {
        // Get overlap region
        double overlapMinX = Math.max(thisBox.getMinX(), otherBox.getMinX());
        double overlapMaxX = Math.min(thisBox.getMaxX(), otherBox.getMaxX());
        double overlapMinY = Math.max(thisBox.getMinY(), otherBox.getMinY());
        double overlapMaxY = Math.min(thisBox.getMaxY(), otherBox.getMaxY());
        double overlapMinZ = Math.max(thisBox.getMinZ(), otherBox.getMinZ());
        double overlapMaxZ = Math.min(thisBox.getMaxZ(), otherBox.getMaxZ());

        // Calculate penetration depth in each axis
        double xPenetration = overlapMaxX - overlapMinX;
        double yPenetration = overlapMaxY - overlapMinY;
        double zPenetration = overlapMaxZ - overlapMinZ;

        // Find minimum penetration axis (separation direction) - reuse work vector
        workSeparationNormal.set(0, 0, 0);
        float penetrationDepth;

        if (xPenetration < yPenetration && xPenetration < zPenetration) {
            // Separate along X axis
            workSeparationNormal.x = (thisBox.getCenterX() > otherBox.getCenterX()) ? 1 : -1;
            penetrationDepth = (float) xPenetration;
        } else if (yPenetration < zPenetration) {
            // Separate along Y axis
            workSeparationNormal.y = (thisBox.getCenterY() > otherBox.getCenterY()) ? 1 : -1;
            penetrationDepth = (float) yPenetration;
        } else {
            // Separate along Z axis
            workSeparationNormal.z = (thisBox.getCenterZ() > otherBox.getCenterZ()) ? 1 : -1;
            penetrationDepth = (float) zPenetration;
        }

        // Return push force (normal * depth) - reuse work vector (caller copies via .mul())
        return workPenetrationForce.set(workSeparationNormal).mul(penetrationDepth);
    }
}
