package anon.def9a2a4.blockships.ship;

import anon.def9a2a4.blockships.ShipRegistry;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.joml.Vector3f;

import java.util.*;

/**
 * Global coordinator for ship-to-ship collision detection.
 * Runs once per tick before individual ship ticks, computing all ship-ship forces centrally.
 * Each ship reads its pre-computed force during its own detect() call.
 *
 * Uses axis-aligned binning for the narrow phase: colliders are projected onto the
 * ship-to-ship axis and dropped into fixed-width bins. Only colliders in matching/adjacent
 * bins across the two ships are checked for 3D overlap.
 */
public class ShipCollisionCoordinator extends BukkitRunnable {

    private static ShipCollisionCoordinator instance;

    private final JavaPlugin plugin;
    private final boolean enabled;
    private final int maxCollisionsPerPair;
    private static final float BIN_WIDTH = 2.0f;
    private static final int SOUND_COOLDOWN_TICKS = 15; // ~0.75s between collision sounds per pair
    private static final Particle.DustOptions COLLISION_DUST =
            new Particle.DustOptions(Color.fromRGB(180, 180, 180), 0.8f);

    // Force map: ship UUID -> accumulated ship-to-ship force for this tick
    private final Map<UUID, Vector3f> forceMap = new HashMap<>();
    // Pool of Vector3f to avoid allocating new ones each tick
    private final List<Vector3f> vectorPool = new ArrayList<>();
    private int vectorPoolIndex = 0;

    // Reusable arrays for binning (grown as needed, never shrunk)
    private float[] projectionsA = new float[0];
    private float[] projectionsB = new float[0];
    private int[] binCountsA = new int[0];
    private int[] binCountsB = new int[0];
    private int[] binOffsetsA = new int[0];
    private int[] binOffsetsB = new int[0];
    private int[] sortedA = new int[0];
    private int[] sortedB = new int[0];

    // Reusable list for grouping ships by world
    private final Map<World, List<ShipInstance>> shipsByWorld = new HashMap<>();

    // Per-pair sound cooldown: pair key -> remaining ticks
    private final Map<Long, Integer> soundCooldowns = new HashMap<>();

    private ShipCollisionCoordinator(JavaPlugin plugin, boolean enabled, int maxCollisionsPerPair) {
        this.plugin = plugin;
        this.enabled = enabled;
        this.maxCollisionsPerPair = maxCollisionsPerPair;
    }

    public static void init(JavaPlugin plugin, boolean enabled, int maxCollisionsPerPair) {
        if (instance != null) {
            instance.cancel();
        }
        instance = new ShipCollisionCoordinator(plugin, enabled, maxCollisionsPerPair);
        instance.runTaskTimer(plugin, 0L, 1L);
    }

    public static void shutdown() {
        if (instance != null) {
            instance.cancel();
            instance = null;
        }
    }

    public static ShipCollisionCoordinator getInstance() {
        return instance;
    }

    /**
     * Returns the accumulated ship-to-ship collision force for a given ship, or zero.
     */
    public Vector3f getShipCollisionForce(UUID shipId) {
        Vector3f force = forceMap.get(shipId);
        return force != null ? new Vector3f(force) : new Vector3f(0, 0, 0);
    }

    @Override
    public void run() {
        if (!enabled) return;

        // Reset force map and vector pool
        forceMap.clear();
        vectorPoolIndex = 0;

        // Tick down sound cooldowns
        soundCooldowns.entrySet().removeIf(e -> { int v = e.getValue() - 1; e.setValue(v); return v <= 0; });

        // Group ships by world (reuse lists to avoid allocation)
        for (List<ShipInstance> list : shipsByWorld.values()) {
            list.clear();
        }
        for (ShipInstance ship : ShipRegistry.getAllShips()) {
            if (ship.vehicle == null || ship.vehicle.isDead()) continue;
            World world = ship.vehicle.getWorld();
            shipsByWorld.computeIfAbsent(world, w -> new ArrayList<>()).add(ship);
        }

        // Process each world independently
        for (List<ShipInstance> ships : shipsByWorld.values()) {
            int n = ships.size();
            if (n < 2) continue;

            // Check all unique pairs
            for (int i = 0; i < n; i++) {
                ShipInstance shipA = ships.get(i);
                for (int j = i + 1; j < n; j++) {
                    ShipInstance shipB = ships.get(j);
                    processShipPair(shipA, shipB);
                }
            }
        }
    }

