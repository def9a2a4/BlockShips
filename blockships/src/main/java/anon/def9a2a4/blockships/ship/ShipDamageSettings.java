package anon.def9a2a4.blockships.ship;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * The {@code damage:} section of config.yml. Global, read at enable and on {@code /blockships reload}
 * (alongside {@link ShipInstance#loadGlobalPhysicsConfig}), so a reload reaches ships already at sea.
 */
public final class ShipDamageSettings {

    private ShipDamageSettings() {}

    /** 0 = per-ship (a hit counts once, at its largest collider share), 1 = per-block (every collider counts). */
    public static double spread = 0;
    /** On all damage to a ship, on top of the per-cause multipliers. */
    public static double multiplier = 1.0;
    public static double explosionMultiplier = 1.0;
    public static boolean potions = true;
    public static boolean poison = false;
    public static double suffocationMultiplier = 0.25;

    public static void load(FileConfiguration cfg, Logger log) {
        String mode = cfg.getString("damage.mode", "per-ship").trim().toLowerCase(Locale.ROOT);
        spread = switch (mode) {
            case "per-block" -> 1;
            case "per-ship" -> 0;
            default -> {
                log.warning("damage.mode '" + mode + "' is not per-ship or per-block; using per-ship");
                yield 0;
            }
        };
        multiplier = java.lang.Math.max(0, cfg.getDouble("damage.multiplier", 1.0));
        explosionMultiplier = java.lang.Math.max(0, cfg.getDouble("damage.explosion-multiplier", 1.0));
        potions = cfg.getBoolean("damage.potions", true);
        poison = cfg.getBoolean("damage.poison", false);
        suffocationMultiplier = java.lang.Math.max(0, cfg.getDouble("damage.suffocation-multiplier", 0.25));

        if (!cfg.isSet("damage.mode")) {
            log.info("Ship damage now counts each hit once per ship rather than once per block it touches."
                + " To restore the old behaviour, add 'damage: {mode: per-block}' to config.yml.");
        }
    }
}
