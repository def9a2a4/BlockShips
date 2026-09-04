package anon.def9a2a4.blockships;

import anon.def9a2a4.blockships.customships.ShipWheelManager;
import anon.def9a2a4.blockships.customships.ShipWheelManager.LockDecision;
import anon.def9a2a4.blockships.customships.ShipWheelManager.WheelState;
import anon.def9a2a4.blockships.customships.ShipWheelMenu;
import anon.def9a2a4.blockships.customships.ShipWheelData;
import anon.def9a2a4.blockships.util.LocationUtil;
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

    // NOTE: there is deliberately no "every action has an answer" test. isRegionGated is a switch
    // EXPRESSION with no default arm, so exhaustiveness is enforced by the compiler — a new MenuAction is
    // a compile error until it is placed in a list. The test this note replaces asserted only "does not
    // throw", which a default arm made vacuously true for every input.

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

    /**
     * {@code prunePending} is written only when true, like {@code locked}, so the common case adds no row
     * weight — and it MUST be written when true, or an unlock-while-sailing forgets its debt across a
     * restart and the stale materialized hull assembles glued. (The fromMap read-back needs a resolvable
     * world, which a serverless test cannot provide; the write side is the half that silently rots.)
     */
    @Test
    void prunePendingIsWrittenOnlyWhenTrue() {
        ShipWheelData off = new ShipWheelData(new org.bukkit.Location(null, 1, 2, 3), BlockFace.NORTH);
        assertFalse(off.toMap().containsKey("prune_pending"), "false must not be written at all");

        ShipWheelData on = new ShipWheelData(new org.bukkit.Location(null, 1, 2, 3), BlockFace.NORTH);
        on.setPrunePending(true);
        assertEquals(Boolean.TRUE, on.toMap().get("prune_pending"));
    }

    // ── ShipWheelManager.decideLock ─────────────────────────────────────────────────────────────────────
    //
    // The pure head of toggleLock. Its inputs are sampled AFTER the ORPHAN reconcile, so ORPHAN rows below
    // exercise totality (the function must answer), not a reachable production state.

    /** The core unlock row: docked wheel, block present — clear the freeze AND prune the glue. */
    @Test
    void unlockOfADockedWheelPrunes() {
        assertEquals(LockDecision.UNLOCK_AND_PRUNE,
            ShipWheelManager.decideLock(true, false, WheelState.NOT_ASSEMBLED, true));
    }

    /**
     * Unlocking while the ship is OUT must defer the prune, not drop it: mid-voyage there is no wheel skull
     * to rewrite, and without the flag the whole materialized hull stays glued — every hull-adjacent slime
     * joins via the sticky closure on the next assembly. {@code ownedBlockPresent} is irrelevant here; a
     * planted look-alike at the empty dock must not change the answer.
     */
    @Test
    void unlockWhileTheShipIsOutDefersThePrune() {
        for (WheelState out : new WheelState[]{WheelState.LOADED, WheelState.UNLOADED_RECOVERABLE}) {
            assertEquals(LockDecision.UNLOCK_DEFER_PRUNE,
                ShipWheelManager.decideLock(true, false, out, false), out + ", no block");
            assertEquals(LockDecision.UNLOCK_DEFER_PRUNE,
                ShipWheelManager.decideLock(true, false, out, true), out + ", block present");
        }
    }

    /**
     * A wheel whose block is genuinely gone (not sailing — gone) unlocks record-only and must NOT get the
     * pending flag: nothing would ever run its prune, so the flag would ride the record forever.
     */
    @Test
    void unlockWithNoBlockAndNoShipOutIsRecordOnly() {
        assertEquals(LockDecision.UNLOCK_RECORD_ONLY,
            ShipWheelManager.decideLock(true, false, WheelState.NOT_ASSEMBLED, false));
    }

    /**
     * The lock arm's refusal ORDER is what keeps each message accurate: still-loading before sailing before
     * no-block, so the three states do not collapse into "it isn't where its record says".
     */
    @Test
    void lockRefusalsKeepTheirOwnMessages() {
        assertEquals(LockDecision.REFUSE_STILL_LOADING,
            ShipWheelManager.decideLock(false, false, WheelState.UNLOADED_RECOVERABLE, true));
        assertEquals(LockDecision.REFUSE_STILL_LOADING,
            ShipWheelManager.decideLock(false, false, WheelState.UNLOADED_RECOVERABLE, false));
        assertEquals(LockDecision.REFUSE_SAILING,
            ShipWheelManager.decideLock(false, false, WheelState.LOADED, true));
        assertEquals(LockDecision.REFUSE_NO_BLOCK,
            ShipWheelManager.decideLock(false, false, WheelState.NOT_ASSEMBLED, false));
        assertEquals(LockDecision.LOCK,
            ShipWheelManager.decideLock(false, false, WheelState.NOT_ASSEMBLED, true));
    }

    /** Refreeze of a locked wheel takes the LOCK arm (a re-snapshot), never the unlock arm. */
    @Test
    void refreezeOfALockedWheelIsALock() {
        assertEquals(LockDecision.LOCK,
            ShipWheelManager.decideLock(true, true, WheelState.NOT_ASSEMBLED, true));
        assertEquals(LockDecision.REFUSE_SAILING,
            ShipWheelManager.decideLock(true, true, WheelState.LOADED, true));
    }

    /** Total: every input combination answers, including the post-reconcile-impossible ORPHAN rows. */
    @Test
    void decideLockIsTotal() {
        for (boolean locked : new boolean[]{false, true}) {
            for (boolean refreeze : new boolean[]{false, true}) {
                for (WheelState s : WheelState.values()) {
                    for (boolean present : new boolean[]{false, true}) {
                        assertNotNull(ShipWheelManager.decideLock(locked, refreeze, s, present));
                    }
                }
            }
        }
    }

    // ── LocationUtil under a COLLECTED world reference ──────────────────────────────────────────────────

    /**
     * A Location whose weak world reference has been collected: {@code isWorldLoaded()} still answers true,
     * and {@code getWorld()} throws {@code IllegalArgumentException("World unloaded")}.
     *
     * <p>Overriding only {@code getWorld()} would make these tests a tautology: {@code liveWorld} consults
     * {@code isWorldLoaded()} FIRST, and for a null-world Location that reads the private Reference field
     * and answers false without ever calling {@code getWorld()} — so a getWorld-only override is dead code
     * and proves nothing beyond what {@code new Location(null, …)} already covers. Both overrides together
     * are what force {@code liveWorld}'s {@code catch (Throwable)} to actually run.
     */
    private static org.bukkit.Location collectedWorldLocation(int x, int y, int z) {
        return new org.bukkit.Location(null, x, y, z) {
            @Override public boolean isWorldLoaded() { return true; }
            @Override public org.bukkit.World getWorld() {
                throw new IllegalArgumentException("World unloaded");
            }
        };
    }

    /** The throw path, not the null path: "I cannot tell" must resolve to "do not touch the block". */
    @Test
    void liveWorldAnswersNullNotAThrowForACollectedWorld() {
        assertNull(LocationUtil.liveWorld(collectedWorldLocation(1, 2, 3)));
    }

    /** Everything layered on liveWorld inherits its totality through the same throw path. */
    @Test
    void derivedHelpersAreTotalForACollectedWorld() {
        assertNull(LocationUtil.worldName(collectedWorldLocation(1, 2, 3)));
        assertNull(LocationUtil.cellKey(collectedWorldLocation(1, 2, 3)));
        assertFalse(LocationUtil.cellsAgree(collectedWorldLocation(1, 2, 3), collectedWorldLocation(1, 2, 3)),
            "a cell in a collected world must answer 'does not agree', even against itself");
    }
}
