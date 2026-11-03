package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.blockconfig.ShipDetector;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Makes a ship wheel a defCoreLib glue anchor, so the Glue Brush works on it and glued blocks ride
 * with the ship.
 *
 * <p>A wheel is a plain {@code PLAYER_HEAD} with no defCoreLib custom-block identity, so the engine's
 * own anchor-id matching can never see it — hence the provider hook. Everything downstream (brush
 * authoring, the particle outline, cuboid fill, capture at assembly, persistence across a restart,
 * the rotated rebind at landing) then works unchanged, because the offsets live in the wheel skull's
 * PDC exactly like every engine anchor's.
 */
public final class ShipWheelAnchors {

    private static final String PLUGIN_ID = "BlockShips";

    private ShipWheelAnchors() {}

    /**
     * Cached hull cells per wheel, used as glue connectivity. Without this, defCoreLib would only
     * accept a glued block cardinally adjacent to the wheel itself — useless on a real ship, where
     * the whole point is to glue something to the bow.
     *
     * <p>Recomputing is a full flood fill, and defCoreLib asks per authoring click, so the result is
     * cached with a short TTL. A stale entry can only ever refuse a far-side glue for a few seconds;
     * it can never cause a wrong one, because connectivity only ever widens what is accepted.
     */
    private static final long CONNECTOR_TTL_MS = 10_000L;

    private record CachedConnectors(Set<Location> cells, long stamp) {}

    private static final Map<String, CachedConnectors> CONNECTOR_CACHE = new HashMap<>();

