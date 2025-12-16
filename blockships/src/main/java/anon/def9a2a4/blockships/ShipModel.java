package anon.def9a2a4.blockships;

import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.*;

import java.util.*;

public final class ShipModel {
    public final List<ModelPart> parts;
    public final List<ItemPart> items;
    public final Vector3f initialRotation;  // yaw, pitch, roll in degrees
    public final Vector3f positionOffset;
    public final Vector3f collisionOffset;
    public final Matrix3f rotationTransform;  // 3x3 matrix to transform vehicle rotation to ship rotation

    public final List<SeatInfo> seats;     // Multiple seat positions, in order they appear in model file
    public final List<CannonInfo> cannons; // Detected cannons (dispenser + obsidian behind)
    public final float waterFloatOffset;   // Y-position offset from water surface where ship floats (for prefab ships)

    // Buoyancy system (for custom block ships)
    public final int totalWeight;               // Sum of all block weights (excluding null-weight blocks)
    public final int blockCount;                // Number of blocks with weight (for density calculation)
    public final Vector3f centerOfVolume;       // Geometric center of all blocks (relative to wheel)
    public final float minY;                    // Bottom of ship (relative to origin)
    public final float maxY;                    // Top of ship (relative to origin)

    // Health system configuration
    public final double maxHealth;              // Maximum health points for the ship
    public final double healthRegenPerSecond;   // Health regeneration rate per second

    // Assembly rotation (for custom block ships disassembly)
    public final float assemblyYaw;             // Yaw angle when assembled (0=S, 90=W, 180=N, 270=E), 0 for prefab ships

    public ShipModel(List<ModelPart> parts, List<ItemPart> items, Vector3f initialRotation, Vector3f positionOffset,
                     Vector3f collisionOffset, Matrix3f rotationTransform, List<SeatInfo> seats, List<CannonInfo> cannons,
                     float waterFloatOffset, double maxHealth, double healthRegenPerSecond,
                     int totalWeight, int blockCount, Vector3f centerOfVolume, float minY, float maxY, float assemblyYaw) {
        this.parts = parts;
        this.items = items;
        this.initialRotation = initialRotation;
        this.positionOffset = positionOffset;
        this.collisionOffset = collisionOffset;
        this.rotationTransform = rotationTransform;
        this.seats = seats;
        this.cannons = cannons;
        this.waterFloatOffset = waterFloatOffset;
        this.maxHealth = maxHealth;
        this.healthRegenPerSecond = healthRegenPerSecond;
        this.totalWeight = totalWeight;
        this.blockCount = blockCount;
        this.centerOfVolume = centerOfVolume;
        this.minY = minY;
        this.maxY = maxY;
        this.assemblyYaw = assemblyYaw;
    }

    /**
     * Calculates the ship's density (weight / block count).
     */
    public float getDensity() {
        if (blockCount == 0) return 0;
        return (float) totalWeight / blockCount;
    }

    /**
     * Calculates the surface offset based on density compared to water.
     * @param waterDensity The reference density of water
     * @param offsetScale How much 1 density unit affects float height
     * @return The Y-offset from water surface (positive = floats higher, negative = sinks)
     */
    public float calculateBuoyancyOffset(float waterDensity, float offsetScale) {
        float density = getDensity();
        // Lower density -> floats higher (positive offset)
        // Higher density -> sinks lower (negative offset)
        return (waterDensity - density) * offsetScale;
    }

    /**
     * Reads a Vector3f offset from config, falling back to default if not present or invalid.
     * @param config The configuration section to read from
     * @param key The config key for the double list [x, y, z]
     * @param defaultValue The default Vector3f to use if key is missing or invalid
     * @return A new Vector3f with values from config or the default
     */
    public static Vector3f readVector3fFromConfig(org.bukkit.configuration.ConfigurationSection config,
                                                    String key, Vector3f defaultValue) {
        List<Double> values = config.getDoubleList(key);
        if (values.size() >= 3) {
            return new Vector3f(
                values.get(0).floatValue(),
                values.get(1).floatValue(),
                values.get(2).floatValue()
            );
        }
        return new Vector3f(defaultValue);
    }

