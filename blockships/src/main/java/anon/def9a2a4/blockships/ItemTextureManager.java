package anon.def9a2a4.blockships;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages item textures loaded from items.yml
 * Provides texture lookup for player head items based on named texture sets and wood types
 */
public class ItemTextureManager {
    private final JavaPlugin plugin;
    private final Map<String, Map<String, String>> textureSets = new HashMap<>();
    private static final String ITEMS_FILE = "items.yml";
    private static final String DEFAULT_KEY = "_DEFAULT";

    public ItemTextureManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Load texture sets from items.yml
     * Expected structure:
     * texture-sets:
     *   CRATES:
     *     _DEFAULT: "base64_texture"
     *     OAK: "base64_texture"
     *     SPRUCE: "base64_texture"
     *     ...
     */
    public void load() {
        File itemsFile = new File(plugin.getDataFolder(), ITEMS_FILE);

        if (!itemsFile.exists()) {
            plugin.getLogger().warning(ITEMS_FILE + " not found. Item textures will not be available.");
            return;
        }

        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(itemsFile);
            ConfigurationSection textureSetsSection = config.getConfigurationSection("texture-sets");

            if (textureSetsSection == null) {
                plugin.getLogger().warning(ITEMS_FILE + " does not contain 'texture-sets' section.");
                return;
            }

            textureSets.clear();
            List<String> loadedSets = new ArrayList<>();

            for (String setName : textureSetsSection.getKeys(false)) {
                ConfigurationSection setSection = textureSetsSection.getConfigurationSection(setName);
                if (setSection == null) continue;

                Map<String, String> textureMap = new HashMap<>();
                for (String woodType : setSection.getKeys(false)) {
                    String texture = setSection.getString(woodType);
                    if (texture != null && !texture.isEmpty()) {
                        textureMap.put(woodType, texture);
                    }
                }

                if (!textureMap.isEmpty()) {
                    textureSets.put(setName, textureMap);
                    loadedSets.add(setName + " (" + textureMap.size() + ")");
                }
            }

            plugin.getLogger().info("Loaded texture sets: " + String.join(", ", loadedSets));

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load " + ITEMS_FILE, e);
        }
    }

    /**
     * Get a texture from a named texture set
     * @param setName The name of the texture set (e.g., "CRATES")
     * @param woodType The wood type (e.g., "OAK", "SPRUCE") - will fall back to _DEFAULT if not found
     * @return The base64 texture string, or null if the set doesn't exist
     */
    public String getTexture(String setName, String woodType) {
        if (setName == null) {
            return null;
        }

        Map<String, String> set = textureSets.get(setName);
        if (set == null) {
            plugin.getLogger().warning("Texture set '" + setName + "' not found in " + ITEMS_FILE);
            return null;
        }

        // Try to get the specific wood type texture
        String texture = set.get(woodType);

        // Fall back to _DEFAULT if specific wood type not found
        if (texture == null && !DEFAULT_KEY.equals(woodType)) {
            texture = set.get(DEFAULT_KEY);
            if (texture == null) {
                plugin.getLogger().warning("Texture set '" + setName + "' has no texture for '" + woodType + "' and no _DEFAULT fallback");
            }
        }

        return texture;
    }

    /**
     * Reload texture sets from items.yml
     */
    public void reload() {
        plugin.getLogger().info("Reloading item textures...");
        load();
    }

    /**
     * Check if a texture set exists
     * @param setName The name of the texture set
     * @return true if the set exists
     */
    public boolean hasTextureSet(String setName) {
        return textureSets.containsKey(setName);
    }

    /**
     * Get the number of loaded texture sets
     * @return The count of texture sets
     */
    public int getTextureSetCount() {
        return textureSets.size();
    }
}
