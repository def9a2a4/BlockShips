package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.HelpBookContent;
import anon.def9a2a4.blockships.ShipConfig;
import anon.def9a2a4.blockships.ShipRegistry;
import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import anon.def9a2a4.blockships.ItemUtil;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GUI menu for interacting with a ship wheel.
 * Provides options to assemble ship, align to grid, or disassemble ship.
 */
public class ShipWheelMenu {

    /**
     * Data class holding ship information for display.
     */
    public static class ShipInfo {
        public final int blockCount;
        public final int totalWeight;
        public final float density;
        public final int maxHealth;
        public final Integer currentHealth;  // null if not assembled
        public final float surfaceOffset;
        public final float airDensity;
        public final float waterDensity;
        // Ship stats
        public final int woolCount;
        public final int bannerCount;
        public final int sailPower;
        public final int engineCount;
        public final float ratio;

        public ShipInfo(int blockCount, int totalWeight, float density, int maxHealth,
                        Integer currentHealth, float surfaceOffset, float airDensity, float waterDensity,
                        int woolCount, int bannerCount, int sailPower, int engineCount, float ratio) {
            this.blockCount = blockCount;
            this.totalWeight = totalWeight;
            this.density = density;
            this.maxHealth = maxHealth;
            this.currentHealth = currentHealth;
            this.surfaceOffset = surfaceOffset;
            this.airDensity = airDensity;
            this.waterDensity = waterDensity;
            this.woolCount = woolCount;
            this.bannerCount = bannerCount;
            this.sailPower = sailPower;
            this.engineCount = engineCount;
            this.ratio = ratio;
        }
    }

    /**
     * Custom InventoryHolder that stores the ShipWheelData.
     * This allows retrieving the wheel data when menu items are clicked,
     * even when the ship is assembled and the wheel block is removed from world.
     */
    public static class ShipWheelMenuHolder implements InventoryHolder {
        private final ShipWheelData wheelData;
        private Inventory inventory;

        public ShipWheelMenuHolder(ShipWheelData wheelData) {
            this.wheelData = wheelData;
        }

        public ShipWheelData getWheelData() {
            return wheelData;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private static final String MENU_TITLE = ChatColor.DARK_BLUE + "Ship Wheel";
    private static final int MENU_SIZE = 27;  // 3 rows

    // Help icon texture (question mark)
    private static final String HELP_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGE5OWIwNWI5YTFkYjRkMjliNWU2NzNkNzdhZTU0YTc3ZWFiNjY4MTg1ODYwMzVjOGEyMDA1YWViODEwNjAyYSJ9fX0=";

    // Help content loaded from help_book.yml via HelpBookContent
    private static String[][] getHelpSections() {
        return HelpBookContent.getSections();
    }

    // Menu item slots - Left group: detect/info, Right group: assemble/align/disassemble
    private static final int HELP_SLOT = 0;
    private static final int CAMERA_MINUS_SLOT = 4;   // Decrease camera distance
    private static final int CAMERA_PLUS_SLOT = 5;    // Increase camera distance
    private static final int DETECT_SLOT = 10;
    private static final int INFO_SLOT = 11;
    private static final int FIRE_CANNONS_SLOT = 12;
    private static final int ASSEMBLE_SLOT = 14;
    private static final int ALIGN_SLOT = 15;
    private static final int DISASSEMBLE_SLOT = 16;
    private static final int FORCE_DISASSEMBLE_SLOT = 17;  // Right of disassemble button
    private static final int HIGHLIGHT_SEATS_SLOT = 19;    // Below detect slot (row 3)

    /**
     * Opens the ship wheel menu for a player.
     *
     * @param player The player to show the menu to
     * @param wheelData The ship wheel data
     */
    public static void openMenu(Player player, ShipWheelData wheelData) {
        // Create custom holder to store wheelData reference
        ShipWheelMenuHolder holder = new ShipWheelMenuHolder(wheelData);
        Inventory menu = Bukkit.createInventory(holder, MENU_SIZE, MENU_TITLE);
        holder.setInventory(menu);

        boolean isAssembled = wheelData.isAssembled();

        // Help/Info button (always available)
        menu.setItem(HELP_SLOT, createHelpItem());

        // Detect Ship button (always available)
        ItemStack detectItem = new ItemStack(Material.ENDER_EYE);
        ItemMeta detectMeta = detectItem.getItemMeta();
        if (detectMeta != null) {
            detectMeta.setDisplayName(ChatColor.AQUA + "Detect Ship");
            detectMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Preview which blocks will be included",
                ChatColor.GRAY + "Shows block count and total weight",
                ChatColor.GRAY + "Spawns particles to visualize ship"
            ));
            detectItem.setItemMeta(detectMeta);
        }
        menu.setItem(DETECT_SLOT, detectItem);

