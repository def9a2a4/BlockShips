package anon.def9a2a4.blockships;

import org.bukkit.inventory.ItemStack;

/**
 * Encapsulates all ship customization data (textures, colors, banners, etc.).
 * This class uses the builder pattern for flexible construction and is immutable once created.
 *
 * This design allows easy extension - new customization features can be added
 * without changing constructor signatures throughout the codebase.
 */
public class ShipCustomization {

    private final ItemStack customBanner;
    private final String woodType;
    private final String balloonColor;
    private final ItemTextureManager textureManager;

    private ShipCustomization(Builder builder) {
        this.customBanner = builder.customBanner;
        this.woodType = builder.woodType;
        this.balloonColor = builder.balloonColor;
        this.textureManager = builder.textureManager;
    }

    /**
     * Creates a new builder for constructing ShipCustomization instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates an empty customization (all fields null).
     */
    public static ShipCustomization empty() {
        return new Builder().build();
    }

    // Getters

    public ItemStack getCustomBanner() {
        return customBanner;
    }

    public String getWoodType() {
        return woodType;
    }

    public String getBalloonColor() {
        return balloonColor;
    }

    public ItemTextureManager getTextureManager() {
        return textureManager;
    }

    /**
     * Builder for ShipCustomization.
     * Allows flexible construction with optional parameters.
     */
    public static class Builder {
        private ItemStack customBanner = null;
        private String woodType = null;
        private String balloonColor = null;
        private ItemTextureManager textureManager = null;

        private Builder() {
        }

        public Builder banner(ItemStack customBanner) {
            this.customBanner = customBanner;
            return this;
        }

        public Builder woodType(String woodType) {
            this.woodType = woodType;
            return this;
        }

        public Builder balloonColor(String balloonColor) {
            this.balloonColor = balloonColor;
            return this;
        }

        public Builder textureManager(ItemTextureManager textureManager) {
            this.textureManager = textureManager;
            return this;
        }

        public ShipCustomization build() {
            return new ShipCustomization(this);
        }
    }
}
