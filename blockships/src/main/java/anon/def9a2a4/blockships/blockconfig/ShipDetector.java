package anon.def9a2a4.blockships.blockconfig;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.*;
// Collections is used for the no-glue detectShipDetailed overload.

/**
 * Detects ship blocks using 6-direction flood fill algorithm.
 * Starts at a ship wheel and expands to all connected allowed blocks.
 */
public class ShipDetector {

    // 6 directions: +X, -X, +Y, -Y, +Z, -Z (no diagonals)
    private static final int[][] DIRECTIONS = {
        {1, 0, 0},   // East
        {-1, 0, 0},  // West
        {0, 1, 0},   // Up
        {0, -1, 0},  // Down
        {0, 0, 1},   // South
        {0, 0, -1}   // North
    };

    private final BlockConfigManager configManager;
    private final int maxShipSize;
    private final int maxScanSize;

    public ShipDetector(int maxShipSize, int maxScanSize) {
        this.configManager = BlockConfigManager.getInstance();
        this.maxShipSize = maxShipSize;
        this.maxScanSize = maxScanSize;
    }

    /**
     * Internal result for detectShipInternal with scan state.
     */
    private static class InternalScanResult {
        final Set<Location> blocks;
        final boolean exceededLimit;
        final boolean scanLimitHit;

        InternalScanResult(Set<Location> blocks, boolean exceededLimit, boolean scanLimitHit) {
            this.blocks = blocks;
            this.exceededLimit = exceededLimit;
            this.scanLimitHit = scanLimitHit;
        }
    }

    /**
     * Detect all ship blocks starting from the ship wheel location.
     * Uses BFS flood fill with 6-direction expansion.
     * Continues scanning past maxShipSize up to maxScanSize to report actual size.
     *
     * @param startLocation The ship wheel location
     * @return Internal result with blocks and status flags
     */
    private InternalScanResult detectShipInternal(Location startLocation, Set<Location> forcedMembers) {
        Set<Location> shipBlocks = new HashSet<>();
        Queue<Location> frontier = new LinkedList<>();
        boolean exceededLimit = false;

        // No wheel, no ship. The seed used to be added unconditionally, which meant a scan started from
        // a cell whose wheel block was gone — an assembled ship (the hull is aired out and the skull
        // removed), or a wheel broken out of band — did not fail. It SUCCEEDED, seeded from air, and
        // then expanded into whatever allow-listed blocks happened to sit next to the empty cell: a
        // dock, a pier, someone else's deck. Callers saw a valid result and reported another
        // structure's blocks as this ship's.
        //
        // This is the same guard BlockStructureScanner.scanFrozen already applies, and the same one the
        // forcedMembers loop below has always applied to every cell except this one. Two callers depend
        // on it — the wheel menu's docked readout and defCoreLib's glue-anchor connector provider —
        // and neither can check for itself, because both legitimately run while the ship is elsewhere.
        if (startLocation.getBlock().getType().isAir()) {
            return new InternalScanResult(shipBlocks, false, false);
        }

        // Start with the initial location
        frontier.add(startLocation.clone());
        shipBlocks.add(startLocation.clone());

        // Glued cells join unconditionally and seed the frontier, so the fill expands THROUGH them —
        // a glued dirt block bridges to allowed hull on its far side. They bypass the allow-list by
        // design (that is what gluing is for), but never the air check: a glued cell whose block was
        // broken while docked would otherwise become a ModelPart holding AIR blockdata, rendering an
        // empty ItemDisplay and airing out an already-air cell at assembly.
        for (Location forced : forcedMembers) {
            if (forced.getBlock().getType().isAir()) continue;
            Location cell = forced.clone();
            if (shipBlocks.add(cell)) frontier.add(cell);
        }

        // BFS flood fill
        while (!frontier.isEmpty()) {
            Location current = frontier.poll();

            // Check all 6 adjacent blocks
            for (int[] direction : DIRECTIONS) {
                Location neighbor = current.clone().add(direction[0], direction[1], direction[2]);

                // Skip if already visited
                if (shipBlocks.contains(neighbor)) {
                    continue;
                }

                // Check if we've hit the scan limit (hard stop)
                if (shipBlocks.size() >= maxScanSize) {
                    return new InternalScanResult(shipBlocks, true, true);
                }

                // Check if we've exceeded the ship size limit (continue scanning but mark as exceeded)
                if (shipBlocks.size() >= maxShipSize) {
                    exceededLimit = true;
                }

                // Check if the block is allowed
                Block block = neighbor.getBlock();
                Material material = block.getType();

                if (material.isAir()) {
                    // Skip air blocks
                    continue;
                }

                if (!configManager.isAllowed(material) && !forcedMembers.contains(neighbor)) {
                    // Skip forbidden blocks (don't add to ship, but continue scanning)
                    continue;
                }

                // Never absorb ANOTHER ship's wheel.
                //
                // blocks.yml allows player_head (a wheel has to be scannable — it is this ship's own anchor),
                // and this fill had no way to tell one wheel from another. So a neighbour who docked their
                // ship against your hull had their wheel swallowed into YOUR mechanism on assembly: aired out
                // of the world along with everything else, with their record left pointing at an empty cell.
                //
                // Excluded rather than refused, deliberately. Making a foreign wheel abort the assembly would
                // be a griefing primitive in reverse — park a wheel against someone's hull and their ship can
                // never sail again, with a message they cannot act on if they cannot break the block. Skipping
                // it just stops the fill there, exactly like any disallowed block.
                if ((material == Material.PLAYER_HEAD || material == Material.PLAYER_WALL_HEAD)
                        && isForeignWheel(block, startLocation)) {
                    continue;
                }

                // Valid block, add to ship and frontier
                shipBlocks.add(neighbor.clone());
                frontier.add(neighbor.clone());
            }
        }

        return new InternalScanResult(shipBlocks, exceededLimit, false);
    }

