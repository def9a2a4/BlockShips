package anon.def9a2a4.blockships.integration;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Optional integration point for region-protection plugins (currently WorldGuard).
 *
 * <p>Accessed statically via {@link #get()} so the static block-placement engine
 * ({@code BlockStructureScanner}) can reach it without extra plumbing. The holder always
 * returns a non-null hook; when WorldGuard is absent or the integration is disabled it is a
 * {@link NoOpWorldGuardHook}, so behavior is byte-identical to having no integration at all.
 */
public interface WorldGuardHook {

    /**
     * @param loc    the candidate block location
     * @param player the acting player, or {@code null} for system/crash paths (treated as a non-member)
     * @return true if building at {@code loc} should be BLOCKED for {@code player}
     */
    boolean isBuildDenied(Location loc, @Nullable Player player);

    /**
     * Cheap O(1) gate: does this world have any regions that could restrict building?
     * Lets callers skip per-cell queries entirely in region-free worlds.
     */
    boolean mightRestrict(World world);

    /**
     * Admin policy for UNATTENDED/system disassembly (the {@code player == null} paths: crash/combat
     * death, {@code forcedisassembleall}) that would place blocks into a protected region.
     *
     * @return true if system paths should place blocks anyway (pre-integration wreck behavior);
     *         false (default) to drop those blocks as items. Player-driven force-disassembly ignores
     *         this and always respects the acting player's own build permission.
     */
    boolean systemPathPlacesInRegions();

    // ===== static holder =====

    WorldGuardHook[] HOLDER = { new NoOpWorldGuardHook() };

    /** The active hook. Never null; defaults to a no-op. */
    static WorldGuardHook get() {
        return HOLDER[0];
    }

    /** Installs a hook (or a no-op to disable). Called on enable/reload. */
    static void set(WorldGuardHook hook) {
        HOLDER[0] = (hook != null) ? hook : new NoOpWorldGuardHook();
    }
}
