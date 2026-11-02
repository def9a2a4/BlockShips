package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShipModel;
import anon.def9a2a4.blockships.ShipThrust;

/**
 * How hard each kind of thrust block is being USED right now, from the driver's input and the ship's
 * motion, and the visual spin that follows from it.
 *
 * <p>defCoreLib spins a powered consumer whenever its network has power, so a parked ship with a running
 * engine used to sit there with every propeller turning. This drives each block's animation from use
 * instead: forward propellers with the ship's speed, side ones and reaction wheels with the steering, lift
 * propellers with the climb, and wheels with the road speed. Visual only — thrust is unchanged.
 *
 * <p>The same use is reported to DefCoreLib as each block's demand, which is what its engines burn fuel
 * for: a parked ship with a lit engine burns nothing, half throttle burns about half.
 *
 * <p>Calls the spin and demand APIs through try/catch: an older DefCoreLib has neither, and a ship must
 * still sail there, just with the old always-spinning look and fixed burn.
 */
final class ShipPropulsionUse {

    /** How fast a block's displayed use eases toward its target, per tick (full range in ~8 ticks). */
    private static final float EASE = 0.125f;
    /** Wheels spinning faster than this alias at 20 updates a second; cap the displayed spin. */
    private static final double MAX_WHEEL_DEG_PER_TICK = 40.0;

    private final ShipInstance ship;
    /** Displayed use per thrust block (index into model.thrustBlocks), eased toward the target. */
    private float[] shown;
    private static volatile boolean apiMissing = false;
    private static volatile boolean demandApiMissing = false;

    ShipPropulsionUse(ShipInstance ship) {
        this.ship = ship;
    }

    /** Signed forward use: +1 full throttle ahead, -1 astern, 0 coasting or unmanned. */
    float axialUse() {
        if (!ship.hasDriver) return 0f;
        if (ship.isForwardPressed) return 1f;
        if (ship.isBackwardPressed) return -1f;
        return 0f;
    }

    /** Signed steering use: +1 right, -1 left. */
    float turnUse() {
        if (!ship.hasDriver) return 0f;
        if (ship.isRightPressed) return 1f;
        if (ship.isLeftPressed) return -1f;
        return 0f;
    }

    /**
     * Lift use, 0..1. Climbing (Space) is full use for anything, a balloon or a ship lifting off the
     * ground included: its lift blocks add climb speed. Otherwise only a flier uses them: none on water,
     * on the ground, or for a balloon, which floats on displacement. Holding altitude uses the share of
     * lift that carries the weight (1 / lift ratio); descending still holds some of it back.
     */
    float verticalUse() {
        ShipPhysics p = ship.physics;
        if (ship.hasDriver && ship.isSpacePressed) return 1f;
        if (ship.isAirship || !p.isFlying() || p.isGrounded()) return 0f;
        float lift = p.liftRatio();
        float hover = lift <= 0f ? 1f : Math.min(1f, 1f / lift);
        if (ship.hasDriver && ship.isSprintPressed) return hover * 0.5f;
        return hover;
    }

    /** Report zero demand for every thrust block (a sinking ship no longer uses its engines). */
    void idle() {
        if (ship.mechanism == null || ship.model == null || ship.model.thrustBlocks.isEmpty()) return;
        pushDemand(ship.model.thrustBlocks, 0f, 0f, 0f);
    }

