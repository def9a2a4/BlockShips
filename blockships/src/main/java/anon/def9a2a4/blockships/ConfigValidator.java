package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

/**
 * Validates config files against bundled JAR versions and warns on mismatches.
 */
public class ConfigValidator {

    private static final List<String> CONFIG_FILES = List.of(
        "config.yml",
        "blocks.yml",
        "items.yml",
        "prefab_ships/ship_small.yml",
        "prefab_ships/ship_big.yml",
        "prefab_ships/airship_small.yml"
    );

    public static void checkConfigMismatches(JavaPlugin plugin) {
        if (!plugin.getConfig().getBoolean("warn-config-mismatch", true)) {
            return;
        }

        Logger logger = plugin.getLogger();

        for (String configPath : CONFIG_FILES) {
            File diskFile = new File(plugin.getDataFolder(), configPath);

            if (!diskFile.exists()) {
                continue;
            }

            try (InputStream jarStream = plugin.getResource(configPath)) {
                if (jarStream == null) {
                    continue;
                }

                String jarContent = readStream(jarStream);
                String diskContent = readFile(diskFile);

                if (!jarContent.equals(diskContent)) {
                    logger.warning("Config mismatch: " + configPath + " (probably due to a recent plugin update)");
                    logger.warning("Unless you know what you're doing, you should delete plugins/BlockShips/" + configPath + " and restart. Set 'warn-config-mismatch: false' in config.yml to disable.");
                }
            } catch (IOException e) {
                // Silently ignore read errors
            }
        }
    }

    private static String readStream(InputStream stream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = stream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    private static String readFile(File file) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) != -1) {
                result.write(buffer, 0, length);
            }
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    /**
     * Adds missing config keys from the bundled JAR config into the user's config.
     * Only adds keys that don't exist — never overwrites existing values.
     * Controlled by the "auto-migrate-config" setting (default: true).
     */
    public static void migrateConfig(JavaPlugin plugin) {
        if (!plugin.getConfig().getBoolean("auto-migrate-config", true)) {
            return;
        }

        try (InputStream jarStream = plugin.getResource("config.yml")) {
            if (jarStream == null) return;

            YamlConfiguration jarConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(jarStream, StandardCharsets.UTF_8));

            var diskConfig = plugin.getConfig();
            int added = 0;

            for (String key : jarConfig.getKeys(true)) {
                if (!jarConfig.isConfigurationSection(key) && !diskConfig.contains(key)) {
                    diskConfig.set(key, jarConfig.get(key));
                    added++;
                }
            }

            if (added > 0) {
                plugin.saveConfig();
                plugin.reloadConfig();
                plugin.getLogger().info("Config migration: added " + added + " new config key(s)");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Config migration failed: " + e.getMessage());
        }
    }
}
