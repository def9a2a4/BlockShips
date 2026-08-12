package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-world ship data storage for chunk-based loading.
 *
 * Storage structure:
 *   worlds/{worldName}/ships/{uuid}.yml - Individual ship metadata
 */
public class ShipWorldData {
    private final JavaPlugin plugin;
    private final File worldsFolder;

    // Set of all ship ids that have a persisted sidecar on disk (worlds/*/ships/*.yml), across every world
    // (loaded or not). Seeded once from disk in the constructor, then maintained incrementally on every sidecar
    // create (saveShipMetadata / saveShipMetadataAsync) and delete (removeShip). Backs getAllPersistedShipIds(),
    // which feeds the wheel-reap guard — so the maintenance MUST stay complete (a false-absent id reaps a live
    // ship's wheel). Thread-safe: adds happen on the main thread at call time (before any async submit).
    private final Set<UUID> persistedShipIds = ConcurrentHashMap.newKeySet();
    // NOTE: the legacy chunk-index (world -> "x,z" -> [ship ids], persisted to chunks.yml) was removed — it only
    // served the deleted native recovery path. Ship recovery now keys on defCoreLib's MechanismRegistry.

    // Cache of metadata existence checks (true = exists, false = doesn't exist)
    // Using ConcurrentHashMap for thread-safe access from chunk load events
    private final Map<String, Boolean> metadataExistsCache = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong lastCacheClear = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    private static final long CACHE_CLEAR_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    // Async I/O executor for non-blocking file operations
    private final java.util.concurrent.atomic.AtomicInteger pendingIOOperations = new java.util.concurrent.atomic.AtomicInteger(0);
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(
        r -> {
            Thread t = new Thread(r, "BlockShips-IO");
            t.setDaemon(true);
            return t;
        }
    );

    public ShipWorldData(JavaPlugin plugin) {
        this.plugin = plugin;
        this.worldsFolder = new File(plugin.getDataFolder(), "worlds");
        seedPersistedShipIds();
    }

    /**
     * Seeds {@link #persistedShipIds} from disk: every {@code worlds/<world>/ships/<uuid>.yml} sidecar. Runs once
     * in the constructor (at plugin enable), BEFORE any wheel-reap consumer can query the set. Independent of
     * {@code Bukkit.getWorlds()} — a directory walk sees worlds that exist on disk but aren't loaded.
     */
    private void seedPersistedShipIds() {
        if (!worldsFolder.exists()) return;
        File[] worldDirs = worldsFolder.listFiles(File::isDirectory);
        if (worldDirs == null) return;
        for (File worldDir : worldDirs) {
            // One-time cleanup: the legacy chunk-index file is no longer written or read. Delete it best-effort so
            // upgraded data folders self-tidy on first run of this build.
            File legacyChunks = new File(worldDir, "chunks.yml");
            if (legacyChunks.exists()) legacyChunks.delete();

            File shipsDir = new File(worldDir, "ships");
            File[] all = shipsDir.listFiles();
            if (all == null) continue;
            // Sweep temp files left by a crash between writeConfigAtomic's write and its rename. They are
            // never newer than the target in any usable sense — the rename is what publishes a write — so
            // deleting is correct. Note this does NOT match *.yml.corrupt, which is evidence and must stay.
            for (File f : all) {
                if (f.getName().endsWith(".tmp") && !f.delete()) {
                    plugin.getLogger().warning("Could not delete stale temp file " + f.getAbsolutePath());
                }
            }
            File[] shipFiles = shipsDir.listFiles((d, name) -> name.endsWith(".yml"));
            if (shipFiles == null) continue;
            for (File f : shipFiles) {
                String name = f.getName();
                try {
                    persistedShipIds.add(UUID.fromString(name.substring(0, name.length() - 4)));
                } catch (IllegalArgumentException ignored) {
                    // stray non-UUID filename — skip
                }
            }
        }
    }

