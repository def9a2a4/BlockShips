package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.ShipModel;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Puts a 0.0.17 native custom ship back into the world as blocks, where it stands. The upgrade docks every
 * custom ship that was still out at sea instead of converting it to a live mechanism; players relaunch it
 * from its wheel. This is 0.0.17's own {@code BlockStructureScanner.placeBlocks}, the code that wrote these
 * models, minus the parts that no longer apply (WorldGuard routing: the ship was already sitting here; the
 * engine tag: engines are gone).
 */
final class NativeShipLanding {

    private NativeShipLanding() {}

    /**
     * Places every part of {@code model} around {@code shipLoc} (the ship root, snapped to a block corner),
     * turned to {@code yaw} (a multiple of 90). Fragile blocks in the way are cleared. A part whose cell holds
     * a solid block is dropped as items, contents included, rather than lost; the wheel (the head at the root
     * cell) drops as {@code wheelItem}, not a plain head.
     *
     * @return how many parts were dropped instead of placed
     */
    static int place(Location shipLoc, ShipModel model, float yaw, ItemStack wheelItem) {
        float rotationDelta = ((yaw - model.assemblyYaw) % 360 + 360) % 360;
        int dropped = 0;

        for (ShipModel.ModelPart part : model.parts) {
            Vector3f rotatedPos = BlockStructureScanner.rotatePosition(part.local.getTranslation(new Vector3f()), rotationDelta);
            // Round: cos(90°) is ~6e-17, not 0, which would put a block one cell off.
            Location blockLoc = shipLoc.clone().add(Math.round(rotatedPos.x), Math.round(rotatedPos.y), Math.round(rotatedPos.z));
            Block block = blockLoc.getBlock();
            Material existingType = block.getType();
            BlockData existingData = block.getBlockData();

            try {
                if (!existingType.isAir() && existingType != Material.WATER && existingType != Material.LAVA) {
                    if (FragileBlocks.isFragile(existingType)) {
                        block.setType(Material.AIR, false);
                    } else {
                        boolean wheel = blockLoc.getBlock().equals(shipLoc.getBlock())
                            && part.block.getMaterial().name().endsWith("PLAYER_HEAD");
                        dropPart(part, blockLoc, wheel ? wheelItem : null);
                        dropped++;
                        continue;
                    }
                }

                BlockData rotatedData = part.block;
                if (part.rawYaml.get("blockdata") instanceof String blockDataString) {
                    try {
                        rotatedData = Bukkit.createBlockData(blockDataString);
                    } catch (IllegalArgumentException ignored) {
                        // fall back to part.block
                    }
                }
                rotatedData = BlockStructureScanner.rotateBlockData(rotatedData, rotationDelta);

                // Waterlogging comes from the destination: only replacing a water source makes it waterlogged.
                if (rotatedData instanceof org.bukkit.block.data.Waterlogged waterlogged) {
                    waterlogged.setWaterlogged(existingType == Material.WATER
                        && existingData instanceof org.bukkit.block.data.Levelled lv && lv.getLevel() == 0);
                }

                block.setBlockData(rotatedData, false);
                restoreMetadata(part, block);
            } catch (Exception e) {
                // The block is placed before any metadata restore can throw, so a bad value only costs this
                // block its decoration.
                Bukkit.getLogger().warning("[BlockShips] docking a native ship: failed to restore block at "
                    + blockLoc + ", skipping its metadata: " + e.getMessage());
            }
        }
        return dropped;
    }

