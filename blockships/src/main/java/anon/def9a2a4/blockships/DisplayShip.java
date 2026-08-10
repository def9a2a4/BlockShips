package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.customships.ShipWheelData;
import anon.def9a2a4.blockships.customships.ShipWheelManager;
import anon.def9a2a4.blockships.customships.ShipWheelMenu;
import anon.def9a2a4.blockships.util.AttributeCompat;
import anon.def9a2a4.blockships.ship.CustomShipRender;
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
    private ShipWorldData shipWorldData;  // Per-world ship storage for chunk-based loading
    private Map<String, ShipModel> shipModels = new HashMap<>();
    private ItemTextureManager textureManager;
    private ItemFactory itemFactory;
    private final List<NamespacedKey> registeredRecipes = new ArrayList<>();
    private final Map<UUID, Long> lastShulkerInteraction = new HashMap<>();  // Cooldown for preventing double-entry
    private final Set<UUID> shipsBeingRecovered = Collections.synchronizedSet(new HashSet<>());  // Prevent concurrent recovery
    private final Set<Long> chunksBeingRecovered = ConcurrentHashMap.newKeySet();  // Track chunks with pending async recovery
    private final Map<UUID, java.util.logging.Level> migrationFailureLogged = new ConcurrentHashMap<>();  // Per-ship highest migration-failure level already logged (so a stuck ship logs once, not every chunk load)

    public DisplayShip(JavaPlugin plugin) {
        this.plugin = plugin;
        this.BANNER_DATA_KEY = new NamespacedKey(plugin, "banner_data");
        this.WOOD_TYPE_KEY = new NamespacedKey(plugin, "wood_type");
        this.SHIP_TYPE_KEY = new NamespacedKey(plugin, "ship_type");
        this.shipWorldData = new ShipWorldData(plugin);
        this.textureManager = new ItemTextureManager(plugin);
    }

    public void initialize() {
        // Item textures and prefab models are read from the jar (or a config/ override) on demand;
        // nothing is extracted to disk.


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

        // NOTE: native-ship migration (migrateLoadedChunks) runs LATER in BlockShipsPlugin enable — AFTER
        // forceRecoverDelegatedShips() — so corelib has already recovered delegated ships (setting byId) before
        // the migration idempotency probe runs, shrinking the crash-mid-migration re-spawn window (#3).

        // Start periodic save task for ships in always-loaded chunks (spawn chunks)
        startPeriodicSaveTask();
    }

    /**
     * At enable, migrate any persisted NATIVE ship (released 0.0.17) in an already-loaded chunk (spawn chunks that
     * never unload) into a delegated mechanism, and reap stragglers. Delegated ships recover via defCoreLib
     * (forceRecoverDelegatedShips + the recovered MechanismAssembleEvent), not here. Called by BlockShipsPlugin
     * AFTER forceRecoverDelegatedShips so corelib has recovered delegated ships before the migration probe runs.
     */
    public void migrateLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                migrateNativeShipsInChunk(world, chunk);
                reapStragglerEntities(world, chunk);
            }
        }
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
        // Persist chunk indices (async - serialized behind metadata writes on ioExecutor)
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

        // Migrate any persisted NATIVE ship (released 0.0.17, custom + prefab) whose root is in this chunk into a
        // delegated mechanism — the only engine. Delegated ships recover via defCoreLib's recovered
        // MechanismAssembleEvent (onMechanismAssemble → reconstructDelegatedShip); nothing native remains to
        // recover here.
        migrateNativeShipsInChunk(world, chunk);

        // Reap leftover native entity stragglers (an already-migrated ship's reap-fail leftovers, or a truly
        // orphaned no-sidecar entity). A ship still PENDING migration (sidecar present + not migrated, root in an
        // unloaded chunk) is left untouched for its own migration.
        reapStragglerEntities(world, chunk);
    }

    /** Blind reaper (M-D): remove native entity stragglers in a chunk — {@code displayship:*}, non-corelib, whose
     *  whose sidecar is absent (orphan) or migrated (delegated). Never touches a delegated
     *  ship's entities (corelib-tagged) or a ship still pending migration (sidecar present + not migrated). Runs on
     *  every chunk-load + at enable, permanently, so multi-chunk reap-fail leftovers get swept when their chunk loads. */
    private void reapStragglerEntities(World world, org.bukkit.Chunk chunk) {
        Map<UUID, Boolean> reapDecision = new HashMap<>();
        for (Entity e : new java.util.ArrayList<>(java.util.Arrays.asList(chunk.getEntities()))) {
            Set<String> tags = e.getScoreboardTags();
            if (ShipTags.isCorelibTagged(tags)) continue;      // delegated — owned by defCoreLib
            UUID shipId = ShipTags.extractShipId(tags);
            if (shipId == null) continue;                       // not a ship entity
            // Do NOT skip when ShipRegistry.byId(shipId) != null: a migrated ship's leftover NATIVE parts
            // (non-corelib — this entity) carry the SAME id as the now-delegated live ship, so a byId hit here means
            // "straggler of a migrated ship", not "leave it alone". The migrated-sidecar check below is what
            // distinguishes those (reap) from a still-pending native ship (leave for migrateNativeShip).
            Boolean reap = reapDecision.get(shipId);
            if (reap == null) {
                ShipPersistence.ShipState st = shipWorldData.loadShipMetadata(world, shipId);
                reap = (st == null) || st.migrated;             // orphan (no sidecar) or already-delegated straggler
                reapDecision.put(shipId, reap);
            }
            if (!reap) continue;                                // pending migration — leave for migrateNativeShip
            dropLeadsAndDetach(e);
            e.remove();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Native → delegated MIGRATION (M-A). Converts released 0.0.17 NATIVE ships (custom + prefab) into delegated
    // defCoreLib mechanisms on chunk-load + at enable, preserving the ship id. Lazy per-chunk + per-ship footprint
    // force-load so multi-chunk ships reap completely. Ordering: migrate (reads pose from the root, force-loads the
    // footprint) THEN reap the native entity graph — never reap before migration (the root holds the position).
    // ─────────────────────────────────────────────────────────────────────────────

    /** Migrate every persisted NATIVE ship whose root ArmorStand is in this loaded chunk. */
    private void migrateNativeShipsInChunk(World world, org.bukkit.Chunk chunk) {
        // Snapshot: migration + reap mutate entities, so don't iterate a live view.
        for (Entity e : new java.util.ArrayList<>(java.util.Arrays.asList(chunk.getEntities()))) {
            if (!(e instanceof ArmorStand root)) continue;
            Set<String> tags = root.getScoreboardTags();
            if (ShipTags.isCorelibTagged(tags)) continue;   // delegated vehicle — not ours
            if (!ShipTags.isRoot(tags)) continue;            // only the ship root ArmorStand
            UUID shipId = ShipTags.extractShipId(tags);
            if (shipId == null) continue;
            try {
                migrateNativeShip(world, shipId, root);
            } catch (Throwable t) {
                logOnce(shipId, java.util.logging.Level.SEVERE,
                    "could not be migrated (unexpected error) — please report this to the BlockShips developer; leaving native entities for retry.", t);
            }
        }
    }

    /**
     * Log helper with per-ship de-duplication for the migration path. When {@code key} is non-null (a
     * migration attempt, which re-fires on every chunk load), the same ship logs at most ONCE per severity —
     * so an operator gets one complete, forwardable diagnostic (reason + any stacktrace) instead of a line
     * every load; a genuinely worse failure (higher level) still surfaces once, and a successful migration
     * clears the entry ({@link #migrationFailureLogged}). When {@code key} is null (a one-shot fresh-spawn or
     * delegated-recovery attempt), it always logs, as before.
     */
    private void logOnce(UUID key, java.util.logging.Level level, String msg, Throwable t) {
        if (key != null) {
            java.util.logging.Level prev = migrationFailureLogged.get(key);
            if (prev != null && prev.intValue() >= level.intValue()) return;  // already logged at >= this severity
            migrationFailureLogged.put(key, level);
            msg = "Ship " + key + ": " + msg;
        }
        if (t != null) plugin.getLogger().log(level, msg, t);
        else plugin.getLogger().log(level, msg);
    }

    /** Migrate one native ship (custom or prefab) → delegated, preserving its id. Idempotent: an already-delegated
     *  ship — live BlockShips registry, migrated-marker sidecar, OR corelib live/persisted state — is a reap-failed
     *  straggler → reap-only, never re-assemble (re-assembling a corelib-owned id duplicates the mechanism). */
    private void migrateNativeShip(World world, UUID shipId, ArmorStand root) {
        ShipInstance live = ShipRegistry.byId(shipId);
        if (live != null && live.mechanism != null) {   // already delegated (race / straggler) — reap the old graph
            reapNativeEntities(shipId, root);
            return;
        }
        ShipPersistence.ShipState state = shipWorldData.loadShipMetadata(world, shipId);
        if (state == null) return;                       // no sidecar — orphan cleanup's job, not migration's
        if (state.migrated) {                            // delegated sidecar — this native root is a straggler
            reapNativeEntities(shipId, root);
            return;
        }
        // DefCoreLib is a hard depend; this is defensive so a missing engine skips quietly (no throw + retry spam).
        if (!Bukkit.getPluginManager().isPluginEnabled("DefCoreLib")) return;
        anon.def9a2a4.corelib.MechanismRegistry reg =
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getMechanismRegistry();
        // Idempotency (#3): if corelib already owns this id — live (byId) OR persisted-but-not-yet-recovered
        // (hasPersistedState, e.g. a crash between a prior migration's assemble+persist and its migrated-marker
        // write) — re-assembling would duplicate the mechanism (the reaper skips corelib-tagged ghosts). Reap the
        // stale native entities only; corelib's own recovery brings the delegated ship back.
        if (reg.byId(shipId) != null || reg.hasPersistedState(world, shipId)) {
            reapNativeEntities(shipId, root);
            return;
        }
        ShipModel model = loadModelForState(state, shipId);
        if (model == null) return;   // loadModelForState logged the reason once (via the dedupe key)
        java.util.List<long[]> forced = forceLoadFootprint(world, root.getLocation(), model);
        try {
            Location pose = root.getLocation().clone();
            if (!Float.isNaN(state.yaw)) pose.setYaw(state.yaw);
            ShipCustomization customization = ShipInstance.buildCustomizationFromState(plugin, state);
            // #2: load the native cargo into the mechanism's typed inventories BEFORE reg.persist (via the 6-arg
            // overload) so the first crash-safe snapshot holds it — not a later re-snapshot. finalizeMigration no
            // longer restores inventories.
            java.util.Map<Integer, org.bukkit.inventory.ItemStack[]> cargo = ShipInstance.decodeCargo(state);
            ShipInstance ship = spawnDelegatedFromModel(shipId, state.shipType, model, pose, customization, cargo);
            if (ship == null) {
                return;   // spawnDelegatedFromModel logged the reason once (via its non-null id)
            }
            ship.finalizeMigration(state);
            ShipRegistry.register(ship);
            // F4: re-link the wheel + recompute stats one tick later (same as delegated recovery) — else a migrated
            // CUSTOM ship stays at its conservative preliminary stats (immovable) until an unload+reload.
            scheduleWheelRelink(ship, shipId);
            shipWorldData.saveShipMetadata(ship);        // re-persist as delegated (writes the migrated marker)
            Location rl = root.getLocation();
            shipWorldData.removeFromChunkIndex(world, shipId, rl.getBlockX() >> 4, rl.getBlockZ() >> 4);
            shipWorldData.saveAllChunkIndices();
            reapNativeEntities(shipId, root);            // AFTER successful migration (ordering is load-bearing)
            migrationFailureLogged.remove(shipId);       // success: allow a future genuinely-new failure to log again
            plugin.getLogger().info("Migrated native " + state.shipType + " ship " + shipId
                + " to the delegated engine.");
        } finally {
            releaseFootprint(world, forced);
        }
    }

    /** Interaction fallback: a player clicked a ship collider whose ship isn't registered. The player is here, so
     *  this chunk is loaded — migrate any native ship rooted in it now and return the (now delegated) instance, or
     *  null if none/failed. (Chunk-load migration normally handles this first; this covers the rare miss.) */
    private ShipInstance attemptInteractionMigration(UUID shipId, Shulker anchor) {
        ShipInstance live = ShipRegistry.byId(shipId);
        if (live != null) return live;
        migrateNativeShipsInChunk(anchor.getWorld(), anchor.getLocation().getChunk());
        return ShipRegistry.byId(shipId);
    }

    /** Force-load the chunks the ship's model footprint spans (root position + model radius, rotation-invariant,
     *  +1 chunk margin for colliders overhanging block edges) so migration + reap see ALL of the ship's entities
     *  across chunk boundaries. Returns the {cx,cz} of chunks we ticketed, to release afterwards. */
    private java.util.List<long[]> forceLoadFootprint(World world, Location rootLoc, ShipModel model) {
        // Rotation-invariant bound: the heading isn't known here and parts rotate with it, so force-load a square
        // around the root sized by the farthest part's radius. A circle of radius maxR covers every heading; the
        // old per-axis un-rotated extent under-covered a long ship turned ~90° (far chunk never ticketed → its
        // native entities never reaped at migration time). Slightly over-covers for a long thin hull — fine for a
        // one-shot migration force-load.
        double maxR = 0;
        for (ShipModel.ModelPart p : model.parts) {
            maxR = Math.max(maxR, Math.hypot(p.local.m30(), p.local.m32()));
        }
        int r = (int) Math.ceil(maxR);
        int cx0 = ((rootLoc.getBlockX() - r) >> 4) - 1;
        int cx1 = ((rootLoc.getBlockX() + r) >> 4) + 1;
        int cz0 = ((rootLoc.getBlockZ() - r) >> 4) - 1;
        int cz1 = ((rootLoc.getBlockZ() + r) >> 4) + 1;
        java.util.List<long[]> forced = new java.util.ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    world.addPluginChunkTicket(cx, cz, plugin);
                    forced.add(new long[]{cx, cz});
                }
            }
        }
        return forced;
    }

    private void releaseFootprint(World world, java.util.List<long[]> forced) {
        for (long[] c : forced) {
            world.removePluginChunkTicket((int) c[0], (int) c[1], plugin);
        }
    }

    /** Reap the native entity graph ({@code displayship:{id}:*}, non-corelib) for a migrated/straggler ship across
     *  its (now force-loaded) footprint. Drops leads on any leash-holder collider first (mirrors the native
     *  lead-drop so leashed mobs aren't silently lost — the lead item returns to the world). */
    private void reapNativeEntities(UUID shipId, ArmorStand root) {
        String shipTagPrefix = ShipTags.shipTag(shipId);
        for (Entity e : root.getWorld().getNearbyEntities(root.getLocation(), 64, 64, 64)) {
            Set<String> tags = e.getScoreboardTags();
            if (ShipTags.isCorelibTagged(tags)) continue;   // never touch delegated entities
            boolean mine = false;
            for (String t : tags) { if (t.startsWith(shipTagPrefix)) { mine = true; break; } }
            if (!mine) continue;
            dropLeadsAndDetach(e);
            e.remove();
        }
        if (root.isValid()) root.remove();
    }

    /** Drop + detach any leads whose holder is {@code holder} (a native collider being reaped), mirroring the
     *  native ship-destroy lead-drop (ShipInstance) so Paper's tickLeash doesn't double-drop when the holder goes. */
    private void dropLeadsAndDetach(Entity holder) {
        for (Entity nearby : holder.getWorld().getNearbyEntities(holder.getLocation(), 12, 12, 12,
                n -> n instanceof io.papermc.paper.entity.Leashable l && l.isLeashed()
                        && holder.equals(l.getLeashHolder()))) {
            holder.getWorld().dropItemNaturally(holder.getLocation(), new ItemStack(org.bukkit.Material.LEAD));
            ((io.papermc.paper.entity.Leashable) nearby).setLeashHolder(null);
        }
    }

    /**
     * Loads the appropriate ShipModel for a saved ship state.
     */
    /** Loads the model for a saved ship. On failure, logs the reason via {@link #logOnce}: pass the ship id as
     *  {@code dedupeKey} on the migration path (which retries every chunk load — so it logs once per ship with a
     *  full, forwardable reason), or null on one-shot callers (which log every time and add their own context). */
    private ShipModel loadModelForState(ShipPersistence.ShipState state, UUID dedupeKey) {
        if ("custom".equals(state.shipType) && state.modelData != null) {
            // Custom ship - deserialize model from stored data
            try {
                return ShipModel.fromMap(state.modelData);
            } catch (Exception e) {
                logOnce(dedupeKey, java.util.logging.Level.WARNING,
                    "could not load its stored custom model: " + e.getMessage(), e);
                return null;
            }
        } else {
            // Prefab ship - load from model file
            String modelPath = plugin.getConfig().getString("ships." + state.shipType + ".model-path");
            if (modelPath == null) {
                // Prefab type not (or no longer) configured — the most common migration-stuck cause. Log once so an
                // admin sees it, but only on the migration path (dedupeKey != null); one-shot callers report their own.
                if (dedupeKey != null) logOnce(dedupeKey, java.util.logging.Level.WARNING,
                    "no model-path configured for ship type '" + state.shipType + "' — cannot migrate; leaving native.", null);
                return null;
            }
            try {
                return ShipModel.fromFile(plugin, modelPath, state.shipType);
            } catch (Exception e) {
                logOnce(dedupeKey, java.util.logging.Level.WARNING,
                    "could not load model file '" + modelPath + "': " + e.getMessage(), e);
                return null;
            }
        }
    }

    // ----- Delegated (defCoreLib) ship recovery (M5) -----

    /**
     * M5: reconstruct a DELEGATED custom ship when defCoreLib re-recovers its mechanism after a restart or an
     * in-play chunk reload — both come through here (defCoreLib parks a mechanism when its chunk unloads and
     * re-recovers it on reload, firing a recovered {@code MechanismAssembleEvent}). Fresh (non-recovered)
     * assemblies are built directly by ShipWheelManager, so they're ignored here.
     */
    @EventHandler
    public void onMechanismAssemble(anon.def9a2a4.corelib.MechanismAssembleEvent event) {
        if (!event.isRecovered()) return;
        // Delegated custom ("blockship:custom") AND delegated prefab ("blockship:prefab", P7.C) both rebuild here.
        if (!"blockship:custom".equals(event.getType()) && !"blockship:prefab".equals(event.getType())) return;
        reconstructDelegatedShip(event.getMechanism());
    }

    /**
     * Rebuild a {@link ShipInstance} around an already-recovered delegated {@link anon.def9a2a4.corelib.Mechanism}
     * (idempotent: no-op if the ship is already registered). Main-thread only (recovery fires from EntitiesLoad).
     * Used by the recovered-event listener and by enable-time forced recovery ({@link #forceRecoverDelegatedShips}).
     */
    void reconstructDelegatedShip(anon.def9a2a4.corelib.Mechanism mech) {
        UUID mechId = mech.id();
        if (ShipRegistry.byId(mechId) != null) return; // already live
        org.bukkit.entity.Entity veh = mech.vehicle();
        if (!(veh instanceof ArmorStand vehicle)) {
            plugin.getLogger().warning("Delegated ship " + mechId + " recovered without an ArmorStand vehicle; skipping");
            return;
        }
        World world = vehicle.getWorld();
        ShipPersistence.ShipState state = shipWorldData.loadShipMetadata(world, mechId);
        if (state == null) {
            plugin.getLogger().warning("Delegated ship " + mechId + " recovered but its ships/" + mechId
                + ".yml sidecar is missing; cannot rebuild the ShipInstance");
            return;
        }
        ShipModel model = loadModelForState(state, null);  // one-shot recovery: log every time (caller adds context below)
        if (model == null) {
            plugin.getLogger().warning("Delegated ship " + mechId + " recovered but its model could not be loaded");
            return;
        }
        ShipInstance ship = ShipInstance.fromRecoveredMechanism(plugin, state, model, vehicle, mech);
        // A2: repopulate the whole-ship leadable shulker. Fresh spawn wires it (spawnDelegatedPrefab), but
        // fromRecoveredMechanism does not — so without this the "click any block with a lead to attach" shortcut
        // is dead after a restart/chunk reload (the per-collider fence fallback in the interaction handler still
        // works). Mirror the spawn-time wiring exactly.
        for (int i = 0; i < model.parts.size(); i++) {
            if (Boolean.TRUE.equals(model.parts.get(i).rawYaml.get("leadable"))) {
                ship.leadableShulker = mech.colliderEntity(i);
                break;
            }
        }
        ship.applyInitialDrivenPose(); // render the saved heading on frame 1 (no one-tick yaw flash)
        ShipRegistry.register(ship);
        // F5: re-add to BlockShips' chunk index (parity with the native recovery paths). addToChunkIndex is
        // idempotent, so this is safe when loadAllChunkIndices already holds the entry; it closes the narrow
        // crash-lost-index window and keeps the chunk key fresh at the recovered position.
        Location recoveredLoc = vehicle.getLocation();
        shipWorldData.addToChunkIndex(world, mechId, recoveredLoc.getBlockX() >> 4, recoveredLoc.getBlockZ() >> 4);
        shipWorldData.saveAllChunkIndices();
        scheduleWheelRelink(ship, mechId);
        plugin.getLogger().info("Recovered delegated " + ("custom".equals(state.shipType) ? "custom" : "prefab")
            + " ship " + mechId);
    }

    /** Re-link a delegated ship's wheel + recompute its stats one tick later. Custom-ship speed/turn derive from
     *  the wheel, whose PDC blocks are loaded lazily by {@code ShipWheelManager.loadAll}, and {@code resolveWheelData}
     *  is lazy — so a synchronous call would find no wheel and leave the ship at its conservative preliminary stats
     *  (immovable). No-op for prefab (no wheel). MUST be called AFTER the ship is registered; the identity guard
     *  drops the task if the ship was replaced/removed in the intervening tick. Shared by the delegated-recovery
     *  path ({@code reconstructDelegatedShip}) and the native→delegated migration ({@code migrateNativeShip}). */
    private void scheduleWheelRelink(ShipInstance ship, UUID id) {
        new BukkitRunnable() {
            @Override public void run() {
                if (ShipRegistry.byId(id) != ship) return; // ship replaced/removed meanwhile
                ship.resolveWheelData();
                ship.physics.recomputeStats();
            }
        }.runTask(plugin);
    }

    /**
     * BS5: at enable, force defCoreLib to recover persisted mechanisms in every already-loaded chunk. Chunks
     * that loaded during world init (before either plugin enabled) fired their EntitiesLoadEvent before this
     * listener existed, so their recovered events were missed; driving {@code recoverMechanismsInChunk} here
     * re-fires them ({@code → onMechanismAssemble → reconstructDelegatedShip}) with wheels already loaded.
     */
    public void forceRecoverDelegatedShips() {
        anon.def9a2a4.corelib.MechanismRegistry mechRegistry =
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getMechanismRegistry();
        if (mechRegistry == null) return;
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                mechRegistry.recoverMechanismsInChunk(chunk);
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
            // Bukkit only matched the shapeless recipe on base material (e.g. any PLAYER_HEAD for the
            // ship_wheel ingredient), so a mob head / renamed head / balloon could craft the result and be
            // silently consumed. Re-validate the grid ourselves: build the ingredient pool the same way
            // ItemUtil registered it (one choice per config key) and require every non-empty grid slot to
            // match a distinct pool entry, matching custom items by their custom_item_id PDC (rename-proof)
            // rather than by display name.
            var ingredientsSection = plugin.getConfig().getConfigurationSection(recipePath + ".ingredients");
            List<RecipeIngredient> pool = new ArrayList<>();
            if (ingredientsSection != null) {
                for (String key : ingredientsSection.getKeys(false)) {
                    List<String> ingredientStrings = plugin.getConfig().getStringList(recipePath + ".ingredients." + key);
                    if (ingredientStrings.isEmpty()) continue;
                    try {
                        List<RecipeIngredient> parsed = RecipeIngredient.parseList(ingredientStrings, plugin, this.textureManager);
                        if (!parsed.isEmpty()) pool.add(parsed.get(0));
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("Failed to parse ingredient for " + shipType + ": " + ex.getMessage());
                        e.getInventory().setResult(null);
                        return;
                    }
                }
            }
            List<RecipeIngredient> remaining = new ArrayList<>(pool);
            for (ItemStack item : e.getInventory().getMatrix()) {
                if (item == null || item.getType().isAir()) continue;
                boolean matched = false;
                for (java.util.Iterator<RecipeIngredient> it = remaining.iterator(); it.hasNext(); ) {
                    if (ingredientMatches(it.next(), item)) { it.remove(); matched = true; break; }
                }
                if (!matched) { e.getInventory().setResult(null); return; }  // wrong/extra ingredient
            }
            if (!remaining.isEmpty()) { e.getInventory().setResult(null); return; }  // missing an ingredient

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

        // Assemble the prefab ship as a DELEGATED defCoreLib mechanism (the only engine). No native fallback:
        // if assembly fails (or the model is unsupported), refuse the spawn and DO NOT consume the kit.
        ShipInstance instance = spawnDelegatedPrefab(shipType, shipModel, spawnAt, customization);
        if (instance == null) {
            p.sendMessage(net.kyori.adventure.text.Component.text(
                    "Couldn't assemble that ship right now — please try again.",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            plugin.getLogger().warning("Delegated assembly returned null for " + shipType
                + "; kit not consumed, no ship spawned.");
            return;
        }
        ShipRegistry.register(instance);

        // Ship-level sidecar (ships/{id}.yml) — delegated recovery reads it in reconstructDelegatedShip. Delegated
        // ships are NOT added to the native chunk index (they recover via defCoreLib's own persistence).
        shipWorldData.saveShipMetadata(instance);

        // Consume one kit
        if (p.getGameMode() != GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
            p.getInventory().setItemInMainHand(hand);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // P7.C: delegated prefab assembly (prefab ship → defCoreLib block-free mechanism)
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Spawn a PREFAB ship as a DELEGATED defCoreLib mechanism (block-free assembly) instead of the native
     * entity engine. Builds a {@code PartSpec} list from the {@link ShipModel} + customization using the
     * EXACT native transform composition (so the delegated render matches native by construction), assembles
     * a driven block-free mechanism, and wraps it in a delegated {@link ShipInstance}. Returns {@code null}
     * on failure (caller falls back to native). See the Phase 7 plan for the transform derivation.
     */
    private ShipInstance spawnDelegatedPrefab(String shipType, ShipModel model, Location spawnAt,
                                              ShipCustomization customization) {
        return spawnDelegatedFromModel(null, shipType, model, spawnAt, customization);
    }

    /**
     * Spawn a ship as a DELEGATED defCoreLib mechanism (block-free assembly) from a {@link ShipModel} + pose. Shared
     * by the fresh prefab kit spawn ({@code id == null} → a random mechanism id) and the native→delegated migration
     * ({@code id != null} → the mechanism keeps the original ship id via defCoreLib's id-preserving overload). Builds
     * a {@code PartSpec} list — prefab models via {@link #buildPrefabParts}, custom (player-built) models via
     * {@link #buildCustomParts} — assembles a driven block-free mechanism, and wraps it in a delegated
     * {@link ShipInstance}. Returns {@code null} on failure (the caller decides fallback/retry).
     */
    private ShipInstance spawnDelegatedFromModel(java.util.UUID id, String shipType, ShipModel model, Location pose,
                                                 ShipCustomization customization) {
        return spawnDelegatedFromModel(id, shipType, model, pose, customization, null);
    }

    /** As above, plus an optional {@code cargo} map (block index → ItemStack[]) loaded into the mechanism's
     *  storage BEFORE it is persisted — used by the native→delegated migration to carry a native ship's captured
     *  chest contents across (block index i == model.parts index i == mechanism block index i). */
    private ShipInstance spawnDelegatedFromModel(java.util.UUID id, String shipType, ShipModel model, Location pose,
                                                 ShipCustomization customization,
                                                 java.util.Map<Integer, org.bukkit.inventory.ItemStack[]> cargo) {
        boolean custom = "custom".equals(shipType);
        // A Y-axis mechanism cannot render a non-identity rotation-matrix. Custom models are always identity
        // (BlockStructureScanner); prefab models could in principle carry one (none shipped do).
        if (!model.rotationTransform.equals(new org.joml.Matrix3f())) {
            logOnce(id, java.util.logging.Level.WARNING,
                "prefab model has a non-identity rotation-matrix, unsupported by the Y-axis mechanism; skipping delegation.", null);
            return null;
        }
        anon.def9a2a4.corelib.MechanismRegistry reg =
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getMechanismRegistry();

        // Vehicle: full-size ArmorStand (rideOffset 1.975 applies), entity yaw FORCED to 0 so display
        // passengers don't double-rotate by the heading (heading rides the mechanism transform). The
        // delegated ShipInstance ctor adds the ship-root tag + health.
        ArmorStand vehicle = pose.getWorld().spawn(pose.clone(), ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setGravity(false);
            as.setSilent(true);
            as.setPersistent(true);
            as.setMarker(false);
            as.setRotation(0f, 0f);
            as.customName(net.kyori.adventure.text.Component.empty());
            as.setCustomNameVisible(false);
        });

        anon.def9a2a4.corelib.Mechanism mechanism = null;
        ShipInstance ship = null;
        try {
            String type = custom ? "blockship:custom" : "blockship:prefab";
            java.util.List<anon.def9a2a4.corelib.MechanismRegistry.PartSpec> specs =
                custom ? buildCustomParts(model) : buildPrefabParts(model, customization);
            float rideOffset = anon.def9a2a4.corelib.MechanismRegistry.ARMORSTAND_RIDE_OFFSET;
            mechanism = (id != null)
                ? reg.assembleFromParts(id, type, specs, vehicle, rideOffset)
                : reg.assembleFromParts(type, specs, vehicle, rideOffset);
            ship = new ShipInstance(plugin, shipType, model, pose, customization, vehicle, mechanism);
            ship.adoptMechanismSeats();
            // Leadable: wire the single leadable shulker from its model-part collider (convenience; the
            // interaction handler's per-collider model-part fallback covers the general case).
            for (int i = 0; i < model.parts.size(); i++) {
                if (Boolean.TRUE.equals(model.parts.get(i).rawYaml.get("leadable"))) {
                    ship.leadableShulker = mechanism.colliderEntity(i);
                    break;
                }
            }
            // Migration: load carried-over native cargo into the freshly assembled (empty) typed inventories
            // BEFORE persist, so defCoreLib snapshots the contents. Without this, migration would spawn empty
            // chests and the native cargo would be lost.
            if (cargo != null) {
                for (java.util.Map.Entry<Integer, org.bukkit.inventory.ItemStack[]> e : cargo.entrySet()) {
                    org.bukkit.inventory.Inventory inv = mechanism.getStorage(e.getKey());
                    org.bukkit.inventory.ItemStack[] items = e.getValue();
                    if (inv != null && items != null) {
                        inv.setContents(java.util.Arrays.copyOf(items, inv.getSize()));
                    }
                }
            }
            reg.persist(mechanism); // crash-safe: survives restart + chunk reload via the M5 path
            ship.applyInitialDrivenPose(); // render the heading on frame 1 (no one-tick yaw flash)
        } catch (Throwable t) {
            // Rollback. assembleFromParts BORROWS the vehicle (ownsVehicle=false), so mechanism.destroy() only
            // strips the vehicle's tag and leaves the ArmorStand alive — the final unconditional remove kills it.
            // If ship != null, ship.destroy() already removed the vehicle (isValid-guarded), so the trailing
            // remove no-ops. Must not skip vehicle removal when mechanism != null but the ShipInstance ctor threw.
            if (mechanism != null) { try { mechanism.destroy(); } catch (Throwable ignored) {} }
            if (ship != null)      { try { ship.destroy();      } catch (Throwable ignored) {} }
            if (vehicle.isValid()) vehicle.remove();
            logOnce(id, java.util.logging.Level.SEVERE,
                "delegated assembly failed for type '" + shipType + "'.", t);
            return null;
        }
        return ship;
    }

    /** Translate a CUSTOM (player-built) {@link ShipModel} into a defCoreLib PartSpec list. Mirrors the native
     *  custom display loop: block parts carry their FULL persisted BlockData ({@code part.block}, so stairs/logs/
     *  slabs/doors keep their state) with a {@code display_yaw} rotation baked in; heads/skulls and banners become
     *  ItemDisplay parts (via {@link CustomShipRender}) with their captured NBT + HEAD/FIXED transform. Parts stay
     *  in {@code model.parts} order so the block index == collider/seat index. Transforms are vehicle-relative
     *  ({@code p.local}); the mechanism supplies the ride-offset (== the native customDisplayOffset) and the heading
     *  (via currentYaw), so no initialRotation/positionOffset/displayOffset is baked in here. */
    private java.util.List<anon.def9a2a4.corelib.MechanismRegistry.PartSpec> buildCustomParts(ShipModel model) {
        java.util.List<anon.def9a2a4.corelib.MechanismRegistry.PartSpec> parts = new java.util.ArrayList<>();
        for (int i = 0; i < model.parts.size(); i++) {
            ShipModel.ModelPart p = model.parts.get(i);
            // Collider frame: mirror buildPrefabParts' reconciliation with initialRotation=identity, Po=0.
            anon.def9a2a4.corelib.CollisionConfig col;
            if (p.collision.enable) {
                org.joml.Vector3f l3 = p.local.transformDirection(
                    new org.joml.Vector3f(1, 1, 1), new org.joml.Vector3f());
                org.joml.Vector3f off = new org.joml.Vector3f(p.collision.offset).sub(l3.mul(0.5f)).add(0f, 0.5f, 0f);
                col = new anon.def9a2a4.corelib.CollisionConfig(true, p.collision.size, off);
            } else {
                col = anon.def9a2a4.corelib.CollisionConfig.NONE;
            }

            if (CustomShipRender.isItemDisplayPart(p.rawYaml)) {
                // Head/skull or banner → ItemDisplay part (no +0.5 corner shift; skull/banner transform applied).
                org.joml.Matrix4f lt = CustomShipRender.applyDisplayTransform(new org.joml.Matrix4f(p.local), p.rawYaml);
                parts.add(anon.def9a2a4.corelib.MechanismRegistry.PartSpec.display(
                    CustomShipRender.buildDisplayItem(p.rawYaml, plugin, i),
                    CustomShipRender.displayMode(p.rawYaml), lt, col));
                continue;
            }

            // Normal block part: full-fidelity BlockData from part.block; +0.5 cancels the BlockDisplay corner
            // shift; display_yaw (chest-style directional) baked about the block centre before the +0.5.
            org.joml.Matrix4f lt = new org.joml.Matrix4f(p.local);
            CustomShipRender.applyDisplayYaw(lt, p.rawYaml);
            lt.mul(new org.joml.Matrix4f().translation(0.5f, 0.5f, 0.5f));
            if (p.storage != null) {
                org.bukkit.event.inventory.InventoryType it = p.storage.type.invType != null
                    ? p.storage.type.invType : org.bukkit.event.inventory.InventoryType.CHEST;
                parts.add(anon.def9a2a4.corelib.MechanismRegistry.PartSpec.block(
                    p.block, lt, col, it, p.storage.type.slots, p.storage.name));
            } else {
                parts.add(anon.def9a2a4.corelib.MechanismRegistry.PartSpec.block(p.block, lt, col));
            }
        }
        return parts;
    }

    /** Translate a prefab {@link ShipModel} + customization into a defCoreLib PartSpec list using the exact
     *  native transform composition. Block parts FIRST + in order (block index i == model.parts index i ==
     *  SeatInfo.blockIndex), then standalone item parts (banners/sails/balloon). See the Phase 7 plan. */
    private java.util.List<anon.def9a2a4.corelib.MechanismRegistry.PartSpec> buildPrefabParts(
            ShipModel model, ShipCustomization customization) {
        org.joml.Matrix4f ri = new org.joml.Matrix4f()
            .rotateY((float) Math.toRadians(model.initialRotation.x))
            .rotateX((float) Math.toRadians(model.initialRotation.y))
            .rotateZ((float) Math.toRadians(model.initialRotation.z));

        java.util.List<anon.def9a2a4.corelib.MechanismRegistry.PartSpec> parts = new java.util.ArrayList<>();
        // Block parts (must stay first + in order — seat/collider block indices reference model.parts).
        for (ShipModel.ModelPart p : model.parts) {
            // localTransform = R_i · T(Po) · L · T(+0.5): the trailing +0.5 cancels the engine's BlockDisplay
            // -0.5 corner shift exactly (for any rotation/scale in L).
            org.joml.Matrix4f lt = new org.joml.Matrix4f(ri)
                .mul(new org.joml.Matrix4f().translation(model.positionOffset))
                .mul(p.local)
                .mul(new org.joml.Matrix4f().translation(0.5f, 0.5f, 0.5f));
            anon.def9a2a4.corelib.CollisionConfig col;
            if (p.collision.enable) {
                // collision.offset = R_i·(Co − Po + b − 0.5·L₃ₓ₃·(1,1,1)) + (0,+0.5,0): reconcile the engine's
                // display↔collider frame (Po→Co, re-inject b, undo the baked +0.5 and the engine's fixed -0.5Y).
                org.joml.Vector3f l3 = p.local.transformDirection(
                    new org.joml.Vector3f(1, 1, 1), new org.joml.Vector3f());
                org.joml.Vector3f inner = new org.joml.Vector3f(model.collisionOffset)
                    .sub(model.positionOffset).add(p.collision.offset).sub(l3.mul(0.5f));
                org.joml.Vector3f off = ri.transformDirection(inner, new org.joml.Vector3f()).add(0f, 0.5f, 0f);
                col = new anon.def9a2a4.corelib.CollisionConfig(true, p.collision.size, off);
            } else {
                col = anon.def9a2a4.corelib.CollisionConfig.NONE;
            }
            if (p.storage != null) {
                // Typed, named cargo: the engine builds + persists the inventory (getStorage routes to it).
                // invType null => a size-based CHEST/BARREL/double-chest; pass CHEST so createTypedInventory
                // sizes by slots (27/54) rather than typing.
                org.bukkit.event.inventory.InventoryType it = p.storage.type.invType != null
                    ? p.storage.type.invType : org.bukkit.event.inventory.InventoryType.CHEST;
                parts.add(anon.def9a2a4.corelib.MechanismRegistry.PartSpec.block(
                    customizedPrefabBlock(p, customization), lt, col,
                    it, p.storage.type.slots, p.storage.name));
            } else {
                parts.add(anon.def9a2a4.corelib.MechanismRegistry.PartSpec.block(
                    customizedPrefabBlock(p, customization), lt, col));
            }
        }
        // Standalone item parts (banners/sails/balloon) — AFTER all block parts so indices never shift.
        for (ShipModel.ItemPart p : model.items) {
            org.joml.Matrix4f lt = new org.joml.Matrix4f(ri)
                .mul(new org.joml.Matrix4f().translation(model.positionOffset))
                .mul(p.local); // item primaries skip the -0.5 corner shift — no +0.5 here
            parts.add(anon.def9a2a4.corelib.MechanismRegistry.PartSpec.display(
                customizedPrefabItem(p, customization), p.displayMode, lt,
                anon.def9a2a4.corelib.CollisionConfig.NONE));
        }
        return parts;
    }

    /** Wood-type-customized {@link org.bukkit.block.data.BlockData} for a prefab block part (mirrors the
     *  native prefab path in ShipInstance). */
    private org.bukkit.block.data.BlockData customizedPrefabBlock(ShipModel.ModelPart p,
                                                                  ShipCustomization customization) {
        String blockName = String.valueOf(p.rawYaml.get("block"));
        String name = customization.getWoodType() != null
            ? WoodTypeUtil.replaceWoodType(blockName, customization.getWoodType()) : blockName;
        Object propsObj = p.rawYaml.get("properties");
        if (propsObj instanceof java.util.Map<?, ?> props && !props.isEmpty()) {
            StringBuilder s = new StringBuilder("minecraft:").append(name.toLowerCase()).append("[");
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : props.entrySet()) {
                if (!first) s.append(",");
                s.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
            s.append("]");
            return Bukkit.createBlockData(s.toString());
        }
        return Bukkit.createBlockData(Material.valueOf(name));
    }

    /** Customization-applied ItemStack for a prefab item part (custom banner / balloon color; mirrors native). */
    private ItemStack customizedPrefabItem(ShipModel.ItemPart p, ShipCustomization customization) {
        ItemStack item = p.item.clone();
        if (customization.getCustomBanner() != null && item.getType().name().endsWith("_BANNER")) {
            item = customization.getCustomBanner().clone();
        }
        if (customization.getBalloonColor() != null && customization.getTextureManager() != null
                && item.getType() == Material.PLAYER_HEAD && item.hasItemMeta()) {
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.SkullMeta skull) {
                String tex = customization.getTextureManager()
                    .getTexture("BALLOONS", customization.getBalloonColor());
                if (tex != null) {
                    ItemUtil.applyPlayerHeadTextureFromBase64(skull, tex, plugin);
                    item.setItemMeta(meta);
                }
            }
        }
        return item;
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

        // Parse shulker tags: displayship:{uuid}, shipseat:{seatIndex}, shipwheel:{location}, interact:{blockIndex}
        // Tag creation: ShipInstance constructor (collision boxes and seats)
        UUID shipId = null;
        int seatIndex = -1;
        String wheelLocation = null;
        int interactBlockIndex = -1;

        Set<String> tags = shulker.getScoreboardTags();
        shipId = ShipTags.extractShipId(tags);
        seatIndex = ShipTags.extractSeatIndex(tags);
        wheelLocation = ShipTags.extractWheelLocation(tags);
        interactBlockIndex = ShipTags.extractInteractIndex(tags);

        if (shipId == null) return;

        ShipInstance inst = ShipRegistry.byId(shipId);
        if (inst == null) {
            // Unregistered ship: migration may not have run yet (the player is here, so this chunk is loaded).
            // Migrate any native ship rooted in this chunk now, then fall through.
            inst = attemptInteractionMigration(shipId, shulker);
            if (inst == null) {
                e.setCancelled(true); // consume the click like the other ship-interaction branches
                return;
            }
        }
        if (!inst.vehicle.isValid()) return;

        // Delegated ships (M4) tag seats via corelib (block-index), not shipseat:{seatIdx}. Recover BlockShips'
        // seat index from the populated seatShulkers list so direct-seat-click mount + occupancy work.
        if (seatIndex < 0 && inst.mechanism != null) {
            seatIndex = inst.seatShulkers.indexOf(shulker);
        }

        // Delegated ships (M4) tag colliders only as corelib:mech:{id}:{i}:collider|seat, so the native
        // block-index extractors above (leadable/cannon/interact/storage) all return -1. Resolve the mechanism
        // block index once here; the lead/cannon/interact/storage branches below fall back to it for a delegated
        // ship (parity invariant: mechanism block index == model.parts index). -1 for a native/prefab ship.
        int mci = (inst.mechanism != null) ? ShipTags.extractCorelibBlockIndex(tags) : -1;

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
        // Delegated fallback: a corelib collider carries no leadable:{i} tag, so consult the model part's
        // leadable flag (same source native uses — BlockStructureScanner sets it from BlockProperties.isLeadable).
        if (leadableBlockIndex < 0 && mci >= 0 && isModelPartLeadable(inst, mci)) {
            leadableBlockIndex = mci;
        }
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
        // Delegated fallback: route mci ONLY when it is genuinely a cannon obsidian (model.cannons membership).
        // The cannon branch cancels+returns on index alone, so a bare mci (>= 0 for EVERY collider) would swallow
        // every delegated click and break interact/storage/seat-mount — the membership guard is load-bearing.
        if (cannonObsidianIndex < 0 && mci >= 0 && isDelegatedCannonObsidian(inst, mci)) {
            cannonObsidianIndex = mci;
        }
        if (cannonObsidianIndex >= 0) {
            inst.fireCannonsByObsidian(cannonObsidianIndex);
            e.setCancelled(true);
            return;
        }

        // Check if this shulker is an interaction block (crafting table, anvil, etc.)
        // Delegated fallback: use mci directly — openInteraction self-gates (returns false for a non-interactable
        // material) so a plain hull/seat block falls through without cancelling.
        int effInteractIndex = interactBlockIndex >= 0 ? interactBlockIndex : mci;
        if (effInteractIndex >= 0) {
            Material blockMaterial = effInteractIndex < inst.model.parts.size()
                ? inst.model.parts.get(effInteractIndex).block.getMaterial() : null;
            if (blockMaterial != null && InteractionBlockHandler.openInteraction(player, blockMaterial)) {
                e.setCancelled(true);
                return;
            }
        }

        // Check if this shulker has storage — it lives on the mechanism, keyed by block index. Returns the live
        // captured container inventory (vanilla chest/barrel/dispenser or custom block); edits round-trip on
        // disassemble via defCoreLib's container restore. Prefab container parts also route here —
        // assembleFromParts builds a typed inventory from the PartSpec storageType and getStorage returns it.
        if (mci >= 0) {
            Inventory storage = inst.mechanism.getStorage(mci);
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
            // Mark seat as occupied (extract seat index from shulker tags; delegated ships resolve via the
            // populated seatShulkers list since their seats carry corelib tags, not shipseat:{seatIdx}).
            int idx = ShipTags.extractSeatIndex(availableSeatShulker.getScoreboardTags());
            if (idx < 0) idx = inst.seatShulkers.indexOf(availableSeatShulker);
            if (idx >= 0) {
                inst.occupySeat(idx);
            }
            // Update timestamp after successful mount
            recordShulkerInteraction(player.getUniqueId());
        }
        e.setCancelled(true);
    }

    /**
     * Whether model block index {@code i} is a leadable fence. Mirrors the exact source native uses
     * ({@code BlockStructureScanner} sets {@code rawYaml["leadable"]=true} from {@code BlockProperties.isLeadable},
     * and native lead transfer / tagging read that same flag — see {@code ShipInstance} tag-bind and
     * {@code ShipWheelManager}'s leads-in seam). Used to route delegated collider clicks that carry no
     * native {@code leadable:{i}} tag.
     */
    private boolean isModelPartLeadable(ShipInstance inst, int i) {
        if (i < 0 || i >= inst.model.parts.size()) return false;
        Map<?, ?> rawYaml = inst.model.parts.get(i).rawYaml;
        return rawYaml != null && Boolean.TRUE.equals(rawYaml.get("leadable"));
    }

    /** Whether model block index {@code i} is a cannon obsidian (a {@code CannonInfo.obsidianBlockIndex}). The
     *  delegated cannon route needs this membership test because the cannon click branch cancels on index alone. */
    private boolean isDelegatedCannonObsidian(ShipInstance inst, int i) {
        for (ShipModel.CannonInfo cannon : inst.model.cannons) {
            if (cannon.obsidianBlockIndex == i) return true;
        }
        return false;
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

        if (shipId != null) {
            ShipInstance inst = ShipRegistry.byId(shipId);
            // Delegated seats carry corelib:mech:{id}:{i}:seat, not shipseat:{i}, so extractSeatIndex is -1.
            // Recover the seat index from the populated seatShulkers list (mirrors the mount-side M4 fallback)
            // so a dismount actually frees the seat — otherwise occupiedSeatIndices/hasDriver stay set and a
            // phantom driver keeps the delegated ship moving after the rider leaves.
            if (seatIndex < 0 && inst != null && inst.mechanism != null) {
                seatIndex = inst.seatShulkers.indexOf(shulker);
            }
            if (inst != null && seatIndex >= 0) {
                inst.freeSeat(seatIndex);
                // Speed persists - don't reset currentSpeed

                Player player = (Player) e.getExited();

                // Teleport player to safe position above collision shulkers
                Location safePos = inst.calculateSafeDismountPosition(player, shulker);
                player.teleport(safePos);
                player.setFallDistance(0);
                float currentSpeed = inst.physics.currentSpeed;
                float currentYVelocity = inst.physics.currentYVelocity;

                float yawRad = (float) Math.toRadians(-inst.physics.currentYaw);
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
        if (inst == null) {
            // Unregistered native ship: hitting it triggers migration to the delegated engine
            // (chunk-load only fires on the load transition), like the shulker-click hook.
            inst = attemptInteractionMigration(shipId, shulker);
            if (inst == null) return;   // couldn't migrate — leave it native (as before)
        }
        if (!inst.vehicle.isValid()) return;

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
        // extractShipId now resolves any corelib:mech: entity, including foreign mechanisms owned by sibling
        // plugins (pipes/railbound/etc.). Only suppress drops for a real BlockShips ship — matches every other
        // shulker handler's byId guard and keeps this from reaching into another plugin's entities.
        if (ShipRegistry.byId(shipId) == null) return;

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
        if (inst == null) {
            // Unregistered native ship: a projectile hit triggers migration too (like click/melee).
            inst = attemptInteractionMigration(shipId, shulker);
            if (inst == null) return;
        }
        if (!inst.vehicle.isValid()) return;

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
        Shulker wheel = findWheelShulker(ship);
        if (wheel == null || !wheel.isValid()) return;

        Location wheelLoc = wheel.getLocation().add(0, 0.5, 0);
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
     * The Mechanism-owned wheel shulker (the block at local (0,0,0)). Returns null if not found.
     */
    private Shulker findWheelShulker(ShipInstance ship) {
        int i = ship.model.wheelPartIndex();
        return i >= 0 ? ship.mechanism.colliderEntity(i) : null;
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

        // The Mechanism owns the collider shulkers. Resolve the block index from the corelib tag (same as the
        // interaction router) and read the box/part detail from the mechanism + model.
        if (inst.mechanism != null) {
            int i = ShipTags.extractCorelibBlockIndex(shulker.getScoreboardTags());
            org.bukkit.util.BoundingBox box = i >= 0 ? inst.mechanism.getColliderBoxByBlock(i) : null;
            ShipModel.ModelPart part = (i >= 0 && i < inst.model.parts.size()) ? inst.model.parts.get(i) : null;
            if (box == null || part == null) {
                player.sendMessage("§c(No mechanism collider for this shulker)");
                return;
            }
            player.sendMessage("");
            player.sendMessage("§b--- Collision Box (delegated) ---");
            player.sendMessage("§eBlock Index: §f" + i);
            player.sendMessage("§eSize: §f" + part.collision.size);
            player.sendMessage("§eOffset: §f[" + part.collision.offset.x + ", " +
                              part.collision.offset.y + ", " + part.collision.offset.z + "]");
            player.sendMessage("§eWorld Box: §f[" +
                              String.format("%.2f", box.getMinX()) + ".." + String.format("%.2f", box.getMaxX()) + ", " +
                              String.format("%.2f", box.getMinY()) + ".." + String.format("%.2f", box.getMaxY()) + ", " +
                              String.format("%.2f", box.getMinZ()) + ".." + String.format("%.2f", box.getMaxZ()) + "]");
            player.sendMessage("");
            player.sendMessage("§b--- Original YAML ---");
            FormatUtil.formatYamlToChat(player, part.rawYaml, "");
            return;
        }
    }

    // ===== Custom Ship Wheel System =====

    /**
     * Helper: Check if an item is a ship wheel custom item
     */
    private boolean isShipWheel(ItemStack stack) {
        return matchesCustomItemId(stack, "ship_wheel");
    }

    /**
     * Checks whether an item carries the given blockships custom_item_id PDC tag. Unlike matching by
     * display name, this cannot be forged by an anvil rename.
     */
    private boolean matchesCustomItemId(ItemStack stack, String customItemId) {
        if (stack == null || !stack.hasItemMeta()) return false;
        PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
        NamespacedKey itemIdKey = new NamespacedKey(plugin, "custom_item_id");
        return pdc.has(itemIdKey, PersistentDataType.STRING) &&
               customItemId.equals(pdc.get(itemIdKey, PersistentDataType.STRING));
    }

    /**
     * Whether a crafting-grid item satisfies a recipe ingredient. Custom-item ingredients are matched by
     * their custom_item_id PDC (rename-proof); everything else uses the ingredient's own matcher.
     */
    private boolean ingredientMatches(RecipeIngredient ingredient, ItemStack item) {
        if (ingredient instanceof CustomItemIngredient ci) {
            return matchesCustomItemId(item, ci.getCustomItemId());
        }
        return ingredient.matches(item);
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

        // WorldGuard: this places the wheel head programmatically (not a BlockPlaceEvent), so WG can't see
        // it natively. Deny planting a wheel in a protected region the player can't build in. Cancel the
        // event too, so vanilla doesn't fall back to placing the wheel item as a plain player head.
        anon.def9a2a4.blockships.integration.WorldGuardHook wg = anon.def9a2a4.blockships.integration.WorldGuardHook.get();
        if (wg.mightRestrict(targetBlock.getWorld()) && wg.isBuildDenied(targetBlock.getLocation(), player)) {
            player.sendMessage("§cYou can't place a ship wheel in this protected region.");
            event.setCancelled(true);
            return;
        }

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
        // Track W (W7): PlayerInteractEvent fires for BOTH hands; only the main hand should open the menu (else
        // it opens twice). Mirrors the guard on onShulkerClick.
        if (event.getHand() != EquipmentSlot.HAND) return;

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
            if (shulker != null && shulker.isValid()) {
                setCameraDistanceOnShulker(shulker, newValue);
            }
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
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onShipWheelBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isShipWheelBlock(block)) return;

        // Respect a break already cancelled by another protection plugin (GriefPrevention/Towny/etc.):
        // leave the wheel intact instead of manually breaking + dropping it.
        if (event.isCancelled()) return;

        // WorldGuard: this handler cancels + manually breaks the block, so it would otherwise fire even
        // when WG denied the break for a non-member. Respect build rights: leave the wheel intact.
        anon.def9a2a4.blockships.integration.WorldGuardHook wgWheel = anon.def9a2a4.blockships.integration.WorldGuardHook.get();
        if (wgWheel.mightRestrict(block.getWorld()) && wgWheel.isBuildDenied(block.getLocation(), event.getPlayer())) {
            event.getPlayer().sendMessage("§cYou can't break this ship wheel in this protected region.");
            event.setCancelled(true);  // self-cancel; don't rely on WorldGuard's own handler firing
            return;
        }

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
