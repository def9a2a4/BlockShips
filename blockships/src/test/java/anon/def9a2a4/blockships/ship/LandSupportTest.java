package anon.def9a2a4.blockships.ship;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The column scan behind land-vehicle stepping: what counts as a step, a wall, or no ground.
 * Bottom is 64.0 (the vehicle rests on a block at y=63) unless a test says otherwise.
 */
class LandSupportTest {

    private static final double FULL = 1.0, FENCE = 1.5, SLAB = 0.5;

    /** A column given as {y -> collision-shape top within the cell}; everything else is air. */
    private static IntToDoubleFunction column(double... yAndTop) {
        Map<Integer, Double> m = new HashMap<>();
        for (int i = 0; i + 1 < yAndTop.length; i += 2) m.put((int) yAndTop[i], yAndTop[i + 1]);
        return y -> m.getOrDefault(y, Double.NaN);
    }

    @Test
    void flatGroundSupportsAtTheBottom() {
        assertEquals(64.0, ShipPhysics.columnSupport(column(63, FULL), 64.0, 1f), 1e-9);
    }

    @Test
    void oneBlockRiseIsAStepForSmallWheels() {
        assertEquals(65.0, ShipPhysics.columnSupport(column(63, FULL, 64, FULL), 64.0, 1f), 1e-9);
    }

    @Test
    void twoBlockRiseIsAWallForSmallWheelsAndAStepForHugeOnes() {
        IntToDoubleFunction wall = column(63, FULL, 64, FULL, 65, FULL);
        assertTrue(Double.isNaN(ShipPhysics.columnSupport(wall, 64.0, 1f)));
        assertEquals(66.0, ShipPhysics.columnSupport(wall, 64.0, 2.5f), 1e-9);
    }

    /** A fence is 1.5 tall: too tall for a small wheel, a step for a large one. */
    @Test
    void fenceUsesItsCollisionHeight() {
        IntToDoubleFunction fence = column(63, FULL, 64, FENCE);
        assertTrue(Double.isNaN(ShipPhysics.columnSupport(fence, 64.0, 1f)));
        assertEquals(65.5, ShipPhysics.columnSupport(fence, 64.0, 1.5f), 1e-9);
    }

    @Test
    void slabIsAHalfStep() {
        assertEquals(64.5, ShipPhysics.columnSupport(column(63, FULL, 64, SLAB), 64.0, 1f), 1e-9);
    }

    @Test
    void ledgeFindsTheLowerGroundWithinReach() {
        assertEquals(62.0, ShipPhysics.columnSupport(column(61, FULL), 64.0, 1f), 1e-9);
    }

    @Test
    void cliffBeyondReachIsNoSupport() {
        assertEquals(Double.NEGATIVE_INFINITY, ShipPhysics.columnSupport(column(50, FULL), 64.0, 1f));
    }

    /**
     * Wheels roll forward: angular velocity along up x forward. Forward SOUTH (+Z) spins about +X, NORTH
     * about -X, EAST (+X) about -Z, WEST about +Z — signs against the models' authored +X/+Z axles.
     */
    @Test
    void wheelSpinSignFollowsTheShipsForward() {
        assertEquals(1.0, ShipPropulsionUse.wheelSpinSign(0f));     // SOUTH
        assertEquals(-1.0, ShipPropulsionUse.wheelSpinSign(180f));  // NORTH
        assertEquals(-1.0, ShipPropulsionUse.wheelSpinSign(270f));  // EAST
        assertEquals(1.0, ShipPropulsionUse.wheelSpinSign(90f));    // WEST
    }
}
