package anon.def9a2a4.blockships;

import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Handles opening interaction GUIs for workstation blocks on ships.
 */
public final class InteractionBlockHandler {

    private InteractionBlockHandler() {} // Prevent instantiation

    /**
     * Opens the appropriate GUI for a block material.
     * @param player The player to open the GUI for
     * @param blockMaterial The material of the block being interacted with
     * @return true if an interaction GUI was opened, false otherwise
     */
    public static boolean openInteraction(Player player, Material blockMaterial) {
        switch (blockMaterial) {
            case CRAFTING_TABLE:
                player.openWorkbench(null, true);
                return true;

            case ANVIL:
            case CHIPPED_ANVIL:
            case DAMAGED_ANVIL:
                player.openAnvil(null, true);
                return true;

            case ENCHANTING_TABLE:
                player.openEnchanting(null, true);
                return true;

            case SMITHING_TABLE:
                player.openSmithingTable(null, true);
                return true;

            case LOOM:
                player.openLoom(null, true);
                return true;

            case STONECUTTER:
                player.openStonecutter(null, true);
                return true;

            case CARTOGRAPHY_TABLE:
                player.openCartographyTable(null, true);
                return true;

            case GRINDSTONE:
                player.openGrindstone(null, true);
                return true;

            default:
                return false;
        }
    }
}
