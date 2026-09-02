package anon.def9a2a4.blockships.customships;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wheel-facing conversions, which are pure functions over an enum and a float and are therefore the one
 * part of this subsystem a unit test can actually reach.
 *
 * <p>Worth testing because they are the quiet kind of wrong. A wheel's facing is the ship's forward axis, so
 * an error here does not throw or log — the ship simply assembles backwards, or sails ninety degrees off, and
 * the player reports it as "my ship is broken". Two paths in {@code ShipWheelManager} now re-derive a facing
 * from a landed block rather than carrying the stored one across (a mechanism that carries a wheel genuinely
 * rotates it), so these conversions went from being used once at placement to being used on every carried
 * landing.
 *
 * <p>Both known traps are pinned below: the 16-way rotation that silently collapses to SOUTH, and the wall
 * head that points the opposite way to the ship.
 */
class WheelFacingTest {

    private static final BlockFace[] CARDINALS = {
        BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    };

    @Test
    void cardinalFacingSurvivesAYawRoundTrip() {
        for (BlockFace face : CARDINALS) {
            float yaw = BlockStructureScanner.blockFaceToYaw(face);
            assertEquals(face, ShipWheelData.yawToBlockFace(yaw),
                face + " did not survive blockFaceToYaw -> yawToBlockFace");
        }
    }

    /**
     * The trap that motivates the whole test. {@code blockFaceToYaw} maps the four cardinals and sends
     * everything else to its {@code default}, which is 0 — i.e. SOUTH. A floor head's
     * {@code Rotatable.getRotation()} is SIXTEEN-way, so feeding one straight in is silently wrong for twelve
     * of its sixteen values, with no error anywhere.
     *
     * <p>The fix is to go through the mod-vector/atan2 form instead, which is what
     * {@code facingFromBlockData} does. This test pins that it actually recovers all sixteen.
     */
    @Test
    void sixteenWayRotationSnapsToTheNearestCardinal() {
        for (BlockFace face : BlockFace.values()) {
            if (face.getModY() != 0) continue;                       // skip UP/DOWN and the vertical compounds
            if (face.getModX() == 0 && face.getModZ() == 0) continue; // and SELF

            BlockFace snapped = ShipWheelManager.floorHeadFacing(face);
            assertTrue(isCardinal(snapped), face + " snapped to a non-cardinal: " + snapped);

            // The property that actually matters: snapping must not move a direction by more than 45°, i.e.
            // it always lands on a NEAREST cardinal. Asserting "it isn't SOUTH" instead would be wrong —
            // SOUTH_EAST and SOUTH_WEST are exact 45° ties and may legitimately resolve either way.
            //
            // This is what distinguishes the mod-vector derivation from naively feeding a 16-way rotation to
            // blockFaceToYaw, which has no case for the compounds and drops all twelve of them to SOUTH.
            // For NORTH_NORTH_EAST that is a 157.5° error, silently, on a value used as a ship's forward axis.
            double error = angularDistance(rawYaw(face), BlockStructureScanner.blockFaceToYaw(snapped));
            assertTrue(error <= 45.0 + 1e-3,
                face + " snapped to " + snapped + ", which is " + error + "° away — not a nearest cardinal");
        }
    }

