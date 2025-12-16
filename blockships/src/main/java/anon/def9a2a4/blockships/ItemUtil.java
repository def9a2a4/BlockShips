package anon.def9a2a4.blockships;

import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for item, recipe, and material operations.
 */
public class ItemUtil {

    /**
     * Registers all recipes (ships and custom items) for a plugin.
     * Clears old recipes first to avoid conflicts on reload.
     *
     * @param plugin The plugin instance
     * @param registeredRecipes List to track registered recipe keys
     * @param itemFactory The item factory for creating recipe placeholders
     */
    public static void registerAllRecipes(Plugin plugin, List<NamespacedKey> registeredRecipes, ItemFactory itemFactory) {
        // Clear our recipe tracking list
        registeredRecipes.clear();

        // Remove all old recipes for this plugin
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r instanceof Keyed && ((Keyed) r).getKey().getNamespace().equals(plugin.getName().toLowerCase())) {
                it.remove();
            }
        }

        List<String> registeredNames = new ArrayList<>();

        // Register recipes for custom items (e.g., balloons)
        var customItemsSection = plugin.getConfig().getConfigurationSection("custom-items");
        if (customItemsSection != null) {
            for (String itemType : customItemsSection.getKeys(false)) {
                if (registerItemRecipe(plugin, itemType, "custom-items." + itemType, registeredRecipes, itemFactory)) {
                    registeredNames.add(itemType);
                }
            }
        }

        // Register recipe for each ship type
        var shipsSection = plugin.getConfig().getConfigurationSection("ships");
        if (shipsSection != null) {
            for (String shipType : shipsSection.getKeys(false)) {
                if (registerItemRecipe(plugin, shipType, "ships." + shipType, registeredRecipes, itemFactory)) {
                    registeredNames.add(shipType);
                }
            }
        }

        if (!registeredNames.isEmpty()) {
            plugin.getLogger().info("Registered recipes: " + String.join(", ", registeredNames));
        }
    }

    /**
     * Registers a single item recipe from config.
     *
     * @param plugin The plugin instance
     * @param itemType The item type ID
     * @param configPath The config path for this item
     * @param registeredRecipes List to track registered recipe keys
     * @param itemFactory The item factory for creating recipe placeholders
     * @return true if the recipe was successfully registered
     */
    public static boolean registerItemRecipe(Plugin plugin, String itemType, String configPath, List<NamespacedKey> registeredRecipes, ItemFactory itemFactory) {
        String recipePath = configPath + ".recipe";
        if (!plugin.getConfig().contains(recipePath)) return false;

        // Get recipe pattern
        List<String> pattern = plugin.getConfig().getStringList(recipePath + ".pattern");
        if (pattern.isEmpty() || pattern.size() != 3) {
            plugin.getLogger().warning("Invalid recipe pattern for " + itemType);
            return false;
        }

        // Create a placeholder kit item (actual customization happens in PrepareItemCraftEvent)
        ItemStack kit = itemFactory.createItemForRecipe(itemType);

        // Create recipe
        NamespacedKey recipeKey = new NamespacedKey(plugin, itemType + "_kit_recipe");
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, kit);
        recipe.shape(pattern.get(0), pattern.get(1), pattern.get(2));

        // Get ingredients using RecipeIngredient system
        var ingredientsSection = plugin.getConfig().getConfigurationSection(recipePath + ".ingredients");
        if (ingredientsSection == null) {
            plugin.getLogger().warning("No ingredients defined for " + itemType);
            return false;
        }

        for (String key : ingredientsSection.getKeys(false)) {
            List<String> ingredientStrings = plugin.getConfig().getStringList(recipePath + ".ingredients." + key);

            if (ingredientStrings.isEmpty()) {
                plugin.getLogger().warning("No ingredients specified for key '" + key + "' in " + itemType);
                continue;
            }

            try {
                // Get texture manager for custom item ingredients
                ItemTextureManager textureManager = ((anon.def9a2a4.blockships.BlockShipsPlugin) plugin)
                        .getDisplayShip().getTextureManager();

                // Parse ingredients using RecipeIngredient system
                List<RecipeIngredient> ingredients = RecipeIngredient.parseList(ingredientStrings, plugin, textureManager);

                if (ingredients.isEmpty()) {
                    plugin.getLogger().warning("No valid ingredients for key '" + key + "' in " + itemType);
                    continue;
                }

                // Use the first ingredient's recipe choice for Bukkit registration
                RecipeChoice choice = ingredients.get(0).getRecipeChoice();
                recipe.setIngredient(key.charAt(0), choice);

            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Failed to parse ingredient '" + key + "' for " + itemType + ": " + e.getMessage());
            }
        }

        Bukkit.addRecipe(recipe, true);
        registeredRecipes.add(recipeKey);
        return true;
    }

    /**
     * Creates a display item for recipe registration with default texture and generic name.
     */
    private static ItemStack createItemForRecipe(Plugin plugin, String itemType, String configPath) {
        String recipePath = configPath + ".recipe";

        // Get base material
        String resultItemName = plugin.getConfig().getString(recipePath + ".result-item", "PAPER");
        Material resultMaterial;
        try {
            resultMaterial = Material.valueOf(resultItemName.toUpperCase());
        } catch (IllegalArgumentException e) {
            resultMaterial = Material.PAPER;
        }

        ItemStack item = new ItemStack(resultMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Create generic display name (strip variant placeholders)
        String nameTemplate = plugin.getConfig().getString(recipePath + ".result-name", "Item");
        String displayName = WoodTypeUtil.stripPlaceholders(nameTemplate);

        meta.displayName(net.kyori.adventure.text.Component.text(displayName)
                .color(net.kyori.adventure.text.format.NamedTextColor.AQUA));

        // Add lore hint
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(net.kyori.adventure.text.Component.text("Customizable based on materials used")
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
        meta.lore(lore);

        // Apply default texture if this is a player head
        if (resultMaterial == Material.PLAYER_HEAD && meta instanceof SkullMeta) {
            String textureSetName = plugin.getConfig().getString(recipePath + ".result-texture-set");
            if (textureSetName != null) {
                ItemTextureManager textureManager = ((anon.def9a2a4.blockships.BlockShipsPlugin) plugin)
                        .getDisplayShip().getTextureManager();
                String defaultTexture = textureManager.getTexture(textureSetName, "_DEFAULT");

                if (defaultTexture != null) {
                    applyPlayerHeadTextureFromBase64((SkullMeta) meta, defaultTexture, plugin);
                } else {
                    plugin.getLogger().warning("No _DEFAULT texture found for " + textureSetName +
                            " (recipe: " + itemType + ")");
                }
            }
        }

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Unlocks all registered recipes for a player.
     *
     * @param player The player to unlock recipes for
     * @param registeredRecipes List of recipe keys to unlock
     * @param plugin The plugin instance (for logging)
     * @return The number of recipes unlocked
     */
    public static int unlockAllRecipesForPlayer(Player player, List<NamespacedKey> registeredRecipes, Plugin plugin) {
        plugin.getLogger().info("Unlocking " + registeredRecipes.size() + " recipe(s) for player " + player.getName());

        if (registeredRecipes.isEmpty()) {
            plugin.getLogger().warning("No recipes registered yet! Make sure recipes were loaded on startup.");
            return 0;
        }

        int count = 0;
        List<NamespacedKey> keysToDiscover = new ArrayList<>();

        for (NamespacedKey recipeKey : registeredRecipes) {
            plugin.getLogger().info("Unlocking recipe: " + recipeKey);
            keysToDiscover.add(recipeKey);
            count++;
        }

        // Discover all recipes at once (more efficient and reliable)
        int discovered = player.discoverRecipes(keysToDiscover);
        plugin.getLogger().info("Player now has " + discovered + " new recipes discovered");
        plugin.getLogger().info("Successfully unlocked " + count + " recipe(s) for " + player.getName());

        // Force update player's recipe book
        player.updateInventory();

        return count;
    }

    /**
     * Applies a custom texture to a player head item.
     * Handles Base64 decoding and URL extraction from texture value.
     *
     * @param meta The SkullMeta to apply texture to
     * @param shipType The ship type (used to look up texture set in config)
     * @param woodType The wood variant (used to select specific texture from set)
     * @param plugin The plugin instance
     * @param textureManager The texture manager instance
     */
    public static void applyPlayerHeadTexture(SkullMeta meta, String shipType, String woodType, Plugin plugin, Object textureManager) {
        // Get texture set name from config
        String textureSetName = plugin.getConfig().getString("ships." + shipType + ".recipe.result-texture-set");
        if (textureSetName == null || textureSetName.isEmpty()) {
            return; // No texture set configured
        }

        // Get texture from ItemTextureManager using reflection to avoid circular dependency
        String texture = null;
        if (textureManager != null) {
            try {
                java.lang.reflect.Method getTextureMethod = textureManager.getClass().getMethod("getTexture", String.class, String.class);
                texture = (String) getTextureMethod.invoke(textureManager, textureSetName, woodType);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to get texture: " + e.getMessage());
            }
        }

        applyPlayerHeadTextureFromBase64(meta, texture, plugin);
    }

    /**
     * Applies a custom texture to a player head from a Base64-encoded texture value.
     * This is the unified implementation used by both ship kits and custom items.
     *
     * @param meta The SkullMeta to apply texture to
     * @param textureBase64 The Base64-encoded texture JSON (format: {"textures":{"SKIN":{"url":"..."}}})
     * @param plugin The plugin instance (for logging)
     */
    public static void applyPlayerHeadTextureFromBase64(SkullMeta meta, String textureBase64, Plugin plugin) {
        if (textureBase64 == null || textureBase64.isEmpty()) {
            return;
        }

        try {
            // Decode Base64 to get texture URL (format: {"textures":{"SKIN":{"url":"..."}}}
            String decoded = new String(Base64.getDecoder().decode(textureBase64));

            // Extract URL from JSON (simple string parsing)
            int urlStart = decoded.indexOf("\"url\":\"") + 7;
            int urlEnd = decoded.indexOf("\"", urlStart);

            if (urlStart > 7 && urlEnd > urlStart) {
                String urlString = decoded.substring(urlStart, urlEnd);
                URL textureUrl = new URL(urlString);

                // Create player profile with texture
                var profile = Bukkit.createPlayerProfile(UUID.randomUUID());
                var textures = profile.getTextures();
                textures.setSkin(textureUrl);
                profile.setTextures(textures);
                meta.setOwnerProfile(profile);
            }
        } catch (Exception e) {
            // Silently fail - texture application is optional
            if (plugin != null) {
                plugin.getLogger().warning("Failed to apply player head texture: " + e.getMessage());
            }
        }
    }

    /**
     * Formats a material name for display.
     * Converts "DARK_OAK" -> "Dark Oak" or "SPRUCE" -> "Spruce"
     *
     * @param name The material name to format
     * @return The formatted name
     */
    public static String formatMaterialName(String name) {
        return Arrays.stream(name.split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }
}