    /** Distinguishes concurrent temp files. See {@link #writeConfigAtomic}. */
    private static final java.util.concurrent.atomic.AtomicLong TMP_SEQ = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Writes {@code cfg} to {@code target} atomically: full write to a temp sibling, then an atomic rename.
     *
     * <p>{@code YamlConfiguration.save(File)} truncates in place, so a crash, kill or ENOSPC mid-write left a
     * truncated sidecar — and {@code loadConfiguration} then swallowed the parse error and returned an empty
     * config, which read as "this ship has no id" and got its entities reaped. A rename is all-or-nothing:
     * the target is either the old file or the new one, never a prefix of either.
     *
     * <p>The temp name carries a unique suffix because two writers can target the same sidecar — the sync
     * path on the main thread and {@link #saveShipMetadataAsync} on {@code BlockShips-IO}. A shared fixed
     * temp name would let them interleave bytes into one file and then rename the wreckage over a good
     * target. With distinct temps the worst case is a lost update (last rename wins), and since both writers
     * snapshot full ship state on the main thread, that is at worst slightly stale — never corrupt.
     *
     * @return true if {@code target} now holds the new content.
     */
    private boolean writeConfigAtomic(File target, YamlConfiguration cfg) {
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().severe("Cannot create directory for " + target.getAbsolutePath());
            return false;
        }
        File tmp = new File(dir, target.getName() + "." + Long.toHexString(TMP_SEQ.getAndIncrement()) + ".tmp");
        try {
            cfg.save(tmp);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to write " + target.getName() + ": " + e.getMessage());
            // The target is untouched, so the previous good content survives.
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
            return false;
        }
    }

    // ===== Ship Metadata Operations =====

    /**
     * Saves ship metadata to per-world storage.
     * Does NOT include position - that comes from the recovered vehicle entity.
     */
    public void saveShipMetadata(ShipInstance ship) {
        World world = ship.vehicle.getLocation().getWorld();
        if (world == null) return;

        File shipFile = getShipFile(world.getName(), ship.id);
        shipFile.getParentFile().mkdirs();

        // Maintain the persisted-id set on the main thread at call time (present-sooner: a reap consumer must never
        // see a live ship's sidecar as absent). The sidecar is about to exist on disk regardless of async timing.
        persistedShipIds.add(ship.id);

        YamlConfiguration config = buildShipMetadataConfig(ship);
        config.set("entity_count", ship.countEntities());

        // Deliberately SYNCHRONOUS, not routed onto ioExecutor: migrateNativeShip writes the migrated marker
        // through here and reapStragglerEntities reads it back in the same chunk iteration
        // (migrateLoadedChunks runs the migrator then the reaper). Deferring the write would make the reaper
        // read the pre-migration sidecar and skip the stragglers it exists to sweep.
        if (writeConfigAtomic(shipFile, config)) {
            // Populate cache on successful save
            metadataExistsCache.put(world.getName() + ":" + ship.id, true);
        }
    }

    /**
     * Saves ship metadata asynchronously: snapshots state on calling thread (must be main thread),
     * then writes YAML to disk on the IO executor thread.
     */
    public void saveShipMetadataAsync(ShipInstance ship) {
        World world = ship.vehicle.getLocation().getWorld();
        if (world == null) return;

        // Snapshot: build YamlConfiguration on main thread (all Bukkit API calls happen here)
        String worldName = world.getName();
        UUID shipId = ship.id;
        YamlConfiguration config = buildShipMetadataConfig(ship);
        int entityCount = ship.countEntities();
        config.set("entity_count", entityCount);

        // Maintain the persisted-id set on the MAIN thread here, BEFORE the async submit — not inside the lambda —
        // so a main-thread reap consumer never observes a false-absent id while the write is still queued.
        persistedShipIds.add(shipId);

        // Write: file I/O on async thread
        pendingIOOperations.incrementAndGet();
        ioExecutor.submit(() -> {
            try {
                File shipFile = getShipFile(worldName, shipId);
                shipFile.getParentFile().mkdirs();
                if (writeConfigAtomic(shipFile, config)) {
                    metadataExistsCache.put(worldName + ":" + shipId, true);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to async save ship metadata for " + shipId + ": " + e.getMessage());
            } finally {
                pendingIOOperations.decrementAndGet();
            }
        });
    }

    /**
     * Builds a YamlConfiguration with all ship metadata.
     * Must be called on the main thread (accesses Bukkit API).
     */
    private YamlConfiguration buildShipMetadataConfig(ShipInstance ship) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("id", ship.id.toString());
        config.set("ship_type", ship.shipType);
        // Engine marker: a delegated (mechanism) ship's sidecar carries migrated=true so the migration reader can
        // distinguish it from a legacy native (0.0.17) sidecar (which never had this key). See ShipState.migrated.
        if (ship.mechanism != null) {
            config.set("migrated", true);
        }

        // Model path for prefab ships
        String modelPath = plugin.getConfig().getString("ships." + ship.shipType + ".model-path");
        if (modelPath != null) {
            config.set("model_path", modelPath);
        }

        // Model data for custom ships
        if ("custom".equals(ship.shipType) && ship.sourceModel != null) {
            config.set("model_data", ship.sourceModel.toMap());
        }

        // Customization
        config.set("wood_type", ship.customization.getWoodType());
        if (ship.customization.getBalloonColor() != null) {
            config.set("balloon_color", ship.customization.getBalloonColor());
        }
        if (ship.customization.getCustomBanner() != null) {
            try {
                byte[] bytes = ship.customization.getCustomBanner().serializeAsBytes();
                config.set("banner", Base64.getEncoder().encodeToString(bytes));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to serialize banner for ship " + ship.id + ": " + e.getMessage());
            }
        }

        // Inventory contents are owned by the mechanism's persistence sidecar for a delegated ship
        // (ship's own storage map is always empty), so there is nothing to serialize here.

        // Internal yaw for chunk recovery (vehicle yaw is frozen at spawnYaw)
        config.set("current_yaw", ship.physics.currentYaw);

        return config;
    }

