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

                // Valid block, add to ship and frontier
                shipBlocks.add(neighbor.clone());
                frontier.add(neighbor.clone());
            }
        }

        return new InternalScanResult(shipBlocks, exceededLimit, false);
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
