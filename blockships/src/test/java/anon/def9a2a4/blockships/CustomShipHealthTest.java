package anon.def9a2a4.blockships;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Max health of a custom ship, from assembly and from a sidecar read back after a restart or chunk reload.
 *
 * <p>{@code ShipModel.fromMap} itself reaches for the live plugin, so it is covered through the two reads it
 * delegates to. The regression this pins: recovery hardcoded max health to 40, so an 836-HP hull came back
 * from every restart at 40.
 */
class CustomShipHealthTest {

    @Test
    void maxHealthIsMassClampedToOneAndTheAttributeCeiling() {
        assertEquals(836.0, ShipModel.customShipMaxHealth(836));
        assertEquals(1.0, ShipModel.customShipMaxHealth(0));
        assertEquals(1024.0, ShipModel.customShipMaxHealth(3000));
    }

    @Test
    void aSidecarWithoutMaxHealthDerivesItFromMassInsteadOfFallingBackTo40() {
        Map<String, Object> legacy = new HashMap<>();
        legacy.put("mass", 836);
        assertEquals(836.0, ShipModel.savedMaxHealth(legacy, 836));
    }

    @Test
    void aSidecarWithMaxHealthKeepsTheRecordedValue() {
        Map<String, Object> saved = new HashMap<>();
        saved.put("max_health", 512.0);
        assertEquals(512.0, ShipModel.savedMaxHealth(saved, 836));
    }
}
