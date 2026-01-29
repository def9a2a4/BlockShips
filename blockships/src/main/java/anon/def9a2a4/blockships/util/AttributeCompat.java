package anon.def9a2a4.blockships.util;

import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Compatibility layer for Attribute API changes between Minecraft versions.
 *
 * Version history:
 * - 1.20.4 and earlier: No GENERIC_SCALE attribute
 * - 1.20.5+: Added GENERIC_SCALE attribute
 * - 1.21.2 and earlier: Attribute is an enum with GENERIC_MAX_HEALTH, GENERIC_SCALE
 * - 1.21.3+: Attribute is an interface, use Registry.ATTRIBUTE.get() with short names (max_health, scale)
 * - 1.21.6+: Added GENERIC_CAMERA_DISTANCE attribute for third-person camera distance when riding entities
 *
 * This class uses PURE REFLECTION to avoid bytecode references that would fail on older versions.
 */
public class AttributeCompat {
    private static final Logger LOGGER = Logger.getLogger("BlockShips");

    private static Attribute MAX_HEALTH;
    private static Attribute SCALE;
    private static Attribute CAMERA_DISTANCE;
    private static boolean initialized = false;
    private static boolean scaleAvailable = true;
    private static boolean cameraDistanceAvailable = true;

    static {
        initialize();
    }

    private static void initialize() {
        if (initialized) return;
        initialized = true;

        LOGGER.info("[AttributeCompat] Initializing attribute compatibility layer...");

        // Resolve MAX_HEALTH - required for health regen
        MAX_HEALTH = resolveAttribute("max_health", "GENERIC_MAX_HEALTH", "MAX_HEALTH", true);
        LOGGER.info("[AttributeCompat] MAX_HEALTH resolved successfully");

        // Resolve SCALE - optional (added in 1.20.5)
        SCALE = resolveAttribute("scale", "GENERIC_SCALE", "SCALE", false);
        if (SCALE != null) {
            LOGGER.info("[AttributeCompat] SCALE resolved successfully");
        } else {
            scaleAvailable = false;
            LOGGER.info("[AttributeCompat] SCALE not available on this server version - shulkers will use default size");
        }

        // Resolve CAMERA_DISTANCE - optional (added in 1.21.6)
        CAMERA_DISTANCE = resolveAttribute("camera_distance", "GENERIC_CAMERA_DISTANCE", "CAMERA_DISTANCE", false);
        if (CAMERA_DISTANCE != null) {
            LOGGER.info("[AttributeCompat] CAMERA_DISTANCE resolved successfully");
        } else {
            cameraDistanceAvailable = false;
            LOGGER.info("[AttributeCompat] CAMERA_DISTANCE not available - using default camera distance");
        }
    }

    /**
     * Resolve an attribute using multiple reflection-based strategies for cross-version compatibility.
     * Uses ONLY reflection to avoid any direct bytecode references to Attribute constants.
     *
     * @param registryKey The registry key name (e.g., "max_health", "scale") - used for Registry lookup
     * @param legacyEnumName The legacy enum name (e.g., "GENERIC_MAX_HEALTH") - used for 1.21.2 and earlier
     * @param newFieldName The new field name (e.g., "MAX_HEALTH") - used for 1.21.3+ interface
     * @param required If true, throws RuntimeException when not found; if false, returns null
     * @return The resolved Attribute, or null if not found and not required
     */
    private static Attribute resolveAttribute(String registryKey, String legacyEnumName, String newFieldName, boolean required) {
        Attribute attr = null;

        // Strategy 1: Try Registry.ATTRIBUTE.get() via reflection (works on 1.21.x)
        attr = tryRegistryLookup(registryKey);
        if (attr != null) {
            return attr;
        }

        // Strategy 2: Try enum lookup with legacy name (GENERIC_* prefix) via reflection
        attr = tryEnumLookupReflection(legacyEnumName);
        if (attr != null) {
            return attr;
        }

        // Strategy 3: Try static field access with new name (1.21.3+ interface)
        attr = tryFieldAccessReflection(newFieldName);
        if (attr != null) {
            return attr;
        }

        // Strategy 4: Try static field access with legacy name
        attr = tryFieldAccessReflection(legacyEnumName);
        if (attr != null) {
            return attr;
        }

        // Not found
        if (required) {
            throw new RuntimeException("Could not find required attribute: " + registryKey +
                " (tried: Registry lookup, enum name: " + legacyEnumName + ", field names: " + newFieldName + ", " + legacyEnumName + ")");
        }

        return null;
    }

