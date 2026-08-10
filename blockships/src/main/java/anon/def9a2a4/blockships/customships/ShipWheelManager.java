package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.ShipConfig;
import anon.def9a2a4.blockships.ShipCustomization;
import anon.def9a2a4.blockships.ship.CollisionBox;
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
import java.util.*;

/**
 * Manages placed ship wheels in the world.
 * Handles the assembly/disassembly of custom ships from ship wheel blocks.
 */
public class ShipWheelManager {
    private static final String WHEELS_FILE = "ship_wheels.yml";

    private final JavaPlugin plugin;
    private final Map<String, ShipWheelData> placedWheels;  // Location key -> wheel data

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
    public boolean saveAll() {
        File wheelsFile = new File(plugin.getDataFolder(), WHEELS_FILE);
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();

        List<Map<String, Object>> wheelList = new ArrayList<>();
        for (ShipWheelData data : placedWheels.values()) {
            wheelList.add(data.toMap());
        }
        config.set("wheels", wheelList);

        try {
            config.save(wheelsFile);
            plugin.getLogger().info("Saved " + wheelList.size() + " ship wheels to " + WHEELS_FILE);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save ship wheels: " + e.getMessage());
            return false;
        }
    }

    /**
     * Loads all ship wheels from ship_wheels.yml.
     */
    public void loadAll() {
        File wheelsFile = new File(plugin.getDataFolder(), WHEELS_FILE);
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
        int failed = 0;

        for (Map<?, ?> map : wheelList) {
            try {
                @SuppressWarnings("unchecked")
                ShipWheelData data = ShipWheelData.fromMap((Map<String, Object>) map);
                if (data != null) {
                    placedWheels.put(locationKey(data.getBlockLocation()), data);
                    loaded++;
                } else {
                    failed++;  // World doesn't exist
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load ship wheel: " + e.getMessage());
                failed++;
            }
        }

        plugin.getLogger().info("Loaded " + loaded + " ship wheels" + (failed > 0 ? " (" + failed + " failed)" : ""));
    }

    /**
     * Creates a stable string key from a Location using block coordinates.
     * Avoids floating-point precision issues with Location as HashMap key.
     */
    private static String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    /**
     * Registers a ship wheel at the given location with its facing direction.
     * The block itself should already be placed by the event handler.
     */
    public boolean placeWheel(Location location, BlockFace facing) {
        // Create and store wheel data
        ShipWheelData wheelData = new ShipWheelData(location, facing);
        placedWheels.put(locationKey(location), wheelData);
        return true;
    }

    /**
     * Removes a ship wheel at the given location.
     */
    public void removeWheel(Location location) {
        ShipWheelData wheelData = placedWheels.remove(locationKey(location));
        if (wheelData != null) {
            // If assembled, destroy the ship too
            if (wheelData.isAssembled()) {
                ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
                if (ship != null) {
                    ship.destroy();
                }
            }
        }
    }

    /**
     * Breaks a ship wheel block after the ship has already been disassembled.
     * Removes from tracking, drops the wheel item, and sets block to air.
     * Use this instead of removeWheel() when the ship is already destroyed/disassembled.
     */
    public void breakWheelBlock(Location location) {
        ShipWheelData wheelData = placedWheels.remove(locationKey(location));
        if (wheelData == null) return;

        // Drop ship wheel item
        org.bukkit.World world = location.getWorld();
        if (world != null && plugin instanceof BlockShipsPlugin bsp) {
            ItemStack wheelItem = bsp.getDisplayShip().createShipWheelItem();
            world.dropItemNaturally(location.clone().add(0.5, 0.5, 0.5), wheelItem);
            location.getBlock().setType(Material.AIR);
        }

        saveAll();
    }

    /**
     * Removes a ship wheel block without dropping the wheel item.
     * Used when a ship is fully destroyed so the wheel is lost along with the ship.
     */
    public void destroyWheelBlock(Location location) {
        ShipWheelData wd = placedWheels.remove(locationKey(location));
        if (wd == null) return;
        if (location.getWorld() != null) {
            location.getBlock().setType(Material.AIR);
        }
        saveAll();
    }

    /**
     * Gets wheel data at a location, if it exists.
     */
    public ShipWheelData getWheelAt(Location location) {
        return placedWheels.get(locationKey(location));
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

    /** True if this id is in BlockShips' persisted chunk index (any world). Mirrors {@code collectPersistedShipIds}. */
    private boolean isPersistedShip(UUID id) {
        if (!(plugin instanceof BlockShipsPlugin bsp)) return false;
        var ds = bsp.getDisplayShip();
        if (ds == null) return false;
        return ds.getShipWorldData().getAllPersistedShipIds().contains(id);
    }

    /**
     * Self-heal a wheel that resolved {@link WheelState#ORPHAN}: clear the stale link (persisting it) and reap
     * ONLY the BlockShips-owned orphan root vehicle ({@code ShipTags.shipRootTag}). Never sweeps
     * {@code corelib:mech:*} — defCoreLib owns and reaps those itself, guarded by its own recovery/persistence
     * latches BlockShips cannot replicate.
     */
    private void reconcileOrphan(ShipWheelData wheel) {
        UUID uuid = wheel.getAssembledShipUUID();
        wheel.setAssembledShipUUID(null);
        saveAll();
        if (uuid == null) return;
        Location near = wheel.getBlockLocation();
        if (near == null || near.getWorld() == null) return;
        String rootTag = anon.def9a2a4.blockships.ShipTags.shipRootTag(uuid);
        for (org.bukkit.entity.Entity e : near.getWorld().getEntities()) {
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
     * Updates the tracked location of a wheel after disassembly at a new position.
     * Removes old map entry and adds new one.
     */
    private void updateWheelLocation(ShipWheelData wheelData, Location newLocation, BlockFace newFacing) {
        Location oldLocation = wheelData.getBlockLocation();
        placedWheels.remove(locationKey(oldLocation));
        wheelData.updateBlockLocation(newLocation, newFacing);
        placedWheels.put(locationKey(newLocation), wheelData);
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

        Location wheelLoc = wheelData.getBlockLocation();

        // Scan the structure. Returns the derived model PLUS the live world blocks in parts-index order
        // (still in the world — air-out is deferred), so the delegated assembler gets block-index parity.
        BlockStructureScanner.ScanResult scan = BlockStructureScanner.scanStructure(wheelLoc, wheelData.getFacing());
        if (scan == null || scan.model().parts.isEmpty()) {
            player.sendMessage("§cNo valid ship structure found!");
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
            Location loc = ship.vehicle.getLocation();
            ShipWorldData shipWorldData = bsp.getDisplayShip().getShipWorldData();
            shipWorldData.saveShipMetadataAsync(ship);
            shipWorldData.addToChunkIndex(loc.getWorld(), ship.id,
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
            shipWorldData.saveAllChunkIndicesAsync();
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
            model.mass, model.woolCount, model.bannerCount);
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

        // Sync current storage inventories back to model before placing blocks
        Map<Integer, Inventory> currentStorages = ship.storages;
        for (Map.Entry<Integer, Inventory> entry : currentStorages.entrySet()) {
            int blockIndex = entry.getKey();
            Inventory inv = entry.getValue();

            if (blockIndex >= 0 && blockIndex < model.parts.size()) {
                ShipModel.ModelPart part = model.parts.get(blockIndex);
                List<Map<String, Object>> itemsData = new ArrayList<>();

                for (int slot = 0; slot < inv.getSize(); slot++) {
                    ItemStack item = inv.getItem(slot);
                    if (item != null && item.getType() != Material.AIR) {
                        Map<String, Object> itemData = new HashMap<>();
                        itemData.put("slot", slot);
                        itemData.put("item", item.serializeAsBytes());
                        itemsData.add(itemData);
                    }
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> yaml = (Map<String, Object>) part.rawYaml;
                yaml.put("container_items", itemsData);
            }
        }

        // WorldGuard: decide ONCE whether the wheel-anchor cell is protected, and pass it into placeBlocks
        // so the head-skip there and the deregister below use the same answer (they can never disagree).
        Location newWheelLocation = shipLoc.getBlock().getLocation();
        // Admin toggle: for unattended/system paths (player == null) that opt into place-anyway, treat the
        // world as region-free so blocks (and the wheel anchor) are placed normally instead of dropped.
        boolean wgOn = anon.def9a2a4.blockships.integration.WorldGuardHook.get().mightRestrict(newWheelLocation.getWorld())
            && !(player == null && anon.def9a2a4.blockships.integration.WorldGuardHook.get().systemPathPlacesInRegions());
        boolean anchorProtected = wgOn && force
            && anon.def9a2a4.blockships.integration.WorldGuardHook.get().isBuildDenied(newWheelLocation, player);

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
            // fences, after blocks land but before the colliders are removed (mirror transferLeadsFromShip).
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
        } else {
            // Track W (W3): block placement is all-or-abort — on throw leave the ship intact + registered so the
            // player can retry (do NOT destroy/clear the wheel; that would lose the structure). Fail loud.
            try {
                BlockStructureScanner.placeBlocks(shipLoc, model, currentYaw, force, player, anchorProtected);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Disassembly (placeBlocks) failed for ship " + ship.id + " — ship left intact for retry", t);
                if (player != null) player.sendMessage(
                    "§cDisassembly failed — the ship is intact; please try again. (see server logs)");
                return false;
            }

            // Leads are best-effort now that blocks are placed — a lead failure must NOT abort (that would leave
            // placed blocks AND a live ship = duplication).
            try {
                transferLeadsFromShip(ship, model, shipLoc, currentYaw);
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Lead transfer failed during disassembly of " + ship.id + " (blocks already placed)", t);
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
            placedWheels.remove(locationKey(wheelData.getBlockLocation()));
            saveAll();
        } else {
            float rotationDelta = currentYaw - model.assemblyYaw;
            BlockFace newFacing = BlockStructureScanner.rotateBlockFace(wheelData.getFacing(), rotationDelta);
            updateWheelLocation(wheelData, newWheelLocation, newFacing);
        }

        // Destroy the ship
        Location shipLoc2 = ship.vehicle.getLocation();
        ship.destroy();

        // Nudge nearby players up to prevent clipping into placed blocks
        nudgeNearbyPlayersUp(shipLoc2, ship.config.assemblyNudgeHeight);

        // Remove ship from per-world storage (delete file and chunk index). The ship is already
        // gone from the world at this point, so a save failure here does not fail the disassembly -
        // it is reported separately via outcome.persistFailed (and logged SEVERE by the callees).
        if (plugin instanceof BlockShipsPlugin bsp) {
            org.bukkit.World world = shipLoc.getWorld();
            if (world != null) {
                ShipWorldData worldData = bsp.getDisplayShip().getShipWorldData();
                boolean fileOk = worldData.removeShip(world, ship.id);
                boolean indexOk = worldData.saveAllChunkIndices();
                if (!fileOk || !indexOk) {
                    outcome.persistFailed = true;
                    plugin.getLogger().severe("disassembleShip: persistence cleanup failed for ship "
                        + ship.id + " (world=" + world.getName() + ")");
                }
            } else {
                // Can't resolve the world, so the per-world YAML / chunk index can't be cleaned. Flag
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
     * Transfers leads from ship's shulkers to fence blocks (via LeashHitch).
     * Called during disassembly after blocks are placed but before ship is destroyed.
     *
     * @param ship The ship instance being disassembled
     * @param model The ship model containing leadable block info
     * @param shipLoc The ship's current location (used as origin for block positions)
     * @param currentYaw The ship's current yaw rotation
     */
    private void transferLeadsFromShip(ShipInstance ship, ShipModel model, Location shipLoc, float currentYaw) {
        // Calculate rotation delta from assembly orientation
        float rotationDelta = currentYaw - model.assemblyYaw;
        while (rotationDelta < 0) rotationDelta += 360;
        while (rotationDelta >= 360) rotationDelta -= 360;

        // Iterate through colliders to find leadable shulkers with attached entities
        for (CollisionBox collider : ship.colliders) {
            int blockIndex = collider.blockIndex;

            // Check if this block is leadable
            if (blockIndex < 0 || blockIndex >= model.parts.size()) {
                continue;
            }
            ShipModel.ModelPart part = model.parts.get(blockIndex);
            if (!part.rawYaml.containsKey("leadable") || !Boolean.TRUE.equals(part.rawYaml.get("leadable"))) {
                continue;
            }

            Shulker shulker = collider.entity;
            if (shulker == null || !shulker.isValid()) {
                continue;
            }

            // Find entities leashed to this shulker
            List<org.bukkit.entity.Entity> leashedEntities = findEntitiesLeashedTo(shulker);
            if (leashedEntities.isEmpty()) {
                continue;
            }

            // Calculate the fence block's world location (apply rotation like placeBlocks does)
            org.joml.Vector3f pos = new org.joml.Vector3f();
            part.local.getTranslation(pos);
            org.joml.Vector3f rotatedPos = BlockStructureScanner.rotatePosition(pos, rotationDelta);
            // Round to match placeBlocks (BlockStructureScanner:775-781): getBlock() below floors, so an
            // unrounded rotated coord that landed just under an integer (cos(90 deg)~6e-17 error) would put the
            // LeashHitch one block off the fence and the lead would pop off on a rotated ship's disassembly.
            Location fenceLoc = shipLoc.clone().add(
                Math.round(rotatedPos.x), Math.round(rotatedPos.y), Math.round(rotatedPos.z));

            // Spawn LeashHitch at the fence block
            org.bukkit.entity.LeashHitch hitch = fenceLoc.getWorld().spawn(
                fenceLoc.getBlock().getLocation().add(0.5, 0.5, 0.5),
                org.bukkit.entity.LeashHitch.class
            );

            // Transfer each leashed entity to the LeashHitch
            for (org.bukkit.entity.Entity entity : leashedEntities) {
                // Entity is guaranteed to be Leashable from findEntitiesLeashedTo
                ((io.papermc.paper.entity.Leashable) entity).setLeashHolder(hitch);
            }
        }
    }

    /**
     * Delegated (M1) leads-out — the {@link Mechanism#setBeforeEntityRemoval} callback body. Identical to
     * {@link #transferLeadsFromShip} except the collider shulker for a block index comes from the mechanism
     * ({@code mech.colliderEntity(i)}) rather than the native {@code ship.colliders} list (empty for a
     * delegated ship). Runs after blocks land but before the mechanism's collider entities are removed.
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
                    ship.model.mass, ship.model.woolCount, ship.model.bannerCount);
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
                if (config.statsEnabled) {
                    int mass = Math.max(1, ship.model.mass);
                    int sailPower = ship.model.woolCount * config.woolPower + ship.model.bannerCount * config.bannerPower;
                    float sailRatio = (float) (config.basePower + sailPower) / mass;
                    float nonEngineRatio = Math.min(sailRatio, config.sailCapRatio);
                    float ratio = Math.min(nonEngineRatio, 1.0f);
                    int speedPercent = config.sailCapRatio > 0
                        ? Math.round(ratio / config.sailCapRatio * 100) : Math.round(ratio * 100);

                    player.sendMessage("§7Sails: §f" + ship.model.woolCount + " wool, " + ship.model.bannerCount + " banners §7(" + sailPower + " pts)");
                    String speedColor = speedPercent >= 125 ? "§b" : speedPercent >= 100 ? "§a" : speedPercent >= 75 ? "§e" : speedPercent >= 50 ? "§6" : "§c";
                    player.sendMessage("§7Speed: " + speedColor + speedPercent + "%");
                } else {
                    player.sendMessage("§7Stats: §8disabled");
                }

                return true;
            }
        }

        // Get max ship size from config
        int maxShipSize = ((BlockShipsPlugin) plugin).getConfig().getInt("custom-ships.max-ship-size", 1000);
        int maxScanSize = ((BlockShipsPlugin) plugin).getConfig().getInt("custom-ships.max-scan-size", 5000);

        // Run ship detection
        ShipDetector detector = new ShipDetector(maxShipSize, maxScanSize);
        ShipDetector.ShipDetectionResult result = detector.detectShipDetailed(wheelLoc);

        if (!result.isSuccess()) {
            // Detection failed - ship too large or other error
            player.sendMessage("§c" + result.getMessage());
            return false;
        }

        Set<Location> shipBlocks = result.getBlocks();
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
            int sailPower = woolCount * config.woolPower + bannerCount * config.bannerPower;
            int shipMass = Math.max(1, calculateMass(shipBlocks));
            float sailRatio = (float) (config.basePower + sailPower) / shipMass;
            float nonEngineRatio = Math.min(sailRatio, config.sailCapRatio);
            float ratio = Math.min(nonEngineRatio, 1.0f);
            int speedPercent = config.sailCapRatio > 0
                ? Math.round(ratio / config.sailCapRatio * 100) : Math.round(ratio * 100);
            player.sendMessage("§7Sails: §f" + woolCount + " wool, " + bannerCount + " banners §7(" + sailPower + " pts)");
            String speedColor = speedPercent >= 125 ? "§b" : speedPercent >= 100 ? "§a" : speedPercent >= 75 ? "§e" : speedPercent >= 50 ? "§6" : "§c";
            player.sendMessage("§7Speed: " + speedColor + speedPercent + "%" + (speedPercent < 50 ? " §8(add banners or wool as sails!)" : ""));
        } else {
            player.sendMessage("§7Stats: §8disabled");
        }

        // Store detected blocks and stats for Ship Info display
        int positiveWeight = calculateMass(shipBlocks);
        wheelData.setLastDetectedBlocks(shipBlocks);
        wheelData.setLastDetectedStats(blockCount, weightedBlockCount, totalWeight, positiveWeight, woolCount, bannerCount);
        wheelData.setLastDetectedBlockCategories(regularBlocks, seatBlocks, driverSeat);

        // Calculate and store buoyancy data for Ship Info display
        calculateAndStoreBuoyancyData(wheelData, shipBlocks, totalWeight, weightedBlockCount);

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
     * Calculate the total weight of all blocks in the ship.
     */
    private int calculateTotalWeight(Set<Location> blocks) {
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        int totalWeight = 0;

        for (Location loc : blocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());
            totalWeight += props.getWeight();
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
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());
            if (props.hasWeight()) {
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
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());
            if (props.hasWeight()) {
                int w = props.getWeight();
                if (w > 0) {
                    positiveWeight += w;
                }
            }
        }

        return positiveWeight;
    }

    /**
     * Calculate and store buoyancy data (centerOfVolumeY, minY, surfaceOffset) for Ship Info display.
     */
    private void calculateAndStoreBuoyancyData(ShipWheelData wheelData, Set<Location> shipBlocks, int totalWeight, int weightedBlockCount) {
        Location wheelLoc = wheelData.getBlockLocation();
        BlockConfigManager configManager = BlockConfigManager.getInstance();

        float minY = Float.MAX_VALUE;
        float sumY = 0;
        int weightedCount = 0;

        for (Location loc : shipBlocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());

            float blockY = (float) (loc.getY() - wheelLoc.getY());
            if (blockY < minY) minY = blockY;

            if (props.hasWeight()) {
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

        // Calculate ship bounds and weighted block count (same logic as BlockStructureScanner)
        BlockConfigManager configManager = BlockConfigManager.getInstance();
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;
        float sumY = 0;
        int weightedBlockCount = 0;

        for (Location loc : shipBlocks) {
            Block block = loc.getBlock();
            BlockProperties props = configManager.getProperties(block.getType(), block.getBlockData());

            float blockY = (float) (loc.getY() - wheelLoc.getY());
            if (blockY < minY) minY = blockY;
            if (blockY > maxY) maxY = blockY;

            if (props.hasWeight()) {
                weightedBlockCount++;
                sumY += blockY;
            }
        }

        // Default bounds if no blocks
        if (minY == Float.MAX_VALUE) minY = 0;
        if (maxY == Float.MIN_VALUE) maxY = 0;

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
                player.sendMessage("§eNo seats detected. Click 'Detect Ship' first.");
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