    /**
     * Reads a Vector3f from a list of numbers.
     * @param list The list containing [x, y, z] values
     * @param defaultValue The default Vector3f to use if list is invalid
     * @return A new Vector3f with values from list or the default
     */
    public static Vector3f readVector3fFromList(List<?> list, Vector3f defaultValue) {
        if (list != null && list.size() >= 3) {
            return new Vector3f(
                ((Number) list.get(0)).floatValue(),
                ((Number) list.get(1)).floatValue(),
                ((Number) list.get(2)).floatValue()
            );
        }
        return new Vector3f(defaultValue);
    }

    public static ShipModel fromFile(JavaPlugin plugin, String filePath) {
        // Load model file
        java.io.File modelFile = new java.io.File(plugin.getDataFolder(), filePath);
        if (!modelFile.exists()) {
            throw new IllegalArgumentException("Model file not found: " + modelFile.getAbsolutePath());
        }

        org.bukkit.configuration.file.YamlConfiguration config;
        try {
            config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(modelFile);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to load model from file: " + filePath, e);
        }

        List<ModelPart> out = new ArrayList<>();
        List<SeatInfo> seats = new ArrayList<>();
        int blockIndex = 0;
        for (Map<?, ?> map : config.getMapList("blocks")) {
            String mat = String.valueOf(map.get("block"));
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) map.get("transformation");
            if (raw == null || raw.size() != 16) throw new IllegalArgumentException("Bad matrix for " + mat);
            float[] m = new float[16];
            for (int i = 0; i < 16; i++) m[i] = ((Number) raw.get(i)).floatValue();

            // Create BlockData with properties if they exist
            BlockData blockData;
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) map.get("properties");
            if (properties != null && !properties.isEmpty()) {
                // Build block state string: minecraft:block_name[prop1=val1,prop2=val2]
                StringBuilder stateString = new StringBuilder("minecraft:");
                stateString.append(mat.toLowerCase());
                stateString.append("[");
                boolean first = true;
                for (Map.Entry<String, Object> entry : properties.entrySet()) {
                    if (!first) stateString.append(",");
                    stateString.append(entry.getKey()).append("=").append(entry.getValue());
                    first = false;
                }
                stateString.append("]");
                blockData = Bukkit.createBlockData(stateString.toString());
            } else {
                blockData = Bukkit.createBlockData(Material.valueOf(mat));
            }

            // Parse collision configuration
            CollisionConfig collision;
            if (map.containsKey("collision")) {
                Object collisionObj = map.get("collision");
                if (collisionObj instanceof Map) {
                    // Parse as dict: {enable: bool, size: float, offset: [x, y, z]}
                    @SuppressWarnings("unchecked")
                    Map<String, Object> collisionMap = (Map<String, Object>) collisionObj;

                    // Read enable (default true)
                    boolean enable = collisionMap.containsKey("enable")
                        ? Boolean.TRUE.equals(collisionMap.get("enable"))
                        : true;

                    // Read size (default 1.0)
                    float size = collisionMap.containsKey("size")
                        ? ((Number) collisionMap.get("size")).floatValue()
                        : 1.0f;

                    // Read offset (default [0, 0, 0])
                    Vector3f offset = new Vector3f(0, 0, 0);
                    if (collisionMap.containsKey("offset")) {
                        @SuppressWarnings("unchecked")
                        List<Object> offsetList = (List<Object>) collisionMap.get("offset");
                        offset = readVector3fFromList(offsetList, new Vector3f(0, 0, 0));
                    }

                    collision = new CollisionConfig(enable, size, offset);
                } else {
                    // Unsupported format - throw error
                    throw new IllegalArgumentException("collision must be a dict with keys: enable, size, offset");
                }
            } else {
                // No collision specified - use default (enabled)
                collision = new CollisionConfig();
            }

            // Parse storage configuration (optional)
            StorageConfig storage = null;
            if (map.containsKey("storage")) {
                Object storageObj = map.get("storage");
                if (storageObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> storageMap = (Map<String, Object>) storageObj;
                    storage = StorageConfig.fromYaml(storageMap);
                } else {
                    throw new IllegalArgumentException("storage must be a dict with keys: type, name (optional)");
                }
            }

