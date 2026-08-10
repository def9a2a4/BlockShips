package anon.def9a2a4.blockships;

import org.bukkit.Location;

import java.util.Set;
import java.util.UUID;

/**
 * Constants and utilities for scoreboard tags used to identify ship entities.
 */
public final class ShipTags {
    public static final String SHIP_PREFIX = "displayship:";
    /** defCoreLib entity tag prefix. A delegated ship's Mechanism tags its vehicle/collider/seat shulkers
     *  "corelib:mech:{mechId}:...". Since a delegated ship's id == its mechId, this bridges those tags back
     *  to the ship (see {@link #extractShipId}). Keep in sync with defCoreLib's MechanismRegistry tag scheme. */
    public static final String CORELIB_MECH_PREFIX = "corelib:mech:";
    public static final String SEAT_PREFIX = "shipseat:";
    public static final String WHEEL_PREFIX = "shipwheel:";
    public static final String INTERACT_PREFIX = "interact:";
    public static final String LEADABLE_PREFIX = "leadable:";
    public static final String BLOCK_INDEX_PREFIX = "blockidx:";
    public static final String DISPLAY_INDEX_PREFIX = "displayidx:";
    public static final String CANNON_PREFIX = "cannon:";
    public static final String PARENT_TAG = "shipparent";
    public static final String CARRIER_TAG = "shipcarrier";
    public static final String COLLIDER_TAG = "shipcollider";
    public static final String DYNLIGHT_PREFIX = "dynlight:";

    private ShipTags() {} // Prevent instantiation

    // Tag creation helpers
    public static String shipTag(UUID id) {
        return SHIP_PREFIX + id;
    }

    public static String shipRootTag(UUID id) {
        return SHIP_PREFIX + id + ":root";
    }

    public static String seatTag(int index) {
        return SEAT_PREFIX + index;
    }

