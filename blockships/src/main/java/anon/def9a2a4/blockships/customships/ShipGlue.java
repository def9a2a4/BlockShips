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
