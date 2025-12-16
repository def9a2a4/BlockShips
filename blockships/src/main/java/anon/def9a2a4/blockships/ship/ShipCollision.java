package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShipConfig;
import anon.def9a2a4.blockships.ShipRegistry;
import anon.def9a2a4.blockships.ShipTags;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles collision detection and response for a ship.
 * Detects terrain, entity, and ship-to-ship collisions and calculates appropriate forces.
 */
public class ShipCollision {
    private final ShipInstance ship;

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

        Vector3f totalForce = new Vector3f(0, 0, 0);
        int collisionCount = 0;
        // String ownShipTag = ShipTags.shipTag(ship.id);

        // Optimization: Query nearby entities once for the entire ship using cached radius
        // Location vehicleLoc = ship.vehicle.getLocation();
        // float radius = ship.collisionRadius;
        // Collection<Entity> allNearbyEntities = vehicleLoc.getWorld()
        //     .getNearbyEntities(vehicleLoc, radius, radius, radius);

        // // Pre-filter: remove own ship entities once (avoid repeated tag checks per collider)
        // List<Entity> relevantEntities = new ArrayList<>();
        // for (Entity e : allNearbyEntities) {
        //     if (!e.getScoreboardTags().contains(ownShipTag)) {
        //         relevantEntities.add(e);
        //     }
        // }

        for (CollisionBox cb : ship.colliders) {
            // 1. Check terrain collisions
            Vector3f terrainForce = calculateTerrainCollisionForce(cb);
            if (terrainForce.lengthSquared() > 0.001f) {
                totalForce.add(terrainForce);
                collisionCount++;
            }

            // // 2. Check entity and ship collisions
            // Location cbLoc = cb.entity.getLocation();

            // for (Entity nearby : relevantEntities) {
            //     // Distance check using max axis distance (matches getNearbyEntities box query)
            //     Location nearbyLoc = nearby.getLocation();
            //     double dx = Math.abs(cbLoc.getX() - nearbyLoc.getX());
            //     double dy = Math.abs(cbLoc.getY() - nearbyLoc.getY());
            //     double dz = Math.abs(cbLoc.getZ() - nearbyLoc.getZ());
            //     if (dx > 2.0 || dy > 2.0 || dz > 2.0) {
            //         continue; // Entity too far from this collider
            //     }

            //     // Check if entity belongs to another ship
            //     Optional<String> otherShipTag = nearby.getScoreboardTags().stream()
            //         .filter(tag -> tag.startsWith(ShipTags.SHIP_PREFIX))
            //         .findFirst();

            //     if (otherShipTag.isPresent() && nearby instanceof Shulker otherShulker) {
            //         // This is another ship's collision box - ship-to-ship collision
            //         try {
            //             String otherShipIdStr = otherShipTag.get().substring(ShipTags.SHIP_PREFIX.length());
            //             UUID otherShipId = UUID.fromString(otherShipIdStr);
            //             ShipInstance otherShip = ShipRegistry.byId(otherShipId);

            //             if (otherShip != null) {
            //                 // Find the collision box for the other ship's shulker
            //                 for (CollisionBox otherCb : otherShip.colliders) {
            //                     if (otherCb.entity == otherShulker) {
            //                         Vector3f shipForce = calculateShipCollisionForce(cb, otherShip, otherCb);
            //                         if (shipForce.lengthSquared() > 0.001f) {
            //                             totalForce.add(shipForce);
            //                             collisionCount++;
            //                         }
            //                         break;
            //                     }
            //                 }
            //             }
            //         } catch (IllegalArgumentException e) {
            //             // Invalid UUID, skip
            //         }
            //     } else if (nearby instanceof LivingEntity || nearby instanceof Boat || nearby instanceof Minecart) {
            //         // Regular entity collision
            //         Vector3f entityForce = calculateEntityCollisionForce(cb, nearby);
            //         if (entityForce.lengthSquared() > 0.001f) {
            //             totalForce.add(entityForce);
            //             collisionCount++;
            //         }
            //     }
            // }
        }

