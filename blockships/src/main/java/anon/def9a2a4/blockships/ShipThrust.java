package anon.def9a2a4.blockships;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;

/**
 * Which defCoreLib blocks push a ship, how hard, and in which direction relative to the hull.
 *
 * <p>A thrust block is classified once, at scan time, into one of three axes; what it actually
 * contributes is decided later from whether it is powered. Classification happens during the scan
 * rather than after assembly because the blocks are still in the world at that point (air-out is
 * deferred), so a single code path serves both the assembled ship and the docked preview.
 */
public final class ShipThrust {

    private ShipThrust() {}

    /** How a thrust block is oriented relative to the hull, and therefore what it does. */
    public enum Axis {
        /** Along the ship's fore-aft line: top speed and acceleration. */
        AXIAL,
        /** Across the ship: turn rate. */
        PERPENDICULAR,
        /** Up or down: cancels weight, and is what lets a heavy ship fly. */
        VERTICAL,
        /** A reaction wheel — turn rate only, whichever way anything is pointing. */
        TURN_ONLY
    }

    /** Default thrust per block type; overridable under {@code custom-ships.stats.thrust}. */
    private static final Map<String, Integer> DEFAULT_THRUST = Map.of(
        "mech:fan", 1,
        "mech:propeller", 4,
        "mech:large_propeller", 12,
        "mech:huge_propeller", 30,
        "mech:thruster", 15,
        "mech:reaction_wheel", 10
    );

    /**
     * Thrust currently being produced, split by what it drives.
     *
     * @param powered how many thrust blocks are actually running, for the driver readout
     * @param total   how many are aboard at all
     */
    public record Totals(int axial, int perpendicular, int vertical, int turnOnly,
                         int powered, int total) {
        public static final Totals NONE = new Totals(0, 0, 0, 0, 0, 0);
        /** Everything that turns the ship: side thrust plus gyroscopes. */
        public int turning() { return perpendicular + turnOnly; }
    }

    /**
     * Sum the thrust a ship is producing right now.
     *
     * <p>Only blocks that are actually running count — a propeller with no power and a thruster with
     * no fuel contribute nothing, which is what makes fuel and power matter in flight. Walks the
     * model's cached thrust list, never all of the ship's blocks.
     *
     * @param mechanism the assembled mechanism, or null when docked (then everything counts as
     *                  powered, giving the "potential" figure the wheel menu shows)
     */
    public static Totals totalsFor(BlockShipsPlugin plugin, anon.def9a2a4.corelib.Mechanism mechanism,
                                   ShipModel model) {
        if (model == null || model.thrustBlocks.isEmpty()) return Totals.NONE;
        int axial = 0, perp = 0, vert = 0, turn = 0, powered = 0;
        for (ShipModel.ThrustBlock t : model.thrustBlocks) {
            if (mechanism != null && !isProducing(mechanism, t)) continue;
            powered++;
            int pts = thrustOf(plugin, t.typeId());
            switch (t.axis()) {
                case AXIAL -> axial += pts;
                case PERPENDICULAR -> perp += pts;
                case VERTICAL -> vert += pts;
                case TURN_ONLY -> turn += pts;
            }
        }
        return new Totals(axial, perp, vert, turn, powered, model.thrustBlocks.size());
    }

    /**
     * Whether one thrust block is currently doing work.
     *
     * <p>A thruster is a fuel burner, so it reports through its state ({@code running_*} vs
     * {@code idle_*}); everything else is a rotation consumer and reports through the network. Both
     * are cheap reads of a cached solve.
     */
    private static boolean isProducing(anon.def9a2a4.corelib.Mechanism mechanism, ShipModel.ThrustBlock t) {
        try {
            if ("mech:thruster".equals(t.typeId())) {
                String state = mechanism.getBlock(t.blockIndex()).customState();
                return state != null && state.startsWith("running");
            }
            return mechanism.isRotationPowered(t.blockIndex());
        } catch (Throwable e) {
            // Index out of range after a partial recovery, or a CoreLib fault: treat as unpowered
            // rather than crediting thrust that may not exist.
            return false;
        }
    }

    /** Config key suffix for a type: {@code mech:large_propeller} -> {@code large-propeller}. */
    private static String configKey(String typeId) {
        int colon = typeId.indexOf(':');
        return (colon >= 0 ? typeId.substring(colon + 1) : typeId).replace('_', '-');
    }

    public static boolean isThrustBlock(String typeId) {
        return typeId != null && DEFAULT_THRUST.containsKey(typeId);
    }

    /** Thrust points for a type, from config where present. */
    public static int thrustOf(BlockShipsPlugin plugin, String typeId) {
        Integer def = DEFAULT_THRUST.get(typeId);
        if (def == null) return 0;
        if (plugin == null) return def;
        return plugin.getConfig().getInt("custom-ships.stats.thrust." + configKey(typeId), def);
    }

    /**
     * The defCoreLib custom-block id of a world block, or null when it is not one.
     *
     * <p>Wrapped because BlockShips must keep working if defCoreLib is missing a class or throws —
     * a propulsion lookup failing should cost a ship its thrust, not its ability to assemble.
     */
    public static String typeIdOf(Block block) {
        try {
            var registry = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry();
            var type = registry.getTypeFromBlock(block);
            return type == null ? null : type.fullId();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Classify a world block's thrust direction in SHIP-LOCAL space.
     *
     * @param assemblyYaw the wheel's yaw at assembly; the world facing is rotated by its negative to
     *                    get back into hull coordinates
     * @return the axis, or null when this block does not produce thrust
     */
    public static Axis classify(Block block, String typeId, float assemblyYaw) {
        if (!isThrustBlock(typeId)) return null;
        // A reaction wheel is driven from below, so its facing always reads as vertical — but it is a
        // gyroscope, and turns the ship whichever way it sits. Without this override every one of them
        // would be counted as lift.
        if ("mech:reaction_wheel".equals(typeId)) return Axis.TURN_ONLY;

        BlockFace worldFacing;
        try {
            worldFacing = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().rotationFacingAt(block);
        } catch (Throwable t) {
            return null;
        }
        if (worldFacing == null) return null;
        if (worldFacing == BlockFace.UP || worldFacing == BlockFace.DOWN) return Axis.VERTICAL;

        // Into hull coordinates. The sign is -assemblyYaw, and it matters: with unsigned magnitudes
        // the two candidate rotations differ by a multiple of 180 and so agree on axial-vs-perpendicular
        // either way, but that stops being true the moment thrust becomes signed.
        BlockFace local = anon.def9a2a4.blockships.customships.BlockStructureScanner
            .rotateBlockFace(worldFacing, -assemblyYaw);
        // The wheel's facing IS the ship's forward direction, and a ship-local facing is measured
        // against a wheel that faces its own 0 — so forward is SOUTH in the local frame, matching
        // blockFaceToYaw's convention (0 = SOUTH).
        boolean alongAxis = local == BlockFace.SOUTH || local == BlockFace.NORTH;
        return alongAxis ? Axis.AXIAL : Axis.PERPENDICULAR;
    }
}
