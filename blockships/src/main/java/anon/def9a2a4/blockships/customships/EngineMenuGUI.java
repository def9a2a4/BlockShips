package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.ship.ShipInstance;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Set;

/**
 * Custom inventory GUI for managing ship engine fuel.
 * Each engine has 3 fuel slots. Only valid furnace fuels are accepted.
 */
public class EngineMenuGUI {

    /** Materials that can be used as fuel in ship engines (matches vanilla furnace fuels). */
    private static final Set<Material> VALID_FUELS = Set.of(
        Material.COAL, Material.CHARCOAL, Material.COAL_BLOCK,
        Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
        Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG, Material.CHERRY_LOG,
        Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS, Material.JUNGLE_PLANKS,
        Material.ACACIA_PLANKS, Material.DARK_OAK_PLANKS, Material.MANGROVE_PLANKS, Material.CHERRY_PLANKS,
        Material.BAMBOO_PLANKS,
        Material.STICK, Material.BAMBOO,
        Material.LAVA_BUCKET, Material.BLAZE_ROD,
        Material.DRIED_KELP_BLOCK,
        Material.OAK_SLAB, Material.SPRUCE_SLAB, Material.BIRCH_SLAB, Material.JUNGLE_SLAB,
        Material.ACACIA_SLAB, Material.DARK_OAK_SLAB, Material.MANGROVE_SLAB, Material.CHERRY_SLAB,
        Material.BAMBOO_SLAB,
        Material.BOOKSHELF, Material.CRAFTING_TABLE, Material.NOTE_BLOCK, Material.JUKEBOX,
        Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
        Material.LECTERN, Material.COMPOSTER, Material.CARTOGRAPHY_TABLE,
        Material.FLETCHING_TABLE, Material.SMITHING_TABLE, Material.LOOM,
        Material.BOW, Material.CROSSBOW, Material.FISHING_ROD,
        Material.WOODEN_SWORD, Material.WOODEN_PICKAXE, Material.WOODEN_AXE, Material.WOODEN_SHOVEL, Material.WOODEN_HOE,
        Material.OAK_FENCE, Material.SPRUCE_FENCE, Material.BIRCH_FENCE, Material.JUNGLE_FENCE,
        Material.ACACIA_FENCE, Material.DARK_OAK_FENCE, Material.MANGROVE_FENCE, Material.CHERRY_FENCE,
        Material.OAK_FENCE_GATE, Material.SPRUCE_FENCE_GATE, Material.BIRCH_FENCE_GATE, Material.JUNGLE_FENCE_GATE,
        Material.ACACIA_FENCE_GATE, Material.DARK_OAK_FENCE_GATE, Material.MANGROVE_FENCE_GATE, Material.CHERRY_FENCE_GATE
    );

    /**
     * Fuel slot indices in the 9-slot GUI (maps 1:1 to blast furnace container indices).
     * Note: vanilla hoppers only feed blast furnace slot 1 (fuel). Slots 0 and 2 must be
     * loaded manually via this GUI. This is a known limitation.
     */
    public static final int[] FUEL_SLOTS = {0, 1, 2};
    /** Status display item slot. */
    public static final int STATUS_SLOT = 4;

    /**
     * Custom holder to store engine context.
     */
    public static class EngineMenuHolder implements InventoryHolder {
        private final ShipInstance ship;
        private final int engineBlockIndex;
        private Inventory inventory;

        public EngineMenuHolder(ShipInstance ship, int engineBlockIndex) {
            this.ship = ship;
            this.engineBlockIndex = engineBlockIndex;
        }

        public ShipInstance getShip() { return ship; }
        public int getEngineBlockIndex() { return engineBlockIndex; }

        @Override
        public Inventory getInventory() { return inventory; }
        public void setInventory(Inventory inv) { this.inventory = inv; }
    }

    /**
     * Opens the engine fuel GUI for a specific engine on a ship.
     */
    public static void open(org.bukkit.entity.Player player, ShipInstance ship, int engineBlockIndex) {
        EngineMenuHolder holder = new EngineMenuHolder(ship, engineBlockIndex);
        Inventory gui = Bukkit.createInventory(holder, 9, ChatColor.DARK_GRAY + "Ship Engine");
        holder.setInventory(gui);

        // Fill non-fuel slots with glass panes
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }
        for (int i = 0; i < 9; i++) {
            gui.setItem(i, filler);
        }

