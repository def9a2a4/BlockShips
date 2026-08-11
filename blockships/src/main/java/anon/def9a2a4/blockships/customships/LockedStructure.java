package anon.def9a2a4.blockships.customships;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A frozen snapshot of which cells belong to a ship, stored on its wheel.
 *
 * <p>A locked ship takes exactly these cells on every assemble instead of re-running the flood fill,
 * so a docked hull stops absorbing whatever a player piles against it. Removals are tolerated (the
 * ship comes back smaller); additions are impossible by construction.
 *
 * <h2>Why cells are stored as offsets, not indices</h2>
 * {@code BlockStructureScanner} iterates a {@code HashSet}, so part order is not reproducible between
 * scans. Anything index-based would silently bind to different blocks.
 *
 * <h2>Why the stored BlockData is advisory</h2>
 * Placing blocks NEXT to a ship rewrites the ship's own BlockData through ordinary neighbour updates
 * — fence/wall/pane connections, stair shapes, waterlogging, redstone power. Dropping cells whose
 * BlockData changed would therefore delete a large part of a hull the first time someone built a dock
 * beside it, which is precisely the scenario the lock exists to protect against. So:
 * <ul>
 *   <li>a cell that is present and non-air is <b>always kept</b>, with whatever BlockData it has now;</li>
 *   <li>the snapshot is compared only to <b>report</b> how many cells changed;</li>
 *   <li>and the comparison ignores the volatile states above, so "changed" means someone actually
 *       replaced or rotated the block.</li>
 * </ul>
 * Substituting a block inside a frozen cell cannot grow the ship, so tolerating it costs nothing.
 */
public final class LockedStructure {

    /**
     * BlockData states that change on their own — through neighbour updates, redstone, weather or
     * water — and so must not count as "the player changed this block".
     */
    private static final Set<String> VOLATILE_STATES = Set.of(
        "waterlogged", "shape", "north", "south", "east", "west", "up", "down",
        "powered", "lit", "snowy", "open", "age", "distance", "persistent",
        "note", "instrument", "level", "occupied", "in_wall", "signal_fire",
        "bites", "power", "triggered", "hanging", "attached", "disarmed",
        "conditional", "has_bottle_0", "has_bottle_1", "has_bottle_2", "extended"
    );

    /** Wheel-relative offsets, in the frame of {@link #facing}, one per cell. */
    private final long[] packed;
    /** Distinct normalized BlockData strings; a cell's palette index indexes this. */
    private final List<String> palette;
    /** The wheel facing the offsets were captured in — offsets rotate from here, never from the last write. */
    private final BlockFace facing;

    private LockedStructure(long[] packed, List<String> palette, BlockFace facing) {
        this.packed = packed;
        this.palette = palette;
        this.facing = facing;
    }

    public int size() { return packed.length; }

    public BlockFace facing() { return facing; }

