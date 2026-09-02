package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

/**
 * A {@link ShipConfig} built from the <b>real, shipped</b> {@code config.yml}.
 *
 * <p>Tests used to build one from an EMPTY {@code YamlConfiguration}, which made every getter fall
 * through to the hardcoded fallback in {@link ShipConfig#load}. That is not the same thing, and
 * assuming it was would have gone unnoticed: {@code config.yml} and those fallbacks currently disagree
 * in at least six places ({@code water-density} 3 vs 2.5, {@code stats.enabled} true vs false, and the
 * four {@code entity-masses}). A test pinning the fallback would stay green while an edit to the
 * shipped file silently invalidated the numbers published in {@code docs/flight-model.md} — the exact
 * drift those tests exist to catch.
 *
 * <p>Works without a server: {@code config.yml} is a plain resource on the test classpath (Gradle's
 * {@code processResources} puts it in {@code build/resources/main}), {@code YamlConfiguration} is a
 * pure parser, and {@link ShipConfig#load} touches its {@code Plugin} argument exactly once — for
 * {@code getConfig()} — so a {@link Proxy} covers it.
 */
public final class ShippedConfig {

    private ShippedConfig() {}

    /** The config a freshly installed server actually runs with, for ship type {@code custom}. */
    public static ShipConfig load() {
        return ShipConfig.load(pluginServing(yaml()), "custom");
    }

    /** The parsed {@code config.yml}, for tests that want to read a key directly. */
    public static YamlConfiguration yaml() {
        try (InputStream in = ShippedConfig.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                throw new IllegalStateException(
                    "config.yml is not on the test classpath — did processResources run?");
            }
            return YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("could not read the shipped config.yml", e);
        }
    }

    /** A {@code Plugin} that answers {@code getConfig()} and nothing else. */
    private static Plugin pluginServing(YamlConfiguration config) {
        return (Plugin) Proxy.newProxyInstance(
            ShippedConfig.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConfig" -> config;
                case "toString" -> "ShippedConfig stub plugin";
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
}
