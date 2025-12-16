package anon.def9a2a4.blockships.blockconfig;

import anon.def9a2a4.blockships.ShipModel;
import org.bukkit.block.data.BlockData;

import java.util.List;

/**
 * Properties for a block type used in ship construction.
 * Includes weight, collision config, and special behaviors.
 */
public class BlockProperties {
    private final boolean allowed;
    private final Integer weight;  // null means block is excluded from density calculations
    private final CollisionConfig collider;
    private final boolean leadable;
    private final boolean seat;
    private final boolean displayRotation;  // true = BlockDisplay ignores facing, apply rotation manually
    private final boolean interaction;  // true = block opens workbench-style GUI when clicked
    private final ShipModel.StorageConfig storage;  // storage config for container blocks
    private final List<ConditionalRule> conditionalRules;

    public BlockProperties(boolean allowed, Integer weight, CollisionConfig collider, boolean leadable, boolean seat) {
        this(allowed, weight, collider, leadable, seat, false, false, null, null);
    }

    public BlockProperties(boolean allowed, Integer weight, CollisionConfig collider, boolean leadable, boolean seat, List<ConditionalRule> conditionalRules) {
        this(allowed, weight, collider, leadable, seat, false, false, null, conditionalRules);
    }

    public BlockProperties(boolean allowed, Integer weight, CollisionConfig collider, boolean leadable, boolean seat, boolean displayRotation, List<ConditionalRule> conditionalRules) {
        this(allowed, weight, collider, leadable, seat, displayRotation, false, null, conditionalRules);
    }

    public BlockProperties(boolean allowed, Integer weight, CollisionConfig collider, boolean leadable, boolean seat, boolean displayRotation, boolean interaction, List<ConditionalRule> conditionalRules) {
        this(allowed, weight, collider, leadable, seat, displayRotation, interaction, null, conditionalRules);
    }

    public BlockProperties(boolean allowed, Integer weight, CollisionConfig collider, boolean leadable, boolean seat, boolean displayRotation, boolean interaction, ShipModel.StorageConfig storage, List<ConditionalRule> conditionalRules) {
        this.allowed = allowed;
        this.weight = weight;
        this.collider = collider;
        this.leadable = leadable;
        this.seat = seat;
        this.displayRotation = displayRotation;
        this.interaction = interaction;
        this.storage = storage;
        this.conditionalRules = conditionalRules;
    }

    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Returns whether this block has a weight (contributes to density calculations).
     * Blocks with null weight are excluded from buoyancy calculations.
     */
    public boolean hasWeight() {
        return weight != null;
    }

    /**
     * Returns the weight of this block, or null if excluded from density calculations.
     */
    public Integer getWeight() {
        return weight;
    }

    public CollisionConfig getCollider() {
        return collider;
    }

    public boolean isLeadable() {
        return leadable;
    }

    public boolean isSeat() {
        return seat;
    }

    /**
     * Returns true if this block needs manual display rotation.
     * Some blocks (like chests) don't respect BlockData facing in BlockDisplay.
     */
    public boolean needsDisplayRotation() {
        return displayRotation;
    }

    /**
     * Returns true if this block opens an interaction GUI when clicked.
     * Examples: crafting table, anvil, loom, stonecutter, etc.
     */
    public boolean isInteraction() {
        return interaction;
    }

    /**
     * Returns the storage config for this block, or null if not a container.
     */
    public ShipModel.StorageConfig getStorage() {
        return storage;
    }

    /**
     * Get properties for a specific block state, applying conditional rules if any.
     */
    public BlockProperties getPropertiesForBlockData(BlockData blockData) {
        if (conditionalRules == null || conditionalRules.isEmpty()) {
            return this;
        }

        // Find matching rule
        for (ConditionalRule rule : conditionalRules) {
            if (rule.matches(blockData)) {
                return rule.getProperties();
            }
        }

        // No matching rule, return defaults
        return this;
    }

    /**
     * Represents a conditional rule that applies different properties based on BlockData.
     * For example: bottom slabs have different colliders than top slabs.
     */
    public static class ConditionalRule {
        private final BlockDataMatcher matcher;
        private final BlockProperties properties;

        public ConditionalRule(BlockDataMatcher matcher, BlockProperties properties) {
            this.matcher = matcher;
            this.properties = properties;
        }

        public boolean matches(BlockData blockData) {
            return matcher.matches(blockData);
        }

        public BlockProperties getProperties() {
            return properties;
        }
    }

    /**
     * Matches BlockData against specific property values.
     * Example: {type: "BOTTOM", waterlogged: false}
     */
    public interface BlockDataMatcher {
        boolean matches(BlockData blockData);
    }
}
