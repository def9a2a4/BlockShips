package anon.def9a2a4.blockships;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The free-sink rates published in {@code docs/flight-model.md}, checked against the SHIPPED
 * {@code config.yml} (see {@link ShippedConfig} for why that distinction matters).
 *
 * <p>That table was hand-computed and one cell was wrong for a while. {@link
 * ShipStats#sinkBlocksPerSecond} is already {@code public static}, so the whole column can simply be
 * recomputed here — no production visibility had to be widened, and {@code ShipConfig.Builder} stays
 * private.
 *
 * <p><b>Only the free-sink column is covered.</b> The "holding Space" column applies
 * {@code 1 - (1 - liftHoldSinkFactor) * min(1, lift)}, which lives inline in the private instance
 * method {@code ShipPhysics.applyAirshipVerticalPhysics} and needs a live {@code ShipInstance}.
 * Covering it means extracting that expression first; until then those six cells are verified by hand.
 */
class SinkRateTest {

    private static final ShipConfig CONFIG = ShippedConfig.load();

    private static void assertSink(float lift, float expected) {
        float actual = ShipStats.sinkBlocksPerSecond(CONFIG, lift);
        assertEquals(expected, actual, 0.05f,
            "lift " + lift + " should sink at " + expected + " b/s, got " + actual);
    }

    /**
     * Guards the guard: if the shipped values ever drift from what this file assumes, fail HERE with a
     * clear reason rather than leaving the table below to fail with a confusing arithmetic mismatch.
     */
    @Test
    void theShippedConfigStillHasTheValuesThisTableWasComputedFrom() {
        assertEquals(0.5f, CONFIG.maxSinkSpeed, 1e-6f,
            "config.yml's max-sink-speed changed — recompute docs/flight-model.md's sink table");
        assertEquals(0.7f, CONFIG.sinkSpeedExponent, 1e-6f,
            "config.yml's sink-speed-exponent changed — recompute docs/flight-model.md's sink table");
        assertEquals(0.35f, CONFIG.liftHoldSinkFactor, 1e-6f,
            "config.yml's lift-hold-sink-factor changed — recompute the 'holding Space' column by hand");
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
        assertEquals(0f, ShipStats.sinkBlocksPerSecond(CONFIG, 1.0f));
        assertEquals(0f, ShipStats.sinkBlocksPerSecond(CONFIG, 1.5f));
    }

    /** Negative lift is clamped to zero rather than producing a NaN out of {@code Math.pow}. */
    @Test
    void negativeLiftFallsAtTheTerminalRate() {
        assertEquals(10.0f, ShipStats.sinkBlocksPerSecond(CONFIG, -2f), 0.05f);
    }

    @Test
    void sinkRateIncreasesAsLiftFalls() {
        float previous = -1f;
        for (float lift = 0.95f; lift >= 0f; lift -= 0.05f) {
            float rate = ShipStats.sinkBlocksPerSecond(CONFIG, lift);
            assertTrue(rate > previous, "sink rate should rise as lift falls, but stalled at lift " + lift);
            previous = rate;
        }
    }
}
