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

        // Did config.yml actually parse? Bukkit answers a YAML error with an empty config backed by jar
        // defaults, so nothing downstream - including the migration below - can tell on its own.
        ConfigValidator.MainConfigStatus configStatus = ConfigValidator.checkMainConfig(this);

        // Upgrade an existing config.yml. saveDefaultConfig() writes the file once and never again, so
        // a changed default only reaches a running server through here.
        ConfigMigration.run(this, configStatus);

        // Warn about files on disk that the plugin does not read, and overrides that are going stale
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

        // Register the wheel as a defCoreLib custom head block. Must run BEFORE any path that can land a
        // carried wheel (forceRecoverDelegatedShips / migrateLoadedChunks below), because a landing block
        // whose type is not registered gets no PDC restored at all. Needs DisplayShip's texture manager,
        // hence its position after initialize().
        anon.def9a2a4.blockships.customships.ShipWheelBlockType.register(this);

        // List the prefab ships in the DefCoreLib catalog (flat under "blockships"), admin-give only.
        anon.def9a2a4.blockships.customships.PrefabShipCatalog.register(this);

        // Initialize ShipWheelManager for custom block ships and load saved wheels
        shipWheelManager = new ShipWheelManager(this);
        shipWheelManager.loadAll();

        // Make ship wheels defCoreLib glue anchors, so players can brush blocks the blocks.yml
        // allow-list would otherwise forbid (dirt, stone, grass) onto a ship. Registered after the
        // wheel manager exists, since the provider looks wheels up through it.
        anon.def9a2a4.blockships.customships.ShipWheelAnchors.register(this);

        // Quarantine unreadable ship sidecars ONCE, here — after loadAll, and before anything that consumes a
        // sidecar. Both consumers below (forceRecoverDelegatedShips -> reconstructDelegatedShip, and
        // migrateLoadedChunks -> migrateNativeShip/reapStragglerEntities) run the migrator before the reaper
        // on the same chunk, so quarantining from inside the shared loader would flip CORRUPT to ABSENT
        // between them and the reaper would delete a live ship's entities.
        int quarantined = displayShip.getShipWorldData().quarantineCorruptSidecars();
        if (quarantined > 0) {
            getLogger().severe("Quarantined " + quarantined + " corrupt ship sidecar(s) to *.yml.corrupt. "
                + "Those ships cannot be rebuilt, but nothing was deleted and no entities were reaped.");
        }

        // M5: rebuild delegated custom ships whose mechanisms defCoreLib recovered from chunks that loaded
        // (fired their EntitiesLoadEvent) before this plugin enabled — those recovered events had no listener
        // yet. Done AFTER loadAll so the wheels are available for stat recomputation on reconstruction.
        displayShip.forceRecoverDelegatedShips();

        // Migrate any released-0.0.17 NATIVE ships in already-loaded (spawn) chunks into delegated mechanisms.
        // Runs AFTER forceRecoverDelegatedShips so corelib has already recovered delegated ships (byId set) before
        // the migration idempotency probe — shrinks the crash-mid-migration re-spawn window (#3).
        displayShip.migrateLoadedChunks();

        // Initialize special drowned listener (spawns drowned holding ship wheels)
        specialDrownedListener = new SpecialDrownedListener(this);
        if (specialDrownedListener.isEnabled()) {
            Bukkit.getPluginManager().registerEvents(specialDrownedListener, this);
            getLogger().info("Special drowned spawning enabled.");
        }

        // Last, so every content file (blocks, items, prefab models) has been resolved by now.
        getLogger().info(ConfigResources.describeSources());

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
            sender.sendMessage("§e/blockships wheels §7- Inspect, list, adopt or purge ship-wheel identity records");
            sender.sendMessage("§e/blockships forcedisassembleall §7- Force-disassemble all assembled ships §c§l[DANGEROUS]");
            sender.sendMessage("§e/blockships killentities §7- Remove all BlockShips entities from worlds §c§l[DANGEROUS]");
        }
        sender.sendMessage("§7Found a bug? Report it at: §b" + ISSUES_URL);
    }

    /**
     * Collects the UUIDs of all persisted ships (loaded + unloaded) from
     * {@link ShipWorldData}'s persisted-id set (scanned from ships/*.yml sidecars across every world on
     * disk, including worlds that aren't currently loaded). No I/O here. The returned set inherently
     * dedupes ids. Returns an empty set if {@code displayShip} is not yet initialized.
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
        /** Assembled wheel whose ship is persisted (has a sidecar) but not registered - unloaded. */
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
                // isWorldLoaded FIRST. The order was inverted: getWorld() is the call that throws, and it
                // sat ahead of the very predicate meant to guard it, so the guard never ran.
                boolean loaded = wl != null && wl.isWorldLoaded() && wl.isChunkLoaded();
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
     *       {@link ShipWorldData}'s persisted-id set ({@link #collectPersistedShipIds()}).</li>
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
        // not yet persisted (loaded can momentarily exceed persisted), and a custom ship
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
        // Split ships (and their entities) by shipType — Prefab (kit-spawned from a model) vs Custom
        // (player-built) — matching the "Prefab Ships / Custom Ships" vocabulary sendStatsBreakdown prints
        // just above. NOT an engine split: both kinds run on defCoreLib mechanisms.
        Set<UUID> prefabShips = new HashSet<>();
        Set<UUID> customShips = new HashSet<>();
        Set<UUID> orphanedShips = new HashSet<>();
        int prefabEnt = 0;
        int customEnt = 0;
        int unattributed = 0;
        int total = 0;
        Map<String, Integer> byType = new HashMap<>();

        // Admin-only command: a single-tick scan of every entity in every loaded world is
        // acceptable here (not run on a hot path).
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                Set<String> tags = entity.getScoreboardTags();
                // displayship: tags every ship's root vehicle, and is also what leftover legacy (pre-migration)
                // entities carry; corelib:mech: tags the mechanism's colliders/seats/displays.
                boolean shipTag = ShipTags.isShipEntity(tags);
                boolean corelibTag = ShipTags.isCorelibTagged(tags);
                if (!shipTag && !corelibTag) continue;                // not a BlockShips entity

                UUID shipId = ShipTags.extractShipId(tags);
                ShipInstance ship = shipId != null ? ShipRegistry.byId(shipId) : null;
                // This guard does TWO jobs — keep both: (1) exclude a FOREIGN corelib mechanism (pipes/railbound)
                // whose shulker never resolves to a registered BlockShips ship; (2) drop an ORPHANED delegated
                // ship's corelib colliders (unregistered → its corelib entities are indistinguishable from a
                // foreign mechanism's; the orphan is still detected via its displayship: vehicle below). Do NOT
                // relax this to count byId==null corelib entities, or it starts reaping/counting sibling plugins.
                if (corelibTag && !shipTag && ship == null) continue;

                total++;
                byType.merge(entity.getType().name(), 1, Integer::sum);

                if (shipId == null) {
                    unattributed++;
                } else if (ship == null) {
                    orphanedShips.add(shipId);                        // displayship-tagged: a real BlockShips ship, now gone
                } else if ("custom".equals(ship.shipType)) {
                    customShips.add(shipId);
                    customEnt++;
                } else {
                    prefabShips.add(shipId);
                    prefabEnt++;
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

        sender.sendMessage("§eBlockShips entities in loaded chunks: §f" + total);
        sender.sendMessage("§7  Prefab ships here: §a" + prefabShips.size()
            + "§7 (§f" + prefabEnt + "§7 entities)");
        sender.sendMessage("§7  Custom ships here: §a" + customShips.size()
            + "§7 (§f" + customEnt + "§7 entities)");
        sender.sendMessage("§7  Orphaned ships: " + attn(orphanedShips.size())
            + "§7; loose entities: " + attn(unattributed));
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
        String world = anon.def9a2a4.blockships.util.LocationUtil.worldName(loc);
        if (world == null) world = "?";
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
                ConfigResources.resetReporting();
                // An unparseable config.yml leaves getConfig() serving jar defaults, so every reload
                // below would silently apply the wrong values. Check before, and tell the sender.
                ConfigValidator.MainConfigStatus reloadedStatus = ConfigValidator.checkMainConfig(this);
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
                ConfigResources.Loaded reloadedBlocks = BlockConfigManager.getInstance().reloadConfig();
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
                // Re-check for files on disk that nothing reads. onEnable does this too, but an admin
                // who drops a file in and reloads is exactly the person who needs to hear about it.
                ConfigValidator.checkForOutdatedResources(this);

                sender.sendMessage("§aBlockShips config reloaded!");
                // The admin editing these files is in-game, not watching the console. Say which copy of
                // each file is actually in force, or #43 happens again.
                if (reloadedStatus.failedToParse()) {
                    sender.sendMessage("§cconfig.yml did not parse: " + reloadedStatus.parseError());
                    sender.sendMessage("§cEvery setting in it is being ignored - running on defaults."
                        + " The file has not been modified.");
                }
                if (reloadedBlocks.error() != null) {
                    sender.sendMessage("§cconfig/blocks.yml did not parse: " + reloadedBlocks.error());
                }
                sender.sendMessage("§7" + ConfigResources.describeSources());
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

                // The Mechanism owns the collider shulkers; gather them by block index.
                java.util.List<org.bukkit.entity.Shulker> colliderShulkers = new java.util.ArrayList<>();
                int n = ship.mechanism.blockCount();
                for (int i = 0; i < n; i++) {
                    org.bukkit.entity.Shulker s = ship.mechanism.colliderEntity(i);
                    if (s != null && s.isValid()) colliderShulkers.add(s);
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
                    int orphaned = ws.orphan.size();
                    int untouched = ws.unloadedPersisted.size();

                    sendStatsBreakdown(sender, persisted, ws);
                    sender.sendMessage("");
                    sender.sendMessage("§c§l⚠ WARNING ⚠");
                    sender.sendMessage("§cThis will §lFORCE-DISASSEMBLE§c the §e" + willDisassemble
                        + "§c currently active ship(s).");
                    if (orphaned > 0) {
                        // Orphans used to be skipped, which stranded their blocks permanently. They are now
                        // actionable: any that still have a live mechanism get their blocks landed.
                        sender.sendMessage("§e" + orphaned + " orphaned wheel(s) will also be cleared. Any"
                            + " whose ship is still holding blocks will have those returned to the world"
                            + " §7(except prefab ships, which hold none).");
                    }
                    if (untouched > 0) {
                        sender.sendMessage("§7" + untouched + " assembled wheel(s) are in unloaded chunks"
                            + " and will be left untouched.");
                    }
                    sender.sendMessage("");
                    sender.sendMessage("§7Type §e/blockships forcedisassembleall confirm §7to confirm.");
                    return true;
                }

                int count = 0;
                int failed = 0;
                int skipped = 0;
                int saveErrors = 0;
                int orphansLanded = 0;
                int orphansDiscarded = 0;

                // Coalesce the per-ship saves into one write at the end — disassembleShip saves twice per
                // ship and each save rewrites the whole file, so this loop was O(N²) on the main thread.
                shipWheelManager.beginBatch();
                try {
                // Get all wheels and force-disassemble assembled ones.
                // Copy to avoid ConcurrentModificationException (disassembly updates wheel locations).
                for (ShipWheelData wheelData : new ArrayList<>(shipWheelManager.getWheels())) {
                    if (!wheelData.isAssembled()) continue;

                    // Not registered as a ShipInstance. Two very different cases hide behind that:
                    //
                    //  - a LIVE mechanism with no ShipInstance (lost/corrupt sidecar) — an orphan. Its blocks
                    //    are real and in the world, and skipping it strands them forever. Land them.
                    //  - genuinely unloaded or gone. Still skipped: calling disassembleShip would sever the
                    //    wheel link and corrupt a ship that still exists in an unloaded chunk.
                    //
                    // Prefab (blockFree) orphans are NOT landed — defCoreLib discards those instead of
                    // restoring blocks, so "recovered" would be a lie. They stay in the skipped count.
                    if (ShipRegistry.byId(wheelData.getAssembledShipUUID()) == null) {
                        UUID orphanId = wheelData.getAssembledShipUUID();
                        ShipOrphans.Outcome oc = ShipOrphans.disassembleOrphan(this, orphanId);
                        if (oc == ShipOrphans.Outcome.LANDED) {
                            orphansLanded++;
                            wheelData.setAssembledShipUUID(null);
                        } else if (oc == ShipOrphans.Outcome.DISCARDED_BLOCK_FREE) {
                            orphansDiscarded++;
                            wheelData.setAssembledShipUUID(null);
                        } else {
                            skipped++;
                        }
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
                } finally {
                    if (!shipWheelManager.endBatch()) saveErrors++;
                }

                getLogger().info(sender.getName() + " ran forcedisassembleall: disassembled " + count
                    + " ship(s)" + (failed > 0 ? ", " + failed + " failed" : "")
                    + (saveErrors > 0 ? ", " + saveErrors + " with save errors" : "")
                    + (orphansLanded > 0 ? ", " + orphansLanded + " orphan(s) landed" : "")
                    + (orphansDiscarded > 0 ? ", " + orphansDiscarded + " block-free orphan(s) discarded" : "")
                    + (skipped > 0 ? ", " + skipped + " skipped (not active)" : ""));

                // disassembleShip DOES persist (twice, in fact — once per branch and once at the tail), so
                // this trailing save is belt-and-braces rather than the only write. It still earns its keep
                // for the orphan branches above, which clear links without going through disassembleShip.
                // Gate on having changed something so a no-op run doesn't rewrite the file.
                if ((count > 0 || orphansLanded > 0 || orphansDiscarded > 0) && !shipWheelManager.saveAll()) {
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
                if (orphansLanded > 0) {
                    sender.sendMessage("§a" + orphansLanded + " orphaned ship(s) had no data left, but their"
                        + " blocks were returned to the world.");
                }
                if (orphansDiscarded > 0) {
                    sender.sendMessage("§e" + orphansDiscarded + " orphaned prefab ship(s) were removed."
                        + " §7Prefabs hold no world blocks, so there was nothing to return.");
                }
                if (skipped > 0) {
                    sender.sendMessage("§7" + skipped + " assembled wheel(s) were skipped (not active -"
                        + " unloaded, or the ship no longer exists).");
                }
                return true;
            }

            if (args[0].equalsIgnoreCase("forceclearwheel")) {
                // F1c — escape hatch for a wheel stuck 'loading' (UNLOADED_RECOVERABLE) by a ship that can never
                // rebind (e.g. reconstructDelegatedShip bailed on a missing sidecar). Bypasses the recoverable
                // check for the ONE targeted wheel. Admin-gated; confirm-gated.
                if (!sender.hasPermission("blockships.admin")) {
                    sender.sendMessage("§cYou don't have permission to use admin commands.");
                    return true;
                }
                // Targeted BY ID, not by proximity. getNearestWheel measures from each record's STORED cell —
                // which, for the stuck wheel this command exists to repair, is precisely the field that is
                // wrong, and is usually an empty cell the ship left at launch. So the old form could silently
                // select a healthy neighbour instead. Worse, the preview and the action each re-ran the
                // nearest-wheel search, so an admin could walk two blocks between them and confirm onto a
                // different wheel than the one they were shown.
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: §e/blockships forceclearwheel <wheelId> <first8>");
                    sender.sendMessage("§7Find the id with §e/blockships wheels list§7 — broken records sort first.");
                    if (sender instanceof org.bukkit.entity.Player p) {
                        ShipWheelData near = shipWheelManager.getNearestWheel(p.getLocation(), 32.0);
                        if (near != null) {
                            sender.sendMessage("§7Nearest recorded wheel: §f" + near.getWheelId()
                                + " §7at " + fmt(near.getBlockLocation())
                                + " §7[" + shipWheelManager.resolveWheelState(near).state() + "]");
                        }
                    }
                    return true;
                }
                java.util.UUID fcId;
                try {
                    fcId = java.util.UUID.fromString(args[1]);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("§cThat is not a wheel id. Use §e/blockships wheels list§c to find it.");
                    return true;
                }
                ShipWheelData target = shipWheelManager.getWheelById(fcId);
                if (target == null) {
                    sender.sendMessage("§cNo record with id " + fcId + ".");
                    return true;
                }
                // ID-derived token rather than the literal "confirm", matching wheels adopt/purge: a stale or
                // mistyped confirmation then cannot land on a wheel the admin never looked at.
                String fcToken = fcId.toString().substring(0, 8);
                if (args.length < 3 || !args[2].equalsIgnoreCase(fcToken)) {
                    sender.sendMessage("§eWheel §f" + fcId + " §7at " + fmt(target.getBlockLocation())
                        + " §7[" + shipWheelManager.resolveWheelState(target).state() + "]");
                    sender.sendMessage("§c§l⚠ §cForce-clear bypasses the recoverable check: it UNLINKS the wheel and "
                        + "reaps its orphan root vehicle. Use ONLY for a wheel stuck 'loading' by an unrecoverable ship.");
                    sender.sendMessage("§7Confirm: §e/blockships forceclearwheel " + fcId + " " + fcToken);
                    return true;
                }
                shipWheelManager.forceClearWheelLink(target);
                getLogger().info(sender.getName() + " force-cleared wheel " + fcId + " at "
                    + fmt(target.getBlockLocation()));
                sender.sendMessage("§aWheel force-cleared. You can now re-assemble.");
                return true;
            }

            if (args[0].equalsIgnoreCase("wheels")) {
                if (!sender.hasPermission("blockships.admin")) {
                    sender.sendMessage("§cYou don't have permission to use admin commands.");
                    return true;
                }
                return handleWheelsCommand(sender, args);
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
                // Split by shipType for the report (same axis as sendEntitySummary / sendStatsBreakdown):
                // Prefab == kit-spawned from a model, Custom == player-built. Not an engine split.
                int prefabShipCount = 0;
                int customShipCount = 0;
                for (ShipInstance ship : shipsToRemove) {
                    destroyedShipIds.add(ship.id);
                    if ("custom".equals(ship.shipType)) customShipCount++; else prefabShipCount++;
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
                } else {
                    cleanupFailed = true;
                    getLogger().severe("killentities: displayShip not initialized; skipped YAML cleanup");
                }
                // Known limitation (LOW, pre-existing): a queued async metadata save can re-write a
                // just-destroyed ship's .yml after this synchronous cleanup, leaving a leaked file. It
                // self-heals (the persisted-id set drops on removeShip; a stale sidecar is pruned on next
                // load) and never yields a live phantom ship. Inherent to the existing I/O pipeline.

                // Then clean up any remaining ship-tagged entities (orphans + any not removed by
                // destroy()). Isolate per-entity so one bad remove() doesn't abort the sweep.
                for (World world : Bukkit.getWorlds()) {
                    for (Entity entity : world.getEntities()) {
                        Set<String> tags = entity.getScoreboardTags();
                        // displayship: orphan sweep, PLUS a DEFENSIVE corelib sweep scoped to ships THIS command
                        // just destroyed. The corelib arm is normally a no-op — mechanism.destroy() already removed
                        // every corelib entity synchronously — and is load-bearing only if removeAllEntities() threw
                        // mid-teardown and leaked. destroyedShipIds holds ship.id == mechId, so it NEVER touches a
                        // foreign mechanism (mechId not in the set) nor a recoverable straddling ship (unregistered →
                        // not destroyed → not in the set). Loaded chunks only, like the rest of this command.
                        boolean sweep = ShipTags.isShipEntity(tags)
                            || (ShipTags.isCorelibTagged(tags) && destroyedShipIds.contains(ShipTags.extractShipId(tags)));
                        if (sweep) {
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
                String shipSplit = "(" + prefabShipCount + " prefab, " + customShipCount + " custom)";
                getLogger().info(sender.getName() + " ran killentities: destroyed " + destroyedOk + "/"
                    + shipCount + " registered ship(s) " + shipSplit + ", removed " + removedCount
                    + " stray tagged entity/entities, cleared " + clearedLinks + " wheel link(s)"
                    + (destroyFailed > 0 ? ", " + destroyFailed + " failed to destroy" : "")
                    + (cleanupFailed ? " (cleanup had errors)" : ""));

                sender.sendMessage("Destroyed " + destroyedOk + " registered ship(s) " + shipSplit
                    + " and their entities; removed " + removedCount + " additional tagged entity/entities"
                    + (clearedLinks > 0 ? ", cleared " + clearedLinks + " wheel link(s)" : ""));
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

    /**
     * {@code /blockships wheels <inspect|list|adopt|purge>} — the diagnostic + repair surface for wheel
     * identity.
     *
     * <p>{@code inspect} is not a convenience: a wheel's identity lives in its block's PDC, which nothing
     * else in the game can show you. It is the only way to falsify "identity survived the voyage", because
     * the legacy-adoption fallback silently re-stamps a wheel that lost its PDC, so every behavioural check
     * would still pass while the thing they test for is broken.
     */
    private boolean handleWheelsCommand(CommandSender sender, String[] args) {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "help";
        ShipWheelManager mgr = shipWheelManager;

        if (!anon.def9a2a4.blockships.customships.ShipWheelBlockType.isRegistered()) {
            sender.sendMessage("§c⚠ The ship wheel is NOT registered with DefCoreLib this session. New wheels "
                + "cannot be stamped and legacy wheels cannot be adopted. Check the startup log.");
        }

        switch (sub) {
            case "inspect": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§cThis must be run by a player (it inspects the block you are looking at).");
                    return true;
                }
                org.bukkit.block.Block b = p.getTargetBlockExact(8);
                if (b == null) {
                    sender.sendMessage("§cLook at a block within 8 blocks.");
                    return true;
                }
                Location l = b.getLocation();
                sender.sendMessage("§e── " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ()
                    + " in " + l.getWorld().getName() + " ──");
                sender.sendMessage("§7material: §f" + b.getType());
                String typeId = null;
                try {
                    var chb = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry().getTypeFromBlock(b);
                    if (chb != null) typeId = chb.fullId();
                } catch (Throwable ignored) { /* engine absent */ }
                sender.sendMessage("§7corelib:block_type: " + (typeId == null ? "§8(none)" : "§f" + typeId));
                java.util.UUID wid = anon.def9a2a4.blockships.customships.ShipWheelBlockType.readWheelId(b);
                sender.sendMessage("§7blockships:wheel_id: " + (wid == null ? "§8(none)" : "§f" + wid));
                if (wid != null) {
                    ShipWheelData rec = mgr.getWheelById(wid);
                    if (rec == null) {
                        sender.sendMessage("§c  → no record for that id (stamped but unknown).");
                    } else {
                        Location c = rec.getBlockLocation();
                        boolean here = anon.def9a2a4.blockships.util.LocationUtil.cellsAgree(c, l);
                        sender.sendMessage("§7  record cell: §f" + fmt(c) + (here ? " §a(matches)" : " §c(MISMATCH)"));
                        sender.sendMessage("§7  state: §f" + mgr.resolveWheelState(rec).state());
                        if (!here) sender.sendMessage("§7  repair: §e/blockships wheels adopt " + wid);
                    }
                }
                ShipWheelData byCell = mgr.getWheelAt(l);
                if (byCell != null && (wid == null || !byCell.getWheelId().equals(wid))) {
                    sender.sendMessage("§7record caching this cell: §f" + byCell.getWheelId()
                        + " §7(state " + mgr.resolveWheelState(byCell).state() + ")");
                }
                return true;
            }
            case "list": {
                var wheels = new java.util.ArrayList<>(mgr.getWheels());
                if (wheels.isEmpty()) {
                    sender.sendMessage("§7No ship wheels recorded.");
                    return true;
                }
                sender.sendMessage("§e" + wheels.size() + " ship wheel(s):");
                // Broken first. The cap is what makes this matter: the wheel an operator is hunting is
                // exactly the one that must not fall off the end of an arbitrarily-ordered list.
                //
                // Health is computed ONCE PER WHEEL into a map, not inside the comparator. A comparator's key
                // extractor runs on every comparison — O(n log n) times — and recordHealth is not cheap: each
                // call walks resolveWheelState into isPersistedShip, which copies the entire persisted-ship
                // id set, and then does up to two tile-entity reads via ownsBlock. It is also not guaranteed
                // stable across calls (a chunk can load mid-sort), and a key that changes underneath
                // List.sort raises "Comparison method violates its general contract!".
                java.util.Map<java.util.UUID, ShipWheelManager.Health> healths = new java.util.HashMap<>();
                for (ShipWheelData w : wheels) healths.put(w.getWheelId(), mgr.recordHealth(w));
                java.util.List<ShipWheelData> ordered = new java.util.ArrayList<>(wheels);
                ordered.sort(java.util.Comparator.comparingInt(
                    (ShipWheelData w) -> switch (healths.get(w.getWheelId())) {
                        case BROKEN -> 0;
                        case ORPHANED -> 1;
                        case UNKNOWN -> 2;
                        case SAILING -> 3;
                        case OK -> 4;
                    }));
                int shown = 0;
                for (ShipWheelData w : ordered) {
                    if (shown++ >= 30) {
                        sender.sendMessage("§7… and " + (ordered.size() - 30) + " more (see ship_wheels.yml).");
                        break;
                    }
                    // Four states, not two. The old boolean folded "sailing" and "chunk not loaded" in with
                    // "fine", so the two situations an operator actually needs to spot — a ship that is out,
                    // and a record whose block is gone — both printed as a green tick, right next to a state
                    // column that might say ORPHAN.
                    ShipWheelManager.Health health = healths.get(w.getWheelId());
                    String glyph = switch (health) {
                        case OK -> "§a✔ ";
                        case SAILING -> "§b⛵ ";
                        case ORPHANED -> "§6⚠ ";
                        case UNKNOWN -> "§7? ";
                        case BROKEN -> "§c✘ ";
                    };
                    sender.sendMessage(glyph + "§f" + w.getWheelId()
                        + " §7at " + fmt(w.getBlockLocation())
                        + " §7[" + mgr.resolveWheelState(w).state() + "]"
                        + (health == ShipWheelManager.Health.BROKEN
                            ? " §c— no wheel block there; §e/blockships wheels adopt " + w.getWheelId() : "")
                        + (health == ShipWheelManager.Health.ORPHANED
                            ? " §6— dead ship link; using the wheel repairs it, or §e/blockships "
                              + "forceclearwheel " + w.getWheelId() : ""));
                }
                return true;
            }
            case "adopt": {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§cThis must be run by a player (it targets the block you are looking at).");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: §e/blockships wheels adopt <wheelId>");
                    sender.sendMessage("§7Deliberately NOT proximity-targeted: a broken record's stored location "
                        + "is exactly the thing that is wrong, so nearest-wheel would grab a healthy neighbour.");
                    return true;
                }
                java.util.UUID id;
                try {
                    id = java.util.UUID.fromString(args[2]);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("§cThat is not a wheel id. Use §e/blockships wheels list§c.");
                    return true;
                }
                ShipWheelData rec = mgr.getWheelById(id);
                if (rec == null) {
                    sender.sendMessage("§cNo record with id " + id + ".");
                    return true;
                }
                org.bukkit.block.Block target = p.getTargetBlockExact(8);
                if (target == null) {
                    sender.sendMessage("§cLook at the wheel block you want this record to point at.");
                    return true;
                }
                // Confirm token echoes the id, so a mistyped or stale confirmation cannot land on the wrong
                // wheel the way a bare "confirm" literal can.
                String token = id.toString().substring(0, 8);
                if (args.length < 4 || !args[3].equalsIgnoreCase(token)) {
                    sender.sendMessage("§eAdopt §f" + id + "§e → " + fmt(target.getLocation()) + "§e?");
                    sender.sendMessage("§7Currently recorded at " + fmt(rec.getBlockLocation()) + ".");
                    sender.sendMessage("§7Confirm: §e/blockships wheels adopt " + id + " " + token);
                    return true;
                }
                // Capture BEFORE the call: adoptWheel relocates the record, so reading it afterwards printed
                // the new cell on both sides of the arrow and the audit line recorded nothing at all.
                String before = fmt(rec.getBlockLocation());
                boolean wasLocked = rec.isLocked();
                ShipWheelManager.AdoptResult res = mgr.adoptWheel(rec, target);
                switch (res) {
                    case OK -> {
                        getLogger().info(sender.getName() + " adopted wheel " + id + ": "
                            + before + " → " + fmt(target.getLocation()));
                        sender.sendMessage("§aAdopted. The block now carries this wheel's identity.");
                        if (wasLocked) {
                            // No flat "was carried across": the carry only happens when the OLD block was
                            // still this wheel's, and this side cannot see whether it was.
                            sender.sendMessage("§7It was locked. The locked structure carries across only if "
                                + "the old block was still this wheel's; if assembly reports missing blocks, "
                                + "unlock and re-lock the wheel.");
                        }
                    }
                    case NOT_IDLE -> {
                        // ORPHAN also lands here, and the generic message is wrong on every clause for it:
                        // its ship is neither assembled nor recoverable, and Disassemble is not the remedy.
                        if (mgr.resolveWheelState(rec).state() == ShipWheelManager.WheelState.ORPHAN) {
                            sender.sendMessage("§cThat record's ship link is stale — its ship is gone. Clear "
                                + "the link first: §e/blockships forceclearwheel " + id + "§c, then retry.");
                        } else {
                            sender.sendMessage("§cThat wheel's ship is assembled or recoverable — refusing. "
                                + "Disassemble it first.");
                        }
                    }
                    case NOT_A_HEAD -> sender.sendMessage("§cThat block is not a player head.");
                    case OTHER_WHEEL_BLOCK -> sender.sendMessage("§cThat block already belongs to a different "
                        + "wheel. Refusing to steal it.");
                    case OTHER_TYPE_BLOCK -> sender.sendMessage("§cThat block is a different DefCoreLib block "
                        + "(a rotator, hoist, or similar). Adopting it would destroy its identity.");
                    case CELL_TAKEN -> {
                        // Collect ALL residents, matching adoptWheel's own check — a single first-match pick
                        // could name a different record than the one that caused the refusal. Then say which
                        // KIND of refusal this was, because the remedies are opposites: a sailing ship's dock
                        // and a live wheel must be left alone, while only a provably stale record should ever
                        // be purged. The old advice here suggested purge unconditionally — which, aimed at a
                        // live legacy wheel (stamp-only OTHER_WHEEL_BLOCK can't protect it), was instructions
                        // for destroying a healthy wheel.
                        org.bukkit.Location targetCell = target.getLocation();
                        java.util.List<ShipWheelData> residents = new java.util.ArrayList<>();
                        for (ShipWheelData w : mgr.getWheels()) {
                            if (w != rec && anon.def9a2a4.blockships.util.LocationUtil
                                    .cellsAgree(w.getBlockLocation(), targetCell)) { residents.add(w); }
                        }
                        boolean explained = false;
                        for (ShipWheelData other : residents) {
                            ShipWheelManager.WheelState st = mgr.resolveWheelState(other).state();
                            if (st == ShipWheelManager.WheelState.LOADED
                                    || st == ShipWheelManager.WheelState.UNLOADED_RECOVERABLE) {
                                sender.sendMessage("§cThat cell is the dock of §f" + other.getWheelId()
                                    + "§c, whose ship is out and needs it back. This is not a repair — "
                                    + "pick a different block.");
                                explained = true;
                            } else if (mgr.recordHealth(other) == ShipWheelManager.Health.OK) {
                                sender.sendMessage("§cThat block is §f" + other.getWheelId()
                                    + "§c's own live wheel (likely a legacy wheel with no stamp). This is "
                                    + "not a repair — adopting would destroy it. If it really is stale, "
                                    + "§e/blockships wheels purge " + other.getWheelId() + "§c and retry.");
                                explained = true;
                            }
                        }
                        if (!explained) {
                            for (ShipWheelData other : residents) {
                                sender.sendMessage("§cAnother record claims that cell: §f" + other.getWheelId()
                                    + "§c. If its wheel is truly gone, §e/blockships wheels purge "
                                    + other.getWheelId() + "§c and retry — but check §e/blockships wheels "
                                    + "inspect§c first; purge deletes the record for good.");
                            }
                            if (residents.isEmpty()) {
                                sender.sendMessage("§cAnother record already claims that cell.");
                            }
                        }
                    }
                    case STAMP_FAILED -> sender.sendMessage("§cCould not stamp the block (is it a skull, and is "
                        + "the wheel registered with DefCoreLib?).");
                }
                return true;
            }
            case "purge": {
                if (args.length < 3) {
                    sender.sendMessage("§cUsage: §e/blockships wheels purge <wheelId>");
                    return true;
                }
                java.util.UUID id;
                try {
                    id = java.util.UUID.fromString(args[2]);
                } catch (IllegalArgumentException e) {
                    sender.sendMessage("§cThat is not a wheel id.");
                    return true;
                }
                ShipWheelData rec = mgr.getWheelById(id);
                if (rec == null) {
                    sender.sendMessage("§cNo record with id " + id + ".");
                    return true;
                }
                String token = id.toString().substring(0, 8);
                if (args.length < 4 || !args[3].equalsIgnoreCase(token)) {
                    sender.sendMessage("§c§l⚠ §cPurge DELETES this record. Its ship, if any, becomes unreachable "
                        + "through this wheel.");
                    // The health mark is the information a purge decision actually turns on — a second
                    // confirm token would break the tab-completer, but a preview that shows OK in red stops
                    // the "purge a healthy wheel because a message told you to" mistake cold.
                    ShipWheelManager.Health h = mgr.recordHealth(rec);
                    sender.sendMessage("§7" + id + " at " + fmt(rec.getBlockLocation())
                        + " [" + mgr.resolveWheelState(rec).state() + "], health=" + h);
                    if (h == ShipWheelManager.Health.OK) {
                        sender.sendMessage("§c§l⚠ §cThis record's wheel block is PRESENT and HEALTHY. Purging "
                            + "it orphans a live wheel — are you sure this is the right id?");
                    }
                    sender.sendMessage("§7Confirm: §e/blockships wheels purge " + id + " " + token);
                    return true;
                }
                getLogger().warning(sender.getName() + " purged wheel record " + id + " at "
                    + fmt(rec.getBlockLocation()));
                mgr.purgeWheel(rec);
                sender.sendMessage("§aRecord purged.");
                return true;
            }
            default: {
                sender.sendMessage("§e/blockships wheels inspect §7— show the identity on the block you're looking at");
                sender.sendMessage("§e/blockships wheels list §7— every record, with a health mark");
                sender.sendMessage("§e/blockships wheels adopt <id> §7— point a record at the block you're looking at");
                sender.sendMessage("§e/blockships wheels purge <id> §7— delete a record that has no wheel left");
                return true;
            }
        }
    }

    /** Short "world x, y, z" for command output. */
    private static String fmt(Location l) {
        // This formats output ABOUT broken records, so it is exactly where an unloaded world turns up — and
        // a bare getWorld() threw straight past the "(unknown)" fallback that exists for this case.
        String w = anon.def9a2a4.blockships.util.LocationUtil.worldName(l);
        if (w == null) return "(unknown)";
        return w + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
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
                subcommands.add("forceclearwheel");
                subcommands.add("killentities");
                subcommands.add("wheels");
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
                // Complete with "confirm" for dangerous commands. forceclearwheel is deliberately NOT in this
                // list any more — its second argument is a wheel id, and offering "confirm" there both fails
                // and hides the completions that would actually help.
                if ("confirm".startsWith(args[1].toLowerCase())) {
                    completions.add("confirm");
                }
            } else if (subcommand.equals("forceclearwheel") && sender.hasPermission("blockships.admin")) {
                if (shipWheelManager != null) {
                    for (ShipWheelData w : shipWheelManager.getWheels()) {
                        String id = w.getWheelId().toString();
                        if (id.startsWith(args[1].toLowerCase())) completions.add(id);
                    }
                }
            } else if (subcommand.equals("wheels") && sender.hasPermission("blockships.admin")) {
                for (String s : new String[]{"inspect", "list", "adopt", "purge"}) {
                    if (s.startsWith(args[1].toLowerCase())) completions.add(s);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("wheels")
                && sender.hasPermission("blockships.admin")
                && (args[1].equalsIgnoreCase("adopt") || args[1].equalsIgnoreCase("purge"))) {
            // Complete wheel ids — these are UUIDs nobody is going to type from memory.
            if (shipWheelManager != null) {
                for (ShipWheelData w : shipWheelManager.getWheels()) {
                    String id = w.getWheelId().toString();
                    if (id.startsWith(args[2].toLowerCase())) completions.add(id);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("forceclearwheel")
                && sender.hasPermission("blockships.admin")) {
            // The confirm token is the first 8 characters of the id just typed.
            try {
                completions.add(java.util.UUID.fromString(args[1]).toString().substring(0, 8));
            } catch (IllegalArgumentException ignored) {
                // Not a well-formed id yet; nothing useful to offer.
            }
        }

        return completions;
    }
}
