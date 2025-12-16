package anon.def9a2a4.blockships;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified factory for creating all item types (ship kits, custom items, etc.).
 * This class acts as a routing layer that determines the appropriate creation method
 * based on item type, maximizing code reuse and maintaining architectural boundaries.
 */
public class ItemFactory {

    private final Plugin plugin;
    private final ItemTextureManager textureManager;

    public ItemFactory(Plugin plugin, ItemTextureManager textureManager) {
        this.plugin = plugin;
        this.textureManager = textureManager;
    }

    /**
     * Creates an item (ship kit or custom item) with the given parameters.
     * This is the unified entry point for all item creation from crafting events.
     *
     * @param itemId The item ID (e.g., "balloon", "smallship")
     * @param variant The variant (e.g., "RED", "OAK", null)
     * @param banner The banner item (for ship customization, can be null)
     * @return ItemStack with appropriate textures and PDC tags
     */
    public ItemStack createItem(String itemId, String variant, ItemStack banner) {
        if (isCustomItem(itemId)) {
            // Custom items: load from config and create
            CustomItem customItem = loadCustomItem(itemId);
            if (customItem != null) {
                return customItem.create(variant);
            }
            // Fallback
            return new ItemStack(Material.PLAYER_HEAD);
        } else {
            // Ship kits: delegate to DisplayShip
            return DisplayShip.createShipKit(itemId, banner, variant, plugin);
        }
    }

    /**
     * Creates a placeholder item for recipe registration (recipe book display).
     * Uses "_DEFAULT" variant and generic naming.
     *
     * @param itemId The item ID
     * @return ItemStack suitable for recipe registration
     */
    public ItemStack createItemForRecipe(String itemId) {
        if (isCustomItem(itemId)) {
            // Custom items: create with _DEFAULT variant
            CustomItem customItem = loadCustomItem(itemId);
            if (customItem != null) {
                return customItem.create("_DEFAULT");
            }
            return new ItemStack(Material.PLAYER_HEAD);
        } else {
            // Ship kits: create placeholder with generic name
            return createShipKitPlaceholder(itemId);
        }
    }

    /**
     * Checks if the given item ID is a custom item (defined in config.yml custom-items section).
     *
     * @param itemId The item ID to check
     * @return true if this is a custom item, false if it's a ship kit
     */
    private boolean isCustomItem(String itemId) {
        return plugin.getConfig().contains("custom-items." + itemId);
    }

    /**
     * Loads a custom item from config.yml on-demand.
     */
    private CustomItem loadCustomItem(String itemId) {
        String configPath = "custom-items." + itemId;
        if (!plugin.getConfig().contains(configPath)) {
            return null;
        }

        String displayName = plugin.getConfig().getString(configPath + ".display-name", "{VARIANT} " + itemId);
        String baseMaterialStr = plugin.getConfig().getString(configPath + ".base-material", "PLAYER_HEAD");
        Material baseMaterial = Material.valueOf(baseMaterialStr.toUpperCase());
        String textureSet = plugin.getConfig().getString(configPath + ".texture-set");
        String variantSource = plugin.getConfig().getString(configPath + ".variant-source");

        return new CustomItem(itemId, displayName, baseMaterial, textureSet, variantSource, plugin, textureManager);
    }

    /**
     * Creates a placeholder ship kit for recipe registration.
     * This mirrors the logic from ItemUtil.createItemForRecipe() but only for ships.
     */
    private ItemStack createShipKitPlaceholder(String itemId) {
        String recipePath = "ships." + itemId + ".recipe";

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
        String nameTemplate = plugin.getConfig().getString(recipePath + ".result-name", "Ship Kit");
        String displayName = WoodTypeUtil.stripPlaceholders(nameTemplate);

        meta.displayName(net.kyori.adventure.text.Component.text(displayName)
                .color(net.kyori.adventure.text.format.NamedTextColor.AQUA));

        // Add lore hint
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(net.kyori.adventure.text.Component.text("Customizable based on materials used")
                .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
        meta.lore(lore);

        // Apply default texture if this is a player head
        if (resultMaterial == Material.PLAYER_HEAD && meta instanceof org.bukkit.inventory.meta.SkullMeta) {
            String textureSetName = plugin.getConfig().getString(recipePath + ".result-texture-set");
            if (textureSetName != null) {
                String defaultTexture = textureManager.getTexture(textureSetName, "_DEFAULT");

                if (defaultTexture != null) {
                    ItemUtil.applyPlayerHeadTextureFromBase64(
                            (org.bukkit.inventory.meta.SkullMeta) meta,
                            defaultTexture,
                            plugin
                    );
                } else {
                    plugin.getLogger().warning("No _DEFAULT texture found for " + textureSetName +
                            " (recipe: " + itemId + ")");
                }
            }
        }

        item.setItemMeta(meta);
        return item;
    }
}
