package anon.def9a2a4.blockships.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * The two primitives for asking questions about a {@link Location} that may outlive its world.
 *
 * <p>Bukkit's {@code Location} holds a <b>weak</b> reference to its world. Once that reference is collected,
 * {@code getWorld()} does not return null — it throws {@code IllegalArgumentException("World unloaded")}
 * (see {@code Location.getWorld()}, which is a {@code Preconditions.checkArgument} on the dereferenced ref).
 * {@code getBlock()}, {@code getChunk()} and {@code isChunkLoaded()} all route through it, so they throw too.
 *
 * <p>That matters here because wheel records outlive worlds — a runtime world unload, a multiverse-style
 * setup — so every bare {@code getWorld()} on a record's cell is a latent throw on a path with no reason to
 * expect one. The failures are nasty out of proportion to the cause: one record in an unloaded world made
 * every right-click on <i>any</i> wheel throw (via a shared state resolver), and made every save throw,
 * which propagated out of {@code onDisable}.
 *
 * <p>{@code isWorldLoaded()} rather than a null check is deliberate for a second reason: it also rejects a
 * <b>stale</b> {@code World} object left behind by an unload/reload cycle, which {@code getWorld()} hands
 * back without complaint. Two records that straddle such a cycle would otherwise disagree permanently about
 * the same world.
 *
 * <p>Lives in {@code util} rather than on {@code ShipWheelManager} because {@code DisplayShip},
 * {@code ShipWheelMenu}, {@code ShipDetector} and {@code BlockShipsPlugin} all need it, and a private copy
 * per class is exactly how the world-name/World-object split below arose in the first place.
 */
public final class LocationUtil {

    private LocationUtil() {}

    /**
     * This location's world, or null if it does not currently have a live one.
     *
     * <p>Total: never throws, for any input, including a location whose world has been collected. Callers are
     * deciding whether to touch a block, and "I cannot tell" must resolve to "do not". The catch is
     * {@code Throwable} — including {@code Error}s — deliberately: on these paths a VM-level failure should
     * degrade to "no live world" rather than escape through a save or a click handler.
     *
     * <p>Does not load chunks. A non-null return says the world is live, not that the cell is loaded — ask
     * {@code isChunkLoaded} on the returned world for that.
     */
    public static @Nullable World liveWorld(@Nullable Location loc) {
        if (loc == null) return null;
        try {
            return loc.isWorldLoaded() ? loc.getWorld() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Do these two locations denote the same block cell?
     *
     * <p>The single cell-comparison primitive. It exists because this question was previously hand-written at
     * seven separate sites, two of which compared {@code World} objects while the rest compared world
     * <i>names</i> — a distinction that only shows up after a world is unloaded and reloaded, at which point
     * the two disagree permanently about the same wheel.
     *
     * <p>Never touches chunks and never throws: a location whose world reference has been collected answers
     * "does not agree" rather than raising, for the same reason as {@link #liveWorld}.
     */
    public static boolean cellsAgree(@Nullable Location a, @Nullable Location b) {
        if (a == null || b == null) return false;
        World wa = liveWorld(a);
        World wb = liveWorld(b);
        if (wa == null || wb == null) return false;
        return wa.getName().equals(wb.getName())
            && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    /**
     * Whether this cell can be READ right now: its world is live and its chunk is loaded. Never loads a
     * chunk and never throws — total for null, for a null world, and for a collected world reference.
     * The totality claim assumes the main thread (as all callers are): the {@code isChunkLoaded} call is
     * outside any guard, and only main-thread execution stops the world from dying between the two calls.
     *
     * <p>The single spelling for the {@code liveWorld(x) != null && world.isChunkLoaded(x >> 4, z >> 4)}
     * pair that had grown seven hand-written copies (plus one Location-side variant). Answers a question
     * about OBSERVABILITY only; callers decide what an unobservable cell means — {@code ownedBlock} fails
     * closed on it, {@code recordHealth} fails open. Do not fold those policies in here.
     */
    public static boolean isCellObservable(@Nullable Location loc) {
        World w = liveWorld(loc);
        return w != null && w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    /**
     * This location's world name, or null if it does not currently have a live world.
     *
     * <p>The single derivation for every cache key and serialised row that names a world, so that a key built
     * at write time and a key built at prune time cannot disagree.
     */
    public static @Nullable String worldName(@Nullable Location loc) {
        World w = liveWorld(loc);
        return w == null ? null : w.getName();
    }

    /**
     * A stable world-qualified key for this cell, or null if its world is not live.
     *
     * <p>The single derivation for every cache keyed by a wheel's cell, so a key built when an entry is
     * written and a key built when it is pruned cannot disagree. They previously did not share one, which
     * would have made an entry unprunable the moment the two spellings drifted.
     *
     * <p>Block coordinates, so it does not inherit {@code Location}'s double-precision equality pitfalls.
     */
    public static @Nullable String cellKey(@Nullable Location loc) {
        String w = worldName(loc);
        if (w == null) return null;
        return w + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