    /** Smallest absolute angle between two yaws, in degrees. */
    private static double angularDistance(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    /** The face's true direction as a yaw, before any snapping. */
    private static double rawYaw(BlockFace face) {
        return Math.toDegrees(Math.atan2(-face.getModX(), face.getModZ()));
    }

    /**
     * A floor head at a cardinal rotation must yield that cardinal, NOT its opposite.
     *
     * <p>This is the half of the wall/floor asymmetry a unit test can reach. The wall arm of
     * {@code facingFromBlockData} inverts ({@code getFacing().getOppositeFace()}, because a wall head
     * points out of the wall while the ship faces into it); the floor arm must not. An earlier version
     * of this file asserted only that {@code getOppositeFace().getOppositeFace()} is the identity —
     * true of every {@code BlockFace} in Bukkit, and therefore incapable of failing for any change
     * made here.
     */
    @Test
    void floorHeadFacingDoesNotInvert() {
        for (BlockFace face : CARDINALS) {
            BlockFace derived = ShipWheelManager.floorHeadFacing(face);
            assertEquals(face, derived, "a floor head at " + face + " should read as " + face);
        }
    }

    @Test
    void rotatingByAFullTurnIsIdentity() {
        for (BlockFace face : CARDINALS) {
            assertEquals(face, BlockStructureScanner.rotateBlockFace(face, 360f), face + " under a full turn");
            assertEquals(face, BlockStructureScanner.rotateBlockFace(face, 0f), face + " under no turn");
        }
    }

    /**
     * Each quarter turn individually, not just the round trip.
     *
     * <p>Asserting only that four 90s return to the start is worthless: it holds for 4 x -90 and for
     * 4 x 180 alike, so it catches neither a sign flip nor a half-turn error — the two ways this can
     * actually be wrong. Yaw runs SOUTH 0, WEST 90, NORTH 180, EAST 270, so +90 must walk
     * SOUTH -> WEST -> NORTH -> EAST.
     */
    @Test
    void aQuarterTurnWalksTheCardinalsInYawOrder() {
        BlockFace[] clockwise = {BlockFace.SOUTH, BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST};
        for (int i = 0; i < clockwise.length; i++) {
            BlockFace from = clockwise[i];
            BlockFace expected = clockwise[(i + 1) % clockwise.length];
            assertEquals(expected, BlockStructureScanner.rotateBlockFace(from, 90f),
                from + " + 90 should be " + expected);
            assertEquals(clockwise[(i + 3) % clockwise.length],
                BlockStructureScanner.rotateBlockFace(from, -90f),
                from + " - 90 should go the other way");
            assertEquals(clockwise[(i + 2) % clockwise.length],
                BlockStructureScanner.rotateBlockFace(from, 180f), from + " + 180 should be opposite");
        }
    }

    @Test
    void snapToNearestCardinalIsIdempotent() {
        for (float yaw = -540f; yaw <= 540f; yaw += 7.5f) {
            float once = ShipWheelData.snapToNearestCardinal(yaw);
            assertEquals(once, ShipWheelData.snapToNearestCardinal(once), 0.001f,
                "snapping twice differed from snapping once, at yaw " + yaw);
            assertTrue(isCardinal(ShipWheelData.yawToBlockFace(once)),
                "snapped yaw " + once + " did not resolve to a cardinal");
        }
    }

    /**
     * Snapping must go to the NEAREST cardinal, not the one below.
     *
     * <p>Idempotence alone does not establish that: flooring to a multiple of 90 is just as idempotent
     * and just as cardinal, while being wrong by up to 89 degrees. The cases that separate the two are
     * the ones either side of a 45-degree boundary, so those are what this pins.
     */
    @Test
    void snapToNearestCardinalRoundsRatherThanFloors() {
        assertEquals(0f, ShipWheelData.snapToNearestCardinal(44f), 0.001f);
        assertEquals(90f, ShipWheelData.snapToNearestCardinal(46f), 0.001f,
            "46 is nearer 90 than 0 — flooring would give 0");
        assertEquals(90f, ShipWheelData.snapToNearestCardinal(134f), 0.001f);
        assertEquals(180f, ShipWheelData.snapToNearestCardinal(136f), 0.001f);
        assertEquals(270f, ShipWheelData.snapToNearestCardinal(314f), 0.001f);
        // 316 rounds up to 360, which must wrap to 0 rather than escaping the range.
        assertEquals(0f, ShipWheelData.snapToNearestCardinal(316f), 0.001f,
            "a snap past 315 must wrap to 0, not report 360");
        // Negative yaws normalise first: -46 is 314.
        assertEquals(270f, ShipWheelData.snapToNearestCardinal(-46f), 0.001f);
    }

    private static boolean isCardinal(BlockFace f) {
        return f == BlockFace.NORTH || f == BlockFace.EAST || f == BlockFace.SOUTH || f == BlockFace.WEST;
    }
}
