package anon.def9a2a4.blockships;

/**
 * The one place a ship's performance ratio is computed.
 *
 * <p>This maths used to live in five copies — {@code ShipModel.getSailRatio}, {@code ShipPhysics},
 * {@code ShipWheelMenu}, and twice in {@code ShipWheelManager} — which had already drifted: the
 * display copies rebuilt {@code sailPower} from wool and banners alone, so once large and huge
 * banners started counting they were silently missing from every number a player could actually see.
 * Anything that needs a ratio goes through here.
 *
 * <p>Kept deliberately small and dependency-free (config + a few ints) so it can be built from an
 * assembled ship's model, from a detect preview, or from a hypothetical block set.
 */
public final class ShipStats {

    /** Sail points from every sail tier: wool, banners, and large/huge banners. */
    public final int sailPower;
    /** Clamped to at least 1 — the ratio divides by it. */
    public final int mass;
    /** Uncapped {@code (basePower + sailPower) / mass}; shown in the info panel to explain the cap. */
    public final float sailRatio;
    /** {@link #sailRatio} after {@code sail-cap-ratio}: sails plateau so propulsion has a job. */
    public final float cappedSailRatio;
    /** The value fed to {@link ShipConfig#computeStat} — capped and clamped to 1. */
    public final float ratio;

    // ── Three-ratio model (stats.mode: ratio3) ───────────────────────────────
    // Sails and propulsion drive different things, so one number can't describe a ship any more.

    /** Speed and acceleration: capped sails PLUS axial thrust. */
    public final float forwardRatio;
    /** Turn rate: side thrust and gyroscopes, plus sails scaled by how fast the ship is moving. */
    public final float turnRatio;
    /** Lift, against SIGNED weight — so buoyancy counts toward flight instead of being ignored. */
    public final float liftRatio;

    private ShipStats(int sailPower, int mass, float sailRatio, float cappedSailRatio, float ratio,
                      float forwardRatio, float turnRatio, float liftRatio) {
        this.sailPower = sailPower;
        this.mass = mass;
        this.sailRatio = sailRatio;
        this.cappedSailRatio = cappedSailRatio;
        this.ratio = ratio;
        this.forwardRatio = forwardRatio;
        this.turnRatio = turnRatio;
        this.liftRatio = liftRatio;
    }

    /**
     * The full three-ratio form.
     *
     * <p>Keeping the sail cap is what gives propulsion a job: uncapped, a handful of huge banners
     * would max a mid-size ship out and no propeller would ever change a number. Sails plateau at
     * {@code sail-cap-ratio}; axial thrust is what closes the remaining gap.
     *
     * <p>Sails feed turning too, but scaled by current speed — a rudder needs water moving past it.
     * Thrust-based turning is speed-independent, which is precisely what makes a reaction wheel
     * worth carrying: it is the thing that turns you when you are stopped.
     *
     * @param speedFrac current speed as a fraction of top speed, 0..1
     */
    public static ShipStats of(ShipConfig config, ShipModel model, ShipThrust.Totals thrust,
                               float speedFrac) {
        return withThrust(config, model.sailPower, model.mass, model.totalWeight, thrust, speedFrac);
    }

    /**
     * The three-ratio form for a ship with no model — the docked wheel menu.
     *
     * <p>Same maths as {@link #of(ShipConfig, ShipModel, ShipThrust.Totals, float)}; the caller
     * supplies what the model would have known. Feed it {@link ShipThrust#scanWorld}, whose totals are
     * potential rather than live, and label the result accordingly.
     *
     * @param totalWeight SIGNED hull weight, so buoyancy counts toward lift
     */
    public static ShipStats of(ShipConfig config, int woolCount, int bannerCount,
                               int largeBannerCount, int hugeBannerCount, int mass,
                               int totalWeight, ShipThrust.Totals thrust, float speedFrac) {
        int sailPower = woolCount * config.woolPower
                      + bannerCount * config.bannerPower
                      + largeBannerCount * config.largeBannerPower
                      + hugeBannerCount * config.hugeBannerPower;
        return withThrust(config, sailPower, mass, totalWeight, thrust, speedFrac);
    }

