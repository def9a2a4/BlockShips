package anon.def9a2a4.blockships;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

/**
 * Checks bundled resource files (blocks, items, prefab ships) against disk versions
 * and warns if they are outdated from a plugin update.
 */
public class ConfigValidator {

    private static final List<String> RESOURCE_FILES = List.of(
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

        for (String resourcePath : RESOURCE_FILES) {
            File diskFile = new File(plugin.getDataFolder(), resourcePath);

            if (!diskFile.exists()) {
                continue;
            }

            try (InputStream jarStream = plugin.getResource(resourcePath)) {
                if (jarStream == null) {
                    continue;
                }

                String jarContent = readStream(jarStream);
                String diskContent = readFile(diskFile);

                if (!jarContent.equals(diskContent)) {
                    logger.warning("Outdated file: " + resourcePath + " differs from the bundled version (probably from a plugin update).");
                    logger.warning("To get new defaults, delete plugins/BlockShips/" + resourcePath + " and restart. Your changes (if any) will be lost.");
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
}
