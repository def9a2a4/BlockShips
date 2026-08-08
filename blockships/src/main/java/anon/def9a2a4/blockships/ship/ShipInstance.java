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
    // Delegated engine (M1): for CUSTOM ships, defCoreLib owns the displays/colliders/mounting and this
    // holds the live Mechanism. null for prefab + legacy-recovery ships, which keep the native entity engine
    // below. When non-null, the native vehicle/parent/display/collider spawn + mount is skipped.
    public anon.def9a2a4.corelib.Mechanism mechanism;
    private Location cachedVehicleLoc;  // Cached per-tick to avoid redundant getLocation() clones
    public final int driverSeatIndex;  // Index of driver seat (always 0)
    public final UUID id;  // Ship UUID - generated on spawn or restored from state
    public final ShipCustomization customization;  // Ship customization data (banner, wood type, colors, textures)

    private BlockDisplay parent;
    private final List<DisplayInstance> displays = new ArrayList<>();
    public final List<CollisionBox> colliders = new ArrayList<>();
    public final Map<Integer, Inventory> storages = new HashMap<>();  // Block index -> inventory
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

    // Incremental recovery tracking
    private int expectedEntityCount = 0;
    private boolean recoveryComplete = true;  // true for newly spawned ships

    // Temporary storage for incremental recovery (carrier/shulker pairing)
    private final Map<Integer, Entity> pendingCarriers = new HashMap<>();
    private final Map<Integer, Shulker> pendingShulkers = new HashMap<>();

    // Fast lookup for recovered display indices (O(1) vs O(n) iteration with tag parsing)
    private final Set<Integer> recoveredDisplayIndices = new HashSet<>();

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

    /**
     * Private constructor for creating ShipInstance without spawning entities.
     * Used by fromState() factory method for chunk load recovery.
     */
    private ShipInstance(JavaPlugin plugin, String shipType, ShipModel model, ShipCustomization customization, UUID existingId) {
        this.plugin = plugin;
        this.shipType = shipType;
        this.model = model;
        this.customization = customization != null ? customization : ShipCustomization.empty();
        this.config = ShipConfig.load(plugin, shipType);
        this.driverSeatIndex = 0;
        this.id = existingId;

        // Determine if this is an airship
        String typeValue = plugin.getConfig().getString("ships." + shipType + ".type", "ship");
        this.isAirship = "airship".equalsIgnoreCase(typeValue) ||
                         ("custom".equals(shipType) && model.getDensity() < config.airDensity);

        // Initialize seatShulkers list with nulls
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

        // Entity references will be recovered via recoverEntities()
        // vehicle, parent, displays, colliders are null/empty
    }

    /**
     * Creates a ShipInstance from saved state without spawning entities.
     * Entity references must be recovered via recoverEntities() after construction.
     *
     * @param plugin The plugin instance
     * @param state The saved ship state
     * @param model The ship model
     * @return A new ShipInstance ready for entity recovery, or null on error
     */
    public static ShipInstance fromState(JavaPlugin plugin, ShipPersistence.ShipState state, ShipModel model) {
        ShipCustomization customization = buildCustomizationFromState(plugin, state);

        ShipInstance instance = new ShipInstance(plugin, state.shipType, model, customization, state.id);
        instance.metadataYaw = state.yaw;

        // For custom ships, restore the source model for disassembly
        if ("custom".equals(state.shipType)) {
            instance.sourceModel = model;
        }

        restoreInventoriesFromState(plugin, instance, state, model);
        return instance;
    }

    /** Rebuild the ship {@link ShipCustomization} (banner / wood / balloon) from a persisted state. Shared by
     *  the native {@link #fromState} and the delegated {@link #fromRecoveredMechanism} recovery paths. */
    private static ShipCustomization buildCustomizationFromState(JavaPlugin plugin, ShipPersistence.ShipState state) {
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

    /** Restore persisted block-storage inventories into {@code instance.storages}. Shared by the native and
     *  delegated recovery paths. (Storage delegation to {@code Mechanism.getStorage} is a later Track-C item;
     *  recovery keeps the battle-tested sidecar restore to avoid regression.) */
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
                        Inventory storage = createStorageInventory(part.storage,
                            part.rawYaml.get("custom_name") instanceof String cns ? cns : null);
                        // Cap to the inventory size: `items` is sized from the persisted
                        // token count, which can exceed the (possibly changed) storage size.
                        storage.setContents(java.util.Arrays.copyOf(items,
                            java.lang.Math.min(items.length, storage.getSize())));
                        instance.storages.put(blockIdx, storage);
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
     * ship-domain logic (physics, steering, health, UI) works again. Sibling of {@link #fromState} (which is
     * native-only and leaves {@code mechanism} null).
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

    public ShipInstance(JavaPlugin plugin, String shipType, ShipModel model, Location spawnLocation, ShipCustomization customization) {
        this(plugin, shipType, model, spawnLocation, customization, null, null);
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
        // Identity unification (M1): a delegated custom ship shares the Mechanism's UUID so ShipRegistry and
        // the ship sidecar both key on it (no ship-UUID↔mechId map). Native ships keep a fresh random id.
        this.id = mechanism != null ? mechanism.id() : UUID.randomUUID();
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
        } else {
        this.vehicle = w.spawn(base, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setGravity(false);
            as.setSilent(true);
            as.setPersistent(true);
            as.customName(net.kyori.adventure.text.Component.empty());
            as.setCustomNameVisible(false);
            as.addScoreboardTag(ShipTags.shipRootTag(id));

            // Root vehicle has health system for ship damage
            org.bukkit.attribute.AttributeInstance maxHealthAttr = as.getAttribute(anon.def9a2a4.blockships.util.AttributeCompat.getMaxHealth());
            if (maxHealthAttr != null) {
                maxHealthAttr.setBaseValue(model.maxHealth);
            }
            as.setHealth(model.maxHealth);

            // Force rotation to match spawn location (Bukkit doesn't auto-apply yaw from Location)
            as.setRotation(base.getYaw(), base.getPitch());
        });
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
        // Delegated ships (M1): the vehicle entity yaw is held at 0 (defCoreLib owns rotation via the display
        // transform matrix), so seed the ship's heading from the model's assembly yaw — NOT the vehicle yaw —
        // else physics thrust + the display rotation delta would think the ship faces 0°. Native ships keep
        // reading the frozen vehicle yaw (their display passengers inherit it for rendering).
        this.spawnYaw = (mechanism != null)
            ? ShipTags.normalizeYaw(model.assemblyYaw)
            : ShipTags.normalizeYaw(vehicle.getYaw());
        this.previousYaw = this.spawnYaw;
        // P7.C: a DELEGATED PREFAB ship carries its heading in the spawn Location yaw, not in the model
        // (model.assemblyYaw==0 for prefab, unlike custom where assemblyYaw IS the heading). So its rotation
        // baseline spawnYaw stays 0 but currentYaw must start at the placement heading; rotate() then spins
        // the mechanism by (currentYaw − spawnYaw) = the heading. (Recovery re-overrides currentYaw from the
        // persisted absolute yaw.) Custom delegated + native ships keep currentYaw == spawnYaw.
        boolean delegatedPrefab = mechanism != null && !"custom".equals(shipType);
        this.physics.currentYaw = delegatedPrefab
            ? ShipTags.normalizeYaw(spawnLocation.getYaw())
            : this.spawnYaw;
        if (delegatedPrefab) this.previousYaw = this.physics.currentYaw;

        // Initialize chunk tracking for persistence
        this.currentChunkX = vehicle.getLocation().getBlockX() >> 4;
        this.currentChunkZ = vehicle.getLocation().getBlockZ() >> 4;

        // ── Native entity engine (prefab + legacy) ────────────────────────────────────────────────
        // For a DELEGATED custom ship (mechanism != null) defCoreLib already spawned the parent, block/item
        // displays, and collider shulkers on the vehicle and mounted the chain — skip all of it here.
        if (mechanism == null) {
        // Spawn displays above the vehicle so they don't flash below ground before
        // being mounted as passengers (1 tick later). Manually tuned offset.
        Location displaySpawnLoc = base.clone().add(0, 2.5, 0);

        // Spawn invisible parent display for rotation control
        parent = w.spawn(displaySpawnLoc, BlockDisplay.class, d -> {
            d.setBlock(Bukkit.createBlockData(Material.AIR));
            d.setInterpolationDuration(config.displayInterpolationDuration);
            d.setTeleportDuration(0);  // Position comes from passenger chain, not teleports
            d.setViewRange(64f);
            d.setPersistent(true);
            d.setGravity(false);
            d.addScoreboardTag(ShipTags.shipTag(this.id));
            d.addScoreboardTag(ShipTags.PARENT_TAG);
        });

        // Pre-compute dynlight tags for shulkers: build position index, check occlusion, resolve neighbor fallback
        Map<Integer, Integer> shulkerLightTags = new HashMap<>();
        if (SHIP_LIGHTS_ENABLED) {
            Map<String, Integer> posMap = new HashMap<>();
            Map<Integer, Integer> lightEmission = new HashMap<>();
            for (int i = 0; i < model.parts.size(); i++) {
                ShipModel.ModelPart mp = model.parts.get(i);
                Matrix4f m = mp.local;
                int x = java.lang.Math.round(m.m30()), y = java.lang.Math.round(m.m31()), z = java.lang.Math.round(m.m32());
                posMap.put(x + "," + y + "," + z, i);
                if (mp.block != null && mp.block.getLightEmission() > 0) {
                    lightEmission.put(i, mp.block.getLightEmission());
                }
            }

            // Check occlusion: skip blocks fully surrounded by opaque blocks
            int[][] allNeighbors = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            Set<Integer> occluded = new HashSet<>();
            for (var entry : lightEmission.entrySet()) {
                int idx = entry.getKey();
                Matrix4f m = model.parts.get(idx).local;
                int x = java.lang.Math.round(m.m30()), y = java.lang.Math.round(m.m31()), z = java.lang.Math.round(m.m32());
                boolean allOpaque = true;
                for (int[] d : allNeighbors) {
                    Integer ni = posMap.get((x + d[0]) + "," + (y + d[1]) + "," + (z + d[2]));
                    if (ni == null || !model.parts.get(ni).block.getMaterial().isOccluding()) {
                        allOpaque = false;
                        break;
                    }
                }
                if (allOpaque) occluded.add(idx);
            }

            // Assign light to shulkers: own collider if available, otherwise delegate to neighbor
            int[][] neighborPriority = {{0,-1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0}};
            for (var entry : lightEmission.entrySet()) {
                int idx = entry.getKey();
                int emission = entry.getValue();
                if (occluded.contains(idx)) continue;

                ShipModel.ModelPart mp = model.parts.get(idx);
                if (mp.collision.enable) {
                    shulkerLightTags.merge(idx, emission, java.lang.Math::max);
                } else {
                    // No collider (torch, etc.) - find neighbor with collider
                    Matrix4f m = mp.local;
                    int x = java.lang.Math.round(m.m30()), y = java.lang.Math.round(m.m31()), z = java.lang.Math.round(m.m32());
                    for (int[] d : neighborPriority) {
                        Integer ni = posMap.get((x + d[0]) + "," + (y + d[1]) + "," + (z + d[2]));
                        if (ni != null && model.parts.get(ni).collision.enable) {
                            shulkerLightTags.merge(ni, emission, java.lang.Math::max);
                            break;
                        }
                    }
                }
            }
        }

        // Pre-compute rotation and collision offset for spawning carriers at final positions
        Matrix4f spawnR = buildRotationMatrix();
        Matrix4f spawnT = new Matrix4f().translation(model.collisionOffset);
        if ("custom".equals(shipType)) {
            spawnT.translate(config.customCollisionOffset);
        }
        Matrix4f spawnWorkWorld = new Matrix4f();
        Vector3f spawnWorkOffset = new Vector3f();
        Vector3f spawnWorkPerBlock = new Vector3f();
        Vector3f spawnWorkPos = new Vector3f();
        Location vehicleLoc = vehicle.getLocation();

        // Pre-compute display world transform for spawn-time visual correctness
        // Uses spawnR (full rotation including vehicle yaw + initial rotation)
        // because at spawn time, displays are free-floating entities (not yet passengers)
        Matrix4f spawnDisplayT = new Matrix4f().translation(model.positionOffset);
        if ("custom".equals(shipType)) {
            spawnDisplayT.translate(config.customDisplayOffset);
        }
        Matrix4f spawnDisplayWorld = new Matrix4f(spawnR).mul(spawnDisplayT);

        // Spawn each block display part as a child
        for (int blockIndex = 0; blockIndex < model.parts.size(); blockIndex++) {
            ShipModel.ModelPart p = model.parts.get(blockIndex);
            final int currentBlockIndex = blockIndex;  // For use in lambda

            // Check if this part needs special rendering (head/skull or banner).
            // Heads (player AND mob) render as ItemDisplay + HEAD transform: BlockDisplay
            // cannot render a skull's rotation (skulls draw via a block-entity renderer).
            // skull_rotation/skull_facing are captured for every head; only player heads
            // additionally carry skull_profile. Their presence marks "this part is a head".
            boolean hasSkullProfile = p.rawYaml.containsKey("skull_profile");
            boolean hasHead = hasSkullProfile
                              || p.rawYaml.containsKey("skull_rotation")
                              || p.rawYaml.containsKey("skull_facing");
            // Detect banners by rotation/facing keys (works for both plain and patterned banners)
            boolean hasBannerPatterns = p.rawYaml.containsKey("banner_patterns") ||
                                        p.rawYaml.containsKey("banner_rotation") ||
                                        p.rawYaml.containsKey("banner_facing");

            org.bukkit.entity.Display child;
            Matrix4f displayTransform;  // Transform used for DisplayInstance (may include rotation)

            if (hasHead || hasBannerPatterns) {
                // Spawn as ItemDisplay to preserve textures
                child = w.spawn(displaySpawnLoc, org.bukkit.entity.ItemDisplay.class, id -> {
                    // Create ItemStack for the display
                    ItemStack displayItem;

                    if (hasHead) {
                        // Create head/skull item. Wall variants have no item form, so map
                        // them to their floor item (mirrors the banner _WALL_ remap below).
                        Material headMaterial = Material.PLAYER_HEAD;
                        String headBlockName = String.valueOf(p.rawYaml.get("block"));
                        if (headBlockName.contains("_WALL_HEAD")) {
                            headBlockName = headBlockName.replace("_WALL_HEAD", "_HEAD");
                        } else if (headBlockName.contains("_WALL_SKULL")) {
                            headBlockName = headBlockName.replace("_WALL_SKULL", "_SKULL");
                        }
                        try {
                            headMaterial = Material.valueOf(headBlockName);
                        } catch (IllegalArgumentException ex) {
                            // Unknown material - fall back to a player head so assembly never aborts
                            plugin.getLogger().warning("Unknown head material '" + headBlockName
                                + "' for block " + currentBlockIndex + ", using PLAYER_HEAD. "
                                + "Please report at " + BlockShipsPlugin.ISSUES_URL);
                        }
                        displayItem = new ItemStack(headMaterial);

                        // Apply a stored skin profile (player heads only; mob heads have none)
                        String profileData = (String) p.rawYaml.get("skull_profile");
                        if (profileData != null
                                && displayItem.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                            com.destroystokyo.paper.profile.PlayerProfile profile = deserializeSkullProfile(profileData);
                            if (profile != null) {
                                skullMeta.setPlayerProfile(profile);
                                displayItem.setItemMeta(skullMeta);
                            }
                        }
                    } else {
                        // Create banner item with patterns
                        String blockName = String.valueOf(p.rawYaml.get("block"));
                        // Wall banners don't have item forms - convert to standing banner
                        if (blockName.contains("_WALL_BANNER")) {
                            blockName = blockName.replace("_WALL_BANNER", "_BANNER");
                        }
                        Material bannerMaterial = Material.valueOf(blockName);
                        displayItem = new ItemStack(bannerMaterial);
                        ItemMeta meta = displayItem.getItemMeta();

                        if (meta instanceof org.bukkit.inventory.meta.BannerMeta) {
                            org.bukkit.inventory.meta.BannerMeta bannerMeta = (org.bukkit.inventory.meta.BannerMeta) meta;

                            @SuppressWarnings("unchecked")
                            java.util.List<Map<String, Object>> patternList =
                                (java.util.List<Map<String, Object>>) p.rawYaml.get("banner_patterns");

                            if (patternList != null) {
                                for (Map<String, Object> patternMap : patternList) {
                                    String colorName = (String) patternMap.get("color");
                                    String patternName = (String) patternMap.get("pattern");

                                    org.bukkit.DyeColor color = org.bukkit.DyeColor.valueOf(colorName);
                                    org.bukkit.block.banner.PatternType patternType =
                                        Registry.BANNER_PATTERN.get(NamespacedKey.minecraft(patternName.toLowerCase()));

                                    if (patternType != null) {
                                        bannerMeta.addPattern(new org.bukkit.block.banner.Pattern(color, patternType));
                                    }
                                }
                            }

                            displayItem.setItemMeta(bannerMeta);
                        }
                    }

                    id.setItemStack(displayItem);
                    id.setViewRange(64f);
                    id.setInterpolationDuration(config.displayInterpolationDuration);
                    id.setTeleportDuration(0);  // Position comes from passenger chain, not teleports
                    id.setShadowRadius(0f);
                    id.setShadowStrength(0f);
                    id.setGlowing(false);
                    id.setGravity(false);
                    id.setPersistent(true);
                    id.addScoreboardTag(ShipTags.shipTag(this.id));
                    id.addScoreboardTag(ShipTags.displayIndexTag(currentBlockIndex));

                    // Apply transformation matrix - different handling for skulls vs banners
                    Matrix4f finalTransform = new Matrix4f(p.local);
                    if ("custom".equals(shipType)) {
                        finalTransform.translate(config.customDisplayOffset);
                    }

                    if (hasHead) {
                        // Heads (player + mob): use HEAD transform mode (displays as worn on head)
                        id.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.HEAD);

                        Matrix4f skullTransform = new Matrix4f(finalTransform);
                        applySkullTransform(skullTransform, p.rawYaml);

                        id.setTransformationMatrix(new Matrix4f(spawnDisplayWorld).mul(skullTransform));
                    } else {
                        // Banners: use FIXED transform mode with custom scaling
                        // FIXED mode displays item at actual size, so we need to scale and position it
                        id.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);

                        Matrix4f bannerTransform = calculateBannerTransform(finalTransform, p.rawYaml);
                        id.setTransformationMatrix(new Matrix4f(spawnDisplayWorld).mul(bannerTransform));
                    }
                });
                // ItemDisplay: apply same rotation transforms as used above for tick() updates
                // Note: displayOffset is applied in tick() via T_display, not here
                displayTransform = new Matrix4f(p.local);
                if (hasHead) {
                    // Apply skull rotation to displayTransform (must match spawn transform above)
                    applySkullTransform(displayTransform, p.rawYaml);
                } else if (hasBannerPatterns) {
                    displayTransform = calculateBannerTransform(new Matrix4f(p.local), p.rawYaml);
                }
            } else {
                // Compute display transform (may include rotation for blocks like chests)
                // Note: displayOffset is applied in tick() via T_display, not here
                displayTransform = new Matrix4f(p.local);

                // Apply display rotation for blocks that need it (e.g., chests ignore BlockData facing)
                if ("custom".equals(shipType) && p.rawYaml.containsKey("display_yaw")) {
                    float displayYaw = ((Number) p.rawYaml.get("display_yaw")).floatValue();
                    // Rotate around block center (not corner)
                    displayTransform.translate(0.5f, 0f, 0.5f);
                    displayTransform.rotateY((float) java.lang.Math.toRadians(-displayYaw));
                    displayTransform.translate(-0.5f, 0f, -0.5f);
                }
                final Matrix4f blockDisplayTransform = displayTransform;

                // Spawn as BlockDisplay (normal blocks)
                child = w.spawn(displaySpawnLoc, BlockDisplay.class, bd -> {
                BlockData blockData;

                // For custom ships, use the saved blockdata string to preserve ALL properties
                // (stairs half/facing, slabs type, chest facing, doors hinge/half, etc.)
                if ("custom".equals(shipType) && p.rawYaml.containsKey("blockdata")) {
                    String blockDataString = (String) p.rawYaml.get("blockdata");
                    blockData = Bukkit.createBlockData(blockDataString);
                } else {
                    // Prefab ships: use wood type replacement logic
                    String blockName = String.valueOf(p.rawYaml.get("block"));
                    String modifiedBlockName = blockName;  // Default: use original block
                    if (customization.getWoodType() != null) {
                        modifiedBlockName = WoodTypeUtil.replaceWoodType(blockName, customization.getWoodType());
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> properties = (Map<String, Object>) p.rawYaml.get("properties");

                    if (properties != null && !properties.isEmpty()) {
                        // Build block state string: minecraft:block_name[prop1=val1,prop2=val2]
                        StringBuilder stateString = new StringBuilder("minecraft:");
                        stateString.append(modifiedBlockName.toLowerCase());
                        stateString.append("[");
                        boolean first = true;
                        for (Map.Entry<String, Object> entry : properties.entrySet()) {
                            if (!first) stateString.append(",");
                            stateString.append(entry.getKey()).append("=").append(entry.getValue());
                            first = false;
                        }
                        stateString.append("]");
                        blockData = Bukkit.createBlockData(stateString.toString());
                    } else {
                        blockData = Bukkit.createBlockData(Material.valueOf(modifiedBlockName));
                    }
                }

                bd.setBlock(blockData);
                bd.setViewRange(64f);
                bd.setInterpolationDuration(config.displayInterpolationDuration);
                bd.setTeleportDuration(0);  // Position comes from passenger chain, not teleports
                bd.setShadowRadius(0f);
                bd.setShadowStrength(0f);
                bd.setGlowing(false);
                bd.setGravity(false);
                bd.setPersistent(true);
                bd.addScoreboardTag(ShipTags.shipTag(this.id));
                bd.addScoreboardTag(ShipTags.displayIndexTag(currentBlockIndex));

                // TODO: Sign text cannot be displayed on BlockDisplay entities (Minecraft limitation).
                // A workaround would be to spawn TextDisplay entities near signs to show the text.

                bd.setTransformationMatrix(new Matrix4f(spawnDisplayWorld).mul(blockDisplayTransform));
            });
            }
            // Use displayTransform for tick updates (includes rotation for blocks that need it)
            displays.add(new DisplayInstance(child, new Matrix4f(displayTransform)));

            // Create inventory for this block if it has storage configured
            if (p.storage != null) {
                Inventory storage = createStorageInventory(p.storage,
                    p.rawYaml.get("custom_name") instanceof String cns ? cns : null);

                // Restore saved inventory contents if available
                if (p.rawYaml.containsKey("container_items")) {
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> itemsData =
                        (java.util.List<java.util.Map<String, Object>>) p.rawYaml.get("container_items");

                    if (itemsData != null) {
                        for (java.util.Map<String, Object> itemData : itemsData) {
                            int slot = ((Number) itemData.get("slot")).intValue();
                            byte[] serialized = (byte[]) itemData.get("item");

                            if (slot >= 0 && slot < storage.getSize() && serialized != null) {
                                try {
                                    ItemStack item = ItemStack.deserializeBytes(serialized);
                                    storage.setItem(slot, item);
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Failed to restore container item at block "
                                        + currentBlockIndex + " slot " + slot + ", dropping it: " + e.getMessage()
                                        + ". Please report at " + BlockShipsPlugin.ISSUES_URL);
                                }
                            }
                        }
                    }
                }

                storages.put(currentBlockIndex, storage);
            }

            // Spawn collision shulker if this block has collision enabled
            if (p.collision.enable) {
                Shulker spawnedShulker = null;
                ArmorStand carrier = null;

                try {
                    // Compute carrier's final world position so it spawns in-place
                    // (avoids entity interpolation pushing players off the ship)
                    computeColliderWorldPos(spawnR, spawnT, p.local, p.collision.offset,
                            vehicleLoc, spawnWorkWorld, spawnWorkOffset, spawnWorkPerBlock, spawnWorkPos);
                    Location carrierSpawnLoc = new Location(w,
                            spawnWorkPos.x, spawnWorkPos.y, spawnWorkPos.z, 0, 0);

                    // Use ArmorStand as carrier (smooth interpolation)
                    carrier = w.spawn(carrierSpawnLoc, ArmorStand.class, as -> {
                        try {
                            as.setInvisible(true);
                            as.setInvulnerable(true);
                            as.setGravity(false);
                            as.setSilent(true);
                            as.setPersistent(true);
                            as.setMarker(true);
                            as.customName(net.kyori.adventure.text.Component.empty());
                            as.setCustomNameVisible(false);
                            as.addScoreboardTag(ShipTags.shipTag(this.id));
                            as.addScoreboardTag(ShipTags.CARRIER_TAG);
                            as.addScoreboardTag(ShipTags.blockIndexTag(currentBlockIndex));
                        } catch (Throwable t) {
                            plugin.getLogger().log(java.util.logging.Level.SEVERE, "ArmorStand config failed for block "
                                + currentBlockIndex + ": " + t.getMessage() + ". Please report at " + BlockShipsPlugin.ISSUES_URL, t);
                        }
                    });

                    // Spawn shulker as passenger for physical collision
                    // Apply size scaling via generic.scale attribute
                    float shulkerSize = p.collision.size;
                    final int finalBlockIndex = currentBlockIndex;
                    final ArmorStand finalCarrier = carrier;
                    Shulker shulker;
                    try {
                        shulker = w.spawn(carrierSpawnLoc, Shulker.class, s -> {
                            try {
                                s.setAI(false);
                                s.setGravity(false);
                                s.setSilent(true);
                                s.setPersistent(true);
                                s.customName(net.kyori.adventure.text.Component.empty());
                                s.setCustomNameVisible(false);
                                s.setCollidable(true);
                                s.setInvisible(true);
                                s.setGlowing(config.collisionDebugGlow);  // Glow if debug mode enabled
                                s.setPeek(0);  // Prevent shulker from peeking/moving up
                                s.setAttachedFace(org.bukkit.block.BlockFace.DOWN);  // Prevent vanilla attachment validation jitter
                                s.addScoreboardTag(ShipTags.shipTag(this.id));
                                s.addScoreboardTag(ShipTags.COLLIDER_TAG);
                                s.addScoreboardTag(ShipTags.blockIndexTag(finalBlockIndex));

                                // Add seat tag if this block is a seat and populate seatShulkers list
                                // Tag format: shipseat:{index}
                                // Parsed in: DisplayShip.handleShulkerInteraction
                                for (int seatIdx = 0; seatIdx < model.seats.size(); seatIdx++) {
                                    if (model.seats.get(seatIdx).blockIndex == finalBlockIndex) {
                                        s.addScoreboardTag(ShipTags.seatTag(seatIdx));
                                        // Store reference in seatShulkers list for fast lookup
                                        seatShulkers.set(seatIdx, s);
                                        // Set health attributes for HUD display when riding
                                        // If maxHealth <= 40, show directly; otherwise scale to 20 hearts
                                        double shulkerMaxHealth = model.maxHealth <= 40 ? model.maxHealth : 40.0;
                                        org.bukkit.attribute.Attribute maxHealthAttr = anon.def9a2a4.blockships.util.AttributeCompat.getMaxHealth();
                                        if (maxHealthAttr != null) {
                                            org.bukkit.attribute.AttributeInstance attr = s.getAttribute(maxHealthAttr);
                                            if (attr != null) {
                                                attr.setBaseValue(shulkerMaxHealth);
                                            }
                                        }
                                        s.setHealth(shulkerMaxHealth);  // Start at full health
                                        break;
                                    }
                                }

                                // Add storage tag if this block has storage
                                if (p.storage != null) {
                                    s.addScoreboardTag(ShipTags.storageTag(finalBlockIndex));
                                }

                                // Add interaction tag if this block opens an interaction GUI
                                if (p.rawYaml.containsKey("interaction") && Boolean.TRUE.equals(p.rawYaml.get("interaction"))) {
                                    s.addScoreboardTag(ShipTags.interactTag(finalBlockIndex));
                                }

                                // Add leadable tag if this block can have leads attached (fences)
                                if (p.rawYaml.containsKey("leadable") && Boolean.TRUE.equals(p.rawYaml.get("leadable"))) {
                                    s.addScoreboardTag(ShipTags.leadableTag(finalBlockIndex));
                                }

                                // Add cannon tag if this obsidian block is part of a cannon
                                for (ShipModel.CannonInfo cannon : model.cannons) {
                                    if (cannon.obsidianBlockIndex == finalBlockIndex) {
                                        s.addScoreboardTag(ShipTags.cannonTag(finalBlockIndex));
                                        break;
                                    }
                                }

                                // Add dynlight tag if this shulker represents a light-emitting block
                                if (SHIP_LIGHTS_ENABLED) {
                                    Integer lightLevel = shulkerLightTags.get(finalBlockIndex);
                                    if (lightLevel != null) {
                                        s.addScoreboardTag(ShipTags.dynlightTag(lightLevel));
                                    }
                                }

                                // Apply scale attribute to change collision box size (added in 1.20.5)
                                try {
                                    org.bukkit.attribute.Attribute scaleAttribute = anon.def9a2a4.blockships.util.AttributeCompat.getScale();
                                    if (scaleAttribute != null) {
                                        org.bukkit.attribute.AttributeInstance scaleAttr = s.getAttribute(scaleAttribute);
                                        if (scaleAttr != null) {
                                            scaleAttr.setBaseValue(shulkerSize);
                                        }
                                    }
                                } catch (Throwable scaleError) {
                                    // Scale attribute not available on this version - shulker uses default size
                                }
                            } catch (Throwable e) {
                                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Shulker config failed for block "
                                    + finalBlockIndex + ": " + e.getMessage() + ". Please report at " + BlockShipsPlugin.ISSUES_URL, e);
                            }
                        });
                    } catch (Throwable e) {
                        // Shulker spawn failed - clean up the carrier to prevent resource leak
                        if (finalCarrier != null && finalCarrier.isValid()) {
                            finalCarrier.remove();
                        }
                        throw e;  // Re-throw to be caught by outer handler
                    }

                    // Track shulker immediately so we can clean it up if subsequent operations fail
                    spawnedShulker = shulker;

                    // Mount shulker on carrier
                    carrier.addPassenger(shulker);

                    colliders.add(new CollisionBox(carrier, shulker, new Matrix4f(p.local), p.collision, currentBlockIndex));
                } catch (Throwable e) {
                    // Clean up any spawned entities to prevent resource leak
                    if (carrier != null && carrier.isValid()) {
                        carrier.remove();
                    }
                    if (spawnedShulker != null && spawnedShulker.isValid()) {
                        spawnedShulker.remove();
                    }
                    plugin.getLogger().log(java.util.logging.Level.SEVERE, "Collider spawn failed for block "
                        + currentBlockIndex + ": " + e.getMessage() + ". Please report at " + BlockShipsPlugin.ISSUES_URL, e);
                }

                // Store leadable shulker reference for prefab ship lead attachment (single lead point)
                // Custom ships use per-fence attachment via leadable tags instead
                if (spawnedShulker != null && !"custom".equals(shipType) && p.rawYaml.containsKey("leadable") && Boolean.TRUE.equals(p.rawYaml.get("leadable"))) {
                    this.leadableShulker = spawnedShulker;
                }
            }
        }

        // Spawn each item display part as a child
        // Display indices continue after block parts for recovery purposes
        final int itemDisplayOffset = model.parts.size();
        for (int itemIndex = 0; itemIndex < model.items.size(); itemIndex++) {
            ShipModel.ItemPart p = model.items.get(itemIndex);
            final int displayIndex = itemDisplayOffset + itemIndex;
            ItemDisplay child = w.spawn(displaySpawnLoc, ItemDisplay.class, id -> {
                // Use custom banner if this is a banner display and we have custom banner data
                ItemStack displayItem = p.item.clone();
                if (customization.getCustomBanner() != null && p.item.getType().name().endsWith("_BANNER")) {
                    displayItem = customization.getCustomBanner().clone();
                }

                // Apply balloon color if this is a player head (balloon) and we have a balloon color
                if (customization.getBalloonColor() != null && customization.getTextureManager() != null &&
                    displayItem.getType() == Material.PLAYER_HEAD && displayItem.hasItemMeta()) {

                    org.bukkit.inventory.meta.ItemMeta meta = displayItem.getItemMeta();
                    if (meta instanceof org.bukkit.inventory.meta.SkullMeta) {
                        // Get balloon texture from texture manager
                        String balloonTexture = customization.getTextureManager().getTexture("BALLOONS", customization.getBalloonColor());
                        if (balloonTexture != null) {
                            ItemUtil.applyPlayerHeadTextureFromBase64(
                                (org.bukkit.inventory.meta.SkullMeta) meta,
                                balloonTexture,
                                plugin
                            );
                            displayItem.setItemMeta(meta);
                        }
                    }
                }

                id.setItemStack(displayItem);
                id.setItemDisplayTransform(p.displayMode);
                id.setViewRange(64f);
                id.setInterpolationDuration(config.displayInterpolationDuration);
                id.setTeleportDuration(0);  // Position comes from passenger chain, not teleports
                id.setShadowRadius(0f);
                id.setShadowStrength(0f);
                id.setGlowing(false);
                id.setGravity(false);
                id.setPersistent(true);
                id.addScoreboardTag(ShipTags.shipTag(this.id));
                id.addScoreboardTag(ShipTags.displayIndexTag(displayIndex));
                id.setTransformationMatrix(new Matrix4f(spawnDisplayWorld).mul(p.local));
            });
            displays.add(new DisplayInstance(child, new Matrix4f(p.local)));
        }
        } // end if (mechanism == null): native parent/display/collider spawn
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
                if (mechanism == null) {
                    // Mount children to parent
                    for (DisplayInstance di : displays) {
                        parent.addPassenger(di.entity);
                    }
                    // Mount parent to vehicle (ArmorStand)
                    vehicle.addPassenger(parent);

                    // Position collision boxes immediately before starting tick task
                    // This prevents them from appearing to "jump" when player first interacts
                    updateCollisionPositions();

                    // Teleport any players who are inside collision shulkers to the top
                    // (they were standing on real blocks that were removed during assembly)
                    pushPlayersOutOfColliders();
                }

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
     * Live world-space collider boxes for this ship, engine-agnostic (M3). For a DELEGATED custom ship
     * defCoreLib owns the colliders, so read them via the Mechanism block-index read-API; for a native
     * (prefab/legacy) ship read the shulker boxes directly. Both {@link ShipCollision} and
     * {@link ShipCollisionCoordinator} consume this so terrain + ship↔ship collision work for either engine.
     * The returned boxes are snapshots (safe to keep for the current pass).
     */
    public java.util.List<org.bukkit.util.BoundingBox> colliderBoxes() {
        java.util.List<org.bukkit.util.BoundingBox> out = new java.util.ArrayList<>();
        if (mechanism != null) {
            int n = mechanism.blockCount();
            for (int i = 0; i < n; i++) {
                org.bukkit.util.BoundingBox b = mechanism.getColliderBoxByBlock(i);
                if (b != null) out.add(b);
            }
        } else {
            for (CollisionBox cb : colliders) {
                if (cb.entity != null && cb.entity.isValid()) out.add(cb.entity.getBoundingBox());
            }
        }
        return out;
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

        // Auto-calculate using max axis distance from vehicle to farthest collider
        Location center = vehicle.getLocation();
        float maxDist = 0;
        if (mechanism != null) {
            // Delegated: measure from the mechanism collider box centers (the shulker list is defCoreLib's).
            for (org.bukkit.util.BoundingBox b : colliderBoxes()) {
                float dx = (float) java.lang.Math.abs(center.getX() - b.getCenterX());
                float dy = (float) java.lang.Math.abs(center.getY() - b.getCenterY());
                float dz = (float) java.lang.Math.abs(center.getZ() - b.getCenterZ());
                float dist = java.lang.Math.max(dx, java.lang.Math.max(dy, dz));
                if (dist > maxDist) maxDist = dist;
            }
        } else {
            for (CollisionBox cb : colliders) {
                Location cbLoc = cb.entity.getLocation();
                // Use max of axis distances (box distance) - cheaper than manhattan and works with getNearbyEntities
                float dx = (float) java.lang.Math.abs(center.getX() - cbLoc.getX());
                float dy = (float) java.lang.Math.abs(center.getY() - cbLoc.getY());
                float dz = (float) java.lang.Math.abs(center.getZ() - cbLoc.getZ());
                float dist = java.lang.Math.max(dx, java.lang.Math.max(dy, dz));
                if (dist > maxDist) maxDist = dist;
            }
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

        // Build rotation matrix including vehicle's current orientation (reuses workRotation)
        Matrix4f R_full = buildRotationMatrix();

        // Build translation matrix for collision offset (reuse workTranslation)
        workTranslation.identity().translation(model.collisionOffset);

        // Add custom ship collision offset from config
        if ("custom".equals(shipType)) {
            workTranslation.translate(config.customCollisionOffset);
        }

        // Throttle passenger integrity checks (only needed after chunk reload, not every tick)
        boolean shouldCheckPassengers = (++passengerCheckCounter % PASSENGER_CHECK_INTERVAL == 0);

        // Update collider (Interaction carrier + Shulker) positions
        for (CollisionBox cb : colliders) {
            // Calculate world position for this collider (reuses work* fields for zero-alloc)
            computeColliderWorldPos(R_full, workTranslation, cb.base, cb.config.offset,
                    currentVehicleLoc, workWorld, workOffset, workPerBlockOffset, workCurrentWorldPos);

            // Calculate velocity (change in position since last tick)
            workVelocity.set(workCurrentWorldPos).sub(cb.previousWorldPos);

            // Check if this is the first tick (previousWorldPos was initialized to 0,0,0)
            // If so, skip velocity application to avoid massive initial velocity spike
            boolean isFirstTick = cb.previousWorldPos.x == 0 && cb.previousWorldPos.y == 0 && cb.previousWorldPos.z == 0;

            // Teleport carrier to world position (including per-block offset)
            // The shulker rides as passenger and follows smoothly (ArmorStand) or choppily (Interaction)
            // Note: Carriers never rotate - only position changes (AABBs don't rotate, shulkers inherit zero rotation)
            // Reuse carrier location to avoid allocation (lazily init if world changed)
            if (workCarrierLoc == null || workCarrierLoc.getWorld() != currentVehicleLoc.getWorld()) {
                workCarrierLoc = currentVehicleLoc.clone();
            }
            workCarrierLoc.setX(currentVehicleLoc.getX() + workOffset.x + workPerBlockOffset.x);
            workCarrierLoc.setY(currentVehicleLoc.getY() + workOffset.y + workPerBlockOffset.y);
            workCarrierLoc.setZ(currentVehicleLoc.getZ() + workOffset.z + workPerBlockOffset.z);
            workCarrierLoc.setYaw(0);
            workCarrierLoc.setPitch(0);

            // Only teleport if position actually changed (avoids collision jitter when idle)
            float velocityMagnitude = workVelocity.length();

            // BEFORE teleport: capture player if this is a seat shulker
            // (teleporting carriers can sometimes dismount nested passengers on pre-1.21.9)
            Player seatedPlayer = null;
            if (TeleportCompat.needsPassengerEject() && seatShulkers.contains(cb.entity)) {
                for (Entity passenger : cb.entity.getPassengers()) {
                    if (passenger instanceof Player p) {
                        seatedPlayer = p;
                        break;
                    }
                }
            }

            // Verify passenger relationship is intact (can break on chunk reload)
            // Throttled: TeleportCompat already re-adds passengers after each teleport,
            // so this only catches chunk reload edge cases - checking every 20 ticks is sufficient
            if (shouldCheckPassengers && cb.carrier.isValid() && cb.entity.isValid() && !cb.carrier.getPassengers().contains(cb.entity)) {
                cb.carrier.addPassenger(cb.entity);
            }

            if (isFirstTick || velocityMagnitude > 0.01) {
                TeleportCompat.teleport(cb.carrier, workCarrierLoc);
                // DO NOT teleport shulker directly - it causes block snapping
                // Shulker should follow carrier as passenger
                // NOTE: Do NOT set velocity on carriers - it causes client-side prediction
                // to fight with teleport positioning, producing Y-axis jitter for players
                // standing on the shulkers. Carriers move only via teleport.
            }

            // AFTER teleport: re-mount player if they were dismounted by teleport (pre-1.21.9 only)
            if (seatedPlayer != null && !cb.entity.getPassengers().contains(seatedPlayer)) {
                // Check if seat is still occupied (intentional dismount via freeSeat() clears this)
                int seatIdx = ShipTags.extractSeatIndex(cb.entity.getScoreboardTags());
                if (seatIdx >= 0 && occupiedSeatIndices.contains(seatIdx)) {
                    final Player playerToRemount = seatedPlayer;
                    final Shulker seat = cb.entity;
                    seat.addPassenger(playerToRemount);
                }
            }

            // Store current position for next tick
            cb.previousWorldPos.set(workCurrentWorldPos);
        }

        // Note: Seats are now the shulkers themselves (no separate seat ArmorStands to update)
        // Shulker positions are already updated in the collision box loop above
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
        if (mechanism != null) {
            // Delegated (M1): defCoreLib owns the colliders, so the native `colliders` list is empty.
            // Mirror the native re-track on defCoreLib's collider carriers, located by scoreboard tag
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
            return;
        }
        for (CollisionBox cb : colliders) {
            if (!cb.carrier.isValid()) continue;
            for (Player player : tracked) {
                player.hideEntity(plugin, cb.carrier);
                player.showEntity(plugin, cb.carrier);
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

        // Set vehicle velocity from actual displacement (after physics + collision response)
        // Must match carrier velocity computation (currentPos - previousPos) so client-side
        // prediction between tracker updates keeps vehicle and carriers in sync
        boolean vehicleMovedThisTick = false;
        if (previousVehicleLocation != null) {
            workVehicleVelocity.setX(cachedVehicleLoc.getX() - previousVehicleLocation.getX())
                .setY(cachedVehicleLoc.getY() - previousVehicleLocation.getY())
                .setZ(cachedVehicleLoc.getZ() - previousVehicleLocation.getZ());
            double speedSq = workVehicleVelocity.lengthSquared();
            float yawDelta = java.lang.Math.abs(normalizeAngle(physics.currentYaw - previousYaw));
            boolean hasMovement = speedSq > POSITION_SYNC_THRESHOLD_SQ;
            boolean hasRotation = yawDelta > 0.1f;
            vehicleMovedThisTick = hasMovement || hasRotation;

            if (vehicleMovedThisTick) {
                vehicle.setVelocity(workVehicleVelocity);
                // Send position sync packet every tick to bypass 3-tick tracker interval
                // This keeps the vehicle (and its passenger display chain) in sync with carriers
                if (POSITION_SYNC_ENABLED) {
                    sendVehiclePositionSync(cachedVehicleLoc, workVehicleVelocity);
                }
            }
        }

        // Always update collision positions when physics ran - carriers must stay
        // in sync with the vehicle (and its passenger display chain) at all speeds.
        // The per-carrier velocity threshold (0.01) inside updateCollisionPositions()
        // already filters out micro-drift.
        updateCollisionPositions();

        // Get current vehicle state (reuse cached location from tick runnable)
        Location currentVehicleLoc = cachedVehicleLoc;
        float yaw = physics.currentYaw;
        float pitch = vehicle.getPitch();

        // Check if ship has moved or rotated
        boolean hasMoved = hasMovedSinceLastTick(currentVehicleLoc, yaw, pitch);

        if (!hasMoved && !firstTick) {
            // Update previous state even when idle - prevents stale previousVehicleLocation
            // from causing a velocity spike when the ship starts moving again
            previousVehicleLocation = currentVehicleLoc.clone();
            previousYaw = yaw;
            previousPitch = pitch;
            // On first idle tick after movement: refresh carrier entity tracking for all
            // nearby players. This forces the client to discard stale entity state and
            // receive fresh spawn packets with exact positions, fixing collision jitter.
            if (ticksSinceLastMovement == 0) {
                // Vehicle yaw stays frozen at spawnYaw - currentYaw is persisted
                // via per-world metadata for chunk recovery instead of vehicle NBT.
                refreshCarrierTracking();
            }
            ticksSinceLastMovement++;
            // Skip display updates but continue physics
            return;
        }

        // Mark first tick as complete
        firstTick = false;

        // Ship has moved, reset idle counter
        ticksSinceLastMovement = 0;

        // Check if ship moved to different chunk - update chunk index
        int newChunkX = currentVehicleLoc.getBlockX() >> 4;
        int newChunkZ = currentVehicleLoc.getBlockZ() >> 4;

        if (currentChunkX != newChunkX || currentChunkZ != newChunkZ) {
            // Update chunk index in per-world storage
            if (plugin instanceof BlockShipsPlugin bsp && bsp.getDisplayShip() != null) {
                ShipWorldData worldData = bsp.getDisplayShip().getShipWorldData();
                worldData.updateChunkIndex(currentVehicleLoc.getWorld(), this.id,
                    currentChunkX, currentChunkZ, newChunkX, newChunkZ);
            }
            currentChunkX = newChunkX;
            currentChunkZ = newChunkZ;
        }

        // Update previous state for next tick
        previousVehicleLocation = currentVehicleLoc.clone();
        previousYaw = yaw;
        previousPitch = pitch;

        updateDisplayTransforms();
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
    private void updateDisplayTransforms() {
        workR_initial.set(cachedR_initial);

        float deltaYaw = physics.currentYaw - spawnYaw;
        float deltaPitch = vehicle.getPitch();  // Pitch starts at 0, so no spawn offset needed

        workDisplayRot.set(
            (float) java.lang.Math.toRadians(-deltaYaw),
            (float) java.lang.Math.toRadians(-deltaPitch),
            0f
        );
        model.rotationTransform.transform(workDisplayRot, workDisplayTransformedRot);
        workDisplayDelta.identity()
            .rotateY(workDisplayTransformedRot.x)
            .rotateX(workDisplayTransformedRot.y)
            .rotateZ(workDisplayTransformedRot.z);
        workR.set(workDisplayDelta).mul(workR_initial);

        // Build translation matrix for position offset (in local space)
        workT.identity().translation(model.positionOffset);

        // Add custom ship display offset from config
        workT_display.set(workT);
        if ("custom".equals(shipType)) {
            workT_display.translate(config.customDisplayOffset);
        }

        // Update each child's transformation: R * T_display * display.base
        for (DisplayInstance di : displays) {
            workWorldMatrix.set(workR).mul(workT_display).mul(di.base);
            di.entity.setTransformationMatrix(workWorldMatrix);
        }
    }

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
     * per-tick {@code DisplayInstance.base}, and the chunk-recovery path so the
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
    private void pushPlayersOutOfColliders() {
        Location center = vehicle.getLocation();
        for (Player player : center.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(center) > PLAYER_PROXIMITY_RADIUS_SQ) continue;
            org.bukkit.util.BoundingBox playerBox = player.getBoundingBox();
            double highestTop = Double.NEGATIVE_INFINITY;
            for (CollisionBox cb : colliders) {
                org.bukkit.util.BoundingBox shulkerBox = cb.entity.getBoundingBox();
                if (playerBox.overlaps(shulkerBox) && playerBox.getMinY() < shulkerBox.getMaxY() - 0.1) {
                    double top = shulkerBox.getMaxY();
                    if (top > highestTop) highestTop = top;
                }
            }
            if (highestTop > Double.NEGATIVE_INFINITY) {
                Location safeLoc = player.getLocation().clone();
                safeLoc.setY(highestTop + config.assemblyNudgeHeight);
                player.teleport(safeLoc);
                player.setFallDistance(0);
            }
        }
    }

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
     * shulker is the mount) — tune {@code colliders.yml} if a seat block is missing. No-op for native ships.
     */
    public void adoptMechanismSeats() {
        if (mechanism == null) return;
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
     * Recovery counterpart of {@link #adoptMechanismSeats}: defCoreLib already RE-designated this mechanism's
     * seats during recovery (from the persisted shulker tags) and fired onSeatRecovered, so this must NOT call
     * {@code designateSeat} again — it only reads back the seat shulkers into {@code seatShulkers} and re-mirrors
     * ship HP. A seat whose shulker is still in a not-yet-loaded neighbour chunk resolves to {@code null} and
     * stays null (harmless — the same as a seat block with no collider); incremental recovery only finalizes
     * once the footprint is complete, so this is the rare large-ship edge.
     */
    public void adoptMechanismSeatsForRecovery() {
        if (mechanism == null) return;
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
    public void restoreStorageContents(Map<Integer, ItemStack[]> savedContents) {
        for (Map.Entry<Integer, ItemStack[]> entry : savedContents.entrySet()) {
            Inventory inv = storages.get(entry.getKey());
            if (inv != null) {
                // Cap to inventory size: an unguarded setContents throws IllegalArgumentException
                // if the saved array is larger than the storage, which would fail the whole ship load.
                ItemStack[] saved = entry.getValue();
                inv.setContents(java.util.Arrays.copyOf(saved,
                    java.lang.Math.min(saved.length, inv.getSize())));
            }
        }
    }

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

                java.util.Random random = new java.util.Random();
                for (CollisionBox collider : colliders) {
                    if (collider.entity != null && collider.entity.isValid()) {
                        if (random.nextDouble() < 0.2) { // 20% chance
                            explosionLocations.add(collider.entity.getLocation().clone());
                        }
                    }
                }

                if (config.destroyOnDeath) {
                    // Full destruction: blocks are lost, only stored items drop
                    // Drop inventory contents (chests, barrels, etc.).
                    for (Map.Entry<Integer, Inventory> storageEntry : storages.entrySet()) {
                        Inventory storage = storageEntry.getValue();
                        for (ItemStack item : storage.getContents()) {
                            if (item != null && !item.getType().isAir()) {
                                world.dropItemNaturally(dropLocation, item);
                            }
                        }
                        storage.clear();
                    }
                    // Drop TileStateInventoryHolder items (shelves, chiseled bookshelves)
                    // These aren't in the storages map - their items are serialized in rawYaml
                    for (ShipModel.ModelPart part : sourceModel.parts) {
                        if (part.storage == null && part.rawYaml.containsKey("container_items")) {
                            @SuppressWarnings("unchecked")
                            java.util.List<java.util.Map<String, Object>> itemsData =
                                (java.util.List<java.util.Map<String, Object>>) part.rawYaml.get("container_items");
                            for (java.util.Map<String, Object> itemData : itemsData) {
                                byte[] serialized = (byte[]) itemData.get("item");
                                if (serialized != null) {
                                    try {
                                        ItemStack item = ItemStack.deserializeBytes(serialized);
                                        if (item != null && !item.getType().isAir()) {
                                            world.dropItemNaturally(dropLocation, item);
                                        }
                                    } catch (Exception e) {
                                        // Skip corrupted items
                                    }
                                }
                            }
                        }
                    }
                    // Drop lead items for any entities leashed to ship shulkers.
                    // In disassemble mode, transferLeadsFromShip() preserves leads by moving them to
                    // fence posts. Here we just drop the lead items so players don't lose them silently.
                    for (CollisionBox cb : colliders) {
                        if (cb.entity == null || !cb.entity.isValid()) continue;
                        for (org.bukkit.entity.Entity nearby : cb.entity.getWorld().getNearbyEntities(
                                cb.entity.getLocation(), 12, 12, 12,
                                e -> e instanceof io.papermc.paper.entity.Leashable l
                                        && l.isLeashed()
                                        && cb.entity.equals(l.getLeashHolder()))) {
                            world.dropItemNaturally(cb.entity.getLocation(),
                                    new ItemStack(org.bukkit.Material.LEAD));
                            // Detach the leash to prevent Paper's tickLeash from dropping a second lead
                            // when the shulker holder is removed by destroy()
                            ((io.papermc.paper.entity.Leashable) nearby).setLeashHolder(null);
                        }
                    }
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

        // Prefab ships (or fallback for custom ships if disassembly failed):
        // Drop all inventory contents first
        if (world != null) {
            for (Inventory storage : storages.values()) {
                for (ItemStack item : storage.getContents()) {
                    if (item != null && !item.getType().isAir()) {
                        world.dropItemNaturally(dropLocation, item);
                    }
                }
                storage.clear();  // Clear the inventory to prevent duplication
            }
        }

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
     * Attempts to recover the vehicle reference after a chunk reload.
     * When a chunk unloads and reloads, the Java reference to the ArmorStand becomes stale.
     * This method finds the vehicle entity by its scoreboard tag and reassigns the reference.
     *
     * @param chunk The chunk to search for the vehicle entity
     * @return true if recovery was successful, false otherwise
     */
    public boolean recoverVehicle(org.bukkit.Chunk chunk) {
        String rootTag = ShipTags.shipRootTag(this.id);

        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof ArmorStand && entity.getScoreboardTags().contains(rootTag)) {
                this.vehicle = (ArmorStand) entity;
                plugin.getLogger().info("Recovered vehicle for ship " + this.id);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if this ship needs entity recovery after chunk load.
     */
    public boolean needsEntityRecovery() {
        return parent == null || !parent.isValid() || vehicle == null || !vehicle.isValid();
    }

    /**
     * Recovers all entity references from a loaded chunk.
     * Called after chunk load when ShipInstance exists but entities need recovery.
     *
     * @param chunk The chunk containing the ship entities
     * @return true if recovery was successful, false otherwise
     */
    public boolean recoverEntities(org.bukkit.Chunk chunk) {
        String shipTagPrefix = ShipTags.shipTag(this.id);  // "displayship:{uuid}"

        // First pass: collect entities from the chunk
        List<Entity> shipEntities = new ArrayList<>();
        for (Entity e : chunk.getEntities()) {
            for (String tag : e.getScoreboardTags()) {
                if (tag.startsWith(shipTagPrefix)) {
                    shipEntities.add(e);
                    break;
                }
            }
        }

        // 1. Recover vehicle (root ArmorStand with :root tag)
        vehicle = null;
        String rootTag = ShipTags.shipRootTag(this.id);
        for (Entity e : shipEntities) {
            if (e instanceof ArmorStand as && e.getScoreboardTags().contains(rootTag)) {
                vehicle = as;
                break;
            }
        }
        if (vehicle == null) {
            plugin.getLogger().warning("Ship " + id + " recovery failed: no vehicle found");
            return false;
        }

        // Second pass: search area around vehicle for any missed entities
        // This catches entities that drifted to adjacent chunks
        Location vLoc = vehicle.getLocation();
        for (Entity e : vLoc.getWorld().getNearbyEntities(vLoc, 32, 32, 32)) {
            if (shipEntities.contains(e)) continue;  // Already found
            for (String tag : e.getScoreboardTags()) {
                if (tag.startsWith(shipTagPrefix)) {
                    shipEntities.add(e);
                    plugin.getLogger().fine("Found additional entity in nearby search: " + e.getType());
                    break;
                }
            }
        }

        // 2. Recover parent BlockDisplay
        parent = null;
        for (Entity e : shipEntities) {
            if (e instanceof BlockDisplay bd && ShipTags.isParent(e.getScoreboardTags())) {
                parent = bd;
                break;
            }
        }
        if (parent == null) {
            plugin.getLogger().warning("Ship " + id + " recovery failed: no parent display found");
            return false;
        }

        // 3. Recover displays by index
        displays.clear();
        recoveredDisplayIndices.clear();
        Map<Integer, Display> displaysByIdx = new TreeMap<>();
        for (Entity e : shipEntities) {
            if (e instanceof Display d && !ShipTags.isParent(e.getScoreboardTags())) {
                int idx = ShipTags.extractDisplayIndex(e.getScoreboardTags());
                if (idx >= 0) {
                    displaysByIdx.put(idx, d);
                }
            }
        }
        // Rebuild displays list with transforms from model
        int totalDisplays = model.parts.size() + model.items.size();
        for (int i = 0; i < totalDisplays; i++) {
            Display d = displaysByIdx.get(i);
            if (d != null) {
                Matrix4f transform = getTransformForDisplayIndex(i);
                displays.add(new DisplayInstance(d, transform));
                recoveredDisplayIndices.add(i);
            }
        }

        // 4. Recover collision boxes (carriers and shulkers)
        colliders.clear();
        Map<Integer, Entity> carriers = new HashMap<>();
        Map<Integer, Shulker> shulkers = new HashMap<>();
        for (Entity e : shipEntities) {
            Set<String> tags = e.getScoreboardTags();
            int blockIdx = ShipTags.extractBlockIndex(tags);
            if (blockIdx < 0) continue;
            if (ShipTags.isCarrier(tags)) {
                carriers.put(blockIdx, e);
            }
            if (ShipTags.isCollider(tags) && e instanceof Shulker s) {
                shulkers.put(blockIdx, s);
            }
        }
        // Pair carriers with shulkers
        for (var entry : carriers.entrySet()) {
            int blockIdx = entry.getKey();
            Shulker s = shulkers.get(blockIdx);
            if (s != null) {
                // Skip a collider whose index no longer fits the model (model definition changed between
                // save and load) instead of throwing - a throw here would abort recovery of every remaining
                // ship in the batch. Matches how the incremental tryAddEntity path tolerates this.
                if (blockIdx >= model.parts.size()) {
                    plugin.getLogger().warning("Ship " + id + " recovery: collider block index " + blockIdx +
                        " exceeds model parts size " + model.parts.size() + " - skipping (model may have changed).");
                    continue;
                }
                // Get collision config from model
                ShipModel.ModelPart part = model.parts.get(blockIdx);
                colliders.add(new CollisionBox(entry.getValue(), s, new Matrix4f(part.local), part.collision, blockIdx));
            }
        }

        // 5. Recover seat shulkers
        seatShulkers.clear();
        for (int i = 0; i < model.seats.size(); i++) {
            seatShulkers.add(null);
        }
        for (Entity e : shipEntities) {
            int seatIdx = ShipTags.extractSeatIndex(e.getScoreboardTags());
            if (seatIdx >= 0 && seatIdx < seatShulkers.size() && e instanceof Shulker s) {
                seatShulkers.set(seatIdx, s);
            }
        }

        // 5b. Recover leadable shulker (for prefab ship lead attachment)
        this.leadableShulker = null;
        for (Entity e : shipEntities) {
            int leadableIdx = ShipTags.extractLeadableIndex(e.getScoreboardTags());
            if (leadableIdx >= 0 && e instanceof Shulker s) {
                this.leadableShulker = s;
                break;  // Only one leadable shulker per ship
            }
        }

        // 6. Restore state and start ticking
        previousVehicleLocation = vehicle.getLocation().clone();
        previousPitch = vehicle.getPitch();
        // spawnYaw must match vehicle.getYaw() (the frozen NBT yaw the client inherits
        // for display passengers on 1.21.9+). currentYaw from metadata provides the delta.
        // Delegated ships (M1): the vehicle yaw is frozen at 0, so seed spawnYaw from the model's
        // assembly yaw instead — mirroring the fresh-spawn ctor — else rotation mis-baselines after a
        // restart. (This native recoverEntities path currently produces mechanism==null ships; the guard
        // is correct-by-construction for when delegated recovery is wired in M5.)
        spawnYaw = (mechanism != null)
            ? ShipTags.normalizeYaw(model.assemblyYaw)
            : ShipTags.normalizeYaw(vehicle.getYaw());
        physics.currentYaw = !Float.isNaN(metadataYaw)
            ? ShipTags.normalizeYaw(metadataYaw)
            : spawnYaw;
        previousYaw = physics.currentYaw;
        firstTick = true;

        // Initialize chunk tracking for persistence
        this.currentChunkX = vehicle.getLocation().getBlockX() >> 4;
        this.currentChunkZ = vehicle.getLocation().getBlockZ() >> 4;
        taskStopped = false;

        // Initialize collision box previous positions to current positions
        // This prevents first-tick velocity spike from (0,0,0) to actual position
        Location currentVehicleLoc = vehicle.getLocation();

        // Build rotation matrix including vehicle's current orientation
        Matrix4f R_full = buildRotationMatrix();

        Matrix4f T_collision = new Matrix4f().translation(model.collisionOffset);
        if ("custom".equals(shipType)) {
            T_collision.translate(config.customCollisionOffset);
        }

        // Initialize each collider's previousWorldPos to current position
        Matrix4f tempWorld = new Matrix4f();
        Vector3f tempOffset = new Vector3f();
        Vector3f tempPerBlock = new Vector3f();
        for (CollisionBox cb : colliders) {
            cb.previousWorldPos = new Vector3f();
            computeColliderWorldPos(R_full, T_collision, cb.base, cb.config.offset,
                    currentVehicleLoc, tempWorld, tempOffset, tempPerBlock, cb.previousWorldPos);
        }

        // Position collision boxes immediately before starting tick task
        updateCollisionPositions();

        // Apply display transforms with correct deltaYaw (may be non-zero if
        // metadata restored a different currentYaw than the vehicle's frozen spawnYaw)
        updateDisplayTransforms();

        // Recompute effective stats. The recovery path previously skipped this, so prefab and
        // sail-only custom ships came back from a chunk reload / server restart stuck at
        // effective*==0 (immovable, can't turn, airships can't ascend/descend). resolveWheelData()
        // links wheelData for custom ships (null no-op for prefab);
        // recomputeStats() is unconditional and idempotent and handles prefab/custom/stats-disabled.
        resolveWheelData();
        physics.recomputeStats();

        // Start tick task
        task = new BukkitRunnable() {
            @Override
            public void run() {
                Location loc = vehicle.getLocation();
                if (!loc.isChunkLoaded()) {
                    return;
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

        plugin.getLogger().info("Recovered " + displays.size() + " displays, " + colliders.size() + " colliders for ship " + id);
        return true;
    }

    /**
     * Gets the transform matrix for a display entity at the given index.
     * Used during entity recovery to reconstruct DisplayInstance objects.
     */
    private Matrix4f getTransformForDisplayIndex(int index) {
        if (index < model.parts.size()) {
            // Block display
            ShipModel.ModelPart part = model.parts.get(index);
            Matrix4f transform = new Matrix4f(part.local);

            // Apply display rotation for custom ships
            if ("custom".equals(shipType) && part.rawYaml.containsKey("display_yaw")) {
                float displayYaw = ((Number) part.rawYaml.get("display_yaw")).floatValue();
                transform.translate(0.5f, 0f, 0.5f);
                transform.rotateY((float) java.lang.Math.toRadians(-displayYaw));
                transform.translate(-0.5f, 0f, -0.5f);
            }

            // Handle heads/skulls and banners
            boolean hasHead = part.rawYaml.containsKey("skull_profile") ||
                              part.rawYaml.containsKey("skull_rotation") ||
                              part.rawYaml.containsKey("skull_facing");
            // Detect banners by rotation/facing keys (works for both plain and patterned banners)
            boolean hasBannerPatterns = part.rawYaml.containsKey("banner_patterns") ||
                                        part.rawYaml.containsKey("banner_rotation") ||
                                        part.rawYaml.containsKey("banner_facing");

            if (hasHead) {
                // Use the shared helper so recovery matches the spawn/tick transform
                // exactly (incl. the wall-head branch this path previously lacked).
                applySkullTransform(transform, part.rawYaml);
            } else if (hasBannerPatterns) {
                return calculateBannerTransform(new Matrix4f(part.local), part.rawYaml);
            }

            return transform;
        } else {
            // Item display (index offset by parts.size())
            int itemIndex = index - model.parts.size();
            if (itemIndex < model.items.size()) {
                return new Matrix4f(model.items.get(itemIndex).local);
            }
            return new Matrix4f();  // Identity matrix as fallback
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
        parent = null;
        displays.clear();
        recoveredDisplayIndices.clear();
        colliders.clear();
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
        if (parent != null) {
            Entity vehicleEntity = parent.getVehicle();
            if (vehicleEntity != null) {
                vehicleEntity.removePassenger(parent);
            }
            for (Entity passenger : parent.getPassengers()) {
                passenger.remove();
            }
            parent.remove();
        }
        // Remove child block/item displays directly. Normally they are passengers of `parent`
        // (removed above), but on a failed assembly they are spawned and not yet mounted (mounting is
        // deferred 1 tick), so remove them by list too. Idempotent - a double remove() is harmless.
        for (DisplayInstance di : displays) {
            if (di.entity != null && di.entity.isValid()) di.entity.remove();
        }
        // Dismount any riders before removing shulkers
        // (removePassenger triggers VehicleExitEvent, which handles safe-position teleport)
        for (CollisionBox cb : colliders) {
            for (Entity passenger : cb.entity.getPassengers()) {
                if (passenger instanceof Player p) {
                    cb.entity.removePassenger(p);
                }
            }
        }
        // Remove all collision shulkers and their carriers
        // Note: Seats are now the shulkers themselves (no separate seat ArmorStands)
        for (CollisionBox cb : colliders) {
            cb.entity.remove();    // Remove shulker (may be a seat)
            cb.carrier.remove();   // Remove carrier (ArmorStand or Interaction)
        }
        // Remove root vehicle
        if (vehicle != null && vehicle.isValid()) vehicle.remove();
        // Clear incremental recovery state
        pendingCarriers.clear();
        pendingShulkers.clear();
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

    /**
     * Counts the number of currently valid/recovered entities.
     * Used during recovery to check how many entities were found.
     * Note: leadableShulker is not counted separately as it's already included in colliders.
     */
    public int countRecoveredEntities() {
        int count = 0;
        if (vehicle != null && vehicle.isValid()) count++;
        if (parent != null && parent.isValid()) count++;
        count += displays.size();
        count += colliders.size() * 2;  // carrier + shulker per collider (leadableShulker is one of these)
        count += (int) seatShulkers.stream().filter(s -> s != null && s.isValid()).count();
        return count;
    }

    // ===== Incremental Recovery Methods =====

    /**
     * Sets the expected entity count for recovery completeness tracking.
     * Called during chunk load recovery with the value from saved metadata.
     */
    public void setExpectedEntityCount(int count) {
        this.expectedEntityCount = count;
        this.recoveryComplete = (count == 0) || (countRecoveredEntities() >= count);
    }

    /**
     * Returns true if all expected entities have been recovered.
     */
    public boolean isRecoveryComplete() {
        return recoveryComplete;
    }

    /**
     * Collects any entities belonging to this ship from a newly-loaded chunk.
     * Called when chunks load and ship recovery is incomplete.
     *
     * @return Number of entities added to ship collections.
     *         Returns 2 for a carrier+shulker pair since colliders count as 2 entities.
     *         Entities waiting for their pair (pending carriers/shulkers) return 0 until matched.
     *         This matches the counting in countEntities() and countRecoveredEntities().
     */
    public int collectEntitiesFromChunk(org.bukkit.Chunk chunk) {
        if (recoveryComplete) return 0;

        // Clean up any invalid pending entities
        pendingCarriers.entrySet().removeIf(e -> !e.getValue().isValid());
        pendingShulkers.entrySet().removeIf(e -> !e.getValue().isValid());

        int added = 0;
        for (Entity e : chunk.getEntities()) {
            UUID entityShipId = ShipTags.extractShipId(e.getScoreboardTags());
            if (!this.id.equals(entityShipId)) continue;
            added += tryAddEntity(e);
        }

        if (added > 0) {
            // Check for any pending carrier/shulker pairs that can now be combined
            added += processPendingColliders();

            int current = countRecoveredEntities();
            if (current >= expectedEntityCount) {
                recoveryComplete = true;
                // Clear pending maps to release entity references
                pendingCarriers.clear();
                pendingShulkers.clear();
                plugin.getLogger().info("Ship " + id + " recovery now complete (" + current + " entities)");
            }
        }
        return added;
    }

    /**
     * Attempts to add an entity to this ship's collections during incremental recovery.
     *
     * @return Number of entities added to ship collections:
     *         - 0: Entity already exists, not recognized, or stored in pending map waiting for pair
     *         - 1: Single entity added (display, seat shulker)
     *         - 2: Carrier+shulker pair completed (both entities added to colliders)
     */
    private int tryAddEntity(Entity e) {
        Set<String> tags = e.getScoreboardTags();

        // Skip vehicle/parent - already have them from initial recovery
        if (e instanceof ArmorStand && ShipTags.isRoot(tags)) return 0;
        if (e instanceof BlockDisplay && ShipTags.isParent(tags)) return 0;

        // Display entity
        if (e instanceof Display d && !ShipTags.isParent(tags)) {
            int idx = ShipTags.extractDisplayIndex(tags);
            if (idx >= 0 && !hasDisplayAtIndex(idx)) {
                Matrix4f transform = getTransformForDisplayIndex(idx);
                displays.add(new DisplayInstance(d, transform));
                recoveredDisplayIndices.add(idx);
                return 1;
            }
        }

        // Collider: need both carrier and shulker with matching block index
        int blockIdx = ShipTags.extractBlockIndex(tags);
        if (blockIdx >= 0 && blockIdx < model.parts.size() && !hasColliderAtIndex(blockIdx)) {
            if (ShipTags.isCarrier(tags)) {
                // Check if we already have a shulker waiting
                Shulker pendingShulker = pendingShulkers.remove(blockIdx);
                if (pendingShulker != null) {
                    ShipModel.ModelPart part = model.parts.get(blockIdx);
                    colliders.add(new CollisionBox(e, pendingShulker, new Matrix4f(part.local), part.collision, blockIdx));
                    // Set leadable reference if this shulker is the lead attachment point
                    if (leadableShulker == null && ShipTags.extractLeadableIndex(pendingShulker.getScoreboardTags()) >= 0) {
                        leadableShulker = pendingShulker;
                    }
                    return 2;
                } else {
                    pendingCarriers.put(blockIdx, e);
                    return 0;  // Don't count until paired with shulker
                }
            }
            if (ShipTags.isCollider(tags) && e instanceof Shulker s) {
                // Check if we already have a carrier waiting
                Entity pendingCarrier = pendingCarriers.remove(blockIdx);
                if (pendingCarrier != null) {
                    ShipModel.ModelPart part = model.parts.get(blockIdx);
                    colliders.add(new CollisionBox(pendingCarrier, s, new Matrix4f(part.local), part.collision, blockIdx));
                    // Set leadable reference if this shulker is the lead attachment point
                    if (leadableShulker == null && ShipTags.extractLeadableIndex(tags) >= 0) {
                        leadableShulker = s;
                    }
                    return 2;
                } else {
                    pendingShulkers.put(blockIdx, s);
                    return 0;  // Don't count until paired with carrier
                }
            }
        }

        // Seat shulker
        int seatIdx = ShipTags.extractSeatIndex(tags);
        if (seatIdx >= 0 && seatIdx < seatShulkers.size() && e instanceof Shulker s) {
            if (seatShulkers.get(seatIdx) == null) {
                seatShulkers.set(seatIdx, s);
                return 1;
            }
        }

        return 0;
    }

    /**
     * Processes any pending carrier/shulker pairs that can now be combined.
     */
    private int processPendingColliders() {
        int added = 0;
        Iterator<Map.Entry<Integer, Entity>> iter = pendingCarriers.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Integer, Entity> entry = iter.next();
            int blockIdx = entry.getKey();
            Shulker s = pendingShulkers.remove(blockIdx);
            if (s != null) {
                ShipModel.ModelPart part = model.parts.get(blockIdx);
                colliders.add(new CollisionBox(entry.getValue(), s, new Matrix4f(part.local), part.collision, blockIdx));
                // Set leadable reference if this shulker is the lead attachment point
                if (leadableShulker == null && ShipTags.extractLeadableIndex(s.getScoreboardTags()) >= 0) {
                    leadableShulker = s;
                }
                iter.remove();
                added += 2;
            }
        }
        return added;
    }

    /**
     * Checks if a display entity at the given index already exists.
     */
    private boolean hasDisplayAtIndex(int index) {
        return recoveredDisplayIndices.contains(index);
    }

    /**
     * Checks if a collider at the given block index already exists.
     */
    private boolean hasColliderAtIndex(int blockIdx) {
        for (CollisionBox cb : colliders) {
            if (cb.blockIndex == blockIdx) return true;
        }
        return false;
    }

    // ===== Custom Ship Methods =====

    /**
     * Aligns the ship to the block grid by snapping position and rotation.
     * Position is rounded to the nearest block coordinates.
     * Rotation is snapped to the nearest 90-degree increment.
     */
    public void alignToGrid() {
        physics.alignToGrid();
        if (mechanism == null) {
            // Native ships: re-baseline spawnYaw to the snapped heading (absorbed by the vehicle's own
            // entity yaw) and refresh the native display transforms.
            spawnYaw = physics.currentYaw;
            updateDisplayTransforms();
        }
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

        // Get dispenser inventory (the ammo). For a delegated ship the native `storages` map is empty — the
        // dispenser's captured inventory rides on the mechanism, keyed by block index (== dispenserBlockIndex
        // via the parity invariant). Consuming from it round-trips back to the placed dispenser on disassemble.
        Inventory inv = (mechanism != null)
            ? mechanism.getStorage(cannon.dispenserBlockIndex)
            : storages.get(cannon.dispenserBlockIndex);
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
