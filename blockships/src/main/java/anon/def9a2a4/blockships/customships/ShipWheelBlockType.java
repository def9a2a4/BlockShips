package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.CustomItem;

/**
 * Registers the ship wheel as a defCoreLib {@code CustomHeadBlock}.
 *
 * <p><b>Why register at all.</b> A wheel's identity has to survive the block being carried by a mechanism
 * (assembly airs the wheel head out of the world and lands it again elsewhere). defCoreLib snapshots a
 * carried block's whole tile PDC unconditionally, but it only ever <i>re-applies</i> that snapshot
 * ({@code restoreConfigPdc}) inside the {@code customTypeId != null} arms of {@code BasicMechanism.placeBlock}.
 * So a bespoke {@code blockships:wheel_id} stamp on an unregistered head is captured faithfully on every
 * voyage and then silently discarded on landing. Registering the type is precisely and only what turns that
 * restore on — it is load-bearing, not cosmetic.
 *
 * <p><b>The type is deliberately inert.</b> No recipes, no {@code onInteract}, no {@code interactGUI}, no
 * storage, no states, no collision, no lifecycle callbacks. BlockShips owns every one of those behaviours
 * already, and each would be a handler-ordering or double-restore hazard: corelib's interact handler runs at
 * {@code HIGH} (before BlockShips' {@code NORMAL}), and an {@code onBlockRemoved} callback would fire during
 * mechanism <i>capture</i> as well as on a real break. The only things we want from corelib are the identity
 * PDC and its restore-on-landing.
 *
 * <p>Two behaviours do come along for free and are wanted: flowing water is dammed at the wheel rather than
 * washing it away ({@code breakOnFluid} left at its default {@code false}), and the head no longer pops off
 * into a plain vanilla drop when its support block goes.
 */
public final class ShipWheelBlockType {

    private ShipWheelBlockType() {}

    /** The texture set / variant the wheel's skin is declared under in {@code items.yml}. */
    private static final String TEXTURE_SET = "SHIP_WHEEL_SET";
    private static final String TEXTURE_VARIANT = "_DEFAULT";

    /**
     * The wheel's declared base64 skin — the single source of truth for both the registered type and the
     * texture-comparison fallback arm of {@code isShipWheel}. Null until {@link #register} has run.
     */
    private static volatile String texture;

    /** The wheel's declared base64 skin, or null if registration has not run or the texture set is missing. */
    public static String texture() {
        return texture;
    }

    /**
     * Builds and registers the type. Best-effort: a missing defCoreLib or a failed registration is logged and
     * swallowed, matching {@link ShipWheelAnchors#register}. Note the consequence of failure is not cosmetic —
     * without the type registered, a landing wheel gets no PDC at all, because {@code getType(customTypeId)}
     * returning null skips <i>both</i> restore arms.
     */
    public static void register(BlockShipsPlugin plugin) {
        if (plugin.getDisplayShip() == null || plugin.getDisplayShip().getTextureManager() == null) {
            plugin.getLogger().warning("Ship wheel texture manager unavailable — the wheel will not be "
                + "registered with DefCoreLib, and wheel identity will not survive a voyage.");
            return;
        }
        String tex = plugin.getDisplayShip().getTextureManager().getTexture(TEXTURE_SET, TEXTURE_VARIANT);
        if (tex == null || tex.isBlank()) {
            // CustomHeadBlock.build() throws for a placeable block with no texture, so bail with a clear
            // message rather than letting an IllegalStateException take down onEnable.
            plugin.getLogger().warning("No " + TEXTURE_SET + "/" + TEXTURE_VARIANT + " texture in items.yml — "
                + "the ship wheel will not be registered with DefCoreLib, and wheel identity will not "
                + "survive a voyage.");
            return;
        }
        try {
            anon.def9a2a4.corelib.CoreLibPlugin.getInstance().getRegistry().register(
                anon.def9a2a4.corelib.CustomHeadBlock.builder("blockships", "ship_wheel")
                    .name(net.kyori.adventure.text.Component.text("Ship Wheel"))
                    .texture(tex)
                    // Pinned rather than inherited: a piston shoving a wheel should break it (dropping the
                    // item through BlockShips' own path), not silently relocate it away from its map entry.
                    .breakOnPiston(true)
                    .build());
            texture = tex;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not register the ship wheel with DefCoreLib: " + t.getMessage()
                + " — wheel identity will not survive a voyage.");
        }
    }

    /** Whether {@code fullId} is the wheel's corelib type id. */
    public static boolean isWheelTypeId(String fullId) {
        return CustomItem.SHIP_WHEEL_CORELIB_ID.equals(fullId);
    }
}
