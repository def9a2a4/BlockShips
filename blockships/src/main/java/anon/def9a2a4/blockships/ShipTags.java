package anon.def9a2a4.blockships;

import org.bukkit.Location;

import java.util.Set;
import java.util.UUID;

/**
 * Constants and utilities for scoreboard tags used to identify ship entities.
 */
public final class ShipTags {
    public static final String SHIP_PREFIX = "displayship:";
    public static final String SEAT_PREFIX = "shipseat:";
    public static final String STORAGE_PREFIX = "storage:";
    public static final String WHEEL_PREFIX = "shipwheel:";
    public static final String INTERACT_PREFIX = "interact:";
    public static final String LEADABLE_PREFIX = "leadable:";
    public static final String BLOCK_INDEX_PREFIX = "blockidx:";
    public static final String DISPLAY_INDEX_PREFIX = "displayidx:";
    public static final String CANNON_PREFIX = "cannon:";
    public static final String PARENT_TAG = "shipparent";
    public static final String CARRIER_TAG = "shipcarrier";
    public static final String COLLIDER_TAG = "shipcollider";

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

    public static String storageTag(int index) {
        return STORAGE_PREFIX + index;
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

    // Tag parsing helpers
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
            }
        }
        return null;
    }

    public static int extractSeatIndex(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(SEAT_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(SEAT_PREFIX.length()));
                } catch (NumberFormatException e) {
                    // Invalid index, continue checking
                }
            }
        }
        return -1;
    }

    public static int extractStorageIndex(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(STORAGE_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(STORAGE_PREFIX.length()));
                } catch (NumberFormatException e) {
                    // Invalid index, continue checking
                }
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
        for (String tag : tags) {
            if (tag.startsWith(INTERACT_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(INTERACT_PREFIX.length()));
                } catch (NumberFormatException e) {
                    // Invalid index, continue checking
                }
            }
        }
        return -1;
    }

    public static int extractLeadableIndex(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(LEADABLE_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(LEADABLE_PREFIX.length()));
                } catch (NumberFormatException e) {
                    // Invalid index, continue checking
                }
            }
        }
        return -1;
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
        for (String tag : tags) {
            if (tag.startsWith(BLOCK_INDEX_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(BLOCK_INDEX_PREFIX.length()));
                } catch (NumberFormatException e) {
                    // Invalid index, continue checking
                }
            }
        }
        return -1;
    }

    public static int extractDisplayIndex(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(DISPLAY_INDEX_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(DISPLAY_INDEX_PREFIX.length()));
                } catch (NumberFormatException e) {
                    // Invalid index, continue checking
                }
            }
        }
        return -1;
    }

    public static int extractCannonIndex(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith(CANNON_PREFIX)) {
                try {
                    return Integer.parseInt(tag.substring(CANNON_PREFIX.length()));
                } catch (NumberFormatException e) {
                    // Invalid index, continue checking
                }
            }
        }
        return -1;
    }

    public static boolean isParent(Set<String> tags) {
        return tags.contains(PARENT_TAG);
    }

    public static boolean isCarrier(Set<String> tags) {
        return tags.contains(CARRIER_TAG);
    }

    public static boolean isCollider(Set<String> tags) {
        return tags.contains(COLLIDER_TAG);
    }
}
