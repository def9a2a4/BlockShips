package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.ShipConfig;
import anon.def9a2a4.blockships.ShipCustomization;
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
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Manages placed ship wheels in the world.
 * Handles the assembly/disassembly of custom ships from ship wheel blocks.
 */
public class ShipWheelManager {
    private static final String WHEELS_FILE = "ship_wheels.yml";

    private final JavaPlugin plugin;

    /**
     * Wheel id → wheel data.
     *
     * <p><b>Keyed by identity, never by location.</b> Assembly airs the wheel's block out of the world, so an
     * assembled ship's cell is empty and anything may occupy it. Under the old location keying that made the
     * cell's coordinates both the ship's identity and a free-for-all: planting a head there let anyone destroy
     * or steer the ship (P1), and placing a real wheel there evicted the sailing ship's record entirely, so it
     * could never be disassembled again (P2). A {@code put} under a fresh id evicts nothing.
     *
     * <p>{@code ShipWheelData.blockLocation} is now a <i>cache</i> of where the block currently is; the
     * block's own {@code blockships:wheel_id} PDC is the truth. A stale cache is expected; it is never
     * treated as corruption. (Phase 2A adds the PDC-first resolver that acts on that.)
     */
    private final Map<UUID, ShipWheelData> placedWheels;

    /**
     * Rows from ship_wheels.yml that could not be turned into a {@link ShipWheelData} — almost always
     * because their world had not been loaded yet, occasionally because they duplicate an id or a cell.
     * Held verbatim and re-emitted by {@link #saveAll()} so that saving is never destructive.
     *
     * <p>These rows are <b>not</b> re-parsed when their world later loads: {@link #loadAll()} runs once from
     * plugin enable and there is no {@code WorldLoadEvent} hook. A wheel in a late-loading world therefore
     * survives the file but stays non-functional until the next restart. That is a deliberate, documented
     * limitation — the point of this list is to stop the data from being deleted, not to hot-recover it.
     */
    private final List<Map<String, Object>> unresolvedRows = new ArrayList<>();

    // Particle colors for ship detection visualization
    private static final Color PARTICLE_WHITE = Color.fromRGB(255, 255, 255);
    private static final Color PARTICLE_ORANGE = Color.fromRGB(255, 165, 0);
    private static final Color PARTICLE_RED = Color.fromRGB(255, 50, 50);

