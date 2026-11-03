package anon.def9a2a4.blockships;

import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BlockShips' side of the 26.3 cushion (a rideable entity resting on a block). defCoreLib carries a cushion on its
 * block's collider shulker while the ship sails; this class only decides seats and moves players between the
 * cushion and the seat shulker, which is where every seat path (steering, HUD, dismount) reads riders.
 *
 * <p>Calls into defCoreLib's {@code CushionSeats}, which is new: against an older DefCoreLib every method here
 * degrades to "no cushions" instead of throwing. On servers without cushions the answers are empty too.
 */
public final class CushionSupport {

    private CushionSupport() {}

    /**
     * A cushion resting on a ship block. {@code seatIdx} is the block whose collider will carry it — the block
     * the player is seated on: the cushion's own block, or the one below it when that block has no collider
     * (carpets, pressure plates, trapdoors…). {@code cell} is the cushion's own cell (the anchor's cell for a
     * slab/stair, where it sits inside it; the cell above otherwise). Indices are positions in the scanned list.
     */
    public record CushionSeat(int anchorIdx, int seatIdx, Block cell) {}

    /** The cushions resting on {@code blocks} that will carry a seat (a cushion with no collider under it is left
     *  behind by the engine, so it can't be one). */
    public static List<CushionSeat> cushionSeats(List<Block> blocks) {
        List<CushionSeat> out = new ArrayList<>();
        try {
            Map<Block, Entity> found = anon.def9a2a4.corelib.CushionSeats.cushionsOn(blocks);
            if (found.isEmpty()) return out;
            Map<Block, Integer> index = new HashMap<>(blocks.size() * 2);
            for (int i = 0; i < blocks.size(); i++) index.putIfAbsent(blocks.get(i), i);
            for (Map.Entry<Block, Entity> e : found.entrySet()) {
                Integer anchor = index.get(e.getKey());
                Block carrier = anon.def9a2a4.corelib.CushionSeats.carrierBlockFor(e.getKey());
                Integer seat = carrier != null ? index.get(carrier) : null;
                if (anchor != null && seat != null) {
                    out.add(new CushionSeat(anchor, seat, e.getValue().getLocation().getBlock()));
                }
            }
        } catch (LinkageError ignored) {
            // DefCoreLib predates cushion support — no cushion seats
        }
        return out;
    }

    /** True when this server has cushions (26.3+) and defCoreLib supports them. */
    public static boolean available() {
        try {
            return anon.def9a2a4.corelib.CushionSeats.available();
        } catch (LinkageError ignored) {
            return false;
        }
    }

    /** True for a cushion defCoreLib is carrying on a mechanism collider. */
    public static boolean isCarried(Entity e) {
        try {
            return anon.def9a2a4.corelib.CushionSeats.isCarried(e);
        } catch (LinkageError ignored) {
            return false;
        }
    }

    private static Entity carriedCushionOn(Shulker seat) {
        try {
            return anon.def9a2a4.corelib.CushionSeats.carriedCushionOn(seat);
        } catch (LinkageError ignored) {
            return null;
        }
    }

    /**
     * Assembly: a player who was sitting on a cushion is still on it, now riding the seat shulker. Move them onto
     * the shulker itself. Returns the players moved, so the caller can mark their seats occupied.
     */
    public static List<Player> moveCushionRidersOntoSeat(Shulker seat) {
        List<Player> moved = new ArrayList<>();
        Entity cushion = carriedCushionOn(seat);
        if (cushion == null) return moved;
        for (Entity p : new ArrayList<>(cushion.getPassengers())) {
            if (!(p instanceof Player player)) continue;
            if (!cushion.removePassenger(player)) continue;
            if (seat.addPassenger(player)) moved.add(player);
            else cushion.addPassenger(player); // refused → back on the cushion, not left standing
        }
        return moved;
    }

    /**
     * Landing: put the player on a seat shulker back on the cushion riding it, so they land seated. defCoreLib then
     * steps the cushion (rider and all) onto the landed block. A cushion holds one rider; anyone else stays put.
     */
    public static void moveSeatRiderOntoCushion(Shulker seat) {
        Entity cushion = carriedCushionOn(seat);
        if (cushion == null || !cushion.getPassengers().isEmpty()) return;
        for (Entity p : new ArrayList<>(seat.getPassengers())) {
            if (!(p instanceof Player player)) continue;
            if (seat.removePassenger(player)) {
                if (!cushion.addPassenger(player)) seat.addPassenger(player); // refused → back on the seat
                return;
            }
        }
    }
}
