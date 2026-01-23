package anon.def9a2a4.blockships.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.List;
import java.util.logging.Logger;

/**
 * Compatibility layer for Entity.teleport() behavior changes between Minecraft versions.
 *
 * Problem: Entity.teleport() silently fails when the entity has passengers (SPIGOT-2064).
 * This bug exists in Spigot/Paper versions before 1.21.9.
 *
 * Version history:
 * - Pre-1.21.9: teleport() fails silently if entity has passengers
 * - 1.21.9+: teleport() retains passengers by default (vanilla behavior)
 *
 * This class detects the version at startup and provides a teleport method that
 * works correctly on all versions.
 */
public class TeleportCompat {
    private static final Logger LOGGER = Logger.getLogger("BlockShips");

    // Version detection result - set once at startup
    private static boolean needsPassengerEject = false;
    private static boolean initialized = false;

    static {
        initialize();
    }

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        // Parse server version to determine if we need the passenger eject workaround
        // Format examples: "1.21.1-R0.1-SNAPSHOT", "1.21.9-R0.1-SNAPSHOT"
        String bukkitVersion = Bukkit.getBukkitVersion();
        LOGGER.info("[TeleportCompat] Detecting teleport behavior for version: " + bukkitVersion);

        try {
            // Extract major.minor.patch from version string
            String[] parts = bukkitVersion.split("-")[0].split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;

            // 1.21.9+ has the fix, earlier versions need workaround
            // Compare as: major * 10000 + minor * 100 + patch
            int version = major * 10000 + minor * 100 + patch;
            int fixedVersion = 1 * 10000 + 21 * 100 + 9; // 1.21.9

            needsPassengerEject = version < fixedVersion;

            if (needsPassengerEject) {
                LOGGER.info("[TeleportCompat] Version " + bukkitVersion + " requires passenger eject workaround (SPIGOT-2064)");
            } else {
                LOGGER.info("[TeleportCompat] Version " + bukkitVersion + " has native passenger teleport support");
            }
        } catch (Exception e) {
            // If version parsing fails, assume we need the workaround (safer)
            needsPassengerEject = true;
            LOGGER.warning("[TeleportCompat] Could not parse version '" + bukkitVersion + "', assuming passenger eject needed: " + e.getMessage());
        }
    }

    /**
     * Check if this server version requires the passenger eject workaround.
     * @return true if teleport() fails with passengers (pre-1.21.9)
     */
    public static boolean needsPassengerEject() {
        if (!initialized) initialize();
        return needsPassengerEject;
    }

    /**
     * Teleport an entity to a location, handling passenger compatibility.
     * On pre-1.21.9 servers, this ejects passengers before teleporting and re-adds them after.
     * On 1.21.9+ servers, this just calls teleport() directly.
     *
     * @param entity The entity to teleport
     * @param location The destination location
     */
    public static void teleport(Entity entity, Location location) {
        if (!initialized) initialize();

        if (needsPassengerEject) {
            teleportWithEject(entity, location);
        } else {
            entity.teleport(location);
        }
    }

    /**
     * Teleport an entity by ejecting passengers first, then re-adding them.
     * This is the workaround for SPIGOT-2064.
     */
    private static void teleportWithEject(Entity entity, Location location) {
        List<Entity> passengers = entity.getPassengers();
        boolean hadPassengers = !passengers.isEmpty();

        if (hadPassengers) {
            entity.eject();
        }

        entity.teleport(location);

        if (hadPassengers) {
            for (Entity passenger : passengers) {
                entity.addPassenger(passenger);
            }
        }
    }
}
