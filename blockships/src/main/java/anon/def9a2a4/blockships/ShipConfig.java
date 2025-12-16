package anon.def9a2a4.blockships;

import org.bukkit.plugin.Plugin;
import org.joml.Vector3f;

/**
 * Encapsulates all ship configuration values loaded from config.yml.
 * Reduces boilerplate in ShipInstance constructor.
 */
public class ShipConfig {
    public final boolean collisionDebugGlow;

    // Movement controls
    public final float maxSpeed;
    public final float acceleration;
    public final float deceleration;
    public final float rotationSpeed;
    public final float rotationAcceleration;

    // Physics
    public final float activeDeceleration;
    public final float mountedDrag;
    public final float unmannedDrag;
    public final float idleDrag;
    public final float rotationDeceleration;
    public final float minMovementThreshold;
    public final float deckPhysicsMinVelocity;
    public final float deckPhysicsMaxVelocity;

    // Buoyancy
    public final float buoyancyStrength;
    public final float buoyancyDamping;
    public final int waterScanAbove;
    public final int waterScanBelow;

    // Weight-based buoyancy (custom ships)
    public final float airDensity;
    public final float waterDensity;

    // Airship controls (for custom ships lighter than air)
    public final float liftAcceleration;
    public final float descendAcceleration;
    public final float maxVerticalSpeed;
    public final float verticalDrag;
    public final float verticalForwardNudge;

    // Collision physics
    public final float shipMass;
    public final float collisionResponseStrength;
    public final float terrainCollisionStrength;
    public final float minPenetrationDepth;
    public final float collisionForceDecay;
    public final float terrainSpeedMultiplier;
    public final float collisionDetectionRadius;  // Radius for getNearbyEntities (-1 = auto-calculate)
    public final float boatMass;
    public final float mobSmallMass;
    public final float mobMediumMass;
    public final float mobLargeMass;

    // Custom ship offsets (cached to avoid config reads every tick)
    public final Vector3f customDisplayOffset;
    public final Vector3f customCollisionOffset;

    private ShipConfig(Builder b) {
        this.collisionDebugGlow = b.collisionDebugGlow;
        this.maxSpeed = b.maxSpeed;
        this.acceleration = b.acceleration;
        this.deceleration = b.deceleration;
        this.rotationSpeed = b.rotationSpeed;
        this.rotationAcceleration = b.rotationAcceleration;
        this.activeDeceleration = b.activeDeceleration;
        this.mountedDrag = b.mountedDrag;
        this.unmannedDrag = b.unmannedDrag;
        this.idleDrag = b.idleDrag;
        this.rotationDeceleration = b.rotationDeceleration;
        this.minMovementThreshold = b.minMovementThreshold;
        this.deckPhysicsMinVelocity = b.deckPhysicsMinVelocity;
        this.deckPhysicsMaxVelocity = b.deckPhysicsMaxVelocity;
        this.buoyancyStrength = b.buoyancyStrength;
        this.buoyancyDamping = b.buoyancyDamping;
        this.waterScanAbove = b.waterScanAbove;
        this.waterScanBelow = b.waterScanBelow;
        this.airDensity = b.airDensity;
        this.waterDensity = b.waterDensity;
        this.liftAcceleration = b.liftAcceleration;
        this.descendAcceleration = b.descendAcceleration;
        this.maxVerticalSpeed = b.maxVerticalSpeed;
        this.verticalDrag = b.verticalDrag;
        this.verticalForwardNudge = b.verticalForwardNudge;
        this.shipMass = b.shipMass;
        this.collisionResponseStrength = b.collisionResponseStrength;
        this.terrainCollisionStrength = b.terrainCollisionStrength;
        this.minPenetrationDepth = b.minPenetrationDepth;
        this.collisionForceDecay = b.collisionForceDecay;
        this.terrainSpeedMultiplier = b.terrainSpeedMultiplier;
        this.collisionDetectionRadius = b.collisionDetectionRadius;
        this.boatMass = b.boatMass;
        this.mobSmallMass = b.mobSmallMass;
        this.mobMediumMass = b.mobMediumMass;
        this.mobLargeMass = b.mobLargeMass;
        this.customDisplayOffset = b.customDisplayOffset;
        this.customCollisionOffset = b.customCollisionOffset;
    }

