package anon.def9a2a4.blockships.customships;

import org.bukkit.Location;
import org.bukkit.block.Block;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bridge to defCoreLib's glue system for ship wheels.
 *
 * <p>Glue lets a player attach blocks a ship could never otherwise carry — dirt, stone, grass —
 * because {@code blocks.yml} is a pure allow-list. The glued cells are stored by defCoreLib in the
 * wheel skull's own PDC, ride with the mechanism while the ship is assembled (captured before
 * air-out, serialized with the mechanism so they survive a restart mid-voyage), and are re-stamped
 * rotated onto the relocated wheel at landing.
 *
 * <p><b>BlockShips deliberately keeps no copy of the offsets.</b> Two sources of truth, rotated by
 * two different code paths, is exactly how glue ends up bound to the wrong real blocks. Always ask
 * the engine.
 */
public final class ShipGlue {

    private ShipGlue() {}

    /**
     * The wheel's glued cells that are present in the world right now, or an empty set when the wheel
     * has no glue (or defCoreLib is somehow unavailable). Cells whose block is gone are already
     * filtered out by the engine, as is the derived sticky closure's bookkeeping.
     */
    public static Set<Location> gluedCells(Block wheelBlock) {
        if (wheelBlock == null) return Collections.emptySet();
        List<Block> resolved;
        try {
            resolved = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().resolveGlue(wheelBlock);
        } catch (Throwable t) {
            // A CoreLib fault must never make a ship unassemblable — degrade to "no glue".
            return Collections.emptySet();
        }
        if (resolved == null || resolved.isEmpty()) return Collections.emptySet();
        Set<Location> out = new HashSet<>(resolved.size() * 2);
        for (Block b : resolved) out.add(b.getLocation());
        return out;
    }

    /**
     * The wheel's RAW authored glue cells — one per stored offset, resolved against the wheel's current
     * position — WITHOUT the derived sticky closure. This is the frozen-membership source for a locked ship:
     * unlike {@link #gluedCells} it never pulls in adjacent slime/honey (which would let a locked ship absorb
     * new world blocks) and never drops stored slime/honey members. Does not include the wheel itself.
     */
    public static List<Location> rawGlueCells(Block wheelBlock) {
        if (wheelBlock == null) return Collections.emptyList();
        int[] offs;
        try {
            offs = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().readGlueOffsets(wheelBlock);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        if (offs == null || offs.length < 3) return Collections.emptyList();
        int ox = wheelBlock.getX(), oy = wheelBlock.getY(), oz = wheelBlock.getZ();
        org.bukkit.World w = wheelBlock.getWorld();
        List<Location> out = new java.util.ArrayList<>(offs.length / 3);
        for (int i = 0; i + 2 < offs.length; i += 3) {
            out.add(new Location(w, ox + offs[i], oy + offs[i + 1], oz + offs[i + 2]));
        }
        return out;
    }

    /** Count of raw authored glue offsets on the wheel (0 if none / unavailable). */
    public static int glueCount(Block wheelBlock) {
        if (wheelBlock == null) return 0;
        try {
            int[] offs = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().readGlueOffsets(wheelBlock);
            return offs == null ? 0 : offs.length / 3;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Overwrite the wheel's glue offsets with exactly {@code cells} (world-axis offsets relative to the wheel;
     * the wheel's own cell is never stored). Used to materialize a locked hull into glue and to prune it back to
     * manual-only on unlock. The engine does NO cap check on this path — callers must gate on {@link #maxSize}.
     */
    public static void writeCells(Block wheelBlock, java.util.Collection<Location> cells) {
        if (wheelBlock == null) return;
        int ox = wheelBlock.getX(), oy = wheelBlock.getY(), oz = wheelBlock.getZ();
        java.util.List<Integer> tmp = new java.util.ArrayList<>(cells.size() * 3);
        for (Location c : cells) {
            int dx = c.getBlockX() - ox, dy = c.getBlockY() - oy, dz = c.getBlockZ() - oz;
            if (dx == 0 && dy == 0 && dz == 0) continue;   // never store the anchor itself
            tmp.add(dx); tmp.add(dy); tmp.add(dz);
        }
        int[] offs = new int[tmp.size()];
        for (int i = 0; i < offs.length; i++) offs[i] = tmp.get(i);
        try {
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().writeGlueOffsets(wheelBlock, offs);
        } catch (Throwable ignored) {
        }
    }

    /** The engine's glue-selection cap (max cells a single anchor can hold). */
    public static int maxSize() {
        try {
            return anon.def9a2a4.corelib.CoreLibPlugin.getInstance().glueMaxSize();
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    /** Drop every glued cell from a wheel — used when the wheel block is destroyed. */
    public static void clear(Block wheelBlock) {
        if (wheelBlock == null) return;
        try {
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().clearGlue(wheelBlock);
        } catch (Throwable ignored) {
            // Best-effort: breaking the wheel destroys the skull tile entity and its PDC anyway.
        }
    }
}
