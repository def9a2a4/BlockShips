package anon.def9a2a4.blockships.blockconfig;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.ShipModel;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Vector3f;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.logging.Logger;

/**
 * Manages block configuration for custom ships.
 * Parses blocks.yml and provides fast lookups.
 */
public class BlockConfigManager {
    private static BlockConfigManager instance;
    private final Map<Material, BlockProperties> blockPropertiesCache = new EnumMap<>(Material.class);
    private final BlockShipsPlugin plugin;
    private final Logger logger;

    private BlockConfigManager(BlockShipsPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public static void initialize(BlockShipsPlugin plugin) {
        if (instance == null) {
            instance = new BlockConfigManager(plugin);
        }
    }

    public static BlockConfigManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("BlockConfigManager not initialized! Call initialize() first.");
        }
        return instance;
    }

    /**
     * Load block configuration from blocks.yml
     */
    public void loadConfig() {
        blockPropertiesCache.clear();

        // Load blocks.yml
        File blocksFile = new File(plugin.getDataFolder(), "blocks.yml");

        // Save default blocks.yml if it doesn't exist
        if (!blocksFile.exists()) {
            plugin.saveResource("blocks.yml", false);
        }

        FileConfiguration blocksConfig = YamlConfiguration.loadConfiguration(blocksFile);

        // Parse all block entries from root level
        for (String key : blocksConfig.getKeys(false)) {
            ConfigurationSection blockConfig = blocksConfig.getConfigurationSection(key);
            if (blockConfig == null) {
                continue;
            }

            try {
                parseBlockEntry(key, blockConfig);
            } catch (Exception e) {
                logger.warning("Failed to parse block config for '" + key + "': " + e.getMessage());
                e.printStackTrace();
            }
        }

        logger.info("Loaded block configuration for " + blockPropertiesCache.size() + " materials from blocks.yml");
    }

    /**
     * Reload block configuration from blocks.yml
     */
    public void reloadConfig() {
        loadConfig();
    }

    private void parseOldFormat() {
        // Legacy support: check if blocks are in config.yml instead
        ConfigurationSection blocksSection = plugin.getConfig().getConfigurationSection("blocks");
        if (blocksSection == null) {
            return;
        }

        logger.info("Found legacy blocks configuration in config.yml, loading from there instead");
        blockPropertiesCache.clear();

        // Parse all block entries
        for (String key : blocksSection.getKeys(false)) {
            ConfigurationSection blockConfig = blocksSection.getConfigurationSection(key);
            if (blockConfig == null) {
                continue;
            }

            try {
                parseBlockEntry(key, blockConfig);
            } catch (Exception e) {
                logger.warning("Failed to parse block config for '" + key + "': " + e.getMessage());
                e.printStackTrace();
            }
        }

        logger.info("Loaded block configuration for " + blockPropertiesCache.size() + " materials");
    }

    private void parseBlockEntry(String key, ConfigurationSection config) {
        // Parse base properties
        boolean allowed = config.getBoolean("allowed", false);

        // Parse weight - null means block is excluded from density calculations
        Integer weight;
        if (config.contains("weight") && config.get("weight") == null) {
            // Explicit null in YAML
            weight = null;
        } else {
            weight = config.getInt("weight", 0);
        }

        boolean leadable = config.getBoolean("leadable", false);
        boolean seat = config.getBoolean("seat", false);
        boolean displayRotation = config.getBoolean("display_rotation", false);
        boolean interaction = config.getBoolean("interaction", false);

        // Parse storage config if present
        ShipModel.StorageConfig storage = parseStorage(config.getConfigurationSection("storage"));

        // Check for conditional rules
        if (config.contains("collider.type") && config.getString("collider.type").equals("conditional")) {
            // Has conditional rules - rules is a YAML list, not a section
            List<?> rulesList = config.getList("collider.rules");

            // Parse conditional properties
            List<BlockProperties.ConditionalRule> conditionalRules = parseConditionalRules(rulesList);
            BlockProperties baseProps = new BlockProperties(allowed, weight, CollisionConfig.DEFAULT, leadable, seat, displayRotation, interaction, storage, conditionalRules);

            applyToMaterials(key, baseProps);
        } else {
            // Simple non-conditional properties
            CollisionConfig collider = parseCollider(config.get("collider"));
            BlockProperties props = new BlockProperties(allowed, weight, collider, leadable, seat, displayRotation, interaction, storage, null);

            applyToMaterials(key, props);
        }
    }

    private List<BlockProperties.ConditionalRule> parseConditionalRules(List<?> rulesConfigs) {
        List<BlockProperties.ConditionalRule> rules = new ArrayList<>();
        if (rulesConfigs == null) return rules;

        for (Object ruleObj : rulesConfigs) {
            if (!(ruleObj instanceof Map<?, ?> ruleMap)) continue;

            Object conditionObj = ruleMap.get("condition");
            if (!(conditionObj instanceof Map<?, ?> conditionMap)) continue;

            // Parse condition
            BlockProperties.BlockDataMatcher matcher = createMatcherFromMap(conditionMap);

            // Parse properties for this condition
            CollisionConfig collider = parseCollider(ruleMap.get("collider"));
            boolean seat = ruleMap.containsKey("seat") && Boolean.TRUE.equals(ruleMap.get("seat"));

            // Create properties (inherit weight/allowed from parent)
            BlockProperties props = new BlockProperties(true, 0, collider, false, seat);

            rules.add(new BlockProperties.ConditionalRule(matcher, props));
        }

        return rules;
    }

    private BlockProperties.BlockDataMatcher createMatcher(ConfigurationSection conditionSection) {
        Map<String, String> conditions = new HashMap<>();
        for (String key : conditionSection.getKeys(false)) {
            conditions.put(key, conditionSection.getString(key));
        }

        return blockData -> {
            for (Map.Entry<String, String> condition : conditions.entrySet()) {
                String property = condition.getKey();
                String expectedValue = condition.getValue().toUpperCase();

                // Check different block data types
                if (blockData instanceof Slab slab) {
                    if (property.equals("type") && !slab.getType().name().equals(expectedValue)) {
                        return false;
                    }
                } else if (blockData instanceof Stairs stairs) {
                    if (property.equals("half") && !stairs.getHalf().name().equals(expectedValue)) {
                        return false;
                    }
                    if (property.equals("shape") && !stairs.getShape().name().equals(expectedValue)) {
                        return false;
                    }
                    if (property.equals("facing") && !stairs.getFacing().name().equals(expectedValue)) {
                        return false;
                    }
                } else if (blockData instanceof Orientable orientable) {
                    if (property.equals("axis") && !orientable.getAxis().name().equals(expectedValue)) {
                        return false;
                    }
                }
            }
            return true;
        };
    }

    private BlockProperties.BlockDataMatcher createMatcherFromMap(Map<?, ?> conditionMap) {
        Map<String, String> conditions = new HashMap<>();
        for (Map.Entry<?, ?> entry : conditionMap.entrySet()) {
            conditions.put(entry.getKey().toString(), entry.getValue().toString());
        }

        return blockData -> {
            for (Map.Entry<String, String> condition : conditions.entrySet()) {
                String property = condition.getKey();
                String expectedValue = condition.getValue().toUpperCase();

                // Check different block data types
                if (blockData instanceof Slab slab) {
                    if (property.equals("type") && !slab.getType().name().equals(expectedValue)) {
                        return false;
                    }
                } else if (blockData instanceof Stairs stairs) {
                    if (property.equals("half") && !stairs.getHalf().name().equals(expectedValue)) {
                        return false;
                    }
                    if (property.equals("shape") && !stairs.getShape().name().equals(expectedValue)) {
                        return false;
                    }
                    if (property.equals("facing") && !stairs.getFacing().name().equals(expectedValue)) {
                        return false;
                    }
                } else if (blockData instanceof Orientable orientable) {
                    if (property.equals("axis") && !orientable.getAxis().name().equals(expectedValue)) {
                        return false;
                    }
                }
            }
            return true;
        };
    }

    private CollisionConfig parseCollider(Object colliderValue) {
        if (colliderValue == null) {
            return CollisionConfig.DEFAULT;
        }

        if (colliderValue instanceof Boolean) {
            return ((Boolean) colliderValue) ? CollisionConfig.DEFAULT : CollisionConfig.NONE;
        }

        if (colliderValue instanceof ConfigurationSection colliderSection) {
            float size = (float) colliderSection.getDouble("size", 1.0);
            List<?> offsetList = colliderSection.getList("offset");
            Vector3f offset = ShipModel.readVector3fFromList(offsetList, new Vector3f(0, 0, 0));
            return new CollisionConfig(true, size, offset);
        }

        // Handle Map objects (from YAML parsing within conditional rule lists)
        if (colliderValue instanceof Map<?, ?> colliderMap) {
            float size = 1.0f;
            if (colliderMap.containsKey("size")) {
                size = ((Number) colliderMap.get("size")).floatValue();
            }
            Object offsetObj = colliderMap.get("offset");
            Vector3f offset = new Vector3f(0, 0, 0);
            if (offsetObj instanceof List<?> offsetList) {
                offset = ShipModel.readVector3fFromList(offsetList, offset);
            }
            return new CollisionConfig(true, size, offset);
        }

        return CollisionConfig.DEFAULT;
    }

    private ShipModel.StorageConfig parseStorage(ConfigurationSection storageSection) {
        if (storageSection == null) {
            return null;
        }

        String typeStr = storageSection.getString("type", "CHEST");
        String name = storageSection.getString("name", "Storage");

        ShipModel.StorageType storageType;
        try {
            storageType = ShipModel.StorageType.valueOf(typeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.warning("Unknown storage type: " + typeStr + ", defaulting to CHEST");
            storageType = ShipModel.StorageType.CHEST;
        }

        return new ShipModel.StorageConfig(storageType, name);
    }

    private void applyToMaterials(String key, BlockProperties properties) {
        if (WildcardMatcher.isTag(key)) {
            // Minecraft tag reference
            Set<Material> materials = resolveTag(key.substring(1));
            for (Material material : materials) {
                blockPropertiesCache.putIfAbsent(material, properties);
            }
        } else if (WildcardMatcher.isWildcard(key)) {
            // Wildcard pattern
            Set<Material> materials = WildcardMatcher.getMatchingMaterials(key);
            for (Material material : materials) {
                blockPropertiesCache.putIfAbsent(material, properties);
            }
        } else {
            // Specific material
            try {
                Material material = Material.valueOf(key.toUpperCase());
                blockPropertiesCache.putIfAbsent(material, properties);
            } catch (IllegalArgumentException e) {
                logger.warning("Unknown material: " + key);
            }
        }
    }

    private Set<Material> resolveTag(String tagName) {
        try {
            NamespacedKey key = NamespacedKey.minecraft(tagName.toLowerCase());
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);

            if (tag != null) {
                return tag.getValues();
            } else {
                logger.warning("Unknown tag: #" + tagName);
                return EnumSet.noneOf(Material.class);
            }
        } catch (Exception e) {
            logger.warning("Failed to resolve tag #" + tagName + ": " + e.getMessage());
            return EnumSet.noneOf(Material.class);
        }
    }

    /**
     * Check if a material is allowed for ship construction.
     */
    public boolean isAllowed(Material material) {
        BlockProperties props = blockPropertiesCache.get(material);
        return props != null && props.isAllowed();
    }

    /**
     * Get properties for a specific block (considering block state).
     */
    public BlockProperties getProperties(Material material, BlockData blockData) {
        BlockProperties baseProps = blockPropertiesCache.get(material);
        if (baseProps == null) {
            // Not in config = forbidden
            return new BlockProperties(false, 0, CollisionConfig.NONE, false, false);
        }

        // Apply conditional rules if any
        return baseProps.getPropertiesForBlockData(blockData);
    }

    /**
     * Get base properties for a material (without considering block state).
     */
    public BlockProperties getProperties(Material material) {
        BlockProperties props = blockPropertiesCache.get(material);
        if (props == null) {
            return new BlockProperties(false, 0, CollisionConfig.NONE, false, false);
        }
        return props;
    }
}
