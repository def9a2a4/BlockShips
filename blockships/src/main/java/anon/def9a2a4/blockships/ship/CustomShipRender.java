package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.ShipModel;
import anon.def9a2a4.blockships.customships.BlockStructureScanner;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;

/**
 * Reconstructs the ItemDisplay appearance + transform for a custom-ship part that renders as an ItemDisplay rather
 * than a BlockDisplay — heads/skulls (any) and banners. The scanner captures the necessary NBT into the part's
 * {@code rawYaml} ({@code skull_profile}/{@code skull_rotation}/{@code skull_facing}, {@code banner_patterns}/
 * {@code banner_rotation}/{@code banner_facing}); this rebuilds the display item + local transform from it.
 *
 * <p>Extracted verbatim from the native {@code ShipInstance} display loop so the delegated (defCoreLib) migration
 * path renders custom heads/banners identically. The native loop keeps its own copy until the native engine is
 * removed; both must stay behaviorally identical. Pure functions of {@code rawYaml} — no ship/instance state.
 */
public final class CustomShipRender {
    private CustomShipRender() {}

    /** A custom part renders as an ItemDisplay iff it is a head or a banner. */
    public static boolean isItemDisplayPart(Map<?, ?> rawYaml) {
        return isHead(rawYaml) || isBanner(rawYaml);
    }

    /** Heads/skulls (player AND mob): skull_rotation/skull_facing captured for every head; only player heads also
     *  carry skull_profile. Presence of any marks "this part is a head". */
    public static boolean isHead(Map<?, ?> rawYaml) {
        return rawYaml.containsKey("skull_profile")
            || rawYaml.containsKey("skull_rotation")
            || rawYaml.containsKey("skull_facing");
    }

    /** Banners detected by rotation/facing keys (works for both plain and patterned banners). */
    public static boolean isBanner(Map<?, ?> rawYaml) {
        return rawYaml.containsKey("banner_patterns")
            || rawYaml.containsKey("banner_rotation")
            || rawYaml.containsKey("banner_facing");
    }

    /**
     * Build the ItemDisplay {@link ItemStack} for a head or banner custom part. Mirrors the native display loop:
     * heads become the head material (wall variants remapped to floor) with the stored skin profile; banners become
     * the banner material (wall→standing) with the stored patterns.
     */
    public static ItemStack buildDisplayItem(Map<?, ?> rawYaml, JavaPlugin plugin, int blockIndex) {
        if (isHead(rawYaml)) {
            Material headMaterial = Material.PLAYER_HEAD;
            String headBlockName = String.valueOf(rawYaml.get("block"));
            if (headBlockName.contains("_WALL_HEAD")) {
                headBlockName = headBlockName.replace("_WALL_HEAD", "_HEAD");
            } else if (headBlockName.contains("_WALL_SKULL")) {
                headBlockName = headBlockName.replace("_WALL_SKULL", "_SKULL");
            }
            try {
                headMaterial = Material.valueOf(headBlockName);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown head material '" + headBlockName + "' for block " + blockIndex
                    + ", using PLAYER_HEAD. Please report at " + BlockShipsPlugin.ISSUES_URL);
            }
            ItemStack displayItem = new ItemStack(headMaterial);
            String profileData = (String) rawYaml.get("skull_profile");
            if (profileData != null && displayItem.getItemMeta() instanceof SkullMeta skullMeta) {
                com.destroystokyo.paper.profile.PlayerProfile profile =
                    BlockStructureScanner.deserializeProfile(profileData);
                if (profile != null) {
                    skullMeta.setPlayerProfile(profile);
                    displayItem.setItemMeta(skullMeta);
                }
            }
            return displayItem;
        }

