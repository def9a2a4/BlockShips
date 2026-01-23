package anon.def9a2a4.blockships.util;

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

        // 1.21.9+ has the fix, earlier versions need workaround (SPIGOT-2064)
        needsPassengerEject = !ServerVersion.isAtLeast(1, 21, 9);

        if (needsPassengerEject) {
            LOGGER.info("[TeleportCompat] Version requires passenger eject workaround (SPIGOT-2064)");
        } else {
            LOGGER.info("[TeleportCompat] Version has native passenger teleport support");
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
