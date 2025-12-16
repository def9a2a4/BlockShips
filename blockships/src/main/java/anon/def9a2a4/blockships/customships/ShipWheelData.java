package anon.def9a2a4.blockships.customships;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Shulker;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks data for a placed ship wheel block.
 * Associates a player head block with its orientation and optional assembled ship.
 */
public class ShipWheelData {
    private Location blockLocation;
    private BlockFace facing;
    private UUID assembledShipUUID;  // UUID of ship if assembled, null if not

    // Detection preview data
    private Set<Location> lastDetectedBlocks;  // Blocks from last detection preview
    private BukkitTask particleTask;  // Active particle visualization task
    private int lastDetectedBlockCount;  // Block count from last detection
    private int lastDetectedWeight;  // Total weight from last detection
    private int lastDetectedPositiveWeight;  // Positive weight sum (for health calculation)

    // Categorized blocks for colored particle visualization
    private Set<Location> lastDetectedRegularBlocks;  // Non-seat blocks (white particles)
    private Set<Location> lastDetectedSeatBlocks;     // Passenger seat blocks (orange particles)
    private Location lastDetectedDriverSeat;          // Driver seat block (red particles)
    private int lastDetectedSeatCount;                // Total seat count

    // Waterline visualization shulker (glowing, shows predicted waterline during detection)
    private Shulker waterlineShulker;

    // Last disassembly conflict info (for showing force option after failure)
    private BlockStructureScanner.PlacementConflicts lastDisassemblyConflicts;

    // Flag to prevent clearing conflicts when menu is about to reopen
    private boolean pendingMenuReopen;

    // Health tracking for assembled ships (for Ship Info display)
    private double lastCurrentHealth;  // Current health when assembled
    private double lastMaxHealth;      // Max health when assembled

    // Buoyancy calculation data (for Ship Info display)
    public float lastCenterOfVolumeY;  // Y component of center of volume
    public float lastMinY;             // Bottom of ship relative to wheel
    public float lastSurfaceOffset;    // Calculated surface offset

    public ShipWheelData(Location blockLocation, BlockFace facing) {
        this.blockLocation = blockLocation.clone();
        this.facing = facing;
        this.assembledShipUUID = null;
        this.lastDetectedBlocks = null;
        this.particleTask = null;
        this.lastDetectedBlockCount = 0;
        this.lastDetectedWeight = 0;
    }

    public Location getBlockLocation() {
        return blockLocation.clone();
    }

    public BlockFace getFacing() {
        return facing;
    }

    /**
     * Updates the wheel's block location and facing direction.
     * Used when a ship is disassembled at a different location than where it was assembled.
     */
    public void updateBlockLocation(Location newLocation, BlockFace newFacing) {
        this.blockLocation = newLocation.clone();
        this.facing = newFacing;
    }

    public UUID getAssembledShipUUID() {
        return assembledShipUUID;
    }

    public void setAssembledShipUUID(UUID shipUUID) {
        this.assembledShipUUID = shipUUID;
    }

    public boolean isAssembled() {
        return assembledShipUUID != null;
    }

    public Set<Location> getLastDetectedBlocks() {
        return lastDetectedBlocks;
    }

    public void setLastDetectedBlocks(Set<Location> blocks) {
        this.lastDetectedBlocks = blocks;
    }

    public int getLastDetectedBlockCount() {
        return lastDetectedBlockCount;
    }

    public int getLastDetectedWeight() {
        return lastDetectedWeight;
    }

    public int getLastDetectedPositiveWeight() {
        return lastDetectedPositiveWeight;
    }

    public void setLastDetectedStats(int blockCount, int totalWeight, int positiveWeight) {
        this.lastDetectedBlockCount = blockCount;
        this.lastDetectedWeight = totalWeight;
        this.lastDetectedPositiveWeight = positiveWeight;
    }

    public Set<Location> getLastDetectedRegularBlocks() {
        return lastDetectedRegularBlocks;
    }

    public Set<Location> getLastDetectedSeatBlocks() {
        return lastDetectedSeatBlocks;
    }

    public Location getLastDetectedDriverSeat() {
        return lastDetectedDriverSeat;
    }

    public int getLastDetectedSeatCount() {
        return lastDetectedSeatCount;
    }

    public void setLastDetectedBlockCategories(Set<Location> regularBlocks, Set<Location> seatBlocks, Location driverSeat) {
        this.lastDetectedRegularBlocks = regularBlocks;
        this.lastDetectedSeatBlocks = seatBlocks;
        this.lastDetectedDriverSeat = driverSeat;
        this.lastDetectedSeatCount = seatBlocks.size() + (driverSeat != null ? 1 : 0);
    }

    public double getLastCurrentHealth() {
        return lastCurrentHealth;
    }

    public double getLastMaxHealth() {
        return lastMaxHealth;
    }

