package anon.def9a2a4.blockships.integration;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * No-op hook used when WorldGuard is not installed or the integration is disabled.
 * Never denies anything and reports no regions, so every call site short-circuits and
 * behaves exactly as it did before the integration existed.
 */
public final class NoOpWorldGuardHook implements WorldGuardHook {

    @Override
    public boolean isBuildDenied(Location loc, @Nullable Player player) {
        return false;
    }

    @Override
    public boolean mightRestrict(World world) {
        return false;
    }

    @Override
    public boolean systemPathPlacesInRegions() {
        // Irrelevant under NoOp (nothing is ever routed to drops), but keep the safe default.
        return false;
    }
}