            // Parse seat configuration (optional)
            if (map.containsKey("seat")) {
                Object seatObj = map.get("seat");
                boolean isDriver = false;
                Vector3f seatBlockOffset = new Vector3f(0, 0, 0);

                if (seatObj instanceof Boolean && Boolean.TRUE.equals(seatObj)) {
                    // Shorthand: seat: true means passenger seat with default offset
                    isDriver = false;
                } else if (seatObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> seatMap = (Map<String, Object>) seatObj;

                    // Read is_driver (default false)
                    isDriver = seatMap.containsKey("is_driver")
                        ? Boolean.TRUE.equals(seatMap.get("is_driver"))
                        : false;

                    // Read offset from block center (default [0, 0, 0])
                    if (seatMap.containsKey("offset")) {
                        @SuppressWarnings("unchecked")
                        List<Object> offsetList = (List<Object>) seatMap.get("offset");
                        seatBlockOffset = readVector3fFromList(offsetList, new Vector3f(0, 0, 0));
                    }
                } else {
                    throw new IllegalArgumentException("seat must be true or a dict with keys: is_driver (optional), offset (optional)");
                }

                // Calculate seat position: block position (from transformation) + seat offset
                // Extract translation from block transformation matrix (row-major: m[3], m[7], m[11])
                Vector3f blockPos = new Vector3f(m[3], m[7], m[11]);
                Vector3f finalOffset = new Vector3f(blockPos).add(seatBlockOffset);

                seats.add(new SeatInfo(finalOffset, blockIndex, isDriver));
            }

