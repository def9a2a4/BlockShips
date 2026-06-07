package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.blockconfig.BlockConfigManager;
import anon.def9a2a4.blockships.customships.ShipWheelData;
import anon.def9a2a4.blockships.customships.ShipWheelManager;
import anon.def9a2a4.blockships.ship.ShipCollisionCoordinator;
import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.util.Vector;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import java.util.ArrayList;
import java.util.List;

public class BlockShipsPlugin extends JavaPlugin {

    private DisplayShip displayShip;
    private ShipSteeringListener steeringListener;
    private ShipWheelManager shipWheelManager;
    private SpecialDrownedListener specialDrownedListener;

    @Override
    public void onEnable() {
        int pluginId = 28443;
        new Metrics(this, pluginId);

        saveDefaultConfig();

        // Warn if bundled resource files (blocks, items, prefab ships) are outdated
        ConfigValidator.checkForOutdatedResources(this);

        // Load global physics config
        ShipInstance.loadGlobalPhysicsConfig(this);

        // Initialize block configuration manager
        BlockConfigManager.initialize(this);
        BlockConfigManager.getInstance().loadConfig();

        // Load help book content from bundled YAML
        HelpBookContent.load(this);

        // Check for ProtocolLib for WASD input detection
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().warning("==================================================");
            getLogger().warning("ProtocolLib not found! WASD ship controls will not work.");
            getLogger().warning("Download it from: https://www.spigotmc.org/resources/protocollib.1997/");
            getLogger().warning("The plugin will continue to load but ships won't be controllable.");
            getLogger().warning("==================================================");
        } else {
            // Initialize steering listener (ProtocolLib WASD detection)
            steeringListener = new ShipSteeringListener(this);
        }

        // Initialize ship-to-ship collision coordinator
        boolean shipCollisionEnabled = getConfig().getBoolean("collision.ship-to-ship-enabled", true);
        int shipCollisionMaxCollisions = getConfig().getInt("collision.ship-to-ship-max-collisions", 20);
        ShipCollisionCoordinator.init(this, shipCollisionEnabled, shipCollisionMaxCollisions);

        // Initialize and register DisplayShip
        displayShip = new DisplayShip(this);
        displayShip.initialize();
        Bukkit.getPluginManager().registerEvents(displayShip, this);

        // Initialize ShipWheelManager for custom block ships and load saved wheels
        shipWheelManager = new ShipWheelManager(this);
        shipWheelManager.loadAll();

        // Initialize special drowned listener (spawns drowned holding ship wheels)
        specialDrownedListener = new SpecialDrownedListener(this);
        if (specialDrownedListener.isEnabled()) {
            Bukkit.getPluginManager().registerEvents(specialDrownedListener, this);
            getLogger().info("Special drowned spawning enabled.");
        }

