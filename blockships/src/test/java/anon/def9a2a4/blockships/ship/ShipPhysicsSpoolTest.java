package anon.def9a2a4.blockships.ship;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thrust spool-up and spool-down, measured rather than asserted in prose.
 *
 * <p>This exists because the claim "a ramp takes {@code thrustSpoolTicks} in either direction" was
 * written in four separate places — the physics javadoc, {@code ShipConfig}, {@code config.yml} and
 * the flight-model document — and was false in all four. Spool-DOWN scales its step by the shrinking
 * current value, so it decays instead of ramping. Nobody noticed for as long as the only record of the
 * behaviour was a sentence.
 */
class ShipPhysicsSpoolTest {

    /** The shipped default, {@code custom-ships.stats.thrust-spool-ticks}. */
    private static final int SPOOL_TICKS = 40;
    private static final float STEP = 1f / SPOOL_TICKS;

    /** Ticks for {@code approach} to carry {@code from} all the way to {@code to}. */
    private static int ticksToReach(float from, float to) {
        float v = from;
        for (int tick = 1; tick <= 100_000; tick++) {
            v = ShipPhysics.approach(v, to, STEP);
            if (v == to) return tick;
        }
        throw new AssertionError("approach never reached " + to + " from " + from);
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
     * and a half times the ramp-up. If a change makes these equal, the docs above need changing too —
     * do not simply relax this bound.
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

    @Test
    void approachIsAFixedPointAtItsTarget() {
        assertEquals(7f, ShipPhysics.approach(7f, 7f, STEP));
        assertEquals(0f, ShipPhysics.approach(0f, 0f, STEP));
    }
}