    private static ShipStats withThrust(ShipConfig config, int sailPower, int rawMass, int totalWeight,
                                        ShipThrust.Totals thrust, float speedFrac) {
        int mass = Math.max(1, rawMass);
        float rawSail = (float) (config.basePower + sailPower) / mass;
        float cappedSail = Math.min(rawSail, config.sailCapRatio);

        float forward = clamp01(cappedSail + (float) thrust.axial() / mass);
        float turn = clamp01((config.baseTurn
                              + sailPower * config.sailTurnFactor * clamp01(speedFrac)
                              + thrust.turning()) / mass);
        // Lift is measured against SIGNED weight, so buoyancy counts toward flight instead of being
        // ignored: a hull that is already near-neutral needs only a little thrust, and a lighter-than-air
        // one is airborne with none. 1.0 means "holds its own weight"; ABOVE 1.0 is what climbs.
        //
        // The neutral/buoyant branch is not a special case bolted on — it is the same quantity measured
        // from a different baseline. At or below zero weight the hull already holds itself up, so it
        // starts at 1.0 and every point of vertical thrust is surplus on top. Two bugs this replaces:
        //   - `max(1, totalWeight)` made the divisor 1 for a buoyant hull, so 30 points of thrust
        //     reported lift 30 and the HUD printed "3000%".
        //   - `max(lift, 1.0f)` pinned a neutral hull to EXACTLY 1.0, so it hovered and could never
        //     climb however many propellers were added — and a zero-weight hull is not an airship
        //     either (`isAirship` needs density < 0, strictly), so nothing else rescued it.
        float lift = totalWeight > 0
            ? (float) thrust.vertical() / totalWeight
            : 1.0f + (float) thrust.vertical() / mass;

        return new ShipStats(sailPower, mass, rawSail, cappedSail, Math.min(cappedSail, 1.0f),
                             forward, turn, lift);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : Math.min(v, 1.0f);
    }

    /** From an assembled ship's model — the authoritative path; the model already knows every tier. */
    public static ShipStats of(ShipConfig config, ShipModel model) {
        return fromSailPower(config, model.sailPower, model.mass);
    }

    /**
     * From raw counts, for the detect preview where there is no model yet.
     *
     * @param largeBannerCount large banners, or 0 when the caller has not counted them
     * @param hugeBannerCount  huge banners, or 0 when the caller has not counted them
     */
    public static ShipStats of(ShipConfig config, int woolCount, int bannerCount,
                               int largeBannerCount, int hugeBannerCount, int mass) {
        int sailPower = woolCount * config.woolPower
                      + bannerCount * config.bannerPower
                      + largeBannerCount * config.largeBannerPower
                      + hugeBannerCount * config.hugeBannerPower;
        return fromSailPower(config, sailPower, mass);
    }

    private static ShipStats fromSailPower(ShipConfig config, int sailPower, int rawMass) {
        int mass = Math.max(1, rawMass);
        float sailRatio = (float) (config.basePower + sailPower) / mass;
        float capped = Math.min(sailRatio, config.sailCapRatio);
        float ratio = Math.min(capped, 1.0f);
        // Sail-only form: forward is the legacy ratio, and there is no thrust to turn or lift with.
        return new ShipStats(sailPower, mass, sailRatio, capped, ratio, ratio, 0f, 0f);
    }

    /**
     * Top speed as a percentage, for player-facing display.
     *
     * <p>Reads {@link #forwardRatio} — the number physics actually drives {@code effectiveMaxSpeed}
     * from — against a full 1.0, and both of those are deliberate:
     *
     * <p>It used to read {@link #ratio}, which is capped sails ONLY. So a ship flying on thrusters
     * with no canvas reported the speed of the sails it did not have, and the stats page contradicted
     * the ship underneath the player.
     *
     * <p>And it used to divide by {@code sail-cap-ratio} so a fully-rigged ship read 100% instead of
     * 80%. That hid the very thing the cap exists to communicate — sails plateau, propulsion closes the
     * gap — and it made the top of the scale depend on a config value: at the default 0.8 the maximum
     * reading is 125%, but at 0.5 it would be 200% and every ship with a propeller would peg the top
     * colour. 80% for full canvas is the honest reading.
     */
    public int speedPercent() {
        return Math.round(forwardRatio * 100);
    }


    /** Colour code for a speed percentage, shared by the two /detect readouts. */
    public static String speedColor(int speedPercent) {
        if (speedPercent >= 125) return "§b";
        if (speedPercent >= 100) return "§a";
        if (speedPercent >= 75) return "§e";
        if (speedPercent >= 50) return "§6";
        return "§c";
    }
}
