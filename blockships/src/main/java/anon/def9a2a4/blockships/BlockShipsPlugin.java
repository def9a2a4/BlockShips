package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.blockconfig.BlockConfigManager;
import anon.def9a2a4.blockships.customships.ShipWheelData;
import anon.def9a2a4.blockships.customships.ShipWheelManager;
import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import java.util.ArrayList;
import java.util.List;

public class BlockShipsPlugin extends JavaPlugin {

    private DisplayShip displayShip;
    private ShipSteeringListener steeringListener;
    private ShipWheelManager shipWheelManager;

    @Override
    public void onEnable() {
        int pluginId = 28443;
        new Metrics(this, pluginId);

        saveDefaultConfig();

        // Load global physics config
        ShipInstance.loadGlobalPhysicsConfig(this);

        // Initialize block configuration manager
        BlockConfigManager.initialize(this);
        BlockConfigManager.getInstance().loadConfig();

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

        // Initialize and register DisplayShip
        displayShip = new DisplayShip(this);
        displayShip.initialize();
        Bukkit.getPluginManager().registerEvents(displayShip, this);

        // Initialize ShipWheelManager for custom block ships and load saved wheels
        shipWheelManager = new ShipWheelManager(this);
        shipWheelManager.loadAll();

        getLogger().info("BlockShips enabled.");
    }

    @Override
    public void onDisable() {
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
        if (sender.hasPermission("blockships.reload")) {
            sender.sendMessage("§e/blockships reload §7- Reload the plugin configuration");
        }
        if (sender.hasPermission("blockships.give")) {
            sender.sendMessage("§e/blockships give <item> §7- Give yourself a ship wheel or ship kit");
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
                if (displayShip != null) {
                    displayShip.reload();
                }
                // Reload block configuration
                BlockConfigManager.getInstance().reloadConfig();
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
                    sender.sendMessage("Available items:");
                    sender.sendMessage("  - ship_wheel");
                    var shipsSection = getConfig().getConfigurationSection("ships");
                    if (shipsSection != null) {
                        for (String shipType : shipsSection.getKeys(false)) {
                            sender.sendMessage("  - " + shipType);
                        }
                    }
                    return true;
                }

                String itemType = args[1].toLowerCase();

                // Handle ship_wheel specially
                if (itemType.equals("ship_wheel")) {
                    ItemStack wheel = displayShip.createShipWheelItem();
                    player.getInventory().addItem(wheel);
                    sender.sendMessage("Gave you a ship wheel!");
                    return true;
                }

                // Verify ship type exists in config
                if (!getConfig().contains("ships." + itemType)) {
                    sender.sendMessage("Unknown item: " + itemType);
                    sender.sendMessage("Available items:");
                    sender.sendMessage("  - ship_wheel");
                    var shipsSection = getConfig().getConfigurationSection("ships");
                    if (shipsSection != null) {
                        for (String type : shipsSection.getKeys(false)) {
                            sender.sendMessage("  - " + type);
                        }
                    }
                    return true;
                }

                // Create ship kit with default wood (SPRUCE) and banner (WHITE)
                ItemStack defaultBanner = new ItemStack(Material.WHITE_BANNER);
                ItemStack shipKit = DisplayShip.createShipKit(itemType, defaultBanner, "SPRUCE", this);

                // Give to player
                player.getInventory().addItem(shipKit);
                sender.sendMessage("Gave you a " + itemType + " ship kit!");
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
            if (sender.hasPermission("blockships.reload")) subcommands.add("reload");
            if (sender.hasPermission("blockships.give")) subcommands.add("give");
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
                // Complete with ship_wheel and ship types from config
                List<String> types = new ArrayList<>();
                types.add("ship_wheel");
                var shipsSection = getConfig().getConfigurationSection("ships");
                if (shipsSection != null) {
                    types.addAll(shipsSection.getKeys(false));
                }

                for (String type : types) {
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
