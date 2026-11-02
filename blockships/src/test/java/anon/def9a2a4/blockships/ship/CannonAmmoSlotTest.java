package anon.def9a2a4.blockships.ship;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which dispenser slot a ship cannon fires. It used to be always the first filled slot, so fire charges in
 * front of TNT meant only fire charges ever fired (#43); now it is a random usable slot, as a vanilla
 * dispenser picks, and slots the caller rules out (TNT while it is disabled) are never chosen.
 */
class CannonAmmoSlotTest {

    @Test
    void noUsableSlotGivesMinusOne() {
        assertEquals(-1, ShipInstance.pickAmmoSlot(9, i -> false, new Random(1)));
        assertEquals(-1, ShipInstance.pickAmmoSlot(0, i -> true, new Random(1)));
    }

    @Test
    void aSingleUsableSlotIsAlwaysChosen() {
        for (int seed = 0; seed < 50; seed++) {
            assertEquals(4, ShipInstance.pickAmmoSlot(9, i -> i == 4, new Random(seed)));
        }
    }

    @Test
    void ruledOutSlotsAreNeverChosen() {
        Set<Integer> tnt = Set.of(0, 2);   // e.g. TNT while cannons.tnt-enabled is off
        Random rng = new Random(7);
        for (int n = 0; n < 500; n++) {
            int slot = ShipInstance.pickAmmoSlot(3, i -> !tnt.contains(i), rng);
            assertEquals(1, slot);
        }
    }

    @Test
    void everyUsableSlotGetsPickedRoughlyEvenly() {
        int[] hits = new int[9];
        Random rng = new Random(42);
        for (int n = 0; n < 9000; n++) {
            hits[ShipInstance.pickAmmoSlot(9, i -> i % 3 == 0, rng)]++;   // slots 0, 3, 6
        }
        for (int i = 0; i < 9; i++) {
            if (i % 3 == 0) assertTrue(hits[i] > 2700 && hits[i] < 3300, "slot " + i + " hit " + hits[i]);
            else assertEquals(0, hits[i]);
        }
    }
}
