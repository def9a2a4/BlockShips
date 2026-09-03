package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins where {@code blocks.yml} is read from, and what happens when the admin's copy is broken.
 *
 * <p>Issue #43: an override at the wrong path is read by nothing, and a broken one used to come back
 * as an <i>empty</i> configuration — which reads downstream as "no block may be used in a ship"
 * rather than as an error.
 */
class ConfigResourcesTest {

    @TempDir
    Path dataFolder;

    private StubPlugin.Captured log;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        ConfigResources.resetReporting();
        log = new StubPlugin.Captured();
        plugin = StubPlugin.serving(dataFolder.toFile(), log);
    }

    private void writeOverride(String contents) throws IOException {
        Path override = dataFolder.resolve("config/blocks.yml");
        Files.createDirectories(override.getParent());
        Files.writeString(override, contents);
    }

    /** The bundled file has hundreds of entries; any real subset of it is unmistakable. */
    private static int bundledEntryCount() {
        return shippedBlocks().getKeys(false).size();
    }

    @Test
    void withoutAnOverrideItReadsTheJar() {
        ConfigResources.Loaded loaded = ConfigResources.loadDetailed(plugin, "blocks.yml");

        assertEquals(ConfigResources.Source.JAR, loaded.source());
        assertEquals(bundledEntryCount(), loaded.config().getKeys(false).size());
        assertTrue(ConfigResources.describeSources().contains("config/blocks.yml"),
            "the summary must point at the override path when nothing is overridden: "
                + ConfigResources.describeSources());
    }

    @Test
    void aValidOverrideWins() throws IOException {
        writeOverride("andesite:\n  allowed: true\n  weight: 4\n");

        ConfigResources.Loaded loaded = ConfigResources.loadDetailed(plugin, "blocks.yml");

        assertEquals(ConfigResources.Source.OVERRIDE, loaded.source());
        assertEquals(1, loaded.config().getKeys(false).size());
        assertTrue(loaded.config().getBoolean("andesite.allowed"));
    }

    /** The reported bug: edits at the data-folder root are read by nothing. */
    @Test
    void aCopyAtTheDataFolderRootIsIgnored() throws IOException {
        Files.writeString(dataFolder.resolve("blocks.yml"), "andesite:\n  allowed: true\n");

        ConfigResources.Loaded loaded = ConfigResources.loadDetailed(plugin, "blocks.yml");

        assertEquals(ConfigResources.Source.JAR, loaded.source());
        assertFalse(loaded.config().contains("andesite"),
            "a root-level blocks.yml must not be read - ConfigValidator warns about it instead");
    }

    @Test
    void aMalformedOverrideFallsBackToTheJarAndSaysSo() throws IOException {
        writeOverride("andesite:\n  allowed: true\n\tweight: 4\n");  // tab: never valid YAML indentation

        ConfigResources.Loaded loaded = ConfigResources.loadDetailed(plugin, "blocks.yml");

        assertEquals(ConfigResources.Source.JAR, loaded.source(),
            "a broken override must not become an empty config that forbids every block");
        assertEquals(bundledEntryCount(), loaded.config().getKeys(false).size());
        assertTrue(loaded.error() != null && !loaded.error().isBlank(), "the parse error must be reported back");
        assertTrue(log.has(Level.SEVERE, "could not be parsed"), log.all());
        assertTrue(log.has(Level.SEVERE, "back to the default"),
            "the admin must be told their restrictions are not in force: " + log.all());
    }

    /**
     * An empty override is honoured, not overruled. Overrides replace rather than merge, so "nothing is
     * allowed" is a coherent choice; substituting the bundled file would invert it.
     */
    @Test
    void anEmptyOverrideIsHonouredWithAWarning() throws IOException {
        writeOverride("");

        ConfigResources.Loaded loaded = ConfigResources.loadDetailed(plugin, "blocks.yml");

        assertEquals(ConfigResources.Source.OVERRIDE, loaded.source());
        assertTrue(loaded.config().getKeys(false).isEmpty());
        assertTrue(log.has(Level.WARNING, "is empty"), log.all());
    }

    @Test
    void repeatedLoadsDoNotSpamButAReloadReportsAgain() throws IOException {
        writeOverride("andesite:\n\tallowed: true\n");

        ConfigResources.loadDetailed(plugin, "blocks.yml");
        int afterFirst = log.lines().size();
        ConfigResources.loadDetailed(plugin, "blocks.yml");
        assertEquals(afterFirst, log.lines().size(),
            "a hot path (prefab models retry per chunk load) must not re-log the same problem");

        ConfigResources.resetReporting();
        ConfigResources.loadDetailed(plugin, "blocks.yml");
        assertTrue(log.lines().size() > afterFirst,
            "an explicit reload must report a still-broken file again");
    }

    @Test
    void aMissingJarResourceIsReportedRatherThanReturningNull() {
        ConfigResources.Loaded loaded = ConfigResources.loadDetailed(plugin, "no_such_file.yml");

        assertEquals(ConfigResources.Source.MISSING, loaded.source());
        assertTrue(loaded.config().getKeys(false).isEmpty());
        assertTrue(log.has(Level.SEVERE, "missing from jar"), log.all());
    }

    /** The real bundled blocks.yml, read off the test classpath the way {@link ShippedConfig} does. */
    private static YamlConfiguration shippedBlocks() {
        try (var in = ConfigResourcesTest.class.getResourceAsStream("/blocks.yml")) {
            if (in == null) {
                throw new IllegalStateException("blocks.yml is not on the test classpath");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read the shipped blocks.yml", e);
        }
    }
}
