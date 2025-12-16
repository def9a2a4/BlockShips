package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class ShipPersistence {
    private static final String SHIPS_FILE = "ships.yml";
    private final JavaPlugin plugin;
    private final java.io.File shipsFile;

    public ShipPersistence(JavaPlugin plugin) {
        this.plugin = plugin;
        this.shipsFile = new java.io.File(plugin.getDataFolder(), SHIPS_FILE);
    }

    // Save all active ships to file
    public void saveAll() {
        List<Map<String, Object>> shipList = new ArrayList<>();
        for (ShipInstance inst : ShipRegistry.getAllShips()) {
            ShipState state = ShipState.fromInstance(inst);
            shipList.add(state.toMap());
        }

        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("ships", shipList);

        try {
            config.save(shipsFile);
            plugin.getLogger().info("Saved " + shipList.size() + " ships to " + SHIPS_FILE);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save ships: " + e.getMessage());
        }
    }

    // Load all ships from file
    public void loadAll() {
        // First, clean up any orphaned ship entities from previous runs
        // This prevents entity duplication when the server restarts
        cleanupOrphanedEntities();

        if (!shipsFile.exists()) {
            return; // No ships to load
        }

        org.bukkit.configuration.file.YamlConfiguration config;
        try {
            config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(shipsFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load ships file: " + e.getMessage());
            return;
        }

        List<Map<?, ?>> shipList = config.getMapList("ships");
        int loaded = 0;
        int failed = 0;

        for (Map<?, ?> map : shipList) {
            try {
                @SuppressWarnings("unchecked")
                ShipState state = ShipState.fromMap((Map<String, Object>) map);

                // Get world
                World world = Bukkit.getWorld(state.worldName);
                if (world == null) {
                    plugin.getLogger().warning("Skipping ship in missing world: " + state.worldName);
                    failed++;
                    continue;
                }

                // Load model - different approach for custom vs prefab ships
                ShipModel model;
                if ("custom".equals(state.shipType) && state.modelData != null) {
                    // Custom ship - deserialize model from stored data
                    try {
                        model = ShipModel.fromMap(state.modelData);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Skipping custom ship with invalid model data: " + e.getMessage());
                        failed++;
                        continue;
                    }
                } else {
                    // Prefab ship - load from model file
                    String modelPath = plugin.getConfig().getString("ships." + state.shipType + ".model-path");
                    if (modelPath == null) {
                        plugin.getLogger().warning("Skipping ship with unknown type: " + state.shipType);
                        failed++;
                        continue;
                    }

                    try {
                        model = ShipModel.fromFile(plugin, modelPath);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Skipping ship with missing model: " + modelPath);
                        failed++;
                        continue;
                    }
                }

                // Create location
                Location loc = new Location(world, state.x, state.y, state.z, state.yaw, state.pitch);

                // Deserialize banner if present
                ItemStack customBanner = null;
                if (state.bannerData != null) {
                    try {
                        byte[] bytes = Base64.getDecoder().decode(state.bannerData);
                        customBanner = ItemStack.deserializeBytes(bytes);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to deserialize banner for ship: " + e.getMessage());
                    }
                }

                // Create ship instance and register (no boat needed - ArmorStand is created internally)
                ShipCustomization customization = ShipCustomization.builder()
                    .banner(customBanner)
                    .woodType(state.woodType)
                    .balloonColor(state.balloonColor)
                    .build();
                ShipInstance instance = new ShipInstance(plugin, state.shipType, model, loc, customization);

                // For custom ships, restore the source model for disassembly
                if ("custom".equals(state.shipType)) {
                    instance.sourceModel = model;
                }

                // Restore inventory contents
                if (!state.inventoryData.isEmpty()) {
                    Map<Integer, ItemStack[]> savedContents = new HashMap<>();
                    for (Map.Entry<Integer, String> entry : state.inventoryData.entrySet()) {
                        try {
                            String[] itemStrings = entry.getValue().split("\\|", -1);  // -1 to keep empty strings
                            ItemStack[] items = new ItemStack[itemStrings.length];
                            for (int i = 0; i < itemStrings.length; i++) {
                                if (!itemStrings[i].isEmpty()) {
                                    byte[] bytes = Base64.getDecoder().decode(itemStrings[i]);
                                    items[i] = ItemStack.deserializeBytes(bytes);
                                } else {
                                    items[i] = null;  // Empty slot
                                }
                            }
                            savedContents.put(entry.getKey(), items);
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to deserialize inventory at block " + entry.getKey() + ": " + e.getMessage());
                        }
                    }
                    instance.restoreStorageContents(savedContents);
                }

                ShipRegistry.register(instance);
                loaded++;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to restore ship: " + e.getMessage());
                failed++;
            }
        }

        plugin.getLogger().info("Loaded " + loaded + " ships" + (failed > 0 ? " (" + failed + " failed)" : ""));
    }

    // Delete the ships file
    public void clear() {
        if (shipsFile.exists()) {
            shipsFile.delete();
        }
    }

    /**
     * Check if legacy ships.yml data exists.
     * Used to determine if migration to per-world storage is needed.
     */
    public boolean hasLegacyData() {
        return shipsFile.exists();
    }

    /**
     * Remove all orphaned ship entities from all worlds.
     * This prevents entity duplication when the server restarts with persistent entities.
     */
    private void cleanupOrphanedEntities() {
        int removedCount = 0;

        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                // Check if entity has a displayship tag
                boolean isShipEntity = ShipTags.isShipEntity(entity.getScoreboardTags());

                if (isShipEntity) {
                    entity.remove();
                    removedCount++;
                }
            }
        }

        if (removedCount > 0) {
            plugin.getLogger().info("Cleaned up " + removedCount + " orphaned ship entities");
        }
    }

    // ===== Serialization =====

    public static final class ShipState {
        public final UUID id;
        public final String shipType;  // Ship type identifier (e.g., "smallship", "bigship", "custom")
        public final String modelPath;  // Model path (for prefab ships)
        public final String worldName;
        public final double x, y, z;
        public final float yaw, pitch;
        public final String bannerData;  // Serialized banner ItemStack
        public final String woodType;  // Wood type string (e.g., "OAK", "DARK_OAK")
        public final String balloonColor;  // Balloon color for airships (e.g., "WHITE", "RED")
        public final Map<Integer, String> inventoryData;  // Block index -> Base64 serialized inventory contents
        public final Map<String, Object> modelData;  // Serialized model (for custom ships only, null for prefab)

        public ShipState(UUID id, String shipType, String modelPath, String worldName, double x, double y, double z,
                         float yaw, float pitch, String bannerData, String woodType, String balloonColor,
                         Map<Integer, String> inventoryData, Map<String, Object> modelData) {
            this.id = id;
            this.shipType = shipType;
            this.modelPath = modelPath;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.bannerData = bannerData;
            this.woodType = woodType;
            this.balloonColor = balloonColor;
            this.inventoryData = inventoryData;
            this.modelData = modelData;
        }

        // Create ShipState from a ShipInstance
        public static ShipState fromInstance(ShipInstance inst) {
            Location loc = inst.vehicle.getLocation();

            // Serialize custom banner
            String bannerData = null;
            if (inst.customization.getCustomBanner() != null) {
                try {
                    byte[] bytes = inst.customization.getCustomBanner().serializeAsBytes();
                    bannerData = Base64.getEncoder().encodeToString(bytes);
                } catch (Exception e) {
                    inst.plugin.getLogger().warning("Failed to serialize banner for persistence: " + e.getMessage());
                }
            }

            // Serialize inventory contents
            Map<Integer, String> inventoryData = new HashMap<>();
            for (Map.Entry<Integer, Inventory> entry : inst.storages.entrySet()) {
                try {
                    Inventory inv = entry.getValue();
                    // Serialize each item in the inventory
                    List<String> itemsData = new ArrayList<>();
                    for (ItemStack item : inv.getContents()) {
                        if (item != null && !item.getType().isAir()) {
                            byte[] bytes = item.serializeAsBytes();
                            itemsData.add(Base64.getEncoder().encodeToString(bytes));
                        } else {
                            itemsData.add("");  // Empty slot marker
                        }
                    }
                    // Join all items with a delimiter
                    inventoryData.put(entry.getKey(), String.join("|", itemsData));
                } catch (Exception e) {
                    inst.plugin.getLogger().warning("Failed to serialize inventory at block " + entry.getKey() + ": " + e.getMessage());
                }
            }

            // Get model path from config for this ship type (null for custom ships)
            String modelPath = inst.plugin.getConfig().getString("ships." + inst.shipType + ".model-path");

            // For custom ships, serialize the model data
            Map<String, Object> modelData = null;
            if ("custom".equals(inst.shipType) && inst.sourceModel != null) {
                modelData = inst.sourceModel.toMap();
            }

            return new ShipState(
                inst.id,
                inst.shipType,
                modelPath,
                loc.getWorld().getName(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                loc.getYaw(),
                loc.getPitch(),
                bannerData,
                inst.customization.getWoodType(),
                inst.customization.getBalloonColor(),
                inventoryData,
                modelData
            );
        }

        // Serialize to YAML-compatible map
        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("id", id.toString());
            map.put("ship_type", shipType);
            if (modelPath != null) {
                map.put("model", modelPath);  // For prefab ships
            }
            map.put("world", worldName);
            map.put("x", x);
            map.put("y", y);
            map.put("z", z);
            map.put("yaw", yaw);
            map.put("pitch", pitch);
            if (bannerData != null) {
                map.put("banner", bannerData);
            }
            map.put("wood_type", woodType);
            if (balloonColor != null) {
                map.put("balloon_color", balloonColor);
            }

            // Save inventory data as map of block index -> serialized contents
            if (!inventoryData.isEmpty()) {
                Map<String, String> invMap = new HashMap<>();
                for (Map.Entry<Integer, String> entry : inventoryData.entrySet()) {
                    invMap.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                map.put("inventories", invMap);
            }

            // Save model data for custom ships
            if (modelData != null) {
                map.put("model_data", modelData);
            }
            return map;
        }

        // Deserialize from YAML-compatible map
        public static ShipState fromMap(Map<String, Object> map) {
            String bannerData = map.containsKey("banner") ? String.valueOf(map.get("banner")) : null;
            String woodType = String.valueOf(map.get("wood_type"));
            String balloonColor = map.containsKey("balloon_color") ? String.valueOf(map.get("balloon_color")) : null;

            // Get ship type, or default to "smallship" for backwards compatibility
            String shipType = map.containsKey("ship_type") ? String.valueOf(map.get("ship_type")) : "smallship";

            // Deserialize inventory data
            Map<Integer, String> inventoryData = new HashMap<>();
            if (map.containsKey("inventories")) {
                @SuppressWarnings("unchecked")
                Map<String, String> invMap = (Map<String, String>) map.get("inventories");
                for (Map.Entry<String, String> entry : invMap.entrySet()) {
                    inventoryData.put(Integer.parseInt(entry.getKey()), entry.getValue());
                }
            }

            // Deserialize model data for custom ships
            @SuppressWarnings("unchecked")
            Map<String, Object> modelData = map.containsKey("model_data")
                ? (Map<String, Object>) map.get("model_data")
                : null;

            // Model path may be null for custom ships
            String modelPath = map.containsKey("model") ? String.valueOf(map.get("model")) : null;

            return new ShipState(
                UUID.fromString(String.valueOf(map.get("id"))),
                shipType,
                modelPath,
                String.valueOf(map.get("world")),
                ((Number) map.get("x")).doubleValue(),
                ((Number) map.get("y")).doubleValue(),
                ((Number) map.get("z")).doubleValue(),
                ((Number) map.get("yaw")).floatValue(),
                ((Number) map.get("pitch")).floatValue(),
                bannerData,
                woodType,
                balloonColor,
                inventoryData,
                modelData
            );
        }
    }
}

