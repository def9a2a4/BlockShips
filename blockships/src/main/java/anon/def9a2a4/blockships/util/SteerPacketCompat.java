package anon.def9a2a4.blockships.util;

import java.util.logging.Logger;

/**
 * Compatibility layer for STEER_VEHICLE packet format changes.
 *
 * Pre-1.21.2: float sideways, float forward, boolean jump, boolean unmount (no sprint)
 * 1.21.2+: Input record with forward, backward, left, right, jump, sneak, sprint booleans
 *
 * On pre-1.21.2, sprint is not available in the packet, so airship descent uses S+Space combo.
 */
public class SteerPacketCompat {
    private static final Logger LOGGER = Logger.getLogger("BlockShips");
    private static boolean logged = false;

    /**
     * Check if sprint is available in the STEER_VEHICLE packet.
     * @return true if sprint is available (1.21.2+), false otherwise
     */
    public static boolean isSprintAvailable() {
        boolean available = ServerVersion.isAtLeast(1, 21, 2);

        // Log once on first check
        if (!logged) {
            logged = true;
            if (available) {
                LOGGER.info("[SteerPacketCompat] Sprint available in STEER_VEHICLE packet (1.21.2+)");
            } else {
                LOGGER.info("[SteerPacketCompat] Sprint NOT in packet - using S+Space for airship descent");
            }
        }

        return available;
    }

    /**
     * Get the airship controls help text for the current version.
     * @return Help text describing how to ascend/descend
     */
    public static String getAirshipControlsHelp() {
        if (isSprintAvailable()) {
            return "WASD to move. Airship: Space for up, Sprint for down.";
        } else {
            return "WASD to move. Airship: Space for up, S+Space for down.";
        }
    }
}
