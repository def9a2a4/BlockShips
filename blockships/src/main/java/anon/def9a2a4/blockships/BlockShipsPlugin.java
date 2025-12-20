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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("blockships")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
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

            if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can use this command.");
                    return true;
                }

                if (!sender.hasPermission("blockships.give")) {
                    sender.sendMessage("You don't have permission to give ship kits.");
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage("Usage: /blockships give <shiptype>");
                    sender.sendMessage("Available ship types:");
                    var shipsSection = getConfig().getConfigurationSection("ships");
                    if (shipsSection != null) {
                        for (String shipType : shipsSection.getKeys(false)) {
                            sender.sendMessage("  - " + shipType);
                        }
                    }
                    return true;
                }

                String shipType = args[1].toLowerCase();

                // Verify ship type exists in config
                if (!getConfig().contains("ships." + shipType)) {
                    sender.sendMessage("Unknown ship type: " + shipType);
                    sender.sendMessage("Available ship types:");
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
                ItemStack shipKit = DisplayShip.createShipKit(shipType, defaultBanner, "SPRUCE", this);

                // Give to player
                player.getInventory().addItem(shipKit);
                sender.sendMessage("Gave you a " + shipType + " ship kit!");
                return true;
            }

            if (args.length > 0 && args[0].equalsIgnoreCase("recipes")) {
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

            if (args.length > 0 && args[0].equalsIgnoreCase("forcedisassembleall")) {
                if (!sender.hasPermission("blockships.admin")) {
                    sender.sendMessage("You don't have permission to use this command.");
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

            if (args.length > 0 && args[0].equalsIgnoreCase("killentities")) {
                if (!sender.hasPermission("blockships.admin")) {
                    sender.sendMessage("You don't have permission to use this command.");
                    return true;
                }

                int removedCount = 0;

                // First destroy all registered ships (cleans up properly)
                int shipCount = ShipRegistry.getAllShips().size();
                ShipRegistry.destroyAll();

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
}