        // Load existing fuel from wheel data
        if (ship.wheelData != null) {
            ItemStack[] fuelSlots = ship.wheelData.getEngineFuelSlots(engineBlockIndex);
            for (int i = 0; i < FUEL_SLOTS.length; i++) {
                gui.setItem(FUEL_SLOTS[i], fuelSlots[i]); // null = empty slot
            }
        } else {
            for (int slot : FUEL_SLOTS) {
                gui.setItem(slot, null);
            }
        }

        // Status item
        gui.setItem(STATUS_SLOT, createStatusItem(ship, engineBlockIndex));

        player.openInventory(gui);
    }

    /**
     * Creates the status display item showing engine state.
     */
    private static ItemStack createStatusItem(ShipInstance ship, int engineBlockIndex) {
        int burnTicks = ship.wheelData != null ? ship.wheelData.getEngineBurnTicks(engineBlockIndex) : 0;
        boolean isBurning = burnTicks > 0;

        // Check if fuel items are present in slots (even if not burning yet)
        boolean hasFuelItems = false;
        if (ship.wheelData != null) {
            ItemStack[] slots = ship.wheelData.getAllEngineFuelSlots().get(engineBlockIndex);
            if (slots != null) {
                for (ItemStack item : slots) {
                    if (item != null && item.getType() != Material.AIR) {
                        hasFuelItems = true;
                        break;
                    }
                }
            }
        }

        ItemStack status;
        if (isBurning) {
            status = new ItemStack(Material.FURNACE);
            ItemMeta meta = status.getItemMeta();
            if (meta != null) {
                int seconds = burnTicks / 20;
                meta.setDisplayName(ChatColor.GREEN + "Engine Running");
                meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Fuel remaining: " + ChatColor.WHITE + seconds + "s",
                    ChatColor.DARK_GRAY + "Click to refresh"
                ));
                status.setItemMeta(meta);
            }
        } else if (hasFuelItems) {
            status = new ItemStack(Material.FURNACE);
            ItemMeta meta = status.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + "Engine Ready");
                meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Fuel loaded — will burn when sailing",
                    ChatColor.DARK_GRAY + "Click to refresh"
                ));
                status.setItemMeta(meta);
            }
        } else {
            status = new ItemStack(Material.BLAST_FURNACE);
            ItemMeta meta = status.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GRAY + "Engine Idle");
                meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Add fuel to the left slots"
                ));
                status.setItemMeta(meta);
            }
        }
        return status;
    }

    /**
     * Checks if a material is a valid furnace fuel.
     */
    public static boolean isValidFuel(Material material) {
        return VALID_FUELS.contains(material);
    }

    /**
     * Returns the burn time in ticks for a fuel material.
     */
    public static int getBurnTime(Material material) {
        return switch (material) {
            case LAVA_BUCKET -> 20000;
            case COAL_BLOCK -> 16000;
            case BLAZE_ROD -> 2400;
            case COAL, CHARCOAL -> 1600;
            case OAK_LOG, SPRUCE_LOG, BIRCH_LOG, JUNGLE_LOG, ACACIA_LOG, DARK_OAK_LOG, MANGROVE_LOG, CHERRY_LOG -> 300;
            case OAK_PLANKS, SPRUCE_PLANKS, BIRCH_PLANKS, JUNGLE_PLANKS, ACACIA_PLANKS, DARK_OAK_PLANKS, MANGROVE_PLANKS, CHERRY_PLANKS, BAMBOO_PLANKS -> 300;
            case OAK_SLAB, SPRUCE_SLAB, BIRCH_SLAB, JUNGLE_SLAB, ACACIA_SLAB, DARK_OAK_SLAB, MANGROVE_SLAB, CHERRY_SLAB, BAMBOO_SLAB -> 150;
            case STICK -> 100;
            case BAMBOO -> 50;
            case DRIED_KELP_BLOCK -> 4000;
            case BOOKSHELF, LECTERN -> 300;
            case CHEST, TRAPPED_CHEST, BARREL -> 300;
            case CRAFTING_TABLE, NOTE_BLOCK, JUKEBOX -> 300;
            case COMPOSTER, CARTOGRAPHY_TABLE, FLETCHING_TABLE, SMITHING_TABLE, LOOM -> 300;
            case BOW, CROSSBOW, FISHING_ROD -> 300;
            case WOODEN_SWORD, WOODEN_PICKAXE, WOODEN_AXE, WOODEN_SHOVEL, WOODEN_HOE -> 200;
            case OAK_FENCE, SPRUCE_FENCE, BIRCH_FENCE, JUNGLE_FENCE, ACACIA_FENCE, DARK_OAK_FENCE, MANGROVE_FENCE, CHERRY_FENCE -> 300;
            case OAK_FENCE_GATE, SPRUCE_FENCE_GATE, BIRCH_FENCE_GATE, JUNGLE_FENCE_GATE, ACACIA_FENCE_GATE, DARK_OAK_FENCE_GATE, MANGROVE_FENCE_GATE, CHERRY_FENCE_GATE -> 300;
            default -> 0;
        };
    }

    /**
     * Checks if a slot index is a fuel slot.
     */
    public static boolean isFuelSlot(int slot) {
        for (int s : FUEL_SLOTS) {
            if (s == slot) return true;
        }
        return false;
    }

    /**
     * Refreshes the status item in an open engine GUI (click-to-refresh).
     */
    public static void refreshStatus(EngineMenuHolder holder) {
        Inventory inv = holder.getInventory();
        inv.setItem(STATUS_SLOT, createStatusItem(holder.getShip(), holder.getEngineBlockIndex()));
    }

    /**
     * Saves fuel slot contents from the GUI back to wheel data.
     * Called when the GUI is closed.
     */
    public static void saveFuelState(EngineMenuHolder holder) {
        ShipInstance ship = holder.getShip();
        if (ship.wheelData == null) return;

        Inventory inv = holder.getInventory();
        ItemStack[] slots = new ItemStack[3];
        for (int i = 0; i < FUEL_SLOTS.length; i++) {
            ItemStack item = inv.getItem(FUEL_SLOTS[i]);
            slots[i] = (item != null && item.getType() != Material.AIR) ? item.clone() : null;
        }
        ship.wheelData.setEngineFuelSlots(holder.getEngineBlockIndex(), slots);
    }

    // ===== Placed (unassembled) engine block GUI =====

    /**
     * Holder for a placed engine block's fuel GUI (no ShipInstance).
     */
    public static class EngineBlockMenuHolder implements org.bukkit.inventory.InventoryHolder {
        private final org.bukkit.block.Block block;
        private Inventory inventory;

        public EngineBlockMenuHolder(org.bukkit.block.Block block) {
            this.block = block;
        }

        public org.bukkit.block.Block getBlock() { return block; }

        @Override
        public Inventory getInventory() { return inventory; }
        public void setInventory(Inventory inv) { this.inventory = inv; }
    }

    /**
     * Opens the custom fuel GUI for a placed (unassembled) engine block.
     * Reads fuel from the blast furnace's container inventory, writes back on close.
     */
    public static void openForBlock(org.bukkit.entity.Player player, org.bukkit.block.Block block) {
        EngineBlockMenuHolder holder = new EngineBlockMenuHolder(block);
        Inventory gui = Bukkit.createInventory(holder, 9, ChatColor.DARK_GRAY + "Ship Engine");
        holder.setInventory(gui);

        // Fill with glass panes
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }
        for (int i = 0; i < 9; i++) {
            gui.setItem(i, filler);
        }

        // Load fuel from the blast furnace's container inventory
        if (block.getState() instanceof org.bukkit.block.Container container) {
            org.bukkit.inventory.Inventory blockInv = container.getSnapshotInventory();
            for (int i = 0; i < FUEL_SLOTS.length && i < blockInv.getSize(); i++) {
                gui.setItem(FUEL_SLOTS[i], blockInv.getItem(i));
            }
        }

        // Status item (always idle for placed blocks — no burn ticks)
        ItemStack status = new ItemStack(Material.BLAST_FURNACE);
        ItemMeta meta = status.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GRAY + "Engine Idle");
            meta.setLore(Arrays.asList(
                ChatColor.GRAY + "Add fuel, then assemble ship"
            ));
            status.setItemMeta(meta);
        }
        gui.setItem(STATUS_SLOT, status);

        player.openInventory(gui);
    }

    /**
     * Saves fuel from the GUI back to the placed blast furnace container.
     */
    public static void saveBlockFuelState(EngineBlockMenuHolder holder) {
        org.bukkit.block.Block block = holder.getBlock();
        if (!(block.getState() instanceof org.bukkit.block.Container)) return;

        org.bukkit.block.Container container = (org.bukkit.block.Container) block.getState();
        org.bukkit.inventory.Inventory blockInv = container.getSnapshotInventory();

        // Write fuel slots back (targeted, not full clear — preserves other container state)
        Inventory gui = holder.getInventory();
        for (int i = 0; i < FUEL_SLOTS.length && i < blockInv.getSize(); i++) {
            ItemStack item = gui.getItem(FUEL_SLOTS[i]);
            blockInv.setItem(i, (item != null && item.getType() != Material.AIR) ? item.clone() : null);
        }
        container.update();
    }
}