    /** Push this tick's spin and demand to every thrust block. Cheap: field writes in defCoreLib. */
    void tick() {
        if (ship.mechanism == null || ship.model == null) return;
        var blocks = ship.model.thrustBlocks;
        if (blocks.isEmpty()) return;
        if (shown == null || shown.length != blocks.size()) shown = new float[blocks.size()];

        float axial = axialUse(), turn = turnUse(), vertical = verticalUse();
        pushDemand(blocks, axial, turn, vertical);
        if (apiMissing) return;
        // Forward propellers show the ship's motion, not the throttle: they wind down as it coasts, and turn
        // backwards going astern. (Their fuel still follows the throttle, above.)
        float top = Math.max(0.0001f, ship.physics.effectiveMaxSpeed());
        float speed = ship.physics.currentSpeed;
        float axialSpin = Math.abs(speed) <= ship.config.minMovementThreshold ? 0f
            : Math.max(-1f, Math.min(1f, speed / top));
        double wheelSign = wheelSpinSign(ship.model.assemblyYaw);
        boolean grounded = ship.physics.isGrounded();
        try {
            for (int i = 0; i < blocks.size(); i++) {
                ShipModel.ThrustBlock t = blocks.get(i);
                if ("mech:thruster".equals(t.typeId())) continue;  // a burner, not a spinner
                if (t.axis() == ShipThrust.Axis.GROUND) {
                    // Rolling without slipping: angular speed = road speed / radius, in degrees per tick,
                    // about the axle. Every ground wheel shares the authored +X/+Z axle sign, so they all
                    // get the same sign; only the radius differs. Off the ground, or a spare that never
                    // touches it, the wheel doesn't roll.
                    double deg = 0;
                    // Below the movement threshold is settling jitter, not rolling: hold the wheel still.
                    if (grounded && ship.model.isGroundWheel(t.blockIndex())
                            && Math.abs(ship.physics.currentSpeed) > ship.config.minMovementThreshold) {
                        double radius = Math.max(0.1, ShipThrust.wheelDiameter(t.typeId()) / 2.0);
                        deg = Math.toDegrees(ship.physics.currentSpeed / radius) * wheelSign;
                        deg = Math.max(-MAX_WHEEL_DEG_PER_TICK, Math.min(MAX_WHEEL_DEG_PER_TICK, deg));
                    }
                    ship.mechanism.setBlockSpinSpeed(t.blockIndex(), deg);
                    continue;
                }
                float target = switch (t.axis()) {
                    case AXIAL -> axialSpin;
                    case PERPENDICULAR, TURN_ONLY -> turn;
                    case VERTICAL -> vertical;
                    default -> 0f;
                };
                float cur = shown[i];
                float d = target - cur;
                cur = Math.abs(d) <= EASE ? target : cur + Math.signum(d) * EASE;
                shown[i] = cur;
                // An unpowered propeller stays still: the override would otherwise borrow its spin
                // animation and turn it with no thrust behind it.
                ship.mechanism.setBlockSpinScale(t.blockIndex(),
                    ShipThrust.isProducing(ship.mechanism, t) ? cur : 0f);
            }
        } catch (NoSuchMethodError | AbstractMethodError e) {
            apiMissing = true;  // older DefCoreLib: keep its always-on spin
        }
    }

    /**
     * Demand, 0..1, for every thrust block — what its share of engine fuel is burned for. Thrusters
     * included: they burn by their own demand. Wheels draw while the throttle is open on the ground.
     */
    private void pushDemand(java.util.List<ShipModel.ThrustBlock> blocks, float axial, float turn, float vertical) {
        if (demandApiMissing) return;
        try {
            boolean grounded = ship.physics.isGrounded();
            for (ShipModel.ThrustBlock t : blocks) {
                float d = switch (t.axis()) {
                    case AXIAL -> Math.abs(axial);
                    case PERPENDICULAR, TURN_ONLY -> Math.abs(turn);
                    case VERTICAL -> vertical;
                    case GROUND -> grounded && ship.model.isGroundWheel(t.blockIndex())
                        ? Math.max(Math.abs(axial), Math.abs(turn)) : 0f;
                };
                ship.mechanism.setBlockDemand(t.blockIndex(), d);
            }
        } catch (NoSuchMethodError | AbstractMethodError e) {
            demandApiMissing = true;  // older DefCoreLib: fixed burn
        }
    }

    /**
     * Sign that turns road speed into spin about the wheels' authored axle. The wheel models spin about
     * +X (axle east-west) or +Z (axle north-south); for the hull's forward direction f in the model frame,
     * rolling forward means angular velocity along up × f = (f.z, 0, -f.x).
     */
    static double wheelSpinSign(float assemblyYaw) {
        double rad = Math.toRadians(-assemblyYaw);
        double fx = Math.sin(rad), fz = Math.cos(rad);
        // Axle runs across f: along X when f is along Z, else along Z.
        return Math.abs(fz) >= Math.abs(fx) ? Math.signum(fz) : -Math.signum(fx);
    }
}