    public void setLastHealth(double currentHealth, double maxHealth) {
        this.lastCurrentHealth = currentHealth;
        this.lastMaxHealth = maxHealth;
    }

    public BukkitTask getParticleTask() {
        return particleTask;
    }

    public void setParticleTask(BukkitTask task) {
        // Cancel existing task if any
        if (this.particleTask != null && !this.particleTask.isCancelled()) {
            this.particleTask.cancel();
        }
        this.particleTask = task;
    }

    public void cancelParticleTask() {
        if (this.particleTask != null && !this.particleTask.isCancelled()) {
            this.particleTask.cancel();
        }
        this.particleTask = null;
        this.lastDetectedBlocks = null;

        // Also remove waterline shulker
        removeWaterlineShulker();
    }

    public Shulker getWaterlineShulker() {
        return waterlineShulker;
    }

    public void setWaterlineShulker(Shulker shulker) {
        // Remove existing shulker if any
        removeWaterlineShulker();
        this.waterlineShulker = shulker;
    }

    public void removeWaterlineShulker() {
        if (this.waterlineShulker != null && !this.waterlineShulker.isDead()) {
            this.waterlineShulker.remove();
        }
        this.waterlineShulker = null;
    }

    public BlockStructureScanner.PlacementConflicts getLastDisassemblyConflicts() {
        return lastDisassemblyConflicts;
    }

    public void setLastDisassemblyConflicts(BlockStructureScanner.PlacementConflicts conflicts) {
        this.lastDisassemblyConflicts = conflicts;
    }

    /**
     * Checks if force disassembly is available (failed disassembly with conflicts).
     */
    public boolean canForceDisassemble() {
        return lastDisassemblyConflicts != null && lastDisassemblyConflicts.total() > 0;
    }

    public boolean isPendingMenuReopen() {
        return pendingMenuReopen;
    }

    public void setPendingMenuReopen(boolean pending) {
        this.pendingMenuReopen = pending;
    }

    /**
     * Snaps a yaw angle to the nearest 90-degree increment (0, 90, 180, 270)
     */
    public static float snapToNearestCardinal(float yaw) {
        // Normalize yaw to 0-360 range
        yaw = yaw % 360;
        if (yaw < 0) yaw += 360;

        // Round to nearest 90 degrees
        int cardinal = Math.round(yaw / 90.0f) * 90;
        return cardinal % 360;
    }

    /**
     * Converts a yaw angle to a BlockFace (cardinal directions only)
     */
    public static BlockFace yawToBlockFace(float yaw) {
        yaw = snapToNearestCardinal(yaw);

        if (yaw >= 315 || yaw < 45) {
            return BlockFace.SOUTH;  // 0 degrees
        } else if (yaw >= 45 && yaw < 135) {
            return BlockFace.WEST;   // 90 degrees
        } else if (yaw >= 135 && yaw < 225) {
            return BlockFace.NORTH;  // 180 degrees
        } else {
            return BlockFace.EAST;   // 270 degrees
        }
    }

    /**
     * Converts a BlockFace to a yaw angle
     */
    public static float blockFaceToYaw(BlockFace face) {
        switch (face) {
            case SOUTH:
                return 0.0f;
            case WEST:
                return 90.0f;
            case NORTH:
                return 180.0f;
            case EAST:
                return 270.0f;
            default:
                return 0.0f;
        }
    }

    // ===== Serialization for persistence =====

    /**
     * Serializes this wheel data to a map for YAML storage.
     * Only persists essential data (location, facing, ship link).
     * Transient data (detection preview, particles) is not persisted.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("world", blockLocation.getWorld().getName());
        map.put("x", blockLocation.getBlockX());
        map.put("y", blockLocation.getBlockY());
        map.put("z", blockLocation.getBlockZ());
        map.put("facing", facing.name());
        if (assembledShipUUID != null) {
            map.put("ship_uuid", assembledShipUUID.toString());
        }
        return map;
    }

    /**
     * Deserializes wheel data from a map loaded from YAML.
     * @param map The serialized data
     * @return The deserialized ShipWheelData, or null if world doesn't exist
     */
    public static ShipWheelData fromMap(Map<String, Object> map) {
        String worldName = (String) map.get("world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;  // World doesn't exist (deleted/renamed)
        }

        Location loc = new Location(world,
            ((Number) map.get("x")).intValue(),
            ((Number) map.get("y")).intValue(),
            ((Number) map.get("z")).intValue());
        BlockFace facing = BlockFace.valueOf((String) map.get("facing"));

        ShipWheelData data = new ShipWheelData(loc, facing);

        if (map.containsKey("ship_uuid")) {
            data.setAssembledShipUUID(UUID.fromString((String) map.get("ship_uuid")));
        }

        return data;
    }
}
