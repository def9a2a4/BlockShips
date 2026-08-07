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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class BlockShipsPlugin extends JavaPlugin {

    /** Where users should report bugs and unexpected errors. */
    public static final String ISSUES_URL = "https://github.com/def9a2a4/BlockShips/issues";

    private DisplayShip displayShip;
    private ShipSteeringListener steeringListener;
    private PaperInputListener paperInputListener;
    private ShipWheelManager shipWheelManager;
    private SpecialDrownedListener specialDrownedListener;

    @Override
    public void onEnable() {
        int pluginId = 28443;
        new Metrics(this, pluginId);

        saveDefaultConfig();

        // Warn if bundled resource files (blocks, items, prefab ships) are outdated
        ConfigValidator.checkForOutdatedResources(this);

        // Install the WorldGuard integration hook (or a no-op) before anything can assemble/disassemble
        setupWorldGuardHook();

        // Load global physics config
        ShipInstance.loadGlobalPhysicsConfig(this);

        // Initialize block configuration manager
        BlockConfigManager.initialize(this);
        BlockConfigManager.getInstance().loadConfig();

        // Load help book content from bundled YAML
        HelpBookContent.load(this);

        // Initialize ship input detection (Paper PlayerInputEvent on 1.21.2+, ProtocolLib fallback)
        if (anon.def9a2a4.blockships.util.ServerVersion.isAtLeast(1, 21, 2) && hasPaperInputEvent()) {
            paperInputListener = new PaperInputListener(this);
            getServer().getPluginManager().registerEvents(paperInputListener, this);
            getLogger().info("Using Paper PlayerInputEvent for ship controls (ProtocolLib not required)");
        } else if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            steeringListener = new ShipSteeringListener(this);
        } else {
            getLogger().warning("==================================================");
            getLogger().warning("WASD ship controls will not work!");
            getLogger().warning("Paper PlayerInputEvent not available, ProtocolLib not found.");
            getLogger().warning("Install ProtocolLib or upgrade to Paper 1.21.2+ for ship controls.");
            getLogger().warning("==================================================");
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

        // M5: rebuild delegated custom ships whose mechanisms defCoreLib recovered from chunks that loaded
        // (fired their EntitiesLoadEvent) before this plugin enabled — those recovered events had no listener
        // yet. Done AFTER loadAll so the wheels are available for stat recomputation on reconstruction.
        displayShip.forceRecoverDelegatedShips();

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

    private boolean hasPaperInputEvent() {
        try {
            Class.forName("org.bukkit.event.player.PlayerInputEvent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== BlockShips v" + getDescription().getVersion() + " ===");
        sender.sendMessage("§e/blockships help §7- Show this help message");
        if (sender.hasPermission("blockships.info")) {
            sender.sendMessage("§e/blockships info §7- Show ship and wheel statistics");
        }
        if (sender.hasPermission("blockships.dismount")) {
            sender.sendMessage("§e/blockships dismount §7- Force-dismount from a ship §8(players only)");
        }
        if (sender.hasPermission("blockships.highlight")) {
            sender.sendMessage("§e/blockships highlightseats §7- Highlight seats on the ship you're looking at §8(players only)");
            sender.sendMessage("§e/blockships highlightcolliders §7- Toggle glowing on collider shulkers §8(players only)");
        }
        if (sender.hasPermission("blockships.reload")) {
            sender.sendMessage("§e/blockships reload §7- Reload the plugin configuration");
        }
        if (sender.hasPermission("blockships.give")) {
            sender.sendMessage("§e/blockships give <item> §7- Give yourself a ship wheel or ship kit §8(players only)");
            sender.sendMessage("§e/blockships spawndrowned §7- Spawn a special drowned at your location §8(players only)");
        }
        if (sender.hasPermission("blockships.recipes")) {
            sender.sendMessage("§e/blockships recipes [player] §7- Unlock all BlockShips recipes");
        }
        if (sender.hasPermission("blockships.admin")) {
            sender.sendMessage("§e/blockships forcedisassembleall §7- Force-disassemble all assembled ships §c§l[DANGEROUS]");
            sender.sendMessage("§e/blockships killentities §7- Remove all BlockShips entities from worlds §c§l[DANGEROUS]");
        }
        sender.sendMessage("§7Found a bug? Report it at: §b" + ISSUES_URL);
    }

    /**
     * Collects the UUIDs of all persisted ships (loaded + unloaded) from
     * {@link ShipWorldData}'s in-memory chunk index across every indexed world on disk,
     * including worlds that aren't currently loaded. No chunk I/O. The returned set
     * inherently dedupes a UUID that (through corruption) appears in more than one
     * world's index. Returns an empty set if {@code displayShip} is not yet initialized.
     */
    private Set<UUID> collectPersistedShipIds() {
        if (displayShip == null) {
            return new HashSet<>();
        }
        return displayShip.getShipWorldData().getAllPersistedShipIds();
    }

    /**
     * Formats a "needs attention" count: grey when 0 (all clear), red when &gt; 0. A red 0 reads as
     * a false alarm, so zeros stay neutral. Use only for genuinely attention-worthy figures
     * (orphaned/loose/orphan-wheel style). Normal "unloaded" counts are an expected steady state and
     * should stay neutral, not red.
     */
    private String attn(int n) {
        return (n > 0 ? "§c" : "§7") + n;
    }

    /**
     * Deduped classification of every placed wheel, shared by {@link #sendStatsBreakdown} and the
     * destructive-command confirm prompts so their figures agree by construction. All ship counts are
     * distinct ship UUIDs (a UUID referenced by two wheels counts once); {@link #assembledWheelCount}
     * is the raw wheel tally, so {@link #duplicateWheelLinks()} exposes duplicate/corrupted links.
     */
    private static final class WheelStats {
        /** Assembled wheel whose ship is registered (loaded) - exactly what force-disassemble acts on. */
        final Set<UUID> registeredWithWheel = new HashSet<>();
        /** Assembled wheel whose ship is persisted (chunk index) but not registered - unloaded. */
        final Set<UUID> unloadedPersisted = new HashSet<>();
        /** Assembled wheel whose ship is neither registered nor persisted - ship gone (orphan). */
        final Set<UUID> orphan = new HashSet<>();
        /** Raw count of assembled wheels (not deduped) - for the duplicate-link check. */
        int assembledWheelCount = 0;
        int unassembledLoaded = 0;
        int unassembledUnloaded = 0;

        /** Extra assembled wheels beyond one-per-ship, i.e. duplicate/corrupted wheel links. */
        int duplicateWheelLinks() {
            return assembledWheelCount
                - (registeredWithWheel.size() + unloadedPersisted.size() + orphan.size());
        }
    }

    /** Classifies all placed wheels against the registry and the persisted-ship set. */
    private WheelStats classifyWheels(Set<UUID> persistedIds) {
        WheelStats s = new WheelStats();
        for (ShipWheelData wheel : shipWheelManager.getWheels()) {
            if (wheel.isAssembled()) {
                s.assembledWheelCount++;
                UUID u = wheel.getAssembledShipUUID();
                if (ShipRegistry.byId(u) != null) {
                    s.registeredWithWheel.add(u);
                } else if (persistedIds.contains(u)) {
                    s.unloadedPersisted.add(u);
                } else {
                    s.orphan.add(u);
                }
            } else {
                // Guard against a wheel whose world was unloaded at runtime - isChunkLoaded()
                // dereferences the world and would otherwise NPE / throw "World unloaded".
                Location wl = wheel.getBlockLocation();
                boolean loaded = wl != null && wl.getWorld() != null && wl.isWorldLoaded()
                    && wl.isChunkLoaded();
                if (loaded) s.unassembledLoaded++; else s.unassembledUnloaded++;
            }
        }
        return s;
    }

    /**
     * Prints the current ship/wheel statistics, split by loaded vs unloaded chunks.
     * Shared by the "info" subcommand and the confirmation prompts of the destructive
     * admin commands so admins can see exactly what is currently tracked.
     *
     * <p>Correctness model:
     * <ul>
     *   <li>Genuinely unloaded ships are <b>not</b> in {@link ShipRegistry} (chunk unload
     *       unregisters them). The source of truth for persisted (loaded+unloaded) ships is
     *       {@link ShipWorldData}'s chunk index ({@link #collectPersistedShipIds()}).</li>
     *   <li>An assembled wheel usually maps 1:1 to a custom {@link ShipInstance}, but a destroyed
     *       ship can leave the wheel flagged assembled ("orphan wheel"). We classify each assembled
     *       wheel by registry + persistence membership so counts stay self-consistent. customUnloaded
     *       is deduped by ship UUID and customLoaded is disjoint from it, so customTotal &lt;=
     *       totalPersisted in steady state (transient exceptions are clamped - see the derived-figures
     *       comment below).</li>
     * </ul>
     */
    private void sendStatsBreakdown(CommandSender sender) {
        Set<UUID> persistedIds = collectPersistedShipIds();
        sendStatsBreakdown(sender, persistedIds, classifyWheels(persistedIds));
    }

    /**
     * Overload for callers (the destructive-command confirm prompts) that have already computed the
     * persisted-ship set and wheel classification, so the shared figures are computed once per prompt
     * rather than recomputed here.
     */
    private void sendStatsBreakdown(CommandSender sender, Set<UUID> persistedIds, WheelStats wheels) {
        // Loaded ships come from the registry (authority on what is currently live).
        int customLoaded = 0, prefabLoaded = 0;
        for (ShipInstance ship : ShipRegistry.getAllShips()) {
            if ("custom".equals(ship.shipType)) customLoaded++; else prefabLoaded++;
        }

        int totalPersisted = persistedIds.size();

        // customUnloaded is a distinct-UUID subset of persistedIds, so it can't exceed persisted
        // membership.
        int customUnloaded = wheels.unloadedPersisted.size();
        int orphanWheels = wheels.orphan.size();
        int wheelsLoaded = wheels.unassembledLoaded;
        int wheelsUnloaded = wheels.unassembledUnloaded;

        // Derived figures. customUnloaded is a deduped subset of persistedIds, and customLoaded is
        // disjoint from it (registered vs not), so in steady state customTotal <= totalPersisted. The
        // Math.max clamps below are still needed for two transient/edge cases: a just-registered ship
        // not yet in the chunk index (loaded can momentarily exceed persisted), and a custom ship
        // persisted in an unloaded chunk whose wheel link was lost (counts here as a prefab -
        // distinguishing it would need per-ship YAML I/O). Display-only; never crashes.
        int customTotal = customLoaded + customUnloaded;
        int prefabTotal = Math.max(0, totalPersisted - customTotal);
        int prefabUnloaded = Math.max(0, prefabTotal - prefabLoaded);
        int totalLoaded = customLoaded + prefabLoaded;
        int totalUnloaded = Math.max(0, totalPersisted - totalLoaded);

        // "unloaded" counts are a normal steady state - keep them neutral (§f), not red.
        sender.sendMessage("§6=== BlockShips Stats ===");
        sender.sendMessage("§ePrefab Ships: §f" + prefabTotal + " total §7(§a" + prefabLoaded
            + " loaded§7, §f" + prefabUnloaded + " unloaded§7)");
        sender.sendMessage("§eCustom Ships: §f" + customTotal + " total §7(§a" + customLoaded
            + " loaded§7, §f" + customUnloaded + " unloaded§7)");
        sender.sendMessage("§eAll Ships: §f" + totalPersisted + " persisted §7(§a" + totalLoaded
            + " loaded§7, §f" + totalUnloaded + " unloaded§7)");
        sender.sendMessage("§eUnassembled Wheels: §f" + (wheelsLoaded + wheelsUnloaded)
            + " total §7(§a" + wheelsLoaded + " loaded§7, §f" + wheelsUnloaded + " unloaded§7)");
        if (orphanWheels > 0) {
            sender.sendMessage("§cOrphaned wheel links: §f" + orphanWheels
                + " §7(ship gone; break the wheel block to clear)");
        }
        // Divergence = duplicate/corrupted wheel links (more assembled wheels than distinct ships).
        // Surface it explicitly rather than let the raw and deduped counts silently disagree.
        int dupLinks = wheels.duplicateWheelLinks();
        if (dupLinks > 0) {
            sender.sendMessage("§c⚠ " + dupLinks + " assembled wheel(s) share a ship link"
                + " (duplicate/corrupted wheel data) - break the extra wheel block(s).");
        }
    }

    /**
     * Prints a compact summary of BlockShips-tagged entities in currently loaded chunks:
     * a registered/orphaned/unattributed ship split plus a by-entity-type rollup. Used by the
     * {@code killentities} confirmation prompt. Returns the total tagged-entity count so callers
     * can reference it in the warning.
     *
     * <p>Only loaded chunks are visible here - {@link World#getEntities()} never loads chunks.
     */
    private int sendEntitySummary(CommandSender sender) {
        Set<UUID> registeredShips = new HashSet<>();
        Set<UUID> orphanedShips = new HashSet<>();
        int unattributed = 0;
        int total = 0;
        Map<String, Integer> byType = new HashMap<>();

        // Admin-only command: a single-tick scan of every entity in every loaded world is
        // acceptable here (not run on a hot path).
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                Set<String> tags = entity.getScoreboardTags();
                if (!ShipTags.isShipEntity(tags)) continue;
                total++;
                byType.merge(entity.getType().name(), 1, Integer::sum);

                UUID shipId = ShipTags.extractShipId(tags);
                if (shipId == null) {
                    unattributed++;
                } else if (ShipRegistry.byId(shipId) != null) {
                    registeredShips.add(shipId);
                } else {
                    orphanedShips.add(shipId);
                }
            }
        }

        // Build a "by type" line sorted by count descending.
        List<Map.Entry<String, Integer>> types = new ArrayList<>(byType.entrySet());
        types.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder typeLine = new StringBuilder();
        for (Map.Entry<String, Integer> e : types) {
            if (typeLine.length() > 0) typeLine.append("§7, §f");
            typeLine.append(e.getValue()).append(" §e").append(e.getKey());
        }

        sender.sendMessage("§eTagged entities in loaded chunks: §f" + total);
        sender.sendMessage("§7  Ships with entities here — loaded: §a" + registeredShips.size()
            + "§7, orphaned: " + attn(orphanedShips.size()) + "§7; loose entities: "
            + attn(unattributed));
        if (typeLine.length() > 0) {
            sender.sendMessage("§7  By type: §f" + typeLine);
        }
        return total;
    }

    /**
     * Installs the WorldGuard integration hook (when WorldGuard is present and the integration is enabled
     * in config) or a no-op hook otherwise. Idempotent — called on enable and on reload, so toggling
     * {@code plugins.worldguard.enabled} takes effect in both directions. Only touches WorldGuard classes
     * from inside a {@code Class.forName} guard, so a missing/broken WorldGuard can never abort startup.
     */
    private void setupWorldGuardHook() {
        boolean enabled = getConfig().getBoolean("plugins.worldguard.enabled", true);
        String systemDisassembly = getConfig().getString("plugins.worldguard.system-disassembly-in-region", "drop-items");
        boolean systemPlacesAnyway = "place-anyway".equalsIgnoreCase(systemDisassembly);
        if (!systemPlacesAnyway && systemDisassembly != null && !systemDisassembly.isBlank()
                && !"drop-items".equalsIgnoreCase(systemDisassembly)) {
            getLogger().warning("Unrecognized plugins.worldguard.system-disassembly-in-region '" + systemDisassembly
                + "'; expected drop-items or place-anyway. Using drop-items.");
        }
        if (enabled && Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                Class.forName("com.sk89q.worldguard.WorldGuard");
                // Re-arm the fail-open error log so a transient fault before this reload doesn't stay silent.
                anon.def9a2a4.blockships.integration.WorldGuardHookImpl.resetErrorThrottle();
                anon.def9a2a4.blockships.integration.WorldGuardHook.set(
                    new anon.def9a2a4.blockships.integration.WorldGuardHookImpl(systemPlacesAnyway));
                getLogger().info("WorldGuard integration ENABLED: ships now respect region build permissions "
                    + "(protected-region disassembly drops blocks as items; assembly/placement/breaking denied). "
                    + "Unattended/crash disassembly in a region: " + (systemPlacesAnyway ? "place-anyway" : "drop-items")
                    + ". Disable via plugins.worldguard.enabled in config.yml.");
            } catch (Throwable t) {
                anon.def9a2a4.blockships.integration.WorldGuardHook.set(
                    new anon.def9a2a4.blockships.integration.NoOpWorldGuardHook());
                getLogger().warning("WorldGuard present but its API failed to load; integration disabled: " + t);
            }
        } else {
            anon.def9a2a4.blockships.integration.WorldGuardHook.set(
                new anon.def9a2a4.blockships.integration.NoOpWorldGuardHook());
            if (!enabled) {
                getLogger().info("WorldGuard integration disabled (plugins.worldguard.enabled: false).");
            } else {
                getLogger().info("WorldGuard not installed; region protection integration inactive.");
            }
        }
    }

    /** Builds a human-readable "ship &lt;uuid&gt; (wheel at &lt;world&gt; x,y,z)" descriptor for console logs. */
    private String describeWheel(ShipWheelData wheelData) {
        Location loc = wheelData.getBlockLocation();
        String world = (loc != null && loc.getWorld() != null) ? loc.getWorld().getName() : "?";
        String coords = (loc != null)
            ? loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
            : "?";
        return "ship " + wheelData.getAssembledShipUUID() + " (wheel at " + world + " " + coords + ")";
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

                UUID shipId = ShipTags.extractShipId(shulker.getScoreboardTags());
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
            if (steeringListener == null && paperInputListener == null) {
                sender.sendMessage("");
                sender.sendMessage("§c§l⚠ WARNING: No ship input handler active! ⚠");
                sender.sendMessage("§cWASD ship controls will not work.");
                sender.sendMessage("§7Install ProtocolLib or upgrade to Paper 1.21.2+");
                sender.sendMessage("");
            }

            // Show help when no args or "help" subcommand
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sendHelp(sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("info")) {
                if (!sender.hasPermission("blockships.info")) {
                    sender.sendMessage("§cYou don't have permission to view BlockShips stats.");
                    return true;
                }
                sendStatsBreakdown(sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("blockships.reload")) {
                    sender.sendMessage("§cYou don't have permission to reload this plugin.");
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
                // Re-apply the WorldGuard integration (installs impl or no-op per the possibly-toggled config)
                setupWorldGuardHook();
                // Reload help book content
                HelpBookContent.load(this);
                // Reload special drowned config, and re-sync its event registration with the
                // (possibly toggled) enabled state - reloadConfig alone leaves a disabled->enabled
                // flip inert (or an enabled->disabled flip still firing) until a full restart.
                if (specialDrownedListener != null) {
                    specialDrownedListener.reloadConfig();
                    org.bukkit.event.HandlerList.unregisterAll(specialDrownedListener);
                    if (specialDrownedListener.isEnabled()) {
                        Bukkit.getPluginManager().registerEvents(specialDrownedListener, this);
                    }
                }
                sender.sendMessage("§aBlockShips config reloaded!");
                return true;
            }

            if (args[0].equalsIgnoreCase("give")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can use this command.");
                    return true;
                }

                if (!sender.hasPermission("blockships.give")) {
                    sender.sendMessage("§cYou don't have permission to give ship kits.");
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
                    giveOrDrop(player, wheel);
                    sender.sendMessage("§aGave you a ship wheel!");
                    return true;
                }

                // Captain's Manual (written book)
                if (itemType.equals("captains_manual")) {
                    ItemStack manual = HelpBookContent.createWrittenBook();
                    giveOrDrop(player, manual);
                    sender.sendMessage("§aGave you a Captain's Manual!");
                    return true;
                }

                // Custom items (balloon, etc.)
                if (getConfig().contains("custom-items." + itemType)) {
                    ItemStack item = displayShip.getItemFactory().createItem(itemType, "_DEFAULT", null);
                    giveOrDrop(player, item);
                    sender.sendMessage("§aGave you a " + itemType + "!");
                    return true;
                }

                // Ship kits
                if (getConfig().contains("ships." + itemType)) {
                    ItemStack defaultBanner = new ItemStack(Material.WHITE_BANNER);
                    ItemStack shipKit = DisplayShip.createShipKit(itemType, defaultBanner, "SPRUCE", this);
                    giveOrDrop(player, shipKit);
                    sender.sendMessage("§aGave you a " + itemType + " ship kit!");
                    return true;
                }

                sender.sendMessage("§cUnknown item: " + itemType);
                sendGiveableItems(sender);
                return true;
            }

            if (args[0].equalsIgnoreCase("spawndrowned")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can use this command.");
                    return true;
                }

                if (!sender.hasPermission("blockships.give")) {
                    sender.sendMessage("§cYou don't have permission to spawn drowned (requires the give permission).");
                    return true;
                }

                if (specialDrownedListener == null) {
                    sender.sendMessage("§cSpecial drowned spawning is not initialized - the plugin may not have"
                        + " enabled correctly. Please report at " + ISSUES_URL);
                    return true;
                }

                var drowned = specialDrownedListener.spawnSpecialDrowned(player.getLocation());
                if (drowned != null) {
                    sender.sendMessage("§aSpawned a special drowned!");
                } else {
                    sender.sendMessage("§cFailed to spawn special drowned: your location has no valid world.");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("dismount")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can use this command.");
                    return true;
                }

                if (!sender.hasPermission("blockships.dismount")) {
                    sender.sendMessage("§cYou don't have permission to dismount from ships.");
                    return true;
                }

                if (ShipInstance.dismountPlayer(player)) {
                    sender.sendMessage("§aDismounted from ship.");
                } else {
                    sender.sendMessage("You are not riding a ship.");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("highlightseats")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cOnly players can use this command.");
                    return true;
                }

                if (!sender.hasPermission("blockships.highlight")) {
                    sender.sendMessage("§cYou don't have permission to use highlight commands.");
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
                    sender.sendMessage("§cOnly players can use this command.");
                    return true;
                }

                if (!sender.hasPermission("blockships.highlight")) {
                    sender.sendMessage("§cYou don't have permission to use highlight commands.");
                    return true;
                }

                ShipInstance ship = findLookedAtShip(player);
                if (ship == null) {
                    sender.sendMessage("§cYou are not looking at a ship.");
                    return true;
                }

                // Gather the collider shulkers from whichever engine owns them: a delegated ship has no native
                // `colliders` list (the Mechanism owns the shulkers), so glow those instead — else this command
                // was dead on delegated ships (unlike its sibling highlightseats, which reads seatShulkers).
                java.util.List<org.bukkit.entity.Shulker> colliderShulkers = new java.util.ArrayList<>();
                if (ship.mechanism != null) {
                    int n = ship.mechanism.blockCount();
                    for (int i = 0; i < n; i++) {
                        org.bukkit.entity.Shulker s = ship.mechanism.colliderEntity(i);
                        if (s != null && s.isValid()) colliderShulkers.add(s);
                    }
                } else {
                    for (var c : ship.colliders) {
                        if (c.entity != null && c.entity.isValid()) colliderShulkers.add(c.entity);
                    }
                }

                if (colliderShulkers.isEmpty()) {
                    sender.sendMessage("§7That ship has no colliders to highlight.");
                    return true;
                }

                // Skip invalid (removed) collider entities, mirroring highlightSeats' defensive checks.
                boolean anyGlowing = colliderShulkers.stream().anyMatch(org.bukkit.entity.Shulker::isGlowing);
                boolean newState = !anyGlowing;
                for (org.bukkit.entity.Shulker s : colliderShulkers) {
                    s.setGlowing(newState);
                }
                sender.sendMessage(newState ? "§aColliders now glowing." : "§7Collider glow disabled.");
                return true;
            }

            if (args[0].equalsIgnoreCase("recipes")) {
                if (!sender.hasPermission("blockships.recipes")) {
                    sender.sendMessage("§cYou don't have permission to unlock recipes.");
                    return true;
                }

                // Determine target player
                Player targetPlayer;
                if (args.length >= 2) {
                    // Target specified player
                    targetPlayer = Bukkit.getPlayer(args[1]);
                    if (targetPlayer == null) {
                        sender.sendMessage("§cPlayer not found: " + args[1]);
                        return true;
                    }
                } else {
                    // Target self (must be a player)
                    if (!(sender instanceof Player)) {
                        sender.sendMessage("§cConsole must specify a player: /blockships recipes <player>");
                        return true;
                    }
                    targetPlayer = (Player) sender;
                }

                // Unlock all plugin recipes
                int unlockedCount = displayShip.unlockAllRecipes(targetPlayer);

                if (targetPlayer.equals(sender)) {
                    sender.sendMessage("§aUnlocked " + unlockedCount + " BlockShips recipe(s)!");
                } else {
                    sender.sendMessage("§aUnlocked " + unlockedCount + " BlockShips recipe(s) for " + targetPlayer.getName() + "!");
                    targetPlayer.sendMessage("§aYou have been granted " + unlockedCount + " BlockShips recipe(s)!");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("forcedisassembleall")) {
                if (!sender.hasPermission("blockships.admin")) {
                    sender.sendMessage("§cYou don't have permission to use admin commands.");
                    return true;
                }

                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    // Derive the figures from the same classification the stats use, so the "will
                    // disassemble" count can't disagree with the breakdown printed above. Only assembled
                    // wheels whose ship is registered are actionable - the disassemble loop below acts
                    // on exactly that set (registeredWithWheel), custom or prefab.
                    Set<UUID> persisted = collectPersistedShipIds();
                    WheelStats ws = classifyWheels(persisted);
                    int willDisassemble = ws.registeredWithWheel.size();
                    int untouched = ws.unloadedPersisted.size() + ws.orphan.size();

                    sendStatsBreakdown(sender, persisted, ws);
                    sender.sendMessage("");
                    sender.sendMessage("§c§l⚠ WARNING ⚠");
                    sender.sendMessage("§cThis will §lFORCE-DISASSEMBLE§c the §e" + willDisassemble
                        + "§c currently active ship(s).");
                    if (untouched > 0) {
                        sender.sendMessage("§7" + untouched + " assembled wheel(s) are not currently active ("
                            + ws.unloadedPersisted.size() + " unloaded, " + ws.orphan.size()
                            + " orphaned - see above) and will be left untouched.");
                    }
                    sender.sendMessage("");
                    sender.sendMessage("§7Type §e/blockships forcedisassembleall confirm §7to confirm.");
                    return true;
                }

                int count = 0;
                int failed = 0;
                int skipped = 0;
                int saveErrors = 0;

                // Get all wheels and force-disassemble assembled ones.
                // Copy to avoid ConcurrentModificationException (disassembly updates wheel locations).
                for (ShipWheelData wheelData : new ArrayList<>(shipWheelManager.getWheels())) {
                    if (!wheelData.isAssembled()) continue;

                    // Skip ships that are not active (unloaded/gone). Calling disassembleShip on them
                    // would silently sever the wheel link (ShipWheelManager clears it when the ship
                    // is not registered), corrupting a ship that still exists in an unloaded chunk.
                    if (ShipRegistry.byId(wheelData.getAssembledShipUUID()) == null) {
                        skipped++;
                        continue;
                    }

                    // Pass null for player - messages not needed for batch operation
                    String where = describeWheel(wheelData);
                    ShipWheelManager.DisassembleOutcome outcome = new ShipWheelManager.DisassembleOutcome();
                    try {
                        boolean success = shipWheelManager.disassembleShip(null, wheelData, true, outcome);
                        if (success) {
                            count++;
                            // Ship is disassembled in-world, but its on-disk cleanup failed to save.
                            // The failure is already logged with full context by disassembleShip and
                            // its callees, so just tally it here (no duplicate caller log line).
                            if (outcome.persistFailed) saveErrors++;
                        } else {
                            failed++;
                            getLogger().warning("forcedisassembleall: disassembleShip returned false for "
                                + where);
                        }
                    } catch (Exception e) {
                        failed++;
                        getLogger().log(Level.WARNING,
                            "forcedisassembleall: exception disassembling " + where, e);
                    }
                }

                getLogger().info(sender.getName() + " ran forcedisassembleall: disassembled " + count
                    + " ship(s)" + (failed > 0 ? ", " + failed + " failed" : "")
                    + (saveErrors > 0 ? ", " + saveErrors + " with save errors" : "")
                    + (skipped > 0 ? ", " + skipped + " skipped (not active)" : ""));

                // disassembleShip mutates in-memory wheel state (clears the link, updates the block
                // location) but does not persist. Save once here so ship_wheels.yml isn't stale
                // until an unrelated save fires (a restart in that window would reload wheels flagged
                // assembled pointing at ships that no longer exist). Gate on count so a no-op run
                // doesn't rewrite the file.
                if (count > 0 && !shipWheelManager.saveAll()) {
                    sender.sendMessage("§cFailed to save wheel state - check the server console for details.");
                }

                sender.sendMessage("Force-disassembled " + count + " ship(s)" +
                    (failed > 0 ? " (" + failed + " failed)" : ""));
                if (failed > 0) {
                    sender.sendMessage("§7" + failed + " ship(s) failed to disassemble - check the server"
                        + " console for details.");
                }
                if (saveErrors > 0) {
                    sender.sendMessage("§c" + saveErrors + " of those were disassembled but their on-disk"
                        + " cleanup failed to save - check the server console for details.");
                }
                if (skipped > 0) {
                    sender.sendMessage("§7" + skipped + " assembled wheel(s) were skipped (not active -"
                        + " unloaded, or the ship no longer exists).");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("killentities")) {
                if (!sender.hasPermission("blockships.admin")) {
                    sender.sendMessage("§cYou don't have permission to use admin commands.");
                    return true;
                }

                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    Set<UUID> persisted = collectPersistedShipIds();
                    int registered = ShipRegistry.getAllShips().size();
                    int unloadedShips = Math.max(0, persisted.size() - registered);

                    sendStatsBreakdown(sender, persisted, classifyWheels(persisted));
                    sender.sendMessage("");
                    int taggedEntities = sendEntitySummary(sender);
                    sender.sendMessage("");
                    sender.sendMessage("§c§l⚠ WARNING ⚠");
                    sender.sendMessage("§cThis will §lDESTROY§c §e" + registered
                        + "§c registered ship(s) in §lLOADED§c chunks.");
                    sender.sendMessage("§cAll §e" + taggedEntities
                        + "§c ship-tagged entity/entities in loaded chunks (including those ships') will be removed.");
                    if (unloadedShips > 0) {
                        sender.sendMessage("§7" + unloadedShips + " ship(s) persisted in unloaded chunks will"
                            + " NOT be removed (load/visit them first).");
                    }
                    sender.sendMessage("");
                    sender.sendMessage("§7Type §e/blockships killentities confirm §7to confirm.");
                    return true;
                }

                int removedCount = 0;
                boolean cleanupFailed = false;

                // Before destroying, snapshot each registered ship's id + world. Capture the world
                // BEFORE destroyAll() removes the vehicle (matches ShipInstance.destroyWithCleanup);
                // destroyedShipIds scopes the wheel-link cleanup below to ONLY registered ships this
                // command fully destroys. We deliberately do NOT add swept-entity UUIDs here: a
                // chunk-straddling ship (root chunk unloaded, a collider in a loaded chunk) is
                // unregistered but its collider would be swept - clearing its link would sever a ship
                // that still persists and will recover.
                List<ShipInstance> shipsToRemove = new ArrayList<>(ShipRegistry.getAllShips());
                int shipCount = shipsToRemove.size();
                Map<UUID, World> worldById = new HashMap<>();
                Set<UUID> destroyedShipIds = new HashSet<>();
                for (ShipInstance ship : shipsToRemove) {
                    destroyedShipIds.add(ship.id);
                    // ship.vehicle can be null on a failed assembly.
                    World world = (ship.vehicle != null && ship.vehicle.getLocation() != null)
                        ? ship.vehicle.getLocation().getWorld() : null;
                    if (world != null) worldById.put(ship.id, world);
                }

                // Destroy all registered ships (cleans up entities). Destroy per-ship so one ship's
                // failure is logged and counted instead of aborting the whole command and leaving the
                // registry half-cleared (plain ShipRegistry.destroyAll() has no per-ship isolation).
                int destroyFailed = 0;
                for (ShipInstance ship : shipsToRemove) {
                    try {
                        ship.destroy();
                    } catch (Exception e) {
                        destroyFailed++;
                        // destroy() unregisters as its last step, so a mid-destroy throw would leave a
                        // phantom entry in the registry (entities swept, but still "loaded"). Force the
                        // unregister here to restore the old destroyAll() safety net. destroyFailed has
                        // its own message, so this is NOT folded into cleanupFailed (avoids a duplicate
                        // "some cleanup failed" line for the same event).
                        ShipRegistry.unregister(ship);
                        getLogger().log(Level.SEVERE, "killentities: failed to destroy ship " + ship.id
                            + " (type=" + ship.shipType + ")", e);
                    }
                }

                // Clean up YAML storage for destroyed ships (only loaded chunks).
                if (displayShip != null) {
                    ShipWorldData shipWorldData = displayShip.getShipWorldData();
                    for (ShipInstance ship : shipsToRemove) {
                        World world = worldById.get(ship.id);
                        if (world == null) {
                            // Unresolved world (null/invalid vehicle): can't target the per-world YAML,
                            // so log it rather than skip silently. Failed-assembly ships usually have no
                            // file, so this is a diagnostic, not counted into cleanupFailed.
                            getLogger().severe("killentities: skipped YAML cleanup for ship " + ship.id
                                + " (type=" + ship.shipType + "): world unresolved");
                            continue;
                        }
                        try {
                            if (!shipWorldData.removeShip(world, ship.id)) cleanupFailed = true;
                        } catch (Exception e) {
                            cleanupFailed = true;
                            getLogger().log(Level.SEVERE, "killentities: YAML cleanup failed for ship " + ship.id
                                + " (type=" + ship.shipType + ", world=" + world.getName() + ")", e);
                        }
                    }
                    // saveAllChunkIndices() logs SEVERE per world on failure and returns false; check it
                    // so cleanupFailed reflects a failed chunks.yml write (previously swallowed).
                    if (!shipWorldData.saveAllChunkIndices()) cleanupFailed = true;
                } else {
                    cleanupFailed = true;
                    getLogger().severe("killentities: displayShip not initialized; skipped YAML cleanup");
                }
                // Known limitation (LOW, pre-existing): a queued async metadata/index save can
                // re-write a just-destroyed ship's .yml / chunk-index entry after this synchronous
                // cleanup, leaving a leaked file / stale index entry. It self-heals (next chunk load
                // prunes zero-entity entries; startup validation drops missing-metadata entries) and
                // never yields a live phantom ship. Inherent to the existing I/O pipeline.

                // Then clean up any remaining ship-tagged entities (orphans + any not removed by
                // destroy()). Isolate per-entity so one bad remove() doesn't abort the sweep.
                for (World world : Bukkit.getWorlds()) {
                    for (Entity entity : world.getEntities()) {
                        if (ShipTags.isShipEntity(entity.getScoreboardTags())) {
                            try {
                                entity.remove();
                                removedCount++;
                            } catch (Exception e) {
                                cleanupFailed = true;
                                getLogger().log(Level.SEVERE, "killentities: failed to remove tagged entity "
                                    + entity.getUniqueId() + " (" + entity.getType().name() + ")", e);
                            }
                        }
                    }
                }

                // Clear wheel links only for the registered ships this command destroyed (see the
                // destroyedShipIds comment above - swept-entity UUIDs are intentionally excluded so a
                // recoverable straddling ship's link is never severed). Pre-existing orphan wheels
                // (ship already gone before this run) are left for manual cleanup and remain visible
                // via the stats "Orphaned wheel links" line.
                int clearedLinks = 0;
                for (ShipWheelData wheel : shipWheelManager.getWheels()) {
                    if (wheel.isAssembled() && destroyedShipIds.contains(wheel.getAssembledShipUUID())) {
                        wheel.setAssembledShipUUID(null);
                        clearedLinks++;
                    }
                }
                if (clearedLinks > 0 && !shipWheelManager.saveAll()) {
                    cleanupFailed = true;
                }

                int destroyedOk = shipCount - destroyFailed;
                getLogger().info(sender.getName() + " ran killentities: destroyed " + destroyedOk + "/"
                    + shipCount + " registered ship(s), removed " + removedCount
                    + " stray tagged entity/entities, cleared " + clearedLinks + " wheel link(s)"
                    + (destroyFailed > 0 ? ", " + destroyFailed + " failed to destroy" : "")
                    + (cleanupFailed ? " (cleanup had errors)" : ""));

                sender.sendMessage("Destroyed " + destroyedOk + " registered ship(s) and their entities; removed "
                    + removedCount + " additional tagged entity/entities" + (clearedLinks > 0
                        ? ", cleared " + clearedLinks + " wheel link(s)" : ""));
                if (destroyFailed > 0) {
                    sender.sendMessage("§c" + destroyFailed + " ship(s) failed to destroy - check the server"
                        + " console for details.");
                }
                if (cleanupFailed) {
                    sender.sendMessage("§cSome cleanup failed - check the server console for details.");
                }
                sender.sendMessage("§7Note: only entities in loaded chunks were affected; ships in unloaded"
                    + " chunks remain.");
                return true;
            }

            // Unrecognized subcommand - show help instead of the raw plugin.yml usage string
            sender.sendMessage("§cUnknown subcommand: " + args[0]);
            sendHelp(sender);
            return true;
        }
        return false;
    }

    private void sendGiveableItems(CommandSender sender) {
        sender.sendMessage("Available items:");
        for (String name : getGiveableItemNames()) {
            sender.sendMessage("  - " + name);
        }
    }

    private List<String> getGiveableItemNames() {
        // LinkedHashSet: ship_wheel/captains_manual are also present in the custom-items config section,
        // so a plain list would show them twice. Dedupe while preserving order.
        java.util.Set<String> items = new java.util.LinkedHashSet<>();
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
        return new ArrayList<>(items);
    }

    /**
     * Gives an item to a player, dropping any that doesn't fit at their feet instead of silently losing it
     * (Bukkit's addItem returns leftovers when the inventory is full and does NOT auto-drop them).
     */
    private void giveOrDrop(org.bukkit.entity.Player p, ItemStack item) {
        if (item == null) return;
        for (ItemStack leftover : p.getInventory().addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
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
            if (sender.hasPermission("blockships.info")) subcommands.add("info");
            if (sender.hasPermission("blockships.dismount")) subcommands.add("dismount");
            if (sender.hasPermission("blockships.highlight")) {
                subcommands.add("highlightseats");
                subcommands.add("highlightcolliders");
            }
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
