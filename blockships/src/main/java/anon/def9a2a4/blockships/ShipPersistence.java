package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class ShipPersistence {

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
        public final int entityCount;  // Expected entity count for recovery validation
        /** True iff this sidecar describes a DELEGATED (defCoreLib mechanism) ship — set for every delegated ship
         *  (fresh custom/prefab AND native→delegated migrations). Absent/false on a legacy native (0.0.17) sidecar.
         *  The migration reader uses it to tell a not-yet-migrated native ship (migrate it) from a reap-failed
         *  straggler of an already-migrated ship (reap only, never re-assemble). Non-final marker field. */
        public boolean migrated = false;

        public ShipState(UUID id, String shipType, String modelPath, String worldName, double x, double y, double z,
                         float yaw, float pitch, String bannerData, String woodType, String balloonColor,
                         Map<Integer, String> inventoryData, Map<String, Object> modelData, int entityCount) {
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
            this.entityCount = entityCount;
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

            // Calculate entity count for recovery validation
            int entityCount = inst.countEntities();

            return new ShipState(
                inst.id,
                inst.shipType,
                modelPath,
                loc.getWorld().getName(),
                loc.getX(),
                loc.getY(),
                loc.getZ(),
                inst.physics.currentYaw,
                loc.getPitch(),
                bannerData,
                inst.customization.getWoodType(),
                inst.customization.getBalloonColor(),
                inventoryData,
                modelData,
                entityCount
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

            // Save entity count for recovery validation
            map.put("entity_count", entityCount);

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

            // Entity count (default to 0 for legacy data without this field)
            int entityCount = map.containsKey("entity_count")
                ? ((Number) map.get("entity_count")).intValue()
                : 0;

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
                modelData,
                entityCount
            );
        }
    }
}

