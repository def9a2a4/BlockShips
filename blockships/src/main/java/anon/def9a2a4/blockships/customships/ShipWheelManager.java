package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.ShipConfig;
import anon.def9a2a4.blockships.ShipCustomization;
import anon.def9a2a4.blockships.ship.CollisionBox;
import anon.def9a2a4.blockships.ship.ShipInstance;
import anon.def9a2a4.blockships.ShipModel;
import anon.def9a2a4.blockships.ShipRegistry;
import anon.def9a2a4.blockships.ShipTags;
import anon.def9a2a4.blockships.ShipWorldData;
import anon.def9a2a4.blockships.blockconfig.BlockConfigManager;
import anon.def9a2a4.blockships.blockconfig.BlockProperties;
import anon.def9a2a4.blockships.blockconfig.ShipDetector;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.bukkit.entity.Shulker;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;

/**
 * Manages placed ship wheels in the world.
 * Handles the assembly/disassembly of custom ships from ship wheel blocks.
 */
public class ShipWheelManager {
    private static final String WHEELS_FILE = "ship_wheels.yml";

    private final JavaPlugin plugin;
    private final Map<String, ShipWheelData> placedWheels;  // Location key -> wheel data

    // Particle colors for ship detection visualization
    private static final Color PARTICLE_WHITE = Color.fromRGB(255, 255, 255);
    private static final Color PARTICLE_ORANGE = Color.fromRGB(255, 165, 0);
    private static final Color PARTICLE_RED = Color.fromRGB(255, 50, 50);

