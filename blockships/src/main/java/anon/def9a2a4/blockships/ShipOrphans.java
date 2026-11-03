package anon.def9a2a4.blockships;

import org.bukkit.entity.Entity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Recovery for <b>orphans</b>: a defCoreLib mechanism that is alive and holding a ship's blocks, but has no
 * {@link anon.def9a2a4.blockships.ship.ShipInstance} wrapped around it — because its {@code ships/{id}.yml}
 * sidecar was lost, corrupt, or never written.
 *
 * <p>Before this, an orphan was a dead end in both directions: {@code forcedisassembleall} skipped it, and
 * breaking its wheel deregistered the wheel while leaving the mechanism holding the blocks. The blocks were
 * either stranded forever or reaped. Landing them is strictly better than either.
 *
 * <p>Lives outside {@code ShipWheelManager} on purpose — that class is already large, and both of its callers
 * are in files being rewritten around it.
 */
public final class ShipOrphans {

    private ShipOrphans() {}

    /** Ids currently being torn down, so a re-entrant call cannot double-handle one. */
    private static final Set<UUID> inFlight = new HashSet<>();

    /** What {@link #disassembleOrphan} did. */
    public enum Outcome {
        /** Blocks were returned to the world. */
        LANDED,
        /** A block-free (prefab) mechanism: defCoreLib discards it rather than landing anything. */
        DISCARDED_BLOCK_FREE,
        /** No live mechanism with that id — nothing to do here. */
        NOT_LIVE
    }

    /**
     * Land an orphaned mechanism's blocks and clean up after it.
     *
     * <p>Three defCoreLib behaviours this has to respect, each of which bites if ignored:
     * <ul>
     *   <li>A {@code blockFree} mechanism short-circuits to {@code destroy()} and lands <b>nothing</b>. Callers
     *       must not report those as recovered.</li>
     *   <li>{@code disassemble()} sets its idempotency latch <i>before</i> doing the work and relies on a
     *       {@code finally} to deregister, so a mid-teardown throw still leaves the mechanism gone. Treat a
     *       throw as "it is gone" and continue cleaning up rather than aborting.</li>
     *   <li>A <i>borrowed</i> vehicle (which is what BlockShips uses — the ArmorStand corelib spawned and
     *       BlockShips adopted) survives disassembly and must be removed separately. An <i>owned</i> one is
     *       removed by corelib a tick later, deliberately, to hide landing flicker. There is no public
     *       accessor to tell them apart, so the removal is deferred a tick and re-checks {@code isValid()}.</li>
     * </ul>
     */
    public static Outcome disassembleOrphan(BlockShipsPlugin plugin, UUID id) {
        if (id == null || !inFlight.add(id)) return Outcome.NOT_LIVE;
        try {
            anon.def9a2a4.corelib.MechanismRegistry reg =
                anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getMechanismRegistry();
            anon.def9a2a4.corelib.Mechanism mech = reg.byId(id);
            if (mech == null) return Outcome.NOT_LIVE;

            boolean blockFree = isBlockFree(mech);
            Entity vehicle = mech.vehicle();
            org.bukkit.World world = mech.pivot() != null ? mech.pivot().getWorld() : null;

            try {
                mech.disassemble();
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Orphan disassembly threw for mechanism " + id + " — it is deregistered either way; "
                        + "some blocks may be missing", t);
            }

            if (vehicle != null) {
                // One tick later: corelib defers its own entity teardown by a tick for owned vehicles, and
                // removing it underneath that is how you get a half-torn-down mechanism.
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    if (vehicle.isValid()) vehicle.remove();
                });
            }

            // Purge whatever sidecar remains. Best-effort: a corrupt one is exactly why we are here.
            if (world != null && plugin.getDisplayShip() != null) {
                plugin.getDisplayShip().getShipWorldData().removeShip(world, id);
            }

            return blockFree ? Outcome.DISCARDED_BLOCK_FREE : Outcome.LANDED;
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                "Could not disassemble orphan mechanism " + id, t);
            return Outcome.NOT_LIVE;
        } finally {
            inFlight.remove(id);
        }
    }

    /**
     * True if disassembling this mechanism would discard it rather than land blocks.
     *
     * <p>Keyed on the mechanism TYPE, not on {@code blockCount()}. corelib's {@code blockFree} flag is the
     * thing that actually decides (it short-circuits {@code disassemble()} to {@code destroy()}), but
     * {@code isBlockFree()} is package-private and {@code blockCount()} is a different number — the count fed
     * to the rotation network, which is non-zero for a prefab whose parts carry no world blocks. BlockShips
     * only ever assembles two types, and the split is exactly along this line: {@code blockship:custom} is
     * built from real world blocks, {@code blockship:prefab} is a model with none.
     */
    private static boolean isBlockFree(anon.def9a2a4.corelib.Mechanism mech) {
        try {
            return !"blockship:custom".equals(mech.type());
        } catch (Throwable t) {
            return false;  // unknown — assume it has blocks, the non-destructive assumption
        }
    }
}
