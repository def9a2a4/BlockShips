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

    /** Admin policy: when true, system/crash disassembly places blocks into regions instead of dropping them. */
    private final boolean systemPlacesAnyway;

    public WorldGuardHookImpl(boolean systemPlacesAnyway) {
        this.systemPlacesAnyway = systemPlacesAnyway;
    }

    @Override
    public boolean isBuildDenied(Location loc, @Nullable Player player) {
        return isBuildDenied(loc, player, false);
    }

    // IMPORTANT: callers MUST gate this behind mightRestrict()/mightRestrictFailClosed() first. In a world
    // with region support disabled (useRegions:false), WorldGuard resolves the query via PermissiveRegionSet,
    // whose queryValue(BUILD) returns DENY (verified in 7.0.17) — so this method would return true (denied)
    // EVERYWHERE in such a world. The gate returns false for those worlds and short-circuits the scan, so it
    // is load-bearing for correctness, not merely an O(1) optimization. Do not call this ungated.
    @Override
    public boolean isBuildDenied(Location loc, @Nullable Player player, boolean failClosedOnError) {
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
            logWgErrorOnce(t); // fail-open (or fail-closed for the assembly gate) on a transient WG fault
            return failClosedOnError;
        }
    }

    @Override
    public boolean mightRestrict(World world) {
        try {
            RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world)); // @Nullable when region data is unloaded/failed/disabled
            // NOTE: a world whose only build restriction is WorldGuard's global "deny build by default"
            // knob with NO regions defined (size()==0) is NOT gated here. Admins wanting BlockShips to
            // honor a world-wide deny must define a `__global__` region (which counts toward size()).
            return rm != null && rm.size() > 0;
        } catch (Throwable t) {
            logWgErrorOnce(t);
            return false;
        }
    }

    @Override
    public boolean mightRestrictFailClosed(World world) {
        try {
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            RegionManager rm = WorldGuard.getInstance().getPlatform().getRegionContainer().get(weWorld);
            if (rm != null) {
                return rm.size() > 0; // regions loaded: gate iff any exist (same as mightRestrict)
            }
            // rm == null means region support is either DISABLED for this world or FAILED to load - and
            // get() can't tell them apart. useRegions is the distinguishing signal (what WG's own query
            // branches on): disabled (false) → no gate, matching mightRestrict and the drop paths; failed
            // (true) → fail closed, matching WG's FailedLoadRegionSet denying BUILD in the per-cell query
            // (which our gate would otherwise short-circuit away, reopening the block-laundering exploit).
            return WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(weWorld).useRegions;
        } catch (Throwable t) {
            logWgErrorOnce(t);
            return true; // genuine fault → fail closed
        }
    }

    @Override
    public boolean systemPathPlacesInRegions() {
        return systemPlacesAnyway;
    }

    private static void logWgErrorOnce(Throwable t) {
        if (!loggedWgError) {
            loggedWgError = true;
            Bukkit.getLogger().warning("[BlockShips] WorldGuard query failed, failing open: " + t);
        }
    }

    /** Re-arms the one-shot fail-open log so a later fault is reported again (called on reload). */
    public static void resetErrorThrottle() {
        loggedWgError = false;
    }
}