        // Assemble Ship button (only if not assembled)
        if (!isAssembled) {
            ItemStack assembleItem = new ItemStack(Material.OAK_BOAT);
            ItemMeta assembleMeta = assembleItem.getItemMeta();
            if (assembleMeta != null) {
                assembleMeta.setDisplayName(ChatColor.GREEN + "Assemble Ship");
                assembleMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Convert detected blocks into a ship"
                ));
                assembleItem.setItemMeta(assembleMeta);
            }
            menu.setItem(ASSEMBLE_SLOT, assembleItem);
        }

        // Align to Grid and Disassemble buttons (only if assembled)
        if (isAssembled) {
            // Align to Grid button
            ItemStack alignItem = new ItemStack(Material.COMPASS);
            ItemMeta alignMeta = alignItem.getItemMeta();
            if (alignMeta != null) {
                alignMeta.setDisplayName(ChatColor.YELLOW + "Align to Grid");
                alignMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Snap ship position and rotation",
                    ChatColor.GRAY + "to the nearest block grid"
                ));
                alignItem.setItemMeta(alignMeta);
            }
            menu.setItem(ALIGN_SLOT, alignItem);

            // Disassemble Ship button
            ItemStack disassembleItem = new ItemStack(Material.IRON_PICKAXE);
            ItemMeta disassembleMeta = disassembleItem.getItemMeta();
            if (disassembleMeta != null) {
                disassembleMeta.setDisplayName(ChatColor.RED + "Disassemble Ship");
                disassembleMeta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Convert ship back into blocks",
                    ChatColor.GRAY + "Will align to grid first"
                ));
                disassembleItem.setItemMeta(disassembleMeta);
            }
            menu.setItem(DISASSEMBLE_SLOT, disassembleItem);

            // Force Disassemble button - only shown after a failed disassembly with conflicts
            if (wheelData.canForceDisassemble()) {
                BlockStructureScanner.PlacementConflicts conflicts = wheelData.getLastDisassemblyConflicts();
                ItemStack forceItem = new ItemStack(Material.TNT);
                ItemMeta forceMeta = forceItem.getItemMeta();
                if (forceMeta != null) {
                    forceMeta.setDisplayName(ChatColor.DARK_RED + "Force Disassemble");
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.RED + "⚠ WARNING ⚠");
                    if (conflicts.fragile > 0) {
                        lore.add(ChatColor.YELLOW + "" + conflicts.fragile + " fragile block(s) will be destroyed");
                        lore.add(ChatColor.GRAY + "(grass, flowers, leaves, etc.)");
                    }
                    if (conflicts.hard > 0) {
                        lore.add(ChatColor.RED + "" + conflicts.hard + " ship block(s) will be LOST");
                        lore.add(ChatColor.GRAY + "(solid blocks in the way)");
                    }
                    lore.add("");
                    lore.add(ChatColor.GRAY + "Click to force disassembly");
                    forceMeta.setLore(lore);
                    forceItem.setItemMeta(forceMeta);
                }
                menu.setItem(FORCE_DISASSEMBLE_SLOT, forceItem);
            }

            // Fire All Cannons button - only shown if ship has cannons
            ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
            if (ship != null && !ship.model.cannons.isEmpty()) {
                ItemStack fireItem = new ItemStack(Material.FIRE_CHARGE);
                ItemMeta fireMeta = fireItem.getItemMeta();
                if (fireMeta != null) {
                    fireMeta.setDisplayName(ChatColor.GOLD + "Fire All Cannons");
                    fireMeta.setLore(Arrays.asList(
                        ChatColor.GRAY + "Fire all " + ship.model.cannons.size() + " cannon(s)",
                        ChatColor.GRAY + "at once"
                    ));
                    fireItem.setItemMeta(fireMeta);
                }
                menu.setItem(FIRE_CANNONS_SLOT, fireItem);
            }

            // Camera distance adjustment buttons (for custom ships)
            if (ship != null) {
                float currentDistance = wheelData.getCameraDistance();
                if (currentDistance < 0) {
                    // Not yet set - calculate default from block count
                    currentDistance = ShipWheelData.calculateDefaultCameraDistance(ship.model.blockCount);
                }
                int displayDistance = Math.round(currentDistance);

                // Decrease button
                ItemStack minusItem = new ItemStack(Material.RED_STAINED_GLASS_PANE);
                ItemMeta minusMeta = minusItem.getItemMeta();
                if (minusMeta != null) {
                    minusMeta.setDisplayName(ChatColor.RED + "- Camera Distance");
                    minusMeta.setLore(Arrays.asList(
                        ChatColor.GRAY + "Current: " + ChatColor.WHITE + displayDistance,
                        ChatColor.GRAY + "Decrease third-person camera distance",
                        ChatColor.DARK_GRAY + "(Range: 4 - 32)"
                    ));
                    minusItem.setItemMeta(minusMeta);
                }
                menu.setItem(CAMERA_MINUS_SLOT, minusItem);

                // Increase button
                ItemStack plusItem = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
                ItemMeta plusMeta = plusItem.getItemMeta();
                if (plusMeta != null) {
                    plusMeta.setDisplayName(ChatColor.GREEN + "+ Camera Distance");
                    plusMeta.setLore(Arrays.asList(
                        ChatColor.GRAY + "Current: " + ChatColor.WHITE + displayDistance,
                        ChatColor.GRAY + "Increase third-person camera distance",
                        ChatColor.DARK_GRAY + "(Range: 4 - 32)"
                    ));
                    plusItem.setItemMeta(plusMeta);
                }
                menu.setItem(CAMERA_PLUS_SLOT, plusItem);
            }
        }

        // Ship Info button - shows weight, density, and buoyancy info from last detection
        menu.setItem(INFO_SLOT, createInfoItem(wheelData));

        // Highlight Seats button - always shown
        menu.setItem(HIGHLIGHT_SEATS_SLOT, createHighlightSeatsItem(wheelData));

        player.openInventory(menu);
    }

    /**
     * Checks if an inventory is a ship wheel menu.
     */
    public static boolean isShipWheelMenu(Inventory inventory) {
        return inventory.getSize() == MENU_SIZE &&
               ChatColor.stripColor(inventory.getType().name()).equals("CHEST") &&
               inventory.getViewers().size() > 0;
    }

    /**
     * Represents an action that can be taken from the ship wheel menu.
     */
    public enum MenuAction {
        HELP,
        DETECT,
        ASSEMBLE,
        ALIGN,
        DISASSEMBLE,
        FORCE_DISASSEMBLE,
        INFO,
        FIRE_CANNONS,
        HIGHLIGHT_SEATS,
        CAMERA_DISTANCE_DECREASE,
        CAMERA_DISTANCE_INCREASE,
        NONE
    }

    /**
     * Gets the action associated with a clicked slot.
     */
    public static MenuAction getActionFromSlot(int slot) {
        if (slot == HELP_SLOT) {
            return MenuAction.HELP;
        } else if (slot == DETECT_SLOT) {
            return MenuAction.DETECT;
        } else if (slot == ASSEMBLE_SLOT) {
            return MenuAction.ASSEMBLE;
        } else if (slot == ALIGN_SLOT) {
            return MenuAction.ALIGN;
        } else if (slot == DISASSEMBLE_SLOT) {
            return MenuAction.DISASSEMBLE;
        } else if (slot == FORCE_DISASSEMBLE_SLOT) {
            return MenuAction.FORCE_DISASSEMBLE;
        } else if (slot == INFO_SLOT) {
            return MenuAction.INFO;
        } else if (slot == FIRE_CANNONS_SLOT) {
            return MenuAction.FIRE_CANNONS;
        } else if (slot == HIGHLIGHT_SEATS_SLOT) {
            return MenuAction.HIGHLIGHT_SEATS;
        } else if (slot == CAMERA_MINUS_SLOT) {
            return MenuAction.CAMERA_DISTANCE_DECREASE;
        } else if (slot == CAMERA_PLUS_SLOT) {
            return MenuAction.CAMERA_DISTANCE_INCREASE;
        }
        return MenuAction.NONE;
    }

    /**
     * Gets ship info from wheel data, calculating derived values.
     *
     * @param wheelData The ship wheel data containing detection results
     * @return ShipInfo or null if no ship detected
     */
    private static ShipInfo getShipInfo(ShipWheelData wheelData) {
        if (wheelData.getLastDetectedBlockCount() <= 0) {
            return null;
        }

        int blockCount = wheelData.getLastDetectedBlockCount();
        int totalWeight = wheelData.getLastDetectedWeight();
        float density = blockCount > 0 ? (float) totalWeight / blockCount : 0;

        // Get config values for float status thresholds
        BlockShipsPlugin plugin = (BlockShipsPlugin) Bukkit.getPluginManager().getPlugin("BlockShips");
        ShipConfig config = ShipConfig.load(plugin, "custom");
        float airDensity = config.airDensity;
        float waterDensity = config.waterDensity;

        // Use stored surface offset (calculated during detection)
        float surfaceOffset = wheelData.lastSurfaceOffset;

        // Health
        int maxHealth;
        Integer currentHealth = null;
        if (wheelData.isAssembled() && wheelData.getLastMaxHealth() > 0) {
            currentHealth = (int) Math.ceil(wheelData.getLastCurrentHealth());
            maxHealth = (int) wheelData.getLastMaxHealth();
        } else {
            int positiveWeight = wheelData.getLastDetectedPositiveWeight();
            maxHealth = Math.max(1, positiveWeight);
        }

        // Ship stats
        int woolCount = wheelData.getLastDetectedWoolCount();
        int bannerCount = wheelData.getLastDetectedBannerCount();
        int sailPower = woolCount * 3 + bannerCount * 7;

        // Compute power ratio
        int engineCount = wheelData.getLastDetectedEngineCount();
        int mass = Math.max(1, Math.abs(totalWeight));
        float sailRatio = (float) (config.basePower + sailPower) / mass;
        float nonEngineRatio = Math.min(sailRatio, config.sailCapRatio);
        // TODO: only count fueled engines once fuel system is implemented
        float engineBonus = (float) (engineCount * config.enginePower) / mass;
        float ratio = Math.min(nonEngineRatio + engineBonus, 1.0f);

        return new ShipInfo(blockCount, totalWeight, density, maxHealth, currentHealth,
                            surfaceOffset, airDensity, waterDensity,
                            woolCount, bannerCount, sailPower, engineCount, ratio);
    }

    /**
     * Creates the Ship Info item with current detection data.
     *
     * @param wheelData The ship wheel data containing detection results
     * @return The Ship Info book item with appropriate lore
     */
    private static ItemStack createInfoItem(ShipWheelData wheelData) {
        ItemStack infoItem = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = infoItem.getItemMeta();
        if (infoMeta != null) {
            infoMeta.setDisplayName(ChatColor.GOLD + "Ship Info");
            List<String> lore = new ArrayList<>();

            ShipInfo info = getShipInfo(wheelData);
            if (info != null) {
                lore.add(ChatColor.GRAY + "Blocks: " + ChatColor.WHITE + info.blockCount);
                lore.add(ChatColor.GRAY + "Total Weight: " + ChatColor.WHITE + info.totalWeight);

                if (info.currentHealth != null) {
                    lore.add(ChatColor.GRAY + "Health: " + ChatColor.RED + "❤ " + info.currentHealth + " / " + info.maxHealth);
                } else {
                    lore.add(ChatColor.GRAY + "Max Health: " + ChatColor.RED + "❤ " + info.maxHealth);
                }

                lore.add(ChatColor.GRAY + "Density: " + ChatColor.WHITE + String.format("%.2f", info.density));
                lore.add(ChatColor.GRAY + "Surface Offset: " + ChatColor.AQUA + String.format("%.2f", info.surfaceOffset) + " blocks");

                // Float status
                if (info.density < info.airDensity) {
                    lore.add(ChatColor.BLUE + "Airship");
                } else if (info.density < info.waterDensity) {
                    lore.add(ChatColor.GREEN + "Floats well");
                } else if (info.density < info.waterDensity + 0.5f) {
                    lore.add(ChatColor.YELLOW + "Sits low in water");
                } else {
                    lore.add(ChatColor.RED + "Sits very low");
                }

                // Ship stats
                lore.add("");
                if (info.woolCount > 0 || info.bannerCount > 0) {
                    if (info.woolCount > 0) {
                        lore.add(ChatColor.GRAY + "Wool: " + ChatColor.WHITE + info.woolCount + ChatColor.GRAY + " (" + (info.woolCount * 3) + " pts)");
                    }
                    if (info.bannerCount > 0) {
                        lore.add(ChatColor.GRAY + "Banners: " + ChatColor.WHITE + info.bannerCount + ChatColor.GRAY + " (" + (info.bannerCount * 7) + " pts)");
                    }
                }
                if (info.engineCount > 0) {
                    lore.add(ChatColor.GRAY + "Engines: " + ChatColor.WHITE + info.engineCount);
                }
                lore.add(ChatColor.GRAY + "Power Ratio: " + ChatColor.YELLOW + String.format("%.2f", info.ratio) + ChatColor.GRAY + " / 1.00");

                // Show effective speed as percentage of default
                int speedPercent = Math.round(info.ratio / 0.7f * 100);
                lore.add(ChatColor.GRAY + "Speed: " + ChatColor.GREEN + speedPercent + "%");
            } else {
                lore.add(ChatColor.GRAY + "No ship detected yet");
                lore.add(ChatColor.GRAY + "Click to detect ship");
            }

            infoMeta.setLore(lore);
            infoItem.setItemMeta(infoMeta);
        }
        return infoItem;
    }

    /**
     * Updates the Ship Info item in an existing inventory without closing/reopening the menu.
     *
     * @param inventory The inventory to update
     * @param wheelData The ship wheel data containing detection results
     */
    public static void updateInfoItem(Inventory inventory, ShipWheelData wheelData) {
        inventory.setItem(INFO_SLOT, createInfoItem(wheelData));
    }

    /**
     * Updates the camera distance buttons in an existing inventory without closing/reopening.
     *
     * @param inventory The inventory to update
     * @param wheelData The ship wheel data containing camera distance setting
     * @param ship The ship instance (for calculating default from block count)
     */
    public static void updateCameraItems(Inventory inventory, ShipWheelData wheelData, ShipInstance ship) {
        float currentDistance = wheelData.getCameraDistance();
        if (currentDistance < 0) {
            currentDistance = ShipWheelData.calculateDefaultCameraDistance(ship.model.blockCount);
        }
        int displayDistance = Math.round(currentDistance);

        // Update minus button
        ItemStack minusItem = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta minusMeta = minusItem.getItemMeta();
        if (minusMeta != null) {
            minusMeta.setDisplayName(ChatColor.RED + "- Camera Distance");
            minusMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Current: " + ChatColor.WHITE + displayDistance,
                ChatColor.GRAY + "Decrease third-person camera distance",
                ChatColor.DARK_GRAY + "(Range: 4 - 32)"
            ));
            minusItem.setItemMeta(minusMeta);
        }
        inventory.setItem(CAMERA_MINUS_SLOT, minusItem);

        // Update plus button
        ItemStack plusItem = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta plusMeta = plusItem.getItemMeta();
        if (plusMeta != null) {
            plusMeta.setDisplayName(ChatColor.GREEN + "+ Camera Distance");
            plusMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Current: " + ChatColor.WHITE + displayDistance,
                ChatColor.GRAY + "Increase third-person camera distance",
                ChatColor.DARK_GRAY + "(Range: 4 - 32)"
            ));
            plusItem.setItemMeta(plusMeta);
        }
        inventory.setItem(CAMERA_PLUS_SLOT, plusItem);
    }

    /**
     * Creates the Highlight Seats button item.
     *
     * @param wheelData The ship wheel data containing detection results
     * @return The Highlight Seats item with seat count info
     */
    private static ItemStack createHighlightSeatsItem(ShipWheelData wheelData) {
        ItemStack seatsItem = new ItemStack(Material.OAK_STAIRS);
        ItemMeta seatsMeta = seatsItem.getItemMeta();
        if (seatsMeta != null) {
            seatsMeta.setDisplayName(ChatColor.YELLOW + "Seats");
            List<String> lore = new ArrayList<>();

            int seatCount = 0;
            int occupiedCount = 0;

            if (wheelData.isAssembled()) {
                // Get info from assembled ship
                ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
                if (ship != null) {
                    seatCount = ship.model.seats.size();
                    // Count occupied seats
                    for (org.bukkit.entity.Shulker seat : ship.seatShulkers) {
                        if (seat != null && seat.isValid()) {
                            boolean hasPlayer = seat.getPassengers().stream()
                                .anyMatch(p -> p instanceof Player);
                            if (hasPlayer) {
                                occupiedCount++;
                            }
                        }
                    }
                }
            } else {
                // Get info from last detection
                seatCount = wheelData.getLastDetectedSeatCount();
            }

            if (seatCount > 0) {
                int passengers = seatCount - 1;
                lore.add(ChatColor.GRAY + "Seats: " + ChatColor.WHITE + seatCount +
                    ChatColor.GRAY + " (1 driver + " + passengers + " passengers)");
                if (wheelData.isAssembled()) {
                    lore.add(ChatColor.GRAY + "Occupied: " + ChatColor.WHITE + occupiedCount + " / " + seatCount);
                }
            } else {
                lore.add(ChatColor.GRAY + "Seats: " + ChatColor.WHITE + "unknown");
            }

            lore.add("");
            lore.add(ChatColor.DARK_GRAY + "Click to highlight");

            seatsMeta.setLore(lore);
            seatsItem.setItemMeta(seatsMeta);
        }
        return seatsItem;
    }

    /**
     * Creates the Help item with a question mark player head texture.
     *
     * @return The Help item with lore
     */
    private static ItemStack createHelpItem() {
        ItemStack helpItem = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) helpItem.getItemMeta();
        if (skullMeta != null) {
            ItemUtil.applyPlayerHeadTextureFromBase64(skullMeta, HELP_TEXTURE,
                Bukkit.getPluginManager().getPlugin("BlockShips"));
            skullMeta.setDisplayName(ChatColor.AQUA + "Info");
            skullMeta.setLore(createHelpLore());
            helpItem.setItemMeta(skullMeta);
        }
        return helpItem;
    }

    /**
     * Creates the lore text for the Help item (condensed, no blank lines).
     *
     * @return List of lore strings
     */
    private static List<String> createHelpLore() {
        List<String> lore = new ArrayList<>();
        for (String[] section : getHelpSections()) {
            lore.add(ChatColor.YELLOW + section[0]);
            lore.add(ChatColor.GRAY + section[1]);
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Click to open help book");
        return lore;
    }

    /**
     * Opens a help book for the player with detailed ship information.
     *
     * @param player The player to show the book to
     */
    public static void openHelpBook(Player player) {
        HelpBookContent.openBook(player);
    }
}
