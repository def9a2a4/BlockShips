package anon.def9a2a4.blockships.ship;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How one hit that reaches many colliders is charged to the ship (issue #47: one TNT or splash potion used
 * to come off the ship once per collider).
 */
class ShipDamageAggregatorTest {

    private static final double PER_SHIP = 0, PER_BLOCK = 1;

    /** Feeds one hit's collider events, in order, and returns the total charged. */
    private static double charge(ShipDamageAggregator agg, Object key, long tick, double spread, double... hits) {
        double total = 0;
        for (double h : hits) total += agg.add(key, h, tick, 1, spread);
        return total;
    }

    @Test
    void perShipChargesTheLargestColliderShareOnce() {
        var agg = new ShipDamageAggregator();
        assertEquals(57, charge(agg, "tnt", 100, PER_SHIP, 20, 57, 40, 57, 3), 1e-9);
    }

    @Test
    void perShipChargesTheFirstEventAtOnceAndOnlyTheIncreaseAfter() {
        var agg = new ShipDamageAggregator();
        assertEquals(20, agg.add("tnt", 20, 100, 1, PER_SHIP), 1e-9);
        assertEquals(0, agg.add("tnt", 10, 100, 1, PER_SHIP), 1e-9);
        assertEquals(37, agg.add("tnt", 57, 100, 1, PER_SHIP), 1e-9);
    }

    @Test
    void perBlockChargesTheFullSumLikeBefore() {
        var agg = new ShipDamageAggregator();
        assertEquals(177, charge(agg, "tnt", 100, PER_BLOCK, 20, 57, 40, 57, 3), 1e-9);
    }

    @Test
    void spreadBlendsBetweenTheTwo() {
        var agg = new ShipDamageAggregator();
        // max 10, rest 30: 10 + 0.5 * 30
        assertEquals(25, charge(agg, "k", 1, 0.5, 10, 10, 10, 10), 1e-9);
    }

    @Test
    void separateHitsInTheSameTickAreChargedSeparately() {
        var agg = new ShipDamageAggregator();
        assertEquals(57, charge(agg, "tntA", 100, PER_SHIP, 57, 57), 1e-9);
        assertEquals(30, charge(agg, "tntB", 100, PER_SHIP, 30, 12), 1e-9);
    }

    @Test
    void theSameKeyOnALaterTickIsANewHit() {
        var agg = new ShipDamageAggregator();
        assertEquals(6, charge(agg, "player", 100, PER_SHIP, 6, 6), 1e-9);
        assertEquals(6, charge(agg, "player", 101, PER_SHIP, 6), 1e-9);
    }

    @Test
    void aWindowHoldsRepeatedContactDamageToOneHitPerWindow() {
        var agg = new ShipDamageAggregator();
        double total = 0;
        for (long t = 0; t < 20; t++) {
            total += agg.add("SUFFOCATION", 0.25, t, 10, PER_SHIP);   // two colliders clipping, every tick
            total += agg.add("SUFFOCATION", 0.25, t, 10, PER_SHIP);
        }
        assertEquals(0.5, total, 1e-9);   // one charge per 10-tick window
    }

    @Test
    void nonPositiveDamageChargesNothing() {
        var agg = new ShipDamageAggregator();
        assertEquals(0, agg.add("k", 0, 1, 1, PER_BLOCK));
        assertEquals(0, agg.add("k", -5, 1, 1, PER_BLOCK));
        assertEquals(0, agg.add("k", Double.NaN, 1, 1, PER_BLOCK));
    }

    @Test
    void pruneDropsClosedWindowsOnly() {
        var agg = new ShipDamageAggregator();
        agg.add("old", 1, 100, 1, PER_SHIP);
        agg.add("open", 1, 100, 10, PER_SHIP);
        agg.prune(101);
        assertEquals(1, agg.size());
        agg.prune(110);
        assertEquals(0, agg.size());
    }
}
