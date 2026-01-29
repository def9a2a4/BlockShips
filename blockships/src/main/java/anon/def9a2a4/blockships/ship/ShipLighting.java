package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShipModel;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages fake lighting for ships using invisible LIGHT blocks.
 * Since ships use display entities (not real blocks), light-emitting blocks
 * don't actually emit light. This class places real LIGHT blocks at
 * shulker positions to simulate lighting.
 */
public class ShipLighting {
    private final ShipInstance ship;
    private final List<Location> placedLights = new ArrayList<>();

    // Tracking for threshold-based updates (reuse vector to reduce GC)
    private final Vector3f lastUpdatePos = new Vector3f();
    private boolean hasLastUpdate = false;
    private float lastUpdateYaw = Float.NaN;

    // Configuration (loaded from plugin config)
    private final boolean enabled;
    private final int maxLights;
    private final float updateThresholdBlocks;
    private final float updateThresholdRotation;

    // Map from block index to CollisionBox for fast lookup
    private Map<Integer, CollisionBox> blockIndexToCollider;

    /**
     * Creates a new ShipLighting manager for the given ship.
     *
     * @param ship The ship instance to manage lighting for
     */
    public ShipLighting(ShipInstance ship) {
        this.ship = ship;

        // Load configuration
        var config = ship.plugin.getConfig();
        this.enabled = config.getBoolean("custom-ships.lighting.enabled", true);
        this.maxLights = config.getInt("custom-ships.lighting.max-lights-per-ship", 32);
        this.updateThresholdBlocks = (float) config.getDouble("custom-ships.lighting.update-threshold-blocks", 1.0);
        this.updateThresholdRotation = (float) config.getDouble("custom-ships.lighting.update-threshold-rotation", 10.0);

        // Note: blockIndexToCollider is built lazily in placeLights()
        // because colliders may not be spawned yet when this constructor is called
    }

    /**
     * Builds a map from block index to CollisionBox for fast lookup.
     * Called lazily on first use since colliders may not exist at construction time.
     */
    private void buildColliderMap() {
        blockIndexToCollider = new HashMap<>();
        for (CollisionBox cb : ship.colliders) {
            blockIndexToCollider.put(cb.blockIndex, cb);
        }
    }

    /**
     * Updates light block positions if the ship has moved significantly.
     * Called from ShipInstance.tick() after physics update.
     */
    public void update() {
        if (!enabled || ship.model.lightSources.isEmpty()) {
            return;
        }

        if (ship.vehicle == null || !ship.vehicle.isValid()) {
            return;
        }

        // Get location once per update cycle (reduces GC pressure)
        Location vehicleLoc = ship.vehicle.getLocation();
        float currentYaw = ship.vehicle.getYaw();

        if (!shouldUpdate(vehicleLoc, currentYaw)) {
            return;
        }

        // Remove old lights and place new ones
        int oldCount = placedLights.size();
        removePlacedLights();
        placeLights(vehicleLoc.getWorld());
        ship.plugin.getLogger().info("[ShipLighting] Updated: removed " + oldCount + ", placed " + placedLights.size() + " lights");

        // Track last update position (reuse vector)
        lastUpdatePos.set((float) vehicleLoc.getX(), (float) vehicleLoc.getY(), (float) vehicleLoc.getZ());
        lastUpdateYaw = currentYaw;
        hasLastUpdate = true;
    }

