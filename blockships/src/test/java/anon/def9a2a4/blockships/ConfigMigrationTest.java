package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration must never write a {@code config.yml} it could not read.
 *
 * <p>The bug this pins: Bukkit answers a YAML error with an <b>empty</b> configuration carrying the
 * jar's copy as defaults, and {@code MemorySection.get(path, def)} reads the section's own map without
 * consulting those defaults — so {@code getInt("config-version", 0)} returns 0 for an unreadable file
 * exactly as it does for a genuinely old one. The migration then "upgraded" the empty stub and saved
 * it over the admin's settings. Unrecoverable, and a reinstall does not help, because
 * {@code saveDefaultConfig()} sees a file present.
 */
class ConfigMigrationTest {

    @TempDir
    Path dataFolder;

    @Test
    void anUnparseableConfigIsNeitherMigratedNorRewritten() throws IOException {
        Path file = dataFolder.resolve("config.yml");
        String original = "cannons:\n  tnt-enabled: true\n\tcooldown-ms: 500\n";  // tab -> invalid
        Files.writeString(file, original);

        StubPlugin.Captured log = new StubPlugin.Captured();
        ConfigValidator.MainConfigStatus status =
            ConfigValidator.checkMainConfig(StubPlugin.serving(dataFolder.toFile(), log));

        assertTrue(status.failedToParse(), "a tab-indented config.yml must be reported as unparseable");
        assertTrue(log.has(Level.SEVERE, "could not be parsed"), log.all());
        assertTrue(log.has(Level.SEVERE, "being ignored"),
            "the admin must be told their settings are not in force: " + log.all());

        AtomicInteger saves = new AtomicInteger();
        ConfigMigration.run(pluginOver(file, saves), status);

        assertEquals(0, saves.get(), "a config that failed to parse must never be saved back");
        assertEquals(original, Files.readString(file), "the file must be left exactly as the admin wrote it");
    }

    /**
     * The premise, pinned: this is the state Bukkit leaves behind when config.yml fails to parse — an
     * empty own-map with the jar's copy as defaults. Constructed directly rather than by feeding
     * {@code loadConfiguration} a broken file, because that path logs through {@code Bukkit.getLogger()}
     * and there is no server here.
     */
    @Test
    void defaultsDoNotRescueTheTwoArgGetters() {
        FileConfiguration cfg = new YamlConfiguration();
        cfg.setDefaults(shippedConfig());

        assertTrue(cfg.getKeys(false).isEmpty());
        assertEquals(0, cfg.getInt("config-version", 0),
            "getInt(path, def) reads the own map only - which is why an unparseable file reads as"
                + " version 0 and used to be 'upgraded' and saved over");
        assertEquals(2, cfg.getInt("config-version"),
            "...while the one-arg form does consult defaults, making the broken file look healthy");
        assertFalse(cfg.getBoolean("custom-ships.stats.enabled", false),
            "the migration's own probe reads false here, so it would rewrite the stub as 'changed'");
    }

    @Test
    void aPreVersionedConfigKeepsEveryUserSetting() throws IOException {
        Path file = dataFolder.resolve("config.yml");
        Files.writeString(file, """
            cannons:
              tnt-enabled: true
              cooldown-ms: 500
            custom-ships:
              stats:
                enabled: false
                lift-falloff-exponent: 2.5
            """);

        StubPlugin.Captured log = new StubPlugin.Captured();
        ConfigValidator.MainConfigStatus status =
            ConfigValidator.checkMainConfig(StubPlugin.serving(dataFolder.toFile(), log));
        assertFalse(status.failedToParse(), log.all());

        AtomicInteger saves = new AtomicInteger();
        ConfigMigration.run(pluginOver(file, saves), status);

        assertEquals(1, saves.get(), "a version bump has to be persisted");
        YamlConfiguration after = YamlConfiguration.loadConfiguration(file.toFile());
        assertTrue(after.getBoolean("cannons.tnt-enabled"), "user settings must survive the upgrade");
        assertEquals(500, after.getInt("cannons.cooldown-ms"));
        assertEquals(2, after.getInt("config-version"));
        // v1->v2 drops this deliberately: the replacement exponent runs the opposite way.
        assertFalse(after.contains("custom-ships.stats.lift-falloff-exponent"));
    }

    /**
     * A {@link Plugin} whose {@code getConfig()} is the file on disk and whose {@code saveConfig()}
     * writes it back, counting the writes. Mirrors {@code JavaPlugin}'s own wiring, including hanging
     * the jar copy underneath as defaults.
     */
    private static Plugin pluginOver(Path file, AtomicInteger saves) {
        // JavaPlugin.reloadConfig() in miniature. Its loadConfiguration(File) reports a parse failure
        // through Bukkit.getLogger(), which needs a running server, so the failure is reproduced here
        // the way it lands in production: an empty own-map with the jar copy underneath as defaults.
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(file.toFile());
        } catch (Exception parseFailure) {
            config = new YamlConfiguration();
        }
        config.setDefaults(shippedConfig());
        final FileConfiguration served = config;
        return (Plugin) Proxy.newProxyInstance(
            ConfigMigrationTest.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConfig" -> served;
                case "getLogger" -> java.util.logging.Logger.getAnonymousLogger();
                case "getName" -> "BlockShips";
                case "saveConfig" -> {
                    saves.incrementAndGet();
                    served.save(file.toFile());
                    yield null;
                }
                case "toString" -> "ConfigMigrationTest plugin";
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> {
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) yield false;
                    if (r.isPrimitive()) yield 0;
                    yield null;
                }
            });
    }

    private static YamlConfiguration shippedConfig() {
        try (var in = ConfigMigrationTest.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                throw new IllegalStateException("config.yml is not on the test classpath");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read the shipped config.yml", e);
        }
    }
}
