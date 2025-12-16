package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.customships.ShipWheelData;
import anon.def9a2a4.blockships.customships.ShipWheelManager;
import anon.def9a2a4.blockships.customships.ShipWheelMenu;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
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
                // Save all currently loaded ships and ensure they're in chunk index
                for (ShipInstance ship : ShipRegistry.getAllShips()) {
                    shipWorldData.saveShipMetadata(ship);

                    // Ensure ship is in chunk index (may have been missed or moved)
                    Location loc = ship.vehicle.getLocation();
                    int chunkX = loc.getBlockX() >> 4;
                    int chunkZ = loc.getBlockZ() >> 4;
                    shipWorldData.addToChunkIndex(loc.getWorld(), ship.id, chunkX, chunkZ);
                }
                shipWorldData.saveAllChunkIndices();
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
                ShipModel model = ShipModel.fromFile(plugin, modelPath);
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
        // Save all ships to per-world storage before shutdown
        shipWorldData.saveAll();
        ShipRegistry.destroyAll();
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
        for (ShipInstance ship : ShipRegistry.getShipsInChunk(chunk)) {
            // Save current state to per-world storage before suspension
            shipWorldData.saveShipMetadata(ship);

            // Suspend tasks and clear stale references
            ship.suspendForChunkUnload();
            // Unregister from active registry (ship data stays in per-world YAML)
            ShipRegistry.unregister(ship);
            plugin.getLogger().fine("Suspended ship " + ship.id + " for chunk unload at " + chunk.getX() + "," + chunk.getZ());
        }
        // Persist chunk indices
        shipWorldData.saveAllChunkIndices();
    }

    /**
     * Handles chunk load events.
     * Looks up ships in the chunk from per-world data, creates ShipInstance, and recovers entity references.
     */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        org.bukkit.Chunk chunk = event.getChunk();
        List<UUID> shipIds = shipWorldData.getShipsInChunk(event.getWorld(), chunk.getX(), chunk.getZ());

        for (UUID shipId : shipIds) {
            // Skip if already in registry (shouldn't happen, but defensive)
            if (ShipRegistry.byId(shipId) != null) {
                continue;
            }

            // Check if this ship is already being recovered (prevent concurrent recovery)
            if (!shipsBeingRecovered.add(shipId)) {
                continue;
            }

            try {
                // Load ship metadata from per-world YAML
                ShipPersistence.ShipState state = shipWorldData.loadShipMetadata(event.getWorld(), shipId);
                if (state == null) {
                    plugin.getLogger().warning("Ship " + shipId + " in chunk index but no metadata file found");
                    continue;
                }

                // Load model
                ShipModel model = loadModelForState(state);
                if (model == null) {
                    plugin.getLogger().warning("Could not load model for ship " + shipId + " (type: " + state.shipType + ")");
                    continue;
                }

                // Create ShipInstance from state (without spawning entities)
                ShipInstance ship = ShipInstance.fromState(plugin, state, model);
                if (ship == null) {
                    plugin.getLogger().warning("Failed to create ShipInstance for " + shipId);
                    continue;
                }

                // Recover entity references from chunk
                if (!ship.recoverEntities(chunk)) {
                    plugin.getLogger().warning("Failed to recover entities for ship " + shipId + " - entities may be missing");
                    continue;
                }

                // Register recovered ship
                ShipRegistry.register(ship);
                plugin.getLogger().info("Recovered ship " + shipId + " from chunk load at " + chunk.getX() + "," + chunk.getZ());
            } finally {
                shipsBeingRecovered.remove(shipId);
            }
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
                return ShipModel.fromFile(plugin, modelPath);
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

        // Load recipe pattern and ingredients
        List<String> pattern = plugin.getConfig().getStringList(recipePath + ".pattern");
        if (pattern.isEmpty() || pattern.size() != 3) {
            e.getInventory().setResult(null);
            return;
        }

        // Build ingredient map
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

        // Validate crafting with RecipeValidator
        RecipeValidator.ValidationResult validation = RecipeValidator.validateCrafting(
                e.getInventory(),
                pattern,
                ingredientMap
        );

        if (!validation.isValid()) {
            e.getInventory().setResult(null);
            return;
        }

        // Extract banner (for ship customization)
        ItemStack banner = RecipeValidator.extractBanner(e.getInventory());

        // Get primary variant (wood type, wool color, etc.)
        String variant = validation.getPrimaryVariant();

        // For airships, extract balloon color from the crafting matrix
        String balloonColor = null;
        if (plugin.getConfig().getString("ships." + shipType + ".type", "").equals("airship")) {
            balloonColor = extractBalloonColor(e.getInventory());
        }

        // Create item using unified ItemFactory
        ItemStack result;
        if (plugin.getConfig().contains("custom-items." + shipType)) {
            // Custom items
            result = itemFactory.createItem(shipType, variant, banner);
        } else {
            // Ship kits - use enhanced method with balloon color
            result = createShipKitWithBalloon(shipType, banner, variant, balloonColor);
        }
        e.getInventory().setResult(result);
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
        Entity vehicle = e.getPlayer().getVehicle();
        if (vehicle instanceof ArmorStand armorStand) {
            // Check if this is a ship seat
            if (armorStand.getScoreboardTags().stream().anyMatch(tag -> tag.contains(":seat"))) {
                ShipInstance inst = ShipRegistry.byVehicle(armorStand);
                if (inst != null) {
                    // Keep ship running; uncomment to despawn when driver sneaks:
                    // inst.destroy();
                }
            }
        }
    }

    @EventHandler
    public void onShulkerClick(PlayerInteractEntityEvent e) {
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
                shulker.addPassenger(player);
                inst.occupySeat(seatIndex);
                // Update timestamp after successful mount
                lastShulkerInteraction.put(player.getUniqueId(), System.currentTimeMillis());
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
            availableSeatShulker.addPassenger(player);
            // Mark seat as occupied (extract seat index from shulker tags)
            int idx = ShipTags.extractSeatIndex(availableSeatShulker.getScoreboardTags());
            if (idx >= 0) {
                inst.occupySeat(idx);
            }
            // Update timestamp after successful mount
            lastShulkerInteraction.put(player.getUniqueId(), System.currentTimeMillis());
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
        shulker.getWorld().playSound(shulker.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.0f, 1.0f);
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

                // Transfer ship velocity to exiting player
                Player player = (Player) e.getExited();
                float currentSpeed = inst.physics.currentSpeed;
                float currentYVelocity = inst.physics.currentYVelocity;

                // Calculate ship velocity from currentSpeed and yaw (needed if ship is moving)
                float yawRad = (float) Math.toRadians(-inst.vehicle.getYaw());
                double forwardX = Math.sin(yawRad) * currentSpeed;
                double forwardZ = Math.cos(yawRad) * currentSpeed;
                boolean shipIsMoving = Math.abs(currentSpeed) > 0.01 || Math.abs(currentYVelocity) > 0.01;

                // Delay by 2 ticks to ensure Minecraft's dismount logic completes
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        // Teleport player up 0.1 blocks to prevent clipping into shulker
                        Location loc = player.getLocation();
                        loc.setY(loc.getY() + 0.1);
                        player.teleport(loc);

                        // Transfer ship velocity if ship is moving (horizontally or vertically)
                        if (shipIsMoving) {
                            player.setVelocity(new org.bukkit.util.Vector(
                                forwardX,
                                currentYVelocity,
                                forwardZ
                            ));
                        }
                    }
                }.runTaskLater(plugin, 2L);
            }
        }
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

        // Cancel the damage to the shulker (keeps shulker effectively invulnerable)
        e.setCancelled(true);

        // Get the damage amount and apply directly to ship health
        double damage = e.getDamage();
        double currentHealth = inst.vehicle.getHealth();
        double newHealth = currentHealth - damage;

        // Show health feedback to attacker via action bar
        if (e instanceof EntityDamageByEntityEvent damageByEntity) {
            Entity damager = damageByEntity.getDamager();
            if (damager instanceof Player attackerPlayer) {
                double maxHealth = inst.vehicle.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue();
                int displayHealth = (int) java.lang.Math.ceil(java.lang.Math.max(0, newHealth));
                String healthText = "§cShip Health: §f" + displayHealth + "/" + (int) maxHealth;
                attackerPlayer.sendActionBar(net.kyori.adventure.text.Component.text(healthText));
            }
        }

        // Check if ship should be destroyed (health reaches 0 or below)
        if (newHealth <= 0) {
            // Destroy ship and drop item immediately to prevent race condition
            inst.destroyAndDropItem();
        } else {
            inst.vehicle.setHealth(newHealth);
        }
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

        // Show feedback if shooter is a player
        if (projectile.getShooter() instanceof Player player) {
            double maxHealth = inst.vehicle.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue();
            int displayHealth = (int) Math.ceil(Math.max(0, newHealth));
            String healthText = "§cShip Health: §f" + displayHealth + "/" + (int) maxHealth;
            player.sendActionBar(net.kyori.adventure.text.Component.text(healthText));
        }

        if (newHealth <= 0) {
            inst.destroyAndDropItem();
        } else {
            inst.vehicle.setHealth(newHealth);
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
        player.sendMessage("§eHealth: §f" + String.format("%.1f", inst.vehicle.getHealth()) + "/" +
                          String.format("%.1f", inst.vehicle.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()));
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
        if (block.getType() != Material.PLAYER_HEAD) return false;
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

        // Place the player head block first
        targetBlock.setType(Material.PLAYER_HEAD);

        // Determine wheel facing direction and set rotation FIRST
        BlockFace wheelFacing;
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            // Placing on floor/ceiling - use Rotatable interface
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
            // Placing on wall - use Directional interface
            // The wall itself is the ship's "front"
            wheelFacing = face;

            if (targetBlock.getBlockData() instanceof org.bukkit.block.data.Directional) {
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) targetBlock.getBlockData();
                directional.setFacing(wheelFacing);
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
     * Event: Handle ship wheel block breaking
     */
    @EventHandler
    public void onShipWheelBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isShipWheelBlock(block)) return;

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

            // Drop ship wheel item
            World world = block.getWorld();
            ItemStack wheelItem = createShipWheelItem();
            world.dropItemNaturally(block.getLocation(), wheelItem);
        }
    }
}