    public ShipWheelManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.placedWheels = new HashMap<>();
    }

    // ===== Persistence =====

    /**
     * Saves all ship wheels to ship_wheels.yml.
     */
    public void saveAll() {
        File wheelsFile = new File(plugin.getDataFolder(), WHEELS_FILE);
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();

        List<Map<String, Object>> wheelList = new ArrayList<>();
        for (ShipWheelData data : placedWheels.values()) {
            wheelList.add(data.toMap());
        }
        config.set("wheels", wheelList);

        try {
            config.save(wheelsFile);
            plugin.getLogger().info("Saved " + wheelList.size() + " ship wheels to " + WHEELS_FILE);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save ship wheels: " + e.getMessage());
        }
    }

    /**
     * Loads all ship wheels from ship_wheels.yml.
     */
    public void loadAll() {
        File wheelsFile = new File(plugin.getDataFolder(), WHEELS_FILE);
        if (!wheelsFile.exists()) {
            return;  // No wheels to load
        }

        org.bukkit.configuration.file.YamlConfiguration config;
        try {
            config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(wheelsFile);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load ship wheels file: " + e.getMessage());
            return;
        }

        List<Map<?, ?>> wheelList = config.getMapList("wheels");
        int loaded = 0;
        int failed = 0;

        for (Map<?, ?> map : wheelList) {
            try {
                @SuppressWarnings("unchecked")
                ShipWheelData data = ShipWheelData.fromMap((Map<String, Object>) map);
                if (data != null) {
                    placedWheels.put(locationKey(data.getBlockLocation()), data);
                    loaded++;
                } else {
                    failed++;  // World doesn't exist
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load ship wheel: " + e.getMessage());
                failed++;
            }
        }

        plugin.getLogger().info("Loaded " + loaded + " ship wheels" + (failed > 0 ? " (" + failed + " failed)" : ""));
    }

    /**
     * Creates a stable string key from a Location using block coordinates.
     * Avoids floating-point precision issues with Location as HashMap key.
     */
    private static String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    /**
     * Registers a ship wheel at the given location with its facing direction.
     * The block itself should already be placed by the event handler.
     */
    public boolean placeWheel(Location location, BlockFace facing) {
        // Create and store wheel data
        ShipWheelData wheelData = new ShipWheelData(location, facing);
        placedWheels.put(locationKey(location), wheelData);
        return true;
    }

    /**
     * Removes a ship wheel at the given location.
     */
    public void removeWheel(Location location) {
        ShipWheelData wheelData = placedWheels.remove(locationKey(location));
        if (wheelData != null) {
            // If assembled, destroy the ship too
            if (wheelData.isAssembled()) {
                ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
                if (ship != null) {
                    ship.destroy();
                }
            }
        }
    }

    /**
     * Breaks a ship wheel block after the ship has already been disassembled.
     * Removes from tracking, drops the wheel item, and sets block to air.
     * Use this instead of removeWheel() when the ship is already destroyed/disassembled.
     */
    public void breakWheelBlock(Location location) {
        ShipWheelData wheelData = placedWheels.remove(locationKey(location));
        if (wheelData == null) return;

        // Drop ship wheel item
        org.bukkit.World world = location.getWorld();
        if (world != null && plugin instanceof BlockShipsPlugin bsp) {
            ItemStack wheelItem = bsp.getDisplayShip().createShipWheelItem();
            world.dropItemNaturally(location.clone().add(0.5, 0.5, 0.5), wheelItem);
            location.getBlock().setType(Material.AIR);
        }

        saveAll();
    }

    /**
     * Gets wheel data at a location, if it exists.
     */
    public ShipWheelData getWheelAt(Location location) {
        return placedWheels.get(locationKey(location));
    }

    /**
     * Gets all placed wheels.
     */
    public Collection<ShipWheelData> getWheels() {
        return placedWheels.values();
    }

    /**
     * Gets wheel data by assembled ship UUID.
     * Used to find the wheel when clicking on a ship's colliders.
     */
    public ShipWheelData getWheelByShipUUID(UUID shipUUID) {
        for (ShipWheelData wheelData : placedWheels.values()) {
            if (shipUUID.equals(wheelData.getAssembledShipUUID())) {
                return wheelData;
            }
        }
        return null;
    }

    /**
     * Updates the tracked location of a wheel after disassembly at a new position.
     * Removes old map entry and adds new one.
     */
    private void updateWheelLocation(ShipWheelData wheelData, Location newLocation, BlockFace newFacing) {
        Location oldLocation = wheelData.getBlockLocation();
        placedWheels.remove(locationKey(oldLocation));
        wheelData.updateBlockLocation(newLocation, newFacing);
        placedWheels.put(locationKey(newLocation), wheelData);
    }

    /**
     * Assembles a custom ship from blocks around the wheel.
     */
    public boolean assembleShip(Player player, ShipWheelData wheelData) {
        if (wheelData.isAssembled()) {
            player.sendMessage("§cThis wheel already has an assembled ship!");
            return false;
        }

        Location wheelLoc = wheelData.getBlockLocation();

        // Scan the structure
        ShipModel model = BlockStructureScanner.scanStructure(wheelLoc, wheelData.getFacing());
        if (model == null || model.parts.isEmpty()) {
            player.sendMessage("§cNo valid ship structure found!");
            return false;
        }

        // Set spawn location yaw to match wheel facing direction
        // This ensures the vehicle spawns facing the correct direction
        float assemblyYaw = BlockStructureScanner.blockFaceToYaw(wheelData.getFacing());
        wheelLoc.setYaw(assemblyYaw);

        // Create a ShipInstance from the model BEFORE removing blocks
        // This allows us to transfer leads while the fence blocks (and LeashHitch) still exist
        // shipType is "custom" for custom block ships
        // Pass empty customization - custom ships use scanned blocks as-is (no wood type replacement)
        // Display/collision offsets are applied inside ShipInstance based on config
        ShipInstance ship = new ShipInstance(plugin, "custom", model, wheelLoc, ShipCustomization.empty());
        ship.sourceModel = model;  // Store the model for disassembly

        // Transfer leads from world to ship's leadable shulkers BEFORE removing blocks
        // This must happen while the fence blocks still exist (LeashHitch attached to fence)
        transferLeadsToShip(ship, model, wheelLoc);

        // NOW remove the blocks from the world (after leads are transferred)
        BlockStructureScanner.removeBlocks(wheelLoc, model);

        // Register the ship
        ShipRegistry.register(ship);

        // Register with per-world storage for chunk recovery
        if (plugin instanceof BlockShipsPlugin bsp) {
            Location loc = ship.vehicle.getLocation();
            ShipWorldData shipWorldData = bsp.getDisplayShip().getShipWorldData();
            shipWorldData.saveShipMetadata(ship);
            shipWorldData.addToChunkIndex(loc.getWorld(), ship.id,
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
            shipWorldData.saveAllChunkIndices();
        }

        // Link the wheel to the ship
        wheelData.setAssembledShipUUID(ship.id);

        // Tag the ship wheel collider (block at dx=0, dy=0, dz=0 relative to wheel origin)
        // This allows opening the menu by right-clicking the wheel collider
        tagShipWheelCollider(ship, wheelLoc);

        player.sendMessage("§aShip assembled! Found " + model.parts.size() + " blocks.");
        return true;
    }

    /**
     * Aligns a ship to the block grid (position and rotation).
     */
    public boolean alignToGrid(Player player, ShipWheelData wheelData) {
        if (!wheelData.isAssembled()) {
            player.sendMessage("§cNo ship to align! Assemble a ship first.");
            return false;
        }

        ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
        if (ship == null) {
            player.sendMessage("§cShip not found!");
            wheelData.setAssembledShipUUID(null);
            return false;
        }

        // Align the ship
        ship.alignToGrid();

        player.sendMessage("§aShip aligned to grid!");
        return true;
    }

    /**
     * Disassembles a ship back into blocks.
     */
    public boolean disassembleShip(@Nullable Player player, ShipWheelData wheelData) {
        return disassembleShip(player, wheelData, false);
    }

    /**
     * Disassembles a ship back into blocks.
     *
     * @param player The player disassembling the ship
     * @param wheelData The ship wheel data
     * @param force If true, destroys fragile blocks (grass, flowers, etc.) in the way.
     *              Hard conflicts will cause those ship blocks to be lost.
     * @return true if disassembly succeeded, false otherwise
     */
    public boolean disassembleShip(@Nullable Player player, ShipWheelData wheelData, boolean force) {
        if (!wheelData.isAssembled()) {
            if (player != null) player.sendMessage("§cNo ship to disassemble!");
            return false;
        }

        ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
        if (ship == null) {
            if (player != null) player.sendMessage("§cShip not found!");
            wheelData.setAssembledShipUUID(null);
            return false;
        }

        // Get the ship's model
        ShipModel model = ship.sourceModel;
        if (model == null) {
            if (player != null) player.sendMessage("§cCannot disassemble this ship (no source model)!");
            return false;
        }

        // Align to grid first
        ship.alignToGrid();

        // Get the ship's current location and rotation
        Location shipLoc = ship.vehicle.getLocation();
        float currentYaw = shipLoc.getYaw();

        // Validate placement area (with rotation)
        BlockStructureScanner.PlacementConflicts conflicts =
            BlockStructureScanner.validatePlacementArea(shipLoc, model, currentYaw);

        if (!conflicts.isClear() && !force) {
            // Store conflict info for force option
            wheelData.setLastDisassemblyConflicts(conflicts);

            if (player != null) {
                player.sendMessage("§cCannot disassemble! Blocks would conflict with existing terrain.");
                player.sendMessage("§eUse Force Disassemble to proceed anyway.");
                if (conflicts.fragile > 0) {
                    player.sendMessage("§e  - " + conflicts.fragile + " fragile block(s) will be destroyed");
                }
                if (conflicts.hard > 0) {
                    player.sendMessage("§c  - " + conflicts.hard + " ship block(s) will be lost");
                }
            }
            return false;
        }

        // Clear conflict state on successful disassembly attempt
        wheelData.setLastDisassemblyConflicts(null);

        // Sync current storage inventories back to model before placing blocks
        Map<Integer, Inventory> currentStorages = ship.storages;
        for (Map.Entry<Integer, Inventory> entry : currentStorages.entrySet()) {
            int blockIndex = entry.getKey();
            Inventory inv = entry.getValue();

            if (blockIndex >= 0 && blockIndex < model.parts.size()) {
                ShipModel.ModelPart part = model.parts.get(blockIndex);
                List<Map<String, Object>> itemsData = new ArrayList<>();

                for (int slot = 0; slot < inv.getSize(); slot++) {
                    ItemStack item = inv.getItem(slot);
                    if (item != null && item.getType() != Material.AIR) {
                        Map<String, Object> itemData = new HashMap<>();
                        itemData.put("slot", slot);
                        itemData.put("item", item.serializeAsBytes());
                        itemsData.add(itemData);
                    }
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> yaml = (Map<String, Object>) part.rawYaml;
                yaml.put("container_items", itemsData);
            }
        }

        // Place the blocks back (with rotation)
        BlockStructureScanner.placeBlocks(shipLoc, model, currentYaw, force);

        // Transfer leads from ship's shulkers to fence blocks before destroying ship
        transferLeadsFromShip(ship, model, shipLoc, currentYaw);

        // Update wheel tracking to new location
        float rotationDelta = currentYaw - model.assemblyYaw;
        BlockFace newFacing = BlockStructureScanner.rotateBlockFace(wheelData.getFacing(), rotationDelta);
        Location newWheelLocation = shipLoc.getBlock().getLocation();
        updateWheelLocation(wheelData, newWheelLocation, newFacing);

        // Destroy the ship
        ship.destroy();
        ShipRegistry.unregister(ship);

        // Remove ship from per-world storage (delete file and chunk index)
        if (plugin instanceof BlockShipsPlugin bsp) {
            org.bukkit.World world = shipLoc.getWorld();
            if (world != null) {
                bsp.getDisplayShip().getShipWorldData().removeShip(world, ship.id);
                bsp.getDisplayShip().getShipWorldData().saveAllChunkIndices();
            }
        }

        // Unlink from wheel
        wheelData.setAssembledShipUUID(null);

        if (player != null) player.sendMessage("§aShip disassembled!");
        return true;
    }

    /**
     * Tags the ship wheel's collision shulker so it can be identified when clicked.
     * The wheel block is at position (0,0,0) relative to the ship origin.
     */
    private void tagShipWheelCollider(ShipInstance ship, Location wheelLoc) {
        // Find the collision shulker at the wheel position (block index 0 should be the wheel)
        // We need to iterate through colliders to find the one at position (0,0,0)
        for (CollisionBox collider : ship.colliders) {
            // Check if this collider is at the wheel position
            // The collider's base transformation should have translation (0,0,0) for the wheel
            org.joml.Vector3f translation = new org.joml.Vector3f();
            collider.base.getTranslation(translation);

            if (Math.abs(translation.x) < 0.01f && Math.abs(translation.y) < 0.01f && Math.abs(translation.z) < 0.01f) {
                // This is the wheel collider - tag it with the wheel location
                org.bukkit.entity.Shulker shulker = collider.entity;
                if (shulker != null && shulker.isValid()) {
                    shulker.addScoreboardTag(ShipTags.wheelTag(wheelLoc));
                }
                break;
            }
        }
    }

    /**
     * Transfers leads from world entities to ship's leadable shulkers.
     * Called during assembly BEFORE blocks are removed, while fence blocks still exist.
     *
     * @param ship The newly created ship instance
     * @param model The ship model containing leadable block info
     * @param wheelLoc The wheel location (origin for block positions)
     */
    private void transferLeadsToShip(ShipInstance ship, ShipModel model, Location wheelLoc) {
        // Iterate through model parts to find leadable blocks
        for (int blockIndex = 0; blockIndex < model.parts.size(); blockIndex++) {
            ShipModel.ModelPart part = model.parts.get(blockIndex);

            // Check if this block is leadable
            if (!part.rawYaml.containsKey("leadable") || !Boolean.TRUE.equals(part.rawYaml.get("leadable"))) {
                continue;
            }

            // Find the shulker for this block index
            Shulker targetShulker = findShulkerByBlockIndex(ship, blockIndex);
            if (targetShulker == null) {
                continue;
            }

            // Calculate the fence block's world location
            org.joml.Vector3f pos = new org.joml.Vector3f();
            part.local.getTranslation(pos);
            Location fenceLoc = wheelLoc.clone().add(pos.x, pos.y, pos.z);

            // Find entities leashed to this fence block (via LeashHitch) and transfer them
            List<org.bukkit.entity.Entity> leashedEntities = findEntitiesLeashedToFence(fenceLoc);

            for (org.bukkit.entity.Entity entity : leashedEntities) {
                // Transfer the lead to the shulker (entity is guaranteed to be Leashable from findEntitiesLeashedToFence)
                io.papermc.paper.entity.Leashable leashable = (io.papermc.paper.entity.Leashable) entity;
                leashable.setLeashHolder(targetShulker);
            }
        }
    }

    /**
     * Finds all entities that are leashed to a fence block via LeashHitch.
     * Uses Paper's Leashable interface to support boats, mobs, and other leashable entities.
     *
     * @param fenceLoc The location of the fence block
     * @return List of leashable entities leashed to the fence
     */
    private List<org.bukkit.entity.Entity> findEntitiesLeashedToFence(Location fenceLoc) {
        List<org.bukkit.entity.Entity> leashed = new ArrayList<>();
        if (fenceLoc.getWorld() == null) {
            return leashed;
        }

        Location fenceBlockLoc = fenceLoc.getBlock().getLocation();

        // Search for entities within lead range (10 blocks is Minecraft's lead limit)
        // Use Paper's Leashable interface to support boats, mobs, and other leashable entities
        for (org.bukkit.entity.Entity entity : fenceLoc.getWorld().getNearbyEntities(fenceLoc, 10, 10, 10)) {
            if (entity instanceof io.papermc.paper.entity.Leashable) {
                io.papermc.paper.entity.Leashable leashable = (io.papermc.paper.entity.Leashable) entity;
                if (leashable.isLeashed()) {
                    org.bukkit.entity.Entity holder = leashable.getLeashHolder();
                    if (holder instanceof org.bukkit.entity.LeashHitch) {
                        // Check if the LeashHitch is at this fence block
                        Location hitchLoc = holder.getLocation().getBlock().getLocation();
                        if (hitchLoc.equals(fenceBlockLoc)) {
                            leashed.add(entity);
                        }
                    }
                }
            }
        }

        return leashed;
    }

    /**
     * Finds the collision shulker for a given block index.
     *
     * @param ship The ship instance
     * @param blockIndex The block index to find
     * @return The shulker entity, or null if not found
     */
    private Shulker findShulkerByBlockIndex(ShipInstance ship, int blockIndex) {
        for (CollisionBox collider : ship.colliders) {
            if (collider.blockIndex == blockIndex) {
                return collider.entity;
            }
        }
        return null;
    }

    /**
     * Transfers leads from ship's shulkers to fence blocks (via LeashHitch).
     * Called during disassembly after blocks are placed but before ship is destroyed.
     *
     * @param ship The ship instance being disassembled
     * @param model The ship model containing leadable block info
     * @param shipLoc The ship's current location (used as origin for block positions)
     * @param currentYaw The ship's current yaw rotation
     */
    private void transferLeadsFromShip(ShipInstance ship, ShipModel model, Location shipLoc, float currentYaw) {
        // Calculate rotation delta from assembly orientation
        float rotationDelta = currentYaw - model.assemblyYaw;
        while (rotationDelta < 0) rotationDelta += 360;
        while (rotationDelta >= 360) rotationDelta -= 360;

        // Iterate through colliders to find leadable shulkers with attached entities
        for (CollisionBox collider : ship.colliders) {
            int blockIndex = collider.blockIndex;

            // Check if this block is leadable
            if (blockIndex < 0 || blockIndex >= model.parts.size()) {
                continue;
            }
            ShipModel.ModelPart part = model.parts.get(blockIndex);
            if (!part.rawYaml.containsKey("leadable") || !Boolean.TRUE.equals(part.rawYaml.get("leadable"))) {
                continue;
            }

            Shulker shulker = collider.entity;
            if (shulker == null || !shulker.isValid()) {
                continue;
            }

            // Find entities leashed to this shulker
            List<org.bukkit.entity.Entity> leashedEntities = findEntitiesLeashedTo(shulker);
            if (leashedEntities.isEmpty()) {
                continue;
            }

            // Calculate the fence block's world location (apply rotation like placeBlocks does)
            org.joml.Vector3f pos = new org.joml.Vector3f();
            part.local.getTranslation(pos);
            org.joml.Vector3f rotatedPos = BlockStructureScanner.rotatePosition(pos, rotationDelta);
            Location fenceLoc = shipLoc.clone().add(rotatedPos.x, rotatedPos.y, rotatedPos.z);

            // Spawn LeashHitch at the fence block
            org.bukkit.entity.LeashHitch hitch = fenceLoc.getWorld().spawn(
                fenceLoc.getBlock().getLocation().add(0.5, 0.5, 0.5),
                org.bukkit.entity.LeashHitch.class
            );

            // Transfer each leashed entity to the LeashHitch
            for (org.bukkit.entity.Entity entity : leashedEntities) {
                // Entity is guaranteed to be Leashable from findEntitiesLeashedTo
                ((io.papermc.paper.entity.Leashable) entity).setLeashHolder(hitch);
            }
        }
    }

    /**
     * Finds all entities that are leashed to the given entity (shulker).
     * Uses Paper's Leashable interface to support boats, mobs, and other leashable entities.
     *
     * @param holder The entity that might be holding leads
     * @return List of leashable entities leashed to the holder
     */
    public List<org.bukkit.entity.Entity> findEntitiesLeashedTo(org.bukkit.entity.Entity holder) {
        List<org.bukkit.entity.Entity> leashed = new ArrayList<>();
        if (holder.getWorld() == null) {
            return leashed;
        }

        // Search for entities within lead range (10 blocks is Minecraft's lead limit)
        // Use Paper's Leashable interface to support boats, mobs, and other leashable entities
        for (org.bukkit.entity.Entity entity : holder.getWorld().getNearbyEntities(holder.getLocation(), 10, 10, 10)) {
            if (entity instanceof io.papermc.paper.entity.Leashable) {
                io.papermc.paper.entity.Leashable leashable = (io.papermc.paper.entity.Leashable) entity;
                if (leashable.isLeashed() && holder.equals(leashable.getLeashHolder())) {
                    leashed.add(entity);
                }
            }
        }

        return leashed;
    }

    /**
     * Detects and previews which blocks would be included in a ship.
     * Shows block count, total weight, and spawns particles to visualize the ship.
     */
    public boolean detectShip(Player player, ShipWheelData wheelData) {
        return detectShip(player, wheelData, true);
    }

    /**
     * Detects and previews which blocks would be included in a ship.
     * Shows block count, total weight, and optionally spawns particles to visualize the ship.
     *
     * @param player The player to send messages to
     * @param wheelData The ship wheel data
     * @param showParticles Whether to show particle visualization
     */
    public boolean detectShip(Player player, ShipWheelData wheelData, boolean showParticles) {
        Location wheelLoc = wheelData.getBlockLocation();

        // Cancel any existing particle task
        wheelData.cancelParticleTask();

        // If ship is assembled, get stats from the ship instance instead of world detection
        // (world blocks are removed when ship is assembled)
        if (wheelData.isAssembled()) {
            ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
            if (ship != null && ship.vehicle != null && ship.vehicle.isValid()) {
                int blockCount = ship.model.parts.size();
                double currentHealth = ship.vehicle.getHealth();
                double maxHealth = ship.vehicle.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue();
                wheelData.setLastDetectedStats(blockCount, ship.model.totalWeight, (int)maxHealth);
                wheelData.setLastHealth(currentHealth, maxHealth);
                // Store buoyancy data from ship model
                wheelData.lastCenterOfVolumeY = ship.model.centerOfVolume.y();
                wheelData.lastMinY = ship.model.minY;
                wheelData.lastSurfaceOffset = ship.model.waterFloatOffset;
                return true;
            }
        }

        // Get max ship size from config
        int maxShipSize = ((BlockShipsPlugin) plugin).getConfig().getInt("custom-ships.max-ship-size", 1000);

        // Run ship detection
        ShipDetector detector = new ShipDetector(maxShipSize);
        ShipDetector.ShipDetectionResult result = detector.detectShipDetailed(wheelLoc);

        if (!result.isSuccess()) {
            // Detection failed - ship too large or other error
            player.sendMessage("§c" + result.getMessage());
            if (result.getBlockCount() > 0) {
                player.sendMessage("§7Try breaking it into smaller sections");
            }
            return false;
        }

        Set<Location> shipBlocks = result.getBlocks();
        if (shipBlocks == null || shipBlocks.isEmpty()) {
            player.sendMessage("§cNo valid blocks found for ship!");
            return false;
        }

        // Categorize blocks into regular blocks and seat blocks
        // Driver seat is always behind the wheel, all detected seat blocks are passenger seats
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        Set<Location> regularBlocks = new HashSet<>();
        Set<Location> seatBlocks = new HashSet<>();

        // Calculate driver seat position (behind the wheel based on facing direction)
        Location driverSeat = wheelLoc.clone();
        BlockFace facing = wheelData.getFacing();
        // Move one block behind the wheel (opposite of facing direction)
        driverSeat.add(facing.getOppositeFace().getModX(), 0, facing.getOppositeFace().getModZ());

        for (Location loc : shipBlocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());

            if (props.isSeat()) {
                // All detected seat blocks are passenger seats
                seatBlocks.add(loc);
            } else {
                regularBlocks.add(loc);
            }
        }

        // Calculate total weight and counts
        int totalWeight = calculateTotalWeight(shipBlocks);
        int blockCount = shipBlocks.size();
        int seatCount = seatBlocks.size() + (driverSeat != null ? 1 : 0);

        // Calculate density to determine if this is an airship
        int weightedBlockCount = countWeightedBlocks(shipBlocks);
        float meanDensity = weightedBlockCount > 0 ? (float) totalWeight / weightedBlockCount : 0;
        ShipConfig config = ShipConfig.load(plugin, "custom");
        boolean isAirship = meanDensity < config.airDensity;

        // Send success messages
        player.sendMessage("§aShip detected successfully!");
        player.sendMessage("§7Blocks: §f" + blockCount + " §7/ §f" + maxShipSize);
        player.sendMessage("§7Total Weight: §f" + totalWeight);
        player.sendMessage("§7Density: §f" + String.format("%.2f", meanDensity) + " §7(air: " + config.airDensity + ", water: " + config.waterDensity + ")");
        if (isAirship) {
            player.sendMessage("§b✦ This ship is lighter than air - it will fly as an AIRSHIP!");
            player.sendMessage("§7  Controls: Space to ascend, Sprint to descend");
        }
        if (seatCount > 0) {
            int passengerCount = seatBlocks.size();
            player.sendMessage("§7Seats: §f" + seatCount + " §7(1 driver + " + passengerCount + " passengers)");
        } else {
            player.sendMessage("§7Seats: §c0 §7(default seat at wheel will be used)");
        }

        // Store detected blocks and stats for Ship Info display
        int positiveWeight = calculatePositiveWeight(shipBlocks);
        wheelData.setLastDetectedBlocks(shipBlocks);
        wheelData.setLastDetectedStats(blockCount, totalWeight, positiveWeight);
        wheelData.setLastDetectedBlockCategories(regularBlocks, seatBlocks, driverSeat);

        // Calculate and store buoyancy data for Ship Info display
        calculateAndStoreBuoyancyData(wheelData, shipBlocks, totalWeight, weightedBlockCount);

        if (showParticles) {
            player.sendMessage("§7(Showing particles for 5 seconds...)");

            // Calculate and spawn waterline visualization shulker
            spawnWaterlineShulker(wheelData, shipBlocks, totalWeight);

            // Start particle visualization
            startParticleVisualization(wheelData);
        }

        return true;
    }

    /**
     * Calculate the total weight of all blocks in the ship.
     */
    private int calculateTotalWeight(Set<Location> blocks) {
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        int totalWeight = 0;

        for (Location loc : blocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());
            totalWeight += props.getWeight();
        }

        return totalWeight;
    }

    /**
     * Count blocks that have a defined weight (used for density calculation).
     */
    private int countWeightedBlocks(Set<Location> blocks) {
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        int count = 0;

        for (Location loc : blocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());
            if (props.hasWeight()) {
                count++;
            }
        }

        return count;
    }

    /**
     * Calculate the sum of positive weights (used for health calculation).
     * Blocks with negative or zero weight contribute nothing to health.
     */
    private int calculatePositiveWeight(Set<Location> blocks) {
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        int positiveWeight = 0;

        for (Location loc : blocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());
            if (props.hasWeight()) {
                int w = props.getWeight();
                if (w > 0) {
                    positiveWeight += w;
                }
            }
        }

        return positiveWeight;
    }

    /**
     * Calculate and store buoyancy data (centerOfVolumeY, minY, surfaceOffset) for Ship Info display.
     */
    private void calculateAndStoreBuoyancyData(ShipWheelData wheelData, Set<Location> shipBlocks, int totalWeight, int weightedBlockCount) {
        Location wheelLoc = wheelData.getBlockLocation();
        BlockConfigManager configManager = BlockConfigManager.getInstance();

        float minY = Float.MAX_VALUE;
        float sumY = 0;
        int weightedCount = 0;

        for (Location loc : shipBlocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());

            float blockY = (float) (loc.getY() - wheelLoc.getY());
            if (blockY < minY) minY = blockY;

            if (props.hasWeight()) {
                weightedCount++;
                sumY += blockY;
            }
        }

        if (minY == Float.MAX_VALUE) minY = 0;
        float centerOfVolumeY = weightedCount > 0 ? sumY / weightedCount : 0;

        // Calculate surface offset using same formula as ShipPhysics
        float surfaceOffset;
        if (weightedCount > 0) {
            float meanDensity = (float) totalWeight / weightedCount;
            ShipConfig config = ShipConfig.load(plugin, "custom");
            float airDensity = config.airDensity;
            float waterDensity = config.waterDensity;

            float t = (meanDensity - airDensity) / (waterDensity - airDensity);
            float referenceY = minY;
            float waterlineY = referenceY + t * (centerOfVolumeY - referenceY);
            surfaceOffset = -waterlineY;
        } else {
            surfaceOffset = 0;
        }

        wheelData.lastCenterOfVolumeY = centerOfVolumeY;
        wheelData.lastMinY = minY;
        wheelData.lastSurfaceOffset = surfaceOffset;
    }

    /**
     * Spawns a glowing shulker at the predicted waterline position.
     * The shulker is invisible, invincible, has no AI/gravity, and glows.
     */
    private void spawnWaterlineShulker(ShipWheelData wheelData, Set<Location> shipBlocks, int totalWeight) {
        Location wheelLoc = wheelData.getBlockLocation();

        // Calculate ship bounds and weighted block count (same logic as BlockStructureScanner)
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;
        float sumY = 0;
        int weightedBlockCount = 0;

        for (Location loc : shipBlocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());

            float blockY = (float) (loc.getY() - wheelLoc.getY());
            if (blockY < minY) minY = blockY;
            if (blockY > maxY) maxY = blockY;

            if (props.hasWeight()) {
                weightedBlockCount++;
                sumY += blockY;
            }
        }

        // Default bounds if no blocks
        if (minY == Float.MAX_VALUE) minY = 0;
        if (maxY == Float.MIN_VALUE) maxY = 0;

        // Calculate center of volume Y
        float centerOfVolumeY = weightedBlockCount > 0 ? sumY / weightedBlockCount : 0;

        // Calculate mean density
        if (weightedBlockCount == 0) {
            // No weighted blocks - no waterline to show
            return;
        }
        float meanDensity = (float) totalWeight / weightedBlockCount;

        // Load air/water density from config
        ShipConfig config = ShipConfig.load(plugin, "custom");
        float airDensity = config.airDensity;
        float waterDensity = config.waterDensity;

        // Check if this would be an airship (lighter than air)
        if (meanDensity < airDensity) {
            // Airship - don't show waterline
            return;
        }

        // Calculate waterline Y using interpolation (same formula as ShipInstance)
        // Using minY - 1 so very light ships (density near 0) float above water
        float t = (meanDensity - airDensity) / (waterDensity - airDensity);
        float referenceY = minY;  // One block below ship bottom
        float waterlineY = referenceY + t * (centerOfVolumeY - referenceY);

        // Spawn location: wheel position + waterline offset
        Location shulkerLoc = wheelLoc.clone().add(0.5, waterlineY, 0.5);

        // Spawn the glowing shulker
        Shulker shulker = wheelLoc.getWorld().spawn(shulkerLoc, Shulker.class, s -> {
            s.setInvisible(true);
            s.setInvulnerable(true);
            s.setAI(false);
            s.setGravity(false);
            s.setGlowing(true);
            s.setSilent(true);
            s.setPersistent(false);  // Don't save to world
            s.setCollidable(false);
            s.setPeek(0.0f);  // Closed shell
            // Set scale to 0.25 (quarter size)
            var scaleAttr = s.getAttribute(org.bukkit.attribute.Attribute.SCALE);
            if (scaleAttr != null) {
                scaleAttr.setBaseValue(0.25);
            }
        });

        wheelData.setWaterlineShulker(shulker);
    }

    /**
     * Starts a repeating task to spawn particles on detected blocks.
     * Runs for 5 seconds (10 iterations × 0.5s).
     * Uses different colors: white for regular blocks, orange for passenger seats, red for driver seat.
     */
    private void startParticleVisualization(ShipWheelData wheelData) {
        Set<Location> regularBlocks = wheelData.getLastDetectedRegularBlocks();
        Set<Location> seatBlocks = wheelData.getLastDetectedSeatBlocks();
        Location driverSeat = wheelData.getLastDetectedDriverSeat();

        // Fallback to all blocks as white if categories aren't set
        if (regularBlocks == null) {
            regularBlocks = wheelData.getLastDetectedBlocks();
            if (regularBlocks == null || regularBlocks.isEmpty()) {
                return;
            }
            seatBlocks = new HashSet<>();
            driverSeat = null;
        }

        final Set<Location> finalRegularBlocks = regularBlocks;
        final Set<Location> finalSeatBlocks = seatBlocks;
        final Location finalDriverSeat = driverSeat;
        final int[] iterationsLeft = {10};  // 10 iterations × 10 ticks = 5 seconds

        BukkitRunnable particleTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (iterationsLeft[0] <= 0) {
                    // Done, clean up
                    wheelData.cancelParticleTask();
                    this.cancel();
                    return;
                }

                // Spawn white particles on regular blocks
                for (Location blockLoc : finalRegularBlocks) {
                    spawnBlockParticles(blockLoc, PARTICLE_WHITE);
                }

                // Spawn orange particles on passenger seat blocks
                for (Location blockLoc : finalSeatBlocks) {
                    spawnBlockParticles(blockLoc, PARTICLE_ORANGE);
                }

                // Spawn red particles on driver seat
                if (finalDriverSeat != null) {
                    spawnBlockParticles(finalDriverSeat, PARTICLE_RED);
                }

                iterationsLeft[0]--;
            }
        };

        // Run every 10 ticks (0.5 seconds)
        wheelData.setParticleTask(particleTask.runTaskTimer(plugin, 0L, 10L));
    }

    /**
     * Spawns particles at the 8 corners of a block with the specified color.
     */
    private void spawnBlockParticles(Location blockLoc, Color color) {
        if (blockLoc.getWorld() == null) {
            return;
        }

        // 8 corners of a block
        double[][] corners = {
            {0, 0, 0},    // Bottom corners
            {1, 0, 0},
            {0, 0, 1},
            {1, 0, 1},
            {0, 1, 0},    // Top corners
            {1, 1, 0},
            {0, 1, 1},
            {1, 1, 1}
        };

        Particle.DustOptions dustOptions = new Particle.DustOptions(color, 1.0f);

        for (double[] corner : corners) {
            Location particleLoc = blockLoc.clone().add(corner[0], corner[1], corner[2]);

            // Spawn colored dust particle
            blockLoc.getWorld().spawnParticle(
                Particle.DUST,
                particleLoc,
                1,                      // Count
                0.05,                   // X spread
                0.05,                   // Y spread
                0.05,                   // Z spread
                0,                      // Speed (not used for DUST)
                dustOptions
            );
        }
    }
}
