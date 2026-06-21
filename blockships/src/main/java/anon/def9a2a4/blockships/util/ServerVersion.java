package anon.def9a2a4.blockships.util;

import org.bukkit.Bukkit;
import java.util.logging.Logger;

/**
 * Unified server version detection for cross-version compatibility.
 * Parses the Bukkit version once at startup and provides version comparison methods.
 *
 * Used by TeleportCompat, SteerPacketCompat, and other compat classes.
 */
public class ServerVersion {
    private static final Logger LOGGER = Logger.getLogger("BlockShips");

    private static int major = 1;
    private static int minor = 0;
    private static int patch = 0;
    private static int versionNumber = 0;
    private static boolean initialized = false;

    static {
        initialize();
    }

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        String bukkitVersion = Bukkit.getBukkitVersion();
        LOGGER.info("[ServerVersion] Parsing version: " + bukkitVersion);

        try {
            // Take leading numeric dot-separated segments; stop at the first non-numeric one.
            // Formats seen: "1.21.1-R0.1-SNAPSHOT", "26.1.2-R0.1-SNAPSHOT", "26.2.build.24-alpha", "26.2.0"
            String[] parts = bukkitVersion.split("-")[0].split("\\.");
            int[] nums = {0, 0, 0};
            int count = 0;
            for (String part : parts) {
                if (count >= 3 || !part.matches("\\d+")) break;
                nums[count++] = Integer.parseInt(part);
            }
            if (count == 0) {
                throw new NumberFormatException("no numeric version components in '" + bukkitVersion + "'");
            }
            major = nums[0];
            minor = nums[1];
            patch = nums[2];
            versionNumber = major * 10000 + minor * 100 + patch;
            LOGGER.info("[ServerVersion] Detected version: " + major + "." + minor + "." + patch);
        } catch (Exception e) {
            LOGGER.severe("[ServerVersion] Could not parse version '" + bukkitVersion + "': " + e.getMessage() + " - defaulting to 1.21.11");
            major = 1;
            minor = 21;
            patch = 11;
            versionNumber = 12111;
        }
    }

    /**
     * Check if server is at least the specified version.
     * @param maj Major version (e.g., 1)
     * @param min Minor version (e.g., 21)
     * @param pat Patch version (e.g., 2)
     * @return true if server version >= specified version
     */
    public static boolean isAtLeast(int maj, int min, int pat) {
        if (!initialized) initialize();
        return versionNumber >= (maj * 10000 + min * 100 + pat);
    }

    public static int getMajor() {
        if (!initialized) initialize();
        return major;
    }

    public static int getMinor() {
        if (!initialized) initialize();
        return minor;
    }

    public static int getPatch() {
        if (!initialized) initialize();
        return patch;
    }
}
