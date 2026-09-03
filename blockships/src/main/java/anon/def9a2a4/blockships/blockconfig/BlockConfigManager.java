package anon.def9a2a4.blockships.blockconfig;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.ConfigResources;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
     * Load block configuration from blocks.yml.
     *
     * <p>Parses into a fresh map and swaps only once that succeeds. A reload must never leave the
     * server holding a half-parsed allow-list: an admin who saves a typo and runs {@code /blockships
     * reload} would otherwise trade a working config for jar defaults, live, with ships in the air.
     *
     * @return where the configuration came from, for the startup/reload summary
     */
    public ConfigResources.Loaded loadConfig() {
        // Load blocks.yml from config/ override if present, else straight from the jar.
        // We deliberately do NOT extract a copy to disk: that copy would go stale and hide
        // newly-added blocks (e.g. *_shelf) on plugin updates.
        ConfigResources.Loaded loaded = ConfigResources.loadDetailed(plugin, "blocks.yml");

        // A reload whose override does not parse gets the bundled file back. At startup that is the
        // right answer; here it is not - swapping a working allow-list for the defaults, live, would
        // re-permit everything the admin had disallowed. Keep what is already running instead.
        if (loaded.error() != null && !blockPropertiesCache.isEmpty()) {
            logger.severe("Keeping the block configuration already in force (" + blockPropertiesCache.size()
                + " materials) rather than reverting to the bundled defaults. Fix config/blocks.yml and"
                + " reload again.");
            return loaded;
        }

        FileConfiguration blocksConfig = loaded.config();
        Set<String> keys = blocksConfig.getKeys(false);

        Map<Material, BlockProperties> parsed = new EnumMap<>(Material.class);
        parseInto(blocksConfig, parsed);

        // Parses cleanly but defines no block at all: an empty file, or every entry mis-indented to a
        // scalar (what a botched hand-edit looks like). Applying that would clear the allow-list and
        // disallow every block, live. An empty result must not take effect — fall back to the bundled
        // default, which always has entries, so ships keep picking blocks up. Only an OVERRIDE has a
        // better source to fall back to; a JAR/MISSING source is already the last resort.
        if (parsed.isEmpty()) {
            if (!keys.isEmpty()) {
                logger.warning("blocks.yml parsed " + keys.size() + " top-level entries but none of them"
                    + " defined a block. Each entry needs indented properties under it, e.g."
                    + " \"andesite:\" then \"  allowed: true\" on the next line.");
            }
            if (loaded.source() == ConfigResources.Source.OVERRIDE) {
                logger.severe("config/blocks.yml defined no blocks; using the bundled default instead so"
                    + " ships can still pick blocks up. Fix the override and reload.");
                YamlConfiguration jarDefault = loadJarDefault();
                if (jarDefault != null) parseInto(jarDefault, parsed);
            }
        }

        blockPropertiesCache.clear();
        blockPropertiesCache.putAll(parsed);
        logger.info("Loaded block configuration for " + blockPropertiesCache.size()
            + " materials from " + loaded.describeSource());
        return loaded;
    }

    /**
     * Reload block configuration from blocks.yml
     */
    public ConfigResources.Loaded reloadConfig() {
        return loadConfig();
    }

    /** Runs every top-level entry of {@code src} through the block parser into {@code target}. */
    private void parseInto(FileConfiguration src, Map<Material, BlockProperties> target) {
        for (String key : src.getKeys(false)) {
            ConfigurationSection blockConfig = src.getConfigurationSection(key);
            if (blockConfig == null) {
                continue;
            }
            try {
                parseBlockEntry(target, key, blockConfig);
            } catch (Exception e) {
                logger.warning("Failed to parse block config for '" + key + "': " + e.getMessage());
            }
        }
    }

    /** The blocks.yml bundled in the jar, or null if it cannot be read (already logged at SEVERE). */
    private YamlConfiguration loadJarDefault() {
        try (InputStream in = plugin.getResource("blocks.yml")) {
            if (in == null) {
                logger.severe("Bundled blocks.yml is missing from the jar; block configuration is empty.");
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.severe("Could not read the bundled blocks.yml: " + e.getMessage());
            return null;
        }
    }

    private void parseBlockEntry(Map<Material, BlockProperties> target, String key, ConfigurationSection config) {
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

            applyToMaterials(target, key, baseProps);
        } else {
            // Simple non-conditional properties
            CollisionConfig collider = parseCollider(config.get("collider"));
            BlockProperties props = new BlockProperties(allowed, weight, collider, leadable, seat, displayRotation, interaction, storage, null);

            applyToMaterials(target, key, props);
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

    private void applyToMaterials(Map<Material, BlockProperties> target, String key, BlockProperties properties) {
        if (WildcardMatcher.isTag(key)) {
            // Minecraft tag reference
            Set<Material> materials = resolveTag(key.substring(1));
            for (Material material : materials) {
                target.putIfAbsent(material, properties);
            }
        } else if (WildcardMatcher.isWildcard(key)) {
            // Wildcard pattern
            Set<Material> materials = WildcardMatcher.getMatchingMaterials(key);
            for (Material material : materials) {
                target.putIfAbsent(material, properties);
            }
        } else {
            // Specific material
            try {
                Material material = Material.valueOf(key.toUpperCase());
                target.putIfAbsent(material, properties);
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
     * Whether {@code blocks.yml} says anything at all about this material.
     *
     * <p>Distinguishes "configured with weight 0" from "never mentioned". {@link #getProperties}
     * synthesises a forbidden entry for an unknown material whose {@code weight} autoboxes to
     * {@code Integer 0} — so {@link BlockProperties#hasWeight()} returns true and the block reads as
     * weightless rather than unpriced. That is fine while the allow-list is the only way in, but a
     * GLUED block is deliberately not allow-listed: left alone it would add 0 to the ship's weight
     * while still incrementing the divisor, dragging mean density toward 0 (i.e. toward the airship
     * threshold) and making glue the cheapest way to make a ship fly.
     */
    public boolean isConfigured(Material material) {
        return blockPropertiesCache.containsKey(material);
    }

    /**
     * The signed buoyancy weight to use for a block, or {@code null} when the block is deliberately
     * excluded from density (an explicit {@code weight: null} in blocks.yml).
     *
     * <p>A material blocks.yml has never heard of is, in practice, a GLUED block — nothing else can
     * get into a ship. Those fall back to defCoreLib's mass table so they weigh something real
     * instead of nothing. That table is inertial mass and is always {@code >= 0}, which is the
     * conservative answer here: a glued stone makes a ship sit lower, never higher.
     *
     * <p>Use this rather than {@code getProperties(...).getWeight()} anywhere weight feeds density,
     * mass or health.
     */
    public Integer resolveWeight(Material material, BlockData blockData) {
        if (isConfigured(material)) {
            BlockProperties props = getProperties(material, blockData);
            return props.hasWeight() ? props.getWeight() : null;
        }
        return corelibMass(material);
    }

    /** defCoreLib's inertial mass for a material, rounded to BlockShips' integer weight scale. */
    private static Integer corelibMass(Material material) {
        try {
            double m = anon.def9a2a4.corelib.CoreLibPlugin.getInstance()
                .getMechanismRegistry().massRegistry().get(material, null);
            return (int) Math.round(m);
        } catch (Throwable t) {
            // CoreLib unavailable/faulted — a sane non-zero default beats a weightless ghost block.
            return 1;
        }
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