    public static void register(BlockShipsPlugin plugin) {
        ShipWheelManager manager = plugin.getShipWheelManager();
        if (manager == null) return;
        try {
            // This lambda deliberately has NO plugin.isEnabled() guard, unlike the leads-in listener in
            // ShipWheelManager's constructor — the asymmetry mirrors corelib's asymmetric registration
            // APIs, not an oversight. registerAnchorProvider REPLACES a same-pluginId provider, so a
            // BlockShips-only reload swaps this lambda for the new instance's on its own; the leads-in
            // listener has no removal or replacement API, so its stale copy lives forever and must
            // self-disarm. And in the brief disabled-not-yet-reenabled window, a null-returning guard
            // here would be actively harmful: corelib answers a null provider result with a plain
            // BlockAnchor whose prunesOnLanding() is true — every landing in that window would DELETE the
            // ship's glue — whereas the dead manager's anchorWheelFor just returns slightly-stale, usable
            // data (world/PDC reads only; its markDirty saves synchronously once disabled).
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().registerAnchorProvider(PLUGIN_ID, block -> {
                ShipWheelData data = manager.anchorWheelFor(block);
                return data == null ? null : new WheelAnchor(plugin, block, data);
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not register the ship-wheel glue anchor with DefCoreLib: "
                + t.getMessage() + " — gluing extra blocks to ships will be unavailable.");
        }
    }

    /** The wheel's current hull, recomputed at most once per {@link #CONNECTOR_TTL_MS}. */
    private static Set<Location> connectors(BlockShipsPlugin plugin, Block wheelBlock) {
        String k = key(wheelBlock);
        long now = System.currentTimeMillis();
        CachedConnectors cached = CONNECTOR_CACHE.get(k);
        if (cached != null && now - cached.stamp() < CONNECTOR_TTL_MS) return cached.cells();

        int maxShipSize = plugin.getConfig().getInt("custom-ships.max-ship-size", 1000);
        int maxScanSize = plugin.getConfig().getInt("custom-ships.max-scan-size", 5000);
        ShipDetector detector = new ShipDetector(maxShipSize, maxScanSize);
        // Silent detect: no particles, no waterline shulker, no chat — bookkeeping, not a player-facing
        // preview. Failure (ship over the size limit) just means no extra connectors, i.e. the engine's
        // ordinary adjacent-to-anchor rule.
        ShipDetector.ShipDetectionResult result =
            detector.detectShipDetailed(wheelBlock.getLocation(), ShipGlue.gluedCells(wheelBlock));
        Set<Location> cells = result.isSuccess() && result.getBlocks() != null
            ? new HashSet<>(result.getBlocks())
            : Collections.emptySet();
        // Sweep before inserting. The TTL was only ever consulted on read, so an entry for a wheel that has
        // since moved or been destroyed was never removed by anything except an explicit forget() — and the
        // key is a cell, so every voyage that lands a wheel somewhere new stranded one. Size-gated so the
        // cost is amortised, and only expired entries go (exactly the set a read would already reject).
        if (CONNECTOR_CACHE.size() > 256) {
            CONNECTOR_CACHE.values().removeIf(c -> now - c.stamp() >= CONNECTOR_TTL_MS);
        }
        CONNECTOR_CACHE.put(k, new CachedConnectors(cells, now));
        return cells;
    }

    /** Drop a wheel's cached connectors — call when the wheel is removed. */
    public static void forget(Block wheelBlock) {
        CONNECTOR_CACHE.remove(key(wheelBlock));
    }

    /**
     * As {@link #forget(Block)}, from a bare cell. For callers that must not resolve a {@code Block} — a
     * {@code getBlock()} on an unloaded chunk force-loads it, and the eviction needs only the key. A dead
     * world (null key) evicts nothing; such an entry can only be reached again through the same dead world,
     * and the sweep in {@code connectors} ages it out regardless.
     */
    public static void forget(Location cell) {
        String k = anon.def9a2a4.blockships.util.LocationUtil.cellKey(cell);
        if (k != null) CONNECTOR_CACHE.remove(k);
    }

    // Byte-identical to LocationUtil.cellKey for a live block — forget(Location) relies on that.
    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    /** The wheel's view of itself as a glue anchor. */
    private record WheelAnchor(BlockShipsPlugin plugin, Block block, ShipWheelData data)
        implements anon.def9a2a4.corelib.ExternalAnchor {

        @Override public Block originBlock() { return block; }

        /**
         * Assembled means the hull is aired out into a mechanism — there is nothing in the world to
         * glue to, and the engine additionally refuses to capture a non-at-rest external anchor into
         * some other mechanism's move.
         */
        @Override public boolean isAtRest() { return !data.isAssembled(); }

        @Override public boolean canAuthor(Player player) {
            // Anchor-level: can you build at the wheel itself.
            return !anon.def9a2a4.blockships.integration.WorldGuardHook.get()
                .isBuildDenied(block.getLocation(), player);
        }

        @Override public boolean canAuthorCell(Player player, Block cell) {
            // The gate that actually matters. Gluing writes no block, so no BlockBreakEvent fires and
            // no region plugin can otherwise see a player parking a wheel beside someone else's build
            // and sailing off with their wall. Mirrors the assembly gate, which already runs this same
            // check per cell.
            return !anon.def9a2a4.blockships.integration.WorldGuardHook.get()
                .isBuildDenied(cell.getLocation(), player);
        }

        @Override public Set<Block> connectorBlocks() {
            // Only meaningful while docked and UNLOCKED. An assembled ship has no hull in the world; a locked
            // ship's members are already glue offsets, so the engine's connects() sees them without extra
            // connectors — and natural spread is frozen, so we must NOT re-flood the allow-list here.
            if (data.isAssembled() || data.isLocked()) return Set.of();
            Set<Location> cells = connectors(plugin, block);
            if (cells.isEmpty()) return Set.of();
            Set<Block> out = new HashSet<>(cells.size() * 2);
            for (Location l : cells) out.add(l.getBlock());
            return out;
        }

        // prunesOnLanding() defaults to false, which is what a ship needs: its glued extras sit on a
        // hull that is not itself glued, so the engine's origin-seeded connectivity prune would
        // delete nearly all of them on the first landing.
    }
}