            out.add(new ModelPart(blockData, matrixFromMinecraftNbt(m), collision, storage, map));
            blockIndex++;
        }

        // Read initial rotation (default to 0, 0, 0)
        Vector3f initialRotation = readVector3fFromConfig(config, "initial-rotation", new Vector3f(0, 0, 0));

        // Read position offset (default to 0, 0, 0)
        Vector3f positionOffset = readVector3fFromConfig(config, "position-offset", new Vector3f(0, 0, 0));

        // Read collision offset (defaults to position-offset if not specified)
        Vector3f collisionOffset = readVector3fFromConfig(config, "collision-offset", positionOffset);

        // Read rotation matrix (default to identity matrix)
        List<Double> rotMatrixList = config.getDoubleList("rotation-matrix");
        Matrix3f rotationTransform = new Matrix3f(); // Identity by default
        if (rotMatrixList.size() >= 9) {
            // Row-major order: [m00, m01, m02, m10, m11, m12, m20, m21, m22]
            rotationTransform.set(
                rotMatrixList.get(0).floatValue(), rotMatrixList.get(1).floatValue(), rotMatrixList.get(2).floatValue(),
                rotMatrixList.get(3).floatValue(), rotMatrixList.get(4).floatValue(), rotMatrixList.get(5).floatValue(),
                rotMatrixList.get(6).floatValue(), rotMatrixList.get(7).floatValue(), rotMatrixList.get(8).floatValue()
            );
        }

        // Parse items
        List<ItemPart> items = new ArrayList<>();
        for (Map<?, ?> map : config.getMapList("items")) {
            String mat = String.valueOf(map.get("item"));
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) map.get("transformation");
            if (raw == null || raw.size() != 16) throw new IllegalArgumentException("Bad matrix for item " + mat);
            float[] m = new float[16];
            for (int i = 0; i < 16; i++) m[i] = ((Number) raw.get(i)).floatValue();

            // Parse item stack
            ItemStack itemStack = new ItemStack(Material.valueOf(mat));

            // Set count if specified
            if (map.containsKey("count")) {
                int count = ((Number) map.get("count")).intValue();
                itemStack.setAmount(count);
            }

            // Parse components if they exist
            @SuppressWarnings("unchecked")
            Map<String, Object> components = (Map<String, Object>) map.get("components");
            if (components != null && !components.isEmpty()) {
                ItemMeta meta = itemStack.getItemMeta();

                // Handle minecraft:profile for player heads
                if (components.containsKey("minecraft:profile")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> profile = (Map<String, Object>) components.get("minecraft:profile");
                    if (profile != null && meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                        // Extract base64 texture value from properties
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> properties = (List<Map<String, Object>>) profile.get("properties");
                        if (properties != null && !properties.isEmpty()) {
                            String textureValue = String.valueOf(properties.get(0).get("value"));
                            // Apply base64 texture directly to skull meta
                            try {
                                com.destroystokyo.paper.profile.PlayerProfile playerProfile =
                                    Bukkit.createProfile(UUID.randomUUID());
                                com.destroystokyo.paper.profile.ProfileProperty property =
                                    new com.destroystokyo.paper.profile.ProfileProperty("textures", textureValue);
                                playerProfile.setProperty(property);
                                skullMeta.setPlayerProfile(playerProfile);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to set player head texture: " + e.getMessage());
                            }
                        }
                    }
                }

                itemStack.setItemMeta(meta);
            }

            // Parse display mode (default to NONE)
            ItemDisplay.ItemDisplayTransform displayMode = ItemDisplay.ItemDisplayTransform.NONE;
            if (map.containsKey("display_mode")) {
                String modeStr = String.valueOf(map.get("display_mode")).toUpperCase();
                try {
                    displayMode = ItemDisplay.ItemDisplayTransform.valueOf(modeStr);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown display mode: " + modeStr + ", using NONE");
                }
            }

            items.add(new ItemPart(itemStack, displayMode, matrixFromMinecraftNbt(m)));
        }

        // Parse water float offset (default to 0.0)
        float waterFloatOffset = (float) config.getDouble("water-float-offset", 0.0);

        // Parse health system configuration
        double maxHealth = config.getDouble("max-health", 40.0);
        double healthRegenPerSecond = config.getDouble("health-regen-per-second", 2.0);

        // Validate seat configuration
        if (seats.isEmpty()) {
            throw new IllegalArgumentException("Ship model must have at least one seat");
        }
        if (!seats.get(0).isDriver) {
            throw new IllegalArgumentException("Seat at index 0 must be the driver seat (is_driver: true)");
        }
        for (int i = 1; i < seats.size(); i++) {
            if (seats.get(i).isDriver) {
                throw new IllegalArgumentException("Only seat at index 0 can be the driver seat. Found is_driver: true at index " + i);
            }
        }

        // Prefab ships don't use weight-based buoyancy - they use waterFloatOffset from YAML
        // Set weight/blockCount to 0, centerOfVolume to origin, minY/maxY to 0, and assemblyYaw to 0 (prefab ships don't rotate on assembly)
        // Prefab ships have no cannons (empty list)
        return new ShipModel(out, items, initialRotation, positionOffset, collisionOffset, rotationTransform,
                           seats, new ArrayList<>(), waterFloatOffset, maxHealth, healthRegenPerSecond,
                           0, 0, new Vector3f(0, 0, 0), 0f, 0f, 0f);
    }

    private static Matrix4f matrixFromMinecraftNbt(final float[] a) {
        // Minecraft configs use row-major order, JOML uses column-major
        // Transpose by swapping row/column indices
        return new Matrix4f(
            a[0],  a[4],  a[8],  a[12],   // m00 m01 m02 m03
            a[1],  a[5],  a[9],  a[13],   // m10 m11 m12 m13
            a[2],  a[6],  a[10], a[14],   // m20 m21 m22 m23
            a[3],  a[7],  a[11], a[15]    // m30 m31 m32 m33
        );
    }

    private static float[] matrixToMinecraftNbt(final Matrix4f m) {
        // Reverse of matrixFromMinecraftNbt: JOML column-major to Minecraft row-major
        // Transpose back by reading in row-major order from JOML's column-major storage
        return new float[] {
            m.m00(), m.m10(), m.m20(), m.m30(),  // row 0
            m.m01(), m.m11(), m.m21(), m.m31(),  // row 1
            m.m02(), m.m12(), m.m22(), m.m32(),  // row 2
            m.m03(), m.m13(), m.m23(), m.m33()   // row 3
        };
    }

    public static final class ModelPart {
        public final BlockData block;
        public final Matrix4f local;
        public final CollisionConfig collision;
        public final StorageConfig storage;  // null if no storage
        public final Map<?, ?> rawYaml;  // Original YAML map for wood type conversion

        public ModelPart(BlockData block, Matrix4f local, CollisionConfig collision, StorageConfig storage, Map<?, ?> rawYaml) {
            this.block = block;
            this.local = local;
            this.collision = collision;
            this.storage = storage;
            this.rawYaml = rawYaml;
        }
    }

    public static final class ItemPart {
        public final ItemStack item;
        public final ItemDisplay.ItemDisplayTransform displayMode;
        public final Matrix4f local;

        public ItemPart(ItemStack item, ItemDisplay.ItemDisplayTransform displayMode, Matrix4f local) {
            this.item = item;
            this.displayMode = displayMode;
            this.local = local;
        }
    }

    public static final class CollisionConfig {
        public final boolean enable;
        public final float size;
        public final Vector3f offset;

        public CollisionConfig() {
            this(true, 1.0f, new Vector3f(0, 0, 0));
        }

        public CollisionConfig(boolean enable, float size, Vector3f offset) {
            this.enable = enable;
            this.size = size;
            this.offset = offset;
        }
    }

    public enum StorageType {
        CHEST(27),
        DOUBLE_CHEST(54),
        DROPPER(9),
        HOPPER(5);

        public final int slots;

        StorageType(int slots) {
            this.slots = slots;
        }
    }

    public static final class StorageConfig {
        public final StorageType type;
        public final String name;

        public StorageConfig(StorageType type, String name) {
            this.type = type;
            this.name = name;
        }

        /**
         * Creates a StorageConfig from a YAML map.
         * @param storageMap The YAML map containing storage configuration
         * @return A new StorageConfig instance
         * @throws IllegalArgumentException if the storage configuration is invalid
         */
        public static StorageConfig fromYaml(Map<String, Object> storageMap) {
            // Read type (required)
            String typeStr = String.valueOf(storageMap.get("type")).toUpperCase();
            StorageType storageType;
            try {
                storageType = StorageType.valueOf(typeStr);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid storage type: " + typeStr +
                    ". Valid types: CHEST, DOUBLE_CHEST, DROPPER, HOPPER");
            }

            // Read name (optional, defaults to storage type name)
            String name = storageMap.containsKey("name")
                ? String.valueOf(storageMap.get("name"))
                : storageType.name().replace("_", " ");

            return new StorageConfig(storageType, name);
        }
    }

    public static final class SeatInfo {
        public final Vector3f offset;      // Position offset from ship center
        public final int blockIndex;       // Index of the block this seat is attached to
        public final boolean isDriver;     // Whether this is the driver seat

        public SeatInfo(Vector3f offset, int blockIndex, boolean isDriver) {
            this.offset = offset;
            this.blockIndex = blockIndex;
            this.isDriver = isDriver;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("offset", Arrays.asList(offset.x, offset.y, offset.z));
            map.put("block_index", blockIndex);
            map.put("is_driver", isDriver);
            return map;
        }

        public static SeatInfo fromMap(Map<String, Object> map) {
            @SuppressWarnings("unchecked")
            List<Number> offsetList = (List<Number>) map.get("offset");
            Vector3f offset = new Vector3f(
                offsetList.get(0).floatValue(),
                offsetList.get(1).floatValue(),
                offsetList.get(2).floatValue()
            );
            int blockIndex = ((Number) map.get("block_index")).intValue();
            boolean isDriver = Boolean.TRUE.equals(map.get("is_driver"));
            return new SeatInfo(offset, blockIndex, isDriver);
        }
    }

    /**
     * Represents a cannon on the ship (dispenser with obsidian behind it).
     */
    public static final class CannonInfo {
        public final int dispenserBlockIndex;  // Index to access dispenser's inventory via storages map
        public final int obsidianBlockIndex;   // Index for click detection (obsidian shulker tag)
        public final BlockFace localFacing;    // Dispenser facing direction in local ship space
        public final Vector3f localPosition;   // Position for projectile spawning (dispenser face)
        public long lastFireTime = 0;          // Per-cannon cooldown tracking (not serialized)

        public CannonInfo(int dispenserBlockIndex, int obsidianBlockIndex, BlockFace localFacing, Vector3f localPosition) {
            this.dispenserBlockIndex = dispenserBlockIndex;
            this.obsidianBlockIndex = obsidianBlockIndex;
            this.localFacing = localFacing;
            this.localPosition = localPosition;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("dispenser_block_index", dispenserBlockIndex);
            map.put("obsidian_block_index", obsidianBlockIndex);
            map.put("local_facing", localFacing.name());
            map.put("local_position", Arrays.asList(localPosition.x, localPosition.y, localPosition.z));
            return map;
        }

        public static CannonInfo fromMap(Map<String, Object> map) {
            int dispenserBlockIndex = ((Number) map.get("dispenser_block_index")).intValue();
            int obsidianBlockIndex = ((Number) map.get("obsidian_block_index")).intValue();
            BlockFace localFacing = BlockFace.valueOf((String) map.get("local_facing"));
            @SuppressWarnings("unchecked")
            List<Number> posList = (List<Number>) map.get("local_position");
            Vector3f localPosition = new Vector3f(
                posList.get(0).floatValue(),
                posList.get(1).floatValue(),
                posList.get(2).floatValue()
            );
            return new CannonInfo(dispenserBlockIndex, obsidianBlockIndex, localFacing, localPosition);
        }
    }

    // ===== Serialization for custom ship persistence =====

    /**
     * Serializes this ShipModel to a map for YAML storage.
     * Used for custom ships (block assembly ships) that don't have a model file.
     * Prefab ships don't need this - they just reference their model file path.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();

        // Buoyancy/physics data
        map.put("assembly_yaw", assemblyYaw);
        map.put("total_weight", totalWeight);
        map.put("block_count", blockCount);
        map.put("min_y", minY);
        map.put("max_y", maxY);
        map.put("center_of_volume", Arrays.asList(centerOfVolume.x, centerOfVolume.y, centerOfVolume.z));

        // Serialize parts - include transformation matrix (not in rawYaml for custom ships)
        List<Map<String, Object>> partsList = new ArrayList<>();
        for (ModelPart part : parts) {
            Map<String, Object> partMap = new HashMap<>();
            // Copy rawYaml data (contains block type, blockdata, etc.)
            if (part.rawYaml != null) {
                for (Map.Entry<?, ?> entry : part.rawYaml.entrySet()) {
                    partMap.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }

            // Always serialize transformation matrix from part.local
            // (rawYaml may not have it for custom ships from BlockStructureScanner)
            float[] m = matrixToMinecraftNbt(part.local);
            List<Float> transformList = new ArrayList<>(16);
            for (float f : m) transformList.add(f);
            partMap.put("transformation", transformList);

            partsList.add(partMap);
        }
        map.put("parts", partsList);

        // Serialize seats
        List<Map<String, Object>> seatsList = new ArrayList<>();
        for (SeatInfo seat : seats) {
            seatsList.add(seat.toMap());
        }
        map.put("seats", seatsList);

        // Serialize cannons
        List<Map<String, Object>> cannonsList = new ArrayList<>();
        for (CannonInfo cannon : cannons) {
            cannonsList.add(cannon.toMap());
        }
        map.put("cannons", cannonsList);

        return map;
    }

    /**
     * Deserializes a ShipModel from a map loaded from YAML.
     * Used for custom ships (block assembly ships) that were saved to ships.yml.
     * @param map The serialized model data
     * @return The deserialized ShipModel
     */
    public static ShipModel fromMap(Map<String, Object> map) {
        // Read buoyancy/physics data
        float assemblyYaw = ((Number) map.get("assembly_yaw")).floatValue();
        int totalWeight = ((Number) map.get("total_weight")).intValue();
        int blockCount = ((Number) map.get("block_count")).intValue();
        float minY = ((Number) map.get("min_y")).floatValue();
        float maxY = ((Number) map.get("max_y")).floatValue();

        @SuppressWarnings("unchecked")
        List<Number> covList = (List<Number>) map.get("center_of_volume");
        Vector3f centerOfVolume = new Vector3f(
            covList.get(0).floatValue(),
            covList.get(1).floatValue(),
            covList.get(2).floatValue()
        );

        // Deserialize parts
        List<ModelPart> parts = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> partsList = (List<Map<String, Object>>) map.get("parts");
        for (Map<String, Object> partMap : partsList) {
            // Reconstruct BlockData from stored data
            String blockType = String.valueOf(partMap.get("block"));
            BlockData blockData;

            // Check if we have stored blockdata string (from BlockStructureScanner)
            if (partMap.containsKey("blockdata")) {
                String blockDataStr = String.valueOf(partMap.get("blockdata"));
                blockData = Bukkit.createBlockData(blockDataStr);
            } else {
                blockData = Bukkit.createBlockData(Material.valueOf(blockType));
            }

            // Reconstruct transformation matrix
            @SuppressWarnings("unchecked")
            List<Number> transformList = (List<Number>) partMap.get("transformation");
            float[] m = new float[16];
            for (int i = 0; i < 16; i++) {
                m[i] = transformList.get(i).floatValue();
            }
            Matrix4f local = matrixFromMinecraftNbt(m);

            // Reconstruct collision config
            CollisionConfig collision;
            if (partMap.containsKey("collision")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> collisionMap = (Map<String, Object>) partMap.get("collision");
                boolean enable = !Boolean.FALSE.equals(collisionMap.get("enable"));
                float size = collisionMap.containsKey("size")
                    ? ((Number) collisionMap.get("size")).floatValue()
                    : 1.0f;
                Vector3f offset = new Vector3f(0, 0, 0);
                if (collisionMap.containsKey("offset")) {
                    @SuppressWarnings("unchecked")
                    List<Number> offsetList = (List<Number>) collisionMap.get("offset");
                    offset = new Vector3f(
                        offsetList.get(0).floatValue(),
                        offsetList.get(1).floatValue(),
                        offsetList.get(2).floatValue()
                    );
                }
                collision = new CollisionConfig(enable, size, offset);
            } else {
                collision = new CollisionConfig();
            }

            // Reconstruct storage config if present
            StorageConfig storage = null;
            if (partMap.containsKey("storage")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> storageMap = (Map<String, Object>) partMap.get("storage");
                storage = StorageConfig.fromYaml(storageMap);
            }

            parts.add(new ModelPart(blockData, local, collision, storage, partMap));
        }

        // Deserialize seats
        List<SeatInfo> seats = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> seatsList = (List<Map<String, Object>>) map.get("seats");
        for (Map<String, Object> seatMap : seatsList) {
            seats.add(SeatInfo.fromMap(seatMap));
        }

        // Deserialize cannons
        List<CannonInfo> cannons = new ArrayList<>();
        if (map.containsKey("cannons")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cannonsList = (List<Map<String, Object>>) map.get("cannons");
            for (Map<String, Object> cannonMap : cannonsList) {
                cannons.add(CannonInfo.fromMap(cannonMap));
            }
        }

        // Custom ships use assemblyYaw for display rotation (must match BlockStructureScanner)
        Vector3f initialRotation = new Vector3f(assemblyYaw, 0, 0);
        Vector3f positionOffset = new Vector3f(0, 0, 0);
        Vector3f collisionOffset = new Vector3f(0, 0, 0);
        Matrix3f rotationTransform = new Matrix3f();  // Identity
        float waterFloatOffset = 0f;
        double maxHealth = 40.0;
        double healthRegenPerSecond = 2.0;

        return new ShipModel(parts, new ArrayList<>(), initialRotation, positionOffset,
            collisionOffset, rotationTransform, seats, cannons, waterFloatOffset,
            maxHealth, healthRegenPerSecond, totalWeight, blockCount,
            centerOfVolume, minY, maxY, assemblyYaw);
    }
}


