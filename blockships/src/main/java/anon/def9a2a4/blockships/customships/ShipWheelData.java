package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.ShipTags;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;;

/**
 * Tracks data for a placed ship wheel block.
 * Associates a player head block with its orientation and optional assembled ship.
 */
public class ShipWheelData {
    /**
     * Safely parses a BlockFace from a map, returning a default value on failure.
     */
    private static BlockFace safeBlockFace(Map<?, ?> map, String key, BlockFace defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        try {
            return BlockFace.valueOf(val.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    /**
     * This wheel's identity, mirrored onto the block itself as {@code blockships:wheel_id}.
     *
     * <p>The block's copy is the authoritative one; this record's {@link #blockLocation} is a cache of where
     * that block currently is. Minted here so a wheel always has an id even before its block is stamped, and
     * so an entry loaded from a pre-identity {@code ship_wheels.yml} gets one for free.
     */
    private UUID wheelId = UUID.randomUUID();

    private Location blockLocation;
    private BlockFace facing;
    private UUID assembledShipUUID;  // UUID of ship if assembled, null if not

    // Detection preview data
    private Set<Location> lastDetectedBlocks;  // Blocks from last detection preview
    private BukkitTask particleTask;  // Active particle visualization task
    private int lastDetectedBlockCount;  // Block count from last detection
    private int lastDetectedWeightedBlockCount;  // Blocks with assigned weight (for density)
    private int lastDetectedWeight;  // Total weight from last detection
    private int lastDetectedPositiveWeight;  // Positive weight sum (for health calculation)
    private int lastDetectedWoolCount;   // Wool blocks (for ship stats)
    private int lastDetectedBannerCount; // Banner blocks (for ship stats)
    // bbanners display-entity banners. Not findable by material, so they come from an entity query
    // (BlockStructureScanner.countLargeHuge) rather than the block walk that fills the two above.
    private int lastDetectedLargeBannerCount;
    private int lastDetectedHugeBannerCount;

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

    // Camera distance setting (persisted per-ship)
    // -1 means "not set, use calculated default based on block count"
    private float cameraDistance = -1;

    // When true, this wheel's ship is "locked": membership is frozen to its glue offsets (stored on the wheel
    // skull by defCoreLib) and the natural allow-list flood fill is disabled, so docking it next to a pile of
    // dirt no longer swallows the dirt. The frozen cells live in the glue store, not here. False = unlocked.
    private boolean naturalFrozen;

    /**
     * The name of {@link #blockLocation}'s world, captured whenever that location is set.
     *
     * <p>Exists so {@link #toMap} cannot throw. A {@code Location} holds a WEAK reference to its world, so
     * {@code getWorld()} raises {@code IllegalArgumentException("World unloaded")} once that reference is
     * collected — and {@code toMap} was reading it. A single wheel in a world unloaded at runtime therefore
     * failed to serialise, and {@code saveAll}'s per-row catch dropped it: the record was PERMANENTLY
     * DELETED from disk on the next save, while the log claimed the previous row had been preserved.
     *
     * <p>Every other field {@code toMap} writes is world-independent, so caching this one name makes the
     * whole serialisation total for the unloaded-world case. It is a name rather than a {@code World} on
     * purpose — a name survives an unload/reload cycle, whereas the {@code World} object does not.
     *
     * <p>Null only if the location handed in had no live world to begin with, which {@code toMap} then
     * reports rather than throwing.
     */
    private @org.jetbrains.annotations.Nullable String worldName;

    public ShipWheelData(Location blockLocation, BlockFace facing) {
        this.blockLocation = blockLocation.clone();
        // Via LocationUtil, not a bare getWorld(): otherwise this just moves the throw from save time to
        // placement/load time. Both current callers hand in a live location, but nothing enforces that.
        this.worldName = anon.def9a2a4.blockships.util.LocationUtil.worldName(blockLocation);
        this.facing = facing;
        this.assembledShipUUID = null;
        this.lastDetectedBlocks = null;
        this.particleTask = null;
        this.lastDetectedBlockCount = 0;
        this.lastDetectedWeight = 0;
    }

    /** This wheel's identity. Never null. Mirrored onto the block as {@code blockships:wheel_id}. */
    public UUID getWheelId() {
        return wheelId;
    }

    /** Only for {@link #fromMap} (adopting a persisted id) and duplicate re-minting at load. */
    void setWheelId(UUID wheelId) {
        if (wheelId != null) this.wheelId = wheelId;
    }

    public Location getBlockLocation() {
        return blockLocation.clone();
    }

    public BlockFace getFacing() {
        return facing;
    }

    /**
     * Updates the wheel's cached block location and facing direction, after the ship lands somewhere other
     * than where it was assembled.
     *
     * <p>Package-private on purpose: {@code blockLocation} is a cache of where the block currently is, and
     * {@code ShipWheelManager.relocate} is its only legitimate writer. A caller that moved it directly would
     * desync the cache from the block's {@code blockships:wheel_id} PDC with nothing to notice.
     */
    void updateBlockLocation(Location newLocation, BlockFace newFacing) {
        this.blockLocation = newLocation.clone();
        this.facing = newFacing;
        // Keep worldName in step with the only other writer of blockLocation. Guarded: a relocation into a
        // world that has since gone keeps the last known name rather than nulling it, so the row still
        // serialises to somewhere real.
        String w = anon.def9a2a4.blockships.util.LocationUtil.worldName(newLocation);
        if (w != null) this.worldName = w;
    }

    /**
     * The name of the world this wheel's cell is in, or null if it never had a live one.
     *
     * <p>Survives that world being unloaded, unlike {@code getBlockLocation().getWorld()}. Use this for
     * anything that only needs to NAME the world — cache keys, serialised rows, operator output — and
     * {@code LocationUtil.liveWorld} for anything that needs to touch it.
     */
    public @org.jetbrains.annotations.Nullable String getWorldName() {
        return worldName;
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

    public void setLastDetectedStats(int blockCount, int weightedBlockCount, int totalWeight,
                                     int positiveWeight, int woolCount, int bannerCount,
                                     int largeBannerCount, int hugeBannerCount) {
        this.lastDetectedBlockCount = blockCount;
        this.lastDetectedWeightedBlockCount = weightedBlockCount;
        this.lastDetectedWeight = totalWeight;
        this.lastDetectedPositiveWeight = positiveWeight;
        this.lastDetectedWoolCount = woolCount;
        this.lastDetectedBannerCount = bannerCount;
        this.lastDetectedLargeBannerCount = largeBannerCount;
        this.lastDetectedHugeBannerCount = hugeBannerCount;
    }

    public int getLastDetectedWeightedBlockCount() {
        return lastDetectedWeightedBlockCount;
    }

    public int getLastDetectedWoolCount() {
        return lastDetectedWoolCount;
    }

    public int getLastDetectedBannerCount() {
        return lastDetectedBannerCount;
    }

    public int getLastDetectedLargeBannerCount() {
        return lastDetectedLargeBannerCount;
    }

    public int getLastDetectedHugeBannerCount() {
        return lastDetectedHugeBannerCount;
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
     * Gets the configured camera distance for this ship.
     * @return The camera distance, or -1 if not set (use calculated default)
     */
    public float getCameraDistance() {
        return cameraDistance;
    }

    /**
     * Sets the camera distance for this ship.
     * @param distance The camera distance (4-32), or -1 to use calculated default
     */
    public void setCameraDistance(float distance) {
        this.cameraDistance = distance;
    }

    /** True when natural allow-list spread is frozen (membership = the wheel's glue offsets). */
    public boolean isLocked() {
        return naturalFrozen;
    }

    /** Freeze (true) or unfreeze (false) natural spread. Callers must {@code saveAll()}. */
    public void setNaturalFrozen(boolean frozen) {
        this.naturalFrozen = frozen;
    }

    /**
     * Calculates a default camera distance based on the number of blocks in the ship.
     * Scales from 4 (small ships) to ~16 (large ships), capped at 20.
     * @param blockCount The number of blocks in the ship
     * @return The calculated default camera distance
     */
    public static float calculateDefaultCameraDistance(int blockCount) {
        // Formula: 4 + sqrt(blockCount) * 0.5, clamped to [4, 20]
        return Math.min(20f, Math.max(4f, 4f + (float) Math.sqrt(blockCount) * 0.5f));
    }

    /**
     * Snaps a yaw angle to the nearest 90-degree increment (0, 90, 180, 270)
     */
    public static float snapToNearestCardinal(float yaw) {
        // Normalize yaw to 0-360 range
        yaw = ShipTags.normalizeYaw(yaw);

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
        // TOTAL: this must not throw for any reachable state of this object. saveAll's per-row catch turns a
        // throw here into permanent deletion of the row from disk, so "cannot serialise" and "should not
        // exist" would become the same thing. Every read below is either a cached primitive or null-guarded.
        Map<String, Object> map = new HashMap<>();
        map.put("wheel_id", wheelId.toString());
        // The cached name, NOT blockLocation.getWorld() — see the worldName field. Falling back to a live
        // read covers a record built before the cache existed; if both are absent the row is written
        // world-less and fromMap quarantines it, which is recoverable, unlike deleting it.
        String w = worldName != null
            ? worldName : anon.def9a2a4.blockships.util.LocationUtil.worldName(blockLocation);
        if (w != null) map.put("world", w);
        map.put("x", blockLocation.getBlockX());
        map.put("y", blockLocation.getBlockY());
        map.put("z", blockLocation.getBlockZ());
        // facing is non-null on every current path, but nothing enforces it and an NPE here costs the row.
        map.put("facing", (facing == null ? BlockFace.NORTH : facing).name());
        if (assembledShipUUID != null) {
            map.put("ship_uuid", assembledShipUUID.toString());
        }
        // Only save camera distance if explicitly set (not -1)
        if (cameraDistance >= 0) {
            map.put("camera_distance", cameraDistance);
        }
        if (naturalFrozen) {
            map.put("locked", true);
        }
        return map;
    }

    /**
     * Deserializes wheel data from a map loaded from YAML.
     * @param map The serialized data
     * @return The deserialized ShipWheelData, or null if world doesn't exist
     */
    public static ShipWheelData fromMap(Map<String, Object> map) {
        // Null-checked before the lookup: Bukkit.getWorld(String) lower-cases its argument and so NPEs on
        // null, and toMap now omits the key entirely rather than throwing when it has no world to name.
        Object rawWorld = map.get("world");
        World world = rawWorld instanceof String s ? Bukkit.getWorld(s) : null;
        if (world == null) {
            return null;  // World doesn't exist (deleted/renamed), or was never recorded
        }

        Location loc = new Location(world,
            ((Number) map.get("x")).intValue(),
            ((Number) map.get("y")).intValue(),
            ((Number) map.get("z")).intValue());
        BlockFace facing = safeBlockFace(map, "facing", BlockFace.NORTH);

        ShipWheelData data = new ShipWheelData(loc, facing);

        // Absent for every wheel written before block identity existed; the constructor already minted one,
        // so those simply keep the fresh id and get stamped onto their block the first time we look at it.
        Object rawId = map.get("wheel_id");
        if (rawId instanceof String s) {
            try {
                data.setWheelId(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                Bukkit.getLogger().warning("[BlockShips] Wheel at " + loc + " had an unreadable wheel_id ("
                    + s + "); minting a new one.");
            }
        }

        if (map.containsKey("ship_uuid")) {
            data.setAssembledShipUUID(UUID.fromString((String) map.get("ship_uuid")));
        }

        // Load camera distance if present (backwards compatible - defaults to -1 if missing)
        if (map.containsKey("camera_distance")) {
            data.setCameraDistance(((Number) map.get("camera_distance")).floatValue());
        }

        // Lock flag. New format is a plain boolean — the frozen cells live in the wheel's glue store, not here.
        // A legacy LockedStructure blob (Map/section) loads as UNLOCKED: the packed store was retired, so those
        // ships re-lock once. Absent (every pre-lock wheel) = unlocked.
        Object lockedRaw = map.get("locked");
        if (lockedRaw instanceof Boolean b) {
            data.setNaturalFrozen(b);
        } else if (lockedRaw instanceof Map || lockedRaw instanceof org.bukkit.configuration.ConfigurationSection) {
            Bukkit.getLogger().info("[BlockShips] Wheel at " + loc + " carried a legacy locked-structure; "
                + "loading it unlocked (re-lock from the wheel menu to freeze it again).");
        }

        return data;
    }
}