    /**
     * Loads ship metadata asynchronously from per-world storage.
     * Returns a CompletableFuture that completes with the ShipState.
     */
    public CompletableFuture<ShipPersistence.ShipState> loadShipMetadataAsync(World world, UUID shipId) {
        String worldName = world.getName();
        pendingIOOperations.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return loadShipMetadataSync(worldName, shipId).state();
            } finally {
                pendingIOOperations.decrementAndGet();
            }
        }, ioExecutor);
    }

    /** Outcome of reading one sidecar. See {@link #loadShipMetadataChecked}. */
    public enum LoadStatus {
        /** Parsed cleanly; {@code state} is non-null. */
        OK,
        /** No file on disk. The ship genuinely has no sidecar. */
        ABSENT,
        /** I/O error reading the file (permissions, a disk blip, a concurrent write). Say nothing about
         *  the ship's validity — retry on the next load. Never destructive. */
        TRANSIENT,
        /** The file exists but is unusable: unparseable YAML, or a missing/blank/mismatched {@code id}. */
        CORRUPT
    }

    /** A sidecar read result. {@code state} is non-null only when {@code status == OK}. */
    public record MetadataLoad(LoadStatus status, @Nullable ShipPersistence.ShipState state) {
        private static final MetadataLoad ABSENT = new MetadataLoad(LoadStatus.ABSENT, null);
        private static final MetadataLoad TRANSIENT = new MetadataLoad(LoadStatus.TRANSIENT, null);
        private static final MetadataLoad CORRUPT = new MetadataLoad(LoadStatus.CORRUPT, null);
    }

    /**
     * Loads ship metadata from per-world storage (sync version for internal use).
     *
     * <p>Distinguishes ABSENT from CORRUPT from TRANSIENT, which the old signature could not: it returned
     * null only for a missing file, and {@code YamlConfiguration.loadConfiguration} swallows both I/O and
     * parse errors into an <i>empty</i> config. A truncated sidecar therefore produced a config whose
     * {@code id} was null and NPE'd on {@code UUID.fromString} — thrown from the reaper, on the main thread,
     * inside a chunk-load event. Callers that destroy things must be able to fail closed on CORRUPT.
     */
    private MetadataLoad loadShipMetadataSync(String worldName, UUID shipId) {
        File shipFile = getShipFile(worldName, shipId);
        if (!shipFile.exists()) {
            return MetadataLoad.ABSENT;
        }

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(shipFile);
        } catch (IOException e) {
            // Transient: says nothing about whether the ship is valid. Do NOT quarantine, do NOT reap.
            plugin.getLogger().warning("Could not read ship sidecar " + shipId + " (world=" + worldName
                + "): " + e.getMessage() + " — treating as a transient error, will retry");
            return MetadataLoad.TRANSIENT;
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            plugin.getLogger().severe("Ship sidecar " + shipId + " (world=" + worldName
                + ") is not valid YAML: " + e.getMessage());
            return MetadataLoad.CORRUPT;
        }

        String id = config.getString("id");
        if (id == null || id.isBlank()) {
            plugin.getLogger().severe("Ship sidecar " + shipId + " (world=" + worldName + ") has no id");
            return MetadataLoad.CORRUPT;
        }
        UUID parsedId;
        try {
            parsedId = UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("Ship sidecar " + shipId + " (world=" + worldName
                + ") has an unreadable id '" + id + "'");
            return MetadataLoad.CORRUPT;
        }
        if (!parsedId.equals(shipId)) {
            // Silently accepted before, building a ShipState under the wrong UUID.
            plugin.getLogger().severe("Ship sidecar " + shipId + " (world=" + worldName
                + ") declares a different id " + parsedId + "; refusing to use it");
            return MetadataLoad.CORRUPT;
        }

        try {
            return new MetadataLoad(LoadStatus.OK, readState(config, worldName, parsedId));
        } catch (Exception e) {
            plugin.getLogger().severe("Ship sidecar " + shipId + " (world=" + worldName
                + ") could not be read: " + e.getMessage());
            return MetadataLoad.CORRUPT;
        }
    }

    /** Builds the {@link ShipPersistence.ShipState} from an already-validated config. */
    private ShipPersistence.ShipState readState(YamlConfiguration config, String worldName, UUID id) {
        String shipType = config.getString("ship_type", "smallship");
        String modelPath = config.getString("model_path");
        String woodType = config.getString("wood_type", "OAK");
        String balloonColor = config.getString("balloon_color");
        String bannerData = config.getString("banner");

        // Model data for custom ships - must convert MemorySection to Map
        Map<String, Object> modelData = null;
        if (config.contains("model_data")) {
            org.bukkit.configuration.ConfigurationSection modelSection = config.getConfigurationSection("model_data");
            if (modelSection != null) {
                modelData = modelSection.getValues(true);  // true = deep copy
            }
        }

        // Inventory data - must convert MemorySection to Map
        Map<Integer, String> inventoryData = new HashMap<>();
        if (config.contains("inventories")) {
            org.bukkit.configuration.ConfigurationSection invSection = config.getConfigurationSection("inventories");
            if (invSection != null) {
                for (String key : invSection.getKeys(false)) {
                    inventoryData.put(Integer.parseInt(key), invSection.getString(key));
                }
            }
        }

        // Entity count for recovery validation (default 0 for legacy data)
        int entityCount = config.getInt("entity_count", 0);

        // Internal yaw for chunk recovery (absent in legacy metadata -> NaN -> fall back to vehicle NBT)
        float currentYaw = config.contains("current_yaw")
            ? (float) config.getDouble("current_yaw") : Float.NaN;

        // Create ShipState without position (position comes from recovered vehicle)
        ShipPersistence.ShipState state = new ShipPersistence.ShipState(
            id,
            shipType,
            modelPath,
            worldName,
            0, 0, 0,      // Position will come from vehicle
            currentYaw, 0, // Yaw from metadata; pitch will come from vehicle
            bannerData,
            woodType,
            balloonColor,
            inventoryData,
            modelData,
            entityCount
        );
        state.migrated = config.getBoolean("migrated", false);
        return state;
    }

    /**
     * Loads ship metadata from per-world storage (synchronous).
     * Returns a ShipState without position data, or null if it could not be read for any reason.
     *
     * <p>Callers that DESTROY something on a null must use {@link #loadShipMetadataChecked} instead and
     * fail closed on CORRUPT/TRANSIENT — a null here conflates "this ship has no sidecar" with "we could
     * not read its sidecar", and reaping on the latter deletes a live ship.
     */
    public ShipPersistence.ShipState loadShipMetadata(World world, UUID shipId) {
        return loadShipMetadataSync(world.getName(), shipId).state();
    }

    /** As {@link #loadShipMetadata}, but reports WHY there is no state. */
    public MetadataLoad loadShipMetadataChecked(World world, UUID shipId) {
        return loadShipMetadataSync(world.getName(), shipId);
    }

    /**
     * One-shot enable-time sweep: rename every unreadable sidecar to {@code <uuid>.yml.corrupt} (bytes
     * preserved) and drop its id from {@link #persistedShipIds}.
     *
     * <p>Must run AFTER the wheel manager's {@code loadAll} and BEFORE {@code forceRecoverDelegatedShips}
     * / {@code migrateLoadedChunks}, which both consume sidecars. Quarantine deliberately does NOT live in
     * the shared loader: the migrator runs before the reaper on the same chunk, so a loader-side rename
     * would flip CORRUPT to ABSENT between them and the reaper would delete a live ship's entities.
     * TRANSIENT failures are left strictly alone — renaming a good file on a disk blip would create the
     * data-loss path this is meant to close.
     *
     * @return the number of sidecars quarantined.
     */
    public int quarantineCorruptSidecars() {
        if (!worldsFolder.exists()) return 0;
        File[] worldDirs = worldsFolder.listFiles(File::isDirectory);
        if (worldDirs == null) return 0;
        int quarantined = 0;
        for (File worldDir : worldDirs) {
            File[] shipFiles = new File(worldDir, "ships").listFiles((d, name) -> name.endsWith(".yml"));
            if (shipFiles == null) continue;
            for (File f : shipFiles) {
                String name = f.getName();
                UUID id;
                try {
                    id = UUID.fromString(name.substring(0, name.length() - 4));
                } catch (IllegalArgumentException ignored) {
                    continue;  // stray non-UUID filename — not ours to judge
                }
                if (loadShipMetadataSync(worldDir.getName(), id).status() != LoadStatus.CORRUPT) continue;
                File dest = new File(f.getParentFile(), name + ".corrupt");
                if (f.renameTo(dest)) {
                    // Otherwise resolveWheelState keeps reading UNLOADED_RECOVERABLE forever and the wheel
                    // can never be assembled or reaped.
                    persistedShipIds.remove(id);
                    metadataExistsCache.put(worldDir.getName() + ":" + id, false);
                    quarantined++;
                    plugin.getLogger().severe("Quarantined corrupt ship sidecar " + id + " (world="
                        + worldDir.getName() + ") to " + dest.getName() + "; its blocks are NOT recoverable "
                        + "from it, but nothing was deleted.");
                } else {
                    plugin.getLogger().severe("Could not quarantine corrupt ship sidecar " + id
                        + " (world=" + worldDir.getName() + "); leaving it in place.");
                }
            }
        }
        return quarantined;
    }

    /**
     * Checks if metadata exists for a ship, using cache.
     * Much faster than loadShipMetadata() for orphan cleanup.
     * Caches both positive and negative results to avoid repeated file I/O.
     */
    public boolean hasMetadata(World world, UUID shipId) {
        // Clear cache periodically using atomic compare-and-set to avoid race conditions
        long now = System.currentTimeMillis();
        long lastClear = lastCacheClear.get();
        if (now - lastClear > CACHE_CLEAR_INTERVAL_MS) {
            if (lastCacheClear.compareAndSet(lastClear, now)) {
                metadataExistsCache.clear();
            }
        }

        String cacheKey = world.getName() + ":" + shipId;
        Boolean cached = metadataExistsCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Check file exists and cache the result
        boolean exists = getShipFile(world.getName(), shipId).exists();
        metadataExistsCache.put(cacheKey, exists);
        return exists;
    }

    /**
     * Removes a ship from storage completely.
     *
     * @return true if the on-disk ship file was removed (or was already absent), false if the file
     *         still exists after the delete attempt. The persisted-id set is always updated.
     */
    public boolean removeShip(World world, UUID shipId) {
        // Update cache to indicate file no longer exists
        metadataExistsCache.put(world.getName() + ":" + shipId, false);
        // Maintain the persisted-id set (main thread). The sidecar is about to be deleted; drop it from the set so
        // the reap guard and stats stop counting it.
        persistedShipIds.remove(shipId);

        // Remove ship file
        boolean fileOk = true;
        File shipFile = getShipFile(world.getName(), shipId);
        if (shipFile.exists() && !shipFile.delete() && shipFile.exists()) {
            fileOk = false;
            plugin.getLogger().severe("Failed to delete ship file for " + shipId + " (world="
                + world.getName() + "): " + shipFile.getAbsolutePath());
        }
        return fileOk;
    }

    // ===== Persistence =====

    /**
     * Saves metadata for all currently loaded ships.
     */
    public void saveAll() {
        for (ShipInstance ship : ShipRegistry.getAllShips()) {
            saveShipMetadata(ship);
        }
    }

    // ===== Helpers =====

    private File getShipFile(String worldName, UUID shipId) {
        return new File(worldsFolder, worldName + "/ships/" + shipId.toString() + ".yml");
    }

    /**
     * All persisted ship ids across every world on disk (loaded or not), scanned from
     * {@code worlds/*}/ships/*.yml sidecars (the source of truth for recoverability). Seeded once at
     * construction and maintained incrementally on every sidecar save/delete, so it sees worlds that exist on
     * disk but aren't currently loaded — unlike iterating {@link org.bukkit.Bukkit#getWorlds()}. No I/O here.
     */
    public Set<UUID> getAllPersistedShipIds() {
        return new HashSet<>(persistedShipIds);
    }

    /**
     * Shuts down the async I/O executor.
     * Should be called on plugin disable.
     */
    public void shutdown() {
        ioExecutor.shutdown();
        try {
            // Wait for pending I/O operations to complete (max 5 seconds)
            long start = System.currentTimeMillis();
            while (pendingIOOperations.get() > 0 && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(50);
            }
            if (pendingIOOperations.get() > 0) {
                plugin.getLogger().warning("Forcing shutdown with " + pendingIOOperations.get() + " pending I/O operations");
            }
            if (!ioExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