        // Update collision force
        if (collisionCount > 0) {
            // Average the forces and set as new collision force
            ship.physics.collisionForce = totalForce.div(collisionCount);
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

        // Separate horizontal and vertical forces
        Vector3f horizontalForce = new Vector3f(collisionForce.x, 0, collisionForce.z);
        float verticalForce = collisionForce.y;

        // Get forward direction vector
        float yawRad = (float) Math.toRadians(-ship.vehicle.getYaw());
        Vector3f forwardDir = new Vector3f(
            (float) Math.sin(yawRad),
            0,
            (float) Math.cos(yawRad)
        );

        // Decompose force into forward and lateral components
        float forwardComponent = horizontalForce.dot(forwardDir);
        Vector3f forwardForce = new Vector3f(forwardDir).mul(forwardComponent);
        Vector3f lateralForce = new Vector3f(horizontalForce).sub(forwardForce);

        // Check if ship is stationary or slow-moving
        boolean isStationary = Math.abs(ship.physics.currentSpeed) < 0.05f;

        if (isStationary) {
            // Ship is stationary/slow - push it in the direction of the force
            // Convert horizontal force directly to velocity
            float forceMagnitude = horizontalForce.length();
            if (forceMagnitude > 0.001f) {
                // Normalize force direction and convert to speed
                Vector3f forceDir = new Vector3f(horizontalForce).normalize();

                // Calculate speed change in force direction
                float speedChange = forceMagnitude * config.collisionResponseStrength * 0.5f; // Dampen conversion

                // Update speed in forward direction based on how aligned force is with forward dir
                float alignment = forceDir.dot(forwardDir);
                ship.physics.currentSpeed += alignment * speedChange;

                // Clamp speed to max
                ship.physics.currentSpeed = Math.max(-config.maxSpeed, Math.min(ship.physics.currentSpeed, config.maxSpeed));

                // Also apply direct positional push for immediate response
                Location vehicleLoc = ship.vehicle.getLocation();
                vehicleLoc.add(
                    forceDir.x * config.collisionResponseStrength * 0.3f,
                    0,
                    forceDir.z * config.collisionResponseStrength * 0.3f
                );
                ship.vehicle.teleport(vehicleLoc);
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
            if (lateralForce.lengthSquared() > 0.001f) {
                Location vehicleLoc = ship.vehicle.getLocation();
                vehicleLoc.add(
                    lateralForce.x * config.collisionResponseStrength,
                    0, // No Y movement
                    lateralForce.z * config.collisionResponseStrength
                );
                ship.vehicle.teleport(vehicleLoc);
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
     * Get the mass of an entity for collision physics calculations.
     */
    private float getMassForEntity(Entity entity) {
        ShipConfig config = ship.config;

        // Boats and minecarts
        if (entity instanceof Boat || entity instanceof Minecart) {
            return config.boatMass;
        }

        // Check if entity is a living entity
        if (!(entity instanceof LivingEntity)) {
            return config.mobMediumMass; // Default for non-living entities
        }

        LivingEntity living = (LivingEntity) entity;

        // Large mobs
        if (living instanceof org.bukkit.entity.IronGolem ||
            living instanceof org.bukkit.entity.Warden ||
            living instanceof org.bukkit.entity.Ravager ||
            living instanceof org.bukkit.entity.EnderDragon ||
            living instanceof org.bukkit.entity.Wither) {
            return config.mobLargeMass;
        }

        // Small mobs
        if (living instanceof org.bukkit.entity.Chicken ||
            living instanceof org.bukkit.entity.Rabbit ||
            living instanceof org.bukkit.entity.Bat ||
            living instanceof org.bukkit.entity.Parrot ||
            living instanceof org.bukkit.entity.Silverfish) {
            return config.mobSmallMass;
        }

        // Medium mobs (default)
        // This includes players, zombies, skeletons, cows, horses, etc.
        return config.mobMediumMass;
    }

    /**
     * Calculate collision force from terrain (blocks).
     */
    private Vector3f calculateTerrainCollisionForce(CollisionBox cb) {
        org.bukkit.util.BoundingBox shulkerBox = cb.entity.getBoundingBox();
        World world = cb.entity.getWorld();
        Vector3f totalForce = new Vector3f(0, 0, 0);
        int collisionCount = 0;
        ShipConfig config = ship.config;

        // Check all blocks within the shulker's bounding box
        int minX = (int) Math.floor(shulkerBox.getMinX());
        int maxX = (int) Math.ceil(shulkerBox.getMaxX());
        int minY = (int) Math.floor(shulkerBox.getMinY());
        int maxY = (int) Math.ceil(shulkerBox.getMaxY());
        int minZ = (int) Math.floor(shulkerBox.getMinZ());
        int maxZ = (int) Math.ceil(shulkerBox.getMaxZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);

                    // Skip non-solid blocks
                    if (!block.getType().isSolid() || block.getType() == Material.WATER) {
                        continue;
                    }

                    // Get block bounding box
                    org.bukkit.util.BoundingBox blockBox = new org.bukkit.util.BoundingBox(
                        x, y, z,
                        x + 1, y + 1, z + 1
                    );

                    // Check if shulker overlaps with block
                    if (shulkerBox.overlaps(blockBox)) {
                        // Calculate penetration depth and separation normal
                        Vector3f force = calculatePenetrationForce(shulkerBox, blockBox);
                        if (force.lengthSquared() > config.minPenetrationDepth * config.minPenetrationDepth) {
                            // Scale force with ship speed (minimum 1.0 to preserve slow-ship behavior)
                            float speedFactor = Math.max(1.0f, Math.abs(ship.physics.currentSpeed) * config.terrainSpeedMultiplier);
                            totalForce.add(force.mul(config.terrainCollisionStrength * speedFactor));
                            collisionCount++;
                        }
                    }
                }
            }
        }

        // Average the force if multiple collisions
        if (collisionCount > 0) {
            totalForce.div(collisionCount);
        }

        return totalForce;
    }

    /**
     * Calculate collision force from an entity.
     */
    private Vector3f calculateEntityCollisionForce(CollisionBox cb, Entity otherEntity) {
        org.bukkit.util.BoundingBox shulkerBox = cb.entity.getBoundingBox();
        org.bukkit.util.BoundingBox entityBox = otherEntity.getBoundingBox();
        ShipConfig config = ship.config;

        // Check overlap
        if (!shulkerBox.overlaps(entityBox)) {
            return new Vector3f(0, 0, 0);
        }

        // Calculate penetration force
        Vector3f penetrationForce = calculatePenetrationForce(shulkerBox, entityBox);

        // Check if penetration is significant enough
        if (penetrationForce.lengthSquared() < config.minPenetrationDepth * config.minPenetrationDepth) {
            return new Vector3f(0, 0, 0);
        }

        // Apply momentum-based physics
        float entityMass = getMassForEntity(otherEntity);
        float collisionShipMass = config.shipMass;

        // Get entity velocity
        org.bukkit.util.Vector entityVelocity = otherEntity.getVelocity();
        float entitySpeed = (float) Math.sqrt(
            entityVelocity.getX() * entityVelocity.getX() +
            entityVelocity.getZ() * entityVelocity.getZ()
        );

        // Calculate force based on mass ratio and relative velocity
        // F = (m1 * v1 - m2 * v2) / (m1 + m2)
        float massRatio = entityMass / (collisionShipMass + entityMass);
        float forceMultiplier = massRatio * (entitySpeed + Math.abs(ship.physics.currentSpeed));

        return penetrationForce.mul(forceMultiplier);
    }

    /**
     * Calculate collision force from another ship.
     */
    private Vector3f calculateShipCollisionForce(CollisionBox cb, ShipInstance otherShip, CollisionBox otherCb) {
        org.bukkit.util.BoundingBox thisBox = cb.entity.getBoundingBox();
        org.bukkit.util.BoundingBox otherBox = otherCb.entity.getBoundingBox();
        ShipConfig config = ship.config;

        // Check overlap
        if (!thisBox.overlaps(otherBox)) {
            return new Vector3f(0, 0, 0);
        }

        // Calculate penetration force
        Vector3f penetrationForce = calculatePenetrationForce(thisBox, otherBox);

        // Check if penetration is significant enough
        if (penetrationForce.lengthSquared() < config.minPenetrationDepth * config.minPenetrationDepth) {
            return new Vector3f(0, 0, 0);
        }

        // Apply momentum-based physics for ship-to-ship collision
        float thisMass = config.shipMass;
        float otherMass = otherShip.config.shipMass;
        float thisSpeed = Math.abs(ship.physics.currentSpeed);
        float otherSpeed = Math.abs(otherShip.physics.currentSpeed);

        // Calculate force based on mass and velocity
        // F = (m1 * v1 - m2 * v2) / (m1 + m2)
        float massRatio = otherMass / (thisMass + otherMass);
        float forceMultiplier = massRatio * (otherSpeed + thisSpeed) * 2.0f; // Amplify ship collisions

        Vector3f force = penetrationForce.mul(forceMultiplier);

        // Apply reaction force to other ship (wake it up if stationary)
        Vector3f reactionForce = new Vector3f(force).negate();
        otherShip.physics.collisionForce.add(reactionForce);

        return force;
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

        // Find minimum penetration axis (separation direction)
        Vector3f separationNormal = new Vector3f(0, 0, 0);
        float penetrationDepth;

        if (xPenetration < yPenetration && xPenetration < zPenetration) {
            // Separate along X axis
            separationNormal.x = (thisBox.getCenterX() > otherBox.getCenterX()) ? 1 : -1;
            penetrationDepth = (float) xPenetration;
        } else if (yPenetration < zPenetration) {
            // Separate along Y axis
            separationNormal.y = (thisBox.getCenterY() > otherBox.getCenterY()) ? 1 : -1;
            penetrationDepth = (float) yPenetration;
        } else {
            // Separate along Z axis
            separationNormal.z = (thisBox.getCenterZ() > otherBox.getCenterZ()) ? 1 : -1;
            penetrationDepth = (float) zPenetration;
        }

        // Return push force (normal * depth)
        return separationNormal.mul(penetrationDepth);
    }
}
