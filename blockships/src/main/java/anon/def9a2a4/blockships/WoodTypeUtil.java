package anon.def9a2a4.blockships;

/**
 * Utilities for handling wood type variations in block names and display strings.
 */
public final class WoodTypeUtil {
    public static final String[] WOOD_TYPES = {
        "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK",
        "MANGROVE", "CHERRY", "BAMBOO", "PALE_OAK", "CRIMSON", "WARPED"
    };

    private WoodTypeUtil() {} // Prevent instantiation

    /**
     * Replaces the wood type prefix in a block name.
     * E.g., "SPRUCE_STAIRS" + "WARPED" -> "WARPED_STAIRS"
     */
    public static String replaceWoodType(String blockName, String targetWoodType) {
        if (blockName == null || targetWoodType == null) return blockName;
        for (String wood : WOOD_TYPES) {
            if (blockName.startsWith(wood + "_")) {
                return blockName.replaceFirst("^" + wood, targetWoodType);
            }
        }
        return blockName;
    }

    /**
     * Replaces {WOOD_TYPE} and {VARIANT} placeholders in text with formatted wood type.
     * If woodType is null, replaces with default values.
     */
    public static String formatPlaceholders(String text, String woodType) {
        if (text == null) return null;
        if (woodType == null) {
            return text.replace("{WOOD_TYPE}", "Wood").replace("{VARIANT}", "");
        }
        String woodName = ItemUtil.formatMaterialName(woodType);
        return text.replace("{WOOD_TYPE}", woodName).replace("{VARIANT}", woodName);
    }

    /**
     * Strips {WOOD_TYPE} and {VARIANT} placeholders from text and cleans up whitespace.
     * Used for creating generic recipe placeholder items.
     */
    public static String stripPlaceholders(String text) {
        if (text == null) return null;
        return text
            .replace("{WOOD_TYPE}", "")
            .replace("{VARIANT}", "")
            .trim()
            .replaceAll("\\s+", " ");
    }
}
