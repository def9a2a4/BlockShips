package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.customships.ShipWheelMenu;
import anon.def9a2a4.blockships.customships.ShipWheelData;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision tables — small pure predicates whose whole content is "which arm fires, for which input".
 *
 * <p>Worth testing for the same reason {@code WheelFacingTest} is: these fail silently in both directions.
 * An over-gated action refuses an owner standing in their own region; an under-gated one hands a passer-by a
 * tool. Neither logs, neither throws, and a manual server run exercises exactly one row of the table — the
 * one the tester happened to be standing in. A future "cleanup" that tidies one of these switches is the
 * realistic way they break, and that is precisely what a table test catches.
 */
class DecisionTableTest {

    // ── DisplayShip.isRegionGated ───────────────────────────────────────────────────────────────────────

    /**
     * DISASSEMBLE looks droppable — surely the per-cell placement policy already refuses protected cells? It
     * does not: under force that policy DEGRADES TO DROPPING THE BLOCKS AS ITEMS rather than refusing. So
     * this gate is the only thing between a passer-by and force-disassembling someone's ship inside a region
     * they cannot build in, converting the hull to ground items.
     */
    @Test
    void disassembleIsRegionGated() {
        assertTrue(DisplayShip.isRegionGated(ShipWheelMenu.MenuAction.DISASSEMBLE));
        assertTrue(DisplayShip.isRegionGated(ShipWheelMenu.MenuAction.FORCE_DISASSEMBLE));
    }

    /** Assembly is gated per-cell at the scan, which is strictly finer than one check at the wheel. */
    @Test
    void assembleIsNotRegionGated() {
        assertFalse(DisplayShip.isRegionGated(ShipWheelMenu.MenuAction.ASSEMBLE));
    }

    /**
     * Read-only actions must never be gated. Gating one of these is the failure that reads as "the plugin is
     * broken in this region" rather than as a protection: a player who cannot even open the help book or see
     * their own ship's stats has no way to tell a region rule from a bug.
     */
    @Test
    void readOnlyActionsAreNeverGated() {
        for (ShipWheelMenu.MenuAction a : new ShipWheelMenu.MenuAction[]{
                ShipWheelMenu.MenuAction.HELP,
                ShipWheelMenu.MenuAction.INFO,
                ShipWheelMenu.MenuAction.DETECT,
                ShipWheelMenu.MenuAction.HIGHLIGHT_SEATS,
                ShipWheelMenu.MenuAction.CAMERA_DISTANCE_DECREASE,
                ShipWheelMenu.MenuAction.CAMERA_DISTANCE_INCREASE,
                ShipWheelMenu.MenuAction.NONE}) {
            assertFalse(DisplayShip.isRegionGated(a), a + " must not require a build permission");
        }
    }

    /** Every action that writes to the world or moves the ship must be gated. */
    @Test
    void worldAffectingActionsAreGated() {
        for (ShipWheelMenu.MenuAction a : new ShipWheelMenu.MenuAction[]{
                ShipWheelMenu.MenuAction.ALIGN,
                ShipWheelMenu.MenuAction.DISASSEMBLE,
                ShipWheelMenu.MenuAction.FORCE_DISASSEMBLE,
                ShipWheelMenu.MenuAction.TOGGLE_LOCK,
                ShipWheelMenu.MenuAction.FIRE_CANNONS}) {
            assertTrue(DisplayShip.isRegionGated(a), a + " must require a build permission");
        }
    }

    /** No action may be missing from the table: the switch has a default, so a new constant silently opts out. */
    @Test
    void everyActionHasAnAnswer() {
        for (ShipWheelMenu.MenuAction a : ShipWheelMenu.MenuAction.values()) {
            DisplayShip.isRegionGated(a);   // must not throw
        }
    }

    // ── ShipWheelData.toMap totality ────────────────────────────────────────────────────────────────────

    /**
     * {@code toMap} must not throw for any reachable state, because {@code saveAll}'s per-row catch turns a
     * throw into PERMANENT DELETION of that row from disk. That is how a wheel in a world unloaded at runtime
     * used to disappear: {@code toMap} read {@code Location.getWorld()}, which raises "World unloaded" once
     * the weak world reference is collected, and the catch logged that the previous row had been preserved
     * while the atomic rename published a file without it.
     *
     * <p>There is no server here, so {@code LocationUtil.worldName} cannot resolve a world and the row comes
     * out world-less — which is exactly the degraded shape this must survive rather than throw on. A
     * world-less row is quarantined by {@code fromMap} and re-emitted verbatim on the next save, so it is
     * recoverable; a throw is not.
     */
    @Test
    void toMapIsTotalWithNoResolvableWorld() {
        ShipWheelData d = new ShipWheelData(new org.bukkit.Location(null, 10, 64, -20), BlockFace.NORTH);
        java.util.Map<String, Object> row = d.toMap();
        assertEquals(10, row.get("x"));
        assertEquals(64, row.get("y"));
        assertEquals(-20, row.get("z"));
        assertEquals("NORTH", row.get("facing"));
        assertNotNull(row.get("wheel_id"), "a row with no id cannot be matched back to its block");
    }

    /**
     * {@code fromMap} is the other half: it must reject a world-less row rather than NPE on it.
     * {@code Bukkit.getWorld(String)} lower-cases its argument, so a null world name is a crash, and the row
     * shape above can now legitimately carry no world at all.
     */
    @Test
    void fromMapRejectsAWorldlessRowWithoutThrowing() {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("x", 1); row.put("y", 2); row.put("z", 3); row.put("facing", "NORTH");
        assertNull(ShipWheelData.fromMap(row), "a row naming no world must quarantine, not crash the load");
    }
}
