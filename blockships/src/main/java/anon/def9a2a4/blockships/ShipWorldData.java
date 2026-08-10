package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-world ship data storage for chunk-based loading.
 *
 * Storage structure:
 *   worlds/{worldName}/chunks.yml - Maps "x,z" -> list of ship UUIDs
 *   worlds/{worldName}/ships/{uuid}.yml - Individual ship metadata
 */
public class ShipWorldData {
    private final JavaPlugin plugin;
    private final File worldsFolder;

    // In-memory chunk indices: world name -> "x,z" -> list of ship UUIDs
    private final Map<String, Map<String, List<UUID>>> chunkIndices = new HashMap<>();

    // Cache of metadata existence checks (true = exists, false = doesn't exist)
    // Using ConcurrentHashMap for thread-safe access from chunk load events
    private final Map<String, Boolean> metadataExistsCache = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong lastCacheClear = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    private static final long CACHE_CLEAR_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    // Async I/O executor for non-blocking file operations
    private final java.util.concurrent.atomic.AtomicInteger pendingIOOperations = new java.util.concurrent.atomic.AtomicInteger(0);
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(
        r -> {
            Thread t = new Thread(r, "BlockShips-IO");
            t.setDaemon(true);
            return t;
        }
    );

    public ShipWorldData(JavaPlugin plugin) {
        this.plugin = plugin;
        this.worldsFolder = new File(plugin.getDataFolder(), "worlds");
    }

    // ===== Chunk Index Operations =====

    /**
     * Gets the list of ship UUIDs in a specific chunk.
     */
    public List<UUID> getShipsInChunk(World world, int chunkX, int chunkZ) {
        String key = chunkX + "," + chunkZ;
        Map<String, List<UUID>> worldIndex = chunkIndices.get(world.getName());
        if (worldIndex == null) return Collections.emptyList();
        List<UUID> ships = worldIndex.get(key);
        return ships != null ? new ArrayList<>(ships) : Collections.emptyList();
    }

    /**
     * Adds a ship to the chunk index.
     * Prevents duplicate entries for the same ship in the same chunk.
     */
    public void addToChunkIndex(World world, UUID shipId, int chunkX, int chunkZ) {
        String worldName = world.getName();
        String key = chunkX + "," + chunkZ;

        List<UUID> ships = chunkIndices.computeIfAbsent(worldName, k -> new HashMap<>())
                                       .computeIfAbsent(key, k -> new ArrayList<>());
        if (!ships.contains(shipId)) {
            ships.add(shipId);
        }
    }

    /**
     * Removes a ship from the chunk index.
     */
    public void removeFromChunkIndex(World world, UUID shipId, int chunkX, int chunkZ) {
        String worldName = world.getName();
        String key = chunkX + "," + chunkZ;

        Map<String, List<UUID>> worldIndex = chunkIndices.get(worldName);
        if (worldIndex == null) return;

        List<UUID> ships = worldIndex.get(key);
        if (ships != null) {
            ships.remove(shipId);
            if (ships.isEmpty()) {
                worldIndex.remove(key);
            }
        }
    }

    /**
     * Updates chunk index when a ship moves between chunks.
     */
    public void updateChunkIndex(World world, UUID shipId,
                                  int oldChunkX, int oldChunkZ,
                                  int newChunkX, int newChunkZ) {
        removeFromChunkIndex(world, shipId, oldChunkX, oldChunkZ);
        addToChunkIndex(world, shipId, newChunkX, newChunkZ);
    }

    // ===== Ship Metadata Operations =====

    /**
     * Saves ship metadata to per-world storage.
     * Does NOT include position - that comes from the recovered vehicle entity.
     */
    public void saveShipMetadata(ShipInstance ship) {
        World world = ship.vehicle.getLocation().getWorld();
        if (world == null) return;

        File shipFile = getShipFile(world.getName(), ship.id);
        shipFile.getParentFile().mkdirs();

        YamlConfiguration config = buildShipMetadataConfig(ship);
        config.set("entity_count", ship.countEntities());

        try {
            config.save(shipFile);
            // Populate cache on successful save
            metadataExistsCache.put(world.getName() + ":" + ship.id, true);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save ship metadata for " + ship.id + ": " + e.getMessage());
        }
    }