    /**
     * Is this head a ship wheel belonging to some ship other than the one being scanned?
     *
     * <p>Identified by the block's own {@code wheel_id} stamp, so it costs a PDC read and only on heads. An
     * <b>unstamped</b> head is not treated as foreign: it may be this ship's own pre-identity wheel, and
     * wrongly excluding that would silently drop the anchor out of its own hull. The comparison is against
     * the seed cell rather than the seed's id because the seed is this ship's wheel by construction — the
     * caller has already established the scan starts from it.
     */
    private static boolean isForeignWheel(Block block, Location startLocation) {
        if (block.getX() == startLocation.getBlockX()
            && block.getY() == startLocation.getBlockY()
            && block.getZ() == startLocation.getBlockZ()) {
            return false;   // the seed itself
        }
        try {
            return anon.def9a2a4.blockships.customships.ShipWheelBlockType.readWheelId(block) != null;
        } catch (Throwable t) {
            return false;   // unreadable: treat as an ordinary block rather than dropping it from the hull
        }
    }

    /**
     * Detect ship and return detailed information about the ship.
     */
    public ShipDetectionResult detectShipDetailed(Location startLocation) {
        return detectShipDetailed(startLocation, Collections.emptySet());
    }

    /**
     * Detect ship, treating {@code forcedMembers} (the wheel's glued cells) as ship blocks regardless
     * of the {@code blocks.yml} allow-list. They also seed the flood fill, so the ship can expand
     * through a glued block to allowed hull beyond it.
     */
    public ShipDetectionResult detectShipDetailed(Location startLocation, Set<Location> forcedMembers) {
        InternalScanResult result = detectShipInternal(startLocation, forcedMembers);

        if (result.blocks.isEmpty()) {
            return new ShipDetectionResult(false, "No valid blocks found for ship", null, 0, false);
        }

        int blockCount = result.blocks.size();

        if (result.scanLimitHit) {
            // Ship so big we stopped counting
            String message = "Ship has at least " + blockCount + " blocks (stopped scanning), maximum is " + maxShipSize;
            return new ShipDetectionResult(false, message, null, blockCount, true);
        }

        if (result.exceededLimit) {
            // Ship over limit but fully scanned
            String message = "Ship has " + blockCount + " blocks which exceeds the maximum of " + maxShipSize;
            return new ShipDetectionResult(false, message, null, blockCount, false);
        }

        return new ShipDetectionResult(true, "Successfully detected ship with " + blockCount + " blocks", result.blocks, blockCount, false);
    }

    /**
     * Result of ship detection.
     */
    public static class ShipDetectionResult {
        private final boolean success;
        private final String message;
        private final Set<Location> blocks;
        private final int blockCount;
        private final boolean scanLimitHit;

        public ShipDetectionResult(boolean success, String message, Set<Location> blocks, int blockCount, boolean scanLimitHit) {
            this.success = success;
            this.message = message;
            this.blocks = blocks;
            this.blockCount = blockCount;
            this.scanLimitHit = scanLimitHit;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public Set<Location> getBlocks() {
            return blocks;
        }

        public int getBlockCount() {
            return blockCount;
        }

        public boolean isScanLimitHit() {
            return scanLimitHit;
        }
    }
}