    /**
     * Checks if the ship has moved enough to warrant a light update.
     *
     * @param vehicleLoc Current vehicle location
     * @param currentYaw Current vehicle yaw
     * @return true if lights should be updated
     */
    private boolean shouldUpdate(Location vehicleLoc, float currentYaw) {
        // First update - always do it
        if (!hasLastUpdate) {
            return true;
        }

        float dx = (float) vehicleLoc.getX() - lastUpdatePos.x;
        float dy = (float) vehicleLoc.getY() - lastUpdatePos.y;
        float dz = (float) vehicleLoc.getZ() - lastUpdatePos.z;
        float distSq = dx * dx + dy * dy + dz * dz;

        // Check distance threshold
        if (distSq >= updateThresholdBlocks * updateThresholdBlocks) {
            return true;
        }

        // Check rotation threshold
        float yawDelta = Math.abs(currentYaw - lastUpdateYaw);
        // Handle wrap-around (e.g., from 350 to 10 degrees)
        if (yawDelta > 180) {
            yawDelta = 360 - yawDelta;
        }

        return yawDelta >= updateThresholdRotation;
    }

    /**
     * Places LIGHT blocks at shulker positions corresponding to ship light sources.
     *
     * @param world The world to place lights in
     */
    private void placeLights(World world) {
        if (world == null) {
            return;
        }

        // Build collider map lazily (colliders aren't available at construction time)
        if (blockIndexToCollider == null) {
            buildColliderMap();
        }

        int placed = 0;
        int skippedNoCollider = 0;
        int skippedNotAir = 0;
        for (ShipModel.LightSource source : ship.model.lightSources) {
            if (placed >= maxLights) {
                break;
            }

            // Get the collider for this light source's target shulker
            CollisionBox collider = blockIndexToCollider.get(source.targetShulkerBlockIndex);
            if (collider == null || collider.entity == null || !collider.entity.isValid()) {
                skippedNoCollider++;
                continue;  // No valid shulker for this light
            }

            // Get shulker center position (getLocation returns feet, add 0.5 for center)
            Location shulkerLoc = collider.entity.getLocation().add(0, 0.5, 0);

            // Skip if chunk not loaded (avoid forcing chunk loads at boundaries)
            if (!shulkerLoc.isChunkLoaded()) {
                continue;
            }

            Block block = shulkerLoc.getBlock();

            // Only place if target block is air (don't replace existing blocks)
            if (block.getType().isAir()) {
                try {
                    // Create LIGHT block with the appropriate level
                    BlockData lightData = Material.LIGHT.createBlockData();
                    if (lightData instanceof Light light) {
                        light.setLevel(source.lightLevel);
                    }
                    // Set block without physics update (false) to avoid cascading updates
                    block.setBlockData(lightData, false);
                    placedLights.add(shulkerLoc.clone());
                    placed++;
                } catch (Exception e) {
                    // Silently ignore errors (e.g., chunk not loaded)
                }
            } else {
                skippedNotAir++;
                ship.plugin.getLogger().info("[ShipLighting] Block at " + block.getX() + "," + block.getY() + "," + block.getZ() + " is " + block.getType());
            }
        }
        if (skippedNoCollider > 0 || skippedNotAir > 0) {
            ship.plugin.getLogger().info("[ShipLighting] Skipped: " + skippedNoCollider + " no collider, " + skippedNotAir + " not air (total sources: " + ship.model.lightSources.size() + ")");
        }
    }

    /**
     * Removes all LIGHT blocks that were placed by this manager.
     */
    public void removePlacedLights() {
        for (Location loc : placedLights) {
            try {
                if (loc.isChunkLoaded()) {
                    Block block = loc.getBlock();
                    // Only remove if it's still a LIGHT block (don't remove player-placed blocks)
                    if (block.getType() == Material.LIGHT) {
                        block.setType(Material.AIR, false);
                    }
                }
            } catch (Exception e) {
                // Silently ignore errors
            }
        }
        placedLights.clear();
    }

    /**
     * Cleans up all placed lights. Called when the ship is destroyed or suspended.
     */
    public void cleanup() {
        removePlacedLights();
        hasLastUpdate = false;
        lastUpdateYaw = Float.NaN;
    }

    /**
     * Gets the number of currently placed light blocks.
     *
     * @return The number of active light blocks
     */
    public int getPlacedLightCount() {
        return placedLights.size();
    }
}
