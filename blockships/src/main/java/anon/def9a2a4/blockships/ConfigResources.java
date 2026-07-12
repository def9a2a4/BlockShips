package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Resolves bundled content resources (blocks.yml, items.yml, prefab ship models, ...).
 *
 * <p>Content is read straight from the jar so that every plugin update ships current defaults.
 * A server admin may override any of these by dropping an edited copy under the data folder's
 * {@code config/} subfolder; the plugin never writes these files itself. This avoids the stale-copy
 * bug where an old on-disk file (extracted by a previous version) hides newly-added defaults.
 */
public final class ConfigResources {

    private ConfigResources() {
    }

    /**
     * Load a bundled resource: {@code <dataFolder>/config/<path>} if it exists on disk, otherwise the
     * bundled jar copy at {@code <path>}. Returns an empty configuration (never null) if neither is found.
     */
    public static YamlConfiguration load(JavaPlugin plugin, String path) {
        File override = new File(plugin.getDataFolder(), "config/" + path);
        if (override.exists()) {
            return YamlConfiguration.loadConfiguration(override);
        }

        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                plugin.getLogger().severe("Bundled resource missing from jar: " + path);
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed reading bundled resource " + path + ": " + e.getMessage());
            return new YamlConfiguration();
        }
    }
}