    /**
     * Try Registry.ATTRIBUTE.get(NamespacedKey) via reflection.
     * This avoids direct reference to Registry.ATTRIBUTE which may not exist or behave differently.
     */
    private static Attribute tryRegistryLookup(String key) {
        try {
            // Get Registry class
            Class<?> registryClass = Class.forName("org.bukkit.Registry");

            // Get ATTRIBUTE field from Registry
            Field attributeField = registryClass.getField("ATTRIBUTE");
            Object attributeRegistry = attributeField.get(null);

            if (attributeRegistry == null) {
                LOGGER.fine("[AttributeCompat] Registry.ATTRIBUTE is null");
                return null;
            }

            // Get the get(NamespacedKey) method
            Method getMethod = attributeRegistry.getClass().getMethod("get", NamespacedKey.class);

            // Create the namespaced key and call get()
            NamespacedKey namespacedKey = NamespacedKey.minecraft(key);
            Object result = getMethod.invoke(attributeRegistry, namespacedKey);

            if (result instanceof Attribute) {
                LOGGER.info("[AttributeCompat] Resolved '" + key + "' via Registry.ATTRIBUTE.get()");
                return (Attribute) result;
            }
        } catch (Throwable e) {
            LOGGER.fine("[AttributeCompat] Registry lookup failed for '" + key + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Try to look up an attribute as an enum value using reflection.
     */
    private static Attribute tryEnumLookupReflection(String enumName) {
        try {
            // Check if Attribute is an enum
            if (!Attribute.class.isEnum()) {
                LOGGER.fine("[AttributeCompat] Attribute class is not an enum, skipping enum lookup");
                return null;
            }

            // Use Enum.valueOf via reflection to avoid compile-time binding
            Method valueOfMethod = Enum.class.getMethod("valueOf", Class.class, String.class);
            Object result = valueOfMethod.invoke(null, Attribute.class, enumName);

            if (result instanceof Attribute) {
                LOGGER.info("[AttributeCompat] Resolved '" + enumName + "' via Enum.valueOf()");
                return (Attribute) result;
            }
        } catch (Throwable e) {
            LOGGER.fine("[AttributeCompat] Enum lookup failed for '" + enumName + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Try to access an attribute as a static field using reflection.
     * This handles the case where Attribute is an interface with static fields.
     */
    private static Attribute tryFieldAccessReflection(String fieldName) {
        try {
            Field field = Attribute.class.getField(fieldName);
            Object value = field.get(null);
            if (value instanceof Attribute) {
                return (Attribute) value;
            }
        } catch (Throwable e) {
            // Field not found or access failed - this is expected on some versions
        }
        return null;
    }

    /**
     * Get the MAX_HEALTH attribute for the current server version.
     * @return The MAX_HEALTH attribute, or null if attribute resolution fails on this server version
     */
    public static Attribute getMaxHealth() {
        if (!initialized) initialize();
        return MAX_HEALTH;
    }

    /**
     * Get the SCALE attribute for the current server version.
     * @return The SCALE attribute, or null if not available (servers before 1.20.5)
     */
    public static Attribute getScale() {
        if (!initialized) initialize();
        return SCALE;
    }

    /**
     * Check if the scale attribute is available on this server version.
     * @return true if scale attribute is available (1.20.5+), false otherwise
     */
    public static boolean isScaleAvailable() {
        if (!initialized) initialize();
        return scaleAvailable;
    }

    /**
     * Get the CAMERA_DISTANCE attribute for the current server version.
     * This attribute controls third-person camera distance when riding an entity.
     * @return The CAMERA_DISTANCE attribute, or null if not available (servers before 1.21.6)
     */
    public static Attribute getCameraDistance() {
        if (!initialized) initialize();
        return CAMERA_DISTANCE;
    }

    /**
     * Check if the camera distance attribute is available on this server version.
     * @return true if camera distance attribute is available (1.21.6+), false otherwise
     */
    public static boolean isCameraDistanceAvailable() {
        if (!initialized) initialize();
        return cameraDistanceAvailable;
    }
}
