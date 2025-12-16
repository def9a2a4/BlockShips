package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
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

        public ShipInfo(int blockCount, int totalWeight, float density, int maxHealth,
                        Integer currentHealth, float surfaceOffset, float airDensity, float waterDensity) {
            this.blockCount = blockCount;
            this.totalWeight = totalWeight;
            this.density = density;
            this.maxHealth = maxHealth;
            this.currentHealth = currentHealth;
            this.surfaceOffset = surfaceOffset;
            this.airDensity = airDensity;
            this.waterDensity = waterDensity;
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

    // Menu item slots - Left group: detect/info, Right group: assemble/align/disassemble
    private static final int DETECT_SLOT = 10;
    private static final int INFO_SLOT = 11;
    private static final int FIRE_CANNONS_SLOT = 12;
    private static final int ASSEMBLE_SLOT = 14;
    private static final int ALIGN_SLOT = 15;
    private static final int DISASSEMBLE_SLOT = 16;
    private static final int FORCE_DISASSEMBLE_SLOT = 17;  // Right of disassemble button

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
        }

        // Ship Info button - shows weight, density, and buoyancy info from last detection
        menu.setItem(INFO_SLOT, createInfoItem(wheelData));

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
        DETECT,
        ASSEMBLE,
        ALIGN,
        DISASSEMBLE,
        FORCE_DISASSEMBLE,
        INFO,
        FIRE_CANNONS,
        NONE
    }

    /**
     * Gets the action associated with a clicked slot.
     */
    public static MenuAction getActionFromSlot(int slot) {
        if (slot == DETECT_SLOT) {
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

        return new ShipInfo(blockCount, totalWeight, density, maxHealth, currentHealth,
                            surfaceOffset, airDensity, waterDensity);
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
}