    /**
     * Saves ship metadata asynchronously: snapshots state on calling thread (must be main thread),
     * then writes YAML to disk on the IO executor thread.
     */
    public void saveShipMetadataAsync(ShipInstance ship) {
        World world = ship.vehicle.getLocation().getWorld();
        if (world == null) return;

        // Snapshot: build YamlConfiguration on main thread (all Bukkit API calls happen here)
        String worldName = world.getName();
        UUID shipId = ship.id;
        YamlConfiguration config = buildShipMetadataConfig(ship);
        int entityCount = ship.countEntities();
        config.set("entity_count", entityCount);

        // Write: file I/O on async thread
        pendingIOOperations.incrementAndGet();
        ioExecutor.submit(() -> {
            try {
                File shipFile = getShipFile(worldName, shipId);
                shipFile.getParentFile().mkdirs();
                config.save(shipFile);
                metadataExistsCache.put(worldName + ":" + shipId, true);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to async save ship metadata for " + shipId + ": " + e.getMessage());
            } finally {
                pendingIOOperations.decrementAndGet();
            }
        });
    }

    /**
     * Builds a YamlConfiguration with all ship metadata.
     * Must be called on the main thread (accesses Bukkit API).
     */
    private YamlConfiguration buildShipMetadataConfig(ShipInstance ship) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("id", ship.id.toString());
        config.set("ship_type", ship.shipType);
        // Engine marker: a delegated (mechanism) ship's sidecar carries migrated=true so the migration reader can
        // distinguish it from a legacy native (0.0.17) sidecar (which never had this key). See ShipState.migrated.
        if (ship.mechanism != null) {
            config.set("migrated", true);
        }

        // Model path for prefab ships
        String modelPath = plugin.getConfig().getString("ships." + ship.shipType + ".model-path");
        if (modelPath != null) {
            config.set("model_path", modelPath);
        }

        // Model data for custom ships
        if ("custom".equals(ship.shipType) && ship.sourceModel != null) {
            config.set("model_data", ship.sourceModel.toMap());
        }

        // Customization
        config.set("wood_type", ship.customization.getWoodType());
        if (ship.customization.getBalloonColor() != null) {
            config.set("balloon_color", ship.customization.getBalloonColor());
        }
        if (ship.customization.getCustomBanner() != null) {
            try {
                byte[] bytes = ship.customization.getCustomBanner().serializeAsBytes();
                config.set("banner", Base64.getEncoder().encodeToString(bytes));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to serialize banner for ship " + ship.id + ": " + e.getMessage());
            }
        }

        // Inventory contents are owned by the mechanism's persistence sidecar for a delegated ship
        // (ship's own storage map is always empty), so there is nothing to serialize here.

        // Internal yaw for chunk recovery (vehicle yaw is frozen at spawnYaw)
        config.set("current_yaw", ship.physics.currentYaw);

