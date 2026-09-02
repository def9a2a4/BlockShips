package anon.def9a2a4.blockships.customships;

import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.TrapDoor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockStructureScanner#bottomFaceY}, which decides where a hull's waterline sits.
 *
 * <p>Testable without a server because it is a pure {@code (BlockData, float) -> float}: the two rules
 * are {@code instanceof} checks, so a {@link Proxy} standing in for {@code TrapDoor} / {@code Slab} is
 * enough. {@code Bukkit.createBlockData} would need a running server; this does not.
 *
 * <p>Worth pinning because it is shared by two paths that used to disagree — the assembly scan and the
 * docked preview shulker — and the symptom of a regression is cosmetic and easy to dismiss: the
 * preview marker sits a half block off for any hull bottomed out in slabs.
 */
class BottomFaceYTest {

    /** A {@code BlockData} that is nothing in particular — the "normal block" case. */
    private static BlockData plain() {
        return proxy(BlockData.class, null);
    }

    private static BlockData trapDoor() {
        return proxy(TrapDoor.class, null);
    }

    private static BlockData slab(Slab.Type type) {
        return proxy(Slab.class, type);
    }

    /**
     * A do-nothing stand-in for a Bukkit BlockData interface. Only {@code getType()} on a Slab is ever
     * consulted, so everything else can answer with a harmless default.
     */
    private static BlockData proxy(Class<? extends BlockData> iface, Slab.Type slabType) {
        return (BlockData) Proxy.newProxyInstance(
            BottomFaceYTest.class.getClassLoader(),
            new Class<?>[] {iface},
            (p, method, args) -> {
                if (method.getName().equals("getType") && method.getParameterCount() == 0) {
                    return slabType;
                }
                if (method.getName().equals("toString")) return iface.getSimpleName() + " stub";
                if (method.getName().equals("equals")) return p == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(p);
                Class<?> r = method.getReturnType();
                if (r == boolean.class) return false;
                if (r.isPrimitive()) return 0;
                return null;
            });
    }

    @Test
    void anOrdinaryBlockBottomsOutAtItsOwnY() {
        assertEquals(4f, BlockStructureScanner.bottomFaceY(plain(), 4f));
        assertEquals(-3f, BlockStructureScanner.bottomFaceY(plain(), -3f));
    }

    /**
     * A trapdoor is excluded entirely, signalled by NaN — a hatch hanging under the deck must not drag
     * the hull's lower bound down with it. NaN, not a sentinel number, so callers have to handle it:
     * {@code Float.isNaN} is the only correct test, and {@code assertEquals} on NaN is not.
     */
    @Test
    void aTrapDoorIsExcluded() {
        assertTrue(Float.isNaN(BlockStructureScanner.bottomFaceY(trapDoor(), 4f)),
            "a trapdoor must be excluded from the hull's lower bound");
    }

    /** A top slab's solid half starts half a block up. */
    @Test
    void aTopSlabBottomsOutHalfABlockUp() {
        assertEquals(4.5f, BlockStructureScanner.bottomFaceY(slab(Slab.Type.TOP), 4f));
    }

    @Test
    void bottomAndDoubleSlabsBottomOutAtTheirOwnY() {
        assertEquals(4f, BlockStructureScanner.bottomFaceY(slab(Slab.Type.BOTTOM), 4f));
        assertEquals(4f, BlockStructureScanner.bottomFaceY(slab(Slab.Type.DOUBLE), 4f));
    }
}