    private void processShipPair(ShipInstance shipA, ShipInstance shipB) {
        // Skip if neither ship is moving or has previous collision force
        boolean aActive = Math.abs(shipA.physics.currentSpeed) > 0.001f
                || Math.abs(shipA.physics.currentRotationVelocity) > 0.01f
                || shipA.physics.collisionForce.lengthSquared() > 0.001f;
        boolean bActive = Math.abs(shipB.physics.currentSpeed) > 0.001f
                || Math.abs(shipB.physics.currentRotationVelocity) > 0.01f
                || shipB.physics.collisionForce.lengthSquared() > 0.001f;
        if (!aActive && !bActive) return;

        // Broad phase: check if collision radii overlap
        Location locA = shipA.vehicle.getLocation();
        Location locB = shipB.vehicle.getLocation();
        float radiusSum = shipA.collisionRadius + shipB.collisionRadius;
        if (radiusSum <= 0) return; // Colliders not yet initialized

        double dx = locA.getX() - locB.getX();
        double dy = locA.getY() - locB.getY();
        double dz = locA.getZ() - locB.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > (double) radiusSum * radiusSum) return;

        // Narrow phase: axis-binned collision detection
        checkPairBinned(shipA, shipB, locA, locB);
    }

    private void checkPairBinned(ShipInstance shipA, ShipInstance shipB,
                                  Location locA, Location locB) {
        // Engine-agnostic collider boxes (M3): native shulker boxes OR the delegated Mechanism read-API.
        // Materialize the per-ship snapshots once per pair-check (block-index-keyed; list position arbitrary).
        List<BoundingBox> boxesA = shipA.colliderBoxes();
        List<BoundingBox> boxesB = shipB.colliderBoxes();
        int nA = boxesA.size();
        int nB = boxesB.size();
        if (nA == 0 || nB == 0) return;

        // Compute sweep axis: unit vector from A to B
        float axisX = (float) (locB.getX() - locA.getX());
        float axisY = (float) (locB.getY() - locA.getY());
        float axisZ = (float) (locB.getZ() - locA.getZ());
        float axisLen = (float) Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
        if (axisLen < 0.001f) {
            // Ships at same position - fallback to X axis
            axisX = 1; axisY = 0; axisZ = 0; axisLen = 1;
        }
        float invLen = 1.0f / axisLen;
        axisX *= invLen;
        axisY *= invLen;
        axisZ *= invLen;

        // Project all colliders onto the axis
        ensureArraySize(nA, nB);

        // Use midpoint as projection origin
        float originX = (float) ((locA.getX() + locB.getX()) * 0.5);
        float originY = (float) ((locA.getY() + locB.getY()) * 0.5);
        float originZ = (float) ((locA.getZ() + locB.getZ()) * 0.5);

        float globalMin = Float.MAX_VALUE;
        float globalMax = -Float.MAX_VALUE;

        // Project ship A colliders
        for (int i = 0; i < nA; i++) {
            BoundingBox bb = boxesA.get(i);
            float cx = (float) ((bb.getMinX() + bb.getMaxX()) * 0.5) - originX;
            float cy = (float) ((bb.getMinY() + bb.getMaxY()) * 0.5) - originY;
            float cz = (float) ((bb.getMinZ() + bb.getMaxZ()) * 0.5) - originZ;
            float proj = cx * axisX + cy * axisY + cz * axisZ;
            projectionsA[i] = proj;
            if (proj < globalMin) globalMin = proj;
            if (proj > globalMax) globalMax = proj;
        }

        // Project ship B colliders
        for (int i = 0; i < nB; i++) {
            BoundingBox bb = boxesB.get(i);
            float cx = (float) ((bb.getMinX() + bb.getMaxX()) * 0.5) - originX;
            float cy = (float) ((bb.getMinY() + bb.getMaxY()) * 0.5) - originY;
            float cz = (float) ((bb.getMinZ() + bb.getMaxZ()) * 0.5) - originZ;
            float proj = cx * axisX + cy * axisY + cz * axisZ;
            projectionsB[i] = proj;
            if (proj < globalMin) globalMin = proj;
            if (proj > globalMax) globalMax = proj;
        }

        // Compute bin count and ensure offset arrays are large enough
        int numBins = (int) Math.ceil((globalMax - globalMin) / BIN_WIDTH) + 1;
        if (numBins <= 0) numBins = 1;
        if (binCountsA.length < numBins) {
            binCountsA = new int[numBins];
            binCountsB = new int[numBins];
            binOffsetsA = new int[numBins];
            binOffsetsB = new int[numBins];
        }

        // Clear bin counts
        for (int i = 0; i < numBins; i++) {
            binCountsA[i] = 0;
            binCountsB[i] = 0;
        }

        // Count colliders per bin for ship A
        for (int i = 0; i < nA; i++) {
            int bin = (int) ((projectionsA[i] - globalMin) / BIN_WIDTH);
            if (bin >= numBins) bin = numBins - 1;
            if (bin < 0) bin = 0;
            binCountsA[bin]++;
        }

        // Count colliders per bin for ship B
        for (int i = 0; i < nB; i++) {
            int bin = (int) ((projectionsB[i] - globalMin) / BIN_WIDTH);
            if (bin >= numBins) bin = numBins - 1;
            if (bin < 0) bin = 0;
            binCountsB[bin]++;
        }

        // Prefix sum to get offsets for ship A
        binOffsetsA[0] = 0;
        for (int i = 1; i < numBins; i++) {
            binOffsetsA[i] = binOffsetsA[i - 1] + binCountsA[i - 1];
        }

        // Prefix sum to get offsets for ship B
        binOffsetsB[0] = 0;
        for (int i = 1; i < numBins; i++) {
            binOffsetsB[i] = binOffsetsB[i - 1] + binCountsB[i - 1];
        }

        // Place collider indices into sorted arrays (counting sort pass 2)
        // We need a copy of offsets as cursors since we increment them
        // Reuse binCounts as cursors (reset to offset values)
        for (int i = 0; i < numBins; i++) {
            binCountsA[i] = binOffsetsA[i];
        }
        for (int i = 0; i < nA; i++) {
            int bin = (int) ((projectionsA[i] - globalMin) / BIN_WIDTH);
            if (bin >= numBins) bin = numBins - 1;
            if (bin < 0) bin = 0;
            sortedA[binCountsA[bin]++] = i;
        }

        for (int i = 0; i < numBins; i++) {
            binCountsB[i] = binOffsetsB[i];
        }
        for (int i = 0; i < nB; i++) {
            int bin = (int) ((projectionsB[i] - globalMin) / BIN_WIDTH);
            if (bin >= numBins) bin = numBins - 1;
            if (bin < 0) bin = 0;
            sortedB[binCountsB[bin]++] = i;
        }

        // Restore bin counts from offsets for iteration
        // binCountsA[bin] is now the end offset; we need start and count
        // Recalculate counts from offsets
        for (int i = 0; i < numBins - 1; i++) {
            binCountsA[i] = binOffsetsA[i + 1] - binOffsetsA[i];
            binCountsB[i] = binOffsetsB[i + 1] - binOffsetsB[i];
        }
        binCountsA[numBins - 1] = nA - binOffsetsA[numBins - 1];
        binCountsB[numBins - 1] = nB - binOffsetsB[numBins - 1];

        // Check A-bins against matching/adjacent B-bins
        int collisionCount = 0;
        float totalPenetration = 0;
        double contactX = 0, contactY = 0, contactZ = 0;

        outerLoop:
        for (int binA = 0; binA < numBins; binA++) {
            if (binCountsA[binA] == 0) continue;

            int startA = binOffsetsA[binA];
            int endA = startA + binCountsA[binA];

            // Check against B bins [binA-1, binA, binA+1]
            int bBinStart = Math.max(0, binA - 1);
            int bBinEnd = Math.min(numBins - 1, binA + 1);

            for (int binB = bBinStart; binB <= bBinEnd; binB++) {
                if (binCountsB[binB] == 0) continue;

                int startB = binOffsetsB[binB];
                int endB = startB + binCountsB[binB];

                for (int ia = startA; ia < endA; ia++) {
                    BoundingBox boxA = boxesA.get(sortedA[ia]);

                    for (int ib = startB; ib < endB; ib++) {
                        BoundingBox boxB = boxesB.get(sortedB[ib]);

                        if (boxA.overlaps(boxB)) {
                            // Compute penetration depth (min overlap across all axes)
                            float pen = minPenetrationDepth(boxA, boxB);
                            if (pen > 0.001f) {
                                totalPenetration += pen;
                                collisionCount++;
                                // Record first overlap midpoint as contact location
                                if (collisionCount == 1) {
                                    contactX = (boxA.getCenterX() + boxB.getCenterX()) * 0.5;
                                    contactY = (boxA.getCenterY() + boxB.getCenterY()) * 0.5;
                                    contactZ = (boxA.getCenterZ() + boxB.getCenterZ()) * 0.5;
                                }
                                if (collisionCount >= maxCollisionsPerPair) {
                                    break outerLoop;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (collisionCount == 0) return;

        // Compute force along ship-to-ship axis, split by mass ratio
        float massA = Math.max(shipA.config.shipMass, 1.0f);
        float massB = Math.max(shipB.config.shipMass, 1.0f);
        float totalMass = massA + massB;

        // Force on A: push away from B (along axis from B to A = negative axis direction)
        // Force on B: push away from A (along axis from A to B = positive axis direction)
        float forceOnAMag = totalPenetration * (massB / totalMass);
        float forceOnBMag = totalPenetration * (massA / totalMass);

        // A gets pushed in -axis direction (away from B)
        Vector3f forceA = getPooledVector();
        forceA.set(-axisX * forceOnAMag, -axisY * forceOnAMag, -axisZ * forceOnAMag);

        // B gets pushed in +axis direction (away from A)
        Vector3f forceB = getPooledVector();
        forceB.set(axisX * forceOnBMag, axisY * forceOnBMag, axisZ * forceOnBMag);

        // Accumulate into force map
        forceMap.merge(shipA.id, forceA, (existing, incoming) -> existing.add(incoming));
        forceMap.merge(shipB.id, forceB, (existing, incoming) -> existing.add(incoming));

        // Collision effects (particles + sound)
        spawnCollisionEffects(shipA, shipB, contactX, contactY, contactZ, totalPenetration);
    }

    /**
     * Spawn particles and play sound at the collision contact point.
     */
    private void spawnCollisionEffects(ShipInstance shipA, ShipInstance shipB,
                                        double cx, double cy, double cz,
                                        float totalPenetration) {
        World world = shipA.vehicle.getWorld();
        Location contact = new Location(world, cx, cy, cz);

        // Grey dust particles scaled by penetration (5-12 particles)
        int count = Math.min(5 + (int) (totalPenetration * 3), 12);
        world.spawnParticle(Particle.DUST, contact, count, 0.6, 0.6, 0.6, 0, COLLISION_DUST);

        // Sound with per-pair cooldown
        long pairKey = pairKey(shipA.id, shipB.id);
        if (!soundCooldowns.containsKey(pairKey)) {
            float volume = (float) plugin.getConfig().getDouble("sounds.ship-collision-volume", 0.15);
            world.playSound(contact, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 0.4f * volume, 1.2f);

            // Also play quiet sound for all seated players on both ships
            // so players far from the contact point still feel the bump
            float playerVolume = volume * 0.5f;
            playForSeatedPlayers(shipA, playerVolume);
            playForSeatedPlayers(shipB, playerVolume);

            soundCooldowns.put(pairKey, SOUND_COOLDOWN_TICKS);
        }
    }

    /**
     * Play collision sound at each seated player's location (only they hear it).
     */
    private static void playForSeatedPlayers(ShipInstance ship, float volume) {
        for (Shulker seat : ship.seatShulkers) {
            if (seat == null) continue;
            for (Entity passenger : seat.getPassengers()) {
                if (passenger instanceof Player player) {
                    player.playSound(player.getLocation(),
                            Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, volume, 1.2f);
                }
            }
        }
    }

    /**
     * Canonical pair key from two UUIDs (order-independent).
     */
    private static long pairKey(UUID a, UUID b) {
        int ha = a.hashCode();
        int hb = b.hashCode();
        long lo = Math.min(ha, hb);
        long hi = Math.max(ha, hb);
        return (hi << 32) | (lo & 0xFFFFFFFFL);
    }

    /**
     * Compute the minimum penetration depth across all three axes.
     */
    private static float minPenetrationDepth(BoundingBox a, BoundingBox b) {
        float xOverlap = (float) (Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX()));
        float yOverlap = (float) (Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY()));
        float zOverlap = (float) (Math.min(a.getMaxZ(), b.getMaxZ()) - Math.max(a.getMinZ(), b.getMinZ()));
        return Math.min(xOverlap, Math.min(yOverlap, zOverlap));
    }

    /**
     * Ensure reusable arrays are large enough for the given collider counts.
     */
    private void ensureArraySize(int nA, int nB) {
        if (projectionsA.length < nA) {
            projectionsA = new float[nA];
            sortedA = new int[nA];
        }
        if (projectionsB.length < nB) {
            projectionsB = new float[nB];
            sortedB = new int[nB];
        }
    }

    /**
     * Get a Vector3f from the pool, growing the pool if needed.
     */
    private Vector3f getPooledVector() {
        if (vectorPoolIndex >= vectorPool.size()) {
            vectorPool.add(new Vector3f());
        }
        Vector3f v = vectorPool.get(vectorPoolIndex++);
        v.set(0, 0, 0);
        return v;
    }
}
