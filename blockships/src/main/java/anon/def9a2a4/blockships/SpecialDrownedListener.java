package anon.def9a2a4.blockships;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Random;

/**
 * Listener that spawns special drowned mobs holding ship wheels.
 * These drowned have custom equipment and always drop their ship wheel on death.
 */
public class SpecialDrownedListener implements Listener {

    private final BlockShipsPlugin plugin;
    private final Random random = new Random();

    // Scoreboard tags to identify special drowned and their mounts
    private static final String SPECIAL_DROWNED_TAG = "blockships:special_drowned";
    private static final String SPECIAL_DROWNED_MOUNT_TAG = "blockships:special_drowned_mount";

    // Cached config values
    private boolean enabled;
    private double spawnChance;
    private String headTexture;
    private boolean holdsTrident;
    private float dropChance;
    private boolean ridesNautilus;

    // Runtime check for Nautilus availability
    private boolean nautilusAvailable = false;

    public SpecialDrownedListener(BlockShipsPlugin plugin) {
        this.plugin = plugin;
        checkNautilusAvailability();
        reloadConfig();
    }

    /**
     * Check if the Nautilus entity type is available on this server version.
     */
    private void checkNautilusAvailability() {
        try {
            EntityType.valueOf("ZOMBIE_NAUTILUS");
            nautilusAvailable = true;
        } catch (IllegalArgumentException e) {
            nautilusAvailable = false;
            plugin.getLogger().info("Nautilus entity not available, special drowned will have Speed instead.");
        }
    }

    /**
     * Reloads configuration values from config.yml.
     * Called on plugin reload.
     */
    public void reloadConfig() {
        enabled = plugin.getConfig().getBoolean("special-drowned.enabled", true);
        spawnChance = plugin.getConfig().getDouble("special-drowned.spawn-chance", 0.02);
        headTexture = plugin.getConfig().getString("special-drowned.head-texture",
                "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTcwYzY5NjJlZWE5ZDFjYjBiNTAzYWI3YjZiODFmYzUwNGI4YmQwMWY4YzQxNTYxZTBjMDIwYjZkMzY2YmQwMiJ9fX0=");
        holdsTrident = plugin.getConfig().getBoolean("special-drowned.holds-trident", true);
        dropChance = (float) plugin.getConfig().getDouble("special-drowned.drop-chance", 1.0);
        ridesNautilus = plugin.getConfig().getBoolean("special-drowned.rides-nautilus", true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!enabled) return;
        if (event.getEntityType() != EntityType.DROWNED) return;

        // Only affect natural spawns and zombie-to-drowned conversions
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL
                && reason != CreatureSpawnEvent.SpawnReason.DROWNED) {
            return;
        }

        Drowned drowned = (Drowned) event.getEntity();

        // Skip baby drowned
        if (drowned.isBaby()) return;

        // Roll spawn chance
        if (random.nextDouble() > spawnChance) return;

        // Apply special equipment and mount
        applySpecialDrownedEquipment(drowned);
        applyNautilusMount(drowned);
    }

    /**
     * Spawns a special drowned at the given location.
     * Used by the /blockships spawndrowned command.
     *
     * @param location The location to spawn the drowned
     * @return The spawned drowned entity
     */
    public Drowned spawnSpecialDrowned(Location location) {
        World world = location.getWorld();
        if (world == null) return null;

        Drowned drowned = world.spawn(location, Drowned.class);
        applySpecialDrownedEquipment(drowned);
        applyNautilusMount(drowned);
        return drowned;
    }

    /**
     * Applies all special equipment to a drowned.
     */
    private void applySpecialDrownedEquipment(Drowned drowned) {
        EntityEquipment equipment = drowned.getEquipment();
        if (equipment == null) return;

        // Main hand: Trident (if configured)
        if (holdsTrident) {
            equipment.setItemInMainHand(new ItemStack(Material.TRIDENT));
            equipment.setItemInMainHandDropChance(0.0f); // Never drop trident
        }

        // Off hand: Ship wheel (always drops)
        ItemStack shipWheel = plugin.getDisplayShip().createShipWheelItem();
        equipment.setItemInOffHand(shipWheel);
        equipment.setItemInOffHandDropChance(dropChance);

        // Helmet: Custom player head
        ItemStack head = createSpecialHead();
        equipment.setHelmet(head);
        equipment.setHelmetDropChance(0.0f); // Never drop the head

        // Chestplate: Leather chestplate with Protection 4
        ItemStack chestplate = createProtectionChestplate();
        equipment.setChestplate(chestplate);
        equipment.setChestplateDropChance(0.0f); // Never drop the chestplate

        // Tag for identification
        drowned.addScoreboardTag(SPECIAL_DROWNED_TAG);

        // Make persistent (won't despawn)
        drowned.setRemoveWhenFarAway(false);
    }

    /**
     * Applies the nautilus mount or speed fallback.
     */
    private void applyNautilusMount(Drowned drowned) {
        if (!ridesNautilus) return;

        if (nautilusAvailable) {
            // Spawn a nautilus and mount the drowned on it
            World world = drowned.getWorld();
            Location location = drowned.getLocation();

            Entity nautilus = world.spawnEntity(location, EntityType.valueOf("ZOMBIE_NAUTILUS"));

            // Tame the nautilus if API supports it
            if (nautilus instanceof Tameable tameable) {
                tameable.setTamed(true);
            }

            // Make persistent (won't despawn)
            if (nautilus instanceof LivingEntity livingNautilus) {
                livingNautilus.setRemoveWhenFarAway(false);
            }

            nautilus.addPassenger(drowned);
            nautilus.addScoreboardTag(SPECIAL_DROWNED_MOUNT_TAG);
        } else {
            // Fallback: give Speed 1 (infinite duration)
            drowned.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED,
                    Integer.MAX_VALUE,
                    0, // Level 1 (0-indexed)
                    false, // ambient
                    false  // particles
            ));
        }
    }

    /**
     * Creates a player head with the configured custom texture.
     */
    private ItemStack createSpecialHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null && headTexture != null && !headTexture.isEmpty()) {
            ItemUtil.applyPlayerHeadTextureFromBase64(meta, headTexture, plugin);
            head.setItemMeta(meta);
        }
        return head;
    }

    /**
     * Creates a leather chestplate with Protection 4.
     */
    private ItemStack createProtectionChestplate() {
        ItemStack chestplate = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemMeta meta = chestplate.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.PROTECTION, 4, true);
            chestplate.setItemMeta(meta);
        }
        return chestplate;
    }
}
