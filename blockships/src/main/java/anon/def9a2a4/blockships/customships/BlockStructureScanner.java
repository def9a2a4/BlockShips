package anon.def9a2a4.blockships.customships;

import anon.def9a2a4.blockships.BlockShipsPlugin;
import anon.def9a2a4.blockships.ShipModel;
import anon.def9a2a4.blockships.ShipTags;
import anon.def9a2a4.blockships.blockconfig.BlockConfigManager;
import anon.def9a2a4.blockships.blockconfig.BlockProperties;
import anon.def9a2a4.blockships.blockconfig.ShipDetector;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.TrapDoor;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Scans blocks using flood fill and converts them into a ShipModel.
 * Uses BlockConfigManager to determine which blocks are allowed.
 */
public class BlockStructureScanner {

    // ========== Rotation Utility Methods ==========

    /**
     * Converts a BlockFace to Minecraft yaw angle.
     * Minecraft yaw: 0=South, 90=West, 180=North, 270=East
     */
    public static float blockFaceToYaw(BlockFace face) {
        switch (face) {
            case SOUTH: return 0.0f;
            case WEST: return 90.0f;
            case NORTH: return 180.0f;
            case EAST: return 270.0f;
            default: return 0.0f;
        }
    }

    /**
     * Converts yaw angle to BlockFace.
     */
    public static BlockFace yawToBlockFace(float yaw) {
        // Normalize to 0-360
        yaw = ShipTags.normalizeYaw(yaw);

        // Round to nearest 90 degrees
        int rounded = Math.round(yaw / 90.0f) * 90;
        rounded = rounded % 360;

        if (rounded == 0) return BlockFace.SOUTH;
        if (rounded == 90) return BlockFace.WEST;
        if (rounded == 180) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    /**
     * Rotates a 3D position around the Y-axis.
     * @param pos The position to rotate
     * @param yawDegrees The rotation angle in degrees
     * @return A new rotated position
     */
    public static Vector3f rotatePosition(Vector3f pos, float yawDegrees) {
        float rad = (float) Math.toRadians(yawDegrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        return new Vector3f(
            pos.x * cos - pos.z * sin,
            pos.y,
            pos.x * sin + pos.z * cos
        );
    }

    /**
     * Rotates a BlockFace by a yaw offset.
     * Only handles horizontal faces (NORTH/SOUTH/EAST/WEST).
     */
    public static BlockFace rotateBlockFace(BlockFace face, float yawDegrees) {
        // Only handle horizontal faces
        if (face == BlockFace.UP || face == BlockFace.DOWN) {
            return face;
        }

        float baseYaw = blockFaceToYaw(face);
        float newYaw = (baseYaw + yawDegrees) % 360;
        if (newYaw < 0) newYaw += 360;

        return yawToBlockFace(newYaw);
    }

    /**
     * Rotates BlockData properties (stairs, chests, doors, etc.) by a yaw offset.
     * @param originalData The original block data
     * @param yawDegrees The rotation angle in degrees (should be multiple of 90)
     * @return A new rotated BlockData
     */
    public static BlockData rotateBlockData(BlockData originalData, float yawDegrees) {
        BlockData rotated = originalData.clone();

        // Round to nearest 90 degrees
        int rotationSteps = Math.round(yawDegrees / 90.0f) % 4;
        if (rotationSteps < 0) rotationSteps += 4;
        if (rotationSteps == 0) return rotated;

        // Handle Directional blocks (stairs, chests, doors, etc.)
        if (rotated instanceof org.bukkit.block.data.Directional) {
            org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) rotated;
            BlockFace originalFacing = directional.getFacing();
            BlockFace newFacing = rotateBlockFace(originalFacing, yawDegrees);

            if (directional.getFaces().contains(newFacing)) {
                directional.setFacing(newFacing);
            }
        }

        // Handle Orientable blocks (logs, pillars, hay bales, etc.)
        if (rotated instanceof org.bukkit.block.data.Orientable orientable) {
            org.bukkit.Axis currentAxis = orientable.getAxis();

            // Y-axis stays Y (vertical), X and Z swap on 90/270 degree rotations
            if (currentAxis != org.bukkit.Axis.Y && rotationSteps % 2 == 1) {
                org.bukkit.Axis newAxis = (currentAxis == org.bukkit.Axis.X)
                    ? org.bukkit.Axis.Z
                    : org.bukkit.Axis.X;
                orientable.setAxis(newAxis);
            }
        }

        // Handle Rotatable blocks (player heads on floor, banners on floor)
        if (rotated instanceof org.bukkit.block.data.Rotatable) {
            org.bukkit.block.data.Rotatable rotatable = (org.bukkit.block.data.Rotatable) rotated;
            BlockFace currentRot = rotatable.getRotation();

            // Rotatable uses 16 directions (each 22.5 degrees)
            // 90 degrees = 4 steps in the rotation system
            int currentStep = rotationToStep(currentRot);
            int newStep = (currentStep + (rotationSteps * 4)) % 16;
            BlockFace newRot = stepToRotation(newStep);

            rotatable.setRotation(newRot);
        }

        // Handle MultipleFacing blocks (fences, walls, etc.)
        if (rotated instanceof org.bukkit.block.data.MultipleFacing) {
            org.bukkit.block.data.MultipleFacing mf = (org.bukkit.block.data.MultipleFacing) rotated;
            Set<BlockFace> originalFaces = new HashSet<>(mf.getFaces());

            // Clear all faces first
            for (BlockFace face : originalFaces) {
                mf.setFace(face, false);
            }

            // Set rotated faces
            for (BlockFace face : originalFaces) {
                BlockFace newFace = rotateBlockFace(face, yawDegrees);
                if (mf.getAllowedFaces().contains(newFace)) {
                    mf.setFace(newFace, true);
                }
            }
        }

        return rotated;
    }

    /**
     * Converts a Rotatable BlockFace to step (0-15).
     * Step 0 = SOUTH, 4 = WEST, 8 = NORTH, 12 = EAST
     */
    private static int rotationToStep(BlockFace face) {
        switch (face) {
            case SOUTH: return 0;
            case SOUTH_SOUTH_WEST: return 1;
            case SOUTH_WEST: return 2;
            case WEST_SOUTH_WEST: return 3;
            case WEST: return 4;
            case WEST_NORTH_WEST: return 5;
            case NORTH_WEST: return 6;
            case NORTH_NORTH_WEST: return 7;
            case NORTH: return 8;
            case NORTH_NORTH_EAST: return 9;
            case NORTH_EAST: return 10;
            case EAST_NORTH_EAST: return 11;
            case EAST: return 12;
            case EAST_SOUTH_EAST: return 13;
            case SOUTH_EAST: return 14;
            case SOUTH_SOUTH_EAST: return 15;
            default: return 0;
        }
    }

    /**
     * Converts a step (0-15) to Rotatable BlockFace.
     */
    private static BlockFace stepToRotation(int step) {
        step = step % 16;
        if (step < 0) step += 16;
        switch (step) {
            case 0: return BlockFace.SOUTH;
            case 1: return BlockFace.SOUTH_SOUTH_WEST;
            case 2: return BlockFace.SOUTH_WEST;
            case 3: return BlockFace.WEST_SOUTH_WEST;
            case 4: return BlockFace.WEST;
            case 5: return BlockFace.WEST_NORTH_WEST;
            case 6: return BlockFace.NORTH_WEST;
            case 7: return BlockFace.NORTH_NORTH_WEST;
            case 8: return BlockFace.NORTH;
            case 9: return BlockFace.NORTH_NORTH_EAST;
            case 10: return BlockFace.NORTH_EAST;
            case 11: return BlockFace.EAST_NORTH_EAST;
            case 12: return BlockFace.EAST;
            case 13: return BlockFace.EAST_SOUTH_EAST;
            case 14: return BlockFace.SOUTH_EAST;
            case 15: return BlockFace.SOUTH_SOUTH_EAST;
            default: return BlockFace.SOUTH;
        }
    }

    /**
     * Set of materials that are attachable blocks (need support from other blocks).
     * These are removed BEFORE their support blocks to prevent item drops.
     * Built once at class load time for O(1) runtime lookups.
     */
    private static final Set<Material> ATTACHABLE_MATERIALS = buildAttachableMaterials();

    private static Set<Material> buildAttachableMaterials() {
        Set<Material> set = EnumSet.noneOf(Material.class);

        // Patterns to match against material names (using contains())
        String[] patterns = {
            "BANNER", "SIGN", "TORCH", "BUTTON", "LEVER", "CARPET", "PRESSURE_PLATE",
            "LADDER", "LANTERN", "BELL", "CANDLE",
            "REPEATER", "COMPARATOR", "TRIPWIRE", "RAIL"
        };

        for (Material mat : Material.values()) {
            String name = mat.name();

            // Exact match for REDSTONE (to avoid REDSTONE_BLOCK, REDSTONE_ORE, etc.)
            if (name.equals("REDSTONE")) {
                set.add(mat);
            }

            // Pattern matching (contains)
            for (String pattern : patterns) {
                if (name.contains(pattern)) {
                    set.add(mat);
                    break;
                }
            }
        }

        return set;
    }

    private static boolean isAttachable(Material type) {
        return ATTACHABLE_MATERIALS.contains(type);
    }

    // ========== Main Methods ==========

    /**
     * Scans blocks using flood fill from the ship wheel location.
     * Uses BlockConfigManager and ShipDetector to find all allowed connected blocks.
     *
     * @param wheelLocation The location of the ship wheel block
     * @param facing The direction the ship wheel is facing
     * @return A ShipModel representing the scanned blocks, or null if scan fails
     */
    public static ShipModel scanStructure(Location wheelLocation, BlockFace facing) {
        // Get max ship size from config
        BlockShipsPlugin plugin = (BlockShipsPlugin) org.bukkit.Bukkit.getPluginManager().getPlugin("BlockShips");
        int maxShipSize = 1000; // Default
        int maxScanSize = 5000; // Default
        if (plugin != null) {
            maxShipSize = plugin.getConfig().getInt("custom-ships.max-ship-size", 1000);
            maxScanSize = plugin.getConfig().getInt("custom-ships.max-scan-size", 5000);
        }

        // Use ShipDetector to flood fill and find all ship blocks
        ShipDetector detector = new ShipDetector(maxShipSize, maxScanSize);
        ShipDetector.ShipDetectionResult result = detector.detectShipDetailed(wheelLocation);

        if (!result.isSuccess()) {
            return null;
        }

        Set<Location> shipBlocks = result.getBlocks();
        if (shipBlocks == null || shipBlocks.isEmpty()) {
            return null;
        }

        List<ShipModel.ModelPart> parts = new ArrayList<>();
        List<ShipModel.SeatInfo> seats = new ArrayList<>();
        BlockConfigManager configManager = BlockConfigManager.getInstance();

        int blockIndex = 0;
        Location wheelOrigin = wheelLocation.clone();

        // Map relative positions to block indices (for finding driver seat block)
        Map<String, Integer> positionToBlockIndex = new HashMap<>();

        // Track weight and center of volume (only for blocks with weight)
        int totalWeight = 0;
        int totalMass = 0;  // Sum of max(0, weight) per block - used for health and power ratio
        int weightedBlockCount = 0;
        float sumX = 0, sumY = 0, sumZ = 0;

        // Track sail blocks and engines for ship stats (power-to-mass ratio)
        int woolCount = 0;
        int bannerCount = 0;
        int engineCount = 0;
        List<Integer> engineBlockIndices = new ArrayList<>();

        // Track ship bounds (for all blocks)
        float minY = Float.MAX_VALUE;
        float maxY = Float.MIN_VALUE;

        // Process each detected block
        for (Location blockLoc : shipBlocks) {
            Block block = blockLoc.getBlock();
            BlockData blockData = block.getBlockData();

            // Force double chests to single to prevent item duplication (GitHub #12)
            // and display issues. Each half keeps only its own inventory slots.
            if (blockData instanceof org.bukkit.block.data.type.Chest) {
                org.bukkit.block.data.type.Chest chestData = (org.bukkit.block.data.type.Chest) blockData;
                if (chestData.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                    chestData.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
                }
            }

            // Get block properties from config
            BlockProperties props = configManager.getProperties(block.getType(), blockData);

            // Calculate position relative to wheel
            double dx = blockLoc.getX() - wheelOrigin.getX();
            double dy = blockLoc.getY() - wheelOrigin.getY();
            double dz = blockLoc.getZ() - wheelOrigin.getZ();

            // Track ship bounds (all blocks contribute, except trapdoors)
            float blockY = (float) dy;
            if (!(blockData instanceof TrapDoor)) {
                float adjustedMinY = blockY;
                float adjustedMaxY = blockY;
                if (blockData instanceof Slab slab) {
                    if (slab.getType() == Slab.Type.TOP) {
                        adjustedMinY = blockY + 0.5f;
                    } else if (slab.getType() == Slab.Type.BOTTOM) {
                        adjustedMaxY = blockY - 0.5f;
                    }
                }
                if (adjustedMinY < minY) minY = adjustedMinY;
                if (adjustedMaxY > maxY) maxY = adjustedMaxY;
            }

            // Only accumulate weight and center of volume for blocks with weight
            // Blocks with null weight are excluded from density calculations
            if (props.hasWeight()) {
                int weight = props.getWeight();
                totalWeight += weight;
                if (weight > 0) {
                    totalMass += weight;
                }
                weightedBlockCount++;
                sumX += (float) dx;
                sumY += (float) dy;
                sumZ += (float) dz;
            }

            // Count sail blocks and engines for ship stats
            boolean isEngine = false;
            Material blockMaterial = block.getType();
            if (Tag.WOOL.isTagged(blockMaterial)) {
                woolCount++;
            } else if (blockMaterial.name().contains("BANNER")) {
                bannerCount++;
            } else if (blockMaterial == Material.BLAST_FURNACE && plugin != null) {
                // Check PDC for ship engine tag
                org.bukkit.block.BlockState blockState = block.getState();
                if (blockState instanceof org.bukkit.block.TileState tileState) {
                    NamespacedKey engineKey = new NamespacedKey(plugin, "custom_item_id");
                    String val = tileState.getPersistentDataContainer()
                        .get(engineKey, org.bukkit.persistence.PersistentDataType.STRING);
                    if ("ship_engine".equals(val)) {
                        isEngine = true;
                        engineCount++;
                        engineBlockIndices.add(blockIndex);
                    }
                }
            }

            // Store position to block index mapping (for finding driver seat block)
            String posKey = (int)dx + "," + (int)dy + "," + (int)dz;
            positionToBlockIndex.put(posKey, blockIndex);

            // Create transformation matrix for this block (translation only - for collision/disassembly)
            Matrix4f transform = new Matrix4f()
                .identity()
                .translate((float) dx, (float) dy, (float) dz);

            // Get collision config from block properties
            anon.def9a2a4.blockships.blockconfig.CollisionConfig colliderConfig = props.getCollider();
            ShipModel.CollisionConfig collision;
            if (colliderConfig.isEnabled()) {
                Vector3f colliderOffset = new Vector3f(colliderConfig.getOffset());

                // Wall heads/skulls: shift the 0.5 shulker toward the wall + up so it
                // sits where the head renders (see applySkullTransform in ShipInstance).
                // Gate on Skull state + Directional so only wall heads are shifted (not
                // other small directional blocks), and on size <= 0.5 so dragon's
                // full-block collider is left centered.
                if (block.getState() instanceof org.bukkit.block.Skull
                        && blockData instanceof org.bukkit.block.data.Directional wallDir
                        && colliderConfig.getSize() <= 0.5f) {
                    org.bukkit.util.Vector f = wallDir.getFacing().getDirection();
                    colliderOffset.set(-(float) f.getX() * 0.25f, 0.25f, -(float) f.getZ() * 0.25f);
                }

                collision = new ShipModel.CollisionConfig(
                    true,
                    colliderConfig.getSize(),
                    colliderOffset
                );
            } else {
                collision = new ShipModel.CollisionConfig(false, 1.0f, new Vector3f(0, 0, 0));
            }

            // Create raw YAML map (for compatibility)
            Map<String, Object> rawYaml = new HashMap<>();
            if (isEngine) {
                rawYaml.put("is_engine", true);
            }

            // Check for storage blocks (chests, furnaces, hoppers, etc.)
            ShipModel.StorageConfig storage = null;
            if (block.getState() instanceof org.bukkit.block.Container) {
                storage = createStorageConfig(block);
                if (storage != null) {
                    // Serialize inventory contents
                    org.bukkit.block.Container container = (org.bukkit.block.Container) block.getState();
                    org.bukkit.inventory.Inventory inv = container.getSnapshotInventory();
                    rawYaml.put("container_items", serializeInventory(inv));

                    // NOTE: the world container is intentionally NOT emptied here. Clearing is
                    // deferred to removeBlocks() (Pass 0) so that if assembly throws between the
                    // scan and removeBlocks (e.g. in the ShipInstance constructor), the world
                    // container keeps its contents -> clean, retryable no-op instead of item loss.

                    // Serialize storage config for persistence
                    Map<String, Object> storageMap = new HashMap<>();
                    storageMap.put("type", storage.type.name());
                    storageMap.put("name", storage.name);
                    rawYaml.put("storage", storageMap);
                }
            }

            // Check for TileStateInventoryHolder blocks (shelves, chiseled bookshelves)
            // These implement InventoryHolder but NOT Container, so need separate handling
            if (block.getState() instanceof io.papermc.paper.block.TileStateInventoryHolder tileInv) {
                java.util.List<Map<String, Object>> tileItems = serializeInventory(tileInv.getSnapshotInventory());
                if (!tileItems.isEmpty()) {
                    rawYaml.put("container_items", tileItems);
                }
                // Clearing is deferred to removeBlocks() (Pass 0) - see the Container branch above.
                // (A plain Container is also a TileStateInventoryHolder, so it re-serializes its
                // still-full snapshot here over container_items with byte-identical data; harmless.)
            }

            // Capture sign text for restoration on disassembly
            if (block.getState() instanceof org.bukkit.block.Sign sign) {
                Map<String, Object> signData = new HashMap<>();
                for (org.bukkit.block.sign.Side side : org.bukkit.block.sign.Side.values()) {
                    org.bukkit.block.sign.SignSide signSide = sign.getSide(side);
                    java.util.List<String> lines = new java.util.ArrayList<>();
                    for (net.kyori.adventure.text.Component line : signSide.lines()) {
                        lines.add(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
                            .gson().serialize(line));
                    }
                    String key = side.name().toLowerCase();
                    signData.put(key + "_lines", lines);
                    // getColor() is @Nullable (Colorable); default to BLACK to avoid an NPE that would abort
                    // the scan. Restore side (:924-925) already tolerates null.
                    org.bukkit.DyeColor signColor = signSide.getColor();
                    signData.put(key + "_color", (signColor != null ? signColor : org.bukkit.DyeColor.BLACK).name());
                    signData.put(key + "_glowing", signSide.isGlowingText());
                }
                signData.put("waxed", sign.isWaxed());
                rawYaml.put("sign_data", signData);
            }

            // Check if this block is a seat (all detected seats are passenger seats)
            // Driver seat is always at the wheel location (added after scanning)
            if (props.isSeat()) {
                Vector3f seatOffset = new Vector3f((float) dx, (float) dy, (float) dz);
                seats.add(new ShipModel.SeatInfo(seatOffset, blockIndex, false));
            }

            // Check if this block is an interaction block (crafting table, anvil, etc.)
            if (props.isInteraction()) {
                rawYaml.put("interaction", true);
            }

            // Check if this block is leadable (fences) and capture any leashed entities
            if (props.isLeadable()) {
                rawYaml.put("leadable", true);
                // Find entities leashed to this fence block via LeashHitch
                List<String> leashedEntityUUIDs = findLeashedEntities(blockLoc);
                if (!leashedEntityUUIDs.isEmpty()) {
                    rawYaml.put("leashed_entity_uuids", leashedEntityUUIDs);
                }
            }

            rawYaml.put("block", block.getType().name());

            // Store BlockData as string to preserve ALL block properties
            // (stairs half/facing, slabs type, chest facing, doors hinge/half, etc.)
            rawYaml.put("blockdata", blockData.getAsString());

            // Store display rotation for blocks that need manual rotation (BlockDisplay ignores their facing)
            if (props.needsDisplayRotation() && blockData instanceof org.bukkit.block.data.Directional) {
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) blockData;
                float facingYaw = blockFaceToYaw(directional.getFacing());
                rawYaml.put("display_yaw", facingYaw);
            }

            // Capture special block metadata that BlockData can't preserve.
            // All heads/skulls (player AND mob) render as ItemDisplay + HEAD transform,
            // so capture their rotation/facing. Only player heads carry a skin profile.
            if (block.getState() instanceof org.bukkit.block.Skull) {
                org.bukkit.block.Skull skull = (org.bukkit.block.Skull) block.getState();
                com.destroystokyo.paper.profile.PlayerProfile profile = skull.getPlayerProfile();
                if (profile != null) {
                    // Serialize the profile to Base64 (player heads only)
                    rawYaml.put("skull_profile", serializeProfile(profile));
                }

                // Store rotation (floor, 16-step) or facing (wall, 4-direction)
                if (blockData instanceof org.bukkit.block.data.Rotatable) {
                    org.bukkit.block.data.Rotatable rotatable = (org.bukkit.block.data.Rotatable) blockData;
                    rawYaml.put("skull_rotation", rotatable.getRotation().name());
                } else if (blockData instanceof org.bukkit.block.data.Directional) {
                    org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) blockData;
                    rawYaml.put("skull_facing", directional.getFacing().name());
                }
            }

            // Banners: store patterns
            if (block.getType().name().contains("BANNER")) {
                if (block.getState() instanceof org.bukkit.block.Banner) {
                    org.bukkit.block.Banner banner = (org.bukkit.block.Banner) block.getState();
                    java.util.List<org.bukkit.block.banner.Pattern> patterns = banner.getPatterns();
                    if (!patterns.isEmpty()) {
                        // Serialize patterns to a list of maps
                        java.util.List<Map<String, Object>> patternList = new java.util.ArrayList<>();
                        for (org.bukkit.block.banner.Pattern pattern : patterns) {
                            Map<String, Object> patternMap = new HashMap<>();
                            patternMap.put("color", pattern.getColor().name());
                            NamespacedKey patternKey = Registry.BANNER_PATTERN.getKey(pattern.getPattern());
                            patternMap.put("pattern", patternKey != null ? patternKey.getKey().toUpperCase() : "BASE");
                            patternList.add(patternMap);
                        }
                        rawYaml.put("banner_patterns", patternList);
                    }

                    // Store facing/rotation
                    if (blockData instanceof org.bukkit.block.data.Rotatable) {
                        org.bukkit.block.data.Rotatable rotatable = (org.bukkit.block.data.Rotatable) blockData;
                        rawYaml.put("banner_rotation", rotatable.getRotation().name());
                    } else if (blockData instanceof org.bukkit.block.data.Directional) {
                        org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) blockData;
                        rawYaml.put("banner_facing", directional.getFacing().name());
                    }
                }
            }

