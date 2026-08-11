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

    private ShipStats(int sailPower, int mass, float sailRatio, float cappedSailRatio, float ratio) {
        this.sailPower = sailPower;
        this.mass = mass;
        this.sailRatio = sailRatio;
        this.cappedSailRatio = cappedSailRatio;
        this.ratio = ratio;
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
        return new ShipStats(sailPower, mass, sailRatio, capped, Math.min(capped, 1.0f));
    }

    /**
     * The ratio as a percentage of "fully rigged", for player-facing display.
     *
     * <p>Measured against the sail cap rather than 1.0, so a ship with every sail it can usefully
     * carry reads as 100% instead of 80%.
     */
    public int speedPercent() {
        return Math.round(ratio * 100);
    }

    /** Percentage relative to the sail cap — see {@link #speedPercent()}. */
    public int speedPercent(ShipConfig config) {
        return config.sailCapRatio > 0
            ? Math.round(ratio / config.sailCapRatio * 100)
            : speedPercent();
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
