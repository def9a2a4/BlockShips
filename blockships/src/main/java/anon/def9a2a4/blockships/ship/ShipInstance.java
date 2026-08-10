package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.*;
import anon.def9a2a4.blockships.customships.ShipWheelData;
import anon.def9a2a4.blockships.customships.ShipWheelManager;
import anon.def9a2a4.blockships.util.TeleportCompat;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.joml.*;

import java.util.*;

public class ShipInstance {
    // Global physics config (loaded once from physics section)
    private static double MOVEMENT_THRESHOLD = 0.01;
    private static double ROTATION_THRESHOLD = 0.01;
    private static int IDLE_TICKS_BEFORE_STOP = 40;
    private static int IDLE_CHECK_INTERVAL = 20;
    private static float PLAYER_PROXIMITY_RADIUS = 10.0f;
    private static float PLAYER_PROXIMITY_RADIUS_SQ = 100.0f;  // Squared for fast distance checks
    private static boolean SHIP_LIGHTS_ENABLED = true;
    private static boolean TNT_ENABLED = false;
    private static int TNT_FUSE_TICKS = 80;
    private static boolean POSITION_SYNC_ENABLED = true;
    private static double POSITION_SYNC_THRESHOLD_SQ = 0.02 * 0.02;

    /**
     * Loads global physics config values from plugin config.
     * Should be called once during plugin initialization.
     */
    public static void loadGlobalPhysicsConfig(JavaPlugin plugin) {
        var cfg = plugin.getConfig();
        MOVEMENT_THRESHOLD = cfg.getDouble("physics.movement-threshold", 0.01);
        ROTATION_THRESHOLD = cfg.getDouble("physics.rotation-threshold", 0.01);
        IDLE_TICKS_BEFORE_STOP = cfg.getInt("physics.idle-ticks-before-stop", 40);
        IDLE_CHECK_INTERVAL = cfg.getInt("physics.idle-check-interval", 20);
        PLAYER_PROXIMITY_RADIUS = (float) cfg.getDouble("physics.player-proximity-radius", 10.0);
        PLAYER_PROXIMITY_RADIUS_SQ = PLAYER_PROXIMITY_RADIUS * PLAYER_PROXIMITY_RADIUS;
        SHIP_LIGHTS_ENABLED = cfg.getBoolean("ship-lights", true);
        TNT_ENABLED = cfg.getBoolean("cannons.tnt-enabled", false);
        TNT_FUSE_TICKS = cfg.getInt("cannons.tnt-fuse-ticks", 80);
        POSITION_SYNC_ENABLED = cfg.getBoolean("physics.position-sync-enabled", true);
        double threshold = cfg.getDouble("physics.position-sync-threshold", 0.02);
        POSITION_SYNC_THRESHOLD_SQ = threshold * threshold;
    }