    /**
     * Loads ship configuration from config.yml for a specific ship type.
     */
    public static ShipConfig load(Plugin plugin, String shipType) {
        String p = "ships." + shipType + ".";
        var cfg = plugin.getConfig();

        return new Builder()
            .collisionDebugGlow(cfg.getBoolean(p + "collision-debug-glow", false))
            // Movement controls
            .maxSpeed((float) cfg.getDouble(p + "controls.max-speed", 0.5))
            .acceleration((float) cfg.getDouble(p + "controls.acceleration", 0.02))
            .deceleration((float) cfg.getDouble(p + "controls.deceleration", 0.015))
            .rotationSpeed((float) cfg.getDouble(p + "controls.rotation-speed", 1.5))
            .rotationAcceleration((float) cfg.getDouble(p + "controls.rotation-acceleration", 0.3))
            // Physics
            .activeDeceleration((float) cfg.getDouble(p + "controls.active-deceleration", 0.025))
            .mountedDrag((float) cfg.getDouble(p + "controls.mounted-drag", 0.99))
            .unmannedDrag((float) cfg.getDouble(p + "controls.unmanned-drag", 0.97))
            .idleDrag((float) cfg.getDouble(p + "controls.idle-drag", 0.93))
            .rotationDeceleration((float) cfg.getDouble(p + "controls.rotation-deceleration", 0.15))
            .minMovementThreshold((float) cfg.getDouble(p + "controls.min-movement-threshold", 0.01))
            .deckPhysicsMinVelocity((float) cfg.getDouble("physics.deck-physics-min-velocity", 0.1))
            .deckPhysicsMaxVelocity((float) cfg.getDouble("physics.deck-physics-max-velocity", 10.0))
            // Buoyancy (custom ships read from custom-ships.buoyancy section)
            .buoyancyStrength((float) cfg.getDouble(
                "custom".equals(shipType)
                    ? "custom-ships.buoyancy.strength"
                    : p + "controls.buoyancy-strength",
                0.05))
            .buoyancyDamping((float) cfg.getDouble(
                "custom".equals(shipType)
                    ? "custom-ships.buoyancy.damping"
                    : p + "controls.buoyancy-damping",
                0.5))
            .waterScanAbove(cfg.getInt(p + "controls.water-scan-above", 5))
            .waterScanBelow(cfg.getInt(p + "controls.water-scan-below", 10))
            // Weight-based buoyancy (from custom-ships section)
            .airDensity((float) cfg.getDouble("custom-ships.buoyancy.air-density", 0.0))
            .waterDensity((float) cfg.getDouble("custom-ships.buoyancy.water-density", 2.5))
            // Airship controls (custom ships read from custom-ships.airship-controls, prefab from ship config)
            .liftAcceleration((float) cfg.getDouble(
                "custom".equals(shipType)
                    ? "custom-ships.airship-controls.lift-acceleration"
                    : p + "controls.lift-acceleration",
                0.05))
            .descendAcceleration((float) cfg.getDouble(
                "custom".equals(shipType)
                    ? "custom-ships.airship-controls.descend-acceleration"
                    : p + "controls.descend-acceleration",
                0.05))
            .maxVerticalSpeed((float) cfg.getDouble(
                "custom".equals(shipType)
                    ? "custom-ships.airship-controls.max-vertical-speed"
                    : p + "controls.max-vertical-speed",
                0.3))
            .verticalDrag((float) cfg.getDouble(
                "custom".equals(shipType)
                    ? "custom-ships.airship-controls.vertical-drag"
                    : p + "controls.vertical-drag",
                0.9))
            .verticalForwardNudge((float) cfg.getDouble(
                "custom".equals(shipType)
                    ? "custom-ships.airship-controls.vertical-forward-nudge"
                    : p + "controls.vertical-forward-nudge",
                0.011))
            // Collision physics
            .shipMass((float) cfg.getDouble(p + "collision.mass", 100.0))
            .collisionResponseStrength((float) cfg.getDouble(p + "collision.response-strength", 0.3))
            .terrainCollisionStrength((float) cfg.getDouble(p + "collision.terrain-strength", 1.0))
            .minPenetrationDepth((float) cfg.getDouble(p + "collision.min-penetration", 0.05))
            .collisionForceDecay((float) cfg.getDouble(p + "collision.force-decay", 0.5))
            .terrainSpeedMultiplier((float) cfg.getDouble(p + "collision.terrain-speed-multiplier", 10.0))
            .collisionDetectionRadius((float) cfg.getDouble(p + "collision.detection-radius", -1.0))
            // Entity masses (global)
            .boatMass((float) cfg.getDouble("entity-masses.boat", 20.0))
            .mobSmallMass((float) cfg.getDouble("entity-masses.mob-small", 10.0))
            .mobMediumMass((float) cfg.getDouble("entity-masses.mob-medium", 50.0))
            .mobLargeMass((float) cfg.getDouble("entity-masses.mob-large", 200.0))
            // Custom ship offsets (only used for custom ships, but loaded for all)
            .customDisplayOffset(readVector3f(cfg, "custom-ships.display-offset", new Vector3f(0, 1.975f, 0)))
            .customCollisionOffset(readVector3f(cfg, "custom-ships.collision-offset", new Vector3f(0, 0, 0)))
            .build();
    }

    private static Vector3f readVector3f(org.bukkit.configuration.file.FileConfiguration cfg, String key, Vector3f defaultValue) {
        java.util.List<Double> values = cfg.getDoubleList(key);
        if (values.size() >= 3) {
            return new Vector3f(
                values.get(0).floatValue(),
                values.get(1).floatValue(),
                values.get(2).floatValue()
            );
        }
        return new Vector3f(defaultValue);
    }

