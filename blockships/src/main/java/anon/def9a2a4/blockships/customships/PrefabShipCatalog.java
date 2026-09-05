package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.DisplayShip;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists BlockShips' prefab ships in the defCoreLib catalog, flat under the {@code blockships} node.
 *
 * <p>A prefab ship is a ship-<b>kit</b> item (a stamped vanilla item carrying a {@code ship_type} PDC),
 * not a defCoreLib custom block, so it can't be a registered {@code CustomHeadBlock}. Instead each is a
 * catalog <i>contribution</i>: a browsable entry whose admin right-click give hands the real kit — the
 * exact item {@code /blockships give <shipType>} produces, via {@link DisplayShip#createShipKit}. The give
 * is gated by the catalog's own {@code corelib.admin} check, so only admins can pull one.
 */
public final class PrefabShipCatalog {

    private PrefabShipCatalog() {}

    /** Config keys of the bundled prefab ships (under {@code ships:}), in catalog order. */
    private static final List<String> PREFAB_SHIPS = List.of("smallship", "bigship", "smallairship");

    public static void register(BlockShipsPlugin plugin) {
        anon.def9a2a4.corelib.CustomBlockRegistry registry;
        try {
            registry = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry();
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not add prefab ships to the DefCoreLib catalog: " + t.getMessage());
            return;
        }
        for (String key : PREFAB_SHIPS) {
            if (!plugin.getConfig().contains("ships." + key)) continue;   // config trimmed / renamed
            try {
                ItemStack icon = kitFor(plugin, key);
                ItemMeta meta = icon.getItemMeta();
                Component name = (meta != null && meta.displayName() != null)
                        ? meta.displayName()
                        : Component.text(key);
                List<Component> lore = (meta != null && meta.lore() != null)
                        ? meta.lore()
                        : new ArrayList<>();
                registry.registerCatalogContribution(new anon.def9a2a4.corelib.CustomBlockRegistry.CatalogContribution(
                        "blockships:" + key + "_kit",
                        "blockships",
                        name,
                        lore,
                        icon,
                        () -> kitFor(plugin, key)));   // rebuilt per give, so each is a fresh, well-formed kit
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not add prefab ship '" + key + "' to the catalog: " + t.getMessage());
            }
        }
    }

    /** The ship-kit item — identical to {@code /blockships give <key>} (default banner + spruce). */
    private static ItemStack kitFor(BlockShipsPlugin plugin, String key) {
        return DisplayShip.createShipKit(key, new ItemStack(Material.WHITE_BANNER), "SPRUCE", plugin);
    }
}
