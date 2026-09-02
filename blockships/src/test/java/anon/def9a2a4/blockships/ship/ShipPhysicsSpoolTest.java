package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShippedConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thrust spool-up and spool-down, measured rather than asserted in prose.
 *
 * <p>This exists because the claim "a ramp takes {@code thrustSpoolTicks} in either direction" was
 * written in five separate places — the physics javadoc twice, {@code ShipConfig}, {@code config.yml}
 * and the flight-model document — and was false in all five. Spool-DOWN scales its step by the
 * shrinking current value, so it decays instead of ramping. Nobody noticed for as long as the only
 * record of the behaviour was a sentence.
 *
 * <p>The spool constant is read from the SHIPPED {@code config.yml} rather than hardcoded, so that
 * changing it turns this red instead of quietly invalidating the tick counts those five texts now
 * quote.
 */
class ShipPhysicsSpoolTest {

    private static final int SPOOL_TICKS = ShippedConfig.load().thrustSpoolTicks;
    private static final float STEP = 1f / SPOOL_TICKS;

    /** Ticks for {@code approach} to carry {@code from} all the way to {@code to}. */
    private static int ticksToReach(float from, float to, float step) {
        float v = from;
        for (int tick = 1; tick <= 100_000; tick++) {
            v = ShipPhysics.approach(v, to, step);
            if (v == to) return tick;
        }
        throw new AssertionError("approach never reached " + to + " from " + from);
    }

    private static int ticksToReach(float from, float to) {
        return ticksToReach(from, to, STEP);
    }

    /**
     * Guards the guard: the tick counts below were computed at 40. If the shipped value moves, fail
     * here with the reason rather than leaving the assertions to fail as bare arithmetic.
     */
    @Test
    void theShippedSpoolConstantIsStillWhatTheseCountsAssume() {
        assertEquals(40, SPOOL_TICKS,
            "config.yml's thrust-spool-ticks changed — recompute the tick counts in this file, "
                + "ShipPhysics.approach's javadoc, ShipConfig, config.yml and docs/flight-model.md");
    }

    @Test
    void spoolUpTakesExactlyTheConfiguredTicks() {
        assertEquals(SPOOL_TICKS, ticksToReach(0f, 250f),
            "ramping up should be exactly thrust-spool-ticks — that half of the claim is true");
    }

    @Test
    void spoolUpIsIndependentOfTheTargetSize() {
        // The step scales with the range, so every ramp-up takes the same wall-clock time.
        assertEquals(SPOOL_TICKS, ticksToReach(0f, 10f));
        assertEquals(SPOOL_TICKS, ticksToReach(0f, 1000f));
    }

    /**
     * The correction this file was written for. 250 -> 0 is 259 ticks, not 40: about 13 seconds, six
     * and a half times the ramp-up. If a change makes these equal, the five texts above need changing
     * too — do not simply relax this bound.
     */
    @Test
    void spoolDownDecaysAndTakesFarLongerThanSpoolUp() {
        int down = ticksToReach(250f, 0f);
        assertEquals(259, down, "250 -> 0 should take 259 ticks at the default spool");
        assertTrue(down > 6 * SPOOL_TICKS,
            "spool-down is exponential, so it must be much slower than the " + SPOOL_TICKS
                + "-tick ramp-up; got " + down);
    }

    /**
     * Spool-down time grows with the starting thrust — the signature of a decay. A linear ramp would
     * give the same count for every start value, which is what the old comments described.
     */
    @Test
    void spoolDownTimeGrowsWithStartingThrust() {
        int small = ticksToReach(10f, 0f);
        int mid = ticksToReach(50f, 0f);
        int large = ticksToReach(250f, 0f);
        assertTrue(small < mid && mid < large,
            "expected strictly increasing decay times, got " + small + " < " + mid + " < " + large);
    }

    /**
     * The {@code Math.max(1f, ...)} floor is what stops spool-down from asymptotically never arriving.
     * Without it the decay below 1 would halve forever and {@code ticksToReach} would run to its cap.
     */
    @Test
    void theRangeFloorLetsADecayActuallyReachZero() {
        // Below 1 the range term is pinned to 1, so the step is a flat 1/SPOOL_TICKS per tick and the
        // decay goes LINEAR rather than geometric: draining v takes about v * SPOOL_TICKS ticks, and a
        // value near zero terminates instead of halving forever. Allowed one tick of slack because
        // 1f/40f is not exact in binary, so the last subtraction can leave a residual needing one more
        // pass — the shape is the claim here, not the exact count.
        for (float start : new float[] {1f, 0.5f, 0.25f}) {
            int expected = Math.round(start * SPOOL_TICKS);
            int actual = ticksToReach(start, 0f);
            assertTrue(Math.abs(actual - expected) <= 1,
                start + " should drain linearly in about " + expected + " ticks, took " + actual);
        }
        assertTrue(ticksToReach(1e-4f, 0f) <= 2,
            "a tiny residual must terminate at once rather than halving forever");
    }
}
