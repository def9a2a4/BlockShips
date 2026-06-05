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

    // Ship stats (power-to-mass ratio system, custom ships only)
    public final int basePower;              // Free power points every ship gets (default: 2)
    public final int enginePower;            // Power points per fueled engine (default: 30)
    public final int woolPower;              // Power points per wool block (default: 3)
    public final int bannerPower;            // Power points per banner block (default: 7)
    public final float fuelBurnMultiplier;   // Multiplier for fuel burn times (default: 1.0)
    public final float sailCapRatio;         // Sail contribution capped at this ratio (default: 0.8)
    public final float defaultRatio;         // Ratio that maps to current default stats (default: 0.7)
    public final float maxRatioMultiplier;   // Stats multiplier at ratio 1.0, relative to default (default: 1.5)
    // Absolute floors (minimum stat values regardless of ratio)
    public final float floorMaxSpeed;        // 0.05 blocks/tick = 1 block/sec
    public final float floorAcceleration;
    public final float floorRotationSpeed;   // 0.6 deg/tick = 30s per revolution
    public final float floorRotationAcceleration;
    public final float floorRotationDeceleration;
    public final float capRotationDeceleration;
    // Absolute caps (maximum stat values regardless of ratio)
    public final float capMaxSpeed;
    public final float capAcceleration;
    public final float capRotationSpeed;
    public final float capRotationAcceleration;
    // Airship vertical stats scaling
    public final float verticalDensityScale;       // How much density magnitude affects vertical ratio
    public final float verticalEngineScale;        // How much engine_points/mass affects vertical ratio
    public final float floorMaxVerticalSpeed;
    public final float floorVerticalAcceleration;
    public final float capMaxVerticalSpeed;
    public final float capVerticalAcceleration;

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

    // Sound settings
    public final float soundMinSpeed;
    public final float airshipSoundMinSpeed;
    public final int soundIntervalTicks;
    public final int airshipSoundIntervalTicks;
    public final float soundVolume;
    public final float airshipSoundVolume;
    public final float soundPitch;

    // Display settings
    public final int displayInterpolationDuration;  // Ticks for display entity interpolation (default: 2, range: 1-4)

    // Camera settings
    public final float cameraDistance;  // Third-person camera distance when riding (default: 4, range: 0-32)

    // Assembly/disassembly settings
    public final float assemblyNudgeHeight;  // Teleport players up by this amount during assembly/disassembly (0 to disable)

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
        this.basePower = b.basePower;
        this.enginePower = b.enginePower;
        this.woolPower = b.woolPower;
        this.bannerPower = b.bannerPower;
        this.fuelBurnMultiplier = b.fuelBurnMultiplier;
        this.sailCapRatio = b.sailCapRatio;
        this.defaultRatio = b.defaultRatio;
        this.maxRatioMultiplier = b.maxRatioMultiplier;
        this.floorMaxSpeed = b.floorMaxSpeed;
        this.floorAcceleration = b.floorAcceleration;
        this.floorRotationSpeed = b.floorRotationSpeed;
        this.floorRotationAcceleration = b.floorRotationAcceleration;
        this.floorRotationDeceleration = b.floorRotationDeceleration;
        this.capRotationDeceleration = b.capRotationDeceleration;
        this.capMaxSpeed = b.capMaxSpeed;
        this.capAcceleration = b.capAcceleration;
        this.capRotationSpeed = b.capRotationSpeed;
        this.capRotationAcceleration = b.capRotationAcceleration;
        this.verticalDensityScale = b.verticalDensityScale;
        this.verticalEngineScale = b.verticalEngineScale;
        this.floorMaxVerticalSpeed = b.floorMaxVerticalSpeed;
        this.floorVerticalAcceleration = b.floorVerticalAcceleration;
        this.capMaxVerticalSpeed = b.capMaxVerticalSpeed;
        this.capVerticalAcceleration = b.capVerticalAcceleration;
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
        this.soundMinSpeed = b.soundMinSpeed;
        this.airshipSoundMinSpeed = b.airshipSoundMinSpeed;
        this.soundIntervalTicks = b.soundIntervalTicks;
        this.airshipSoundIntervalTicks = b.airshipSoundIntervalTicks;
        this.soundVolume = b.soundVolume;
        this.airshipSoundVolume = b.airshipSoundVolume;
        this.soundPitch = b.soundPitch;
        this.displayInterpolationDuration = b.displayInterpolationDuration;
        this.cameraDistance = b.cameraDistance;
        this.assemblyNudgeHeight = b.assemblyNudgeHeight;
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
            .customDisplayOffset(ShipModel.readVector3fFromConfig(cfg, "custom-ships.display-offset", new Vector3f(0, 1.975f, 0)))
            .customCollisionOffset(ShipModel.readVector3fFromConfig(cfg, "custom-ships.collision-offset", new Vector3f(0, 0, 0)))
            // Movement sound settings (per-ship with fallback to global)
            .soundMinSpeed((float) cfg.getDouble(p + "movement-sounds.min-speed", cfg.getDouble("sounds.min-speed", 0.1)))
            .airshipSoundMinSpeed((float) cfg.getDouble(p + "movement-sounds.airship-min-speed", cfg.getDouble("sounds.airship-min-speed", 0.45)))
            .soundIntervalTicks(cfg.getInt(p + "movement-sounds.interval-ticks", cfg.getInt("sounds.interval-ticks", 20)))
            .airshipSoundIntervalTicks(cfg.getInt(p + "movement-sounds.airship-interval-ticks", cfg.getInt("sounds.airship-interval-ticks", 12)))
            .soundVolume((float) cfg.getDouble(p + "movement-sounds.volume", cfg.getDouble("sounds.volume", 1.5)))
            .airshipSoundVolume((float) cfg.getDouble(p + "movement-sounds.airship-volume", cfg.getDouble("sounds.airship-volume", 0.15)))
            .soundPitch((float) cfg.getDouble(p + "movement-sounds.pitch", cfg.getDouble("sounds.pitch", 1.0)))
            // Display interpolation (global setting, higher = smoother but more latency)
            .displayInterpolationDuration(cfg.getInt("physics.display-interpolation-duration", 2))
            // Camera distance (for prefab ships; custom ships use per-ship value from ShipWheelData)
            .cameraDistance((float) cfg.getDouble(p + "camera-distance", 4.0))
            .assemblyNudgeHeight((float) cfg.getDouble("custom-ships.assembly-nudge-height", 0.2))
            // Ship stats (power-to-mass ratio system)
            .basePower(cfg.getInt("custom-ships.stats.base-power", 2))
            .enginePower(cfg.getInt("custom-ships.stats.engine-power", 30))
            .woolPower(cfg.getInt("custom-ships.stats.wool-power", 3))
            .bannerPower(cfg.getInt("custom-ships.stats.banner-power", 7))
            .fuelBurnMultiplier((float) cfg.getDouble("custom-ships.stats.fuel-burn-multiplier", 1.0))
            .sailCapRatio((float) cfg.getDouble("custom-ships.stats.sail-cap-ratio", 0.8))
            .defaultRatio((float) cfg.getDouble("custom-ships.stats.default-ratio", 0.7))
            .maxRatioMultiplier((float) cfg.getDouble("custom-ships.stats.max-ratio-multiplier", 1.5))
            .floorMaxSpeed((float) cfg.getDouble("custom-ships.stats.floor-max-speed", 0.05))
            .floorAcceleration((float) cfg.getDouble("custom-ships.stats.floor-acceleration", 0.015))
            .floorRotationSpeed((float) cfg.getDouble("custom-ships.stats.floor-rotation-speed", 0.6))
            .floorRotationAcceleration((float) cfg.getDouble("custom-ships.stats.floor-rotation-acceleration", 0.05))
            .floorRotationDeceleration((float) cfg.getDouble("custom-ships.stats.floor-rotation-deceleration", 0.05))
            .capRotationDeceleration((float) cfg.getDouble("custom-ships.stats.cap-rotation-deceleration", -1))
            .capMaxSpeed((float) cfg.getDouble("custom-ships.stats.cap-max-speed", -1))
            .capAcceleration((float) cfg.getDouble("custom-ships.stats.cap-acceleration", -1))
            .capRotationSpeed((float) cfg.getDouble("custom-ships.stats.cap-rotation-speed", -1))
            .capRotationAcceleration((float) cfg.getDouble("custom-ships.stats.cap-rotation-acceleration", -1))
            .verticalDensityScale((float) cfg.getDouble("custom-ships.stats.vertical-density-scale", 0.3))
            .verticalEngineScale((float) cfg.getDouble("custom-ships.stats.vertical-engine-scale", 0.01))
            .floorMaxVerticalSpeed((float) cfg.getDouble("custom-ships.stats.floor-max-vertical-speed", 0.03))
            .floorVerticalAcceleration((float) cfg.getDouble("custom-ships.stats.floor-vertical-acceleration", 0.01))
            .capMaxVerticalSpeed((float) cfg.getDouble("custom-ships.stats.cap-max-vertical-speed", 0.5))
            .capVerticalAcceleration((float) cfg.getDouble("custom-ships.stats.cap-vertical-acceleration", 0.1))
            .build();
    }

    /**
     * Computes an effective stat value from a power-to-mass ratio using linear interpolation.
     * ratio 0.0 → floor, ratio defaultRatio → defaultVal, ratio 1.0 → cap.
     * Result is clamped to [floor, cap].
     */
    public float computeStat(float ratio, float defaultVal, float floor, float configCap) {
        float cap = configCap > 0 ? configCap : defaultVal * maxRatioMultiplier;
        float stat;
        if (ratio <= defaultRatio) {
            // Interpolate floor → default over ratio 0.0 → defaultRatio
            float t = defaultRatio > 0 ? ratio / defaultRatio : 0;
            stat = floor + t * (defaultVal - floor);
        } else if (defaultRatio < 1.0f) {
            // Interpolate default → cap over ratio defaultRatio → 1.0
            float t = (ratio - defaultRatio) / (1.0f - defaultRatio);
            stat = defaultVal + t * (cap - defaultVal);
        } else {
            stat = cap;
        }
        return Math.max(floor, Math.min(cap, stat));
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
        // Ship stats defaults
        int basePower = 2;
        int enginePower = 30;
        int woolPower = 3;
        int bannerPower = 7;
        float fuelBurnMultiplier = 1.0f;
        float sailCapRatio = 0.8f;
        float defaultRatio = 0.7f;
        float maxRatioMultiplier = 1.5f;
        float floorMaxSpeed = 0.05f;           // 1 block/sec
        float floorAcceleration = 0.015f;
        float floorRotationSpeed = 0.6f;       // 30s per revolution
        float floorRotationAcceleration = 0.05f;
        float floorRotationDeceleration = 0.05f;
        float capRotationDeceleration = -1f;
        float capMaxSpeed = -1f;               // -1 = auto (maxRatioMultiplier * default)
        float capAcceleration = -1f;
        float capRotationSpeed = -1f;
        float capRotationAcceleration = -1f;
        float verticalDensityScale = 0.3f;
        float verticalEngineScale = 0.01f;
        float floorMaxVerticalSpeed = 0.03f;
        float floorVerticalAcceleration = 0.01f;
        float capMaxVerticalSpeed = 0.5f;
        float capVerticalAcceleration = 0.1f;

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
        float soundMinSpeed = 0.1f;
        float airshipSoundMinSpeed = 0.45f;
        int soundIntervalTicks = 20;
        int airshipSoundIntervalTicks = 12;
        float soundVolume = 1.5f;
        float airshipSoundVolume = 0.15f;
        float soundPitch = 1.0f;
        int displayInterpolationDuration = 2;  // Ticks for display entity interpolation (1-4)
        float cameraDistance = 4.0f;  // Default matches Minecraft default
        float assemblyNudgeHeight = 0.2f;

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
        Builder basePower(int v) { basePower = v; return this; }
        Builder enginePower(int v) { enginePower = v; return this; }
        Builder woolPower(int v) { woolPower = v; return this; }
        Builder bannerPower(int v) { bannerPower = v; return this; }
        Builder fuelBurnMultiplier(float v) { fuelBurnMultiplier = v; return this; }
        Builder sailCapRatio(float v) { sailCapRatio = v; return this; }
        Builder defaultRatio(float v) { defaultRatio = v; return this; }
        Builder maxRatioMultiplier(float v) { maxRatioMultiplier = v; return this; }
        Builder floorMaxSpeed(float v) { floorMaxSpeed = v; return this; }
        Builder floorAcceleration(float v) { floorAcceleration = v; return this; }
        Builder floorRotationSpeed(float v) { floorRotationSpeed = v; return this; }
        Builder floorRotationAcceleration(float v) { floorRotationAcceleration = v; return this; }
        Builder floorRotationDeceleration(float v) { floorRotationDeceleration = v; return this; }
        Builder capRotationDeceleration(float v) { capRotationDeceleration = v; return this; }
        Builder capMaxSpeed(float v) { capMaxSpeed = v; return this; }
        Builder capAcceleration(float v) { capAcceleration = v; return this; }
        Builder capRotationSpeed(float v) { capRotationSpeed = v; return this; }
        Builder capRotationAcceleration(float v) { capRotationAcceleration = v; return this; }
        Builder verticalDensityScale(float v) { verticalDensityScale = v; return this; }
        Builder verticalEngineScale(float v) { verticalEngineScale = v; return this; }
        Builder floorMaxVerticalSpeed(float v) { floorMaxVerticalSpeed = v; return this; }
        Builder floorVerticalAcceleration(float v) { floorVerticalAcceleration = v; return this; }
        Builder capMaxVerticalSpeed(float v) { capMaxVerticalSpeed = v; return this; }
        Builder capVerticalAcceleration(float v) { capVerticalAcceleration = v; return this; }
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
        Builder soundMinSpeed(float v) { soundMinSpeed = v; return this; }
        Builder airshipSoundMinSpeed(float v) { airshipSoundMinSpeed = v; return this; }
        Builder soundIntervalTicks(int v) { soundIntervalTicks = v; return this; }
        Builder airshipSoundIntervalTicks(int v) { airshipSoundIntervalTicks = v; return this; }
        Builder soundVolume(float v) { soundVolume = v; return this; }
        Builder airshipSoundVolume(float v) { airshipSoundVolume = v; return this; }
        Builder soundPitch(float v) { soundPitch = v; return this; }
        Builder displayInterpolationDuration(int v) { displayInterpolationDuration = Math.max(1, Math.min(4, v)); return this; }
        Builder cameraDistance(float v) { cameraDistance = v; return this; }
        Builder assemblyNudgeHeight(float v) { assemblyNudgeHeight = v; return this; }

        ShipConfig build() { return new ShipConfig(this); }
    }
}
