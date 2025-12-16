package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.*;
import anon.def9a2a4.blockships.customships.ShipWheelData;
import anon.def9a2a4.blockships.customships.ShipWheelManager;
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
    }

    public final JavaPlugin plugin;
    public final ShipModel model;
    public final String shipType;  // Ship type identifier (e.g., "smallship", "bigship")
    public ArmorStand vehicle;  // Root entity used for physics (non-final for chunk recovery)
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
    private BukkitRunnable task;
    private BukkitRunnable idleCheckTask;

    // Movement tracking for optimization
    private Location previousVehicleLocation;
    private float previousYaw;
    private float previousPitch;
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
        // Deserialize banner if present
        ItemStack customBanner = null;
        if (state.bannerData != null) {
            try {
                byte[] bytes = Base64.getDecoder().decode(state.bannerData);
                customBanner = ItemStack.deserializeBytes(bytes);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to deserialize banner for ship " + state.id + ": " + e.getMessage());
            }
        }

        ShipCustomization customization = ShipCustomization.builder()
            .banner(customBanner)
            .woodType(state.woodType)
            .balloonColor(state.balloonColor)
            .build();

        ShipInstance instance = new ShipInstance(plugin, state.shipType, model, customization, state.id);

        // For custom ships, restore the source model for disassembly
        if ("custom".equals(state.shipType)) {
            instance.sourceModel = model;
        }

        // Restore inventory contents
        if (!state.inventoryData.isEmpty()) {
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
                            Inventory storage = Bukkit.createInventory(null, part.storage.type.slots,
                                net.kyori.adventure.text.Component.text(part.storage.name));
                            storage.setContents(items);
                            instance.storages.put(blockIdx, storage);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to deserialize inventory at block " + entry.getKey() + ": " + e.getMessage());
                }
            }
        }

        return instance;
    }

    public ShipInstance(JavaPlugin plugin, String shipType, ShipModel model, Location spawnLocation, ShipCustomization customization) {
        this.plugin = plugin;
        this.shipType = shipType;
        this.model = model;
        this.customization = customization != null ? customization : ShipCustomization.empty();
        this.id = UUID.randomUUID();
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
        this.vehicle = w.spawn(base, ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setGravity(false);
            as.setSilent(true);
            as.setPersistent(true);
            as.addScoreboardTag(ShipTags.shipRootTag(id));

            // Root vehicle has health system for ship damage
            org.bukkit.attribute.AttributeInstance maxHealthAttr = as.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (maxHealthAttr != null) {
                maxHealthAttr.setBaseValue(model.maxHealth);
            }
            as.setHealth(model.maxHealth);

            // Force rotation to match spawn location (Bukkit doesn't auto-apply yaw from Location)
            as.setRotation(base.getYaw(), base.getPitch());
        });

        // Seats are now the collision shulkers themselves (no separate ArmorStands)
        // Driver seat is always at index 0 (validated in ShipModel.fromFile)

        // Initialize seatShulkers list with nulls (will be populated during collision spawning)
        for (int i = 0; i < model.seats.size(); i++) {
            seatShulkers.add(null);
        }

        // Initialize delegates
        this.physics = new ShipPhysics(this);
        this.collision = new ShipCollision(this);

        // Initialize previous state
        this.previousVehicleLocation = vehicle.getLocation().clone();
        this.previousYaw = vehicle.getYaw();
        this.previousPitch = vehicle.getPitch();

        // Initialize chunk tracking for persistence
        this.currentChunkX = vehicle.getLocation().getBlockX() >> 4;
        this.currentChunkZ = vehicle.getLocation().getBlockZ() >> 4;

        // Spawn invisible parent display for rotation control
        parent = w.spawn(base, BlockDisplay.class, d -> {
            d.setBlock(Bukkit.createBlockData(Material.AIR));
            d.setInterpolationDuration(1);
            d.setTeleportDuration(1);
            d.setViewRange(64f);
            d.setPersistent(true);
            d.setGravity(false);
            d.addScoreboardTag(ShipTags.shipTag(this.id));
            d.addScoreboardTag(ShipTags.PARENT_TAG);
        });

        // Spawn each block display part as a child
        for (int blockIndex = 0; blockIndex < model.parts.size(); blockIndex++) {
            ShipModel.ModelPart p = model.parts.get(blockIndex);
            final int currentBlockIndex = blockIndex;  // For use in lambda

            // Check if this part needs special rendering (player head or banner)
            boolean hasSkullProfile = p.rawYaml.containsKey("skull_profile");
            boolean hasBannerPatterns = p.rawYaml.containsKey("banner_patterns");

            org.bukkit.entity.Display child;
            Matrix4f displayTransform;  // Transform used for DisplayInstance (may include rotation)

            if (hasSkullProfile || hasBannerPatterns) {
                // Spawn as ItemDisplay to preserve textures
                child = w.spawn(base, org.bukkit.entity.ItemDisplay.class, id -> {
                    // Create ItemStack for the display
                    ItemStack displayItem;

                    if (hasSkullProfile) {
                        // Create player head item with texture
                        displayItem = new ItemStack(Material.PLAYER_HEAD);
                        ItemMeta meta = displayItem.getItemMeta();

                        if (meta instanceof org.bukkit.inventory.meta.SkullMeta) {
                            org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) meta;
                            String profileData = (String) p.rawYaml.get("skull_profile");

                            // Deserialize and apply profile
                            com.destroystokyo.paper.profile.PlayerProfile profile = deserializeSkullProfile(profileData);
                            if (profile != null) {
                                skullMeta.setPlayerProfile(profile);
                            }

                            displayItem.setItemMeta(skullMeta);
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
                    id.setInterpolationDuration(1);
                    id.setTeleportDuration(1);
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

                    if (hasSkullProfile) {
                        // Player heads: use HEAD transform mode (displays as worn on head)
                        id.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.HEAD);

                        Matrix4f skullTransform = new Matrix4f(finalTransform);

                        // Calculate yaw from stored rotation data
                        float skullYaw = 0.0f;
                        if (p.rawYaml.containsKey("skull_rotation")) {
                            // Floor head: 16-step rotation
                            BlockFace rotation = BlockFace.valueOf((String) p.rawYaml.get("skull_rotation"));
                            skullYaw = getYawFromBlockFace(rotation);
                        } else if (p.rawYaml.containsKey("skull_facing")) {
                            // Wall head: 4-direction facing
                            BlockFace facing = BlockFace.valueOf((String) p.rawYaml.get("skull_facing"));
                            skullYaw = getYawFromBlockFace(facing);
                        }

                        // Position skull: move to block center, rotate, then offset
                        // Needs: up 0.5, left 0.5, forward 0.5
                        skullTransform.translate(0.5f, 0.5f, 0.5f);  // Move to block center
                        skullTransform.rotateY((float) java.lang.Math.toRadians(-skullYaw));

                        id.setTransformationMatrix(skullTransform);
                    } else {
                        // Banners: use FIXED transform mode with custom scaling
                        // FIXED mode displays item at actual size, so we need to scale and position it
                        id.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);

                        // Get banner rotation from rawYaml to determine if wall or standing banner
                        boolean isWallBanner = p.rawYaml.containsKey("banner_facing");

                        Matrix4f bannerTransform = new Matrix4f(finalTransform);

                        // Calculate yaw from stored rotation data
                        float bannerYaw = 0.0f;
                        if (isWallBanner) {
                            // Wall banner: 4-direction facing
                            BlockFace facing = BlockFace.valueOf((String) p.rawYaml.get("banner_facing"));
                            bannerYaw = getYawFromBlockFace(facing);
                        } else if (p.rawYaml.containsKey("banner_rotation")) {
                            // Standing banner: 16-step rotation
                            BlockFace rotation = BlockFace.valueOf((String) p.rawYaml.get("banner_rotation"));
                            bannerYaw = getYawFromBlockFace(rotation);
                        }

                        // Tuning values for banner display
                        float bannerScale = 2f;  // Scale to proper size

                        if (isWallBanner) {
                            // Wall banner: center in block, rotate, offset to wall
                            bannerTransform.translate(0.5f, 0.5f, 0.5f);
                            bannerTransform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                            bannerTransform.scale(bannerScale);
                            bannerTransform.translate(0.0f, -0.5f, -0.275f);  // down 1 block, forward 0.25, wall offset
                        } else {
                            // Standing banner: position at block center, rotate
                            bannerTransform.translate(0.5f, 0.5f, 0.5f);
                            bannerTransform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                            bannerTransform.scale(bannerScale);
                        }
                        id.setTransformationMatrix(bannerTransform);
                    }
                });
                // ItemDisplay: apply same rotation transforms as used above for tick() updates
                // Note: displayOffset is applied in tick() via T_display, not here
                displayTransform = new Matrix4f(p.local);
                if (hasSkullProfile) {
                    // Apply skull rotation to displayTransform (must match spawn transforms above)
                    float skullYaw = 0.0f;
                    if (p.rawYaml.containsKey("skull_rotation")) {
                        BlockFace rotation = BlockFace.valueOf((String) p.rawYaml.get("skull_rotation"));
                        skullYaw = getYawFromBlockFace(rotation);
                    } else if (p.rawYaml.containsKey("skull_facing")) {
                        BlockFace facing = BlockFace.valueOf((String) p.rawYaml.get("skull_facing"));
                        skullYaw = getYawFromBlockFace(facing);
                    }
                    displayTransform.translate(0.5f, 0.5f, 0.5f);
                    displayTransform.rotateY((float) java.lang.Math.toRadians(-skullYaw));
                } else if (hasBannerPatterns) {
                    // Apply banner rotation to displayTransform (must match spawn transforms above)
                    boolean isWallBanner = p.rawYaml.containsKey("banner_facing");
                    float bannerYaw = 0.0f;
                    if (isWallBanner) {
                        BlockFace facing = BlockFace.valueOf((String) p.rawYaml.get("banner_facing"));
                        bannerYaw = getYawFromBlockFace(facing);
                    } else if (p.rawYaml.containsKey("banner_rotation")) {
                        BlockFace rotation = BlockFace.valueOf((String) p.rawYaml.get("banner_rotation"));
                        bannerYaw = getYawFromBlockFace(rotation);
                    }
                    float bannerScale = 2f;
                    if (isWallBanner) {
                        displayTransform.translate(0.5f, 0.5f, 0.5f);
                        displayTransform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                        displayTransform.scale(bannerScale);
                        displayTransform.translate(0.0f, -0.5f, -0.275f);  // down 1 block, forward 0.25, wall offset
                    } else {
                        displayTransform.translate(0.5f, 0.5f, 0.5f);
                        displayTransform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                        displayTransform.scale(bannerScale);
                    }
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
                child = w.spawn(base, BlockDisplay.class, bd -> {
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
                bd.setInterpolationDuration(1);
                bd.setTeleportDuration(1);
                bd.setShadowRadius(0f);
                bd.setShadowStrength(0f);
                bd.setGlowing(false);
                bd.setGravity(false);
                bd.setPersistent(true);
                bd.addScoreboardTag(ShipTags.shipTag(this.id));
                bd.addScoreboardTag(ShipTags.displayIndexTag(currentBlockIndex));

                // TODO: Sign text cannot be displayed on BlockDisplay entities (Minecraft limitation).
                // A workaround would be to spawn TextDisplay entities near signs to show the text.

                bd.setTransformationMatrix(blockDisplayTransform);
            });
            }
            // Use displayTransform for tick updates (includes rotation for blocks that need it)
            displays.add(new DisplayInstance(child, new Matrix4f(displayTransform)));

            // Create inventory for this block if it has storage configured
            if (p.storage != null) {
                Inventory storage = Bukkit.createInventory(null, p.storage.type.slots,
                    net.kyori.adventure.text.Component.text(p.storage.name));

                // Restore saved inventory contents if available
                if (p.rawYaml.containsKey("container_items")) {
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> itemsData =
                        (java.util.List<java.util.Map<String, Object>>) p.rawYaml.get("container_items");

                    if (itemsData != null) {
                        for (java.util.Map<String, Object> itemData : itemsData) {
                            int slot = (Integer) itemData.get("slot");
                            byte[] serialized = (byte[]) itemData.get("item");

                            if (slot >= 0 && slot < storage.getSize() && serialized != null) {
                                try {
                                    ItemStack item = ItemStack.deserializeBytes(serialized);
                                    storage.setItem(slot, item);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                }

                storages.put(currentBlockIndex, storage);
            }

            // Spawn collision shulker if this block has collision enabled
            if (p.collision.enable) {
                // Create spawn location with zero rotation (carriers never rotate)
                Location carrierSpawnLoc = base.clone();
                carrierSpawnLoc.setYaw(0);
                carrierSpawnLoc.setPitch(0);

                // Use ArmorStand as carrier (smooth interpolation)
                ArmorStand carrier = w.spawn(carrierSpawnLoc, ArmorStand.class, as -> {
                    as.setInvisible(true);
                    as.setInvulnerable(true);
                    as.setGravity(false);
                    as.setSilent(true);
                    as.setPersistent(true);
                    as.setMarker(true);  // Marker mode: no hitbox, can't be pushed
                    as.addScoreboardTag(ShipTags.shipTag(this.id));
                    as.addScoreboardTag(ShipTags.CARRIER_TAG);
                    as.addScoreboardTag(ShipTags.blockIndexTag(currentBlockIndex));
                });

                // Spawn shulker as passenger for physical collision
                // Apply size scaling via generic.scale attribute
                float shulkerSize = p.collision.size;
                final int finalBlockIndex = currentBlockIndex;
                Shulker shulker = w.spawn(carrierSpawnLoc, Shulker.class, s -> {
                    s.setAI(false);
                    s.setInvulnerable(true);
                    s.setGravity(false);
                    s.setSilent(true);
                    s.setPersistent(true);
                    s.setCollidable(true);
                    s.setInvisible(true);
                    s.setGlowing(config.collisionDebugGlow);  // Glow if debug mode enabled
                    s.setPeek(0);  // Prevent shulker from peeking/moving up
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

                    // Apply scale attribute to change collision box size
                    org.bukkit.attribute.AttributeInstance scaleAttr = s.getAttribute(org.bukkit.attribute.Attribute.SCALE);
                    if (scaleAttr != null) {
                        scaleAttr.setBaseValue(shulkerSize);
                    }
                });

                // Mount shulker on carrier
                carrier.addPassenger(shulker);

                colliders.add(new CollisionBox(carrier, shulker, new Matrix4f(p.local), p.collision, currentBlockIndex));

                // Store leadable shulker reference for prefab ship lead attachment (single lead point)
                // Custom ships use per-fence attachment via leadable tags instead
                if (!"custom".equals(shipType) && p.rawYaml.containsKey("leadable") && Boolean.TRUE.equals(p.rawYaml.get("leadable"))) {
                    this.leadableShulker = shulker;
                }
            }
        }

        // Spawn each item display part as a child
        // Display indices continue after block parts for recovery purposes
        final int itemDisplayOffset = model.parts.size();
        for (int itemIndex = 0; itemIndex < model.items.size(); itemIndex++) {
            ShipModel.ItemPart p = model.items.get(itemIndex);
            final int displayIndex = itemDisplayOffset + itemIndex;
            ItemDisplay child = w.spawn(base, ItemDisplay.class, id -> {
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
                id.setInterpolationDuration(1);
                id.setTeleportDuration(1);
                id.setShadowRadius(0f);
                id.setShadowStrength(0f);
                id.setGlowing(false);
                id.setGravity(false);
                id.setPersistent(true);
                id.addScoreboardTag(ShipTags.shipTag(this.id));
                id.addScoreboardTag(ShipTags.displayIndexTag(displayIndex));
                id.setTransformationMatrix(p.local);
            });
            displays.add(new DisplayInstance(child, new Matrix4f(p.local)));
        }

        // Wait 1 tick for entities to spawn, then mount and start ticking
        new BukkitRunnable() {
            @Override
            public void run() {
                // Mount children to parent
                for (DisplayInstance di : displays) {
                    parent.addPassenger(di.entity);
                }
                // Mount parent to vehicle (ArmorStand)
                vehicle.addPassenger(parent);

                // Position collision boxes immediately before starting tick task
                // This prevents them from appearing to "jump" when player first interacts
                updateCollisionPositions();

                // Start tick loop
                task = new BukkitRunnable() {
                    @Override
                    public void run() {
                        // Check if chunk is loaded - if not, skip tick but don't destroy
                        Location loc = vehicle.getLocation();
                        if (!loc.isChunkLoaded()) {
                            return; // Chunk unloaded, suspend ship but don't destroy
                        }
                        if (vehicle.isDead() || !vehicle.isValid()) {
                            destroy();
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
     * Builds a rotation matrix that combines the vehicle's current orientation with the model's initial rotation.
     * This matrix should be used for positioning both collision boxes AND display entities.
     *
     * Note: Display entities do NOT inherit yaw rotation from their parent vehicle - only position is inherited.
     * Therefore, we must explicitly apply the vehicle's rotation to display transforms.
     */
    private Matrix4f buildRotationMatrix() {
        float yaw = vehicle.getYaw();
        float pitch = vehicle.getPitch();

        Vector3f vehicleRot = new Vector3f(
            (float) java.lang.Math.toRadians(-yaw),
            (float) java.lang.Math.toRadians(-pitch),
            0f
        );
        Vector3f transformedRot = model.rotationTransform.transform(vehicleRot, new Vector3f());
        transformedRot.x += (float) java.lang.Math.toRadians(model.initialRotation.x);
        transformedRot.y += (float) java.lang.Math.toRadians(model.initialRotation.y);
        transformedRot.z += (float) java.lang.Math.toRadians(model.initialRotation.z);

        return new Matrix4f()
            .rotateY(transformedRot.x)
            .rotateX(transformedRot.y)
            .rotateZ(transformedRot.z);
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
        for (CollisionBox cb : colliders) {
            Location cbLoc = cb.entity.getLocation();
            // Use max of axis distances (box distance) - cheaper than manhattan and works with getNearbyEntities
            float dx = (float) java.lang.Math.abs(center.getX() - cbLoc.getX());
            float dy = (float) java.lang.Math.abs(center.getY() - cbLoc.getY());
            float dz = (float) java.lang.Math.abs(center.getZ() - cbLoc.getZ());
            float dist = java.lang.Math.max(dx, java.lang.Math.max(dy, dz));
            if (dist > maxDist) maxDist = dist;
        }
        // Add padding (2.0 for original getNearbyEntities radius per collider)
        this.collisionRadius = maxDist + 2.0f;
    }

    public void updateCollisionPositions() {
        Location currentVehicleLoc = vehicle.getLocation();

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

        // Build rotation matrix including vehicle's current orientation
        Matrix4f R_full = buildRotationMatrix();

        // Build translation matrix for collision offset
        Matrix4f T_collision = new Matrix4f().translation(model.collisionOffset);

        // Add custom ship collision offset from config
        if ("custom".equals(shipType)) {
            T_collision.translate(config.customCollisionOffset);
        }

        // Update collider (Interaction carrier + Shulker) positions
        for (CollisionBox cb : colliders) {
            // Calculate world transformation for this collider using collision offset
            Matrix4f world = new Matrix4f(R_full).mul(T_collision).mul(cb.base);

            // Extract position from transformation matrix
            Vector3f offset = new Vector3f();
            world.getTranslation(offset);

            // Apply per-block collision offset (rotated by R_full to follow ship orientation)
            Vector3f perBlockOffset = new Vector3f(cb.config.offset);
            R_full.transformPosition(perBlockOffset);

            // Calculate current world position (base position + block offset + per-block offset)
            Vector3f currentWorldPos = new Vector3f(
                (float) currentVehicleLoc.getX() + offset.x + perBlockOffset.x,
                (float) currentVehicleLoc.getY() + offset.y + perBlockOffset.y,
                (float) currentVehicleLoc.getZ() + offset.z + perBlockOffset.z
            );

            // Calculate velocity (change in position since last tick)
            Vector3f velocity = new Vector3f(currentWorldPos).sub(cb.previousWorldPos);

            // Check if this is the first tick (previousWorldPos was initialized to 0,0,0)
            // If so, skip velocity application to avoid massive initial velocity spike
            boolean isFirstTick = cb.previousWorldPos.x == 0 && cb.previousWorldPos.y == 0 && cb.previousWorldPos.z == 0;

            // Teleport carrier to world position (including per-block offset)
            // The shulker rides as passenger and follows smoothly (ArmorStand) or choppily (Interaction)
            // Note: Carriers never rotate - only position changes (AABBs don't rotate, shulkers inherit zero rotation)
            Location carrierLoc = currentVehicleLoc.clone().add(
                offset.x + perBlockOffset.x,
                offset.y + perBlockOffset.y,
                offset.z + perBlockOffset.z
            );
            carrierLoc.setYaw(0);
            carrierLoc.setPitch(0);

            // Only teleport if position actually changed (avoids collision jitter when idle)
            float velocityMagnitude = velocity.length();

            // BEFORE teleport: capture player if this is a seat shulker
            // (teleporting carriers can sometimes dismount nested passengers)
            Player seatedPlayer = null;
            if (seatShulkers.contains(cb.entity)) {
                for (Entity passenger : cb.entity.getPassengers()) {
                    if (passenger instanceof Player p) {
                        seatedPlayer = p;
                        break;
                    }
                }
            }

            if (isFirstTick || velocityMagnitude > 0.001) {
                cb.carrier.teleport(carrierLoc);

                // Set carrier velocity for better client/server sync (skip on first tick)
                if (!isFirstTick) {
                    cb.carrier.setVelocity(new org.bukkit.util.Vector(velocity.x, velocity.y, velocity.z));
                }
            }

            // AFTER teleport: re-mount player if they were dismounted
            if (seatedPlayer != null && !cb.entity.getPassengers().contains(seatedPlayer)) {
                final Player playerToRemount = seatedPlayer;
                final Shulker seat = cb.entity;
                // Delay by 1 tick to ensure teleport fully completes
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (playerToRemount.isValid() && seat.isValid()) {
                            seat.addPassenger(playerToRemount);
                        }
                    }
                }.runTaskLater(plugin, 1L);
            }

            // Apply velocity to players standing on this shulker
            physics.applyDeckPhysics(cb, velocity, isFirstTick);

            // Store current position for next tick
            cb.previousWorldPos.set(currentWorldPos);
        }

        // Note: Seats are now the shulkers themselves (no separate seat ArmorStands to update)
        // Shulker positions are already updated in the collision box loop above
    }

    void tick() {
        // Health regeneration (20 ticks per second)
        if (vehicle.isValid() && !vehicle.isDead()) {
            double currentHealth = vehicle.getHealth();
            double maxHealth = vehicle.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue();

            // Regenerate health per tick (divide by 20 since this runs 20 times per second)
            double regenPerTick = model.healthRegenPerSecond / 20.0;
            double newHealth = java.lang.Math.min(currentHealth + regenPerTick, maxHealth);
            vehicle.setHealth(newHealth);

            // Check for ship destruction
            if (currentHealth <= 0) {
                destroyAndDropItem();
                return;  // Stop processing this tick
            }
        }

        // Apply custom physics and steering (runs every tick)
        handleSteeringInput();
        collision.detect();  // Detect collisions and accumulate forces
        physics.update();    // Apply physics (movement, rotation, buoyancy)
        collision.applyResponse();  // Apply collision response
        updateCollisionPositions();  // Sync collision boxes with vehicle BEFORE movement check

        // Get current vehicle state
        Location currentVehicleLoc = vehicle.getLocation();
        float yaw = vehicle.getYaw();
        float pitch = vehicle.getPitch();

        // Check if ship has moved or rotated
        boolean hasMoved = hasMovedSinceLastTick(currentVehicleLoc, yaw, pitch);

        if (!hasMoved && !firstTick) {
            // Ship hasn't moved, increment idle counter
            ticksSinceLastMovement++;

            // If idle for too long, stop the task (but keep running physics)
            if (ticksSinceLastMovement >= IDLE_TICKS_BEFORE_STOP && !taskStopped) {
                // Note: We don't stop the task anymore since we need physics to run
                // Instead, we just skip display updates below
            }
            // Skip display/collision updates but continue physics
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
            if (plugin instanceof BlockShipsPlugin bsp) {
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

        // Build rotation matrix for initial rotation offset ONLY (not vehicle rotation)
        // Vehicle rotation is inherited since displays are passengers of the vehicle
        Matrix4f R_initial = new Matrix4f()
            .rotateY((float) java.lang.Math.toRadians(model.initialRotation.x))
            .rotateX((float) java.lang.Math.toRadians(model.initialRotation.y))
            .rotateZ((float) java.lang.Math.toRadians(model.initialRotation.z));

        // Build translation matrix for position offset (in local space)
        Matrix4f T = new Matrix4f().translation(model.positionOffset);

        // Add custom ship display offset from config
        Matrix4f T_display = new Matrix4f(T);
        if ("custom".equals(shipType)) {
            T_display.translate(config.customDisplayOffset);
        }

        // Update each child's transformation: R_initial * T_display * display.base
        // Only apply static rotations - vehicle rotation is inherited
        for (DisplayInstance di : displays) {
            Matrix4f world = new Matrix4f(R_initial).mul(T_display).mul(di.base);
            di.entity.setTransformationMatrix(world);
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
                    destroy();
                    cancel();
                    return;
                }

                // Check if vehicle has moved
                Location currentLoc = vehicle.getLocation();
                float currentYaw = vehicle.getYaw();
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
                            // Check if chunk is loaded - if not, skip tick but don't destroy
                            Location tickLoc = vehicle.getLocation();
                            if (!tickLoc.isChunkLoaded()) {
                                return; // Chunk unloaded, suspend ship but don't destroy
                            }
                            if (vehicle.isDead() || !vehicle.isValid()) {
                                destroy();
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
     * Marks a seat as free.
     */
    public void freeSeat(int seatIndex) {
        occupiedSeatIndices.remove(seatIndex);
        if (seatIndex == driverSeatIndex) {
            hasDriver = false;
            // Immediately kill vertical velocity for airships when driver exits
            if (isAirship) {
                physics.currentYVelocity = 0.0f;
            }
            // Snap position and rotation to reduce floating-point jitter
            physics.snapToFineGrid();
        }
    }

    /**
     * Restores storage inventory contents from saved data.
     * Used when loading ships from persistence.
     */
    public void restoreStorageContents(Map<Integer, ItemStack[]> savedContents) {
        for (Map.Entry<Integer, ItemStack[]> entry : savedContents.entrySet()) {
            Inventory inv = storages.get(entry.getKey());
            if (inv != null) {
                inv.setContents(entry.getValue());
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
                if (plugin instanceof BlockShipsPlugin bsp) {
                    ItemStack shipWheel = bsp.getDisplayShip().createShipWheelItem();
                    world.dropItemNaturally(dropLocation, shipWheel);
                }
            } else {
                // Prefab ships drop the ship kit with customization
                ItemStack shipKit = DisplayShip.createShipKit(customization.getCustomBanner(), customization.getWoodType(), shipType);
                world.dropItemNaturally(dropLocation, shipKit);
            }
        }

        // Clean up all entities
        destroy();
    }

    /**
     * Spawns destruction explosions at the given locations.
     * Used when a custom ship is destroyed - causes entity damage but no block damage.
     */
    private void spawnDestructionExplosions(World world, List<Location> locations) {
        for (Location loc : locations) {
            // Small explosion: does entity damage, no block damage
            world.createExplosion(loc, 1.5f, false, false);
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
                // Validate block index against model - throw if model definition changed
                if (blockIdx >= model.parts.size()) {
                    throw new IllegalStateException("Ship " + id + " recovery failed: block index " + blockIdx +
                        " exceeds model parts size " + model.parts.size() + ". Model definition may have changed.");
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
        previousYaw = vehicle.getYaw();
        previousPitch = vehicle.getPitch();
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
        for (CollisionBox cb : colliders) {
            Matrix4f world = new Matrix4f(R_full).mul(T_collision).mul(cb.base);
            Vector3f offset = new Vector3f();
            world.getTranslation(offset);

            Vector3f perBlockOffset = new Vector3f(cb.config.offset);
            R_full.transformPosition(perBlockOffset);

            cb.previousWorldPos = new Vector3f(
                (float) currentVehicleLoc.getX() + offset.x + perBlockOffset.x,
                (float) currentVehicleLoc.getY() + offset.y + perBlockOffset.y,
                (float) currentVehicleLoc.getZ() + offset.z + perBlockOffset.z
            );
        }

        // Position collision boxes immediately before starting tick task
        updateCollisionPositions();

        // Update display transforms to match current vehicle orientation
        // Build rotation matrix for initial rotation offset ONLY (not vehicle rotation)
        // Vehicle rotation is inherited since displays are passengers of the vehicle
        Matrix4f R_initial = new Matrix4f()
            .rotateY((float) java.lang.Math.toRadians(model.initialRotation.x))
            .rotateX((float) java.lang.Math.toRadians(model.initialRotation.y))
            .rotateZ((float) java.lang.Math.toRadians(model.initialRotation.z));

        Matrix4f T = new Matrix4f().translation(model.positionOffset);
        Matrix4f T_display = new Matrix4f(T);
        if ("custom".equals(shipType)) {
            T_display.translate(config.customDisplayOffset);
        }

        for (DisplayInstance di : displays) {
            Matrix4f world = new Matrix4f(R_initial).mul(T_display).mul(di.base);
            di.entity.setTransformationMatrix(world);
        }

        // Start tick task
        task = new BukkitRunnable() {
            @Override
            public void run() {
                Location loc = vehicle.getLocation();
                if (!loc.isChunkLoaded()) {
                    return;
                }
                if (vehicle.isDead() || !vehicle.isValid()) {
                    destroy();
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

            // Handle skulls and banners
            boolean hasSkullProfile = part.rawYaml.containsKey("skull_profile");
            boolean hasBannerPatterns = part.rawYaml.containsKey("banner_patterns");

            if (hasSkullProfile) {
                float skullYaw = 0.0f;
                if (part.rawYaml.containsKey("skull_rotation")) {
                    BlockFace rotation = BlockFace.valueOf((String) part.rawYaml.get("skull_rotation"));
                    skullYaw = getYawFromBlockFace(rotation);
                } else if (part.rawYaml.containsKey("skull_facing")) {
                    BlockFace facing = BlockFace.valueOf((String) part.rawYaml.get("skull_facing"));
                    skullYaw = getYawFromBlockFace(facing);
                }
                transform.translate(0.5f, 0.5f, 0.5f);
                transform.rotateY((float) java.lang.Math.toRadians(-skullYaw));
            } else if (hasBannerPatterns) {
                boolean isWallBanner = part.rawYaml.containsKey("banner_facing");
                float bannerYaw = 0.0f;
                if (isWallBanner) {
                    BlockFace facing = BlockFace.valueOf((String) part.rawYaml.get("banner_facing"));
                    bannerYaw = getYawFromBlockFace(facing);
                } else if (part.rawYaml.containsKey("banner_rotation")) {
                    BlockFace rotation = BlockFace.valueOf((String) part.rawYaml.get("banner_rotation"));
                    bannerYaw = getYawFromBlockFace(rotation);
                }
                float bannerScale = 2f;
                if (isWallBanner) {
                    transform.translate(0.5f, 0.5f, 0.5f);
                    transform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                    transform.scale(bannerScale);
                    transform.translate(0.0f, -0.5f, -0.275f);
                } else {
                    transform.translate(0.5f, 0.5f, 0.5f);
                    transform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                    transform.scale(bannerScale);
                }
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
        colliders.clear();
        seatShulkers.clear();
        // vehicle reference is kept but may become stale
        taskStopped = true;
    }

    /**
     * Returns true if this ship needs its entities respawned.
     * This happens after chunk unload removes non-root entities.
     * @deprecated Use needsEntityRecovery() instead
     */
    @Deprecated
    public boolean needsEntityRespawn() {
        return parent == null || !parent.isValid();
    }

    /**
     * Removes all ship entities except the root vehicle ArmorStand.
     * Called on chunk unload to prevent stale entities from persisting.
     * The ship will respawn these entities when the chunk loads again.
     */
    public void removeNonRootEntities() {
        // Remove parent BlockDisplay and all display passengers
        if (parent != null && parent.isValid()) {
            vehicle.removePassenger(parent);  // Detach from root
            for (Entity passenger : parent.getPassengers()) {
                if (passenger.isValid()) passenger.remove();
            }
            parent.remove();
        }
        parent = null;
        displays.clear();

        // Remove collision entities (carriers and shulkers)
        for (CollisionBox cb : colliders) {
            if (cb.entity != null && cb.entity.isValid()) cb.entity.remove();
            if (cb.carrier != null && cb.carrier.isValid()) cb.carrier.remove();
        }
        colliders.clear();
        seatShulkers.clear();

        // Cancel tick tasks (will be restarted on respawn)
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (idleCheckTask != null) {
            idleCheckTask.cancel();
            idleCheckTask = null;
        }
    }

    /**
     * Respawns all non-root entities (displays, colliders, parent).
     * Called after chunk load when only the root vehicle exists.
     * Note: storages map is NOT cleared - inventory contents persist across chunk reloads.
     */
    public void respawnEntities() {
        World w = vehicle.getLocation().getWorld();
        Location base = vehicle.getLocation();

        // Re-initialize seatShulkers list with nulls (will be populated during collision spawning)
        seatShulkers.clear();
        for (int i = 0; i < model.seats.size(); i++) {
            seatShulkers.add(null);
        }

        // Spawn invisible parent display for rotation control
        parent = w.spawn(base, BlockDisplay.class, d -> {
            d.setBlock(Bukkit.createBlockData(Material.AIR));
            d.setInterpolationDuration(1);
            d.setTeleportDuration(1);
            d.setViewRange(64f);
            d.setPersistent(true);
            d.setGravity(false);
            d.addScoreboardTag(ShipTags.shipTag(this.id));
            d.addScoreboardTag(ShipTags.PARENT_TAG);
        });

        // Spawn each block display part as a child (reuse spawning logic from constructor)
        for (int blockIndex = 0; blockIndex < model.parts.size(); blockIndex++) {
            ShipModel.ModelPart p = model.parts.get(blockIndex);
            final int currentBlockIndex = blockIndex;

            // Check if this part needs special rendering (player head or banner)
            boolean hasSkullProfile = p.rawYaml.containsKey("skull_profile");
            boolean hasBannerPatterns = p.rawYaml.containsKey("banner_patterns");

            org.bukkit.entity.Display child;
            Matrix4f displayTransform;

            if (hasSkullProfile || hasBannerPatterns) {
                // Spawn as ItemDisplay to preserve textures
                child = w.spawn(base, org.bukkit.entity.ItemDisplay.class, id -> {
                    ItemStack displayItem;

                    if (hasSkullProfile) {
                        displayItem = new ItemStack(Material.PLAYER_HEAD);
                        ItemMeta meta = displayItem.getItemMeta();
                        if (meta instanceof org.bukkit.inventory.meta.SkullMeta) {
                            org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) meta;
                            String profileData = (String) p.rawYaml.get("skull_profile");
                            com.destroystokyo.paper.profile.PlayerProfile profile = deserializeSkullProfile(profileData);
                            if (profile != null) {
                                skullMeta.setPlayerProfile(profile);
                            }
                            displayItem.setItemMeta(skullMeta);
                        }
                    } else {
                        String blockName = String.valueOf(p.rawYaml.get("block"));
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
                    id.setInterpolationDuration(1);
                    id.setTeleportDuration(1);
                    id.setShadowRadius(0f);
                    id.setShadowStrength(0f);
                    id.setGlowing(false);
                    id.setGravity(false);
                    id.setPersistent(true);
                    id.addScoreboardTag(ShipTags.shipTag(this.id));

                    Matrix4f finalTransform = new Matrix4f(p.local);
                    if ("custom".equals(shipType)) {
                        finalTransform.translate(config.customDisplayOffset);
                    }

                    if (hasSkullProfile) {
                        id.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.HEAD);
                        Matrix4f skullTransform = new Matrix4f(finalTransform);
                        float skullYaw = 0.0f;
                        if (p.rawYaml.containsKey("skull_rotation")) {
                            BlockFace rotation = BlockFace.valueOf((String) p.rawYaml.get("skull_rotation"));
                            skullYaw = getYawFromBlockFace(rotation);
                        } else if (p.rawYaml.containsKey("skull_facing")) {
                            BlockFace facing = BlockFace.valueOf((String) p.rawYaml.get("skull_facing"));
                            skullYaw = getYawFromBlockFace(facing);
                        }
                        skullTransform.translate(0.5f, 0.5f, 0.5f);
                        skullTransform.rotateY((float) java.lang.Math.toRadians(-skullYaw));
                        id.setTransformationMatrix(skullTransform);
                    } else {
                        id.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
                        boolean isWallBanner = p.rawYaml.containsKey("banner_facing");
                        Matrix4f bannerTransform = new Matrix4f(finalTransform);
                        float bannerYaw = 0.0f;
                        if (isWallBanner) {
                            BlockFace facing = BlockFace.valueOf((String) p.rawYaml.get("banner_facing"));
                            bannerYaw = getYawFromBlockFace(facing);
                        } else if (p.rawYaml.containsKey("banner_rotation")) {
                            BlockFace rotation = BlockFace.valueOf((String) p.rawYaml.get("banner_rotation"));
                            bannerYaw = getYawFromBlockFace(rotation);
                        }
                        float bannerScale = 2f;
                        if (isWallBanner) {
                            bannerTransform.translate(0.5f, 0.5f, 0.5f);
                            bannerTransform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                            bannerTransform.scale(bannerScale);
                            bannerTransform.translate(0.0f, -0.5f, -0.275f);
                        } else {
                            bannerTransform.translate(0.5f, 0.5f, 0.5f);
                            bannerTransform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                            bannerTransform.scale(bannerScale);
                        }
                        id.setTransformationMatrix(bannerTransform);
                    }
                });
                displayTransform = new Matrix4f(p.local);
                if (hasSkullProfile) {
                    float skullYaw = 0.0f;
                    if (p.rawYaml.containsKey("skull_rotation")) {
                        BlockFace rotation = BlockFace.valueOf((String) p.rawYaml.get("skull_rotation"));
                        skullYaw = getYawFromBlockFace(rotation);
                    } else if (p.rawYaml.containsKey("skull_facing")) {
                        BlockFace facing = BlockFace.valueOf((String) p.rawYaml.get("skull_facing"));
                        skullYaw = getYawFromBlockFace(facing);
                    }
                    displayTransform.translate(0.5f, 0.5f, 0.5f);
                    displayTransform.rotateY((float) java.lang.Math.toRadians(-skullYaw));
                } else if (hasBannerPatterns) {
                    boolean isWallBanner = p.rawYaml.containsKey("banner_facing");
                    float bannerYaw = 0.0f;
                    if (isWallBanner) {
                        BlockFace facing = BlockFace.valueOf((String) p.rawYaml.get("banner_facing"));
                        bannerYaw = getYawFromBlockFace(facing);
                    } else if (p.rawYaml.containsKey("banner_rotation")) {
                        BlockFace rotation = BlockFace.valueOf((String) p.rawYaml.get("banner_rotation"));
                        bannerYaw = getYawFromBlockFace(rotation);
                    }
                    float bannerScale = 2f;
                    if (isWallBanner) {
                        displayTransform.translate(0.5f, 0.5f, 0.5f);
                        displayTransform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                        displayTransform.scale(bannerScale);
                        displayTransform.translate(0.0f, -0.5f, -0.275f);
                    } else {
                        displayTransform.translate(0.5f, 0.5f, 0.5f);
                        displayTransform.rotateY((float) java.lang.Math.toRadians(-bannerYaw));
                        displayTransform.scale(bannerScale);
                    }
                }
            } else {
                displayTransform = new Matrix4f(p.local);
                if ("custom".equals(shipType) && p.rawYaml.containsKey("display_yaw")) {
                    float displayYaw = ((Number) p.rawYaml.get("display_yaw")).floatValue();
                    displayTransform.translate(0.5f, 0f, 0.5f);
                    displayTransform.rotateY((float) java.lang.Math.toRadians(-displayYaw));
                    displayTransform.translate(-0.5f, 0f, -0.5f);
                }
                final Matrix4f blockDisplayTransform = displayTransform;

                child = w.spawn(base, BlockDisplay.class, bd -> {
                    BlockData blockData;
                    if ("custom".equals(shipType) && p.rawYaml.containsKey("blockdata")) {
                        String blockDataString = (String) p.rawYaml.get("blockdata");
                        blockData = Bukkit.createBlockData(blockDataString);
                    } else {
                        String blockName = String.valueOf(p.rawYaml.get("block"));
                        String modifiedBlockName = blockName;
                        if (customization.getWoodType() != null) {
                            modifiedBlockName = WoodTypeUtil.replaceWoodType(blockName, customization.getWoodType());
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> properties = (Map<String, Object>) p.rawYaml.get("properties");
                        if (properties != null && !properties.isEmpty()) {
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
                    bd.setInterpolationDuration(1);
                    bd.setTeleportDuration(1);
                    bd.setShadowRadius(0f);
                    bd.setShadowStrength(0f);
                    bd.setGlowing(false);
                    bd.setGravity(false);
                    bd.setPersistent(true);
                    bd.addScoreboardTag(ShipTags.shipTag(this.id));
                    bd.setTransformationMatrix(blockDisplayTransform);
                });
            }
            displays.add(new DisplayInstance(child, new Matrix4f(displayTransform)));

            // Spawn collision shulker if this block has collision enabled
            if (p.collision.enable) {
                Location carrierSpawnLoc = base.clone();
                carrierSpawnLoc.setYaw(0);
                carrierSpawnLoc.setPitch(0);

                // Use ArmorStand as carrier (smooth interpolation)
                ArmorStand carrier = w.spawn(carrierSpawnLoc, ArmorStand.class, as -> {
                    as.setInvisible(true);
                    as.setInvulnerable(true);
                    as.setGravity(false);
                    as.setSilent(true);
                    as.setPersistent(true);
                    as.setMarker(true);
                    as.addScoreboardTag(ShipTags.shipTag(this.id));
                });

                float shulkerSize = p.collision.size;
                final int finalBlockIndex = currentBlockIndex;
                Shulker shulker = w.spawn(carrierSpawnLoc, Shulker.class, s -> {
                    s.setAI(false);
                    s.setInvulnerable(true);
                    s.setGravity(false);
                    s.setSilent(true);
                    s.setPersistent(true);
                    s.setCollidable(true);
                    s.setInvisible(true);
                    s.setGlowing(config.collisionDebugGlow);
                    s.setPeek(0);
                    s.addScoreboardTag(ShipTags.shipTag(this.id));

                    for (int seatIdx = 0; seatIdx < model.seats.size(); seatIdx++) {
                        if (model.seats.get(seatIdx).blockIndex == finalBlockIndex) {
                            s.addScoreboardTag(ShipTags.seatTag(seatIdx));
                            seatShulkers.set(seatIdx, s);
                            break;
                        }
                    }
                    if (p.storage != null) {
                        s.addScoreboardTag(ShipTags.storageTag(finalBlockIndex));
                    }
                    if (p.rawYaml.containsKey("interaction") && Boolean.TRUE.equals(p.rawYaml.get("interaction"))) {
                        s.addScoreboardTag(ShipTags.interactTag(finalBlockIndex));
                    }
                    if (p.rawYaml.containsKey("leadable") && Boolean.TRUE.equals(p.rawYaml.get("leadable"))) {
                        s.addScoreboardTag(ShipTags.leadableTag(finalBlockIndex));
                    }
                    org.bukkit.attribute.AttributeInstance scaleAttr = s.getAttribute(org.bukkit.attribute.Attribute.SCALE);
                    if (scaleAttr != null) {
                        scaleAttr.setBaseValue(shulkerSize);
                    }
                });
                carrier.addPassenger(shulker);
                colliders.add(new CollisionBox(carrier, shulker, new Matrix4f(p.local), p.collision, currentBlockIndex));

                // Store leadable shulker reference for prefab ship lead attachment
                if (p.rawYaml.containsKey("leadable") && Boolean.TRUE.equals(p.rawYaml.get("leadable"))) {
                    this.leadableShulker = shulker;
                }
            }
        }

        // Spawn each item display part as a child
        for (ShipModel.ItemPart p : model.items) {
            ItemDisplay child = w.spawn(base, ItemDisplay.class, id -> {
                ItemStack displayItem = p.item.clone();
                if (customization.getCustomBanner() != null && p.item.getType().name().endsWith("_BANNER")) {
                    displayItem = customization.getCustomBanner().clone();
                }
                if (customization.getBalloonColor() != null && customization.getTextureManager() != null &&
                    displayItem.getType() == Material.PLAYER_HEAD && displayItem.hasItemMeta()) {
                    org.bukkit.inventory.meta.ItemMeta meta = displayItem.getItemMeta();
                    if (meta instanceof org.bukkit.inventory.meta.SkullMeta) {
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
                id.setInterpolationDuration(1);
                id.setTeleportDuration(1);
                id.setShadowRadius(0f);
                id.setShadowStrength(0f);
                id.setGlowing(false);
                id.setGravity(false);
                id.setPersistent(true);
                id.addScoreboardTag(ShipTags.shipTag(this.id));
                id.setTransformationMatrix(p.local);
            });
            displays.add(new DisplayInstance(child, new Matrix4f(p.local)));
        }

        // Wait 1 tick for entities to spawn, then mount and start ticking
        new BukkitRunnable() {
            @Override
            public void run() {
                // Mount children to parent
                for (DisplayInstance di : displays) {
                    parent.addPassenger(di.entity);
                }
                // Mount parent to vehicle (ArmorStand)
                vehicle.addPassenger(parent);

                // Position collision boxes immediately before starting tick task
                updateCollisionPositions();

                // Start tick loop
                task = new BukkitRunnable() {
                    @Override
                    public void run() {
                        // Check if chunk is loaded - if not, skip tick but don't destroy
                        Location loc = vehicle.getLocation();
                        if (!loc.isChunkLoaded()) {
                            return; // Chunk unloaded, suspend ship but don't destroy
                        }
                        if (vehicle.isDead() || !vehicle.isValid()) {
                            destroy();
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

    public void destroy() {
        if (task != null) task.cancel();
        if (idleCheckTask != null) idleCheckTask.cancel();
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
        // Remove all collision shulkers and their carriers
        // Note: Seats are now the shulkers themselves (no separate seat ArmorStands)
        for (CollisionBox cb : colliders) {
            cb.entity.remove();    // Remove shulker (may be a seat)
            cb.carrier.remove();   // Remove carrier (ArmorStand or Interaction)
        }
        // Remove root vehicle
        if (vehicle.isValid()) vehicle.remove();
        ShipRegistry.unregister(this);
    }

    // ===== Custom Ship Methods =====

    /**
     * Aligns the ship to the block grid by snapping position and rotation.
     * Position is rounded to the nearest block coordinates.
     * Rotation is snapped to the nearest 90-degree increment.
     */
    public void alignToGrid() {
        Location loc = vehicle.getLocation();

        // Snap position to nearest block corner (integer coordinates)
        // Ships spawn at block corner positions (e.g., 5.0, 10.0, 8.0), not block centers
        double x = java.lang.Math.round(loc.getX());
        double y = java.lang.Math.round(loc.getY());
        double z = java.lang.Math.round(loc.getZ());

        // Snap yaw to nearest 90 degrees (0, 90, 180, 270)
        float yaw = loc.getYaw();
        yaw = yaw % 360;
        if (yaw < 0) yaw += 360;
        int cardinal = java.lang.Math.round(yaw / 90.0f) * 90;
        float snappedYaw = cardinal % 360;

        // Snap pitch to 0 (horizontal)
        float snappedPitch = 0.0f;

        // Find players standing on this ship's shulkers BEFORE moving
        // Map: player -> the shulker they're standing on
        Map<Player, Shulker> playersOnDeck = new HashMap<>();
        String shipTag = ShipTags.shipTag(this.id);

        for (Player player : loc.getWorld().getPlayers()) {
            if (player.getLocation().distance(loc) > 32) continue;

            // Check nearby entities for shulkers belonging to this ship
            for (Entity nearby : player.getNearbyEntities(2, 2, 2)) {
                if (!(nearby instanceof Shulker shulker)) continue;
                if (!shulker.getScoreboardTags().contains(shipTag)) continue;

                // Check if player is standing on this shulker
                org.bukkit.util.BoundingBox playerBox = player.getBoundingBox();
                org.bukkit.util.BoundingBox shulkerBox = shulker.getBoundingBox();

                double playerFeetY = playerBox.getMinY();
                double shulkerTopY = shulkerBox.getMaxY();

                boolean withinHorizontalBounds =
                    playerBox.getMinX() < shulkerBox.getMaxX() &&
                    playerBox.getMaxX() > shulkerBox.getMinX() &&
                    playerBox.getMinZ() < shulkerBox.getMaxZ() &&
                    playerBox.getMaxZ() > shulkerBox.getMinZ();

                boolean onTop = playerFeetY >= shulkerTopY - 0.1 && playerFeetY <= shulkerTopY + 0.3;

                if (withinHorizontalBounds && onTop) {
                    playersOnDeck.put(player, shulker);
                    break; // Player can only stand on one shulker
                }
            }
        }

        // Set the new aligned location
        Location aligned = new Location(loc.getWorld(), x, y, z, snappedYaw, snappedPitch);
        vehicle.teleport(aligned);

        // Update collision positions immediately so shulkers move with the ship
        updateCollisionPositions();

        // Teleport players to their shulker's new position + 0.1 Y offset
        for (Map.Entry<Player, Shulker> entry : playersOnDeck.entrySet()) {
            Player player = entry.getKey();
            Shulker shulker = entry.getValue();
            Location shulkerLoc = shulker.getLocation();
            Location playerLoc = player.getLocation();
            // Place player at shulker's X/Z, on top of shulker's bounding box + 0.1
            player.teleport(new Location(
                shulkerLoc.getWorld(),
                shulkerLoc.getX(),
                shulker.getBoundingBox().getMaxY() + 0.1,
                shulkerLoc.getZ(),
                playerLoc.getYaw(),
                playerLoc.getPitch()
            ));
        }

        // Reset velocity and rotation
        vehicle.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        physics.currentSpeed = 0.0f;
        physics.currentRotationVelocity = 0.0f;
        physics.currentYVelocity = 0.0f;
        physics.collisionForce.set(0, 0, 0);
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

        // Get dispenser inventory
        Inventory inv = storages.get(cannon.dispenserBlockIndex);
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
            case LINGERING_POTION:
                ThrownPotion potion = world.spawn(spawnLoc, ThrownPotion.class);
                potion.setItem(item.clone());
                potion.setVelocity(velocity);
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
        world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 1.5f);
    }
}