    public static String wheelTag(Location loc) {
        return WHEEL_PREFIX + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    public static String interactTag(int index) {
        return INTERACT_PREFIX + index;
    }

    public static String leadableTag(int index) {
        return LEADABLE_PREFIX + index;
    }

    public static String blockIndexTag(int index) {
        return BLOCK_INDEX_PREFIX + index;
    }

    public static String displayIndexTag(int index) {
        return DISPLAY_INDEX_PREFIX + index;
    }

    public static String cannonTag(int obsidianBlockIndex) {
        return CANNON_PREFIX + obsidianBlockIndex;
    }

    public static String dynlightTag(int lightLevel) {
        return DYNLIGHT_PREFIX + lightLevel;
    }

    /**
     * Normalizes a yaw angle to the 0-360 range.
     */
    public static float normalizeYaw(float yaw) {
        yaw = yaw % 360;
        if (yaw < 0) yaw += 360;
        return yaw;
    }

    // Tag parsing helpers

    private static int extractIntIndex(Set<String> tags, String prefix) {
        for (String tag : tags) {
            if (tag.startsWith(prefix)) {
                try {
                    return Integer.parseInt(tag.substring(prefix.length()));
                } catch (NumberFormatException e) {
                    // Invalid index, continue checking
                }
            }
        }
        return -1;
    }

    public static UUID extractShipId(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(SHIP_PREFIX)) {
                String idPart = tag.substring(SHIP_PREFIX.length());
                // Handle root tags like "displayship:uuid:root"
                int colonIdx = idPart.indexOf(':');
                if (colonIdx > 0) {
                    idPart = idPart.substring(0, colonIdx);
                }
                try {
                    return UUID.fromString(idPart);
                } catch (IllegalArgumentException e) {
                    // Invalid UUID format, continue checking
                }
            } else if (tag.startsWith(CORELIB_MECH_PREFIX)) {
                // Delegated-engine bridge (M4): a Mechanism-owned collider/seat shulker carries
                // "corelib:mech:{mechId}:{i}:collider|seat". A delegated ship's id == its mechId, so return
                // that so ShipRegistry.byId resolves it in every handler (boarding, damage, exit).
                String rest = tag.substring(CORELIB_MECH_PREFIX.length());
                int colonIdx = rest.indexOf(':');
                String idPart = colonIdx > 0 ? rest.substring(0, colonIdx) : rest;
                try {
                    return UUID.fromString(idPart);
                } catch (IllegalArgumentException e) {
                    // Invalid UUID format, continue checking
                }
            }
        }
        return null;
    }

    public static int extractSeatIndex(Set<String> tags) {
        return extractIntIndex(tags, SEAT_PREFIX);
    }

    /**
     * Whether these tags mark the DRIVER seat of a delegated (defCoreLib) ship. A delegated seat shulker
     * carries {@code corelib:mech:{id}:{blockIndex}:driver_seat} (see defCoreLib BasicMechanism.designateSeat)
     * instead of {@code shipseat:0}, so the steering listeners test this alongside {@code extractSeatIndex==0}.
     * Reading the (immutable) tag snapshot is thread-safe — usable from the ProtocolLib netty thread, unlike a
     * cross-thread {@code seatShulkers} list lookup.
     */
    public static boolean isCorelibDriverSeat(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(CORELIB_MECH_PREFIX) && tag.endsWith(":driver_seat")) return true;
        }
        return false;
    }

    /**
     * Block index {@code i} from a delegated (defCoreLib) collider/seat shulker tag
     * {@code corelib:mech:{mechId}:{i}:collider|seat|driver_seat}, or -1 if none. This is the delegated-engine
     * analog of {@link #extractCannonIndex}/{@link #extractInteractIndex} — a
     * Mechanism-owned shulker carries only the {@code corelib:mech:} tag, so those native extractors return -1
     * for it, and the click router needs the block index to route via the parity invariant (mechanism block
     * index == model.parts index). Parsed with {@code indexOf} off the literal prefix (the mechId UUID has no
     * colons) rather than {@code split}. The vehicle tag {@code corelib:mech:{id}:vehicle} has no second colon
     * → returns -1. Scans ALL tags and continues past a non-index/parse failure (a driver seat carries
     * {@code :collider}+{@code :seat}+{@code :driver_seat} at once — all agree on {@code i}).
     */
    public static int extractCorelibBlockIndex(Set<String> tags) {
        for (String tag : tags) {
            if (!tag.startsWith(CORELIB_MECH_PREFIX)) continue;
            String rest = tag.substring(CORELIB_MECH_PREFIX.length()); // "{uuid}:{i}:{role}" | "{uuid}:vehicle"
            int c1 = rest.indexOf(':');
            if (c1 < 0) continue;
            String after = rest.substring(c1 + 1);                     // "{i}:{role}" | "vehicle"
            int c2 = after.indexOf(':');
            if (c2 < 0) continue;                                      // vehicle tag → no index
            try {
                return Integer.parseInt(after.substring(0, c2));
            } catch (NumberFormatException e) {
                // Not an index-bearing corelib tag; keep scanning.
            }
        }
        return -1;
    }

    public static String extractWheelLocation(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(WHEEL_PREFIX)) {
                return tag.substring(WHEEL_PREFIX.length());
            }
        }
        return null;
    }

    public static int extractInteractIndex(Set<String> tags) {
        return extractIntIndex(tags, INTERACT_PREFIX);
    }

    public static int extractLeadableIndex(Set<String> tags) {
        return extractIntIndex(tags, LEADABLE_PREFIX);
    }

    /**
     * Whether any tag marks this entity as owned by a defCoreLib mechanism ({@code corelib:mech:...} — the
     * delegated ship's vehicle, colliders, displays, and seat shulkers). Native recovery/orphan-cleanup must
     * skip these: defCoreLib owns their lifecycle and recovers them itself, so BlockShips must neither reap
     * them nor run native (parent-BlockDisplay-based) recovery on them.
     */
    public static boolean isCorelibTagged(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(CORELIB_MECH_PREFIX)) return true;
        }
        return false;
    }

    public static boolean isShipEntity(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(SHIP_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    public static int extractBlockIndex(Set<String> tags) {
        return extractIntIndex(tags, BLOCK_INDEX_PREFIX);
    }

    public static int extractDisplayIndex(Set<String> tags) {
        return extractIntIndex(tags, DISPLAY_INDEX_PREFIX);
    }

    public static int extractCannonIndex(Set<String> tags) {
        return extractIntIndex(tags, CANNON_PREFIX);
    }

    public static boolean isParent(Set<String> tags) {
        return tags.contains(PARENT_TAG);
    }

    public static boolean isRoot(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(SHIP_PREFIX) && tag.endsWith(":root")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isCarrier(Set<String> tags) {
        return tags.contains(CARRIER_TAG);
    }

    public static boolean isCollider(Set<String> tags) {
        return tags.contains(COLLIDER_TAG);
    }
}
