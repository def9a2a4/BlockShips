package anon.def9a2a4.blockships.blockconfig;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.*;

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

    public ShipDetector(int maxShipSize) {
        this.configManager = BlockConfigManager.getInstance();
        this.maxShipSize = maxShipSize;
    }

    /**
     * Detect all ship blocks starting from the ship wheel location.
     * Uses BFS flood fill with 6-direction expansion.
     *
     * @param startLocation The ship wheel location
     * @return Set of all blocks that are part of the ship, or null if detection failed
     */
    public Set<Location> detectShip(Location startLocation) {
        Set<Location> shipBlocks = new HashSet<>();
        Queue<Location> frontier = new LinkedList<>();

        // Start with the initial location
        frontier.add(startLocation.clone());
        shipBlocks.add(startLocation.clone());

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

                // Check if we've hit the size limit
                if (shipBlocks.size() >= maxShipSize) {
                    // Ship too large, return null to indicate failure
                    return null;
                }

                // Check if the block is allowed
                Block block = neighbor.getBlock();
                Material material = block.getType();

                if (material.isAir()) {
                    // Skip air blocks
                    continue;
                }

                if (!configManager.isAllowed(material)) {
                    // Skip forbidden blocks (don't add to ship, but continue scanning)
                    continue;
                }

                // Valid block, add to ship and frontier
                shipBlocks.add(neighbor.clone());
                frontier.add(neighbor.clone());
            }
        }

        return shipBlocks;
    }

    /**
     * Detect ship and return detailed information about the ship.
     */
    public ShipDetectionResult detectShipDetailed(Location startLocation) {
        Set<Location> blocks = detectShip(startLocation);

        if (blocks == null) {
            return new ShipDetectionResult(false, "Ship exceeds maximum size of " + maxShipSize + " blocks", null);
        }

        if (blocks.isEmpty()) {
            return new ShipDetectionResult(false, "No valid blocks found for ship", null);
        }

        return new ShipDetectionResult(true, "Successfully detected ship with " + blocks.size() + " blocks", blocks);
    }

    /**
     * Result of ship detection.
     */
    public static class ShipDetectionResult {
        private final boolean success;
        private final String message;
        private final Set<Location> blocks;

        public ShipDetectionResult(boolean success, String message, Set<Location> blocks) {
            this.success = success;
            this.message = message;
            this.blocks = blocks;
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
            return blocks != null ? blocks.size() : 0;
        }
    }
}
