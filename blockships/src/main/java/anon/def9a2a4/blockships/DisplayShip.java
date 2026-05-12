package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.customships.ShipWheelData;
import anon.def9a2a4.blockships.customships.ShipWheelManager;
import anon.def9a2a4.blockships.customships.ShipWheelMenu;
import anon.def9a2a4.blockships.util.AttributeCompat;
import anon.def9a2a4.blockships.ship.CollisionBox;
import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DisplayShip implements Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey BANNER_DATA_KEY;
    private final NamespacedKey WOOD_TYPE_KEY;
    private final NamespacedKey SHIP_TYPE_KEY;
    private ShipModel model;
    private ShipPersistence persistence;
    private ShipWorldData shipWorldData;  // Per-world ship storage for chunk-based loading
    private Map<String, ShipModel> shipModels = new HashMap<>();
    private ItemTextureManager textureManager;
    private ItemFactory itemFactory;
    private final List<NamespacedKey> registeredRecipes = new ArrayList<>();
    private final Map<UUID, Long> lastShulkerInteraction = new HashMap<>();  // Cooldown for preventing double-entry
    private final Set<UUID> shipsBeingRecovered = Collections.synchronizedSet(new HashSet<>());  // Prevent concurrent recovery
    private final Set<Long> chunksBeingRecovered = ConcurrentHashMap.newKeySet();  // Track chunks with pending async recovery
    private final org.joml.Vector3f workWheelTranslation = new org.joml.Vector3f();  // Reusable for findWheelCollider

    public DisplayShip(JavaPlugin plugin) {
        this.plugin = plugin;
        this.BANNER_DATA_KEY = new NamespacedKey(plugin, "banner_data");
        this.WOOD_TYPE_KEY = new NamespacedKey(plugin, "wood_type");
        this.SHIP_TYPE_KEY = new NamespacedKey(plugin, "ship_type");
        this.persistence = new ShipPersistence(plugin);
        this.shipWorldData = new ShipWorldData(plugin);
        this.textureManager = new ItemTextureManager(plugin);
    }

    public void initialize() {
        // Extract default model files from JAR if they don't exist
        extractDefaultModelFiles();

        // Load item textures from items.yml
        textureManager.load();

        // Initialize item factory
        itemFactory = new ItemFactory(plugin, textureManager);

        // Load all ship models from config
        loadShipModels();

        // Register recipes for all ship types
        registerRecipes();

        // Load chunk indices from per-world storage
        shipWorldData.loadAllChunkIndices();

        // Check for legacy ships.yml and migrate if needed
        if (persistence.hasLegacyData()) {
            migrateLegacyShipData();
        }

        // Scan loaded chunks for unregistered ships (handles spawn chunks, server restart)
        recoverUnregisteredShips();

        // Start periodic save task for ships in always-loaded chunks (spawn chunks)
        startPeriodicSaveTask();
    }

    /**
     * Scans all loaded chunks for ship entities that aren't registered in ShipRegistry.
     * This handles: spawn chunks that never unload, server restart, pre-migration ships.
     */
    private void recoverUnregisteredShips() {
        int recovered = 0;
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                recovered += recoverUnregisteredShipsInChunk(chunk);
            }
        }
        if (recovered > 0) {
            plugin.getLogger().info("Recovered " + recovered + " unregistered ship(s) on startup");
        }
    }

    /**
     * Scans a chunk for ship root entities that aren't registered and recovers them.
     * @return Number of ships recovered
     */
    private int recoverUnregisteredShipsInChunk(org.bukkit.Chunk chunk) {
        int recovered = 0;

        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ArmorStand)) continue;

            Set<String> tags = entity.getScoreboardTags();
            UUID shipId = null;
            boolean isRoot = false;

            // Look for root tag: "displayship:{uuid}:root"
            for (String tag : tags) {
                if (tag.startsWith(ShipTags.SHIP_PREFIX) && tag.endsWith(":root")) {
                    String idPart = tag.substring(ShipTags.SHIP_PREFIX.length(), tag.length() - 5);
                    try {
                        shipId = UUID.fromString(idPart);
                        isRoot = true;
                        break;
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                }
            }

            if (!isRoot || shipId == null) continue;
            if (ShipRegistry.byId(shipId) != null) continue;  // Already registered

            // Check if this ship is already being recovered (prevent concurrent recovery)
            if (!shipsBeingRecovered.add(shipId)) {
                continue;
            }

            try {
                // Found unregistered ship root - check if we have saved metadata
                ShipPersistence.ShipState state = shipWorldData.loadShipMetadata(chunk.getWorld(), shipId);

                if (state == null) {
                    // No metadata - can't recover without knowing ship type
                    plugin.getLogger().warning("Found orphaned ship root " + shipId + " with no metadata - cannot recover");
                    continue;
                }

                // Load model and recover
                ShipModel model = loadModelForState(state);
                if (model == null) {
                    plugin.getLogger().warning("Failed to load model for orphaned ship " + shipId);
                    continue;
                }

                ShipInstance ship = ShipInstance.fromState(plugin, state, model);
                if (ship == null) {
                    plugin.getLogger().warning("Failed to create ShipInstance for orphaned ship " + shipId);
                    continue;
                }

                if (ship.recoverEntities(chunk)) {
                    ShipRegistry.register(ship);

                    // Ensure ship is in chunk index
                    Location loc = ship.vehicle.getLocation();
                    shipWorldData.addToChunkIndex(chunk.getWorld(), ship.id,
                        loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
                    shipWorldData.saveAllChunkIndices();

                    plugin.getLogger().info("Recovered unregistered ship " + shipId + " in chunk " + chunk.getX() + "," + chunk.getZ());
                    recovered++;
                }
            } finally {
                shipsBeingRecovered.remove(shipId);
            }
        }

        return recovered;
    }

    /**
     * Starts a periodic task to save ship data for ships in always-loaded chunks.
     * Ships in spawn chunks never trigger onChunkUnload, so they need periodic saving.
     */
    private void startPeriodicSaveTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Snapshot ship state on main thread, then write async
                for (ShipInstance ship : ShipRegistry.getAllShips()) {
                    shipWorldData.saveShipMetadataAsync(ship);

                    // Ensure ship is in chunk index (may have been missed or moved)
                    Location loc = ship.vehicle.getLocation();
                    int chunkX = loc.getBlockX() >> 4;
                    int chunkZ = loc.getBlockZ() >> 4;
                    shipWorldData.addToChunkIndex(loc.getWorld(), ship.id, chunkX, chunkZ);
                }
                shipWorldData.saveAllChunkIndicesAsync();
            }
        }.runTaskTimer(plugin, 20L * 60, 20L * 60);  // Every 60 seconds
    }

    public void loadShips() {
        persistence.loadAll();
    }

    public void saveShips() {
        persistence.saveAll();
    }

    /**
     * Migrates ship data from legacy ships.yml to per-world YAML storage.
     * This is a one-time migration that runs when the plugin detects ships.yml exists.
     */
    private void migrateLegacyShipData() {
        plugin.getLogger().info("Migrating ship data from ships.yml to per-world storage...");

        // Load all ships using the old persistence system
        // This spawns them as entities with fresh references
        persistence.loadAll();

        int migrated = 0;
        for (ShipInstance ship : ShipRegistry.getAllShips()) {
            Location loc = ship.vehicle.getLocation();
            World world = loc.getWorld();
            if (world == null) continue;

            // Save ship metadata to per-world storage
            shipWorldData.saveShipMetadata(ship);

            // Add to chunk index
            int chunkX = loc.getBlockX() >> 4;
            int chunkZ = loc.getBlockZ() >> 4;
            shipWorldData.addToChunkIndex(world, ship.id, chunkX, chunkZ);

            migrated++;
        }

        // Save all chunk indices to disk
        shipWorldData.saveAllChunkIndices();

        // Delete the old ships.yml file
        persistence.clear();

        plugin.getLogger().info("Migration complete: " + migrated + " ships migrated to per-world storage");
    }

    public void reload() {
        textureManager.reload();
        loadShipModels();
        registerRecipes();
        plugin.getLogger().info("DisplayShip reloaded with " + shipModels.size() + " ship type(s)");
    }

    private void loadShipModels() {
        shipModels.clear();

        // Iterate through all ship types in config
        var shipsSection = plugin.getConfig().getConfigurationSection("ships");
        if (shipsSection == null) {
            plugin.getLogger().warning("No ships defined in config!");
            return;
        }

        List<String> loadedShips = new ArrayList<>();
        for (String shipType : shipsSection.getKeys(false)) {
            String modelPath = plugin.getConfig().getString("ships." + shipType + ".model-path");
            if (modelPath != null) {
                ShipModel model = ShipModel.fromFile(plugin, modelPath, shipType);
                shipModels.put(shipType, model);
                loadedShips.add(shipType + " (" + model.parts.size() + " blocks)");
            }
        }

        if (!loadedShips.isEmpty()) {
            plugin.getLogger().info("Loaded prefab ships: " + String.join(", ", loadedShips));
        }

        // Set default model to first ship type (for backwards compatibility)
        if (!shipModels.isEmpty()) {
            this.model = shipModels.values().iterator().next();
        }
    }

    private void extractDefaultModelFiles() {
        // Extract items.yml if it doesn't exist
        java.io.File itemsFile = new java.io.File(plugin.getDataFolder(), "items.yml");
        if (!itemsFile.exists()) {
            try {
                plugin.saveResource("items.yml", false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("items.yml not found in JAR resources. You'll need to provide it manually.");
            }
        }

        // Get all unique model files from config
        var shipsSection = plugin.getConfig().getConfigurationSection("ships");
        if (shipsSection == null) return;

        Set<String> modelFiles = new HashSet<>();
        for (String shipType : shipsSection.getKeys(false)) {
            String modelPath = plugin.getConfig().getString("ships." + shipType + ".model-path");
            if (modelPath != null) {
                modelFiles.add(modelPath);
            }
        }

        // Extract each unique model file if it doesn't exist
        for (String modelPath : modelFiles) {
            java.io.File file = new java.io.File(plugin.getDataFolder(), modelPath);
            if (!file.exists()) {
                // Create parent directories if needed
                file.getParentFile().mkdirs();
                try {
                    plugin.saveResource(modelPath, false);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Model file '" + modelPath + "' not found in JAR resources. You'll need to provide it manually.");
                }
            }
        }
    }

    public void shutdown() {
        // Save all ships to per-world storage - entities persist via Minecraft
        shipWorldData.saveAll();
        // Shutdown async I/O executor
        shipWorldData.shutdown();
        // Don't call destroyAll() - let entities persist for recovery on restart
        // Just clear the in-memory registry
        ShipRegistry.clear();
    }

    public ItemFactory getItemFactory() {
        return itemFactory;
    }

    public ItemTextureManager getTextureManager() {
        return textureManager;
    }

    public ShipWorldData getShipWorldData() {
        return shipWorldData;
    }

    // ===== Chunk & Orphan Management =====

    /**
     * Handles chunk unload events.
     * Suspends ship tasks and unregisters from ShipRegistry.
     * Entities persist naturally in the chunk - we just lose our Java references.
     */
    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        org.bukkit.Chunk chunk = event.getChunk();

        // Cancel any pending async recovery for this chunk
        chunksBeingRecovered.remove(chunk.getChunkKey());

        for (ShipInstance ship : ShipRegistry.getShipsInChunk(chunk)) {
            // Snapshot state on main thread, write async via ioExecutor
            // (safe: chunk load also uses ioExecutor, so reads are serialized after writes)
            shipWorldData.saveShipMetadataAsync(ship);

            // Suspend tasks and clear stale references
            ship.suspendForChunkUnload();
            // Unregister from active registry (ship data stays in per-world YAML)
            ShipRegistry.unregister(ship);
            plugin.getLogger().fine("Suspended ship " + ship.id + " for chunk unload at " + chunk.getX() + "," + chunk.getZ());
        }
        // Persist chunk indices (async — serialized behind metadata writes on ioExecutor)
        shipWorldData.saveAllChunkIndicesAsync();
    }

    /**
     * Handles chunk load events.
     * Looks up ships in the chunk from per-world data, creates ShipInstance, and recovers entity references.
     * Also handles incremental recovery for ships that span multiple chunks.
     * File I/O is performed asynchronously to avoid blocking the main thread.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        org.bukkit.Chunk chunk = event.getChunk();
        World world = event.getWorld();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        long chunkKey = chunk.getChunkKey();

        // PART 1: Normal recovery for ships indexed in this chunk (async file I/O)
        List<UUID> shipIds = shipWorldData.getShipsInChunk(world, chunkX, chunkZ);

        // Filter to ships that need recovery
        List<UUID> shipsToRecover = new ArrayList<>();
        for (UUID shipId : shipIds) {
            if (ShipRegistry.byId(shipId) == null && shipsBeingRecovered.add(shipId)) {
                shipsToRecover.add(shipId);
            }
        }

        if (!shipsToRecover.isEmpty()) {
            // Mark chunk as having pending recovery
            chunksBeingRecovered.add(chunkKey);

            // Load all metadata asynchronously in parallel
            List<CompletableFuture<ShipPersistence.ShipState>> futures = shipsToRecover.stream()
                .map(id -> shipWorldData.loadShipMetadataAsync(world, id))
                .toList();

            // When all loads complete, sync back to main thread for entity operations
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    // Run entity recovery on main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        // Check if chunk was unloaded while we were loading
                        if (!chunksBeingRecovered.remove(chunkKey) || !chunk.isLoaded()) {
                            // Chunk unloaded - cleanup and skip recovery
                            shipsToRecover.forEach(shipsBeingRecovered::remove);
                            return;
                        }

                        Set<UUID> failedRecovery = new HashSet<>();

                        for (int i = 0; i < shipsToRecover.size(); i++) {
                            UUID shipId = shipsToRecover.get(i);
                            try {
                                // Re-check chunk state before each ship recovery (chunk could unload mid-loop)
                                if (!chunk.isLoaded()) {
                                    plugin.getLogger().fine("Chunk unloaded during recovery loop - aborting remaining ships");
                                    // Clean up remaining ships (current ship's finally block will handle itself)
                                    for (int j = i + 1; j < shipsToRecover.size(); j++) {
                                        shipsBeingRecovered.remove(shipsToRecover.get(j));
                                    }
                                    return;
                                }

                                // Skip if already registered (another chunk may have recovered it)
                                if (ShipRegistry.byId(shipId) != null) {
                                    continue;
                                }

                                ShipPersistence.ShipState state;
                                try {
                                    state = futures.get(i).join();
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Failed to load metadata for ship " + shipId + ": " + e.getMessage());
                                    failedRecovery.add(shipId);
                                    continue;
                                }
                                if (state == null) {
                                    shipWorldData.removeFromChunkIndex(world, shipId, chunkX, chunkZ);
                                    continue;
                                }

                                // Count entities in this chunk only
                                int entitiesInChunk = countEntitiesInChunk(chunk, shipId);
                                if (entitiesInChunk == 0) {
                                    plugin.getLogger().fine("Ship " + shipId + " not in indexed chunk - removing stale index entry");
                                    shipWorldData.removeFromChunkIndex(world, shipId, chunkX, chunkZ);
                                    continue;
                                }

                                // Load model
                                ShipModel model = loadModelForState(state);
                                if (model == null) {
                                    plugin.getLogger().warning("Could not load model for ship " + shipId + " (type: " + state.shipType + ")");
                                    failedRecovery.add(shipId);
                                    continue;
                                }

                                // Create ShipInstance from state
                                ShipInstance ship = ShipInstance.fromState(plugin, state, model);
                                if (ship == null) {
                                    plugin.getLogger().warning("Failed to create ShipInstance for " + shipId);
                                    failedRecovery.add(shipId);
                                    continue;
                                }

                                ship.setExpectedEntityCount(state.entityCount);

                                // Try to recover entities (re-check chunk state first)
                                if (!chunk.isLoaded()) {
                                    plugin.getLogger().fine("Chunk unloaded before entity recovery for " + shipId);
                                    failedRecovery.add(shipId);
                                    continue;
                                }
                                boolean recovered = ship.recoverEntities(chunk);
                                if (!recovered) {
                                    plugin.getLogger().info("Ship " + shipId + " vehicle not in this chunk - will recover when vehicle chunk loads");
                                    shipWorldData.removeFromChunkIndex(world, shipId, chunkX, chunkZ);
                                    failedRecovery.add(shipId);
                                    continue;
                                }

                                ShipRegistry.register(ship);

                                if (!ship.isRecoveryComplete()) {
                                    plugin.getLogger().info("Ship " + shipId + " partially recovered - waiting for more chunks");
                                } else {
                                    plugin.getLogger().info("Recovered ship " + shipId + " from chunk load at " + chunkX + "," + chunkZ);
                                }
                            } finally {
                                shipsBeingRecovered.remove(shipId);
                            }
                        }

                        // Run orphan cleanup after async recovery completes (only if chunk still loaded)
                        if (chunk.isLoaded()) {
                            processOrphanCleanup(chunk, world, failedRecovery);
                        }
                    });
                });
        }

        // PART 2: Immediate incremental recovery for already-registered incomplete ships
        // (This runs synchronously since it doesn't involve file I/O)
        Set<UUID> processedIncompleteShips = new HashSet<>();
        for (Entity e : chunk.getEntities()) {
            UUID entityShipId = ShipTags.extractShipId(e.getScoreboardTags());
            if (entityShipId == null) continue;

            ShipInstance ship = ShipRegistry.byId(entityShipId);
            // Skip ships being recovered asynchronously to avoid concurrent modification
            if (ship != null && !ship.isRecoveryComplete() && !shipsBeingRecovered.contains(entityShipId) && processedIncompleteShips.add(entityShipId)) {
                ship.collectEntitiesFromChunk(chunk);
            }
        }

        // PART 3: Orphan cleanup - only if no async recovery is pending
        // (If async recovery is pending, orphan cleanup runs after it completes)
        if (shipsToRecover.isEmpty()) {
            processOrphanCleanup(chunk, world, Collections.emptySet());
        }
    }

    /**
     * Processes orphan cleanup for entities in a chunk.
     * Handles unregistered ships by either recovering them or removing orphaned entities.
     */
    private void processOrphanCleanup(org.bukkit.Chunk chunk, World world, Set<UUID> failedRecoveryThisEvent) {
        for (Entity e : chunk.getEntities()) {
            UUID entityShipId = ShipTags.extractShipId(e.getScoreboardTags());
            if (entityShipId == null) continue;

            ShipInstance ship = ShipRegistry.byId(entityShipId);
            if (ship != null) continue; // Already registered

            // Skip if we already tried to recover this ship
            if (failedRecoveryThisEvent.contains(entityShipId)) {
                continue;
            }

            if (!shipWorldData.hasMetadata(world, entityShipId)) {
                // No metadata - truly orphaned, remove entity
                e.remove();
                plugin.getLogger().fine("Removed orphaned entity " + e.getType() + " for deleted ship " + entityShipId);
            } else if (ShipTags.isRoot(e.getScoreboardTags()) && e instanceof ArmorStand) {
                // Found vehicle for unregistered ship with metadata - attempt recovery
                if (shipsBeingRecovered.add(entityShipId)) {
                    try {
                        recoverShipFromVehicle(world, chunk, entityShipId, (ArmorStand) e, failedRecoveryThisEvent);
                    } finally {
                        shipsBeingRecovered.remove(entityShipId);
                    }
                }
            }
        }
    }

    /**
     * Counts ship entities in a chunk without modifying any state.
     */
    private int countEntitiesInChunk(org.bukkit.Chunk chunk, UUID shipId) {
        String shipTagPrefix = ShipTags.shipTag(shipId);
        int count = 0;
        for (Entity e : chunk.getEntities()) {
            for (String tag : e.getScoreboardTags()) {
                if (tag.startsWith(shipTagPrefix)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /**
     * Recovers a ship starting from its vehicle entity.
     * Used when ship entities are found in a chunk that isn't in the chunk index.
     * @param failedRecoveryThisEvent Set to track ships that fail recovery (prevents duplicate attempts)
     */
    private void recoverShipFromVehicle(World world, org.bukkit.Chunk chunk, UUID shipId, ArmorStand vehicle, Set<UUID> failedRecoveryThisEvent) {
        // Load metadata
        ShipPersistence.ShipState state = shipWorldData.loadShipMetadata(world, shipId);
        if (state == null) {
            plugin.getLogger().fine("Ship " + shipId + " has vehicle but no metadata - removing orphan");
            vehicle.remove();
            return;
        }

        // Load model
        ShipModel model = loadModelForState(state);
        if (model == null) {
            plugin.getLogger().warning("Could not load model for ship " + shipId);
            failedRecoveryThisEvent.add(shipId);
            return;
        }

        // Create ShipInstance
        ShipInstance ship = ShipInstance.fromState(plugin, state, model);
        if (ship == null) {
            plugin.getLogger().warning("Failed to create ShipInstance for " + shipId);
            failedRecoveryThisEvent.add(shipId);
            return;
        }

        // Set expected entity count
        ship.setExpectedEntityCount(state.entityCount);

        // Recover entities
        if (!ship.recoverEntities(chunk)) {
            plugin.getLogger().warning("Failed to recover ship " + shipId + " from vehicle - will retry on next chunk load");
            failedRecoveryThisEvent.add(shipId);
            return;
        }

        // Register and update chunk index
        ShipRegistry.register(ship);
        Location loc = vehicle.getLocation();
        shipWorldData.addToChunkIndex(world, shipId, loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        shipWorldData.saveAllChunkIndices();

        if (!ship.isRecoveryComplete()) {
            plugin.getLogger().info("Ship " + shipId + " recovered from moved location - waiting for more chunks");
        } else {
            plugin.getLogger().info("Ship " + shipId + " recovered from moved location at " + chunk.getX() + "," + chunk.getZ());
        }
    }

    /**
     * Loads the appropriate ShipModel for a saved ship state.
     */
    private ShipModel loadModelForState(ShipPersistence.ShipState state) {
        if ("custom".equals(state.shipType) && state.modelData != null) {
            // Custom ship - deserialize model from stored data
            try {
                return ShipModel.fromMap(state.modelData);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load custom ship model: " + e.getMessage());
                return null;
            }
        } else {
            // Prefab ship - load from model file
            String modelPath = plugin.getConfig().getString("ships." + state.shipType + ".model-path");
            if (modelPath == null) {
                return null;
            }
            try {
                return ShipModel.fromFile(plugin, modelPath, state.shipType);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load model file " + modelPath + ": " + e.getMessage());
                return null;
            }
        }
    }

    // ----- Recipe & Item -----
    private void registerRecipes() {
        ItemUtil.registerAllRecipes(plugin, registeredRecipes, itemFactory);
    }

    /**
     * Unlocks all BlockShips recipes for a player.
     * @param player The player to unlock recipes for
     * @return The number of recipes unlocked
     */
    public int unlockAllRecipes(Player player) {
        return ItemUtil.unlockAllRecipesForPlayer(player, registeredRecipes, plugin);
    }

    /**
     * Unlocks recipes when a player completes the configured advancement.
     */
    @EventHandler
    public void onPlayerAdvancementDone(PlayerAdvancementDoneEvent event) {
        String configuredAdvancement = plugin.getConfig().getString("recipe-unlock.advancement", "minecraft:story/smelt_iron");
        if (event.getAdvancement().getKey().toString().equals(configuredAdvancement)) {
            ItemUtil.unlockAllRecipesForPlayer(event.getPlayer(), registeredRecipes, plugin);
        }
    }

    /**
     * Unlocks recipes for players who already have the configured advancement when they join.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String configuredAdvancement = plugin.getConfig().getString("recipe-unlock.advancement", "minecraft:story/smelt_iron");
        NamespacedKey key = NamespacedKey.fromString(configuredAdvancement);
        if (key == null) return;

        Advancement advancement = Bukkit.getAdvancement(key);
        if (advancement == null) return;

        AdvancementProgress progress = event.getPlayer().getAdvancementProgress(advancement);
        if (progress.isDone()) {
            ItemUtil.unlockAllRecipesForPlayer(event.getPlayer(), registeredRecipes, plugin);
        }
    }

    /**
     * Create a ship kit for a specific ship type with custom banner and wood type.
     */
    public static ItemStack createShipKit(ItemStack banner, String woodType, String shipType) {
        return createShipKit(shipType, banner, woodType, Bukkit.getPluginManager().getPlugin("BlockShips"));
    }

    /**
     * Create a ship kit for a specific ship type with custom banner and wood type.
     */
    public static ItemStack createShipKit(String shipType, ItemStack banner, String woodType, org.bukkit.plugin.Plugin plugin) {
        // Check if item is in ships or custom-items section
        String recipePath;
        if (plugin.getConfig().contains("ships." + shipType)) {
            recipePath = "ships." + shipType + ".recipe";
        } else if (plugin.getConfig().contains("custom-items." + shipType)) {
            recipePath = "custom-items." + shipType + ".recipe";
        } else {
            recipePath = "ships." + shipType + ".recipe"; // fallback
        }

        // Get result item type from config (default to PAPER)
        String resultItemName = plugin.getConfig().getString(recipePath + ".result-item", "PAPER");
        Material resultMaterial;
        try {
            resultMaterial = Material.valueOf(resultItemName.toUpperCase());
        } catch (IllegalArgumentException e) {
            resultMaterial = Material.PAPER;
        }

        ItemStack item = new ItemStack(resultMaterial);
        ItemMeta meta = item.getItemMeta();

        // Get result name template from config (default to "Ship Kit")
        String nameTemplate = plugin.getConfig().getString(recipePath + ".result-name", "Ship Kit");

        // Replace template variables ({WOOD_TYPE} and {VARIANT} both map to woodType parameter)
        String displayName = WoodTypeUtil.formatPlaceholders(nameTemplate, woodType);

        meta.displayName(net.kyori.adventure.text.Component.text(displayName)
                .color(net.kyori.adventure.text.format.NamedTextColor.AQUA));

        // Store ship type in persistent data container
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        NamespacedKey shipTypeKey = new NamespacedKey(plugin, "ship_type");
        pdc.set(shipTypeKey, PersistentDataType.STRING, shipType);

        // Build lore with banner and wood type info if provided
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();

        if (banner != null && banner.getType().name().endsWith("_BANNER")) {
            // Store banner data in persistent data container
            try {
                String serialized = serializeBanner(banner);
                NamespacedKey bannerKey = new NamespacedKey(plugin, "banner_data");
                pdc.set(bannerKey, PersistentDataType.STRING, serialized);
            } catch (Exception e) {
                // Can't log from static context, silently continue
            }

            // Add banner color to lore
            String bannerName = ItemUtil.formatMaterialName(banner.getType().name().replace("_BANNER", ""));
            lore.add(net.kyori.adventure.text.Component.text(bannerName + " Banner")
                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }

        if (woodType != null) {
            // Store wood type in persistent data container
            NamespacedKey woodKey = new NamespacedKey(plugin, "wood_type");
            pdc.set(woodKey, PersistentDataType.STRING, woodType);

            // Add wood type to lore
            String woodName = ItemUtil.formatMaterialName(woodType);
            lore.add(net.kyori.adventure.text.Component.text("Material: " + woodName)
                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }

        meta.lore(lore);

        // Apply player head texture if applicable
        if (resultMaterial == Material.PLAYER_HEAD && woodType != null && meta instanceof org.bukkit.inventory.meta.SkullMeta) {
            // Get texture manager from plugin
            Object textureManager = null;
            if (plugin instanceof BlockShipsPlugin blockShipsPlugin) {
                textureManager = blockShipsPlugin.getDisplayShip().getTextureManager();
            }
            ItemUtil.applyPlayerHeadTexture((org.bukkit.inventory.meta.SkullMeta) meta, shipType, woodType, plugin, textureManager);
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeShipKit(String shipType, ItemStack banner, String woodType) {
        return createShipKit(shipType, banner, woodType, plugin);
    }

    /**
     * Creates a ship kit with balloon color support for airships.
     */
    private ItemStack createShipKitWithBalloon(String shipType, ItemStack banner, String woodType, String balloonColor) {
        // Start with the base ship kit
        ItemStack kit = createShipKit(shipType, banner, woodType, plugin);

        // Add balloon color to PDC and lore if provided
        if (balloonColor != null && kit.hasItemMeta()) {
            ItemMeta meta = kit.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();

            // Store balloon color in PDC
            NamespacedKey balloonKey = new NamespacedKey(plugin, "balloon_color");
            pdc.set(balloonKey, PersistentDataType.STRING, balloonColor);

            // Add balloon color to lore
            List<net.kyori.adventure.text.Component> lore = meta.lore();
            if (lore == null) {
                lore = new ArrayList<>();
            }

            String balloonName = ItemUtil.formatMaterialName(balloonColor);
            lore.add(net.kyori.adventure.text.Component.text("Balloon: " + balloonName)
                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));

            meta.lore(lore);
            kit.setItemMeta(meta);
        }

        return kit;
    }

    /**
     * Extracts the balloon color from balloons in the crafting matrix.
     * Looks for custom balloon items and extracts their variant from lore.
     */
    private String extractBalloonColor(CraftingInventory inventory) {
        ItemStack[] matrix = inventory.getMatrix();
        if (matrix == null) return null;

        for (ItemStack item : matrix) {
            if (item == null || !item.hasItemMeta()) continue;

            // Check if this is a balloon by looking at display name
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName()) {
                net.kyori.adventure.text.Component nameComponent = meta.displayName();
                if (nameComponent != null) {
                    String displayName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(nameComponent);
                    if (displayName.endsWith("Ship Balloon")) {
                        // Extract variant from lore
                        String variant = CustomItem.extractVariantFromLore(item);
                        if (variant != null) {
                            return variant;
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isShipKit(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || !stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        // Check if it has the ship type key
        return pdc.has(SHIP_TYPE_KEY, PersistentDataType.STRING);
    }

    // ----- Banner Serialization -----

    private static String serializeBanner(ItemStack banner) throws Exception {
        byte[] bytes = banner.serializeAsBytes();
        return Base64.getEncoder().encodeToString(bytes);
    }

    private ItemStack deserializeBanner(String data) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(data);
        return ItemStack.deserializeBytes(bytes);
    }

    // ----- Interactions -----

    @EventHandler
    public void onCraftShipKit(PrepareItemCraftEvent e) {
        Recipe recipe = e.getRecipe();
        if (recipe == null) return;
        if (!(recipe instanceof Keyed keyedRecipe)) return;

        // Check if this recipe belongs to this plugin
        if (!keyedRecipe.getKey().getNamespace().equals(plugin.getName().toLowerCase())) return;

        // Extract ship type from recipe key (e.g., "smallship_kit_recipe" -> "smallship")
        String recipeKey = keyedRecipe.getKey().getKey();
        if (!recipeKey.endsWith("_kit_recipe")) return;
        String shipType = recipeKey.replace("_kit_recipe", "");

        // Determine config path
        String configPath = plugin.getConfig().contains("ships." + shipType) ? "ships." : "custom-items.";
        String recipePath = configPath + shipType + ".recipe";

        boolean shapeless = plugin.getConfig().getBoolean(recipePath + ".shapeless", false);

        // For shapeless recipes, Bukkit already validated ingredient matching
        // For shaped recipes, use RecipeValidator for variant extraction
        String variant = null;
        ItemStack banner = null;
        String balloonColor = null;

        if (shapeless) {
            // Shapeless: Bukkit handles validation. Extract banner if present.
            banner = RecipeValidator.extractBanner(e.getInventory());
        } else {
            // Shaped: full pattern-based validation
            List<String> pattern = plugin.getConfig().getStringList(recipePath + ".pattern");
            if (pattern.isEmpty() || pattern.size() != 3) {
                e.getInventory().setResult(null);
                return;
            }

            Map<Character, List<RecipeIngredient>> ingredientMap = new HashMap<>();
            var ingredientsSection = plugin.getConfig().getConfigurationSection(recipePath + ".ingredients");
            if (ingredientsSection != null) {
                for (String key : ingredientsSection.getKeys(false)) {
                    List<String> ingredientStrings = plugin.getConfig().getStringList(recipePath + ".ingredients." + key);
                    try {
                        List<RecipeIngredient> ingredients = RecipeIngredient.parseList(ingredientStrings, plugin, this.textureManager);
                        ingredientMap.put(key.charAt(0), ingredients);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Failed to parse ingredient for " + shipType + ": " + ex.getMessage());
                        e.getInventory().setResult(null);
                        return;
                    }
                }
            }

            RecipeValidator.ValidationResult validation = RecipeValidator.validateCrafting(
                    e.getInventory(),
                    pattern,
                    ingredientMap
            );

            if (!validation.isValid()) {
                e.getInventory().setResult(null);
                return;
            }

            banner = RecipeValidator.extractBanner(e.getInventory());
            variant = validation.getPrimaryVariant();

            if (plugin.getConfig().getString("ships." + shipType + ".type", "").equals("airship")) {
                balloonColor = extractBalloonColor(e.getInventory());
            }
        }

        // Create item using unified ItemFactory
        ItemStack result;
        if ("captains_manual".equals(shipType)) {
            // Captain's Manual: create a written book with help content
            result = HelpBookContent.createWrittenBook();
        } else if (plugin.getConfig().contains("custom-items." + shipType)) {
            // Custom items
            result = itemFactory.createItem(shipType, variant, banner);
        } else {
            // Ship kits - use enhanced method with balloon color
            result = createShipKitWithBalloon(shipType, banner, variant, balloonColor);
        }
        e.getInventory().setResult(result);
    }

    @EventHandler
    public void onCraftNonConsumable(org.bukkit.event.inventory.CraftItemEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed)) return;
        if (!keyed.getKey().getNamespace().equals(plugin.getName().toLowerCase())) return;
        String recipeKey = keyed.getKey().getKey();
        if (!recipeKey.equals("captains_manual_kit_recipe")) return;

        // Return the ship wheel to the player after crafting
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item != null && isShipWheel(item)) {
                ItemStack wheelCopy = item.clone();
                wheelCopy.setAmount(1);
                org.bukkit.entity.HumanEntity crafter = event.getWhoClicked();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    crafter.getInventory().addItem(wheelCopy);
                });
                break;
            }
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack hand = e.getItem();
        if (!isShipKit(hand)) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        Location spawnAt = (e.getClickedBlock() != null)
                ? e.getClickedBlock().getLocation().add(0.5, 0.5, 0.5)
                : p.getLocation().add(p.getLocation().getDirection().multiply(2.0));

        // Set spawn location to face player's direction (yaw only, not pitch)
        spawnAt.setYaw(p.getLocation().getYaw());
        spawnAt.setPitch(0);  // Always spawn level

        // Extract ship type, banner data, wood type, and balloon color from ship kit
        String shipType = null;
        ItemStack customBanner = null;
        String woodType = null;
        String balloonColor = null;

        if (hand.hasItemMeta()) {
            PersistentDataContainer pdc = hand.getItemMeta().getPersistentDataContainer();

            // Extract ship type
            if (pdc.has(SHIP_TYPE_KEY, PersistentDataType.STRING)) {
                shipType = pdc.get(SHIP_TYPE_KEY, PersistentDataType.STRING);
            }

            // Extract banner
            if (pdc.has(BANNER_DATA_KEY, PersistentDataType.STRING)) {
                try {
                    String bannerData = pdc.get(BANNER_DATA_KEY, PersistentDataType.STRING);
                    customBanner = deserializeBanner(bannerData);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to deserialize banner data: " + ex.getMessage());
                }
            }

            // Extract wood type
            if (pdc.has(WOOD_TYPE_KEY, PersistentDataType.STRING)) {
                woodType = pdc.get(WOOD_TYPE_KEY, PersistentDataType.STRING);
            }

            // Extract balloon color (for airships)
            NamespacedKey balloonKey = new NamespacedKey(plugin, "balloon_color");
            if (pdc.has(balloonKey, PersistentDataType.STRING)) {
                balloonColor = pdc.get(balloonKey, PersistentDataType.STRING);
            }
        }

        // Get the ship model for this ship type
        ShipModel shipModel = shipModels.get(shipType);
        if (shipModel == null) {
            p.sendMessage(net.kyori.adventure.text.Component.text("Unknown ship type: " + shipType)
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        // Create ship (no boat needed - ArmorStand is the root vehicle)
        // Build customization wrapper
        ShipCustomization customization = ShipCustomization.builder()
                .banner(customBanner)
                .woodType(woodType)
                .balloonColor(balloonColor)
                .textureManager(textureManager)
                .build();

        // Create ship instance (ShipInstance detects airship type from config automatically)
        ShipInstance instance = new ShipInstance(plugin, shipType, shipModel, spawnAt, customization);
        ShipRegistry.register(instance);

        // Register with per-world storage for chunk recovery
        Location loc = instance.vehicle.getLocation();
        shipWorldData.saveShipMetadata(instance);
        shipWorldData.addToChunkIndex(loc.getWorld(), instance.id, loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        shipWorldData.saveAllChunkIndices();

        // Consume one kit
        if (p.getGameMode() != GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
            p.getInventory().setItemInMainHand(hand);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Player player = e.getPlayer();
        Entity vehicle = player.getVehicle();

        // Handle player riding a ship seat shulker (sneak to dismount)
        if (vehicle instanceof Shulker) {
            ShipInstance.dismountPlayer(player);
        }

        // Legacy: handle ArmorStand seats (if any remain from old versions)
        if (vehicle instanceof ArmorStand armorStand) {
            if (armorStand.getScoreboardTags().stream().anyMatch(tag -> tag.contains(":seat"))) {
                ShipInstance inst = ShipRegistry.byVehicle(armorStand);
                if (inst != null) {
                    // Keep ship running
                }
            }
        }
    }

    @EventHandler
    public void onShulkerClick(PlayerInteractEntityEvent e) {
        // Only process main hand interactions to prevent double-firing
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        Entity clicked = e.getRightClicked();
        Player player = e.getPlayer();

        // Debug tool: if player holds quartz, show all scoreboard tags on any entity
        if (player.getInventory().getItemInMainHand().getType() == Material.QUARTZ) {
            showEntityTags(player, clicked);
            e.setCancelled(true);
            return;
        }

        // Allow players to enter ship by right-clicking collision shulkers
        if (clicked instanceof Shulker shulker) {
            handleShulkerInteraction(e, shulker);
        }

        // Also handle clicking on collision carriers (ArmorStand or Interaction with shulker passengers)
        if (clicked instanceof ArmorStand || clicked instanceof org.bukkit.entity.Interaction) {
            // Check if the carrier has a shulker passenger
            for (Entity passenger : clicked.getPassengers()) {
                if (passenger instanceof Shulker shulker) {
                    handleShulkerInteraction(e, shulker);
                }
            }
        }
    }

    private void handleShulkerInteraction(PlayerInteractEntityEvent e, Shulker shulker) {
        Player player = e.getPlayer();

        // Cooldown system: prevent double-mounting but allow interactions after delay
        if (player.isInsideVehicle()) {
            UUID playerId = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long lastClick = lastShulkerInteraction.get(playerId);

            if (lastClick != null && (now - lastClick) < 500) {
                // Within cooldown - block to prevent double-entry
                e.setCancelled(true);
                return;
            }
            // Past cooldown - allow interaction (timestamp updated after successful action)
        }

        // Parse shulker tags: displayship:{uuid}, storage:{blockIndex}, shipseat:{seatIndex}, shipwheel:{location}, interact:{blockIndex}
        // Tag creation: ShipInstance constructor (collision boxes and seats)
        UUID shipId = null;
        int storageBlockIndex = -1;
        int seatIndex = -1;
        String wheelLocation = null;
        int interactBlockIndex = -1;

        Set<String> tags = shulker.getScoreboardTags();
        shipId = ShipTags.extractShipId(tags);
        storageBlockIndex = ShipTags.extractStorageIndex(tags);
        seatIndex = ShipTags.extractSeatIndex(tags);
        wheelLocation = ShipTags.extractWheelLocation(tags);
        interactBlockIndex = ShipTags.extractInteractIndex(tags);

        if (shipId == null) return;

        ShipInstance inst = ShipRegistry.byId(shipId);
        if (inst == null || !inst.vehicle.isValid()) return;

        // Check if player is holding a ship wheel - show info message
        if (isShipWheel(player.getInventory().getItemInMainHand())) {
            if ("custom".equals(inst.shipType)) {
                player.sendMessage("§eShip wheels cannot be added to assembled ships. " +
                    "Use the existing wheel (sneak + right-click) to access the ship menu. " +
                    "Enter this ship by right-clicking with an empty hand.");
            } else {
                player.sendMessage("§eShip wheels are for creating custom ships from blocks you build. " +
                    "This is a prefab ship - these are spawned from ship kits crafted at a crafting table. " +
                    "You can enter this ship by right-clicking with an empty hand.");
            }
            e.setCancelled(true);
            return;
        }

        // Debug tool: if player holds echo shard, show collision info
        if (player.getInventory().getItemInMainHand().getType() == Material.ECHO_SHARD) {
            showCollisionDebugInfo(player, shulker, inst);
            e.setCancelled(true);
            return;
        }

        // Check if this is a ship wheel collider - open menu regardless of shift
        if (wheelLocation != null) {
            // Parse location from tag: "X,Y,Z"
            String[] coords = wheelLocation.split(",");
            if (coords.length == 3) {
                try {
                    int x = Integer.parseInt(coords[0]);
                    int y = Integer.parseInt(coords[1]);
                    int z = Integer.parseInt(coords[2]);
                    Location loc = new Location(shulker.getWorld(), x, y, z);

                    ShipWheelManager manager = ((BlockShipsPlugin) plugin).getShipWheelManager();
                    ShipWheelData wheelData = manager.getWheelAt(loc);
                    if (wheelData != null) {
                        ShipWheelMenu.openMenu(player, wheelData);
                        e.setCancelled(true);
                        return;
                    }
                } catch (NumberFormatException ignored) {
                    // Invalid wheel location tag - continue with normal interaction
                }
            }
        }

        // Check if player is shift-right-clicking any shulker on the ship - open ship wheel menu
        if (player.isSneaking()) {
            ShipWheelManager manager = ((BlockShipsPlugin) plugin).getShipWheelManager();
            ShipWheelData wheelData = manager.getWheelByShipUUID(shipId);
            if (wheelData != null) {
                ShipWheelMenu.openMenu(player, wheelData);
                e.setCancelled(true);
                return;
            }
        }

        // Prefab ship lead attachment: clicking ANY block attaches to designated lead point
        if (player.getInventory().getItemInMainHand().getType() == Material.LEAD) {
            Entity leadingEntity = findEntityBeingLedByPlayer(player);
            if (leadingEntity != null) {
                Shulker leadPoint = inst.leadableShulker;
                if (leadPoint != null) {
                    ((io.papermc.paper.entity.Leashable) leadingEntity).setLeashHolder(leadPoint);
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // Check if this shulker is leadable (fence block) - handle lead attach/detach
        // For custom ships: attach to specific fence. For prefab ships: detach from lead point.
        int leadableBlockIndex = ShipTags.extractLeadableIndex(tags);
        if (leadableBlockIndex >= 0) {
            ShipWheelManager manager = ((BlockShipsPlugin) plugin).getShipWheelManager();
            List<Entity> leashedEntities = manager.findEntitiesLeashedTo(shulker);

            // First check: detach existing leads
            if (!leashedEntities.isEmpty()) {
                handleLeadDetachment(player, shulker, leashedEntities);
                e.setCancelled(true);
                return;
            }

            // Second check: attach new lead (player holding lead with entity)
            if (player.getInventory().getItemInMainHand().getType() == Material.LEAD) {
                Entity leadingEntity = findEntityBeingLedByPlayer(player);
                if (leadingEntity != null) {
                    ((io.papermc.paper.entity.Leashable) leadingEntity).setLeashHolder(shulker);
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // Check if this shulker is a cannon trigger (obsidian block)
        int cannonObsidianIndex = ShipTags.extractCannonIndex(tags);
        if (cannonObsidianIndex >= 0) {
            inst.fireCannonsByObsidian(cannonObsidianIndex);
            e.setCancelled(true);
            return;
        }

        // Check if this shulker is an interaction block (crafting table, anvil, etc.)
        if (interactBlockIndex >= 0) {
            Material blockMaterial = (interactBlockIndex >= 0 && interactBlockIndex < inst.model.parts.size())
                ? inst.model.parts.get(interactBlockIndex).block.getMaterial() : null;
            if (blockMaterial != null && InteractionBlockHandler.openInteraction(player, blockMaterial)) {
                e.setCancelled(true);
                return;
            }
        }

        // Check if this shulker is a ship engine - open fuel GUI
        if (storageBlockIndex >= 0 && inst.model.engineBlockIndices.contains(storageBlockIndex)) {
            anon.def9a2a4.blockships.customships.EngineMenuGUI.open(player, inst, storageBlockIndex);
            e.setCancelled(true);
            return;
        }

        // Check if this shulker has storage
        if (storageBlockIndex >= 0) {
            Inventory storage = inst.storages.get(storageBlockIndex);
            if (storage != null) {
                player.openInventory(storage);
                e.setCancelled(true);
                return;
            }
        }

        // Check if this shulker is marked as a seat
        if (seatIndex >= 0) {
            // Player clicked on a seat collider - mount directly to this shulker if not occupied
            if (!shulker.getPassengers().stream().anyMatch(p -> p instanceof Player)) {
                // Set camera distance before mounting
                setCameraDistanceOnShulker(shulker, getCameraDistanceForShip(inst));
                shulker.addPassenger(player);
                inst.occupySeat(seatIndex);
                // Update timestamp after successful mount
                recordShulkerInteraction(player.getUniqueId());
                e.setCancelled(true);
                return;
            }
            // Seat is occupied - do nothing
            e.setCancelled(true);
            return;
        }

        // Player clicked on a non-seat collider - mount to first available seat shulker
        Shulker availableSeatShulker = inst.getFirstAvailableSeatShulker();
        if (availableSeatShulker != null) {
            // Set camera distance before mounting
            setCameraDistanceOnShulker(availableSeatShulker, getCameraDistanceForShip(inst));
            availableSeatShulker.addPassenger(player);
            // Mark seat as occupied (extract seat index from shulker tags)
            int idx = ShipTags.extractSeatIndex(availableSeatShulker.getScoreboardTags());
            if (idx >= 0) {
                inst.occupySeat(idx);
            }
            // Update timestamp after successful mount
            recordShulkerInteraction(player.getUniqueId());
        }
        e.setCancelled(true);
    }

    /**
     * Handles detaching leads from a leadable shulker (fence block on assembled ship).
     * Mimics vanilla fence behavior: first entity attaches to player, rest drop as items.
     */
    private void handleLeadDetachment(Player player, Shulker shulker, List<Entity> leashedEntities) {
        boolean firstEntity = true;
        Entity playerLeadingEntity = findEntityBeingLedByPlayer(player);
        boolean playerAlreadyLeading = (playerLeadingEntity != null);

        for (Entity entity : leashedEntities) {
            if (!(entity instanceof io.papermc.paper.entity.Leashable leashable)) continue;

            // Detach from shulker
            leashable.setLeashHolder(null);

            // First entity attaches to player (if player isn't already leading something)
            if (firstEntity && !playerAlreadyLeading) {
                leashable.setLeashHolder(player);
                firstEntity = false;
            } else {
                // Drop lead as item at entity's location
                entity.getWorld().dropItemNaturally(entity.getLocation(), new ItemStack(Material.LEAD));
            }
        }

        // Play lead break sound
        float leadVolume = (float) plugin.getConfig().getDouble("sounds.lead-break-volume", 1.0);
        shulker.getWorld().playSound(shulker.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.0f * leadVolume, 1.0f);
    }

    /**
     * Finds an entity that the player is currently leading with a lead.
     * Searches within 10 blocks (Minecraft's lead range limit).
     */
    private Entity findEntityBeingLedByPlayer(Player player) {
        for (Entity entity : player.getWorld().getNearbyEntities(player.getLocation(), 10, 10, 10)) {
            if (entity instanceof io.papermc.paper.entity.Leashable leashable) {
                if (leashable.isLeashed() && player.equals(leashable.getLeashHolder())) {
                    return entity;
                }
            }
        }
        return null;
    }

    @EventHandler
    public void onPlayerExitVehicle(VehicleExitEvent e) {
        // Check if player is exiting a ship seat shulker
        if (!(e.getExited() instanceof Player)) return;
        if (!(e.getVehicle() instanceof Shulker shulker)) return;

        // Parse tags: displayship:{uuid} and shipseat:{index}
        // Tag creation: ShipInstance constructor (lines 285-297)
        Set<String> exitTags = shulker.getScoreboardTags();
        UUID shipId = ShipTags.extractShipId(exitTags);
        int seatIndex = ShipTags.extractSeatIndex(exitTags);

        if (shipId != null && seatIndex >= 0) {
            ShipInstance inst = ShipRegistry.byId(shipId);
            if (inst != null) {
                inst.freeSeat(seatIndex);
                // Speed persists - don't reset currentSpeed

                Player player = (Player) e.getExited();

                // Teleport player to safe position above collision shulkers
                Location safePos = inst.calculateSafeDismountPosition(player, shulker);
                player.teleport(safePos);
                player.setFallDistance(0);
                float currentSpeed = inst.physics.currentSpeed;
                float currentYVelocity = inst.physics.currentYVelocity;

                float yawRad = (float) Math.toRadians(-inst.vehicle.getYaw());
                double forwardX = Math.sin(yawRad) * currentSpeed;
                double forwardZ = Math.cos(yawRad) * currentSpeed;
                boolean shipIsMoving = Math.abs(currentSpeed) > 0.01 || Math.abs(currentYVelocity) > 0.01;

                if (shipIsMoving) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            player.setVelocity(new org.bukkit.util.Vector(
                                forwardX,
                                currentYVelocity,
                                forwardZ
                            ));
                        }
                    }.runTaskLater(plugin, 1L);
                }
            }
        }
    }

    /**
     * Handle player disconnect while riding a ship to prevent entity removal.
     * Must eject player BEFORE disconnect completes.
     */
    private void handlePlayerDisconnectOnShip(Player player) {
        try {
            ShipInstance.dismountPlayerFromAnyShip(player);
        } catch (Exception e) {
            plugin.getLogger().warning("Error handling player disconnect from ship: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        handlePlayerDisconnectOnShip(e.getPlayer());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent e) {
        handlePlayerDisconnectOnShip(e.getPlayer());
    }

    /**
     * Handle damage to collision shulkers and apply it to the ship's health.
     */
    @EventHandler
    public void onShulkerDamage(EntityDamageEvent e) {
        // Only handle shulker damage
        if (!(e.getEntity() instanceof Shulker shulker)) return;

        // Check if this shulker belongs to a ship
        UUID shipId = ShipTags.extractShipId(shulker.getScoreboardTags());
        if (shipId == null) return;

        ShipInstance inst = ShipRegistry.byId(shipId);
        if (inst == null || !inst.vehicle.isValid()) return;

        // Ignore drowning damage - ships don't take drowning damage
        // Set air to max int so this rarely fires again
        if (e.getCause() == EntityDamageEvent.DamageCause.DROWNING) {
            e.setCancelled(true);
            shulker.setRemainingAir(Integer.MAX_VALUE);
            return;
        }

        // Reduce suffocation damage by 75% - shulkers can briefly clip into blocks
        if (e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) {
            e.setDamage(e.getDamage() * 0.25);
        }

        // Cancel the damage to the shulker (keeps shulker effectively invulnerable)
        e.setCancelled(true);

        // Get the damage amount and apply directly to ship health
        double damage = e.getDamage();
        double currentHealth = inst.vehicle.getHealth();
        double newHealth = currentHealth - damage;

        org.bukkit.attribute.Attribute maxHealthAttr = anon.def9a2a4.blockships.util.AttributeCompat.getMaxHealth();
        org.bukkit.attribute.AttributeInstance maxHealthInstance = maxHealthAttr != null ? inst.vehicle.getAttribute(maxHealthAttr) : null;
        double maxHealth = maxHealthInstance != null ? maxHealthInstance.getBaseValue() : 100.0;  // Fallback to 100 if unavailable

        // Show health feedback to attacker via action bar
        if (e instanceof EntityDamageByEntityEvent damageByEntity) {
            Entity damager = damageByEntity.getDamager();
            if (damager instanceof Player attackerPlayer) {
                int displayHealth = (int) java.lang.Math.ceil(java.lang.Math.max(0, newHealth));
                String healthText = "§cShip Health: §f" + displayHealth + "/" + (int) maxHealth;
                attackerPlayer.sendActionBar(net.kyori.adventure.text.Component.text(healthText));
            }
        }

        // Damage feedback effects
        spawnDamageParticles(shulker);
        playDamageSound(shulker.getLocation());
        double healthPercent = Math.max(0, newHealth) / maxHealth;
        spawnWheelHealthParticles(inst, healthPercent);
        notifyRidersOfHealth(inst, newHealth, maxHealth);

        // Check if ship should be destroyed (health reaches 0 or below)
        if (newHealth <= 0) {
            // Destroy ship and drop item immediately to prevent race condition
            inst.destroyAndDropItem();
        } else {
            inst.vehicle.setHealth(newHealth);
            inst.syncSeatShulkerHealth(newHealth);
        }
    }

    /**
     * Prevent ship collider shulkers from dropping items if they die.
     * This handles edge cases like /kill command or other plugins killing entities.
     */
    @EventHandler
    public void onShulkerDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Shulker shulker)) return;

        // Check if this shulker belongs to a ship
        UUID shipId = ShipTags.extractShipId(shulker.getScoreboardTags());
        if (shipId == null) return;

        // Clear all drops - ship colliders should never drop items
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    /**
     * Handle projectile hits on ship collision shulkers.
     * Arrows bounce off shulkers and fireballs do nothing by default,
     * so we manually apply damage here.
     */
    @EventHandler
    public void onProjectileHitShip(ProjectileHitEvent e) {
        if (!(e.getHitEntity() instanceof Shulker shulker)) return;

        UUID shipId = ShipTags.extractShipId(shulker.getScoreboardTags());
        if (shipId == null) return;

        ShipInstance inst = ShipRegistry.byId(shipId);
        if (inst == null || !inst.vehicle.isValid()) return;

        Projectile projectile = e.getEntity();

        // Skip wind charges - they already work via EntityDamageEvent
        if (projectile instanceof WindCharge) return;

        double damage = getProjectileDamage(projectile);
        if (damage <= 0) return;

        // Apply damage to ship health
        double currentHealth = inst.vehicle.getHealth();
        double newHealth = currentHealth - damage;
        org.bukkit.attribute.Attribute maxHealthAttr = anon.def9a2a4.blockships.util.AttributeCompat.getMaxHealth();
        org.bukkit.attribute.AttributeInstance maxHealthInstance = maxHealthAttr != null ? inst.vehicle.getAttribute(maxHealthAttr) : null;
        double maxHealth = maxHealthInstance != null ? maxHealthInstance.getBaseValue() : 100.0;  // Fallback to 100 if unavailable

        // Show feedback if shooter is a player
        if (projectile.getShooter() instanceof Player player) {
            int displayHealth = (int) Math.ceil(Math.max(0, newHealth));
            String healthText = "§cShip Health: §f" + displayHealth + "/" + (int) maxHealth;
            player.sendActionBar(net.kyori.adventure.text.Component.text(healthText));
        }

        // Damage feedback effects
        spawnDamageParticles(shulker);
        playDamageSound(shulker.getLocation());
        double healthPercent = Math.max(0, newHealth) / maxHealth;
        spawnWheelHealthParticles(inst, healthPercent);
        notifyRidersOfHealth(inst, newHealth, maxHealth);

        if (newHealth <= 0) {
            inst.destroyAndDropItem();
        } else {
            inst.vehicle.setHealth(newHealth);
            inst.syncSeatShulkerHealth(newHealth);
        }

        // Remove projectile (it would normally bounce/do nothing)
        projectile.remove();
    }

    /**
     * Calculate damage for different projectile types.
     */
    private double getProjectileDamage(Projectile projectile) {
        if (projectile instanceof Arrow arrow) {
            // Arrow damage scales with velocity (max ~10 at full draw)
            double velocity = arrow.getVelocity().length();
            return Math.max(1, velocity * 2);
        } else if (projectile instanceof Fireball) {
            return 6.0; // Ghast fireball
        } else if (projectile instanceof SmallFireball) {
            return 5.0; // Blaze fireball / fire charge
        } else if (projectile instanceof ThrownPotion || projectile instanceof Snowball || projectile instanceof Egg) {
            return 0.0; // No impact damage
        }
        return 1.0; // Fallback for other projectiles
    }

    // ==================== Ship Damage Feedback Effects ====================

    /**
     * Spawns smoke particles at the damaged shulker location.
     */
    private void spawnDamageParticles(Shulker shulker) {
        Location loc = shulker.getLocation().add(0, 0.5, 0);
        World world = loc.getWorld();
        if (world == null) return;

        world.spawnParticle(Particle.SMOKE, loc, 15, 0.5, 0.5, 0.5, 0.02);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, loc, 4, 0.25, 0.25, 0.25, 0.01);
    }

    /**
     * Spawns health-colored particles at the ship's wheel location.
     * Color interpolates from grey (full health) to red (zero health).
     */
    private void spawnWheelHealthParticles(ShipInstance ship, double healthPercent) {
        CollisionBox wheelCollider = findWheelCollider(ship);
        if (wheelCollider == null || wheelCollider.entity == null || !wheelCollider.entity.isValid()) return;

        Location wheelLoc = wheelCollider.entity.getLocation().add(0, 0.5, 0);
        World world = wheelLoc.getWorld();
        if (world == null) return;

        // Interpolate: grey (128,128,128) at 100% health -> red (255,0,0) at 0% health
        double t = 1.0 - healthPercent;
        int r = (int) (128 + t * 127);   // 128 -> 255
        int g = (int) (128 * (1 - t));   // 128 -> 0
        int b = (int) (128 * (1 - t));   // 128 -> 0

        Color healthColor = Color.fromRGB(r, g, b);
        Particle.DustOptions dustOptions = new Particle.DustOptions(healthColor, 1.5f);
        world.spawnParticle(Particle.DUST, wheelLoc, 2, 0.1, 0.1, 0.1, 0, dustOptions);
    }

    /**
     * Finds the wheel collider (the one at position 0,0,0 in the ship's coordinate system).
     */
    private CollisionBox findWheelCollider(ShipInstance ship) {
        for (CollisionBox collider : ship.colliders) {
            collider.base.getTranslation(workWheelTranslation);
            if (Math.abs(workWheelTranslation.x) < 0.01f && Math.abs(workWheelTranslation.y) < 0.01f && Math.abs(workWheelTranslation.z) < 0.01f) {
                return collider;
            }
        }
        return null;
    }

    /**
     * Plays a damage sound at the given location.
     */
    private void playDamageSound(Location location) {
        World world = location.getWorld();
        if (world == null) return;
        float volume = (float) plugin.getConfig().getDouble("sounds.damage-volume", 0.1);
        world.playSound(location, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.8f * volume, 1.2f);
    }

    /**
     * Sends ship health information to all players riding the ship via action bar.
     */
    private void notifyRidersOfHealth(ShipInstance ship, double currentHealth, double maxHealth) {
        int displayHealth = (int) Math.ceil(Math.max(0, currentHealth));
        String healthText = "§cShip Health: §f" + displayHealth + "/" + (int) maxHealth;

        for (Shulker seat : ship.seatShulkers) {
            if (seat != null && seat.isValid()) {
                for (Entity passenger : seat.getPassengers()) {
                    if (passenger instanceof Player player) {
                        player.sendActionBar(net.kyori.adventure.text.Component.text(healthText));
                    }
                }
            }
        }
    }

    /**
     * Debug tool: Display all scoreboard tags on an entity when player right-clicks with quartz.
     */
    private void showEntityTags(Player player, Entity entity) {
        Set<String> tags = entity.getScoreboardTags();
        player.sendMessage("§6=== Entity Tags Debug ===");
        player.sendMessage("§eEntity Type: §f" + entity.getType().name());
        player.sendMessage("§eUUID: §f" + entity.getUniqueId());
        player.sendMessage("§eLocation: §f" + String.format("%.2f, %.2f, %.2f",
                entity.getLocation().getX(), entity.getLocation().getY(), entity.getLocation().getZ()));
        player.sendMessage("");
        if (tags.isEmpty()) {
            player.sendMessage("§7(No scoreboard tags)");
        } else {
            player.sendMessage("§eScoreboard Tags (" + tags.size() + "):");
            for (String tag : tags) {
                player.sendMessage("§f  - " + tag);
            }
        }
    }

    /**
     * Debug tool: Display collision shulker information when player right-clicks with echo shard.
     */
    private void showCollisionDebugInfo(Player player, Shulker shulker, ShipInstance inst) {
        player.sendMessage("§6=== Collision Shulker Debug ===");
        player.sendMessage("§eShip ID: §f" + inst.id);
        player.sendMessage("§eShip Type: §f" + inst.shipType);
        player.sendMessage("§eWood Type: §f" + inst.customization.getWoodType());
        org.bukkit.attribute.Attribute maxHealthAttr = anon.def9a2a4.blockships.util.AttributeCompat.getMaxHealth();
        org.bukkit.attribute.AttributeInstance maxHealthInstance = maxHealthAttr != null ? inst.vehicle.getAttribute(maxHealthAttr) : null;
        double maxHealthValue = maxHealthInstance != null ? maxHealthInstance.getValue() : 100.0;
        player.sendMessage("§eHealth: §f" + String.format("%.1f", inst.vehicle.getHealth()) + "/" +
                          String.format("%.1f", maxHealthValue));
        player.sendMessage("§eSpeed: §f" + String.format("%.3f", inst.physics.currentSpeed));

        // Find the CollisionBox for this shulker
        CollisionBox matchedBox = null;
        int colliderIndex = -1;
        int index = 0;
        for (CollisionBox box : inst.colliders) {
            if (box.entity.equals(shulker)) {
                matchedBox = box;
                colliderIndex = index;
                break;
            }
            index++;
        }

        if (matchedBox != null) {
            player.sendMessage("");
            player.sendMessage("§b--- Collision Box ---");
            player.sendMessage("§eCollider Index: §f" + colliderIndex);
            player.sendMessage("§eSize: §f" + matchedBox.config.size);
            player.sendMessage("§eOffset: §f[" +
                              matchedBox.config.offset.x + ", " +
                              matchedBox.config.offset.y + ", " +
                              matchedBox.config.offset.z + "]");
            player.sendMessage("§eWorld Position: §f[" +
                              String.format("%.2f", shulker.getLocation().getX()) + ", " +
                              String.format("%.2f", shulker.getLocation().getY()) + ", " +
                              String.format("%.2f", shulker.getLocation().getZ()) + "]");

            // Display transformation matrix
            player.sendMessage("");
            player.sendMessage("§b--- Transformation Matrix ---");
            org.joml.Matrix4f m = matchedBox.base;
            player.sendMessage("§f[" + String.format("%.4f", m.m00()) + ", " + String.format("%.4f", m.m10()) + ", " + String.format("%.4f", m.m20()) + ", " + String.format("%.4f", m.m30()) + "]");
            player.sendMessage("§f[" + String.format("%.4f", m.m01()) + ", " + String.format("%.4f", m.m11()) + ", " + String.format("%.4f", m.m21()) + ", " + String.format("%.4f", m.m31()) + "]");
            player.sendMessage("§f[" + String.format("%.4f", m.m02()) + ", " + String.format("%.4f", m.m12()) + ", " + String.format("%.4f", m.m22()) + ", " + String.format("%.4f", m.m32()) + "]");
            player.sendMessage("§f[" + String.format("%.4f", m.m03()) + ", " + String.format("%.4f", m.m13()) + ", " + String.format("%.4f", m.m23()) + ", " + String.format("%.4f", m.m33()) + "]");

            // Find corresponding ModelPart by matching transformation matrix
            ShipModel.ModelPart matchedPart = null;
            for (ShipModel.ModelPart part : inst.model.parts) {
                if (part.collision.enable && MathUtil.matricesEqual(part.local, matchedBox.base)) {
                    matchedPart = part;
                    break;
                }
            }

            // Alternative: match by collider index if matrix comparison fails
            if (matchedPart == null) {
                int enabledCount = 0;
                for (ShipModel.ModelPart part : inst.model.parts) {
                    if (part.collision.enable) {
                        if (enabledCount == colliderIndex) {
                            matchedPart = part;
                            break;
                        }
                        enabledCount++;
                    }
                }
            }

            if (matchedPart != null) {
                player.sendMessage("");
                player.sendMessage("§b--- Original YAML ---");
                FormatUtil.formatYamlToChat(player, matchedPart.rawYaml, "");
            } else {
                player.sendMessage("");
                player.sendMessage("§c(Could not find matching ModelPart)");
            }
        } else {
            player.sendMessage("§c(CollisionBox not found for this shulker)");
        }
    }

    // ===== Custom Ship Wheel System =====

    /**
     * Helper: Check if an item is a ship wheel custom item
     */
    private boolean isShipWheel(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        NamespacedKey itemIdKey = new NamespacedKey(plugin, "custom_item_id");
        return pdc.has(itemIdKey, PersistentDataType.STRING) &&
               "ship_wheel".equals(pdc.get(itemIdKey, PersistentDataType.STRING));
    }

    /**
     * Helper: Check if a block is a placed ship wheel
     */
    private boolean isShipWheelBlock(Block block) {
        Material type = block.getType();
        if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) return false;
        ShipWheelManager manager = ((BlockShipsPlugin) plugin).getShipWheelManager();
        return manager.getWheelAt(block.getLocation()) != null;
    }

    /**
     * Creates a ship wheel item for dropping.
     */
    public ItemStack createShipWheelItem() {
        return itemFactory.createItem("ship_wheel", null, null);
    }

    /**
     * Event: Place ship wheel item as a block
     */
    @EventHandler
    public void onPlaceShipWheel(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (!isShipWheel(item)) return;

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        // Don't place if clicking an existing ship wheel (let onShipWheelRightClick handle it)
        if (isShipWheelBlock(clickedBlock)) return;

        BlockFace face = event.getBlockFace();
        Block targetBlock = clickedBlock.getRelative(face);

        // Check if target location is valid for placement
        if (!targetBlock.getType().isAir()) return;

        Player player = event.getPlayer();

        // Determine wheel facing direction and set block type + rotation
        BlockFace wheelFacing;
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            // Placing on floor/ceiling - use PLAYER_HEAD with Rotatable interface
            targetBlock.setType(Material.PLAYER_HEAD);

            // Player's facing becomes the ship's "front"
            float yaw = ShipWheelData.snapToNearestCardinal(player.getLocation().getYaw());
            wheelFacing = ShipWheelData.yawToBlockFace(yaw);

            // Convert BlockFace to rotation (0-15 where each increment is 22.5 degrees)
            // We use every 4th value for cardinal directions: 0=south, 4=west, 8=north, 12=east
            org.bukkit.block.BlockFace rotation;
            switch (wheelFacing) {
                case SOUTH:
                    rotation = org.bukkit.block.BlockFace.SOUTH;  // 0
                    break;
                case WEST:
                    rotation = org.bukkit.block.BlockFace.WEST;  // 4
                    break;
                case NORTH:
                    rotation = org.bukkit.block.BlockFace.NORTH;  // 8
                    break;
                case EAST:
                    rotation = org.bukkit.block.BlockFace.EAST;  // 12
                    break;
                default:
                    rotation = org.bukkit.block.BlockFace.SOUTH;
            }

            if (targetBlock.getBlockData() instanceof org.bukkit.block.data.Rotatable) {
                org.bukkit.block.data.Rotatable rotatable = (org.bukkit.block.data.Rotatable) targetBlock.getBlockData();
                rotatable.setRotation(rotation);
                targetBlock.setBlockData(rotatable);
            }
        } else {
            // Placing on wall - use PLAYER_WALL_HEAD with Directional interface
            targetBlock.setType(Material.PLAYER_WALL_HEAD);

            // Ship faces INTO the wall (opposite of the clicked face)
            wheelFacing = face.getOppositeFace();

            if (targetBlock.getBlockData() instanceof org.bukkit.block.data.Directional) {
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) targetBlock.getBlockData();
                directional.setFacing(face);  // Block faces outward (clicked face)
                targetBlock.setBlockData(directional);
            }
        }

        // Set the skull texture AFTER setting rotation/facing
        if (targetBlock.getState() instanceof org.bukkit.block.Skull && item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta) {
            org.bukkit.block.Skull skull = (org.bukkit.block.Skull) targetBlock.getState();
            org.bukkit.inventory.meta.SkullMeta skullMeta = (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();

            // Copy the player profile (which contains the texture)
            com.destroystokyo.paper.profile.PlayerProfile profile = skullMeta.getPlayerProfile();
            if (profile != null) {
                skull.setPlayerProfile(profile);
                skull.update();
            }
        }

        // Register with ShipWheelManager
        ShipWheelManager manager = ((BlockShipsPlugin) plugin).getShipWheelManager();
        boolean success = manager.placeWheel(targetBlock.getLocation(), wheelFacing);

        if (success) {
            // Consume item (unless creative mode)
            if (player.getGameMode() != GameMode.CREATIVE) {
                item.setAmount(item.getAmount() - 1);
            }
            event.setCancelled(true);
        } else {
            // Failed to place - revert block
            targetBlock.setType(Material.AIR);
        }
    }

    /**
     * Event: Right-click ship wheel block to open menu
     */
    @EventHandler
    public void onShipWheelRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || !isShipWheelBlock(block)) return;

        Player player = event.getPlayer();
        ShipWheelManager manager = ((BlockShipsPlugin) plugin).getShipWheelManager();
        ShipWheelData wheelData = manager.getWheelAt(block.getLocation());

        if (wheelData != null) {
            ShipWheelMenu.openMenu(player, wheelData);
            event.setCancelled(true);
        }
    }

    /**
     * Event: Handle ship wheel menu clicks
     */
    @EventHandler
    public void onShipWheelMenuClick(InventoryClickEvent event) {
        // Check if this is a ship wheel menu by checking the inventory holder
        if (!(event.getInventory().getHolder() instanceof ShipWheelMenu.ShipWheelMenuHolder)) {
            return;
        }

        event.setCancelled(true); // Prevent item removal

        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        int slot = event.getRawSlot();
        ShipWheelMenu.MenuAction action = ShipWheelMenu.getActionFromSlot(slot);

        if (action == ShipWheelMenu.MenuAction.NONE) return;

        // Get wheel data from the custom inventory holder
        ShipWheelMenu.ShipWheelMenuHolder holder = (ShipWheelMenu.ShipWheelMenuHolder) event.getInventory().getHolder();
        ShipWheelData wheelData = holder.getWheelData();

        if (wheelData == null) {
            player.sendMessage("§cShip wheel data not found!");
            player.closeInventory();
            return;
        }

        ShipWheelManager manager = ((BlockShipsPlugin) plugin).getShipWheelManager();

        boolean stateChanged = false;

        switch (action) {
            case HELP:
                ShipWheelMenu.openHelpBook(player);
                break;
            case DETECT:
                manager.detectShip(player, wheelData);
                // Refresh menu to update ship info lore
                player.closeInventory();
                Bukkit.getScheduler().runTaskLater(plugin, () -> ShipWheelMenu.openMenu(player, wheelData), 1L);
                break;
            case ASSEMBLE:
                stateChanged = manager.assembleShip(player, wheelData);
                break;
            case ALIGN:
                manager.alignToGrid(player, wheelData);
                break;
            case DISASSEMBLE:
                stateChanged = manager.disassembleShip(player, wheelData);
                // If disassembly failed but force is available, reopen menu to show force option
                if (!stateChanged && wheelData.canForceDisassemble()) {
                    wheelData.setPendingMenuReopen(true);
                    player.closeInventory();
                    Bukkit.getScheduler().runTaskLater(plugin, () -> ShipWheelMenu.openMenu(player, wheelData), 1L);
                    return;
                }
                break;
            case FORCE_DISASSEMBLE:
                stateChanged = manager.disassembleShip(player, wheelData, true);
                break;
            case INFO:
                // Run ship detection and update the info item in place (no particles)
                manager.detectShip(player, wheelData, false);
                ShipWheelMenu.updateInfoItem(event.getInventory(), wheelData);
                break;
            case FIRE_CANNONS:
                // Fire all cannons on the ship
                if (wheelData.isAssembled()) {
                    ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
                    if (ship != null) {
                        ship.fireAllCannons();
                    }
                }
                break;
            case HIGHLIGHT_SEATS:
                manager.highlightSeats(player, wheelData);
                break;
            case CAMERA_DISTANCE_DECREASE:
            case CAMERA_DISTANCE_INCREASE:
                handleCameraDistanceChange(player, wheelData, action == ShipWheelMenu.MenuAction.CAMERA_DISTANCE_INCREASE, event.getInventory());
                // Don't set stateChanged - we update items in place
                break;
        }

        // Close and reopen menu if state changed (for assemble/disassemble)
        if (stateChanged) {
            player.closeInventory();
            // Reopen after a tick to show updated state
            new BukkitRunnable() {
                @Override
                public void run() {
                    ShipWheelMenu.openMenu(player, wheelData);
                }
            }.runTaskLater(plugin, 1L);
        }
    }

    /**
     * Event: Clear force disassembly state when ship wheel menu is closed
     */
    @EventHandler
    public void onShipWheelMenuClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShipWheelMenu.ShipWheelMenuHolder)) {
            return;
        }

        ShipWheelMenu.ShipWheelMenuHolder holder = (ShipWheelMenu.ShipWheelMenuHolder) event.getInventory().getHolder();
        ShipWheelData wheelData = holder.getWheelData();
        if (wheelData != null) {
            // Only clear conflicts if the menu isn't about to reopen (for force disassemble option)
            if (!wheelData.isPendingMenuReopen()) {
                wheelData.setLastDisassemblyConflicts(null);
            }
            wheelData.setPendingMenuReopen(false);
        }
    }

    /**
     * Handles camera distance adjustment from the ship wheel menu.
     * Updates the value, applies to all seat shulkers, and refreshes menu items in place.
     *
     * @param player The player adjusting the camera distance
     * @param wheelData The ship wheel data
     * @param increase true to increase, false to decrease
     * @param inventory The menu inventory to update in place
     */
    private void handleCameraDistanceChange(Player player, ShipWheelData wheelData, boolean increase, Inventory inventory) {
        ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
        if (ship == null) return;

        float current = wheelData.getCameraDistance();
        if (current < 0) {
            current = ShipWheelData.calculateDefaultCameraDistance(ship.model.blockCount);
        }

        float step = 2f;  // Adjust by 2 per click
        float newValue = increase ? current + step : current - step;
        newValue = Math.max(4f, Math.min(32f, newValue));  // Clamp to valid range

        wheelData.setCameraDistance(newValue);

        // Update all seat shulkers immediately (so change takes effect if player is riding)
        for (Shulker shulker : ship.seatShulkers) {
            setCameraDistanceOnShulker(shulker, newValue);
        }

        // Update menu items in place (no close/reopen)
        ShipWheelMenu.updateCameraItems(inventory, wheelData, ship);
    }

    /**
     * Sets the camera_distance attribute on a shulker for third-person camera positioning.
     * Only effective on Minecraft 1.21.6+ where this attribute exists.
     *
     * @param shulker The shulker to set the attribute on
     * @param distance The camera distance (4-32, default 4)
     */
    private void setCameraDistanceOnShulker(Shulker shulker, float distance) {
        org.bukkit.attribute.Attribute cameraDistAttr = AttributeCompat.getCameraDistance();
        if (cameraDistAttr != null) {
            try {
                org.bukkit.attribute.AttributeInstance attr = shulker.getAttribute(cameraDistAttr);
                if (attr != null) {
                    attr.setBaseValue(distance);
                }
            } catch (Exception e) {
                // Silently ignore - attribute may not be applicable on this server version
            }
        }
    }

    /**
     * Gets the appropriate camera distance for a ship instance.
     * For prefab ships, returns the config value.
     * For custom ships, returns the wheel data value or calculates from block count.
     *
     * @param inst The ship instance
     * @return The camera distance to use
     */
    private float getCameraDistanceForShip(ShipInstance inst) {
        if ("custom".equals(inst.shipType)) {
            // For custom ships, check wheel data for per-ship setting
            ShipWheelManager manager = ((BlockShipsPlugin) plugin).getShipWheelManager();
            ShipWheelData wheelData = manager.getWheelByShipUUID(inst.id);
            if (wheelData != null && wheelData.getCameraDistance() >= 0) {
                return wheelData.getCameraDistance();
            } else {
                return ShipWheelData.calculateDefaultCameraDistance(inst.model.blockCount);
            }
        }
        // For prefab ships, use config value
        return inst.config.cameraDistance;
    }

    /**
     * Event: Handle ship wheel block breaking
     */
    @EventHandler
    public void onShipWheelBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isShipWheelBlock(block)) return;

        // Cancel event to prevent other plugins (like HeadSmith) from also handling this
        event.setCancelled(true);

        ShipWheelManager manager = ((BlockShipsPlugin) plugin).getShipWheelManager();
        ShipWheelData wheelData = manager.getWheelAt(block.getLocation());

        if (wheelData != null) {
            // Check if ship is assembled - warn player
            if (wheelData.isAssembled()) {
                Player player = event.getPlayer();
                player.sendMessage("§cWarning: Breaking this wheel will destroy the assembled ship!");
            }

            // Remove wheel (this also destroys the ship if assembled)
            manager.removeWheel(block.getLocation());

            // Manually remove the block since we cancelled the event
            block.setType(Material.AIR);

            // Drop ship wheel item
            World world = block.getWorld();
            ItemStack wheelItem = createShipWheelItem();
            world.dropItemNaturally(block.getLocation(), wheelItem);
        }
    }

    // ===== Engine Menu GUI event handlers =====

    @EventHandler
    public void onEngineMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof anon.def9a2a4.blockships.customships.EngineMenuGUI.EngineMenuHolder)) return;

        int slot = event.getRawSlot();
        // Allow fuel slot interactions, block everything else in the top inventory
        if (slot >= 0 && slot < 9) {
            if (!anon.def9a2a4.blockships.customships.EngineMenuGUI.isFuelSlot(slot)) {
                event.setCancelled(true);
                return;
            }
            // Validate fuel: if placing an item, check it's valid fuel
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                if (!anon.def9a2a4.blockships.customships.EngineMenuGUI.isValidFuel(cursor.getType())) {
                    event.setCancelled(true);
                }
            }
        }
        // Block shift-clicks from player inventory that would move non-fuel items into engine GUI
        if (event.isShiftClick() && slot >= 9) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !anon.def9a2a4.blockships.customships.EngineMenuGUI.isValidFuel(clicked.getType())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEngineMenuClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof anon.def9a2a4.blockships.customships.EngineMenuGUI.EngineMenuHolder holder) {
            anon.def9a2a4.blockships.customships.EngineMenuGUI.saveFuelState(holder);
        }
    }

    // ===== Ship Engine event handlers =====

    private static final String ENGINE_PDC_VALUE = "ship_engine";

    /**
     * Transfers PDC tag from a ship engine item to the placed block's TileState.
     * Bukkit doesn't auto-transfer item PDC to blocks, so we do it manually.
     */
    @EventHandler
    public void onPlaceShipEngine(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.BLAST_FURNACE || !item.hasItemMeta()) return;

        PersistentDataContainer itemPdc = item.getItemMeta().getPersistentDataContainer();
        NamespacedKey itemIdKey = new NamespacedKey(plugin, "custom_item_id");
        String itemId = itemPdc.get(itemIdKey, PersistentDataType.STRING);
        if (!ENGINE_PDC_VALUE.equals(itemId)) return;

        // Transfer PDC to block TileState
        Block block = event.getBlockPlaced();
        org.bukkit.block.BlockState state = block.getState();
        if (state instanceof org.bukkit.block.TileState tileState) {
            tileState.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, ENGINE_PDC_VALUE);
            tileState.update();
        }
    }

    /**
     * Prevents ship engines from burning fuel via vanilla smelting mechanics.
     */
    @EventHandler
    public void onEngineFurnaceBurn(FurnaceBurnEvent event) {
        if (isShipEngine(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents ship engines from smelting items via vanilla mechanics.
     */
    @EventHandler
    public void onEngineFurnaceSmelt(FurnaceSmeltEvent event) {
        if (isShipEngine(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * Drops the custom ship engine item when a player breaks an engine block.
     * Without this, breaking drops a vanilla blast furnace (losing PDC tag and glint).
     */
    @EventHandler
    public void onBreakShipEngine(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isShipEngine(block)) return;

        event.setCancelled(true);
        block.setType(Material.AIR);
        block.getWorld().dropItemNaturally(
            block.getLocation().add(0.5, 0.5, 0.5),
            itemFactory.createItem("ship_engine", "_DEFAULT", null));
    }

    /**
     * Checks if a block is a ship engine (blast furnace with engine PDC tag).
     */
    private boolean isShipEngine(Block block) {
        if (block.getType() != Material.BLAST_FURNACE) return false;
        org.bukkit.block.BlockState state = block.getState();
        if (!(state instanceof org.bukkit.block.TileState tileState)) return false;
        NamespacedKey itemIdKey = new NamespacedKey(plugin, "custom_item_id");
        return ENGINE_PDC_VALUE.equals(
            tileState.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING));
    }

    /**
     * Records a shulker interaction timestamp for cooldown tracking.
     * Also cleans up stale entries to prevent memory leaks.
     */
    private void recordShulkerInteraction(UUID playerId) {
        long now = System.currentTimeMillis();
        // Clean up entries older than 1 second to prevent unbounded growth
        lastShulkerInteraction.entrySet().removeIf(e -> (now - e.getValue()) > 1000);
        lastShulkerInteraction.put(playerId, now);
    }
}
