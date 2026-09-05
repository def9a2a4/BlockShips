package anon.def9a2a4.blockships.customships;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ShipWheelManager#plural}, the single inflection point behind every count a player reads.
 *
 * <p>Three separate places printed "1 passengers" before this existed — docked chat, assembled chat and
 * the wheel menu lore — and each was written independently. Checking it once here replaces a
 * three-place manual check that nobody was going to repeat.
 */
class PluralTest {

    @Test
    void oneIsSingular() {
        assertEquals("1 passenger", ShipWheelManager.plural(1, "passenger", "passengers"));
        assertEquals("1 driver", ShipWheelManager.plural(1, "driver", "drivers"));
    }

    @Test
    void zeroIsPlural() {
        // "0 passengers", not "0 passenger" — and it must not be dropped, which is exactly why these
        // callers cannot reuse appendCount.
        assertEquals("0 passengers", ShipWheelManager.plural(0, "passenger", "passengers"));
    }

    @Test
    void manyIsPlural() {
        assertEquals("2 drivers", ShipWheelManager.plural(2, "driver", "drivers"));
        assertEquals("17 passengers", ShipWheelManager.plural(17, "passenger", "passengers"));
    }

    /** The sails line inflects irregular nouns by passing both forms; nothing derives one from the other. */
    @Test
    void theTwoFormsAreIndependent() {
        assertEquals("1 wool", ShipWheelManager.plural(1, "wool", "wool"));
        assertEquals("3 wool", ShipWheelManager.plural(3, "wool", "wool"));
        assertEquals("1 large banner", ShipWheelManager.plural(1, "large banner", "large banners"));
    }
}
