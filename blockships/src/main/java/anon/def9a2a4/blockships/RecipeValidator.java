package anon.def9a2a4.blockships;

import org.bukkit.Material;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates crafting recipes at craft-time, ensuring ingredients match and variants are consistent.
 */
public class RecipeValidator {

    /**
     * Result of recipe validation containing success status and extracted variants.
     */
    public static class ValidationResult {
        private final boolean valid;
        private final Map<String, String> variants;
        private final String failureReason;

        private ValidationResult(boolean valid, Map<String, String> variants, String failureReason) {
            this.valid = valid;
            this.variants = variants;
            this.failureReason = failureReason;
        }

        public static ValidationResult success(Map<String, String> variants) {
            return new ValidationResult(true, variants, null);
        }

        public static ValidationResult failure(String reason) {
            return new ValidationResult(false, new HashMap<>(), reason);
        }

        public boolean isValid() {
            return valid;
        }

        public Map<String, String> getVariants() {
            return variants;
        }

        public String getFailureReason() {
            return failureReason;
        }

        /**
         * Gets the primary variant (first non-null variant found).
         * Used for items that have a single variant type (e.g., wood type).
         */
        public String getPrimaryVariant() {
            return variants.values().stream()
                    .filter(v -> v != null && !v.isEmpty())
                    .findFirst()
                    .orElse(null);
        }

        /**
         * Gets a specific variant by source (e.g., "PLANKS", "WOOL").
         */
        public String getVariant(String source) {
            return variants.get(source);
        }
    }

    /**
     * Validates a crafting matrix against recipe ingredients.
     * Ensures all ingredients match and variants are consistent within each ingredient type.
     *
     * @param inventory The crafting inventory
     * @param recipePattern The 3x3 recipe pattern (9 characters)
     * @param ingredientMap Map of pattern characters to ingredient lists
     * @return ValidationResult containing success status and extracted variants
     */
    public static ValidationResult validateCrafting(
            CraftingInventory inventory,
            String recipePattern,
            Map<Character, List<RecipeIngredient>> ingredientMap) {

        ItemStack[] matrix = inventory.getMatrix();
        Map<String, String> extractedVariants = new HashMap<>();
        Map<Character, String> variantsBySlot = new HashMap<>();

        // Iterate through the 3x3 crafting grid
        for (int i = 0; i < 9 && i < recipePattern.length(); i++) {
            char patternChar = recipePattern.charAt(i);
            ItemStack item = (matrix != null && i < matrix.length) ? matrix[i] : null;

            // Skip empty slots
            if (patternChar == ' ') {
                continue;
            }

            // Get expected ingredients for this slot
            List<RecipeIngredient> expectedIngredients = ingredientMap.get(patternChar);
            if (expectedIngredients == null || expectedIngredients.isEmpty()) {
                return ValidationResult.failure("No ingredient defined for pattern character: " + patternChar);
            }

            // Check if item is null or air (should not happen for non-empty pattern)
            if (item == null || item.getType() == Material.AIR) {
                return ValidationResult.failure("Missing ingredient at slot " + i);
            }

            // Check if the item matches any of the expected ingredients
            boolean matched = false;
            String matchedVariant = null;
            String variantSource = null;

            for (RecipeIngredient ingredient : expectedIngredients) {
                if (ingredient.matches(item)) {
                    matched = true;
                    matchedVariant = ingredient.getVariant(item);

                    // Determine variant source
                    if (ingredient instanceof WildcardIngredient) {
                        variantSource = ((WildcardIngredient) ingredient).getSuffix();
                    } else if (ingredient instanceof CustomItemIngredient) {
                        // For custom items, variant source is based on the config's variant-source field
                        // We'll use the item ID as the variant source key
                        variantSource = ((CustomItemIngredient) ingredient).getCustomItemId();
                    }
                    break;
                }
            }

            if (!matched) {
                return ValidationResult.failure("Invalid ingredient at slot " + i);
            }

            // Track variant for consistency checking
            if (matchedVariant != null && variantSource != null) {
                // Check if we've seen this pattern character before
                if (variantsBySlot.containsKey(patternChar)) {
                    String previousVariant = variantsBySlot.get(patternChar);
                    if (!previousVariant.equals(matchedVariant)) {
                        return ValidationResult.failure(
                                "Inconsistent variants: " + previousVariant + " and " + matchedVariant +
                                " for ingredient " + patternChar
                        );
                    }
                } else {
                    variantsBySlot.put(patternChar, matchedVariant);
                    extractedVariants.put(variantSource, matchedVariant);
                }
            }
        }

        return ValidationResult.success(extractedVariants);
    }

    /**
     * Convenience method for validating with a pattern string (e.g., "PBP/PCP/PCP").
     * Converts slash-separated pattern to a 9-character string.
     */
    public static ValidationResult validateCrafting(
            CraftingInventory inventory,
            List<String> patternLines,
            Map<Character, List<RecipeIngredient>> ingredientMap) {

        // Convert pattern lines to a single 9-character string
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            String line = (i < patternLines.size()) ? patternLines.get(i) : "   ";
            // Pad to 3 characters if needed
            while (line.length() < 3) {
                line += " ";
            }
            pattern.append(line.substring(0, 3));
        }

        return validateCrafting(inventory, pattern.toString(), ingredientMap);
    }

    /**
     * Extracts banner from the crafting matrix (for ship customization).
     * Returns null if no banner found.
     */
    public static ItemStack extractBanner(CraftingInventory inventory) {
        ItemStack[] matrix = inventory.getMatrix();
        if (matrix == null) return null;

        for (ItemStack item : matrix) {
            if (item != null && item.getType().name().endsWith("_BANNER")) {
                return item.clone();
            }
        }

        return null;
    }
}
