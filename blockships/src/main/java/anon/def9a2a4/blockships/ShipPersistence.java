package anon.def9a2a4.blockships;

import java.util.*;

public final class ShipPersistence {

    // ===== Serialization =====

    public static final class ShipState {
        public final UUID id;
        public final String shipType;  // Ship type identifier (e.g., "smallship", "bigship", "custom")
        public final String modelPath;  // Model path (for prefab ships)
        public final String worldName;
        public final double x, y, z;
        public final float yaw, pitch;
        public final String bannerData;  // Serialized banner ItemStack
        public final String woodType;  // Wood type string (e.g., "OAK", "DARK_OAK")
        public final String balloonColor;  // Balloon color for airships (e.g., "WHITE", "RED")
        public final Map<Integer, String> inventoryData;  // Block index -> Base64 serialized inventory contents
        public final Map<String, Object> modelData;  // Serialized model (for custom ships only, null for prefab)
        public final int entityCount;  // Expected entity count for recovery validation
        /** True iff this sidecar describes a DELEGATED (defCoreLib mechanism) ship — set for every delegated ship
         *  (fresh custom/prefab AND native→delegated migrations). Absent/false on a legacy native (0.0.17) sidecar.
         *  The migration reader uses it to tell a not-yet-migrated native ship (migrate it) from a reap-failed
         *  straggler of an already-migrated ship (reap only, never re-assemble). Non-final marker field. */
        public boolean migrated = false;

        public ShipState(UUID id, String shipType, String modelPath, String worldName, double x, double y, double z,
                         float yaw, float pitch, String bannerData, String woodType, String balloonColor,
                         Map<Integer, String> inventoryData, Map<String, Object> modelData, int entityCount) {
            this.id = id;
            this.shipType = shipType;
            this.modelPath = modelPath;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.bannerData = bannerData;
            this.woodType = woodType;
            this.balloonColor = balloonColor;
            this.inventoryData = inventoryData;
            this.modelData = modelData;
            this.entityCount = entityCount;
        }

    }
}