        // Banner
        String blockName = String.valueOf(rawYaml.get("block"));
        if (blockName.contains("_WALL_BANNER")) {
            blockName = blockName.replace("_WALL_BANNER", "_BANNER");
        }
        ItemStack displayItem = new ItemStack(Material.valueOf(blockName));
        ItemMeta meta = displayItem.getItemMeta();
        if (meta instanceof BannerMeta bannerMeta) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> patternList = (List<Map<String, Object>>) rawYaml.get("banner_patterns");
            if (patternList != null) {
                for (Map<String, Object> patternMap : patternList) {
                    org.bukkit.DyeColor color = org.bukkit.DyeColor.valueOf((String) patternMap.get("color"));
                    org.bukkit.block.banner.PatternType patternType = Registry.BANNER_PATTERN.get(
                        NamespacedKey.minecraft(((String) patternMap.get("pattern")).toLowerCase()));
                    if (patternType != null) {
                        bannerMeta.addPattern(new org.bukkit.block.banner.Pattern(color, patternType));
                    }
                }
            }
            displayItem.setItemMeta(bannerMeta);
        }
        return displayItem;
    }

    /** The {@link org.bukkit.entity.ItemDisplay.ItemDisplayTransform} mode for a head (HEAD) vs banner (FIXED). */
    public static org.bukkit.entity.ItemDisplay.ItemDisplayTransform displayMode(Map<?, ?> rawYaml) {
        return isHead(rawYaml)
            ? org.bukkit.entity.ItemDisplay.ItemDisplayTransform.HEAD
            : org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED;
    }

    /**
     * Apply the head/banner display transform (in-place / returning) onto a base local transform. For heads this
     * applies {@link #applySkullTransform}; for banners {@link #calculateBannerTransform}. The caller passes the
     * part's local transform ({@code p.local}); the result is the ItemDisplay's local transform.
     */
    public static Matrix4f applyDisplayTransform(Matrix4f baseLocal, Map<?, ?> rawYaml) {
        if (isHead(rawYaml)) {
            Matrix4f t = new Matrix4f(baseLocal);
            applySkullTransform(t, rawYaml);
            return t;
        }
        return calculateBannerTransform(baseLocal, rawYaml);
    }

    /** Head/skull display transform (in-place). Floor heads use 16-step {@code skull_rotation}; wall heads use
     *  4-direction {@code skull_facing}. Player + mob heads identical. */
    public static void applySkullTransform(Matrix4f transform, Map<?, ?> rawYaml) {
        float skullYaw = 0.0f;
        boolean isWallSkull = rawYaml.containsKey("skull_facing");
        if (rawYaml.containsKey("skull_rotation")) {
            skullYaw = getYawFromBlockFace(ShipInstance.safeBlockFace(rawYaml, "skull_rotation", BlockFace.NORTH));
        } else if (isWallSkull) {
            skullYaw = getYawFromBlockFace(ShipInstance.safeBlockFace(rawYaml, "skull_facing", BlockFace.NORTH));
        }
        if (isWallSkull) {
            transform.translate(0.5f, 0.5f + 0.25f, 0.5f);
            transform.rotateY((float) Math.toRadians(-skullYaw + 180));
            transform.translate(0.0f, 0.0f, 0.25f);
        } else {
            transform.translate(0.5f, 0.5f, 0.5f);
            transform.rotateY((float) Math.toRadians(-skullYaw));
        }
    }

    /** Banner display transform. Handles floor (standing) and wall banners. */
    public static Matrix4f calculateBannerTransform(Matrix4f baseTransform, Map<?, ?> rawYaml) {
        Matrix4f transform = new Matrix4f(baseTransform);
        boolean isWallBanner = rawYaml.containsKey("banner_facing");
        float bannerYaw = 0.0f;
        if (isWallBanner) {
            bannerYaw = getYawFromBlockFace(ShipInstance.safeBlockFace(rawYaml, "banner_facing", BlockFace.NORTH));
        } else if (rawYaml.containsKey("banner_rotation")) {
            bannerYaw = getYawFromBlockFace(ShipInstance.safeBlockFace(rawYaml, "banner_rotation", BlockFace.NORTH));
        }
        float bannerScale = 2f;
        if (isWallBanner) {
            transform.translate(0.5f, 0.5f, 0.5f);
            transform.rotateY((float) Math.toRadians(-bannerYaw));
            transform.translate(0.0f, -0.97f, -0.5f);
            transform.scale(bannerScale);
        } else {
            transform.translate(0.5f, 0.5f, 0.5f);
            transform.rotateY((float) Math.toRadians(-bannerYaw));
            transform.scale(bannerScale);
        }
        return transform;
    }

    /** BlockFace → yaw for banner/skull rotation (16-step). Copied from the native display loop. */
    public static float getYawFromBlockFace(BlockFace face) {
        switch (face) {
            case SOUTH: return 0.0f;
            case SOUTH_SOUTH_WEST: return 22.5f;
            case SOUTH_WEST: return 45.0f;
            case WEST_SOUTH_WEST: return 67.5f;
            case WEST: return 90.0f;
            case WEST_NORTH_WEST: return 112.5f;
            case NORTH_WEST: return 135.0f;
            case NORTH_NORTH_WEST: return 157.5f;
            case NORTH: return 180.0f;
            case NORTH_NORTH_EAST: return 202.5f;
            case NORTH_EAST: return 225.0f;
            case EAST_NORTH_EAST: return 247.5f;
            case EAST: return 270.0f;
            case EAST_SOUTH_EAST: return 292.5f;
            case SOUTH_EAST: return 315.0f;
            case SOUTH_SOUTH_EAST: return 337.5f;
            default: return 0.0f;
        }
    }

    /** Apply a custom part's {@code display_yaw} (chest-style directional rotation about the block centre). */
    public static void applyDisplayYaw(Matrix4f transform, Map<?, ?> rawYaml) {
        if (!rawYaml.containsKey("display_yaw")) return;
        float displayYaw = ((Number) rawYaml.get("display_yaw")).floatValue();
        transform.translate(0.5f, 0f, 0.5f);
        transform.rotateY((float) Math.toRadians(-displayYaw));
        transform.translate(-0.5f, 0f, -0.5f);
    }
}
