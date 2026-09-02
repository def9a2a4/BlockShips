package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.HelpBookContent;
import anon.def9a2a4.blockships.ShipConfig;
import anon.def9a2a4.blockships.ShipRegistry;
import anon.def9a2a4.blockships.ShipStats;
import anon.def9a2a4.blockships.ShipThrust;
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
        public final int mass;  // sum of positive block weights (for display + ratio)
        public final float density;
        public final int maxHealth;
        public final Integer currentHealth;  // null if not assembled
        public final float airDensity;
        public final float waterDensity;
        // Ship stats
        public final int woolCount;
        public final int bannerCount;
        // bbanners display-entity sails. Without these the lore listed only wool and banners above a
        // Sail Power total that DOES include the tiers, so the breakdown visibly failed to add up.
        public final int largeBannerCount;
        public final int hugeBannerCount;
        public final int sailPower;
        public final float sailCapRatio; // sail cap threshold (from config, e.g. 0.8)
        public final float sailRatio;  // uncapped sail ratio (before sail cap applied)
        public final float ratio;      // final ratio (with sail cap)
        public final boolean statsEnabled; // whether the power-to-mass stats system is active
        // Propulsion. Thrust used to be invisible here: the menu called the sail-only ShipStats
        // overload, which hardcodes turn and lift to zero and never reads thrustBlocks — so a ship
        // that demonstrably flew on thruster power reported none of it.
        public final float forwardRatio;
        public final float turnRatio;   // at rest: sails aid turning only once the ship is moving
        public final float liftRatio;
        public final ShipThrust.Totals thrust;
        public final boolean thrustIsLive; // false = docked "potential", nothing is actually powered

        public ShipInfo(int blockCount, int totalWeight, int mass, float density, int maxHealth,
                        Integer currentHealth, float airDensity, float waterDensity,
                        int woolCount, int bannerCount, int largeBannerCount, int hugeBannerCount,
                        int sailPower,
                        float sailCapRatio, float sailRatio, float ratio,
                        boolean statsEnabled,
                        float forwardRatio, float turnRatio, float liftRatio,
                        ShipThrust.Totals thrust, boolean thrustIsLive) {
            this.forwardRatio = forwardRatio;
            this.turnRatio = turnRatio;
            this.liftRatio = liftRatio;
            this.thrust = thrust;
            this.thrustIsLive = thrustIsLive;
            this.blockCount = blockCount;
            this.totalWeight = totalWeight;
            this.mass = mass;
            this.density = density;
            this.maxHealth = maxHealth;
            this.currentHealth = currentHealth;
            this.airDensity = airDensity;
            this.waterDensity = waterDensity;
            this.woolCount = woolCount;
            this.bannerCount = bannerCount;
            this.largeBannerCount = largeBannerCount;
            this.hugeBannerCount = hugeBannerCount;
            this.sailPower = sailPower;
            this.sailCapRatio = sailCapRatio;
            this.sailRatio = sailRatio;
            this.ratio = ratio;
            this.statsEnabled = statsEnabled;
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
    private static final int STATS_SLOT = 20;              // Below info slot (row 3)
    private static final int LOCK_SLOT = 13;               // Between fire-cannons and assemble

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

        // Show Ship button (always available)
        ItemStack detectItem = new ItemStack(Material.ENDER_EYE);
        ItemMeta detectMeta = detectItem.getItemMeta();
        if (detectMeta != null) {
            detectMeta.setDisplayName(ChatColor.AQUA + "Show Ship");
            detectMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Shows the blocks actually part of the ship",
                ChatColor.GRAY + "Shows block count and total weight",
                ChatColor.GRAY + "Spawns particles to visualize ship"
            ));
            detectItem.setItemMeta(detectMeta);
        }
        menu.setItem(DETECT_SLOT, detectItem);

        // Lock button (always available)
        menu.setItem(LOCK_SLOT, createLockItem(wheelData));

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
                    if (conflicts.protectedCount > 0) {
                        lore.add(ChatColor.GOLD + "" + conflicts.protectedCount + " block(s) in a protected region");
                        lore.add(ChatColor.GRAY + "(will drop as items, not placed)");
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
        menu.setItem(STATS_SLOT, createStatsItem(wheelData));

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
        TOGGLE_LOCK,
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
        } else if (slot == STATS_SLOT) {
            return MenuAction.INFO;  // Clicking stats banner also refreshes ship info
        } else if (slot == LOCK_SLOT) {
            return MenuAction.TOGGLE_LOCK;
        }
        return MenuAction.NONE;
    }

    /**
     * How long a docked ship's thrust scan stays good, as a backstop only — the entry also carries the
     * ship's own detect state, so any change a player makes and then re-detects invalidates it at once.
     * This bounds how long a scan can lag a change the player has NOT re-detected.
     */
    private static final long DOCKED_THRUST_TTL_MS = 10_000L;

    /**
     * Cached scan plus the detect state it was taken under. Those fields are compared on READ rather than
     * being folded into the key, which matters: as part of the key they minted a fresh permanent entry
     * every time a block count changed, so a player iterating on a hull — place a block, /detect, place,
     * /detect — leaked one entry per iteration for the life of the server.
     */
    private record CachedThrust(ShipThrust.Totals totals, long stamp,
                                int blockCount, org.bukkit.block.BlockFace facing, boolean locked) {
        boolean matches(ShipWheelData d) {
            return blockCount == d.getLastDetectedBlockCount()
                && facing == d.getFacing()
                && locked == d.isLocked();
        }
    }

    /**
     * One entry per wheel location, replaced in place. Bounded by the number of distinct wheels a player
     * has opened a menu on, and pruned whenever a wheel is removed (see {@link #forgetDockedThrust}).
     */
    private static final java.util.Map<String, CachedThrust> DOCKED_THRUST_CACHE = new java.util.HashMap<>();

    private static String dockedThrustKey(org.bukkit.Location wheelLoc) {
        return wheelLoc.getWorld().getName() + ":" + wheelLoc.getBlockX()
             + ":" + wheelLoc.getBlockY() + ":" + wheelLoc.getBlockZ();
    }

    /** Drop a wheel's cached scan — call when the wheel is removed, mirroring ShipWheelAnchors.forget. */
    public static void forgetDockedThrust(org.bukkit.Location wheelLoc) {
        if (wheelLoc == null || wheelLoc.getWorld() == null) return;
        DOCKED_THRUST_CACHE.remove(dockedThrustKey(wheelLoc));
    }

    /**
     * Potential thrust for a ship with no model, classified straight from the world.
     *
     * <p>A docked ship has never been through {@code BlockStructureScanner}, so there is no thrust list
     * to read — the hull has to be found first. That is a full flood fill, hence the cache: opening the
     * menu repeatedly must not re-scan a thousand blocks each time.
     *
     * <p>An entry is reused only while the wheel's detect state is unchanged. Time alone was not enough:
     * the Ship Info button runs a fresh detect that prints one set of numbers to chat and then rendered
     * lore from a cache up to ten seconds old, so a single click could disagree with itself. Comparing
     * the detect state means the re-detect that changed the ship also drops the entry — and a wheel
     * broken and rebuilt at the same coordinates cannot inherit the previous ship's figures.
     */
    private static ShipThrust.Totals dockedThrust(BlockShipsPlugin plugin, ShipWheelData wheelData) {
        org.bukkit.Location wheelLoc = wheelData.getBlockLocation();
        if (wheelLoc == null || wheelLoc.getWorld() == null) return ShipThrust.Totals.NONE;

        String key = dockedThrustKey(wheelLoc);
        long now = System.currentTimeMillis();
        CachedThrust cached = DOCKED_THRUST_CACHE.get(key);
        if (cached != null && now - cached.stamp() < DOCKED_THRUST_TTL_MS && cached.matches(wheelData)) {
            return cached.totals();
        }

        ShipThrust.Totals totals = ShipThrust.Totals.NONE;
        try {
            java.util.Set<org.bukkit.Location> cells;
            if (wheelData.isLocked()) {
                // A locked ship is exactly its frozen set — the raw glue cells plus the wheel — and NOT
                // a flood fill. detectShip and scanFrozen both take this branch; without it the stats
                // page would count propellers stacked against a docked hull that will never assemble
                // with it, and contradict the chat readout from the same click. rawGlueCells is the
                // right source: gluedCells additionally pulls in the sticky closure, which is precisely
                // the growth a lock exists to prevent.
                cells = new java.util.HashSet<>(ShipGlue.rawGlueCells(wheelLoc.getBlock()));
                cells.add(wheelLoc);
            } else {
                int maxShipSize = plugin.getConfig().getInt("custom-ships.max-ship-size", 1000);
                int maxScanSize = plugin.getConfig().getInt("custom-ships.max-scan-size", 5000);
                // Silent detect: no particles, no waterline shulker, no chat. Same call the glue anchor
                // provider uses for its connector set. Returns failure for a missing wheel block, so an
                // assembled ship cannot scan the dock it left behind.
                var result = new anon.def9a2a4.blockships.blockconfig.ShipDetector(maxShipSize, maxScanSize)
                    .detectShipDetailed(wheelLoc, ShipGlue.gluedCells(wheelLoc.getBlock()));
                cells = result.isSuccess() ? result.getBlocks() : null;
            }
            if (cells != null) {
                totals = ShipThrust.scanWorld(plugin, cells,
                    BlockStructureScanner.blockFaceToYaw(wheelData.getFacing()));
            }
        } catch (Throwable t) {
            // A ship over the size limit, or a detect fault. Showing no thrust is the right failure —
            // never let a stats readout stop a menu from opening.
            totals = ShipThrust.Totals.NONE;
        }
        DOCKED_THRUST_CACHE.put(key, new CachedThrust(totals, now,
            wheelData.getLastDetectedBlockCount(), wheelData.getFacing(), wheelData.isLocked()));
        return totals;
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
        int weightedBlockCount = wheelData.getLastDetectedWeightedBlockCount();
        int totalWeight = wheelData.getLastDetectedWeight();
        // Use weighted block count for density (matches ShipModel.getDensity() and physics)
        float density = weightedBlockCount > 0 ? (float) totalWeight / weightedBlockCount : 0;

        // Get config values for float status thresholds
        BlockShipsPlugin plugin = (BlockShipsPlugin) Bukkit.getPluginManager().getPlugin("BlockShips");
        ShipConfig config = ShipConfig.load(plugin, "custom");
        float airDensity = config.airDensity;
        float waterDensity = config.waterDensity;

        // Health. The 1024 clamp mirrors BlockStructureScanner's, which is what the ship's actual
        // max-health attribute gets. Without it the same hull reads its raw mass here and 1024 once
        // assembled — and, because lastMaxHealth is not persisted, an assembled ship falls into this
        // branch after every restart too.
        int maxHealth;
        Integer currentHealth = null;
        if (wheelData.isAssembled() && wheelData.getLastMaxHealth() > 0) {
            currentHealth = (int) Math.ceil(wheelData.getLastCurrentHealth());
            maxHealth = (int) wheelData.getLastMaxHealth();
        } else {
            int shipMass = wheelData.getLastDetectedPositiveWeight();
            maxHealth = (int) Math.min(1024.0, Math.max(1, shipMass));
        }

        // Ship stats - use live ShipInstance data when assembled
        int woolCount, bannerCount, largeBannerCount, hugeBannerCount, mass;
        if (wheelData.isAssembled()) {
            ShipInstance ship = ShipRegistry.byId(wheelData.getAssembledShipUUID());
            if (ship != null && ship.model != null) {
                woolCount = ship.model.woolCount;
                bannerCount = ship.model.bannerCount;
                largeBannerCount = ship.model.largeBannerCount;
                hugeBannerCount = ship.model.hugeBannerCount;
                mass = Math.max(1, ship.model.mass);
            } else {
                // Ship not found (destroyed?) - use detection data
                woolCount = wheelData.getLastDetectedWoolCount();
                bannerCount = wheelData.getLastDetectedBannerCount();
                largeBannerCount = wheelData.getLastDetectedLargeBannerCount();
                hugeBannerCount = wheelData.getLastDetectedHugeBannerCount();
                mass = Math.max(1, wheelData.getLastDetectedPositiveWeight());
            }
        } else {
            // Unassembled - use detection data
            woolCount = wheelData.getLastDetectedWoolCount();
            bannerCount = wheelData.getLastDetectedBannerCount();
            largeBannerCount = wheelData.getLastDetectedLargeBannerCount();
            hugeBannerCount = wheelData.getLastDetectedHugeBannerCount();
            mass = Math.max(1, wheelData.getLastDetectedPositiveWeight());
        }

        // This menu is a READER. Every sail tier above comes either from the assembled model or from
        // the scalars detectShip stored — it never rescans the world for them. dockedThrust below does
        // build its own cell set, on a short TTL cache; do not reach for it here, or the stats page and
        // the detect chat will drift apart again the moment that cache goes stale.
        //
        // Both branches go through a THRUST-AWARE ShipStats form. The sail-only overloads zeroed out
        // turn and lift and never looked at thrustBlocks, which is why propulsion used to fly a ship and
        // show up nowhere in its own stats page; they are deleted now.
        ShipStats stats;
        ShipThrust.Totals thrust;
        boolean thrustIsLive;
        ShipInstance assembled = wheelData.isAssembled()
            ? ShipRegistry.byId(wheelData.getAssembledShipUUID()) : null;

        if (assembled != null && assembled.model != null) {
            // Live: only blocks actually powered (or, for a thruster, burning) are counted, so the
            // menu agrees with what the ship is doing rather than with what it could do.
            thrust = ShipThrust.totalsFor(plugin, assembled.mechanism, assembled.model);
            thrustIsLive = true;
            // speedFrac 0: sails aid turning in proportion to speed, and a player reading a menu is
            // parked. The turn figure is therefore "at rest", which is also the honest one to compare
            // reaction wheels against. The lore says so.
            stats = ShipStats.of(config, assembled.model, thrust, 0f);
        } else {
            // Docked (or the model is gone): classify from the world. Potential, not live.
            thrust = dockedThrust(plugin, wheelData);
            thrustIsLive = false;
            stats = ShipStats.of(config, woolCount, bannerCount, largeBannerCount, hugeBannerCount,
                mass, totalWeight, thrust, 0f);
        }

        return new ShipInfo(blockCount, totalWeight, mass, density, maxHealth, currentHealth,
                            airDensity, waterDensity,
                            woolCount, bannerCount, largeBannerCount, hugeBannerCount, stats.sailPower,
                            config.sailCapRatio, stats.sailRatio, stats.ratio,
                            config.statsEnabled,
                            stats.forwardRatio, stats.turnRatio, stats.liftRatio,
                            thrust, thrustIsLive);
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

                // Density with colored float status
                ChatColor densityColor;
                String floatStatus;
                if (info.density < info.airDensity) {
                    densityColor = ChatColor.AQUA;
                    floatStatus = "Airship";
                } else if (info.density < info.waterDensity) {
                    densityColor = ChatColor.GREEN;
                    floatStatus = "Floats well";
                } else if (info.density < info.waterDensity + 0.5f) {
                    densityColor = ChatColor.YELLOW;
                    floatStatus = "Sits low";
                } else {
                    densityColor = ChatColor.RED;
                    floatStatus = "Sits very low";
                }
                lore.add(ChatColor.GRAY + "Density: " + densityColor + String.format("%.2f", info.density)
                    + ChatColor.GRAY + " (" + densityColor + floatStatus + ChatColor.GRAY + ")");

                // Ship stats (simplified - detailed breakdown in stats item below)
                lore.add("");
                if (info.statsEnabled) {
                    // forwardRatio, NOT the sails-only ratio: this is the number ShipPhysics actually
                    // drives top speed from, so a thruster-driven ship no longer reports the speed of
                    // sails it does not have.
                    int speedPercent = Math.round(info.forwardRatio * 100);
                    String maxTag = info.forwardRatio >= 1.0f ? ChatColor.AQUA + " (max)" : "";
                    lore.add(ChatColor.GRAY + "Speed: " + speedColor(speedPercent) + speedPercent + "%" + maxTag);
                    if (speedPercent < 50) {
                        lore.add(ChatColor.DARK_PURPLE + "(add sails, or propellers along the hull!)");
                    }
                } else {
                    lore.add(ChatColor.GRAY + "Stats system disabled - fixed speed");
                }
            } else {
                lore.add(ChatColor.GRAY + "No ship detected yet");
                lore.add(ChatColor.GRAY + "Click to show ship");
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
        inventory.setItem(STATS_SLOT, createStatsItem(wheelData));
    }

    /**
     * Creates the detailed Ship Stats item (banner) with full breakdown.
     */
    private static ItemStack createStatsItem(ShipWheelData wheelData) {
        ItemStack statsItem = new ItemStack(Material.WHITE_BANNER);
        ItemMeta statsMeta = statsItem.getItemMeta();
        if (statsMeta != null) {
            statsMeta.setDisplayName(ChatColor.GOLD + "Ship Stats");
            List<String> lore = new ArrayList<>();

            ShipInfo info = getShipInfo(wheelData);
            if (info != null) {
              if (!info.statsEnabled) {
                // Stats system off: composition/points/ratio are inert - show only mass + a note.
                lore.add("");
                lore.add(ChatColor.GRAY + "Mass: " + ChatColor.WHITE + info.mass);
                lore.add(ChatColor.GRAY + "Stats system disabled - fixed speed");
              } else {
                BlockShipsPlugin plugin = (BlockShipsPlugin) Bukkit.getPluginManager().getPlugin("BlockShips");
                ShipConfig config = ShipConfig.load(plugin, "custom");
                // Sail breakdown
                if (info.woolCount > 0) {
                    lore.add(ChatColor.GRAY + "Wool: " + ChatColor.WHITE + info.woolCount
                        + ChatColor.GRAY + " (" + (info.woolCount * config.woolPower) + " pts)");
                }
                if (info.bannerCount > 0) {
                    lore.add(ChatColor.GRAY + "Banners: " + ChatColor.WHITE + info.bannerCount
                        + ChatColor.GRAY + " (" + (info.bannerCount * config.bannerPower) + " pts)");
                }
                if (info.largeBannerCount > 0) {
                    lore.add(ChatColor.GRAY + "Large Banners: " + ChatColor.WHITE + info.largeBannerCount
                        + ChatColor.GRAY + " (" + (info.largeBannerCount * config.largeBannerPower) + " pts)");
                }
                if (info.hugeBannerCount > 0) {
                    lore.add(ChatColor.GRAY + "Huge Banners: " + ChatColor.WHITE + info.hugeBannerCount
                        + ChatColor.GRAY + " (" + (info.hugeBannerCount * config.hugeBannerPower) + " pts)");
                }

                // Sail power with cap indicator
                if (info.sailPower > 0) {
                    int sailCapPoints = Math.round(info.sailCapRatio * info.mass);
                    int effectiveSailPts = config.basePower + info.sailPower;  // base + sail
                    if (effectiveSailPts > sailCapPoints) {
                        lore.add(ChatColor.GRAY + "Sail Power: " + ChatColor.WHITE + info.sailPower + " pts"
                            + ChatColor.YELLOW + " (capped at " + sailCapPoints + " pts)");
                    } else {
                        lore.add(ChatColor.GRAY + "Sail Power: " + ChatColor.WHITE + info.sailPower + " pts");
                    }
                }

                lore.add("");
                lore.add(ChatColor.GRAY + "Mass: " + ChatColor.WHITE + info.mass);
                // Effective power after caps (matches physics formula):
                // cappedSailPower = min(basePower + sailPower, sailCapRatio * mass), capped at 1.0 * mass total
                int rawSailPower = config.basePower + info.sailPower;
                int cappedSailPower = Math.min(rawSailPower, Math.round(info.sailCapRatio * info.mass));
                int effectivePower = Math.min(cappedSailPower, info.mass);
                lore.add(ChatColor.GRAY + "Effective Power: " + ChatColor.WHITE + effectivePower
                    + ChatColor.GRAY + " / " + info.mass + " pts");
                // "Sail Ratio", not "Power Ratio": this block — Effective Power, the cap, this line — is
                // the SAIL budget and always was. Leaving it labelled "Power" put it in direct
                // contradiction with the thrust-derived "Forward" figure a few lines below.
                lore.add(ChatColor.GRAY + "Sail Ratio: " + ChatColor.YELLOW
                    + String.format("%.2f", info.ratio) + ChatColor.GRAY + " / "
                    + String.format("%.2f", info.sailCapRatio) + " cap");

                int speedPercent = Math.round(info.forwardRatio * 100);
                String maxTag = info.forwardRatio >= 1.0f ? ChatColor.AQUA + " (max)" : "";
                lore.add(ChatColor.GRAY + "Speed: " + speedColor(speedPercent) + speedPercent + "%" + maxTag);

                appendPropulsionLore(lore, info, config);
              }
            } else {
                lore.add(ChatColor.GRAY + "Show ship first");
            }

            statsMeta.setLore(lore);
            statsItem.setItemMeta(statsMeta);
        }
        return statsItem;
    }

    /**
     * The propulsion half of the stats page: what the ship's thrust is doing to each of the three
     * ratios, and how much of it is actually running.
     *
     * <p>Silent on a ship with no propulsion — a sailing boat should not have to read three zeroes.
     */
    private static void appendPropulsionLore(List<String> lore, ShipInfo info, ShipConfig config) {
        ShipThrust.Totals t = info.thrust;
        if (t == null || t.total() <= 0) return;

        lore.add("");
        // The engine count lives here rather than on the driver's action bar: it is a fact about how
        // the ship is built, not about how it is moving, and it was crowding the speed meter.
        String countColor = !info.thrustIsLive ? ChatColor.GRAY.toString()
            : t.powered() >= t.total() ? ChatColor.GREEN.toString()
            : t.powered() > 0 ? ChatColor.YELLOW.toString() : ChatColor.RED.toString();
        lore.add(ChatColor.GRAY + "Propulsion: " + countColor + t.powered()
            + ChatColor.GRAY + " / " + t.total() + (info.thrustIsLive ? " running" : " aboard"));
        if (!info.thrustIsLive) {
            // Docked: nothing is powered, so these are the numbers the ship would hit with everything
            // running. Saying so is the difference between a forecast and a lie.
            lore.add(ChatColor.DARK_GRAY + "  (potential — dock power is off)");
        }

        if (t.axial() > 0)      lore.add(ChatColor.GRAY + "  Forward thrust: " + ChatColor.WHITE + t.axial() + " pts");
        if (t.turning() > 0)    lore.add(ChatColor.GRAY + "  Turning thrust: " + ChatColor.WHITE + t.turning() + " pts");
        if (t.vertical() > 0)   lore.add(ChatColor.GRAY + "  Lift thrust: " + ChatColor.WHITE + t.vertical() + " pts");

        lore.add("");
        lore.add(ChatColor.GRAY + "Forward: " + ratioColor(info.forwardRatio)
            + String.format("%.2f", info.forwardRatio));
        // Sails only aid turning in proportion to speed, and a ship whose menu is open is parked — so
        // this is the stopped figure. Anything thrust-driven (a side propeller as much as a reaction
        // wheel) keeps working at a standstill, so this is the fair number for comparing them.
        lore.add(ChatColor.GRAY + "Turn: " + ratioColor(info.turnRatio)
            + String.format("%.2f", info.turnRatio) + ChatColor.DARK_GRAY + " (at rest)");
        if (t.vertical() > 0) {
            // Four-way, matching what the flight model actually does: full surplus climbs at speed, a
            // little surplus climbs slowly, exactly enough holds, and anything short sinks — at a rate
            // set by how much is missing, so show that rate rather than making the player infer it.
            float lift = info.liftRatio;
            float fullClimb = 1f + config.climbSurplusFull;
            // Cap the printed figure. Past fullClimb the ship already climbs at its maximum, so the
            // extra is not information — and a huge propeller on a light hull reads "1250%", which is
            // technically true and useless.
            String liftText = lift > fullClimb
                ? Math.round(fullClimb * 100) + "%+"
                : Math.round(lift * 100) + "%";
            String liftColor = lift >= 1f ? ChatColor.GREEN.toString()
                : lift >= 0.75f ? ChatColor.YELLOW.toString() : ChatColor.RED.toString();
            String verdict;
            if (lift >= fullClimb) {
                verdict = ChatColor.AQUA + " (climbs)";
            } else if (lift >= 1f + config.climbSurplusFull * 0.1f) {
                // A tenth of the surplus is roughly a tenth of climb speed — below that it is a hover
                // with rounding on top, and calling it "climbing" would be a lie the player can measure.
                verdict = ChatColor.AQUA + " (climbs slowly)";
            } else if (lift >= 1f) {
                verdict = ChatColor.GRAY + " (holds altitude)";
            } else {
                verdict = ChatColor.DARK_GRAY + String.format(" (sinks %.1f blocks/s)",
                    ShipStats.sinkBlocksPerSecond(config, lift));
            }
            // Docked, every block counts as running, so this whole verdict is a forecast for a ship
            // with its engines lit. Say so — the Propulsion line's hedge is too far above to carry here,
            // and "180% (climbs)" on a hull with no power source aboard is exactly the promise
            // ShipThrust.scanWorld's contract warns against making.
            if (!info.thrustIsLive) verdict += ChatColor.DARK_GRAY + " once powered";
            lore.add(ChatColor.GRAY + "Lift: " + liftColor + liftText + verdict);
        }
    }

    /** Shared colour ramp for a 0..1 ratio. */
    private static String ratioColor(float ratio) {
        if (ratio >= 0.9f) return ChatColor.AQUA.toString();
        if (ratio >= 0.6f) return ChatColor.GREEN.toString();
        if (ratio >= 0.3f) return ChatColor.YELLOW.toString();
        return ChatColor.RED.toString();
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
     * Re-render the lock button in place, without closing and reopening the menu.
     * Mirrors {@link #updateCameraItems} — a toggle that flickers the whole GUI reads as a bug.
     */
    public static void updateLockItem(Inventory inventory, ShipWheelData wheelData) {
        inventory.setItem(LOCK_SLOT, createLockItem(wheelData));
    }

    /**
     * The lock button: freeze which blocks belong to this ship, so docking it next to a pile of
     * blocks no longer swallows them.
     */
    private static ItemStack createLockItem(ShipWheelData wheelData) {
        boolean locked = wheelData.isLocked();
        ItemStack item = new ItemStack(locked ? Material.IRON_TRAPDOOR : Material.TRIPWIRE_HOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName((locked ? ChatColor.AQUA + "Structure Locked" : ChatColor.GRAY + "Structure Unlocked"));
            List<String> lore = new ArrayList<>();
            if (locked) {
                lore.add(ChatColor.GRAY + "Frozen blocks: " + ChatColor.WHITE
                    + (ShipGlue.glueCount(wheelData.getBlockLocation().getBlock()) + 1));
                lore.add(ChatColor.GRAY + "This ship assembles from exactly");
                lore.add(ChatColor.GRAY + "these blocks. Nothing new is picked up,");
                lore.add(ChatColor.GRAY + "but you can still glue/unglue any of them.");
                lore.add("");
                lore.add(ChatColor.GRAY + "Blocks that are gone are simply skipped.");
                lore.add(ChatColor.YELLOW + "Click" + ChatColor.GRAY + " to unlock");
                lore.add(ChatColor.YELLOW + "Shift-click" + ChatColor.GRAY + " to re-freeze from the");
                lore.add(ChatColor.GRAY + "current structure (after repairs or additions)");
            } else {
                lore.add(ChatColor.GRAY + "This ship picks up any connected");
                lore.add(ChatColor.GRAY + "allowed block when it assembles —");
                lore.add(ChatColor.GRAY + "including whatever is stacked against");
                lore.add(ChatColor.GRAY + "the hull while it is docked.");
                lore.add("");
                lore.add(ChatColor.YELLOW + "Click" + ChatColor.GRAY + " to freeze the current structure");
            }
            meta.setLore(lore);
            if (locked) meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                              org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
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
        lore.add(ChatColor.GRAY + "WASD to move, Space is up, Sprint is down");
        lore.add(ChatColor.GRAY + "Place ship's wheel on ship and click 'Assemble' (boat)");
        lore.add(ChatColor.GRAY + "Right-click ship to board, Sneak to dismount");
        // Keep this in step with help_book.yml's "Weight & Buoyancy" and "Propulsion" sections — this
        // is what a player reads BEFORE opening the book, so a stale line here contradicts it.
        lore.add(ChatColor.GRAY + "Sails make you go faster,");
        lore.add(ChatColor.GRAY + "glowstone and other glowing blocks make you float.");
        lore.add(ChatColor.GRAY + "Enough floating blocks -> airship,");
        lore.add(ChatColor.GRAY + "or enough upward thrust to fly a heavy one");
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Click for more info -- Captain's Manual");
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

    /**
     * Returns a ChatColor for speed percentage display.
     * Red (&lt;40%) -&gt; Gold (40-64%) -&gt; Yellow (65-84%) -&gt; Green (85-99%) -&gt; Aqua (100%, maxed)
     *
     * <p>The thresholds moved down when the figure switched from the sails-only ratio to
     * {@code forwardRatio}. That ratio is {@code clamp01}'d, so the reading can never exceed 100 — the
     * old 125 tier was unreachable and the old 100 tier fired only at exact saturation, leaving three
     * usable colours out of five. Aqua now means "maxed out", which is the thing worth signalling.
     */
    private static ChatColor speedColor(int speedPercent) {
        if (speedPercent >= 100) return ChatColor.AQUA;
        if (speedPercent >= 85) return ChatColor.GREEN;
        if (speedPercent >= 65) return ChatColor.YELLOW;
        if (speedPercent >= 40) return ChatColor.GOLD;
        return ChatColor.RED;
    }
}