    @SuppressWarnings("unchecked")
    private static void restoreMetadata(ShipModel.ModelPart part, Block block) {
        Map<String, Object> raw = (Map<String, Object>) part.rawYaml;

        if (raw.get("skull_profile") instanceof String profileData
                && block.getState() instanceof org.bukkit.block.Skull skull) {
            com.destroystokyo.paper.profile.PlayerProfile profile = BlockStructureScanner.deserializeProfile(profileData);
            if (profile != null) {
                skull.setPlayerProfile(profile);
                skull.update();
            }
        }

        if (raw.get("banner_patterns") instanceof List<?> patternList
                && block.getState() instanceof org.bukkit.block.Banner banner) {
            List<org.bukkit.block.banner.Pattern> patterns = new ArrayList<>();
            for (Object o : patternList) {
                Map<String, Object> p = (Map<String, Object>) o;
                org.bukkit.block.banner.PatternType type = Registry.BANNER_PATTERN.get(
                    NamespacedKey.minecraft(String.valueOf(p.get("pattern")).toLowerCase()));
                if (type != null) {
                    patterns.add(new org.bukkit.block.banner.Pattern(
                        org.bukkit.DyeColor.valueOf(String.valueOf(p.get("color"))), type));
                }
            }
            banner.setPatterns(patterns);
            banner.update();
        }

        // Containers, and shelves / chiseled bookshelves, which are not Containers. A fresh state AFTER
        // setBlockData, contents set on the snapshot BEFORE update(), or the inventory is cleared.
        if (raw.get("container_items") instanceof List<?> itemsData) {
            org.bukkit.block.BlockState state = block.getState();
            org.bukkit.inventory.Inventory snapshot = state instanceof org.bukkit.block.Container c ? c.getSnapshotInventory()
                : state instanceof io.papermc.paper.block.TileStateInventoryHolder t ? t.getSnapshotInventory() : null;
            if (snapshot != null) {
                List<ItemStack> overflow = new ArrayList<>();
                snapshot.setContents(BlockStructureScanner.deserializeInventory(
                    (List<Map<String, Object>>) itemsData, snapshot.getSize(), overflow));
                state.update();
                for (ItemStack extra : overflow) {
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), extra);
                }
            }
        }

        if (raw.get("sign_data") instanceof Map<?, ?> signData && block.getState() instanceof org.bukkit.block.Sign sign) {
            for (org.bukkit.block.sign.Side side : org.bukkit.block.sign.Side.values()) {
                org.bukkit.block.sign.SignSide signSide = sign.getSide(side);
                String key = side.name().toLowerCase();
                if (signData.get(key + "_lines") instanceof List<?> lines) {
                    for (int i = 0; i < lines.size() && i < 4; i++) {
                        signSide.line(i, net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
                            .gson().deserialize(String.valueOf(lines.get(i))));
                    }
                }
                if (signData.get(key + "_color") instanceof String color) signSide.setColor(org.bukkit.DyeColor.valueOf(color));
                if (signData.get(key + "_glowing") instanceof Boolean glowing) signSide.setGlowingText(glowing);
            }
            if (signData.get("waxed") instanceof Boolean waxed) sign.setWaxed(waxed);
            sign.update();
        }

        if (raw.containsKey("custom_name")) {
            org.bukkit.block.BlockState nameState = block.getState();
            if (nameState instanceof org.bukkit.Nameable n) {
                n.customName(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
                    .gson().deserialize(String.valueOf(raw.get("custom_name"))));
                nameState.update();
            }
        }
    }

    /** A part that can't be placed: its contents and its item form go on the ground at its cell. */
    @SuppressWarnings("unchecked")
    private static void dropPart(ShipModel.ModelPart part, Location blockLoc, ItemStack asItem) {
        Location at = blockLoc.clone().add(0.5, 0.5, 0.5);
        if (part.rawYaml.get("container_items") instanceof List<?> itemsData) {
            List<ItemStack> all = new ArrayList<>();
            for (ItemStack item : BlockStructureScanner.deserializeInventory((List<Map<String, Object>>) itemsData, 0, all)) {
                if (item != null) all.add(item);
            }
            for (ItemStack item : all) at.getWorld().dropItemNaturally(at, item);
        }
        Material m = part.block.getMaterial();
        if (asItem != null) at.getWorld().dropItemNaturally(at, asItem);
        else if (m.isItem()) at.getWorld().dropItemNaturally(at, new ItemStack(m));
    }
}
