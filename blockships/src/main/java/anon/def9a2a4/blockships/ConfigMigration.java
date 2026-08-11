package anon.def9a2a4.blockships;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * One-shot config upgrades for servers that already have a {@code config.yml} on disk.
 *
 * <p>Most new settings need nothing here: every read is {@code getX(path, javaDefault)}, so an absent
 * key just takes its Java default. The exception is a setting whose default we want to <b>change</b>
 * — {@code saveDefaultConfig()} only ever writes the file once, so an existing server keeps the old
 * value forever and never sees the new behaviour.
 *
 * <p>Deliberately conservative: a value is only rewritten when it still matches what the old default
 * shipped as. A server that made a choice keeps it.
 */
public final class ConfigMigration {

    private ConfigMigration() {}

    /** Bump when adding a migration step below. */
    private static final int CURRENT_VERSION = 1;

    private static final String VERSION_KEY = "config-version";

    public static void run(BlockShipsPlugin plugin) {
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

        cfg.set(VERSION_KEY, CURRENT_VERSION);
        plugin.saveConfig();
        if (changed) {
            plugin.getLogger().info("Config upgraded to version " + CURRENT_VERSION + ".");
        }
    }
}
