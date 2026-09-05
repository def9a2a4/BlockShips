package anon.def9a2a4.blockships;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Startup and reload checks for configuration that is on disk but not being read.
 *
 * <p>Content resources (blocks.yml, items.yml, prefab ship models) are read from the jar, or from an
 * optional override under the data folder's {@code config/} subfolder - they are no longer extracted
 * to disk. Everything here exists because the failure modes are otherwise silent: an edited file that
 * the plugin does not read looks exactly like a plugin that ignores its config.
 *
 * <p>Only the staleness check is gated by {@code warn-config-mismatch}. That flag means "stop nagging
 * me about drift from the bundled defaults"; it must not silence a file that is being ignored outright,
 * because the admin who turned it off is the one most likely to be quietly mis-configured.
 */
public class ConfigValidator {

    private static final List<String> CONTENT_FILES = List.of(
        "blocks.yml",
        "items.yml",
        "prefab_ships/ship_small.yml",
        "prefab_ships/ship_big.yml",
        "prefab_ships/airship_small.yml"
    );

    /**
     * Whether {@code <dataFolder>/config.yml} could actually be parsed.
     *
     * @param exists     false when there is no file yet (a fresh install, before saveDefaultConfig)
     * @param parseError the YAML error, or null when the file parsed cleanly
     */
    public record MainConfigStatus(boolean exists, String parseError) {
        public boolean failedToParse() {
            return exists && parseError != null;
        }
    }

    /**
     * Strictly parse {@code config.yml} and report a failure as the plugin's own SEVERE.
     *
     * <p>This cannot be inferred from {@code getConfig()}. Bukkit's loader turns a parse error into an
     * <i>empty</i> configuration with the jar's copy hung underneath as defaults, so every read still
     * returns a plausible value and nothing downstream can tell that the admin's file was discarded.
     * The only signal is a SEVERE on Bukkit's logger, which carries no {@code [BlockShips]} prefix.
     *
     * <p>Callers must also treat a failure as "do not write this file" - see {@link ConfigMigration}.
     */
    public static MainConfigStatus checkMainConfig(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) {
            return new MainConfigStatus(false, null);
        }
        try {
            new YamlConfiguration().load(file);
            return new MainConfigStatus(true, null);
        } catch (IOException | InvalidConfigurationException e) {
            String error = String.valueOf(e.getMessage()).replace('\n', ' ');
            Logger logger = plugin.getLogger();
            logger.severe("config.yml could not be parsed: " + error);
            logger.severe("EVERY setting in plugins/" + plugin.getName() + "/config.yml is being ignored"
                + " - the plugin is running entirely on its bundled defaults.");
            logger.severe("Fix the YAML error above, then restart or run /blockships reload."
                + " The file will not be modified while it is unreadable.");
            return new MainConfigStatus(true, error);
        }
    }

    public static void checkForOutdatedResources(Plugin plugin) {
        Logger logger = plugin.getLogger();
        File dataFolder = plugin.getDataFolder();

        warnAboutMisplacedFiles(plugin, logger, dataFolder);

        if (!plugin.getConfig().getBoolean("warn-config-mismatch", true)) {
            return;
        }

        // A config/blocks.yml override that lacks entries the bundled default has is going stale.
        File override = new File(dataFolder, "config/blocks.yml");
        if (!override.isFile()) {
            return;
        }
        ConfigResources.Loaded loaded = ConfigResources.loadDetailed(plugin, "blocks.yml");
        if (loaded.source() != ConfigResources.Source.OVERRIDE) {
            // Unreadable or absent - loadDetailed already said so; a key diff would only contradict it.
            return;
        }
        try (InputStream jarStream = plugin.getResource("blocks.yml")) {
            if (jarStream == null) {
                return;
            }
            YamlConfiguration jarConfig = YamlConfiguration.loadConfiguration(
                new InputStreamReader(jarStream, StandardCharsets.UTF_8));

            Set<String> missing = new TreeSet<>(jarConfig.getKeys(false));
            missing.removeAll(loaded.config().getKeys(false));
            if (!missing.isEmpty()) {
                String sample = missing.stream().limit(5).collect(Collectors.joining(", "));
                // Deliberately phrased as a key diff, not as "these blocks are unavailable": keys may be
                // wildcards (*_shelf) or tags (#wool), so a file that spells the same materials out
                // differently reports phantom misses.
                logger.warning("Override config/blocks.yml does not have " + missing.size()
                    + " entries the bundled default has (e.g. " + sample + "). If those are new blocks,"
                    + " add them to your override - it replaces the bundled file rather than merging with it.");
            }
        } catch (IOException e) {
            logger.warning("Could not compare config/blocks.yml against the bundled default: " + e.getMessage());
        }
    }

    /** Files sitting where the plugin will never look at them. Never gated - these are live misconfigurations. */
    private static void warnAboutMisplacedFiles(Plugin plugin, Logger logger, File dataFolder) {
        for (String path : CONTENT_FILES) {
            File legacy = new File(dataFolder, path);
            if (legacy.isFile()) {
                logger.warning("Found " + path + " at the plugin folder root. This location is NOT read"
                    + " (defaults come from the jar). Your edits to it are being ignored - move it to"
                    + " config/" + path + " to apply them, or delete it.");
            }
        }

        File configDir = new File(dataFolder, "config");
        if (!configDir.isDirectory()) {
            return;
        }

        // config.yml is the one file that lives at the ROOT; a copy under config/ is read by nothing.
        if (new File(configDir, "config.yml").isFile()) {
            logger.warning("Found config/config.yml. The main settings file is read from the plugin folder"
                + " root (plugins/" + plugin.getName() + "/config.yml), never from config/ - that folder is"
                + " only for blocks.yml, items.yml and prefab ship models. Move it up one level to apply it.");
        }

        Set<String> recognized = new HashSet<>(CONTENT_FILES);
        recognized.add("config.yml");  // reported above; don't list it twice
        var shipsSection = plugin.getConfig().getConfigurationSection("ships");
        if (shipsSection != null) {
            for (String shipType : shipsSection.getKeys(false)) {
                String modelPath = plugin.getConfig().getString("ships." + shipType + ".model-path");
                if (modelPath != null) {
                    recognized.add(modelPath);
                }
            }
        }

        List<String> unrecognized = new ArrayList<>();
        collectYamlFiles(configDir, "", unrecognized);
        unrecognized.removeAll(recognized);
        if (!unrecognized.isEmpty()) {
            logger.info("Files in config/ that the plugin does not read: "
                + String.join(", ", unrecognized) + ". Overrides must be named exactly like the bundled"
                + " file they replace (" + String.join(", ", CONTENT_FILES) + ").");
        }
    }

    /** Data-folder-relative, slash-normalized .yml paths under {@code dir}. Directories are not flagged. */
    private static void collectYamlFiles(File dir, String prefix, List<String> out) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            String relative = prefix + entry.getName();
            if (entry.isDirectory()) {
                // Never descend a symlinked directory: isDirectory() follows links, so a cycle under
                // config/ recurses to StackOverflowError out of onEnable. Skipping is safe for legitimate
                // symlinked setups too — the loader reads overrides through their own path regardless;
                // this diagnostic just won't enumerate that subtree. Symlinked .yml FILES stay listed,
                // matching what the loader reads.
                if (java.nio.file.Files.isSymbolicLink(entry.toPath())) continue;
                collectYamlFiles(entry, relative + "/", out);
            } else if (relative.endsWith(".yml")) {
                out.add(relative);
            }
        }
    }
}