        return config;
    }

    /**
     * Saves all chunk indices to disk asynchronously.
     * Snapshots index data on calling thread, writes on IO thread.
     */
    public void saveAllChunkIndicesAsync() {
        // Snapshot: deep copy chunk indices on main thread
        Map<String, Map<String, List<String>>> snapshot = new HashMap<>();
        for (Map.Entry<String, Map<String, List<UUID>>> worldEntry : chunkIndices.entrySet()) {
            Map<String, List<String>> worldSnapshot = new HashMap<>();
            for (Map.Entry<String, List<UUID>> chunkEntry : worldEntry.getValue().entrySet()) {
                List<String> uuidStrings = new ArrayList<>();
                for (UUID uuid : chunkEntry.getValue()) {
                    uuidStrings.add(uuid.toString());
                }
                worldSnapshot.put(chunkEntry.getKey(), uuidStrings);
            }
            snapshot.put(worldEntry.getKey(), worldSnapshot);
        }

        // Write on async thread
        pendingIOOperations.incrementAndGet();
        ioExecutor.submit(() -> {
            try {
                for (Map.Entry<String, Map<String, List<String>>> worldEntry : snapshot.entrySet()) {
                    String worldName = worldEntry.getKey();
                    File worldDir = new File(worldsFolder, worldName);
                    worldDir.mkdirs();
                    File chunksFile = new File(worldDir, "chunks.yml");

                    YamlConfiguration config = new YamlConfiguration();
                    for (Map.Entry<String, List<String>> chunkEntry : worldEntry.getValue().entrySet()) {
                        config.set(chunkEntry.getKey(), chunkEntry.getValue());
                    }
                    config.save(chunksFile);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to async save chunk indices: " + e.getMessage());
            } finally {
                pendingIOOperations.decrementAndGet();
            }
        });
    }

    /**
     * Loads ship metadata asynchronously from per-world storage.
     * Returns a CompletableFuture that completes with the ShipState.
     */
    public CompletableFuture<ShipPersistence.ShipState> loadShipMetadataAsync(World world, UUID shipId) {
        String worldName = world.getName();
        pendingIOOperations.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadShipMetadataSync(worldName, shipId);
            } finally {
                pendingIOOperations.decrementAndGet();
            }
        }, ioExecutor);
    }

    /**
     * Loads ship metadata from per-world storage (sync version for internal use).
     * Returns a ShipState without position data.
     */
    private ShipPersistence.ShipState loadShipMetadataSync(String worldName, UUID shipId) {
        File shipFile = getShipFile(worldName, shipId);
        if (!shipFile.exists()) {
            return null;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(shipFile);

        String id = config.getString("id");
        String shipType = config.getString("ship_type", "smallship");
        String modelPath = config.getString("model_path");
        String woodType = config.getString("wood_type", "OAK");
        String balloonColor = config.getString("balloon_color");
        String bannerData = config.getString("banner");

        // Model data for custom ships - must convert MemorySection to Map
        Map<String, Object> modelData = null;
        if (config.contains("model_data")) {
            org.bukkit.configuration.ConfigurationSection modelSection = config.getConfigurationSection("model_data");
            if (modelSection != null) {
                modelData = modelSection.getValues(true);  // true = deep copy
            }
        }

        // Inventory data - must convert MemorySection to Map
        Map<Integer, String> inventoryData = new HashMap<>();
        if (config.contains("inventories")) {
            org.bukkit.configuration.ConfigurationSection invSection = config.getConfigurationSection("inventories");
            if (invSection != null) {
                for (String key : invSection.getKeys(false)) {
                    inventoryData.put(Integer.parseInt(key), invSection.getString(key));
                }
            }
        }

        // Entity count for recovery validation (default 0 for legacy data)
        int entityCount = config.getInt("entity_count", 0);

        // Internal yaw for chunk recovery (absent in legacy metadata -> NaN -> fall back to vehicle NBT)
        float currentYaw = config.contains("current_yaw")
            ? (float) config.getDouble("current_yaw") : Float.NaN;

        // Create ShipState without position (position comes from recovered vehicle)
        ShipPersistence.ShipState state = new ShipPersistence.ShipState(
            UUID.fromString(id),
            shipType,
            modelPath,
            worldName,
            0, 0, 0,      // Position will come from vehicle
            currentYaw, 0, // Yaw from metadata; pitch will come from vehicle
            bannerData,
            woodType,
            balloonColor,
            inventoryData,
            modelData,
            entityCount
        );
        state.migrated = config.getBoolean("migrated", false);
        return state;
    }

    /**
     * Loads ship metadata from per-world storage (synchronous).
     * Returns a ShipState without position data.
     */
    public ShipPersistence.ShipState loadShipMetadata(World world, UUID shipId) {
        return loadShipMetadataSync(world.getName(), shipId);
    }

    /**
     * Checks if metadata exists for a ship, using cache.
     * Much faster than loadShipMetadata() for orphan cleanup.
     * Caches both positive and negative results to avoid repeated file I/O.
     */
    public boolean hasMetadata(World world, UUID shipId) {
        // Clear cache periodically using atomic compare-and-set to avoid race conditions
        long now = System.currentTimeMillis();
        long lastClear = lastCacheClear.get();
        if (now - lastClear > CACHE_CLEAR_INTERVAL_MS) {
            if (lastCacheClear.compareAndSet(lastClear, now)) {
                metadataExistsCache.clear();
            }
        }

        String cacheKey = world.getName() + ":" + shipId;
        Boolean cached = metadataExistsCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Check file exists and cache the result
        boolean exists = getShipFile(world.getName(), shipId).exists();
        metadataExistsCache.put(cacheKey, exists);
        return exists;
    }

    /**
     * Removes a ship from storage completely.
     *
     * @return true if the on-disk ship file was removed (or was already absent), false if the file
     *         still exists after the delete attempt. The in-memory chunk index is always updated.
     */
    public boolean removeShip(World world, UUID shipId) {
        // Update cache to indicate file no longer exists
        metadataExistsCache.put(world.getName() + ":" + shipId, false);

        // Remove ship file
        boolean fileOk = true;
        File shipFile = getShipFile(world.getName(), shipId);
        if (shipFile.exists() && !shipFile.delete() && shipFile.exists()) {
            fileOk = false;
            plugin.getLogger().severe("Failed to delete ship file for " + shipId + " (world="
                + world.getName() + "): " + shipFile.getAbsolutePath());
        }

        // Remove from all chunk indices for this world
        Map<String, List<UUID>> worldIndex = chunkIndices.get(world.getName());
        if (worldIndex != null) {
            worldIndex.values().forEach(list -> list.remove(shipId));
            // Clean up empty entries
            worldIndex.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
        return fileOk;
    }

    // ===== Persistence =====

    /**
     * Loads all chunk indices from disk.
     */
    public void loadAllChunkIndices() {
        chunkIndices.clear();

        if (!worldsFolder.exists()) return;

        File[] worldDirs = worldsFolder.listFiles(File::isDirectory);
        if (worldDirs == null) return;

        for (File worldDir : worldDirs) {
            String worldName = worldDir.getName();
            File chunksFile = new File(worldDir, "chunks.yml");

            if (!chunksFile.exists()) continue;

            YamlConfiguration config = YamlConfiguration.loadConfiguration(chunksFile);
            Map<String, List<UUID>> worldIndex = new HashMap<>();

            for (String key : config.getKeys(false)) {
                List<String> uuidStrings = config.getStringList(key);
                List<UUID> uuids = new ArrayList<>();
                for (String uuidStr : uuidStrings) {
                    try {
                        uuids.add(UUID.fromString(uuidStr));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid UUID in chunk index: " + uuidStr);
                    }
                }
                if (!uuids.isEmpty()) {
                    worldIndex.put(key, uuids);
                }
            }

            if (!worldIndex.isEmpty()) {
                chunkIndices.put(worldName, worldIndex);
            }
        }

        // Validate chunk indices - remove entries for ships with missing metadata files
        int removedStale = validateAndCleanChunkIndices();

        int totalShips = chunkIndices.values().stream()
            .flatMap(m -> m.values().stream())
            .mapToInt(List::size)
            .sum();
        if (totalShips > 0 || removedStale > 0) {
            plugin.getLogger().info("Loaded chunk indices: " + totalShips + " valid ship entries" +
                (removedStale > 0 ? ", removed " + removedStale + " stale entries" : ""));
        }
    }

    /**
     * Validates chunk indices and removes entries for ships with missing metadata files.
     * Also removes duplicate UUIDs within the same chunk.
     * @return The number of stale entries removed
     */
    private int validateAndCleanChunkIndices() {
        int removedCount = 0;

        for (String worldName : new ArrayList<>(chunkIndices.keySet())) {
            Map<String, List<UUID>> worldIndex = chunkIndices.get(worldName);

            for (String chunkKey : new ArrayList<>(worldIndex.keySet())) {
                List<UUID> ships = worldIndex.get(chunkKey);

                // Remove duplicates and ships with missing metadata files
                Set<UUID> seen = new HashSet<>();
                Iterator<UUID> iter = ships.iterator();
                while (iter.hasNext()) {
                    UUID uuid = iter.next();
                    // Remove if duplicate or missing metadata file
                    if (seen.contains(uuid) || !getShipFile(worldName, uuid).exists()) {
                        iter.remove();
                        removedCount++;
                    } else {
                        seen.add(uuid);
                    }
                }

                // Remove empty chunk entries
                if (ships.isEmpty()) {
                    worldIndex.remove(chunkKey);
                }
            }

            // Remove empty world entries
            if (worldIndex.isEmpty()) {
                chunkIndices.remove(worldName);
            }
        }

        // Save cleaned indices if we removed anything
        if (removedCount > 0) {
            saveAllChunkIndices();
        }

        return removedCount;
    }

    /**
     * Saves all chunk indices to disk.
     */
    public boolean saveAllChunkIndices() {
        boolean allOk = true;
        for (Map.Entry<String, Map<String, List<UUID>>> worldEntry : chunkIndices.entrySet()) {
            String worldName = worldEntry.getKey();
            Map<String, List<UUID>> worldIndex = worldEntry.getValue();

            File worldDir = new File(worldsFolder, worldName);
            worldDir.mkdirs();
            File chunksFile = new File(worldDir, "chunks.yml");

            YamlConfiguration config = new YamlConfiguration();
            for (Map.Entry<String, List<UUID>> chunkEntry : worldIndex.entrySet()) {
                List<String> uuidStrings = new ArrayList<>();
                for (UUID uuid : chunkEntry.getValue()) {
                    uuidStrings.add(uuid.toString());
                }
                config.set(chunkEntry.getKey(), uuidStrings);
            }

            try {
                config.save(chunksFile);
            } catch (IOException e) {
                allOk = false;
                plugin.getLogger().severe("Failed to save chunk index for world " + worldName + ": " + e.getMessage());
            }
        }
        return allOk;
    }

    /**
     * Saves everything - chunk indices and all ship metadata for currently loaded ships.
     */
    public void saveAll() {
        // Save chunk indices
        saveAllChunkIndices();

        // Save metadata for all currently loaded ships
        for (ShipInstance ship : ShipRegistry.getAllShips()) {
            saveShipMetadata(ship);
        }
    }

    // ===== Helpers =====

    private File getShipFile(String worldName, UUID shipId) {
        return new File(worldsFolder, worldName + "/ships/" + shipId.toString() + ".yml");
    }

    /**
     * Gets all ship UUIDs known in a world (from chunk indices).
     */
    public Set<UUID> getAllShipIds(World world) {
        Set<UUID> ids = new HashSet<>();
        Map<String, List<UUID>> worldIndex = chunkIndices.get(world.getName());
        if (worldIndex != null) {
            worldIndex.values().forEach(ids::addAll);
        }
        return ids;
    }

    /**
     * All persisted ship ids across every indexed world on disk (loaded or not).
     * Reads the full in-memory chunk index directly, so it sees worlds that exist on
     * disk but aren't currently loaded (e.g. an unloaded Multiverse world) — unlike
     * iterating {@link org.bukkit.Bukkit#getWorlds()}. No chunk I/O.
     */
    public Set<UUID> getAllPersistedShipIds() {
        Set<UUID> ids = new HashSet<>();
        for (Map<String, List<UUID>> worldIndex : chunkIndices.values()) {
            worldIndex.values().forEach(ids::addAll);
        }
        return ids;
    }

    /**
     * Shuts down the async I/O executor.
     * Should be called on plugin disable.
     */
    public void shutdown() {
        ioExecutor.shutdown();
        try {
            // Wait for pending I/O operations to complete (max 5 seconds)
            long start = System.currentTimeMillis();
            while (pendingIOOperations.get() > 0 && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(50);
            }
            if (pendingIOOperations.get() > 0) {
                plugin.getLogger().warning("Forcing shutdown with " + pendingIOOperations.get() + " pending I/O operations");
            }
            if (!ioExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
