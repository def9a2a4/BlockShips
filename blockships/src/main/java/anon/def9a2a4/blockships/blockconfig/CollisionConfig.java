package anon.def9a2a4.blockships.blockconfig;

import org.joml.Vector3f;

/**
 * Collision configuration for a block.
 * Compatible with the collision format used in prefab ship configs.
 */
public class CollisionConfig {
    private final boolean enabled;
    private final float size;
    private final Vector3f offset;

    // Default collision: enabled, size 1.0, no offset
    public static final CollisionConfig DEFAULT = new CollisionConfig(true, 1.0f, new Vector3f(0, 0, 0));

    // No collision
    public static final CollisionConfig NONE = new CollisionConfig(false, 1.0f, new Vector3f(0, 0, 0));

    public CollisionConfig(boolean enabled, float size, Vector3f offset) {
        this.enabled = enabled;
        this.size = size;
        this.offset = offset;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public float getSize() {
        return size;
    }

    public Vector3f getOffset() {
        return offset;
    }

    /**
     * Create from config value: true/false/{size, offset}
     */
    public static CollisionConfig fromConfigValue(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value) ? DEFAULT : NONE;
        }
        // If it's a map, parse size and offset (handled by BlockConfigManager)
        return DEFAULT;
    }

    @Override
    public String toString() {
        if (!enabled) {
            return "CollisionConfig{disabled}";
        }
        return String.format("CollisionConfig{size=%.2f, offset=[%.2f, %.2f, %.2f]}",
            size, offset.x, offset.y, offset.z);
    }
}
