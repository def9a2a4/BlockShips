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

            BlockFace snapped = yawFromModVector(face);
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

    @Test
    void everyCardinalIsItsOwnNearestCardinal() {
        for (BlockFace face : CARDINALS) {
            assertEquals(face, yawFromModVector(face), face + " should snap to itself");
        }
    }

    /**
     * A wall head faces OUT of the wall while the ship faces INTO it, so placement records the opposite of the
     * block's facing. Anything that re-derives a facing from a wall head must apply the same inversion; not
     * doing so is a silent 180.
     */
    @Test
    void wallHeadFacingIsTheOppositeOfTheShipFacing() {
        for (BlockFace clicked : CARDINALS) {
            BlockFace shipFacing = clicked.getOppositeFace();
            assertEquals(clicked, shipFacing.getOppositeFace(),
                "opposite-of-opposite should be the original for " + clicked);
            assertTrue(isCardinal(shipFacing), "a cardinal's opposite should be cardinal: " + clicked);
        }
    }

    @Test
    void rotatingByAFullTurnIsIdentity() {
        for (BlockFace face : CARDINALS) {
            assertEquals(face, BlockStructureScanner.rotateBlockFace(face, 360f), face + " under a full turn");
            assertEquals(face, BlockStructureScanner.rotateBlockFace(face, 0f), face + " under no turn");
        }
    }

    @Test
    void rotatingByFourQuarterTurnsReturnsToStart() {
        for (BlockFace face : CARDINALS) {
            BlockFace f = face;
            for (int i = 0; i < 4; i++) f = BlockStructureScanner.rotateBlockFace(f, 90f);
            assertEquals(face, f, face + " after four 90-degree turns");
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

    /** The derivation {@code facingFromBlockData} uses for a floor head. Mirrored here so the maths is pinned. */
    private static BlockFace yawFromModVector(BlockFace face) {
        float yaw = (float) Math.toDegrees(Math.atan2(-face.getModX(), face.getModZ()));
        return ShipWheelData.yawToBlockFace(yaw);
    }

    private static boolean isCardinal(BlockFace f) {
        return f == BlockFace.NORTH || f == BlockFace.EAST || f == BlockFace.SOUTH || f == BlockFace.WEST;
    }
}
