package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShipModel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Shulker;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Represents a collision box for a ship block.
 * Contains the carrier entity (ArmorStand), the shulker for physical collision,
 * and metadata about the collision configuration.
 */
public class CollisionBox {
    public final Entity carrier;           // Carrier entity (ArmorStand or Interaction)
    public final Shulker entity;           // Shulker passenger for physical collision
    public final Matrix4f base;            // Base transformation matrix
    public final ShipModel.CollisionConfig config;  // Collision configuration
    public final int blockIndex;           // Index of the block this collision box belongs to
    public Vector3f previousWorldPos;      // Track position for velocity calculation

    public CollisionBox(Entity carrier, Shulker entity, Matrix4f base, ShipModel.CollisionConfig config, int blockIndex) {
        this.carrier = carrier;
        this.entity = entity;
        this.base = base;
        this.config = config;
        this.blockIndex = blockIndex;
        this.previousWorldPos = new Vector3f(0, 0, 0);
    }
}
