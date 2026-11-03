package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.customships.BlockStructureScanner;
import org.bukkit.block.BlockFace;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wheels and land vehicles: which wheels drive, which count as touching the ground, and what ground
 * thrust does to the stats. All pure — the world-facing parts (rotationFacingAt, collision shapes)
 * are behind {@link ShipThrust#classifyFacing} and {@code ShipPhysics.columnSupport}.
 */
class LandVehicleTest {

    private static final BlockFace[] CARDINALS = {
        BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    };

    // ── Classification ───────────────────────────────────────────────────────

    /** A wheel drives only with its axle across the hull, for every heading the Ship's Wheel can face. */
    @Test
    void woodenWheelsMapToTheirSize() {
        assertEquals("mech:wheel", ShipThrust.canonicalTypeId("mech:wheel_oak"));
        assertEquals("mech:large_wheel", ShipThrust.canonicalTypeId("mech:large_wheel_dark_oak"));
        assertEquals("mech:huge_wheel", ShipThrust.canonicalTypeId("mech:huge_wheel_pale_oak"));
        // Not wheels, or not a known wood: unchanged.
        assertEquals("mech:reaction_wheel", ShipThrust.canonicalTypeId("mech:reaction_wheel"));
        assertEquals("mech:wheel_stone", ShipThrust.canonicalTypeId("mech:wheel_stone"));
        assertEquals("mech:gearbox_oak", ShipThrust.canonicalTypeId("mech:gearbox_oak"));
        assertTrue(ShipThrust.isWheel(ShipThrust.canonicalTypeId("mech:huge_wheel_warped")));
    }

    @Test
    void wheelOnTheSideIsGroundAndOnTheBowIsMisaligned() {
        for (BlockFace forward : CARDINALS) {
            float yaw = BlockStructureScanner.blockFaceToYaw(forward);
            for (BlockFace mount : CARDINALS) {
                boolean alongHull = mount == forward || mount == forward.getOppositeFace();
                for (String id : new String[]{"mech:wheel", "mech:large_wheel", "mech:huge_wheel"}) {
                    ShipThrust.Axis axis = ShipThrust.classifyFacing(id, mount, yaw);
                    if (alongHull) {
                        assertNull(axis, id + " facing " + mount + " on a ship facing " + forward);
                        assertTrue(ShipThrust.isMisalignedWheel(id, mount, yaw));
                    } else {
                        assertEquals(ShipThrust.Axis.GROUND, axis, id + " facing " + mount + " on a ship facing " + forward);
                        assertFalse(ShipThrust.isMisalignedWheel(id, mount, yaw));
                    }
                }
            }
        }
    }

    /** The wheels must not change what a propeller on the same mount does. */
    @Test
    void propellerClassificationIsUnchanged() {
        float yaw = BlockStructureScanner.blockFaceToYaw(BlockFace.SOUTH);
        assertEquals(ShipThrust.Axis.AXIAL, ShipThrust.classifyFacing("mech:propeller", BlockFace.SOUTH, yaw));
        assertEquals(ShipThrust.Axis.PERPENDICULAR, ShipThrust.classifyFacing("mech:propeller", BlockFace.EAST, yaw));
        assertEquals(ShipThrust.Axis.VERTICAL, ShipThrust.classifyFacing("mech:propeller", BlockFace.UP, yaw));
        assertEquals(ShipThrust.Axis.TURN_ONLY, ShipThrust.classifyFacing("mech:reaction_wheel", BlockFace.UP, yaw));
        assertNull(ShipThrust.classifyFacing("mech:wheel", BlockFace.UP, yaw));
    }

    // ── Ground wheels ────────────────────────────────────────────────────────

    /** Wheels at the hull bottom count; a wheel hung higher up the side is decoration. */
    @Test
    void onlyWheelsReachingTheBottomCount() {
        ShipModel m = model(
            new float[][]{{0, 0, 0}, {1, 0, 0}, {0, 1, 0}},          // hull: two-block floor and a cabin block
            new Object[][]{{"mech:wheel", 0, 0, 1}, {"mech:wheel", 1, 0, 1}, {"mech:wheel", 0, 1, 1}});
        assertTrue(m.isLandVehicle());
        assertEquals(2, m.groundWheels.size(), "the cabin-height wheel must not count");
        assertEquals(0f, m.landBottomY, 1e-6, "small wheels never reach below the hull");
    }

    /** A huge wheel's tyre reaches a block below its cell, so it lifts the hull and outranks small ones. */
    @Test
    void bigWheelsLowerTheBottomAndSmallOnesThenDoNotCount() {
        ShipModel m = model(
            new float[][]{{0, 0, 0}, {1, 0, 0}},
            new Object[][]{{"mech:huge_wheel", 0, 0, 1}, {"mech:wheel", 1, 0, 1}});
        assertEquals(-1f, m.landBottomY, 1e-6);
        assertEquals(1, m.groundWheels.size());
        assertEquals("mech:huge_wheel", m.groundWheels.get(0).typeId());
    }

    /** No wheels, or wheels only on the bow: an ordinary ship, whose bottom is its keel. */
    @Test
    void shipWithoutGroundWheelsIsNotALandVehicle() {
        ShipModel m = model(new float[][]{{0, 0, 0}, {0, -1, 0}}, new Object[0][]);
        assertFalse(m.isLandVehicle());
        assertEquals(m.minY, m.landBottomY, 1e-6);
        assertEquals(0, m.supportSamplesXZ.length);
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    @Test
    void groundThrustCountsOnlyWhenGrounded() {
        ShipConfig cfg = ShippedConfig.load();
        ShipModel m = model(new float[][]{{0, 0, 0}, {1, 0, 0}}, new Object[][]{{"mech:wheel", 0, 0, 1}});
        ShipThrust.Totals wheels = new ShipThrust.Totals(0, 0, 0, 0, 40, 2, 2);
        ShipStats air = ShipStats.of(cfg, m, wheels, 0f, false);
        ShipStats land = ShipStats.of(cfg, m, wheels, 0f, true);
        assertTrue(land.forwardRatio > air.forwardRatio, "wheels on the ground must add forward push");
        assertTrue(land.turnRatio > air.turnRatio, "wheels must let a stopped car turn");
        assertEquals(ShipStats.of(cfg, m, ShipThrust.Totals.NONE, 0f, true).forwardRatio,
            ShipStats.of(cfg, m, ShipThrust.Totals.NONE, 0f, false).forwardRatio, 1e-6,
            "grounded alone changes nothing without wheel thrust");
    }

    @Test
    void stepHeightsComeFromConfigAndGrowWithSize() {
        ShipConfig cfg = ShippedConfig.load();
        assertTrue(cfg.stepHeightOf("mech:wheel") >= 1f);
        assertTrue(cfg.stepHeightOf("mech:large_wheel") > cfg.stepHeightOf("mech:wheel"));
        assertTrue(cfg.stepHeightOf("mech:huge_wheel") > cfg.stepHeightOf("mech:large_wheel"));
        assertEquals(0f, cfg.stepHeightOf("mech:propeller"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * A model from hull cells and wheels. Each wheel is {typeId, x, y, z}; it is appended as its own part
     * (a wall head is a block of the ship) and listed as a GROUND thrust block.
     */
    private static ShipModel model(float[][] hull, Object[][] wheels) {
        List<ShipModel.ModelPart> parts = new ArrayList<>();
        List<ShipModel.ThrustBlock> thrust = new ArrayList<>();
        float minY = Float.MAX_VALUE;
        for (float[] c : hull) {
            parts.add(part(c[0], c[1], c[2]));
            minY = Math.min(minY, c[1]);
        }
        for (Object[] w : wheels) {
            float x = ((Number) w[1]).floatValue(), y = ((Number) w[2]).floatValue(), z = ((Number) w[3]).floatValue();
            thrust.add(new ShipModel.ThrustBlock(parts.size(), (String) w[0], ShipThrust.Axis.GROUND));
            parts.add(part(x, y, z));
            minY = Math.min(minY, y);
        }
        return new ShipModel(parts, new ArrayList<>(), new Vector3f(), new Vector3f(), new Vector3f(),
            new Matrix3f(), new ArrayList<>(), new ArrayList<>(), 0f, 40, 0,
            parts.size() * 2, parts.size() * 2, parts.size(), new Vector3f(), minY, 1f, 0f,
            0, 0, 0, 0, 3, 7, 20, 50, thrust);
    }

    private static ShipModel.ModelPart part(float x, float y, float z) {
        return new ShipModel.ModelPart(null, new Matrix4f().translate(x, y, z),
            new ShipModel.CollisionConfig(), null, null);
    }
}