    /**
     * Safely parses a BlockFace from a YAML map, returning a default value on failure.
     * Handles null values and invalid enum names gracefully.
     */
    static BlockFace safeBlockFace(Map<?, ?> yaml, String key, BlockFace defaultValue) {
        Object val = yaml.get(key);
        if (val == null) return defaultValue;
        try {
            return BlockFace.valueOf(val.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    public final JavaPlugin plugin;
    public final ShipModel model;
    public final String shipType;  // Ship type identifier (e.g., "smallship", "bigship")
    public ArmorStand vehicle;  // Root entity used for physics (non-final for chunk recovery)
    // Delegated engine: defCoreLib owns the displays/colliders/mounting; this holds the live Mechanism
    // (always present — every ship is delegated).
    public final anon.def9a2a4.corelib.Mechanism mechanism;
    private Location cachedVehicleLoc;  // Cached per-tick to avoid redundant getLocation() clones
    public final int driverSeatIndex;  // Index of driver seat (always 0)
    public final UUID id;  // Ship UUID - generated on spawn or restored from state
    public final ShipCustomization customization;  // Ship customization data (banner, wood type, colors, textures)

    public final List<Shulker> seatShulkers = new ArrayList<>();  // Seat shulkers in order (index 0 = driver)
    private final Set<Integer> occupiedSeatIndices = new HashSet<>();  // Track which seats are occupied
    public Shulker leadableShulker;  // Designated lead attachment point (for prefab ships)
    public anon.def9a2a4.blockships.customships.ShipWheelData wheelData;  // Reference to wheel data (set during assembly)

    /**
     * Lazily resolves wheelData if not set (e.g., after chunk recovery).
     * Looks up via ShipWheelManager by ship UUID.
     */
    public anon.def9a2a4.blockships.customships.ShipWheelData resolveWheelData() {
        if (wheelData != null) return wheelData;
        if (plugin instanceof anon.def9a2a4.blockships.BlockShipsPlugin bsp) {
            wheelData = bsp.getShipWheelManager().getWheelByShipUUID(id);
            if (wheelData != null && physics != null) {
                physics.recomputeStats();  // Recompute now that fuel state is available
            }
        }
        return wheelData;
    }

    private BukkitRunnable task;
    private BukkitRunnable idleCheckTask;

    // Movement tracking for optimization
    private Location previousVehicleLocation;
    private float previousYaw;
    private float previousPitch;
    float spawnYaw;  // Track spawn yaw for display rotation delta calculation
    private float metadataYaw = Float.NaN;  // Yaw from per-world metadata (for chunk recovery)
    private int ticksSinceLastMovement = 0;
    private boolean taskStopped = false;
    private boolean firstTick = true; // Force first tick to update positions

    // Speed display optimization - only update action bar when speed changes significantly
    private static final float SPEED_DISPLAY_THRESHOLD = 0.02f;
    private float previousDisplayedSpeed = 0f;

    // All config values loaded from config.yml
    public final ShipConfig config;

    // Delegate instances for physics and collision
    public ShipPhysics physics;
    public ShipCollision collision;

    // Driver and player tracking (public for delegates)
    public boolean hasDriver = false;
    public boolean hasPlayersNearby = false;

    // Input state tracking (set by ShipSteeringListener, read by ShipPhysics)
    public boolean isForwardPressed = false;
    public boolean isBackwardPressed = false;
    public boolean isLeftPressed = false;
    public boolean isRightPressed = false;

    // Vertical input state for airships (set by ShipSteeringListener, read by ShipPhysics)
    public boolean isSpacePressed = false;
    public boolean isSprintPressed = false;

    // Airship mode flag - determined by density at construction
    public final boolean isAirship;

    // Collision detection radius (cached for getNearbyEntities optimization)
    public float collisionRadius = -1;

    // Custom ship support - for ships assembled from blocks
    public ShipModel sourceModel = null;  // Original block model for disassembly

    // Chunk tracking for persistence - updated on movement
    private int currentChunkX, currentChunkZ;

    // Throttle passenger integrity checks (only needed after chunk reload, not every tick)
    private int passengerCheckCounter = 0;
    private static final int PASSENGER_CHECK_INTERVAL = 20;

    // Reusable matrices for updateCollisionPositions() - object pooling to reduce GC pressure
    private final Matrix4f workRotation = new Matrix4f();
    private final Matrix4f workTranslation = new Matrix4f();
    private final Matrix4f workWorld = new Matrix4f();
    private final Vector3f workVehicleRot = new Vector3f();
    private final Vector3f workTransformedRot = new Vector3f();
    private final Vector3f workOffset = new Vector3f();
    private final Vector3f workPerBlockOffset = new Vector3f();
    private final Vector3f workCurrentWorldPos = new Vector3f();
    private final Vector3f workVelocity = new Vector3f();

    // Reusable objects for getDisplayTransform() - reduces GC pressure in per-tick display updates
    private final Vector3f workDisplayRot = new Vector3f();
    private final Vector3f workDisplayTransformedRot = new Vector3f();
    private final Matrix4f workDisplayDelta = new Matrix4f();
    private final Matrix4f workR_initial = new Matrix4f();  // For initial rotation matrix
    private final Matrix4f cachedR_initial = new Matrix4f(); // Pre-computed initial rotation (model.initialRotation is final)
    private float initialRotRadX, initialRotRadY, initialRotRadZ; // Pre-computed toRadians(model.initialRotation)
    private final Matrix4f workR = new Matrix4f();          // For combined rotation matrix
    private final Matrix4f workT = new Matrix4f();          // For translation matrix
    private final Matrix4f workT_display = new Matrix4f();  // For display translation matrix
    private final Matrix4f workWorldMatrix = new Matrix4f(); // For per-display world transform

    // Reusable Bukkit objects for tick loop - reduces GC pressure
    private Location workCarrierLoc;  // Lazily initialized (needs World reference from vehicle)
    private final org.bukkit.util.Vector workVehicleVelocity = new org.bukkit.util.Vector();

    /** Rebuild the ship {@link ShipCustomization} (banner / wood / balloon) from a persisted state. Shared by
     *  the delegated {@link #fromRecoveredMechanism} recovery + migration paths. */
    public static ShipCustomization buildCustomizationFromState(JavaPlugin plugin, ShipPersistence.ShipState state) {
        ItemStack customBanner = null;
        if (state.bannerData != null) {
            try {
                byte[] bytes = Base64.getDecoder().decode(state.bannerData);
                customBanner = ItemStack.deserializeBytes(bytes);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to deserialize banner for ship " + state.id + ": " + e.getMessage());
            }
        }
        return ShipCustomization.builder()
            .banner(customBanner)
            .woodType(state.woodType)
            .balloonColor(state.balloonColor)
            .build();
    }

    /** Restore persisted block-storage inventory contents. Storage is mechanism-owned (built empty by
     *  {@code createTypedInventory} at assembly), so overlay saved contents ONTO {@code mechanism.getStorage(i)}. */
    private static void restoreInventoriesFromState(JavaPlugin plugin, ShipInstance instance,
                                                    ShipPersistence.ShipState state, ShipModel model) {
        if (state.inventoryData.isEmpty()) return;
        for (Map.Entry<Integer, String> entry : state.inventoryData.entrySet()) {
            try {
                String[] itemStrings = entry.getValue().split("\\|", -1);
                ItemStack[] items = new ItemStack[itemStrings.length];
                for (int i = 0; i < itemStrings.length; i++) {
                    if (!itemStrings[i].isEmpty()) {
                        byte[] bytes = Base64.getDecoder().decode(itemStrings[i]);
                        items[i] = ItemStack.deserializeBytes(bytes);
                    } else {
                        items[i] = null;
                    }
                }
                // Create inventory for this block if it exists in model
                int blockIdx = entry.getKey();
                if (blockIdx < model.parts.size()) {
                    ShipModel.ModelPart part = model.parts.get(blockIdx);
                    if (part.storage != null) {
                        // Overlay saved contents onto the mechanism's inventory. Null = model/mechanism index
                        // mismatch; skip.
                        Inventory storage = instance.mechanism.getStorage(blockIdx);
                        if (storage == null) continue;
                        // Cap to the inventory size: `items` is sized from the persisted
                        // token count, which can exceed the (possibly changed) storage size.
                        storage.setContents(java.util.Arrays.copyOf(items,
                            java.lang.Math.min(items.length, storage.getSize())));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to deserialize inventory at block " + entry.getKey() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Reconstruct a DELEGATED custom {@link ShipInstance} from a defCoreLib-recovered {@link Mechanism} + the
     * ship sidecar state, after a restart or chunk reload (fired via a recovered {@code MechanismAssembleEvent}).
     * The mechanism already owns the recovered vehicle/display/collider/seat entities; this wraps them so
     * ship-domain logic (physics, steering, health, UI) works again.
     */
    public static ShipInstance fromRecoveredMechanism(JavaPlugin plugin, ShipPersistence.ShipState state,
                                                      ShipModel model, ArmorStand vehicle,
                                                      anon.def9a2a4.corelib.Mechanism mechanism) {
        // The delegated ctor resets vehicle health to model.maxHealth; the ArmorStand persisted its real
        // health in NBT across the restart/reload, so capture it first and re-apply after (matching native
        // recovery, which reads health live off the recovered vehicle). Clamp: a model edited between sessions
        // could make the saved health exceed the new max and setHealth would throw.
        double savedHealth = vehicle.getHealth();
        ShipCustomization customization = buildCustomizationFromState(plugin, state);

        ShipInstance inst = new ShipInstance(plugin, state.shipType, model, vehicle.getLocation(),
            customization, vehicle, mechanism);
        inst.sourceModel = model;
        inst.metadataYaw = state.yaw;
        inst.vehicle.setHealth(java.lang.Math.min(savedHealth, model.maxHealth));

        // Seed the heading from the persisted absolute yaw (spawnYaw stays model.assemblyYaw, so the first
        // repositionDriven(currentYaw - spawnYaw) reproduces the saved display rotation with no jump). The
        // delegated ctor already set currentYaw = spawnYaw = assemblyYaw; override with the saved heading.
        if (!Float.isNaN(state.yaw)) {
            inst.physics.currentYaw = ShipTags.normalizeYaw(state.yaw);
            inst.previousYaw = inst.physics.currentYaw;
        }

        restoreInventoriesFromState(plugin, inst, state, model);
        inst.adoptMechanismSeatsForRecovery();
        return inst;
    }


    /**
     * Full fresh-spawn ctor. When {@code mechanism != null} (delegated custom ship, M1), defCoreLib owns the
     * displays/colliders/mounting: {@code providedVehicle} is the ArmorStand the mechanism was assembled on,
     * and ALL native vehicle/parent/display/collider spawn + mount below is skipped. When both are null this is
     * the classic native path (prefab ships; and any legacy custom spawn). {@code providedVehicle} must be
     * non-null iff {@code mechanism} is non-null.
     */
    public ShipInstance(JavaPlugin plugin, String shipType, ShipModel model, Location spawnLocation,
                        ShipCustomization customization,
                        ArmorStand providedVehicle,
                        anon.def9a2a4.corelib.Mechanism mechanism) {
        this.plugin = plugin;
        this.shipType = shipType;
        this.model = model;
        this.customization = customization != null ? customization : ShipCustomization.empty();
        this.mechanism = mechanism;
        // Identity unification (M1): a ship shares its Mechanism's UUID so ShipRegistry and the ship sidecar
        // both key on it (no ship-UUID↔mechId map).
        this.id = mechanism.id();
        this.driverSeatIndex = 0;

        // Load all config values
        this.config = ShipConfig.load(plugin, shipType);

        // Determine if this is an airship:
        // 1. Prefab ships with config type: airship
        // 2. Custom ships with density less than air
        String typeValue = plugin.getConfig().getString("ships." + shipType + ".type", "ship");
        this.isAirship = "airship".equalsIgnoreCase(typeValue) ||
                         ("custom".equals(shipType) && model.getDensity() < config.airDensity);

        World w = spawnLocation.getWorld();
        Location base = spawnLocation.clone();

        // Create root vehicle ArmorStand (for physics, health, display mounting)
        // Players never ride this directly - they ride seat ArmorStands instead
        // If any spawn/setup below throws, tear down whatever was already spawned so a failed
        // assembly leaves no orphaned ghost entities (see catch at the end of the spawn sequence).
        try {
        if (providedVehicle != null) {
            // Delegated (M1): adopt the ArmorStand defCoreLib already spawned + mounted the display chain on
            // (tagged corelib:mech:{id}:vehicle). Add the ship-root tag + health attribute so BlockShips'
            // damage/lookup still resolve it. Its entity yaw is 0 (defCoreLib owns rotation via the display
            // matrix); the ship's heading lives in spawnYaw/currentYaw (seeded from model.assemblyYaw below).
            this.vehicle = providedVehicle;
            this.vehicle.addScoreboardTag(ShipTags.shipRootTag(id));
            org.bukkit.attribute.AttributeInstance maxHealthAttr = this.vehicle.getAttribute(anon.def9a2a4.blockships.util.AttributeCompat.getMaxHealth());
            if (maxHealthAttr != null) {
                maxHealthAttr.setBaseValue(model.maxHealth);
            }
            this.vehicle.setHealth(model.maxHealth);
        }

        // Seats are now the collision shulkers themselves (no separate ArmorStands)
        // Driver seat is always at index 0 (validated in ShipModel.fromFile)

        // Initialize seatShulkers list with nulls (will be populated during collision spawning)
        for (int i = 0; i < model.seats.size(); i++) {
            seatShulkers.add(null);
        }

        // Cache initial rotation radians (model.initialRotation is final)
        this.initialRotRadX = (float) java.lang.Math.toRadians(model.initialRotation.x);
        this.initialRotRadY = (float) java.lang.Math.toRadians(model.initialRotation.y);
        this.initialRotRadZ = (float) java.lang.Math.toRadians(model.initialRotation.z);
        cachedR_initial.identity().rotateY(initialRotRadX).rotateX(initialRotRadY).rotateZ(initialRotRadZ);

        // Initialize delegates
        this.physics = new ShipPhysics(this);
        this.collision = new ShipCollision(this);

        // Compute initial effective stats. For prefab ships this is the final value
        // (config-based, no ratio). For custom ships this is a preliminary computation
        // recomputed after wheelData is linked in ShipWheelManager.
        this.physics.recomputeStats();

        // Initialize previous state
        this.previousVehicleLocation = vehicle.getLocation().clone();
        this.previousPitch = vehicle.getPitch();
        // The vehicle entity yaw is held at 0 (defCoreLib owns rotation via the display transform matrix), so
        // seed the ship's heading from the model's assembly yaw — NOT the vehicle yaw — else physics thrust +
        // the display rotation delta would think the ship faces 0°.
        this.spawnYaw = ShipTags.normalizeYaw(model.assemblyYaw);
        this.previousYaw = this.spawnYaw;
        // P7.C: a DELEGATED PREFAB ship carries its heading in the spawn Location yaw, not in the model
        // (model.assemblyYaw==0 for prefab, unlike custom where assemblyYaw IS the heading). So its rotation
        // baseline spawnYaw stays 0 but currentYaw must start at the placement heading; rotate() then spins
        // the mechanism by (currentYaw − spawnYaw) = the heading. (Recovery re-overrides currentYaw from the
        // persisted absolute yaw.) Custom ships keep currentYaw == spawnYaw.
        boolean delegatedPrefab = !"custom".equals(shipType);
        this.physics.currentYaw = delegatedPrefab
            ? ShipTags.normalizeYaw(spawnLocation.getYaw())
            : this.spawnYaw;
        if (delegatedPrefab) this.previousYaw = this.physics.currentYaw;

        // Initialize chunk tracking for persistence
        this.currentChunkX = vehicle.getLocation().getBlockX() >> 4;
        this.currentChunkZ = vehicle.getLocation().getBlockZ() >> 4;

        } catch (Throwable ex) {
            // Assembly failed partway - despawn everything already spawned (vehicle, parent,
            // block/item displays, collider carriers + shulkers) so no invisible ghosts remain,
            // then rethrow so the caller reports the failure.
            destroy();
            throw ex;
        }

        // Wait 1 tick for entities to spawn, then mount and start ticking
        new BukkitRunnable() {
            @Override
            public void run() {
                // Native mount (prefab + legacy). A delegated custom ship is already mounted by defCoreLib
                // (parent → vehicle, colliders on carriers), so skip — but still start the tick loop below.

                // Start tick loop
                task = new BukkitRunnable() {
                    @Override
                    public void run() {
                        // Cache vehicle location once per tick to avoid redundant clones
                        cachedVehicleLoc = vehicle.getLocation();
                        if (!cachedVehicleLoc.isChunkLoaded()) {
                            return; // Chunk unloaded, suspend ship but don't destroy
                        }
                        if (vehicle.isDead() || !vehicle.isValid()) {
                            destroyWithPersistenceCleanup();
                            cancel();
                            return;
                        }
                        tick();
                    }
                };
                task.runTaskTimer(plugin, 0L, 1L);
            }
        }.runTaskLater(plugin, 1L);
    }

    /**
     * Helper method to destroy ship with persistence cleanup.
     * Used by tick tasks when the vehicle is dead/invalid.
     */
    private void destroyWithPersistenceCleanup() {
        if (plugin instanceof BlockShipsPlugin bsp && bsp.getDisplayShip() != null) {
            destroyWithCleanup(bsp.getDisplayShip().getShipWorldData());
        } else {
            destroy();
        }
    }

    /**
     * Builds a rotation matrix that combines the ship's current orientation with the model's initial rotation.
     * Uses the internal yaw tracked by ShipPhysics (vehicle yaw is frozen at spawnYaw).
     * Used for positioning collision boxes; display entities use a separate delta rotation path.
     */
    private Matrix4f buildRotationMatrix() {
        float yaw = physics.currentYaw;
        float pitch = vehicle.getPitch();

        // Reuse work vectors instead of allocating new ones
        workVehicleRot.set(
            (float) java.lang.Math.toRadians(-yaw),
            (float) java.lang.Math.toRadians(-pitch),
            0f
        );
        model.rotationTransform.transform(workVehicleRot, workTransformedRot);
        workTransformedRot.x += initialRotRadX;
        workTransformedRot.y += initialRotRadY;
        workTransformedRot.z += initialRotRadZ;

        // Reuse workRotation matrix instead of allocating new one
        return workRotation.identity()
            .rotateY(workTransformedRot.x)
            .rotateX(workTransformedRot.y)
            .rotateZ(workTransformedRot.z);
    }

    /**
     * Computes a collider's world position from the rotation matrix, collision translation,
     * local transform, and per-block offset. Writes result into {@code outPos}.
     * All matrix/vector parameters are used as scratch space - callers can pass work fields
     * for zero-alloc usage in the tick loop, or temporary locals elsewhere.
     */
    private static void computeColliderWorldPos(
            Matrix4f R_full, Matrix4f T_collision, Matrix4f localBase, Vector3f perBlockOffset,
            Location vehicleLoc,
            Matrix4f workWorld, Vector3f workOffset, Vector3f workPerBlockOff, Vector3f outPos) {
        workWorld.set(R_full).mul(T_collision).mul(localBase);
        workWorld.getTranslation(workOffset);
        workPerBlockOff.set(perBlockOffset);
        R_full.transformPosition(workPerBlockOff);
        outPos.set(
            (float) vehicleLoc.getX() + workOffset.x + workPerBlockOff.x,
            (float) vehicleLoc.getY() + workOffset.y + workPerBlockOff.y,
            (float) vehicleLoc.getZ() + workOffset.z + workPerBlockOff.z
        );
    }

    /**
     * Live world-space collider boxes for this ship (M3). defCoreLib owns the colliders, so read them via the
     * Mechanism block-index read-API. Both {@link ShipCollision} and {@link ShipCollisionCoordinator} consume
     * this so terrain + ship↔ship collision work. The returned boxes are snapshots (safe to keep for the
     * current pass).
     */
    public java.util.List<org.bukkit.util.BoundingBox> colliderBoxes() {
        // Let core snapshot its colliders directly (O(collider-count), fresh list).
        return mechanism.colliderBoxes();
    }

    /**
     * Calculates the collision detection radius for getNearbyEntities optimization.
     * Uses configured value for prefab ships, or auto-calculates from collider positions.
     */
    private void calculateCollisionRadius() {
        if (config.collisionDetectionRadius > 0) {
            // Use configured value for prefab ships
            this.collisionRadius = config.collisionDetectionRadius;
            return;
        }

        // Auto-calculate using max axis distance from vehicle to farthest collider box center (defCoreLib
        // owns the collider entities).
        Location center = vehicle.getLocation();
        float maxDist = 0;
        for (org.bukkit.util.BoundingBox b : colliderBoxes()) {
            float dx = (float) java.lang.Math.abs(center.getX() - b.getCenterX());
            float dy = (float) java.lang.Math.abs(center.getY() - b.getCenterY());
            float dz = (float) java.lang.Math.abs(center.getZ() - b.getCenterZ());
            float dist = java.lang.Math.max(dx, java.lang.Math.max(dy, dz));
            if (dist > maxDist) maxDist = dist;
        }
        // Add padding (2.0 for original getNearbyEntities radius per collider)
        this.collisionRadius = maxDist + 2.0f;
    }

    public void updateCollisionPositions() {
        Location currentVehicleLoc = cachedVehicleLoc != null ? cachedVehicleLoc : vehicle.getLocation();

        // Calculate collision detection radius once (on first call after colliders are spawned)
        if (collisionRadius < 0) {
            calculateCollisionRadius();
        }

        // Optimization: Check if any players are nearby using player list + squared distance
        // Much faster than getNearbyEntities which searches all entity types in a large area
        hasPlayersNearby = false;
        for (Player player : currentVehicleLoc.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(currentVehicleLoc) <= PLAYER_PROXIMITY_RADIUS_SQ) {
                hasPlayersNearby = true;
                break;
            }
        }

        // The mechanism owns collider/shulker positioning for delegated ships; nothing to sync here beyond
        // the radius + nearby-player flags computed above.
    }

    /**
     * Forces all tracked players to discard and re-receive entity state for carriers.
     * This mimics the entity refresh that happens on relog: the client gets fresh SPAWN_ENTITY
     * packets with exact server-side positions, eliminating accumulated tracker drift that
     * causes player-on-shulker collision jitter.
     */
    void refreshCarrierTracking() {
        java.util.Collection<Player> tracked = vehicle.getTrackedPlayers();
        if (tracked.isEmpty()) return;
        // defCoreLib owns the colliders. Re-track its collider carriers, located by scoreboard tag
        // (corelib:mech:{id}:{i}:carrier — ship.id == mechId). Settle is infrequent (this only fires
        // on a movement->idle transition), so the one-shot nearby scan is cheap. Hiding+showing the
        // carrier re-sends it and its passenger shulker, re-syncing the solid collision box on clients.
        String carrierPrefix = "corelib:mech:" + id + ":";
        double r = (collisionRadius > 0 ? collisionRadius : 32f) + 2.0;
        for (Entity e : vehicle.getWorld().getNearbyEntities(vehicle.getLocation(), r, r, r)) {
            if (!e.isValid()) continue;
            boolean isCarrier = false;
            for (String tag : e.getScoreboardTags()) {
                if (tag.startsWith(carrierPrefix) && tag.endsWith(":carrier")) { isCarrier = true; break; }
            }
            if (!isCarrier) continue;
            for (Player player : tracked) {
                player.hideEntity(plugin, e);
                player.showEntity(plugin, e);
            }
        }
    }

    void tick() {
        // Health regeneration (20 ticks per second)
        // Wrapped in try-catch to prevent tick crash if attribute lookup fails
        try {
            if (vehicle.isValid() && !vehicle.isDead()) {
                org.bukkit.attribute.Attribute maxHealthAttr = anon.def9a2a4.blockships.util.AttributeCompat.getMaxHealth();
                if (maxHealthAttr != null) {
                    org.bukkit.attribute.AttributeInstance attrInstance = vehicle.getAttribute(maxHealthAttr);
                    if (attrInstance != null) {
                        double currentHealth = vehicle.getHealth();
                        double maxHealth = attrInstance.getBaseValue();

                        // Regenerate health per tick (divide by 20 since this runs 20 times per second)
                        double regenPerTick = model.healthRegenPerSecond / 20.0;
                        double newHealth = java.lang.Math.min(currentHealth + regenPerTick, maxHealth);

                        // Only update health when it actually changed (avoids NMS overhead at full health)
                        if (newHealth != currentHealth) {
                            vehicle.setHealth(newHealth);
                            syncSeatShulkerHealth(newHealth);
                        }

                        // Check for ship destruction
                        if (currentHealth <= 0) {
                            destroyAndDropItem();
                            return;  // Stop processing this tick
                        }
                    }
                }
            }
        } catch (Throwable e) {
            // Log once and continue - don't crash the entire tick loop
            if (firstTick) {
                plugin.getLogger().warning("Health regeneration failed (will continue without it): " + e.getMessage());
            }
        }

        // Apply custom physics and steering (runs every tick)
        handleSteeringInput();
        collision.detect();  // Detect collisions and accumulate forces
        physics.update();    // Apply physics (movement, rotation, buoyancy)
        collision.applyResponse();  // Apply collision response
        cachedVehicleLoc = vehicle.getLocation();  // Refresh after physics moved the vehicle

        if (mechanism != null) {
            // Delegated movement (M2): defCoreLib owns the displays/colliders. physics.update()+applyResponse
            // already positioned the vehicle this tick; sync the mechanism to it and apply the rotation delta
            // relative to as-built (spawnYaw). repositionDriven also sets the vehicle's client velocity hint,
            // so we SKIP the native setVelocity / position-sync packet / updateCollisionPositions /
            // updateDisplayTransforms below. Keep only persistence chunk-index + previous-state bookkeeping.
            // M3: the native updateCollisionPositions (which lazily seeds collisionRadius) is skipped for a
            // delegated ship, so seed it here once from the mechanism collider boxes (used by the coordinator).
            if (collisionRadius < 0) calculateCollisionRadius();

            // Item 1 (deck physics): when the ship RISES this tick, lift deck-standers with it BEFORE the colliders
            // move. repositionDriven → repositionColliders teleports the shulkers up by dy; carryRidersUp detects a
            // stander off the shulker's CURRENT top face, so it must run while the shulker is still under their feet
            // (otherwise it overshoots and misses them for dy > 0.05). Use the ACTUAL vehicle Y-delta — NOT
            // physics.currentYVelocity, which applyResponse has already mutated to next tick's value. Seated riders
            // are excluded inside carryRidersUp (they're passengers, already lifted by their seat). Gate on a real
            // rise + a player near the ship so a large/airship isn't scanning colliders every ascending tick empty.
            if (previousVehicleLocation != null && cachedVehicleLoc.getWorld() != null
                    && cachedVehicleLoc.getWorld().equals(previousVehicleLocation.getWorld())) {
                double dy = cachedVehicleLoc.getY() - previousVehicleLocation.getY();
                if (dy > 0.02) {
                    double r = (collisionRadius > 0 ? collisionRadius : PLAYER_PROXIMITY_RADIUS) + 4.0;
                    double rSq = r * r;
                    boolean near = false;
                    for (Player pl : cachedVehicleLoc.getWorld().getPlayers()) {
                        if (pl.getLocation().distanceSquared(cachedVehicleLoc) <= rSq) { near = true; break; }
                    }
                    if (near) mechanism.carryRidersUp(dy);
                }
            }

            mechanism.repositionDriven(physics.currentYaw - spawnYaw);

            // Smoothness (native parity): repositionDriven sets only the vehicle's setVelocity dead-reckoning
            // hint, so the vehicle is tracker-updated every ~3 ticks and rubber-bands against the mechanism's
            // every-tick collider teleports. Send the same per-tick ENTITY_TELEPORT position-sync packet the
            // native path uses to force the vehicle's client position every tick (keeping its passenger display
            // chain locked to the colliders). Same threshold gate as native; compute velocity BEFORE the
            // bookkeeping below refreshes previousVehicleLocation/previousYaw. Vehicle yaw is frozen at 0 for a
            // delegated ship (all visual rotation rides the display transform), so cachedVehicleLoc's yaw is the
            // correct packet yaw. defCoreLib stays ProtocolLib-free; BlockShips owns the packet.
            if (previousVehicleLocation != null && POSITION_SYNC_ENABLED) {
                workVehicleVelocity.setX(cachedVehicleLoc.getX() - previousVehicleLocation.getX())
                    .setY(cachedVehicleLoc.getY() - previousVehicleLocation.getY())
                    .setZ(cachedVehicleLoc.getZ() - previousVehicleLocation.getZ());
                double speedSq = workVehicleVelocity.lengthSquared();
                float yawDelta = java.lang.Math.abs(normalizeAngle(physics.currentYaw - previousYaw));
                if (speedSq > POSITION_SYNC_THRESHOLD_SQ || yawDelta > 0.1f) {
                    sendVehiclePositionSync(cachedVehicleLoc, workVehicleVelocity);
                }
            }

            int nCX = cachedVehicleLoc.getBlockX() >> 4, nCZ = cachedVehicleLoc.getBlockZ() >> 4;
            if ((currentChunkX != nCX || currentChunkZ != nCZ)
                    && plugin instanceof BlockShipsPlugin bsp && bsp.getDisplayShip() != null) {
                bsp.getDisplayShip().getShipWorldData().updateChunkIndex(
                    cachedVehicleLoc.getWorld(), this.id, currentChunkX, currentChunkZ, nCX, nCZ);
                currentChunkX = nCX;
                currentChunkZ = nCZ;
                // M5: re-index the mechanism in defCoreLib's own persistence onto the new pivot chunk too, so a
                // crash MID-VOYAGE recovers the ship where it actually is (corelib recovery keys on the pivot
                // chunk; assembly-time persist() indexed only the launch chunk). persist() MOVES the entry (D4),
                // so no bloat. Cheap: only fires on a chunk-boundary crossing.
                anon.def9a2a4.corelib.MechanismRegistry mr =
                    anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getMechanismRegistry();
                if (mr != null) mr.persist(mechanism);
            }
            // Settle-time collider re-sync (native parity): on the first idle tick after movement,
            // re-track the mechanism's collider carriers so clients discard accumulated tracker drift
            // that jitters players standing on the deck. The native idle gate (updateCollisionPositions
            // path) is unreachable here — the delegated branch returns before it — so replicate it.
            // Evaluate movement BEFORE the previous-state refresh below (hasMovedSinceLastTick reads
            // previousVehicleLocation/previousYaw/previousPitch). The firstTick short-circuit avoids a
            // null previousVehicleLocation on the very first tick, mirroring the native !firstTick gate.
            boolean movedThisTick = firstTick
                || hasMovedSinceLastTick(cachedVehicleLoc, physics.currentYaw, vehicle.getPitch());
            if (!movedThisTick) {
                if (ticksSinceLastMovement == 0) refreshCarrierTracking();
                ticksSinceLastMovement++;
            } else {
                ticksSinceLastMovement = 0;
            }
            firstTick = false;
            previousVehicleLocation = cachedVehicleLoc.clone();
            previousYaw = physics.currentYaw;
            previousPitch = vehicle.getPitch();
            return;
        }
    }

    /**
     * Updates display entity transformations based on the current rotation delta.
     * Vehicle yaw is frozen at spawnYaw - all visual rotation is applied here via the
     * display transformation matrix, using the internal yaw tracked by ShipPhysics.
     * This avoids the entity tracker's byte-precision (~1.4 deg) rotation packets.
     *
     * Called from tick() on every active tick, and from alignToGrid() (to reset
     * the transformation after spawnYaw is re-anchored to currentYaw).
     */

    // Cached ProtocolLib + NMS reflection state for position sync packets
    private static volatile boolean positionSyncInitialized = false;
    private static volatile boolean positionSyncAvailable = false;
    private static volatile java.lang.reflect.Constructor<?> posRotConstructor = null;
    private static volatile java.lang.reflect.Constructor<?> vec3Constructor = null;

    /**
     * Sends an ENTITY_TELEPORT packet for the vehicle to all tracked players.
     * Bypasses the default 3-tick entity tracker interval for ArmorStands, ensuring
     * the client has up-to-date vehicle position every tick. This keeps the display
     * entity passenger chain visually in sync with independently-tracked carriers.
     *
     * Packet structure (1.21.11):
     *   [0] int entityId
     *   [1] PositionMoveRotation(Vec3 position, Vec3 deltaMovement, float yRot, float xRot)
     *   [2] Set relativeTo (empty = absolute)
     *   [3] boolean onGround
     */
    private void sendVehiclePositionSync(Location loc, org.bukkit.util.Vector velocity) {
        if (!positionSyncInitialized) {
            positionSyncInitialized = true;
            try {
                Class.forName("com.comphenix.protocol.ProtocolLibrary");
                // Find NMS PositionMoveRotation constructor: (Vec3, Vec3, float, float)
                Class<?> pmrClass = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
                Class<?> vec3Class = Class.forName("net.minecraft.world.phys.Vec3");
                vec3Constructor = vec3Class.getConstructor(double.class, double.class, double.class);
                posRotConstructor = pmrClass.getConstructor(vec3Class, vec3Class, float.class, float.class);
                positionSyncAvailable = true;
                plugin.getLogger().info("[PositionSync] Vehicle position sync enabled");
            } catch (Exception | NoClassDefFoundError e) {
                positionSyncAvailable = false;
                plugin.getLogger().warning("[PositionSync] Vehicle position sync unavailable: " + e.getMessage());
            }
        }
        if (!positionSyncAvailable) return;

        try {
            com.comphenix.protocol.ProtocolManager pm = com.comphenix.protocol.ProtocolLibrary.getProtocolManager();
            com.comphenix.protocol.events.PacketContainer packet = pm.createPacket(
                com.comphenix.protocol.PacketType.Play.Server.ENTITY_TELEPORT);

            com.comphenix.protocol.reflect.StructureModifier<Object> mod = packet.getModifier();

            // [0] entity ID
            mod.write(0, vehicle.getEntityId());

            // [1] PositionMoveRotation - construct NMS object via cached reflection
            Object pos = vec3Constructor.newInstance(loc.getX(), loc.getY(), loc.getZ());
            double vx = velocity != null ? velocity.getX() : 0;
            double vy = velocity != null ? velocity.getY() : 0;
            double vz = velocity != null ? velocity.getZ() : 0;
            Object vel = vec3Constructor.newInstance(vx, vy, vz);
            Object posRot = posRotConstructor.newInstance(pos, vel, loc.getYaw(), loc.getPitch());
            mod.write(1, posRot);

            // [2] relative flags - leave as empty set (absolute positioning)
            // [3] onGround
            mod.write(3, false);

            for (Player player : vehicle.getTrackedPlayers()) {
                pm.sendServerPacket(player, packet);
            }
        } catch (NoClassDefFoundError | Exception e) {
            positionSyncAvailable = false;
            plugin.getLogger().warning("[PositionSync] Failed to send position packet, disabling: " + e.getMessage());
        }
    }

    private void handleSteeringInput() {
        // Get driver seat shulker (index 0)
        Shulker driverShulker = seatShulkers.isEmpty() ? null : seatShulkers.get(0);

        if (driverShulker == null) {
            hasDriver = false;
            return;
        }

        // Get the player riding the driver shulker
        Player player = null;
        for (Entity passenger : driverShulker.getPassengers()) {
            if (passenger instanceof Player p) {
                player = p;
                break;
            }
        }

        if (player == null) {
            hasDriver = false;
            return;
        }

        hasDriver = true;

        // NOTE: Actual WASD input detection is handled by ShipSteeringListener (ProtocolLib)
        // This method just displays current speed to the player

        // Only update action bar if speed changed significantly (optimization)
        float speedPercent = physics.currentSpeed / config.maxSpeed;  // -1.0 to 1.0
        if (java.lang.Math.abs(speedPercent - previousDisplayedSpeed) < SPEED_DISPLAY_THRESHOLD) {
            return;  // Speed hasn't changed enough, skip update
        }
        previousDisplayedSpeed = speedPercent;

        // Display current speed as action bar with visual progress bar
        int barLength = 20;
        int filledBars = (int) (java.lang.Math.abs(speedPercent) * barLength);

        // Build progress bar
        StringBuilder bar = new StringBuilder();
        String direction = speedPercent >= 0 ? ">" : "<";
        String color = speedPercent >= 0 ? "§a" : "§c";  // Green for forward, red for reverse

        bar.append("§7Speed: ").append(color).append(direction).append(" §7[");
        for (int i = 0; i < barLength; i++) {
            if (i < filledBars) {
                bar.append(color).append("|");
            } else {
                bar.append("§8|");
            }
        }
        bar.append("§7]");

        // Send to action bar
        player.sendActionBar(net.kyori.adventure.text.Component.text(bar.toString()));
    }

    // Set input state from ShipSteeringListener
    public void setInputState(boolean forward, boolean backward, boolean left, boolean right) {
        this.isForwardPressed = forward;
        this.isBackwardPressed = backward;
        this.isLeftPressed = left;
        this.isRightPressed = right;
    }

    // Set vertical input state from ShipSteeringListener (for airships)
    public void setVerticalInputState(boolean space, boolean sprint) {
        this.isSpacePressed = space;
        this.isSprintPressed = sprint;
    }

    // Helper method to normalize angle differences to -180 to 180 range
    private float normalizeAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }

    // Helper method to convert BlockFace to yaw angle for banner rotation
    private static float getYawFromBlockFace(BlockFace face) {
        switch (face) {
            case SOUTH: return 0.0f;
            case SOUTH_SOUTH_WEST: return 22.5f;
            case SOUTH_WEST: return 45.0f;
            case WEST_SOUTH_WEST: return 67.5f;
            case WEST: return 90.0f;
            case WEST_NORTH_WEST: return 112.5f;
            case NORTH_WEST: return 135.0f;
            case NORTH_NORTH_WEST: return 157.5f;
            case NORTH: return 180.0f;
            case NORTH_NORTH_EAST: return 202.5f;
            case NORTH_EAST: return 225.0f;
            case EAST_NORTH_EAST: return 247.5f;
            case EAST: return 270.0f;
            case EAST_SOUTH_EAST: return 292.5f;
            case SOUTH_EAST: return 315.0f;
            case SOUTH_SOUTH_EAST: return 337.5f;
            default: return 0.0f;
        }
    }

    /**
     * Calculate transform matrix for banner display entities.
     * Handles both floor (standing) and wall banners.
     */
    private Matrix4f calculateBannerTransform(Matrix4f baseTransform, Map<?, ?> rawYaml) {
        Matrix4f transform = new Matrix4f(baseTransform);
        boolean isWallBanner = rawYaml.containsKey("banner_facing");

        float bannerYaw = 0.0f;
        if (isWallBanner) {
            BlockFace facing = safeBlockFace(rawYaml, "banner_facing", BlockFace.NORTH);
            bannerYaw = getYawFromBlockFace(facing);
        } else if (rawYaml.containsKey("banner_rotation")) {
            BlockFace rotation = safeBlockFace(rawYaml, "banner_rotation", BlockFace.NORTH);
            bannerYaw = getYawFromBlockFace(rotation);
        }

        float bannerScale = 2f;

        if (isWallBanner) {
            // Wall banner: position at block center, rotate, offset toward wall
            transform.translate(0.5f, 0.5f, 0.5f);
            transform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
            transform.translate(0.0f, -0.97f, -0.5f);  // Down 0.75 + toward wall (local -Z after rotation)
            transform.scale(bannerScale);
        } else {
            // Floor banner: position at block center, rotate to face banner direction
            transform.translate(0.5f, 0.5f, 0.5f);
            transform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
            transform.scale(bannerScale);
        }

        return transform;
    }

    /**
     * Applies the head/skull display transform (in-place) onto {@code transform}.
     * Handles both floor heads (16-step {@code skull_rotation}) and wall heads
     * (4-direction {@code skull_facing}). Shared by the spawn transform, the
     * per-tick display transform, and the chunk-recovery path so the
     * three cannot drift. Applies to player and mob heads identically.
     */
    private void applySkullTransform(Matrix4f transform, Map<?, ?> rawYaml) {
        float skullYaw = 0.0f;
        boolean isWallSkull = rawYaml.containsKey("skull_facing");
        if (rawYaml.containsKey("skull_rotation")) {
            // Floor head: 16-step rotation
            BlockFace rotation = safeBlockFace(rawYaml, "skull_rotation", BlockFace.NORTH);
            skullYaw = getYawFromBlockFace(rotation);
        } else if (isWallSkull) {
            // Wall head: 4-direction facing
            BlockFace facing = safeBlockFace(rawYaml, "skull_facing", BlockFace.NORTH);
            skullYaw = getYawFromBlockFace(facing);
        }

        if (isWallSkull) {
            // Wall skulls: +0.25 Y offset, +180 deg yaw, +0.25 Z toward wall
            transform.translate(0.5f, 0.5f + 0.25f, 0.5f);
            transform.rotateY((float) java.lang.Math.toRadians(-skullYaw + 180));
            transform.translate(0.0f, 0.0f, 0.25f);
        } else {
            // Floor skulls: centered at block center
            transform.translate(0.5f, 0.5f, 0.5f);
            transform.rotateY((float) java.lang.Math.toRadians(-skullYaw));
        }
    }

    // Start a slower-polling task to check for movement when ship is idle
    private void startIdleCheckTask() {
        if (idleCheckTask != null) {
            idleCheckTask.cancel();
        }

        idleCheckTask = new BukkitRunnable() {
            @Override
            public void run() {
                // Check if chunk is loaded - if not, skip check but don't destroy
                Location loc = vehicle.getLocation();
                if (!loc.isChunkLoaded()) {
                    return; // Chunk unloaded, suspend ship but don't destroy
                }
                if (vehicle.isDead() || !vehicle.isValid()) {
                    destroyWithPersistenceCleanup();
                    cancel();
                    return;
                }

                // Check if vehicle has moved
                Location currentLoc = vehicle.getLocation();
                float currentYaw = physics.currentYaw;
                float currentPitch = vehicle.getPitch();

                if (hasMovedSinceLastTick(currentLoc, currentYaw, currentPitch)) {
                    // Movement detected, restart main tick task
                    cancel(); // Stop idle check task
                    idleCheckTask = null;
                    taskStopped = false;
                    ticksSinceLastMovement = 0;

                    task = new BukkitRunnable() {
                        @Override
                        public void run() {
                            // Cache vehicle location once per tick to avoid redundant clones
                            cachedVehicleLoc = vehicle.getLocation();
                            if (!cachedVehicleLoc.isChunkLoaded()) {
                                return; // Chunk unloaded, suspend ship but don't destroy
                            }
                            if (vehicle.isDead() || !vehicle.isValid()) {
                                destroyWithPersistenceCleanup();
                                cancel();
                                return;
                            }
                            tick();
                        }
                    };
                    task.runTaskTimer(plugin, 0L, 1L);
                }
            }
        };
        // Check periodically for movement based on config
        idleCheckTask.runTaskTimer(plugin, (long) IDLE_CHECK_INTERVAL, (long) IDLE_CHECK_INTERVAL);
    }

    /**
     * Check if ship has moved since last tick based on position and rotation changes.
     * Used by tick() and startIdleCheckTask() for idle detection.
     */
    private boolean hasMovedSinceLastTick(Location currentLoc, float currentYaw, float currentPitch) {
        double distanceMoved = currentLoc.distance(previousVehicleLocation);
        double yawChange = java.lang.Math.abs(normalizeAngle(currentYaw - previousYaw));
        double pitchChange = java.lang.Math.abs(normalizeAngle(currentPitch - previousPitch));

        return distanceMoved >= MOVEMENT_THRESHOLD ||
               yawChange >= ROTATION_THRESHOLD ||
               pitchChange >= ROTATION_THRESHOLD;
    }

    /**
     * Deserializes a Base64 skull profile string into a PlayerProfile.
     * Delegates to BlockStructureScanner for the actual implementation.
     */
    private static com.destroystokyo.paper.profile.PlayerProfile deserializeSkullProfile(String textureBase64) {
        return anon.def9a2a4.blockships.customships.BlockStructureScanner.deserializeProfile(textureBase64);
    }

    // ===== Seat Management Methods =====

    /**
     * Gets the first available seat index (driver seat first, then others in order).
     * Returns -1 if all seats are occupied.
     */
    /**
     * Gets the first available seat shulker (driver first, then others).
     * Returns null if all seats are occupied.
     */
    public Shulker getFirstAvailableSeatShulker() {
        // Simple linear scan through seatShulkers list (index 0 = driver, then passengers)
        for (int i = 0; i < seatShulkers.size(); i++) {
            Shulker seat = seatShulkers.get(i);
            if (seat != null) {
                boolean hasPlayer = seat.getPassengers().stream().anyMatch(p -> p instanceof Player);
                if (!hasPlayer) {
                    return seat;
                }
            }
        }
        return null;  // All seats occupied
    }

    /**
     * Marks a seat as occupied.
     */
    public void occupySeat(int seatIndex) {
        occupiedSeatIndices.add(seatIndex);
        if (seatIndex == driverSeatIndex) {
            hasDriver = true;
        }
    }

    /**
     * Calculates a safe dismount position above collision shulkers near the seat.
     * Scans nearby colliders to find the highest top surface and places the player above it.
     */
    public Location calculateSafeDismountPosition(Player player, Shulker seatShulker) {
        Location seatLoc = seatShulker.getLocation();
        Location playerLoc = player.getLocation();

        double seatTopY = seatLoc.getY() + getShulkerHeight(seatShulker);

        // Scan nearby colliders for the highest top surface that overlaps horizontally. Use the engine-agnostic
        // colliderBoxes() (world-space boxes for both engines, null/invalid already filtered) so this works for a
        // delegated ship too — its native `colliders` list is empty, which previously made this a no-op and
        // dropped the player just above the seat (clip risk). getMinY()/getMaxY() are the real box bounds
        // (more accurate than the assumed shulker height).
        double highestTopY = seatTopY;
        double horizontalThreshold = 0.8; // half player width + half shulker width
        for (org.bukkit.util.BoundingBox b : colliderBoxes()) {
            double dx = java.lang.Math.abs(b.getCenterX() - seatLoc.getX());
            double dz = java.lang.Math.abs(b.getCenterZ() - seatLoc.getZ());
            double cbBottomY = b.getMinY();
            if (dx < horizontalThreshold && dz < horizontalThreshold && cbBottomY <= seatTopY + 1.8) {
                double cbTopY = b.getMaxY();
                if (cbTopY > highestTopY) {
                    highestTopY = cbTopY;
                }
            }
        }

        Location safe = seatLoc.clone();
        safe.setY(highestTopY + config.assemblyNudgeHeight);
        safe.setYaw(playerLoc.getYaw());
        safe.setPitch(playerLoc.getPitch());
        return safe;
    }

    /**
     * Scans for nearby players whose bounding box overlaps a collision shulker
     * and teleports them to the top of the highest overlapping collider.
     * Called after updateCollisionPositions() on first tick to fix assembly clipping.
     */

    /** Returns the height of a shulker's collision box (1.0 * scale). */
    private static double getShulkerHeight(Shulker shulker) {
        try {
            org.bukkit.attribute.Attribute scaleAttr = anon.def9a2a4.blockships.util.AttributeCompat.getScale();
            if (scaleAttr != null) {
                org.bukkit.attribute.AttributeInstance inst = shulker.getAttribute(scaleAttr);
                if (inst != null) {
                    return inst.getBaseValue(); // height = 1.0 * scale
                }
            }
        } catch (Throwable ignored) {}
        return 1.0;
    }

    /**
     * Dismounts a player from a ship if they are riding a ship shulker.
     * Calls removePassenger which triggers VehicleExitEvent - the DisplayShip
     * event handler handles safe-position teleport, seat freeing, and velocity transfer.
     * @param player The player to dismount
     * @return true if the player was dismounted from a ship, false otherwise
     */
    public static boolean dismountPlayer(Player player) {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof Shulker shulker)) {
            return false;
        }

        Set<String> tags = shulker.getScoreboardTags();
        // Resolve the ship from the shulker tags. extractShipId is corelib-aware, so this covers BOTH a native
        // seat (displayship:) and a delegated seat (corelib:mech:) — the old isShipEntity gate matched only the
        // native prefix, so forced dismount silently no-op'd on delegated ships (a passenger could get stuck).
        // The byId != null check also rejects a foreign corelib mechanism's shulker (pipes/railbound/etc.).
        UUID sid = ShipTags.extractShipId(tags);
        if (sid == null || ShipRegistry.byId(sid) == null) {
            return false;
        }

        // Remove passenger (triggers VehicleExitEvent synchronously, which handles
        // safe-position teleport and seat freeing via DisplayShip.onPlayerExitVehicle)
        shulker.removePassenger(player);

        return true;
    }

    /**
     * Dismounts a player from any ship, with fallback scan if getVehicle() fails.
     * Used during disconnect when Bukkit may have already cleared the vehicle reference.
     * Bypasses VehicleExitEvent to avoid teleport/velocity logic on a disconnecting player.
     */
    public static boolean dismountPlayerFromAnyShip(Player player) {
        // Fast path: vehicle reference is still valid
        if (dismountPlayer(player)) return true;

        // Fallback: scan all ships' seat shulkers for this player
        for (ShipInstance ship : ShipRegistry.getAllShips()) {
            for (int i = 0; i < ship.seatShulkers.size(); i++) {
                Shulker seat = ship.seatShulkers.get(i);
                if (seat == null) continue;
                if (seat.getPassengers().contains(player)) {
                    seat.removePassenger(player);
                    ship.freeSeat(i);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Marks a seat as free.
     */
    public void freeSeat(int seatIndex) {
        occupiedSeatIndices.remove(seatIndex);
        if (seatIndex == driverSeatIndex) {
            hasDriver = false;
            // Clear all input state when driver exits
            isForwardPressed = false;
            isBackwardPressed = false;
            isLeftPressed = false;
            isRightPressed = false;
            isSpacePressed = false;
            isSprintPressed = false;
            // Kill vertical velocity on driver exit - buoyancy deadzone (0.1 blocks)
            // will catch whatever offset remains without producing jitter
            physics.currentYVelocity = 0.0f;
            // Snap position and rotation to reduce floating-point jitter
            physics.snapToFineGrid();
        }
    }

    /**
     * Syncs seat shulker health to match ship health for HUD display when riding.
     * If maxHealth <= 40, health is shown directly (1:1 mapping).
     * If maxHealth > 40, health is scaled to 20 hearts max (40 HP).
     */
    /**
     * M4: for a DELEGATED ship, designate its seats on the Mechanism and adopt the resulting seat shulkers
     * into {@code seatShulkers}, so the existing seat code (boarding, steering = index 0, HP-mirror, dismount)
     * works unchanged. Block-index parity (mechanism index i == scan index i) makes {@code SeatInfo.blockIndex}
     * a valid Mechanism block index. A seat only materializes if defCoreLib gave that block a collider (its
     * shulker is the mount) — tune {@code colliders.yml} if a seat block is missing.
     */
    public void adoptMechanismSeats() {
        for (int seatIdx = 0; seatIdx < model.seats.size(); seatIdx++) {
            ShipModel.SeatInfo si = model.seats.get(seatIdx);
            mechanism.designateSeat(si.blockIndex, si.isDriver);
            Shulker s = mechanism.seatEntity(si.blockIndex);
            if (s != null) seatShulkers.set(seatIdx, s);
        }
        // Mirror ship HP onto the newly-adopted seat shulkers for the vanilla riding HUD.
        syncSeatShulkerHealth(vehicle.getHealth());
    }

    /**
     * P7.R4: render the correct heading on the FIRST frame after spawn/recovery. The physics tick calls
     * {@code mechanism.repositionDriven(currentYaw − spawnYaw)} every tick, but until that first tick a delegated
     * PREFAB display sits at {@code spawnYaw} (== {@code model.assemblyYaw}, i.e. 0 for a prefab) — a one-frame
     * flash for any non-zero placement/saved heading. Applying it once here removes the flash. A no-op
     * rotation (relYaw==0) for custom ships (currentYaw == spawnYaw). Idempotent with the tick —
     * {@code repositionDriven} is absolute-from-spawn, not incremental.
     */
    public void applyInitialDrivenPose() {
        mechanism.repositionDriven(physics.currentYaw - spawnYaw);
    }

    /**
     * Finalize a freshly-assembled MIGRATED ship (native→delegated). The mechanism + delegated {@link ShipInstance}
     * are already built by {@code DisplayShip.spawnDelegatedFromModel} (seats designated, cargo loaded PRE-persist,
     * mechanism persisted). This adds the recovery-style state the fresh-spawn path doesn't seed:
     * <ul>
     *   <li>{@code sourceModel} — so the re-saved sidecar persists {@code model_data} for later delegated recovery
     *       of a custom ship (defCoreLib rebuilds the mechanism; BlockShips rebuilds the model from the sidecar);</li>
     *   <li>{@code currentYaw} — from the persisted absolute heading ({@code state.yaw}); the delegated custom ctor
     *       leaves currentYaw == spawnYaw (assemblyYaw), so a turned ship must be re-seeded;</li>
     *   <li>the driven pose is re-applied so frame 1 renders at the saved heading.</li>
     * </ul>
     * Inventory contents are NOT restored here — they are loaded into the mechanism's typed inventories BEFORE
     * {@code reg.persist} via the cargo path (#2), so the first crash-safe snapshot already holds them.
     */
    public void finalizeMigration(ShipPersistence.ShipState state) {
        this.sourceModel = model;
        if (!Float.isNaN(state.yaw)) {
            physics.currentYaw = ShipTags.normalizeYaw(state.yaw);
            previousYaw = physics.currentYaw;
        }
        applyInitialDrivenPose();
    }

    /** Decode a persisted sidecar's {@code inventoryData} (block index → "|"-joined Base64 ItemStacks) into a cargo
     *  map (block index → ItemStack[]) for loading into a migrated mechanism's typed inventories BEFORE persist (#2),
     *  so migrated chest contents survive a hard crash rather than depending on a later re-snapshot. Block index i ==
     *  model.parts index i == mechanism block index i. */
    public static Map<Integer, ItemStack[]> decodeCargo(ShipPersistence.ShipState state) {
        Map<Integer, ItemStack[]> cargo = new HashMap<>();
        if (state.inventoryData == null) return cargo;
        for (Map.Entry<Integer, String> entry : state.inventoryData.entrySet()) {
            try {
                String[] itemStrings = entry.getValue().split("\\|", -1);
                ItemStack[] items = new ItemStack[itemStrings.length];
                for (int i = 0; i < itemStrings.length; i++) {
                    if (!itemStrings[i].isEmpty()) {
                        items[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(itemStrings[i]));
                    }
                }
                cargo.put(entry.getKey(), items);
            } catch (Exception e) {
                // Skip a corrupt block's cargo rather than abort the whole migration.
            }
        }
        return cargo;
    }

    /**
     * Recovery counterpart of {@link #adoptMechanismSeats}: defCoreLib already RE-designated this mechanism's
     * seats during recovery (from the persisted shulker tags) and fired onSeatRecovered, so this must NOT call
     * {@code designateSeat} again — it only reads back the seat shulkers into {@code seatShulkers} and re-mirrors
     * ship HP. A seat whose shulker is still in a not-yet-loaded neighbour chunk resolves to {@code null} and
     * stays null (harmless — the same as a seat block with no collider); incremental recovery only finalizes
     * once the footprint is complete, so this is the rare large-ship edge.
     */
    public void adoptMechanismSeatsForRecovery() {
        for (int seatIdx = 0; seatIdx < model.seats.size(); seatIdx++) {
            ShipModel.SeatInfo si = model.seats.get(seatIdx);
            Shulker s = mechanism.seatEntity(si.blockIndex);
            if (s != null) seatShulkers.set(seatIdx, s);
        }
        syncSeatShulkerHealth(vehicle.getHealth());
    }

    public void syncSeatShulkerHealth(double currentHealth) {
        double maxHealth = model.maxHealth;
        double shulkerMaxHealth;
        double shulkerCurrentHealth;

        if (maxHealth <= 40) {
            shulkerMaxHealth = maxHealth;
            shulkerCurrentHealth = java.lang.Math.max(0, currentHealth);
        } else {
            shulkerMaxHealth = 40.0; // 20 hearts
            shulkerCurrentHealth = java.lang.Math.max(0, (currentHealth / maxHealth) * 40.0);
        }

        org.bukkit.attribute.Attribute maxHealthAttr = anon.def9a2a4.blockships.util.AttributeCompat.getMaxHealth();
        for (Shulker seat : seatShulkers) {
            if (seat != null && seat.isValid()) {
                if (maxHealthAttr != null) {
                    org.bukkit.attribute.AttributeInstance attr = seat.getAttribute(maxHealthAttr);
                    if (attr != null) {
                        attr.setBaseValue(shulkerMaxHealth);
                    }
                }
                seat.setHealth(java.lang.Math.min(shulkerCurrentHealth, shulkerMaxHealth));
            }
        }
    }

    /**
     * Creates the virtual storage inventory for a ship storage block.
     * Routes odd-size storage (e.g. HOPPER = 5 slots) through the type-based
     * {@code createInventory} overload, which has no multiple-of-9 restriction that the
     * size-based overload enforces (assembling a hopper would otherwise throw).
     */
    private static Inventory createStorageInventory(ShipModel.StorageConfig sc, String customNameGson) {
        net.kyori.adventure.text.Component title;
        if (customNameGson != null) {
            try {
                // A container's real (anvil) name, captured at scan; full color/format fidelity.
                title = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(customNameGson);
            } catch (Exception e) {
                title = net.kyori.adventure.text.Component.text(sc.name);  // malformed persisted JSON -> generic label
            }
        } else {
            title = net.kyori.adventure.text.Component.text(sc.name);
        }
        return (sc.type.invType != null)
            ? Bukkit.createInventory(null, sc.type.invType, title)
            : Bukkit.createInventory(null, sc.type.slots, title);
    }

    /**
     * Restores storage inventory contents from saved data.
     * Used when loading ships from persistence.
     */

    /**
     * Destroys the ship and drops the appropriate item at the ship's location.
     * For custom ships (block assembly), drops a ship wheel item.
     * For prefab ships, drops a ship kit with customization data.
     * Called when ship health reaches 0.
     */
    public void destroyAndDropItem() {
        if (!vehicle.isValid()) return;

        // Notify all riding players before destruction
        notifyRidersOfDestruction();

        Location dropLocation = vehicle.getLocation();
        World world = dropLocation.getWorld();

        // Custom ships: force disassemble, break wheel, spawn explosions
        if ("custom".equals(shipType) && plugin instanceof BlockShipsPlugin bsp) {
            ShipWheelManager manager = bsp.getShipWheelManager();
            ShipWheelData wheelData = manager.getWheelByShipUUID(this.id);

            if (wheelData != null && sourceModel != null && world != null) {
                // 1. Align ship to grid
                alignToGrid();

                // 2. Collect explosion locations BEFORE destruction
                List<Location> explosionLocations = new ArrayList<>();
                explosionLocations.add(vehicle.getLocation().clone()); // Always include root/wheel location

                // Only the root/wheel explosion location above: a delegated ship's collider entities belong to
                // the mechanism, so no secondary scatter (cosmetic, accepted).

                if (config.destroyOnDeath) {
                    // Full destruction: blocks are lost, only stored items drop. The engine is the SINGLE drop
                    // authority for a delegated ship — cargo, block-entity contents (bs_tsih_items), and leads
                    // are all dropped from Mechanism.destroy() via destroyWithCleanup() below (leads fall back
                    // to Paper's tickLeash when the holder shulker is removed). Do NOT re-add a native drop loop
                    // that reads the mechanism's inventories/colliders here, or items duplicate.
                    // Capture wheel location before entities are removed
                    Location wheelLoc = wheelData.getBlockLocation();
                    // Destroy entities and clean up persistence
                    if (bsp.getDisplayShip() != null) {
                        destroyWithCleanup(bsp.getDisplayShip().getShipWorldData());
                    } else {
                        destroy();
                    }
                    wheelData.setAssembledShipUUID(null);
                    // Remove wheel block from world and tracking (without dropping wheel item)
                    manager.destroyWheelBlock(wheelLoc);
                    // Spawn explosions
                    spawnDestructionExplosions(world, explosionLocations);
                    return;
                }

                // 3. Force disassemble - this calls ship.destroy() internally
                boolean disassembled = manager.disassembleShip(null, wheelData, true);

                if (disassembled) {
                    // 4. Break the ship wheel block
                    Location wheelLoc = wheelData.getBlockLocation();
                    manager.breakWheelBlock(wheelLoc);

                    // 5. Spawn explosions at saved locations
                    spawnDestructionExplosions(world, explosionLocations);
                    return;
                }
            }
            // Fallback if disassembly failed - use old behavior below
        }

        // Prefab ships (or fallback for custom ships if disassembly failed).
        // Cargo is dropped by the engine from Mechanism.destroy() (below), so no inventory drop here.

        // Drop appropriate item based on ship type
        if (world != null) {
            if ("custom".equals(shipType)) {
                // Custom ships drop the ship wheel item (fallback)
                if (plugin instanceof BlockShipsPlugin bsp && bsp.getDisplayShip() != null) {
                    ItemStack shipWheel = bsp.getDisplayShip().createShipWheelItem();
                    world.dropItemNaturally(dropLocation, shipWheel);
                }
            } else {
                // Prefab ships drop the ship kit with customization
                ItemStack shipKit = DisplayShip.createShipKit(customization.getCustomBanner(), customization.getWoodType(), shipType);
                world.dropItemNaturally(dropLocation, shipKit);
            }
        }

        // Clean up all entities and persistence storage
        if (plugin instanceof BlockShipsPlugin bsp && bsp.getDisplayShip() != null) {
            destroyWithCleanup(bsp.getDisplayShip().getShipWorldData());
        } else {
            destroy();
        }
    }

    /**
     * Spawns destruction explosions at the given locations.
     * Used when a custom ship is destroyed - causes entity damage but no block damage.
     */
    private void spawnDestructionExplosions(World world, List<Location> locations) {
        for (Location loc : locations) {
            // Small explosion: does entity damage, no block damage
            world.createExplosion(loc, 1.0f, false, false);
        }
    }

    /**
     * Notifies all players riding the ship that it has been destroyed.
     * Called before ship destruction when health reaches 0.
     */
    private void notifyRidersOfDestruction() {
        for (Shulker seat : seatShulkers) {
            if (seat != null && seat.isValid()) {
                for (Entity passenger : seat.getPassengers()) {
                    if (passenger instanceof Player player) {
                        player.sendMessage("§c§lYour ship has been destroyed!");
                    }
                }
            }
        }
    }

    /**
     * Suspends ship for chunk unload - cancels tasks but keeps entities.
     * Entity references become stale but will be recovered on chunk load.
     */
    public void suspendForChunkUnload() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (idleCheckTask != null) {
            idleCheckTask.cancel();
            idleCheckTask = null;
        }
        // Clear references (they'll be stale anyway after chunk unloads)
        seatShulkers.clear();
        // vehicle reference is kept but may become stale
        taskStopped = true;
    }

    public void destroy() {
        // Track W (W2): clear the originating wheel's assembled-link so no wheel is left "confused" (believing a
        // now-dead ship is still assembled → Assemble refuses). This is the central self-heal covering EVERY
        // destroy() caller (out-of-band vehicle death, the destroyAndDropItem fallback, etc.). Equality-guarded:
        // never clears a wheel that has since re-linked to a different ship, and no-ops both the assembly-rollback
        // (link not set yet) and prefab ships (no wheel points at this id). Callers own the subsequent saveAll().
        anon.def9a2a4.blockships.customships.ShipWheelData wd = wheelData;
        if (wd == null && plugin instanceof anon.def9a2a4.blockships.BlockShipsPlugin bsp0) {
            wd = bsp0.getShipWheelManager().getWheelByShipUUID(id);
        }
        if (wd != null && id.equals(wd.getAssembledShipUUID())) {
            wd.setAssembledShipUUID(null);
        }
        if (task != null) task.cancel();
        if (idleCheckTask != null) idleCheckTask.cancel();
        if (mechanism != null) {
            // Delegated safe-dismount: mechanism.destroy() below removes the seat shulkers via Entity.remove(),
            // which ejects any seated player IN PLACE without firing VehicleExitEvent — so the safe-position
            // teleport + fall-distance reset would be skipped. Mirror the native removePassenger→VehicleExitEvent
            // path (see the colliders loop below, which is empty for a delegated ship) over seatShulkers, and do
            // it BEFORE tearing the mechanism down while the seats still exist and the ship is still registered.
            for (Shulker seat : seatShulkers) {
                if (seat != null && seat.isValid()) {
                    for (Entity passenger : seat.getPassengers()) {
                        if (passenger instanceof Player p) seat.removePassenger(p);
                    }
                }
            }
            // Delegated (M1): defCoreLib owns the parent/displays/colliders — tear them down via the Mechanism
            // (removes entities WITHOUT restoring blocks). Idempotent if disassemble() already ran. The native
            // lists below are empty for a delegated ship; the external vehicle is still removed at the end.
            try { mechanism.destroy(); } catch (Throwable ignored) {}
        }
        // Remove root vehicle (defCoreLib owns the borrowed vehicle's displays/colliders; mechanism.destroy above
        // tore them down, but the vehicle ArmorStand itself is BlockShips-owned and removed here).
        if (vehicle != null && vehicle.isValid()) vehicle.remove();
        ShipRegistry.unregister(this);
    }

    /**
     * Destroys the ship and cleans up persistence storage (metadata file and chunk index).
     * Use this instead of destroy() when the ship should be permanently removed.
     */
    public void destroyWithCleanup(ShipWorldData shipWorldData) {
        // Get world before destroy() removes the vehicle - handle null/invalid vehicle
        World world = (vehicle != null && vehicle.isValid()) ? vehicle.getLocation().getWorld() : null;
        destroy();  // Remove entities and unregister
        if (world != null && shipWorldData != null) {
            shipWorldData.removeShip(world, this.id);
            shipWorldData.saveAllChunkIndices();
        }
    }

    /**
     * Counts the total number of entities that make up this ship.
     * Calculated from model structure to ensure consistent counts regardless of recovery state.
     * Used for persistence to validate recovery completeness.
     *
     * Note: leadableShulker is NOT counted separately - it's one of the collision shulkers
     * that also has a leadable tag. The shulker is already counted in the colliders.
     */
    public int countEntities() {
        int count = 2;  // vehicle + parent (always present)
        count += model.parts.size();  // block displays
        count += model.items.size();  // item displays
        // Each part with collision gets carrier + shulker (leadable shulkers are included here)
        count += (int) model.parts.stream().filter(p -> p.collision != null).count() * 2;
        count += model.seats.size();  // seat shulkers
        return count;
    }

    // ===== Custom Ship Methods =====

    /**
     * Aligns the ship to the block grid by snapping position and rotation.
     * Position is rounded to the nearest block coordinates.
     * Rotation is snapped to the nearest 90-degree increment.
     */
    public void alignToGrid() {
        physics.alignToGrid();
        // Delegated ships: spawnYaw MUST stay pinned to the as-built assembly yaw. The vehicle yaw is
        // frozen at 0 and all rotation is (currentYaw - spawnYaw) against construction-time geometry, so
        // re-baselining spawnYaw would make the next tick's repositionDriven(currentYaw - spawnYaw) evaluate
        // to a stale delta and snap the whole ship back toward as-built. physics.alignToGrid() already
        // repositioned the mechanism displays/colliders via repositionDriven, so nothing else is needed.
    }

    // ========== Cannon System ==========

    /** Default cannon cooldown in milliseconds */
    private static final long DEFAULT_CANNON_COOLDOWN_MS = 1000;

    /**
     * Fires a single cannon, consuming an item from the dispenser inventory.
     * @param cannon The cannon to fire
     * @return true if cannon fired successfully
     */
    public boolean fireCannon(ShipModel.CannonInfo cannon) {
        // Check cooldown
        long now = System.currentTimeMillis();
        long cooldownMs = plugin.getConfig().getLong("cannons.cooldown-ms", DEFAULT_CANNON_COOLDOWN_MS);
        if (now - cannon.lastFireTime < cooldownMs) {
            return false;
        }

        // Get dispenser inventory (the ammo). The dispenser's captured inventory rides on the mechanism, keyed
        // by block index (== dispenserBlockIndex via the parity invariant). Consuming from it round-trips back
        // to the placed dispenser on disassemble.
        Inventory inv = mechanism.getStorage(cannon.dispenserBlockIndex);
        if (inv == null || inv.isEmpty()) {
            return false;
        }

        // Find first non-null item to fire
        ItemStack projectile = null;
        int projectileSlot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                projectile = item;
                projectileSlot = i;
                break;
            }
        }

        if (projectile == null) return false;

        // Transform fire direction by ship's current rotation
        Vector3f worldDirection = transformLocalDirectionToWorld(cannon.localFacing);

        // Transform spawn position to world coordinates
        Vector3f worldPos = transformLocalPositionToWorld(cannon.localPosition);
        Location spawnLoc = new Location(vehicle.getWorld(),
            worldPos.x, worldPos.y, worldPos.z);

        // Fire projectile based on item type
        fireProjectile(spawnLoc, worldDirection, projectile);

        // Consume one item
        projectile.setAmount(projectile.getAmount() - 1);
        if (projectile.getAmount() <= 0) {
            inv.setItem(projectileSlot, null);
        }

        // Spawn smoke particles at dispenser face
        spawnCannonEffects(spawnLoc, worldDirection);

        // Update cooldown
        cannon.lastFireTime = now;

        return true;
    }

    /**
     * Fires all cannons associated with an obsidian block.
     * @param obsidianBlockIndex The block index of the clicked obsidian
     * @return Number of cannons that fired
     */
    public int fireCannonsByObsidian(int obsidianBlockIndex) {
        int fired = 0;
        for (ShipModel.CannonInfo cannon : model.cannons) {
            if (cannon.obsidianBlockIndex == obsidianBlockIndex) {
                if (fireCannon(cannon)) {
                    fired++;
                }
            }
        }
        return fired;
    }

    /**
     * Fires all cannons on the ship.
     * @return Number of cannons that fired
     */
    public int fireAllCannons() {
        int fired = 0;
        for (ShipModel.CannonInfo cannon : model.cannons) {
            if (fireCannon(cannon)) {
                fired++;
            }
        }
        return fired;
    }

    /**
     * Transforms a local direction (BlockFace) to world direction accounting for ship rotation.
     */
    private Vector3f transformLocalDirectionToWorld(BlockFace localFace) {
        Vector3f localDir = new Vector3f(
            localFace.getModX(),
            localFace.getModY(),
            localFace.getModZ()
        );

        Matrix4f R_full = buildRotationMatrix();
        R_full.transformDirection(localDir);

        return localDir.normalize();
    }

    /**
     * Transforms a local position to world coordinates.
     */
    private Vector3f transformLocalPositionToWorld(Vector3f localPos) {
        Matrix4f R_full = buildRotationMatrix();
        Matrix4f T_collision = new Matrix4f().translation(model.collisionOffset);

        Matrix4f world = new Matrix4f(R_full).mul(T_collision);
        Vector3f worldPos = new Vector3f(localPos);
        world.transformPosition(worldPos);

        Location vehicleLoc = vehicle.getLocation();
        worldPos.add((float) vehicleLoc.getX(), (float) vehicleLoc.getY(), (float) vehicleLoc.getZ());

        return worldPos;
    }

    /**
     * Fires a projectile based on item type (mimics dispenser behavior).
     */
    private void fireProjectile(Location spawnLoc, Vector3f direction, ItemStack item) {
        World world = spawnLoc.getWorld();
        org.bukkit.util.Vector velocity = new org.bukkit.util.Vector(
            direction.x, direction.y, direction.z
        ).multiply(2.5);  // Projectile speed

        Material type = item.getType();

        switch (type) {
            case ARROW:
                world.spawn(spawnLoc, Arrow.class, arrow -> {
                    arrow.setVelocity(velocity);
                });
                break;
            case SPECTRAL_ARROW:
                world.spawn(spawnLoc, SpectralArrow.class, spectral -> {
                    spectral.setVelocity(velocity);
                });
                break;
            case TIPPED_ARROW:
                world.spawn(spawnLoc, Arrow.class, tipped -> {
                    if (item.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta potionMeta) {
                        tipped.setBasePotionType(potionMeta.getBasePotionType());
                    }
                    tipped.setVelocity(velocity);
                });
                break;
            case WIND_CHARGE:
                world.spawn(spawnLoc, org.bukkit.entity.WindCharge.class, windCharge -> {
                    windCharge.setVelocity(velocity);
                });
                break;
            case FIRE_CHARGE:
                SmallFireball fireball = world.spawn(spawnLoc, SmallFireball.class);
                fireball.setDirection(velocity.normalize());
                break;
            case FIREWORK_ROCKET:
                Firework fw = world.spawn(spawnLoc, Firework.class);
                fw.setVelocity(velocity);
                break;
            case SNOWBALL:
                Snowball snowball = world.spawn(spawnLoc, Snowball.class);
                snowball.setVelocity(velocity);
                break;
            case EGG:
                Egg egg = world.spawn(spawnLoc, Egg.class);
                egg.setVelocity(velocity);
                break;
            case SPLASH_POTION:
                world.spawn(spawnLoc, ThrownPotion.class, potion -> {
                    potion.setItem(item.clone());
                    potion.setVelocity(velocity);
                });
                break;
            case LINGERING_POTION:
                world.spawn(spawnLoc, LingeringPotion.class, potion -> {
                    potion.setItem(item.clone());
                    potion.setVelocity(velocity);
                });
                break;
            case TNT:
                if (TNT_ENABLED) {
                    world.spawn(spawnLoc, TNTPrimed.class, tnt -> {
                        tnt.setFuseTicks(TNT_FUSE_TICKS);
                        tnt.setVelocity(velocity);
                    });
                } else {
                    Item droppedTnt = world.dropItem(spawnLoc, new ItemStack(type, 1));
                    droppedTnt.setVelocity(velocity.multiply(0.5));
                }
                break;
            default:
                // Drop as item for unsupported types
                Item dropped = world.dropItem(spawnLoc, new ItemStack(type, 1));
                dropped.setVelocity(velocity.multiply(0.5));
                break;
        }
    }

    /**
     * Spawns smoke particles at cannon location when fired.
     */
    private void spawnCannonEffects(Location loc, Vector3f direction) {
        World world = loc.getWorld();

        // Smoke particles on dispenser face
        world.spawnParticle(Particle.SMOKE, loc, 10, 0.1, 0.1, 0.1, 0.05);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 3, 0.05, 0.05, 0.05, 0.01);

        // Sound effect
        float cannonVolume = (float) plugin.getConfig().getDouble("sounds.cannon-volume", 0.35);
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f * cannonVolume, 1.5f);
    }
}
