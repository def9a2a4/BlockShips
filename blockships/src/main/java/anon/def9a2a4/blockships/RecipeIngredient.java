package anon.def9a2a4.blockships;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Represents an ingredient in a recipe with validation and variant extraction capabilities.
 */
public interface RecipeIngredient {

    /**
     * Checks if the given ItemStack matches this ingredient.
     */
    boolean matches(ItemStack stack);

    /**
     * Extracts the variant from the ItemStack (e.g., "OAK" from OAK_PLANKS, "RED" from RED_WOOL).
     * Returns null if no variant can be extracted or if the stack doesn't match.
     */
    String getVariant(ItemStack stack);

    /**
     * Gets the RecipeChoice for Bukkit recipe registration.
     */
    RecipeChoice getRecipeChoice();

    /**
     * Parses an ingredient string from config into a RecipeIngredient instance.
     * Supports:
     * - Direct materials: "CHEST", "PHANTOM_MEMBRANE"
     * - Wildcards: "*_PLANKS", "*_WOOL"
     * - Custom items: "blockships:balloon", "blockships:hull_piece"
     *
     * @param ingredientString The ingredient string from config
     * @param plugin The plugin instance (for custom items)
     * @param textureManager The texture manager (for custom items)
     * @return A RecipeIngredient instance
     */
    static RecipeIngredient parse(String ingredientString, Plugin plugin, ItemTextureManager textureManager) {
        if (ingredientString.startsWith("blockships:")) {
            // Custom item ingredient
            String itemId = ingredientString.substring("blockships:".length());
            return new CustomItemIngredient(itemId, plugin, textureManager);
        } else if (ingredientString.startsWith("*")) {
            // Wildcard ingredient
            String suffix = ingredientString.substring(1);
            return new WildcardIngredient(suffix);
        } else {
            // Direct vanilla material
            try {
                Material material = Material.valueOf(ingredientString.toUpperCase());
                return new VanillaIngredient(material);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown material: " + ingredientString);
            }
        }
    }

    /**
     * Parses a list of ingredient strings into a list of RecipeIngredient instances.
     */
    static List<RecipeIngredient> parseList(List<String> ingredientStrings, Plugin plugin, ItemTextureManager textureManager) {
        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (String str : ingredientStrings) {
            ingredients.add(parse(str, plugin, textureManager));
        }
        return ingredients;
    }
}

/**
 * Ingredient representing a direct vanilla material (e.g., CHEST, PHANTOM_MEMBRANE).
 */
class VanillaIngredient implements RecipeIngredient {
    private final Material material;

    public VanillaIngredient(Material material) {
        this.material = material;
    }

    @Override
    public boolean matches(ItemStack stack) {
        return stack != null && stack.getType() == material;
    }

    @Override
    public String getVariant(ItemStack stack) {
        // Vanilla materials don't have variants
        return null;
    }

    @Override
    public RecipeChoice getRecipeChoice() {
        return new RecipeChoice.MaterialChoice(material);
    }

    public Material getMaterial() {
        return material;
    }
}

/**
 * Ingredient representing a wildcard pattern (e.g., *_PLANKS, *_WOOL).
 */
class WildcardIngredient implements RecipeIngredient {
    private final String suffix;
    private final List<Material> matchingMaterials;

    public WildcardIngredient(String suffix) {
        this.suffix = suffix;
        this.matchingMaterials = expandWildcard(suffix);
    }

    private List<Material> expandWildcard(String suffix) {
        return Arrays.stream(Material.values())
                .filter(m -> m.name().endsWith(suffix) && m.isItem())
                .collect(Collectors.toList());
    }

    @Override
    public boolean matches(ItemStack stack) {
        return stack != null && matchingMaterials.contains(stack.getType());
    }

    @Override
    public String getVariant(ItemStack stack) {
        if (!matches(stack)) {
            return null;
        }

        // Extract variant by removing suffix from material name
        // e.g., "OAK_PLANKS" with suffix "_PLANKS" -> "OAK"
        String materialName = stack.getType().name();
        if (materialName.endsWith(suffix)) {
            return materialName.substring(0, materialName.length() - suffix.length());
        }

        return null;
    }

    @Override
    public RecipeChoice getRecipeChoice() {
        return new RecipeChoice.MaterialChoice(matchingMaterials);
    }

    public String getSuffix() {
        return suffix;
    }

    public List<Material> getMatchingMaterials() {
        return new ArrayList<>(matchingMaterials);
    }
}

/**
 * Ingredient representing a custom item (e.g., blockships:balloon).
 * Loads config directly - no registry needed.
 */
class CustomItemIngredient implements RecipeIngredient {
    private final String customItemId;
    private final Plugin plugin;
    private final ItemTextureManager textureManager;

    public CustomItemIngredient(String customItemId, Plugin plugin, ItemTextureManager textureManager) {
        this.customItemId = customItemId;
        this.plugin = plugin;
        this.textureManager = textureManager;
    }

    /**
     * Loads custom item config from config.yml on-demand.
     */
    private CustomItem loadCustomItem() {
        String configPath = "custom-items." + customItemId;
        if (!plugin.getConfig().contains(configPath)) {
            return null;
        }

        String displayName = plugin.getConfig().getString(configPath + ".display-name", "{VARIANT} " + customItemId);
        String baseMaterialStr = plugin.getConfig().getString(configPath + ".base-material", "PLAYER_HEAD");
        Material baseMaterial = Material.valueOf(baseMaterialStr.toUpperCase());
        String textureSet = plugin.getConfig().getString(configPath + ".texture-set");
        String variantSource = plugin.getConfig().getString(configPath + ".variant-source");

        return new CustomItem(customItemId, displayName, baseMaterial, textureSet, variantSource, plugin, textureManager);
    }

    @Override
    public boolean matches(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }

        CustomItem customItem = loadCustomItem();
        if (customItem == null) {
            return false;
        }

        // Check base material matches
        if (stack.getType() != customItem.getBaseMaterial()) {
            return false;
        }

        // Check display name ends with item display name suffix
        // E.g., "Red Balloon" ends with "Balloon"
        String displayName = stack.getItemMeta().getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }

        String itemSuffix = extractSuffix(customItem.getDisplayNameTemplate());
        return displayName.endsWith(itemSuffix);
    }

    @Override
    public String getVariant(ItemStack stack) {
        if (!matches(stack)) {
            return null;
        }

        CustomItem customItem = loadCustomItem();
        String displayName = stack.getItemMeta().getDisplayName();
        String itemSuffix = extractSuffix(customItem.getDisplayNameTemplate());

        // Extract prefix: "Red Balloon" -> "Red"
        String prefix = displayName.replace(itemSuffix, "").trim();

        // Convert to material-style format: "Red" -> "RED"
        return prefix.toUpperCase().replace(" ", "_");
    }

    /**
     * Extracts the suffix from a display name template.
     * E.g., "{VARIANT} Balloon" -> "Balloon"
     */
    private String extractSuffix(String template) {
        return template.replace("{VARIANT}", "").trim();
    }

    @Override
    public RecipeChoice getRecipeChoice() {
        CustomItem customItem = loadCustomItem();
        if (customItem == null) {
            return new RecipeChoice.MaterialChoice(Material.PLAYER_HEAD);
        }

        // For simplicity: just use MaterialChoice
        // The PrepareItemCraftEvent validation will check the actual item
        return new RecipeChoice.MaterialChoice(customItem.getBaseMaterial());
    }

    public String getCustomItemId() {
        return customItemId;
    }
}
