package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The free-sink rates published in {@code docs/flight-model.md}.
 *
 * <p>That table was hand-computed and one cell was wrong for a while. Since {@link
 * ShipStats#sinkBlocksPerSecond} is already {@code public static} and {@link ShipConfig#load} reads
 * nothing but a {@code FileConfiguration}, the whole column can simply be recomputed here — no
 * visibility had to be widened for this, and the {@code ShipConfig.Builder} stays private.
 *
 * <p><b>Only the free-sink column is covered.</b> The "holding Space" column applies
 * {@code 1 - (1 - liftHoldSinkFactor) * min(1, lift)}, which lives inline in the private instance
 * method {@code ShipPhysics.applyAirshipVerticalPhysics} and needs a live {@code ShipInstance}.
 * Covering it means extracting that expression first; until then those six cells are still verified by
 * hand.
 */
class SinkRateTest {

    /** A {@code Plugin} that answers {@code getConfig()} and nothing else. */
    private static ShipConfig defaultConfig() {
        YamlConfiguration empty = new YamlConfiguration();
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
            SinkRateTest.class.getClassLoader(),
            new Class<?>[] {Plugin.class},
            (p, method, args) -> {
                if (method.getName().equals("getConfig")) return empty;
                if (method.getName().equals("toString")) return "stub plugin";
                if (method.getName().equals("equals")) return p == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                Class<?> r = method.getReturnType();
                if (r == boolean.class) return false;
                if (r.isPrimitive()) return 0;
                return null;
            });
        // An empty config means every getter falls through to load()'s own defaults, which are the
        // shipped ones — so this is the config a fresh server runs with.
        return ShipConfig.load(plugin, "custom");
    }

    private static void assertSink(float lift, float expected) {
        float actual = ShipStats.sinkBlocksPerSecond(defaultConfig(), lift);
        assertEquals(expected, actual, 0.05f,
            "lift " + lift + " should sink at " + expected + " b/s, got " + actual);
    }

    @Test
    void theDocumentedFreeSinkColumnMatchesTheCode() {
        assertSink(0.99f, 0.4f);
        assertSink(0.90f, 2.0f);
        assertSink(0.75f, 3.8f);
        assertSink(0.50f, 6.2f);
        assertSink(0.25f, 8.2f);
        assertSink(0.00f, 10.0f);
    }

    @Test
    void holdingAltitudeOrClimbingDoesNotSink() {
        assertEquals(0f, ShipStats.sinkBlocksPerSecond(defaultConfig(), 1.0f));
        assertEquals(0f, ShipStats.sinkBlocksPerSecond(defaultConfig(), 1.5f));
    }

    /** Negative lift is clamped to zero rather than producing a NaN out of {@code Math.pow}. */
    @Test
    void negativeLiftFallsAtTheTerminalRate() {
        assertEquals(10.0f, ShipStats.sinkBlocksPerSecond(defaultConfig(), -2f), 0.05f);
    }

    @Test
    void sinkRateIncreasesAsLiftFalls() {
        ShipConfig config = defaultConfig();
        float previous = -1f;
        for (float lift = 0.95f; lift >= 0f; lift -= 0.05f) {
            float rate = ShipStats.sinkBlocksPerSecond(config, lift);
            assertTrue(rate > previous, "sink rate should rise as lift falls, but stalled at lift " + lift);
            previous = rate;
        }
    }
}