    public ShipWheelManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.placedWheels = new HashMap<>();
        registerLeadsInSeam();
    }

    /**
     * M1 seam — leads-in. Registers a ONE-TIME registry pre-air-out listener: during a custom-ship assembly,
     * defCoreLib airs out the source fences itself, so the only window where a live fence and its collider
     * shulker coexist is the pre-air-out callback. For each leadable source fence, transfer any entities
     * leashed to it onto the mechanism's collider shulker (mirrors the native leads-in transfer).
     * Registered once (not per-assembly, which would stack duplicate listeners) and filtered by mech type.
     * Uses the source block MATERIAL for the leadable test (no ShipInstance exists yet) via the same
     * {@code BlockConfigManager} source the scan uses, and the block-index parity (source list position i ==
     * mechanism block index i) to reach {@code colliderEntity(i)}.
     */
    private void registerLeadsInSeam() {
        anon.def9a2a4.corelib.MechanismRegistry mechRegistry =
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getMechanismRegistry();
        mechRegistry.addPreAirOutListener((mech, sourceBlocks) -> {
            // MechanismRegistry has no listener-removal, so on a BlockShips-only reload (CoreLib left running)
            // this lambda from the previous, now-disabled instance stays registered. Bail if that plugin is
            // gone — the live instance's listener handles the assembly.
            if (!plugin.isEnabled()) return;
            if (!"blockship:custom".equals(mech.type())) return;
            BlockConfigManager cfg = BlockConfigManager.getInstance();
            for (int i = 0; i < sourceBlocks.size(); i++) {
                Block src = sourceBlocks.get(i);
                if (src == null || !cfg.getProperties(src.getType()).isLeadable()) continue;
                List<org.bukkit.entity.Entity> leashed = findEntitiesLeashedToFence(src.getLocation());
                if (leashed.isEmpty()) continue;
                Shulker collider = mech.colliderEntity(i);
                if (collider == null) continue; // leadable fence without a collider → nothing to ride
                for (org.bukkit.entity.Entity entity : leashed) {
                    ((io.papermc.paper.entity.Leashable) entity).setLeashHolder(collider);
                }
            }
        });
    }

    // ===== Persistence =====

    /**
     * Saves all ship wheels to ship_wheels.yml.
     *
     * @return true if the file was written successfully, false if saving failed.
     */
    /** While true, {@link #saveAll()} only marks the set dirty; {@link #endBatch()} does the one real write. */
    private boolean batching = false;
    private boolean batchDirty = false;

    /**
     * Coalesce the saves inside a bulk operation into one write.
     *
     * <p>{@code saveAll} rewrites the whole file, and {@code disassembleShip} calls it twice per ship — so a
     * bulk command over N ships did O(N²) row serializations and 2N+1 file writes in a single tick. Every
     * one of those is now atomic (temp write + rename), which makes the cost worse, not better.
     *
     * <p>Deliberately explicit begin/end rather than a lambda wrapper: the one caller mutates a pile of local
     * tallies, and forcing those through effectively-final holders would obscure it. Must be paired in a
     * {@code finally}.
     */
    public void beginBatch() {
        batching = true;
        batchDirty = false;
    }

    /** Set while a deferred flush is already queued, so a burst of changes still costs one write. */
    private boolean saveScheduled = false;

    /**
     * Record that the wheel set changed, and flush on the next tick rather than now.
     *
     * <p>For callers on a hot engine path, where {@link #saveAll()} would be wrong for its cost rather than
     * its effect: it re-serialises every wheel, writes a temp file, renames it and logs a line. The glue
     * anchor provider is the motivating case — corelib consults it for every block of every glued structure
     * on every mover stroke, so a rotator carrying a wheel would otherwise write the file continuously, in
     * lockstep with a redstone clock, forever.
     *
     * <p>Coalescing is safe because the in-memory set is authoritative for everything except a crash, and the
     * exposure is one tick. Callers that create a block-and-record pair (placement, adoption) must still use
     * {@link #saveAll()} directly: for those, a crash in that window leaves a stamped block with no record,
     * which is worse than the write.
     */
    private void markDirty() {
        if (batching) { batchDirty = true; return; }
        if (saveScheduled) return;
        saveScheduled = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            saveScheduled = false;
            saveAll();
        });
    }

    /** Ends a {@link #beginBatch()} and flushes if anything changed. @return false if the write failed. */
    public boolean endBatch() {
        batching = false;
        if (!batchDirty) return true;
        batchDirty = false;
        return saveAll();
    }

    public boolean saveAll() {
        if (batching) {
            batchDirty = true;
            return true;
        }
        File wheelsFile = new File(plugin.getDataFolder(), WHEELS_FILE);
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();

        // Serialise INSIDE the try. toMap reads the record's world name, and Location.getWorld() throws once
        // its weak reference to an unloaded world is collected — so a single wheel in a world that was
        // unloaded at runtime made every save throw. That is worse than losing the save: it propagated out of
        // onDisable before the ship subsystem was shut down or the IO executor drained, so a /stop wrote
        // nothing at all. A row that cannot be serialised is skipped and preserved verbatim instead.
        List<Map<String, Object>> wheelList = new ArrayList<>();
        File tmp = new File(wheelsFile.getParentFile(), WHEELS_FILE + ".tmp");
        try {
            for (ShipWheelData data : placedWheels.values()) {
                try {
                    wheelList.add(data.toMap());
                } catch (Throwable t) {
                    plugin.getLogger().warning("Could not serialise ship wheel " + data.getWheelId()
                        + " (its world is probably unloaded); leaving the previous row in place.");
                }
            }
            // Re-emit rows that could not be parsed at load — almost always because their world had not been
            // loaded yet. Without this, saving is destructive: loadAll drops those rows and the very next save
            // rewrites the file without them, permanently deleting every wheel in a world that a world manager
            // enables after us.
            wheelList.addAll(unresolvedRows);
            config.set("wheels", wheelList);

            // Atomic: write a temp sibling, then rename. config.save() truncates in place, so a crash, kill
            // or ENOSPC mid-write left a truncated ship_wheels.yml — and loadConfiguration swallows the parse
            // error and hands back an EMPTY config, so the next boot loaded zero wheels and the very next
            // save wrote that empty set back. Every wheel on the server, gone, with no error a player would
            // ever see.
            //
            // A fixed temp name is safe here (unlike the sidecars): saveAll is main-thread only.
            config.save(tmp);
            try {
                Files.move(tmp.toPath(), wheelsFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp.toPath(), wheelsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            plugin.getLogger().info("Saved " + wheelList.size() + " ship wheels to " + WHEELS_FILE);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save ship wheels: " + e.getMessage());
            // The live file is untouched — the previous good content survives.
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
            return false;
        }
    }

    /**
     * Loads all ship wheels from ship_wheels.yml.
     */
    public void loadAll() {
        File wheelsFile = new File(plugin.getDataFolder(), WHEELS_FILE);

        // Sweep a temp file left by a crash between saveAll's write and its rename. The rename is what
        // publishes a write, so an orphaned temp is never the live copy and deleting it is correct.
        File staleTmp = new File(plugin.getDataFolder(), WHEELS_FILE + ".tmp");
        if (staleTmp.exists() && !staleTmp.delete()) {
            plugin.getLogger().warning("Could not delete stale " + staleTmp.getName());
        }

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
        int quarantined = 0;
        // Cells already claimed this load. Location keying used to merge same-cell rows silently; id keying
        // will not, so without this both survive forever and inflate /blockships stats.
        Set<String> seenCells = new HashSet<>();

        for (Map<?, ?> map : wheelList) {
            Map<String, Object> row = copyRow(map);
            try {
                ShipWheelData data = ShipWheelData.fromMap(row);
                if (data == null) {
                    // World not loaded (or otherwise unresolvable). Keep the row verbatim.
                    unresolvedRows.add(row);
                    quarantined++;
                    continue;
                }
                // Duplicate id (copied ship_wheels.yml, cloned world): the second put would silently evict
                // the first. Do NOT re-mint the later one — the block in the world still carries the old id,
                // so re-minting manufactures a record that can never resolve. Quarantine it instead.
                if (placedWheels.containsKey(data.getWheelId())) {
                    plugin.getLogger().warning("Duplicate wheel id " + data.getWheelId()
                        + " in " + WHEELS_FILE + "; keeping the first and quarantining the later row.");
                    unresolvedRows.add(row);
                    quarantined++;
                    continue;
                }
                // Two records sharing a cached cell is NOT corruption, and quarantining one was destructive.
                //
                // A sailing wheel's record keeps caching its launch cell for the whole voyage (the cache is
                // only rewritten on landing or adoption), and that cell stands empty and buildable — so
                // "assembled wheel A and docked wheel B share a cell" is the success state of id keying, not a
                // fault. Dropping one of them from placedWheels made it invisible to every lookup, command and
                // save while its block sat in the world still stamped, so that wheel's menu was dead for the
                // whole session and its ship unreachable. Which one lost was decided by HashMap iteration
                // order via the previous save, and because quarantined rows are re-emitted AFTER live ones the
                // loser stayed second in the file and was re-quarantined on every boot after.
                //
                // Warn only, and only when BOTH rows are docked, which is the one case that really is odd. The
                // gate has to sit on both sides of the add() — testing it only before the check would still
                // quarantine the legitimate pair whenever the assembled row happened to be read first.
                // isAssembled() is usable here (fromMap sets the link from ship_uuid) and is the only thing
                // that is: resolveWheelState would misread every linked row, since ShipRegistry is empty and
                // no chunk is loaded this early.
                String cell = locationKey(data.getBlockLocation());
                if (!data.isAssembled() && cell != null && !seenCells.add(cell)) {
                    plugin.getLogger().warning("Two docked wheels are recorded at " + cell + " ("
                        + data.getWheelId() + " and an earlier row). Keeping both — wheels are identified by "
                        + "their id, not their cell. Check them with /blockships wheels list.");
                }
                placedWheels.put(data.getWheelId(), data);
                loaded++;
            } catch (Exception e) {
                // Also non-destructive: a row that throws mid-parse used to be dropped permanently.
                plugin.getLogger().warning("Failed to load ship wheel: " + e.getMessage());
                unresolvedRows.add(row);
                quarantined++;
            }
        }

        plugin.getLogger().info("Loaded " + loaded + " ship wheels"
            + (quarantined > 0 ? " (" + quarantined + " unresolved, preserved on save)" : ""));
    }

    /** Defensive copy of a raw config row into the shape {@link ShipWheelData#fromMap} and saveAll want. */
    private static Map<String, Object> copyRow(Map<?, ?> raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() != null) row.put(e.getKey().toString(), e.getValue());
        }
        return row;
    }

    /**
     * Creates a stable string key from a Location using block coordinates.
     * Avoids floating-point precision issues with Location as HashMap key.
     */
    private static String locationKey(Location loc) {
        World w = liveWorld(loc);
        if (w == null) return null;
        return w.getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    /**
     * This location's world, or null if it does not currently have one.
     *
     * <p>Bukkit's {@link Location} holds a <i>weak</i> reference to its world, and {@code getWorld()} throws
     * {@code IllegalArgumentException("World unloaded")} once that reference is collected — it does not return
     * null. Records outlive worlds (a runtime world unload, a multiverse-style setup), so every bare
     * {@code getWorld()} in this class was a latent throw on a path with no reason to expect one.
     *
     * <p>Two of those mattered. {@code resolveWheelState} dereferenced it while answering whether a ship was
     * recoverable, so a single record in an unloaded world made <i>every</i> right-click on <i>any</i> wheel
     * throw. And {@code saveAll} serialises through {@code toMap}, which reads the world name — so one such
     * record made every save throw, which propagated out of {@code onDisable} before it could shut the ship
     * subsystem down or drain the IO executor.
     */
    private static @Nullable World liveWorld(@Nullable Location loc) {
        if (loc == null) return null;
        try {
            return loc.isWorldLoaded() ? loc.getWorld() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The wheel whose <i>cached</i> cell is {@code cell}, or null.
     *
     * <p>A linear scan, deliberately — not a maintained reverse index. An index would need updating at every
     * mutation site and would become a second source of truth that could authorise the wrong block; this
     * cannot desync. It is also far cheaper than the {@code getState()} that any PDC read on the same call
     * path already pays, and the class already scans on interactive paths ({@link #getWheelByShipUUID},
     * {@link #getNearestWheel}).
     *
     * <p>The cached cell is a hint, not identity. Phase 2A resolves blocks by their {@code wheel_id} PDC and
     * this becomes a fallback for wheels that predate the stamp.
     */
    private @Nullable ShipWheelData byCachedCell(Location cell) {
        if (cell == null) return null;
        for (ShipWheelData w : placedWheels.values()) {
            if (cellsAgree(cell, w.getBlockLocation())) return w;
        }
        return null;
    }

    /**
     * Registers a ship wheel at the given location with its facing direction, stamping identity onto the
     * block. The block itself should already be placed, textured and updated by the event handler.
     *
     * @return false if the block could not be stamped, in which case nothing was recorded and the caller
     *         must undo the placement. This genuinely can fail (defCoreLib missing, registration failed, the
     *         block is not a tile entity), so the caller's failure branch is live — it is not dead code.
     */
    public PlaceResult placeWheel(Location location, BlockFace facing) {
        // Is a live ship's dock here? Its cell stands empty for the whole voyage, so it is buildable and
        // nothing stopped a second wheel being planted on it. That no longer steals the record (identity is
        // the block's own stamp), but it is still a trap: when the ship comes home its wheel cannot land on
        // an occupied cell, so it is dropped as a generic item and the ship loses its identity, glue and lock.
        //
        // Iterates every record at the cell rather than asking byCachedCell. That helper returns the FIRST
        // HashMap match, which is arbitrary now that two records may legitimately share a cell — and this
        // block goes on to DELETE a record, so picking the wrong one by hash order would be destructive.
        List<ShipWheelData> residents = new ArrayList<>();
        for (ShipWheelData w : placedWheels.values()) {
            if (cellsAgree(location, w.getBlockLocation())) residents.add(w);
        }
        for (ShipWheelData r : residents) {
            WheelState st = resolveWheelState(r).state();
            if (st == WheelState.LOADED || st == WheelState.UNLOADED_RECOVERABLE) return PlaceResult.CELL_RESERVED;
        }
        // Everything left is docked or orphaned, i.e. a record that believes its wheel is HERE — and it
        // plainly is not, because the player just placed a fresh head in this cell. Reap rather than refuse:
        // a stale record must not be able to make a cell permanently unusable.
        for (ShipWheelData r : residents) {
            plugin.getLogger().info("Dropping stale wheel record " + r.getWheelId() + " at "
                + locationKey(location) + ": a new wheel is being placed there.");
            placedWheels.remove(r.getWheelId());
        }

        ShipWheelData wheelData = new ShipWheelData(location, facing);

        // Stamp BEFORE recording. If the stamp fails the block is not a wheel and must not be tracked as one:
        // an untracked head is merely litter, whereas a tracked-but-unmarked cell is the ghost-record state
        // that lets any later head at those coordinates impersonate this wheel.
        if (!ShipWheelBlockType.stamp(location.getBlock(), wheelData.getWheelId())) {
            return PlaceResult.STAMP_FAILED;
        }

        placedWheels.put(wheelData.getWheelId(), wheelData);
        // The block is stamped by this point, so a crash before the next save would leave a marked block with
        // no record. Persist immediately rather than waiting for some unrelated path to save.
        saveAll();
        return PlaceResult.OK;
    }

    /**
     * Why {@link #placeWheel} did or did not record a wheel.
     *
     * <p>An enum rather than a boolean because the two failures need different words: the caller's single
     * failure branch used to blame a DefCoreLib registration error for everything, which would be flatly
     * wrong — and unactionable — for a player who simply picked a cell another ship is going to land on.
     */
    public enum PlaceResult {
        OK,
        /** The block could not be stamped with its identity (defCoreLib missing, registration failed). */
        STAMP_FAILED,
        /** A ship that is currently out has this cell as its dock, and needs it back. */
        CELL_RESERVED
    }

    /**
     * Is {@code block} still this wheel's own block?
     *
     * <p>The gate on every world write in {@link #teardownWheel}. Without it a teardown working from the
     * wheel's cached cell happily deletes whatever a player has since built there — see the {@link
     * ShipInstance} callers, which capture the cell while the ship is still assembled (so it is the launch
     * cell, standing empty and open to anyone) and then air it out.
     */
    private boolean ownsBlock(ShipWheelData wheel, Block block) {
        Material t = block.getType();
        if (t != Material.PLAYER_HEAD && t != Material.PLAYER_WALL_HEAD) return false;
        UUID stamped = ShipWheelBlockType.readWheelId(block);
        // STAMP-FIRST, AND DELIBERATELY WITHOUT A CELL TEST. "Is this block this wheel's" must stay true for a
        // wheel the engine has legitimately carried somewhere the record has not caught up with yet, which is
        // the normal state mid-landing. The consequence is that this alone does NOT distinguish an original
        // from a /clone of it — both carry the id. Anywhere the question is really "is this the right block at
        // the right PLACE", compare cells explicitly; see agreement().
        if (stamped != null) return stamped.equals(wheel.getWheelId());

        // Unstamped: a wheel predating the identity stamp, where the record's own cached cell is all there is.
        //
        // An ASSEMBLED wheel can never own a block. Its cell is empty by construction — assembly airs the head
        // out of the world — so anything standing there belongs to somebody else. Without this clause the
        // legacy arm becomes a cell-keyed write authority: a wheel sails, a player builds a wheel-textured
        // head on the vacated dock (which happens whenever stamping is unavailable), the ship is destroyed,
        // and teardown airs out THEIR block and drops nothing. That is the impersonation bug running in the
        // destructive direction.
        if (wheel.isAssembled()) return false;

        // Compare the record's own cell directly rather than asking byCachedCell "who is recorded here". That
        // lookup answers a different question and returns the FIRST HashMap match, so with two records sharing
        // a cell it is a coin flip — and worse, teardownWheel removes the record before calling this, so the
        // scan could never find `wheel` at all and every unstamped wheel silently read as un-owned: no item
        // dropped, the block left standing, and a log line claiming the opposite.
        Location cell = wheel.getBlockLocation();
        if (cell == null || cell.getWorld() == null) return false;
        if (!cellsAgree(cell, block.getLocation())) return false;

        // Require the wheel's declared skin too. A plain head planted on a legacy wheel's dock would otherwise
        // satisfy everything above. Shared with adoptLegacyWheel so the two cannot drift.
        return ShipWheelBlockType.hasDeclaredSkin(block);
    }

    /**
     * Do these two locations denote the same block cell?
     *
     * <p>The single cell-comparison primitive. It exists because this question was previously hand-written at
     * five separate sites, one of which compared {@code World} objects while the rest compared world
     * <i>names</i> — a distinction that only shows up after a world is unloaded and reloaded, at which point
     * the two disagree permanently about the same wheel.
     *
     * <p>Never touches chunks and never throws: a location whose world reference has been collected answers
     * "does not agree" rather than raising, because every caller is deciding whether to act on a block and
     * "I cannot tell" must mean "no".
     */
    private static boolean cellsAgree(@Nullable Location a, @Nullable Location b) {
        if (a == null || b == null) return false;
        World wa = liveWorld(a);
        World wb = liveWorld(b);
        if (wa == null || wb == null) return false;
        return wa.getName().equals(wb.getName())
            && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    /**
     * This wheel's block, or null if the cell does not currently hold it.
     *
     * <p><b>The only legal way to get a {@link Block} from a record.</b> Identity was re-keyed so that a block
     * resolves to a wheel by its own stamp — but the reverse direction, "where is this wheel's block so I may
     * write to it", was left as a bare {@code getBlockLocation().getBlock()} at every site that needed it. The
     * record's location is a cache of the wheel's dock, and every failure mode this class defends against ends
     * with that cache pointing at a cell somebody else now owns. Writing through it is how a stale menu
     * flood-fills a stranger's build into a ship, overwrites their glue, or spawns markers in their base.
     *
     * <p>Fails closed on an unloaded chunk, and checks that BEFORE calling {@code getBlock()}, which would
     * otherwise force a synchronous chunk load from whatever event asked.
     */
    @Nullable Block ownedBlock(ShipWheelData wheel) {
        if (wheel == null) return null;
        Location cell = wheel.getBlockLocation();
        World world = liveWorld(cell);
        if (world == null) return null;
        if (!world.isChunkLoaded(cell.getBlockX() >> 4, cell.getBlockZ() >> 4)) return null;
        Block b = cell.getBlock();
        return ownsBlock(wheel, b) ? b : null;
    }

    /**
     * The single teardown path. Deregisters the wheel and optionally destroys its ship, drops its item and
     * clears its cell — but <b>only ever touches the world when the cell still holds this wheel's block</b>.
     *
     * @param dropItem    drop a ship-wheel item at the cell (callers that drop it themselves pass false)
     * @param airBlock    clear the cell (callers that clear it themselves pass false)
     * @param destroyShip tear down the linked ship as well
     */
    private void teardownWheel(ShipWheelData wheel, boolean dropItem, boolean airBlock, boolean destroyShip) {
        if (wheel == null) return;
        placedWheels.remove(wheel.getWheelId());

        // ownedBlock, not a hand-rolled getBlock() + ownsBlock: it also refuses an unloaded chunk rather than
        // force-loading one from a teardown. Order no longer matters here — the old ownsBlock consulted
        // byCachedCell, which scans placedWheels, so running it AFTER the remove above meant it could never
        // find this wheel and every unstamped wheel silently read as un-owned: no item dropped, the head left
        // standing, and the log below claiming the cell no longer held it.
        Location cell = wheel.getBlockLocation();
        Block block = ownedBlock(wheel);
        boolean owned = block != null;
        if (block == null && cell != null && liveWorld(cell) != null) block = cell.getBlock();

        if (destroyShip && wheel.isAssembled()) {
            ShipInstance ship = ShipRegistry.byId(wheel.getAssembledShipUUID());
            if (ship == null) {
                // Orphan: no ShipInstance, but a live mechanism may still be holding this ship's blocks
                // (lost or corrupt sidecar). Land them rather than stranding them — breaking the wheel used
                // to deregister the wheel and leave the blocks in limbo.
                if (plugin instanceof BlockShipsPlugin obsp) {
                    anon.def9a2a4.blockships.ShipOrphans.disassembleOrphan(obsp, wheel.getAssembledShipUUID());
                }
            }
            if (ship != null) {
                // destroyWithCleanup, not destroy(): it also deletes the ships/<id>.yml sidecar, and it must
                // read the world BEFORE destroy() removes the vehicle. Reimplementing that inline gets a null
                // world and silently leaves a phantom that recovers on the next restart.
                if (plugin instanceof BlockShipsPlugin bsp && bsp.getDisplayShip() != null) {
                    ship.destroyWithCleanup(bsp.getDisplayShip().getShipWorldData());
                } else {
                    ship.destroy();
                }
            }
        }

        if (owned) {
            ShipGlue.clear(block);
            ShipWheelAnchors.forget(block);
            ShipWheelMenu.forgetDockedThrust(cell);
            if (dropItem && plugin instanceof BlockShipsPlugin bsp && bsp.getDisplayShip() != null) {
                block.getWorld().dropItemNaturally(cell.clone().add(0.5, 0.5, 0.5),
                    bsp.getDisplayShip().createShipWheelItem());
            }
            if (airBlock) block.setType(Material.AIR);
        } else if (block != null) {
            // Still drop the cell-keyed caches — those harm nobody and would otherwise leak.
            ShipWheelAnchors.forget(block);
            ShipWheelMenu.forgetDockedThrust(cell);
            plugin.getLogger().info("Wheel " + wheel.getWheelId() + " deregistered, but " + locationKey(cell)
                + " no longer holds its block — leaving that cell alone.");
        }

        saveAll();
    }

    /**
     * Deregisters a wheel and destroys its ship. The caller has already cancelled the break and airs the
     * block + drops the item itself (that is what {@code onShipWheelBreak} does), so this does neither.
     */
    public void removeWheel(ShipWheelData wheelData) {
        teardownWheel(wheelData, false, false, true);
    }

    /**
     * Breaks a ship wheel block after the ship has already been disassembled.
     * Removes from tracking, drops the wheel item, and sets block to air.
     */
    public void breakWheelBlock(ShipWheelData wheelData) {
        teardownWheel(wheelData, true, true, false);
    }

    /**
     * Removes a ship wheel block without dropping the wheel item.
     * Used when a ship is fully destroyed so the wheel is lost along with the ship.
     */
    public void destroyWheelBlock(ShipWheelData wheelData) {
        teardownWheel(wheelData, false, true, false);
    }

    /** Bounds the mismatch WARNING to once per (wheel id, cell) — this runs on every right-click. */
    private final Set<String> mismatchLogged = new HashSet<>();

    /**
     * The one block → wheel resolution path. <b>Identity comes from the block's PDC, never from its
     * coordinates.</b>
     *
     * <p>Location used to be doing two jobs: "this wheel is currently at X,Y,Z" (a legitimate attribute) and
     * "the wheel IS whatever sits at X,Y,Z" (the bug). Assembly airs the head out of the world, so an
     * assembled ship's recorded cell is empty and anything may occupy it — which is how planting a plain head
     * there let anyone open a sailing ship's menu or destroy it.
     *
     * <p>Order matters; this runs on every right-click and every block break.
     */
    public @Nullable ShipWheelData getWheelAtBlock(@Nullable Block block) {
        if (block == null) return null;
        // 1. Material gate first — cheapest check, and everything below needs a getState().
        Material type = block.getType();
        if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) return null;

        // 2. The stamp.
        UUID id = ShipWheelBlockType.readWheelId(block);
        if (id != null) {
            ShipWheelData data = placedWheels.get(id);
            // Stamped but unknown: a leaked /defcorelib give, a restored backup, a failed save. Not a wheel
            // we can act on — leave it to corelib's own break handling and /blockships wheels adopt.
            if (data == null) return null;
            if (agreement(data, block) == Agreement.AGREES) return data;
            refuseMismatch(data, block, data.getBlockLocation(), true);
            return null;
        }

        // 3. No stamp — a wheel placed before identity existed. Adopt it in place.
        return adoptLegacyWheel(block);
    }

    /** How a stamped block's location relates to the cell its record caches. See {@link #agreement}. */
    enum Agreement {
        /** The block is at the record's cell. The ordinary case. */
        AGREES,
        /** The record's cell is observable and no longer holds a block with this id — the wheel was moved. */
        MOVED,
        /** The record's cell ALSO holds a block carrying this id — two blocks claim one wheel. */
        DUPLICATE_CLAIM,
        /** The record's cell cannot be looked at (no world, or an unloaded chunk). */
        UNOBSERVABLE
    }

    /**
     * Relate a block carrying a wheel's stamp to the cell that wheel's record caches.
     *
     * <p>One predicate, three callers, because the alternative is three hand-written copies that drift. The
     * question is the same everywhere — "does the record's cell agree, and if not, can I tell whether the
     * wheel moved or was copied?" — while the <i>answer</i> is used differently: resolution refuses anything
     * but agreement, the engine-removal callback refuses to reap on anything but agreement, and the glue
     * anchor provider follows a move but refuses a copy.
     *
     * <p><b>Never forces a chunk load.</b> The provider calls this for every block of every structure on every
     * mover stroke, and the removal callback runs inside an explosion's block loop.
     *
     * <p><b>{@code UNOBSERVABLE} must be treated as refusal, not as agreement.</b> It is the whole defence
     * against a copied block: {@code /clone} and structure blocks duplicate a block entity's PDC with no
     * Bukkit event, and an attacker chooses whether the original's chunk is loaded. Failing open here would
     * hand them the record by walking away from it.
     */
    private Agreement agreement(ShipWheelData wheel, Block block) {
        Location cached = wheel.getBlockLocation();
        if (cellsAgree(cached, block.getLocation())) return Agreement.AGREES;
        World cw = liveWorld(cached);
        if (cw == null || !cw.isChunkLoaded(cached.getBlockX() >> 4, cached.getBlockZ() >> 4)) {
            return Agreement.UNOBSERVABLE;
        }
        UUID atCached = ShipWheelBlockType.readWheelId(cached.getBlock());
        return wheel.getWheelId().equals(atCached) ? Agreement.DUPLICATE_CLAIM : Agreement.MOVED;
    }

    /**
     * A block carries wheel X's id but X's record points elsewhere. Refuse, log once, resolve nothing.
     *
     * <p>Deliberately NOT a silent self-heal. {@code /clone} and structure blocks duplicate a block entity's
     * PDC with no Bukkit event, so "the block wins" would let a copy steal the record — and every world-write
     * path that follows the record (the AIR-out in the teardown, ShipGlue.writeCells, toggleLock) would
     * follow it to the thief's cell. Recovery is the explicit {@code /blockships wheels adopt}.
     *
     * <p>Rarer than an earlier version of this note claimed, but <b>not</b> near-unreachable: that note argued
     * corelib cancels every survival route that could move a stamped head, which is true of <i>vanilla</i>
     * pistons and false of corelib's own movers. A mechanical piston, hoist or rotator will carry a docked
     * wheel to a new cell without telling us, and the record then disagrees with the block until something
     * repairs it.
     *
     * @param probeCached whether it may read the block at the cached cell to distinguish "two blocks claim
     *        this wheel" from "the wheel moved". That read is a {@code getState()}, which force-loads the
     *        chunk — fine from a right-click, <b>not</b> fine from callers that run inside an explosion's
     *        block loop or corelib's glue-expansion walk. Those pass false and get the weaker message.
     */
    private void refuseMismatch(ShipWheelData data, Block block, @Nullable Location cached, boolean probeCached) {
        String key = data.getWheelId() + "@" + locationKey(block.getLocation());
        if (mismatchLogged.size() > 512) mismatchLogged.clear();  // bounded; this is diagnostics, not state
        if (!mismatchLogged.add(key)) return;
        boolean duplicate = probeCached && cached != null && liveWorld(cached) != null
            && liveWorld(cached).isChunkLoaded(cached.getBlockX() >> 4, cached.getBlockZ() >> 4)
            && data.getWheelId().equals(ShipWheelBlockType.readWheelId(cached.getBlock()));
        plugin.getLogger().warning("Wheel " + data.getWheelId() + " is stamped on the block at "
            + locationKey(block.getLocation()) + " but its record points at "
            + (cached == null ? "nowhere" : locationKey(cached))
            + (duplicate
                ? ", which carries the SAME id — two blocks are claiming this wheel (a copied block?). "
                  + "Refusing both."
                : ", which does not. Refusing to resolve; the record is not being rewritten.")
            + " Use /blockships wheels adopt to repair it.");
    }

    /**
     * One-shot in-place adoption of a wheel that predates the identity stamp.
     *
     * <p>Kept as a permanent lookup path rather than a boot sweep: if a voyage ever lands a wheel whose type
     * did not resolve, corelib never reaches {@code restoreConfigPdc} and the {@code wheel_id} is destroyed,
     * so this has to be able to run again later. It is self-limiting — each success removes one candidate.
     */
    private @Nullable ShipWheelData adoptLegacyWheel(Block block) {
        // Without a declared texture the texture test below degrades to "any player head".
        String declared = ShipWheelBlockType.texture();
        if (declared == null) return null;

        // Refuse a block that belongs to some OTHER corelib type — a rotator, a hoist, anything a player may
        // have put on a vacated cell — because stamping it would overwrite its block_type and destroy its
        // identity while handing its cell to this ship.
        //
        // Narrowed from "carries any corelib type" to "carries a type that is not ours". The blanket form
        // also refused the wheel's OWN blocks, which is the case that actually needs repairing: a wheel whose
        // landing restored its corelib mark but lost its wheel_id is corelib-typed, so it was permanently
        // unadoptable — dead on right-click and, before the drop rule existed, destroying the item on break.
        // Of the three hazards the blanket form was written for, only one was ever real: a HeadSmith head
        // carries no corelib type at all (the texture gate is what stops those), while rotators and hoists do
        // and are still refused here. Vanilla-placed wheel ITEMS are now deliberately admitted — they are our
        // own type, and requiring the texture plus an idle record at that exact cell is guard enough.
        try {
            var chb = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry().getTypeFromBlock(block);
            if (chb != null && !ShipWheelBlockType.isWheelTypeId(chb.fullId())) return null;
        } catch (Throwable t) {
            return null;  // no engine, no adoption
        }

        // Shared with ownsBlock so the two cannot disagree about what a wheel looks like. Also refuses a head
        // whose skin cannot be read at all — including a wheel that landed BLANK because its type was not
        // registered at the time. That is correct and must stay: such a block carries no evidence of being a
        // wheel, and "any untextured player head at a recorded cell" is exactly the impersonation primitive
        // the identity re-key exists to close. Recovery for that case is the explicit wheels adopt command.
        if (!ShipWheelBlockType.hasDeclaredSkin(block)) return null;

        ShipWheelData candidate = byCachedCell(block.getLocation());
        if (candidate == null) return null;

        // THE security condition. A sailing ship's cell is necessarily empty, so any head there was planted;
        // adopting it would hand the planter the ship. NOT_ASSEMBLED is required — not merely "not LOADED",
        // which would also admit UNLOADED_RECOVERABLE and let someone hijack a ship parked in an unloaded
        // chunk. It also makes hasLiveMechanism's fail-open behaviour (it returns true on a corelib fault)
        // narrow adoption rather than widen it.
        if (resolveWheelState(candidate).state() != WheelState.NOT_ASSEMBLED) return null;

        if (!ShipWheelBlockType.stamp(block, candidate.getWheelId())) return null;
        plugin.getLogger().info("Adopted legacy ship wheel at " + locationKey(block.getLocation())
            + " as " + candidate.getWheelId() + ".");
        return candidate;
    }

    /** A wheel by its id, or null. */
    public @Nullable ShipWheelData getWheelById(UUID id) {
        return id == null ? null : placedWheels.get(id);
    }

    /** Why an {@link #adoptWheel} attempt was refused, or {@code OK}. */
    public enum AdoptResult {
        OK,
        /** The ship is assembled, sailing or merely parked-and-recoverable. Never retarget a live ship. */
        NOT_IDLE,
        /** The target cell does not hold a player head. */
        NOT_A_HEAD,
        /** The target block already belongs to a DIFFERENT wheel. */
        OTHER_WHEEL_BLOCK,
        /** Another record already caches the target cell. */
        CELL_TAKEN,
        /** The target block is some other corelib block type — a rotator, a hoist — not a wheel. */
        OTHER_TYPE_BLOCK,
        /** The stamp could not be written (no engine, not a tile entity, registration failed). */
        STAMP_FAILED
    }

    /**
     * Point a record at a block and stamp the block with its id — the repair for a record and block that
     * disagree, which {@link #getWheelAtBlock} refuses to resolve on its own.
     *
     * <p>Every guard here is load-bearing, because this is the one operation that can deliberately move a
     * wheel's identity onto a block of the caller's choosing. Without them it is a ship-hijack primitive: an
     * operator could retarget any record onto a head planted on a sailing ship's vacated cell and then break
     * it to destroy a ship they never touched. Hence: the ship must be idle, the block must not already
     * belong to someone else, and no other record may claim the cell.
     */
    public AdoptResult adoptWheel(ShipWheelData record, Block target) {
        if (resolveWheelState(record).state() != WheelState.NOT_ASSEMBLED) return AdoptResult.NOT_IDLE;
        Material t = target.getType();
        if (t != Material.PLAYER_HEAD && t != Material.PLAYER_WALL_HEAD) return AdoptResult.NOT_A_HEAD;
        UUID stamped = ShipWheelBlockType.readWheelId(target);
        if (stamped != null && !stamped.equals(record.getWheelId())) return AdoptResult.OTHER_WHEEL_BLOCK;

        // Refuse a block belonging to another corelib type. stamp() calls markBlock, which OVERWRITES
        // block_type — so adopting onto a rotator or hoist would silently destroy that block's identity and
        // leave its owner with a dead machine. Same predicate as adoptLegacyWheel, so the two agree about
        // what counts as "ours"; a blank untextured head (a landing whose type did not resolve) carries no
        // corelib type at all and is still adoptable here, which is the point — this command is that block's
        // only repair.
        try {
            var chb = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry().getTypeFromBlock(target);
            if (chb != null && !ShipWheelBlockType.isWheelTypeId(chb.fullId())) return AdoptResult.OTHER_TYPE_BLOCK;
        } catch (Throwable ignored) {
            // No engine to ask. stamp() below will fail on its own if that is really the case.
        }

        ShipWheelData resident = byCachedCell(target.getLocation());
        if (resident != null && resident != record) return AdoptResult.CELL_TAKEN;

        Location old = record.getBlockLocation();

        // Carry the frozen structure across BEFORE stamping the new block.
        //
        // A lock lives as glue offsets in the WHEEL BLOCK's own PDC, not in the record — so moving the record
        // without moving them left isLocked() true and the frozen set empty, and the next assembly died with
        // "Nothing left of the locked structure". Read from the old block while the record still points at it;
        // the offsets are relative to the wheel, so re-writing them under the new block is all it takes.
        Set<Location> frozen = null;
        if (record.isLocked() && old != null && liveWorld(old) != null
                && liveWorld(old).isChunkLoaded(old.getBlockX() >> 4, old.getBlockZ() >> 4)) {
            try {
                frozen = new HashSet<>(ShipGlue.rawGlueCells(old.getBlock()));
            } catch (Throwable ignored) {
                frozen = null;
            }
        }

        if (!ShipWheelBlockType.stamp(target, record.getWheelId())) return AdoptResult.STAMP_FAILED;

        if (old != null && liveWorld(old) != null) {
            ShipGlue.clear(old.getBlock());
            ShipWheelAnchors.forget(old.getBlock());
            ShipWheelMenu.forgetDockedThrust(old);
        }
        relocate(record, target.getLocation(), record.getFacing());
        if (frozen != null && !frozen.isEmpty()) {
            try {
                ShipGlue.writeCells(target, frozen);
            } catch (Throwable t) {
                plugin.getLogger().warning("Adopted wheel " + record.getWheelId() + " but could not carry its "
                    + "locked structure across; unlock and re-lock it. (" + t.getMessage() + ")");
            }
        }
        mismatchLogged.clear();  // the state that was being warned about is gone
        saveAll();
        return AdoptResult.OK;
    }

    /** Drops a record outright. The block, if any, is left alone — purge is for records with no wheel. */
    public void purgeWheel(ShipWheelData wheel) {
        if (wheel == null) return;
        placedWheels.remove(wheel.getWheelId());
        Location cell = wheel.getBlockLocation();
        if (cell != null && cell.getWorld() != null) {
            ShipWheelAnchors.forget(cell.getBlock());
            ShipWheelMenu.forgetDockedThrust(cell);
        }
        saveAll();
    }

    /** What {@code /blockships wheels list} can say about a record without leaving its cell. */
    public enum Health {
        /** The cell holds this wheel's block. */
        OK,
        /** The ship is out; the cell is legitimately empty and there is nothing to check. */
        SAILING,
        /** The cell's chunk is not loaded, so the question cannot be answered right now. */
        UNKNOWN,
        /** The cell does not hold this wheel's block. */
        BROKEN
    }

    /**
     * Does the block at this wheel's cached cell actually carry its identity?
     *
     * <p>Four answers rather than a boolean, because the boolean version had to fold three genuinely
     * different situations into "healthy" and so reported the two states an operator most needs to find —
     * a sailing ship and a wheel that is simply gone — identically to a wheel that is fine. Worse, it did it
     * beside a state column that was already printing {@code ORPHAN}, so the list contradicted itself.
     *
     * <p>{@code UNKNOWN} deliberately fails OPEN where {@link #ownedBlock} fails closed: this decides what to
     * print, not whether to act, and marking every wheel in an unloaded chunk as broken would bury the real
     * ones. Do not merge the two.
     */
    public Health recordHealth(ShipWheelData wheel) {
        // resolveWheelState, not the raw isAssembled() flag: an ORPHAN's link is non-null but its ship is
        // gone, and calling that "sailing" is how a dead record used to render as healthy.
        if (resolveWheelState(wheel).state() != WheelState.NOT_ASSEMBLED) return Health.SAILING;
        Location cell = wheel.getBlockLocation();
        World w = liveWorld(cell);
        if (w == null) return Health.UNKNOWN;
        if (!w.isChunkLoaded(cell.getBlockX() >> 4, cell.getBlockZ() >> 4)) return Health.UNKNOWN;
        return ownsBlock(wheel, cell.getBlock()) ? Health.OK : Health.BROKEN;
    }

    /** @deprecated prefer {@link #recordHealth}; kept so callers that only want a yes/no still compile. */
    @Deprecated
    public boolean isRecordHealthy(ShipWheelData wheel) {
        Health h = recordHealth(wheel);
        return h == Health.OK || h == Health.SAILING || h == Health.UNKNOWN;
    }

    /**
     * The engine removed a wheel block by a route that never reaches {@code BlockBreakEvent} — an explosion,
     * fire, a fluid break, {@code /setblock}, {@code /fill}, a piston break, or a corelib drill boring it out.
     *
     * <p>Before this, every one of those left the block gone and the record behind, pointing at an empty cell.
     * That orphan is the raw material for impersonation: it is exactly the state a planted head can be adopted
     * into. Nothing else covers these paths — BlockShips listens to {@code BlockBreakEvent} and nothing else.
     *
     * <p><b>Does not drop an item</b>, deliberately. Each corelib path has its own considered drop policy and
     * they disagree on purpose: {@code handleExplosion} already drops the real wheel, {@code onBlockBurn}
     * drops nothing ("consumed by fire"), and {@code onBlockDestroy} sets {@code setWillDrop(false)} so a
     * destroy-mode command cannot leak a head. Dropping here would duplicate the wheel on every explosion.
     */
    public void onEngineRemovedWheelBlock(Block block) {
        if (block == null) return;
        // Material gate: without it the unstamped arm below will happily reap a record because something
        // that is not even a head was destroyed at its cell.
        Material t = block.getType();
        if (t != Material.PLAYER_HEAD && t != Material.PLAYER_WALL_HEAD) return;

        UUID id = ShipWheelBlockType.readWheelId(block);
        ShipWheelData wheel = (id != null) ? placedWheels.get(id) : byCachedCell(block.getLocation());
        if (wheel == null) return;

        // The block must be at the record's cell, not merely carry its id. ownsBlock is stamp-first and does
        // NOT establish this, so it cannot stand in here: /clone and structure blocks copy a block entity's
        // PDC verbatim with no Bukkit event, so a copy carries the id too. Without this check, blowing up the
        // COPY deletes the ORIGINAL's record and leaves the real wheel stamped-but-unknown — a state no
        // command can repair.
        if (agreement(wheel, block) != Agreement.AGREES) {
            // probeCached=false: this runs inside handleExplosion's walk over the blast list, so it must not
            // force-load the cached cell's chunk once per destroyed block.
            refuseMismatch(wheel, block, wheel.getBlockLocation(), false);
            return;
        }

        // A wheel whose ship is out is not this block, whatever this block is. Both states below have a cell
        // that is legitimately EMPTY — assembly airs the head out — so anything standing there was planted.
        WheelState st = resolveWheelState(wheel).state();
        if (st == WheelState.LOADED) {
            // Independent of corelib's capture-depth counter: assembly airs the wheel out through this same
            // plumbing, so if that counter ever failed to cover a path we would delete the record of a ship
            // that is about to sail, surfacing much later as "cannot be disassembled".
            plugin.getLogger().severe("Refusing to drop the record for wheel " + wheel.getWheelId()
                + ": its ship is loaded, so this removal is an assembly capture that was not flagged as one. "
                + "Please report this at " + BlockShipsPlugin.ISSUES_URL);
            return;
        }
        if (st == WheelState.UNLOADED_RECOVERABLE) {
            // The ship is parked or mid-recovery — its blocks are still held by a mechanism or a sidecar, and
            // it WILL come back wanting this record. Refusing costs nothing; reaping loses the ship. This is
            // the state an attacker reaches by simply sailing out of render distance: leave the dock empty,
            // let someone plant a head on it, and have them blow it up.
            plugin.getLogger().warning("Refusing to drop the record for wheel " + wheel.getWheelId()
                + ": its ship is parked or recovering, so the head removed here was not the wheel.");
            return;
        }
        if (st == WheelState.ORPHAN) {
            // Reap. ORPHAN means the link is dead and the wheel block really was in the world — this is the
            // case the method exists for. Do NOT call reconcileOrphan from here: it scans nearby entities and
            // tears down a mechanism, and this runs inside an explosion's per-block loop.
            plugin.getLogger().warning("Reaping ORPHAN wheel " + wheel.getWheelId()
                + " (dead ship link " + wheel.getAssembledShipUUID() + ") along with its destroyed block.");
        }

        placedWheels.remove(wheel.getWheelId());
        ShipGlue.clear(block);
        ShipWheelAnchors.forget(block);
        ShipWheelMenu.forgetDockedThrust(block.getLocation());
        saveAll();
        plugin.getLogger().info("Ship wheel " + wheel.getWheelId() + " at "
            + locationKey(block.getLocation()) + " was destroyed by the world; record removed.");
    }

    /**
     * Wheel lookup for the defCoreLib glue-anchor provider.
     *
     * <p>Deliberately NOT {@link #getWheelAtBlock}: that refuses on any disagreement, and this is called from
     * inside {@code disassemble()}'s landed-anchor rebind loop where a disagreement is expected. Refusing
     * there returns null, corelib falls back to a plain {@code BlockAnchor}, and because
     * {@code prunesOnLanding()} defaults true it deletes every glued offset not chained to the origin — the
     * ship's glue, silently and permanently.
     *
     * <p>But it cannot ignore the cell either, which is what it used to do. Glue lives in the <i>block's</i>
     * own PDC and {@code WheelAnchor.originBlock()} hands corelib whichever block was queried, so resolving a
     * {@code /clone}d copy by its id alone let the copy capture the wheel's anchor: the brush wrote offsets
     * into the copy, at offsets relative to the copy, while assembly went on reading glue from the original.
     * Success particles, silently missing blocks.
     *
     * <p>So: distinguish the two. A wheel the engine <b>moved</b> is followed — that is a rotator or hoist
     * carrying a docked wheel, which corelib permits and never tells us about, and refusing it would prune
     * that ship's glue on landing. A wheel that is <b>duplicated</b> is refused, both copies, as is one whose
     * original cannot be observed.
     *
     * <p>Constraints on this method that are not obvious and must not be optimised away:
     * <ul>
     *   <li><b>Never force a chunk load.</b> corelib calls this for every block of every resolved structure on
     *       every mover stroke, to fixpoint.</li>
     *   <li><b>Never let anything escape.</b> There is no try/catch anywhere in the provider chain —
     *       {@code Anchors.externalFor}, {@code GlueManager.expandNested} and the rebind call in
     *       {@code BasicMechanism} are all bare — so a throw here aborts a mover stroke mid-move, or escapes
     *       {@code disassemble()} and makes a successful landing report "some blocks may be missing".</li>
     *   <li><b>Never save synchronously on a move.</b> A rotator carrying a glued wheel would otherwise
     *       rewrite the whole wheel file, and log a line, once per stroke forever.</li>
     * </ul>
     */
    public @Nullable ShipWheelData anchorWheelFor(@Nullable Block block) {
        try {
            if (block == null) return null;
            Material type = block.getType();
            // The provider had no material guard at all: any block sitting at a recorded cell claimed the
            // wheel's anchor, and WheelAnchor.originBlock() then wrote glue offsets into that block's PDC.
            if (type != Material.PLAYER_HEAD && type != Material.PLAYER_WALL_HEAD) return null;

            UUID id = ShipWheelBlockType.readWheelId(block);
            // Unstamped legacy wheel: resolved BY cell, so agreement holds by construction.
            if (id == null) return byCachedCell(block.getLocation());

            ShipWheelData w = placedWheels.get(id);
            if (w == null) return null;

            switch (agreement(w, block)) {
                case AGREES:
                    return w;
                case MOVED:
                    // Something carried this wheel and the record had no way to learn about it. Follow the
                    // block: it is the only party that still knows where the wheel is.
                    //
                    // Facing is re-derived from the landed block rather than carried over, because a rotator
                    // genuinely rotates the wheel. A stale facing is not cosmetic — it is fed to the structure
                    // scan as the ship's forward axis, so the ship would assemble in the wrong frame.
                    BlockFace landedFacing = facingFromBlockData(block, w.getFacing());
                    Location old = w.getBlockLocation();
                    ShipWheelAnchors.forget(old.getBlock());
                    ShipWheelMenu.forgetDockedThrust(old);
                    relocate(w, block.getLocation(), landedFacing);
                    plugin.getLogger().info("Ship wheel " + w.getWheelId() + " moved " + locationKey(old)
                        + " → " + locationKey(block.getLocation())
                        + " (carried by a mechanism); its record now follows the block.");
                    markDirty();   // NOT saveAll() — see the class note above
                    return w;
                case DUPLICATE_CLAIM:
                case UNOBSERVABLE:
                default:
                    refuseMismatch(w, block, w.getBlockLocation(), false);
                    return null;
            }
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                "Ship-wheel anchor lookup failed; corelib will fall back to a pruning anchor for this block", t);
            return null;
        }
    }

    /**
     * The ship-facing implied by a placed wheel head, falling back to {@code fallback} when it cannot be read.
     *
     * <p>The two head forms disagree, and getting either wrong is a silent 180°:
     * <ul>
     *   <li><b>Floor head</b> — {@code Rotatable.getRotation()} is SIXTEEN-way. Handing a non-cardinal
     *       straight to the yaw conversion falls through its {@code default} and silently reads as SOUTH, so
     *       it must be snapped through the same yaw path a placement uses.</li>
     *   <li><b>Wall head</b> — {@code Directional.getFacing()} is always cardinal, so it needs no snap, but it
     *       points <i>outward</i> from the wall while the ship faces <i>into</i> it. Placement records
     *       {@code getOppositeFace()}; so must this.</li>
     * </ul>
     */
    private static BlockFace facingFromBlockData(Block block, BlockFace fallback) {
        try {
            org.bukkit.block.data.BlockData data = block.getBlockData();
            if (data instanceof org.bukkit.block.data.Directional dir) {
                return dir.getFacing().getOppositeFace();
            }
            if (data instanceof org.bukkit.block.data.Rotatable rot) {
                BlockFace r = rot.getRotation();
                float yaw = (float) Math.toDegrees(Math.atan2(-r.getModX(), r.getModZ()));
                return ShipWheelData.yawToBlockFace(yaw);
            }
        } catch (Throwable ignored) {
            // Fall through to the recorded facing.
        }
        return fallback;
    }


    /**
     * Gets wheel data whose CACHED cell is this location.
     *
     * <p>Not an identity test — see {@link #getWheelAtBlock}, which is what every "is this block a wheel?"
     * caller must use. This survives for the paths that legitimately work from a recorded position (glue
     * resolution for a wheel that has not been stamped yet, stats, nearest-wheel).
     */
    public ShipWheelData getWheelAt(Location location) {
        return byCachedCell(location);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Wheel↔ship reconciliation (Track W). The wheel's assembledShipUUID must agree with ShipRegistry, the
    // persisted set, and defCoreLib's mechanism registry, but nothing keeps them in lockstep — so a dead ship
    // can leave the wheel "confused" (still flagged assembled), and Assemble then refuses. A single authority
    // derives the real state so a dead ship reads as unassembled WITHOUT scattered imperative clears, and is
    // the one seam M5 (delegated persistence) later extends.
    // ─────────────────────────────────────────────────────────────────────────

    public enum WheelState {
        /** No ship linked. */
        NOT_ASSEMBLED,
        /** Ship is registered and ticking. */
        LOADED,
        /** Linked ship isn't registered but will come back (chunk unloaded / mid-recovery / a live mechanism). */
        UNLOADED_RECOVERABLE,
        /** Linked ship is genuinely gone (chunk loaded, unregistered, no live mechanism) — safe to clear/reap. */
        ORPHAN
    }

    /** Result of {@link #resolveWheelState}: the derived state plus the live ship when {@code LOADED}. */
    public record WheelResolution(WheelState state, @Nullable ShipInstance ship) {}

    /**
     * Toggle the wheel's lock, or re-freeze one that is already locked. Locking is DOCKED-ONLY: it materializes
     * the ship's current membership (a fresh detect, including glued cells) into the wheel's glue offsets and
     * sets {@code naturalFrozen}, so the ship then assembles from exactly those cells and the brush can still
     * add/remove any of them. Unlocking prunes the glue back to the genuinely-manual cells (those the natural
     * flood fill won't re-derive) and re-enables natural spread. Re-freezing is the repair path: cells lost to
     * an obstructed/protected landing erode the glue permanently otherwise.
     *
     * @param refreeze when true, re-snapshot instead of unlocking an already-locked wheel
     * @return a player-facing summary, or null when the action was refused (message already sent)
     */
    public @Nullable String toggleLock(Player player, ShipWheelData wheelData, boolean refreeze) {
        Location wheelLoc = wheelData.getBlockLocation();
        // Both arms below write glue offsets into whatever block is here — ShipGlue.writeCells REPLACES the
        // block's offset array, so on a foreign block it deletes that block's own glue and substitutes this
        // ship's hull. The only gate used to be "not air", which admits everything. A menu opened before this
        // wheel was destroyed, plus a corelib rotator or hoist since placed on the vacated cell (both store
        // their offsets in the same skull PDC), was enough to silently rebind a neighbour's structure.
        //
        // Deliberately NOT gated on state first: an ORPHAN wheel reports isAssembled() true but reaches the
        // unlock arm below anyway (it gates on ship() == null, which an orphan satisfies), so a state check
        // ahead of this would wave through exactly the case that needs stopping.
        Block wheelBlock = ownedBlock(wheelData);
        if (wheelBlock == null) {
            if (player != null) {
                player.sendMessage("§cThat ship wheel isn't where its record says it is — nothing was changed. "
                    + "If it was moved, right-click the wheel itself; if it's gone, ask an admin for "
                    + "/blockships wheels adopt.");
            }
            return null;
        }

        // ── Unlock ──────────────────────────────────────────────────────────────────────────────────────
        if (wheelData.isLocked() && !refreeze) {
            wheelData.setNaturalFrozen(false);
            // Prune the materialized hull back to manual-only: cells the natural flood fill will re-derive on
            // its own need not stay glued. Docked only — mid-flight there is no wheel skull to rewrite.
            if (resolveWheelState(wheelData).ship() == null && !wheelBlock.getType().isAir()) {
                int maxShipSize = ((BlockShipsPlugin) plugin).getConfig().getInt("custom-ships.max-ship-size", 1000);
                int maxScanSize = ((BlockShipsPlugin) plugin).getConfig().getInt("custom-ships.max-scan-size", 5000);
                ShipDetector.ShipDetectionResult natural =
                    new ShipDetector(maxShipSize, maxScanSize).detectShipDetailed(wheelLoc, Collections.emptySet());
                Set<String> naturalKeys = new HashSet<>();
                if (natural.isSuccess() && natural.getBlocks() != null) {
                    for (Location c : natural.getBlocks()) naturalKeys.add(cellKey(c));
                }
                List<Location> manual = new ArrayList<>();
                for (Location c : ShipGlue.rawGlueCells(wheelBlock)) {
                    if (!naturalKeys.contains(cellKey(c))) manual.add(c);
                }
                ShipGlue.writeCells(wheelBlock, manual);
            }
            saveAll();
            return "Unlocked — this ship will pick up connected blocks again when it assembles.";
        }

        // ── Lock / refreeze (docked only) ───────────────────────────────────────────────────────────────
        WheelResolution res = resolveWheelState(wheelData);
        // A detect on an unloaded chunk reads air and would freeze a one-block ship. ORPHAN is a confused link
        // rather than a live ship; reconcile it first so the snapshot is meaningful.
        if (res.state() == WheelState.UNLOADED_RECOVERABLE) {
            player.sendMessage("§cShip is still loading — try again in a moment.");
            return null;
        }
        if (res.state() == WheelState.ORPHAN) {
            reconcileOrphan(wheelData);
            res = resolveWheelState(wheelData);
        }
        if (res.ship() != null) {
            // Docked-only: a flying ship's hull is aired out, so there is no wheel skull to store glue on.
            player.sendMessage("§cDock the ship before locking it.");
            return null;
        }

        int maxShipSize = ((BlockShipsPlugin) plugin).getConfig().getInt("custom-ships.max-ship-size", 1000);
        int maxScanSize = ((BlockShipsPlugin) plugin).getConfig().getInt("custom-ships.max-scan-size", 5000);
        ShipDetector.ShipDetectionResult scan =
            new ShipDetector(maxShipSize, maxScanSize).detectShipDetailed(wheelLoc, ShipGlue.gluedCells(wheelBlock));
        if (!scan.isSuccess() || scan.getBlocks() == null || scan.getBlocks().isEmpty()) {
            player.sendMessage("§c" + scan.getMessage());
            return null;
        }
        Set<Location> members = scan.getBlocks();
        int cap = ShipGlue.maxSize();
        if (members.size() > cap) {
            player.sendMessage("§cShip is too large to lock (" + members.size() + " blocks, glue cap " + cap
                + "). Raise glue.max-size in defCoreLib to lock ships this big.");
            return null;
        }
        int previous = wheelData.isLocked() ? ShipGlue.glueCount(wheelBlock) + 1 : -1;   // +1 for the wheel cell
        ShipGlue.writeCells(wheelBlock, members);   // the wheel's own (0,0,0) cell is skipped inside writeCells
        wheelData.setNaturalFrozen(true);
        saveAll();

        int now = members.size();
        if (previous >= 0 && previous != now) {
            return "Re-froze " + now + " blocks (was " + previous + ").";
        }
        return "Locked " + now + " blocks — this ship will no longer pick up anything new.";
    }

    /** Block-coordinate key for set membership without Location's double-precision equality pitfalls. */
    private static String cellKey(Location l) {
        return l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }


    /**
     * The authoritative "is this wheel really assembled?" check (Track W R0). Route every assembled-for-action
     * decision (assemble/align/disassemble/menu) through this instead of trusting the raw {@code isAssembled()}
     * flag. Pre-M5 a delegated ship cannot rebind once unregistered, so ORPHAN (chunk loaded + no live mechanism)
     * means genuinely gone. The recoverability branch is the single place M5 extends (add a defCoreLib
     * persisted-mechanism check there so a parked ship reads UNLOADED_RECOVERABLE instead of ORPHAN).
     */
    public WheelResolution resolveWheelState(ShipWheelData wheel) {
        UUID uuid = wheel.getAssembledShipUUID();
        if (uuid == null) return new WheelResolution(WheelState.NOT_ASSEMBLED, null);
        ShipInstance ship = ShipRegistry.byId(uuid);
        if (ship != null) return new WheelResolution(WheelState.LOADED, ship);
        // Not registered. Recoverable if the wheel's chunk is unloaded / recovery is pending, or a live mechanism
        // still exists (an in-session chunk reload can leave the mechanism alive but the ShipInstance unregistered).
        // Otherwise — chunk loaded (so on-load recovery already ran) and no live mechanism — it is genuinely gone.
        Location wl = wheel.getBlockLocation();
        boolean chunkLoaded = wl != null && wl.getWorld() != null
            && wl.getWorld().isChunkLoaded(wl.getBlockX() >> 4, wl.getBlockZ() >> 4);
        // F1: also treat a ship in BlockShips' persisted set as recoverable. M5 delegated persistence/recovery
        // means a persisted-but-not-yet-recovered ship is absent from activeMechanisms (so hasLiveMechanism is
        // false) yet WILL rebind via MechanismAssembleEvent — reaping it here would destroy a live-recoverable
        // ship. This does NOT strand a genuinely-dead ship: every death routes through ShipInstance.destroy()
        // (W2) which nulls the link → NOT_ASSEMBLED above → this branch is unreached; and removeShip prunes the
        // persisted set. (Same source classifyWheels uses, so the two authorities agree.)
        if (!chunkLoaded || hasLiveMechanism(uuid) || isPersistedShip(uuid)) {
            return new WheelResolution(WheelState.UNLOADED_RECOVERABLE, null);
        }
        return new WheelResolution(WheelState.ORPHAN, null);
    }

    private boolean coreLibFaultLogged = false;

    /** True if defCoreLib still has a live (in-memory) mechanism with this id (delegated ships: mechId == ship.id). */
    private boolean hasLiveMechanism(UUID id) {
        try {
            anon.def9a2a4.corelib.MechanismRegistry reg =
                anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getMechanismRegistry();
            return reg != null && reg.byId(id) != null;
        } catch (Throwable t) {
            // F1b — fail CLOSED for a reaping decision: a transient CoreLib fault must NOT bias toward ORPHAN/reap.
            // Treat as "recoverable, don't reap". Log once so a PERMANENT CoreLib-absent fault is diagnosable.
            if (!coreLibFaultLogged) {
                coreLibFaultLogged = true;
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "hasLiveMechanism: defCoreLib query failed; treating wheels as recoverable (not reaping). "
                    + "If defCoreLib is permanently unavailable, unrecoverable wheels will read as 'loading'.", t);
            }
            return true;
        }
    }

    /** True if this id has a persisted ship sidecar on disk (any world). Mirrors {@code collectPersistedShipIds}. */
    private boolean isPersistedShip(UUID id) {
        if (!(plugin instanceof BlockShipsPlugin bsp)) return false;
        var ds = bsp.getDisplayShip();
        if (ds == null) return false;
        return ds.getShipWorldData().getAllPersistedShipIds().contains(id);
    }

    /**
     * Self-heal a wheel that resolved {@link WheelState#ORPHAN}: land any blocks still held, clear the stale
     * link, and reap the leftover root vehicle.
     *
     * <p><b>Land before unlinking.</b> This used to null the link first and then reap, which is how an orphan
     * record at an empty cell got manufactured in the first place — and if a mechanism was still holding the
     * ship's blocks, they went with it.
     *
     * <p>The old javadoc claimed this "never sweeps {@code corelib:mech:*}". That was false: the only entity
     * that ever carries {@code shipRootTag} is the corelib-spawned ArmorStand BlockShips adopts as its
     * vehicle, which by construction also carries {@code corelib:mech:{id}:vehicle}. The reap is still
     * correct — it only runs once the mechanism is provably gone — but it is a corelib-tagged entity, and
     * {@code isCorelibTagged} is deliberately NOT used as a filter here for that reason.
     */
    private void reconcileOrphan(ShipWheelData wheel) {
        UUID uuid = wheel.getAssembledShipUUID();
        if (uuid == null) {
            wheel.setAssembledShipUUID(null);
            saveAll();
            return;
        }

        // If a mechanism is somehow still live for this id, get the blocks back into the world before the
        // link goes — after that we have no way to reach it.
        if (plugin instanceof BlockShipsPlugin bsp) {
            anon.def9a2a4.blockships.ShipOrphans.disassembleOrphan(bsp, uuid);
        }

        wheel.setAssembledShipUUID(null);
        saveAll();

        Location near = wheel.getBlockLocation();
        if (near == null || near.getWorld() == null) return;
        String rootTag = anon.def9a2a4.blockships.ShipTags.shipRootTag(uuid);
        // Bounded, not world.getEntities(): this runs from five call sites, two of them player-interactive
        // (assemble, disassemble), and an unbounded whole-world entity walk on a click is a stall waiting to
        // happen. corelib bounds the equivalent sweep the same way, around the persisted pivot.
        for (org.bukkit.entity.Entity e : near.getWorld().getNearbyEntities(near, 96, 96, 96)) {
            if (e instanceof org.bukkit.entity.ArmorStand && e.getScoreboardTags().contains(rootTag)) {
                e.remove();
            }
        }
    }

    /**
     * Admin escape hatch (F1c): unconditionally clear a wheel's assembled link + reap its orphan root vehicle,
     * bypassing the recoverability check. For a wheel stuck {@code UNLOADED_RECOVERABLE} by a ship that can never
     * rebind (e.g. a missing sidecar / model-deserialize failure in reconstructDelegatedShip). Admin-gated by the
     * command; players cannot reach it (the menu defers on UNLOADED_RECOVERABLE).
     */
    public void forceClearWheelLink(ShipWheelData wheel) {
        reconcileOrphan(wheel);
    }

    /** The nearest placed wheel to {@code loc} within {@code radius} blocks (same world), or null. */
    public ShipWheelData getNearestWheel(Location loc, double radius) {
        if (loc == null || loc.getWorld() == null) return null;
        ShipWheelData best = null;
        double bestSq = radius * radius;
        for (ShipWheelData w : placedWheels.values()) {
            Location bl = w.getBlockLocation();
            if (bl == null || bl.getWorld() == null || !bl.getWorld().equals(loc.getWorld())) continue;
            double d = bl.distanceSquared(loc);
            if (d <= bestSq) { bestSq = d; best = w; }
        }
        return best;
    }

    /**
     * Moves a wheel's <i>cached</i> cell after it lands somewhere new.
     *
     * <p>No map surgery: {@code placedWheels} is keyed by wheel id, which does not change when the block
     * moves, so the old remove/re-put pair was a no-op on the same key. This is the only caller of
     * {@link ShipWheelData#updateBlockLocation}, which is package-private so the cache cannot be moved
     * behind the manager's back.
     */
    private void relocate(ShipWheelData wheelData, Location newLocation, BlockFace newFacing) {
        wheelData.updateBlockLocation(newLocation, newFacing);
    }

    /**
     * Confirms the wheel actually landed where we just said it did, and drops the record if it did not.
     *
     * <p>The cache is relocated BEFORE {@code disassemble()} (so the glue rebind can resolve it), which means
     * it is written optimistically. The engine does not always place the anchor head: when a solid block
     * already occupies the landing cell it drops the wheel as an item instead, and a mid-teardown throw can
     * leave the same result. Without this check that produces a record pointing at a cell no block will ever
     * carry — an orphan that {@code /blockships wheels adopt} cannot repair, because there is no block
     * bearing the id to adopt.
     *
     * <p>Deliberately tolerant of an <b>unstamped</b> head: a wheel placed before identity existed lands
     * without a {@code wheel_id}, and the lookup-time legacy fallback adopts it on first interaction. Only a
     * cell holding no head at all, or a head belonging to a <i>different</i> wheel, counts as "did not land".
     */
    private void verifyWheelLanded(ShipWheelData wheelData, Location cell, boolean cellWasOccupied) {
        if (cell == null || liveWorld(cell) == null) return;
        Block landed = cell.getBlock();
        Material type = landed.getType();
        boolean isHead = type == Material.PLAYER_HEAD || type == Material.PLAYER_WALL_HEAD;
        UUID landedId = ShipWheelBlockType.readWheelId(landed);

        // Our own stamped wheel: unambiguous, always accepted.
        if (isHead && wheelData.getWheelId().equals(landedId)) return;

        // An UNSTAMPED head is accepted only if this cell was empty before the landing. The tolerance exists
        // for a legacy wheel, which lands carrying no id and gets adopted on the next right-click — but it is
        // indistinguishable from a plain player head that was already standing here, and accepting one of
        // those hands the record to a squatter: the wheel itself was dropped as an item (corelib's landing
        // takes "solid wins" on an occupied cell), so the record would go on pointing at somebody else's
        // block, and every wheel→block write would follow it there.
        if (isHead && landedId == null && !cellWasOccupied) return;
        placedWheels.remove(wheelData.getWheelId());
        ShipWheelAnchors.forget(landed);
        plugin.getLogger().warning("Wheel " + wheelData.getWheelId() + " did not land at "
            + locationKey(cell) + " (cell holds " + type + (landedId != null ? ", wheel " + landedId : "")
            + "). The engine dropped it as an item; dropping the record so it cannot strand.");
    }

    /**
     * Assembles a custom ship from blocks around the wheel.
     */
    public boolean assembleShip(Player player, ShipWheelData wheelData) {
        // Track W (R0): resolve the REAL state, not the raw flag. A LOADED/loading ship blocks re-assembly; an
        // ORPHAN (stale link from an out-of-band ship death) self-heals here so the player can assemble again —
        // this is the fix for the "wheel confused about assembled, Assemble refuses" symptom.
        WheelResolution wr = resolveWheelState(wheelData);
        if (wr.state() == WheelState.LOADED) {
            player.sendMessage("§cThis wheel already has an assembled ship!");
            return false;
        }
        if (wr.state() == WheelState.UNLOADED_RECOVERABLE) {
            player.sendMessage("§eThis wheel's ship is still loading — try again in a moment.");
            return false;
        }
        if (wr.state() == WheelState.ORPHAN) {
            reconcileOrphan(wheelData);
        }

        // The flood fill is about to be seeded from the record's cell, and everything it reaches will be
        // AIRED OUT of the world and carried away as a ship. The only test on that seed was "not air", so a
        // record left pointing at a vacated cell — which is what several failure paths produce — would suck
        // in whatever a neighbour has since built there and delete it from the world. WorldGuard is checked
        // per-cell later, so this bites everywhere outside a region.
        //
        // Placed AFTER the state switch above on purpose: by here LOADED and UNLOADED_RECOVERABLE have
        // already been refused with their own accurate messages, and an ORPHAN has just had its dead link
        // cleared, so the wheel is genuinely docked and its block genuinely ought to be under us.
        if (ownedBlock(wheelData) == null) {
            player.sendMessage("§cThis ship wheel's block isn't at the recorded spot, so there's nothing to "
                + "assemble from. Right-click the wheel block itself to re-sync it.");
            return false;
        }

        Location wheelLoc = wheelData.getBlockLocation();

        // Scan the structure. Returns the derived model PLUS the live world blocks in parts-index order
        // (still in the world — air-out is deferred), so the delegated assembler gets block-index parity.
        //
        // A LOCKED wheel skips the flood fill entirely and rebuilds from its frozen cell set, so blocks
        // stacked against the docked hull are never absorbed. Both paths funnel into the same
        // captureCells(), so the two ScanResults are structurally identical.
        BlockStructureScanner.ScanResult scan = wheelData.isLocked()
            ? BlockStructureScanner.scanFrozen(wheelLoc, wheelData.getFacing())
            : BlockStructureScanner.scanStructure(wheelLoc, wheelData.getFacing());
        if (scan == null || scan.model().parts.isEmpty()) {
            player.sendMessage(wheelData.isLocked()
                ? "§cNothing left of the locked structure — unlock the wheel and re-detect."
                : "§cNo valid ship structure found!");
            return false;
        }
        ShipModel model = scan.model();

        // Set spawn location yaw to match wheel facing direction
        // This ensures the vehicle spawns facing the correct direction
        float assemblyYaw = BlockStructureScanner.blockFaceToYaw(wheelData.getFacing());
        wheelLoc.setYaw(assemblyYaw);

        // WorldGuard: deny assembly if any scanned block sits in a protected region the player can't build
        // in. This checks EVERY flood-filled cell (removeBlocks would delete them all), closing the
        // block-laundering exploit (assemble across a border, then force-disassemble to drop the blocks as
        // items). No force override here — that would defeat the purpose. Members/ops with bypass pass freely.
        // Gated by mightRestrictFailClosed so worlds without regions (and servers without WorldGuard) skip
        // the scan entirely, while a transient WG fault fails CLOSED (scan runs, cells count as protected)
        // so the exploit can't reopen during a WG hiccup.
        if (anon.def9a2a4.blockships.integration.WorldGuardHook.get().mightRestrictFailClosed(wheelLoc.getWorld())) {
            BlockStructureScanner.PlacementConflicts wgConflicts =
                BlockStructureScanner.validatePlacementArea(wheelLoc, model, assemblyYaw, player, true);
            if (wgConflicts.protectedCount > 0) {
                player.sendMessage("§cCannot assemble — " + wgConflicts.protectedCount
                    + " block(s) are in a protected region you can't build in.");
                return false;
            }
        }

        // Delegate the entity engine to defCoreLib (M1): spawn the vehicle, assemble a driven Mechanism from
        // the live scanned blocks (this airs them out AND spawns the displays/colliders), and wrap it in a
        // ShipInstance that skips native entity spawning. shipType "custom"; empty customization (scanned
        // blocks used as-is). The mechanism's block index i == scan.orderedBlocks() position i, so every
        // seat/storage/collision index BlockShips derived from the scan stays valid through the Mechanism API.
        anon.def9a2a4.corelib.MechanismRegistry mechRegistry =
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getMechanismRegistry();
        // Full-size (non-marker) ArmorStand so ARMORSTAND_RIDE_OFFSET applies (matches the legacy vehicle).
        // Spawned at the wheel origin with the assembly yaw so the mechanism's pivot + as-built orientation
        // align with BlockShips' model (part transforms are relative to this same origin).
        org.bukkit.entity.ArmorStand vehicle = wheelLoc.getWorld().spawn(wheelLoc, org.bukkit.entity.ArmorStand.class, as -> {
            as.setInvisible(true);
            as.setGravity(false);
            as.setSilent(true);
            as.setMarker(false);
            as.setPersistent(true);
            // Track W (W1): shield the invisible root vehicle from direct damage/removal (lava, fire, explosion
            // AoE). Ship damage is routed through the collision shulkers (onShulkerDamage → vehicle.setHealth),
            // which bypasses invulnerability, so cannons/sinking/regen are unaffected; this only removes the
            // out-of-band root-death vector that leaves the wheel stuck "assembled". destroy()'s remove() still works.
            as.setInvulnerable(true);
            // Entity yaw MUST be 0: defCoreLib puts ALL rotation into the display transform matrix (its driven
            // contract), and display-entity passengers inherit the vehicle's entity yaw on 1.21.9+ — a non-zero
            // yaw here would rotate the displays an extra assemblyYaw while the teleported collider carriers are
            // unaffected (the "displays 90° off, colliders correct" bug). The ship's facing is carried by the
            // mechanism instead: spawnYaw = currentYaw = model.assemblyYaw so repositionDriven(relYaw=0) at rest.
            as.setRotation(0f, 0f);
        });

        ShipInstance ship = null;
        anon.def9a2a4.corelib.Mechanism mechanism = null;
        try {
            // Airs out the source blocks + spawns displays/colliders on the vehicle (driven mode: BlockShips
            // positions the vehicle each tick and calls repositionDriven — see ShipPhysics/tick, M2).
            mechanism = mechRegistry.assembleMechanism("blockship:custom", scan.orderedBlocks(), vehicle,
                anon.def9a2a4.corelib.MechanismRegistry.ARMORSTAND_RIDE_OFFSET, true, null);
            ship = new ShipInstance(plugin, "custom", model, wheelLoc, ShipCustomization.empty(), vehicle, mechanism);
            ship.sourceModel = model;  // Store the model for disassembly
            ship.adoptMechanismSeats();  // M4: designate seats on the mechanism + populate seatShulkers
            // M5: opt the mechanism into defCoreLib crash-safe persistence (writes its MechanismState + indexes
            // its pivot chunk) so it's SAVED (not disassembled) at /stop and re-recovered on restart/chunk-reload,
            // firing a recovered MechanismAssembleEvent that DisplayShip rebuilds this ShipInstance from. After
            // adoptMechanismSeats so the seat shulker tags are already set when the state snapshot is taken.
            mechRegistry.persist(mechanism);
            // Leads-in is handled by the registry pre-air-out listener (registerLeadsInSeam) — it fires DURING
            // assembleMechanism above (before the fences are aired out), re-leashing mobs onto the colliders.
        } catch (Throwable t) {
            // Roll back: if the mechanism assembled it already aired out the blocks — disassemble to restore
            // them; otherwise just remove the bare vehicle. Guard cleanup so it can't mask the original cause.
            if (mechanism != null) {
                // Leads-in may already have fired (pre-air-out), leaving mobs leashed to the colliders. Return
                // them to the original fences (rotationDelta 0 → original cells) before disassemble deletes the
                // colliders, else the leads pop. Restoration is to already-validated cells, so no WG policy needed.
                final anon.def9a2a4.corelib.Mechanism fMech = mechanism;
                try { mechanism.setBeforeEntityRemoval(() -> transferLeadsFromMechanism(fMech, model, wheelLoc, model.assemblyYaw)); }
                catch (Throwable ignored) { /* best-effort; don't mask the original failure */ }
                try { mechanism.disassemble(); }
                catch (Throwable cleanup) { plugin.getLogger().warning("Cleanup after failed assembly also failed: " + cleanup.getMessage()); }
            } else if (vehicle.isValid()) {
                vehicle.remove();
            }
            if (ship != null) {
                try { ship.destroy(); } catch (Throwable ignored) {}
            }
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Ship assembly failed for " + player.getName(), t);
            player.sendMessage(net.kyori.adventure.text.Component.text(
                "Ship assembly failed: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""),
                net.kyori.adventure.text.format.NamedTextColor.RED));
            player.sendMessage(net.kyori.adventure.text.Component.text(
                "This is a bug - please report it with server logs at " + anon.def9a2a4.blockships.BlockShipsPlugin.ISSUES_URL,
                net.kyori.adventure.text.format.NamedTextColor.RED));
            return false;
        }
        // (No removeBlocks call — assembleMechanism already aired out the source blocks.)

        // Nudge nearby players up to prevent falling through during the 1-tick
        // window before collision shulkers are positioned
        nudgeNearbyPlayersUp(wheelLoc, ship.config.assemblyNudgeHeight);

        // Register the ship
        ShipRegistry.register(ship);

        // Register with per-world storage for chunk recovery
        if (plugin instanceof BlockShipsPlugin bsp) {
            ShipWorldData shipWorldData = bsp.getDisplayShip().getShipWorldData();
            shipWorldData.saveShipMetadataAsync(ship);
        }

        // Link the wheel to the ship
        wheelData.setAssembledShipUUID(ship.id);
        ship.wheelData = wheelData;
        // Track W (W5): persist the wheel↔ship link immediately (every other mutating path saveAll()s). Without
        // it, a crash between assembly and a clean onDisable loses the link while ship metadata persists, leaving
        // a live recovered ship with no wheel (the reverse orphan).
        saveAll();

        ship.physics.recomputeStats();  // Must recompute after wheelData is linked

        // Update detection stats so the ship wheel menu shows correct data immediately
        wheelData.setLastDetectedStats(model.parts.size(), model.blockCount, model.totalWeight,
            model.mass, model.woolCount, model.bannerCount,
            model.largeBannerCount, model.hugeBannerCount);
        wheelData.setLastHealth(ship.vehicle.getHealth(), model.maxHealth);
        wheelData.lastCenterOfVolumeY = model.centerOfVolume.y();
        wheelData.lastMinY = model.minY;
        wheelData.lastSurfaceOffset = model.waterFloatOffset;

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
        // Track W (R0): route through the reconciler — an ORPHAN self-heals rather than silently severing a
        // still-recoverable (unloaded) ship on a momentary byId==null.
        WheelResolution wr = resolveWheelState(wheelData);
        switch (wr.state()) {
            case NOT_ASSEMBLED -> { player.sendMessage("§cNo ship to align! Assemble a ship first."); return false; }
            case UNLOADED_RECOVERABLE -> { player.sendMessage("§eShip is still loading — try again in a moment."); return false; }
            case ORPHAN -> {
                reconcileOrphan(wheelData);
                player.sendMessage("§cThat ship was lost — the wheel has been reset. You can assemble again.");
                return false;
            }
            default -> { /* LOADED — proceed */ }
        }
        ShipInstance ship = wr.ship();

        // Align the ship
        ship.alignToGrid();

        player.sendMessage("§aShip aligned to grid!");
        return true;
    }

    /**
     * Result holder that lets batch callers distinguish a disassembly failure (reported via the
     * method's boolean return) from a persistence failure that happens <em>after</em> the ship has
     * already been taken apart in-world. A persistence failure does not make disassembly "fail" - the
     * ship is gone - but the admin still needs to know the on-disk cleanup did not fully succeed.
     */
    public static final class DisassembleOutcome {
        /** Set true if removing the ship's per-world YAML / chunk-index entry failed to save. */
        public boolean persistFailed = false;
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
        return disassembleShip(player, wheelData, force, new DisassembleOutcome());
    }

    /**
     * Disassembles a ship back into blocks, reporting persistence failures separately.
     *
     * @param player The player disassembling the ship
     * @param wheelData The ship wheel data
     * @param force If true, destroys fragile blocks (grass, flowers, etc.) in the way.
     * @param outcome Populated with {@code persistFailed=true} if the post-disassembly YAML /
     *               chunk-index cleanup failed to save (disassembly itself still succeeded).
     * @return true if disassembly succeeded, false otherwise
     */
    public boolean disassembleShip(@Nullable Player player, ShipWheelData wheelData, boolean force,
                                   DisassembleOutcome outcome) {
        // Track W (R0): route through the reconciler — ORPHAN self-heals instead of severing an unloaded ship.
        WheelResolution wr = resolveWheelState(wheelData);
        switch (wr.state()) {
            case NOT_ASSEMBLED -> { if (player != null) player.sendMessage("§cNo ship to disassemble!"); return false; }
            case UNLOADED_RECOVERABLE -> { if (player != null) player.sendMessage("§eShip is still loading — try again in a moment."); return false; }
            case ORPHAN -> {
                reconcileOrphan(wheelData);
                if (player != null) player.sendMessage("§cThat ship was lost — the wheel has been reset.");
                return false;
            }
            default -> { /* LOADED — proceed */ }
        }
        ShipInstance ship = wr.ship();

        // Get the ship's model
        ShipModel model = ship.sourceModel;
        if (model == null) {
            if (player != null) player.sendMessage("§cCannot disassemble this ship (no source model)!");
            return false;
        }

        // Align to grid first
        ship.alignToGrid();

        // Get the ship's current location and rotation (vehicle yaw is frozen,
        // so read the internal yaw which was just snapped by alignToGrid)
        Location shipLoc = ship.vehicle.getLocation();
        float currentYaw = ship.physics.currentYaw;

        // Validate placement area (with rotation). Pass the acting player so WorldGuard-protected cells
        // they can't build in are counted as conflicts (members/ops with bypass are unaffected).
        BlockStructureScanner.PlacementConflicts conflicts =
            BlockStructureScanner.validatePlacementArea(shipLoc, model, currentYaw, player);

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
                if (conflicts.protectedCount > 0) {
                    player.sendMessage("§6  - " + conflicts.protectedCount
                        + " block(s) in a protected region will DROP as items");
                }
            }
            return false;
        }

        // Clear conflict state on successful disassembly attempt
        wheelData.setLastDisassemblyConflicts(null);

        // Storage inventories are mechanism-owned for a delegated ship; defCoreLib's disassemble() restores
        // container contents from its own snapshot, so there is nothing to sync back to the model here.

        // WorldGuard: decide ONCE whether the wheel-anchor cell is protected, and pass it into placeBlocks
        // so the head-skip there and the deregister below use the same answer (they can never disagree).
        Location newWheelLocation = shipLoc.getBlock().getLocation();
        // Admin toggle: for unattended/system paths (player == null) that opt into place-anyway, treat the
        // world as region-free so blocks (and the wheel anchor) are placed normally instead of dropped.
        boolean wgOn = anon.def9a2a4.blockships.integration.WorldGuardHook.get().mightRestrict(newWheelLocation.getWorld())
            && !(player == null && anon.def9a2a4.blockships.integration.WorldGuardHook.get().systemPathPlacesInRegions());
        boolean anchorProtected = wgOn && force
            && anon.def9a2a4.blockships.integration.WorldGuardHook.get().isBuildDenied(newWheelLocation, player);

        // Is something already standing on the cell the wheel is about to land in?
        //
        // Sampled here, BEFORE the relocate below and before the place policy's closure captures
        // anchorProtected, because it changes what both of those should do. If corelib's landing would take
        // its "solid wins" branch at this cell, the wheel head is NOT placed: an explosion effect plays and
        // the wheel is dropped as a GENERIC item — one minted from the block type, carrying no wheel_id — so
        // the ship's identity, glue and structure lock are all lost while a record survives pointing at a
        // block that belongs to somebody else.
        //
        // Asked of corelib rather than reimplemented. The predicate consults two material sets that live on
        // opposite sides of a visibility boundary there, so any copy made here would necessarily be a partial
        // one written against BlockShips' own lists — which already differ (leaf litter, bushes, nether wart
        // and cocoa are fragile to corelib and not to us), and would drift further every time either list
        // changes. A false positive here is not cheap: it would refuse landings that work today.
        boolean anchorCellOccupied = !anchorProtected
            && anon.def9a2a4.corelib.MechanismRegistry.landingSolidWouldWin(newWheelLocation.getBlock().getType());
        if (anchorCellOccupied) {
            // Degrade to the protected-anchor path rather than refusing the disassembly.
            //
            // Refusing was the obvious fix and is the wrong one: the anchor cell IS the ship's pivot, hidden
            // underneath the ship's own block displays and quite possibly inside terrain the player cannot
            // break or a region they cannot build in. A captain who cannot land is stuck with no in-game
            // remedy at all, whereas the branch below lands the hull, hands the wheel back as an item, and
            // deregisters cleanly so the record never comes to alias a stranger's block. Losing the glue and
            // the lock is a bad outcome; losing the ship is a worse one.
            anchorProtected = true;
            plugin.getLogger().info("Wheel anchor cell " + locationKey(newWheelLocation)
                + " is occupied by " + newWheelLocation.getBlock().getType()
                + "; landing the hull and returning wheel " + wheelData.getWheelId() + " as an item.");
        }

        // Move the wheel's cached cell BEFORE disassembly, not after — this is load-bearing, not tidying.
        //
        // defCoreLib's disassemble() rebinds landed glue anchors near the end of its run: it asks the anchor
        // provider to claim the landed block (BasicMechanism -> Anchors.externalFor -> ShipWheelAnchors).
        // Our provider looked the wheel up by its cached cell, which was still the OLD dock — so the claim
        // failed, corelib fell back to a plain BlockAnchor, and BlockAnchor.prunesOnLanding() is true where
        // ExternalAnchor's is false. The prune keeps only cells chained back to the origin through other
        // glued cells; an unlocked wheel's brush-authored extras sit on unglued hull, so they were deleted.
        // Silently, permanently, on every landing at a new cell. (toggleLock's own javadoc already calls
        // re-freezing "the repair path" for exactly this erosion.)
        //
        // Only the non-protected arm moves: the anchorProtected arm below REMOVES the record instead, and
        // hoisting that would null the provider lookup during the very rebind loop this is fixing.
        Location oldWheelCell = wheelData.getBlockLocation();
        if (!anchorProtected) {
            float preRotationDelta = currentYaw - model.assemblyYaw;
            BlockFace preFacing = BlockStructureScanner.rotateBlockFace(wheelData.getFacing(), preRotationDelta);
            relocate(wheelData, newWheelLocation, preFacing);
            // The connector cache is location-keyed and nothing else drops the old entry.
            if (oldWheelCell != null && oldWheelCell.getWorld() != null) {
                ShipWheelAnchors.forget(oldWheelCell.getBlock());
            }
        }

        if (ship.mechanism != null) {
            // Delegated (M1): the Mechanism restores the blocks to the world AND removes its own displays/
            // colliders. Wire the three disassembly seams so the delegated teardown reproduces the native
            // placeBlocks behavior (WG drop-routing + wheel-anchor skip, wall→floor drop remap, leads-out),
            // then disassemble. The external vehicle is removed by ship.destroy() below.
            final int anchorX = newWheelLocation.getBlockX();
            final int anchorY = newWheelLocation.getBlockY();
            final int anchorZ = newWheelLocation.getBlockZ();
            final boolean fForce = force;
            final boolean fWgOn = wgOn;
            final boolean fAnchorProtected = anchorProtected;
            final org.bukkit.entity.Player fPlayer = player;
            // F6: net yaw applied to blockData at landing (same transform placeBlocks/the engine snap to), used to
            // rotate a bed's assembly-frame facing into its WORLD facing so the 2-cell partner cell is correct.
            final float fRotationDelta = currentYaw - model.assemblyYaw;

            // Cell placement policy — mirror BlockStructureScanner.placeBlocks:884-896. Compare the anchor by
            // BLOCK COORDINATES (not Location.equals — that also compares yaw/pitch and never matches).
            ship.mechanism.setCellPlacePolicy((target, block) -> {
                if (target.getX() == anchorX && target.getY() == anchorY && target.getZ() == anchorZ) {
                    // Wheel anchor: SKIP when protected (BlockShips drops the wheel item + deregisters below),
                    // else PLACE normally.
                    return fAnchorProtected
                        ? anon.def9a2a4.corelib.Mechanism.PlaceDecision.SKIP
                        : anon.def9a2a4.corelib.Mechanism.PlaceDecision.PLACE;
                }
                if (fForce && fWgOn) {
                    boolean denied = anon.def9a2a4.blockships.integration.WorldGuardHook.get()
                        .isBuildDenied(target.getLocation(), fPlayer);
                    // F6: a 2-cell block (bed/door) must share ONE fate across both cells, or a WG border running
                    // between them places one half and drops the other — and the DropItemHook then suppresses the
                    // second half's drop, LOSING it. If EITHER cell is denied, DROP both (the hook drops exactly one
                    // item for the pair). Doors/bisected: partner is vertical (rotation-immune). Bed: partner is in
                    // the rotated facing (blockData is assembly-frame; rotate it by the net landing yaw).
                    if (!denied && block != null) {
                        org.bukkit.block.data.BlockData bd = block.blockData;
                        org.bukkit.block.Block partner = null;
                        if (bd instanceof org.bukkit.block.data.Bisected bis
                                && !(bd instanceof org.bukkit.block.data.type.Stairs)
                                && !(bd instanceof org.bukkit.block.data.type.TrapDoor)) {
                            partner = target.getRelative(0,
                                bis.getHalf() == org.bukkit.block.data.Bisected.Half.TOP ? -1 : 1, 0);
                        } else if (bd instanceof org.bukkit.block.data.type.Bed bed) {
                            org.bukkit.block.BlockFace f =
                                BlockStructureScanner.rotateBlockFace(bed.getFacing(), fRotationDelta);
                            partner = target.getRelative(
                                bed.getPart() == org.bukkit.block.data.type.Bed.Part.FOOT ? f : f.getOppositeFace());
                        }
                        if (partner != null && anon.def9a2a4.blockships.integration.WorldGuardHook.get()
                                .isBuildDenied(partner.getLocation(), fPlayer)) {
                            denied = true;
                        }
                    }
                    if (denied) return anon.def9a2a4.corelib.Mechanism.PlaceDecision.DROP;
                }
                return anon.def9a2a4.corelib.Mechanism.PlaceDecision.PLACE;
            });

            // Drop-item hook — (i) multi-cell dupe guard: drop only the primary half of a 2-cell block (else a
            // WG-denied bed/door drops twice); (ii) wall→floor material remap for variants with no item form.
            ship.mechanism.setDropItemHook((mb, defaultDrop) -> {
                org.bukkit.block.data.BlockData bd = mb.blockData;
                if (bd instanceof org.bukkit.block.data.Bisected bis
                        && !(bd instanceof org.bukkit.block.data.type.Stairs)
                        && !(bd instanceof org.bukkit.block.data.type.TrapDoor)
                        && bis.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) {
                    return null; // upper half — primary (bottom) half already dropped
                }
                if (bd instanceof org.bukkit.block.data.type.Bed bed
                        && bed.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD) {
                    return null; // head half — foot already dropped
                }
                if (defaultDrop != null) return defaultDrop; // engine already had an item form
                String name = bd.getMaterial().name();
                String remapped = name;
                if (name.contains("_WALL_HEAD")) remapped = name.replace("_WALL_HEAD", "_HEAD");
                else if (name.contains("_WALL_SKULL")) remapped = name.replace("_WALL_SKULL", "_SKULL");
                else if (name.contains("_WALL_BANNER")) remapped = name.replace("_WALL_BANNER", "_BANNER");
                else if (name.contains("_WALL_SIGN")) remapped = name.replace("_WALL_SIGN", "_SIGN");
                else if (name.contains("WALL_TORCH")) remapped = name.replace("WALL_TORCH", "TORCH");
                else if (name.equals("REDSTONE_WIRE")) remapped = "REDSTONE";
                else if (name.equals("TRIPWIRE")) remapped = "STRING";
                try {
                    Material rm = Material.valueOf(remapped);
                    if (rm.isItem()) return new ItemStack(rm);
                } catch (IllegalArgumentException ignored) { /* no floor form — suppress */ }
                return null;
            });

            // Leads-out — re-leash entities off the collider shulkers onto fresh LeashHitches on the landed
            // fences, after blocks land but before the colliders are removed (via transferLeadsFromMechanism).
            final ShipModel fModel = model;
            final Location fShipLoc = shipLoc.clone();
            final float fYaw = currentYaw;
            final anon.def9a2a4.corelib.Mechanism fMech = ship.mechanism;
            ship.mechanism.setBeforeEntityRemoval(() -> transferLeadsFromMechanism(fMech, fModel, fShipLoc, fYaw));

            // Track W (W3): defCoreLib's disassemble() is idempotent + mostly-complete; a mid-teardown throw is
            // rare. Do NOT abort (the idempotency latch blocks a clean re-restore) — log and CONTINUE to the
            // teardown tail (ship.destroy() clears the wheel via W2) so the wheel self-heals rather than sticking.
            try {
                ship.mechanism.disassemble();
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Delegated disassembly threw for ship " + ship.id + " — completing teardown; some blocks may be missing", t);
                if (player != null) player.sendMessage(
                    "§eDisassembly hit an error — the ship was taken down but some blocks may be missing. (see server logs)");
            }
        }

        // Update wheel tracking to new location
        if (anchorProtected) {
            // The anchor head was NOT placed (protected region). Return the wheel as an item and deregister
            // the wheel — like breakWheelBlock() but WITHOUT touching the protected cell's block.
            org.bukkit.World wWorld = newWheelLocation.getWorld();
            if (wWorld != null && plugin instanceof BlockShipsPlugin bsp) {
                wWorld.dropItemNaturally(newWheelLocation.clone().add(0.5, 0.5, 0.5),
                    bsp.getDisplayShip().createShipWheelItem());
            }
            // Clear the launch cell's glue BEFORE deregistering, and only if that cell still holds this
            // wheel. oldWheelCell was captured while the ship was still assembled, so it is the cell the
            // wheel left at launch — empty for the whole voyage and free for anyone to build on. ShipGlue
            // .clear is a persistent PDC write, so running it unconditionally wipes the glue off whatever is
            // standing there now. (The ordering also matters: ownedBlock consults ownsBlock, whose legacy arm
            // needs the record to still describe the wheel.)
            Block oldBlock = ownedBlock(wheelData);
            if (oldBlock != null && cellsAgree(oldWheelCell, oldBlock.getLocation())) {
                ShipGlue.clear(oldBlock);
                ShipWheelAnchors.forget(oldBlock);
            }
            if (oldWheelCell != null) ShipWheelMenu.forgetDockedThrust(oldWheelCell);
            placedWheels.remove(wheelData.getWheelId());
            saveAll();
            if (player != null) {
                player.sendMessage(anchorCellOccupied
                    ? "§eSomething was standing where the wheel had to land, so the wheel came back to you as "
                      + "an item — this ship is no longer tracked."
                    : "§eThe wheel couldn't be placed in that protected region, so it came back to you as an "
                      + "item — this ship is no longer tracked.");
                player.sendMessage("§7Place the wheel again to start a new ship. Its glue and structure lock "
                    + "are not carried over.");
            }
        } else {
            // anchorCellOccupied is false on this arm by construction (an occupied cell diverts to the
            // protected-anchor branch above), so this is the "cell was clear" case — pass it through anyway
            // rather than hard-coding false, so the two stay linked if that ever changes.
            verifyWheelLanded(wheelData, newWheelLocation, anchorCellOccupied);
        }

        // Destroy the ship
        Location shipLoc2 = ship.vehicle.getLocation();
        ship.destroy();

        // Nudge nearby players up to prevent clipping into placed blocks
        nudgeNearbyPlayersUp(shipLoc2, ship.config.assemblyNudgeHeight);

        // Remove ship from per-world storage (delete the sidecar file). The ship is already
        // gone from the world at this point, so a save failure here does not fail the disassembly -
        // it is reported separately via outcome.persistFailed (and logged SEVERE by the callees).
        if (plugin instanceof BlockShipsPlugin bsp) {
            org.bukkit.World world = shipLoc.getWorld();
            if (world != null) {
                ShipWorldData worldData = bsp.getDisplayShip().getShipWorldData();
                boolean fileOk = worldData.removeShip(world, ship.id);
                if (!fileOk) {
                    outcome.persistFailed = true;
                    plugin.getLogger().severe("disassembleShip: persistence cleanup failed for ship "
                        + ship.id + " (world=" + world.getName() + ")");
                }
            } else {
                // Can't resolve the world, so the per-world YAML sidecar can't be cleaned. Flag
                // it as a persistence failure rather than skip silently - the ship is gone in-world but
                // its on-disk record may linger.
                outcome.persistFailed = true;
                plugin.getLogger().warning("disassembleShip: skipped persistence cleanup for ship "
                    + ship.id + ": world unresolved");
            }
        }

        // Unlink from wheel
        wheelData.setAssembledShipUUID(null);
        // F4: persist the relocated wheel + cleared link now (both branches). Without this a crash before the next
        // save reloads ship_wheels.yml with the OLD location still flagged assembled at an already-deleted sidecar.
        saveAll();

        if (player != null) player.sendMessage("§aShip disassembled!");
        return true;
    }

    /**
     * Teleports nearby players up slightly to prevent them from clipping into
     * collision shulkers or placed blocks during assembly/disassembly transitions.
     */
    private static void nudgeNearbyPlayersUp(Location center, float nudgeHeight) {
        if (nudgeHeight <= 0) return;
        double radius = 20;
        double radiusSq = radius * radius;
        for (Player p : center.getWorld().getPlayers()) {
            Location pLoc = p.getLocation();
            if (pLoc.distanceSquared(center) <= radiusSq
                    && Math.abs(pLoc.getY() - center.getY()) < 10) {
                Location loc = pLoc.clone();
                loc.setY(loc.getY() + nudgeHeight);
                p.teleport(loc);
                p.setFallDistance(0);
            }
        }
    }

    /**
     * Tags the ship wheel's collision shulker so it can be identified when clicked.
     * The wheel block is at position (0,0,0) relative to the ship origin.
     */
    private void tagShipWheelCollider(ShipInstance ship, Location wheelLoc) {
        // Delegated ships have no native `colliders` (the Mechanism owns the collider shulkers). Find the wheel
        // block — the unique model part at local translation (0,0,0), the flood-fill seed; base == part.local so
        // this is the same search the native branch does — and tag its engine collider shulker so a non-sneak
        // right-click on the wheel opens the ship menu, matching native.
        //
        // Recovery dependency: this tag survives a restart only because defCoreLib collider shulkers are
        // setPersistent(true) and RE-ADOPTED (not respawned) on recovery, so the tag rides the shulker's
        // region-file entity NBT — NOT the MechanismState snapshot. That's why tagging AFTER mechRegistry.persist()
        // is correct (don't "fix" the ordering) and why reconstructDelegatedShip deliberately does not re-tag. If
        // defCoreLib ever respawns fresh colliders on recovery, this branch (or reconstructDelegatedShip) must re-tag.
        int i = ship.model.wheelPartIndex();
        if (i >= 0) {
            org.bukkit.entity.Shulker shulker = ship.mechanism.colliderEntity(i);
            if (shulker != null && shulker.isValid()) {
                shulker.addScoreboardTag(ShipTags.wheelTag(wheelLoc));
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
     * Leads-out — the {@link Mechanism#setBeforeEntityRemoval} callback body. The collider shulker for a block
     * index comes from the mechanism ({@code mech.colliderEntity(i)}). Runs after blocks land but before the
     * mechanism's collider entities are removed.
     */
    private void transferLeadsFromMechanism(anon.def9a2a4.corelib.Mechanism mech, ShipModel model,
                                            Location shipLoc, float currentYaw) {
        float rotationDelta = currentYaw - model.assemblyYaw;
        while (rotationDelta < 0) rotationDelta += 360;
        while (rotationDelta >= 360) rotationDelta -= 360;

        for (int blockIndex = 0; blockIndex < model.parts.size(); blockIndex++) {
            ShipModel.ModelPart part = model.parts.get(blockIndex);
            if (!part.rawYaml.containsKey("leadable") || !Boolean.TRUE.equals(part.rawYaml.get("leadable"))) {
                continue;
            }

            Shulker shulker = mech.colliderEntity(blockIndex);
            if (shulker == null || !shulker.isValid()) {
                continue;
            }

            List<org.bukkit.entity.Entity> leashedEntities = findEntitiesLeashedTo(shulker);
            if (leashedEntities.isEmpty()) {
                continue;
            }

            org.joml.Vector3f pos = new org.joml.Vector3f();
            part.local.getTranslation(pos);
            org.joml.Vector3f rotatedPos = BlockStructureScanner.rotatePosition(pos, rotationDelta);
            // Round to match placeBlocks / the mechanism's landing cell (both floor + 90°-snap), else a
            // rotated ship's LeashHitch lands one cell off and the lead pops.
            Location fenceLoc = shipLoc.clone().add(
                Math.round(rotatedPos.x), Math.round(rotatedPos.y), Math.round(rotatedPos.z));

            org.bukkit.entity.LeashHitch hitch = fenceLoc.getWorld().spawn(
                fenceLoc.getBlock().getLocation().add(0.5, 0.5, 0.5),
                org.bukkit.entity.LeashHitch.class
            );

            for (org.bukkit.entity.Entity entity : leashedEntities) {
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
                org.bukkit.attribute.Attribute maxHealthAttr = anon.def9a2a4.blockships.util.AttributeCompat.getMaxHealth();
                org.bukkit.attribute.AttributeInstance maxHealthInstance = maxHealthAttr != null ? ship.vehicle.getAttribute(maxHealthAttr) : null;
                double maxHealth = maxHealthInstance != null ? maxHealthInstance.getBaseValue() : 100.0;
                wheelData.setLastDetectedStats(blockCount, ship.model.blockCount, ship.model.totalWeight,
                    ship.model.mass, ship.model.woolCount, ship.model.bannerCount,
                    ship.model.largeBannerCount, ship.model.hugeBannerCount);
                wheelData.setLastHealth(currentHealth, maxHealth);
                // Store buoyancy data from ship model
                wheelData.lastCenterOfVolumeY = ship.model.centerOfVolume.y();
                wheelData.lastMinY = ship.model.minY;
                wheelData.lastSurfaceOffset = ship.model.waterFloatOffset;

                // Send detection chat for assembled ship
                ShipConfig config = ShipConfig.load(plugin, "custom");
                player.sendMessage("§aShip detected (assembled)");
                player.sendMessage("§7Blocks: §f" + blockCount);
                player.sendMessage("§7Health: §f" + (int) Math.ceil(currentHealth) + " §7/ §f" + (int) maxHealth);
                // Seats, matching the docked readout. Note model.seats INCLUDES the driver (it is
                // seats.get(0)), unlike the detect preview's seatBlocks, which holds passengers only —
                // so derive the passenger count rather than adding one.
                int seatCount = ship.model.seats.size();
                int passengerCount = 0;
                for (ShipModel.SeatInfo seat : ship.model.seats) {
                    if (!seat.isDriver) passengerCount++;
                }
                if (seatCount > 0) {
                    player.sendMessage("§7Seats: §f" + seatCount
                        + " §7(" + (seatCount - passengerCount) + " driver + " + passengerCount + " passengers)");
                } else {
                    player.sendMessage("§7Seats: §c0 §7(default seat at wheel is used)");
                }
                if (config.statsEnabled) {
                    // Thrust-aware, and only the blocks actually running count. The sail-only overload
                    // used here before hardcoded forwardRatio to the sail ratio, so a thruster-driven
                    // ship reported the speed of its sails — which on a bare hull is none.
                    anon.def9a2a4.blockships.ShipThrust.Totals thrust =
                        anon.def9a2a4.blockships.ShipThrust.totalsFor(
                            (BlockShipsPlugin) plugin, ship.mechanism, ship.model);
                    anon.def9a2a4.blockships.ShipStats stats =
                        anon.def9a2a4.blockships.ShipStats.of(config, ship.model, thrust, 0f);
                    int speedPercent = stats.speedPercent();

                    player.sendMessage("§7Sails: §f" + describeSails(ship.model) + " §7(" + stats.sailPower + " pts)");
                    player.sendMessage("§7Speed: "
                        + anon.def9a2a4.blockships.ShipStats.speedColor(speedPercent) + speedPercent + "%");
                    sendThrustSummary(player, thrust, true);
                } else {
                    player.sendMessage("§7Stats: §8disabled");
                }

                return true;
            }
        }

        // Everything below classifies the world starting from the record's cell, and it is NOT read-only: it
        // overwrites this wheel's stored detection stats, recomputes its buoyancy data, and spawns a waterline
        // shulker at the result. Pointed at a cell the wheel no longer occupies, it reports a neighbour's
        // structure as this ship and leaves a marker entity in their build.
        //
        // Note this is reached whenever the assembled branch above did not return — including for an ORPHAN,
        // whose isAssembled() is true but whose ShipInstance is gone, so it falls straight through to here.
        if (ownedBlock(wheelData) == null) {
            player.sendMessage("§cNo ship wheel at that location — it may still be assembled, still loading, "
                + "or the wheel may have been moved or destroyed.");
            return false;
        }

        // Get max ship size from config
        int maxShipSize = ((BlockShipsPlugin) plugin).getConfig().getInt("custom-ships.max-ship-size", 1000);
        int maxScanSize = ((BlockShipsPlugin) plugin).getConfig().getInt("custom-ships.max-scan-size", 5000);

        // Membership per the same rules assembly uses, so the preview never disagrees with what actually
        // assembles: a LOCKED ship shows exactly its frozen set (raw glue offsets + wheel, NOT the flood fill,
        // so blocks stacked against a docked hull don't appear); an unlocked ship runs the detect fed the same
        // glued cells assembly will use.
        Set<Location> shipBlocks;
        if (wheelData.isLocked()) {
            // No wheel, no ship — the same guard scanFrozen applies, and the same one the flood-fill path
            // gained in ShipDetector. Without it this branch was the hole that guard did not cover: an
            // assembled-but-unresolvable wheel (its chunk unloaded, so the early return above did not
            // fire) reads an aired-out cell, finds no glue on the skull that is not there, and reports a
            // one-block ship — then overwrites the wheel's stored detection stats with 1/0/0, clobbering
            // the real figures the menu reads.
            if (wheelLoc.getBlock().getType().isAir()) {
                player.sendMessage("§cNo ship wheel at that location — it may still be assembled or loading.");
                return false;
            }
            shipBlocks = new HashSet<>();
            for (Location c : ShipGlue.rawGlueCells(wheelLoc.getBlock())) {
                if (!c.getBlock().getType().isAir()) shipBlocks.add(c);
            }
            shipBlocks.add(wheelLoc.clone());
            // Same current-limit re-check scanFrozen performs. Without it an oversized frozen set
            // previews cleanly here and then refuses to assemble, with nothing explaining why.
            if (shipBlocks.size() > maxShipSize) {
                player.sendMessage("§cShip too large: §f" + shipBlocks.size() + " §7/ §f" + maxShipSize
                    + " §7(frozen while the limit was higher — unlock and re-freeze to shrink it)");
                return false;
            }
        } else {
            ShipDetector detector = new ShipDetector(maxShipSize, maxScanSize);
            ShipDetector.ShipDetectionResult result =
                detector.detectShipDetailed(wheelLoc, ShipGlue.gluedCells(wheelLoc.getBlock()));
            if (!result.isSuccess()) {
                player.sendMessage("§c" + result.getMessage());   // too large or other error
                return false;
            }
            shipBlocks = result.getBlocks();
        }

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

        int woolCount = 0;
        int bannerCount = 0;
        for (Location loc : shipBlocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());

            if (props.isSeat()) {
                // All detected seat blocks are passenger seats
                seatBlocks.add(loc);
            } else {
                regularBlocks.add(loc);
            }

            // Count sail blocks for ship stats
            Material blockMaterial = block.getType();
            if (Tag.WOOL.isTagged(blockMaterial)) {
                woolCount++;
            } else if (blockMaterial.name().contains("BANNER")) {
                bannerCount++;
            }
        }

        // Large/huge banners are bbanners display entities, so the block walk above cannot see them.
        // Same helper the assembly scan uses, so docked and assembled report the same sail power.
        int[] tierBanners = BlockStructureScanner.countLargeHuge(shipBlocks);
        int largeBannerCount = tierBanners[0];
        int hugeBannerCount = tierBanners[1];

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
        // Ship stats
        if (config.statsEnabled) {
            // One world scan feeds both the speed figure and the summary below — hoisted out of the
            // summary so the two cannot disagree, and so the docked Speed includes thrust the same way
            // the assembled one does.
            anon.def9a2a4.blockships.ShipThrust.Totals thrust =
                anon.def9a2a4.blockships.ShipThrust.scanWorld((BlockShipsPlugin) plugin, shipBlocks,
                    BlockStructureScanner.blockFaceToYaw(wheelData.getFacing()));
            anon.def9a2a4.blockships.ShipStats stats = anon.def9a2a4.blockships.ShipStats.of(
                config, woolCount, bannerCount, largeBannerCount, hugeBannerCount,
                calculateMass(shipBlocks), totalWeight, thrust, 0f);
            int speedPercent = stats.speedPercent();
            player.sendMessage("§7Sails: §f"
                + describeSails(woolCount, bannerCount, largeBannerCount, hugeBannerCount)
                + " §7(" + stats.sailPower + " pts)");
            player.sendMessage("§7Speed: " + anon.def9a2a4.blockships.ShipStats.speedColor(speedPercent)
                + speedPercent + "%"
                + (speedPercent < 50 ? " §8(add sails, or propellers along the hull)" : ""));
            sendThrustSummary(player, thrust, false);
        } else {
            player.sendMessage("§7Stats: §8disabled");
        }

        // Store detected blocks and stats for Ship Info display
        int positiveWeight = calculateMass(shipBlocks);
        wheelData.setLastDetectedBlocks(shipBlocks);
        wheelData.setLastDetectedStats(blockCount, weightedBlockCount, totalWeight, positiveWeight,
            woolCount, bannerCount, largeBannerCount, hugeBannerCount);
        wheelData.setLastDetectedBlockCategories(regularBlocks, seatBlocks, driverSeat);

        // Calculate and store buoyancy data for Ship Info display
        calculateAndStoreBuoyancyData(wheelData, shipBlocks, totalWeight);

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
     * Report the propulsion aboard, grouped by what it does. The classification is the same one
     * ShipPhysics applies to movement: mount a propeller sideways and it reads "turning", not "forward".
     *
     * <p>One method for both the assembled and the docked readout, taking the {@link
     * anon.def9a2a4.blockships.ShipThrust.Totals} the caller already computed. They used to be two, and
     * had drifted — the docked one collapsed gyroscopes into turning while the assembled one split them,
     * so the same ship described itself differently depending on whether it was flying.
     *
     * <p>{@code live} distinguishes what is RUNNING from what is merely aboard. The assembled readout
     * used to print every block unlabelled, so a ship whose fuel had run out still reported
     * "forward: 4 (48 pts)" while producing nothing — and it read as authoritative, because the
     * "not yet applied to movement" disclaimer that once excused it was removed when thrust started
     * actually driving physics.
     */
    private static void sendThrustSummary(Player player, anon.def9a2a4.blockships.ShipThrust.Totals t,
                                          boolean live) {
        if (t.total() <= 0) return;
        String head = live
            ? "§7Propulsion: §f" + t.powered() + "§7/§f" + t.total() + " §7running"
            : "§7Propulsion: §f" + t.total() + " block(s) §8(potential — nothing is powered while docked)";
        player.sendMessage(head);
        if (t.axial() > 0)    player.sendMessage("  §7forward: §f" + t.axial() + " pts");
        if (t.turning() > 0)  player.sendMessage("  §7turning: §f" + t.turning() + " pts");
        if (t.vertical() > 0) player.sendMessage("  §7lift: §f" + t.vertical() + " pts");
    }

    /** "3 wool, 2 banners, 1 large banner" — omits tiers the ship doesn't carry. */
    private static String describeSails(ShipModel model) {
        return describeSails(model.woolCount, model.bannerCount,
            model.largeBannerCount, model.hugeBannerCount);
    }

    /**
     * Counts-based form, for the docked preview, which has no {@link ShipModel}. Both readouts go
     * through this so the same ship cannot describe itself two ways depending on whether it happens
     * to be assembled.
     */
    private static String describeSails(int wool, int banner, int large, int huge) {
        StringBuilder sb = new StringBuilder();
        appendCount(sb, wool, "wool", "wool");
        appendCount(sb, banner, "banner", "banners");
        appendCount(sb, large, "large banner", "large banners");
        appendCount(sb, huge, "huge banner", "huge banners");
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static void appendCount(StringBuilder sb, int n, String singular, String plural) {
        if (n <= 0) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(n).append(' ').append(n == 1 ? singular : plural);
    }

    /**
     * Calculate the total weight of all blocks in the ship.
     *
     * <p>All three of these helpers go through {@link BlockConfigManager#resolveWeight}, matching
     * {@code BlockStructureScanner}: a glued block is not in blocks.yml and must be priced from
     * defCoreLib's mass table rather than read as weightless.
     */
    private int calculateTotalWeight(Set<Location> blocks) {
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        int totalWeight = 0;

        for (Location loc : blocks) {
            Block block = loc.getBlock();
            // Guarded: this used to unbox getWeight() with no hasWeight() check, unlike its two
            // siblings below — a block configured with an explicit `weight: null` NPE'd /detect.
            Integer w = configManager.resolveWeight(block.getType(), block.getBlockData());
            if (w != null) {
                totalWeight += w;
            }
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
            if (configManager.resolveWeight(block.getType(), block.getBlockData()) != null) {
                count++;
            }
        }

        return count;
    }

    /**
     * Calculate the sum of positive weights (used for health calculation).
     * Blocks with negative or zero weight contribute nothing to health.
     */
    private int calculateMass(Set<Location> blocks) {
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        int positiveWeight = 0;

        for (Location loc : blocks) {
            Block block = loc.getBlock();
            Integer w = configManager.resolveWeight(block.getType(), block.getBlockData());
            if (w != null && w > 0) {
                positiveWeight += w;
            }
        }

        return positiveWeight;
    }

    /**
     * Calculate and store buoyancy data (centerOfVolumeY, minY, surfaceOffset) for Ship Info display.
     *
     * <p>Mirrors {@link BlockStructureScanner#captureCells}'s bounds and centre-of-volume maths exactly,
     * including {@link BlockStructureScanner#bottomFaceY} and the {@code resolveWeight} membership test,
     * so the preview waterline the player sees matches the one the assembled ship floats at.
     */
    private void calculateAndStoreBuoyancyData(ShipWheelData wheelData, Set<Location> shipBlocks, int totalWeight) {
        Location wheelLoc = wheelData.getBlockLocation();
        BlockConfigManager configManager = BlockConfigManager.getInstance();

        float minY = Float.MAX_VALUE;
        float sumY = 0;
        int weightedCount = 0;

        for (Location loc : shipBlocks) {
            Block block = loc.getBlock();

            float blockY = (float) (loc.getY() - wheelLoc.getY());
            float bottom = BlockStructureScanner.bottomFaceY(block.getBlockData(), blockY);
            if (!Float.isNaN(bottom) && bottom < minY) minY = bottom;

            // resolveWeight, matching the scanner and countWeightedBlocks. props.hasWeight() selects the
            // same set today only because an unlisted material synthesises a non-null 0; using the same
            // predicate everywhere keeps meanDensity's divisor and this one from drifting apart.
            if (configManager.resolveWeight(block.getType(), block.getBlockData()) != null) {
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

        // Calculate ship bounds and weighted block count (same logic as BlockStructureScanner —
        // bottomFaceY and resolveWeight, so the shulker marks the waterline the ship will actually
        // float at rather than one derived from raw block Ys).
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        float minY = Float.MAX_VALUE;
        float sumY = 0;
        int weightedBlockCount = 0;

        for (Location loc : shipBlocks) {
            Block block = loc.getBlock();

            float blockY = (float) (loc.getY() - wheelLoc.getY());
            float bottom = BlockStructureScanner.bottomFaceY(block.getBlockData(), blockY);
            if (!Float.isNaN(bottom) && bottom < minY) minY = bottom;

            if (configManager.resolveWeight(block.getType(), block.getBlockData()) != null) {
                weightedBlockCount++;
                sumY += blockY;
            }
        }

        // Default bounds if no blocks
        if (minY == Float.MAX_VALUE) minY = 0;

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
            s.customName(net.kyori.adventure.text.Component.empty());
            s.setCustomNameVisible(false);
            s.setSilent(true);
            s.setPersistent(false);  // Don't save to world
            s.setCollidable(false);
            s.setPeek(0.0f);  // Closed shell
            // Set scale to 0.25 (quarter size)
            org.bukkit.attribute.Attribute scaleAttribute = anon.def9a2a4.blockships.util.AttributeCompat.getScale();
            if (scaleAttribute != null) {
                var scaleAttr = s.getAttribute(scaleAttribute);
                if (scaleAttr != null) {
                    scaleAttr.setBaseValue(0.25);
                }
            }
        });

        wheelData.setWaterlineShulker(shulker);
    }

    /**
     * Starts a repeating task to spawn particles on detected blocks.
     * Runs for 5 seconds (10 iterations x 0.5s).
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
        final int[] iterationsLeft = {10};  // 10 iterations x 10 ticks = 5 seconds

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

    /**
     * Spawns particles above a seat shulker with randomness.
     * Used for assembled ships to highlight the rideable surface.
     * Spawns at center X/Z, 0.25 blocks above the top of the shulker.
     */
    private void spawnShulkerSeatParticles(Shulker shulker, Color color) {
        Location loc = shulker.getLocation();
        if (loc.getWorld() == null) {
            return;
        }

        // Get shulker scale (default 1.0) to compute actual height
        double scale = 1.0;
        org.bukkit.attribute.Attribute scaleAttribute = anon.def9a2a4.blockships.util.AttributeCompat.getScale();
        if (scaleAttribute != null) {
            var scaleAttr = shulker.getAttribute(scaleAttribute);
            if (scaleAttr != null) {
                scale = scaleAttr.getBaseValue();
            }
        }

        // Shulker base height is 1.0 block, scaled by the scale attribute
        double shulkerHeight = 1.0 * scale;
        double particleY = loc.getY() + shulkerHeight + 0.25;

        Particle.DustOptions dustOptions = new Particle.DustOptions(color, 1.2f);

        // Spawn multiple particles above the shulker with randomness
        loc.getWorld().spawnParticle(
            Particle.DUST,
            loc.getX(),
            particleY,
            loc.getZ(),
            8,                          // Count - spawn multiple
            0.25,                       // X spread (randomness)
            0.25,                       // Y spread
            0.25,                       // Z spread (randomness)
            0,                          // Speed
            dustOptions
        );
    }

    /**
     * Highlights seat positions with particles.
     * For unassembled ships: uses detected seat block locations (corner particles).
     * For assembled ships: uses seat shulker entity locations (center-top with randomness).
     *
     * @param player The player who triggered the action
     * @param wheelData The ship wheel data
     */
    public void highlightSeats(Player player, ShipWheelData wheelData) {
        // Cancel any existing particle task
        wheelData.cancelParticleTask();

        if (wheelData.isAssembled()) {
            // Delegate to ShipInstance version for assembled ships
            ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
            if (ship == null) {
                player.sendMessage("§cShip not found!");
                return;
            }
            highlightSeats(player, ship);
        } else {
            // Unassembled: use block locations with corner particles
            Set<Location> seatBlocks = new HashSet<>();
            Location driverSeat = wheelData.getLastDetectedDriverSeat();
            Set<Location> detected = wheelData.getLastDetectedSeatBlocks();

            if ((detected == null || detected.isEmpty()) && driverSeat == null) {
                player.sendMessage("§eNo seats detected. Click 'Show Ship' first.");
                return;
            }

            if (detected != null) {
                seatBlocks.addAll(detected);
            }

            int totalSeats = seatBlocks.size() + (driverSeat != null ? 1 : 0);
            player.sendMessage("§aHighlighting " + totalSeats + " seat(s) for 5 seconds...");

            final Set<Location> finalSeatBlocks = seatBlocks;
            final Location finalDriverSeat = driverSeat;
            final int[] iterationsLeft = {10};

            BukkitRunnable particleTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (iterationsLeft[0] <= 0) {
                        wheelData.cancelParticleTask();
                        this.cancel();
                        return;
                    }

                    for (Location blockLoc : finalSeatBlocks) {
                        spawnBlockParticles(blockLoc, PARTICLE_ORANGE);
                    }

                    if (finalDriverSeat != null) {
                        spawnBlockParticles(finalDriverSeat, PARTICLE_RED);
                    }

                    iterationsLeft[0]--;
                }
            };

            wheelData.setParticleTask(particleTask.runTaskTimer(plugin, 0L, 10L));
        }
    }

    /**
     * Highlights seat positions with particles for an assembled ship.
     * Used by the /blockships highlightseats command.
     * Spawns particles at center-top of seat shulkers with randomness.
     *
     * @param player The player who triggered the action
     * @param ship The ship instance to highlight seats on
     */
    public void highlightSeats(Player player, ShipInstance ship) {
        List<Shulker> passengerSeats = new ArrayList<>();
        Shulker driverSeatShulker = null;

        for (int i = 0; i < ship.seatShulkers.size(); i++) {
            Shulker seat = ship.seatShulkers.get(i);
            if (seat != null && seat.isValid()) {
                if (i == 0) {
                    driverSeatShulker = seat;
                } else {
                    passengerSeats.add(seat);
                }
            }
        }

        if (driverSeatShulker == null && passengerSeats.isEmpty()) {
            player.sendMessage("§eNo seats found on this ship.");
            return;
        }

        int totalSeats = passengerSeats.size() + (driverSeatShulker != null ? 1 : 0);
        player.sendMessage("§aHighlighting " + totalSeats + " seat(s) for 5 seconds...");

        final List<Shulker> finalPassengerSeats = passengerSeats;
        final Shulker finalDriverSeat = driverSeatShulker;
        final int[] iterationsLeft = {10};  // 10 iterations x 10 ticks = 5 seconds

        BukkitRunnable particleTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (iterationsLeft[0] <= 0) {
                    this.cancel();
                    return;
                }

                // Spawn orange particles on passenger seat shulkers
                for (Shulker seat : finalPassengerSeats) {
                    if (seat.isValid()) {
                        spawnShulkerSeatParticles(seat, PARTICLE_ORANGE);
                    }
                }

                // Spawn red particles on driver seat shulker
                if (finalDriverSeat != null && finalDriverSeat.isValid()) {
                    spawnShulkerSeatParticles(finalDriverSeat, PARTICLE_RED);
                }

                iterationsLeft[0]--;
            }
        };

        // Run every 10 ticks (0.5 seconds)
        particleTask.runTaskTimer(plugin, 0L, 10L);
    }
}
