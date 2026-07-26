package anon.def9a2a4.blockships.integration;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.Association;              // NOTE: domains, NOT protection.association
import com.sk89q.worldguard.protection.association.Associables;
import com.sk89q.worldguard.protection.association.RegionAssociable;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;

/**
 * WorldGuard-backed implementation of {@link WorldGuardHook}.
 *
 * <p><b>Must only be loaded/instantiated when WorldGuard is confirmed present</b> (referenced
 * exclusively from behind a {@code Class.forName} guard in the plugin's enable path), so a
 * missing WorldGuard class can never hard-crash startup.
 *
 * <p>Uses the built-in {@link Flags#BUILD} flag, so no custom-flag {@code onLoad} registration
 * is needed. The two subtle-but-critical choices — {@code != ALLOW} (not {@code == DENY}) and a
 * non-null {@code NON_MEMBER} associable for the no-player path — are documented inline.
 */
public final class WorldGuardHookImpl implements WorldGuardHook {

    private static volatile boolean loggedWgError = false; // one-shot throttle for fail-open logging

    @Override
    public boolean isBuildDenied(Location loc, @Nullable Player player) {
        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();

            RegionAssociable subject;
            if (player != null) {
                LocalPlayer lp = WorldGuardPlugin.inst().wrapPlayer(player);
                // Region queries do NOT honor bypass permissions — must check explicitly.
                if (WorldGuard.getInstance().getPlatform().getSessionManager()
                        .hasBypass(lp, BukkitAdapter.adapt(loc.getWorld()))) {
                    return false;
                }
                subject = lp;
            } else {
                // Flags.BUILD.requiresSubject() == true: a null subject THROWS.
                // NON_MEMBER denies inside any region (drop items) and is ALLOW outside (place).
                subject = Associables.constant(Association.NON_MEMBER);
            }

            StateFlag.State state = container.createQuery()
                    .queryState(BukkitAdapter.adapt(loc), subject, Flags.BUILD);

            // Membership-based denial resolves to null (not DENY); outside any region BUILD defaults
            // to ALLOW. So "denied" == "not explicitly ALLOW". Do NOT use == DENY here.
            return state != StateFlag.State.ALLOW;
        } catch (Throwable t) {
            logWgErrorOnce(t); // fail-open: a transient WG fault must not destroy a ship or block building
            return false;
        }
    }

    @Override
    public boolean mightRestrict(World world) {
        try {
            RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world)); // @Nullable when region data is unloaded/failed/disabled
            return rm != null && rm.size() > 0;
        } catch (Throwable t) {
            logWgErrorOnce(t);
            return false;
        }
    }

    private static void logWgErrorOnce(Throwable t) {
        if (!loggedWgError) {
            loggedWgError = true;
            Bukkit.getLogger().warning("[BlockShips] WorldGuard query failed, failing open: " + t);
        }
    }
}
