package anon.def9a2a4.blockships;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple data class for custom items loaded from config.yml.
 * No registry, no complex lookups - just creates items based on config data.
 */
public class CustomItem {

    private final String id;
    private final String displayNameTemplate;
    private final Material baseMaterial;
    private final String textureSet;
    private final String variantSource;
    private final Plugin plugin;
    private final ItemTextureManager textureManager;
    private final NamespacedKey itemIdKey;
    private final NamespacedKey variantKey;

    public CustomItem(String id, String displayNameTemplate, Material baseMaterial,
                     String textureSet, String variantSource,
                     Plugin plugin, ItemTextureManager textureManager) {
        this.id = id;
        this.displayNameTemplate = displayNameTemplate;
        this.baseMaterial = baseMaterial;
        this.textureSet = textureSet;
        this.variantSource = variantSource;
        this.plugin = plugin;
        this.textureManager = textureManager;
        this.itemIdKey = new NamespacedKey(plugin, "custom_item_id");
        this.variantKey = new NamespacedKey(plugin, "item_variant");
    }

    /**
     * Creates an ItemStack of this custom item with the specified variant.
     */
    public ItemStack create(String variant) {
        // Default to _DEFAULT if no variant specified (for items without variant extraction)
        if (variant == null) {
            variant = "_DEFAULT";
        }

        ItemStack item = new ItemStack(baseMaterial);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // Set display name with variant substitution
        String displayName = displayNameTemplate.replace("{VARIANT}", formatVariantName(variant));
        meta.setDisplayName(displayName);

        // Store custom item data in PDC
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(itemIdKey, PersistentDataType.STRING, id);
        pdc.set(variantKey, PersistentDataType.STRING, variant);

        // Add lore
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        if (id.equals("ship_wheel")) {
            // Ship wheel has special lore with usage instructions
            lore.add(net.kyori.adventure.text.Component.text("Place down and right click to open menu")
                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
        } else {
            lore.add(net.kyori.adventure.text.Component.text("Type: " + formatVariantName(variant))
                    .color(net.kyori.adventure.text.format.NamedTextColor.GRAY));
            // Store variant in lore for easy extraction (used for airship balloon colors)
            lore.add(net.kyori.adventure.text.Component.text("Variant: " + variant)
                    .color(net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
        }
        meta.lore(lore);

        // Apply texture if this is a player head
        if (baseMaterial == Material.PLAYER_HEAD && meta instanceof SkullMeta && textureSet != null) {
            String textureValue = textureManager.getTexture(textureSet, variant);
            if (textureValue != null) {
                ItemUtil.applyPlayerHeadTextureFromBase64((SkullMeta) meta, textureValue, plugin);
            } else {
                plugin.getLogger().warning("No texture found for " + id + " variant " + variant);
            }
        }

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Formats a variant name for display.
     * E.g., "RED" -> "Red", "DARK_OAK" -> "Dark Oak"
     */
    private String formatVariantName(String variant) {
        if (variant == null || variant.isEmpty() || variant.equals("_DEFAULT")) {
            return "";
        }
        return ItemUtil.formatMaterialName(variant);
    }

    public String getId() {
        return id;
    }

    public String getDisplayNameTemplate() {
        return displayNameTemplate;
    }

    public Material getBaseMaterial() {
        return baseMaterial;
    }

    public String getTextureSet() {
        return textureSet;
    }

    public String getVariantSource() {
        return variantSource;
    }

    /**
     * Extracts the variant from a custom item's lore.
     * Looks for "Variant: XXX" in the lore and returns the variant string.
     *
     * @param item The item to extract variant from
     * @return The variant string (e.g., "RED", "BLUE") or null if not found
     */
    public static String extractVariantFromLore(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) {
            return null;
        }

        List<net.kyori.adventure.text.Component> lore = meta.lore();
        if (lore == null) {
            return null;
        }

        for (net.kyori.adventure.text.Component line : lore) {
            String plainText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line);
            if (plainText.startsWith("Variant: ")) {
                return plainText.substring("Variant: ".length());
            }
        }

        return null;
    }
}
