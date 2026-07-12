package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Content resources (blocks.yml, items.yml, prefab ship models) are read from the jar, or from an
 * optional override under the data folder's {@code config/} subfolder - they are no longer extracted
 * to disk. This validator:
 *   1. warns if a legacy copy still lives at the plugin-folder root (from an older version) where it is
 *      no longer read, so admins know to move or delete it, and
 *   2. warns if a {@code config/} override is missing block entries the bundled default has (i.e. the
 *      override is going stale and may hide newly-added blocks).
 */
public class ConfigValidator {

    private static final List<String> CONTENT_FILES = List.of(
        "blocks.yml",
        "items.yml",
        "prefab_ships/ship_small.yml",
        "prefab_ships/ship_big.yml",
        "prefab_ships/airship_small.yml"
    );

    public static void checkForOutdatedResources(JavaPlugin plugin) {
        if (!plugin.getConfig().getBoolean("warn-config-mismatch", true)) {
            return;
        }

        Logger logger = plugin.getLogger();
        File dataFolder = plugin.getDataFolder();

        for (String path : CONTENT_FILES) {
            // 1. Legacy copy at the data-folder root is no longer read.
            File legacy = new File(dataFolder, path);
            if (legacy.exists()) {
                logger.warning("Found " + path + " at the plugin folder root. This location is no longer read"
                    + " (defaults now come from the jar). To keep customizations, move it to config/" + path
                    + " - otherwise you can delete it.");
            }
        }

        // 2. A config/blocks.yml override that lacks entries the bundled default has is going stale.
        File override = new File(dataFolder, "config/blocks.yml");
        if (override.exists()) {
            try (InputStream jarStream = plugin.getResource("blocks.yml")) {
                if (jarStream == null) {
                    return;
                }
                YamlConfiguration jarConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(jarStream, StandardCharsets.UTF_8));
                YamlConfiguration diskConfig = YamlConfiguration.loadConfiguration(override);

                Set<String> missing = new TreeSet<>(jarConfig.getKeys(false));
                missing.removeAll(diskConfig.getKeys(false));
                if (!missing.isEmpty()) {
                    String sample = missing.stream().limit(5).collect(Collectors.joining(", "));
                    logger.warning("Override config/blocks.yml is missing " + missing.size()
                        + " entries present in the bundled default (e.g. " + sample + "). Those blocks may be"
                        + " unavailable in ships until you add them to your override or delete it to use jar defaults.");
                }
            } catch (IOException e) {
                // Silently ignore read errors
            }
        }
    }
}
