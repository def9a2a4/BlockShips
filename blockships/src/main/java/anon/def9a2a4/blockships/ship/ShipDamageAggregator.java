package anon.def9a2a4.blockships.ship;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Turns the per-collider damage events of one hit into one amount of ship damage.
 *
 * <p>A ship's hitbox is one collider shulker per block, and vanilla damages every entity an area effect
 * reaches separately: one TNT fires an {@code EntityDamageEvent} for each of the hundreds of colliders in
 * its radius, and each used to come off the ship's single HP pool in full (issue #47). This groups those
 * events by the hit they belong to (a {@code key} the caller derives from the damage source) and reports
 * only the HP still owed.
 *
 * <p>For each key the total charged is {@code max + spread * (sum - max)}: {@code spread = 0} charges the
 * largest single collider hit (per-ship), {@code spread = 1} charges the full sum (the old per-block
 * behaviour). It works incrementally, so the first event of a hit applies at once and later ones only add
 * whatever they raise the total by. One instance per ship; main thread only.
 */
public final class ShipDamageAggregator {

    private static final class Entry {
        final long expiresAt;
        double max, sum, applied;

        Entry(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    private final Map<Object, Entry> entries = new HashMap<>();

    /**
     * Records one collider's share of a hit and returns the HP to take off the ship now (never negative).
     *
     * @param key         identifies the hit: the same explosion, attacker or source within the window
     * @param amount      the damage this collider took
     * @param nowTick     the current game tick
     * @param windowTicks how long events with this key count as the same hit (at least 1: the same tick)
     * @param spread      0 for per-ship, 1 for per-block, anything between to blend
     */
    public double add(Object key, double amount, long nowTick, int windowTicks, double spread) {
        if (!(amount > 0)) return 0;
        Entry e = entries.get(key);
        if (e == null || nowTick >= e.expiresAt) {
            e = new Entry(nowTick + java.lang.Math.max(1, windowTicks));
            entries.put(key, e);
        }
        e.max = java.lang.Math.max(e.max, amount);
        e.sum += amount;
        double s = java.lang.Math.min(1, java.lang.Math.max(0, spread));
        double total = e.max + s * (e.sum - e.max);
        double owed = total - e.applied;
        if (owed <= 0) return 0;
        e.applied = total;
        return owed;
    }

    /** Drops hits whose window has closed. Cheap; call once a tick. */
    public void prune(long nowTick) {
        if (entries.isEmpty()) return;
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
            if (nowTick >= it.next().expiresAt) it.remove();
        }
    }

    int size() {
        return entries.size();
    }
}
