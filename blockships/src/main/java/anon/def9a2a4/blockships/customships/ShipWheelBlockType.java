package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.CustomItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * The ship wheel's defCoreLib block identity: registration, and the read/write of the two PDC keys that make
 * a placed wheel recognisable as itself.
 *
 * <p><b>Why register at all.</b> A wheel's identity has to survive the block being carried by a mechanism
 * (assembly airs the wheel head out of the world and lands it again elsewhere). defCoreLib snapshots a
 * carried block's whole tile PDC unconditionally, but it only ever <i>re-applies</i> that snapshot
 * ({@code restoreConfigPdc}) inside the {@code customTypeId != null} arms of {@code BasicMechanism.placeBlock}.
 * So a bespoke stamp on an <i>unregistered</i> head is captured faithfully on every voyage and then silently
 * discarded on landing. Registering the type is precisely and only what turns that restore on.
 *
 * <p><b>Registering is not enough on its own.</b> corelib marks a block from its own {@code BlockPlaceEvent}
 * handler, and BlockShips places the wheel head programmatically and cancels the interact event — so that
 * handler never runs, and no catch-up sweep can help (they all require the PDC to already be present). That
 * is why {@link #stamp} exists and why every path that creates a wheel block must call it.
 *
 * <p><b>The type is nearly inert.</b> No recipes, no {@code onInteract}, no {@code interactGUI}, no storage,
 * no states, no collision — BlockShips owns all of those and each would be a handler-ordering or
 * double-restore hazard. The one callback it does take is {@code onBlockRemoved}, which is how BlockShips
 * learns about the removal routes that never fire {@code BlockBreakEvent} (explosion, fire, fluid,
 * {@code /setblock}, piston, drill); every one of those used to leave a record behind at an empty cell.
 * It is guarded by {@code isCapturingForMechanism()}, which is exactly what distinguishes a real removal
 * from assembly airing the wheel out.
 */
public final class ShipWheelBlockType {

    private ShipWheelBlockType() {}

    /** The texture set / variant the wheel's skin is declared under in {@code items.yml}. */
    private static final String TEXTURE_SET = "SHIP_WHEEL_SET";
    private static final String TEXTURE_VARIANT = "_DEFAULT";

    /**
     * Which wheel this block is. Deliberately in the {@code blockships} namespace, NOT {@code corelib}:
     * {@code restoreConfigPdc} strips every {@code corelib:} key from a landing block's carried PDC, so a
     * {@code corelib:}-namespaced id would be dropped on the first voyage. This one survives.
     */
    public static final NamespacedKey WHEEL_ID_KEY = new NamespacedKey("blockships", "wheel_id");

    /**
     * The wheel's declared base64 skin — the single source of truth for the registered type and for the
     * texture comparison used to recognise a pre-identity wheel. Null until {@link #register} has succeeded,
     * which is also the signal that block identity is unavailable this session.
     */
    private static volatile String texture;

    /** The wheel's declared base64 skin, or null if registration did not succeed. */
    public static String texture() {
        return texture;
    }

    /** Whether the type registered successfully this session. When false, no wheel block can carry identity. */
    public static boolean isRegistered() {
        return texture != null;
    }

    /**
     * Builds and registers the type.
     *
     * <p>Failure is logged at SEVERE and swallowed rather than taking down {@code onEnable}, but it is not a
     * cosmetic failure. Two distinct outcomes, which an earlier version of this javadoc had backwards:
     * <ul>
     *   <li><b>Never registered, then carried.</b> Capture takes the block-entity snapshot precisely when the
     *       type is <i>unknown</i> ({@code chb == null}), so the head keeps its <i>skin</i> and loses only
     *       {@code wheel_id}. It comes back looking right and answering to nothing.</li>
     *   <li><b>Captured while registered, landed after registration failed.</b> No snapshot was taken (the
     *       type was known at capture) and {@code placeBlock} skips the whole restore arm, so it comes back as
     *       a <b>blank, untextured head with no identity</b> — and because it has no skin, the texture gate in
     *       {@code adoptLegacyWheel} correctly refuses to adopt it. Only {@code /blockships wheels adopt}
     *       recovers it.</li>
     * </ul>
     * Anything that stamps or recognises a wheel must check {@link #isRegistered()} first.
     */
    public static void register(BlockShipsPlugin plugin) {
        if (plugin.getDisplayShip() == null || plugin.getDisplayShip().getTextureManager() == null) {
            plugin.getLogger().severe("Ship wheel texture manager unavailable — the wheel is NOT registered "
                + "with DefCoreLib. Wheel identity will not survive a voyage. Report at " + BlockShipsPlugin.ISSUES_URL);
            return;
        }
        String tex = plugin.getDisplayShip().getTextureManager().getTexture(TEXTURE_SET, TEXTURE_VARIANT);
        if (tex == null || tex.isBlank()) {
            // CustomHeadBlock.build() throws for a placeable block with no texture, so bail with a clear
            // message rather than letting an IllegalStateException take down onEnable.
            plugin.getLogger().severe("No " + TEXTURE_SET + "/" + TEXTURE_VARIANT + " texture in items.yml — "
                + "the ship wheel is NOT registered with DefCoreLib. Wheel identity will not survive a voyage.");
            return;
        }
        try {
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry().register(
                anon.def9a2a4.corelib.CustomHeadBlock.builder("blockships", "ship_wheel")
                    .name(net.kyori.adventure.text.Component.text("Ship Wheel"))
                    .texture(tex)
                    // Drop the wheel item whenever corelib is the one removing the block.
                    //
                    // WHY THIS MATTERS FAR MORE THAN IT LOOKS. corelib's break handler is MONITOR +
                    // ignoreCancelled and unconditionally calls setDropItems(false) before consulting
                    // dropRules(). With no rules declared, every path where BlockShips REFUSES to resolve a
                    // wheel — a /clone'd copy, a stamp that never landed, a record quarantined at load —
                    // ended with the block deleted and NOTHING dropped. That turned every "safely refuse"
                    // decision elsewhere in this plugin into silent item destruction, which is why several
                    // other fixes could not be written until this line existed.
                    //
                    // No double-drop on any route, checked individually against corelib:
                    //   - tracked player break: BlockShips cancels at HIGHEST before corelib's
                    //     ignoreCancelled handler and drops its own item. Both early returns in
                    //     onShipWheelBreak (already-cancelled, WorldGuard-denied) also leave the event
                    //     cancelled, so the WG arm does not become a dupe-in-a-claim.
                    //   - creative: corelib returns before the rule loop. Still drops nothing. Correct.
                    //   - explosion and drill: already drop unconditionally without reading rules.
                    //   - piston: unreachable, see cancelPistons below.
                    //   - fluid: gated behind breakOnFluid(), which this type never sets.
                    //   - teardownWheel / DisplayShip use raw setType(AIR), which fires no event at all.
                    // NOT covered, by design: /setblock ... destroy (corelib calls setWillDrop(false)) and
                    // fire (onBlockBurn never consults dropRules).
                    //
                    // Known cosmetic consequence: corelib's mint carries corelib's name/lore and ours carries
                    // blockships:custom_item_id, so the two wheel items do not stack. Everything functional
                    // accepts both (isShipWheel, ingredientMatches); corelib has no per-type createItem hook.
                    .drops(anon.def9a2a4.corelib.CustomHeadBlock.DropRule.self())
                    // A wheel must never be shoved away from its record, because the record's cached cell is
                    // how a DOCKED wheel is recognised. breakOnPiston is NOT the alternative it looks like:
                    // it would let a piston delete the wheel and hand back an item, silently unmooring the
                    // ship from its record. Cancelling aborts the whole push, which is the safe direction.
                    // (These two are mutually exclusive and build() throws if both are set — and that throw
                    // would be swallowed below, silently disabling registration.)
                    .cancelPistons(true)
                    // corelib's drill removes blocks with NO BlockBreakEvent — so no WorldGuard, no claim
                    // check, nothing. Without this it is a protection-bypassing wheel remover: the item does
                    // come back (the drill's registered-type arm drops enrichDrop unconditionally), but the
                    // wheel's glue and lock set do not, and the removal happens inside someone else's claim.
                    //
                    // Scope honestly: this gate needs getTypeFromBlock to resolve, so it protects only a
                    // fully-marked wheel in a session where registration succeeded. An unstamped legacy wheel
                    // or a blank failed-restore head still falls to breakNaturally, which calls no
                    // onBlockRemoved and so strands the record. Closing that needs a type-independent veto in
                    // corelib, which is deliberately not done here — a drill still bores through the entire
                    // hull with no event either way, so the wheel flag is a point fix, not a protection fix.
                    .drillable(false)
                    // Told when the ENGINE removes a wheel block by a route that never fires
                    // BlockBreakEvent: explosion, fire, fluid, /setblock, /fill, piston break, drill. Those
                    // all left the record behind pointing at an empty cell — the orphan state a planted head
                    // gets adopted into. isCapturingForMechanism() is the discriminator that makes this safe:
                    // assembly airs the wheel out through the same plumbing, and corelib raises that flag
                    // around exactly the removal callback. (An older comment here argued a callback was
                    // impossible for that reason; the flag is public API and answers it.)
                    .onBlockRemoved((block, state) -> {
                        try {
                            if (anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry()
                                    .isCapturingForMechanism()) return;
                            ShipWheelManager mgr = plugin.getShipWheelManager();
                            if (mgr != null) mgr.onEngineRemovedWheelBlock(block);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Ship-wheel removal callback failed: " + t.getMessage());
                        }
                    })
                    .build());
            texture = tex;
        } catch (Throwable t) {
            plugin.getLogger().severe("Could not register the ship wheel with DefCoreLib: " + t.getMessage()
                + " — wheel identity will not survive a voyage.");
        }
    }

    /**
     * Writes both identity keys onto a freshly placed wheel head: corelib's {@code block_type} (so the engine
     * treats it as a registry block and restores its PDC on landing) and our {@code wheel_id} (so we know
     * which wheel it is).
     *
     * <p>Must be called <i>after</i> any {@code Skull.update()} on the same block — corelib's
     * {@code markBlock} takes its own fresh block state, so an update from an older snapshot afterwards would
     * wipe what was just written.
     *
     * @return true if the block now carries both keys. False means the caller must not treat this block as a
     *         wheel — {@code markBlock} silently does nothing when the block is not a tile entity, so a
     *         caller that assumed success would leave an unmarked head behind with a live map entry.
     */
    public static boolean stamp(Block block, UUID wheelId) {
        if (!isRegistered() || block == null || wheelId == null) return false;
        try {
            var registry = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry();
            anon.def9a2a4.corelib.CustomHeadBlock type =
                registry.getType(CustomItem.SHIP_WHEEL_CORELIB_ID);
            if (type == null) return false;

            registry.markBlock(block, type, null);
            // markBlock is a silent no-op on a non-TileState, so verify rather than assume.
            if (registry.getTypeFromBlock(block) == null) return false;

            if (!(block.getState() instanceof TileState tile)) return false;
            tile.getPersistentDataContainer().set(WHEEL_ID_KEY, PersistentDataType.STRING, wheelId.toString());
            tile.update(false, false);   // physics-suppressed, matching markBlock's own write
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The wheel id carried by this block, or null if it is not a head, is not one of ours, or predates the
     * identity stamp. Cheap material gate first — this runs on every right-click and every block break.
     */
    public static UUID readWheelId(Block block) {
        if (block == null) return null;
        Material m = block.getType();
        if (m != Material.PLAYER_HEAD && m != Material.PLAYER_WALL_HEAD) return null;
        if (!(block.getState() instanceof TileState tile)) return null;
        String raw = tile.getPersistentDataContainer().get(WHEEL_ID_KEY, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;   // corrupted value; treat as unidentified rather than throwing on a hot path
        }
    }

    /**
     * Whether this block wears the wheel's declared skin.
     *
     * <p>The single block-side texture comparison. It exists because "is this head wearing our skin" is asked
     * from two places that must never disagree — {@code adoptLegacyWheel}, which uses it to decide whether an
     * unstamped head may be adopted, and {@code ownsBlock}, which uses it to decide whether an unstamped head
     * may be aired out and dropped. Two hand-written copies of that comparison is one drifting apart.
     *
     * <p><b>Deliberately conservative: false when the skin cannot be read at all.</b> A profile that has not
     * resolved, an odd tile state, or a blank head all answer false rather than "unknown". Both callers are
     * deciding whether to take an irreversible action on a block they do not otherwise have identity for, so
     * "I cannot tell" must mean "do not touch it". The cost is that a wheel whose skin genuinely changed
     * (an {@code items.yml} texture edit between sessions, another plugin re-skinning the head) stops being
     * recognised — but that wheel is already unadoptable today for exactly the same reason, so this does not
     * widen the failure, it only makes the two sites agree about it.
     *
     * <p>Note this is the <i>block</i>-side check. The item-side equivalent in {@code DisplayShip.isShipWheel}
     * reads a {@code PLAYER_HEAD} item's profile instead and is a genuinely different function; do not fold
     * them together.
     *
     * @return false if the type never registered (no declared skin to compare against), if the block is not a
     *         readable skull, or if the skin differs.
     */
    public static boolean hasDeclaredSkin(Block block) {
        String declared = texture;
        if (declared == null || block == null) return false;
        return declared.equals(blockSkinBase64(block));
    }

    /** The raw base64 {@code textures} profile property on a placed head, or null if it cannot be read. */
    private static String blockSkinBase64(Block block) {
        try {
            // getState(false): no snapshot copy — this runs on break and teardown paths.
            if (!(block.getState(false) instanceof org.bukkit.block.Skull skull)) return null;
            com.destroystokyo.paper.profile.PlayerProfile profile = skull.getPlayerProfile();
            if (profile == null) return null;
            for (com.destroystokyo.paper.profile.ProfileProperty p : profile.getProperties()) {
                if ("textures".equals(p.getName())) return p.getValue();
            }
        } catch (Throwable ignored) {
            // A skull whose profile has not resolved yet, or an odd tile state. Treat as unreadable.
        }
        return null;
    }

    /** Tell corelib the block is gone, so its location index and any displays are cleaned up. */
    public static void notifyRemoved(Block block) {
        try {
            var registry = anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry();
            anon.def9a2a4.corelib.CustomHeadBlock type = registry.getTypeFromBlock(block);
            if (type != null) registry.onBlockRemoved(block, type);
        } catch (Throwable ignored) {
            // Best-effort cleanup of an in-memory index; never worth failing a break over.
        }
    }

    /** Whether {@code fullId} is the wheel's corelib type id. */
    public static boolean isWheelTypeId(String fullId) {
        return CustomItem.SHIP_WHEEL_CORELIB_ID.equals(fullId);
    }
}
