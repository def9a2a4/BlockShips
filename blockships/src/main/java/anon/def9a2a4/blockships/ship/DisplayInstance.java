package anon.def9a2a4.blockships.ship;

import org.bukkit.entity.Display;
import org.joml.Matrix4f;

/**
 * Represents a display entity (BlockDisplay or ItemDisplay) that is part of a ship.
 * Contains the entity reference and its base transformation matrix.
 */
public class DisplayInstance {
    public final Display entity;
    public final Matrix4f base;

    public DisplayInstance(Display entity, Matrix4f base) {
        this.entity = entity;
        this.base = base;
    }
}
