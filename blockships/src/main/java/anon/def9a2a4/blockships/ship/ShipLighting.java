package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShipModel;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
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
    private final List<int[]> placedLights = new ArrayList<>(32);  // {x, y, z} coordinates

    // Tracking for threshold-based updates (reuse vector to reduce GC)
    private final Vector3f lastUpdatePos = new Vector3f();
    private boolean hasLastUpdate = false;
    private float lastUpdateYaw = Float.NaN;

    // Configuration (loaded from plugin config)
    private final boolean enabled;
    private final int maxLights;
    private final float updateThresholdBlocks;
    private final float updateThresholdRotation;

    // Array from block index to CollisionBox for fast lookup (indices are dense)
    private CollisionBox[] blockIndexToCollider;

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
        this.updateThresholdRotation = (float) config.getDouble("custom-ships.lighting.update-threshold-rotation", 3.0);

        // Note: blockIndexToCollider is built lazily in placeLights()
        // because colliders may not be spawned yet when this constructor is called
    }

    /**
     * Builds an array from block index to CollisionBox for fast lookup.
     * Called lazily on first use since colliders may not exist at construction time.
     */
    private void buildColliderArray() {
        blockIndexToCollider = new CollisionBox[ship.model.parts.size()];
        for (CollisionBox cb : ship.colliders) {
            blockIndexToCollider[cb.blockIndex] = cb;
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

        // Place new lights first, then remove old ones (reduces flickering)
        List<int[]> oldLights = new ArrayList<>(placedLights);
        placedLights.clear();
        World world = vehicleLoc.getWorld();
        placeLights(world);
        removeOldLights(oldLights, world);

        // Track last update position (reuse vector)
        lastUpdatePos.set((float) vehicleLoc.getX(), (float) vehicleLoc.getY(), (float) vehicleLoc.getZ());
        lastUpdateYaw = currentYaw;
        hasLastUpdate = true;
    }

    /**
     * Forces an immediate light update regardless of movement thresholds.
     * Useful after teleportation or other instant position changes.
     */
    public void forceUpdate() {
        hasLastUpdate = false;
        update();
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

        // Build collider array lazily (colliders aren't available at construction time)
        if (blockIndexToCollider == null) {
            buildColliderArray();
        }

        // Cache chunk loaded status to avoid redundant checks
        Map<Long, Boolean> chunkLoadedCache = new HashMap<>();

        int placed = 0;
        for (ShipModel.LightSource source : ship.model.lightSources) {
            if (placed >= maxLights) {
                break;
            }

            // Get the collider for this light source's target shulker
            CollisionBox collider = blockIndexToCollider[source.targetShulkerBlockIndex];
            if (collider == null || collider.entity == null || !collider.entity.isValid()) {
                continue;  // No valid shulker for this light
            }

            // Get shulker center position (getLocation returns feet, add 0.5 for center)
            Location shulkerLoc = collider.entity.getLocation().add(0, 0.5, 0);

            // Skip if chunk not loaded (use cache to avoid redundant world queries)
            int chunkX = shulkerLoc.getBlockX() >> 4;
            int chunkZ = shulkerLoc.getBlockZ() >> 4;
            long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            boolean chunkLoaded = chunkLoadedCache.computeIfAbsent(chunkKey,
                    k -> world.isChunkLoaded(chunkX, chunkZ));
            if (!chunkLoaded) {
                continue;
            }

            Block block = shulkerLoc.getBlock();
            Material blockType = block.getType();

            // Place if target block is air or water (waterlogged light)
            if (blockType.isAir() || blockType == Material.WATER) {
                try {
                    BlockData lightData = Material.LIGHT.createBlockData();
                    if (lightData instanceof Light light) {
                        light.setLevel(source.lightLevel);
                        if (blockType == Material.WATER) {
                            light.setWaterlogged(true);
                        }
                    }
                    block.setBlockData(lightData, false);
                    placedLights.add(new int[]{block.getX(), block.getY(), block.getZ()});
                    placed++;
                } catch (Exception e) {
                    // Silently ignore errors
                }
            }
        }
    }

    /**
     * Removes all LIGHT blocks that were placed by this manager.
     */
    public void removePlacedLights() {
        World world = ship.vehicle != null && ship.vehicle.isValid() ? ship.vehicle.getWorld() : null;
        if (world != null) {
            for (int[] pos : placedLights) {
                try {
                    if (world.isChunkLoaded(pos[0] >> 4, pos[2] >> 4)) {
                        Block block = world.getBlockAt(pos[0], pos[1], pos[2]);
                        if (block.getType() == Material.LIGHT) {
                            BlockData data = block.getBlockData();
                            if (data instanceof Light light && light.isWaterlogged()) {
                                block.setType(Material.WATER, false);
                            } else {
                                block.setType(Material.AIR, false);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Silently ignore
                }
            }
        }
        placedLights.clear();
    }

    /**
     * Removes LIGHT blocks at the specified positions.
     * Skips positions that have new lights placed (to avoid removing lights we just placed).
     */
    private void removeOldLights(List<int[]> positions, World world) {
        for (int[] pos : positions) {
            // Skip if this position has a new light placed
            if (hasLightAt(pos[0], pos[1], pos[2])) {
                continue;
            }
            try {
                if (world.isChunkLoaded(pos[0] >> 4, pos[2] >> 4)) {
                    Block block = world.getBlockAt(pos[0], pos[1], pos[2]);
                    if (block.getType() == Material.LIGHT) {
                        // Restore water if the light was waterlogged
                        BlockData data = block.getBlockData();
                        if (data instanceof Light light && light.isWaterlogged()) {
                            block.setType(Material.WATER, false);
                        } else {
                            block.setType(Material.AIR, false);
                        }
                    }
                }
            } catch (Exception e) {
                // Silently ignore errors
            }
        }
    }

    /**
     * Checks if placedLights contains a light at the same block position.
     */
    private boolean hasLightAt(int x, int y, int z) {
        for (int[] pos : placedLights) {
            if (pos[0] == x && pos[1] == y && pos[2] == z) {
                return true;
            }
        }
        return false;
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
