package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

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
     */
    public void addToChunkIndex(World world, UUID shipId, int chunkX, int chunkZ) {
        String worldName = world.getName();
        String key = chunkX + "," + chunkZ;

        chunkIndices.computeIfAbsent(worldName, k -> new HashMap<>())
                    .computeIfAbsent(key, k -> new ArrayList<>())
                    .add(shipId);
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

        YamlConfiguration config = new YamlConfiguration();
        config.set("id", ship.id.toString());
        config.set("ship_type", ship.shipType);

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

        // Inventory contents
        Map<String, String> inventories = new HashMap<>();
        for (Map.Entry<Integer, org.bukkit.inventory.Inventory> entry : ship.storages.entrySet()) {
            try {
                List<String> itemsData = new ArrayList<>();
                for (ItemStack item : entry.getValue().getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        byte[] bytes = item.serializeAsBytes();
                        itemsData.add(Base64.getEncoder().encodeToString(bytes));
                    } else {
                        itemsData.add("");
                    }
                }
                inventories.put(String.valueOf(entry.getKey()), String.join("|", itemsData));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to serialize inventory at block " + entry.getKey() + ": " + e.getMessage());
            }
        }
        if (!inventories.isEmpty()) {
            config.set("inventories", inventories);
        }

        try {
            config.save(shipFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save ship metadata for " + ship.id + ": " + e.getMessage());
        }
    }

    /**
     * Loads ship metadata from per-world storage.
     * Returns a ShipState without position data.
     */
    public ShipPersistence.ShipState loadShipMetadata(World world, UUID shipId) {
        File shipFile = getShipFile(world.getName(), shipId);
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

        // Create ShipState without position (position comes from recovered vehicle)
        return new ShipPersistence.ShipState(
            UUID.fromString(id),
            shipType,
            modelPath,
            world.getName(),
            0, 0, 0,  // Position will come from vehicle
            0, 0,     // Rotation will come from vehicle
            bannerData,
            woodType,
            balloonColor,
            inventoryData,
            modelData
        );
    }

    /**
     * Removes a ship from storage completely.
     */
    public void removeShip(World world, UUID shipId) {
        // Remove ship file
        File shipFile = getShipFile(world.getName(), shipId);
        if (shipFile.exists()) {
            shipFile.delete();
        }

        // Remove from all chunk indices for this world
        Map<String, List<UUID>> worldIndex = chunkIndices.get(world.getName());
        if (worldIndex != null) {
            worldIndex.values().forEach(list -> list.remove(shipId));
            // Clean up empty entries
            worldIndex.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
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

        int totalShips = chunkIndices.values().stream()
            .flatMap(m -> m.values().stream())
            .mapToInt(List::size)
            .sum();
        if (totalShips > 0) {
            plugin.getLogger().info("Loaded chunk indices for " + totalShips + " ship entries across " + chunkIndices.size() + " worlds");
        }
    }

    /**
     * Saves all chunk indices to disk.
     */
    public void saveAllChunkIndices() {
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
                plugin.getLogger().severe("Failed to save chunk index for world " + worldName + ": " + e.getMessage());
            }
        }
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
}