    private static class Builder {
        boolean collisionDebugGlow = false;
        float maxSpeed = 0.5f;
        float acceleration = 0.02f;
        float deceleration = 0.015f;
        float rotationSpeed = 1.5f;
        float rotationAcceleration = 0.3f;
        float activeDeceleration = 0.025f;
        float mountedDrag = 0.99f;
        float unmannedDrag = 0.97f;
        float idleDrag = 0.93f;
        float rotationDeceleration = 0.15f;
        float minMovementThreshold = 0.01f;
        float deckPhysicsMinVelocity = 0.1f;
        float deckPhysicsMaxVelocity = 10.0f;
        float buoyancyStrength = 0.15f;
        float buoyancyDamping = 0.7f;
        int waterScanAbove = 5;
        int waterScanBelow = 10;
        float airDensity = 0.0f;
        float waterDensity = 2.5f;
        float liftAcceleration = 0.05f;
        float descendAcceleration = 0.05f;
        float maxVerticalSpeed = 0.3f;
        float verticalDrag = 0.9f;
        float verticalForwardNudge = 0.011f;
        float shipMass = 100.0f;
        float collisionResponseStrength = 0.3f;
        float terrainCollisionStrength = 1.0f;
        float minPenetrationDepth = 0.05f;
        float collisionForceDecay = 0.5f;
        float terrainSpeedMultiplier = 10.0f;
        float collisionDetectionRadius = -1.0f;
        float boatMass = 20.0f;
        float mobSmallMass = 10.0f;
        float mobMediumMass = 50.0f;
        float mobLargeMass = 200.0f;
        Vector3f customDisplayOffset = new Vector3f(0, 1.975f, 0);
        Vector3f customCollisionOffset = new Vector3f(0, 0, 0);

        Builder collisionDebugGlow(boolean v) { collisionDebugGlow = v; return this; }
        Builder maxSpeed(float v) { maxSpeed = v; return this; }
        Builder acceleration(float v) { acceleration = v; return this; }
        Builder deceleration(float v) { deceleration = v; return this; }
        Builder rotationSpeed(float v) { rotationSpeed = v; return this; }
        Builder rotationAcceleration(float v) { rotationAcceleration = v; return this; }
        Builder activeDeceleration(float v) { activeDeceleration = v; return this; }
        Builder mountedDrag(float v) { mountedDrag = v; return this; }
        Builder unmannedDrag(float v) { unmannedDrag = v; return this; }
        Builder idleDrag(float v) { idleDrag = v; return this; }
        Builder rotationDeceleration(float v) { rotationDeceleration = v; return this; }
        Builder minMovementThreshold(float v) { minMovementThreshold = v; return this; }
        Builder deckPhysicsMinVelocity(float v) { deckPhysicsMinVelocity = v; return this; }
        Builder deckPhysicsMaxVelocity(float v) { deckPhysicsMaxVelocity = v; return this; }
        Builder buoyancyStrength(float v) { buoyancyStrength = v; return this; }
        Builder buoyancyDamping(float v) { buoyancyDamping = v; return this; }
        Builder waterScanAbove(int v) { waterScanAbove = v; return this; }
        Builder waterScanBelow(int v) { waterScanBelow = v; return this; }
        Builder airDensity(float v) { airDensity = v; return this; }
        Builder waterDensity(float v) { waterDensity = v; return this; }
        Builder liftAcceleration(float v) { liftAcceleration = v; return this; }
        Builder descendAcceleration(float v) { descendAcceleration = v; return this; }
        Builder maxVerticalSpeed(float v) { maxVerticalSpeed = v; return this; }
        Builder verticalDrag(float v) { verticalDrag = v; return this; }
        Builder verticalForwardNudge(float v) { verticalForwardNudge = v; return this; }
        Builder shipMass(float v) { shipMass = v; return this; }
        Builder collisionResponseStrength(float v) { collisionResponseStrength = v; return this; }
        Builder terrainCollisionStrength(float v) { terrainCollisionStrength = v; return this; }
        Builder minPenetrationDepth(float v) { minPenetrationDepth = v; return this; }
        Builder collisionForceDecay(float v) { collisionForceDecay = v; return this; }
        Builder terrainSpeedMultiplier(float v) { terrainSpeedMultiplier = v; return this; }
        Builder collisionDetectionRadius(float v) { collisionDetectionRadius = v; return this; }
        Builder boatMass(float v) { boatMass = v; return this; }
        Builder mobSmallMass(float v) { mobSmallMass = v; return this; }
        Builder mobMediumMass(float v) { mobMediumMass = v; return this; }
        Builder mobLargeMass(float v) { mobLargeMass = v; return this; }
        Builder customDisplayOffset(Vector3f v) { customDisplayOffset = v; return this; }
        Builder customCollisionOffset(Vector3f v) { customCollisionOffset = v; return this; }

        ShipConfig build() { return new ShipConfig(this); }
    }
}