        getLogger().info("BlockShips enabled.");
    }

    @Override
    public void onDisable() {
        // Shutdown ship-to-ship collision coordinator
        ShipCollisionCoordinator.shutdown();

        // Save ship wheels before shutdown
        if (shipWheelManager != null) {
            shipWheelManager.saveAll();
        }
        if (displayShip != null) {
            displayShip.shutdown();
        }
        getLogger().info("BlockShips disabled.");
    }

    public DisplayShip getDisplayShip() {
        return displayShip;
    }

    public ShipWheelManager getShipWheelManager() {
        return shipWheelManager;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== BlockShips v" + getDescription().getVersion() + " ===");
        sender.sendMessage("§e/blockships help §7- Show this help message");
        sender.sendMessage("§e/blockships info §7- Show ship and wheel statistics");
        sender.sendMessage("§e/blockships dismount §7- Force-dismount from a ship");
        sender.sendMessage("§e/blockships highlightseats §7- Highlight seats on the ship you're looking at");
        sender.sendMessage("§e/blockships highlightcolliders §7- Toggle glowing on collider shulkers");
        if (sender.hasPermission("blockships.reload")) {
            sender.sendMessage("§e/blockships reload §7- Reload the plugin configuration");
        }
        if (sender.hasPermission("blockships.give")) {
            sender.sendMessage("§e/blockships give <item> §7- Give yourself a ship wheel or ship kit");
            sender.sendMessage("§e/blockships spawndrowned §7- Spawn a special drowned at your location");
        }
        if (sender.hasPermission("blockships.recipes")) {
            sender.sendMessage("§e/blockships recipes [player] §7- Unlock all BlockShips recipes");
        }
        if (sender.hasPermission("blockships.admin")) {
            sender.sendMessage("§e/blockships forcedisassembleall §7- Force-disassemble all assembled ships §c§l[DANGEROUS]");
            sender.sendMessage("§e/blockships killentities §7- Remove all BlockShips entities from worlds §c§l[DANGEROUS]");
        }
        sender.sendMessage("§7Found a bug? Report it at: §bhttps://github.com/def9a2a4/BlockShips/issues");
    }

    /**
     * Finds the ship the player is currently looking at via raycasting.
     * @param player The player
     * @return The ShipInstance being looked at, or null if none found
     */
    private ShipInstance findLookedAtShip(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();

        // Check entities along the ray (up to 50 blocks)
        for (double d = 0; d <= 50; d += 0.5) {
            Location check = eye.clone().add(direction.clone().multiply(d));

            for (Entity e : check.getWorld().getNearbyEntities(check, 1, 1, 1)) {
                if (!(e instanceof Shulker shulker)) continue;

                java.util.UUID shipId = ShipTags.extractShipId(shulker.getScoreboardTags());
                if (shipId != null) {
                    return ShipRegistry.byId(shipId);
                }
            }
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("blockships")) {
            // Warn if ProtocolLib is missing
            if (steeringListener == null) {
                sender.sendMessage("");
                sender.sendMessage("§c§l⚠ WARNING: ProtocolLib not found! ⚠");
                sender.sendMessage("§cWASD ship controls will not work without it.");
                sender.sendMessage("§7Download: §bhttps://www.spigotmc.org/resources/protocollib.1997/");
                sender.sendMessage("");
            }

            // Show help when no args or "help" subcommand
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sendHelp(sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("info")) {
                // Count prefab ships (non-custom) and custom ships
                int prefabLoaded = 0, prefabUnloaded = 0;
                int customLoaded = 0, customUnloaded = 0;

                for (ShipInstance ship : ShipRegistry.getAllShips()) {
                    boolean loaded = ship.vehicle.getLocation().isChunkLoaded();
                    if ("custom".equals(ship.shipType)) {
                        if (loaded) customLoaded++; else customUnloaded++;
                    } else {
                        if (loaded) prefabLoaded++; else prefabUnloaded++;
                    }
                }

                // Count ship wheels not on assembled ships
                int wheelsLoaded = 0, wheelsUnloaded = 0;
                for (ShipWheelData wheel : shipWheelManager.getWheels()) {
                    if (!wheel.isAssembled()) {
                        boolean loaded = wheel.getBlockLocation().isChunkLoaded();
                        if (loaded) wheelsLoaded++; else wheelsUnloaded++;
                    }
                }

                sender.sendMessage("§6=== BlockShips Info ===");
                sender.sendMessage("§ePrefab Ships: §a" + prefabLoaded + " loaded§7, §c" + prefabUnloaded + " unloaded");
                sender.sendMessage("§eShip Wheels: §a" + wheelsLoaded + " loaded§7, §c" + wheelsUnloaded + " unloaded");
                sender.sendMessage("§eCustom Ships: §a" + customLoaded + " loaded§7, §c" + customUnloaded + " unloaded");
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("blockships.reload")) {
                    sender.sendMessage("You don't have permission to reload this plugin.");
                    return true;
                }
                reloadConfig();
                // Reload global physics config
                ShipInstance.loadGlobalPhysicsConfig(this);
                // Re-initialize ship-to-ship collision coordinator with new config
                boolean reloadedEnabled = getConfig().getBoolean("collision.ship-to-ship-enabled", true);
                int reloadedMaxCollisions = getConfig().getInt("collision.ship-to-ship-max-collisions", 20);
                ShipCollisionCoordinator.init(this, reloadedEnabled, reloadedMaxCollisions);
                if (displayShip != null) {
                    displayShip.reload();
                }
                // Reload block configuration
                BlockConfigManager.getInstance().reloadConfig();
                // Reload help book content
                HelpBookContent.load(this);
                // Reload special drowned config
                if (specialDrownedListener != null) {
                    specialDrownedListener.reloadConfig();
                }
                sender.sendMessage("BlockShips config reloaded!");
                return true;
            }

            if (args[0].equalsIgnoreCase("give")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command.");
                    return true;
                }

                if (!sender.hasPermission("blockships.give")) {
                    sender.sendMessage("You don't have permission to give ship kits.");
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage("Usage: /blockships give <item>");
                    sendGiveableItems(sender);
                    return true;
                }

                String itemType = args[1].toLowerCase();

                // Ship wheel
                if (itemType.equals("ship_wheel")) {
                    ItemStack wheel = displayShip.createShipWheelItem();
                    player.getInventory().addItem(wheel);
                    sender.sendMessage("Gave you a ship wheel!");
                    return true;
                }

                // Captain's Manual (written book)
                if (itemType.equals("captains_manual")) {
                    ItemStack manual = HelpBookContent.createWrittenBook();
                    player.getInventory().addItem(manual);
                    sender.sendMessage("Gave you a Captain's Manual!");
                    return true;
                }

                // Custom items (ship_engine, balloon, etc.)
                if (getConfig().contains("custom-items." + itemType)) {
                    ItemStack item = displayShip.getItemFactory().createItem(itemType, "_DEFAULT", null);
                    player.getInventory().addItem(item);
                    sender.sendMessage("Gave you a " + itemType + "!");
                    return true;
                }

                // Ship kits
                if (getConfig().contains("ships." + itemType)) {
                    ItemStack defaultBanner = new ItemStack(Material.WHITE_BANNER);
                    ItemStack shipKit = DisplayShip.createShipKit(itemType, defaultBanner, "SPRUCE", this);
                    player.getInventory().addItem(shipKit);
                    sender.sendMessage("Gave you a " + itemType + " ship kit!");
                    return true;
                }

                sender.sendMessage("Unknown item: " + itemType);
                sendGiveableItems(sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("spawndrowned")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command.");
                    return true;
                }

                if (!sender.hasPermission("blockships.give")) {
                    sender.sendMessage("You don't have permission to spawn drowned.");
                    return true;
                }

                if (specialDrownedListener == null) {
                    sender.sendMessage("Special drowned spawning is not initialized.");
                    return true;
                }

                var drowned = specialDrownedListener.spawnSpecialDrowned(player.getLocation());
                if (drowned != null) {
                    sender.sendMessage("Spawned a special drowned!");
                } else {
                    sender.sendMessage("Failed to spawn special drowned.");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("dismount")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command.");
                    return true;
                }

                if (!sender.hasPermission("blockships.dismount")) {
                    sender.sendMessage("You don't have permission to use this command.");
                    return true;
                }

                if (ShipInstance.dismountPlayer(player)) {
                    sender.sendMessage("Dismounted from ship.");
                } else {
                    sender.sendMessage("You are not riding a ship.");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("highlightseats")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command.");
                    return true;
                }

                ShipInstance ship = findLookedAtShip(player);
                if (ship == null) {
                    sender.sendMessage("§cYou are not looking at a ship.");
                    return true;
                }

                shipWheelManager.highlightSeats(player, ship);
                return true;
            }

            if (args[0].equalsIgnoreCase("highlightcolliders")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command.");
                    return true;
                }

                ShipInstance ship = findLookedAtShip(player);
                if (ship == null) {
                    sender.sendMessage("§cYou are not looking at a ship.");
                    return true;
                }

                boolean anyGlowing = ship.colliders.stream().anyMatch(c -> c.entity.isGlowing());
                boolean newState = !anyGlowing;
                for (var c : ship.colliders) {
                    c.entity.setGlowing(newState);
                }
                sender.sendMessage(newState ? "§aColliders now glowing." : "§7Collider glow disabled.");
                return true;
            }

            if (args[0].equalsIgnoreCase("recipes")) {
                if (!sender.hasPermission("blockships.recipes")) {
                    sender.sendMessage("You don't have permission to unlock recipes.");
                    return true;
                }

                // Determine target player
                Player targetPlayer;
                if (args.length >= 2) {
                    // Target specified player
                    targetPlayer = Bukkit.getPlayer(args[1]);
                    if (targetPlayer == null) {
                        sender.sendMessage("Player not found: " + args[1]);
                        return true;
                    }
                } else {
                    // Target self (must be a player)
                    if (!(sender instanceof Player)) {
                        sender.sendMessage("Console must specify a player: /blockships recipes <player>");
                        return true;
                    }
                    targetPlayer = (Player) sender;
                }

                // Unlock all plugin recipes
                int unlockedCount = displayShip.unlockAllRecipes(targetPlayer);

                if (targetPlayer.equals(sender)) {
                    sender.sendMessage("Unlocked " + unlockedCount + " BlockShips recipe(s)!");
                } else {
                    sender.sendMessage("Unlocked " + unlockedCount + " BlockShips recipe(s) for " + targetPlayer.getName() + "!");
                    targetPlayer.sendMessage("You have been granted " + unlockedCount + " BlockShips recipe(s)!");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("forcedisassembleall")) {
                if (!sender.hasPermission("blockships.admin")) {
                    sender.sendMessage("You don't have permission to use this command.");
                    return true;
                }

                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    sender.sendMessage("§c§l⚠ WARNING ⚠");
                    sender.sendMessage("§cThis will §lFORCE-DISASSEMBLE ALL ASSEMBLED SHIPS§c!");
                    sender.sendMessage("");
                    sender.sendMessage("§7Type §e/blockships forcedisassembleall confirm §7to confirm.");
                    return true;
                }

                int count = 0;
                int failed = 0;

                // Get all wheels and force-disassemble assembled ones
                // Copy to avoid ConcurrentModificationException (disassembly updates wheel locations)
                for (ShipWheelData wheelData : new java.util.ArrayList<>(shipWheelManager.getWheels())) {
                    if (wheelData.isAssembled()) {
                        // Pass null for player - messages not needed for batch operation
                        boolean success = shipWheelManager.disassembleShip(null, wheelData, true);
                        if (success) count++;
                        else failed++;
                    }
                }

                sender.sendMessage("Force-disassembled " + count + " ship(s)" +
                    (failed > 0 ? " (" + failed + " failed)" : ""));
                return true;
            }

            if (args[0].equalsIgnoreCase("killentities")) {
                if (!sender.hasPermission("blockships.admin")) {
                    sender.sendMessage("You don't have permission to use this command.");
                    return true;
                }

                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    sender.sendMessage("§c§l⚠ WARNING ⚠");
                    sender.sendMessage("§cThis will §lDESTROY ALL BLOCKSHIPS ENTITIES§c in all worlds!");
                    sender.sendMessage("");
                    sender.sendMessage("§7Type §e/blockships killentities confirm §7to confirm.");
                    return true;
                }

                int removedCount = 0;

                // Before destroying, collect ship info for YAML cleanup
                List<ShipInstance> shipsToRemove = new ArrayList<>(ShipRegistry.getAllShips());
                int shipCount = shipsToRemove.size();

                // Destroy all registered ships (cleans up entities)
                ShipRegistry.destroyAll();

                // Clean up YAML storage for destroyed ships (only loaded chunks)
                ShipWorldData shipWorldData = displayShip.getShipWorldData();
                for (ShipInstance ship : shipsToRemove) {
                    World world = ship.vehicle.getLocation().getWorld();
                    if (world != null) {
                        shipWorldData.removeShip(world, ship.id);
                    }
                }
                shipWorldData.saveAllChunkIndices();

                // Then clean up any orphaned entities with ship tags
                for (World world : Bukkit.getWorlds()) {
                    for (Entity entity : world.getEntities()) {
                        if (ShipTags.isShipEntity(entity.getScoreboardTags())) {
                            entity.remove();
                            removedCount++;
                        }
                    }
                }

                sender.sendMessage("Destroyed " + shipCount + " registered ship(s), removed " +
                    removedCount + " orphaned entity/entities");
                return true;
            }
        }
        return false;
    }

    private void sendGiveableItems(CommandSender sender) {
        sender.sendMessage("Available items:");
        sender.sendMessage("  - ship_wheel");
        sender.sendMessage("  - captains_manual");
        var customItemsSection = getConfig().getConfigurationSection("custom-items");
        if (customItemsSection != null) {
            for (String key : customItemsSection.getKeys(false)) {
                sender.sendMessage("  - " + key);
            }
        }
        var shipsSection = getConfig().getConfigurationSection("ships");
        if (shipsSection != null) {
            for (String key : shipsSection.getKeys(false)) {
                sender.sendMessage("  - " + key);
            }
        }
    }

    private List<String> getGiveableItemNames() {
        List<String> items = new ArrayList<>();
        items.add("ship_wheel");
        items.add("captains_manual");
        var customItemsSection = getConfig().getConfigurationSection("custom-items");
        if (customItemsSection != null) {
            items.addAll(customItemsSection.getKeys(false));
        }
        var shipsSection = getConfig().getConfigurationSection("ships");
        if (shipsSection != null) {
            items.addAll(shipsSection.getKeys(false));
        }
        return items;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("blockships")) {
            return null;
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Complete subcommands based on permissions
            List<String> subcommands = new ArrayList<>();
            subcommands.add("help");
            subcommands.add("info");
            subcommands.add("dismount");
            subcommands.add("highlightseats");
            subcommands.add("highlightcolliders");
            if (sender.hasPermission("blockships.reload")) subcommands.add("reload");
            if (sender.hasPermission("blockships.give")) {
                subcommands.add("give");
                subcommands.add("spawndrowned");
            }
            if (sender.hasPermission("blockships.recipes")) subcommands.add("recipes");
            if (sender.hasPermission("blockships.admin")) {
                subcommands.add("forcedisassembleall");
                subcommands.add("killentities");
            }

            for (String sub : subcommands) {
                if (sub.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            String subcommand = args[0].toLowerCase();

            if (subcommand.equals("give") && sender.hasPermission("blockships.give")) {
                for (String type : getGiveableItemNames()) {
                    if (type.toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(type);
                    }
                }
            } else if (subcommand.equals("recipes") && sender.hasPermission("blockships.recipes")) {
                // Complete online player names
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                        completions.add(player.getName());
                    }
                }
            } else if ((subcommand.equals("forcedisassembleall") || subcommand.equals("killentities"))
                    && sender.hasPermission("blockships.admin")) {
                // Complete with "confirm" for dangerous commands
                if ("confirm".startsWith(args[1].toLowerCase())) {
                    completions.add("confirm");
                }
            }
        }

        return completions;
    }
}
