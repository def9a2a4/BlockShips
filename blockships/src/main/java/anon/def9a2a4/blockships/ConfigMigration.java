package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * One-shot config upgrades for servers that already have a {@code config.yml} on disk.
 *
 * <p>Most new settings need nothing here: every read is {@code getX(path, javaDefault)}, so an absent
 * key just takes its Java default. The exception is a setting whose default we want to <b>change</b>
 * — {@code saveDefaultConfig()} only ever writes the file once, so an existing server keeps the old
 * value forever and never sees the new behaviour.
 *
 * <p>Mostly conservative, with two honest exceptions. The v0→v1 stats flip rewrites any
 * {@code stats.enabled: false} — the old shipped config extracted that literal into every server's
 * file, so an admin's explicit false is indistinguishable from the untouched default; both are
 * deliberately flipped (an explicit {@code true} is the one choice that provably was one, and it is
 * kept). And the v1→v2 step deletes {@code lift-falloff-exponent} regardless of its value, tuned or
 * not, because the two systems run in opposite directions. Everything else follows the conservative
 * rule: rewrite only what still matches the old shipped default.
 *
 * <p><b>Never runs against a config.yml that failed to parse.</b> Bukkit turns a YAML error into an
 * empty configuration with the jar's copy hung underneath as defaults, and {@code getInt(key, 0)} reads
 * the section's own map without consulting those defaults - so an unreadable file looks exactly like a
 * pre-versioned one, migration "upgrades" the empty stub, and {@link org.bukkit.plugin.Plugin#saveConfig()}
 * writes it back over the admin's real settings. That is unrecoverable and survives a reinstall, since
 * {@code saveDefaultConfig()} then sees a file present. The parse result is passed in rather than
 * re-derived, because by this point {@code getConfig()} can no longer tell the two cases apart.
 */
public final class ConfigMigration {

    private ConfigMigration() {}

    /** Bump when adding a migration step below. */
    private static final int CURRENT_VERSION = 2;

    private static final String VERSION_KEY = "config-version";

    public static void run(Plugin plugin, ConfigValidator.MainConfigStatus configStatus) {
        // checkMainConfig already logged the failure; all we owe the admin here is to not touch the file.
        if (configStatus.failedToParse()) return;

        FileConfiguration cfg = plugin.getConfig();
        int version = cfg.getInt(VERSION_KEY, 0);
        if (version >= CURRENT_VERSION) return;

        boolean changed = false;

        // v0 -> v1: the ship-stats system was shipped disabled, pending "a rework of the stats system
        // in a future update". That rework has landed (sail tiers, propulsion, the three-ratio model),
        // so the flag turns on — but only for servers that never chose to enable it themselves.
        if (version < 1) {
            if (!cfg.getBoolean("custom-ships.stats.enabled", false)) {
                cfg.set("custom-ships.stats.enabled", true);
                plugin.getLogger().info(
                    "Config upgrade: custom-ships.stats.enabled turned ON. Ship performance now scales "
                  + "with sails and mass rather than being fixed. Set it back to false to restore the "
                  + "previous behaviour.");
                changed = true;
            }
            // Removed with the experimental ship-engine subsystem; nothing reads them.
            if (cfg.contains("custom-ships.stats.fuel-burn-multiplier")) {
                cfg.set("custom-ships.stats.fuel-burn-multiplier", null);
                changed = true;
            }
            if (cfg.contains("custom-ships.stats.vertical-engine-scale")) {
                cfg.set("custom-ships.stats.vertical-engine-scale", null);
                changed = true;
            }
        }

        // v1 -> v2: the vertical model was rebuilt. Descent used to cancel gravity in proportion to
        // lift^lift-falloff-exponent; it now scales with how much lift is MISSING, (1 - lift)^sink-speed-exponent.
        //
        // The key is DELETED rather than renamed on purpose. The two exponents point opposite ways —
        // raising the old one made descent harsher, raising the new one makes it gentler — so carrying
        // a server's tuned value across would silently invert its intent. Better to drop it and let the
        // new default apply than to honour a number that now means the reverse.
        if (version < 2) {
            if (cfg.contains("custom-ships.stats.lift-falloff-exponent")) {
                cfg.set("custom-ships.stats.lift-falloff-exponent", null);
                plugin.getLogger().info(
                    "Config upgrade: custom-ships.stats.lift-falloff-exponent removed. Descent is now "
                  + "shaped by sink-speed-exponent, which scales with missing lift rather than with lift "
                  + "— the two run in opposite directions, so the old value was not carried over.");
                changed = true;
            }
        }

        cfg.set(VERSION_KEY, CURRENT_VERSION);
        plugin.saveConfig();
        plugin.getLogger().info("Config upgraded to version " + CURRENT_VERSION
            + (changed ? "." : " (no settings needed changing)."));
    }
}