            // Persist a block's custom name (anvil-renamed containers, banners, ...) - Nameable tile-entity
            // NBT that blockdata can't carry. Restored generically in placeBlocks; used as the storage GUI title.
            if (block.getState() instanceof org.bukkit.Nameable nameable) {
                net.kyori.adventure.text.Component cn = nameable.customName();
                if (cn != null) {
                    rawYaml.put("custom_name",
                        net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(cn));
                }
            }

            parts.add(new ShipModel.ModelPart(blockData, transform, collision, storage, rawYaml));
            blockIndex++;
        }

        // Must have at least one block
        if (parts.isEmpty()) {
            return null;
        }

        // Driver seat is always behind the wheel, inserted at index 0
        // Any detected seat blocks become passenger seats (already added above)
        // Find the block index for driver seat:
        // 1. Check block behind the wheel
        // 2. Check block under that
        // 3. Fallback to wheel block (index 0)
        BlockFace opposite = facing.getOppositeFace();

        String behindKey = opposite.getModX() + ",0," + opposite.getModZ();
        String underKey = opposite.getModX() + ",-1," + opposite.getModZ();

        int driverBlockIndex = 0; // fallback to wheel block
        if (positionToBlockIndex.containsKey(behindKey)) {
            driverBlockIndex = positionToBlockIndex.get(behindKey);
        } else if (positionToBlockIndex.containsKey(underKey)) {
            driverBlockIndex = positionToBlockIndex.get(underKey);
        }

        Vector3f driverSeatOffset = new Vector3f(opposite.getModX(), 0, opposite.getModZ());
        seats.add(0, new ShipModel.SeatInfo(driverSeatOffset, driverBlockIndex, true));

        // Create ShipModel with default settings
        // Calculate assembly yaw for disassembly rotation tracking
        float assemblyYaw = blockFaceToYaw(facing);
        Vector3f initialRotation = new Vector3f(assemblyYaw, 0, 0);  // Rotate displays to match wheel facing
        Vector3f positionOffset = new Vector3f(0, 0, 0);
        Vector3f collisionOffset = new Vector3f(0, 0, 0);
        Matrix3f rotationTransform = new Matrix3f().identity();

        // Calculate health from positive block weights (heavier blocks = more health)
        // Blocks with negative/zero weight don't reduce health, just contribute nothing
        double maxHealth = Math.min(1024.0, Math.max(1.0, totalMass));
        // TODO: healthRegenPerSecond may be modified in the future by properties of the ship
        double healthRegenPerSecond = 1.0;

        // Calculate center of volume (only from blocks with weight)
        Vector3f centerOfVolume = weightedBlockCount > 0
            ? new Vector3f(sumX / weightedBlockCount, sumY / weightedBlockCount, sumZ / weightedBlockCount)
            : new Vector3f(0, 0, 0);

        // Default bounds if no blocks found (shouldn't happen, but be safe)
        if (minY == Float.MAX_VALUE) minY = 0;
        if (maxY == Float.MIN_VALUE) maxY = 0;

        // Calculate float offset from density (same formula as ShipPhysics)
        float waterFloatOffset;
        if (weightedBlockCount > 0) {
            float meanDensity = (float) totalWeight / weightedBlockCount;
            float airDensity = (float) plugin.getConfig().getDouble("custom-ships.buoyancy.air-density", 0.0);
            float waterDensity = (float) plugin.getConfig().getDouble("custom-ships.buoyancy.water-density", 2.5);

            float t = (meanDensity - airDensity) / (waterDensity - airDensity);
            float referenceY = minY;
            float waterlineY = referenceY + t * (centerOfVolume.y() - referenceY);
            waterFloatOffset = -waterlineY;
        } else {
            waterFloatOffset = 0.25f;  // Fallback
        }

        // Detect cannons (dispenser + obsidian behind)
        List<ShipModel.CannonInfo> cannons = detectCannons(parts);

        // Load configurable sail power values
        int woolPower = plugin.getConfig().getInt("custom-ships.stats.wool-power", 3);
        int bannerPower = plugin.getConfig().getInt("custom-ships.stats.banner-power", 7);

        return new ShipModel(
            parts,
            Collections.emptyList(),  // No items for MVP
            initialRotation,
            positionOffset,
            collisionOffset,
            rotationTransform,
            seats,
            cannons,
            waterFloatOffset,
            maxHealth,
            healthRegenPerSecond,
            totalWeight,
            totalMass,
            weightedBlockCount,  // Only count blocks with weight for density
            centerOfVolume,
            minY,
            maxY,
            assemblyYaw,  // Store for disassembly rotation calculation
            woolCount,
            bannerCount,
            woolPower,
            bannerPower,
            engineCount,
            engineBlockIndices
        );
    }

    /**
     * Result of placement area validation, containing conflict counts.
     */
    public static class PlacementConflicts {
        public final int fragile;
        public final int hard;
        /** Cells inside a WorldGuard-protected region the player can't build in (dropped as items, not placed). */
        public final int protectedCount;

        public PlacementConflicts(int fragile, int hard) {
            this(fragile, hard, 0);
        }

        public PlacementConflicts(int fragile, int hard, int protectedCount) {
            this.fragile = fragile;
            this.hard = hard;
            this.protectedCount = protectedCount;
        }

        public int total() { return fragile + hard + protectedCount; }
        public boolean isClear() { return total() == 0; }
    }

    /**
     * Validates placement area and returns conflict counts.
     *
     * @param wheelLocation The center location where the wheel will be placed
     * @param model The ship model to check
     * @param currentShipYaw The ship's current yaw rotation
     * @return PlacementConflicts with fragile and hard conflict counts
     */
    public static PlacementConflicts validatePlacementArea(Location wheelLocation, ShipModel model, float currentShipYaw) {
        return validatePlacementArea(wheelLocation, model, currentShipYaw, null);
    }

    /**
     * Validates placement area, additionally treating WorldGuard-protected cells (that {@code player}
     * cannot build in) as conflicts. {@code player} may be null (crash/system path → checked as a
     * non-member). Protected cells are counted as {@code protectedCount} and take precedence over
     * fragile/hard classification.
     */
    public static PlacementConflicts validatePlacementArea(Location wheelLocation, ShipModel model, float currentShipYaw,
                                                           org.bukkit.entity.Player player) {
        // Calculate rotation delta from assembly orientation
        float rotationDelta = currentShipYaw - model.assemblyYaw;
        while (rotationDelta < 0) rotationDelta += 360;
        while (rotationDelta >= 360) rotationDelta -= 360;

        int fragile = 0;
        int hard = 0;
        int protectedCount = 0;

        // O(1) gate: only pay per-cell WorldGuard queries in worlds that actually have regions.
        // Admin toggle: unattended/system paths (player == null) opting into place-anyway see no regions,
        // so no cell is counted as protected (matches placeBlocks, which places them normally).
        boolean wgOn = anon.def9a2a4.blockships.integration.WorldGuardHook.get().mightRestrict(wheelLocation.getWorld())
            && !(player == null && anon.def9a2a4.blockships.integration.WorldGuardHook.get().systemPathPlacesInRegions());

        for (ShipModel.ModelPart part : model.parts) {
            // Extract position from transformation matrix
            Vector3f pos = new Vector3f();
            part.local.getTranslation(pos);

            // Rotate position by delta
            Vector3f rotatedPos = rotatePosition(pos, rotationDelta);

            // Round to nearest integer to avoid floating-point precision errors
            Location blockLoc = wheelLocation.clone().add(
                Math.round(rotatedPos.x),
                Math.round(rotatedPos.y),
                Math.round(rotatedPos.z)
            );

            // A cell in a protected region takes precedence over terrain classification: on force it
            // drops as items rather than being placed or destroyed.
            if (wgOn && anon.def9a2a4.blockships.integration.WorldGuardHook.get().isBuildDenied(blockLoc, player)) {
                protectedCount++;
                continue;
            }

            Block block = blockLoc.getBlock();
            Material type = block.getType();

            // Check if block location is replaceable (air or similar)
            if (!type.isAir() && type != Material.WATER && type != Material.LAVA) {
                if (FragileBlocks.isFragile(type)) {
                    fragile++;
                } else {
                    hard++;
                }
            }
        }

        return new PlacementConflicts(fragile, hard, protectedCount);
    }

    /**
     * Places blocks from a ShipModel into the world with rotation support.
     *
     * @param wheelLocation The center location to place blocks
     * @param model The ship model containing block data
     * @param currentShipYaw The ship's current yaw rotation
     * @return true if placement succeeded, false otherwise
     */
    public static boolean placeBlocks(Location wheelLocation, ShipModel model, float currentShipYaw) {
        return placeBlocks(wheelLocation, model, currentShipYaw, false, null, false);
    }

    public static boolean placeBlocks(Location wheelLocation, ShipModel model, float currentShipYaw, boolean force) {
        return placeBlocks(wheelLocation, model, currentShipYaw, force, null, false);
    }

    /**
     * Places blocks from a ShipModel into the world with rotation support.
     *
     * @param wheelLocation The center location to place blocks
     * @param model The ship model containing block data
     * @param currentShipYaw The ship's current yaw rotation
     * @param force If true, destroys fragile blocks (grass, flowers, etc.) that are in the way.
     *              Non-fragile conflicting blocks will cause the ship block to be skipped.
     * @param player The acting player (nullable — crash/system paths), used for WorldGuard checks.
     * @param anchorProtected Decided once by the caller: if true, the wheel-anchor cell is inside a
     *              protected region, so its head is SKIPPED here (the caller drops the wheel item and
     *              deregisters instead). Passing this in — rather than re-querying — keeps the skip and
     *              the caller's deregister decision in perfect agreement.
     * @return true if placement succeeded, false otherwise
     */
    public static boolean placeBlocks(Location wheelLocation, ShipModel model, float currentShipYaw, boolean force,
                                      org.bukkit.entity.Player player, boolean anchorProtected) {
        // Only the non-force path needs the conflict scan (to abort on any obstruction). Under force the
        // result is unused, so skip this full O(n) scan — and its per-cell WorldGuard pass — entirely.
        if (!force) {
            PlacementConflicts conflicts = validatePlacementArea(wheelLocation, model, currentShipYaw, player);
            if (!conflicts.isClear()) {
                return false;
            }
        }

        // O(1) gate: only pay per-cell WorldGuard queries in worlds that actually have regions.
        // Admin toggle: unattended/system paths (player == null) that opt into place-anyway skip the drop
        // routing entirely, so protected cells are placed normally (pre-integration wreck behavior).
        boolean wgOn = anon.def9a2a4.blockships.integration.WorldGuardHook.get().mightRestrict(wheelLocation.getWorld())
            && !(player == null && anon.def9a2a4.blockships.integration.WorldGuardHook.get().systemPathPlacesInRegions());

        // Calculate rotation delta from assembly orientation
        float rotationDelta = currentShipYaw - model.assemblyYaw;
        while (rotationDelta < 0) rotationDelta += 360;
        while (rotationDelta >= 360) rotationDelta -= 360;

        for (ShipModel.ModelPart part : model.parts) {
            // Extract position from transformation matrix
            Vector3f pos = new Vector3f();
            part.local.getTranslation(pos);

            // Rotate position by delta
            Vector3f rotatedPos = rotatePosition(pos, rotationDelta);

            // Round to nearest integer to avoid floating-point precision errors
            // (e.g., cos(90 deg) ~ 6.12e-17 instead of exactly 0 can cause off-by-one block placement)
            Location blockLoc = wheelLocation.clone().add(
                Math.round(rotatedPos.x),
                Math.round(rotatedPos.y),
                Math.round(rotatedPos.z)
            );
            Block block = blockLoc.getBlock();
            Material existingType = block.getType();

            try {
            // WorldGuard: the wheel anchor is handled by the caller (drop wheel item + deregister),
            // so skip placing its head here when protected. Must come first so the wheel is never
            // routed through dropPartAsItems (which would drop a plain head) or destroyed as terrain.
            if (isWheelAnchor(blockLoc, wheelLocation)) {
                if (anchorProtected) continue;   // skip placement, no drop — caller drops the wheel item
                // else fall through and place the wheel head normally
            } else if (force && wgOn
                    && anon.def9a2a4.blockships.integration.WorldGuardHook.get().isBuildDenied(blockLoc, player)) {
                // Non-anchor cell in a protected region: drop the block (and its contents) as items
                // instead of writing it into the region, then leave the existing terrain untouched.
                dropPartAsItems(part, blockLoc);
                continue;
            }

            // Handle conflicts in force mode
            if (!existingType.isAir() && existingType != Material.WATER && existingType != Material.LAVA) {
                if (force && FragileBlocks.isFragile(existingType)) {
                    // Destroy fragile block (no drops)
                    block.setType(Material.AIR, false);
                } else if (force) {
                    // Hard conflict in force mode - skip this ship block
                    continue;
                }
                // In non-force mode, we already validated so this shouldn't happen
            }

            // Place the block - prefer stored blockdata string if available (preserves all properties)
            // Also rotate block properties (stair facing, chest facing, etc.)
            if (part.rawYaml.containsKey("blockdata")) {
                String blockDataString = (String) part.rawYaml.get("blockdata");
                try {
                    BlockData originalData = org.bukkit.Bukkit.createBlockData(blockDataString);
                    BlockData rotatedData = rotateBlockData(originalData, rotationDelta);
                    block.setBlockData(rotatedData, false);  // false = don't apply physics immediately
                } catch (IllegalArgumentException e) {
                    // Fallback to part.block if string parse fails
                    BlockData rotatedData = rotateBlockData(part.block, rotationDelta);
                    block.setBlockData(rotatedData, false);
                }
            } else {
                BlockData rotatedData = rotateBlockData(part.block, rotationDelta);
                block.setBlockData(rotatedData, false);
            }

            // Restore special metadata for player heads and banners
            // Note: BlockData rotation is already handled above
            if (part.rawYaml.containsKey("skull_profile")) {
                // Restore player head texture
                String profileData = (String) part.rawYaml.get("skull_profile");
                com.destroystokyo.paper.profile.PlayerProfile profile = deserializeProfile(profileData);

                if (block.getState() instanceof org.bukkit.block.Skull && profile != null) {
                    org.bukkit.block.Skull skull = (org.bukkit.block.Skull) block.getState();
                    skull.setPlayerProfile(profile);
                    skull.update();
                }
            }

            if (part.rawYaml.containsKey("banner_patterns")) {
                // Restore banner patterns
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> patternList = (java.util.List<Map<String, Object>>) part.rawYaml.get("banner_patterns");

                if (block.getState() instanceof org.bukkit.block.Banner && patternList != null) {
                    org.bukkit.block.Banner banner = (org.bukkit.block.Banner) block.getState();
                    java.util.List<org.bukkit.block.banner.Pattern> patterns = new java.util.ArrayList<>();

                    for (Map<String, Object> patternMap : patternList) {
                        String colorName = (String) patternMap.get("color");
                        String patternName = (String) patternMap.get("pattern");

                        org.bukkit.DyeColor color = org.bukkit.DyeColor.valueOf(colorName);
                        org.bukkit.block.banner.PatternType patternType =
                            Registry.BANNER_PATTERN.get(NamespacedKey.minecraft(patternName.toLowerCase()));

                        if (patternType != null) {
                            patterns.add(new org.bukkit.block.banner.Pattern(color, patternType));
                        }
                    }

                    banner.setPatterns(patterns);
                    banner.update();
                }
            }

            // Restore engine PDC tag on blast furnaces
            if (Boolean.TRUE.equals(part.rawYaml.get("is_engine"))) {
                org.bukkit.block.BlockState state = block.getState();
                if (state instanceof org.bukkit.block.TileState tileState) {
                    BlockShipsPlugin bsPlugin = (BlockShipsPlugin) org.bukkit.Bukkit.getPluginManager().getPlugin("BlockShips");
                    if (bsPlugin != null) {
                        NamespacedKey engineKey = new NamespacedKey(bsPlugin, "custom_item_id");
                        tileState.getPersistentDataContainer().set(engineKey,
                            org.bukkit.persistence.PersistentDataType.STRING, "ship_engine");
                        tileState.update();
                    }
                }
            }

            // Restore container inventories
            // NOTE: Must get a fresh BlockState AFTER setBlockData, and set inventory contents
            // on the snapshot BEFORE calling update(), otherwise the inventory is cleared.
            if (part.rawYaml.containsKey("container_items") && block.getState() instanceof org.bukkit.block.Container) {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> itemsData =
                    (java.util.List<Map<String, Object>>) part.rawYaml.get("container_items");

                org.bukkit.block.Container container = (org.bukkit.block.Container) block.getState();
                java.util.List<org.bukkit.inventory.ItemStack> overflow = new java.util.ArrayList<>();
                org.bukkit.inventory.ItemStack[] items = deserializeInventory(itemsData, container.getSnapshotInventory().getSize(), overflow);

                // Set items on the snapshot's inventory, then update to persist
                container.getSnapshotInventory().setContents(items);
                container.update();
                // Drop any overflow (virtual GUI larger than the real block, e.g. a furnace) so it's not lost
                for (org.bukkit.inventory.ItemStack extra : overflow) {
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), extra);
                }
            }

            // Restore TileStateInventoryHolder blocks (shelves, chiseled bookshelves)
            // These are NOT Containers, so need separate restoration
            if (part.rawYaml.containsKey("container_items")
                    && block.getState() instanceof io.papermc.paper.block.TileStateInventoryHolder tileInv) {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> itemsData =
                    (java.util.List<Map<String, Object>>) part.rawYaml.get("container_items");

                java.util.List<org.bukkit.inventory.ItemStack> overflow = new java.util.ArrayList<>();
                org.bukkit.inventory.ItemStack[] items = deserializeInventory(itemsData, tileInv.getSnapshotInventory().getSize(), overflow);
                tileInv.getSnapshotInventory().setContents(items);
                tileInv.update();
                for (org.bukkit.inventory.ItemStack extra : overflow) {
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), extra);
                }
            }

            // Restore sign text
            if (part.rawYaml.containsKey("sign_data") && block.getState() instanceof org.bukkit.block.Sign sign) {
                @SuppressWarnings("unchecked")
                Map<String, Object> signData = (Map<String, Object>) part.rawYaml.get("sign_data");
                for (org.bukkit.block.sign.Side side : org.bukkit.block.sign.Side.values()) {
                    org.bukkit.block.sign.SignSide signSide = sign.getSide(side);
                    String key = side.name().toLowerCase();
                    @SuppressWarnings("unchecked")
                    java.util.List<String> lines = (java.util.List<String>) signData.get(key + "_lines");
                    if (lines != null) {
                        for (int i = 0; i < lines.size() && i < 4; i++) {
                            signSide.line(i, net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
                                .gson().deserialize(lines.get(i)));
                        }
                    }
                    String color = (String) signData.get(key + "_color");
                    if (color != null) signSide.setColor(org.bukkit.DyeColor.valueOf(color));
                    Boolean glowing = (Boolean) signData.get(key + "_glowing");
                    if (glowing != null) signSide.setGlowingText(glowing);
                }
                Boolean waxed = (Boolean) signData.get("waxed");
                if (waxed != null) sign.setWaxed(waxed);
                sign.update();
            }

            // Restore a Nameable block's custom name (containers, banners) captured at scan. Separate generic
            // pass so it fires even for a named block with no items/patterns. Safe double-update: getState()
            // reads the just-written world state, so setting only the name preserves items/patterns.
            if (part.rawYaml.containsKey("custom_name")) {
                org.bukkit.block.BlockState nameState = block.getState();
                if (nameState instanceof org.bukkit.Nameable n) {
                    n.customName(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
                        .gson().deserialize(String.valueOf(part.rawYaml.get("custom_name"))));
                    nameState.update();
                }
            }
            } catch (Exception e) {
                // A block is always placed (setBlockData above) before any throwing metadata restore, so a
                // bad banner/sign/container value only skips this one block's decoration - the rest of the
                // ship still places and the ship still fully disassembles + deregisters.
                org.bukkit.Bukkit.getLogger().warning("[BlockShips] placeBlocks: failed to restore block at "
                    + blockLoc + ", skipping its metadata: " + e.getMessage());
            }
        }

        return true;
    }

    /** True if this cell is the ship's wheel anchor (local translation (0,0,0) → equals the wheel location). */
    private static boolean isWheelAnchor(Location blockLoc, Location wheelLocation) {
        return blockLoc.getBlockX() == wheelLocation.getBlockX()
            && blockLoc.getBlockY() == wheelLocation.getBlockY()
            && blockLoc.getBlockZ() == wheelLocation.getBlockZ();
    }

    /**
     * Drops a ship block (and its stored container/engine-fuel contents) as items instead of placing it,
     * used for cells inside a WorldGuard-protected region during a forced disassembly. Preserves custom-item
     * identity: engines drop as the ship engine item; vanilla blocks drop as their item form (wall-mounted
     * variants remapped to their floor item). The wheel anchor is never routed here — the caller drops it.
     */
    private static void dropPartAsItems(ShipModel.ModelPart part, Location blockLoc) {
        org.bukkit.World world = blockLoc.getWorld();
        if (world == null) return;
        Location drop = blockLoc.clone().add(0.5, 0.5, 0.5);

        // 1) Stored container / engine-fuel contents (synced into the model before placement, so current).
        if (part.rawYaml.containsKey("container_items")) {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> itemsData =
                (java.util.List<Map<String, Object>>) part.rawYaml.get("container_items");
            if (itemsData != null) {
                for (Map<String, Object> itemData : itemsData) {
                    byte[] serialized = (byte[]) itemData.get("item");
                    if (serialized == null) continue;
                    try {
                        org.bukkit.inventory.ItemStack stack = org.bukkit.inventory.ItemStack.deserializeBytes(serialized);
                        if (stack != null) world.dropItemNaturally(drop, stack);
                    } catch (Exception e) {
                        org.bukkit.Bukkit.getLogger().warning("[BlockShips] dropPartAsItems: failed to deserialize a "
                            + "container item, skipping it: " + e.getMessage());
                    }
                }
            }
        }

        // 2) The block itself, preserving custom-item identity where it has one.
        BlockShipsPlugin bsPlugin = (BlockShipsPlugin) org.bukkit.Bukkit.getPluginManager().getPlugin("BlockShips");
        org.bukkit.inventory.ItemStack mainItem = null;

        if (Boolean.TRUE.equals(part.rawYaml.get("is_engine"))
                && bsPlugin != null && bsPlugin.getDisplayShip() != null
                && bsPlugin.getDisplayShip().getItemFactory() != null) {
            mainItem = bsPlugin.getDisplayShip().getItemFactory().createItem("ship_engine", "_DEFAULT", null);
        }

        if (mainItem == null) {
            // Vanilla block → its item form. Wall-mounted variants have no item; remap to the floor form.
            Material m = part.block.getMaterial();
            if (!m.isItem()) {
                String name = m.name();
                String remapped = name;
                if (name.contains("_WALL_HEAD")) remapped = name.replace("_WALL_HEAD", "_HEAD");
                else if (name.contains("_WALL_SKULL")) remapped = name.replace("_WALL_SKULL", "_SKULL");
                else if (name.contains("_WALL_BANNER")) remapped = name.replace("_WALL_BANNER", "_BANNER");
                else if (name.contains("_WALL_SIGN")) remapped = name.replace("_WALL_SIGN", "_SIGN");
                else if (name.contains("WALL_TORCH")) remapped = name.replace("WALL_TORCH", "TORCH");
                else if (name.equals("REDSTONE_WIRE")) remapped = "REDSTONE";
                else if (name.equals("TRIPWIRE")) remapped = "STRING";
                try {
                    m = Material.valueOf(remapped);
                } catch (IllegalArgumentException ignored) { /* fall through to isItem check */ }
            }
            if (m.isItem()) {
                mainItem = new org.bukkit.inventory.ItemStack(m);
            } else {
                org.bukkit.Bukkit.getLogger().warning("[BlockShips] dropPartAsItems: no item form for "
                    + part.block.getMaterial() + " in a protected region; block not dropped.");
            }
        }

        if (mainItem != null) world.dropItemNaturally(drop, mainItem);
    }

    /**
     * Removes blocks that were part of a ship structure.
     * Uses two-pass removal to prevent attached blocks (banners, signs, etc.) from dropping.
     * Water flows into the freed space because setType(AIR, true) triggers block updates
     * (may be costly for very large ships).
     *
     * <p>MUST stay synchronous with the scan that produced {@code model}: scanStructure serializes
     * container contents but leaves the world containers full, and Pass 0 below empties them just
     * before removal. That is only safe because no server tick (hence no hopper transfer) occurs
     * between the scan and this call. Inserting a {@code runTaskLater} between them would turn this
     * into an item-duplication bug.
     *
     * @param wheelLocation The center location of the structure
     * @param model The ship model containing block positions
     */
    public static void removeBlocks(Location wheelLocation, ShipModel model) {
        // Two-pass removal: attachables first, then solid blocks
        // This prevents banners/signs from dropping when their support is removed
        List<Location> attachableBlocks = new ArrayList<>();
        List<Location> solidBlocks = new ArrayList<>();

        for (ShipModel.ModelPart part : model.parts) {
            // Extract position from transformation matrix
            Vector3f pos = new Vector3f();
            part.local.getTranslation(pos);

            Location blockLoc = wheelLocation.clone().add(pos.x, pos.y, pos.z);
            Block block = blockLoc.getBlock();

            // Categorize blocks
            if (isAttachable(block.getType())) {
                attachableBlocks.add(blockLoc);
            } else {
                solidBlocks.add(blockLoc);
            }
        }

        // Pass 0: empty container snapshots so the setType(AIR) passes below can't spill their
        // contents. The contents were already serialized into the model during scanStructure.
        // Only solid blocks can be containers (no inventory-holding block is attachable). Each
        // clear is guarded so a failure on one block can't leave a half-cleared / half-removed ship.
        for (Location loc : solidBlocks) {
            try {
                org.bukkit.block.BlockState st = loc.getBlock().getState();
                if (st instanceof io.papermc.paper.block.TileStateInventoryHolder tsih) {
                    tsih.getSnapshotInventory().clear();
                    tsih.update();  // write the emptied state so setType(AIR) can't drop items
                }
            } catch (Exception e) {
                // Static context: no plugin field here. Use the always-available server logger.
                // Worst case, this one container spills its items on setType - dropped, not deleted.
                org.bukkit.Bukkit.getLogger().warning("[BlockShips] removeBlocks: failed to clear "
                    + "container at " + loc + " before removal: " + e.getMessage());
            }
        }

        // Pass 1: Remove attachables first (they depend on solid blocks)
        for (Location loc : attachableBlocks) {
            loc.getBlock().setType(Material.AIR, true);
        }

        // Pass 2: Remove solid blocks
        for (Location loc : solidBlocks) {
            loc.getBlock().setType(Material.AIR, true);
        }
    }

    /**
     * Creates a StorageConfig for a container block.
     * First checks blocks.yml config, then falls back to hardcoded defaults.
     */
    private static ShipModel.StorageConfig createStorageConfig(Block block) {
        Material type = block.getType();

        // First check blocks.yml config
        BlockProperties props = BlockConfigManager.getInstance().getProperties(type);
        if (props.getStorage() != null) {
            return props.getStorage();
        }

        // Fallback to hardcoded defaults for blocks not in config
        ShipModel.StorageType storageType;
        String name;

        switch (type) {
            case CHEST:
            case TRAPPED_CHEST:
                storageType = ShipModel.StorageType.CHEST;
                name = "Ship Chest";
                break;
            case BARREL:
                storageType = ShipModel.StorageType.CHEST;
                name = "Ship Barrel";
                break;
            case FURNACE:
            case BLAST_FURNACE:
            case SMOKER:
                // Open a real 3-slot furnace GUI in flight (exact match to the block's 3 slots -> no overflow
                // on disassembly). Smoker/blast furnace render as a furnace GUI (cosmetic; same 3 slots).
                // Blast-furnace engines also hit this arm, but their storage inventory is inert at runtime
                // (they use EngineMenuGUI), so it's harmless. Applies to newly assembled ships only; existing
                // ships keep their persisted CHEST type (the disassembly overflow-drop keeps that safe).
                storageType = ShipModel.StorageType.FURNACE;
                name = "Ship Furnace";
                break;
            case HOPPER:
                storageType = ShipModel.StorageType.HOPPER;
                name = "Ship Hopper";
                break;
            case DROPPER:
            case DISPENSER:
                storageType = ShipModel.StorageType.DROPPER;
                name = "Ship Dropper";
                break;
            default:
                return null;  // Not a recognized storage type
        }

        return new ShipModel.StorageConfig(storageType, name);
    }

    /**
     * Serializes an inventory to a map that can be stored in YAML.
     * Returns a list of maps, each containing slot index and serialized item.
     */
    private static java.util.List<Map<String, Object>> serializeInventory(org.bukkit.inventory.Inventory inv) {
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();

        for (int slot = 0; slot < inv.getSize(); slot++) {
            org.bukkit.inventory.ItemStack item = inv.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("slot", slot);
                // Serialize ItemStack to base64 using Bukkit's serialization
                itemData.put("item", item.serializeAsBytes());
                items.add(itemData);
            }
        }

        return items;
    }

    /**
     * Deserializes an inventory from stored data.
     * Returns an array of ItemStacks that can be set to an inventory.
     */
    /**
     * Deserializes persisted container items into an array sized to the REAL block inventory.
     * Items whose stored slot is beyond the real inventory (e.g. a furnace that was shown in flight
     * as a larger virtual chest) are added to {@code overflowOut} so the caller can drop them to the
     * world instead of silently destroying them. Slot index is read via Number to tolerate a
     * Long/Double from a migrated/hand-edited model.
     */
    private static org.bukkit.inventory.ItemStack[] deserializeInventory(java.util.List<Map<String, Object>> itemsData, int inventorySize,
                                                                         java.util.List<org.bukkit.inventory.ItemStack> overflowOut) {
        org.bukkit.inventory.ItemStack[] items = new org.bukkit.inventory.ItemStack[inventorySize];

        if (itemsData != null) {
            for (Map<String, Object> itemData : itemsData) {
                int slot = ((Number) itemData.get("slot")).intValue();
                byte[] serialized = (byte[]) itemData.get("item");
                if (serialized == null || slot < 0) continue;

                try {
                    org.bukkit.inventory.ItemStack item = org.bukkit.inventory.ItemStack.deserializeBytes(serialized);
                    if (item == null) continue;
                    if (slot < inventorySize) {
                        items[slot] = item;
                    } else {
                        // Virtual GUI had more slots than the real block - don't destroy the overflow.
                        overflowOut.add(item);
                    }
                } catch (Exception e) {
                    org.bukkit.Bukkit.getLogger().warning("[BlockShips] Failed to deserialize container item at slot "
                        + slot + ", dropping it: " + e.getMessage() + ". Please report at "
                        + anon.def9a2a4.blockships.BlockShipsPlugin.ISSUES_URL);
                }
            }
        }

        return items;
    }

    /**
     * Serializes a PlayerProfile to Base64 string.
     */
    private static String serializeProfile(com.destroystokyo.paper.profile.PlayerProfile profile) {
        try {
            // Get the texture property from the profile
            java.util.Collection<com.destroystokyo.paper.profile.ProfileProperty> properties = profile.getProperties();
            for (com.destroystokyo.paper.profile.ProfileProperty prop : properties) {
                if ("textures".equals(prop.getName())) {
                    return prop.getValue();  // This is already Base64
                }
            }
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[BlockShips] Failed to serialize player head profile, head texture"
                + " will be lost: " + e.getMessage() + ". Please report at "
                + anon.def9a2a4.blockships.BlockShipsPlugin.ISSUES_URL);
        }
        return null;
    }

    /**
     * Deserializes a Base64 string back to a PlayerProfile.
     */
    public static com.destroystokyo.paper.profile.PlayerProfile deserializeProfile(String textureBase64) {
        if (textureBase64 == null || textureBase64.isEmpty()) {
            return null;
        }

        try {
            // Create a new profile with a random UUID
            com.destroystokyo.paper.profile.PlayerProfile profile =
                org.bukkit.Bukkit.createProfile(java.util.UUID.randomUUID(), null);

            // Add the texture property
            profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", textureBase64));

            return profile;
        } catch (Exception e) {
            org.bukkit.Bukkit.getLogger().warning("[BlockShips] Failed to deserialize player head profile, head texture"
                + " will be lost: " + e.getMessage() + ". Please report at "
                + anon.def9a2a4.blockships.BlockShipsPlugin.ISSUES_URL);
        }
        return null;
    }

    /**
     * Detects cannon patterns in the ship: dispensers with obsidian directly behind them.
     * A cannon fires in the direction the dispenser faces; obsidian must be on the opposite side.
     * Multiple dispensers can share the same obsidian block (they fire together).
     *
     * @param parts The list of ModelParts from scanning
     * @return List of detected CannonInfo
     */
    private static List<ShipModel.CannonInfo> detectCannons(List<ShipModel.ModelPart> parts) {
        List<ShipModel.CannonInfo> cannons = new ArrayList<>();

        // Build position -> block index map for fast lookup
        Map<String, Integer> posToIndex = new HashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            Vector3f pos = new Vector3f();
            parts.get(i).local.getTranslation(pos);
            String key = Math.round(pos.x) + "," + Math.round(pos.y) + "," + Math.round(pos.z);
            posToIndex.put(key, i);
        }

        // Find dispensers and check for obsidian behind them
        for (int i = 0; i < parts.size(); i++) {
            ShipModel.ModelPart part = parts.get(i);
            if (part.block.getMaterial() != Material.DISPENSER) continue;

            // Get dispenser facing direction from BlockData
            if (!(part.block instanceof org.bukkit.block.data.Directional)) continue;
            org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) part.block;
            BlockFace facing = directional.getFacing();

            // Calculate position behind dispenser (opposite of facing)
            BlockFace behind = facing.getOppositeFace();
            Vector3f dispenserPos = new Vector3f();
            part.local.getTranslation(dispenserPos);

            String behindKey = (Math.round(dispenserPos.x) + behind.getModX()) + "," +
                              (Math.round(dispenserPos.y) + behind.getModY()) + "," +
                              (Math.round(dispenserPos.z) + behind.getModZ());

            // Check if obsidian exists behind
            Integer obsidianIndex = posToIndex.get(behindKey);
            if (obsidianIndex == null) continue;

            ShipModel.ModelPart obsidianPart = parts.get(obsidianIndex);
            if (obsidianPart.block.getMaterial() != Material.OBSIDIAN) continue;

            // Calculate spawn position (dispenser face center, offset 0.6 blocks in facing direction)
            Vector3f spawnPos = new Vector3f(dispenserPos);
            spawnPos.add(facing.getModX() * 0.6f, facing.getModY() * 0.6f, facing.getModZ() * 0.6f);

            cannons.add(new ShipModel.CannonInfo(i, obsidianIndex, facing, spawnPos));
        }

        return cannons;
    }

    /**
     * Finds all entities leashed to a fence block via LeashHitch.
     * Returns a list of entity UUIDs that are leashed to a LeashHitch at the given location.
     *
     * @param fenceLoc The location of the fence block
     * @return List of UUID strings for leashed entities
     */
    private static List<String> findLeashedEntities(Location fenceLoc) {
        List<String> leashedUUIDs = new ArrayList<>();
        if (fenceLoc.getWorld() == null) {
            return leashedUUIDs;
        }

        // Search for entities within lead range (10 blocks is Minecraft's lead limit)
        // Use Paper's Leashable interface to support boats, mobs, and other leashable entities
        for (org.bukkit.entity.Entity entity : fenceLoc.getWorld().getNearbyEntities(fenceLoc, 10, 10, 10)) {
            if (entity instanceof io.papermc.paper.entity.Leashable) {
                io.papermc.paper.entity.Leashable leashable = (io.papermc.paper.entity.Leashable) entity;
                if (leashable.isLeashed()) {
                    org.bukkit.entity.Entity holder = leashable.getLeashHolder();
                    if (holder instanceof org.bukkit.entity.LeashHitch) {
                        // Check if the LeashHitch is at this fence block
                        Location hitchLoc = holder.getLocation().getBlock().getLocation();
                        if (hitchLoc.equals(fenceLoc.getBlock().getLocation())) {
                            leashedUUIDs.add(entity.getUniqueId().toString());
                        }
                    }
                }
            }
        }

        return leashedUUIDs;
    }
}