    /** Freeze a cell list, relative to the wheel and in its current facing. */
    public static LockedStructure capture(Location wheelLocation, BlockFace facing,
                                          Collection<Location> cells) {
        int wx = wheelLocation.getBlockX(), wy = wheelLocation.getBlockY(), wz = wheelLocation.getBlockZ();
        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        long[] out = new long[cells.size()];
        int n = 0;
        for (Location cell : cells) {
            Block b = cell.getBlock();
            if (b.getType().isAir()) continue;
            String norm = normalize(b.getBlockData());
            int pi = paletteIndex.computeIfAbsent(norm, k -> paletteIndex.size());
            out[n++] = pack(cell.getBlockX() - wx, cell.getBlockY() - wy, cell.getBlockZ() - wz, pi);
        }
        long[] trimmed = new long[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return new LockedStructure(trimmed, new ArrayList<>(paletteIndex.keySet()), facing);
    }

    /** Outcome of resolving a locked set against the world. */
    public record Resolved(List<Location> cells, int missing, int changed) {}

    /**
     * Resolve to the cells that are actually present now, rotating the stored offsets from the frame
     * they were captured in into the wheel's current facing.
     *
     * <p>Always rotates from the ORIGINAL frame rather than re-deriving from the previous resolve, so
     * repeated dock/rotate cycles cannot accumulate rounding.
     */
    public Resolved resolve(Location wheelLocation, BlockFace currentFacing) {
        float yawDelta = BlockStructureScanner.blockFaceToYaw(currentFacing)
                       - BlockStructureScanner.blockFaceToYaw(facing);
        int wx = wheelLocation.getBlockX(), wy = wheelLocation.getBlockY(), wz = wheelLocation.getBlockZ();
        List<Location> cells = new ArrayList<>(packed.length);
        int missing = 0, changed = 0;
        for (long v : packed) {
            Vector3f rotated = BlockStructureScanner.rotatePosition(
                new Vector3f(unpackX(v), unpackY(v), unpackZ(v)), yawDelta);
            Location loc = new Location(wheelLocation.getWorld(),
                wx + Math.round(rotated.x), wy + Math.round(rotated.y), wz + Math.round(rotated.z));
            Block b = loc.getBlock();
            if (b.getType().isAir()) { missing++; continue; }
            cells.add(loc);
            int pi = unpackPalette(v);
            if (pi >= 0 && pi < palette.size() && !matchesSnapshot(palette.get(pi), b.getBlockData())) {
                changed++;
            }
        }
        return new Resolved(cells, missing, changed);
    }

    // ── serialization ────────────────────────────────────────────────────────
    // One base64 string + a palette list, NOT a nested int list: ship_wheels.yml is rewritten in full
    // and synchronously on every wheel mutation, and Bukkit's YAML dumps sequences in block style —
    // a 1000-cell ship as List<List<Integer>> would be ~5000 lines per wheel.

    public Map<String, Object> toMap() {
        ByteBuffer buf = ByteBuffer.allocate(packed.length * Long.BYTES);
        for (long v : packed) buf.putLong(v);
        Map<String, Object> map = new HashMap<>();
        map.put("facing", facing.name());
        map.put("cells", Base64.getEncoder().encodeToString(buf.array()));
        map.put("palette", palette);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static LockedStructure fromMap(Map<?, ?> map) {
        if (map == null) return null;
        Object cells = map.get("cells");
        if (!(cells instanceof String s) || s.isEmpty()) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length % Long.BYTES != 0) return null;
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long[] packed = new long[bytes.length / Long.BYTES];
        for (int i = 0; i < packed.length; i++) packed[i] = buf.getLong();

        List<String> palette = new ArrayList<>();
        Object rawPalette = map.get("palette");
        if (rawPalette instanceof List<?> l) for (Object o : l) palette.add(String.valueOf(o));

        BlockFace facing = BlockFace.NORTH;
        Object f = map.get("facing");
        if (f != null) {
            try {
                facing = BlockFace.valueOf(String.valueOf(f));
            } catch (IllegalArgumentException ignored) {
                // Corrupt facing: NORTH means offsets resolve unrotated, which is the identity case
                // for a wheel that never rotated. Better than discarding the whole lock.
            }
        }
        return new LockedStructure(packed, palette, facing);
    }

    // ── packing ──────────────────────────────────────────────────────────────
    // 16 signed bits per axis (±32767, far beyond any ship) + 16 bits of palette index.

    private static long pack(int x, int y, int z, int paletteIndex) {
        return ((x & 0xFFFFL) << 48) | ((y & 0xFFFFL) << 32)
             | ((z & 0xFFFFL) << 16) | (paletteIndex & 0xFFFFL);
    }

    private static int unpackX(long v) { return (short) (v >>> 48); }
    private static int unpackY(long v) { return (short) (v >>> 32); }
    private static int unpackZ(long v) { return (short) (v >>> 16); }
    private static int unpackPalette(long v) { return (int) (v & 0xFFFFL); }

    // ── BlockData normalization ──────────────────────────────────────────────

    /** {@code minecraft:oak_stairs[facing=north,shape=straight,waterlogged=false]} → drops the volatile states. */
    static String normalize(BlockData data) {
        String s = data.getAsString();
        int open = s.indexOf('[');
        if (open < 0) return s;
        String base = s.substring(0, open);
        String inner = s.substring(open + 1, s.length() - 1);
        StringBuilder kept = new StringBuilder();
        for (String pair : inner.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (VOLATILE_STATES.contains(pair.substring(0, eq))) continue;
            if (kept.length() > 0) kept.append(',');
            kept.append(pair);
        }
        return kept.length() == 0 ? base : base + "[" + kept + "]";
    }

    /**
     * Whether a live block still matches a stored snapshot, ignoring volatile states.
     *
     * <p>Uses {@link BlockData#matches}, which compares only the states the snapshot explicitly
     * names — so the states stripped by {@link #normalize} are genuinely not compared, rather than
     * compared against a default.
     */
    private static boolean matchesSnapshot(String snapshot, BlockData live) {
        try {
            return Bukkit.createBlockData(snapshot).matches(live);
        } catch (IllegalArgumentException e) {
            // Material removed or renamed by a version change — treat as changed, never as missing.
            return false;
        }
    }
}
