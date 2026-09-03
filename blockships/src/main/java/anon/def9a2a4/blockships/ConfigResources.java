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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves bundled content resources (blocks.yml, items.yml, prefab ship models, ...).
 *
 * <p>Content is read straight from the jar so that every plugin update ships current defaults.
 * A server admin may override any of these by dropping an edited copy under the data folder's
 * {@code config/} subfolder; the plugin never writes these files itself. This avoids the stale-copy
 * bug where an old on-disk file (extracted by a previous version) hides newly-added defaults.
 *
 * <p>An override <b>fully replaces</b> the bundled copy — the two are not merged. That makes a
 * broken override consequential, so this class refuses to paper over one: a file that does not parse
 * is reported at SEVERE and the jar copy is used instead, rather than the empty configuration that
 * {@link YamlConfiguration#loadConfiguration(File)} silently hands back (it swallows the parse error
 * into Bukkit's own logger, where it carries no plugin prefix and is easy to scroll past).
 */
public final class ConfigResources {

    private ConfigResources() {
    }

    /** Where a resource's content actually came from. */
    public enum Source {
        /** The admin's copy under {@code <dataFolder>/config/}. */
        OVERRIDE,
        /** The copy bundled in the plugin jar. */
        JAR,
        /** Neither could be read — the returned configuration is empty. */
        MISSING
    }

    /**
     * A loaded resource together with its provenance.
     *
     * @param error the parse failure that forced a fallback, or null if the load was clean
     */
    public record Loaded(YamlConfiguration config, Source source, String path, String error) {

        /** Human phrase for the startup/reload summary, e.g. {@code "your override (config/blocks.yml)"}. */
        public String describeSource() {
            return switch (source) {
                case OVERRIDE -> "your override (config/" + path + ")";
                case JAR -> "the jar";
                case MISSING -> "nowhere - it could not be read";
            };
        }
    }

    /**
     * Last problem reported per path, so a resource re-read on a hot path does not spam the console.
     *
     * <p>{@code ShipModel.fromFile} reaches this class from {@code DisplayShip.loadModelForState},
     * which retries on every chunk load; without this a single broken prefab override would log
     * forever. A path whose problem changes (or is fixed and re-broken) reports again.
     */
    private static final Map<String, String> LAST_PROBLEM = new ConcurrentHashMap<>();

    /** Where each resource was last read from, for the startup/reload summary. */
    private static final Map<String, Source> LAST_SOURCE = new ConcurrentHashMap<>();

    /**
     * Forget what has been loaded and reported.
     *
     * <p>Called at enable and on {@code /blockships reload}. Without it, an admin who reloads a second
     * time without fixing a broken override would get silence — the dedupe above cannot tell "still
     * broken, already told you" from "still broken, and you just asked again".
     */
    public static void resetReporting() {
        LAST_PROBLEM.clear();
        LAST_SOURCE.clear();
    }

    /**
     * One line naming which content files came from an override and which from the jar.
     *
     * <p>This is the only place an <i>upgraded</i> server is told the {@code config/} folder exists:
     * the comments in config.yml reach fresh installs only, because {@code saveDefaultConfig()} never
     * rewrites a file that is already there.
     */
    public static String describeSources() {
        if (LAST_SOURCE.isEmpty()) {
            return "Content files: none loaded yet.";
        }
        List<String> overridden = new ArrayList<>();
        List<String> bundled = new ArrayList<>();
        for (Map.Entry<String, Source> entry : new TreeMap<>(LAST_SOURCE).entrySet()) {
            (entry.getValue() == Source.OVERRIDE ? overridden : bundled).add(entry.getKey());
        }

        if (overridden.isEmpty()) {
            return "Content files: all from the jar (" + String.join(", ", bundled) + "). To customize"
                + " one, put an edited copy in the plugin's config/ folder, e.g. config/blocks.yml"
                + " - see the README. A copy at the plugin folder root is not read.";
        }
        String summary = "Content files: from your config/ overrides: " + String.join(", ", overridden) + ".";
        if (!bundled.isEmpty()) {
            summary += " From the jar: " + String.join(", ", bundled) + ".";
        }
        return summary;
    }

    /**
     * Load a bundled resource: {@code <dataFolder>/config/<path>} if it exists on disk, otherwise the
     * bundled jar copy at {@code <path>}. Returns an empty configuration (never null) if neither is found.
     */
    public static YamlConfiguration load(Plugin plugin, String path) {
        return loadDetailed(plugin, path).config();
    }

    /** As {@link #load}, but also reports where the content came from. */
    public static Loaded loadDetailed(Plugin plugin, String path) {
        Loaded loaded = resolve(plugin, path);
        LAST_SOURCE.put(path, loaded.source());
        return loaded;
    }

    private static Loaded resolve(Plugin plugin, String path) {
        File override = new File(plugin.getDataFolder(), "config/" + path);
        if (override.isFile()) {
            YamlConfiguration parsed = new YamlConfiguration();
            try {
                parsed.load(override);
            } catch (IOException | InvalidConfigurationException e) {
                String error = e.getMessage();
                // Fall back to the jar rather than refusing to enable: aborting onEnable would skip
                // onDisable's wheel-save and display shutdown, stranding every assembled ship in the
                // world as orphaned display entities nobody can drive or disassemble.
                reportOnce(plugin, path, "parse-failed:" + error, () -> {
                    plugin.getLogger().severe("config/" + path + " could not be parsed: " + error);
                    plugin.getLogger().severe("Falling back to the bundled " + path + ". Anything you"
                        + " disallowed or re-weighted in your override is back to the default until this is"
                        + " fixed. Correct the YAML, then run /blockships reload.");
                });
                return fromJar(plugin, path, error);
            }

            // An empty override is honoured, not overruled: overrides replace rather than merge, so
            // "no entries" is a coherent (if drastic) choice, and quietly substituting the bundled
            // file would invert the admin's intent. It is loud because it is far more often a mistake.
            if (parsed.getKeys(false).isEmpty()) {
                reportOnce(plugin, path, "empty", () ->
                    plugin.getLogger().warning("config/" + path + " is empty, so it defines nothing at all."
                        + " An override replaces the bundled file rather than adding to it - delete the"
                        + " override to go back to the bundled " + path + "."));
            } else {
                reportOnce(plugin, path, "", () -> {});
            }
            return new Loaded(parsed, Source.OVERRIDE, path, null);
        }

        return fromJar(plugin, path, null);
    }

    private static Loaded fromJar(Plugin plugin, String path, String overrideError) {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                plugin.getLogger().severe("Bundled resource missing from jar: " + path);
                return new Loaded(new YamlConfiguration(), Source.MISSING, path, overrideError);
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
            return new Loaded(config, Source.JAR, path, overrideError);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed reading bundled resource " + path + ": " + e.getMessage());
            return new Loaded(new YamlConfiguration(), Source.MISSING, path, overrideError);
        }
    }

    private static void reportOnce(Plugin plugin, String path, String problem, Runnable report) {
        if (!problem.equals(LAST_PROBLEM.put(path, problem))) {
            report.run();
        }
    }
}
